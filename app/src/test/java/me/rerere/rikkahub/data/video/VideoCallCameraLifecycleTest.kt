/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 相机生命周期的**确定性交错**回归（REVIEW_R5 §1/§3/§4）。
 *
 * 这里用的不是测试模型，而是生产代码里真正在用的两个纯组件：
 * - [CameraGenerationGate]：`VideoCallCameraController` 里所有状态迁移（回调 check+act、
 *   开始/结束一代、切镜头、方向变化）都在这把门闩里做；
 * - [CameraCaptureTickets]：`VideoCallCameraController` 里每一次抓帧的票据与时间戳对齐。
 *
 * 所以这里钉住的正是那两条被 REVIEW_R5 点名的行为：
 * 1. `@Synchronized isCurrent` 只包住一次 read，stop/switch 可以插在 check 与 act 之间；
 * 2. 时间戳未知的票据接受任意 JPEG，超时的旧图会被新请求当成本次画面。
 */
class VideoCallCameraLifecycleTest {

    // ------------------------------------------------------------------
    // §1 代次门闩：check 与 act 必须在同一把锁内
    // ------------------------------------------------------------------

    @Test
    fun `adopting a new generation hands the old one back for release outside the lock`() {
        val gate = CameraGenerationGate<Any>()
        val first = Any()
        val second = Any()
        assertNull("最开始没有当前代", gate.adopt(first))
        assertTrue(gate.isCurrent(first))
        // 换代的返回值就是"要在锁外释放"的那一代。
        assertSame(first, gate.adopt(second))
        assertTrue(gate.isCurrent(second))
        assertFalse(gate.isCurrent(first))
        assertSame(second, gate.end())
        assertNull(gate.currentOrNull())
        // 每次换代/结束都 +1，用于诊断（过期判断一律用对象身份）。
        val token = gate.currentToken()
        gate.adopt(first)
        assertEquals(token + 1, gate.currentToken())
    }

    @Test
    fun `a stale callback can never act even after reading the current generation`() {
        val gate = CameraGenerationGate<Any>()
        val generation = Any()
        gate.adopt(generation)
        // stop / 切镜头 / 打开失败：这一代已经不是当前代了。
        gate.end()
        val executed = AtomicBoolean(false)
        val result = gate.ifCurrent(generation) {
            executed.set(true)
            it
        }
        assertNull(result)
        assertFalse("过期回调的 block 绝不能执行（否则就会写回 Previewing/释放别人的设备）", executed.get())
        assertNull(gate.currentOrNull())
    }

    /**
     * 真正的多线程交错：回调在"检查通过之后、动手之前"被挂起，这时候 stop 必须被
     * 挡在同一把锁外面——旧实现在这里会让 stop 插进来，然后旧回调用已释放的设备
     * `setRepeatingRequest` 并把 status 写回 Previewing。
     */
    @Test
    fun `stop cannot interleave between the check and the act of a camera callback`() {
        val gate = CameraGenerationGate<Any>()
        val generation = Any()
        gate.adopt(generation)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val entered = CountDownLatch(1)
        val allowAct = CountDownLatch(1)
        val callback = Thread {
            gate.ifCurrent(generation) {
                events += "check:current"
                entered.countDown()
                // 模拟"接管资源 + 下发请求 + 发布状态"这段真实的非挂起动作。
                allowAct.await()
                events += "act:takeover+setRepeating+publish"
            }
        }
        callback.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val stopper = Thread {
            gate.exclusive {
                gate.end()
                events += "stop:end"
            }
        }
        stopper.start()
        // stop 此刻必须一行都没执行：它被挡在门闩外面。
        Thread.sleep(100L)
        assertEquals(listOf("check:current"), events.toList())

        allowAct.countDown()
        callback.join(5_000L)
        stopper.join(5_000L)
        // 顺序必然是"回调完整做完 → 才轮到 stop"，中间不可能插进半截。
        assertEquals(
            listOf("check:current", "act:takeover+setRepeating+publish", "stop:end"),
            events.toList(),
        )
    }

    @Test
    fun `a callback that loses the race never touches the new generation`() {
        val gate = CameraGenerationGate<Any>()
        val oldGeneration = Any()
        val newGeneration = Any()
        gate.adopt(oldGeneration)
        // 切镜头：old 被摘除、new 成为当前代。
        gate.adopt(newGeneration)
        val touched = AtomicBoolean(false)
        assertNull(gate.ifCurrent(oldGeneration) { touched.set(true) })
        assertFalse(touched.get())
        assertTrue("新一代仍然是当前代", gate.isCurrent(newGeneration))
    }

    // ------------------------------------------------------------------
    // §3 抓帧票据：未知时间戳绝不接受任意 JPEG
    // ------------------------------------------------------------------

    @Test
    fun `an unknown timestamp never accepts an arbitrary jpeg`() {
        val tickets = CameraCaptureTickets()
        val ticket = tickets.begin()
        // onCaptureStarted 还没到：这张图只能有界暂存，绝不能被当成本次结果。
        assertNull(tickets.offer(timestampNs = 1_000L, bytes = byteArrayOf(1)))
        assertEquals(1, tickets.heldFrameCount())
        assertEquals(Long.MIN_VALUE, tickets.lastDeliveredTimestampNs())

        // 真正的请求时间戳是 2_000：暂存的 1_000 对不上，不能被认领。
        assertNull(tickets.noteCaptureStarted(ticket, 2_000L))
        assertEquals(Long.MIN_VALUE, tickets.lastDeliveredTimestampNs())

        // 严格相等的那张才可以交付。
        val delivery = tickets.offer(timestampNs = 2_000L, bytes = byteArrayOf(2))
        assertNotNull(delivery)
        assertSame(ticket, delivery!!.ticket)
        assertEquals(2_000L, tickets.lastDeliveredTimestampNs())
        assertFalse(tickets.isPending(ticket))
    }

    @Test
    fun `an early jpeg is matched only when the timestamp is exactly equal`() {
        val tickets = CameraCaptureTickets()
        val ticket = tickets.begin()
        // ImageReader 的回调先到（顺序没有保证），票据还不知道自己的时间戳。
        assertNull(tickets.offer(timestampNs = 1_500L, bytes = byteArrayOf(15)))
        val delivery = tickets.noteCaptureStarted(ticket, 1_500L)
        assertNotNull(delivery)
        assertEquals(15, delivery!!.bytes[0].toInt())
        // 同一张票据不会交付第二次。
        assertNull(tickets.offer(timestampNs = 1_500L, bytes = byteArrayOf(15)))
    }

    @Test
    fun `early frames are held in a bounded buffer`() {
        val tickets = CameraCaptureTickets(maxHeldFrames = 2)
        val ticket = tickets.begin()
        assertNull(tickets.offer(10L, byteArrayOf(10)))
        assertNull(tickets.offer(20L, byteArrayOf(20)))
        assertNull(tickets.offer(30L, byteArrayOf(30)))
        assertEquals(2, tickets.heldFrameCount())
        // 最旧的（10）已经被挤掉：票据时间戳等于 10 时什么也拿不到。
        assertNull(tickets.noteCaptureStarted(ticket, 10L))

        // 还在缓冲里的那一张仍然可以被精确匹配。
        val fresh = CameraCaptureTickets(maxHeldFrames = 2)
        val matched = fresh.begin()
        assertNull(fresh.offer(10L, byteArrayOf(10)))
        assertNull(fresh.offer(20L, byteArrayOf(20)))
        val delivery = fresh.noteCaptureStarted(matched, 20L)
        assertNotNull(delivery)
        assertEquals(20, delivery!!.bytes[0].toInt())
    }

    @Test
    fun `a timed out request never hands its late frame to the next capture`() {
        val tickets = CameraCaptureTickets()
        val timedOut = tickets.begin()
        tickets.noteCaptureStarted(timedOut, 1_000L)
        // 超时：作废票据并把已知时间戳记成作废水位。
        tickets.end(timedOut)
        assertEquals(1_000L, tickets.staleBeforeTimestampNs())

        val next = tickets.begin()
        // 旧请求的那张图迟到：<= 作废水位，必须直接丢掉。
        assertNull(tickets.offer(1_000L, byteArrayOf(99)))
        assertEquals(Long.MIN_VALUE, tickets.lastDeliveredTimestampNs())
        // 新请求自己的图照常交付。
        assertNull("新票据还没有时间戳时谁都不认", tickets.noteCaptureStarted(next, 5_000L))
        assertNotNull(tickets.offer(5_000L, byteArrayOf(5)))
    }

    /**
     * REVIEW_R5 §3 点名的具体顺序：**旧请求超时发生在 onCaptureStarted 之前**
     * （所以旧请求的时间戳一直未知），旧图晚到，而新票据的时间戳也还不知道。
     * 旧实现会在这里把旧图当成本次结果。
     */
    @Test
    fun `a late frame of an unknown stamped request is never adopted by the next ticket`() {
        val tickets = CameraCaptureTickets()
        val timedOut = tickets.begin()
        // 超时之前 onCaptureStarted 没到：没有作废水位可用。
        tickets.end(timedOut)
        assertEquals(Long.MIN_VALUE, tickets.staleBeforeTimestampNs())

        val next = tickets.begin()
        // 旧请求的图（时间戳 7_000）晚到：新票据时间戳未知 → 只能暂存，绝不交付。
        assertNull(tickets.offer(7_000L, byteArrayOf(7)))
        assertEquals(Long.MIN_VALUE, tickets.lastDeliveredTimestampNs())
        // 新请求真正的时间戳是 9_000：暂存的那张对不上，依然不交付。
        assertNull(tickets.noteCaptureStarted(next, 9_000L))
        assertEquals(Long.MIN_VALUE, tickets.lastDeliveredTimestampNs())
        // 只有新请求自己的图会被交付。
        val delivery = tickets.offer(9_000L, byteArrayOf(9))
        assertNotNull(delivery)
        assertEquals(9, delivery!!.bytes[0].toInt())
    }

    @Test
    fun `ending a ticket clears the held frames and stale frames stay rejected`() {
        val tickets = CameraCaptureTickets()
        val ticket = tickets.begin()
        assertNull(tickets.offer(4_000L, byteArrayOf(4)))
        assertEquals(1, tickets.heldFrameCount())
        tickets.end(ticket)
        assertEquals("超时必须清掉暂存（否则迟到的旧图会一直留在内存里）", 0, tickets.heldFrameCount())
        assertNull(tickets.openTicketOrNull())
        assertNull("票据结束后不再接受任何帧", tickets.offer(4_000L, byteArrayOf(4)))
        assertNull(tickets.offer(5_000L, byteArrayOf(5)))
    }

    @Test
    fun `frames must be strictly increasing within a generation`() {
        val tickets = CameraCaptureTickets()
        val first = tickets.begin()
        tickets.noteCaptureStarted(first, 100L)
        assertNotNull(tickets.offer(100L, byteArrayOf(1)))
        tickets.end(first)

        val second = tickets.begin()
        tickets.noteCaptureStarted(second, 90L)
        // 90 <= 已经交付过的 100：旧帧，丢弃。
        assertNull(tickets.offer(90L, byteArrayOf(2)))
    }

    @Test
    fun `beginning a new ticket abandons the previous one and its held frames`() {
        val tickets = CameraCaptureTickets()
        val first = tickets.begin()
        assertNull(tickets.offer(1_000L, byteArrayOf(1)))
        val second = tickets.begin()
        assertFalse(tickets.isPending(first))
        assertTrue(tickets.isPending(second))
        assertEquals(0, tickets.heldFrameCount())
        // 旧票据再补时间戳也不会交付任何东西。
        assertNull(tickets.noteCaptureStarted(first, 1_000L))
    }

    // ------------------------------------------------------------------
    // §4 显示方向：180° 且 view 尺寸不变时也必须重新发布
    // ------------------------------------------------------------------

    @Test
    fun `rotation tracker ignores repeated reports of the same angle`() {
        val tracker = CameraDisplayRotationTracker()
        assertTrue("第一次观察要算变化（需要建立初始值）", tracker.observe(0))
        assertFalse("同一角度连发（多数机型 onDisplayChanged 会连发）不重复处理", tracker.observe(0))
        assertTrue("0 → 180 必须被发现", tracker.observe(180))
        assertFalse(tracker.observe(180))
        assertTrue(tracker.observe(270))
        assertEquals(270, tracker.current())
        tracker.reset()
        assertNull(tracker.current())
        assertTrue(tracker.observe(0))
    }

    /**
     * 180° 旋转时 view 尺寸不变，`onSurfaceTextureSizeChanged` 不会触发，所以必须靠
     * DisplayManager 监听。这里证明"同样的 buffer/view 尺寸下，显示角度变了矩阵就真的变了"，
     * 也就是说尺寸没变 ≠ 画面不需要刷新。
     */
    @Test
    fun `a display rotation of 180 degrees changes the transform even with identical sizes`() {
        val upright = VideoPreviewGeometry.textureViewTransform(
            sensorOrientation = 90,
            displayRotationDegrees = 0,
            frontFacing = false,
            bufferWidth = 1280,
            bufferHeight = 720,
            viewWidth = 1080,
            viewHeight = 1920,
        )
        val upsideDown = VideoPreviewGeometry.textureViewTransform(
            sensorOrientation = 90,
            displayRotationDegrees = 180,
            frontFacing = false,
            bufferWidth = 1280,
            bufferHeight = 720,
            viewWidth = 1080,
            viewHeight = 1920,
        )
        assertEquals("尺寸没变，但旋转角必须变", 180, (upsideDown.rotationDegrees - upright.rotationDegrees + 360) % 360)
        assertTrue("矩阵必须真的不同（否则屏幕上还是旧的朝向）", upright.matrix != upsideDown.matrix)
        // 180° 不交换宽高：所以这条路径永远不可能靠 view 尺寸变化被发现。
        assertFalse(VideoPreviewGeometry.swapped(1280, 720, 180))
    }

    @Test
    fun `surface rotation constants map to the four display degrees`() {
        assertEquals(
            listOf(0, 90, 180, 270, 0),
            listOf(0, 1, 2, 3, 7).map { VideoPreviewGeometry.displayRotationDegrees(it) },
        )
    }

    // ------------------------------------------------------------------
    // REVIEW_R6 §3：换代时"运行时失效"必须在 gate.end 的同一临界区、
    // 且在相机资源释放之前；排队中的 start 必须重验 surface 归属
    // ------------------------------------------------------------------

    /**
     * stop / 切镜头 = 结束一代。这里用生产代码里真正在用的
     * [CameraGenerationLifecycle] 驱动真实 [CameraGenerationGate] 与 [VideoCallRuntime]：
     * - 失效回调执行时，另一个线程拿不到门闩（证明与 gate.end 同临界区）；
     * - 释放回调执行时，旧留影许可已经失效（证明失效在释放之前）。
     */
    @Test
    fun `ending a generation invalidates the runtime inside the gate critical section before release`() {
        val call = "lifecycle-call"
        VideoCallRuntime.reset()
        try {
            assertTrue(VideoCallRuntime.enterVideoCall(call))
            assertTrue(VideoCallRuntime.beginTurnRequest(call, "t1"))
            val gateway = object : VideoCallCameraGateway {
                override val available: Boolean = true
                override val unavailableReason: String = ""
                override suspend fun capture(): VideoFrame? = null
            }
            VideoCallRuntime.registerCamera(call, gateway)
            val permit = VideoCallRuntime.requestSnapshot(call, "t1", "m1", 1L)?.permit
            assertNotNull("留影许可必须先真的发出去", permit)
            assertTrue(VideoCallRuntime.isSnapshotPermitUsable(permit))

            val gate = CameraGenerationGate<Any>()
            gate.adopt(Any())
            val events = Collections.synchronizedList(mutableListOf<String>())
            var otherEnteredWhileInvalidating = false
            var permitUsableAtRelease: Boolean? = null
            lateinit var other: Thread
            val lifecycle = CameraGenerationLifecycle(
                gate = gate,
                invalidateRuntime = {
                    // 另一个线程此时应该被门闩挡住：说明我们还处在 gate.end 的临界区里。
                    other = Thread { gate.exclusive { events += "other-entered" } }
                    other.start()
                    Thread.sleep(100L)
                    otherEnteredWhileInvalidating = events.contains("other-entered")
                    events += "invalidate"
                    VideoCallRuntime.notifyCameraGenerationChanged(gateway)
                },
                release = {
                    events += "release"
                    permitUsableAtRelease = VideoCallRuntime.isSnapshotPermitUsable(permit)
                },
            )

            lifecycle.end()
            other.join(5_000L)

            assertEquals(listOf("invalidate", "release", "other-entered"), events.toList())
            assertFalse("运行时失效必须与 gate.end 在同一个临界区", otherEnteredWhileInvalidating)
            assertFalse("失效必须发生在资源释放之前（释放时许可已经不可用）", permitUsableAtRelease ?: true)
            assertFalse("结束后旧许可一律作废", VideoCallRuntime.isSnapshotPermitUsable(permit))
            assertNull("结束后不再有当前代", gate.currentOrNull())
        } finally {
            VideoCallRuntime.reset()
        }
    }

    @Test
    fun `a queued camera start is rejected once its surface or the preview gate is gone`() {
        val surface = Any()
        val texture = Any()
        assertTrue(cameraStartAllowed(true, surface, surface, texture, texture))
        assertFalse("预览门禁已经关了：不许再开", cameraStartAllowed(false, surface, surface, texture, texture))
        assertFalse(
            "Surface 已经被 detach/替换：之前排队的 start 不许再开旧相机",
            cameraStartAllowed(true, null, surface, texture, texture),
        )
        assertFalse(
            "Texture 已经换了：旧 surface 不属于这一代",
            cameraStartAllowed(true, surface, surface, Any(), texture),
        )
        assertFalse(
            "null surface 一律拒绝",
            cameraStartAllowed(true, surface, null, texture, texture),
        )
    }
}
