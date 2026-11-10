package me.rerere.rikkahub.data.interaction

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.CallTranscriptEntry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.tts.model.SpeechMarkup
import me.rerere.rikkahub.data.video.VideoGiftProtocol

internal enum class CallCardDestination { Incoming, Active, History }

/** Only a matching, explicitly supplied conversation can prove detail accessibility. */
internal fun ChatInteraction.canAccessCallHistory(conversationId: kotlin.uuid.Uuid?): Boolean =
    conversationId != null && this.conversationId == conversationId.toString()

internal fun ChatInteraction.callDestination(activeConversationId: String?, activeStartedAt: Long): CallCardDestination = when {
    status == "pending" && actor == "assistant" -> CallCardDestination.Incoming
    conversationId == activeConversationId &&
        ((status == "connecting" && activeStartedAt == 0L) ||
            (status == "accepted" && activeStartedAt > 0 && connectedAtMillis == activeStartedAt)) -> CallCardDestination.Active
    else -> CallCardDestination.History
}

/** An asynchronous hang-up must never finish a later call in the same conversation. */
internal fun ChatInteraction.belongsToEndingCall(startedAt: Long?, connectingIds: Set<String>): Boolean =
    status in setOf("connecting", "accepted") && when {
        startedAt == null -> true
        startedAt > 0 -> connectedAtMillis == startedAt
        else -> status == "connecting" && id in connectingIds
    }

/** Full, unbounded display text shared by persistence, recovery and coverage verification. */
internal fun UIMessage.videoCallTranscriptEntry(userName: String, assistantName: String): CallTranscriptEntry? {
    if (role !in setOf(MessageRole.USER, MessageRole.ASSISTANT) ||
        annotations.any { it is ChatInteraction }) return null
    val words = parts.mapNotNull {
        when (it) {
            is UIMessagePart.Text -> SpeechMarkup.transcript(VideoGiftProtocol.strip(it.text))
            is UIMessagePart.VoiceMessage -> it.transcript
            else -> null
        }
    }.joinToString("\n").trim()
    if (words.isBlank()) return null
    return CallTranscriptEntry(
        if (role == MessageRole.USER) "user" else "assistant",
        if (role == MessageRole.USER) userName else assistantName,
        words,
        createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds(),
    )
}

/** Exact linked IDs only: never infer a video call from timestamps. No text/row truncation. */
internal fun ChatInteraction.linkedVideoCallTranscript(
    messages: List<UIMessage>, userName: String, assistantName: String,
): List<CallTranscriptEntry> {
    val byId = messages.associateBy { it.id.toString() }
    return resolvedVideoCallMessageIds(messages).mapNotNull { byId[it]?.videoCallTranscriptEntry(userName, assistantName) }
}

/** Video calls recover late revisions from exact IDs; ordinary voice keeps its legacy behavior. */
internal fun ChatInteraction.transcriptFrom(messages: List<UIMessage>, userName: String, assistantName: String): List<CallTranscriptEntry> {
    if (videoCall && videoCallMessageIds.isNotEmpty()) {
        val recovered = linkedVideoCallTranscript(messages, userName, assistantName)
        if (recovered.isEmpty()) return callTranscript.orEmpty()
        val existingIds = messages.mapTo(hashSetOf()) { it.id.toString() }
        if (videoCallMessageIds.all { it in existingIds }) return recovered
        // Preserve rows for removed originals while replacing revisions of still-present messages.
        val currentKeys = recovered.mapTo(hashSetOf()) { it.role to it.timestampMillis }
        val missingRows = callTranscript.orEmpty().filter { (it.role to it.timestampMillis) !in currentKeys }
        return (recovered + missingRows).sortedBy { it.timestampMillis }
    }
    callTranscript?.let { return it }
    val start = connectedAtMillis ?: return emptyList()
    val end = endedAtMillis ?: return emptyList()
    return messages.mapNotNull { message ->
        val time = message.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        if (time !in start..end || message.role !in setOf(MessageRole.USER, MessageRole.ASSISTANT) ||
            message.annotations.any { it is ChatInteraction }) return@mapNotNull null
        val words = message.parts.mapNotNull {
            when (it) {
                is UIMessagePart.Text -> SpeechMarkup.transcript(it.text)
                is UIMessagePart.VoiceMessage -> it.transcript
                else -> null
            }
        }.joinToString("\n").trim()
        if (words.isBlank()) null else CallTranscriptEntry(
            if (message.role == MessageRole.USER) "user" else "assistant",
            if (message.role == MessageRole.USER) userName else assistantName, words, time)
    }
}
