/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 由视频面板实现的相机入口：面板不在前台时就是 null，服务端因此知道"现在没有画面"。 */
interface VideoCallCameraGateway {
    val available: Boolean
    val unavailableReason: String
    suspend fun capture(): VideoFrame?
    /** Synchronously release hardware when the user closes the camera. Never opens it. */
    fun closeCamera() {}
}

/** UI 上的一枚礼物气泡 / 一次全屏特效请求。id 用来去重（重放不会重复播）。 */
data class VideoGiftEvent(
    val id: String,
    val gift: VideoGift,
    val conversationId: String,
    val turnMessageId: String,
    val atMillis: Long,
)

/**
 * 模型用 `<ws_vsnap/>` 请求留影。一次回复最多一条，按消息 ID 去重。
 *
 * [permit] 是这次异步留影唯一的授权凭证：抓帧、落盘、写消息之前都要用它校验
 * "电话还是这通、相机还是这一代"，并且只能消费一次。
 */
data class VideoSnapshotRequest(
    val id: String,
    val conversationId: String,
    val turnMessageId: String,
    val cameraAvailable: Boolean,
    val atMillis: Long,
    val permit: VideoSnapshotPermit? = null,
)

/**
 * 视频通话进程内桥接。
 *
 * - 输出转换器需要知道"当前这通视频通话属于哪个会话"，才能把礼物卡挂到正确的回复上；
 * - 视频面板把自己的相机入口注册进来，服务端据此完成 `<ws_vsnap/>` 留影和每轮抓帧；
 * - 礼物去重台账、待发送帧、礼物计数都集中在这里，挂断时统一清空。
 *
 * 作用域是**失败关闭**的（fail closed），而且授权**不是**从消息历史里推断的：
 * - 没有进行中的视频通话时，任何 `null` 会话都不算"有效"；
 * - 只有这通通话自己生成、随队列项不可变传进来的 `requestToken` 才有效
 *   （见 [VideoCallTurnLedger]）。"同一个会话里最后一条用户消息"不再是授权依据，
 *   因此聊天页手打的一轮、后台主动消息、工具重放都偷不到通话状态；
 * - 统计用独立的累计值，不受 UI 历史列表上限影响；
 * - 相机入口按通话作用域注册：进另一个会话的视频页不会替换本通话的相机。
 */
object VideoCallRuntime {
    /** UI 上最多保留多少枚历史礼物气泡（只影响展示，不影响总数）。 */
    private const val MAX_GIFT_EVENTS = 100

    @Volatile private var cameraEnabled = true

    /** User intent survives surface/permission changes and same-call re-entry. */
    fun setCameraEnabled(conversationId: String, enabled: Boolean): Boolean {
        val gateway = synchronized(this) {
            if (!isActive(conversationId)) return false
            if (cameraEnabled == enabled) return true
            cameraEnabled = enabled
            turns.rotateCameraEpoch()
            frames.clear()
            _pendingFrameAtMillis.value = null
            _camera.value
        }
        // Never call a gateway under the runtime monitor (gate -> runtime lock order).
        if (!enabled) gateway?.closeCamera()
        return true
    }

    fun isCameraEnabled(): Boolean = cameraEnabled

    private val captureMutex = Mutex()
    private val ledger = VideoGiftLedger()
    private val frames = VideoFrameCache()

    /** 请求 token / 通话代次 / 留影许可的台账（纯逻辑，可单测）。 */
    private val turns = VideoCallTurnLedger()

    private val _conversationId = MutableStateFlow<String?>(null)
    val conversationId: StateFlow<String?> = _conversationId.asStateFlow()

    private val _camera = MutableStateFlow<VideoCallCameraGateway?>(null)
    val camera: StateFlow<VideoCallCameraGateway?> = _camera.asStateFlow()

    private val _gifts = MutableStateFlow<List<VideoGiftEvent>>(emptyList())
    val gifts: StateFlow<List<VideoGiftEvent>> = _gifts.asStateFlow()

    private val _latestGift = MutableStateFlow<VideoGiftEvent?>(null)
    val latestGift: StateFlow<VideoGiftEvent?> = _latestGift.asStateFlow()

    private val _pendingFrameAtMillis = MutableStateFlow<Long?>(null)
    val pendingFrameAtMillis: StateFlow<Long?> = _pendingFrameAtMillis.asStateFlow()

    private val _giftEvents = MutableSharedFlow<VideoGiftEvent>(extraBufferCapacity = 32)
    val giftEvents: SharedFlow<VideoGiftEvent> = _giftEvents.asSharedFlow()

    private val _snapshotRequests = MutableSharedFlow<VideoSnapshotRequest>(extraBufferCapacity = 16)
    val snapshotRequests: SharedFlow<VideoSnapshotRequest> = _snapshotRequests.asSharedFlow()

    private val snapshotServedMessageIds = LinkedHashSet<String>()

    /** 独立累计值：真实总数，不会被 MAX_GIFT_EVENTS 截断。 */
    private var tallyAccumulator: VideoGiftTally = VideoGiftTally()

    /**
     * 进入一通视频通话。
     *
     * 只有**没有别的通话在跑**时才能接管：另一个会话的视频页/旧协调器即便还活着，
     * 也不允许把当前通话的相机、待发送帧、礼物台账清掉（那会让正在进行的通话掉画面）。
     *
     * @return true 表示这通通话现在生效（重入同一会话也算成功）。
     */
    fun enterVideoCall(conversationId: String): Boolean {
        val target = conversationId.trim()
        if (target.isEmpty()) return false
        synchronized(this) {
            val active = _conversationId.value
            if (active != null && active != target) return false
            if (active != target) {
                clearCallStateLocked()
                _conversationId.value = target
            }
            // 同一通话重入也走一次：台账自己保证幂等（只有换会话才清授权、递增代次）。
            turns.beginCall(target)
        }
        return true
    }

    /**
     * 挂断 / 退后台：相机、待发送帧、礼物台账、所有请求 token 全部失效。
     *
     * [conversationId] 明确给出时只结束**这一通**：别的会话的调用不能顺手把当前通话清掉。
     *
     * @return true 表示这次调用真的结束了那通通话。
     */
    fun exitVideoCall(conversationId: String? = null): Boolean {
        val asked = conversationId?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(this) {
            if (asked != null && _conversationId.value != asked) return false
            _conversationId.value = null
            _camera.value = null
            clearCallStateLocked()
        }
        turns.endCall()
        return true
    }

    /**
     * 是否处于"这通视频通话里"。传 null 一律 false：
     * 未明确声明属于当前通话的调用方，不许借用通话状态。
     *
     * 注意：这只是"会话级"作用域判断；送礼/留影还要额外的请求 token 授权。
     *
     * 与其他作用域读写共用同一把 runtime 监视器（REVIEW_R6 §3）。
     */
    @Synchronized
    fun isActive(conversationId: String?): Boolean {
        val active = _conversationId.value ?: return false
        val asked = conversationId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return asked == active
    }

    /**
     * 提交一轮"由这通视频通话发起"的对话之前登记请求 token。
     *
     * token 由服务生成并随队列项不可变传递，所以这里不需要（也不允许）从消息历史里
     * 反推"哪条用户消息是这一轮的触发者"。
     */
    fun beginTurnRequest(conversationId: String?, requestToken: String?): Boolean =
        turns.beginTurn(conversationId, requestToken) != null

    /**
     * 这一轮**正常结束**：关掉 token，但保留它已经发出的留影许可
     * （合法的异步留影本来就落在请求结束之后）。
     */
    fun completeTurnRequest(requestToken: String?) {
        turns.completeTurn(requestToken)
    }

    /**
     * 这一轮被**取消 / 打断 / 失败**：关掉 token，并撤销这次请求发出的全部留影许可。
     * 被打断的回复不该还能把画面写进聊天。
     */
    fun cancelTurnRequest(requestToken: String?) {
        turns.cancelTurn(requestToken)
    }

    fun isTurnGranted(conversationId: String?, requestToken: String?): Boolean =
        turns.isGranted(conversationId, requestToken)

    /** Returns only the original camera generation granted to this request, never a renewed one. */
    @Synchronized
    fun cameraEpochForTurn(conversationId: String?, requestToken: String?): Long? {
        if (!cameraEnabled || !isActive(conversationId)) return null
        return turns.cameraEpochForTurn(conversationId, requestToken)
    }

    fun currentCallEpoch(): Long = turns.currentCallEpoch()

    fun currentCameraEpoch(): Long = turns.currentCameraEpoch()

    /**
     * 抓帧是挂起操作：抓帧**之前**记下相机代次，回来之后用这个确认
     * "还是同一通电话、还是同一代相机"。
     *
     * 没有这道校验时，挂断/离开面板之后才返回的那一帧会重新 hold 进待发送队列，
     * 于是下一句会把一张已经过期的画面发出去。
     */
    fun isCameraEpochCurrent(conversationId: String?, cameraEpoch: Long): Boolean =
        cameraEnabled && isActive(conversationId) && turns.currentCameraEpoch() == cameraEpoch

    /**
     * 注册相机入口。
     *
     * 只有**当前这通视频通话**才能注册自己的相机：进入另一个会话的视频页时不会替换掉
     * 本通话的 gateway（旧实现无条件替换，会串台）。
     *
     * 与 enter/exit/notify/hold 共用同一把 runtime 监视器：作用域判断与"换 gateway/换代"
     * 不会互相插进来（REVIEW_R6 §3）。
     *
     * @return false 表示当前没有匹配的通话，注册被拒绝。
     */
    @Synchronized
    fun registerCamera(conversationId: String?, gateway: VideoCallCameraGateway?): Boolean {
        val target = gateway ?: return false
        if (!isActive(conversationId)) return false
        val changed = _camera.value !== target
        _camera.value = target
        if (changed) turns.rotateCameraEpoch()
        return true
    }

    @Synchronized
    fun unregisterCamera(conversationId: String?, gateway: VideoCallCameraGateway?) {
        if (!isActive(conversationId)) return
        if (gateway == null || _camera.value === gateway) {
            _camera.value = null
            turns.rotateCameraEpoch()
        }
    }

    /**
     * 相机控制器**自己的一代结束了**（REVIEW_R5 §2 / REVIEW_R6 §3）。
     *
     * 由 `VideoCallCameraController` 在切镜头 / 离开面板 / 退后台 / 打开失败 / 屏幕方向变化
     * 时**同步**调用（不依赖晚到的 Compose effect），而且是在门闩的**同一临界区**里调用
     * （`CameraGenerationLifecycle`），所以"new 一代已经生效、runtime 还认旧代"的窗口不存在。
     *
     * **锁序是 gate → runtime**：这里允许在持有相机门闩时进入 runtime 监视器，
     * 因此 runtime 侧任何读取 gateway 的地方（[cameraAvailable]、[cameraUnavailableReason]、
     * [captureFrame]、[requestSnapshot]）都**不持**这把监视器去调 gateway——
     * 否则会和"持门闩等 runtime 锁"形成反向锁序（REVIEW_R6 §3）。
     *
     * 作用域是"本通话 + 就是这个 gateway 对象"：
     * - 没注册（另一个会话的控制器、或已注销）→ 空操作，绝不误伤当前通话；
     * - 注册着 → 立刻让相机代次失效，并在**同一个临界区**里清掉待发送帧，
     *   所以"失效了但帧还在"这个窗口不存在（排队中的抓帧回来时 epoch 已经不匹配）。
     *
     * @return true 表示这次真的让当前通话的相机代次失效了。
     */
    @Synchronized
    fun notifyCameraGenerationChanged(gateway: VideoCallCameraGateway?): Boolean {
        val target = gateway ?: return false
        val conversation = _conversationId.value ?: return false
        if (_camera.value !== target) return false
        if (!isActive(conversation)) return false
        turns.rotateCameraEpoch()
        frames.clear()
        _pendingFrameAtMillis.value = null
        return true
    }

    /** 读相机状态：**绝不**在持 runtime 监视器时调用（它会去要相机门闩）。 */
    fun cameraAvailable(): Boolean = cameraEnabled && _camera.value?.available == true

    /** 读相机不可用原因：同样不许在持 runtime 监视器时调用。 */
    fun cameraUnavailableReason(): String =
        if (!cameraEnabled) "摄像头已关闭" else
            _camera.value?.unavailableReason?.takeIf { it.isNotBlank() } ?: "相机未就绪（视频画面不在前台）"

    /**
     * 需要一帧：优先用手动/定时夹住的待发送帧，没有就现场抓。
     *
     * 抓帧是挂起操作：取消必须原样透传（`CancellationException` 不是"抓帧失败"，
     * 吞掉它会让已经取消的协程继续跑下去并写出不该存在的照片）。
     *
     * 排队也是作用域的一部分（REVIEW_R5 §2）：拿到互斥锁**之后**必须再确认一次
     * "还是这通电话、还是这个 gateway、还是这一代相机"，抓完再确认一次。
     * 不这样做的话，一个在锁上排队的抓帧会用已经被换掉的旧 gateway 抓画面
     * （面板退后台/切镜头之后仍然能抓到一帧旧画面）。
     */
    suspend fun captureFrame(): VideoFrame? {
        if (!cameraEnabled) return null
        val conversation = _conversationId.value ?: return null
        val gateway = _camera.value ?: return null
        val epoch = turns.currentCameraEpoch()
        if (!gateway.available) return null
        return captureMutex.withLock {
            // 排队期间可能已经挂断 / 换相机 / 换代：绝不拿旧 gateway 抓。
            val current = _camera.value
            if (current == null || current !== gateway) return null
            if (!isCameraEpochCurrent(conversation, epoch)) return null
            if (!current.available) return null
            val frame = try {
                current.capture()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                null
            } ?: return null
            // 抓帧返回之后再确认一次：抓的过程中换代/挂断的这一帧必须丢掉。
            if (!isCameraEpochCurrent(conversation, epoch)) return null
            if (_camera.value !== gateway) return null
            frame
        }
    }

    /**
     * 定时/手动抓到的帧：只替换内存里的"待发送帧"，不会自己发出去。
     *
     * [conversationId] / [cameraEpoch] 是**抓帧之前**记下的作用域快照（见 [currentCameraEpoch]）：
     * 抓帧期间可能挂断、离开面板或换相机，此时这一帧必须被丢掉，而不是悄悄塞回待发送队列。
     * 两者都不传时退回"只接受当前这一通"的宽松判断，供不带作用域的调用方使用。
     *
     * 与 [notifyCameraGenerationChanged] 共用同一把监视器：相机换代（含清帧）与
     * "epoch 校验 + 写入"不会互相插进来，否则会出现"换代刚清完帧，旧帧又被写回"。
     *
     * @return false 表示这一帧没被接受（作用域已失效，或手动帧正在等待发送、自动帧不覆盖它）。
     */
    @Synchronized
    fun holdPendingFrame(
        frame: VideoFrame,
        conversationId: String? = null,
        cameraEpoch: Long? = null,
    ): Boolean {
        if (!cameraEnabled) return false
        val scopeConversation = conversationId?.trim()?.takeIf { it.isNotEmpty() }
        if (scopeConversation != null) {
            if (!isActive(scopeConversation)) return false
        } else if (_conversationId.value == null) {
            return false
        }
        if (cameraEpoch != null && turns.currentCameraEpoch() != cameraEpoch) return false
        if (!frames.offer(frame)) return false
        _pendingFrameAtMillis.value = frame.capturedAtMillis
        return true
    }

    /**
     * 待发送帧的读取/取出/清理与 [holdPendingFrame]、[notifyCameraGenerationChanged]
     * 共用**同一把 runtime 监视器**（REVIEW_R6 §3）：作用域一致，不会出现
     * "换代刚清完帧、读取者又拿到旧帧"或"清帧与写入交错"的顺序。
     */
    @Synchronized
    fun pendingFrame(): VideoFrame? = frames.peek()

    @Synchronized
    fun takePendingFrame(): VideoFrame? = frames.take().also {
        if (it != null) _pendingFrameAtMillis.value = null
    }

    /**
     * 清掉"待发送帧"。
     *
     * [conversationId] 明确给出时只有"当前通话就是它"才清：另一路会话的页面销毁/退后台
     * 不许把正在进行的那通电话刚夹住的画面清掉。
     *
     * @return true 表示真的清了（false：作用域不属于当前通话）。
     */
    @Synchronized
    fun clearPendingFrame(conversationId: String? = null): Boolean {
        val asked = conversationId?.trim()?.takeIf { it.isNotEmpty() }
        if (asked != null && !isActive(asked)) return false
        frames.clear()
        _pendingFrameAtMillis.value = null
        return true
    }

    /**
     * 由输出转换器在"生成结束"阶段调用。
     *
     * 生效条件（缺一不可）：
     * 1. 视频通话正在进行，且 [conversationId] 明确等于当前通话；
     * 2. [requestToken] 是这通通话自己生成、并随这个请求一路传下来的 token（还没被关闭）；
     * 3. 本轮还没送过礼物，且这条回复的 ID 没在台账里出现过。
     *
     * 重放、重入、停止旧请求后重新生成都不会让同一条回复再送一次礼物。
     */
    @Synchronized
    fun grantGift(
        conversationId: String?,
        requestToken: String?,
        messageId: String,
        gift: VideoGift?,
        atMillis: Long,
    ): VideoGift? {
        val active = _conversationId.value ?: return null
        if (conversationId?.trim() != active) return null
        // 先确认协议里真的有合法礼物，再去占用本轮名额：否则一次"没有礼物"的
        // finish 会把这一轮唯一的发放名额白白消耗掉。
        if (gift == null) return null
        // 同一条回复的重复 finish 直接返回：既不重复发，也不重复消耗名额。
        if (ledger.giftOf(messageId) != null) return null
        if (!turns.consumeGiftGrant(conversationId, requestToken)) return null
        val granted = ledger.grant(messageId, gift) ?: return null
        tallyAccumulator = tallyAccumulator.plus(granted)
        val event = VideoGiftEvent("$messageId:${granted.id}", granted, active, messageId, atMillis)
        _gifts.value = (_gifts.value + event).takeLast(MAX_GIFT_EVENTS)
        _latestGift.value = event
        _giftEvents.tryEmit(event)
        return granted
    }

    /**
     * 模型请求留影（`<ws_vsnap/>`）。
     *
     * 与 [grantGift] 同样的作用域限制；同一回复最多一次。
     * 相机不可用时也会发一次事件，让服务端把"画面不可用"准确告诉模型。
     * 真正落盘前还要用 [consumeSnapshotPermit] 再验一次（电话/相机代次/一次性）。
     *
     * **锁序（REVIEW_R6 §3）**：相机状态必须在**进入 runtime 监视器之前**读取。
     * 控制器会在持有相机门闩时调用 [notifyCameraGenerationChanged]（gate → runtime），
     * 如果这里反过来"持 runtime 监视器去问 gateway.available"，两边就会死锁。
     */
    fun requestSnapshot(
        conversationId: String?,
        requestToken: String?,
        messageId: String,
        atMillis: Long,
    ): VideoSnapshotRequest? {
        // 不在监视器里读相机状态：gateway.available 会去要相机门闩。
        val cameraReady = cameraAvailable()
        return synchronized(this) {
            val active = _conversationId.value ?: return@synchronized null
            if (conversationId?.trim() != active) return@synchronized null
            if (!turns.isGranted(conversationId, requestToken)) return@synchronized null
            val key = messageId.trim()
            if (key.isEmpty() || !snapshotServedMessageIds.add(key)) return@synchronized null
            while (snapshotServedMessageIds.size > MAX_GIFT_EVENTS) {
                val oldest = snapshotServedMessageIds.firstOrNull() ?: break
                snapshotServedMessageIds.remove(oldest)
            }
            val request = VideoSnapshotRequest(
                id = "snap:$key",
                conversationId = active,
                turnMessageId = key,
                cameraAvailable = cameraReady && cameraEnabled,
                atMillis = atMillis,
                permit = if (cameraEnabled) turns.issueSnapshotPermit(conversationId, requestToken, key) else null,
            )
            _snapshotRequests.tryEmit(request)
            request
        }
    }

    /** 落盘/写消息之前校验留影许可（电话还在、相机没换代、没消费过）。 */
    fun isSnapshotPermitUsable(permit: VideoSnapshotPermit?): Boolean = turns.isSnapshotPermitUsable(permit)

    /** 消费留影许可；返回 true 才允许写照片。 */
    fun consumeSnapshotPermit(permit: VideoSnapshotPermit?): Boolean = turns.consumeSnapshotPermit(permit)

    /** 通话内礼物统计（独立累计值，不受 UI 列表上限影响）。 */
    @Synchronized
    fun tally(): VideoGiftTally = tallyAccumulator

    @Synchronized
    fun grantedGifts(): List<VideoGift> = _gifts.value.map { it.gift }

    /** UI 历史列表里还留着多少枚（有上限，只影响展示）。 */
    @Synchronized
    fun giftCountForUi(): Int = _gifts.value.size

    @Synchronized
    fun clearGifts() {
        ledger.clear()
        _gifts.value = emptyList()
        _latestGift.value = null
        tallyAccumulator = VideoGiftTally()
    }

    /** 仅供测试与挂断清理：把所有进程内状态恢复成"没有视频通话"。 */
    @Synchronized
    fun reset() {
        _conversationId.value = null
        _camera.value = null
        clearCallStateLocked()
        turns.reset()
    }

    private fun clearCallStateLocked() {
        cameraEnabled = true
        frames.clear()
        _pendingFrameAtMillis.value = null
        ledger.clear()
        _gifts.value = emptyList()
        _latestGift.value = null
        snapshotServedMessageIds.clear()
        tallyAccumulator = VideoGiftTally()
    }
}
