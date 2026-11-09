/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 礼物注解（持久化投影）的稳定性。
 *
 * 与 [VideoGiftProtocolTest] 分开：那边是**纯逻辑**（可以脱离 ai 模块在 JVM 里真跑），
 * 这里需要 `UIMessageAnnotation`，只能在 app 模块的 Gradle 单测里执行。
 */
class VideoGiftAnnotationTest {
    @Test
    fun `gift annotation is stable and replay safe`() {
        val annotation = videoGiftAnnotation(VideoGift.METEOR_SHOWER, "conv", "turn-1", 1_000L)
        assertEquals("turn-1:meteor", annotation.id)
        assertEquals("meteor", annotation.giftId)
        assertEquals("流星雨", annotation.giftName)
        assertEquals("conv", annotation.conversationId)
        assertEquals("🌠 流星雨", annotation.caption())
    }
}
