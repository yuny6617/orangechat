/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

/**
 * 抓帧 JPEG 的方向归一化策略（纯逻辑，可在 JVM 单测里跑）。
 *
 * 背景（REVIEW_R4 §4）：旧实现同时做了两件事 ——
 * 1. 抓帧请求里设置 `JPEG_ORIENTATION = 90`（让 HAL 转）；
 * 2. 落盘时再按 `VideoFrame.rotationDegrees = 90` 用 Matrix 转一次。
 * camera2 允许 HAL 直接旋转像素，也允许只写 EXIF 标签，于是"HAL 真转了"的机型上
 * 会**双转**：一张竖屏照片落盘后是倒的/躺的，而且这个错误只在部分机型上出现。
 *
 * 现在的约定是**单一旋转所有者**：
 * - 请求时只向 HAL 要 0 度（[VideoPreviewGeometry.REQUESTED_JPEG_ORIENTATION]），像素保持 sensor 原始朝向；
 * - 旋转只在这里施加一次；
 * - 落盘时重新编码 JPEG，因此写入文件的字节**不带 EXIF**，方向不存在第二种解释。
 *
 * EXIF 只读不用来旋转：既然我们请求的是 0 度，文件里的方向标签本来应该是"正常"。
 * 如果某个机型仍然写了非 0 的标签，那说明它没遵守请求 —— 这种情况只作为一个诊断标记
 * ([Decision.exifContradictsRequest]) 记下来，**绝不**把它再叠加到像素上
 * （叠加就是"双转"本身）。
 */
object JpegOrientationPolicy {
    /** 一次归一化的最终决策。 */
    data class Decision(
        /** 真正施加到像素上的顺时针旋转角（0/90/180/270）。 */
        val rotationDegrees: Int,
        /** 真正施加到像素上的水平镜像。 */
        val mirror: Boolean,
        /** JPEG 里 EXIF Orientation 标签声明的旋转角（仅诊断用）。 */
        val exifRotationDegrees: Int,
        /** EXIF 标签是否声明了水平翻转（仅诊断用）。 */
        val exifMirrored: Boolean,
        /** EXIF 与"请求 0 度"矛盾：设备没有按请求写文件。 */
        val exifContradictsRequest: Boolean,
        /** 像素被旋转的次数：只允许 0 或 1，**永远不可能是 2**。 */
        val pixelRotations: Int,
    )

    /**
     * @param geometryRotationDegrees 由 [VideoPreviewGeometry.uprightRotation] 算出的转正角度。
     * @param mirror 前摄是否镜像。
     * @param exifOrientation 抓到的 JPEG 的 EXIF Orientation 值（1..8，读不到就是 0）。
     */
    fun decide(
        geometryRotationDegrees: Int,
        mirror: Boolean,
        exifOrientation: Int,
    ): Decision {
        val rotation = VideoPreviewGeometry.normalizeDegrees(geometryRotationDegrees)
        val exifRotation = exifRotationFor(exifOrientation)
        val exifMirrored = exifMirroredFor(exifOrientation)
        return Decision(
            rotationDegrees = rotation,
            mirror = mirror,
            exifRotationDegrees = exifRotation,
            exifMirrored = exifMirrored,
            exifContradictsRequest = exifRotation != 0 || exifMirrored,
            pixelRotations = if (rotation == 0) 0 else 1,
        )
    }

    /** EXIF Orientation → 它声明需要的顺时针旋转角。 */
    fun exifRotationFor(orientation: Int): Int = when (orientation) {
        3 -> 180 // 180° rotated
        6 -> 90 // 90° CW
        8 -> 270 // 270° CW
        else -> 0
    }

    /** EXIF Orientation 里声明了水平翻转的那几个值（2/4/5/7）。 */
    fun exifMirroredFor(orientation: Int): Boolean = when (orientation) {
        2, 4, 5, 7 -> true
        else -> false
    }
}
