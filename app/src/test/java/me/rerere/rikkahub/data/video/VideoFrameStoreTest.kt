/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * 抓帧 JPEG 的**有界解码**与方向/镜像归一化。
 *
 * 用 Robolectric 的原生图形（`NATIVE`）跑真实的 `BitmapFactory` / `Bitmap.compress`：
 * 采样、旋转、镜像、颜色都必须在真实的 Android 图形栈上成立，而不是影子对象的"应该如此"。
 *
 * 这里钉死三件事：
 * 1. 大图**采样解码**，绝不分配源图尺寸的位图（否则 12MP 一帧就是 48MB，低端机直接 OOM）；
 * 2. 采样之后方向/镜像/颜色仍然"所见即所发"，取景范围没有被裁掉、比例没有被拉歪；
 * 3. 坏图/超限一律返回 null，绝不半张图、绝不崩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoFrameStoreTest {
    private val topLeft = Color.rgb(220, 30, 30)
    private val topRight = Color.rgb(30, 200, 30)
    private val bottomLeft = Color.rgb(30, 30, 220)
    private val bottomRight = Color.rgb(230, 220, 40)

    // ---- 测试图 ----

    private fun encode(
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat,
        paint: (Bitmap) -> Unit,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            paint(bitmap)
            val output = ByteArrayOutputStream()
            assertTrue("测试图必须编码成功", bitmap.compress(format, 100, output))
            return output.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun solid(
        width: Int,
        height: Int,
        color: Int,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
    ): ByteArray = encode(width, height, format) { it.eraseColor(color) }

    /** 四个象限四种颜色：转错方向/镜像反了/采样裁掉一边，都会立刻反映在颜色位置上。 */
    private fun quadrants(
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
    ): ByteArray = encode(width, height, format) { bitmap ->
        val halfWidth = width / 2
        val halfHeight = height / 2
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = when {
                    y < halfHeight && x < halfWidth -> topLeft
                    y < halfHeight -> topRight
                    x < halfWidth -> bottomLeft
                    else -> bottomRight
                }
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    // ---- 断言辅助 ----

    /** 只读尺寸：不解码像素，用来验"最终最长边"和"比例"。 */
    private fun bounds(bytes: ByteArray): PixelSize {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return PixelSize(options.outWidth, options.outHeight)
    }

    private fun decodeOrFail(bytes: ByteArray): Bitmap {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("结果必须是一张可解码的 JPEG", bitmap)
        return requireNotNull(bitmap)
    }

    private fun compressed(
        raw: ByteArray,
        quality: VideoFrameQuality = VideoFrameQuality.P384,
        rotationDegrees: Int = 0,
        mirror: Boolean = false,
    ): ByteArray {
        val result = VideoFrameStore.compressToJpeg(raw, quality, rotationDegrees, mirror)
        assertNotNull("压缩必须成功", result)
        return requireNotNull(result)
    }

    private fun assertColorNear(expected: Int, actual: Int, tolerance: Int = 45) {
        assertTrue(
            "颜色偏差过大: expected=${Integer.toHexString(expected)} actual=${Integer.toHexString(actual)}",
            abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
                abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
                abs(Color.blue(expected) - Color.blue(actual)) <= tolerance,
        )
    }

    /** 逐象限取**格子中心**的颜色：中心点离边界足够远，不会被 JPEG 振铃干扰。 */
    private fun assertQuadrantColors(
        jpeg: ByteArray,
        expectedTopLeft: Int,
        expectedTopRight: Int,
        expectedBottomLeft: Int,
        expectedBottomRight: Int,
    ) {
        val bitmap = decodeOrFail(jpeg)
        try {
            val halfWidth = bitmap.width / 2
            val halfHeight = bitmap.height / 2
            assertColorNear(expectedTopLeft, bitmap.getPixel(halfWidth / 2, halfHeight / 2))
            assertColorNear(expectedTopRight, bitmap.getPixel(halfWidth + halfWidth / 2, halfHeight / 2))
            assertColorNear(expectedBottomLeft, bitmap.getPixel(halfWidth / 2, halfHeight + halfHeight / 2))
            assertColorNear(
                expectedBottomRight,
                bitmap.getPixel(halfWidth + halfWidth / 2, halfHeight + halfHeight / 2),
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertAspect(expectedRatio: Float, size: PixelSize) {
        val ratio = size.width.toFloat() / size.height.toFloat()
        assertTrue(
            "比例被采样/缩放改变了: ${size.width}x${size.height} ($ratio) != $expectedRatio",
            abs(ratio - expectedRatio) < 0.01f,
        )
    }

    // ---- 有界解码 ----

    @Test
    fun decodeSamplesLargeJpegInsteadOfAllocatingTheSourceSize() {
        val source = solid(2000, 1500, Color.RED)
        val decoded = VideoFrameStore.decode(source, maxEdgePx = 256)
        assertNotNull("大图必须能解出来", decoded)
        val bitmap = requireNotNull(decoded)
        try {
            // 2000 的源图按 2 的幂采到 500（再采一格 250 就低于目标边 256）：
            // 绝不会分配 2000×1500 = 3MP 的源尺寸位图。
            assertEquals(500, bitmap.width)
            assertEquals(375, bitmap.height)
            assertAspect(4f / 3f, PixelSize(bitmap.width, bitmap.height))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun decodeHonoursThePixelBudgetBeforeTheTargetEdge() {
        // 6MP 的源图超过单张位图的像素预算（4MP）：必须再多采一格，哪怕会离目标边远一点。
        val source = solid(3000, 2000, Color.RED)
        val decoded = VideoFrameStore.decode(source, maxEdgePx = 256)
        assertNotNull("大图必须能解出来", decoded)
        val bitmap = requireNotNull(decoded)
        try {
            val longest = maxOf(bitmap.width, bitmap.height)
            assertTrue("解码结果必须远小于源图（3000）: $longest", longest < 512)
            assertTrue("有界解码不该丢掉目标边: $longest", longest >= 256)
            assertTrue(
                "解码结果超出像素预算: ${bitmap.width}x${bitmap.height}",
                bitmap.width.toLong() * bitmap.height.toLong() <= VideoFrameStore.MAX_DECODED_PIXELS,
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun decodeNeverUpscalesSmallImages() {
        val source = solid(100, 80, Color.RED)
        val decoded = VideoFrameStore.decode(source, maxEdgePx = 512)
        assertNotNull(decoded)
        val bitmap = requireNotNull(decoded)
        try {
            assertEquals(100, bitmap.width)
            assertEquals(80, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    // ---- 坏图 / 超限：一律 null ----

    @Test
    fun badImagesFailClosed() {
        assertNull("空数组不是图片", VideoFrameStore.decode(ByteArray(0)))
        assertNull("随手几个字节不是图片", VideoFrameStore.decode(byteArrayOf(1, 2, 3, 4)))
        assertNull(VideoFrameStore.compressToJpeg(ByteArray(0), VideoFrameQuality.P384))
        assertNull(VideoFrameStore.compressToJpeg(byteArrayOf(1, 2, 3, 4), VideoFrameQuality.P384))
        // 只有半截 JPEG：尺寸头可能还在，像素已经没了 —— 必须是 null，不能交出半张图。
        val truncated = solid(200, 200, Color.RED).copyOf(120)
        assertNull(VideoFrameStore.decode(truncated))
        assertNull(VideoFrameStore.compressToJpeg(truncated, VideoFrameQuality.P384))
    }

    @Test
    fun oversizedInputsAreRejectedBeforeAnyPixelsAreAllocated() {
        // raw 字节超过硬上限：连尺寸都不读。
        assertNull(VideoFrameStore.decode(ByteArray(VideoFrameStore.MAX_RAW_BYTES + 1)))
        // 源图单边超过硬上限：读得出尺寸，但绝不解码。
        val tooWide = solid(VideoFrameStore.MAX_SOURCE_EDGE + 1, 8, Color.RED)
        assertEquals(VideoFrameStore.MAX_SOURCE_EDGE + 1, bounds(tooWide).width)
        assertNull(VideoFrameStore.decode(tooWide))
        assertNull(VideoFrameStore.compressToJpeg(tooWide, VideoFrameQuality.P384))
    }

    // ---- 画质 / 比例 ----

    @Test
    fun everyQualityPresetBoundsTheLongestEdgeAndKeepsTheAspectRatio() {
        val source = solid(1600, 1200, Color.RED)
        VideoFrameQuality.entries.forEach { quality ->
            val size = bounds(compressed(source, quality = quality))
            assertEquals("$quality 的最长边必须落在档位上", quality.maxEdgePx, maxOf(size.width, size.height))
            assertAspect(4f / 3f, size)
        }
    }

    @Test
    fun compressDoesNotUpscaleFramesSmallerThanThePreset() {
        val result = compressed(solid(100, 80, Color.RED), quality = VideoFrameQuality.P512)
        assertEquals(PixelSize(100, 80), bounds(result))
    }

    // ---- 方向 / 镜像 / 取景范围 ----

    @Test
    fun sampledDecodeKeepsDirectionMirrorAndFullFrameColors() {
        // 1200×800 会触发采样（P384 时解到 600×400），所以这里同时验证"采样 + 转正"。
        val source = quadrants(1200, 800)

        // 不转：象限原样，左右上下都没被裁掉。
        val upright = compressed(source)
        assertEquals(PixelSize(384, 256), bounds(upright))
        assertQuadrantColors(upright, topLeft, topRight, bottomLeft, bottomRight)

        // 顺时针 90°：源 TL→目标 TR，源 TR→目标 BR，源 BR→目标 BL，源 BL→目标 TL。
        val clockwise = compressed(source, rotationDegrees = 90)
        assertEquals(PixelSize(256, 384), bounds(clockwise))
        assertQuadrantColors(
            clockwise,
            expectedTopLeft = bottomLeft,
            expectedTopRight = topLeft,
            expectedBottomLeft = bottomRight,
            expectedBottomRight = topRight,
        )

        // 180°：对角互换。
        val upsideDown = compressed(source, rotationDegrees = 180)
        assertEquals(PixelSize(384, 256), bounds(upsideDown))
        assertQuadrantColors(
            upsideDown,
            expectedTopLeft = bottomRight,
            expectedTopRight = bottomLeft,
            expectedBottomLeft = topRight,
            expectedBottomRight = topLeft,
        )

        // 270°（顺时针）：源 TL→目标 BL，源 TR→目标 TL，源 BR→目标 TR，源 BL→目标 BR。
        val counterClockwise = compressed(source, rotationDegrees = 270)
        assertEquals(PixelSize(256, 384), bounds(counterClockwise))
        assertQuadrantColors(
            counterClockwise,
            expectedTopLeft = topRight,
            expectedTopRight = bottomRight,
            expectedBottomLeft = topLeft,
            expectedBottomRight = bottomLeft,
        )

        // 只镜像：左右互换、上下不变（前摄"所见即所发"）。
        val mirrored = compressed(source, mirror = true)
        assertEquals(PixelSize(384, 256), bounds(mirrored))
        assertQuadrantColors(
            mirrored,
            expectedTopLeft = topRight,
            expectedTopRight = topLeft,
            expectedBottomLeft = bottomRight,
            expectedBottomRight = bottomLeft,
        )

        // 先转 90° 再镜像：镜像在正立之后做。
        val rotatedMirrored = compressed(source, rotationDegrees = 90, mirror = true)
        assertEquals(PixelSize(256, 384), bounds(rotatedMirrored))
        assertQuadrantColors(
            rotatedMirrored,
            expectedTopLeft = topLeft,
            expectedTopRight = bottomLeft,
            expectedBottomLeft = topRight,
            expectedBottomRight = bottomRight,
        )
    }

    @Test
    fun rotatedPortraitBecomesLandscapeWithTheSameAspect() {
        // 900×1200 的竖图顺时针转 90°：输出宽高互换（3:4 → 4:3），比例不变。
        val portrait = solid(900, 1200, Color.BLUE)
        val rotated = bounds(compressed(portrait, rotationDegrees = 90))
        assertEquals(PixelSize(384, 288), rotated)
        assertAspect(4f / 3f, rotated)
    }

    // ---- 输入格式 ----

    @Test
    fun pngInputStillProducesAJpeg() {
        val source = solid(400, 300, Color.RED, Bitmap.CompressFormat.PNG)
        val result = compressed(source)
        // 输出必须是 JPEG（FF D8）：PNG 只是输入，落盘格式不变。
        assertEquals(0xFF.toByte(), result[0])
        assertEquals(0xD8.toByte(), result[1])
        val size = bounds(result)
        assertEquals(PixelSize(384, 288), size)
        val bitmap = decodeOrFail(result)
        try {
            assertColorNear(Color.RED, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
        } finally {
            bitmap.recycle()
        }
    }
}
