/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.video.VideoCallRuntime
import me.rerere.rikkahub.data.video.VideoGiftProtocol
import me.rerere.rikkahub.data.video.videoGiftAnnotation

/**
 * 视频通话指令（`<ws_gift id="..."/>` / `<ws_vsnap/>`）的纯处理逻辑。
 *
 * 三个阶段（[VideoDirectiveStage]、[plan]）各自的责任：
 * - **Streaming**：只把模型原始正文记录/冻结成 [VideoDirectiveIntent]，**原样返回 messages**。
 *   这是唯一能读到原始流的阶段：解析必须基于原文，"改正文"不是它的职责。
 *   它的输出还会被上层继续跑 `visualTransforms`/`onGenerationFinish` 并持久化，
 *   在流式阶段剥离会让当前状态变成被删过的正文（跨分片标签一旦被当成合并基准就会永久拆散）。
 * - **Display**：剥离协议（末尾半截也先藏起来），只影响显示。
 * - **Finish**：剥离协议（这里没有半截了）并执行一次冻结结果。
 *
 * 执行还会再查一遍作用域：当前通话 + 这通通话自己生成、随请求传下来的 `requestToken`
 * （不再是"最后一条用户消息"这种可以复用的伪身份）。
 */
internal fun applyVideoCallDirectives(
    messages: List<UIMessage>,
    stage: VideoDirectiveStage,
    giftsEnabled: Boolean,
    conversationId: String?,
    requestToken: String?,
    session: VideoCallDirectiveSession?,
    nowMillis: Long = System.currentTimeMillis(),
): List<UIMessage> {
    val plan = stage.plan()
    // 只看"正在生成的这一条"：历史回复永远不会被补发礼物，也不会被重写。
    val index = messages.indexOfLast { it.role == MessageRole.ASSISTANT && it.finishedAt == null }
    if (index < 0) return messages
    val target = messages[index]
    val messageId = target.id.toString()

    if (plan.parse) {
        // 只在这里读**模型原始输出**并冻结解析结果（这是管线中唯一能读到原始流的阶段）。
        val rawBody = target.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        session?.recordStreaming(messageId, rawBody)
    }

    var message = target
    if (plan.strip) {
        val strippedParts = target.parts.map { part ->
            if (part !is UIMessagePart.Text) {
                part
            } else {
                val cleaned = VideoGiftProtocol.strip(part.text, streaming = plan.bufferPartialTags)
                if (cleaned == part.text) part else part.copy(text = cleaned)
            }
        }
        if (strippedParts != target.parts) message = target.copy(parts = strippedParts)
    }

    // Finish：只消费解析结果，不读正文。
    if (plan.execute && session != null) {
        val intent = session.intentOf(messageId)
        if (intent != null && intent.hasAny) {
            if (giftsEnabled && intent.gift != null) {
                val alreadyAnnotated = message.annotations.any { it is UIMessageAnnotation.VideoCallGift }
                if (!alreadyAnnotated) {
                    val gift = VideoCallRuntime.grantGift(
                        conversationId = conversationId,
                        requestToken = requestToken,
                        messageId = messageId,
                        gift = intent.gift,
                        atMillis = nowMillis,
                    )
                    if (gift != null) {
                        message = message.copy(
                            annotations = message.annotations + videoGiftAnnotation(
                                gift = gift,
                                conversationId = conversationId.orEmpty(),
                                turnMessageId = messageId,
                                createdAtMillis = nowMillis,
                            ),
                        )
                    }
                }
            }
            if (intent.snapshotRequested) {
                VideoCallRuntime.requestSnapshot(
                    conversationId = conversationId,
                    requestToken = requestToken,
                    messageId = messageId,
                    atMillis = nowMillis,
                )
            }
        }
    }

    if (message === target) return messages
    return messages.mapIndexed { position, element -> if (position == index) message else element }
}
