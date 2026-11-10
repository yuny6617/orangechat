package me.rerere.rikkahub.data.interaction

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.CallTranscriptEntry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Instant

class CallHistoryTest {
    private val card = ChatInteraction("id", "conv", "a", "assistant", "call",
        status = "ended", connectedAtMillis = 1000, endedAtMillis = 4000)
    private fun at(message: UIMessage, time: Long) = message.copy(createdAt =
        Instant.fromEpochMilliseconds(time).toLocalDateTime(TimeZone.currentSystemDefault()))

    @Test fun routesPendingActiveAndEndedCardsWithoutRestartingHistoricalCalls() {
        assertEquals(CallCardDestination.Incoming, card.copy(status = "pending").callDestination(null, 0))
        assertEquals(CallCardDestination.Active, card.copy(status = "accepted").callDestination("conv", 1000))
        assertEquals(CallCardDestination.Active, card.copy(status = "connecting").callDestination("conv", 0))
        assertEquals(CallCardDestination.History, card.callDestination("conv", 1000))
        assertEquals(CallCardDestination.History, card.copy(status = "accepted").callDestination(null, 0))
        assertEquals(CallCardDestination.History, card.copy(status = "accepted").callDestination("other", 1000))
        assertEquals(CallCardDestination.History, card.copy(status = "declined").callDestination(null, 0))
        assertEquals(CallCardDestination.History, card.copy(status = "accepted").callDestination("conv", 5000))
        assertEquals(CallCardDestination.History, card.copy(status = "connecting").callDestination("conv", 5000))
    }

    @Test fun delayedHangupOnlyFinishesItsOwnConnectedOrInitializingCall() {
        val connected = card.copy(status = "accepted")
        assertTrue(connected.belongsToEndingCall(1000, emptySet()))
        assertFalse(connected.copy(connectedAtMillis = 5000).belongsToEndingCall(1000, emptySet()))
        val initializing = card.copy(status = "connecting", connectedAtMillis = null)
        assertTrue(initializing.belongsToEndingCall(0, setOf("id")))
        assertFalse(initializing.copy(id = "next-call").belongsToEndingCall(0, setOf("id")))
        assertFalse(card.belongsToEndingCall(1000, setOf("id")))
    }

    @Test fun legacyRecoveryExcludesOtherCallsToolsAndInteractionCards() {
        val messages = listOf(at(UIMessage.user("之前"), 900), at(UIMessage.user("你好"), 1100),
            at(UIMessage.assistant("我在听"), 1500),
            at(UIMessage.assistant("内部卡片").copy(annotations = listOf(card)), 2200),
            at(UIMessage.assistant("之后"), 4100))
        val result = card.transcriptFrom(messages, "严槿", "南枳")
        assertEquals(listOf("你好", "我在听"), result.map { it.text })
        assertEquals(listOf("严槿", "南枳"), result.map { it.speaker })
        assertEquals(listOf(1100L, 1500L), result.map { it.timestampMillis })
        assertTrue(card.copy(connectedAtMillis = null).transcriptFrom(messages, "我", "你").isEmpty())
    }

    @Test fun videoLinkedRecoveryKeepsFullLongTextAndFallsBackOnlyWhenOriginalsAreGone() {
        val words = "完整原文不能裁切。".repeat(3000)
        val user = at(UIMessage.user("问题"), 1100)
        val assistant = at(UIMessage.assistant("[[voice emotion=happy]]$words[[/voice]]"), 1500)
        val video = card.copy(videoCall = true,
            videoCallMessageIds = listOf(user.id.toString(), assistant.id.toString()),
            callTranscript = emptyList())
        val rows = video.linkedVideoCallTranscript(listOf(user, assistant), "我", "对方")
        assertEquals(listOf("问题", words), rows.map { it.text })
        val persisted = video.copy(callTranscript = rows)
        assertEquals(rows, persisted.transcriptFrom(emptyList(), "我", "对方"))
        assertEquals(rows, persisted.transcriptFrom(listOf(user), "我", "对方"))
        val protocol = assistant.copy(parts = listOf(me.rerere.ai.ui.UIMessagePart.Text(
            "[[voice emotion=happy]]你好[[/voice]]<ws_gift id=\"heart\"/>再见",
        )))
        val cleaned = video.linkedVideoCallTranscript(listOf(protocol), "我", "对方").single().text
        assertTrue(cleaned.contains("你好") && cleaned.contains("再见"))
        assertFalse(cleaned.contains("ws_gift") || cleaned.contains("[[voice"))
        val revised = assistant.copy(parts = listOf(me.rerere.ai.ui.UIMessagePart.Text("最终保存全文")))
        assertEquals(listOf("问题", "最终保存全文"),
            persisted.transcriptFrom(listOf(user, revised), "我", "对方").map { it.text })
    }

    @Test fun dedicatedTranscriptIsAuthoritativeAndSurvivesBackupSerialization() {
        val row = CallTranscriptEntry("user", "严槿", "本次通话", 1200)
        val original: UIMessageAnnotation = card.copy(callTranscript = listOf(row))
        val restored = Json.decodeFromString<UIMessageAnnotation>(Json.encodeToString(original)) as ChatInteraction
        assertEquals(listOf(row), restored.callTranscript)
        assertEquals(listOf(row), restored.transcriptFrom(listOf(at(UIMessage.user("别的消息"), 1300)), "我", "你"))
        assertTrue(card.copy(callTranscript = emptyList()).transcriptFrom(listOf(at(UIMessage.user("别的消息"), 1300)), "我", "你").isEmpty())
    }
}
