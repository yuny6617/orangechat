/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import android.app.Application
import android.graphics.Matrix
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
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

    // ------------------------------------------------------------------
    // 预览人工校准（PreviewCalibration）：只转预览，绝不动抓帧
    // ------------------------------------------------------------------

    /**
     * 校准必须真的把**最终画面**转过去。
     *
     * 交给 `TextureView` 的 M 满足 `M ∘ S = F`（S 是它的默认拉伸，F 是想要的画面），
     * 校准后重新 cover：`M' = K ∘ Rot ∘ M`。此例基准旋转 90° 后再右转 90°，
     * 最终 1280x720 内容须等比放大 8/3 倍，才能填满 1080x1920。
     */
    @Test
    fun `manual calibration rotates the final preview around the view centre`() {
        val bufferWidth = 1280
        val bufferHeight = 720
        val viewWidth = 1080
        val viewHeight = 1920
        val transform = VideoPreviewGeometry.textureViewTransform(
            sensorOrientation = 90,
            displayRotationDegrees = 0,
            frontFacing = false,
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            calibrationDegrees = 90,
        )
        assertEquals("几何角度不受校准影响", 90, transform.rotationDegrees)
        assertEquals(90, transform.calibrationDegrees)

        val stretch = textureViewDefaultStretch(bufferWidth, bufferHeight, viewWidth, viewHeight)
        // 独立像素公式：最终旋转180°，中心不动，s=max(1080/1280,1920/720)。
        val expected = Affine.translate(-640f, -360f)
            .then(Affine.scale(-8f / 3f))
            .then(Affine.translate(540f, 960f))
        for (px in listOf(0f, 640f, 1280f)) {
            for (py in listOf(0f, 360f, 720f)) {
                val shown = stretch.then(transform.matrix).map(px, py)
                val want = expected.map(px, py)
                assertPoint(want.x, want.y, shown)
            }
        }
    }

    @Test
    fun `calibration wraps to right angles and can be undone`() {
        assertEquals(0, PreviewCalibration.normalize(360))
        assertEquals(270, PreviewCalibration.normalize(-90))
        assertEquals(90, PreviewCalibration.rotateRight(0))
        assertEquals(0, PreviewCalibration.rotateRight(270))
        assertEquals(270, PreviewCalibration.rotateLeft(0))
        assertEquals(0, PreviewCalibration.rotateLeft(90))
        // 转反了必须能原路退回：右转一次再左转一次 = 原地不动。
        assertEquals(90, PreviewCalibration.rotateLeft(PreviewCalibration.rotateRight(90)))
        assertEquals(listOf(0, 90, 180, 270), PreviewCalibration.STEPS)
    }

    @Test
    fun `zero calibration changes nothing and unknown devices default to zero`() {
        fun transform(calibrationDegrees: Int) = VideoPreviewGeometry.textureViewTransform(
            sensorOrientation = 90,
            displayRotationDegrees = 0,
            frontFacing = false,
            bufferWidth = 640,
            bufferHeight = 480,
            viewWidth = 480,
            viewHeight = 640,
            calibrationDegrees = calibrationDegrees,
        )

        val withoutCalibration = transform(0)
        assertEquals("0° 校准必须等于完全不加校准", withoutCalibration.matrix, transform(0).matrix)
        assertEquals(0, withoutCalibration.calibrationDegrees)
        assertTrue("90° 校准必须真的改变矩阵", transform(90).matrix != withoutCalibration.matrix)
        // 未知设备不附加旋转；目标机型的用户反馈由显式设备策略单独处理。
        assertEquals(0, PreviewCalibration.DEFAULT_DEGREES)
        assertEquals(0, PreviewCalibration.reset())
        assertEquals("90°", PreviewCalibration.label(90))
        assertEquals("0°", PreviewCalibration.label(360))
    }

    /**
     * 校准**同时**进入预览与抓帧：两者用同一个角度，且各自只加一次。
     *
     * 用户要求"看到的画面就是发给模型的画面"，所以抓帧角 = 基准角 + 校准角；
     * 预览矩阵的 `rotationDegrees` 仍是基准角（日志可分开看），
     * `rotation + calibration` 必须正好等于抓帧角。
     */
    @Test
    fun `capture angle includes preview calibration exactly once`() {
        val base = VideoPreviewGeometry.uprightRotation(90, 0, frontFacing = false)
        assertEquals(90, base)
        assertEquals(90, VideoPreviewGeometry.captureRotationDegrees(90, 0, frontFacing = false))
        assertEquals(180, VideoPreviewGeometry.captureRotationDegrees(90, 0, frontFacing = false, calibrationDegrees = 90))
        assertEquals(0, VideoPreviewGeometry.captureRotationDegrees(90, 0, frontFacing = false, calibrationDegrees = 270))

        listOf(0, 90, 180, 270).forEach { calibration ->
            val preview = VideoPreviewGeometry.textureViewTransform(
                sensorOrientation = 90,
                displayRotationDegrees = 0,
                frontFacing = false,
                bufferWidth = 640,
                bufferHeight = 480,
                viewWidth = 480,
                viewHeight = 640,
                calibrationDegrees = calibration,
            )
            assertEquals("预览基准角不因校准改变", base, preview.rotationDegrees)
            assertEquals(calibration, preview.calibrationDegrees)
            assertEquals(
                "抓帧角必须等于预览的基准角 + 校准角",
                VideoPreviewGeometry.captureRotationDegrees(90, 0, frontFacing = false, calibrationDegrees = calibration),
                VideoPreviewGeometry.normalizeDegrees(preview.rotationDegrees + preview.calibrationDegrees),
            )
        }
    }

    /**
     * 预览卡用的比例必须是"旋转 + 校准"之后整张源图的正立尺寸。
     *
     * 4:3 的 buffer 在竖屏基准角 90° 下是 3:4；再校准 90° 后应变成 4:3。
     * UI 按它设置卡片宽高比，预览的 cover 才等于整图贴合（不裁、不拉伸）。
     */
    @Test
    fun `preview content size is the upright source size after calibration`() {
        assertEquals(
            PixelSize(480, 640),
            VideoPreviewGeometry.orientedContentSize(640, 480, rotationDegrees = 90),
        )
        assertEquals(
            PixelSize(640, 480),
            VideoPreviewGeometry.orientedContentSize(640, 480, rotationDegrees = 90, calibrationDegrees = 90),
        )
        assertEquals(
            PixelSize(480, 640),
            VideoPreviewGeometry.orientedContentSize(640, 480, rotationDegrees = 90, calibrationDegrees = 180),
        )
        assertEquals(
            PixelSize(640, 480),
            VideoPreviewGeometry.orientedContentSize(640, 480, rotationDegrees = 90, calibrationDegrees = 270),
        )
        // 尺寸未知时必须是 0x0，不能被当成 1:1 的有效比例。
        assertEquals(PixelSize(0, 0), VideoPreviewGeometry.orientedContentSize(0, 480, 90))
        assertEquals(PixelSize(0, 0), VideoPreviewGeometry.orientedContentSize(640, -1, 90))

        assertEquals(
            PixelSize(480, 640),
            VideoPreviewGeometry.textureViewTransform(
                sensorOrientation = 90,
                displayRotationDegrees = 0,
                frontFacing = false,
                bufferWidth = 640,
                bufferHeight = 480,
                viewWidth = 480,
                viewHeight = 640,
            ).contentSize,
        )
    }

    /**
     * 预览卡按 contentSize 定比例时，整张 sensor 画面必须可见：四个 buffer 角点
     * **精确落在**视图四角（没有被 cover 裁掉），也就是预览与抓帧 JPEG 的取景范围相同。
     *
     * 这是"不能依赖 TextureView 默认拉伸"的直接检查：只要视图比例 = 正立源图比例，
     * 预览的可见范围就是整个 buffer，不需要猜 TextureView 会把画面裁掉多少。
     */
    @Test
    fun `card aspect matching the content size shows the whole sensor frame`() {
        val bufferWidth = 640
        val bufferHeight = 480
        listOf(0, 90, 180, 270).forEach { rotation ->
            val content = VideoPreviewGeometry.orientedContentSize(bufferWidth, bufferHeight, rotation)
            // 视图按 contentSize 同比例放大 2 倍（真实设备上只是像素不同）。
            val viewWidth = content.width * 2
            val viewHeight = content.height * 2
            val visible = VideoPreviewGeometry.visibleAffine(
                rotationDegrees = rotation,
                mirror = false,
                bufferWidth = bufferWidth,
                bufferHeight = bufferHeight,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
            )
            assertEquals("rotation=$rotation 必须等比", 2f, visible.uniformScale, delta)
            // buffer 四角必须一一落到视图四角：整图可见、没有被裁，也没有超出视图。
            assertCornersLandOnView(
                mapped = listOf(
                    visible.map(0f, 0f),
                    visible.map(bufferWidth.toFloat(), 0f),
                    visible.map(bufferWidth.toFloat(), bufferHeight.toFloat()),
                    visible.map(0f, bufferHeight.toFloat()),
                ),
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                tag = "rotation=$rotation",
            )
        }
    }

    /**
     * 校准之后同样不能裁掉画面：卡片比例取自 `PreviewTransform.contentSize`，
     * 把 `setTransform` 的矩阵与 TextureView 默认拉伸复合（`M ∘ S`）后，整张源图仍恰好铺满视图。
     *
     * 这同时验证"预览与 JPEG 的取景范围一致"在**校准路径**上成立，而不只是 0° 校准。
     */
    @Test
    fun `calibrated preview with content-sized card still shows the whole frame`() {
        val bufferWidth = 640
        val bufferHeight = 480
        listOf(0, 90, 180, 270).forEach { calibration ->
            val baseRotation = VideoPreviewGeometry.uprightRotation(90, 0, frontFacing = false)
            val content = VideoPreviewGeometry.orientedContentSize(
                bufferWidth = bufferWidth,
                bufferHeight = bufferHeight,
                rotationDegrees = baseRotation,
                calibrationDegrees = calibration,
            )
            // 视图按 contentSize 同比例放大 2 倍；矩阵必须按这个真实 view 尺寸计算。
            val viewWidth = content.width * 2
            val viewHeight = content.height * 2
            val transform = VideoPreviewGeometry.textureViewTransform(
                sensorOrientation = 90,
                displayRotationDegrees = 0,
                frontFacing = false,
                bufferWidth = bufferWidth,
                bufferHeight = bufferHeight,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                calibrationDegrees = calibration,
            )
            assertEquals(content, transform.contentSize)
            val stretch = textureViewDefaultStretch(bufferWidth, bufferHeight, viewWidth, viewHeight)
            val shown = stretch.then(transform.matrix)
            // M ∘ S 必须把整张源图的四角一一送到视图四角（校准后仍不裁、不拉伸）。
            assertCornersLandOnView(
                mapped = listOf(
                    shown.map(0f, 0f),
                    shown.map(bufferWidth.toFloat(), 0f),
                    shown.map(bufferWidth.toFloat(), bufferHeight.toFloat()),
                    shown.map(0f, bufferHeight.toFloat()),
                ),
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                tag = "calibration=$calibration",
            )
        }
    }

    /**
     * 前后摄、横竖屏、校准、镜像的**所有组合**下，预览最终画面与 JPEG 归一化必须只差
     * 一个统一缩放（旋转方向、镜像轴完全一致）。
     *
     * 这是"模型收到的方向/镜像与预览一致"的纯几何证明：比较两者的线性部分
     * （`mapVector`），不用管视图尺寸和裁剪平移。
     */
    @Test
    fun `preview and jpeg normalization share rotation and mirror in every combination`() {
        listOf(0, 90, 180, 270).forEach { sensor ->
            listOf(0, 90, 180, 270).forEach { display ->
                listOf(false, true).forEach { front ->
                    listOf(0, 90, 180, 270).forEach { calibration ->
                        listOf(false, true).forEach { mirror ->
                            val baseRotation = VideoPreviewGeometry.uprightRotation(sensor, display, front)
                            val content = VideoPreviewGeometry.orientedContentSize(
                                bufferWidth = 640,
                                bufferHeight = 480,
                                rotationDegrees = baseRotation,
                                calibrationDegrees = calibration,
                            )
                            val viewWidth = content.width * 2
                            val viewHeight = content.height * 2
                            val transform = VideoPreviewGeometry.textureViewTransform(
                                sensorOrientation = sensor,
                                displayRotationDegrees = display,
                                frontFacing = front,
                                bufferWidth = 640,
                                bufferHeight = 480,
                                viewWidth = viewWidth,
                                viewHeight = viewHeight,
                                calibrationDegrees = calibration,
                                previewMirrored = mirror,
                            )
                            assertEquals(content, transform.contentSize)
                            val shown = textureViewDefaultStretch(640, 480, viewWidth, viewHeight)
                                .then(transform.matrix)
                            val jpeg = VideoPreviewGeometry.uprightImageAffine(
                                rotationDegrees = VideoPreviewGeometry.captureRotationDegrees(
                                    sensorOrientation = sensor,
                                    displayRotationDegrees = display,
                                    frontFacing = front,
                                    calibrationDegrees = calibration,
                                ),
                                mirror = mirror,
                                width = 640,
                                height = 480,
                            )
                            val tag = "sensor=$sensor display=$display front=$front calibration=$calibration mirror=$mirror"
                            val shownX = shown.mapVector(1f, 0f)
                            val shownY = shown.mapVector(0f, 1f)
                            val jpegX = jpeg.mapVector(1f, 0f)
                            val jpegY = jpeg.mapVector(0f, 1f)
                            val scale = kotlin.math.hypot(shownX.x, shownX.y) /
                                kotlin.math.hypot(jpegX.x, jpegX.y)
                            assertEquals(tag, scale * jpegX.x, shownX.x, 1e-3f)
                            assertEquals(tag, scale * jpegX.y, shownX.y, 1e-3f)
                            assertEquals(tag, scale * jpegY.x, shownY.x, 1e-3f)
                            assertEquals(tag, scale * jpegY.y, shownY.y, 1e-3f)
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 预览 / 抓帧尺寸配对：同一宽高比，优先 4:3
    // ------------------------------------------------------------------

    @Test
    fun `stream sizes prefer a matching four by three pair over larger wide sizes`() {
        val previews = listOf(
            PixelSize(1920, 1080),
            PixelSize(1280, 720),
            PixelSize(1280, 960),
            PixelSize(640, 480),
            PixelSize(320, 240),
        )
        val captures = listOf(
            PixelSize(1920, 1080),
            PixelSize(1280, 720),
            PixelSize(1280, 960),
            PixelSize(640, 480),
        )
        val chosen = requireNotNull(
            VideoPreviewGeometry.chooseCameraStreamSizes(previews, captures, maxEdge = 1280),
        )
        assertEquals(PixelSize(1280, 960), chosen.preview)
        assertEquals(PixelSize(1280, 960), chosen.capture)
        assertEquals(
            VideoPreviewGeometry.aspectRatio(chosen.preview.width, chosen.preview.height),
            VideoPreviewGeometry.aspectRatio(chosen.capture.width, chosen.capture.height),
            1e-6f,
        )
    }

    @Test
    fun `stream sizes pair different resolutions when they share an aspect`() {
        // 预览只有 640x480(4:3)，JPEG 有更大的 1280x960(4:3)：允许不同分辨率，只要比例相同。
        val chosen = VideoPreviewGeometry.chooseCameraStreamSizes(
            previewSizes = listOf(PixelSize(1280, 720), PixelSize(640, 480)),
            captureSizes = listOf(PixelSize(1280, 720), PixelSize(1280, 960)),
            maxEdge = 1280,
        )
        assertEquals(PixelSize(640, 480), chosen?.preview)
        assertEquals(PixelSize(1280, 960), chosen?.capture)
    }

    @Test
    fun `stream sizes fall back to a shared wide aspect when no four by three exists`() {
        val chosen = VideoPreviewGeometry.chooseCameraStreamSizes(
            previewSizes = listOf(PixelSize(1280, 720), PixelSize(960, 540)),
            captureSizes = listOf(PixelSize(1920, 1080), PixelSize(1280, 720)),
            maxEdge = 1280,
        )
        assertEquals(PixelSize(1280, 720), chosen?.preview)
        assertEquals(PixelSize(1280, 720), chosen?.capture)
    }

    @Test
    fun `stream sizes reject invalid and oversized entries`() {
        val chosen = VideoPreviewGeometry.chooseCameraStreamSizes(
            previewSizes = listOf(PixelSize(0, 480), PixelSize(4032, 3024), PixelSize(640, 480)),
            captureSizes = listOf(PixelSize(0, 0), PixelSize(4000, 3000), PixelSize(1280, 960)),
            maxEdge = 1280,
        )
        assertEquals(PixelSize(640, 480), chosen?.preview)
        assertEquals(PixelSize(1280, 960), chosen?.capture)
    }

    @Test
    fun `stream pairing rejects transposed axes and merely close ratios`() {
        val preview = listOf(PixelSize(640, 480))
        assertNull(VideoPreviewGeometry.chooseCameraStreamSizes(preview, listOf(PixelSize(480, 640))))
        assertNull(VideoPreviewGeometry.chooseCameraStreamSizes(preview, listOf(PixelSize(640, 482))))
        val exact = VideoPreviewGeometry.chooseCameraStreamSizes(
            preview,
            listOf(PixelSize(1280, 964), PixelSize(800, 600)),
        )
        assertEquals(PixelSize(800, 600), exact?.capture)
    }

    @Test
    fun `stream sizes return null when no common aspect exists`() {
        assertNull(
            VideoPreviewGeometry.chooseCameraStreamSizes(
                previewSizes = listOf(PixelSize(640, 480)),
                captureSizes = listOf(PixelSize(1280, 720)),
            ),
        )
        assertNull(VideoPreviewGeometry.chooseCameraStreamSizes(emptyList(), listOf(PixelSize(640, 480))))
        assertNull(VideoPreviewGeometry.chooseCameraStreamSizes(listOf(PixelSize(640, 480)), emptyList()))
        assertNull(
            VideoPreviewGeometry.chooseCameraStreamSizes(
                listOf(PixelSize(640, 480)),
                listOf(PixelSize(640, 480)),
                maxEdge = 0,
            ),
        )
    }

    // ------------------------------------------------------------------
    // Round1 相机兼容：同比例优先、只有大 JPEG 时的有界兜底、无同比例时保留预览
    // ------------------------------------------------------------------

    @Test
    fun `stream plan picks the smallest larger same aspect jpeg when none fits the preview limit`() {
        val plan = requireNotNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = listOf(PixelSize(1280, 720), PixelSize(640, 480)),
                captureSizes = listOf(
                    PixelSize(3840, 2160),
                    PixelSize(1920, 1080),
                    PixelSize(2560, 1440),
                ),
            ),
        )
        assertEquals(PixelSize(1280, 720), plan.preview)
        // 只有 16:9 能配对：选最小的更大输出 1920x1080，而不是 4K/2K。
        assertEquals(PixelSize(1920, 1080), plan.capture)
        assertTrue(plan.captureEnabled)
        assertEquals("", plan.captureDisabledReason)
        val capture = requireNotNull(plan.capture)
        assertTrue("兜底 JPEG 必须大于预览上限", capture.maxEdge > VideoPreviewGeometry.PREVIEW_MAX_EDGE)
        assertTrue(
            capture.width.toLong() * capture.height <=
                VideoPreviewGeometry.CAPTURE_MAX_PIXELS.toLong(),
        )
        // 比例必须精确相等：所见即所发不允许近似比例。
        assertEquals(
            VideoPreviewGeometry.aspectRatio(plan.preview.width, plan.preview.height),
            VideoPreviewGeometry.aspectRatio(capture.width, capture.height),
            1e-6f,
        )
    }

    @Test
    fun `stream plan chooses the smallest bounded fallback not the largest jpeg`() {
        val plan = requireNotNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = listOf(PixelSize(640, 480)),
                captureSizes = listOf(
                    PixelSize(4000, 3000),
                    PixelSize(1600, 1200),
                    PixelSize(2048, 1536),
                ),
            ),
        )
        assertEquals(PixelSize(640, 480), plan.preview)
        // 1600x1200 在该比例下是唯一落在 1080p 级预算内的更大输出；
        // 2048x1536(3.1MP) 与 4000x3000(12MP) 都必须被拒绝（整张解码会 OOM）。
        assertEquals(PixelSize(1600, 1200), plan.capture)
    }

    @Test
    fun `stream plan keeps preview and disables capture when every matching jpeg is too large`() {
        val plan = requireNotNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = listOf(PixelSize(1280, 960)),
                captureSizes = listOf(
                    PixelSize(1920, 1440),
                    PixelSize(3264, 2448),
                    PixelSize(4000, 3000),
                ),
            ),
        )
        assertEquals(PixelSize(1280, 960), plan.preview)
        assertNull("超预算的同比例 JPEG 不能当成可用抓帧", plan.capture)
        assertFalse(plan.captureEnabled)
        assertEquals(
            VideoPreviewGeometry.CAPTURE_DISABLED_OVERSIZED_CAPTURE,
            plan.captureDisabledReason,
        )
    }

    @Test
    fun `stream plan keeps preview and never approximates when no aspect is shared`() {
        val plan = requireNotNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = listOf(PixelSize(1280, 720), PixelSize(960, 540)),
                captureSizes = listOf(PixelSize(1280, 960), PixelSize(640, 480)),
            ),
        )
        // 没有共同比例：保留面积最大的预览，但绝不把 16:9 的预览配给 4:3 的 JPEG。
        assertEquals(PixelSize(1280, 720), plan.preview)
        assertNull(plan.capture)
        assertEquals(
            VideoPreviewGeometry.CAPTURE_DISABLED_NO_MATCHING_ASPECT,
            plan.captureDisabledReason,
        )
    }

    @Test
    fun `preview only plan keeps a valid preview geometry without any jpeg`() {
        val plan = requireNotNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = listOf(PixelSize(1280, 720)),
                captureSizes = listOf(PixelSize(1280, 960)),
            ),
        )
        assertNull("4:3 JPEG 不能配给 16:9 预览", plan.capture)
        // 停用抓帧不能影响预览几何：正立内容的宽高比必须仍然等于选中预览的宽高比。
        val transform = VideoPreviewGeometry.textureViewTransform(
            sensorOrientation = 90,
            displayRotationDegrees = 0,
            frontFacing = false,
            bufferWidth = plan.preview.width,
            bufferHeight = plan.preview.height,
            viewWidth = 480,
            viewHeight = 640,
        )
        assertTrue(transform.contentSize.width > 0 && transform.contentSize.height > 0)
        assertEquals(
            VideoPreviewGeometry.aspectRatio(plan.preview.width, plan.preview.height),
            VideoPreviewGeometry.aspectRatio(transform.contentSize.width, transform.contentSize.height),
            1e-6f,
        )
    }

    @Test
    fun `stream plan prefers a within limit jpeg over a larger fallback`() {
        val plan = requireNotNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = listOf(PixelSize(640, 480)),
                captureSizes = listOf(PixelSize(1600, 1200), PixelSize(1280, 960)),
            ),
        )
        assertEquals(PixelSize(640, 480), plan.preview)
        // 有 <=1280 的同比例输出时就用它，不必去解一张更大的图。
        assertEquals(PixelSize(1280, 960), plan.capture)
        // 严格入口（只允许两边都 <=1280）也不会去选那张更大的 1600x1200。
        assertEquals(
            PixelSize(1280, 960),
            VideoPreviewGeometry.chooseCameraStreamSizes(
                previewSizes = listOf(PixelSize(640, 480)),
                captureSizes = listOf(PixelSize(1600, 1200), PixelSize(1280, 960)),
            )?.capture,
        )
    }

    @Test
    fun `stream plan never falls back to a merely close or transposed aspect`() {
        val exact = requireNotNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = listOf(PixelSize(640, 480)),
                captureSizes = listOf(PixelSize(1920, 1080), PixelSize(1600, 1200)),
            ),
        )
        // 1600x1200 是精确 4:3；1920x1080 只是"接近"，不能拿来兜底。
        assertEquals(PixelSize(1600, 1200), exact.capture)
        assertNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = listOf(PixelSize(640, 480)),
                captureSizes = listOf(PixelSize(1920, 1080)),
            )?.capture,
        )
        // 横竖互换也不是同一个 sensor 轴，不能配对。
        assertNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = listOf(PixelSize(480, 640)),
                captureSizes = listOf(PixelSize(1600, 1200)),
            )?.capture,
        )
    }

    @Test
    fun `stream plan prefers a capturable preview over a larger preview only one`() {
        val plan = requireNotNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = listOf(PixelSize(1280, 720), PixelSize(640, 480)),
                captureSizes = listOf(PixelSize(640, 480)),
            ),
        )
        // 4:3 能配对，就选 640x480 的预览 + 抓帧；不是保留 1280x720 预览却停用抓帧。
        assertEquals(PixelSize(640, 480), plan.preview)
        assertEquals(PixelSize(640, 480), plan.capture)
    }

    @Test
    fun `stream plan needs a preview even when a matching jpeg exists`() {
        assertNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = emptyList(),
                captureSizes = listOf(PixelSize(640, 480)),
            ),
        )
        assertNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = listOf(PixelSize(1920, 1080)),
                captureSizes = listOf(PixelSize(1280, 720)),
            ),
        )
        assertNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = listOf(PixelSize(640, 480)),
                captureSizes = listOf(PixelSize(640, 480)),
                previewMaxEdge = 0,
            ),
        )
    }

    @Test
    fun `preview card aspect and capture jpeg aspect agree for every orientation`() {
        val pair = VideoPreviewGeometry.chooseCameraStreamSizes(
            previewSizes = listOf(PixelSize(1280, 960)),
            captureSizes = listOf(PixelSize(1280, 960)),
        )!!
        listOf(0, 90, 180, 270).forEach { sensor ->
            listOf(0, 90, 180, 270).forEach { display ->
                listOf(false, true).forEach { front ->
                    listOf(0, 90, 180, 270).forEach { calibration ->
                        val rotation = VideoPreviewGeometry.uprightRotation(sensor, display, front)
                        val previewTransform = VideoPreviewGeometry.textureViewTransform(
                            sensorOrientation = sensor,
                            displayRotationDegrees = display,
                            frontFacing = front,
                            bufferWidth = pair.preview.width,
                            bufferHeight = pair.preview.height,
                            viewWidth = 480,
                            viewHeight = 640,
                            calibrationDegrees = calibration,
                        )
                        val captureRotation = VideoPreviewGeometry.captureRotationDegrees(
                            sensorOrientation = sensor,
                            displayRotationDegrees = display,
                            frontFacing = front,
                            calibrationDegrees = calibration,
                        )
                        val captureSize = VideoPreviewGeometry.uprightSize(
                            pair.capture.width,
                            pair.capture.height,
                            captureRotation,
                        )
                        assertEquals(
                            "sensor=$sensor display=$display front=$front calibration=$calibration",
                            VideoPreviewGeometry.aspectRatio(captureSize.width, captureSize.height),
                            VideoPreviewGeometry.aspectRatio(
                                previewTransform.contentSize.width,
                                previewTransform.contentSize.height,
                            ),
                            1e-4f,
                        )
                        assertEquals(
                            VideoPreviewGeometry.normalizeDegrees(rotation + calibration),
                            captureRotation,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `device default is narrow user reported guidance not a global OPPO fix`() {
        assertEquals(90, PreviewCalibration.deviceDefault("OPPO", "Reno14 Pro", 36))
        assertEquals(90, PreviewCalibration.deviceDefault("oppo", "OPPO Reno14 Pro", 36))
        assertEquals(0, PreviewCalibration.deviceDefault("OPPO", "UNKNOWN-HARDWARE-CODE", 36))
        assertEquals(0, PreviewCalibration.deviceDefault("OPPO", "Reno14", 36))
        assertEquals(0, PreviewCalibration.deviceDefault("other", "Reno14 Pro", 36))
        assertEquals(0, PreviewCalibration.deviceDefault("OPPO", "Reno14 Pro", 35))
        assertEquals(0, PreviewCalibration.deviceDefault("OPPO", "Reno14 Pro", 37))
    }

    /** 实际调用生产 toAndroidMatrix 桥接，独立用 Android Matrix 拼合 M S 并逆映射 view。
     * 这验证矩阵布局/拼接/像素几何，不模拟 TextureView 的 framework/OEM 渲染管线。
     */
    @Test
    fun `android matrix bridge covers every calibrated view without stretch or holes`() {
        val buffers = listOf(1280 to 720, 640 to 480, 720 to 1280)
        val views = listOf(1080 to 1920, 1080 to 2400, 2400 to 1080, 800 to 800)
        for ((bw, bh) in buffers) for ((vw, vh) in views) {
            for (rotation in listOf(0, 90, 180, 270)) for (front in listOf(false, true)) {
                for (calibration in listOf(0, 90, 180, 270)) for (mirror in listOf(false, true)) {
                    val displayRotation = 0
                    val transform = VideoPreviewGeometry.textureViewTransform(
                        rotation, displayRotation, front, bw, bh, vw, vh, calibration, previewMirrored = mirror,
                    )
                    val stretch = Matrix().apply { setScale(vw.toFloat() / bw, vh.toFloat() / bh) }
                    val shown = Matrix().apply {
                        setConcat(transform.matrix.toAndroidMatrix(), stretch)
                    }
                    val tag = "buffer=${bw}x$bh view=${vw}x$vh r=$rotation front=$front c=$calibration mirror=$mirror"
                    val finalRotation = (rotation + calibration) % 360
                    val widthAfterRotation = if (finalRotation % 180 == 0) bw else bh
                    val heightAfterRotation = if (finalRotation % 180 == 0) bh else bw
                    val scale = maxOf(vw.toFloat() / widthAfterRotation, vh.toFloat() / heightAfterRotation)
                    val vectors = floatArrayOf(1f, 0f, 0f, 1f)
                    shown.mapVectors(vectors)
                    assertEquals(tag, scale, kotlin.math.hypot(vectors[0], vectors[1]), delta)
                    assertEquals(tag, scale, kotlin.math.hypot(vectors[2], vectors[3]), delta)
                    assertEquals(tag, 0f, vectors[0] * vectors[2] + vectors[1] * vectors[3], delta)
                    val determinant = vectors[0] * vectors[3] - vectors[1] * vectors[2]
                    assertEquals(tag, mirror, determinant < 0f)

                    // 覆盖不是只比角点包围盒：逆映射 view 四角和边中点必须全部位于源buffer内。
                    val inverse = Matrix()
                    assertTrue(tag, shown.invert(inverse))
                    val viewPoints = floatArrayOf(
                        0f, 0f, vw.toFloat(), 0f, vw.toFloat(), vh.toFloat(), 0f, vh.toFloat(),
                        vw / 2f, 0f, vw / 2f, vh.toFloat(), 0f, vh / 2f, vw.toFloat(), vh / 2f,
                    )
                    inverse.mapPoints(viewPoints)
                    for (index in viewPoints.indices step 2) {
                        assertTrue(tag, viewPoints[index] >= -delta && viewPoints[index] <= bw + delta)
                        assertTrue(tag, viewPoints[index + 1] >= -delta && viewPoints[index + 1] <= bh + delta)
                    }
                    val centre = floatArrayOf(bw / 2f, bh / 2f)
                    shown.mapPoints(centre)
                    assertEquals(tag, vw / 2f, centre[0], delta)
                    assertEquals(tag, vh / 2f, centre[1], delta)

                    // 独立整数公式：先合并旋转，再镜像屏幕 x；绝不在校准之前翻转。
                    val rotatedRight = when (finalRotation) {
                        0 -> Point2(1f, 0f)
                        90 -> Point2(0f, 1f)
                        180 -> Point2(-1f, 0f)
                        else -> Point2(0f, -1f)
                    }
                    val expectedRight = Point2(
                        if (mirror) -rotatedRight.x else rotatedRight.x,
                        rotatedRight.y,
                    )
                    assertEquals(tag, expectedRight.x * scale, vectors[0], delta)
                    assertEquals(tag, expectedRight.y * scale, vectors[1], delta)
                }
            }
        }
    }

    @Test
    fun `android matrix bridge preserves asymmetric affine layout and translation`() {
        val mapped = floatArrayOf(7f, 11f)
        Affine(a = 2f, b = 3f, c = 5f, d = 7f, e = 11f, f = 13f)
            .toAndroidMatrix().mapPoints(mapped)
        assertEquals(80f, mapped[0], delta)
        assertEquals(111f, mapped[1], delta)
    }

    private fun assertPoint(expectedX: Float, expectedY: Float, actual: Point2) {
        assertEquals("x", expectedX, actual.x, delta)
        assertEquals("y", expectedY, actual.y, delta)
    }

    /**
     * 四个映射点必须一一命中视图四角（顺序不计）：说明整张源图正好铺满视图，
     * 既没有被 cover 裁掉，也没有超出去或留下黑边。
     */
    private fun assertCornersLandOnView(
        mapped: List<Point2>,
        viewWidth: Int,
        viewHeight: Int,
        tag: String,
    ) {
        val expected = listOf(
            Point2(0f, 0f),
            Point2(viewWidth.toFloat(), 0f),
            Point2(viewWidth.toFloat(), viewHeight.toFloat()),
            Point2(0f, viewHeight.toFloat()),
        )
        assertEquals("$tag 映射点数量", expected.size, mapped.size)
        val remaining = mapped.toMutableList()
        expected.forEach { want ->
            val hit = remaining.firstOrNull {
                kotlin.math.abs(it.x - want.x) <= delta && kotlin.math.abs(it.y - want.y) <= delta
            } ?: run {
                assertTrue("$tag 缺少视图角 (${want.x},${want.y})，实际映射=$mapped", false)
                return@forEach
            }
            remaining.remove(hit)
        }
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

    @Test
    fun `fit preview keeps the whole buffer inside a 4x3 view without stretching`() {
        val buffers = listOf(1280 to 720, 640 to 480, 480 to 640, 1080 to 1920)
        val views = listOf(800 to 600, 810 to 607)
        for ((bw, bh) in buffers) for ((vw, vh) in views) {
            for (rotation in listOf(0, 90, 180, 270)) for (calibration in listOf(0, 90)) {
                val transform = VideoPreviewGeometry.textureViewTransform(
                    sensorOrientation = rotation,
                    displayRotationDegrees = 0,
                    frontFacing = false,
                    bufferWidth = bw,
                    bufferHeight = bh,
                    viewWidth = vw,
                    viewHeight = vh,
                    calibrationDegrees = calibration,
                    previewMirrored = false,
                    fit = true,
                )
                val stretch = Matrix().apply { setScale(vw.toFloat() / bw, vh.toFloat() / bh) }
                val shown = Matrix().apply {
                    setConcat(transform.matrix.toAndroidMatrix(), stretch)
                }
                val tag = "buffer=${bw}x$bh view=${vw}x$vh r=$rotation c=$calibration"
                val vectors = floatArrayOf(1f, 0f, 0f, 1f)
                shown.mapVectors(vectors)
                // Both axes must use the same scale: no horizontal/vertical compression.
                assertEquals(tag, kotlin.math.hypot(vectors[0], vectors[1]),
                    kotlin.math.hypot(vectors[2], vectors[3]), 0.001f)
                val corners = floatArrayOf(
                    0f, 0f, bw.toFloat(), 0f, bw.toFloat(), bh.toFloat(), 0f, bh.toFloat(),
                )
                shown.mapPoints(corners)
                for (index in corners.indices step 2) {
                    assertTrue("$tag 左边越界 x=${corners[index]}", corners[index] >= -0.01f)
                    assertTrue("$tag 上边越界 y=${corners[index + 1]}", corners[index + 1] >= -0.01f)
                    assertTrue("$tag 右边越界 x=${corners[index]}", corners[index] <= vw + 0.01f)
                    assertTrue("$tag 下边越界 y=${corners[index + 1]}", corners[index + 1] <= vh + 0.01f)
                }
            }
        }
    }

    @Test
    fun `portrait fit calibration has four distinct clockwise corner positions`() {
        // Reported regression: landscape buffer, sensor 90. Shape-based rotation used to
        // collapse calibration 0/270 into 90 degrees and 90/180 into 270 degrees.
        val expected = listOf(
            Point2(300f, 0f),
            Point2(300f, 312.5f),
            Point2(0f, 400f),
            Point2(0f, 87.5f),
        )
        val positions = listOf(0, 90, 180, 270).mapIndexed { index, calibration ->
            val transform = VideoPreviewGeometry.textureViewTransform(
                90, 0, false, 640, 480, 300, 400,
                calibrationDegrees = calibration, previewMirrored = false, fit = true,
            )
            val shown = textureViewDefaultStretch(640, 480, 300, 400).then(transform.matrix)
            val corner = shown.map(0f, 0f)
            assertPoint(expected[index].x, expected[index].y, corner)
            corner
        }
        assertEquals(4, positions.toSet().size)
    }

    @Test
    fun `fit preview and capture map asymmetric points identically after uniform fit`() {
        for ((bw, bh) in listOf(640 to 480, 480 to 640)) {
            for (sensor in listOf(0, 90, 180, 270)) for (display in listOf(0, 90, 180, 270)) {
                for (front in listOf(false, true)) for (calibration in listOf(0, 90, 180, 270)) {
                    for (mirror in listOf(false, true)) {
                        val transform = VideoPreviewGeometry.textureViewTransform(
                            sensor, display, front, bw, bh, 300, 400, calibration,
                            previewMirrored = mirror, fit = true,
                        )
                        val shown = textureViewDefaultStretch(bw, bh, 300, 400).then(transform.matrix)
                        // Independent quarter-turn arithmetic and integer corner mapping.
                        val angle = ((if (front) sensor + display else sensor - display) + calibration + 720) % 360
                        assertEquals(angle, VideoPreviewGeometry.captureRotationDegrees(
                            sensor, display, front, calibration,
                        ))
                        val width = if (angle % 180 == 0) bw else bh
                        val height = if (angle % 180 == 0) bh else bw
                        val scale = minOf(300f / width, 400f / height)
                        val jpeg = VideoPreviewGeometry.uprightImageAffine(angle, mirror, bw, bh)
                        val points = listOf(Point2(0f, 0f), Point2(bw.toFloat(), 0f),
                            Point2(0f, bh.toFloat()), Point2(bw * 0.23f, bh * 0.61f))
                        for (point in points) {
                            val rotated = when (angle) {
                                0 -> point
                                90 -> Point2(bh - point.y, point.x)
                                180 -> Point2(bw - point.x, bh - point.y)
                                else -> Point2(point.y, bw - point.x)
                            }
                            val x = if (mirror) width - rotated.x else rotated.x
                            assertPoint(x, rotated.y, jpeg.map(point))
                            assertPoint(
                                (300f - width * scale) / 2f + x * scale,
                                (400f - height * scale) / 2f + rotated.y * scale,
                                shown.map(point),
                            )
                        }
                    }
                }
            }
        }
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
