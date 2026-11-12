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
 * 用于日志与测试断言。[contentSize] 是**生产 UI 也要用**的字段：预览卡的宽高比必须按它设置，
 * 否则预览会为了铺满卡片而裁掉一部分，而抓帧 JPEG 仍是整张源图。
 */
data class PreviewTransform(
    /** 由 sensor/display 推出来的角度（不含用户校准）。 */
    val rotationDegrees: Int = 0,
    /** 用户手动校准的角度（同时作用于预览与抓帧 JPEG）。 */
    val calibrationDegrees: Int = 0,
    val mirrorHorizontally: Boolean = false,
    /**
     * 旋转 + 校准之后**正立源图**的尺寸（90/270 时宽高互换）。
     *
     * 预览卡的宽高比必须等于 `width / height`：此时等比 cover 刚好贴合，整张源图可见；
     * 抓帧 JPEG 的正立尺寸也是这个比例。[width]/[height] 为 0 表示尺寸还没就绪。
     */
    val contentSize: PixelSize = PixelSize(0, 0),
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
 * - 前摄：`(sensor + display) % 360`。
 *
 * 预览镜像与用户校准**同时**作用于预览和抓帧 JPEG（用户明确要求"所见方向/镜像就是发给
 * 模型的方向"）。预览和 JPEG 共用 [captureRotationDegrees]：抓帧角度 = 预览基准角 + 校准角，
 * 镜像 = 预览镜像开关。因此两者只差"预览会按视图做等比裁切"这一层。
 *
 * ## 预览 / JPEG 的取景范围一致
 *
 * [PreviewTransform.contentSize] 给出"旋转 + 校准之后正立源图"的尺寸。UI 必须把预览卡
 * 做成这个宽高比（见 `VideoCallPage`），此时 [visibleAffine] 的 cover 缩放正好等于
 * 等比贴合：**整张 sensor 画面都可见，没有裁切**。抓帧 JPEG 也是整张源图旋转/镜像，
 * 所以"预览看到的取景范围"和"发给模型的照片取景范围"按构造相同，而不是靠
 * `TextureView` 对源 buffer 的默认拉伸去猜。
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
     * mirror 由图像调用方指定；视频通话把**预览的镜像开关**原样传进来，保证模型收到的
     * 方向/镜像与用户看到的一致。
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
     * The video-call card is portrait. Some front cameras (the reported device included) expose a
     * landscape buffer whose upright result still has width > height, which leaves the portrait
     * card full of black bars. Rotate that case by 90° for both preview and captured JPEG.
     */
    fun portraitRotationAdjustment(bufferWidth: Int, bufferHeight: Int, rotationDegrees: Int): Int {
        if (bufferWidth <= 0 || bufferHeight <= 0) return 0
        val upright = uprightSize(bufferWidth, bufferHeight, rotationDegrees)
        return if (upright.width > upright.height) 90 else 0
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
     *
     * 调用方还应该把 view 的宽高比设成 [contentSize] 的比例：此时 `F` 的 cover 缩放
     * 与等比贴合相同，**整张 buffer 都在视图内**，预览不再依赖"裁掉多少"的额外假设，
     * 也不会出现"预览裁了、JPEG 没裁"的取景范围差异。
     */
    fun visibleAffine(
        rotationDegrees: Int,
        mirror: Boolean,
        bufferWidth: Int,
        bufferHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        /**
         * true = letterbox the whole upright frame inside the view (never crop or stretch).
         * false keeps the historical center-crop behavior used by non-video callers.
         */
        fit: Boolean = false,
    ): Affine {
        if (bufferWidth <= 0 || bufferHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return Affine.IDENTITY
        }
        val rotation = normalizeDegrees(rotationDegrees)
        val visible = uprightSize(bufferWidth, bufferHeight, rotation)
        // 等比：fit 取较小者（整图可见、留边），否则取较大者（填满、裁边）。两种都不拉伸。
        val scaleX = viewWidth.toFloat() / visible.width.toFloat()
        val scaleY = viewHeight.toFloat() / visible.height.toFloat()
        val scale = if (fit) minOf(scaleX, scaleY) else maxOf(scaleX, scaleY)
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
        fit: Boolean = false,
    ): Affine {
        val visible = visibleAffine(
            rotationDegrees = rotationDegrees,
            mirror = mirror,
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            fit = fit,
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
         * 用户手动校准角度；预览矩阵和抓帧控制器使用**同一个**角度。
         *
         * 这是"几何算完之后的最后一道人工修正"：真机（OPPO/ColorOS）上画面差 90° 时，
         * 用户点一下"右转"就能转正，不依赖我们对 framework 复合顺序的推断是否正确。
         * 抓帧控制器读取这一校准角度并和同一个 uprightRotation 组合。
         */
        calibrationDegrees: Int = 0,
        previewMirrored: Boolean = frontFacing,
        /**
         * true = the whole camera frame is letterboxed into the view. Video calls use this so the
         * 4:3 card never crops/stretches the picture; other callers keep center-crop by default.
         */
        fit: Boolean = false,
    ): PreviewTransform {
        val rotation = uprightRotation(sensorOrientation, displayRotationDegrees, frontFacing)
        val calibration = PreviewCalibration.normalize(calibrationDegrees)
        val base = textureViewAffine(
            rotationDegrees = rotation,
            mirror = false,
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            fit = fit,
        )
        // Fit mode folds the calibration directly into the rotation and does not rescale to
        // cover the view: `withPreviewCalibration` deliberately re-crops, which would defeat
        // letterboxing and can look squeezed on a 4:3 card.
        val calibrated = if (fit) {
            val portraitAdjustment = portraitRotationAdjustment(
                bufferWidth = bufferWidth,
                bufferHeight = bufferHeight,
                rotationDegrees = rotation + calibration,
            )
            textureViewAffine(
                rotationDegrees = normalizeDegrees(rotation + calibration + portraitAdjustment),
                mirror = false,
                bufferWidth = bufferWidth,
                bufferHeight = bufferHeight,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                fit = true,
            )
        } else {
            withPreviewCalibration(base, calibration, viewWidth, viewHeight)
        }
        return PreviewTransform(
            rotationDegrees = rotation,
            calibrationDegrees = calibration,
            mirrorHorizontally = previewMirrored,
            // 预览卡要用的宽高比 = 旋转 + 校准之后整张源图的正立比例。
            contentSize = orientedContentSize(
                bufferWidth = bufferWidth,
                bufferHeight = bufferHeight,
                rotationDegrees = rotation,
                calibrationDegrees = calibration,
            ),
            // Flip last, after calibration rotation: left/right always means screen space.
            matrix = calibrated.let {
                if (previewMirrored) it.then(Affine.mirrorAround(viewWidth / 2f, viewHeight / 2f)) else it
            },
        )
    }

    /**
     * 旋转 + 用户校准之后**正立源图**的尺寸；预览卡宽高比与抓帧 JPEG 的正立尺寸都用它。
     *
     * 只做直角旋转，所以结果仍是轴对齐矩形。尺寸未知（<= 0）时返回 0x0，
     * 调用方不得把它当成"1x1 有效比例"。
     */
    fun orientedContentSize(
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
        calibrationDegrees: Int = 0,
    ): PixelSize {
        if (bufferWidth <= 0 || bufferHeight <= 0) return PixelSize(0, 0)
        val effective = normalizeDegrees(rotationDegrees + PreviewCalibration.normalize(calibrationDegrees))
        return uprightSize(bufferWidth, bufferHeight, effective)
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
     * 抓帧 / 落盘 JPEG 要用的旋转角：sensor/display 基准角 **+ 用户校准角**。
     *
     * 用户明确要求"看到的画面就是发给模型的画面"：预览矩阵把校准当作最后一步旋转，
     * 这里把同一个校准角加进 JPEG 的归一化旋转，两者只差预览按视图做的那层等比裁切
     * （预览卡宽高比 = [PreviewTransform.contentSize] 时，那层裁切等于整图贴合）。
     *
     * 角度只归一化到直角，且**只加一次**：抓帧请求仍固定 `JPEG_ORIENTATION=0`，
     * HAL 不转像素，落盘时由 [uprightImageAffine] 施加唯一一次旋转 + 镜像。
     */
    fun captureRotationDegrees(
        sensorOrientation: Int,
        displayRotationDegrees: Int,
        frontFacing: Boolean,
        calibrationDegrees: Int = 0,
    ): Int = normalizeDegrees(
        uprightRotation(sensorOrientation, displayRotationDegrees, frontFacing) +
            PreviewCalibration.normalize(calibrationDegrees),
    )

    /** 预览输出的最长边上限（与 `VideoFramePolicy.MAX_CAPTURE_EDGE` 一致）。 */
    const val PREVIEW_MAX_EDGE: Int = 1280

    /**
     * 任何被选中 JPEG 的**像素**上限（含"只有更大输出"时的兜底候选）。
     *
     * 为什么是 1920×1080（2,073,600）：下游 `VideoFrameStore` 目前是**整张 JPEG 解码**
     * 之后再缩放，ARGB_8888 一张 1080p 约 8.3MB；方向归一化还会再要一张同尺寸位图。
     * 两张大图 + 缩放目标仍在低端机可承受的瞬时预算内；超过这个上限的 JPEG 宁可停用
     * 抓帧，也不赌一次 12MP 全解码（OOM 会直接杀进程，比没有照片严重得多）。
     */
    const val CAPTURE_MAX_PIXELS: Int = 1920 * 1080

    /** 更大输出的最长边上限；与 [CAPTURE_MAX_PIXELS] 一起构成严格的解码预算。 */
    const val CAPTURE_FALLBACK_MAX_EDGE: Int = 1920

    /** 预览可以开，但设备没有任何与预览同宽高比的 JPEG 输出。 */
    const val CAPTURE_DISABLED_NO_MATCHING_ASPECT: String =
        "相机没有与预览同宽高比的照片输出，已保留预览并停用抓帧"

    /** 同比例 JPEG 存在，但最小的一张也超出安全解码预算。 */
    const val CAPTURE_DISABLED_OVERSIZED_CAPTURE: String =
        "相机同比例的照片输出都超出安全解码上限，已保留预览并停用抓帧"

    /**
     * 预览 / 抓帧选中的一组输出尺寸（**两边都不超过上限**的严格配对）。
     *
     * 两者**宽高比相同**是"预览取景范围 == 照片取景范围"的前提：否则预览按 4:3 取景、
     * 抓帧却是 16:9（或反过来），模型收到的画面必然比用户看到的多/少一块。
     */
    data class CameraStreamSizes(
        val preview: PixelSize,
        val capture: PixelSize,
    )

    /**
     * 一次相机会话实际可用的输出计划。
     *
     * [capture] 为 null 表示"预览能开，但抓帧停用"：设备没有和预览同宽高比、
     * 又不超过解码预算的 JPEG 输出。此时**绝不允许**用近似比例或拉伸凑一张图——
     * 那会让模型收到与用户所见不同的取景范围，违背"所见即所发"。
     * 调用方应保留预览（session 只挂 preview surface、不建 ImageReader），
     * 并让抓帧返回 null + [captureDisabledReason] 作为提示。
     */
    data class CameraStreamPlan(
        val preview: PixelSize,
        val capture: PixelSize?,
        val captureDisabledReason: String = "",
    ) {
        val captureEnabled: Boolean
            get() = capture != null
    }

    /** 宽高比（大边 / 小边，恒 >= 1），用于与"4:3"这类不区分横竖的目标比较。 */
    fun aspectRatio(width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return 0f
        return maxOf(width, height).toFloat() / minOf(width, height).toFloat()
    }

    /** 单个预览候选与它选中的 JPEG（null = 这个比例没有可用 JPEG）。 */
    private class StreamCandidate(
        val preview: PixelSize,
        val capture: PixelSize?,
        val aspect: Float,
    )

    /**
     * 选一套"预览 + 抓帧"输出计划（相机兼容性修复的主入口）。
     *
     * 规则：
     * 1. **预览**只考虑正数、最长边不超过 [previewMaxEdge] 的尺寸；
     * 2. 预览与抓帧的有向 width/height 必须**精确**相等（交叉乘法），不接受横竖互换或近似比例；
     * 3. 对每个预览比例，优先选同比例、最长边不超过 [previewMaxEdge] 的 JPEG，取面积最大的那张
     *    （预算内画质最好）；所有 JPEG 都必须满足 [captureMaxPixels] 的严格解码预算；
     * 4. 如果该比例只有更大的 JPEG，选**最小的**那张同比例输出（离上限最近、解码最省），
     *    但必须同时满足 [captureFallbackMaxEdge] 与 [captureMaxPixels] 的严格预算；
     * 5. 优先 4:3（[preferredAspect]，允许横竖），其次预览面积最大的候选；
     * 6. 只要存在任何可抓帧的比例，就优先选可抓帧的候选；完全找不到同比例 JPEG 时，
     *    仍然返回**只预览**的计划（[CameraStreamPlan.capture] == null），由调用方停用抓帧，
     *    而不是让整台相机打不开，也绝不近似比例误发。
     *
     * 只有在连一个可用预览都没有时返回 null（这时相机确实开不了）。
     * 纯函数，不碰 Android，可在 JVM 上逐条钉住。
     */
    fun chooseCameraStreamPlan(
        previewSizes: List<PixelSize>,
        captureSizes: List<PixelSize>,
        previewMaxEdge: Int = PREVIEW_MAX_EDGE,
        captureMaxPixels: Int = CAPTURE_MAX_PIXELS,
        captureFallbackMaxEdge: Int = CAPTURE_FALLBACK_MAX_EDGE,
        preferredAspect: Float = 4f / 3f,
        aspectTolerance: Float = 0.02f,
    ): CameraStreamPlan? {
        if (previewMaxEdge <= 0) return null
        val previews = previewSizes.filter {
            it.width > 0 && it.height > 0 && it.maxEdge <= previewMaxEdge
        }
        if (previews.isEmpty()) return null
        val captures = captureSizes.filter { it.width > 0 && it.height > 0 }

        val candidates = previews.map { preview ->
            val aspect = aspectRatio(preview.width, preview.height)
            // Compare directed ratios exactly: 640x480 and 480x640 are different sensor axes.
            val sameAspect = captures.filter {
                it.width.toLong() * preview.height == preview.width.toLong() * it.height
            }
            val withinPreviewLimit = sameAspect
                .filter {
                    it.maxEdge <= previewMaxEdge &&
                        it.width.toLong() * it.height <= captureMaxPixels.toLong()
                }
                .maxByOrNull { it.width.toLong() * it.height }
            val boundedLarger = if (withinPreviewLimit != null) {
                null
            } else {
                sameAspect
                    .filter {
                        it.maxEdge > previewMaxEdge &&
                            it.maxEdge <= captureFallbackMaxEdge &&
                            it.width.toLong() * it.height <= captureMaxPixels.toLong()
                    }
                    // "最小同比例更大输出"：优先面积最小，其次最长边最小；绝不挑最大的那张。
                    .minWithOrNull(
                        compareBy<PixelSize> { it.width.toLong() * it.height }
                            .thenBy { it.maxEdge },
                    )
            }
            StreamCandidate(
                preview = preview,
                capture = withinPreviewLimit ?: boundedLarger,
                aspect = aspect,
            )
        }

        fun isPreferred(candidate: StreamCandidate): Boolean =
            kotlin.math.abs(candidate.aspect - preferredAspect) <= aspectTolerance

        fun rankPairs(list: List<StreamCandidate>): StreamCandidate? =
            list.maxWithOrNull(
                compareBy<StreamCandidate> { it.preview.width.toLong() * it.preview.height }
                    .thenBy { (it.capture?.let { capture -> capture.width.toLong() * capture.height }) ?: 0L },
            )

        val capturable = candidates.filter { it.capture != null }
        val chosen = (if (capturable.isNotEmpty()) {
            rankPairs(capturable.filter { isPreferred(it) }) ?: rankPairs(capturable)
        } else {
            // 只预览：没有可对齐的 JPEG，就不必为比例牺牲分辨率，取面积最大的预览。
            candidates.maxWithOrNull(
                compareBy<StreamCandidate> { it.preview.width.toLong() * it.preview.height }
                    .thenBy { if (isPreferred(it)) 1 else 0 },
            )
        }) ?: return null

        val capture = chosen.capture
        val reason = if (capture != null) {
            ""
        } else {
            // 全局判断：只要存在任何一个"预览比例 == 某个 JPEG 输出"的组合，
            // 说明停用抓帧不是因为没有同比例，而是那些同比例输出都超预算。
            val hasCommonAspect = previews.any { preview ->
                captures.any {
                    it.width.toLong() * preview.height == preview.width.toLong() * it.height
                }
            }
            if (hasCommonAspect) {
                CAPTURE_DISABLED_OVERSIZED_CAPTURE
            } else {
                CAPTURE_DISABLED_NO_MATCHING_ASPECT
            }
        }
        return CameraStreamPlan(
            preview = chosen.preview,
            capture = capture,
            captureDisabledReason = reason,
        )
    }

    /**
     * 严格版配对：预览与抓帧**两边**都不超过 [maxEdge]，找不到就返回 null。
     *
     * 大 JPEG 的兜底逻辑只在 [chooseCameraStreamPlan] 里启用；这个入口保持旧契约，
     * 供"必须把两边都压在上限内"的调用方/回归测试使用。
     */
    fun chooseCameraStreamSizes(
        previewSizes: List<PixelSize>,
        captureSizes: List<PixelSize>,
        maxEdge: Int = PREVIEW_MAX_EDGE,
        preferredAspect: Float = 4f / 3f,
        aspectTolerance: Float = 0.02f,
    ): CameraStreamSizes? {
        val plan = chooseCameraStreamPlan(
            previewSizes = previewSizes,
            captureSizes = captureSizes,
            previewMaxEdge = maxEdge,
            // 这个入口不允许任何一边超过 maxEdge，因此关掉大图兜底；像素预算也放开，
            // 保持"两边都 <= maxEdge"的旧契约。
            captureMaxPixels = Int.MAX_VALUE,
            captureFallbackMaxEdge = maxEdge,
            preferredAspect = preferredAspect,
            aspectTolerance = aspectTolerance,
        ) ?: return null
        val capture = plan.capture ?: return null
        return CameraStreamSizes(preview = plan.preview, capture = capture)
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

/**
 * 预览方向的**人工校准**（用户要求：预览怎么转，发给模型的抓帧 JPEG 就怎么转）。
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
 * - 影响 [VideoPreviewGeometry.textureViewTransform] 的预览矩阵；
 * - 也通过 [VideoPreviewGeometry.captureRotationDegrees] 进入抓帧 JPEG 的归一化旋转：
 *   预览与照片使用同一个角度，且抓帧请求仍固定 `JPEG_ORIENTATION=0`，只转一次；
 * - 换镜头、退后台再回来、页面重建都**不清空**（角度是用户的设置，不是一次性状态）；
 * - 运行时改变角度会让**正在飞的抓帧**作废（旧的几何不能套在新画面上）。
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