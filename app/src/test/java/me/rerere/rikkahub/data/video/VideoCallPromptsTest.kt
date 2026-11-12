/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCallPromptsTest {
    @Test
    fun `gifts are off by default`() {
        assertFalse(VideoCallSetting().giftsEnabled)
        assertFalse(VideoCallSetting().giftsAvailable)
        assertFalse(VideoCallContext(cameraAvailable = true).giftsEnabled)
    }

    @Test
    fun `disabled gifts are stated explicitly and the tag is never offered`() {
        val text = VideoCallPrompts.turnContext(VideoCallContext(cameraAvailable = true, pictureAttached = true))
        assertTrue(text.contains(VideoCallPrompts.GIFT_DISABLED_NOTE))
        assertFalse(text.contains(VideoCallPrompts.GIFT_INSTRUCTION))
    }

    @Test
    fun `enabled gifts inject the reviewable instruction with no real money promise`() {
        val text = VideoCallPrompts.turnContext(
            VideoCallContext(cameraAvailable = true, giftsEnabled = true),
        )
        assertTrue(text.contains(VideoCallPrompts.GIFT_INSTRUCTION))
        assertTrue(text.contains("没有真实金额"))
        assertTrue(text.contains("每条回复最多送一件"))
        assertTrue(text.contains("不需要付费"))
        assertFalse(text.contains("余额"))
        // 送礼提示词必须是可人工审查的原文
        assertTrue(VideoCallPrompts.GIFT_INSTRUCTION.contains("galaxy_train"))
        assertTrue(VideoCallPrompts.GIFT_INSTRUCTION.contains("不要催促"))
        // 允许轻松的虚拟互动条件，但仍然禁止真实支付/真实价值
        assertTrue(VideoCallPrompts.GIFT_INSTRUCTION.contains("轻松的虚拟互动"))
        assertTrue(VideoCallPrompts.GIFT_INSTRUCTION.contains("严禁把礼物和真实金钱"))
    }

    @Test
    fun `camera unavailable is stated with the real reason`() {
        val text = VideoCallPrompts.turnContext(
            VideoCallContext(cameraAvailable = false, cameraUnavailableReason = "没有相机权限"),
        )
        assertTrue(text.contains("没有相机权限"))
        assertTrue(text.contains("不要声称看见了对方"))
        // 没有画面时不教模型留影
        assertFalse(text.contains("<ws_vsnap/>"))
    }

    @Test
    fun `vision unsupported is stated separately from camera availability`() {
        val text = VideoCallPrompts.turnContext(
            VideoCallContext(cameraAvailable = true, visionSupported = false),
        )
        assertTrue(text.contains(VideoCallPrompts.VISION_UNAVAILABLE_NOTE))
        assertFalse(text.contains("<ws_vsnap/>"))
        assertFalse(VideoCallContext(cameraAvailable = true, visionSupported = false, pictureAttached = true).canSeeUser)
        assertTrue(VideoCallContext(cameraAvailable = true, pictureAttached = true).canSeeUser)
    }

    @Test
    fun `camera on but no picture this turn never claims sight`() {
        // 相机可用、模型能识图，但本轮抓帧失败/没到点：绝不能声称看见了
        val context = VideoCallContext(cameraAvailable = true, visionSupported = true, pictureAttached = false)
        assertFalse(context.canSeeUser)
        val text = VideoCallPrompts.turnContext(context)
        assertTrue(text.contains(VideoCallPrompts.NO_PICTURE_THIS_TURN_NOTE))
        assertFalse(text.contains("本轮附带了当前相机画面"))
        // "本轮没有图"不等于"留影不可用"：相机在工作，模型仍然可以主动请求留影。
        assertTrue(text.contains("<ws_vsnap/>"))
    }

    @Test
    fun `snapshot stays available in off mode while sight is still denied`() {
        val context = VideoCallContext(
            cameraAvailable = true,
            visionSupported = true,
            pictureAttached = false,
            captureMode = VideoFrameCaptureMode.OFF,
        )
        val text = VideoCallPrompts.turnContext(context)
        // OFF：不会自动附带画面，但可以主动留影
        assertTrue(VideoCallPrompts.captureModeNote(VideoFrameCaptureMode.OFF)!!.let(text::contains))
        assertTrue(text.contains("<ws_vsnap/>"))
        // 仍然不许声称已经看见了
        assertTrue(text.contains(VideoCallPrompts.NO_PICTURE_THIS_TURN_NOTE))
        assertFalse(context.canSeeUser)
    }

    @Test
    fun `attached picture enables the capture-mode note and snapshot protocol`() {
        val context = VideoCallContext(
            cameraAvailable = true,
            visionSupported = true,
            pictureAttached = true,
            captureMode = VideoFrameCaptureMode.EVERY_REPLY,
        )
        assertTrue(context.canSeeUser)
        val text = VideoCallPrompts.turnContext(context)
        assertTrue(text.contains("<ws_vsnap/>"))
        assertTrue(text.contains("本轮附带了当前相机画面"))
    }

    @Test
    fun `gifts already sent are summarized to avoid farming`() {
        val text = VideoCallPrompts.turnContext(
            VideoCallContext(
                cameraAvailable = true,
                giftsEnabled = true,
                giftsSent = VideoGiftTally.of(listOf(VideoGift.HEART, VideoGift.HEART)),
            ),
        )
        assertTrue(text.contains("你已经送出：小心心 ×2"))
    }

    @Test
    fun `capture mode notes describe what is actually attached`() {
        assertNotNull(VideoCallPrompts.captureModeNote(VideoFrameCaptureMode.EVERY_REPLY))
        val continuous = VideoCallPrompts.captureModeNote(VideoFrameCaptureMode.CONTINUOUS_SEQUENCE)!!
        assertTrue(continuous.contains("连续画面"))
        assertTrue(continuous.contains("每秒"))
        assertTrue(VideoCallPrompts.captureModeNote(VideoFrameCaptureMode.OFF)!!.contains("不会自动附带画面"))
        assertTrue(VideoCallPrompts.captureModeNote(VideoFrameCaptureMode.EVERY_30S)!!.contains("30"))
        assertTrue(VideoCallPrompts.captureModeNote(VideoFrameCaptureMode.EVERY_60S)!!.contains("60"))
        // 30/60 秒必须明确说明"不持续采样"，不能让模型以为它们也在每秒抓帧。
        assertTrue(VideoCallPrompts.captureModeNote(VideoFrameCaptureMode.EVERY_30S)!!.contains("不会持续采样"))
        assertTrue(VideoCallPrompts.captureModeNote(VideoFrameCaptureMode.EVERY_60S)!!.contains("不会持续采样"))
    }

    @Test
    fun `gift bubble text stays a plain virtual label`() {
        assertEquals("送出 💗 小心心", VideoCallPrompts.giftBubbleText(VideoGift.HEART))
    }
}
