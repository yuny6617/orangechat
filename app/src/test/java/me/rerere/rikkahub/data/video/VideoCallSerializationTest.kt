/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCallSerializationTest {
    @Test
    fun `video call setting defaults keep gifts off`() {
        val decoded = JsonInstant.decodeFromString<VideoCallSetting>("{}")
        assertFalse(decoded.giftsEnabled)
        assertEquals(VideoFrameCaptureMode.EVERY_REPLY, decoded.frameCaptureMode)
        assertEquals(VideoFrameQuality.P512, decoded.frameQuality)
        assertTrue(decoded.showUserDanmaku)
    }

    @Test
    fun `video call setting round-trips enum serial names`() {
        val setting = VideoCallSetting(
            giftsEnabled = true,
            frameCaptureMode = VideoFrameCaptureMode.EVERY_60S,
            frameQuality = VideoFrameQuality.P768,
            showUserDanmaku = false,
        )
        val encoded = JsonInstant.encodeToString(setting)
        assertTrue(encoded.contains("every_60s"))
        assertTrue(encoded.contains("p768"))
        assertEquals(setting, JsonInstant.decodeFromString<VideoCallSetting>(encoded))
        // 旧版本没有这个字段时也必须能以默认值读回
        val legacy = JsonInstant.decodeFromString<VideoCallSetting>("""{"giftsEnabled":true}""")
        assertTrue(legacy.giftsEnabled)
        assertEquals(VideoFrameCaptureMode.EVERY_REPLY, legacy.frameCaptureMode)
    }

    @Test
    fun `preview calibration defaults to automatic and preserves explicit angles`() {
        assertEquals(null, VideoCallSetting().previewCalibrationDegrees)
        // 老版本缺字段或显式 null 都表示自动，不向未知设备强加90°。
        val legacy = JsonInstant.decodeFromString<VideoCallSetting>("""{"giftsEnabled":true}""")
        assertEquals(null, legacy.previewCalibrationDegrees)
        val automatic = JsonInstant.decodeFromString<VideoCallSetting>("""{"previewCalibrationDegrees":null}""")
        assertEquals(null, automatic.previewCalibrationDegrees)
        assertEquals(
            null,
            JsonInstant.decodeFromString<VideoCallSetting>(JsonInstant.encodeToString(automatic))
                .previewCalibrationDegrees,
        )
        // 显式0（复位）和所有已保存角度必须保留，不能被设备默认覆盖。
        listOf(0, 90, 180, 270).forEach { angle ->
            val encoded = JsonInstant.encodeToString(VideoCallSetting(previewCalibrationDegrees = angle))
            assertTrue(encoded.contains("\"previewCalibrationDegrees\":$angle"))
            assertEquals(
                angle,
                JsonInstant.decodeFromString<VideoCallSetting>(encoded).previewCalibrationDegrees,
            )
        }
    }

    @Test
    fun `legacy chat interaction json without gift summary still decodes`() {
        val legacy = """
            {"type":"chat_interaction","id":"i1","conversationId":"c1","assistantId":"a1",
             "actor":"user","kind":"call","status":"ended"}
        """.trimIndent()
        val decoded = JsonInstant.decodeFromString<me.rerere.ai.ui.UIMessageAnnotation>(legacy)
        val card = decoded as me.rerere.ai.ui.UIMessageAnnotation.ChatInteraction
        assertEquals("", card.giftSummary)
        assertEquals("ended", card.status)
        // 老数据一定**没有**视频关联：默认空 → 显示层不猜、不隐藏。
        assertFalse(card.videoCall)
        assertTrue(card.videoCallMessageIds.isEmpty())
    }

    @Test
    fun `video call card keeps its message links across reload`() {
        val userId = kotlin.uuid.Uuid.random()
        val replyId = kotlin.uuid.Uuid.random()
        val card = me.rerere.ai.ui.UIMessageAnnotation.ChatInteraction(
            id = "i2",
            conversationId = "c1",
            assistantId = "a1",
            actor = "user",
            kind = "call",
            status = "ended",
            callTranscript = listOf(
                me.rerere.ai.ui.CallTranscriptEntry("user", "我", "你好", 1L),
                me.rerere.ai.ui.CallTranscriptEntry("assistant", "对方", "我在", 2L),
            ),
            videoCall = true,
            videoCallMessageIds = listOf(userId.toString(), replyId.toString()),
        )
        val decoded = JsonInstant.decodeFromString<me.rerere.ai.ui.UIMessageAnnotation>(
            JsonInstant.encodeToString<me.rerere.ai.ui.UIMessageAnnotation>(card),
        ) as me.rerere.ai.ui.UIMessageAnnotation.ChatInteraction
        assertTrue(decoded.videoCall)
        assertEquals(listOf(userId.toString(), replyId.toString()), decoded.videoCallMessageIds)
        // 重启后必须同时有真实关联原文和完整卡内文字，不能凭空隐藏不存在的消息。
        val originals = listOf(
            me.rerere.ai.ui.UIMessage.user("你好").copy(id = userId),
            me.rerere.ai.ui.UIMessage.assistant("我在").copy(id = replyId),
        )
        assertEquals(
            setOf(userId, replyId),
            me.rerere.rikkahub.data.interaction.videoCallCoveredMessageIds(
                listOf(me.rerere.ai.ui.UIMessage.user("卡").copy(annotations = listOf(decoded))) + originals,
            ),
        )
    }

    @Test
    fun `gift summary round-trips on the ended call card`() {
        val card = me.rerere.ai.ui.UIMessageAnnotation.ChatInteraction(
            id = "i1",
            conversationId = "c1",
            assistantId = "a1",
            actor = "user",
            kind = "call",
            status = "ended",
            giftSummary = "小心心 ×2 · 花束 ×1",
        )
        val decoded = JsonInstant.decodeFromString<me.rerere.ai.ui.UIMessageAnnotation>(
            JsonInstant.encodeToString<me.rerere.ai.ui.UIMessageAnnotation>(card),
        ) as me.rerere.ai.ui.UIMessageAnnotation.ChatInteraction
        assertEquals("小心心 ×2 · 花束 ×1", decoded.giftSummary)
    }

    @Test
    fun `video gift annotation round-trips inside a message`() {
        val annotation = videoGiftAnnotation(VideoGift.FIREWORKS, "conv", "turn-9", 42L)
        val message = me.rerere.ai.ui.UIMessage.assistant("好玩").copy(annotations = listOf(annotation))
        val decoded = JsonInstant.decodeFromString<me.rerere.ai.ui.UIMessage>(
            JsonInstant.encodeToString(message),
        )
        val gift = decoded.annotations
            .filterIsInstance<me.rerere.ai.ui.UIMessageAnnotation.VideoCallGift>()
            .single()
        assertEquals("turn-9:fireworks", gift.id)
        assertEquals("fireworks", gift.giftId)
        assertEquals("夏日烟火", gift.giftName)
        assertEquals("conv", gift.conversationId)
        assertEquals(42L, gift.createdAtMillis)
        assertEquals("好玩", decoded.toText())
    }
}
