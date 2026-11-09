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
 * 预览 / 抓帧方向的**逐点几何**测试。
 *
 * REVIEW_R4 §5 指出旧测试断言的是 `scale = max(view/content)` 这个伪正确公式：
 * 它只验证了"函数返回自己算出来的数"，完全没有验证画面上会发生什么。
 * `TextureView` 默认就会把 buffer 拉伸到视图大小，所以这里改为验证**真正被看到的变换**：
 *
 * 1. `setTransform` 拿到的矩阵 M 与 TextureView 的默认拉伸 S 合起来，必须等于想要的
 *    `visibleAffine`（旋转 + 等比 centerCrop + 镜像）；
 * 2. 直接用代表性像素点验证落点（角点、中心点）；
 * 3. 验证等比性（各向放大倍数一致）与"铺满不留黑边"。
 */
class VideoPreviewGeometryTest {
    private val delta = 0.01f

    /** TextureView 把 buffer 拉伸到视图大小的默认变换。 */
    private fun textureViewDefaultStretch(
        bufferWidth: Int,
        bufferHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): Affine = Affine.scale(
        viewWidth.toFloat() / bufferWidth.toFloat(),
        viewHeight.toFloat() / bufferHeight.toFloat(),
    )

    @Test
    fun `back camera rotation is sensor minus display`() {
        assertEquals(90, VideoPreviewGeometry.uprightRotation(90, 0, frontFacing = false))
        assertEquals(180, VideoPreviewGeometry.uprightRotation(90, 270, frontFacing = false))
        assertEquals(0, VideoPreviewGeometry.uprightRotation(90, 90, frontFacing = false))
        assertEquals(270, VideoPreviewGeometry.uprightRotation(90, 180, frontFacing = false))
        assertEquals(270, VideoPreviewGeometry.uprightRotation(270, 0, frontFacing = false))
        assertEquals(90, VideoPreviewGeometry.uprightRotation(270, 180, frontFacing = false))
    }

    @Test
    fun `front camera rotation is sensor plus display`() {
        assertEquals(270, VideoPreviewGeometry.uprightRotation(270, 0, frontFacing = true))
        assertEquals(0, VideoPreviewGeometry.uprightRotation(270, 90, frontFacing = true))
        assertEquals(90, VideoPreviewGeometry.uprightRotation(270, 180, frontFacing = true))
        assertEquals(180, VideoPreviewGeometry.uprightRotation(270, 270, frontFacing = true))
    }

    @Test
    fun `rotation is always normalized to a right angle`() {
        assertEquals(0, VideoPreviewGeometry.normalizeDegrees(-45))
        assertEquals(90, VideoPreviewGeometry.normalizeDegrees(450))
        assertEquals(270, VideoPreviewGeometry.normalizeDegrees(-90))
        assertEquals(0, VideoPreviewGeometry.normalizeDegrees(359))
        assertEquals(0, VideoPreviewGeometry.uprightRotation(90, 450, frontFacing = false))
    }

    /**
     * 核心不变量：**用户看到的** = 想要的几何。
     *
     * TextureView 实际渲染 `M ∘ S`（M 是我们 setTransform 的矩阵，S 是它的默认拉伸）。
     * 这里把四组真实尺寸下的 `M ∘ S` 与 `visibleAffine` 逐系数比对。
     */
    @Test
    fun `texture view matrix cancels its own default stretch`() {
        val cases = listOf(
            // buffer, view, sensor, display, front
            Case(1280, 720, 1080, 2400, 90, 0, false),
            Case(1280, 720, 1080, 1920, 90, 0, false),
            Case(640, 480, 1080, 1920, 90, 0, false),
            Case(1920, 1080, 2400, 1080, 90, 90, false),
            Case(1280, 720, 1080, 2340, 270, 0, true),
            Case(720, 1280, 1080, 1920, 0, 0, true),
        )
        cases.forEach { case ->
            val rotation = VideoPreviewGeometry.uprightRotation(
                sensorOrientation = case.sensor,
                displayRotationDegrees = case.display,
                frontFacing = case.front,
            )
            val received = VideoPreviewGeometry.textureViewAffine(
                rotationDegrees = rotation,
                mirror = case.front,
                bufferWidth = case.bufferWidth,
                bufferHeight = case.bufferHeight,
                viewWidth = case.viewWidth,
                viewHeight = case.viewHeight,
            )
            val shown = textureViewDefaultStretch(
                case.bufferWidth,
                case.bufferHeight,
                case.viewWidth,
                case.viewHeight,
            ).then(received)
            val expected = VideoPreviewGeometry.visibleAffine(
                rotationDegrees = rotation,
                mirror = case.front,
                bufferWidth = case.bufferWidth,
                bufferHeight = case.bufferHeight,
                viewWidth = case.viewWidth,
                viewHeight = case.viewHeight,
            )
            assertEquals(case.describe(), expected.a, shown.a, 1e-3f)
            assertEquals(case.describe(), expected.b, shown.b, 1e-3f)
            assertEquals(case.describe(), expected.c, shown.c, 1e-3f)
            assertEquals(case.describe(), expected.d, shown.d, 1e-3f)
            assertEquals(case.describe(), expected.e, shown.e, 1e-3f)
            assertEquals(case.describe(), expected.f, shown.f, 1e-3f)
        }
    }

    /**
     * 竖屏手机、后摄 sensor 90°、buffer 1280x720、view 1080x1920。
     *
     * 转 90° 之后可见尺寸正好是 720x1280，等比放大 1.5 倍正好铺满 1080x1920，
     * 因此四个 buffer 角点应当**精确落到**四个 view 角点上（不裁、不压扁、无黑边）。
     */
    @Test
    fun `portrait 1280x720 buffer lands exactly on the 1080x1920 view corners`() {
        val visible = VideoPreviewGeometry.visibleAffine(
            rotationDegrees = 90,
            mirror = false,
            bufferWidth = 1280,
            bufferHeight = 720,
            viewWidth = 1080,
            viewHeight = 1920,
        )
        // 90° 顺时针：左上 -> 右上，右下 -> 左下。
        assertPoint(1080f, 0f, visible.map(0f, 0f))
        assertPoint(1080f, 1920f, visible.map(1280f, 0f))
        assertPoint(0f, 1920f, visible.map(1280f, 720f))
        assertPoint(0f, 0f, visible.map(0f, 720f))
        // 中心必须落在视图中心。
        assertPoint(540f, 960f, visible.map(640f, 360f))
        // 等比：1.5 倍，两个方向一致。
        assertEquals(1.5f, visible.uniformScale, delta)
    }

    /**
     * 4:3 的 buffer 塞进 1080x1920 的竖屏视图：等比放大 4 倍后左右被裁掉，
     * 上下正好贴合 —— 这就是 centerCrop，绝不能是拉伸。
     */
    @Test
    fun `4 by 3 buffer is center cropped into a tall view without distortion`() {
        val visible = VideoPreviewGeometry.visibleAffine(
            rotationDegrees = 0,
            mirror = false,
            bufferWidth = 640,
            bufferHeight = 480,
            viewWidth = 1080,
            viewHeight = 1920,
        )
        assertEquals(4f, visible.uniformScale, delta)
        // 上下边正好对齐视图上下边。
        assertPoint(540f, 0f, visible.map(320f, 0f))
        assertPoint(540f, 1920f, visible.map(320f, 480f))
        // 左右被裁：中心 x=540 两侧各延伸 1280。
        assertPoint(-740f, 960f, visible.map(0f, 240f))
        assertPoint(1820f, 960f, visible.map(640f, 240f))
        assertCoversView(visible, 640, 480, 1080, 1920)
    }

    /**
     * 关键回归：因为有默认拉伸，交给 TextureView 的矩阵**本身不能是**"旋转 + 等比缩放"。
     * 如果它是，就说明代码还在用那个伪正确公式（拉伸根本没被抵消）。
     */
    @Test
    fun `the matrix handed to texture view is not a plain uniform scale`() {
        val received = VideoPreviewGeometry.textureViewAffine(
            rotationDegrees = 0,
            mirror = false,
            bufferWidth = 640,
            bufferHeight = 480,
            viewWidth = 1080,
            viewHeight = 1920,
        )
        assertTrue(
            "buffer 与 view 比例不同时，必须用非等比矩阵抵消 TextureView 的默认拉伸",
            received.uniformScale.isNaN(),
        )
        // 但把默认拉伸抵消之后，屏幕上的效果必须是等比的。
        val shown = textureViewDefaultStretch(640, 480, 1080, 1920).then(received)
        assertEquals(4f, shown.uniformScale, delta)
    }

    @Test
    fun `front camera preview is mirrored horizontally`() {
        val visible = VideoPreviewGeometry.visibleAffine(
            rotationDegrees = 270,
            mirror = true,
            bufferWidth = 1280,
            bufferHeight = 720,
            viewWidth = 1080,
            viewHeight = 1920,
        )
        // 镜像前 buffer 左上角落在右上角，镜像后应落在左上角。
        val unmirrored = VideoPreviewGeometry.visibleAffine(
            rotationDegrees = 270,
            mirror = false,
            bufferWidth = 1280,
            bufferHeight = 720,
            viewWidth = 1080,
            viewHeight = 1920,
        )
        val before = unmirrored.map(0f, 0f)
        assertPoint(1080f - before.x, before.y, visible.map(0f, 0f))
        // 中心点镜像后仍是中心。
        assertPoint(540f, 960f, visible.map(640f, 360f))
    }

    /** 任意旋转/镜像组合都必须铺满视图（不留黑边）并且等比。 */
    @Test
    fun `every rotation covers the view with uniform scale`() {
        val buffers = listOf(1280 to 720, 640 to 480, 720 to 1280, 1920 to 1080)
        val views = listOf(1080 to 1920, 1080 to 2400, 2400 to 1080, 800 to 800)
        listOf(0, 90, 180, 270).forEach { rotation ->
            listOf(false, true).forEach { mirror ->
                buffers.forEach { (bufferWidth, bufferHeight) ->
                    views.forEach { (viewWidth, viewHeight) ->
                        val visible = VideoPreviewGeometry.visibleAffine(
                            rotationDegrees = rotation,
                            mirror = mirror,
                            bufferWidth = bufferWidth,
                            bufferHeight = bufferHeight,
                            viewWidth = viewWidth,
                            viewHeight = viewHeight,
                        )
                        assertFalse(
                            "rotation=$rotation mirror=$mirror ${bufferWidth}x$bufferHeight -> " +
                                "${viewWidth}x$viewHeight 不是等比变换",
                            visible.uniformScale.isNaN(),
                        )
                        assertCoversView(visible, bufferWidth, bufferHeight, viewWidth, viewHeight)
                    }
                }
            }
        }
    }

    /**
     * 抓帧落盘的那一次归一化：sensor 原始像素 → 正立图像。
     *
     * 用 4x2 的小图逐点验证：90° 顺时针必须把左上角送到新图的右上角，
     * 并且新图尺寸宽高互换。这就是"照片方向"本身，不是角度返回值。
     */
    @Test
    fun `upright image affine rotates pixels clockwise exactly once`() {
        val upright = VideoPreviewGeometry.uprightImageAffine(
            rotationDegrees = 90,
            mirror = false,
            width = 4,
            height = 2,
        )
        assertEquals(PixelSize(width = 2, height = 4), VideoPreviewGeometry.uprightSize(4, 2, 90))
        assertPoint(2f, 0f, upright.map(0f, 0f)) // 左上 -> 右上
        assertPoint(0f, 4f, upright.map(4f, 2f)) // 右下 -> 左下
        assertPoint(0f, 0f, upright.map(0f, 2f)) // 左下 -> 左上
        assertPoint(2f, 4f, upright.map(4f, 0f)) // 右上 -> 右下
        assertEquals(1f, upright.uniformScale, delta)
    }

    @Test
    fun `upright image affine mirrors after rotating`() {
        val mirrored = VideoPreviewGeometry.uprightImageAffine(
            rotationDegrees = 90,
            mirror = true,
            width = 4,
            height = 2,
        )
        // 旋转后左上角在 (2,0)，镜像后回到 (0,0)。
        assertPoint(0f, 0f, mirrored.map(0f, 0f))
        assertPoint(2f, 4f, mirrored.map(4f, 2f))
        assertEquals(1f, mirrored.uniformScale, delta)
    }

    @Test
    fun `zero degree orientation leaves the image untouched`() {
        val identity = VideoPreviewGeometry.uprightImageAffine(
            rotationDegrees = 0,
            mirror = false,
            width = 5,
            height = 3,
        )
        assertPoint(0f, 0f, identity.map(0f, 0f))
        assertPoint(5f, 3f, identity.map(5f, 3f))
        assertEquals(PixelSize(width = 5, height = 3), VideoPreviewGeometry.uprightSize(5, 3, 0))
    }

    @Test
    fun `unknown sizes degrade to identity instead of crashing`() {
        val visible = VideoPreviewGeometry.visibleAffine(
            rotationDegrees = 90,
            mirror = true,
            bufferWidth = 0,
            bufferHeight = 0,
            viewWidth = 0,
            viewHeight = 0,
        )
        assertEquals(Affine.IDENTITY, visible)
        val transform = VideoPreviewGeometry.textureViewTransform(
            sensorOrientation = 90,
            displayRotationDegrees = 0,
            frontFacing = true,
            bufferWidth = 0,
            bufferHeight = 0,
            viewWidth = 0,
            viewHeight = 0,
        )
        assertEquals(90, transform.rotationDegrees)
        assertTrue(transform.mirrorHorizontally)
    }

    @Test
    fun `preview and jpeg share the same angle`() {
        listOf(0, 90, 180, 270).forEach { sensor ->
            listOf(0, 90, 180, 270).forEach { display ->
                listOf(false, true).forEach { front ->
                    val transform = VideoPreviewGeometry.textureViewTransform(
                        sensorOrientation = sensor,
                        displayRotationDegrees = display,
                        frontFacing = front,
                        bufferWidth = 640,
                        bufferHeight = 480,
                        viewWidth = 480,
                        viewHeight = 640,
                    )
                    assertEquals(
                        VideoPreviewGeometry.uprightRotation(sensor, display, front),
                        transform.rotationDegrees,
                    )
                    assertEquals(front, transform.mirrorHorizontally)
                }
            }
        }
    }

    private fun assertPoint(expectedX: Float, expectedY: Float, actual: Point2) {
        assertEquals("x", expectedX, actual.x, delta)
        assertEquals("y", expectedY, actual.y, delta)
    }

    /** 铺满：buffer 四个角映射出来的包围盒必须把整个视图包住（否则就是黑边）。 */
    private fun assertCoversView(
        visible: Affine,
        bufferWidth: Int,
        bufferHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ) {
        val corners = listOf(
            visible.map(0f, 0f),
            visible.map(bufferWidth.toFloat(), 0f),
            visible.map(bufferWidth.toFloat(), bufferHeight.toFloat()),
            visible.map(0f, bufferHeight.toFloat()),
        )
        val minX = corners.minOf { it.x }
        val maxX = corners.maxOf { it.x }
        val minY = corners.minOf { it.y }
        val maxY = corners.maxOf { it.y }
        assertTrue(
            "左边有黑边：$minX > 0（buffer ${bufferWidth}x$bufferHeight, view ${viewWidth}x$viewHeight）",
            minX <= delta,
        )
        assertTrue("上边有黑边：$minY > 0", minY <= delta)
        assertTrue("右边有黑边：$maxX < $viewWidth", maxX >= viewWidth - delta)
        assertTrue("下边有黑边：$maxY < $viewHeight", maxY >= viewHeight - delta)
    }

    private data class Case(
        val bufferWidth: Int,
        val bufferHeight: Int,
        val viewWidth: Int,
        val viewHeight: Int,
        val sensor: Int,
        val display: Int,
        val front: Boolean,
    ) {
        fun describe(): String =
            "buffer ${bufferWidth}x$bufferHeight -> view ${viewWidth}x$viewHeight " +
                "sensor=$sensor display=$display front=$front"
    }
}
