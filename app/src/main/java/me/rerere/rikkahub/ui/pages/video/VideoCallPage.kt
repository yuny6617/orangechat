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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
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
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.video.VIDEO_GIFT_EFFECT_DURATION_MS
import me.rerere.rikkahub.data.video.DanmakuLine
import me.rerere.rikkahub.data.video.PreviewTransform
import me.rerere.rikkahub.data.video.VideoCallPolicy
import me.rerere.rikkahub.data.video.VideoCallRuntime
import me.rerere.rikkahub.data.video.VideoCallStartAction
import me.rerere.rikkahub.data.video.VideoCameraState
import me.rerere.rikkahub.data.video.VideoCameraStatus
import me.rerere.rikkahub.data.video.VideoFrame
import me.rerere.rikkahub.data.video.VideoFrameScheduler
import me.rerere.rikkahub.data.video.VideoGiftEffectQueue
import me.rerere.rikkahub.data.video.VideoGiftEffectRequest
import me.rerere.rikkahub.data.video.toAndroidMatrix
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.VoiceCallService
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.PermissionStatus
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import kotlin.uuid.Uuid

private const val TAG = "VideoCallPage"

/** 弹幕气泡存活时长与同屏上限：动画有生命周期，数量有上限，不会一直堆在内存里。 */
private const val DANMAKU_LIFETIME_MS = 6_000L
private const val DANMAKU_MAX_VISIBLE = 6

/**
 * 视频通话面板。
 *
 * - 相机画面铺底，控件是黑玻璃；没有相机/没有权限时同一面板退化成黑底，文字/弹幕/礼物照常；
 * - 左上角头像+名字+计时徽章，点一下打断当前生成/TTS；
 * - 右上角挂断、夹帧、前后切换、礼物数量统计；
 * - 打完的字走语音通话同一条对话链（同一个队列、同一个 TTS、同一套挂断清理）。
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

    BackHandler { hangUp() }

    // 服务自己持有稳定的 StateFlow：coordinator 重建时 UI 订阅对象不变，
    // 弹幕/礼物计数/提示始终是当前通话的状态（旧写法会一直订阅旧 flow）。
    val service = boundService
    val panelFlow = remember(service) { service?.videoState ?: VoiceCallService.idleVideoState }
    val panelState by panelFlow.collectAsStateWithLifecycle()

    val cameraController = remember(conversationId) { VideoCallCameraController(context) }
    val cameraStatusState by cameraController.status.collectAsStateWithLifecycle()
    val previewTransform by cameraController.previewTransform.collectAsStateWithLifecycle()

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
                delay(VIDEO_GIFT_EFFECT_DURATION_MS)
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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val previewing = cameraStatusState.state == VideoCameraState.Previewing ||
            cameraStatusState.state == VideoCameraState.Requesting
        // TextureView 必须一直存在，并且由它自己把 SurfaceTexture 交给相机；
        // 未就绪时在上面盖一层黑底说明，同一面板退化成"黑底纯文字模式"。
        CameraPreviewSurface(
            controller = cameraController,
            transform = previewTransform,
            modifier = Modifier.fillMaxSize(),
        )
        if (!previewing) {
            BlackStage(
                status = cameraStatusState,
                permissionGranted = cameraGranted,
                onRequestPermission = { cameraPermission.requestPermissions() },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 顶部/底部压暗，保证黑玻璃控件在任何画面上都可读
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.55f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.72f),
                    ),
                ),
            ),
        )

        VideoGiftEffectOverlay(activeEffect, Modifier.fillMaxSize())

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(22.dp))
                    .background(Color.Black.copy(alpha = 0.42f), RoundedCornerShape(22.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(22.dp))
                    .clickable(enabled = canSend) { boundService?.requestInterrupt() }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 用项目已有的助手头像组件（不是主题吉祥物）。
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    UIAvatar(
                        name = assistant?.name.orEmpty().ifBlank { "AI 助手" },
                        modifier = Modifier.size(26.dp),
                        value = assistant?.avatar ?: Avatar.Dummy,
                        interactive = false,
                        loading = false,
                    )
                }
                Column {
                    Text(
                        text = assistant?.name?.ifBlank { "AI 助手" } ?: "AI 助手",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        text = "${callStatusLabel(callState.status)} · $duration",
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (panelState.tally.total > 0) {
                    GlassChip("🎁 ${panelState.tally.total}")
                }
                GlassChip(label = "⇄", onClick = { if (shouldBindCamera) cameraController.switchCamera() })
                GlassChip(label = "📸", onClick = { clipFrame() })
                GlassChip(label = "✕", tint = Color(0xFFFFA6BE), onClick = { hangUp() })
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(bottom = 86.dp).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (otherCallActive) {
                Text(
                    "另一个会话正在通话中。这里不会发送消息，也不会控制那通电话；请先挂断它。",
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF3A1520).copy(alpha = 0.78f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    color = Color(0xFFFFC2CE),
                    fontSize = 11.sp,
                )
            }
            notice?.let {
                Text(
                    it,
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                )
            }
            callState.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    error,
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF3A1520).copy(alpha = 0.72f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    color = Color(0xFFFFC2CE),
                    fontSize = 11.sp,
                )
            }
            if (!micGranted) {
                Text(
                    "麦克风未开启，当前是纯文字模式",
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                )
            }
            if (pendingFrameAt != null) {
                Text(
                    "📸 已夹住一帧，会随下一句发送",
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                )
            }
            FloatingDanmaku(
                lines = panelState.bubbles,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding().navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(22.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicTextField(
                value = input,
                onValueChange = { input = it.take(200) },
                modifier = Modifier.weight(1f),
                enabled = !otherCallActive,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendText() }),
                decorationBox = { inner ->
                    if (input.isEmpty()) {
                        Text("说点什么…", color = Color.White.copy(alpha = 0.45f), fontSize = 14.sp)
                    }
                    inner()
                },
            )
            AudioToggleChip(
                muted = callState.isMuted,
                enabled = canSend,
                onToggle = { boundService?.toggleMute() },
            )
            Box(
                Modifier.clip(CircleShape)
                    .background(Color.White.copy(alpha = if (input.isBlank() || !canSend) 0.12f else 0.9f))
                    .clickable(enabled = input.isNotBlank() && canSend) { sendText() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "发送",
                    color = if (input.isBlank() || !canSend) Color.White.copy(alpha = 0.4f) else Color(0xFF1B1B22),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (otherCallActive) {
            Text(
                "另一个会话正在通话",
                Modifier.align(Alignment.Center).padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 打字发送的返回值语义：true 表示这一轮已经进入队列。 */
private fun serviceUsableSend(service: VoiceCallService?, text: String): Boolean =
    service?.submitTypedText(text) == true

/**
 * 真正"上浮 + 淡出"的弹幕：每条按到达时间做生命周期动画，同屏最多 [DANMAKU_MAX_VISIBLE] 条，
 * 过期就从布局里消失，不会越堆越多。
 */
@Composable
private fun FloatingDanmaku(
    lines: List<DanmakuLine>,
    modifier: Modifier = Modifier,
) {
    val visible = lines.takeLast(DANMAKU_MAX_VISIBLE)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(visible.isNotEmpty()) {
        while (visible.isNotEmpty()) {
            now = System.currentTimeMillis()
            delay(80L)
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        visible.forEach { line ->
            val progress = ((now - line.atMillis).toFloat() / DANMAKU_LIFETIME_MS).coerceIn(0f, 1f)
            if (progress >= 1f) return@forEach
            val alpha = when {
                progress < 0.1f -> progress / 0.1f
                progress > 0.72f -> (1f - progress) / 0.28f
                else -> 1f
            }.coerceIn(0f, 1f)
            Row(
                Modifier.fillMaxWidth()
                    .graphicsLayer {
                        this.alpha = alpha
                        translationY = -(progress * 26.dp.toPx())
                    },
                horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start,
            ) {
                Text(
                    text = line.text,
                    modifier = Modifier.widthIn(max = 280.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (line.fromUser) Color(0xFF2B2B33).copy(alpha = 0.78f)
                            else Color.Black.copy(alpha = 0.66f),
                            RoundedCornerShape(16.dp),
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = Color.White,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

/**
 * 预览：TextureView + 显式变换矩阵。
 *
 * camera2 的 buffer 永远是 sensor 方向，SurfaceView 不会替应用旋转；TextureView 的
 * `setTransform` 可以按 sensor/display 角度真正转正，并等比"填满"（多出来的部分裁掉），
 * 前摄再镜像。
 *
 * 注意：TextureView **默认就会把 buffer 拉伸到视图大小**，所以矩阵不能是"在默认拉伸之上
 * 再 postRotate + 按像素比放一个等比 scale"——那样消不掉拉伸，还会留下黑边。
 * 真正要交给它的是 [PreviewTransform.matrix]：它先抵消默认拉伸，再做旋转 +
 * 等比 centerCrop + 镜像（几何在 [me.rerere.rikkahub.data.video.VideoPreviewGeometry]
 * 里算成纯数据，配套 JVM 单测逐点验证）。旋转角度与抓帧 JPEG 的方向来自同一个纯函数。
 */
@Composable
private fun CameraPreviewSurface(
    controller: VideoCallCameraController,
    transform: PreviewTransform,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        // 是否立刻开相机由门禁决定；控制器的 Surface 所有权在 detach 时释放。
                        controller.attachSurface(texture, width, height)
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                        controller.updateViewSize(width, height)
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
private fun applyPreviewTransform(view: TextureView, transform: PreviewTransform) {
    if (view.width <= 0 || view.height <= 0) return
    view.setTransform(transform.matrix.toAndroidMatrix())
}

@Composable
private fun BlackStage(
    status: VideoCameraStatus,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("镜头未开启", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                cameraStatusReason(status),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                "文字、弹幕和礼物仍然可用",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
            if (!permissionGranted) {
                Spacer(Modifier.size(4.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.14f))
                        .border(1.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(18.dp))
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

@Composable
private fun AudioToggleChip(muted: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.16f else 0.08f), CircleShape)
            .clickable(enabled = enabled) { onToggle() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (muted) "🔇" else "🎙",
            fontSize = 14.sp,
            color = Color.White.copy(alpha = if (enabled) 0.9f else 0.4f),
        )
    }
}

@Composable
private fun GlassChip(
    label: String,
    tint: Color = Color.White,
    onClick: (() -> Unit)? = null,
) {
    Box(
        Modifier.clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.42f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun callStatusLabel(status: me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus): String = when (status) {
    me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus.Idle -> "准备就绪"
    me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus.Listening -> "正在聆听"
    me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus.Processing -> "正在思考"
    me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus.Speaking -> "正在说话"
    me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus.Error -> "出错了"
}
