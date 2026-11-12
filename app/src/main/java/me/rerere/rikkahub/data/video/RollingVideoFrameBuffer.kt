package me.rerere.rikkahub.data.video

/**
 * 通话中 1 Hz 采样出来的"最近几帧"，只活在内存里，不落盘。
 *
 * 这里有**两层代次**，缺一层都会漏帧/串帧：
 * - [epoch]：缓冲自己的代次。[clear]（挂断、关摄像头、切会话）会递增它。抓帧前记下，
 *   [accept] 时不一致就丢弃 —— 于是挂断后仍在途的抓帧回来也进不来。
 * - 相机代次：帧集**原子地**绑定在"这一代相机"上。[accept] 同时拿到"抓帧时记下的那一代"
 *   和"落进来时当前的那一代"，两者不一致就是迟到帧，直接拒绝；换代时整批清空，
 *   绝不让前后两代相机的画面混在同一个拼图里。[latest] 也只交出绑定在当前这一代的帧。
 *
 * 单帧字节和总量都有上限：原始 JPEG 来自相机 HAL，不能只靠"最多 9 张"来兜内存。
 */
internal class RollingVideoFrameBuffer(
    /** 原始 JPEG 字节总量上限（见 [MAX_TOTAL_BYTES]）。 */
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
) {
    private val frames = ArrayDeque<VideoFrame>()
    private var totalBytes = 0L
    private var epoch = 0L
    private var lastSampleAtMillis = Long.MIN_VALUE
    private var cameraEpoch = Long.MIN_VALUE

    @Synchronized
    fun epoch(): Long = epoch

    @Synchronized
    fun clear() {
        epoch++
        frames.clear()
        totalBytes = 0L
        lastSampleAtMillis = Long.MIN_VALUE
        cameraEpoch = Long.MIN_VALUE
    }

    /**
     * 收下一帧自动采样的画面；容量 1..9，手动夹帧不走这里。
     *
     * @param expectedEpoch 抓帧**开始之前**读到的 [epoch]。
     * @param capturedCameraEpoch 抓帧**开始之前**读到的相机代次。
     * @param currentCameraEpoch 这一帧**落进来时**的相机代次（调用方在 accept 前读取）。
     * @param sampleAtElapsedMillis 抓帧起点的单调时间；与帧的展示时间、回调耗时无关。
     * @return true 表示这一帧真的留在了缓冲里。
     */
    @Synchronized
    fun accept(
        expectedEpoch: Long,
        capturedCameraEpoch: Long,
        currentCameraEpoch: Long,
        frame: VideoFrame,
        capacity: Int,
        sampleAtElapsedMillis: Long,
    ): Boolean {
        if (epoch != expectedEpoch) return false
        if (capacity !in MIN_FRAMES..MAX_FRAMES) return false
        if (frame.manual) return false
        if (frame.bytes.isEmpty() || frame.bytes.size > maxTotalBytes) return false
        // 迟到帧：拍的时候还是那一代，落进来的时候已经不是了。这里必须和"当前"比，
        // 不能只看缓冲里存的那一代 —— 否则换相机瞬间到达的旧帧会被当成新一组的第一帧。
        if (capturedCameraEpoch != currentCameraEpoch) return false
        // 代次只增不减：即使调用方把"当前代次"读成了旧值，也不许把帧集倒退回上一代。
        if (cameraEpoch != Long.MIN_VALUE && capturedCameraEpoch < cameraEpoch) return false
        if (cameraEpoch != capturedCameraEpoch) {
            frames.clear()
            totalBytes = 0L
            lastSampleAtMillis = Long.MIN_VALUE
            cameraEpoch = capturedCameraEpoch
        }
        // 按抓帧起点的单调时钟限频；展示时间和回调耗时都不能改变采样节拍。
        if (frames.isNotEmpty() && sampleAtElapsedMillis - lastSampleAtMillis < MIN_SAMPLE_GAP_MILLIS) {
            return false
        }
        frames.addLast(frame)
        totalBytes += frame.bytes.size.toLong()
        lastSampleAtMillis = sampleAtElapsedMillis
        trim(capacity)
        return true
    }

    /**
     * 当前相机代次下最近的 [maxCount] 帧（1..9）。
     *
     * 缓冲绑定的代次与 [currentCameraEpoch] 不一致时返回空列表：换相机之后，
     * 上一代的画面即使还没来得及被新帧覆盖，也绝不能再作为这一轮的输入。
     */
    @Synchronized
    fun latest(maxCount: Int, currentCameraEpoch: Long): List<VideoFrame> {
        if (cameraEpoch == Long.MIN_VALUE || cameraEpoch != currentCameraEpoch) return emptyList()
        return frames.takeLast(maxCount.coerceIn(MIN_FRAMES, MAX_FRAMES))
    }

    /** 同时按帧数和字节数淘汰最旧的帧；新帧刚加在尾部，不会被自己挤掉。 */
    private fun trim(capacity: Int) {
        while (frames.isNotEmpty() && (frames.size > capacity || totalBytes > maxTotalBytes)) {
            val removed = frames.removeFirst()
            totalBytes -= removed.bytes.size.toLong()
        }
    }

    internal companion object {
        const val MIN_FRAMES: Int = 1
        const val MAX_FRAMES: Int = 9

        /** 1 Hz 采样下相邻两帧的最小间隔（毫秒），留 50 ms 给回调/时钟抖动。 */
        const val MIN_SAMPLE_GAP_MILLIS: Long = 950L

        /**
         * 原始 JPEG 总量上限。单帧通常 0.2–1 MB（最长边 1280），
         * 9 帧远小于这里；上限只是防止异常 HAL 输出把通话内存打满。
         */
        const val MAX_TOTAL_BYTES: Long = 12L * 1024 * 1024
    }
}

/** Pure guard shared by rolling and single-frame automatic attachment paths. */
internal fun isAutomaticVideoAttachmentCurrent(
    expectedMode: VideoFrameCaptureMode,
    currentMode: VideoFrameCaptureMode,
    expectedCameraEpoch: Long,
    currentCameraEpoch: Long,
    cameraGranted: Boolean,
    callCurrent: Boolean,
): Boolean = expectedMode != VideoFrameCaptureMode.OFF && expectedMode == currentMode &&
    expectedCameraEpoch == currentCameraEpoch && cameraGranted && callCurrent

/** 滚动采样周期：用户看到的是"每秒采一帧"。 */
internal const val ROLLING_SAMPLE_PERIOD_MILLIS: Long = 1_000L

/**
 * 1 Hz 采样节拍（**单调时钟**，不是墙上时间）。
 *
 * 旧写法是 `capture(); delay(1000)`，真实周期 = 抓帧耗时 + 1 s，慢设备上会明显低于 1 Hz。
 * 这里在抓帧**结束**时算"距离下一次采样还差多少"，把抓帧花掉的时间从等待里扣掉：
 *
 * - 正常情况：抓帧 300 ms，则只等 700 ms，下一次采样仍在 1 s 节拍上；
 * - 抓帧本身超过一个周期：不补发一串抓帧（那会连续占用相机），立刻采下一帧，
 *   并把后续节拍重新对齐到这一次的起点。
 *
 * [reset] 在开始（或重新开始）采样时调用，以当前单调时刻作为第一个节拍起点。
 */
internal class RollingSampleCadence(
    private val periodMillis: Long = ROLLING_SAMPLE_PERIOD_MILLIS,
) {
    private var nextSampleAtMillis: Long? = null

    fun reset(nowMillis: Long) {
        nextSampleAtMillis = nowMillis
    }

    /**
     * @param startedAtMillis 本次抓帧开始时的单调时刻。
     * @param nowMillis 本次抓帧结束时的单调时刻。
     * @return 距离下一次采样还需要等待的毫秒数（0 表示可以立刻开始下一帧）。
     */
    fun delayAfterSample(startedAtMillis: Long, nowMillis: Long): Long {
        val scheduled = nextSampleAtMillis
        // 已经错过了原定节拍（抓帧太久/被调度延迟）：从这一次的起点重新排，不追赶旧节拍。
        val nextStart = if (scheduled != null && scheduled > startedAtMillis) {
            scheduled
        } else {
            startedAtMillis + periodMillis
        }
        nextSampleAtMillis = nextStart
        return (nextStart - nowMillis).coerceAtLeast(0L)
    }
}
