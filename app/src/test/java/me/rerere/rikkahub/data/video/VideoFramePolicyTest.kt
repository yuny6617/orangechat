/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFramePolicyTest {
    @Test
    fun `only every reply captures on send`() {
        assertTrue(VideoFramePolicy.captureOnTurn(VideoFrameCaptureMode.EVERY_REPLY))
        assertFalse(VideoFramePolicy.captureOnTurn(VideoFrameCaptureMode.OFF))
        assertFalse(VideoFramePolicy.captureOnTurn(VideoFrameCaptureMode.EVERY_30S))
        assertFalse(VideoFramePolicy.captureOnTurn(VideoFrameCaptureMode.EVERY_60S))
    }

    @Test
    fun `timer modes expose their interval`() {
        assertNull(VideoFrameCaptureMode.OFF.intervalMillis)
        assertNull(VideoFrameCaptureMode.EVERY_REPLY.intervalMillis)
        assertEquals(30_000L, VideoFrameCaptureMode.EVERY_30S.intervalMillis)
        assertEquals(60_000L, VideoFrameCaptureMode.EVERY_60S.intervalMillis)
        assertTrue(VideoFrameCaptureMode.EVERY_30S.timerDriven)
        assertTrue(VideoFrameCaptureMode.EVERY_60S.timerDriven)
        assertFalse(VideoFrameCaptureMode.EVERY_REPLY.timerDriven)
    }

    @Test
    fun `fit keeps aspect ratio and caps the longest edge`() {
        assertEquals(PixelSize(384, 216), VideoFramePolicy.fit(1920, 1080, 384))
        assertEquals(PixelSize(432, 768), VideoFramePolicy.fit(1080, 1920, 768))
        assertEquals(PixelSize(100, 80), VideoFramePolicy.fit(100, 80, 512))
        assertEquals(PixelSize(1, 1), VideoFramePolicy.fit(0, 0, 512))
    }

    @Test
    fun `quality preset never exceeds its longest edge`() {
        val target = VideoFramePolicy.forQuality(4000, 3000, VideoFrameQuality.P384)
        assertEquals(384, target.width)
        assertEquals(288, target.height)
        assertEquals(384, VideoFrameQuality.P384.maxEdgePx)
        assertEquals(512, VideoFrameQuality.P512.maxEdgePx)
        assertEquals(768, VideoFrameQuality.P768.maxEdgePx)
        assertTrue(VideoFramePolicy.JPEG_QUALITY in 60..95)
    }

    @Test
    fun `scheduler only refreshes after the interval`() {
        var now = 0L
        val scheduler = VideoFrameScheduler { now }
        assertFalse(scheduler.shouldCapture(VideoFrameCaptureMode.EVERY_REPLY))
        assertFalse(scheduler.shouldCapture(VideoFrameCaptureMode.OFF))
        assertTrue(scheduler.shouldCapture(VideoFrameCaptureMode.EVERY_30S))
        scheduler.markCaptured()
        now = 29_000L
        assertFalse(scheduler.shouldCapture(VideoFrameCaptureMode.EVERY_30S))
        now = 30_000L
        assertTrue(scheduler.shouldCapture(VideoFrameCaptureMode.EVERY_30S))
        scheduler.markCaptured()
        now = 80_000L
        assertFalse(scheduler.shouldCapture(VideoFrameCaptureMode.EVERY_60S))
        now = 90_000L
        assertTrue(scheduler.shouldCapture(VideoFrameCaptureMode.EVERY_60S))
        scheduler.reset()
        assertTrue(scheduler.shouldCapture(VideoFrameCaptureMode.EVERY_60S))
    }

    @Test
    fun `frame cache keeps exactly one pending frame and consumes it`() {
        val cache = VideoFrameCache()
        assertNull(cache.peek())
        assertFalse(cache.hasFrame)
        val first = VideoFrame(byteArrayOf(1), 10, 10, 1_000L)
        val second = VideoFrame(byteArrayOf(2), 10, 10, 2_000L)
        cache.replace(first)
        cache.replace(second)
        assertSame(second, cache.peek())
        assertSame(second, cache.take())
        assertNull(cache.peek())
        assertFalse(cache.hasFrame)
        cache.replace(first)
        cache.clear()
        assertNull(cache.take())
    }
}
