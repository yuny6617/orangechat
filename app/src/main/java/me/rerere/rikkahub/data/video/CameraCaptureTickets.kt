/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

/**
 * 一次抓帧的**票据 + JPEG 时间戳对齐**（REVIEW_R5 §3）。
 *
 * 上一版的两个真实缺陷：
 *
 * 1. `deliverImage` 在票据还没拿到 sensor 时间戳时（`expected == null`）会**接受任意**
 *    JPEG。于是"旧请求超时（它的 onCaptureStarted 还没到）→ 旧图晚到 → 新票据时间戳
 *    仍然未知"这条路径上，旧画面会被当成本次请求的结果；
 * 2. 早到的合法 JPEG（`onCaptureStarted` 与 `ImageReader` 回调谁先到并没有保证）
 *    在这套规则下没有任何位置可放，只能被丢掉或者被误收。
 *
 * 现在的规则（全部在这里，纯 Kotlin，可单测）：
 * - **只有时间戳严格相等**才会被交付；未知时间戳的请求谁也不接受；
 * - 早到的 JPEG 暂存在**有界**队列里（默认 2 张），等 `onCaptureStarted` 给出时间戳之后
 *   按时间戳精确匹配；
 * - 超时/取消/失败一定 [end] 票据：清掉暂存，并把已知的时间戳记成
 *   "作废水位"（`<=` 它的帧一律丢弃），迟到的旧图永远喂不给下一次抓帧；
 * - 帧时间戳还必须**单调递增**，且大于作废水位。
 */
class CameraCaptureTickets(private val maxHeldFrames: Int = DEFAULT_MAX_HELD_FRAMES) {
    /** 一次抓帧的票据。时间戳可能一直未知（`onCaptureStarted` 没到）。 */
    class Ticket internal constructor(val id: Long) {
        internal var timestampNs: Long? = null

        internal var abandoned: Boolean = false

        internal var delivered: Boolean = false
    }

    /** 一张**确实属于这张票据**的图。 */
    class Delivery internal constructor(val ticket: Ticket, val bytes: ByteArray)

    private class HeldFrame(val timestampNs: Long, val bytes: ByteArray)

    private val lock = Any()
    private var nextId: Long = 1L
    private var openTicket: Ticket? = null
    private val heldFrames = ArrayDeque<HeldFrame>()
    private var staleBeforeTimestampNs: Long = Long.MIN_VALUE
    private var lastDeliveredTimestampNs: Long = Long.MIN_VALUE

    /**
     * 开一张新票据。
     *
     * 上一张（如果还没结束）立刻作废：它的图不可能属于新请求（时间戳唯一），
     * 暂存的早到帧也一并清掉，绝不留给新请求。
     */
    fun begin(): Ticket = synchronized(lock) {
        openTicket?.let { it.abandoned = true }
        heldFrames.clear()
        Ticket(nextId++).also { openTicket = it }
    }

    /**
     * `onCaptureStarted` / `onCaptureCompleted` 给出了这次请求的 sensor 时间戳。
     *
     * @return 如果之前有**时间戳刚好相等**的早到帧，就把它作为交付返回；否则 null。
     */
    fun noteCaptureStarted(ticket: Ticket, timestampNs: Long): Delivery? = synchronized(lock) {
        if (openTicket !== ticket || ticket.abandoned || ticket.delivered) return null
        if (ticket.timestampNs == null) ticket.timestampNs = timestampNs
        val expected = ticket.timestampNs ?: return null
        val early = takeHeldLocked(expected) ?: return null
        deliverLocked(ticket, early)
    }

    /**
     * `ImageReader` 里来了一帧。
     *
     * @return 只有"确实属于当前票据"时才返回交付；其余情况（旧帧、未知时间戳的暂存、
     *         时间戳不等、没有打开的票据）一律 null，调用方不得把这一帧交给任何人。
     */
    fun offer(timestampNs: Long, bytes: ByteArray): Delivery? = synchronized(lock) {
        if (timestampNs <= staleBeforeTimestampNs) return null
        if (timestampNs <= lastDeliveredTimestampNs) return null
        val ticket = openTicket ?: return null
        if (ticket.abandoned || ticket.delivered) return null
        val expected = ticket.timestampNs
        if (expected == null) {
            // 不知道这张票据的时间戳：只能有界暂存，等 onCaptureStarted 来决定。
            holdLocked(HeldFrame(timestampNs, bytes))
            return null
        }
        if (expected != timestampNs) return null
        deliverLocked(ticket, HeldFrame(timestampNs, bytes))
    }

    /**
     * 结束票据（正常交付 / 超时 / 取消 / 失败都要走）。
     *
     * 已知时间戳会被记成作废水位，迟到的旧图不会喂给下一次抓帧；暂存的早到帧一起清掉。
     */
    fun end(ticket: Ticket) = synchronized(lock) {
        if (openTicket === ticket) openTicket = null
        ticket.abandoned = true
        ticket.timestampNs?.let { stale ->
            if (stale > staleBeforeTimestampNs) staleBeforeTimestampNs = stale
        }
        heldFrames.clear()
    }

    fun openTicketOrNull(): Ticket? = synchronized(lock) { openTicket }

    fun isPending(ticket: Ticket): Boolean = synchronized(lock) {
        openTicket === ticket && !ticket.abandoned && !ticket.delivered
    }

    /** 调试/测试用：暂存了几张还没匹配上的早到帧。 */
    fun heldFrameCount(): Int = synchronized(lock) { heldFrames.size }

    fun staleBeforeTimestampNs(): Long = synchronized(lock) { staleBeforeTimestampNs }

    fun lastDeliveredTimestampNs(): Long = synchronized(lock) { lastDeliveredTimestampNs }

    private fun holdLocked(frame: HeldFrame) {
        heldFrames.removeAll { it.timestampNs == frame.timestampNs }
        heldFrames.addLast(frame)
        while (heldFrames.size > maxHeldFrames) heldFrames.removeFirst()
    }

    private fun takeHeldLocked(timestampNs: Long): HeldFrame? {
        val index = heldFrames.indexOfFirst { it.timestampNs == timestampNs }
        if (index < 0) return null
        return heldFrames.removeAt(index)
    }

    private fun deliverLocked(ticket: Ticket, frame: HeldFrame): Delivery? {
        if (frame.timestampNs <= staleBeforeTimestampNs) return null
        if (frame.timestampNs <= lastDeliveredTimestampNs) return null
        lastDeliveredTimestampNs = frame.timestampNs
        ticket.delivered = true
        return Delivery(ticket, frame.bytes)
    }

    companion object {
        /** 同一时刻只有一个请求在等，2 张足够覆盖"顺序反过来"的两种先后。 */
        const val DEFAULT_MAX_HELD_FRAMES: Int = 2
    }
}

/**
 * 显示方向的变化检测（REVIEW_R5 §4）。
 *
 * 为什么需要它：`displayRotationDegrees` 原来是"打开相机那一刻"的快照，
 * `updateViewSize` 只改宽高。旋转到 180°（或任何 view 尺寸不变的旋转）时
 * `onSurfaceTextureSizeChanged` 根本不会触发，预览矩阵与抓帧方向就停在旧角度。
 * 另外多数机型的 `onDisplayChanged` 会连续上报多次同一角度，
 * 同一角度重复上报**不能**重复发布矩阵、更不能重复换代。
 */
class CameraDisplayRotationTracker {
    private var last: Int? = null

    /** @return true 表示角度真的变了（需要重新发布矩阵并让抓帧票作废）。 */
    @Synchronized
    fun observe(degrees: Int): Boolean {
        val previous = last
        last = degrees
        return previous != degrees
    }

    @Synchronized
    fun current(): Int? = last

    @Synchronized
    fun reset() {
        last = null
    }
}
