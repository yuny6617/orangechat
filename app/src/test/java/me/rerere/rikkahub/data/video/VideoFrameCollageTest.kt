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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoFrameCollageTest {
    private val background = Color.rgb(16, 22, 28)

    private fun jpeg(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            val output = ByteArrayOutputStream()
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
            return output.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(sheet: ByteArray): Bitmap {
        val bitmap = BitmapFactory.decodeByteArray(sheet, 0, sheet.size)
        assertNotNull("拼图必须是一张可解码的 JPEG", bitmap)
        return bitmap
    }

    private fun assertColorNear(expected: Int, actual: Int, tolerance: Int = 24) {
        assertTrue(
            "颜色偏差过大: expected=${Integer.toHexString(expected)} actual=${Integer.toHexString(actual)}",
            abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
                abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
                abs(Color.blue(expected) - Color.blue(actual)) <= tolerance,
        )
    }

    @Test
    fun singleLandscapeFrameKeepsAspectRatioInsideSquareSheet() {
        val sheet = VideoFrameCollage.compose(listOf(jpeg(400, 300, Color.RED)))
        assertNotNull(sheet)
        val bitmap = decode(sheet!!)
        try {
            assertEquals(VideoFrameCollage.SIDE, bitmap.width)
            assertEquals(VideoFrameCollage.SIDE, bitmap.height)
            // 4:3 的横图放进正方形：宽度铺满，上下各留 150px 背景。
            assertColorNear(background, bitmap.getPixel(600, 60))
            assertColorNear(Color.RED, bitmap.getPixel(600, 600))
            assertColorNear(Color.RED, bitmap.getPixel(10, 600))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun portraitFrameIsLetterboxedInsteadOfStretched() {
        val sheet = VideoFrameCollage.compose(listOf(jpeg(300, 400, Color.BLUE)))
        assertNotNull(sheet)
        val bitmap = decode(sheet!!)
        try {
            // 3:4 的竖图：高度铺满，左右各留 150px 背景；画面本身没有被拉成 1:1。
            assertColorNear(background, bitmap.getPixel(60, 600))
            assertColorNear(Color.BLUE, bitmap.getPixel(600, 600))
            assertColorNear(Color.BLUE, bitmap.getPixel(600, 10))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun orientedJpegFromStoreIsLetterboxedWithoutCropping() {
        // 4:3 的源图 → compressToJpeg（有界采样 + 方向归一化）→ 拼图。
        // 单帧在 1200×1200 里必须是 1200×900（上下各留 150px 背景）：
        // 采样既没有裁掉左右，也没有把 4:3 拉成 1:1。
        val oriented = VideoFrameStore.compressToJpeg(
            raw = jpeg(1600, 1200, Color.RED),
            quality = VideoFrameQuality.P384,
        )
        assertNotNull("有界采样 + 转正必须能出图", oriented)
        val sheet = VideoFrameCollage.compose(listOf(requireNotNull(oriented)))
        assertNotNull(sheet)
        val bitmap = decode(requireNotNull(sheet))
        try {
            assertEquals(VideoFrameCollage.SIDE, bitmap.width)
            assertEquals(VideoFrameCollage.SIDE, bitmap.height)
            assertColorNear(background, bitmap.getPixel(600, 60))
            assertColorNear(Color.RED, bitmap.getPixel(600, 600))
            // 左右两角都是画面：整张源图都在，没有因为采样被裁掉一边。
            assertColorNear(Color.RED, bitmap.getPixel(10, 600))
            assertColorNear(Color.RED, bitmap.getPixel(1190, 600))
            // 画面下边在 y=1050：再往下必须是背景，说明比例没被拉高。
            assertColorNear(Color.RED, bitmap.getPixel(600, 1040))
            assertColorNear(background, bitmap.getPixel(600, 1060))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun fourFramesFillTwoByTwoGridInOrder() {
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val sheet = VideoFrameCollage.compose(colors.map { jpeg(200, 200, it) })
        assertNotNull(sheet)
        val bitmap = decode(sheet!!)
        try {
            val half = VideoFrameCollage.SIDE / 2
            colors.forEachIndexed { index, color ->
                val x = (index % 2) * half + half / 2
                val y = (index / 2) * half + half / 2
                assertColorNear(color, bitmap.getPixel(x, y))
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun fiveFramesUseThreeColumnsAndLeaveLastCellEmpty() {
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA)
        val sheet = VideoFrameCollage.compose(colors.map { jpeg(100, 100, it) })
        assertNotNull(sheet)
        val bitmap = decode(sheet!!)
        try {
            val cellWidth = VideoFrameCollage.SIDE / 3
            val cellHeight = VideoFrameCollage.SIDE / 2
            colors.forEachIndexed { index, color ->
                val x = (index % 3) * cellWidth + cellWidth / 2
                val y = (index / 3) * cellHeight + cellHeight / 2
                assertColorNear(color, bitmap.getPixel(x, y))
            }
            // 第 6 格没有帧，必须是背景色，不能被上一张图撑满。
            assertColorNear(background, bitmap.getPixel(2 * cellWidth + cellWidth / 2, cellHeight + cellHeight / 2))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun nineFramesFillThreeByThreeGrid() {
        val colors = listOf(
            Color.RED,
            Color.GREEN,
            Color.BLUE,
            Color.YELLOW,
            Color.MAGENTA,
            Color.CYAN,
            Color.rgb(255, 165, 0),
            Color.WHITE,
            Color.rgb(128, 128, 128),
        )
        val sheet = VideoFrameCollage.compose(colors.map { jpeg(120, 120, it) })
        assertNotNull(sheet)
        val bitmap = decode(sheet!!)
        try {
            val cell = VideoFrameCollage.SIDE / 3
            colors.forEachIndexed { index, color ->
                val x = (index % 3) * cell + cell / 2
                val y = (index / 3) * cell + cell / 2
                assertColorNear(color, bitmap.getPixel(x, y))
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun emptyTooManyOrCorruptFramesFailClosed() {
        assertNull(VideoFrameCollage.compose(emptyList()))
        assertNull(VideoFrameCollage.compose(List(10) { jpeg(10, 10, Color.RED) }))
        assertNull(VideoFrameCollage.compose(listOf(byteArrayOf(1, 2, 3, 4))))
        // 一张坏帧不能让整张拼图"悄悄少一格"。
        assertNull(
            VideoFrameCollage.compose(
                listOf(jpeg(10, 10, Color.RED), ByteArray(0), jpeg(10, 10, Color.BLUE)),
            ),
        )
    }
}
