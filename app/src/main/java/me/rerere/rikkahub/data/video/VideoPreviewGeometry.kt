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
 * [matrix] 是**视图坐标系**下的完整矩阵（含抵消 TextureView 默认拉伸的部分 + 用户校准），
 * UI 层直接 `setValues` 即可；[rotationDegrees]/[calibrationDegrees]/[mirrorHorizontally]
 * 只用于日志与测试断言（**不参与计算**）。
 */
data class PreviewTransform(
    /** 由 sensor/display 推出来的角度（不含用户校准）。 */
    val rotationDegrees: Int = 0,
    /** 用户手动校准的角度（只影响预览，不影响抓帧 JPEG 的方向）。 */
    val calibrationDegrees: Int = 0,
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
 * - 前摄：`(sensor + display) % 360`；预览镜像独立可选，通话 JPEG 不镜像以保留文字方向。
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
     * mirror 仅由图像调用方指定；通话 JPEG 传 false，不随用户预览镜像变化。
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
     * 预览矩阵的**几何部分**（不含用户校准）。
     *
     * `TextureView` 默认会把 buffer **拉伸**到 view 大小（相当于先做
     * `S = diag(viewW/bufferW, viewH/bufferH)`）。这里算出"想要的最终画面"
     * [visibleAffine]（buffer 坐标 → view 坐标），再右乘 `S⁻¹` 把那层默认拉伸抵消掉：
     * `setTransform` 拿到的矩阵 M 满足
     *
     * ```
     * M ∘ S = F      （可以断言、也有测试逐点验证）
     * ```
     *
     * 从 `M ∘ S = F` 可得 `M = F ∘ S⁻¹`（函数复合从右向左执行）。
     * 不能写成 `S⁻¹ ∘ F`；矩阵一般不可交换。这里关于屏幕最终画面 `M ∘ S = F` 的描述，
     * 仍以 TextureView 实际复合顺序为前提。
     *
     * 该前提在本轮**没有**通过 AOSP framework 源码或真机渲染验证。
     * Android Matrix 桥接测试只能验证给定复合顺序下的布局与几何，不能证明 TextureView
     * 或 OEM 生产者实际采用了此顺序；仍须在目标设备及原本正确的设备上验收。
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

    /** 给 `TextureView.setTransform` 的矩阵 = 想要的画面 ∘ 默认拉伸的逆（即 `M ∘ S = F`）。 */
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
        /**
         * 用户手动校准角度（只作用于**预览**）。
         *
         * 这是"几何算完之后的最后一道人工修正"：真机（OPPO/ColorOS）上画面差 90° 时，
         * 用户点一下"右转"就能转正，不依赖我们对 framework 复合顺序的推断是否正确。
         * 抓帧 JPEG 的方向**不包含**这个角度（见 [captureRotationDegrees]）。
         */
        calibrationDegrees: Int = 0,
        previewMirrored: Boolean = frontFacing,
    ): PreviewTransform {
        val rotation = uprightRotation(sensorOrientation, displayRotationDegrees, frontFacing)
        val base = textureViewAffine(
            rotationDegrees = rotation,
            mirror = false,
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
        )
        return PreviewTransform(
            rotationDegrees = rotation,
            calibrationDegrees = PreviewCalibration.normalize(calibrationDegrees),
            mirrorHorizontally = previewMirrored,
            // Flip last, after calibration rotation: left/right always means screen space.
            matrix = withPreviewCalibration(base, calibrationDegrees, viewWidth, viewHeight).let {
                if (previewMirrored) it.then(Affine.mirrorAround(viewWidth / 2f, viewHeight / 2f)) else it
            },
        )
    }

    /**
     * 把"用户校准"叠到已经算好的预览矩阵上。
     *
     * 输入必须是 [textureViewAffine] 的完整未裁剪矩阵 M，满足 `M S = F`。
     * S 将整个 buffer 映射到 `[0, viewWidth] × [0, viewHeight]`；所以将这个矩形的四角
     * 经 M、再经校准旋转 R 映射，即可得到完整源图的旋转后边界（不是 view 已裁剪的截图）。
     * 对该边界重新求统一 centerCrop 缩放 K 和居中平移，输出 `M' = K R M`，
     * 则 `M' S = K R F`：只转预览、铺满视图且不拉伸。90/270 后宽高互换，
     * 仅绕中心旋转而不重算 cover 会在非方形 view 中露出空白。
     *
     * 所有旋转均归一化为直角，因此旋转后的源图仍是轴对齐矩形；此处包围盒就是源图边界。
     * K 可小于 1：重新利用原先被裁掉的 buffer 区域，避免只放大旧 view 截图导致过度裁剪。
     */
    fun withPreviewCalibration(
        matrix: Affine,
        calibrationDegrees: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): Affine {
        val rotation = PreviewCalibration.normalize(calibrationDegrees)
        if (rotation == 0 || viewWidth <= 0 || viewHeight <= 0) return matrix
        val rotated = matrix.then(
            Affine.rotateAround(rotation, viewWidth / 2f, viewHeight / 2f),
        )
        val corners = listOf(
            rotated.map(0f, 0f),
            rotated.map(viewWidth.toFloat(), 0f),
            rotated.map(viewWidth.toFloat(), viewHeight.toFloat()),
            rotated.map(0f, viewHeight.toFloat()),
        )
        val minX = corners.minOf { it.x }
        val maxX = corners.maxOf { it.x }
        val minY = corners.minOf { it.y }
        val maxY = corners.maxOf { it.y }
        val width = maxX - minX
        val height = maxY - minY
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return matrix
        val coverScale = maxOf(viewWidth / width, viewHeight / height)
        return rotated
            .then(Affine.translate(-(minX + width / 2f), -(minY + height / 2f)))
            .then(Affine.scale(coverScale))
            .then(Affine.translate(viewWidth / 2f, viewHeight / 2f))
    }

    /**
     * 抓帧要用的旋转角（**不含**用户预览校准）。
     *
     * 预览校准是"我看到的画面偏了"的人工修正；抓帧/落盘 JPEG 的方向由 sensor/display
     * 几何唯一决定，不能被预览校准带着转（用户明确要求"不改变 JPEG 方向"）。
     */
    fun captureRotationDegrees(
        sensorOrientation: Int,
        displayRotationDegrees: Int,
        frontFacing: Boolean,
    ): Int = uprightRotation(sensorOrientation, displayRotationDegrees, frontFacing)

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

/**
 * 预览方向的**人工校准**（只作用于预览画面，绝不改变抓帧 JPEG 的方向）。
 *
 * ## 为什么要有人工校准
 *
 * camera2 的 buffer 朝向、`TextureView` 的默认拉伸、生产者隐式变换三者叠加之后的真实
 * 符号约定**只能靠真机确认**（本环境拿不到 OEM 的 framework 源码，也无法联网验证）。
 * 与其继续猜"自动几何到底哪一步反了"，不如给一个用户一按就能看出来的兜底：
 * 「右转 90° / 左转 90° / 复位」，结果落在设置里，下次进来还是这个角度。
 *
 * 用户实测反馈（OPPO Reno14 Pro / Android 16 / ColorOS 16.0.5）：预览**左转 90°**，
 * 需要右转 90° 校准。这不是已验证的 HAL 行为，也不能外推到其他机型或系统版本。
 * [DEFAULT_DEGREES] 不对未知设备附加旋转；[deviceDefault] 仅提供基于这次用户反馈的窄匹配建议，
 * 由设置/UI 层接入自动策略，且用户显式保存的校准值应始终优先。
 *
 * ## 边界
 *
 * - 只影响 [VideoPreviewGeometry.textureViewTransform] 的预览矩阵；
 * - [VideoPreviewGeometry.captureRotationDegrees] 与落盘归一化**不含**它，
 *   所以"预览被校准过"不会让存下来的抓帧/照片跟着转；
 * - 换镜头、退后台再回来、页面重建都**不清空**（角度是用户的设置，不是一次性状态）。
 */
object PreviewCalibration {
    /** 未知设备默认不附加旋转；设备建议值须由调用方显式选择。 */
    const val DEFAULT_DEGREES: Int = 0

    /**
     * 仅基于用户反馈的设备建议，不表示已证实的 OEM/HAL 规律。
     * Build.MODEL 若返回硬件编号而非营销名，不猜测编号：退回 0，交给手动校准。
     * sdkInt=36 只约束 Android 16，不能证明 ColorOS 版本；调用方仍须真机验收。
     */
    fun deviceDefault(manufacturer: String, model: String, sdkInt: Int): Int =
        if (manufacturer.trim().equals("OPPO", ignoreCase = true) &&
            model.contains("Reno14 Pro", ignoreCase = true) && sdkInt == 36
        ) {
            90
        } else {
            DEFAULT_DEGREES
        }

    /** 一次按钮点击转动的角度。 */
    const val STEP_DEGREES: Int = 90

    /** 面板上的四个档位，与 [normalize] 之后的值一一对应。 */
    val STEPS: List<Int> = listOf(0, 90, 180, 270)

    /** 任意输入 → 0/90/180/270（非法值既不会写进设置，也不会进矩阵）。 */
    fun normalize(degrees: Int): Int = VideoPreviewGeometry.normalizeDegrees(degrees)

    /** 画面顺时针再转 90°。 */
    fun rotateRight(current: Int): Int = normalize(current + STEP_DEGREES)

    /** 画面逆时针再转 90°（发现转多了就用它回退）。 */
    fun rotateLeft(current: Int): Int = normalize(current - STEP_DEGREES)

    /** 复位成"不做人工校准"。 */
    fun reset(): Int = 0

    /** 面板上显示的角度文本。 */
    fun label(degrees: Int): String = "${normalize(degrees)}°"
}