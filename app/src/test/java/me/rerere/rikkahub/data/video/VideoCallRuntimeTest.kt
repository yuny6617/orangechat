/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 进程内桥接（`VideoCallRuntime`）的**作用域**与**所有权**。
 *
 * 这里覆盖 REVIEW_R3 §5/§6 的两条真实缺陷：
 * - 取消/完成的区别：`cancelTurnRequest` 必须撤销这次请求的留影许可，`completeTurnRequest` 保留；
 * - 挂断/换代之后晚到的帧与别的会话的"进入/挂断/清帧"不许动当前通话。
 */
class VideoCallRuntimeTest {
    private val conversationId = "conversation-1"
    private val otherConversation = "conversation-2"

    @Before
    fun setUp() {
        VideoCallRuntime.reset()
    }

    @After
    fun tearDown() {
        VideoCallRuntime.reset()
    }

    private fun frame(atMillis: Long = 1_000L, manual: Boolean = false) = VideoFrame(
        bytes = byteArrayOf(1),
        width = 1,
        height = 1,
        capturedAtMillis = atMillis,
        manual = manual,
    )

    private class Gateway(override val available: Boolean = true) : VideoCallCameraGateway {
        override val unavailableReason: String = if (available) "" else "相机不可用"
        override suspend fun capture(): VideoFrame? = null
    }

    @Test
    fun `only the owning call can end or replace the live one`() {
        assertTrue(VideoCallRuntime.enterVideoCall(conversationId))
        assertTrue("同一会话重入是幂等的", VideoCallRuntime.enterVideoCall(conversationId))
        // 另一个会话试图接管：拒绝，且当前通话仍然是它。
        assertFalse(VideoCallRuntime.enterVideoCall(otherConversation))
        assertTrue(VideoCallRuntime.isActive(conversationId))
        assertFalse(VideoCallRuntime.isActive(otherConversation))
        // 别的会话挂断：拒绝。
        assertFalse(VideoCallRuntime.exitVideoCall(otherConversation))
        assertTrue(VideoCallRuntime.isActive(conversationId))
        // 自己挂断：成功，之后一切失败关闭。
        assertTrue(VideoCallRuntime.exitVideoCall(conversationId))
        assertFalse(VideoCallRuntime.isActive(conversationId))
        assertNull(VideoCallRuntime.conversationId.value)
    }

    @Test
    fun `camera registration is scoped to the live call and rotates the camera epoch`() {
        assertFalse("没有通话时不能注册", VideoCallRuntime.registerCamera(conversationId, Gateway()))
        assertTrue(VideoCallRuntime.enterVideoCall(conversationId))
        val gateway = Gateway()
        assertFalse("别的会话不能注册", VideoCallRuntime.registerCamera(otherConversation, gateway))
        val before = VideoCallRuntime.currentCameraEpoch()
        assertTrue(VideoCallRuntime.registerCamera(conversationId, gateway))
        assertTrue("注册新相机换代", VideoCallRuntime.currentCameraEpoch() != before)
        assertTrue(VideoCallRuntime.cameraAvailable())
        // 别的会话注销：不影响当前通话。
        VideoCallRuntime.unregisterCamera(otherConversation, gateway)
        assertTrue(VideoCallRuntime.cameraAvailable())
        // 自己注销：换代、清空。
        val current = VideoCallRuntime.currentCameraEpoch()
        VideoCallRuntime.unregisterCamera(conversationId, gateway)
        assertFalse(VideoCallRuntime.cameraAvailable())
        assertTrue(VideoCallRuntime.currentCameraEpoch() != current)
    }

    @Test
    fun `a frame held for another call or an older camera generation is rejected`() {
        VideoCallRuntime.enterVideoCall(conversationId)
        val epoch = VideoCallRuntime.currentCameraEpoch()
        VideoCallRuntime.unregisterCamera(conversationId, null)
        assertFalse("相机换代前的帧不能塞回待发送队列", VideoCallRuntime.holdPendingFrame(frame(), conversationId, epoch))
        assertNull(VideoCallRuntime.pendingFrame())
        assertTrue(VideoCallRuntime.holdPendingFrame(frame(), conversationId, VideoCallRuntime.currentCameraEpoch()))
        assertNotNull(VideoCallRuntime.pendingFrame())
        // 别的会话：不接受
        VideoCallRuntime.clearPendingFrame(conversationId)
        assertFalse(VideoCallRuntime.holdPendingFrame(frame(), otherConversation, VideoCallRuntime.currentCameraEpoch()))
        // 别的会话也不能清当前通话的待发送帧
        assertTrue(VideoCallRuntime.holdPendingFrame(frame(), conversationId))
        assertFalse(VideoCallRuntime.clearPendingFrame(otherConversation))
        assertNotNull(VideoCallRuntime.pendingFrame())
        assertTrue(VideoCallRuntime.clearPendingFrame(conversationId))
        assertNull(VideoCallRuntime.pendingFrame())
    }

    @Test
    fun `manual frame wins over the timer and is consumed once`() {
        VideoCallRuntime.enterVideoCall(conversationId)
        assertTrue(VideoCallRuntime.holdPendingFrame(frame(atMillis = 1, manual = true), conversationId))
        // 定时器抓到的自动帧不许顶掉用户手动夹的那一帧
        assertFalse(VideoCallRuntime.holdPendingFrame(frame(atMillis = 2), conversationId))
        assertTrue(VideoCallRuntime.pendingFrame()?.manual == true)
        assertNotNull(VideoCallRuntime.takePendingFrame())
        assertNull(VideoCallRuntime.takePendingFrame())
        assertNull(VideoCallRuntime.pendingFrameAtMillis.value)
    }

    @Test
    fun `request token is the only gate for gifts and only one gift per turn`() {
        VideoCallRuntime.enterVideoCall(conversationId)
        assertFalse(VideoCallRuntime.beginTurnRequest(otherConversation, "t1"))
        assertTrue(VideoCallRuntime.beginTurnRequest(conversationId, "t1"))
        // 没有 token / 伪造 token / 别的会话：都不发放
        assertNull(VideoCallRuntime.grantGift(conversationId, null, "m1", VideoGift.HEART, 1L))
        assertNull(VideoCallRuntime.grantGift(conversationId, "forged", "m1", VideoGift.HEART, 1L))
        assertNull(VideoCallRuntime.grantGift(otherConversation, "t1", "m1", VideoGift.HEART, 1L))
        assertNotNull(VideoCallRuntime.grantGift(conversationId, "t1", "m1", VideoGift.HEART, 1L))
        // 同一轮第二枚：拒绝；同一条回复重复 finish：也拒绝
        assertNull(VideoCallRuntime.grantGift(conversationId, "t1", "m2", VideoGift.BOUQUET, 2L))
        assertNull(VideoCallRuntime.grantGift(conversationId, "t1", "m1", VideoGift.BOUQUET, 3L))
        assertEquals(1, VideoCallRuntime.tally().total)
        assertEquals(1, VideoCallRuntime.grantedGifts().size)
        // 重复登记同一个 token 不刷新名额（REVIEW_R3 §2）
        assertTrue(VideoCallRuntime.beginTurnRequest(conversationId, "t1"))
        assertNull(VideoCallRuntime.grantGift(conversationId, "t1", "m3", VideoGift.HEART, 4L))
        // 新一轮有自己的名额
        assertTrue(VideoCallRuntime.beginTurnRequest(conversationId, "t2"))
        assertNotNull(VideoCallRuntime.grantGift(conversationId, "t2", "m4", VideoGift.HEART, 5L))
    }

    @Test
    fun `completing a turn keeps its snapshot permit while cancelling revokes it`() {
        VideoCallRuntime.enterVideoCall(conversationId)
        assertTrue(VideoCallRuntime.beginTurnRequest(conversationId, "t1"))
        assertTrue(VideoCallRuntime.beginTurnRequest(conversationId, "t2"))
        val completed = VideoCallRuntime.requestSnapshot(conversationId, "t1", "m1", 1L)?.permit
        val cancelled = VideoCallRuntime.requestSnapshot(conversationId, "t2", "m2", 2L)?.permit
        assertNotNull(completed)
        assertNotNull(cancelled)

        VideoCallRuntime.completeTurnRequest("t1")
        assertTrue("正常完成后合法的异步留影仍然可以落盘", VideoCallRuntime.isSnapshotPermitUsable(completed))
        assertTrue(VideoCallRuntime.consumeSnapshotPermit(completed))
        assertFalse("许可只能消费一次", VideoCallRuntime.consumeSnapshotPermit(completed))

        VideoCallRuntime.cancelTurnRequest("t2")
        assertFalse("取消的这一轮，它的留影许可必须作废", VideoCallRuntime.isSnapshotPermitUsable(cancelled))
        assertFalse(VideoCallRuntime.consumeSnapshotPermit(cancelled))
    }

    @Test
    fun `snapshot request needs a live token and is served once per message`() {
        VideoCallRuntime.enterVideoCall(conversationId)
        VideoCallRuntime.beginTurnRequest(conversationId, "t1")
        assertNull(VideoCallRuntime.requestSnapshot(conversationId, "forged", "m1", 1L))
        assertNull(VideoCallRuntime.requestSnapshot(otherConversation, "t1", "m1", 1L))
        assertNotNull(VideoCallRuntime.requestSnapshot(conversationId, "t1", "m1", 1L))
        assertNull("同一条回复只登记一次", VideoCallRuntime.requestSnapshot(conversationId, "t1", "m1", 2L))
        assertNotNull(VideoCallRuntime.requestSnapshot(conversationId, "t1", "m2", 3L))
        // 挂断之后：一切失效
        VideoCallRuntime.exitVideoCall(conversationId)
        assertNull(VideoCallRuntime.requestSnapshot(conversationId, "t1", "m3", 4L))
        assertNull(VideoCallRuntime.grantGift(conversationId, "t1", "m3", VideoGift.HEART, 5L))
    }

    @Test
    fun `camera epoch snapshots invalidate late captures`() {
        VideoCallRuntime.enterVideoCall(conversationId)
        val epochAtCaptureStart = VideoCallRuntime.currentCameraEpoch()
        assertTrue(VideoCallRuntime.isCameraEpochCurrent(conversationId, epochAtCaptureStart))
        // 抓帧期间挂断/离开面板：回来时作用域已经失效
        VideoCallRuntime.unregisterCamera(conversationId, null)
        assertFalse(VideoCallRuntime.isCameraEpochCurrent(conversationId, epochAtCaptureStart))
        assertFalse(VideoCallRuntime.isCameraEpochCurrent(otherConversation, VideoCallRuntime.currentCameraEpoch()))
    }

    @Test
    fun `capture is fail closed when no camera is registered`() = runBlocking {
        assertNull(VideoCallRuntime.captureFrame())
        VideoCallRuntime.enterVideoCall(conversationId)
        assertNull(VideoCallRuntime.captureFrame())
        VideoCallRuntime.registerCamera(conversationId, Gateway(available = false))
        assertNull("相机不可用时不抓帧", VideoCallRuntime.captureFrame())
        VideoCallRuntime.registerCamera(conversationId, Gateway(available = true))
        // gateway 自己返回 null：仍然 fail closed，而不是编一帧出来
        assertNull(VideoCallRuntime.captureFrame())
    }

    @Test
    fun `cancellation from the camera gateway is never swallowed`() = runBlocking {
        VideoCallRuntime.enterVideoCall(conversationId)
        val cancelling = object : VideoCallCameraGateway {
            override val available: Boolean = true
            override val unavailableReason: String = ""
            override suspend fun capture(): VideoFrame? = throw CancellationException("cancelled")
        }
        VideoCallRuntime.registerCamera(conversationId, cancelling)
        val error = runCatching { VideoCallRuntime.captureFrame() }.exceptionOrNull()
        assertTrue("取消必须原样透传（吞掉会让已取消的协程继续写照片）", error is CancellationException)
    }

    @Test
    fun `gifts and tally are cleared when the call ends`() {
        VideoCallRuntime.enterVideoCall(conversationId)
        VideoCallRuntime.beginTurnRequest(conversationId, "t1")
        VideoCallRuntime.grantGift(conversationId, "t1", "m1", VideoGift.FIREWORKS, 1L)
        assertEquals(1, VideoCallRuntime.tally().total)
        val latest = VideoCallRuntime.latestGift.value
        assertNotNull(latest)
        VideoCallRuntime.exitVideoCall(conversationId)
        assertEquals(0, VideoCallRuntime.tally().total)
        assertTrue(VideoCallRuntime.grantedGifts().isEmpty())
        assertNull(VideoCallRuntime.latestGift.value)
    }

    // ------------------------------------------------------------------
    // REVIEW_R5 §2：控制器自己的一代结束时必须**同步**让运行时换代
    // ------------------------------------------------------------------

    @Test
    fun `a controller generation change invalidates the live call and clears the pending frame`() {
        VideoCallRuntime.enterVideoCall(conversationId)
        val gateway = Gateway()
        VideoCallRuntime.registerCamera(conversationId, gateway)
        val epoch = VideoCallRuntime.currentCameraEpoch()
        assertTrue(VideoCallRuntime.holdPendingFrame(frame(), conversationId, epoch))

        // 切镜头 / 退后台 / 打开失败 / 方向变化：控制器直接调用，不经过 Compose effect。
        assertTrue(VideoCallRuntime.notifyCameraGenerationChanged(gateway))
        assertTrue("相机代次必须真的变了", VideoCallRuntime.currentCameraEpoch() != epoch)
        assertNull("同一作用域里的待发送帧必须一起清掉", VideoCallRuntime.pendingFrame())
        assertNull(VideoCallRuntime.pendingFrameAtMillis.value)
        assertFalse(
            "持有旧 epoch 快照的帧不许再塞回队列",
            VideoCallRuntime.holdPendingFrame(frame(atMillis = 2L), conversationId, epoch),
        )
        // gateway 身份没变、通话还在：仍然可以按新 epoch 收帧。
        assertTrue(
            VideoCallRuntime.holdPendingFrame(
                frame(atMillis = 3L),
                conversationId,
                VideoCallRuntime.currentCameraEpoch(),
            ),
        )
    }

    @Test
    fun `a generation change from another gateway or after hang up is ignored`() {
        VideoCallRuntime.enterVideoCall(conversationId)
        val registered = Gateway()
        VideoCallRuntime.registerCamera(conversationId, registered)
        val epoch = VideoCallRuntime.currentCameraEpoch()

        assertFalse("别的 gateway 不能让当前通话换代", VideoCallRuntime.notifyCameraGenerationChanged(Gateway()))
        assertEquals(epoch, VideoCallRuntime.currentCameraEpoch())
        assertFalse("null gateway 也不能", VideoCallRuntime.notifyCameraGenerationChanged(null))

        VideoCallRuntime.exitVideoCall(conversationId)
        assertFalse("挂断之后没有任何作用域可以失效", VideoCallRuntime.notifyCameraGenerationChanged(registered))
    }

    @Test
    fun `a capture queued behind another one is discarded when the generation changes`() = runBlocking {
        VideoCallRuntime.enterVideoCall(conversationId)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val slow = object : VideoCallCameraGateway {
            override val available: Boolean = true
            override val unavailableReason: String = ""
            override suspend fun capture(): VideoFrame? {
                started.complete(Unit)
                release.await()
                return frame(atMillis = 1L)
            }
        }
        VideoCallRuntime.registerCamera(conversationId, slow)
        val epochBefore = VideoCallRuntime.currentCameraEpoch()

        var first: VideoFrame? = frame() // 占位，避免编译期把类型推断成 Nothing?
        var second: VideoFrame? = frame()
        val holder = launch(Dispatchers.Default) { first = VideoCallRuntime.captureFrame() }
        started.await()
        // UNDISPATCHED：这个协程会同步跑进 captureFrame，**先取好 epoch 快照**，
        // 然后才在互斥锁上挂起（第一个抓帧还卡在 gateway 里）。
        val queued = launch(start = CoroutineStart.UNDISPATCHED) {
            second = VideoCallRuntime.captureFrame()
        }
        // 排队期间换了相机代次：两个抓帧回来时作用域都已经失效。
        assertTrue(VideoCallRuntime.notifyCameraGenerationChanged(slow))
        release.complete(Unit)
        holder.join()
        queued.join()
        assertNull("抓着途中换代：这一帧不属于当前代", first)
        assertNull("排队前取的 epoch 快照在拿到锁之后必须复查，过期就不抓/丢掉", second)
        assertFalse(
            VideoCallRuntime.holdPendingFrame(frame(), conversationId, epochBefore),
        )
    }

    @Test
    fun `a frame captured across a camera swap is discarded`() = runBlocking {
        VideoCallRuntime.enterVideoCall(conversationId)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val slow = object : VideoCallCameraGateway {
            override val available: Boolean = true
            override val unavailableReason: String = ""
            override suspend fun capture(): VideoFrame? {
                started.complete(Unit)
                release.await()
                return frame(atMillis = 7L)
            }
        }
        VideoCallRuntime.registerCamera(conversationId, slow)
        var result: VideoFrame? = frame()
        val job = launch(Dispatchers.Default) { result = VideoCallRuntime.captureFrame() }
        started.await()
        // 切镜头 = 一个**新的 gateway 对象**注册进来。
        VideoCallRuntime.registerCamera(conversationId, Gateway())
        release.complete(Unit)
        job.join()
        assertNull("抓帧返回后必须复查 gateway 身份，旧对象抓到的帧一律丢弃", result)
    }

    // ------------------------------------------------------------------
    // REVIEW_R6 §3：stop/switch（= 控制器结束一代）必须让已发出的留影许可即刻失效
    // ------------------------------------------------------------------

    @Test
    fun `ending a camera generation invalidates a pending snapshot permit and its frame at once`() {
        VideoCallRuntime.enterVideoCall(conversationId)
        assertTrue(VideoCallRuntime.beginTurnRequest(conversationId, "t1"))
        val gateway = Gateway()
        VideoCallRuntime.registerCamera(conversationId, gateway)
        val epoch = VideoCallRuntime.currentCameraEpoch()
        assertTrue(VideoCallRuntime.holdPendingFrame(frame(), conversationId, epoch))
        val permit = VideoCallRuntime.requestSnapshot(conversationId, "t1", "m1", 1L)?.permit
        assertNotNull("先确认这条许可本来是有效的", permit)
        assertTrue(VideoCallRuntime.isSnapshotPermitUsable(permit))

        // 控制器 stop / 切镜头 / 退后台：在门闩的**同一临界区**里让运行时失效。
        assertTrue(VideoCallRuntime.notifyCameraGenerationChanged(gateway))

        assertFalse("stop/switch 之后旧的留影许可必须立刻失效", VideoCallRuntime.isSnapshotPermitUsable(permit))
        assertFalse("失效的许可不许再落盘", VideoCallRuntime.consumeSnapshotPermit(permit))
        assertNull("同一作用域的待发送帧一起作废", VideoCallRuntime.pendingFrame())
        assertNull(VideoCallRuntime.pendingFrameAtMillis.value)
        assertFalse(
            "持有旧 epoch 快照的帧也不许再写回",
            VideoCallRuntime.holdPendingFrame(frame(atMillis = 2L), conversationId, epoch),
        )
    }

    /**
     * 锁序回归（REVIEW_R6 §3）：控制器会在**持有相机门闩**时调用
     * [VideoCallRuntime.notifyCameraGenerationChanged]（gate → runtime），
     * 所以 runtime 侧任何读取 gateway 的地方都必须在自己的监视器**之外**。
     *
     * 这里用真实线程把两边卡在一起：如果 `requestSnapshot` 还是"持 runtime 监视器去问
     * gateway.available"，两个线程就会互相等锁，下面的 `get(3s)` 直接超时失败。
     */
    @Test
    fun `snapshot requests never take the runtime monitor while reading the camera gate`() {
        VideoCallRuntime.enterVideoCall(conversationId)
        assertTrue(VideoCallRuntime.beginTurnRequest(conversationId, "t1"))
        val gateLock = java.util.concurrent.locks.ReentrantLock()
        val gateHeld = java.util.concurrent.CountDownLatch(1)
        val gateway = object : VideoCallCameraGateway {
            // 模拟 controller 的 available getter：要拿相机门闩。
            override val available: Boolean
                get() = gateLock.lock().let {
                    try {
                        true
                    } finally {
                        gateLock.unlock()
                    }
                }
            override val unavailableReason: String = ""
            override suspend fun capture(): VideoFrame? = null
        }
        VideoCallRuntime.registerCamera(conversationId, gateway)

        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val controller = pool.submit {
                gateLock.lock()
                try {
                    gateHeld.countDown()
                    // 控制器的 gate.end 临界区：失效走 runtime 监视器。
                    VideoCallRuntime.notifyCameraGenerationChanged(gateway)
                } finally {
                    gateLock.unlock()
                }
            }
            val requester = pool.submit<VideoSnapshotRequest?> {
                gateHeld.await(2, java.util.concurrent.TimeUnit.SECONDS)
                VideoCallRuntime.requestSnapshot(conversationId, "t1", "lock-order", 1L)
            }
            // 让两边都有机会卡在锁上；有反向锁序的话这里就会永久互相等待。
            assertTrue(gateHeld.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val request = try {
                requester.get(3, java.util.concurrent.TimeUnit.SECONDS)
            } finally {
                controller.get(3, java.util.concurrent.TimeUnit.SECONDS)
            }
            assertNotNull("读相机门闩不能与 runtime 监视器交叉死锁", request)
        } finally {
            pool.shutdownNow()
        }
    }
}
