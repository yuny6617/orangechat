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

/** 把一次生效的礼物变成可持久化的聊天礼物卡注解。 */
fun videoGiftAnnotation(
    gift: VideoGift,
    conversationId: String,
    turnMessageId: String,
    createdAtMillis: Long,
): UIMessageAnnotation.VideoCallGift = UIMessageAnnotation.VideoCallGift(
    id = "$turnMessageId:${gift.id}",
    giftId = gift.id,
    giftName = gift.displayName,
    conversationId = conversationId,
    turnMessageId = turnMessageId,
    createdAtMillis = createdAtMillis,
)

/** 聊天礼物卡/气泡上的说明文字。 */
fun UIMessageAnnotation.VideoCallGift.caption(): String {
    val gift = VideoGiftCatalog.byId(giftId)
    return if (gift == null) giftName.ifBlank { "视频礼物" } else "${gift.emoji} ${gift.displayName}"
}
