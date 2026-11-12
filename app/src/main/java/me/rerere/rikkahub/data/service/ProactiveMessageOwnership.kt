/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.buildConversationRequestMessages
import me.rerere.rikkahub.data.video.withoutHistoricalCameraImages
import kotlin.uuid.Uuid

/**
 * Compose the initial proactive request from durable history.
 *
 * A background wake-up has no camera capability, so historical video-call frames/clips/snapshots
 * must never be replayed here. Clean before transformers (so nothing can encode a camera file into
 * anonymous base64 or OCR text) and again after the request messages are assembled; ordinary
 * attachments are deliberately kept by [withoutHistoricalCameraImages].
 */
internal fun buildProactiveRequestMessages(
    systemPrompt: String,
    historyMessages: List<UIMessage>,
    summary: String?,
    context: String,
): List<UIMessage> = beginProactiveRequest(
    buildConversationRequestMessages(
        systemPrompt = systemPrompt,
        messages = historyMessages.withoutHistoricalCameraImages(),
        contextMessageSize = 0,
        summary = summary,
    ).withoutHistoricalCameraImages(),
    context = context,
)

/** A new background decision is a request-only user turn, never a persisted chat message. */
internal fun beginProactiveRequest(messages: List<UIMessage>, context: String = ""): List<UIMessage> =
    messages + UIMessage.user(
        "<proactive_trigger>这是应用发起的一次后台判断，不是对方的新发言。" +
            "请依据以上聊天与本次背景信息，决定发送、等待或停止，不要重新回答历史提问。" +
            "</proactive_trigger>" + context.takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty(),
    )

/** A tool result closes the wire turn as user, unless visible assistant content follows it. */
internal fun prepareProactiveToolContinuation(messages: List<UIMessage>): List<UIMessage> {
    val last = messages.lastOrNull() ?: return messages
    if (last.role != MessageRole.ASSISTANT) return messages
    if (last.getTools().any { !it.isExecuted }) return messages
    val lastWirePart = last.parts.lastOrNull {
        it is UIMessagePart.Tool || it is UIMessagePart.Text ||
            it is UIMessagePart.Reasoning || it is UIMessagePart.Image
    }
    if (lastWirePart is UIMessagePart.Tool && lastWirePart.isExecuted) return messages
    return messages + UIMessage.user(
        "请接着上面的工具结果完成本次后台判断；已执行的行动不要重复执行。",
    )
}

/**
 * Start a new assistant turn without letting [UIMessage.handleMessageChunk] reuse the id of an
 * existing assistant message when the request history itself ends with ASSISTANT.
 *
 * The placeholder is local streaming state only; callers must send [messages] (without the
 * placeholder) to the provider.
 */
internal fun beginProactiveAssistantTurn(
    messages: List<UIMessage>,
    modelId: Uuid?,
): List<UIMessage> = messages + UIMessage(
    role = MessageRole.ASSISTANT,
    parts = emptyList(),
    modelId = modelId,
)

/** Never grant this run ownership of a message that existed before the run started. */
internal fun registerProactiveRunMessageId(
    messageId: Uuid,
    protectedMessageIds: Set<Uuid>,
    runMessageIds: MutableSet<Uuid>,
): Boolean {
    if (messageId in protectedMessageIds) return false
    runMessageIds += messageId
    return true
}

/** Cleanup is limited to ids owned by this run, even if a caller accidentally supplies an old id. */
internal fun ownedProactiveCleanupIds(
    requestedIds: Collection<Uuid>,
    protectedMessageIds: Set<Uuid>,
): Set<Uuid> = requestedIds.toSet() - protectedMessageIds
