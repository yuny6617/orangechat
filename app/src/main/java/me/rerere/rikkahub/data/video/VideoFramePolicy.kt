/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 自动抓帧频率。
 *
 * 只有 [EVERY_REPLY] 会在每一轮发送前抓一帧；[EVERY_30S] / [EVERY_60S] 只刷新"待发送帧"，
 * 不会自己发起请求，也不会自己发消息。定时抓到的帧同样只活在内存里。
 */
@Serializable
enum class VideoFrameCaptureMode {
    @SerialName("off")
    OFF,

    @SerialName("every_reply")
    EVERY_REPLY,

    @SerialName("every_30s")
    EVERY_30S,

    @SerialName("every_60s")
    EVERY_60S;

    val intervalMillis: Long?
        get() = when (this) {
            OFF, EVERY_REPLY -> null
            EVERY_30S -> 30_000L
            EVERY_60S -> 60_000L
        }

    val timerDriven: Boolean
        get() = intervalMillis != null

    val displayName: String
        get() = when (this) {
            OFF -> "关闭"
            EVERY_REPLY -> "每轮一帧"
            EVERY_30S -> "每 30 秒"
            EVERY_60S -> "每 60 秒"
        }
}

/** 抓帧画质：最长边像素上限 + 固定的合理 JPEG 压缩。 */
@Serializable
enum class VideoFrameQuality(val maxEdgePx: Int) {
    @SerialName("p384")
    P384(384),

    @SerialName("p512")
    P512(512),

    @SerialName("p768")
    P768(768);

    val displayName: String
        get() = "最长边 ${maxEdgePx}px"
}

data class PixelSize(val width: Int, val height: Int) {
    val maxEdge: Int
        get() = maxOf(width, height)
}

/**
 * 待发送的一帧：只在内存里存在，发送/挂断后立即消失（身份比较，不做内容比较）。
 *
 * [rotationDegrees]/[mirror] 来自相机 sensor 方向 + 当前显示方向：抓到的 JPEG 要按这个
 * 旋转/镜像后再压缩，否则前摄是反的、竖屏拿到的是一张横倒的图。
 */
class VideoFrame(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val capturedAtMillis: Long,
    val manual: Boolean = false,
    val rotationDegrees: Int = 0,
    val mirror: Boolean = false,
)

object VideoFramePolicy {
    /** 视觉模型吃得动、又不会让请求变大太多的压缩质量。 */
    const val JPEG_QUALITY: Int = 82

    /** 单帧像素上限之外再兜一层，避免超大 sensor 输出把内存打满。 */
    const val MAX_CAPTURE_EDGE: Int = 1280

    /** 每轮发送是否需要在发送前现场抓一帧。 */
    fun captureOnTurn(mode: VideoFrameCaptureMode): Boolean = mode == VideoFrameCaptureMode.EVERY_REPLY

    /** 等比缩放，最长边不超过 maxEdge。 */
    fun fit(srcWidth: Int, srcHeight: Int, maxEdge: Int): PixelSize {
        if (srcWidth <= 0 || srcHeight <= 0 || maxEdge <= 0) return PixelSize(1, 1)
        val longest = maxOf(srcWidth, srcHeight)
        if (longest <= maxEdge) return PixelSize(srcWidth, srcHeight)
        val scale = maxEdge.toDouble() / longest
        return PixelSize(
            width = (srcWidth * scale).toInt().coerceAtLeast(1),
            height = (srcHeight * scale).toInt().coerceAtLeast(1),
        )
    }

    fun forQuality(srcWidth: Int, srcHeight: Int, quality: VideoFrameQuality): PixelSize =
        fit(srcWidth, srcHeight, minOf(quality.maxEdgePx, MAX_CAPTURE_EDGE))

    /**
     * 定时器抓到的自动帧能不能覆盖当前待发送帧？
     *
     * 手动夹住的一帧优先：用户按下快门之后，按钮夹的就是那一张，
     * 不能被 30/60 秒定时器静悄悄换成另一张。直到它被发送或清空。
     */
    fun shouldReplacePendingFrame(existing: VideoFrame?, incoming: VideoFrame): Boolean =
        existing == null || incoming.manual || !existing.manual
}

/**
 * 定时抓帧计时器。
 *
 * 时钟可注入，整段逻辑不依赖 Android，单测里可以精确控制"过多少秒才刷新一帧"。
 */
class VideoFrameScheduler(private val clock: () -> Long = { System.currentTimeMillis() }) {
    private var lastCaptureAtMillis: Long? = null

    fun shouldCapture(mode: VideoFrameCaptureMode): Boolean {
        val interval = mode.intervalMillis ?: return false
        val last = lastCaptureAtMillis ?: return true
        return clock() - last >= interval
    }

    fun markCaptured(atMillis: Long = clock()) {
        lastCaptureAtMillis = atMillis
    }

    fun reset() {
        lastCaptureAtMillis = null
    }
}

/** 手动画面的待发送帧：只有一帧，发送即消费，绝不堆积。 */
class VideoFrameCache {
    private var current: VideoFrame? = null

    val hasFrame: Boolean
        get() = current != null

    fun peek(): VideoFrame? = current

    fun replace(frame: VideoFrame) {
        current = frame
    }

    /**
     * 按优先级写入待发送帧。
     *
     * @return false 表示这一帧被忽略了（手动帧正在等待发送，定时自动帧不许顶掉它）。
     */
    fun offer(frame: VideoFrame): Boolean {
        if (!VideoFramePolicy.shouldReplacePendingFrame(current, frame)) return false
        current = frame
        return true
    }

    fun take(): VideoFrame? = current?.also { current = null }

    fun clear() {
        current = null
    }
}
