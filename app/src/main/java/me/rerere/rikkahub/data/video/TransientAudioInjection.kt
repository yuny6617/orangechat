/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 把"本轮那一段录音"作为**仅本次请求**的音频输入挂到触发这一轮的用户消息上。
 *
 * 与 `GenerationHandler.withTransientRequestImages` 同一套约定：
 *
 * - 只改发出去的 `internalMessages`，**不写聊天历史**（历史里只有占位文字），
 *   所以下一轮、以及重启之后的历史里都不会再出现这段音频的 base64；
 * - 锚点是提交时分配的**队列项 ID**（也就是那条用户消息的 ID），而不是"请求里最后一条 USER"：
 *   工具多步会插入新消息，最后一条 USER 可能已经不属于这一轮；
 * - 必须提供存在且角色为 USER 的锚点；无效时终止请求，绝不猜测最后一条 USER；
 * - 同一个请求内部的工具多步**复用**同一份注入（注入发生在每次请求构造时，
 *   文件在请求结束前一直存在），请求一结束文件就被 [TransientAudioClip] 释放；
 * - 幂等：锚点上已经有同一段音频时不会再加一遍（重建同一个请求也不会出现两份 base64）。
 */
fun List<UIMessage>.withTransientRequestAudio(
    audioUrl: String?,
    anchorMessageId: String? = null,
): List<UIMessage> {
    val url = audioUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return this
    val anchor = requireNotNull(anchorMessageId?.trim()?.takeIf { it.isNotEmpty() }) {
        "本轮录音缺少用户消息锚点，已停止发送"
    }
    val index = indexOfLast { it.id.toString() == anchor }
    check(index >= 0) { "本轮录音的用户消息已不在请求中，已停止发送" }
    check(this[index].role == MessageRole.USER) { "本轮录音锚点不是用户消息，已停止发送" }
    val existing = this[index].parts
        .filterIsInstance<UIMessagePart.Audio>()
        .any { it.url == url }
    if (existing) return this
    return mapIndexed { position, message ->
        if (position == index) {
            message.copy(parts = message.parts + UIMessagePart.Audio(url))
        } else {
            message
        }
    }
}
