/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.video.DanmakuLine
import me.rerere.rikkahub.data.video.appendBounded
import me.rerere.rikkahub.data.video.DanmakuStream
import me.rerere.rikkahub.data.video.FramePersistence
import me.rerere.rikkahub.data.video.FrameFileOwnership
import me.rerere.rikkahub.data.video.VideoCallContext
import me.rerere.rikkahub.data.video.VideoCallPrompts
import me.rerere.rikkahub.data.video.VideoCallRuntime
import me.rerere.rikkahub.data.video.VideoCallSetting
import me.rerere.rikkahub.data.video.VideoFrame
import me.rerere.rikkahub.data.video.VideoFrameCaptureMode
import me.rerere.rikkahub.data.video.VideoFrameCollage
import me.rerere.rikkahub.data.video.VideoFramePolicy
import me.rerere.rikkahub.data.video.VideoFrameStore
import me.rerere.rikkahub.data.video.ROLLING_SAMPLE_PERIOD_MILLIS
import me.rerere.rikkahub.data.video.RollingSampleCadence
import me.rerere.rikkahub.data.video.RollingVideoFrameBuffer
import me.rerere.rikkahub.data.video.VideoGiftCustomization
import me.rerere.rikkahub.data.video.VideoGiftEvent
import me.rerere.rikkahub.data.video.VideoGiftTally
import me.rerere.rikkahub.data.video.VideoSnapshotRequest
import me.rerere.rikkahub.data.video.giftSummaryLine
import me.rerere.rikkahub.data.video.videoCallContextFromSetting
import kotlin.uuid.Uuid

/** 视频面板用的通话侧状态（相机状态由面板自己的控制器维护）。 */
internal data class VideoCallPanelState(
    val active: Boolean = false,
    val visionSupported: Boolean = true,
    /**
     * 最近几条"上浮气泡"（有寿命、会淡出）。只用于需要动画的旧位置；
     * 面板正文改成 [history] 那种常驻可回看的列表。
     */
    val bubbles: List<DanmakuLine> = emptyList(),
    /**
     * 这一通电话到目前为止的**全部**文字（有界：最多 [MAX_HISTORY_LINES] 条）。
     *
     * 是真机反馈的直接产物：原来面板上只有 6 条、6 秒就消失的气泡，用户没法回看
     * "刚才那句到底说了什么"。这里保留稳定 id + 有界缓存，面板用 LazyColumn 渲染，
     * 滚动/回看都不再依赖动画寿命；真正持久的那份仍然是通话记录卡里的 transcript。
     */
    val history: List<DanmakuLine> = emptyList(),
    val tally: VideoGiftTally = VideoGiftTally(),
    val latestGift: me.rerere.rikkahub.data.video.VideoGift? = null,
    val notice: String? = null,
)

/** 一轮要附带的画面；[permanent] 决定它进聊天历史还是只作为本次请求的临时输入。 */
internal data class VideoTurnAttachment(val url: String, val permanent: Boolean)

/**
 * 视频通话中"只属于视频"的那部分逻辑：抓帧、留影、弹幕、礼物汇总。
 *
 * 语音通话的全部既有行为（ASR/VAD/TTS/打断/挂断）仍由 [VoiceCallService] 负责，
 * 这里只在 videoMode 打开时被调用，语音链路的默认路径不受影响。
 *
 * 异步安全：所有协程都挂在 [monitorJob] 下，[close] 一次取消全部；
 * 留影写入前会再校验"通话还在、会话还匹配、这次请求还是当前这次"，
 * 挂断后不会再拍摄、也不会往聊天里写新消息。
 */
internal class VideoCallCoordinator(
    private val context: Context,
    scope: CoroutineScope,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
    private val conversationId: Uuid,
    private val visionSupported: () -> Boolean,
) {
    private val _state = MutableStateFlow(VideoCallPanelState())
    val state: StateFlow<VideoCallPanelState> = _state.asStateFlow()

    /** 只属于这通视频通话的子作用域：close 时整棵取消。 */
    private val monitorJob = SupervisorJob(scope.coroutineContext[Job])
    private val callScope = CoroutineScope(scope.coroutineContext + monitorJob)

    private val danmaku = DanmakuStream()
    private val rollingFrames = RollingVideoFrameBuffer()
    private val turnNotes = mutableListOf<String>()
    @Volatile
    private var closed = false

    private val conversationKey = conversationId.toString()

    fun setting(): VideoCallSetting = settingsStore.settingsFlow.value.videoCallSetting

    fun start() {
        if (closed) return
        // 另一个会话的通话正在跑：不接管（接管会把那通电话的相机/待发送帧/礼物清掉）。
        if (!VideoCallRuntime.enterVideoCall(conversationKey)) {
            _state.update { it.copy(active = false) }
            return
        }
        callScope.launch {
            // 只收**这通通话**的礼物事件：另一路会话的礼物不得在这里冒泡/播特效。
            VideoCallRuntime.giftEvents
                .filter { it.conversationId == conversationKey }
                .collect { event -> onGift(event) }
        }
        callScope.launch {
            VideoCallRuntime.snapshotRequests.collect { request -> onSnapshotRequest(request) }
        }
        callScope.launch(Dispatchers.IO) {
            // 1 Hz 滚动画面属于"连续画面（每秒）"；旧的"每轮一帧"设置也保留同一行为，
            // 已保存的 every_reply 不迁移。OFF 不抓；30/60 秒仍由面板定时器刷新待发送帧——
            // 不能因为加了滚动缓冲，就让它们偷偷变成每秒抓一帧、每轮都发一张拼图
            // （那会改掉用户选定的旧语义）。
            val cadence = RollingSampleCadence()
            var sampling = false
            while (isCurrentCall()) {
                val mode = setting().frameCaptureMode
                if (!VideoFramePolicy.captureOnTurn(mode)) {
                    if (sampling) {
                        // 切到 OFF/30s/60s：上一模式留下的帧不能再混进之后的拼图。
                        rollingFrames.clear()
                        sampling = false
                    }
                    delay(ROLLING_SAMPLE_PERIOD_MILLIS)
                    continue
                }
                if (!VideoCallRuntime.cameraAvailable() || !visionSupported()) {
                    if (sampling) {
                        rollingFrames.clear()
                        sampling = false
                    }
                    delay(ROLLING_SAMPLE_PERIOD_MILLIS)
                    continue
                }
                if (!sampling) {
                    cadence.reset(SystemClock.elapsedRealtime())
                    sampling = true
                }
                val startedAt = SystemClock.elapsedRealtime()
                val bufferEpoch = rollingFrames.epoch()
                val cameraEpoch = VideoCallRuntime.currentCameraEpoch()
                val frame = try {
                    VideoCallRuntime.captureFrame()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (frame != null && isAutomaticAttachmentCurrent(mode, cameraEpoch)) {
                    rollingFrames.accept(
                        expectedEpoch = bufferEpoch,
                        capturedCameraEpoch = cameraEpoch,
                        // 在落进缓冲的这一刻再读一次当前代次：抓帧期间换相机的话，
                        // 这一帧会被缓冲当作迟到帧拒绝。
                        currentCameraEpoch = VideoCallRuntime.currentCameraEpoch(),
                        frame = frame,
                        sampleAtElapsedMillis = startedAt,
                        capacity = setting().rollingFrameCount.coerceIn(
                            RollingVideoFrameBuffer.MIN_FRAMES,
                            RollingVideoFrameBuffer.MAX_FRAMES,
                        ),
                    )
                }
                // 用单调时钟扣掉抓帧耗时，让相邻采样的**起点**间隔接近 1 s。
                delay(cadence.delayAfterSample(startedAt, SystemClock.elapsedRealtime()))
            }
        }
        _state.update {
            it.copy(
                active = true,
                visionSupported = visionSupported(),
                tally = VideoCallRuntime.tally(),
            )
        }
    }

    fun close() {
        closed = true
        rollingFrames.clear()
        monitorJob.cancel()
        // 只有"当前这通通话"才清运行时的待发送帧/相机入口：别的会话的页面销毁
        // 不能把正在进行的通话的画面/特效清掉。
        if (VideoCallRuntime.isActive(conversationKey)) {
            VideoCallRuntime.clearPendingFrame(conversationKey)
            VideoCallRuntime.exitVideoCall(conversationKey)
        }
        danmaku.reset()
        _state.value = VideoCallPanelState()
    }

    fun cameraAvailable(): Boolean = VideoCallRuntime.cameraAvailable()

    fun clearRollingFrames() = rollingFrames.clear()

    /** 这轮是不是"还在同一次调用"：挂断/换通话/关面板之后一律 false。 */
    fun isCurrentCall(): Boolean =
        !closed && VideoCallRuntime.isActive(conversationKey)

    /**
     * 登记这一轮的**请求 token**（授权凭证）。
     *
     * token 由 VoiceCallService 生成并随队列项不可变传递；这里只负责把"这通通话 + 这个 token"
     * 登记进运行时台账。结束由 [completeTurnRequest]，取消/打断由 [cancelTurnRequest]。
     */
    fun beginTurnRequest(requestToken: String): Boolean {
        if (!isCurrentCall()) return false
        return VideoCallRuntime.beginTurnRequest(conversationKey, requestToken)
    }

    /** 这一轮正常结束：token 关闭，但合法的异步留影仍然可以落盘。 */
    fun completeTurnRequest(requestToken: String) {
        VideoCallRuntime.completeTurnRequest(requestToken)
    }

    /** 这一轮被打断/取消/失败：token 关闭，且撤销这次请求发出的全部留影许可。 */
    fun cancelTurnRequest(requestToken: String) {
        VideoCallRuntime.cancelTurnRequest(requestToken)
    }

    /**
     * 一轮请求要注入的视频上下文。
     *
     * [pictureAttached] 必须是**本轮实际有没有附图**的结果，不能只看 cameraAvailable。
     * [audioAttached] 同理：只有音频直传路线真的把这一段录音挂上去时才为 true
     * （ASR 路线只有文字、录音被取消/暂停时也没有音频）。
     * [includeTurnNotes] 为 false 时不消费本轮备注，用于生成"附图说明被摘掉之后"的兜底文案
     * （同一份备注不能被两个变体各消费一次）。
     */
    fun turnContext(
        pictureAttached: Boolean,
        includeTurnNotes: Boolean = true,
        audioAttached: Boolean = false,
    ): String {
        val current = setting()
        val cameraAvailable = VideoCallRuntime.cameraAvailable()
        val vision = visionSupported()
        // 上下文的拼装是纯逻辑（[videoCallContextFromSetting]）：设置里的自定义礼物名/赠礼文案
        // 在这里进入提示词，不在这里再判断一次。
        val context = videoCallContextFromSetting(
            setting = current,
            cameraAvailable = cameraAvailable,
            cameraUnavailableReason = VideoCallRuntime.cameraUnavailableReason(),
            visionSupported = vision,
            pictureAttached = pictureAttached,
            audioAttached = audioAttached,
            giftsSent = VideoCallRuntime.tally(),
        )
        val notes = if (includeTurnNotes) takeTurnNotes() else emptyList()
        return (listOf(VideoCallPrompts.turnContext(context)) + notes).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    /**
     * 这一轮要附带的画面。
     *
     * 优先级：手动夹帧（永久保存）> 1 Hz 连续画面拼图（临时）> 30/60 秒定时帧（临时）。
     * 连续画面拼图同时服务新默认 [VideoFrameCaptureMode.CONTINUOUS_SEQUENCE] 与旧设置
     * [VideoFrameCaptureMode.EVERY_REPLY]；OFF 不自动附图（手动夹帧除外），
     * 模型不识图时不抓图也不附图。
     */
    suspend fun frameAttachmentForTurn(): VideoTurnAttachment? {
        if (!isCurrentCall()) return null
        if (!visionSupported()) return null
        val mode = setting().frameCaptureMode
        val cameraEpoch = VideoCallRuntime.currentCameraEpoch()
        // 先取出待发送帧：手动夹帧和 30/60 秒定时帧都靠它。手动帧永远优先。
        val pending = VideoCallRuntime.takePendingFrame()
        if (pending?.manual == true) {
            // 手动夹帧是用户显式动作：即使自动抓帧已关，也照样随这一句发出并永久保存。
            val url = persistFrame(pending, FramePersistence.Clip) ?: return null
            return VideoTurnAttachment(url = url, permanent = true)
        }
        if (!isAutomaticAttachmentCurrent(mode, cameraEpoch)) return null
        // 1 Hz 滚动拼图属于"连续画面"和旧的"每轮一帧"；OFF/30s/60s 保持原有语义，
        // 不能因为加了缓冲就变成所有非 OFF 模式每轮都发一张拼图。
        if (VideoFramePolicy.captureOnTurn(mode)) {
            rollingCollageAttachment(cameraEpoch, mode)?.let { return it }
        }
        // 拼图会挂起；失败回退前重新确认，避免切模式后仍启动一次自动抓帧。
        if (!isAutomaticAttachmentCurrent(mode, cameraEpoch)) return null
        val frame = try {
            when (mode) {
                // OFF：只剩显式手动帧（上面已处理），不自动附图。
                VideoFrameCaptureMode.OFF -> null
                // 连续画面 / 旧"每轮一帧"：拼图失败时退回旧行为，发送前现场抓一帧。
                VideoFrameCaptureMode.CONTINUOUS_SEQUENCE,
                VideoFrameCaptureMode.EVERY_REPLY -> pending ?: captureFrameForTurn()
                // 30/60 秒：只发面板定时器刷新出来的那一张，没刷新过就不发。
                VideoFrameCaptureMode.EVERY_30S,
                VideoFrameCaptureMode.EVERY_60S -> pending
            }
        } catch (cancelled: CancellationException) {
            // 取消必须透传：吞掉它会让已经取消的一轮继续去落盘/提交。
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        // 自动帧从取出/抓取到落盘期间必须仍属于原来的模式和相机。
        if (!isAutomaticAttachmentCurrent(mode, cameraEpoch)) return null
        val url = persistFrame(frame, FramePersistence.Temp) ?: return null
        if (!isAutomaticAttachmentCurrent(mode, cameraEpoch)) {
            VideoFrameStore.deleteTransient(context, listOf(url))
            return null
        }
        return VideoTurnAttachment(url = url, permanent = false)
    }

    private suspend fun captureFrameForTurn(): VideoFrame? {
        if (!VideoCallRuntime.cameraAvailable()) return null
        return VideoCallRuntime.captureFrame()
    }

    /**
     * 最近几帧等比拼成一张临时图。
     *
     * 拼图期间可能挂断/关摄像头/切会话：缓冲代次和相机代次都要在**落盘前后**
     * 各校验一次，任何一次失效都删掉已经写出的临时文件。
     */
    private suspend fun rollingCollageAttachment(
        cameraEpoch: Long,
        mode: VideoFrameCaptureMode,
    ): VideoTurnAttachment? {
        if (!isAutomaticAttachmentCurrent(mode, cameraEpoch)) return null
        if (!VideoCallRuntime.cameraAvailable()) return null
        val count = setting().rollingFrameCount.coerceIn(
            RollingVideoFrameBuffer.MIN_FRAMES,
            RollingVideoFrameBuffer.MAX_FRAMES,
        )
        val bufferEpoch = rollingFrames.epoch()
        val rolling = rollingFrames.latest(count, cameraEpoch)
        if (rolling.isEmpty()) return null
        val quality = setting().frameQuality
        // 拼图是**可选**增强：任何一张图编不出来都不该让整条文字/语音消息发不出去。
        // 取消仍然原样透传，其余失败退化为"这一轮不带滚动画面"。
        val collage = try {
            withContext(Dispatchers.IO) {
                val oriented = rolling.mapNotNull { frame ->
                    VideoFrameStore.compressToJpeg(
                        raw = frame.bytes,
                        quality = quality,
                        rotationDegrees = frame.rotationDegrees,
                        mirror = frame.mirror,
                    )
                }
                if (oriented.size != rolling.size) null else VideoFrameCollage.compose(oriented)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        if (!isRollingAttachmentCurrent(bufferEpoch, cameraEpoch, mode)) return null
        val file = persistCollage(collage) ?: return null
        val url = VideoFrameStore.fileUrl(file)
        if (!isRollingAttachmentCurrent(bufferEpoch, cameraEpoch, mode)) {
            VideoFrameStore.deleteTransient(context, listOf(url))
            return null
        }
        return VideoTurnAttachment(url = url, permanent = false)
    }

    private fun isAutomaticAttachmentCurrent(mode: VideoFrameCaptureMode, cameraEpoch: Long): Boolean =
        me.rerere.rikkahub.data.video.isAutomaticVideoAttachmentCurrent(
            expectedMode = mode,
            currentMode = setting().frameCaptureMode,
            expectedCameraEpoch = cameraEpoch,
            currentCameraEpoch = VideoCallRuntime.currentCameraEpoch(),
            cameraGranted = VideoCallRuntime.isCameraEpochCurrent(conversationKey, cameraEpoch),
            callCurrent = isCurrentCall(),
        ) && visionSupported()

    private fun isRollingAttachmentCurrent(
        bufferEpoch: Long,
        cameraEpoch: Long,
        mode: VideoFrameCaptureMode,
    ): Boolean =
        rollingFrames.epoch() == bufferEpoch &&
            isAutomaticAttachmentCurrent(mode, cameraEpoch)

    /**
     * 把一帧压成 JPEG 并落盘（压缩/写文件都在 IO 线程）。
     *
     * 写文件本身**不可取消**（一段很小的写操作），但这里有个很容易踩的坑：
     * `withContext(NonCancellable)` 在**切回调用方 dispatcher** 时会做一次 prompt cancellation
     * 检查，直接抛 `CancellationException`。旧实现是
     * `val url = withContext(...) ?: return null` 再加一句 `ensureActive()` 兜底 —— 那次
     * 取消会让 `url` 这个赋值根本没发生，后面的清理代码永远不知道文件已经写出来了，
     * 于是永久照片就漏在磁盘上没人知道。
     *
     * 现在把"写出来的文件"记在外层所有权的变量里，清理放在**外层 try/finally**：
     * 只要 url 没有成功交出去（包括那次 prompt cancellation），文件都会被删掉。
     */
    suspend fun persistFrame(frame: VideoFrame, persistence: FramePersistence): String? {
        var written: java.io.File? = null
        var handedOff = false
        try {
            val file = withContext(Dispatchers.IO + NonCancellable) {
                writeFrameFile(frame, persistence)?.also { written = it }
            } ?: return null
            currentCoroutineContext().ensureActive()
            handedOff = true
            return VideoFrameStore.fileUrl(file)
        } finally {
            if (!handedOff) written?.let { VideoFrameStore.deleteOwned(context, VideoFrameStore.fileUrl(it)) }
        }
    }

    private fun writeFrameFile(frame: VideoFrame, persistence: FramePersistence): java.io.File? {
        val jpeg = VideoFrameStore.compressToJpeg(
            raw = frame.bytes,
            quality = setting().frameQuality,
            rotationDegrees = frame.rotationDegrees,
            mirror = frame.mirror,
        ) ?: return null
        return VideoFrameStore.saveJpeg(
            bytes = jpeg,
            directory = VideoFrameStore.directoryFor(context, persistence),
            prefix = VideoFrameStore.prefixFor(persistence),
        )
    }

    /**
     * 拼图落盘（临时目录）。取消发生在 `withContext(NonCancellable)` 切回时的 prompt
     * cancellation 里时，`file` 的赋值根本不会发生；所以文件所有权必须记在**外层**
     * 变量里，并在 finally 里兜底删除 —— 和 [persistFrame] 是同一个模型。
     */
    private suspend fun persistCollage(jpeg: ByteArray): java.io.File? {
        var written: java.io.File? = null
        var handedOff = false
        try {
            val file = withContext(Dispatchers.IO + NonCancellable) {
                VideoFrameStore.saveJpeg(
                    bytes = jpeg,
                    directory = VideoFrameStore.transientDirectory(context),
                    prefix = "sequence",
                )?.also { written = it }
            } ?: return null
            currentCoroutineContext().ensureActive()
            handedOff = true
            return file
        } finally {
            if (!handedOff) {
                written?.let {
                    VideoFrameStore.deleteTransient(context, listOf(VideoFrameStore.fileUrl(it)))
                }
            }
        }
    }

    fun onUserTurn(text: String, atMillis: Long = System.currentTimeMillis()) {
        danmaku.reset()
        if (!setting().showUserDanmaku) return
        val line = DanmakuLine("user:$atMillis", true, text.trim().take(MAX_BUBBLE_CHARS), atMillis)
        if (line.text.isEmpty()) return
        appendBubble(line)
    }

    fun onAssistantText(full: String, atMillis: Long = System.currentTimeMillis()) {
        val lines = danmaku.push(full)
        lines.forEachIndexed { index, text ->
            appendBubble(
                DanmakuLine("assistant:$atMillis:$index", false, text.take(MAX_BUBBLE_CHARS), atMillis),
            )
        }
    }

    fun onAssistantTurnFinished(full: String, atMillis: Long = System.currentTimeMillis()) {
        val lines = danmaku.flush(full)
        lines.forEachIndexed { index, text ->
            appendBubble(
                DanmakuLine("assistant-done:$atMillis:$index", false, text.take(MAX_BUBBLE_CHARS), atMillis),
            )
        }
    }

    /** 结束通话卡上的礼物汇总：名字用**这一刻**的设置（历史卡上的字符串不会回改）。 */
    fun giftSummary(): String =
        giftSummaryLine(VideoCallRuntime.tally(), VideoGiftCustomization.sanitizedNames(setting()))

    fun notify(text: String?) {
        _state.update { it.copy(notice = text) }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun visionAvailable(): Boolean = visionSupported()

    @Synchronized
    private fun addTurnNote(note: String) {
        if (note.isBlank()) return
        turnNotes += note
        while (turnNotes.size > MAX_TURN_NOTES) turnNotes.removeAt(0)
    }

    @Synchronized
    private fun takeTurnNotes(): List<String> {
        val notes = turnNotes.toList()
        turnNotes.clear()
        return notes
    }

    private fun appendBubble(line: DanmakuLine) {
        _state.update { current ->
            current.copy(
                bubbles = (current.bubbles + line).takeLast(MAX_BUBBLES),
                // 常驻历史用同一批行（同一个稳定 id），只是不做寿命淘汰，只做有界截断。
                history = current.history.appendBounded(line, MAX_HISTORY_LINES),
            )
        }
    }

    private fun onGift(event: VideoGiftEvent) {
        _state.update {
            it.copy(
                tally = VideoCallRuntime.tally(),
                latestGift = event.gift,
            )
        }
        // 气泡上的名字用当前设置（与提示词清单、结束卡汇总同一份取值顺序）。
        val names = VideoGiftCustomization.sanitizedNames(setting())
        appendBubble(
            DanmakuLine(
                id = "gift:${event.id}",
                fromUser = false,
                text = "${event.gift.emoji} ${VideoGiftCustomization.nameOf(names, event.gift)}",
                atMillis = event.atMillis,
            ),
        )
    }

    /**
     * `<ws_vsnap/>`：相机可用就留影进聊天，不可用就把准确的失败说明交给模型。
     *
     * 这个协程挂在 [monitorJob] 上：挂断会立刻取消。每个挂起点之后都要重新校验：
     * 1. 还在同一通电话里（会话匹配、没挂断）；
     * 2. 留影许可仍然有效（同一 call epoch + 同一代相机 + 没被消费过）。
     *
     * 许可是一次的：真正写进聊天之前 [VideoCallRuntime.consumeSnapshotPermit]，
     * 所以"面板离开 → 相机换代 → 才回来写照片"这条路径不可能插入一张旧照片。
     */
    private fun onSnapshotRequest(request: VideoSnapshotRequest) {
        if (request.conversationId != conversationKey) return
        callScope.launch {
            if (!isCurrentCall()) return@launch
            if (request.permit == null || !VideoCallRuntime.isSnapshotPermitUsable(request.permit)) {
                // 许可已经过期（挂断 / 面板离开 / 相机换代）：明确告诉模型没拿到画面。
                addTurnNote(VideoCallPrompts.cameraUnavailableText("留影许可已失效（通话或相机已切换）"))
                return@launch
            }
            if (!visionSupported()) {
                addTurnNote(VideoCallPrompts.VISION_UNAVAILABLE_NOTE)
                notify("当前模型不支持识图，留影未发送")
                return@launch
            }
            if (!request.cameraAvailable) {
                addTurnNote(VideoCallPrompts.cameraUnavailableText(VideoCallRuntime.cameraUnavailableReason()))
                notify("相机不可用，留影未发送")
                return@launch
            }
            val frame = VideoCallRuntime.captureFrame()
            if (!isCurrentCall()) return@launch
            if (frame == null) {
                addTurnNote(VideoCallPrompts.cameraUnavailableText(VideoCallRuntime.cameraUnavailableReason()))
                notify("这一帧没有抓到，留影未发送")
                return@launch
            }
            val url = persistFrame(frame, FramePersistence.Snapshot)
            if (!isCurrentCall()) {
                // 挂断/离开面板：不写聊天，也不留下没人引用的永久图片。
                if (url != null) VideoFrameStore.deleteOwned(context, url)
                return@launch
            }
            if (url == null) {
                addTurnNote("留影保存失败，你没有收到画面；不要声称已经留下照片。")
                notify("留影保存失败")
                return@launch
            }
            // 许可的**校验与消费**都放进 appendVideoCallMessage 的 saveMutex 里（发布之前）。
            // 旧实现在这里先消费、再去等保存锁：等锁期间挂断/面板离开/相机换代都不会取消
            // 这个协程（通话本身没挂断），于是一张已经作废的照片照样写进聊天。
            // 也不能先 isUsable 再 consume：许可被消费后 isUsable 立刻为 false。
            val ownership = FrameFileOwnership()
            var rejected = false
            val saved = try {
                chatService.appendVideoCallMessage(
                    conversationId = conversationId,
                    message = UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(
                            UIMessagePart.Text("📷 留影（视频通话）"),
                            UIMessagePart.Image(url = url),
                        ),
                    ),
                    ownership = ownership,
                    validate = {
                        VideoCallRuntime.consumeSnapshotPermit(request.permit).also { granted ->
                            if (!granted) rejected = true
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                // 没发布过就自己删；发布过/可能发布过则由所有权状态兜住（永不删）。
                if (ownership.claimDeletion()) VideoFrameStore.deleteOwned(context, url)
                throw cancelled
            } catch (_: Exception) {
                // 写库失败：没发布过照样清掉自己刚写的文件（所有权会告诉我们能不能删）。
                false
            }
            if (!saved) {
                if (ownership.claimDeletion()) VideoFrameStore.deleteOwned(context, url)
                if (rejected) {
                    addTurnNote("留影许可已失效，你没有收到画面；不要声称已经留下照片。")
                    notify("留影许可失效")
                } else {
                    addTurnNote("留影保存失败，你没有收到画面；不要声称已经留下照片。")
                    notify("留影写入聊天失败")
                }
            } else {
                notify("已经留影到聊天")
            }
        }
    }

    private companion object {
        const val MAX_BUBBLES = 16

        /** 面板常驻历史的条数上限（有界缓存；真正持久的记录在通话卡里）。 */
        const val MAX_HISTORY_LINES = 400
        const val MAX_BUBBLE_CHARS = 140
        const val MAX_TURN_NOTES = 4
    }
}
