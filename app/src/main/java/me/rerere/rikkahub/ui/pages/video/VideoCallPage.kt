/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.video

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.os.IBinder
import android.util.Log
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.video.VIDEO_GIFT_EFFECT_DURATION_MS
import me.rerere.rikkahub.data.video.CameraFacing
import me.rerere.rikkahub.data.video.DanmakuLine
import me.rerere.rikkahub.data.video.PreviewCalibration
import me.rerere.rikkahub.data.video.PreviewTransform
import me.rerere.rikkahub.data.video.VideoCallPolicy
import me.rerere.rikkahub.data.video.VideoCallRuntime
import me.rerere.rikkahub.data.video.VideoCallStartAction
import me.rerere.rikkahub.data.video.VideoCameraState
import me.rerere.rikkahub.data.video.VideoCameraStatus
import me.rerere.rikkahub.data.video.VideoFrame
import me.rerere.rikkahub.data.video.VideoFrameScheduler
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.video.skipIfMatching
import me.rerere.rikkahub.data.video.VideoGiftEffectQueue
import me.rerere.rikkahub.data.video.VideoGiftEffectRequest
import me.rerere.rikkahub.data.video.toAndroidMatrix
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.VoiceCallService
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.vector.ImageVector
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.MicOff01
import me.rerere.hugeicons.stroke.Record
import me.rerere.hugeicons.stroke.Speaker01
import me.rerere.hugeicons.stroke.Stop
import me.rerere.rikkahub.data.video.VideoVoicePolicy
import me.rerere.rikkahub.data.video.VideoVoiceStatus
import me.rerere.rikkahub.data.video.VideoVoiceUiState
import me.rerere.rikkahub.ui.components.message.InteractionColors
import me.rerere.rikkahub.ui.components.message.interactionColors
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.PermissionStatus
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import kotlin.uuid.Uuid

private const val TAG = "VideoCallPage"

// 旧的"上浮弹幕"（6 秒淡出、同屏最多 6 条）已被常驻字幕列表取代：
// 真机要求文字常驻可回看，寿命动画恰好是"看不到刚才那句"的原因。
// 保留常量只为说明历史行为，界面上已经不再引用。

/**
 * 视频通话面板。
 *
 * 视觉：与既有语音页同一套明亮玻璃主题（`interactionColors` 渐变底 + 白色玻璃卡 + 中文小字），
 * **不再是覆盖整屏的黑色遮罩**。镜头画面缩进一张圆角卡里，始终可见但不铺满。
 *
 * 文字：常驻可回看。`CallTranscriptList` 用 LazyColumn + 稳定 id 渲染服务侧的有界历史
 * （最多 400 行），不再 6 秒淡出、也不再只留最后 6 条；手动往上翻看时新消息不会把列表拽回底部。
 *
 * 控件：切前后 / 抓帧 / 麦克风（真 Mic01/MicOff01 图标 + 中文状态）/ 挂断，点身份行可打断
 * 当前生成与朗读；礼物特效仍然盖在最上层。
 *
 * 生命周期：
 * - 相机同时受 Surface / 权限 / 生命周期 STARTED / 通话进行中 四个条件约束，任一不成立就解绑；
 * - 退后台（ON_STOP）时，**绑定模式**的通话立即结束（它不占前台服务类型，不该在后台跑），
 *   正常的前台语音/视频通话继续跑；
 * - 挂断先调服务实例的 endCall，再 stopService，不依赖页面 unbind 的时机；
 * - 启动只认"用户进入面板"这一次明确意图，另一通电话结束不会让这里自动复活/自动启动。
 */
@Composable
fun VideoCallPage(
    conversationId: Uuid,
    onBack: () -> Unit,
) {
    val chatService: ChatService = org.koin.compose.koinInject()
    val context = LocalContext.current
    val settings = LocalSettings.current
    val toaster = LocalToaster.current
    val conversation by remember(conversationId) { chatService.getConversationFlow(conversationId) }
        .collectAsStateWithLifecycle()
    val assistant = settings.getAssistantById(conversation.assistantId)
    val callState by VoiceCallService.activeUiState.collectAsStateWithLifecycle()
    val startedAt by VoiceCallService.startedAtMillis.collectAsStateWithLifecycle()
    val activeConversationId by VoiceCallService.activeConversationId.collectAsStateWithLifecycle()

    val ourConversationId = conversationId.toString()
    val callActive = activeConversationId == ourConversationId
    val otherCallActive = activeConversationId != null && !callActive

    var boundService by remember { mutableStateOf<VoiceCallService?>(null) }
    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                boundService = (binder as? VoiceCallService.LocalBinder)?.getService()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                boundService = null
            }
        }
    }

    val cameraPermission = rememberPermissionState(PermissionCamera)
    val audioPermission = rememberPermissionState(PermissionRecordAudio)
    val cameraGranted = cameraPermission.allRequiredPermissionsGranted
    val micGranted = audioPermission.allRequiredPermissionsGranted

    // 权限按顺序请求：先相机，等它的结果回来再问麦克风，绝不两个请求同时 launch。
    var cameraRequested by remember { mutableStateOf(false) }
    var micRequested by remember { mutableStateOf(false) }
    val cameraStatus = cameraPermission.permissionStates.values.firstOrNull() ?: PermissionStatus.NotRequested
    val micStatus = audioPermission.permissionStates.values.firstOrNull() ?: PermissionStatus.NotRequested
    LaunchedEffect(cameraStatus, micRequested) {
        if (cameraStatus == PermissionStatus.NotRequested && !cameraRequested) {
            cameraRequested = true
            cameraPermission.requestPermissions()
            return@LaunchedEffect
        }
        // 相机的请求已经落地（或本来就有权限），这时才请求麦克风。
        if (!micRequested && micStatus == PermissionStatus.NotRequested) {
            micRequested = true
            audioPermission.requestPermissions()
        }
    }

    /**
     * "用户进入视频面板"这一次明确的启动意图，只消费一次。
     *
     * 关键：另一通电话结束时**不能**让它复活——[VideoCallStartAction.BlockedByOtherCall]
     * 会把意图直接作废，之后必须由用户重新进入页面才会再启动。
     */
    var callStartIntent by remember(conversationId) { mutableStateOf(true) }
    // 两个权限请求都落地之后才决定启动形态：绝不在权限弹窗期间就用绑定模式把会话跑起来
    // （那会立刻触发 ON_STOP / 5 秒前台窗口等一整类竞态）。
    val permissionsSettled = cameraStatus != PermissionStatus.NotRequested &&
        micStatus != PermissionStatus.NotRequested
    // 不依赖 binder 是否已经连上：服务自己公布"当前是不是绑定模式"。
    val boundOnlyCall by VoiceCallService.boundOnlyCall.collectAsStateWithLifecycle()
    val boundSession = boundOnlyCall && callActive
    val startAction = VideoCallPolicy.startAction(
        hasStartIntent = callStartIntent && permissionsSettled,
        otherCallActive = otherCallActive,
        callActive = callActive,
        boundSession = boundSession,
        cameraGranted = cameraGranted,
        micGranted = micGranted,
    )
    LaunchedEffect(startAction, callActive, boundService, cameraGranted, micGranted) {
        when (startAction) {
            // 有任一权限：前台服务。重复调用是幂等的（同会话只做能力对账/升级），
            // 这也是"绑定模式→前台"与"权限组合变化刷新类型"的唯一入口。
            VideoCallStartAction.StartForeground ->
                VoiceCallService.start(context, ourConversationId, video = true)
            // 两个权限都没有：由可见 Activity 绑定运行；服务还没连上就等下一次。
            VideoCallStartAction.StartBound -> boundService?.startBoundCall(ourConversationId, video = true)
            // 别的会话正在通话：这一次进入的意图直接作废，之后绝不自动启动。
            VideoCallStartAction.BlockedByOtherCall -> callStartIntent = false
            VideoCallStartAction.None -> Unit
        }
        // 通话真的起来了才算"意图已经兑现"，否则（权限在毫秒间被撤销等）保留意图，
        // 让下一次判断还能退回绑定模式，而不是把用户卡在一个没开始的空面板里。
        if (callActive) callStartIntent = false
    }

    /**
     * 权限组合变化时的**显式会话刷新**（REVIEW_R5 §5）。
     *
     * `startAction` 在"通话已经在前台跑着"时恒为 [VideoCallStartAction.None]，
     * 所以 camera-only 的视频通话首次拒绝麦克风、之后又授权，旧实现永远不会再让
     * service 对账一次：前台类型不会加 MICROPHONE，ASR/TTS 也不会补建。
     * 这里走服务的专用入口：只对**本会话**生效、且幂等（能力组合没变就不做事）。
     */
    LaunchedEffect(cameraGranted, micGranted, callActive, boundService) {
        if (!callActive) return@LaunchedEffect
        boundService?.refreshSessionPermissions(ourConversationId)
        // 麦克风授权/ASR 配置变化会让"录一段"从可用变不可用（或反过来），按钮状态跟着刷新。
        boundService?.refreshVideoVoiceInput()
    }

    /**
     * 挂断：先结束服务里的这通通话，再停服务。
     *
     * 只挂断属于这一页的通话；只 stopService 是不够的——页面还绑着服务时
     * onDestroy 不会发生，旧相机/旧请求会继续活着。
     */
    fun hangUp() {
        if (callActive) {
            val service = boundService
            if (service != null && service.boundConversationId() == ourConversationId) {
                service.endCall()
            }
            VoiceCallService.stop(context)
        }
        onBack()
    }

    DisposableEffect(conversationId) {
        val intent = Intent(context, VoiceCallService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        onDispose {
            try {
                context.unbindService(serviceConnection)
            } catch (error: Exception) {
                Log.e(TAG, "unbindService 失败", error)
            }
        }
    }

    // System back / edge-swipe must behave like the top-left back button: keep the call alive
    // in the floating call surface instead of hanging up. Only the explicit 挂断 action ends it.
    BackHandler { onBack() }

    // 服务自己持有稳定的 StateFlow：coordinator 重建时 UI 订阅对象不变，
    // 弹幕/礼物计数/提示始终是当前通话的状态（旧写法会一直订阅旧 flow）。
    val service = boundService
    val panelFlow = remember(service) { service?.videoState ?: VoiceCallService.idleVideoState }
    val panelState by panelFlow.collectAsStateWithLifecycle()

    val cameraController = remember(conversationId) { VideoCallCameraController(context) }
    LaunchedEffect(cameraController, callState.isPreviewMirrored) {
        cameraController.setPreviewMirrored(callState.isPreviewMirrored)
    }
    val cameraStatusState by cameraController.status.collectAsStateWithLifecycle()
    val previewTransform by cameraController.previewTransform.collectAsStateWithLifecycle()

    /**
     * 人工校准角度同步用于预览和本轮发送的抓帧 JPEG（见 [PreviewCalibration]）。
     *
     * 角度是**设置**（持久化），不是本页的临时状态：换镜头、退后台再回来、页面重建
     * 都不会把它清掉。真机上画面偏了就点「右转/左转」，角度会立刻生效并写回设置。
     *
     * **前后摄分开**：真机上 OPPO PKZ110 前摄要 90°、后摄要 270°，共享一个角度
     * 必然让其中一支侧躺。这里按当前镜头的 facing 取对应的值。
     */
    val previewCalibrationFrontDegrees = settings.videoCallSetting.previewCalibrationDegrees
        ?.let(PreviewCalibration::normalize)
        ?: PreviewCalibration.deviceDefaultFront(
            android.os.Build.MANUFACTURER,
            android.os.Build.MODEL,
            android.os.Build.VERSION.SDK_INT,
        )
    val previewCalibrationBackDegrees = settings.videoCallSetting.previewCalibrationBackDegrees
        ?.let(PreviewCalibration::normalize)
        ?: PreviewCalibration.deviceDefaultBack(
            android.os.Build.MANUFACTURER,
            android.os.Build.MODEL,
            android.os.Build.VERSION.SDK_INT,
        )
    val previewFacingIsFront = cameraStatusState.facing == CameraFacing.Front
    val previewCalibrationDegrees =
        if (previewFacingIsFront) previewCalibrationFrontDegrees else previewCalibrationBackDegrees
    val settingsStore: SettingsStore = org.koin.compose.koinInject()
    val uiScope = rememberCoroutineScope()
    LaunchedEffect(
        cameraController,
        previewCalibrationFrontDegrees,
        previewCalibrationBackDegrees,
    ) {
        cameraController.setPreviewCalibrationDegrees(previewCalibrationFrontDegrees, frontFacing = true)
        cameraController.setPreviewCalibrationDegrees(previewCalibrationBackDegrees, frontFacing = false)
    }
    fun applyPreviewCalibration(degrees: Int) {
        val normalized = PreviewCalibration.normalize(degrees)
        val front = previewFacingIsFront
        // 先让画面转起来（不等写盘），再落库；两处都以同一个归一化角度为准。
        cameraController.setPreviewCalibrationDegrees(normalized, frontFacing = front)
        uiScope.launch {
            settingsStore.update { current ->
                val video = current.videoCallSetting
                current.copy(
                    videoCallSetting = if (front) {
                        video.copy(previewCalibrationDegrees = normalized)
                    } else {
                        video.copy(previewCalibrationBackDegrees = normalized)
                    },
                )
            }
        }
    }

    // 相机入口按**通话作用域**注册：进另一个会话的视频页不会替换本通话的相机。
    // 通话在跑但协调器还没建好（启动中）时不注册，等 panelState.active 变成 true 再注册。
    LaunchedEffect(cameraController, callActive, panelState.active) {
        when {
            callActive && panelState.active ->
                VideoCallRuntime.registerCamera(ourConversationId, cameraController)
            !callActive ->
                VideoCallRuntime.unregisterCamera(ourConversationId, cameraController)
        }
    }
    DisposableEffect(cameraController) {
        onDispose {
            VideoCallRuntime.unregisterCamera(ourConversationId, cameraController)
            cameraController.unbind()
            // 离开视频面：自动缓存必须清掉，不能把旧画面随下一句发出去。
            // 只清**本会话**这一通的帧：别的会话的页面销毁不能动正在进行的那通电话。
            VideoCallRuntime.clearPendingFrame(ourConversationId)
        }
    }

    // 生命周期 STARTED 追踪：退后台立刻解绑，回前台再按门禁重绑。
    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleStarted by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner, boundService) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> lifecycleStarted = true
                Lifecycle.Event.ON_STOP -> {
                    lifecycleStarted = false
                    cameraController.unbind()
                    // 旧帧（自动 + 手动）一律作废：不在没有相机的时候留用旧图。
                    // 同样只针对本会话这一通。
                    VideoCallRuntime.clearPendingFrame(ourConversationId)
                    // 正在录的那一段跟着作废（退回后台就不再采音，也不偷偷发出去）。
                    boundService?.onHostBackground()
                    // 绑定模式的通话跟着可见性走：退后台就结束。
                    // 正常的前台语音/视频通话不受影响（isBoundOnly 为 false）。
                    boundService?.endBoundCallOnHostStop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 相机门禁：Surface + 权限 + STARTED + 通话进行中，缺一不可。
    // 门禁结果交给控制器执行：不成立就立刻释放相机（授权弹窗/退后台/挂断都走这条路径），
    // 成立时如果没有在预览就重新开（页面重建、从后台回来）。
    val surfaceReady by cameraController.surfaceReady.collectAsStateWithLifecycle()
    val shouldBindCamera = VideoCallPolicy.shouldBindCamera(
        surfaceReady = surfaceReady,
        permissionGranted = cameraGranted,
        lifecycleStarted = lifecycleStarted,
        callActive = callActive,
        cameraEnabled = callState.isCameraEnabled,
    )
    LaunchedEffect(shouldBindCamera) {
        cameraController.setPreviewAllowed(shouldBindCamera)
    }

    // 计时只在通话真正进行时走；挂断立刻停。
    var duration by remember { mutableStateOf("00:00") }
    LaunchedEffect(startedAt, callActive) {
        if (!callActive || startedAt <= 0L) {
            duration = "00:00"
            return@LaunchedEffect
        }
        while (true) {
            val seconds = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
            duration = "%02d:%02d".format(seconds / 60, seconds % 60)
            delay(1_000L)
        }
    }

    // 定时抓帧：只刷新"待发送帧"，不自己发消息；通话结束/退后台就停。
    val captureMode = settings.videoCallSetting.frameCaptureMode
    LaunchedEffect(captureMode, cameraStatusState.state, callActive, shouldBindCamera) {
        if (!callActive || !shouldBindCamera) return@LaunchedEffect
        if (!captureMode.timerDriven || cameraStatusState.state != VideoCameraState.Previewing) return@LaunchedEffect
        val scheduler = VideoFrameScheduler()
        while (true) {
            delay(1_000L)
            if (!callActive) break
            // 手动夹住的一帧优先：定时器不许把它换成另一张。
            if (VideoCallRuntime.pendingFrame()?.manual == true) continue
            if (scheduler.shouldCapture(captureMode)) {
                // 抓帧是挂起操作：先记下相机代次，回来之后确认"还是同一通电话、同一代相机"，
                // 否则挂断/离开面板之后才返回的这一帧会被重新 hold 进待发送队列。
                val epoch = VideoCallRuntime.currentCameraEpoch()
                val frame = VideoCallRuntime.captureFrame()
                if (frame != null &&
                    VideoCallRuntime.holdPendingFrame(frame, ourConversationId, epoch)
                ) {
                    scheduler.markCaptured()
                }
            }
        }
    }

    // 礼物全屏特效：只在页面可见且通话中排队；只收**本会话**的礼物；退后台清队列并停动画。
    // 队列/身份逻辑在 VideoGiftEffectQueue 里（纯 Kotlin，可单测）：每次出队都是带新 key 的
    // 新实例，连续两次同档位礼物不会因为 activeEffect 值相等而让 overlay 的动画不重启。
    val effectsActive = lifecycleStarted && callActive
    var activeEffect by remember { mutableStateOf<VideoGiftEffectRequest?>(null) }
    val effectQueue = remember { VideoGiftEffectQueue() }
    LaunchedEffect(effectsActive, ourConversationId) {
        if (!effectsActive) return@LaunchedEffect
        try {
            VideoCallRuntime.giftEvents
                .filter { it.conversationId == ourConversationId }
                .collect { event -> effectQueue.offer(event.id, event.gift) }
        } finally {
            effectQueue.clear()
            activeEffect = null
        }
    }
    LaunchedEffect(effectsActive, ourConversationId) {
        if (!effectsActive) {
            effectQueue.clear()
            activeEffect = null
            return@LaunchedEffect
        }
        try {
            while (true) {
                val request = effectQueue.poll()
                if (request == null) {
                    delay(150L)
                    continue
                }
                activeEffect = request
                // Skip wakes this player immediately; pending gifts retain their FIFO order.
                withTimeoutOrNull(VIDEO_GIFT_EFFECT_DURATION_MS) {
                    snapshotFlow { activeEffect?.key }.first { it != request.key }
                }
                // 只清掉自己这一条：被取消/换人时不要把后来者顶掉。
                if (activeEffect?.key == request.key) activeEffect = null
            }
        } finally {
            effectQueue.clear()
            activeEffect = null
        }
    }

    // 挂断（包括从通知挂断）：立即解绑相机、停计时/特效、清空待发送帧。
    var hasJoinedCall by remember { mutableStateOf(false) }
    LaunchedEffect(callActive) {
        if (callActive) {
            hasJoinedCall = true
        } else if (hasJoinedCall) {
            cameraController.unbind()
            VideoCallRuntime.clearPendingFrame(ourConversationId)
            effectQueue.clear()
            activeEffect = null
            hasJoinedCall = false
        }
    }

    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    val pendingFrameAt by VideoCallRuntime.pendingFrameAtMillis.collectAsStateWithLifecycle()

    LaunchedEffect(panelState.notice) {
        val message = panelState.notice ?: return@LaunchedEffect
        notice = message
        delay(3_000L)
        if (notice == message) notice = null
    }

    val boundForUs = boundService?.boundConversationId() == ourConversationId
    val canSend = callActive && boundForUs && !otherCallActive
    // 手动录音这一段的状态（安全 Idle 初值；纯语音通话恒为 Idle）。
    val voiceInput = callState.voiceInput

    val sendText: () -> Unit = {
        val text = input.trim()
        when {
            text.isEmpty() -> Unit
            otherCallActive -> toaster.show("当前有另一通通话进行中，先挂断它再发这一边")
            !canSend -> toaster.show("通话还没准备好，请稍等")
            serviceUsableSend(boundService, text) -> input = ""
            else -> toaster.show("消息没有发出去，请稍后再试")
        }
    }
    val clipFrame: () -> Unit = {
        if (!cameraStatusState.isPreviewing || !shouldBindCamera) {
            toaster.show("相机还没准备好，暂时夹不到画面")
        } else {
            scope.launch {
                // 抓帧之前记下作用域快照；await 之后只有"同一通电话 + 同一代相机"才允许 hold。
                val epoch = VideoCallRuntime.currentCameraEpoch()
                val frame = VideoCallRuntime.captureFrame()
                if (frame == null) {
                    toaster.show("这一帧没有抓到")
                } else {
                    // 手动帧优先，直到它随下一句发出去或被清空。
                    val stored = VideoCallRuntime.holdPendingFrame(
                        VideoFrame(
                            bytes = frame.bytes,
                            width = frame.width,
                            height = frame.height,
                            capturedAtMillis = frame.capturedAtMillis,
                            manual = true,
                            rotationDegrees = frame.rotationDegrees,
                            mirror = frame.mirror,
                        ),
                        ourConversationId,
                        epoch,
                    )
                    toaster.show(if (stored) "已夹住这一帧，会随下一句发送" else "通话/相机已变化，这一帧没有夹住")
                }
            }
        }
    }

    // 明亮玻璃主题：与既有语音页（GlassCallPanel / interactionColors）同一套色板，
    // 不再是覆盖整屏的黑色遮罩。镜头画面缩进一张圆角卡里，文字改成常驻可回看的列表。
    val theme = assistant?.chatInteractionTheme ?: "ice"
    val colors = interactionColors(theme)
    val previewing = cameraStatusState.state == VideoCameraState.Previewing ||
        cameraStatusState.state == VideoCameraState.Requesting

    Box(
        Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(colors.top, colors.bottom)))
            .statusBarsPadding(),
    ) {
        Column(
            // imePadding 必须留着：RouteActivity 是 edge-to-edge（enableEdgeToEdge），
            // 键盘不会替我们缩小 window，只有消费 IME inset 输入框才不会被键盘盖住。
            // 上半区（相机/字幕/提示）可滚动，控制行与输入框固定在下，永远可见。
            Modifier.fillMaxSize().navigationBarsPadding().imePadding().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Bound the scrolling content; keep call actions and the composer outside it.
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            // 顶部：折叠 + 「视频通话」+ 礼物计数
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassTextChip(label = "↙", ink = colors.ink, onClick = onBack)
                Spacer(Modifier.weight(1f))
                Text(
                    "视频通话",
                    Modifier.background(Color.White.copy(alpha = .65f), CircleShape)
                        .border(1.dp, Color.White, CircleShape)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink,
                )
                Spacer(Modifier.weight(1f))
                if (panelState.tally.total > 0) {
                    GlassTextChip(label = "🎁 ${panelState.tally.total}", ink = colors.ink, onClick = null)
                } else {
                    Spacer(Modifier.size(36.dp))
                }
            }

            // 身份行：头像 + 名字 + 状态·时长；点一下打断当前生成/朗读（保留原有打断入口）。
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White.copy(alpha = .45f), RoundedCornerShape(22.dp))
                    .border(1.dp, Color.White.copy(alpha = .8f), RoundedCornerShape(22.dp))
                    .clickable(enabled = canSend) { boundService?.requestInterrupt() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                UIAvatar(
                    name = assistant?.name.orEmpty().ifBlank { "AI 助手" },
                    modifier = Modifier.size(34.dp),
                    value = assistant?.avatar ?: Avatar.Dummy,
                    interactive = false,
                    loading = false,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        assistant?.name?.ifBlank { "AI 助手" } ?: "AI 助手",
                        color = colors.ink,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        "${callStatusLabel(callState.status)} · $duration",
                        color = colors.ink.copy(alpha = .7f),
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
                // 音频路由：外放 / 听筒。与"麦克风开闭"是两个不同的开关，绝不重复同一个开关。
                GlassTextChip(
                    label = if (callState.isSpeakerOn) "外放" else "听筒",
                    ink = colors.ink,
                    onClick = if (canSend) ({ boundService?.toggleSpeaker() }) else null,
                )
                Text(
                    "打断",
                    Modifier.clip(CircleShape)
                        .background(Color.White.copy(alpha = .55f), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = .9f), CircleShape)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    color = colors.ink.copy(alpha = if (canSend) .9f else .35f),
                    fontSize = 11.sp,
                )
            }

            // 镜头：始终可见，但只占一张固定 4:3 的圆角卡。
            //
            // 预览矩阵使用 fit（整张源图等比贴合、留边），所以任何传感器比例都不会被
            // 拉伸或裁剪。外层仍按宽度量出 4:3 高度，键盘不会把相机画面压扁。
            Box(
                // 竖屏 4:3（宽:高 = 3:4）。用户明确要求竖屏画面，不能再按横屏 4:3 拉伸。
                Modifier.fillMaxWidth().aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color(0xFF1E2A33)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.fillMaxSize()
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color(0xFF1E2A33), RoundedCornerShape(26.dp))
                        .border(1.5.dp, Color.White.copy(alpha = .85f), RoundedCornerShape(26.dp)),
                ) {
                    // Remove the TextureView while disabled: no stale camera buffer can masquerade as live.
                    if (callState.isCameraEnabled) {
                        CameraPreviewSurface(
                            controller = cameraController,
                            transform = previewTransform,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                    if (!callState.isCameraEnabled) {
                        Text("摄像头已关闭 · 通话继续", Modifier.align(Alignment.Center), color = Color.White)
                    } else if (!previewing) {
                        CameraOffCard(
                            status = cameraStatusState,
                            permissionGranted = cameraGranted,
                            onRequestPermission = { cameraPermission.requestPermissions() },
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                    Row(
                        Modifier.align(Alignment.TopStart).padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        GlassTextChip(
                            label = if (callState.isCameraEnabled) "关闭摄像头" else "开启摄像头",
                            ink = colors.ink,
                            onClick = if (canSend) ({ boundService?.toggleVideoCamera() }) else null,
                        )
                        GlassTextChip(
                            label = if (callState.isPreviewMirrored) "预览镜像：开" else "预览镜像：关",
                            ink = colors.ink,
                            onClick = if (canSend) ({ boundService?.togglePreviewMirror() }) else null,
                        )
                    }
                    Text(
                        "所见方向和镜像会同步发送给模型",
                        Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 46.dp),
                        color = Color.White, fontSize = 10.sp,
                    )
                    // 方向校准同步用于预览和本轮发给模型的照片。
                    // 设备建议仅窄匹配用户报告的机型；实际效果仍需真机确认，可手动校准。
                    // 这里把角度直接写在按钮中间，点一次就能看出转没转对，也能往回转/复位。
                    PreviewCalibrationControls(
                        degrees = previewCalibrationDegrees,
                        onRotateLeft = {
                            applyPreviewCalibration(PreviewCalibration.rotateLeft(previewCalibrationDegrees))
                        },
                        onRotateRight = {
                            applyPreviewCalibration(PreviewCalibration.rotateRight(previewCalibrationDegrees))
                        },
                        onReset = { applyPreviewCalibration(PreviewCalibration.reset()) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                    )
                }
            }

            // Bound the nested LazyColumn explicitly inside the scrolling content.
            // 常驻文字：稳定 id + 有界历史，可上下回看，不会 6 秒就消失。
            CallTranscriptList(
                lines = panelState.history,
                colors = colors,
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )

            if (otherCallActive) {
                Text(
                    "另一个会话正在通话中。这里不会发送消息，也不会控制那通电话；请先挂断它。",
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFFFD9E1).copy(alpha = .8f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    color = Color(0xFF8A2340),
                    fontSize = 11.sp,
                )
            }
            notice?.let {
                Text(
                    it,
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = .6f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    color = colors.ink.copy(alpha = .85f),
                    fontSize = 11.sp,
                )
            }
            callState.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    error,
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFFFD9E1).copy(alpha = .7f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    color = Color(0xFF8A2340),
                    fontSize = 11.sp,
                )
            }
            if (!micGranted) {
                Text(
                    "麦克风未开启，当前是纯文字模式",
                    Modifier.clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = .6f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    color = colors.ink.copy(alpha = .8f),
                    fontSize = 11.sp,
                )
            }
            if (pendingFrameAt != null) {
                Text(
                    "📸 已夹住一帧，会随下一句发送",
                    Modifier.clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = .6f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    color = colors.ink.copy(alpha = .85f),
                    fontSize = 11.sp,
                )
            }
            // 手动录音这一段的状态：录音中显示时长上限，不可用时**必须**说清原因（不假装在听）。
            if (voiceInput.isRecording || voiceInput.isBusy || voiceInput.endedByLimit) {
                VoiceRecordingBanner(voiceInput, colors)
            }
            if (voiceInput.status == VideoVoiceStatus.Unavailable && voiceInput.reason.isNotBlank()) {
                Text(
                    voiceInput.reason,
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFFFD9E1).copy(alpha = .6f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    color = Color(0xFF8A2340),
                    fontSize = 11.sp,
                )
            }

            }

            // Fixed below the bounded scrolling area, above the IME.
            // 控制行与输入框始终独立于可滚动的相机、字幕和提示区域。
            Row(
                Modifier.fillMaxWidth()
                    .background(Color.White.copy(alpha = .45f), RoundedCornerShape(26.dp))
                    .border(1.dp, Color.White.copy(alpha = .8f), RoundedCornerShape(26.dp))
                    .padding(vertical = 8.dp, horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassCallAction(
                    icon = null,
                    emoji = "⇄",
                    label = "切前后",
                    tint = colors.accent,
                    enabled = shouldBindCamera,
                    onClick = { cameraController.switchCamera() },
                )
                GlassCallAction(
                    icon = HugeIcons.Camera01,
                    emoji = null,
                    label = "抓帧",
                    tint = colors.accent,
                    enabled = cameraStatusState.isPreviewing && shouldBindCamera,
                    onClick = { clipFrame() },
                )
                // 录音：同一颗按钮既是"开始录音"也是"结束并发送"。转写中/发送中禁用（防重复提交）。
                GlassCallAction(
                    icon = if (voiceInput.isRecording) HugeIcons.Stop else HugeIcons.Record,
                    emoji = null,
                    label = VideoVoicePolicy.buttonLabel(voiceInput),
                    tint = if (voiceInput.isRecording) Color(0xFFCF5C7A) else colors.accent,
                    enabled = canSend && voiceInput.route != null &&
                        (voiceInput.status == VideoVoiceStatus.Idle || voiceInput.isRecording),
                    onClick = { boundService?.toggleVideoVoiceInput() },
                )
                // 麦克风：真正的 Mic01/MicOff01 图标 + 中文状态。闭麦会取消未发送的那一段（不自动提交）。
                GlassCallAction(
                    icon = if (callState.isMuted) HugeIcons.MicOff01 else HugeIcons.Mic01,
                    emoji = null,
                    label = if (callState.isMuted) "已闭麦" else "麦克风",
                    tint = if (callState.isMuted) Color(0xFF9C3352) else colors.accent,
                    enabled = canSend,
                    onClick = { boundService?.toggleMute() },
                )
                GlassCallAction(
                    icon = HugeIcons.Cancel01,
                    emoji = null,
                    label = "挂断",
                    tint = Color(0xFFCF5C7A),
                    enabled = true,
                    onClick = { hangUp() },
                )
            }

            // 输入框常驻：键盘弹起时整列一起上移，不会被挡住。
            // 注意：麦克风开关只在上面那颗按钮上出现**一次**，这里不再重复第二个同样的开关。
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White.copy(alpha = .72f), RoundedCornerShape(22.dp))
                    .border(1.dp, Color.White.copy(alpha = .95f), RoundedCornerShape(22.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it.take(200) },
                    modifier = Modifier.weight(1f).heightIn(max = 64.dp),
                    maxLines = 3,
                    enabled = !otherCallActive,
                    textStyle = TextStyle(color = colors.ink, fontSize = 14.sp),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendText() }),
                    decorationBox = { inner ->
                        if (input.isEmpty()) {
                            Text("说点什么…", color = colors.ink.copy(alpha = .45f), fontSize = 14.sp)
                        }
                        inner()
                    },
                )
                Box(
                    Modifier.clip(CircleShape)
                        .background(
                            if (input.isBlank() || !canSend) colors.ink.copy(alpha = .12f)
                            else colors.accent,
                            CircleShape,
                        )
                        .clickable(enabled = input.isNotBlank() && canSend) { sendText() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "发送",
                        color = if (input.isBlank() || !canSend) colors.ink.copy(alpha = .4f) else Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.size(2.dp))
        }

        VideoGiftEffectOverlay(
            effect = activeEffect,
            modifier = Modifier.fillMaxSize(),
            onSkip = { renderedKey -> activeEffect = activeEffect.skipIfMatching(renderedKey) },
        )
    }
}

/** 打字发送的返回值语义：true 表示这一轮已经进入队列。 */
private fun serviceUsableSend(service: VoiceCallService?, text: String): Boolean =
    service?.submitTypedText(text) == true

/**
 * 常驻字幕列表。
 *
 * 真机反馈的两条硬要求都在这里：
 * 1. 文字**常驻可回看**：不再 6 秒淡出、不再只留最后 6 条；
 * 2. 长通话不炸内存：行来自服务侧 `history`（有界 400 条、稳定 id），列表用 LazyColumn，
 *    只有可见行才组合。
 *
 * 自动贴底只在**用户本来就在底部**时发生：手动往上翻看旧内容时，新一句话不会把他拽回底部。
 */
@Composable
private fun CallTranscriptList(
    lines: List<DanmakuLine>,
    colors: InteractionColors,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val count = lines.size
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf true
            last >= info.totalItemsCount - 1
        }
    }
    LaunchedEffect(count) {
        if (count > 0 && atBottom) listState.animateScrollToItem(count - 1)
    }
    if (lines.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "开始说话，文字会留在这里，可以随时往上翻。",
                color = colors.ink.copy(alpha = .45f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(lines, key = { it.id }) { line ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start,
            ) {
                Text(
                    text = line.text,
                    modifier = Modifier.widthIn(max = 290.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (line.fromUser) colors.accent.copy(alpha = .22f)
                            else Color.White.copy(alpha = .68f),
                            RoundedCornerShape(16.dp),
                        )
                        .border(1.dp, Color.White.copy(alpha = .9f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = colors.ink,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

/** 圆角小玻璃片（文字）。 */
@Composable
private fun GlassTextChip(label: String, ink: Color, onClick: (() -> Unit)?) {
    Box(
        Modifier.clip(CircleShape)
            .background(Color.White.copy(alpha = .55f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = .9f), CircleShape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 底部的图标 + 中文小字动作（与语音页 GlassCallButton 同一套观感）。
 *
 * [emoji] 与 [icon] 二选一：切镜头/抓帧暂时用符号，麦克风/挂断用真正的图标
 * （Mic01/MicOff01/Cancel01），避免再用 🔇/🎙 这种"看不出是静音还是没开"的写法。
 */
@Composable
private fun GlassCallAction(
    icon: ImageVector?,
    emoji: String?,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier.clip(RoundedCornerShape(16.dp))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier.size(42.dp)
                .background(tint.copy(alpha = if (enabled) .28f else .12f), CircleShape)
                .border(1.dp, Color.White.copy(alpha = .8f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (enabled) tint else tint.copy(alpha = .4f),
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Text(emoji ?: "", fontSize = 17.sp, color = tint.copy(alpha = if (enabled) 1f else .4f))
            }
        }
        Text(
            label,
            color = tint.copy(alpha = if (enabled) .95f else .4f),
            fontSize = 10.sp,
        )
    }
}

/**
 * 手动录音的状态条：录音中显示"已录多久 / 上限多少 + 再点一次才发送"，
 * 转写中/发送中/已到上限都明确写出来，绝不用"正在聆听"这种含糊或骗人的说法。
 */
@Composable
private fun VoiceRecordingBanner(voice: VideoVoiceUiState, colors: InteractionColors) {
    val text = when {
        voice.isRecording ->
            "🔴 正在录：${VideoVoicePolicy.formatDuration(voice.elapsedMs)} / " +
                "${voice.maxDurationMs / 1000}秒 · 再点一次结束并发送（不会自动发）"

        voice.status == VideoVoiceStatus.Transcribing -> "⏳ 正在转写，等最后一段文字…"
        voice.status == VideoVoiceStatus.Sending -> "📤 发送中…"
        voice.endedByLimit -> "⏱️ 已到 ${voice.maxDurationMs / 1000} 秒上限，这一段已自动结束并发送"
        else -> VideoVoicePolicy.hintLabel(voice)
    }
    Text(
        text,
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = .62f), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        color = colors.ink.copy(alpha = .85f),
        fontSize = 11.sp,
    )
}
/**
 * 预览卡右下角的方向校准小控件：`↺ 左转 | 90° | ↻ 右转 | 复位`。
 *
 * 为什么要有它：camera2 buffer 朝向 + `TextureView` 默认拉伸 + 生产者隐式变换的真实符号
 * 约定只能靠真机确认。实测 OPPO/ColorOS 竖屏后摄画面左转 90°，点一次「右转」即可转正；
 * 转多了就点「左转」回退，或「复位」回到不做人工校准。
 *
 * 它**只改预览矩阵**：抓帧/落盘 JPEG 的方向由 sensor/display 几何单独决定，不受影响。
 * 角度来源是设置（持久化），所以换镜头、退后台再进来仍然是这个角度。
 */
@Composable
private fun PreviewCalibrationControls(
    degrees: Int,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = .86f), RoundedCornerShape(18.dp))
            .border(1.dp, Color.White, RoundedCornerShape(18.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PreviewCalibrationButton("↺", "预览左转 90°", onRotateLeft)
        Text(
            PreviewCalibration.label(degrees),
            color = Color(0xFF2F4858),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        PreviewCalibrationButton("↻", "预览右转 90°", onRotateRight)
        PreviewCalibrationButton("复位", "预览方向复位", onReset)
    }
}

/** 校准控件里的单个小按钮（玻璃风格，与预览卡同一套观感）。 */
@Composable
private fun PreviewCalibrationButton(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Text(
        label,
        Modifier.clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = contentDescription) { onClick() }
            .padding(horizontal = 7.dp, vertical = 4.dp),
        color = Color(0xFF2F4858),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
    )
}
/**
 * 预览：TextureView + 显式变换矩阵。
 *
 * camera2 的 buffer 永远是 sensor 方向，SurfaceView 不会替应用旋转；TextureView 的
 * `setTransform` 可以按 sensor/display 角度真正转正，前摄再镜像。
 *
 * 注意：TextureView **默认就会把 buffer 拉伸到视图大小**，所以矩阵不能是"在默认拉伸之上
 * 再 postRotate + 按像素比放一个等比 scale"——那样消不掉拉伸，还会留下黑边。
 * 真正要交给它的是 [PreviewTransform.matrix]：它先抵消默认拉伸，再做旋转 +
 * 等比贴合 + 镜像（几何在 [me.rerere.rikkahub.data.video.VideoPreviewGeometry]
 * 里算成纯数据，配套 JVM 单测逐点验证）。调用方把视图宽高比设成
 * [PreviewTransform.contentSize] 的比例，所以"贴合"就是整张源图可见；
 * 旋转角度、镜像与抓帧 JPEG 来自同一套纯函数。
 */
@Composable
private fun CameraPreviewSurface(
    controller: VideoCallCameraController,
    transform: PreviewTransform,
    modifier: Modifier = Modifier,
) {
    // 回调里要读到**当前**的矩阵：AndroidView 的 update 只在状态变化时跑一次，
    // 而 SurfaceTexture 回调可能在视图尺寸就绪/变化时后到。两边都套一遍，
    // 才不会出现"矩阵算对了但没落到 TextureView 上"（真机表现为侧躺 90°）。
    val latestTransform = rememberUpdatedState(transform)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        // 是否立刻开相机由门禁决定；控制器的 Surface 所有权在 detach 时释放。
                        controller.attachSurface(texture, width, height)
                        applyPreviewTransform(this@apply, latestTransform.value, width, height)
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                        controller.updateViewSize(width, height)
                        // 尺寸变化后控制器会**同步**发布按新尺寸算好的矩阵；这里直接用控制器
                        // 的当前值，避免用组合里还没刷新的旧矩阵在过渡帧上再算错一次。
                        applyPreviewTransform(this@apply, controller.previewTransform.value, width, height)
                    }

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        // 相机必须先停下来，才允许系统回收 SurfaceTexture。
                        controller.detachSurface()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
        update = { view -> applyPreviewTransform(view, transform) },
    )
}

/**
 * 把纯几何算出来的矩阵应用到 TextureView 上。
 *
 * [PreviewTransform.matrix] 已经包含"抵消 TextureView 默认拉伸"的部分（见
 * [VideoPreviewGeometry.textureViewAffine]），这里只做一次坐标转换，不再自己拼旋转/缩放，
 * 否则就等于把几何算错两次。
 */
private fun applyPreviewTransform(
    view: TextureView,
    transform: PreviewTransform,
    width: Int = view.width,
    height: Int = view.height,
) {
    if (width <= 0 || height <= 0) return
    runCatching { view.setTransform(transform.matrix.toAndroidMatrix()) }
}

/**
 * 镜头没起来时的提示：**只在这张预览卡内部**说明原因。
 *
 * 旧实现用整屏黑底 + 覆盖全屏的黑色渐变把明亮玻璃控件压成黑底白字；现在相机不可用
 * 只是卡片里的一段说明，页面其余部分仍然是明亮主题。
 */
@Composable
private fun CameraOffCard(
    status: VideoCameraStatus,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.background(Brush.verticalGradient(listOf(Color(0xFF23323D), Color(0xFF2E4152)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("镜头未开启", color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                cameraStatusReason(status),
                color = Color.White.copy(alpha = 0.68f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                "文字、礼物和留影照常可用",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
            if (!permissionGranted) {
                Box(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.18f))
                        .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(18.dp))
                        .clickable { onRequestPermission() }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Text("允许使用相机", color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }
}

private fun cameraStatusReason(status: VideoCameraStatus): String = when (status.state) {
    VideoCameraState.PermissionDenied -> "没有相机权限，画面不会上传；打字和礼物照常可用。"
    VideoCameraState.Unavailable -> status.message.ifBlank { "没有可用的相机。" }
    VideoCameraState.Failed -> status.message.ifBlank { "相机打开失败。" }
    VideoCameraState.Requesting -> "相机正在启动…"
    VideoCameraState.Idle -> status.message.ifBlank { "相机已关闭。" }
    VideoCameraState.Previewing -> ""
}


/**
 * 状态文案。
 *
 * 视频通话是**手动**采音，所以 `Listening` 在这里不能显示成"正在聆听"——
 * 那会让人以为麦克风一直在录。它只表示"在线、可以点录音也可以打字"。
 */
private fun callStatusLabel(status: me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus): String = when (status) {
    me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus.Idle -> "准备就绪"
    me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus.Listening -> "在线"
    me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus.Processing -> "正在思考"
    me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus.Speaking -> "正在说话"
    me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus.Error -> "出错了"
}
