/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import me.rerere.rikkahub.service.voiceCallShouldListen
import me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus

/**
 * 视频/语音通话里那些"纯策略"的判断。
 *
 * 全部是纯函数/纯状态机，不碰 Android 框架，方便单测把边界逐条钉住：
 * 权限组合、前台服务类型、无麦克风时是否还听、每轮 token 的失效。
 */
enum class CallForegroundCapability {
    Camera,
    Microphone,
}

/**
 * 服务该怎么活：
 * - [Foreground]：至少有一个可用能力，按**实际启用的组合**声明前台服务类型；
 * - [BoundOnly]：相机和麦克风都没有授权，退化成"可见 Activity 绑定运行"的纯文字模式，
 *   不占任何前台服务类型（离开页面即结束），也不假装自己是麦克风/相机服务。
 */
data class CallServicePlan(
    val foreground: Boolean,
    val capabilities: Set<CallForegroundCapability>,
)

object VideoCallPolicy {
    /**
     * 是否允许启动通话（前台）服务。
     *
     * 相机或麦克风有一个就能启动；两个都没有时**不是**"不能通话"，
     * 而是走绑定模式继续打字/弹幕/礼物。
     */
    fun canStartForegroundCall(cameraGranted: Boolean, micGranted: Boolean): Boolean =
        cameraGranted || micGranted

    /** 纯文字模式：两个权限都没有，由可见 Activity 绑定服务运行。 */
    fun isTextOnlyMode(cameraGranted: Boolean, micGranted: Boolean): Boolean =
        !cameraGranted && !micGranted

    /**
     * 前台服务类型按实际启用能力组合：
     * - 视频 + 相机权限 → CAMERA；
     * - 有麦克风权限 → 再 OR 上 MICROPHONE（语音通话只用麦克风）；
     * - 没有相机权限的视频通话**不**声明 CAMERA（否则 startForeground 会抛异常）。
     *
     * **前台服务必须至少声明一个真实能力**：纯语音（`videoMode = false`）只有相机权限时，
     * 既不能声明 CAMERA（这一轮根本不用相机），也没有麦克风可以声明，
     * `startForeground` 只会在 5 秒后抛 `ForegroundServiceDidNotStartInTime`。
     * 这种组合不是"前台服务但类型为空集"，而是**不能前台**，退化成绑定模式。
     */
    fun servicePlan(cameraGranted: Boolean, micGranted: Boolean, videoMode: Boolean): CallServicePlan {
        val capabilities = buildSet {
            if (videoMode && cameraGranted) add(CallForegroundCapability.Camera)
            if (micGranted) add(CallForegroundCapability.Microphone)
        }
        return CallServicePlan(foreground = capabilities.isNotEmpty(), capabilities = capabilities)
    }

    /** 没有麦克风权限就不要启动 ASR（会一直报错并污染文字链）。 */
    fun shouldStartAsr(micGranted: Boolean): Boolean = micGranted

    /**
     * 这一轮要不要继续监听麦克风。
     *
     * `audioUnavailable`（ASR/TTS 初始化失败或没权限）时必须返回 false，
     * 否则语音链会持续尝试并打断纯文字通话。
     *
     * [manualVideoInput] 是视频通话的**手动**输入模式：麦克风只在用户点"录一段"时开，
     * 所以自动监听（VAD 自动发送、回复期间继续听、打断检测）在这里必须是关的。
     * 纯语音通话不传这个参数（默认 false），既有行为一个字都不变。
     */
    fun shouldListen(
        status: VoiceCallStatus,
        muted: Boolean,
        keepListeningDuringReply: Boolean,
        audioUnavailable: Boolean = false,
        manualVideoInput: Boolean = false,
    ): Boolean =
        !manualVideoInput && !audioUnavailable &&
            voiceCallShouldListen(status, muted, keepListeningDuringReply)

    /**
     * 相机绑定门禁：Surface 就绪 + 有相机权限 + 生命周期 STARTED + 通话还在进行，
     * 四个条件缺一不可。授权变化、退后台、挂断都会让它变 false，从而一致地解绑。
     */
    fun shouldBindCamera(
        surfaceReady: Boolean,
        permissionGranted: Boolean,
        lifecycleStarted: Boolean,
        callActive: Boolean,
        cameraEnabled: Boolean = true,
    ): Boolean = cameraEnabled && surfaceReady && permissionGranted && lifecycleStarted && callActive

    /**
     * 服务在收到"启动/权限变化"之后应该做什么。
     *
     * 关键点：**任何**走 `startForegroundService` 进来的请求都必须在这个调用里同步
     * 决定要不要立刻 `startForeground`，否则就是 5 秒 `ForegroundServiceDidNotStartInTime` 崩溃。
     * 权限过渡期间（绑定模式已经在跑同一个会话）也必须在这里升级成前台，而不是
     * "发现同会话就直接 return"。
     */
    fun reconcilePlan(
        mode: CallSessionMode,
        foregroundCapabilities: Set<CallForegroundCapability>,
        cameraGranted: Boolean,
        micGranted: Boolean,
        videoMode: Boolean,
        audioPipelineReady: Boolean,
        foregroundRequired: Boolean,
    ): CallReconcilePlan {
        val plan = servicePlan(cameraGranted, micGranted, videoMode)
        if (!plan.foreground) {
            // 两个权限都没有：不能声明任何前台服务类型。
            // 若是从 startForegroundService 进来的，必须立刻停掉自己（由页面绑定承载纯文字通话），
            // 绝不能用 MICROPHONE 类型硬 startForeground。
            return CallReconcilePlan(
                mode = CallSessionMode.BoundOnly,
                capabilities = emptySet(),
                action = if (foregroundRequired) {
                    CallReconcileAction.StopService
                } else {
                    CallReconcileAction.None
                },
                ensureAudioPipeline = false,
            )
        }
        val action = when {
            mode != CallSessionMode.Foreground -> CallReconcileAction.StartForegroundNow
            foregroundCapabilities != plan.capabilities -> CallReconcileAction.UpdateForegroundType
            else -> CallReconcileAction.None
        }
        return CallReconcilePlan(
            mode = CallSessionMode.Foreground,
            capabilities = plan.capabilities,
            action = action,
            // 之前没有麦克风能力（绑定模式 / 只有相机）→ 现在补建 ASR/TTS/麦克风，且只建一次。
            ensureAudioPipeline = CallForegroundCapability.Microphone in plan.capabilities && !audioPipelineReady,
        )
    }

    /**
     * 页面的启动策略。
     *
     * "用户进入视频面板"本身就是一次明确的启动授权（[hasStartIntent]，只消费一次）。
     * 因此：
     * - 另一通电话在进行 → [VideoCallStartAction.BlockedByOtherCall]，并且**不保留**意图，
     *   等那通电话结束时绝不自动启动新的；
     * - 通话已在跑但只是绑定模式、现在有权限了 → 升级为前台（幂等 start 不会重建通话）；
     * - 两个权限都没有 → 绑定模式；
     * - 意图已经消费过 → 什么都不做（退后台/挂断后回前台不自动复活）。
     *
     * 注意：这个函数**只决定"要不要（重新）启动"**。通话已经在前台跑着时的权限变化
     * 不会让它返回任何动作（`callActive = true, boundSession = false` → [VideoCallStartAction.None]），
     * 那种情况必须走 [refreshAction]（REVIEW_R5 §5：camera-only 通话补到麦克风后，
     * 旧实现因为这里恒为 None 而永远不会让 service 重新对账，麦克风就永远不生效）。
     */
    fun startAction(
        hasStartIntent: Boolean,
        otherCallActive: Boolean,
        callActive: Boolean,
        boundSession: Boolean,
        cameraGranted: Boolean,
        micGranted: Boolean,
    ): VideoCallStartAction {
        if (otherCallActive) return VideoCallStartAction.BlockedByOtherCall
        val foregroundPossible = canStartForegroundCall(cameraGranted, micGranted)
        if (callActive) {
            return if (foregroundPossible && boundSession) {
                VideoCallStartAction.StartForeground
            } else {
                VideoCallStartAction.None
            }
        }
        if (!hasStartIntent) return VideoCallStartAction.None
        return if (foregroundPossible) {
            VideoCallStartAction.StartForeground
        } else {
            VideoCallStartAction.StartBound
        }
    }

    /**
     * 通话**已经在前台跑**时，权限组合变化该让服务做什么（REVIEW_R5 §5）。
     *
     * 这就是 [startAction] 明确覆盖不到的那条路径：
     * - camera-only 的视频通话首次拒绝麦克风 → 之后再次授权，必须让服务重新对账
     *   （`startForeground` 类型加上 MICROPHONE + 补建 ASR/TTS 一次）；
     * - 权限被撤回 → 前台类型要跟着减，绝不能继续声明已经没权限的能力；
     * - 两个权限都被撤回 → [VideoCallRefreshAction.DropForeground]：不能维持错误的 FGS，
     *   页面还绑着就降级成绑定模式，否则结束这通电话。
     *
     * @param audioPipelineReady 语音链路是否已经建好：已建好且能力组合没变就返回
     *        [VideoCallRefreshAction.None]，保证重复调用**幂等**、不重复初始化。
     */
    fun refreshAction(
        callActive: Boolean,
        foreground: Boolean,
        currentCapabilities: Set<CallForegroundCapability>,
        cameraGranted: Boolean,
        micGranted: Boolean,
        videoMode: Boolean,
        audioPipelineReady: Boolean,
    ): VideoCallRefreshAction {
        if (!callActive) return VideoCallRefreshAction.NotCurrentCall
        val plan = servicePlan(cameraGranted, micGranted, videoMode)
        if (!plan.foreground) return VideoCallRefreshAction.DropForeground
        if (!foreground) return VideoCallRefreshAction.RefreshSession
        if (currentCapabilities != plan.capabilities) return VideoCallRefreshAction.RefreshSession
        val needsAudioPipeline =
            CallForegroundCapability.Microphone in plan.capabilities && !audioPipelineReady
        return if (needsAudioPipeline) {
            VideoCallRefreshAction.RefreshSession
        } else {
            VideoCallRefreshAction.None
        }
    }
}

/** 通话进行中、权限组合变化时的动作（[VideoCallPolicy.refreshAction]）。 */
enum class VideoCallRefreshAction {
    /** 能力组合没变、语音链路也建好了：什么都不用做（重复调用必须落在这里）。 */
    None,

    /** 这通电话不在跑／不是当前会话：调用方不得对它做任何事。 */
    NotCurrentCall,

    /** 让服务对账一次：升级成前台、刷新前台类型、补建语音链路（每项各自都是幂等的）。 */
    RefreshSession,

    /** 已经没有任何前台能力：不能维持错误的 FGS，降级成绑定模式或结束通话。 */
    DropForeground,
}

/** 服务当前的存活形态。 */
enum class CallSessionMode {
    /** 没有通话在跑。 */
    None,

    /** 由可见 Activity 绑定运行（两个权限都没有的纯文字黑底模式）。 */
    BoundOnly,

    /** 前台服务，按实际能力组合声明类型。 */
    Foreground,
}

enum class CallReconcileAction {
    None,

    /** 必须**同步**调用 startForeground（5 秒窗口）。 */
    StartForegroundNow,

    /** 已经是前台，但实际权限组合变了：刷新类型。 */
    UpdateForegroundType,

    /** 从 startForegroundService 进来却没有任何前台能力：立刻 stopSelf。 */
    StopService,
}

data class CallReconcilePlan(
    val mode: CallSessionMode,
    val capabilities: Set<CallForegroundCapability>,
    val action: CallReconcileAction,
    val ensureAudioPipeline: Boolean,
)

enum class VideoCallStartAction {
    None,

    /** 调 `VoiceCallService.start(...)`（幂等；也用于绑定模式→前台的升级）。 */
    StartForeground,

    /** 调 `startBoundCall(...)`（可见 Activity 绑定运行）。 */
    StartBound,

    /** 别的会话正在通话：不启动，也不保留意图。 */
    BlockedByOtherCall,
}

/**
 * 每轮对话的令牌（token）门禁。
 *
 * 旧的挂起任务（等待抓帧、等待提交）拿的是旧 token；打断/挂断先 [invalidate]，
 * 旧任务在下一个检查点就会发现自己已经过期，从而**不再**提交请求或写消息。
 */
class VoiceCallTurnGate {
    private var current: Long = 0

    @Synchronized
    fun begin(): Long {
        current += 1
        return current
    }

    @Synchronized
    fun isCurrent(token: Long): Boolean = token != 0L && token == current

    @Synchronized
    fun invalidate() {
        current += 1
    }

    @Synchronized
    fun current(): Long = current
}
