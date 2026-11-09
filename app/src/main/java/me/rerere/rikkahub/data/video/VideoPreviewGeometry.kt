/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import kotlin.math.cos
import kotlin.math.sin

/** 二维浮点坐标（图像像素 / 视图像素都用它）。纯值类型，不依赖 Android。 */
data class Point2(val x: Float, val y: Float)

/**
 * 2D 仿射变换，语义与 `android.graphics.Matrix` 的前两行完全一致（y 轴向下）：
 *
 * ```
 * x' = a*x + c*y + e
 * y' = b*x + d*y + f
 * ```
 *
 * 单测直接对**具体像素点**做映射，因此"转错方向 / 转两次 / 非等比拉伸"这类
 * 只在真机上才看得出来的错误，可以在 JVM 上钉死。
 */
data class Affine(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val e: Float = 0f,
    val f: Float = 0f,
) {
    fun map(x: Float, y: Float): Point2 = Point2(a * x + c * y + e, b * x + d * y + f)

    fun map(point: Point2): Point2 = map(point.x, point.y)

    /** 先做 `this`，再做 [next]。 */
    fun then(next: Affine): Affine = Affine(
        a = next.a * a + next.c * b,
        b = next.b * a + next.d * b,
        c = next.a * c + next.c * d,
        d = next.b * c + next.d * d,
        e = next.a * e + next.c * f + next.e,
        f = next.b * e + next.d * f + next.f,
    )

    /** 只取线性部分作用在向量上（不含平移），用来验证"等比"。 */
    fun mapVector(x: Float, y: Float): Point2 = Point2(a * x + c * y, b * x + d * y)

    /** 线性部分的统一缩放系数；非等比时返回 NaN。 */
    val uniformScale: Float
        get() {
            val xAxis = mapVector(1f, 0f)
            val yAxis = mapVector(0f, 1f)
            val sx = kotlin.math.hypot(xAxis.x, xAxis.y)
            val sy = kotlin.math.hypot(yAxis.x, yAxis.y)
            val dot = xAxis.x * yAxis.x + xAxis.y * yAxis.y
            return if (kotlin.math.abs(sx - sy) <= 1e-3f && kotlin.math.abs(dot) <= 1e-3f) sx else Float.NaN
        }

    companion object {
        val IDENTITY = Affine()

        fun translate(dx: Float, dy: Float): Affine = Affine(e = dx, f = dy)

        fun scale(sx: Float, sy: Float): Affine = Affine(a = sx, d = sy)

        fun scale(scale: Float): Affine = scale(scale, scale)

        /** 顺时针旋转（Android 的 y 轴向下，所以正角度=顺时针）。 */
        fun rotateDegrees(degrees: Int): Affine {
            val radians = Math.toRadians(degrees.toDouble())
            val cosValue = cos(radians).toFloat()
            val sinValue = sin(radians).toFloat()
            return Affine(a = cosValue, b = sinValue, c = -sinValue, d = cosValue)
        }

        /** 以 [centerX],[centerY] 为不动点旋转。 */
        fun rotateAround(degrees: Int, centerX: Float, centerY: Float): Affine =
            translate(-centerX, -centerY).then(rotateDegrees(degrees)).then(translate(centerX, centerY))

        /** 以 [centerX],[centerY] 为不动点水平镜像。 */
        fun mirrorAround(centerX: Float, centerY: Float): Affine =
            translate(-centerX, -centerY).then(scale(-1f, 1f)).then(translate(centerX, centerY))
    }
}

/**
 * 预览层最终要交给 `TextureView.setTransform` 的变换。
 *
 * [matrix] 是**视图坐标系**下的完整矩阵（含抵消 TextureView 默认拉伸的部分），
 * UI 层直接 `setValues` 即可；[rotationDegrees]/[mirrorHorizontally] 只用于日志与测试断言。
 */
data class PreviewTransform(
    val rotationDegrees: Int = 0,
    val mirrorHorizontally: Boolean = false,
    val matrix: Affine = Affine.IDENTITY,
)

/**
 * 相机预览 / 抓帧 / EXIF 的方向计算。
 *
 * camera2 的 buffer 永远是 sensor 方向，`SurfaceView`/`TextureView` 不会替你转，
 * 所以旋转必须由应用显式做。这里把全部几何算成**纯数据**，三处共用同一套数学：
 *
 * 1. `TextureView.setTransform`（预览矩阵，见 [textureViewTransform]）；
 * 2. 抓帧 JPEG 的落盘归一化（见 [uprightImageAffine]）；
 * 3. 抓帧请求本身（`JPEG_ORIENTATION` 固定请求 0，见 [REQUESTED_JPEG_ORIENTATION]）。
 *
 * 角度公式（Android 官方 `setCameraDisplayOrientation` 的等价形式）：
 * - 后摄：`(sensor - display + 360) % 360`；
 * - 前摄：`(sensor + display) % 360`，并且预览/照片水平镜像。
 */
object VideoPreviewGeometry {
    /**
     * 抓帧请求里给 HAL 的 `JPEG_ORIENTATION`。
     *
     * **固定 0**：camera2 允许 HAL 自己旋转像素，也允许只写 EXIF 标签；如果我们请求 90
     * 同时又按 90 转一次，在"真的转了像素"的机型上就会**双转**（旧实现正是这样）。
     * 请求 0 等于告诉 HAL"什么都别做"，像素保持 sensor 原始朝向，
     * 旋转只由 [uprightImageAffine] 施加一次，而且我们重新编码时会丢掉 EXIF，
     * 所以落盘文件的方向是唯一确定的。
     */
    const val REQUESTED_JPEG_ORIENTATION: Int = 0

    /**
     * `Surface.ROTATION_*` / `Display.getRotation()` 常量 → 角度（REVIEW_R5 §4）。
     *
     * 故意只用 Int 而不是 `android.view.Surface` 常量：这样这段映射是纯函数，
     * 可以在 JVM 上把 0/90/180/270 与"未知值退回 0"逐条钉住。
     * 对应关系与 Android 一致：`ROTATION_0 = 0`、`ROTATION_90 = 1`、
     * `ROTATION_180 = 2`、`ROTATION_270 = 3`。
     */
    fun displayRotationDegrees(surfaceRotation: Int): Int = when (surfaceRotation) {
        1 -> 90
        2 -> 180
        3 -> 270
        else -> 0
    }

    /** 把任意角度归一化到 0/90/180/270。 */
    fun normalizeDegrees(degrees: Int): Int {
        val normalized = ((degrees % 360) + 360) % 360
        return when {
            normalized < 45 -> 0
            normalized < 135 -> 90
            normalized < 225 -> 180
            normalized < 315 -> 270
            else -> 0
        }
    }

    /** 把画面转正需要顺时针旋转的角度。 */
    fun uprightRotation(
        sensorOrientation: Int,
        displayRotationDegrees: Int,
        frontFacing: Boolean,
    ): Int {
        val sensor = normalizeDegrees(sensorOrientation)
        val display = normalizeDegrees(displayRotationDegrees)
        val degrees = if (frontFacing) {
            (sensor + display) % 360
        } else {
            (sensor - display + 360) % 360
        }
        return normalizeDegrees(degrees)
    }

    /** 旋转 90/270 之后宽高会互换。 */
    fun swapped(width: Int, height: Int, rotationDegrees: Int): Boolean =
        rotationDegrees % 180 != 0

    /**
     * 抓帧落盘时的**唯一一次**归一化：sensor 原始像素 → 正立图像。
     *
     * 输出图像的尺寸由 [uprightSize] 给出（90/270 时宽高互换）。
     * 前摄在这里镜像，保证"预览里看到的自己"和"照片里的自己"是同一侧。
     */
    fun uprightImageAffine(
        rotationDegrees: Int,
        mirror: Boolean,
        width: Int,
        height: Int,
    ): Affine {
        if (width <= 0 || height <= 0) return Affine.IDENTITY
        val rotation = normalizeDegrees(rotationDegrees)
        val target = uprightSize(width, height, rotation)
        val centerX = width / 2f
        val centerY = height / 2f
        val targetCenterX = target.width / 2f
        val targetCenterY = target.height / 2f
        var matrix = Affine.translate(-centerX, -centerY)
            .then(Affine.rotateDegrees(rotation))
            .then(Affine.translate(targetCenterX, targetCenterY))
        if (mirror) {
            // 镜像在**正立之后**做：翻转的是用户看到的那一维。
            matrix = matrix.then(Affine.mirrorAround(targetCenterX, targetCenterY))
        }
        return matrix
    }

    fun uprightSize(width: Int, height: Int, rotationDegrees: Int): PixelSize =
        if (swapped(width, height, normalizeDegrees(rotationDegrees))) {
            PixelSize(width = height, height = width)
        } else {
            PixelSize(width = width, height = height)
        }

    /**
     * 预览矩阵。
     *
     * 关键点（REVIEW_R4 §5）：`TextureView` 默认就会把 buffer **拉伸**到 view 大小
     * （相当于先做 `S = diag(viewW/bufferW, viewH/bufferH)`），所以在默认拉伸之上再
     * "postRotate + 按像素比放一个等比 scale"是**消除不了**这次拉伸的，反而会留下黑边或压扁。
     *
     * 正确做法：先算出想要的最终画面 [visibleAffine]（buffer 坐标 → view 坐标，
     * 旋转 + 等比 centerCrop + 镜像），再左乘 `S⁻¹` 把那层默认拉伸抵消掉：
     * `setTransform` 拿到的矩阵 M 必须满足 `M ∘ S = F`。
     *
     * 前提是调用方用 `setDefaultBufferSize` 把 buffer 尺寸设成相机真实输出尺寸
     * （否则 `S` 就不是这里的 `diag(vw/bw, vh/bh)`，两者必须一致）。
     */
    fun visibleAffine(
        rotationDegrees: Int,
        mirror: Boolean,
        bufferWidth: Int,
        bufferHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): Affine {
        if (bufferWidth <= 0 || bufferHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return Affine.IDENTITY
        }
        val rotation = normalizeDegrees(rotationDegrees)
        val visible = uprightSize(bufferWidth, bufferHeight, rotation)
        // 等比填满：取两个方向缩放比的较大者，多出来的部分被裁掉（不拉伸、不留黑边）。
        val scale = maxOf(
            viewWidth.toFloat() / visible.width.toFloat(),
            viewHeight.toFloat() / visible.height.toFloat(),
        )
        val bufferCenterX = bufferWidth / 2f
        val bufferCenterY = bufferHeight / 2f
        val viewCenterX = viewWidth / 2f
        val viewCenterY = viewHeight / 2f
        var matrix = Affine.translate(-bufferCenterX, -bufferCenterY)
            .then(Affine.scale(scale))
            .then(Affine.rotateDegrees(rotation))
            .then(Affine.translate(viewCenterX, viewCenterY))
        if (mirror) {
            matrix = matrix.then(Affine.mirrorAround(viewCenterX, viewCenterY))
        }
        return matrix
    }

    /** 给 `TextureView.setTransform` 的矩阵 = 想要的画面 ∘ 默认拉伸的逆。 */
    fun textureViewAffine(
        rotationDegrees: Int,
        mirror: Boolean,
        bufferWidth: Int,
        bufferHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): Affine {
        val visible = visibleAffine(
            rotationDegrees = rotationDegrees,
            mirror = mirror,
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
        )
        if (bufferWidth <= 0 || bufferHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return visible
        val textureViewDefaultStretch = Affine.scale(
            viewWidth.toFloat() / bufferWidth.toFloat(),
            viewHeight.toFloat() / bufferHeight.toFloat(),
        )
        // 先抵消默认拉伸（view → buffer），再套上想要的画面（buffer → view）。
        return textureViewDefaultStretch.inverse().then(visible)
    }

    fun textureViewTransform(
        sensorOrientation: Int,
        displayRotationDegrees: Int,
        frontFacing: Boolean,
        bufferWidth: Int,
        bufferHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): PreviewTransform {
        val rotation = uprightRotation(sensorOrientation, displayRotationDegrees, frontFacing)
        return PreviewTransform(
            rotationDegrees = rotation,
            mirrorHorizontally = frontFacing,
            matrix = textureViewAffine(
                rotationDegrees = rotation,
                mirror = frontFacing,
                bufferWidth = bufferWidth,
                bufferHeight = bufferHeight,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
            ),
        )
    }

    private fun Affine.inverse(): Affine {
        val determinant = a * d - b * c
        if (determinant == 0f) return Affine.IDENTITY
        val inverseA = d / determinant
        val inverseB = -b / determinant
        val inverseC = -c / determinant
        val inverseD = a / determinant
        return Affine(
            a = inverseA,
            b = inverseB,
            c = inverseC,
            d = inverseD,
            e = -(inverseA * e + inverseC * f),
            f = -(inverseB * e + inverseD * f),
        )
    }
}
