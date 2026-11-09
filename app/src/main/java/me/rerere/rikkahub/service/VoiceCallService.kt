/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import me.rerere.tts.model.SpeechMarkup
import me.rerere.tts.provider.speechDeliveryPrompt
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider

import me.rerere.rikkahub.data.sync.isChatRecoveryBlocking
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.rerere.ai.ui.CallTranscriptEntry
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.provider.Modality
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.video.CallCapabilityState
import me.rerere.rikkahub.data.video.CallForegroundCapability
import me.rerere.rikkahub.data.video.CallReconcileAction
import me.rerere.rikkahub.data.video.CallSessionMode
import me.rerere.rikkahub.data.video.VideoCallPolicy
import me.rerere.rikkahub.data.video.VideoCallRefreshAction
import me.rerere.rikkahub.data.video.VideoCallRuntime
import me.rerere.rikkahub.data.video.VideoFrameStore
import me.rerere.rikkahub.data.video.VideoGiftProtocol
import me.rerere.rikkahub.data.video.VoiceCallTurnGate
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.VOICE_CALL_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.hooks.CustomAsrState
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.rikkahub.ui.hooks.createCustomAsrState
import me.rerere.rikkahub.ui.hooks.createCustomTtsState
import me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus
import me.rerere.rikkahub.ui.pages.voice.listeningAfterReply
import me.rerere.rikkahub.ui.pages.voice.VoiceCallUiState
import okhttp3.OkHttpClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

private const val TAG = "VoiceCallService"

/** 视频面板里打的字只用来显示在字幕上，不许无限增长。 */
private const val MAX_TYPED_TRANSCRIPT = 200

/**
 * 语音通话后台服务
 *
 * 把原来 VoiceCallVM 里的业务逻辑迁移成"独立运行、跟随 Service 生命周期"的形式.
 * 用户在 VoiceCallPage 手动开始通话后, 切到后台/退出页面, 通话依然继续跑,
 * 有持续通知栏, 点通知能回到通话页面. 只有用户主动点"挂断"才真正结束.
 *
 * 同一时刻只允许存在一路通话 (由 _activeConversationId 这个 companion object 级别的
 * StateFlow 做单例保护).
 */
class VoiceCallService : Service(), KoinComponent {
    private val chatService: ChatService by inject()
    private val httpClient: OkHttpClient by inject()
    private val settingsStore: SettingsStore by inject()

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "VoiceCallService coroutine exception", e)
        }
    )

    private lateinit var conversationId: Uuid
    private lateinit var asr: CustomAsrState
    private lateinit var tts: CustomTtsState
    private lateinit var microphone: VoiceCallMicrophone

    /**
     * 语音链路（ASR/TTS/麦克风）属于**哪一次通话会话**（REVIEW_R6 §2）。
     *
     * `lateinit` 变量"存在"只说明曾经建过，绝不能当作"这次通话已经就绪"：bound service
     * 会被系统复用承载下一通电话，如果只看 `::asr.isInitialized`，上一通留下的旧 ASR 会被
     * 当成这次成功初始化（哪怕这次根本没有麦克风权限），还会继续把旧转写回调送进新通话。
     *
     * 所以三个资源各自带一个会话号：只有 `token == sessionToken` 才算这次通话真正可用的链路。
     * 挂断、权限撤回、换会话都会作废会话号并释放旧实例。
     */
    private var asrToken: Long = -1L
    private var ttsToken: Long = -1L
    private var microphoneToken: Long = -1L
    private var sessionToken: Long = 0L

    private val hasAsr: Boolean get() = ::asr.isInitialized && asrToken == sessionToken
    private val hasTts: Boolean get() = ::tts.isInitialized && ttsToken == sessionToken
    private val hasMicrophone: Boolean get() = ::microphone.isInitialized && microphoneToken == sessionToken

    /** 这通电话是否已经"开过场"（[startCall] 跑过一次）：补建不许再重置计时/转写/生成状态。 */
    private var callStarted = false

    /**
     * 音频链路工厂 + 通话交互记录 + 会话流：**最小可注入点**（REVIEW_R6 §1/§2）。
     *
     * 生产实现就是真实 provider 工厂、[ChatService]；Robolectric 集成回归只替换这几处，
     * 前台服务、通知、权限对账、初始化代次、补建与清理……全部走真实代码路径。
     * 注意：默认实现里才去解析 `httpClient`/`settingsStore`/`chatService`，
     * 因此替换之后不会再触碰这些依赖。
     */
    internal var createAsrState: suspend () -> CustomAsrState =
        { createCustomAsrState(applicationContext, httpClient, settingsStore) }

    internal var createTtsState: suspend () -> CustomTtsState =
        { createCustomTtsState(applicationContext, settingsStore) }

    internal var beginCallInteraction: (Uuid, Long) -> Job? =
        { id, startedAt -> chatService.beginInteractionCall(id, startedAt) }

    internal var finishCallInteraction:
        (Uuid, Job?, Long, List<CallTranscriptEntry>, String) -> Unit =
        { id, startJob, startedAt, transcript, giftSummary ->
            chatService.finishInteractionCalls(id, startJob, startedAt, transcript, giftSummary)
        }

    /**
     * 视频协调器的**创建点**（最小可注入点，和 [createAsrState] 同一个思路）。
     *
     * 生产默认实现就是原来的构造调用（依赖在 lambda 里才解析，替换之后不再触碰 Koin）。
     * 服务集成回归把它换成一个返回 null 的工厂，就能在不引入整套 `ChatService` 依赖的前提下，
     * 跑真实的 `videoMode` 服务路径（权限对账、前台类型、初始化代次、撤权降级）；
     * 相机/礼物/留影本身的行为完全不变，仍由各自的测试覆盖。
     */
    internal var createVideoCoordinator: (
        context: Context,
        scope: CoroutineScope,
        conversationId: Uuid,
        visionSupported: () -> Boolean,
    ) -> VideoCallCoordinator? = { context, scope, id, vision ->
        VideoCallCoordinator(context, scope, settingsStore, chatService, id, vision)
    }

    private var microphonePolicyJob: Job? = null
    private var callEnded = false
    private var interactionStartJob: Job? = null
    private val callTranscript = VoiceCallTranscript()

    // 视频通话：只有从视频入口进入时才启用，语音通话的默认路径完全不受影响
    private var videoMode = false
    private var video: VideoCallCoordinator? = null

    /**
     * 面板用的**稳定** StateFlow：无论 coordinator 被重建多少次，UI 订阅的对象都不变。
     *
     * 旧写法是 `video?.state ?: idleVideoState`（每次读取可能返回不同的 flow），
     * bound service 对象不变时 Compose 会一直订阅旧的那个，弹幕/礼物计数就停更了。
     */
    private val _panelState = MutableStateFlow(VideoCallPanelState())
    private var panelStateJob: Job? = null

    // 视频模式下允许没有麦克风/没有 TTS：这些标志只影响"有没有声音"，不影响文字与礼物链路
    private var audioUnavailable = false

    /** 没有相机也没有麦克风权限时，服务由可见 Activity 绑定运行（不占前台服务类型）。 */
    private var boundOnly = false

    /**
     * 前台能力状态（是否在前台 / 声明了哪些类型 / 语音链路建好没有）。
     *
     * 收在 [CallCapabilityState] 里：它同时是"通话中权限变化要不要刷新"的判断依据，
     * 因此"补授权只补建一次""权限撤回降级"这类顺序行为可以在 JVM 单测里按顺序验证。
     */
    private val capabilityState = CallCapabilityState()

    /**
     * 当前有几个客户端 bind 着这个服务。
     *
     * 权限被撤回到"没有任何前台能力"时要决定是降级成绑定模式还是结束通话（REVIEW_R5 §5）：
     * 有页面还连着就降级，没有就结束，绝不留一个不该存在的 FGS 或一个没人看的通话。
     */
    private var boundClients = 0

    private var notificationUpdatesRunning = false
    private var notificationJob: Job? = null

    /**
     * 初始化的代次 / Job。
     *
     * bound service 会被系统复用（同一实例承载后来的通话），旧初始化协程如果能恢复，
     * 就会把旧 ASR/TTS 装进新通话。所以每次初始化换代并取消旧 Job：
     * 旧协程发现自己过期后只清理自己创建的资源，绝不改当前状态。
     */
    private var initJob: Job? = null
    private var initEpoch: Long = 0L

    /** 每轮对话的令牌门禁：打断/挂断先失效，旧任务在检查点就会自己退出。 */
    private val turnGate = VoiceCallTurnGate()
    private var turnJob: Job? = null

    /** 当前这一轮的请求 token（视频通话授权凭证）；随队列项不可变地传给生成管线。 */
    private var activeRequestToken: String? = null

    internal val videoState: StateFlow<VideoCallPanelState> = _panelState.asStateFlow()

    /** 当前绑定的服务是否就是这通视频通话（面板用来避免控制别人的通话）。 */
    fun boundConversationId(): String? = if (::conversationId.isInitialized) conversationId.toString() else null

    fun isVideoMode(): Boolean = videoMode

    /** 服务是否以"可见 Activity 绑定运行"的方式承载通话（无前台服务类型）。 */
    fun isBoundOnly(): Boolean = boundOnly && !callEnded

    /**
     * 页面退后台（ON_STOP）时调用：**只**结束"绑定运行"的通话。
     *
     * 正常的前台语音/视频通话必须继续（通知栏仍可控制），所以这里按模式判断，
     * 不会误杀 FGS 通话。
     *
     * @return true 表示确实结束了这通绑定通话。
     */
    fun endBoundCallOnHostStop(): Boolean {
        if (!boundOnly || callEnded) return false
        endCall()
        stopSelf()
        return true
    }

    private val _uiState = MutableStateFlow(VoiceCallUiState())
    val uiState: StateFlow<VoiceCallUiState> = _uiState.asStateFlow()

    /**
     * 会话流的取用点（最小可注入点，见 [createAsrState]）：默认走 [ChatService]，
     * Robolectric 集成回归注入一个最小会话状态流，其余初始化/监控/权限路径都是真实代码。
     */
    internal var conversationStateFor: (Uuid) -> StateFlow<Conversation> =
        { id -> chatService.getConversationFlow(id) }

    val conversation: StateFlow<Conversation>
        get() = conversationStateFor(conversationId)

    // 任务协程
    private var vadJob: Job? = null
    private var speakingMonitorJob: Job? = null
    private var conversationMonitorJob: Job? = null
    private var asrMonitorJob: Job? = null
    private var interruptDetectJob: Job? = null
    private var lastSpokenText: String = ""
    private var activeVoiceMessage: QueuedMessage? = null
    private val voiceRequestIds = mutableSetOf<Uuid>()

    // 跟踪 AI 消息的增量, 用于流式 TTS
    private var lastAssistantText: String = ""
    private var hasSentCurrentMessage = false

    // 流式 TTS: 记录已发送给 TTS 的文本长度
    private var ttsSentLength: Int = 0

    // 静音状态 (独立于 _uiState.isMuted, 检测循环里直接读这个字段更快)
    private var isMuted: Boolean = false
    private var initialAudioMode: Int? = null
    private var initialSpeakerphoneOn = false
    private var initialCommunicationDevice: AudioDeviceInfo? = null

    companion object {
        private val _startedAtMillis = MutableStateFlow(0L)
        val startedAtMillis: StateFlow<Long> = _startedAtMillis.asStateFlow()
        private val _activeConversationId = MutableStateFlow<String?>(null)
        val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()
        private val _activeUiState = MutableStateFlow(VoiceCallUiState())
        val activeUiState: StateFlow<VoiceCallUiState> = _activeUiState.asStateFlow()

        private val _videoCallActive = MutableStateFlow(false)
        val videoCallActive: StateFlow<Boolean> = _videoCallActive.asStateFlow()

        /**
         * 当前通话是否以"可见 Activity 绑定运行"的方式承载（不占前台服务类型）。
         *
         * 页面用它判断"要不要在权限获批后升级成前台"——不依赖 binder 是否已经连上。
         */
        private val _boundOnlyCall = MutableStateFlow(false)
        val boundOnlyCall: StateFlow<Boolean> = _boundOnlyCall.asStateFlow()

        internal val idleVideoState = MutableStateFlow(VideoCallPanelState()).asStateFlow()

        fun isRunning(): Boolean = _activeConversationId.value != null

        fun isVideoCall(): Boolean = _videoCallActive.value

        fun toggleMute(context: Context) = sendControlAction(context, ACTION_TOGGLE_MUTE)

        fun toggleSpeaker(context: Context) = sendControlAction(context, ACTION_TOGGLE_SPEAKER)

        private fun sendControlAction(context: Context, action: String) {
            if (isRunning()) context.applicationContext.startService(Intent(context, VoiceCallService::class.java).setAction(action))
        }

        /**
         * 启动服务: 调用方 (VoiceCallPage) 负责在自己判断"没有冲突"之后才调这个方法.
         */
        fun start(context: Context, conversationId: String, video: Boolean = false) {
            val intent = Intent(context, VoiceCallService::class.java).apply {
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                if (video) putExtra(EXTRA_VIDEO_MODE, true)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "启动 VoiceCallService 失败, conversationId=$conversationId", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, VoiceCallService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "停止 VoiceCallService 失败", e)
            }
        }

        const val EXTRA_CONVERSATION_ID = "conversationId"
        const val EXTRA_VIDEO_MODE = "videoCallMode"
        const val ACTION_HANG_UP = "me.rerere.rikkahub.VOICE_CALL_HANG_UP"
        const val ACTION_TOGGLE_MUTE = "me.rerere.rikkahub.VOICE_CALL_TOGGLE_MUTE"
        const val ACTION_TOGGLE_SPEAKER = "me.rerere.rikkahub.VOICE_CALL_TOGGLE_SPEAKER"
        const val NOTIFICATION_ID = 40001
    }

    // Binder, 供 VoiceCallPage bindService 用
    inner class LocalBinder : Binder() {
        fun getService(): VoiceCallService = this@VoiceCallService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder {
        boundClients += 1
        return binder
    }

    /**
     * 无相机/无麦克风权限时的纯文字黑底模式：由可见 Activity 绑定启动，不占前台服务类型。
     *
     * 通话生命周期跟着页面可见性走：页面 unbind 时 [onUnbind] 会立刻结束通话。
     *
     * @return false 表示已有别的通话在进行（不允许抢占）。
     */
    fun startBoundCall(conversationIdString: String, video: Boolean = true): Boolean {
        if (conversationIdString.isBlank()) return false
        if (_activeConversationId.value == conversationIdString) {
            if (video) startVideoMode()
            // 权限可能在绑定期间获批：幂等地对账一次（有权限就升级成前台）。
            reconcileServiceCapabilities(foregroundRequired = false)
            return true
        }
        if (_activeConversationId.value != null) return false
        return beginCallSession(conversationIdString, video = video, foreground = false)
    }

    /**
     * 权限变化后的**显式刷新**（REVIEW_R5 §5 / REVIEW_R6 §2）。
     *
     * 只对"当前这通通话"生效：不属于本会话的调用直接返回 false，绝不误伤别的通话。
     * 幂等：能力组合没变、语音链路也已经建好（或正按同一组能力初始化中）时什么都不做。
     *
     * 三条真实路径都从这里进 reconcile：
     * - 补授权（camera-only → 相机+麦克风）：刷新前台类型 + 补建**一次**输入链路；
     * - 撤回麦克风（相机还在）：前台类型减回 CAMERA，并**立刻**停录音、释放 ASR、
     *   把语音链路标成 not ready（旧实现只缩前台类型，麦克风继续录、继续报错）；
     * - 两个都撤回：降级成绑定模式或结束通话（[dropForegroundKeepCall]）。
     *
     * @return true 表示服务仍是这通通话的有效承载（刷新成功或无需刷新）。
     */
    fun refreshSessionPermissions(conversationIdString: String): Boolean {
        if (callEnded || !::conversationId.isInitialized) return false
        if (_activeConversationId.value != conversationIdString) return false
        return when (
            capabilityState.refreshAction(
                cameraGranted = hasCameraPermission(),
                micGranted = hasMicrophonePermission(),
                videoMode = videoMode,
            )
        ) {
            VideoCallRefreshAction.None -> true
            VideoCallRefreshAction.NotCurrentCall -> false
            VideoCallRefreshAction.RefreshSession -> reconcileServiceCapabilities(foregroundRequired = false)
            VideoCallRefreshAction.DropForeground -> dropForegroundKeepCall()
        }
    }

    /**
     * 权限被撤回到"一个前台能力都没有"（REVIEW_R5 §5）。
     *
     * 必须真的停掉前台状态：继续按旧类型挂着 FGS 就是在声明自己已经没有的权限。
     * 还有页面绑着就降级成绑定模式（纯文字继续，离开页面即结束）；否则结束这通通话。
     *
     * @return true 表示通话仍在（已降级），false 表示已经结束。
     */
    private fun dropForegroundKeepCall(): Boolean {
        if (callEnded) return false
        if (capabilityState.isForegroundRunning()) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }.onFailure {
                Log.w(TAG, "停止前台服务失败", it)
            }
        }
        capabilityState.onForegroundStopped()
        notificationJob?.cancel()
        notificationJob = null
        notificationUpdatesRunning = false
        if (boundClients <= 0) {
            endCall()
            stopSelf()
            return false
        }
        boundOnly = true
        _boundOnlyCall.value = true
        // 麦克风/ASR **立刻**停掉并释放（REVIEW_R6 §2）：这里没有前台能力了，
        // 再留着录音既是错误的权限声明，重新授权后也分不清"旧链路"还是"这次建好的链路"。
        // 语音链路同时标成 not ready，重新授权时 reconcile 会按新能力补建一次。
        disableAudioPipeline("没有任何前台能力，降级为绑定模式")
        _uiState.update { it.copy(amplitudes = emptyList()) }
        return true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        boundClients = (boundClients - 1).coerceAtLeast(0)
        // 绑定模式的通话只在页面可见期间存在：没有客户端就结束，避免后台偷偷录音/拍照。
        if (boundOnly && !callEnded) {
            endCall()
            stopSelf()
        }
        return super.onUnbind(intent)
    }

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch { _uiState.collect { _activeUiState.value = it } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isChatRecoveryBlocking()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // 用户点了通知栏上的"挂断"按钮
        if (intent?.action == ACTION_HANG_UP) {
            endCall()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TOGGLE_MUTE) {
            toggleMute()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TOGGLE_SPEAKER) {
            toggleSpeaker()
            return START_NOT_STICKY
        }

        val convIdStr = intent?.getStringExtra(EXTRA_CONVERSATION_ID)
        if (convIdStr == null) {
            Log.e(TAG, "onStartCommand 缺少 conversationId 参数, 无法启动通话")
            stopSelf()
            return START_NOT_STICKY
        }

        // 已经在跑同一个对话的通话：绝不"发现同会话就 return"。
        // 这是原来 5 秒必崩的路径：页面在权限过渡期先用绑定模式跑起来，
        // 之后相机/麦克风获批再 startForegroundService 进来，这里必须**同步**对账并
        // 立刻 startForeground（或刷新类型），否则 ForegroundServiceDidNotStartInTimeException。
        if (_activeConversationId.value == convIdStr) {
            if (intent?.getBooleanExtra(EXTRA_VIDEO_MODE, false) == true) startVideoMode()
            if (!reconcileServiceCapabilities(foregroundRequired = true)) {
                // 任何前台能力都没有：不能硬 startForeground，收尾由绑定模式承载。
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        // 兜底: 已经在跑别的对话的通话, 防御性丢弃
        if (_activeConversationId.value != null && _activeConversationId.value != convIdStr) {
            Log.w(
                TAG,
                "已有通话 ${_activeConversationId.value} 在进行, 忽略新的 start 请求 $convIdStr"
            )
            // 丢弃也必须满足 5 秒约定：当前通话有前台能力就刷新一次前台状态，
            // 完全没有前台能力就停掉自己（不留下一个必须崩溃的 start 请求）。
            if (!reconcileServiceCapabilities(foregroundRequired = false) || !capabilityState.isForegroundRunning()) {
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        val video = intent?.getBooleanExtra(EXTRA_VIDEO_MODE, false) == true
        if (!beginCallSession(convIdStr, video = video, foreground = true)) {
            stopSelf(startId)
        }

        // 不用 START_STICKY: 通话被系统杀死不应该自动重启接着录音
        return START_NOT_STICKY
    }

    /**
     * 启动通话会话。
     *
     * [foreground] = true 走前台服务（至少有一个相机/麦克风权限）；
     * false 时由可见 Activity 绑定运行（两个权限都被拒绝的纯文字黑底模式），
     * 不声明任何前台服务类型，离开页面即结束。
     */
    private fun beginCallSession(conversationIdString: String, video: Boolean, foreground: Boolean): Boolean {
        try {
            conversationId = Uuid.parse(conversationIdString)
        } catch (e: Exception) {
            Log.e(TAG, "conversationId 解析失败: $conversationIdString", e)
            return false
        }

        _activeConversationId.value = conversationIdString
        callEnded = false
        callTranscript.clear()
        videoMode = video
        boundOnly = !foreground
        _boundOnlyCall.value = boundOnly
        audioUnavailable = false
        // 新会话：上一通可能留下未清理的实例（异常退出/复用），先释放再进入新会话；
        // 会话号 +1 后，旧实例即使还在内存里也一律不算"这次通话的就绪链路"（REVIEW_R6 §2）。
        releaseAudioPipeline()
        sessionToken += 1
        callStarted = false
        capabilityState.reset()
        turnGate.invalidate()
        endActiveRequestToken(cancelled = true)

        // 关键修复: 必须先同步调用 startForeground, 用一个初始状态的通知占位.
        // Android 要求 startForegroundService() 调用后 5 秒内必须调用 startForeground(),
        // 否则触发 ForegroundServiceDidNotStartInTimeException 崩溃.
        // 不能等 ASR/TTS 异步初始化完成后才调用, 真正的初始化放到下面的协程里做.
        if (foreground && !reconcileServiceCapabilities(foregroundRequired = true)) {
            Log.e(TAG, "startForeground 失败, conversationId=$conversationId")
            _startedAtMillis.value = 0L
            _activeConversationId.value = null
            boundOnly = false
            return false
        }

        startInitialization()
        return true
    }

    /**
     * **幂等的**能力对账：绑定→前台、权限组合变化、首次启动都走这里。
     *
     * 契约：
     * - 只要现在有前台能力，就一定（同步）处于 startForeground 状态，不会留下 5 秒窗口；
     * - 只有实际权限组合变了才刷新前台类型；
     * - 语音链路（ASR/TTS/麦克风）与通知刷新各自只补建一次，不会重复订阅。
     *
     * @return false 表示"现在不应也不能以前台服务运行"（调用方必须 stopSelf）。
     */
    private fun reconcileServiceCapabilities(foregroundRequired: Boolean): Boolean {
        if (callEnded || !::conversationId.isInitialized) return !foregroundRequired
        val cameraGranted = hasCameraPermission()
        val micGranted = hasMicrophonePermission()
        val mode = when {
            capabilityState.isForegroundRunning() -> CallSessionMode.Foreground
            _activeConversationId.value != null -> CallSessionMode.BoundOnly
            else -> CallSessionMode.None
        }
        val plan = VideoCallPolicy.reconcilePlan(
            mode = mode,
            foregroundCapabilities = capabilityState.declaredCapabilities(),
            cameraGranted = cameraGranted,
            micGranted = micGranted,
            videoMode = videoMode,
            // "正在按同一组能力初始化"也算已经处理：重复对账不允许取消/重启初始化
            // （旧写法只看 audioPipelineReady，初始化还没提交时每次刷新都会重启一次，REVIEW_R6 §2）。
            audioPipelineReady = capabilityState.isAudioPipelineSettledFor(
                cameraGranted = cameraGranted,
                micGranted = micGranted,
                videoMode = videoMode,
            ),
            foregroundRequired = foregroundRequired,
        )
        when (plan.action) {
            CallReconcileAction.StopService -> {
                Log.w(TAG, "没有任何前台能力, 不能以前台服务运行, conversationId=$conversationId")
                return false
            }

            CallReconcileAction.StartForegroundNow,
            CallReconcileAction.UpdateForegroundType ->
                if (!startForegroundWith(plan.capabilities)) return !foregroundRequired

            CallReconcileAction.None -> Unit
        }
        if (plan.mode == CallSessionMode.Foreground) {
            boundOnly = false
            _boundOnlyCall.value = false
            ensureNotificationUpdates()
        }
        if (CallForegroundCapability.Microphone !in plan.capabilities) {
            // 这次的权限组合里没有麦克风：**立刻**停录音、释放 ASR，并把语音链路标成 not ready。
            // 只缩前台类型是不够的——旧的 ASR/VAD 会继续录音、报错，重新授权后也会把
            // 旧链路当成"已经建好"（REVIEW_R6 §2）。
            if (shouldInvalidateInputPipeline()) {
                disableAudioPipeline("当前权限组合里没有麦克风")
            } else {
                // 全新会话的第一套初始化还在飞（还没提交、也还没开场）：它还要建 TTS 并
                // startCall 开场，这里**不能**整条取消，否则这通电话永远停在"没开始"。
                // 它建出来的输入链路会在提交前被权限+target 复查丢掉，绝不可能落成 ready
                // （REVIEW_R7 §1）。
                audioUnavailable = true
                capabilityState.setAudioPipelineReady(false)
            }
        } else if (plan.mode == CallSessionMode.Foreground && plan.ensureAudioPipeline) {
            startInitialization()
        }
        return true
    }

    /**
     * 麦克风被撤回时，这次刷新要不要**整条作废**在飞的初始化（REVIEW_R7 §1）。
     *
     * - `hasAsr` / `hasMicrophone`：输入链路已经提交过，必须立刻停录音并释放；
     * - `callStarted`：这通电话已经开场，在飞的初始化只可能是"只补建输入"的
     *   [initializeAudioInput]，取消它不会丢 TTS 与通话状态；
     * - 全新会话的第一套初始化（两者都不成立）→ **不取消**：那条协程还要建 TTS、还要
     *   [startCall] 开场，取消它会让这次通话停在"没开始"，纯文字的输入框也接不上。
     *   它的 ASR 结果由 [initializeCall] 提交前的权限+target 复查丢弃。
     */
    private fun shouldInvalidateInputPipeline(): Boolean = hasAsr || hasMicrophone || callStarted

    /**
     * 提交语音输入链路之前的**最后一次**复查：麦克风权限还在、并且当前能力组合仍然包含麦克风。
     *
     * 只看初始化代次不够：权限撤回不一定会先经过一次 [refreshSessionPermissions]
     * （系统广播/页面回调可能晚到），工厂返回时代次可能仍然是"当前"的（REVIEW_R7 §1）。
     */
    private fun inputCapabilityStillGranted(): Boolean =
        hasMicrophonePermission() &&
            CallForegroundCapability.Microphone in currentCapabilityTarget()

    /** 前台服务类型的位掩码：按**实际启用的能力组合**声明，0 表示不能前台运行。 */
    private fun startForegroundWith(capabilities: Set<CallForegroundCapability>): Boolean {
        val type = foregroundServiceTypeMask(capabilities)
        if (type == 0) return false
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(_uiState.value),
                type,
            )
            capabilityState.onForegroundRunning(capabilities)
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败, conversationId=$conversationId", e)
            false
        }
    }

    /** 后台通知内容刷新：只订阅一次，挂断时取消（否则挂断后还会继续发通知）。 */
    private fun ensureNotificationUpdates() {
        if (notificationUpdatesRunning || boundOnly) return
        notificationUpdatesRunning = true
        notificationJob = serviceScope.launch {
            uiState.collect { state ->
                try {
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, buildNotification(state))
                } catch (e: Exception) {
                    Log.e(TAG, "刷新通话通知失败", e)
                }
            }
        }
    }

    /**
     * 启动一次语音链路初始化（新会话的整套，或换权限后的补建）。
     *
     * 两条防抖（REVIEW_R6 §2）：
     * - **同一组能力已经有初始化在飞** → 直接返回：页面重组/权限回调重复触发时不会
     *   取消并重启同一个初始化（旧实现会反复 cancel/restart，ASR 可能永远起不来）；
     * - 能力组合真的变了（撤权/重新授权）→ 仍然换代并取消旧初始化，旧结果一律作废
     *   （[initializeCall] / [initializeAudioInput] 提交前都复查代次）。
     */
    private fun startInitialization() {
        if (callEnded || !::conversationId.isInitialized) return
        val target = currentCapabilityTarget()
        if (capabilityState.isInitializationInFlightFor(target)) {
            Log.d(TAG, "语音链路已按同一组能力在初始化中, 不重复触发: $target")
            return
        }
        val epoch = ++initEpoch
        initJob?.cancel()
        capabilityState.beginInitialization(epoch, target)
        initJob = serviceScope.launch {
            try {
                // 会话已经开过场（补授权/补建）时只重建输入链路：
                // 不能再次 startCall 重置计时、转写与"正在生成"状态（REVIEW_R6 §2）。
                if (callStarted) initializeAudioInput(epoch) else initializeCall(epoch)
            } catch (cancelled: CancellationException) {
                // 取消不是失败：新通话/补建已经接管，不要写 Error 状态。
                throw cancelled
            } catch (error: Exception) {
                if (isInitEpochCurrent(epoch)) failInit(error)
                else Log.w(TAG, "过期初始化代次失败, 已忽略: ${error.message}")
            } finally {
                capabilityState.endInitialization(epoch)
            }
        }
    }

    private fun currentCapabilityTarget(): Set<CallForegroundCapability> =
        VideoCallPolicy.servicePlan(
            cameraGranted = hasCameraPermission(),
            micGranted = hasMicrophonePermission(),
            videoMode = videoMode,
        ).capabilities

    private fun isInitEpochCurrent(epoch: Long): Boolean =
        epoch == initEpoch && !callEnded && _activeConversationId.value != null

    /**
     * 初始化 ASR/TTS/麦克风并开始通话（新会话：前台与绑定模式共用）。
     *
     * 所有资源先建到局部变量，**代次仍然有效**才一次性提交；过期就把自己建的东西
     * 清理掉（finally），绝不把旧 ASR/TTS 装进新通话。
     *
     * 就绪判定只看**这次初始化本身**（[pendingAsr] 是否真的建出来），
     * 不再用 `lateinit` 变量是否被赋值过（那是"曾经建过"，不是"这次可用"，REVIEW_R6 §2）。
     */
    private suspend fun initializeCall(epoch: Long) {
        var pendingAsr: CustomAsrState? = null
        var pendingTts: CustomTtsState? = null
        var committed = false
        try {
            // 视频模式允许"没有麦克风也能用"：ASR 初始化失败不再让整通电话失败，
            // 面板仍可打字/弹幕/收礼物；纯语音通话保持原来的报错行为。
            val micGranted = hasMicrophonePermission()
            if (VideoCallPolicy.shouldStartAsr(micGranted)) {
                try {
                    pendingAsr = createAsrState()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    if (!videoMode) {
                        if (isInitEpochCurrent(epoch)) failInit(e)
                        return
                    }
                    Log.e(TAG, "视频模式下语音识别初始化失败, 转为纯文字模式", e)
                    audioUnavailable = true
                }
            } else {
                audioUnavailable = true
            }
            if (!isInitEpochCurrent(epoch)) return

            try {
                // 关键修复: 两个工厂函数现在是 suspend 函数, 会真正挂起等待
                // provider 设置完成后才返回实例, 消除了之前 controller 为 null 的竞态.
                pendingTts = createTtsState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (!videoMode) {
                    if (isInitEpochCurrent(epoch)) failInit(e)
                    return
                }
                Log.e(TAG, "视频模式下 TTS 初始化失败, 以静音方式继续", e)
            }
            if (!isInitEpochCurrent(epoch)) return

            // 提交前**再查一次**当前权限与能力组合（REVIEW_R7 §1）：工厂是 suspend 的，
            // 等待期间用户可能已经把麦克风撤回。撤回了就丢掉这次建出来的输入链路，
            // 绝不把它装成"这次就绪"——TTS 与文字通话照常提交，新会话仍然可打字。
            val staleAsr = pendingAsr
            if (staleAsr != null && !inputCapabilityStillGranted()) {
                runCatching { staleAsr.cleanup() }
                pendingAsr = null
            }

            // 提交：先占住字段再生效，提交后不再由 finally 清理。
            // 只有"这通电话里仍然算就绪"的旧链路才需要先释放（已作废/已释放的绝不重复 cleanup）。
            val createdAsr = pendingAsr
            pendingAsr?.let { created ->
                if (hasAsr) runCatching { asr.cleanup() }
                asr = created
                asrToken = sessionToken
                pendingAsr = null
            }
            pendingTts?.let { created ->
                if (hasTts) runCatching { tts.stop() }
                tts = created
                ttsToken = sessionToken
                pendingTts = null
            }
            createdAsr?.let { created ->
                microphone = createMicrophone(created)
                microphoneToken = sessionToken
                audioUnavailable = false
                capabilityState.setAudioPipelineReady(true)
            }
            if (createdAsr == null) {
                // 这次初始化没有建出 ASR（没有权限/失败）：链路 not ready，语音监听关闭，
                // 但通话本身继续（视频模式下纯文字可用）。
                microphoneToken = -1L
                audioUnavailable = true
                capabilityState.setAudioPipelineReady(false)
            }
            committed = true

            startCall()
            ensureNotificationUpdates()
        } finally {
            // 没提交成功的资源（换代/挂断/失败）只清理自己，绝不动当前通话。
            if (!committed) {
                runCatching { pendingAsr?.cleanup() }
                runCatching { pendingTts?.stop() }
            }
        }
    }

    /**
     * 只重建**输入链路**（ASR + 麦克风）：权限被撤回后重新授予时用（REVIEW_R6 §2）。
     *
     * TTS 与当前这一轮的计时/转写/正在生成状态都不动——补建不是"重新开始通话"。
     */
    private suspend fun initializeAudioInput(epoch: Long) {
        if (!hasMicrophonePermission()) {
            // 权限已经不在了：什么都不建，保持 not ready，等下一次授权。
            audioUnavailable = true
            capabilityState.setAudioPipelineReady(false)
            return
        }
        val created = try {
            createAsrState()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "补建语音识别失败, conversationId=$conversationId", e)
            if (isInitEpochCurrent(epoch)) {
                audioUnavailable = true
                capabilityState.setAudioPipelineReady(false)
            }
            return
        }
        if (!isInitEpochCurrent(epoch)) {
            // 换代/挂断发生在工厂挂起期间：只清理自己建出来的东西。
            runCatching { created.cleanup() }
            return
        }
        if (!inputCapabilityStillGranted()) {
            // 补建期间权限被撤回（或能力组合已经变了）：这次建出来的链路一律作废，
            // 绝不提交、绝不标记 ready（REVIEW_R7 §1）。旧链路已经被撤权时释放过，
            // 这里不再碰它，也绝不复用这个 created 实例。
            runCatching { created.cleanup() }
            audioUnavailable = true
            capabilityState.setAudioPipelineReady(false)
            return
        }

        // 只释放"这通电话里仍然算就绪"的旧链路：已作废/已释放的绝不重复 cleanup。
        val previous = if (hasAsr) asr else null
        asr = created
        asrToken = sessionToken
        // 旧链路（已作废的那套）一定要释放：它的转写回调绝不能继续送进这通电话。
        runCatching { previous?.cleanup() }
        microphone = createMicrophone(created)
        microphoneToken = sessionToken
        audioUnavailable = false
        capabilityState.setAudioPipelineReady(true)
        startAudioMonitoring()
    }

    /**
     * 麦克风 + 转写回调。
     *
     * 回调必须先确认"这仍是当前这次通话的链路"：会话号 + 权限 + 是否该听，
     * 三者缺一就不许把转写写进 UI（旧通话的 ASR 回调不许串台，REVIEW_R6 §2）。
     */
    private fun createMicrophone(asrState: CustomAsrState): VoiceCallMicrophone {
        // 回调闭包里固定住创建时的会话号**和**这一套 ASR 实例（REVIEW_R7 §2）：
        // 同一通电话里撤权/补建之后，旧 provider 的迟到回调虽然还算"同一会话号"，
        // 但已经不是当前 ASR 实例，必须一起挡掉（否则它会把旧转写写进新链路）。
        val token = sessionToken
        return VoiceCallMicrophone(asrState, serviceScope) { transcript ->
            if (token == sessionToken && hasAsr && asrState === asr && shouldListenToCall()) {
                _uiState.update { it.copy(userTranscript = transcript) }
            }
        }
    }

    /**
     * 停掉并释放当前语音链路（可重复调用）。
     *
     * 顺序固定：先让麦克风 stop（它自己会在 stop 之前作废转写回调代次），
     * 再释放 ASR/TTS，最后把会话号作废——任何晚到的回调都无法再被当成"当前链路"。
     */
    private fun releaseAudioPipeline() {
        stopMicrophoneAndAsr()
        // 只释放"这次通话里仍然算就绪"的 TTS：挂断、复用、onDestroy 都会再走一遍这里，
        // 已经 cleanup 过的实例绝不能再 cleanup 一次（REVIEW_R7 §2：幂等收尾）。
        if (hasTts) runCatching { tts.cleanup() }.onFailure { Log.w(TAG, "释放 TTS 失败", it) }
        ttsToken = -1L
    }

    /**
     * 停掉并释放**输入**链路（麦克风 + ASR），保留 TTS。
     *
     * 撤回麦克风权限不该把正在朗读的 TTS 掐掉，所以这里只动输入侧。
     */
    private fun stopMicrophoneAndAsr() {
        // 用 has*（会话号仍然匹配）而不是 `::x.isInitialized`：挂断/复用/onDestroy 都会再走一遍，
        // 只判断"变量被赋值过"会把已经 cleanup 的旧实例重复 cleanup（注释说不重复、代码却在重复）。
        if (hasMicrophone) {
            runCatching {
                microphone.update(VoiceCallStatus.Idle, muted = true, keepListeningDuringReply = false)
            }.onFailure { Log.w(TAG, "停止麦克风失败", it) }
        }
        if (hasAsr) runCatching { asr.cleanup() }.onFailure { Log.w(TAG, "释放 ASR 失败", it) }
        asrToken = -1L
        microphoneToken = -1L
    }

    /**
     * **权限不再支持语音输入**时立刻降级（REVIEW_R6 §2）。
     *
     * 这是"撤销麦克风还在录、还在报错、重新授权后也永远是静音"的修复点：
     * - 作废在飞的初始化（它的结果不许落到这通电话上）；
     * - 停麦克风、取消 VAD/ASR/打断/策略协程，旧转写回调立刻失效；
     * - 释放 ASR 并把链路标成 not ready，重新授权时 reconcile 会**重新补建一次**；
     * - 清掉 UI 上的半截转写与振幅，绝不把撤权前的语音留在界面上。
     */
    private fun disableAudioPipeline(reason: String) {
        Log.i(TAG, "禁用语音链路: $reason, conversationId=${if (::conversationId.isInitialized) conversationId else "-"}")
        initEpoch += 1
        initJob?.cancel()
        initJob = null
        capabilityState.cancelInitialization()
        stopMicrophoneAndAsr()
        asrMonitorJob?.cancel()
        asrMonitorJob = null
        vadJob?.cancel()
        vadJob = null
        interruptDetectJob?.cancel()
        interruptDetectJob = null
        microphonePolicyJob?.cancel()
        microphonePolicyJob = null
        audioUnavailable = true
        capabilityState.setAudioPipelineReady(false)
        _uiState.update { it.copy(amplitudes = emptyList(), userTranscript = "") }
    }

    private fun failInit(error: Exception) {
        Log.e(TAG, "初始化语音通话失败, conversationId=$conversationId", error)
        _uiState.update {
            it.copy(
                status = VoiceCallStatus.Error,
                errorMessage = "初始化失败: ${error.message}"
            )
        }
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun hasMicrophonePermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * 前台服务类型按**实际启用的能力组合**拼位掩码：
     * 视频 + 相机 → CAMERA，有麦克风再 OR 上 MICROPHONE。
     * 只有相机没有麦克风时不会声明 MICROPHONE（ASR 也不会启动）。
     */
    private fun foregroundServiceTypeMask(capabilities: Set<CallForegroundCapability>): Int =
        capabilities.fold(0) { type, capability ->
            type or when (capability) {
                CallForegroundCapability.Camera -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                CallForegroundCapability.Microphone -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }

    /**
     * 开始语音通话
     *
     * 麦克风由统一策略管理，回复期间是否监听取决于语音设置。
     */
    fun startCall() {
        if (callStarted) {
            // 这通电话已经开过场（例如补授权后重新建好了输入链路）：
            // 只补建语音监控，**不**重置计时、转写、正在生成的状态（REVIEW_R6 §2）。
            startAudioMonitoring()
            return
        }
        if (_startedAtMillis.value == 0L) {
            _startedAtMillis.value = System.currentTimeMillis()
            interactionStartJob = beginCallInteraction(conversationId, _startedAtMillis.value)
        }
        callStarted = true
        lastAssistantText = ""
        lastSpokenText = ""
        hasSentCurrentMessage = false
        ttsSentLength = 0
        isMuted = false

        _uiState.update {
            it.copy(
                status = VoiceCallStatus.Listening,
                userTranscript = "",
                errorMessage = null,
                isMuted = false
            )
        }

        try {
            syncMicrophone()
        } catch (e: Exception) {
            Log.e(TAG, "启动 ASR 失败, conversationId=$conversationId", e)
            if (!videoMode) {
                _uiState.update {
                    it.copy(
                        status = VoiceCallStatus.Error,
                        errorMessage = "麦克风启动失败: ${e.message}"
                    )
                }
                return
            }
            audioUnavailable = true
        }

        if (hasMicrophone) {
            startVadDetection()
            startAsrMonitor()
        }
        startConversationMonitor()
        if (videoMode) startVideoMode()
    }

    /**
     * 语音监控（ASR/VAD/麦克风策略订阅）：只有在麦克风真的建好之后才启动，
     * 补授权后也只补一次。
     */
    private fun startAudioMonitoring() {
        if (callEnded || !hasMicrophone) return
        startVadDetection()
        startAsrMonitor()
        if (microphonePolicyJob?.isActive != true) {
            microphonePolicyJob = serviceScope.launch {
                combine(
                    uiState.map { it.status to it.isMuted }.distinctUntilChanged(),
                    settingsStore.settingsFlow.map { it.voiceCallKeepListeningDuringReply }.distinctUntilChanged(),
                ) { _, _ -> Unit }.collect { syncMicrophone() }
            }
        }
    }

    /**
     * 从别的状态切回 Listening 时复位状态 + VAD 计时器.
     * 恢复监听策略，尊重当前静音状态。
     */
    private fun startListening(recoveryMessage: String? = null) {
        if (callEnded) return
        if (hasTts) tts.stop()
        ttsSentLength = 0
        lastAssistantText = ""
        hasSentCurrentMessage = false

        _uiState.update { it.listeningAfterReply(recoveryMessage) }

        // 停止"打断检测"协程 (Speaking 状态才需要它)
        interruptDetectJob?.cancel()

        syncMicrophone()

        if (hasMicrophone) startVadDetection()
    }

    /**
     * VAD: 检测用户停顿后自动发送 (仅 Listening 状态生效).
     *
     * 优化: 更快的响应时间, 更灵敏的检测.
     * 阈值参数 (800ms / 2 字符 / 2 秒音量超时) 保持不变, 不要动.
     */
    private fun startVadDetection() {
        vadJob?.cancel()
        vadJob = serviceScope.launch {
            var lastTranscript = ""
            var silenceStartTime: Long = 0L
            var lastAmplitudeTime: Long = System.currentTimeMillis()
            val silenceThresholdMs = 800L
            val minTranscriptLength = 2
            val amplitudeTimeoutMs = 2000L

            while (true) {
                delay(100)
                if (_uiState.value.status != VoiceCallStatus.Listening) break
                if (isMuted) continue // 静音期间不检测, 也不发送
                if (!_uiState.value.autoSendEnabled) continue

                val currentTranscript = _uiState.value.userTranscript
                val amplitudes = _uiState.value.amplitudes
                val recentAmplitude = amplitudes.takeLast(3).average().toFloat()

                // 检测音量活动 - 如果有声音就重置计时
                if (recentAmplitude > 0.05f) {
                    lastAmplitudeTime = System.currentTimeMillis()
                }

                if (currentTranscript != lastTranscript) {
                    // 转写还在变化, 重置静音计时
                    lastTranscript = currentTranscript
                    silenceStartTime = 0L
                } else if (currentTranscript.length >= minTranscriptLength) {
                    // 转写稳定且有内容, 开始/继续计时
                    if (silenceStartTime == 0L) {
                        silenceStartTime = System.currentTimeMillis()
                    }
                    val silentFor = System.currentTimeMillis() - silenceStartTime
                    val amplitudeSilentFor = System.currentTimeMillis() - lastAmplitudeTime

                    // 触发条件: 转写稳定且静音足够, 或音量持续低迷
                    if (silentFor >= silenceThresholdMs || amplitudeSilentFor >= amplitudeTimeoutMs) {
                        Log.d(
                            TAG,
                            "VAD triggered auto-send (silentFor=$silentFor, ampSilent=$amplitudeSilentFor)"
                        )
                        sendCurrentMessage()
                        break
                    }
                }
            }
        }
    }

    /**
     * 发送当前转写的消息.
     * 先切换麦克风策略，再发送请求。
     */
    private fun sendCurrentMessage() {
        if (_uiState.value.status != VoiceCallStatus.Listening || hasSentCurrentMessage) return
        val transcript = _uiState.value.userTranscript.trim()
        vadJob?.cancel()

        if (transcript.isBlank()) {
            // 没有有效内容, 回到监听
            startListening()
            return
        }

        beginTurn(transcript, fromVoice = true)
    }

    /**
     * 视频面板里打的字：走同一套通话对话链（同一队列、同一 TTS、同一挂断清理）。
     * 只有视频模式会用到，纯语音通话不暴露这个入口。
     *
     * 正在生成/正在说话时打字发送视为"开始新一轮"：先把上一轮失效并撤回，
     * 绝不允许旧回调污染新轮。
     *
     * @return false 表示通话还没真正开始（例如服务尚未初始化）或输入为空。
     */
    fun submitTypedText(text: String): Boolean {
        val typed = text.trim()
        if (typed.isEmpty() || callEnded) return false
        if (!::conversationId.isInitialized) return false
        when (_uiState.value.status) {
            VoiceCallStatus.Processing, VoiceCallStatus.Speaking -> invalidateTurnAndWithdraw()
            else -> Unit
        }
        vadJob?.cancel()
        interruptDetectJob?.cancel()
        return beginTurn(typed, fromVoice = false)
    }

    /**
     * 让"当前这一轮"失效并撤回它的请求：打断/挂断/开始新一轮都走这里。
     *
     * 先失效令牌（旧协程在下一个检查点会自己退出），再取消旧任务，最后撤回队列里的请求。
     */
    private fun invalidateTurnAndWithdraw() {
        turnGate.invalidate()
        turnJob?.cancel()
        turnJob = null
        speakingMonitorJob?.cancel()
        speakingMonitorJob = null
        interruptDetectJob?.cancel()
        val entry = activeVoiceMessage
        activeVoiceMessage = null
        // 授权先关掉：被打断的这一轮即使还有旧回调跑完，也不可能再送礼/留影。
        // 取消（而不是完成）还要撤销它已经发出的留影许可。
        endActiveRequestToken(cancelled = true)
        if (entry != null) {
            voiceRequestIds -= entry.id
            if (::conversationId.isInitialized) chatService.cancelQueuedMessage(conversationId, entry.id)
        }
        if (hasTts) tts.stop()
    }

    /** 生成一轮视频请求的授权 token；非视频通话返回 null（也就没有任何送礼/留影权限）。 */
    private fun beginTurnRequestIfVideoMode(): String? {
        if (!videoMode) return null
        val coordinator = video ?: return null
        val candidate = "vc-${Uuid.random()}"
        if (!coordinator.beginTurnRequest(candidate)) return null
        activeRequestToken = candidate
        return candidate
    }

    private fun endActiveRequestToken(cancelled: Boolean = false) {
        val token = activeRequestToken ?: return
        activeRequestToken = null
        closeRequestToken(token, cancelled)
    }

    /**
     * 关闭**指定的**那个 token，并且只在它仍是"当前 token"时才清全局变量。
     *
     * 旧实现只有一个全局 [activeRequestToken]：上一轮抓帧的协程在新一轮 `begin` 之后才跑到
     * finally，它一把 `activeRequestToken` 置空/关闭，就会把**新一轮**的授权关掉。
     * token 必须是局部的，全局变量只是"当前这一轮"的便捷指针。
     */
    private fun closeRequestToken(token: String, cancelled: Boolean) {
        if (activeRequestToken == token) activeRequestToken = null
        val coordinator = video ?: return
        if (cancelled) coordinator.cancelTurnRequest(token) else coordinator.completeTurnRequest(token)
    }

    private fun beginTurn(text: String, fromVoice: Boolean): Boolean {
        if (callEnded) return false
        // 新一轮开始前先让上一轮彻底失效（挂断/打断/旧请求回调都不许再提交）
        turnGate.invalidate()
        turnJob?.cancel()
        endActiveRequestToken(cancelled = true)
        val token = turnGate.begin()
        _uiState.update {
            it.copy(
                status = VoiceCallStatus.Processing,
                userTranscript = if (fromVoice) it.userTranscript else text.take(MAX_TYPED_TRANSCRIPT),
                assistantText = "",
                errorMessage = null
            )
        }
        syncMicrophone()
        if (hasTts) tts.stop()
        ttsSentLength = 0
        lastAssistantText = ""
        hasSentCurrentMessage = true
        video?.onUserTurn(text)
        turnJob = serviceScope.launch {
            try {
                dispatchTurn(text, token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "发送通话消息失败, conversationId=$conversationId", error)
                if (turnGate.isCurrent(token)) recoverAfterReplyFailure("发送失败: ${error.message}")
            }
        }
        return true
    }

    private suspend fun dispatchTurn(text: String, token: Long) {
        if (!turnGate.isCurrent(token) || callEnded) return
        val parts = mutableListOf<UIMessagePart>(UIMessagePart.Text(text))
        // 仅本次请求有效的临时图片（自动抓帧），请求结束后由 ChatService 删除。
        val transientImages = mutableListOf<String>()
        // 手动夹帧是永久文件：没交出去（打断/挂断/提交失败）就必须自己删，否则留下孤儿照片。
        val permanentImages = mutableListOf<String>()
        var handedOff = false
        // 视频通话的请求 token：由这里生成，随队列项不可变地传到生成管线。
        // 非视频通话没有 token，礼物/留影因此不会在任何其它生成里生效。
        val requestToken = beginTurnRequestIfVideoMode()
        try {
            // 视频模式：本轮要附带的画面。手动夹帧永久保存，自动帧只作本次请求的临时输入。
            video?.let { coordinator ->
                val attachment = coordinator.frameAttachmentForTurn()
                if (attachment != null) {
                    if (attachment.permanent) {
                        parts += UIMessagePart.Image(url = attachment.url)
                        permanentImages += attachment.url
                    } else {
                        transientImages += attachment.url
                    }
                }
            }
            // 抓帧/压缩是挂起操作，期间可能已经被打断或挂断：提交前必须再校验一次。
            if (!turnGate.isCurrent(token) || callEnded) {
                if (transientImages.isNotEmpty()) VideoFrameStore.deleteTransient(applicationContext, transientImages)
                permanentImages.forEach { VideoFrameStore.deleteOwned(applicationContext, it) }
                return
            }
            val attachedPicture = parts.any { it is UIMessagePart.Image } || transientImages.isNotEmpty()
            val prompts = callSystemPrompts(attachedPicture)
            val entry = requireNotNull(
                chatService.submitMessage(
                    conversationId,
                    parts.toList(),
                    transientSystemPrompt = prompts.actual,
                    transientImageUrls = transientImages.toList(),
                    // 本次新建、还没进历史的永久帧：只有"没写入历史"时才允许删（ChatService 负责）。
                    ownedImageUrls = permanentImages.toList(),
                    // 队列暂停会摘掉"本轮附图"，届时改用无图版本的视频上下文。
                    transientSystemPromptWithoutImages = prompts.withoutTransientImages,
                    // 授权随队列项不可变传入；入队与授权不存在"先失败一半"的中间态。
                    requestToken = requestToken,
                )
            ) { "语音内容为空或会话已删除" }
            handedOff = true
            if (!turnGate.isCurrent(token)) {
                // 提交过程中被打断：立刻撤回这条请求，不留在队列里。
                voiceRequestIds -= entry.id
                if (::conversationId.isInitialized) chatService.cancelQueuedMessage(conversationId, entry.id)
                return
            }
            activeVoiceMessage = entry
            callTranscript.record("user:" + entry.id, "user",
                settingsStore.settingsFlow.value.displaySetting.userNickname.ifBlank { "我" }, text)
            voiceRequestIds += entry.id
            speakingMonitorJob?.cancel()
            speakingMonitorJob = serviceScope.launch {
                try {
                    val finalText = entry.reply.await()
                    voiceRequestIds -= entry.id
                    if (activeVoiceMessage !== entry) return@launch
                    if (finalText == null) {
                        failVoiceReply(entry, "本次语音消息已撤回或中断")
                    } else if (finalText.isBlank()) {
                        failVoiceReply(entry, "本次未返回可朗读的回复，请到聊天页查看")
                    } else {
                        publishVoiceReply(entry, queuedSpeechReplyText(conversation.value.currentMessages, entry.id)
                            .ifBlank { finalText })
                        onGenerationDone(entry)
                    }
                } catch (cancelled: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw cancelled
                    voiceRequestIds -= entry.id
                    failVoiceReply(entry, "本次回复已中断，请重新发送")
                } catch (_: MessageQueuePausedException) {
                    // A paused entry remains queued; hang-up can still withdraw this utterance by ID.
                    failVoiceReply(entry, "发送队列已暂停，请先在聊天页继续或撤回")
                } catch (_: Exception) {
                    voiceRequestIds -= entry.id
                    failVoiceReply(entry, "本次回复失败，请到聊天页查看错误")
                }
            }
        } finally {
            // 没交出去的临时帧必须自己清掉；交出去的那份由 ChatService 在请求结束时删除。
            if (!handedOff) {
                if (transientImages.isNotEmpty()) {
                    VideoFrameStore.deleteTransient(applicationContext, transientImages)
                }
                // 手动夹帧同样是"没交出去就删"：不能留下没人引用的永久照片。
                permanentImages.forEach { VideoFrameStore.deleteOwned(applicationContext, it) }
                // 这一轮根本没进队列：**这个** token 的生命周期到此结束。
                // 交出去之后 token 的所有权在请求本身（ChatService job 的 finally 决定
                // 是 complete 还是 cancel），这里绝不能提前关掉它 —— 那会让本轮礼物/留影全部失效。
                // 关键：只关自己的 token，旧协程晚到的 finally 不许把新一轮的授权关掉。
                requestToken?.let { closeRequestToken(it, cancelled = true) }
            }
        }
    }

    /**
     * 本轮的 system prompt。
     *
     * [actual] 是这一轮真实附图（手动夹帧或自动抓帧）时用的版本；
     * [withoutTransientImages] 是**同一轮**在"本轮附图"被摘掉之后改用（队列暂停会让临时图失效，
     * 永久手动图仍然在 parts 里，所以那种情况不用这个变体）。
     */
    private data class TurnSystemPrompts(val actual: String?, val withoutTransientImages: String?)

    private fun callSystemPrompts(pictureAttached: Boolean): TurnSystemPrompts {
        val speech = speechDeliveryPrompt(settingsStore.settingsFlow.value.getSelectedTTSProvider())
        fun join(videoPrompt: String?): String? = listOfNotNull(
            speech.takeIf { it.isNotBlank() },
            videoPrompt?.takeIf { it.isNotBlank() },
        ).joinToString("\n\n").ifBlank { null }

        // 语音通话原有的 system prompt（朗读风格），视频模式下再叠加视频上下文。
        val coordinator = video ?: return TurnSystemPrompts(join(null), null)
        // 本轮的备注只被"真实版本"消费一次；无图变体不再重复取用。
        val withPicture = coordinator.turnContext(pictureAttached, includeTurnNotes = true)
        val withoutPicture = if (pictureAttached) {
            coordinator.turnContext(pictureAttached = false, includeTurnNotes = false)
        } else {
            withPicture
        }
        return TurnSystemPrompts(join(withPicture), join(withoutPicture))
    }


    /**
     * 监听对话流变化, 实现:
     * 1. 流式 TTS (检测到新句子即朗读)
     * 2. AI 开始输出时立即进入 Speaking 状态, 让用户可以打断
     * 3. AI 回复完成后回到 Listening
     */
    private fun startConversationMonitor() {
        conversationMonitorJob?.cancel()
        conversationMonitorJob = serviceScope.launch {
            conversation.collect { conv ->
                if (_uiState.value.status != VoiceCallStatus.Processing &&
                    _uiState.value.status != VoiceCallStatus.Speaking
                ) return@collect

                val entry = activeVoiceMessage ?: return@collect
                val currentText = queuedSpeechReplyText(conv.currentMessages, entry.id)
                if (currentText.isNotBlank()) publishVoiceReply(entry, currentText)
            }
        }
    }

    private fun publishVoiceReply(entry: QueuedMessage, currentText: String) {
        if (activeVoiceMessage !== entry || _uiState.value.status !in
            setOf(VoiceCallStatus.Processing, VoiceCallStatus.Speaking)) return
        // 防御：任何协议标签都不允许进入气泡/字幕/TTS。
        val text = VideoGiftProtocol.strip(currentText)
        _uiState.update { it.copy(assistantText = SpeechMarkup.transcript(text)) }
        callTranscript.record("assistant:" + entry.id, "assistant",
            settingsStore.settingsFlow.value.assistants.firstOrNull { it.id == conversation.value.assistantId }
                ?.name?.ifBlank { "对方" } ?: "对方", text)
        video?.onAssistantText(text)
        if (hasTts && text.length > ttsSentLength) {
            val newText = text.substring(ttsSentLength)
            val ready = SpeechMarkup.readyPrefixLength(newText)
            if (ready > 0) tts.enqueueText(newText.substring(0, ready))
            ttsSentLength += ready
        }
        if (_uiState.value.status == VoiceCallStatus.Processing && text.isNotBlank()) {
            _uiState.update { it.copy(status = VoiceCallStatus.Speaking) }
            if (hasMicrophone) startInterruptDetection()
        }
        lastAssistantText = text
    }

    private fun failVoiceReply(entry: QueuedMessage, message: String) {
        if (activeVoiceMessage !== entry) return
        recoverAfterReplyFailure(message)
    }

    private fun recoverAfterReplyFailure(message: String) {
        if (callEnded) return
        activeVoiceMessage = null
        // 这一轮失败了：授权随之关闭，并且撤销它已经发出的留影许可 ——
        // 一次失败的回复不该还能把画面写进聊天。
        endActiveRequestToken(cancelled = true)
        interruptDetectJob?.cancel()
        // Invalidate partial ASR text from the failed turn, even with reply-listening enabled.
        if (hasMicrophone) {
            microphone.update(VoiceCallStatus.Idle, muted = true, keepListeningDuringReply = false)
        }
        startListening(message + "；已恢复聆听，请重新说。")
    }

    private suspend fun onGenerationDone(entry: QueuedMessage) {
        if (activeVoiceMessage !== entry || _uiState.value.status !in
            setOf(VoiceCallStatus.Processing, VoiceCallStatus.Speaking)) return
        // 朗读最后剩余的文本
        val finalText = lastAssistantText
        if (hasTts && finalText.length > ttsSentLength) {
            val remaining = finalText.substring(ttsSentLength)
            if (remaining.isNotBlank()) {
                tts.enqueueText(remaining)
                ttsSentLength = finalText.length
            }
        }
        // 收尾：把最后半句也补成一条气泡
        video?.onAssistantTurnFinished(finalText)
        // 这一轮**正常结束**：关闭授权 token，但保留它已经发出的留影许可
        // （合法的异步留影本来就落在请求结束之后）。
        endActiveRequestToken(cancelled = false)

        _uiState.update { it.copy(status = VoiceCallStatus.Speaking) }
        if (hasMicrophone) startInterruptDetection()
        if (hasTts) waitForTtsToFinish()

        // 回到监听
        if (activeVoiceMessage === entry && _uiState.value.status == VoiceCallStatus.Speaking) {
            activeVoiceMessage = null
            startListening()
        }
    }

    private suspend fun waitForTtsToFinish() {
        // 等待 TTS 开始播放
        var waitStart = System.currentTimeMillis()
        while (!tts.isSpeaking.value && System.currentTimeMillis() - waitStart < 5000) {
            delay(100)
        }
        // 等待 TTS 播放完成.
        // 不能只靠 isSpeaking: 它在 worker 的 finally 里才会变 false,
        // 一旦 worker 挂在网络请用/音频播放上 (isSpeaking 永远 true),
        // 这里就死循环, 通话永远卡在 "正在传达".
        // 改用 "活动超时": 跟踪 TTS 最后一次处于活动状态的时间,
        // 连续 5 秒没有新的播放活动(不是 Playing/Buffering 且 isSpeaking 为 false)
        // 就认为说完了. 另勠 5 分钟硬截止兜底.
        val idleTimeoutMs = 5_000L
        val hardDeadlineMs = 300_000L
        val startTime = System.currentTimeMillis()
        var lastActiveTime = System.currentTimeMillis()
        while (true) {
            val now = System.currentTimeMillis()
            val status = tts.playbackState.value.status
            val active = tts.isSpeaking.value ||
                status == me.rerere.tts.model.PlaybackStatus.Playing ||
                status == me.rerere.tts.model.PlaybackStatus.Buffering
            if (active) {
                lastActiveTime = now
            }
            // 连续 idleTimeoutMs 没活动 → 说完了
            if (!active && now - lastActiveTime >= idleTimeoutMs) {
                break
            }
            // 硬截止兜底 (TTS 真卡死)
            if (now - startTime > hardDeadlineMs) {
                Log.w(TAG, "TTS 播放超过 5 分钟未结束, 强制停止以防卡死")
                tts.stop()
                break
            }
            delay(300)
        }
        // 额外等待状态更新
        delay(300)
    }

    /**
     * Speaking 状态下的打断检测.
     *
     * 与 startVadDetection (判断"该发送了") 职责不同:
     * 这里只关心"用户是否开始说话了", 一旦检测到就立即打断, 不等静音判断.
     *
     * 用比 Listening 状态更高的音量阈值 (0.15f vs 0.05f), 降低被 AI 自己声音误触发的概率.
     * 残留风险: AEC 不是 100% 完美, 外放音量很大或低端机型硬件 AEC 差时仍可能误触发,
     * 后续可加音量差阈值调优, 但不阻塞现在的实现.
     */
    private fun startInterruptDetection() {
        interruptDetectJob?.cancel()
        interruptDetectJob = serviceScope.launch {
            var baselineTranscript = _uiState.value.userTranscript
            while (true) {
                delay(150)
                if (_uiState.value.status != VoiceCallStatus.Speaking) break
                if (!shouldListenToCall() || !hasMicrophone || !microphone.isListening) continue

                val currentTranscript = _uiState.value.userTranscript
                val amplitudes = _uiState.value.amplitudes
                val recentAmplitude = amplitudes.takeLast(3).average().toFloat()

                // 转写文本相较于进入 Speaking 时有新增内容, 或者音量突然超过阈值,
                // 都视为"用户开始说话了"
                val hasNewTranscript = currentTranscript.length > baselineTranscript.length + 1
                val hasLoudVoice = recentAmplitude > 0.15f

                if (hasNewTranscript || hasLoudVoice) {
                    Log.d(
                        TAG,
                        "检测到用户打断, amplitude=$recentAmplitude"
                    )
                    interruptSpeaking()
                    break
                }
            }
        }
    }

    /**
     * 用户打断 AI 说话 (Barge-in).
     * 手动打断始终可用，返回 Listening 后恢复麦克风策略。
     */
    fun interruptSpeaking() {
        if (_uiState.value.status != VoiceCallStatus.Speaking) return
        invalidateTurnAndWithdraw()
        startListening()
    }

    /**
     * 面板上点"计时/头像"徽章时的打断：
     * 正在说话就停 TTS 并回到聆听，正在生成就撤回这一轮请求，纯文字模式只停语音。
     */
    fun requestInterrupt() {
        when (_uiState.value.status) {
            VoiceCallStatus.Speaking -> interruptSpeaking()
            VoiceCallStatus.Processing -> {
                invalidateTurnAndWithdraw()
                startListening("已打断这一轮")
            }
            else -> if (hasTts) tts.stop()
        }
    }

    /**
     * 监听 ASR 状态 (用于非流式 ASR 如 SiliconFlow).
     * 当 ASR 从 Recording -> Idle 且转写不为空时, 立即发送.
     *
     * 加了 !isMuted 判断, 避免静音操作本身触发的 Recording→非Recording 跳变被误判成"该发送了".
     */
    private fun startAsrMonitor() {
        if (!hasAsr || !hasMicrophone) return
        asrMonitorJob?.cancel()
        // 绑定到**这一套** ASR 与这次会话：换链路/换通话之后旧状态流不许再驱动 UI。
        val asrStream = asr
        val token = sessionToken
        asrMonitorJob = serviceScope.launch {
            asrStream.state.collect { asrState ->
                // 除了会话号，还要求**就是这个 ASR 实例**：同一通电话里撤权/补建之后
                // 旧 provider 的状态流仍然会冒出事件，它不许再驱动 UI（REVIEW_R7 §2）。
                if (token != sessionToken || !hasAsr || !hasMicrophone || asrStream !== asr) return@collect
                updateAmplitudes(asrState.amplitudes)
                if (asrState.status == me.rerere.asr.ASRStatus.Error) {
                    if (!callEnded && microphone.isListening) {
                        val msg = asrState.errorMessage ?: "语音识别发生未知错误"
                        Log.e(TAG, "ASR 底层报错, conversationId=$conversationId, msg=$msg")
                        _uiState.update {
                            it.copy(status = VoiceCallStatus.Error, errorMessage = "语音识别错误: $msg")
                        }
                        syncMicrophone()
                    }
                    return@collect
                }
                val isRecording = asrState.isRecording
                val completed = microphone.completedTranscript(asrState)
                if (completed != null && shouldListenToCall()) {
                    val transcript = completed.trim()
                    _uiState.update { it.copy(userTranscript = transcript) }
                    if (_uiState.value.status == VoiceCallStatus.Listening &&
                        transcript.isNotEmpty() && _uiState.value.autoSendEnabled) {
                        Log.d(TAG, "ASR monitor: Auto-send after ASR completed")
                        sendCurrentMessage()
                    }
                }

                // A paused provider may take time to stop; retry a pending resume only then.
                if (!isRecording) {
                    runCatching { microphone.resumeIfPending() }
                        .onFailure { Log.e(TAG, it.toString(), it) }
                }
            }
        }
    }

    /**
     * 切换静音. 在任何状态下都要能生效/取消, 不再判断 status.
     * 取消静音后仍遵守“回复期间继续监听”设置。
     */
    fun toggleMute() {
        isMuted = !isMuted
        _uiState.update { it.copy(isMuted = isMuted) }

        syncMicrophone()
    }

    fun toggleSpeaker() {
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        val enableSpeaker = !_uiState.value.isSpeakerOn
        try {
            if (initialAudioMode == null) {
                initialAudioMode = audioManager.mode
                @Suppress("DEPRECATION")
                run { initialSpeakerphoneOn = audioManager.isSpeakerphoneOn }
            }
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                if (initialCommunicationDevice == null) initialCommunicationDevice = audioManager.communicationDevice
                val targetType = if (enableSpeaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                val device = if (enableSpeaker) {
                    audioManager.availableCommunicationDevices.firstOrNull { it.type == targetType }
                } else {
                    initialCommunicationDevice?.takeIf { it.type == targetType }
                        ?: audioManager.availableCommunicationDevices.firstOrNull { it.type == targetType }
                }
                check(device != null && audioManager.setCommunicationDevice(device)) {
                    if (enableSpeaker) "扬声器当前不可用" else "听筒当前不可用"
                }
            } else {
                @Suppress("DEPRECATION")
                run { audioManager.isSpeakerphoneOn = enableSpeaker }
            }
            _uiState.update { it.copy(isSpeakerOn = enableSpeaker, errorMessage = null) }
        } catch (error: Exception) {
            _uiState.update { it.copy(errorMessage = error.message ?: "切换扬声器失败") }
        }
    }

    /**
     * 切换自动发送模式. UI 上不再挂载按钮, 但保留方法 (autoSendEnabled 字段仍在用).
     */
    fun toggleAutoSend() {
        _uiState.update { it.copy(autoSendEnabled = !it.autoSendEnabled) }
    }

    /**
     * 打开视频模式：注册进程内桥接、启动留影/礼物订阅。
     * 幂等——重复调用不会重建，避免重入页面时重复绑定相机。
     */
    private fun startVideoMode() {
        videoMode = true
        if (video != null) return
        if (!::conversationId.isInitialized) return
        val coordinator = createVideoCoordinator(
            applicationContext,
            serviceScope,
            conversationId,
        ) { visionSupported() } ?: return
        video = coordinator
        _videoCallActive.value = true
        // 把 coordinator 的状态镜像到**服务自己的稳定 StateFlow** 上：
        // 面板订阅的对象不再随 coordinator 重建而变化，弹幕/计数/提示永远是当前状态。
        panelStateJob?.cancel()
        panelStateJob = serviceScope.launch {
            coordinator.state.collect { _panelState.value = it }
        }
        coordinator.start()
        // 视频通话默认外放（和常见视频通话一致）；纯语音通话的音频路由逻辑不变。
        if (!_uiState.value.isSpeakerOn) {
            runCatching { toggleSpeaker() }.onFailure { Log.w(TAG, "视频通话切换外放失败", it) }
        }
    }

    /** 当前会话的聊天模型能不能真正看到画面。 */
    internal fun visionSupported(): Boolean {
        val current = runCatching { conversation.value }.getOrNull() ?: return false
        val model = settingsStore.settingsFlow.value.getChatModelOf(current) ?: return false
        return Modality.IMAGE in model.inputModalities
    }

    /**
     * 挂断 / 结束通话.
     * 额外复位 _activeConversationId 和移除前台通知.
     */
    fun endCall() {
        // 先记住"这通电话到底有没有在前台跑"：下面会多次重置能力状态，
        // 之前先 reset 再查 isForegroundRunning() 恒为 false，于是**永远不 stopForeground**，
        // 通知和前台服务一直被留到 onDestroy（REVIEW_R6 §1）。
        val foregroundWasRunning = capabilityState.isForegroundRunning()
        val giftSummary = video?.giftSummary().orEmpty()
        if (!callEnded && ::conversationId.isInitialized && _startedAtMillis.value > 0L) {
            // 只有真的开始过通话交互才收尾：绑定模式下从未 startCall 的服务不该去动
            // 这条会话里别人的 connecting 通话卡片。
            finishCallInteraction(
                conversationId,
                interactionStartJob,
                _startedAtMillis.value,
                callTranscript.snapshot(),
                giftSummary,
            )
        }
        interactionStartJob = null
        callEnded = true
        callStarted = false
        // 先让这一轮彻底失效（等待抓帧/等待提交的旧任务会自己退出），再取消。
        turnGate.invalidate()
        turnJob?.cancel()
        turnJob = null
        // 挂断：这一轮的 token 与它发出的留影许可全部作废（下面 exitVideoCall 还会整体清一遍）。
        endActiveRequestToken(cancelled = true)
        // 初始化协程换代：旧 ASR/TTS 的恢复不许再影响任何后来的通话。
        initEpoch += 1
        initJob?.cancel()
        initJob = null
        capabilityState.cancelInitialization()
        panelStateJob?.cancel()
        panelStateJob = null
        _panelState.value = VideoCallPanelState()
        notificationJob?.cancel()
        notificationJob = null
        notificationUpdatesRunning = false
        video?.close()
        video = null
        videoMode = false
        VideoCallRuntime.exitVideoCall()
        // 只清理"安全龄期"之外的孤儿临时帧：还在被别的请求使用的图绝不能被扫掉。
        VideoFrameStore.sweepStaleTransients(applicationContext)
        microphonePolicyJob?.cancel()
        microphonePolicyJob = null
        activeVoiceMessage = null
        vadJob?.cancel()
        speakingMonitorJob?.cancel()
        conversationMonitorJob?.cancel()
        asrMonitorJob?.cancel()
        interruptDetectJob?.cancel()
        if (::conversationId.isInitialized) {
            voiceRequestIds.toList().forEach { chatService.cancelQueuedMessage(conversationId, it) }
        }
        voiceRequestIds.clear()
        // 挂断必须**释放**语音链路（不只是 stop）：否则被复用承载下一通电话时，
        // 旧 ASR 会被当成"这次已经建好"的链路，旧转写回调也可能串到新通话（REVIEW_R6 §2）。
        releaseAudioPipeline()
        // 换会话：会话号 +1，任何还在内存里的旧实例都不再算"这次通话的链路"。
        sessionToken += 1
        audioUnavailable = false
        restoreCallAudioRoute()
        _uiState.update {
            it.copy(
                status = VoiceCallStatus.Idle,
                isSpeakerOn = false,
                amplitudes = emptyList(),
                userTranscript = "",
            )
        }
        _startedAtMillis.value = 0L
        _activeConversationId.value = null
        _videoCallActive.value = false
        // 前台与通知：**挂断当场**停掉，不等 onDestroy（页面还绑着服务时 onDestroy 很久才来）。
        if (foregroundWasRunning) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
                Log.e(TAG, "stopForeground 失败", e)
            }
        }
        capabilityState.reset()
        boundOnly = false
        _boundOnlyCall.value = false
    }

    private fun restoreCallAudioRoute() {
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
                initialCommunicationDevice = null
            } else if (initialAudioMode != null) {
                @Suppress("DEPRECATION")
                run { audioManager.isSpeakerphoneOn = initialSpeakerphoneOn }
            }
            initialAudioMode?.let { audioManager.mode = it }
            initialAudioMode = null
        }.onFailure { Log.w(TAG, "恢复通话音频路由失败", it) }
    }

    /**
     * 更新振幅数据 (供 UI 动画使用)
     */
    fun updateAmplitudes(amplitudes: List<Float>) {
        _uiState.update { it.copy(amplitudes = if (shouldListenToCall()) amplitudes else emptyList()) }
    }

    /**
     * 这一轮要不要继续监听。
     *
     * 除了通话状态/静音/设置，还必须看**当前权限**与"这次会话的麦克风是否真的建好"：
     * 旧写法完全不读 `hasMicrophonePermission()`，麦克风被撤回后仍会继续录音并报错
     * （REVIEW_R6 §2）。
     */
    private fun shouldListenToCall(): Boolean =
        !callEnded && hasMicrophonePermission() && VideoCallPolicy.shouldListen(
            status = _uiState.value.status,
            muted = isMuted,
            keepListeningDuringReply = settingsStore.settingsFlow.value.voiceCallKeepListeningDuringReply,
            audioUnavailable = audioUnavailable || !hasMicrophone,
        )

    private fun syncMicrophone() {
        if (!hasMicrophonePermission()) {
            // 权限已经不在了：把还在跑的录音**主动停掉**，链路标成 not ready。
            // 只 return 不 stop 会让麦克风（以及旧 ASR）继续录，重新授权后也永远是静音。
            audioUnavailable = true
            capabilityState.setAudioPipelineReady(false)
            if (::microphone.isInitialized) {
                runCatching {
                    microphone.update(VoiceCallStatus.Idle, muted = true, keepListeningDuringReply = false)
                }.onFailure { Log.w(TAG, "权限撤回后停止麦克风失败", it) }
            }
            microphoneToken = -1L
            _uiState.update { it.copy(amplitudes = emptyList()) }
            return
        }
        // 没有麦克风/ASR 不可用：纯文字模式，绝不把整通电话打成 Error。
        if (audioUnavailable || !hasMicrophone) {
            _uiState.update { it.copy(amplitudes = emptyList()) }
            return
        }
        try {
            microphone.update(
                if (callEnded) VoiceCallStatus.Idle else _uiState.value.status,
                isMuted,
                settingsStore.settingsFlow.value.voiceCallKeepListeningDuringReply,
            )
            if (!microphone.isListening) _uiState.update { it.copy(amplitudes = emptyList()) }
        } catch (error: Exception) {
            Log.e(TAG, "麦克风状态切换失败", error)
            if (videoMode) {
                // 视频通话的纯文字/礼物链路不能被麦克风错误打断。
                audioUnavailable = true
                _uiState.update { it.copy(amplitudes = emptyList()) }
            } else {
                _uiState.update { it.copy(status = VoiceCallStatus.Error, errorMessage = "麦克风切换失败: ${error.message}") }
            }
        }
    }

    /**
     * 构建通话通知. 通话中这种更醒目、带操作按钮的通知.
     */
    private fun buildNotification(state: VoiceCallUiState): android.app.Notification {
        val contentText = when (state.status) {
            VoiceCallStatus.Listening -> "正在聆听..."
            VoiceCallStatus.Processing -> "正在思考..."
            VoiceCallStatus.Speaking -> "正在说话..."
            VoiceCallStatus.Error -> state.errorMessage ?: "通话出错"
            VoiceCallStatus.Idle -> "通话中"
        }

        // 点击通知本体: 回到 RouteActivity 并导航到 VoiceCallPage
        val contentIntent = PendingIntent.getActivity(
            this,
            conversationId.hashCode(),
            Intent(this, RouteActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("openVoiceCallConversationId", conversationId.toString())
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 通知上的"挂断"按钮: 直接发一个带 ACTION_HANG_UP 的 Intent 给自己这个 Service
        val hangUpIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, VoiceCallService::class.java).apply { action = ACTION_HANG_UP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, VOICE_CALL_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("语音通话")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.small_icon)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, "挂断", hangUpIntent)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            // 兜底, 防止外部通过 stopService 直接杀掉时状态没清理干净。
            // endCall → releaseAudioPipeline 已经按 hasAsr/hasTts/hasMicrophone 释放过
            // "这次通话里仍然算就绪"的实例；这里再查一次同样只看 has*（会话号匹配），
            // 绝不重复 cleanup 已经释放的 ASR/TTS（REVIEW_R7 §2：注释说不重复，代码也要不重复）。
            endCall()
            if (hasAsr) runCatching { asr.cleanup() }
            if (hasTts) runCatching { tts.cleanup() }
            asrToken = -1L
            ttsToken = -1L
            microphoneToken = -1L
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy 清理失败", e)
        }
        serviceScope.cancel()
    }
}
