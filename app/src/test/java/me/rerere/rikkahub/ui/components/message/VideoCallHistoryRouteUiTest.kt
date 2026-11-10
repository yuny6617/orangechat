package me.rerere.rikkahub.ui.components.message

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.CallTranscriptEntry
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.interaction.*
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.pages.voice.CallHistorySource
import me.rerere.rikkahub.ui.pages.voice.LocalCallHistorySource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w420dp-h1100dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoCallHistoryRouteUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun readonlyCardUsesFullSourceAndUpdatesOpenDialogWithoutSnapshotBarrier() {
        val id = Uuid.random()
        val user = UIMessage.user("用户完整原文")
        val initial = UIMessage.assistant("前半").copy(parts = listOf(
            UIMessagePart.Reasoning("不应进入转录的思考"), UIMessagePart.Text("前半")))
        var reply by mutableStateOf(initial)
        var snapshot by mutableStateOf<List<CallTranscriptEntry>?>(listOf(
            CallTranscriptEntry("assistant", "对方", "前半", 1)))
        var showCard by mutableStateOf(true)
        var sourceId by mutableStateOf(id)
        val card = ChatInteraction("route", id.toString(), "a", "user", "call", status = "ended",
            videoCall = true, videoCallMessageIds = listOf(user.id.toString(), initial.id.toString()))
        compose.setContent {
            val currentCard = card.copy(callTranscript = snapshot)
            val originals = listOf(user, reply)
            val all = if (showCard) listOf(UIMessage.user("卡").copy(annotations = listOf(currentCard))) + originals else originals
            val covered = videoCallCoveredMessageIds(all, all, sourceId)
            MaterialTheme {
                CompositionLocalProvider(LocalSettings provides Settings(),
                    LocalCallHistorySource provides CallHistorySource(sourceId, all)) {
                    Column {
                        if (showCard) RichChatCard(currentCard, id, readOnly = true)
                        originals.forEach { original ->
                            val display = if (original.id in covered) original.withoutVideoCallTranscriptText() else original
                            display.parts.filterIsInstance<UIMessagePart.Text>().forEach { Text(it.text) }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText(user.toText()).assertDoesNotExist()
        compose.onNodeWithText("前半").assertDoesNotExist()
        compose.onNodeWithText("✦ 已结束 · 点击查看").performClick()
        compose.onNodeWithTag("call-history").assertExists()
        compose.onNodeWithText(user.toText()).assertIsDisplayed()
        compose.onNodeWithText("前半").assertIsDisplayed()
        val late = "前半，迟到的后半必须一个字也不少。"
        compose.runOnIdle { reply = initial.copy(parts = listOf(UIMessagePart.Text(late))) }
        compose.onNodeWithText(late).assertIsDisplayed()
        compose.onNodeWithText("前半").assertDoesNotExist()
        compose.runOnIdle { snapshot = emptyList() }
        compose.onNodeWithText(late).assertIsDisplayed()
        compose.onNodeWithTag("gift-detail-close").performClick()
        compose.onNodeWithText(late).assertDoesNotExist()
        compose.runOnIdle { sourceId = Uuid.random() }
        compose.onNodeWithText(late).assertIsDisplayed()
        compose.runOnIdle { sourceId = id; showCard = false }
        compose.onNodeWithText(user.toText()).assertIsDisplayed()
        compose.onNodeWithText(late).assertIsDisplayed()
        val generation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(user, reply).map { it.toMessageNode() })
            .messagesForGeneration()
        assertEquals(1, generation.count { it.toText() == late })
        assertEquals(1, generation.count { it.toText() == user.toText() })
    }

    @Test fun previewWithoutSourceOpensSnapshotWithoutKoinAndCannotHideOriginals() {
        val original = UIMessage.assistant("没有可达详情的全文")
        val card = ChatInteraction("preview", Uuid.random().toString(), "a", "user", "call", status = "ended",
            videoCall = true, videoCallMessageIds = listOf(original.id.toString()), callTranscript = emptyList())
        assertEquals(emptySet<Uuid>(), videoCallCoveredMessageIds(listOf(
            UIMessage.user("卡").copy(annotations = listOf(card)), original)))
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalSettings provides Settings()) {
            RichChatCard(card, null, readOnly = true)
        } } }
        compose.onNodeWithText("✦ 已结束 · 点击查看").performClick()
        compose.onNodeWithTag("call-history").assertExists()
    }
}
