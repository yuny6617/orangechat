/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.interaction

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/*
 * 视频通话卡与"逐条通话消息"之间的**持久关联**。
 *
 * 用户的原始反馈（P4 / P5）：挂断之后聊天页把这次通话的每一条用户/模型文本都当成
 * 独立气泡铺出来，用户只想看到一张通话记录卡。这里解决的就是"哪些消息属于这通视频通话"。
 *
 * 三条硬约束（都写成了可单测的纯函数）：
 * 1. **不许按时间猜**：关联只来自持久字段 [ChatInteraction.videoCallMessageIds]；
 *    字段为空（老数据 / 语音通话 / 写入失败）时一律不隐藏。
 * 2. **不许改上下文**：这里只回答"显示层要吸收哪些 id"，
 *    `Conversation.messagesForGeneration` / `visibleMessageNodes` 一个字节都不动，
 *    所以模型读到的仍是逐字原文，而且只出现一次（卡里的 transcript 不会再注入一份）。
 * 3. **卡删了就必须能看见原文**：关联就存在卡自己的注解上，卡没了 → 集合为空 → 消息自动可见。
 */

/**
 * 一条用户消息在队列里的"回复边界"。
 *
 * 与 [me.rerere.rikkahub.service.queuedReplyText] / `queuedSpeechReplyText` **同一个边界**：
 * 从这条用户消息之后、到**下一条用户消息**之前的所有助手消息都属于这一轮。
 * 工具多步插进来的助手片段因此也归到同一轮，不需要另猜"最新一条助手"。
 *
 * 只返回**真实存在**的消息 id；用户消息本身也在返回值里（它同样是这通电话的一条书写记录）。
 */
internal fun videoCallReplyMessageIds(messages: List<UIMessage>, userMessageId: Uuid): List<Uuid> {
    val index = messages.indexOfFirst { it.id == userMessageId && it.role == MessageRole.USER }
    if (index < 0) return emptyList()
    val replies = messages.drop(index + 1)
        .takeWhile { it.role != MessageRole.USER }
        .filter { it.role == MessageRole.ASSISTANT }
        .map { it.id }
    return listOf(userMessageId) + replies
}

/**
 * 一通视频通话要写进卡里的全部消息 id（稳定顺序、去重、已排除不存在的消息）。
 *
 * [userMessageIds] 是服务侧在这通电话里真实提交过的用户消息 id
 * （队列项 id 就是落库的用户消息 id），**不是**时间段推断。
 */
internal fun videoCallLinkedMessageIds(
    messages: List<UIMessage>,
    userMessageIds: Collection<Uuid>,
): List<String> {
    val linked = LinkedHashSet<String>()
    userMessageIds.forEach { id ->
        videoCallReplyMessageIds(messages, id).forEach { linked += it.toString() }
    }
    return linked.toList()
}

/**
 * 显示层应该被**通话卡吸收**的消息 id 集合。
 *
 * 只有同时满足下面全部条件的卡才生效：
 * - `kind == "call"` 且 `videoCall == true`（红包/转账卡与纯语音通话永远不参与）；
 * - 卡上写着 [ChatInteraction.videoCallMessageIds]（默认空 → 老数据不隐藏）；
 * - 卡上有**可展示的 transcript**：卡内必须能看到同样的完整文字，
 *   否则宁可继续铺逐条消息，也不能把文字藏到用户再也打不开的地方。
 *
 * 卡被删除 / 分支被切走时，这条注解就不在 [messages] 里了，集合自然为空 —— 原文自动恢复可见。
 */
internal fun videoCallCoveredMessageIds(messages: List<UIMessage>): Set<Uuid> {
    val covered = LinkedHashSet<Uuid>()
    messages.flatMap { it.annotations }.filterIsInstance<ChatInteraction>()
        .filter { it.kind == "call" && it.videoCall && it.videoCallMessageIds.isNotEmpty() && !it.callTranscript.isNullOrEmpty() }
        .forEach { card ->
            // The history route uses these same exact-ID rows, including late streaming revisions.
            val accessible = card.transcriptFrom(messages, "我", "对方")
            val persisted = card.callTranscript.orEmpty()
            card.videoCallMessageIds.forEach messageLoop@{ raw ->
                val message = messages.firstOrNull { it.id.toString() == raw } ?: return@messageLoop
                if (!message.absorbedIntoVideoCallCard()) return@messageLoop
                val row = message.videoCallTranscriptEntry("我", "对方") ?: return@messageLoop
                // Fail closed for empty/incomplete snapshots. Matching role + FULL text is required;
                // a prefix, aggregate partial or another role's identical sentence is not proof.
                if (persisted.any { it.role == row.role && it.text == row.text } &&
                    accessible.any { it.role == row.role && it.text == row.text }) covered += message.id
            }
        }
    return covered
}

/**
 * 这条消息是不是"能被通话卡吸收的纯文字"。
 *
 * 只吸收纯文字：带图片/音频/视频/文档/工具步骤的消息继续显示，
 * 这样手动夹住的留影、`<ws_vsnap/>` 存下来的照片、礼物卡和工具过程都不会被藏进一张只有文字的卡里
 * （brief 明确要求：藏了就不能让用户再找不到他特意留影的媒体）。
 */
internal fun UIMessage.absorbedIntoVideoCallCard(): Boolean {
    if (parts.isEmpty()) return false
    if (parts.any { it !is UIMessagePart.Text }) return false
    // 卡自己的消息（以及头像互动等带注解的消息）永远不吸收。
    if (annotations.any { it is ChatInteraction }) return false
    return true
}

/**
 * 显示层的最后一步：把被通话卡吸收的**纯文字**消息从列表里去掉。
 *
 * 这里是"只留 P4 通话卡、不铺 P5 逐条气泡"的实际落点，被 `ChatList` 直接调用；
 * 它只碰要显示的这个节点列表，`Conversation.messagesForGeneration()` 完全不受影响。
 */
internal fun List<me.rerere.rikkahub.data.model.MessageNode>.withoutVideoCallCardMessages(
    covered: Set<Uuid>,
): List<me.rerere.rikkahub.data.model.MessageNode> =
    if (covered.isEmpty()) this
    else filterNot { node ->
        val message = node.currentMessage
        message.id in covered && message.absorbedIntoVideoCallCard()
    }

/**
 * 把一批消息 id 并进通话卡（幂等、保持顺序、有上限）。
 *
 * 上限只是防止异常数据把单条注解撑爆；正常一通电话远小于这个数量。
 */
internal fun ChatInteraction.withVideoCallMessages(ids: Collection<String>): ChatInteraction {
    if (ids.isEmpty()) return this
    val merged = LinkedHashSet(videoCallMessageIds)
    merged += ids
    val bounded = if (merged.size <= MAX_VIDEO_CALL_MESSAGE_IDS) merged.toList()
    else merged.toList().takeLast(MAX_VIDEO_CALL_MESSAGE_IDS)
    return copy(videoCallMessageIds = bounded)
}

/** 从别的通话卡上摘掉同一批 id：同一条消息只属于一通电话。 */
internal fun ChatInteraction.withoutVideoCallMessages(ids: Set<String>): ChatInteraction {
    if (ids.isEmpty() || videoCallMessageIds.none { it in ids }) return this
    return copy(videoCallMessageIds = videoCallMessageIds.filterNot { it in ids })
}

/** 单张卡最多记住多少条消息 id。 */
internal const val MAX_VIDEO_CALL_MESSAGE_IDS: Int = 800
