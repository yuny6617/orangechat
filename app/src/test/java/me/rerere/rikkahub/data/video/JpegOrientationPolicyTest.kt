/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抓帧 JPEG 的"只旋转一次"策略测试。
 *
 * 这是 REVIEW_R4 §4 的回归点：旧实现一边请求 `JPEG_ORIENTATION = 90`，一边又在落盘时
 * 用 Matrix 转 90。camera2 允许 HAL 直接转像素，所以"真的转了"的机型上会双转。
 * 这里不测"函数返回了它自己算的角度"，而是测**最终落到像素上的总旋转**：
 * 请求给 HAL 的角度 + 我们施加的角度 = 恰好一次转正角。
 */
class JpegOrientationPolicyTest {
    @Test
    fun `hal is never asked to rotate pixels`() {
        // 请求 0 = HAL 什么都不做，旋转所有权唯一。这条断言是"不会双转"的前提。
        assertEquals(0, VideoPreviewGeometry.REQUESTED_JPEG_ORIENTATION)
    }

    @Test
    fun `pixels are rotated exactly once for every exif orientation`() {
        val orientations = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8)
        listOf(0, 90, 180, 270).forEach { geometry ->
            listOf(false, true).forEach { mirror ->
                orientations.forEach { exif ->
                    val decision = JpegOrientationPolicy.decide(geometry, mirror, exif)
                    // 我们施加的旋转 + HAL 被请求的旋转 = 恰好一次转正。
                    val total = VideoPreviewGeometry.normalizeDegrees(
                        VideoPreviewGeometry.REQUESTED_JPEG_ORIENTATION + decision.rotationDegrees,
                    )
                    assertEquals(
                        "geometry=$geometry exif=$exif",
                        VideoPreviewGeometry.normalizeDegrees(geometry),
                        total,
                    )
                    assertTrue(
                        "像素只允许转 0 或 1 次（geometry=$geometry exif=$exif 实际=${decision.pixelRotations}）",
                        decision.pixelRotations <= 1,
                    )
                    assertEquals(mirror, decision.mirror)
                }
            }
        }
    }

    @Test
    fun `exif rotation is diagnosed but never applied on top`() {
        // 6 = 顺时针 90°：如果把它也施加一次，90+90=180 就错了。
        val decision = JpegOrientationPolicy.decide(
            geometryRotationDegrees = 90,
            mirror = false,
            exifOrientation = 6,
        )
        assertEquals(90, decision.rotationDegrees)
        assertEquals(90, decision.exifRotationDegrees)
        assertTrue(decision.exifContradictsRequest)
        assertEquals(1, decision.pixelRotations)
    }

    @Test
    fun `normal exif means the device honoured the request`() {
        listOf(0, 1).forEach { exif ->
            val decision = JpegOrientationPolicy.decide(270, mirror = true, exifOrientation = exif)
            assertFalse("exif=$exif 应该是正常方向", decision.exifContradictsRequest)
            assertEquals(270, decision.rotationDegrees)
            assertTrue(decision.mirror)
        }
    }

    @Test
    fun `mirrored exif orientations are flagged`() {
        listOf(2, 4, 5, 7).forEach { exif ->
            assertTrue("exif=$exif 声明了水平翻转", JpegOrientationPolicy.exifMirroredFor(exif))
            assertTrue(JpegOrientationPolicy.decide(0, false, exif).exifContradictsRequest)
        }
        listOf(1, 3, 6, 8).forEach { exif ->
            assertFalse(JpegOrientationPolicy.exifMirroredFor(exif))
        }
    }

    @Test
    fun `exif rotation table matches the spec`() {
        assertEquals(0, JpegOrientationPolicy.exifRotationFor(1))
        assertEquals(180, JpegOrientationPolicy.exifRotationFor(3))
        assertEquals(90, JpegOrientationPolicy.exifRotationFor(6))
        assertEquals(270, JpegOrientationPolicy.exifRotationFor(8))
        assertEquals(0, JpegOrientationPolicy.exifRotationFor(0))
        // 未知值不能当成功，也不能乱转。
        assertEquals(0, JpegOrientationPolicy.exifRotationFor(99))
    }

    /**
     * 端到端（纯几何部分）：竖屏后摄 sensor 90°、请求 0 度时，
     * 落盘归一化后的图像必须是"宽高互换 + 顺时针 90°"，与预览看到的同一方向。
     */
    @Test
    fun `portrait capture ends up upright with swapped dimensions`() {
        val rotation = VideoPreviewGeometry.uprightRotation(
            sensorOrientation = 90,
            displayRotationDegrees = 0,
            frontFacing = false,
        )
        val decision = JpegOrientationPolicy.decide(rotation, mirror = false, exifOrientation = 1)
        val size = VideoPreviewGeometry.uprightSize(1280, 720, decision.rotationDegrees)
        assertEquals(PixelSize(width = 720, height = 1280), size)
        // 左上角必须走到新图的右上角（顺时针），而不是左上角（没转）或左下角（逆时针）。
        val upright = VideoPreviewGeometry.uprightImageAffine(
            rotationDegrees = decision.rotationDegrees,
            mirror = decision.mirror,
            width = 1280,
            height = 720,
        )
        val corner = upright.map(0f, 0f)
        assertEquals(720f, corner.x, 0.01f)
        assertEquals(0f, corner.y, 0.01f)
    }
}
