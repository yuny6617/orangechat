/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import me.rerere.ai.ui.UIMessageAnnotation

/*
 * 礼物注解的**持久化投影**。
 *
 * 单独成文件的原因：`UIMessageAnnotation` 属于 ai 模块，而 [VideoGift]、[VideoGiftProtocol]、
 * [VideoGiftLedger]、[VideoGiftTally] 都是纯逻辑。拆开之后纯逻辑部分可以真正被 JVM 单测
 * 编译执行（见 RUN_CORE_TESTS.ps1），不需要为了测协议解析而去 stub 一个消息类型。
 */

/**
 * 把一次生效的礼物变成可持久化的聊天礼物卡注解。
 *
 * [displayName] 是**写入当时**的展示名快照（来自用户当时的设置）。
 * 之后用户再改名字，这条历史记录也不会跟着变 —— 打印在卡上的还是当时那一次的名字。
 */
fun videoGiftAnnotation(
    gift: VideoGift,
    conversationId: String,
    turnMessageId: String,
    createdAtMillis: Long,
    displayName: String = gift.displayName,
): UIMessageAnnotation.VideoCallGift = UIMessageAnnotation.VideoCallGift(
    id = "$turnMessageId:${gift.id}",
    giftId = gift.id,
    giftName = displayName.ifBlank { gift.displayName },
    conversationId = conversationId,
    turnMessageId = turnMessageId,
    createdAtMillis = createdAtMillis,
)

/**
 * 聊天礼物卡/气泡上的说明文字。
 *
 * 优先用注解里记录的快照名（[UIMessageAnnotation.VideoCallGift.giftName]）：
 * 老数据没有名字时退回内置名，历史记录因此既不会显示空白，也不会被后来的改名覆盖。
 */
fun UIMessageAnnotation.VideoCallGift.caption(): String {
    val gift = VideoGiftCatalog.byId(giftId)
    val label = giftName.ifBlank { gift?.displayName ?: "视频礼物" }
    return gift?.let { "${it.emoji} $label" } ?: label
}
