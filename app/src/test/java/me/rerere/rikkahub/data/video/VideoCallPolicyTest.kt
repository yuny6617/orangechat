/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限/前台服务类型/麦克风策略/每轮令牌/相机门禁的纯逻辑测试。
 *
 * 这些判断原本散在 Service 与页面里、无法单测；抽成纯函数后可以逐条钉住，
 * 包括"两个权限都拒绝时不是不能通话，而是走绑定模式"这类边界。
 */
class VideoCallPolicyTest {
    @Test
    fun `a camera or microphone permission is enough to start the foreground call`() {
        assertTrue(VideoCallPolicy.canStartForegroundCall(cameraGranted = true, micGranted = false))
        assertTrue(VideoCallPolicy.canStartForegroundCall(cameraGranted = false, micGranted = true))
        assertFalse(VideoCallPolicy.canStartForegroundCall(cameraGranted = false, micGranted = false))
        assertTrue(VideoCallPolicy.canStartForegroundCall(cameraGranted = true, micGranted = true))
    }

    @Test
    fun `both permissions denied falls back to the bound text only mode`() {
        assertTrue(VideoCallPolicy.isTextOnlyMode(cameraGranted = false, micGranted = false))
        assertFalse(VideoCallPolicy.isTextOnlyMode(cameraGranted = true, micGranted = false))
        val plan = VideoCallPolicy.servicePlan(cameraGranted = false, micGranted = false, videoMode = true)
        assertFalse(plan.foreground)
        assertTrue(plan.capabilities.isEmpty())
    }

    @Test
    fun `foreground service types follow the capabilities actually in use`() {
        // 视频 + 相机 + 麦克风：CAMERA | MICROPHONE
        val both = VideoCallPolicy.servicePlan(cameraGranted = true, micGranted = true, videoMode = true)
        assertTrue(both.foreground)
        assertEquals(
            setOf(CallForegroundCapability.Camera, CallForegroundCapability.Microphone),
            both.capabilities,
        )
        // 只有相机：只要 CAMERA，不声明麦克风（ASR 也不会启动）
        val cameraOnly = VideoCallPolicy.servicePlan(cameraGranted = true, micGranted = false, videoMode = true)
        assertEquals(setOf(CallForegroundCapability.Camera), cameraOnly.capabilities)
        // 只有麦克风：退化成 CAMERA 之外的 MICROPHONE，绝不声明没有权限的 CAMERA
        val micOnly = VideoCallPolicy.servicePlan(cameraGranted = false, micGranted = true, videoMode = true)
        assertEquals(setOf(CallForegroundCapability.Microphone), micOnly.capabilities)
        // 纯语音通话不需要相机
        val voice = VideoCallPolicy.servicePlan(cameraGranted = true, micGranted = true, videoMode = false)
        assertEquals(setOf(CallForegroundCapability.Microphone), voice.capabilities)
    }

    @Test
    fun `a foreground plan always declares at least one real capability`() {
        // REVIEW_R3 §7：旧实现 servicePlan(videoMode=false, 相机=true, 麦克风=false)
        // 返回 foreground=true + 空能力集 → startForeground(type=0) 失败 → 5 秒必崩。
        for (videoMode in listOf(true, false)) {
            for (camera in listOf(true, false)) {
                for (mic in listOf(true, false)) {
                    val plan = VideoCallPolicy.servicePlan(cameraGranted = camera, micGranted = mic, videoMode = videoMode)
                    assertEquals(
                        "video=$videoMode camera=$camera mic=$mic：前台计划必须声明类型",
                        plan.foreground,
                        plan.capabilities.isNotEmpty(),
                    )
                }
            }
        }
    }

    @Test
    fun `voice call without microphone can never run as a foreground service`() {
        val plan = VideoCallPolicy.servicePlan(cameraGranted = true, micGranted = false, videoMode = false)
        assertFalse("纯语音没有麦克风就不是前台服务", plan.foreground)
        assertTrue(plan.capabilities.isEmpty())

        val reconcile = VideoCallPolicy.reconcilePlan(
            mode = CallSessionMode.BoundOnly,
            foregroundCapabilities = emptySet(),
            cameraGranted = true,
            micGranted = false,
            videoMode = false,
            audioPipelineReady = false,
            foregroundRequired = true,
        )
        assertEquals(CallSessionMode.BoundOnly, reconcile.mode)
        assertEquals(CallReconcileAction.StopService, reconcile.action)
        assertTrue(reconcile.capabilities.isEmpty())

        // 同样的权限组合在视频模式下是合法的：相机就是它的前台类型。
        val video = VideoCallPolicy.servicePlan(cameraGranted = true, micGranted = false, videoMode = true)
        assertTrue(video.foreground)
        assertEquals(setOf(CallForegroundCapability.Camera), video.capabilities)
    }

    @Test
    fun `asr is never started without microphone permission`() {
        assertFalse(VideoCallPolicy.shouldStartAsr(micGranted = false))
        assertTrue(VideoCallPolicy.shouldStartAsr(micGranted = true))
    }

    @Test
    fun `audio unavailable disables listening but keeps the call alive`() {
        // 纯语音：Listening 且未静音 → 监听
        assertTrue(VideoCallPolicy.shouldListen(VoiceCallStatus.Listening, muted = false, keepListeningDuringReply = true))
        // 音频不可用（没权限/ASR 初始化失败）→ 任何状态都不监听
        assertFalse(
            VideoCallPolicy.shouldListen(
                VoiceCallStatus.Listening, muted = false, keepListeningDuringReply = true, audioUnavailable = true,
            ),
        )
        assertFalse(
            VideoCallPolicy.shouldListen(
                VoiceCallStatus.Speaking, muted = false, keepListeningDuringReply = true, audioUnavailable = true,
            ),
        )
        // 静音 / Idle / Error 仍然不监听（保持原语音行为）
        assertFalse(VideoCallPolicy.shouldListen(VoiceCallStatus.Listening, muted = true, keepListeningDuringReply = true))
        assertFalse(VideoCallPolicy.shouldListen(VoiceCallStatus.Idle, muted = false, keepListeningDuringReply = true))
        assertFalse(VideoCallPolicy.shouldListen(VoiceCallStatus.Error, muted = false, keepListeningDuringReply = true))
    }

    @Test
    fun `manual video input disables every automatic listening path`() {
        // 视频通话是手动采音：无论状态、是否静音、是否"回复期间继续听"，自动监听都必须是关的
        // （否则 VAD 会自动分段发送、ASR monitor 会抢用户那一颗按钮的麦克风）。
        VoiceCallStatus.entries.forEach { status ->
            assertFalse(
                "手动视频模式在 $status 下也不许自动监听",
                VideoCallPolicy.shouldListen(
                    status, muted = false, keepListeningDuringReply = true, manualVideoInput = true,
                ),
            )
        }
        // 纯语音（不传这个参数）行为完全不变。
        assertTrue(VideoCallPolicy.shouldListen(VoiceCallStatus.Listening, muted = false, keepListeningDuringReply = true))
        assertTrue(VideoCallPolicy.shouldListen(VoiceCallStatus.Speaking, muted = false, keepListeningDuringReply = true))
    }

    @Test
    fun `camera binds only when surface permission lifecycle and call all agree`() {
        assertTrue(
            VideoCallPolicy.shouldBindCamera(
                surfaceReady = true, permissionGranted = true, lifecycleStarted = true, callActive = true,
            ),
        )
        assertFalse(
            VideoCallPolicy.shouldBindCamera(
                surfaceReady = false, permissionGranted = true, lifecycleStarted = true, callActive = true,
            ),
        )
        // 授权前 Surface 已经创建：授权变化后要重新判断（这里描述的是"没权限就不绑"）
        assertFalse(
            VideoCallPolicy.shouldBindCamera(
                surfaceReady = true, permissionGranted = false, lifecycleStarted = true, callActive = true,
            ),
        )
        assertFalse(
            VideoCallPolicy.shouldBindCamera(
                surfaceReady = true, permissionGranted = true, lifecycleStarted = false, callActive = true,
            ),
        )
        assertFalse(
            VideoCallPolicy.shouldBindCamera(
                surfaceReady = true, permissionGranted = true, lifecycleStarted = true, callActive = false,
            ),
        )
    }

    @Test
    fun `turn gate invalidates stale async work`() {
        val gate = VoiceCallTurnGate()
        val first = gate.begin()
        assertTrue(gate.isCurrent(first))
        // 打断/挂断：旧 token 立刻失效
        gate.invalidate()
        assertFalse(gate.isCurrent(first))
        // 新的一轮才有新 token
        val second = gate.begin()
        assertTrue(gate.isCurrent(second))
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.current() != 0L)
        // 0 永远不是有效 token（避免"未初始化就算当前"）
        assertFalse(gate.isCurrent(0L))
    }

    @Test
    fun `turn gate is safe under concurrent invalidation`() {
        val gate = VoiceCallTurnGate()
        val token = gate.begin()
        val other = Thread { gate.invalidate() }
        other.start()
        other.join()
        assertFalse(gate.isCurrent(token))
    }

    // ---------------------------------------------------------------------
    // 前台服务能力对账：REVIEW_R2 §1 的"5 秒必崩"路径
    // ---------------------------------------------------------------------

    @Test
    fun `bound session gaining camera permission must start foreground immediately`() {
        // 页面在权限弹窗前先用绑定模式跑起来，相机获批后 startForegroundService 进来：
        // 必须立刻 startForeground（旧实现"发现同会话直接 return"→ 5 秒崩溃）。
        val plan = VideoCallPolicy.reconcilePlan(
            mode = CallSessionMode.BoundOnly,
            foregroundCapabilities = emptySet(),
            cameraGranted = true,
            micGranted = false,
            videoMode = true,
            audioPipelineReady = false,
            foregroundRequired = true,
        )
        assertEquals(CallSessionMode.Foreground, plan.mode)
        assertEquals(setOf(CallForegroundCapability.Camera), plan.capabilities)
        assertEquals(CallReconcileAction.StartForegroundNow, plan.action)
        // 只有相机：不补建 ASR（没有麦克风权限就不该启动语音识别）
        assertFalse(plan.ensureAudioPipeline)
    }

    @Test
    fun `repeating the same call start is idempotent`() {
        val plan = VideoCallPolicy.reconcilePlan(
            mode = CallSessionMode.Foreground,
            foregroundCapabilities = setOf(CallForegroundCapability.Camera, CallForegroundCapability.Microphone),
            cameraGranted = true,
            micGranted = true,
            videoMode = true,
            audioPipelineReady = true,
            foregroundRequired = true,
        )
        assertEquals(CallSessionMode.Foreground, plan.mode)
        assertEquals(CallReconcileAction.None, plan.action)
        assertFalse("不该重复补建语音链路", plan.ensureAudioPipeline)
    }

    @Test
    fun `gaining microphone permission updates the foreground type and builds audio once`() {
        val plan = VideoCallPolicy.reconcilePlan(
            mode = CallSessionMode.Foreground,
            foregroundCapabilities = setOf(CallForegroundCapability.Camera),
            cameraGranted = true,
            micGranted = true,
            videoMode = true,
            audioPipelineReady = false,
            foregroundRequired = true,
        )
        assertEquals(
            setOf(CallForegroundCapability.Camera, CallForegroundCapability.Microphone),
            plan.capabilities,
        )
        assertEquals(CallReconcileAction.UpdateForegroundType, plan.action)
        assertTrue(plan.ensureAudioPipeline)
    }

    @Test
    fun `voice call upgraded to video refreshes the type without rebuilding the call`() {
        val plan = VideoCallPolicy.reconcilePlan(
            mode = CallSessionMode.Foreground,
            foregroundCapabilities = setOf(CallForegroundCapability.Microphone),
            cameraGranted = true,
            micGranted = true,
            videoMode = true,
            audioPipelineReady = true,
            foregroundRequired = true,
        )
        assertEquals(
            setOf(CallForegroundCapability.Camera, CallForegroundCapability.Microphone),
            plan.capabilities,
        )
        assertEquals(CallReconcileAction.UpdateForegroundType, plan.action)
        assertFalse(plan.ensureAudioPipeline)
    }

    @Test
    fun `no permission at all can never start a foreground service`() {
        val required = VideoCallPolicy.reconcilePlan(
            mode = CallSessionMode.BoundOnly,
            foregroundCapabilities = emptySet(),
            cameraGranted = false,
            micGranted = false,
            videoMode = true,
            audioPipelineReady = false,
            foregroundRequired = true,
        )
        // 不能声明任何类型硬 startForeground：必须 stopSelf，让绑定模式承载。
        assertEquals(CallReconcileAction.StopService, required.action)
        assertTrue(required.capabilities.isEmpty())

        val bound = VideoCallPolicy.reconcilePlan(
            mode = CallSessionMode.BoundOnly,
            foregroundCapabilities = emptySet(),
            cameraGranted = false,
            micGranted = false,
            videoMode = true,
            audioPipelineReady = false,
            foregroundRequired = false,
        )
        assertEquals(CallSessionMode.BoundOnly, bound.mode)
        assertEquals(CallReconcileAction.None, bound.action)
    }

    // ---------------------------------------------------------------------
    // 页面启动意图：REVIEW_R2 §2 的"另一通电话结束自动启动新通话"
    // ---------------------------------------------------------------------

    @Test
    fun `page start action follows the one shot intent`() {        // 进入页面、没有权限 → 绑定模式
        assertEquals(
            VideoCallStartAction.StartBound,
            VideoCallPolicy.startAction(
                hasStartIntent = true, otherCallActive = false, callActive = false, boundSession = false,
                cameraGranted = false, micGranted = false,
            ),
        )
        // 进入页面、有权限 → 前台服务
        assertEquals(
            VideoCallStartAction.StartForeground,
            VideoCallPolicy.startAction(
                hasStartIntent = true, otherCallActive = false, callActive = false, boundSession = false,
                cameraGranted = true, micGranted = true,
            ),
        )
        // 意图已消费（退后台/挂断后回前台）：绝不自动复活
        assertEquals(
            VideoCallStartAction.None,
            VideoCallPolicy.startAction(
                hasStartIntent = false, otherCallActive = false, callActive = false, boundSession = false,
                cameraGranted = true, micGranted = true,
            ),
        )
        // 别的会话正在通话：不启动，也不保留意图
        assertEquals(
            VideoCallStartAction.BlockedByOtherCall,
            VideoCallPolicy.startAction(
                hasStartIntent = true, otherCallActive = true, callActive = false, boundSession = false,
                cameraGranted = true, micGranted = true,
            ),
        )
        // 通话已在进行、只是绑定模式，现在有权限 → 升级为前台（幂等 start）
        assertEquals(
            VideoCallStartAction.StartForeground,
            VideoCallPolicy.startAction(
                hasStartIntent = false, otherCallActive = false, callActive = true, boundSession = true,
                cameraGranted = true, micGranted = false,
            ),
        )
        // 通话已在进行、已经是前台 → 什么都不做
        assertEquals(
            VideoCallStartAction.None,
            VideoCallPolicy.startAction(
                hasStartIntent = false, otherCallActive = false, callActive = true, boundSession = false,
                cameraGranted = true, micGranted = true,
            ),
        )
    }

    // ---------------------------------------------------------------------
    // REVIEW_R5 §5：已经在前台的通话补到权限 / 权限被撤回时的显式刷新
    // ---------------------------------------------------------------------

    @Test
    fun `a camera only foreground call must refresh when the microphone is granted later`() {
        // 真实路径：camera-only 视频通话首次拒绝麦克风 → 之后又授权。
        // startAction 在"通话已在前台"时恒为 None，所以必须由 refreshAction 触发对账。
        assertEquals(
            VideoCallStartAction.None,
            VideoCallPolicy.startAction(
                hasStartIntent = false, otherCallActive = false, callActive = true, boundSession = false,
                cameraGranted = true, micGranted = true,
            ),
        )
        assertEquals(
            VideoCallRefreshAction.RefreshSession,
            VideoCallPolicy.refreshAction(
                callActive = true,
                foreground = true,
                currentCapabilities = setOf(CallForegroundCapability.Camera),
                cameraGranted = true,
                micGranted = true,
                videoMode = true,
                audioPipelineReady = false,
            ),
        )
        // 语音链路补建完成之后再刷新：幂等，什么都不做（不重复初始化 ASR/TTS）。
        assertEquals(
            VideoCallRefreshAction.None,
            VideoCallPolicy.refreshAction(
                callActive = true,
                foreground = true,
                currentCapabilities = setOf(
                    CallForegroundCapability.Camera,
                    CallForegroundCapability.Microphone,
                ),
                cameraGranted = true,
                micGranted = true,
                videoMode = true,
                audioPipelineReady = true,
            ),
        )
    }

    @Test
    fun `revoking a permission refreshes the type and revoking both drops the foreground service`() {
        // 麦克风被撤回：前台类型必须从 CAMERA|MICROPHONE 减回 CAMERA。
        assertEquals(
            VideoCallRefreshAction.RefreshSession,
            VideoCallPolicy.refreshAction(
                callActive = true,
                foreground = true,
                currentCapabilities = setOf(
                    CallForegroundCapability.Camera,
                    CallForegroundCapability.Microphone,
                ),
                cameraGranted = true,
                micGranted = false,
                videoMode = true,
                audioPipelineReady = true,
            ),
        )
        // 相机也被撤回：没有任何前台能力，不能继续挂着一个错误的 FGS。
        assertEquals(
            VideoCallRefreshAction.DropForeground,
            VideoCallPolicy.refreshAction(
                callActive = true,
                foreground = true,
                currentCapabilities = setOf(CallForegroundCapability.Camera),
                cameraGranted = false,
                micGranted = false,
                videoMode = true,
                audioPipelineReady = true,
            ),
        )
        // 纯语音（videoMode=false）只剩相机权限：同样没有任何真实前台能力。
        assertEquals(
            VideoCallRefreshAction.DropForeground,
            VideoCallPolicy.refreshAction(
                callActive = true,
                foreground = true,
                currentCapabilities = setOf(CallForegroundCapability.Camera),
                cameraGranted = true,
                micGranted = false,
                videoMode = false,
                audioPipelineReady = true,
            ),
        )
    }

    @Test
    fun `refresh is scoped to the live call and upgrades a bound session`() {
        // 不是当前通话：调用方不许对别人的通话做任何事。
        assertEquals(
            VideoCallRefreshAction.NotCurrentCall,
            VideoCallPolicy.refreshAction(
                callActive = false,
                foreground = true,
                currentCapabilities = setOf(CallForegroundCapability.Camera),
                cameraGranted = true,
                micGranted = true,
                videoMode = true,
                audioPipelineReady = true,
            ),
        )
        // 还在绑定模式（没有前台）：有权限了就要升级成前台。
        assertEquals(
            VideoCallRefreshAction.RefreshSession,
            VideoCallPolicy.refreshAction(
                callActive = true,
                foreground = false,
                currentCapabilities = emptySet(),
                cameraGranted = true,
                micGranted = false,
                videoMode = true,
                audioPipelineReady = false,
            ),
        )
    }

    // ---------------------------------------------------------------------
    // 相机门禁在真实生命周期里的走向（授权弹窗 / 页面重建 / 挂断）
    // ---------------------------------------------------------------------

    @Test
    fun `camera gate follows the real permission dialog and recreate path`() {
        // 页面刚进来：TextureView 的 surface 还没就绪，通话也还没开始
        assertFalse(
            VideoCallPolicy.shouldBindCamera(
                surfaceReady = false, permissionGranted = false, lifecycleStarted = true, callActive = false,
            ),
        )
        // surface 就绪 + 通话开始，但授权弹窗抢走了焦点（ON_STOP）：不开相机
        assertFalse(
            VideoCallPolicy.shouldBindCamera(
                surfaceReady = true, permissionGranted = false, lifecycleStarted = false, callActive = true,
            ),
        )
        // 弹窗关闭回到前台、权限已给：这时才开（一次）
        assertTrue(
            VideoCallPolicy.shouldBindCamera(
                surfaceReady = true, permissionGranted = true, lifecycleStarted = true, callActive = true,
            ),
        )
        // 用户拒绝相机权限：同一面板退化成黑底，只打字
        assertFalse(
            VideoCallPolicy.shouldBindCamera(
                surfaceReady = true, permissionGranted = false, lifecycleStarted = true, callActive = true,
            ),
        )
        // 页面重建：surface 先销毁再重建，重建期间不开相机
        assertFalse(
            VideoCallPolicy.shouldBindCamera(
                surfaceReady = false, permissionGranted = true, lifecycleStarted = true, callActive = true,
            ),
        )
        assertTrue(
            VideoCallPolicy.shouldBindCamera(
                surfaceReady = true, permissionGranted = true, lifecycleStarted = true, callActive = true,
            ),
        )
        // 退后台（ON_STOP）立刻停：绝不在背景里留 Surface 继续开相机
        assertFalse(
            VideoCallPolicy.shouldBindCamera(
                surfaceReady = true, permissionGranted = true, lifecycleStarted = false, callActive = true,
            ),
        )
        // 挂断之后即使一切都还就绪也不再开
        assertFalse(
            VideoCallPolicy.shouldBindCamera(
                surfaceReady = true, permissionGranted = true, lifecycleStarted = true, callActive = false,
            ),
        )
    }
}
