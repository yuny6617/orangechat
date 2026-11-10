/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.uuid.Uuid
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.video.TransientAudioClip
import me.rerere.rikkahub.data.video.VideoCallRuntime
import me.rerere.rikkahub.ui.hooks.CustomAsrState
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus
import me.rerere.tts.model.PlaybackState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * 真实 [VoiceCallService] 的**服务集成回归**（REVIEW_R6 §1/§2、REVIEW_R7 §1/§2）。
 *
 * 为什么不是"再测一遍纯 state"：
 * - 这里跑的是 `Robolectric.buildService(VoiceCallService::class.java)` 出来的真服务，
 *   前台/通知断言来自真实的 `ShadowService`（`isForegroundStopped`、
 *   `lastForegroundNotification`），权限用真实的 `ShadowApplication` grant/deny；
 * - 走的都是页面真正调用的入口：`onBind`（绑定客户端）、`startBoundCall`、
 *   `refreshSessionPermissions`、`endCall`；
 * - 只替换**注入点**（ASR/TTS 工厂、通话交互记录、会话流，R7 另加视频协调器创建点——
 *   它只是"建协调器"这一步，相机/礼物/留影本身的生产代码一行没改），其余初始化代次、
 *   补建幂等、权限对账、麦克风停止/ASR 释放、前台与通知全部是生产代码。
 *
 * 这些用例直接钉住 R6/R7 点名的缺陷：
 * 1. `endCall` 先 `reset()` 再查 `isForegroundRunning()` → **永远不 stopForeground**；
 * 2. 撤回麦克风只缩前台类型、不停录音、不释放 ASR → 继续录/报错，重新授权后永远静音；
 * 3. 服务被复用：`lateinit asr` 存在即被当作"这次已就绪" → 旧转写串到新通话；
 * 4. 初始化未完成时重复 refresh → 反复 cancel/restart，ASR 可能永远起不来；
 * 5. （R7）ASR 工厂挂起期间撤麦克风（相机保留）→ 旧初始化**不许**提交成 ready，
 *    也**不许**把新会话的 TTS/开场一起取消；旧 provider 回调不许复用/串台；
 * 6. （R7）清理幂等：挂断、复用、`onDestroy` 反复收尾只 cleanup 一次。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class VoiceCallServiceLifecycleTest {
    private lateinit var app: Application
    private lateinit var appScope: AppScope
    private lateinit var controller: ServiceController<VoiceCallService>
    private lateinit var service: VoiceCallService
    private lateinit var settings: SettingsStore

    private val conversationId = Uuid.random().toString()
    private val asrInstances = mutableListOf<FakeAsr>()
    private val ttsInstances = mutableListOf<FakeTts>()
    private var asrFactoryCalls = 0
    private var asrGate: CompletableDeferred<Unit>? = null

    @Before
    fun setUp() {
        VideoCallRuntime.reset()
        app = ApplicationProvider.getApplicationContext()
        appScope = AppScope()
        startKoin {
            modules(
                module {
                    // Koin 测试 fixture：真实 SettingsStore（settingsFlow 立刻有 dummy 值），
                    // 服务只需要读 voiceCallKeepListeningDuringReply。
                    single { SettingsStore(app, appScope) }
                    // SettingsStore 的持久化协程会用到 Pebble 模板引擎（与其它 Koin 测试一致）。
                    single { io.pebbletemplates.pebble.PebbleEngine.Builder().build() }
                }
            )
        }
        settings = org.koin.core.context.GlobalContext.get().get()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        controller = Robolectric.buildService(VoiceCallService::class.java).create()
        service = controller.get()
        val conversationState = MutableStateFlow(Conversation(assistantId = Uuid.random(), messageNodes = emptyList()))
        service.conversationStateFor = { conversationState }
        service.createAsrState = {
            asrFactoryCalls += 1
            // 需要模拟"初始化还没完成"的用例会在这里挂起。
            asrGate?.await()
            FakeAsr().also { asrInstances += it }
        }
        service.createTtsState = { FakeTts().also { ttsInstances += it } }
        // 通话交互记录需要 ChatService；这两个注入点只为让真实初始化路径能跑完。
        service.beginCallInteraction = { _, _ -> null }
        service.finishCallInteraction = { _, _, _, _, _, _, _ -> }
    }

    @After
    fun tearDown() {
        // 先挂断（companion 里的"当前通话"是进程级状态，必须清掉，避免污染下一个用例），
        // 再销毁服务。
        runCatching { service.endCall() }
        runCatching { controller.destroy() }
        runCatching { appScope.cancel() }
        runCatching { stopKoin() }
        VideoCallRuntime.reset()
    }

    private fun pumpMain(timeoutMillis: Long = 10_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * 走**真实的**前台启动路径：页面调 `VoiceCallService.start(...)` 之后系统会回调
     * `onStartCommand`，这里直接触发同一个回调（`isChatRecoveryBlocking()` 对普通
     * Application 为 false，不会提前 return）。
     */
    private fun startForegroundCall(
        conversation: String = conversationId,
        video: Boolean = false,
    ) {
        val intent = Intent(app, VoiceCallService::class.java).apply {
            putExtra(VoiceCallService.EXTRA_CONVERSATION_ID, conversation)
            if (video) putExtra(VoiceCallService.EXTRA_VIDEO_MODE, true)
        }
        service.onStartCommand(intent, 0, 1)
    }

    // ------------------------------------------------------------------
    // §1 挂断必须当场停前台与通知
    // ------------------------------------------------------------------

    @Test
    fun `hanging up stops the foreground notification the call started`() {
        // 页面绑着服务（真实 onBind 回调），然后通话真的以后台前台服务跑起来。
        service.onBind(null)
        startForegroundCall()
        assertNotNull("通话必须真的把前台服务/通知挂起来", shadowOf(service).lastForegroundNotification)
        assertFalse(shadowOf(service).isForegroundStopped)
        assertEquals(conversationId, service.boundConversationId())

        service.endCall()

        assertTrue(
            "endCall 必须当场 stopForeground（旧代码先 reset 再查 isForegroundRunning() 恒为 false，通知留到 onDestroy）",
            shadowOf(service).isForegroundStopped,
        )
        assertFalse("挂断之后不再有进行中的通话", VoiceCallService.isRunning())
        assertNull(VoiceCallService.activeConversationId.value)
    }

    // ------------------------------------------------------------------
    // §2 撤回麦克风：停录音、释放 ASR、降级，而不是继续录
    // ------------------------------------------------------------------

    @Test
    fun `revoking the microphone stops the recorder and the foreground service`() {
        service.onBind(null)
        startForegroundCall()
        // 真实初始化跑完：假 ASR 已经被麦克风启动（开始录音）。
        pumpMain { asrInstances.size == 1 && asrInstances.single().startCount == 1 }
        val asr = asrInstances.single()

        // 用户在系统设置里撤回麦克风：页面会调用 refreshSessionPermissions。
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(service.refreshSessionPermissions(conversationId))

        assertTrue("撤权必须停掉前台服务", shadowOf(service).isForegroundStopped)
        assertTrue("页面还绑着：降级成绑定模式而不是直接挂断", service.isBoundOnly())
        assertTrue("麦克风必须被停掉", asr.stopCount >= 1)
        assertEquals("旧 ASR 必须被释放（否则重新授权后还在用旧链路）", 1, asr.cleanupCount)
        assertEquals("撤权后不许留半截转写", "", service.uiState.value.userTranscript)
        assertTrue(service.uiState.value.amplitudes.isEmpty())
    }

    // ------------------------------------------------------------------
    // §2 重新授权：补建一次、不重置计时/状态
    // ------------------------------------------------------------------

    @Test
    fun `regranting the microphone rebuilds the input pipeline once without restarting the call`() {
        service.onBind(null)
        startForegroundCall()
        pumpMain { asrInstances.size == 1 && asrInstances.single().startCount == 1 }
        val firstAsr = asrInstances.single()
        val startedAt = VoiceCallService.startedAtMillis.value
        assertTrue("通话必须真的开始了（计时已开始）", startedAt > 0L)

        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(service.refreshSessionPermissions(conversationId))
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

        // 页面重组/权限回调重复触发：补建必须只发生一次。
        repeat(4) { assertTrue(service.refreshSessionPermissions(conversationId)) }
        pumpMain { asrInstances.size == 2 && asrInstances[1].startCount == 1 }

        assertEquals("重新授权只补建一次（重复刷新不许反复 cancel/restart）", 2, asrInstances.size)
        assertEquals("旧链路在撤权时被释放", 1, firstAsr.cleanupCount)
        assertFalse("重新授权要升回前台", VoiceCallService.boundOnlyCall.value)
        assertEquals("补建不许重置通话计时", startedAt, VoiceCallService.startedAtMillis.value)
        assertEquals("补建不许留下上一轮的转写", "", service.uiState.value.userTranscript)
    }

    // ------------------------------------------------------------------
    // §2 初始化未完成时重复刷新：不许取消/重启
    // ------------------------------------------------------------------

    @Test
    fun `refreshing while the first initialization is still running does not restart it`() {
        asrGate = CompletableDeferred()
        service.onBind(null)
        startForegroundCall()
        pumpMain { asrFactoryCalls == 1 }

        repeat(5) { assertTrue(service.refreshSessionPermissions(conversationId)) }
        assertEquals("初始化还没提交时，重复刷新绝不许重启初始化", 1, asrFactoryCalls)

        asrGate?.complete(Unit)
        pumpMain { asrInstances.size == 1 && asrInstances.single().startCount == 1 }
        assertEquals(1, asrFactoryCalls)
        assertFalse(shadowOf(service).isForegroundStopped)
    }

    // ------------------------------------------------------------------
    // §2 服务被复用：上一通的 ASR 绝不能算"这次就绪"
    // ------------------------------------------------------------------

    @Test
    fun `a reused service never treats the previous call's asr as ready`() {
        // 第一通：有麦克风，ASR 真的建起来并开始录音。
        service.onBind(null)
        startForegroundCall()
        pumpMain { asrInstances.size == 1 && asrInstances.single().startCount == 1 }
        val firstAsr = asrInstances.single()
        service.endCall()
        assertEquals("挂断就要释放旧 ASR（不能只 stop）", 1, firstAsr.cleanupCount)

        // 第二通：同一台 bound service 被复用，但这次没有麦克风权限（页面退回绑定模式）。
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val secondCall = Uuid.random().toString()
        assertTrue(service.startBoundCall(secondCall, video = false))
        pumpMain { ttsInstances.size == 2 }

        assertEquals("没有麦克风权限就不许新建 ASR", 1, asrInstances.size)
        assertTrue("这次通话没有可用的语音链路（链路 not ready）", service.uiState.value.amplitudes.isEmpty())
        // 旧 ASR 的迟到回调：绝不能改到新通话的转写。
        firstAsr.emitTranscription("上一通电话的迟到转写")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("旧 ASR 的转写不许串到新通话", "", service.uiState.value.userTranscript)

        // 这次真的授权了：必须新建一套链路，而不是把上一通的当成就绪。
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(service.refreshSessionPermissions(secondCall))
        pumpMain { asrInstances.size == 2 && asrInstances[1].startCount == 1 }
        assertNotSame("复用的服务必须新建 ASR，不能复用上一通那个", firstAsr, asrInstances[1])
    }

    // ------------------------------------------------------------------
    // §R7-1 初始化挂起期间撤麦克风（相机保留）：旧结果不许提交、新会话仍要可打字
    // ------------------------------------------------------------------

    @Test
    fun `revoking the microphone while the asr factory is suspended never commits that input`() {
        // 相机还在：视频通话里撤麦克风后前台能力只剩 CAMERA，通话不许被降级/挂断。
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        // 视频协调器需要整套 ChatService：这里只替换创建点（相机/礼物逻辑不受影响）。
        service.createVideoCoordinator = { _, _, _, _ -> null }
        asrGate = CompletableDeferred()

        service.onBind(null)
        startForegroundCall(video = true)
        pumpMain { asrFactoryCalls == 1 }
        assertTrue("这条用例必须跑的是真实 videoMode 路径", service.isVideoMode())
        assertTrue("ASR 工厂还挂着：这时一个实例都不该建出来", asrInstances.isEmpty())

        // 用户在初始化期间撤回麦克风（相机权限保留）。
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue("相机还在：这通电话必须继续活着", service.refreshSessionPermissions(conversationId))
        assertFalse("相机还在：不许停掉前台服务", shadowOf(service).isForegroundStopped)
        assertFalse("有相机就不许降级成绑定模式", service.isBoundOnly())
        assertEquals("撤权不许重启/新建 ASR", 1, asrFactoryCalls)

        // 放行旧工厂：它会返回一个 ASR 实例，但这个结果已经作废。
        asrGate?.complete(Unit)
        pumpMain { asrInstances.isNotEmpty() && VoiceCallService.startedAtMillis.value > 0L }

        val staleAsr = asrInstances.single()
        assertEquals("撤权前建出来的旧 ASR 绝不许开始录音", 0, staleAsr.startCount)
        assertEquals("旧结果不许被当成当前链路去 stop/start", 0, staleAsr.stopCount)
        assertEquals("旧结果必须当场释放（旧代码会把它装成就绪链路、一次都不 cleanup）", 1, staleAsr.cleanupCount)
        assertEquals("只能有这一个（被丢弃的）实例", 1, asrFactoryCalls)
        assertEquals("TTS 不许被撤麦克风连累（新会话仍要能朗读）", 1, ttsInstances.size)
        assertTrue("新会话仍要真的开场（计时已开始）", VoiceCallService.startedAtMillis.value > 0L)
        assertTrue("文字通话必须仍在跑、可打字", VoiceCallService.isRunning())
        assertEquals("文字通话必须停在可打字状态（不是 Error）", VoiceCallStatus.Listening, service.uiState.value.status)
        assertEquals("旧链路的迟到转写绝不许写进 UI", "", service.uiState.value.userTranscript)

        // 重新授权：只补建一个**新**的输入链路，旧实例绝不复用。
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(service.refreshSessionPermissions(conversationId))
        pumpMain { asrInstances.size == 2 && asrInstances[1].startCount == 1 }

        assertEquals("再授予只建一个新实例", 2, asrInstances.size)
        assertEquals("补建只触发一次工厂", 2, asrFactoryCalls)
        assertNotSame("绝不复用撤权期间建出来的旧实例", staleAsr, asrInstances[1])
        assertEquals("旧实例不许被重复 cleanup", 1, staleAsr.cleanupCount)
        assertEquals("新实例还在用：不许被 cleanup", 0, asrInstances[1].cleanupCount)
        assertEquals("补建不许留下上一轮的转写", "", service.uiState.value.userTranscript)
    }

    // ------------------------------------------------------------------
    // §R7-2 补建期间撤权：同样不接受旧结果
    // ------------------------------------------------------------------

    @Test
    fun `a rebuild that loses the microphone while suspended is rejected instead of installed`() {
        // 第一通：有麦克风的纯语音通话，ASR 真的在录。
        service.onBind(null)
        startForegroundCall()
        pumpMain { asrInstances.size == 1 && asrInstances.single().startCount == 1 }
        val firstAsr = asrInstances.single()
        val startedAt = VoiceCallService.startedAtMillis.value
        assertTrue("通话必须真的开场了", startedAt > 0L)

        // 撤权：输入链路立刻停掉并释放。
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(service.refreshSessionPermissions(conversationId))
        assertEquals(1, firstAsr.cleanupCount)
        assertTrue("页面还绑着：降级成绑定模式", service.isBoundOnly())

        // 重新授权 → 补建在飞（ASR 工厂挂起）。
        asrGate = CompletableDeferred()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(service.refreshSessionPermissions(conversationId))
        pumpMain { asrFactoryCalls == 2 }
        assertEquals("补建还没提交时不许有实例", 1, asrInstances.size)

        // 补建期间权限又被撤回，而页面的 refresh 回调还没到（授权状态本身已经变了）。
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        asrGate?.complete(Unit)
        pumpMain { asrInstances.size == 2 }

        val staleRebuild = asrInstances[1]
        assertEquals("撤权之后的补建结果绝不许开始录音", 0, staleRebuild.startCount)
        assertEquals("补建结果必须当场释放（旧代码会把它装成当前链路）", 1, staleRebuild.cleanupCount)
        assertEquals("旧结果不许被当成当前链路去 stop", 0, staleRebuild.stopCount)
        assertTrue("补建的旧结果不许被装成 ready", service.uiState.value.amplitudes.isEmpty())
        assertEquals("", service.uiState.value.userTranscript)
        assertEquals("补建不许重置通话计时", startedAt, VoiceCallService.startedAtMillis.value)

        // 再授予：只多建一个新实例，被丢弃的补建结果绝不复用。
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(service.refreshSessionPermissions(conversationId))
        pumpMain { asrInstances.size == 3 && asrInstances[2].startCount == 1 }

        assertEquals("再授予只补建一个新实例", 3, asrInstances.size)
        assertNotSame("绝不复用被丢弃的补建结果", staleRebuild, asrInstances[2])
        assertEquals("被丢弃的补建结果不许被重复 cleanup", 1, staleRebuild.cleanupCount)
        assertEquals("第一套链路只 cleanup 一次", 1, firstAsr.cleanupCount)
    }

    // ------------------------------------------------------------------
    // §R7-3 清理幂等：挂断 / 复用 / onDestroy 反复收尾只 cleanup 一次
    // ------------------------------------------------------------------

    @Test
    fun `hanging up and destroying the service clean each audio instance exactly once`() {
        service.onBind(null)
        startForegroundCall()
        pumpMain { asrInstances.size == 1 && asrInstances.single().startCount == 1 }
        val asr = asrInstances.single()
        val tts = ttsInstances.single()

        service.endCall()
        assertEquals("挂断必须释放 ASR 一次", 1, asr.cleanupCount)
        assertEquals("挂断必须释放 TTS 一次", 1, tts.cleanupCount)

        // 页面还绑着服务时，onDestroy 会晚到；复用同一个实例承载下一通也会再走一遍收尾。
        // 旧代码用 ::isInitialized 判断，这里会 cleanup 3 次。
        service.endCall()
        assertEquals("重复挂断不许重复 cleanup ASR", 1, asr.cleanupCount)
        assertEquals("重复挂断不许重复 cleanup TTS", 1, tts.cleanupCount)

        controller.destroy()
        assertEquals("onDestroy 的兜底收尾不许重复 cleanup ASR", 1, asr.cleanupCount)
        assertEquals("onDestroy 的兜底收尾不许重复 cleanup TTS", 1, tts.cleanupCount)
    }

    // ==================================================================
    // R2：视频通话的**手动**录音（点一次开始，再点一次结束并发送）
    // ==================================================================

    private val recorderInstances = mutableListOf<FakeRecorder>()
    private val submissions = mutableListOf<Submission>()
    private val cancelledRequests = mutableListOf<Uuid>()

    private data class Submission(
        val parts: List<UIMessagePart>,
        val prompt: String?,
        val promptWithoutAttachments: String?,
        val audio: TransientAudioClip?,
        val endedByLimit: Boolean,
        val wasRequesting: Boolean,
    )

    /** 视频通话 + 相机/麦克风都在：这条路径才允许手动录音。 */
    private fun startVideoCall(
        directSupported: Boolean = true,
        asrConfigured: Boolean = true,
        withMic: Boolean = true,
        unmuteManually: Boolean = true,
    ) {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        if (withMic) shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        // 视频协调器需要整套 ChatService：只替换创建点（相机/礼物逻辑不受影响）。
        service.createVideoCoordinator = { _, _, _, _ -> null }
        service.directAudioSupported = { directSupported }
        service.asrConfigured = { asrConfigured }
        service.createVoiceRecorder = {
            FakeRecorder(
                directory = java.io.File(
                    me.rerere.rikkahub.data.video.TransientAudioClip.directory(app.cacheDir).absolutePath,
                ),
            ).also { recorderInstances += it }
        }
        service.submitCallMessage = { parts, prompt, _, _, promptWithoutAttachments, _, audio ->
            submissions += Submission(
                parts = parts,
                prompt = prompt,
                promptWithoutAttachments = promptWithoutAttachments,
                audio = audio,
                endedByLimit = service.uiState.value.voiceInput.endedByLimit,
                wasRequesting = audio?.currentPhase ==
                    me.rerere.rikkahub.data.video.TransientAudioClip.Phase.REQUESTING,
            )
            QueuedMessage(parts = parts, transientAudio = audio)
        }
        service.cancelCallRequest = { id -> cancelledRequests += id }
        service.onBind(null)
        startForegroundCall(video = true)
        pumpMain { VoiceCallService.startedAtMillis.value > 0L && service.isVideoMode() }
        assertTrue("新视频通话服务真实闭麦", service.uiState.value.isMuted)
        if (unmuteManually) service.toggleMute()
    }

    @Test
    fun `video privacy controls persist on reentry without hanging up or automatic audio`() {
        startVideoCall(unmuteManually = false)
        assertFalse(service.toggleVideoVoiceInput())
        assertEquals(0, recorderInstances.size)
        assertEquals(0, asrInstances.single().startCount)
        // Coordinator creation is stubbed in this fixture: explicitly enter its real runtime.
        VideoCallRuntime.enterVideoCall(conversationId)
        var closed = 0
        val gateway = object : me.rerere.rikkahub.data.video.VideoCallCameraGateway {
            override val available get() = closed == 0
            override val unavailableReason = "closed"
            override suspend fun capture(): me.rerere.rikkahub.data.video.VideoFrame? = null
            override fun closeCamera() { closed++ }
        }
        VideoCallRuntime.registerCamera(conversationId, gateway)
        val startedAt = VoiceCallService.startedAtMillis.value
        service.toggleVideoCamera()
        service.togglePreviewMirror()
        assertEquals(1, closed)
        assertFalse(VideoCallRuntime.cameraAvailable())
        assertFalse(service.uiState.value.isCameraEnabled)
        startForegroundCall(video = true)
        service.refreshSessionPermissions(conversationId)
        service.onHostBackground()
        service.startCall()
        assertTrue(service.uiState.value.isMuted)
        assertFalse(service.uiState.value.isCameraEnabled)
        assertTrue(service.uiState.value.isPreviewMirrored)
        assertEquals(startedAt, VoiceCallService.startedAtMillis.value)
        assertEquals(conversationId, service.boundConversationId())
        service.toggleMute()
        assertFalse(service.uiState.value.isMuted)
        assertEquals(0, recorderInstances.size)
        assertEquals(0, asrInstances.single().startCount)
        assertTrue(service.submitTypedText("相机关着也能说话"))
        pumpMain { submissions.size == 1 }
        assertNull(submissions.single().audio)
        assertFalse(service.uiState.value.isCameraEnabled)
        service.toggleVideoCamera()
        assertTrue(service.uiState.value.isCameraEnabled)
    }

    private fun videoVoice() = service.uiState.value.voiceInput

    /** 等价于"用户在设置页把视频输入方式改成 X"：直接改设置流的当前值（不落盘）。 */
    private fun forceVoiceInputMode(mode: me.rerere.rikkahub.data.video.VideoVoiceInputMode) {
        settings.settingsFlow.value = settings.settingsFlow.value.copy(
            videoCallSetting = settings.settingsFlow.value.videoCallSetting.copy(voiceInputMode = mode),
        )
        service.refreshVideoVoiceInput()
    }

    @Test
    fun `entering a video call never starts capturing on its own`() {
        startVideoCall()

        // 真机要求：进入视频默认不采音，也绝不显示"正在聆听"。
        assertEquals(me.rerere.rikkahub.data.video.VideoVoiceStatus.Idle, videoVoice().status)
        assertEquals("没有用户点击就不许有录音器", 0, recorderInstances.size)
        // ASR 实例会被建出来备用，但**绝不能**自动 start（那等于自动监听/自动发送）。
        assertEquals("视频通话不许自动开始 ASR（自动发送/抢麦）", 0, asrInstances.single().startCount)
        assertTrue(service.uiState.value.amplitudes.isEmpty())
        assertFalse(videoVoice().isRecording)
    }

    @Test
    fun `one tap starts recording and the second tap sends exactly one request`() {
        startVideoCall()

        assertTrue(service.toggleVideoVoiceInput())
        assertEquals(me.rerere.rikkahub.data.video.VideoVoiceStatus.Recording, videoVoice().status)
        assertEquals(me.rerere.rikkahub.data.video.VideoVoiceRoute.DIRECT_AUDIO, videoVoice().route)
        val recorder = recorderInstances.single()
        assertEquals(1, recorder.startCount)
        assertEquals("开始录音前必须先停 TTS（避免录回自己）", 1, ttsInstances.single().stopCount)
        assertTrue("还没点结束：一条请求都不许发", submissions.isEmpty())

        // 第二下 = 结束并发送（不是"取消"）。
        assertTrue(service.toggleVideoVoiceInput())
        assertEquals(1, recorder.stopCount)
        pumpMain { submissions.size == 1 }

        val submission = submissions.single()
        assertNotNull("这一轮必须带上录音", submission.audio)
        val clip = requireNotNull(submission.audio)
        assertTrue("录音文件在提交时必须真实存在", clip.exists)
        assertTrue(
            "提交的文字是诚实的占位（没有伪造逐字稿）",
            submission.parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>().single()
                .text.contains("仅本轮提供"),
        )
        assertFalse(submission.endedByLimit)
        assertEquals(me.rerere.rikkahub.data.video.VideoVoiceStatus.Idle, videoVoice().status)

        // 请求结束：由请求自己释放，文件立刻消失（挂断后不留受管音频）。
        assertTrue(clip.release(fromRequest = true))
        assertFalse(clip.exists)
    }

    @Test
    fun `hitting the sixty second limit still sends the segment and says so`() {
        startVideoCall()
        assertTrue(service.toggleVideoVoiceInput())
        val recorder = recorderInstances.single()

        // 录音器自己到了上限（真实实现里 bytes 到点就停）：没有第二次点击，也必须发送。
        recorder.finishNaturally(reachedLimit = true, durationMs = 60_000L)
        pumpMain { submissions.size == 1 }

        val submission = submissions.single()
        assertNotNull("到上限自动结束的这一段也要发送", submission.audio)
        assertTrue(recorder.reachedLimit)
        // 而且必须**明确告诉用户**（不能悄悄结束）。
        assertNotNull("到上限必须给出提示", service.lastVideoVoiceNotice)
        assertTrue(
            "提示里要写清 60 秒上限",
            service.lastVideoVoiceNotice!!.contains("60"),
        )
        assertEquals(me.rerere.rikkahub.data.video.VideoVoiceStatus.Idle, videoVoice().status)
        submission.audio?.release(fromRequest = true)
    }

    @Test
    fun `closing the microphone cancels the segment instead of submitting it`() {
        startVideoCall()
        assertTrue(service.toggleVideoVoiceInput())
        val recorder = recorderInstances.single()

        service.toggleMute()

        assertTrue(service.uiState.value.isMuted)
        assertEquals("闭麦 = 取消采集", 1, recorder.abortCount)
        assertEquals("闭麦绝不自动提交", 0, submissions.size)
        assertEquals(me.rerere.rikkahub.data.video.VideoVoiceStatus.Idle, videoVoice().status)
        // 闭麦之后仍然可以打字。
        assertTrue(service.submitTypedText("我用打字说"))
        pumpMain { submissions.size == 1 }
        assertNull("打字这一轮没有录音", submissions.single().audio)
    }

    @Test
    fun `audio focus loss cancels the segment instead of sending it`() {
        startVideoCall()
        assertTrue(service.toggleVideoVoiceInput())
        val recorder = recorderInstances.single()

        // 真实录音器在焦点被抢时**不再**发布"Idle + 文件路径"（那会被当成用户说完了），
        // 而是取消这一段并把原因写进 Error —— 这里用替身复现同一条状态流。
        recorder.failForAudioFocusLoss()
        pumpMain { videoVoice().status == me.rerere.rikkahub.data.video.VideoVoiceStatus.Unavailable }

        assertEquals("焦点丢失绝不自动发送", 0, submissions.size)
        val voice = videoVoice()
        assertEquals(me.rerere.rikkahub.data.video.VideoVoiceStatus.Unavailable, voice.status)
        assertTrue("必须给出明确原因：${voice.reason}", voice.reason.contains("占用"))
        assertEquals("面板提示要用同一句原文", voice.reason, service.lastVideoVoiceNotice)
    }

    @Test
    fun `muted recording is refused with a reason instead of silently pretending`() {
        startVideoCall()
        service.toggleMute()
        assertFalse(service.toggleVideoVoiceInput())
        assertEquals(0, recorderInstances.size)
        assertEquals(me.rerere.rikkahub.data.video.VideoVoiceStatus.Idle, videoVoice().status)
        assertTrue(videoVoice().reason.contains("麦克风已关闭"))
    }

    @Test
    fun `revoking the microphone cancels an in flight recording`() {
        startVideoCall()
        assertTrue(service.toggleVideoVoiceInput())
        val recorder = recorderInstances.single()

        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(service.refreshSessionPermissions(conversationId))

        assertEquals(1, recorder.abortCount)
        assertEquals("撤权之后这一段绝不许发出去", 0, submissions.size)
        assertEquals(me.rerere.rikkahub.data.video.VideoVoiceStatus.Unavailable, videoVoice().status)
    }

    @Test
    fun `leaving the page cancels without sending`() {
        startVideoCall()
        assertTrue(service.toggleVideoVoiceInput())
        val recorder = recorderInstances.single()

        service.onHostBackground()

        assertEquals(1, recorder.abortCount)
        assertEquals(0, submissions.size)
        assertEquals(me.rerere.rikkahub.data.video.VideoVoiceStatus.Idle, videoVoice().status)
    }

    @Test
    fun `hanging up releases the recorder and leaves no managed audio`() {
        startVideoCall()
        assertTrue(service.toggleVideoVoiceInput())
        val recorder = recorderInstances.single()
        val directory = me.rerere.rikkahub.data.video.TransientAudioClip
            .directory(app.cacheDir)

        service.endCall()

        assertEquals("挂断必须停掉采集", 1, recorder.abortCount)
        assertEquals("挂断必须释放录音器（只能有一个麦克风实例）", 1, recorder.cleanupCount)
        assertEquals(0, submissions.size)
        assertEquals(me.rerere.rikkahub.data.video.VideoVoiceStatus.Idle, videoVoice().status)
        assertTrue(
            "挂断之后不许留下受管音频",
            (directory.listFiles() ?: emptyArray()).none {
                it.name.startsWith(me.rerere.rikkahub.data.video.TransientAudioClip.FILE_PREFIX)
            },
        )
    }

    /**
     * 用户点 #5 的服务侧接缝：挂断时把"这通视频通话提交过哪些用户消息 id"交给通话卡。
     *
     * 真实链路是 `submitTypedText → beginTurn → dispatchTurn → submitCallMessage`；
     * 队列项 id 就是落库的用户消息 id，写卡时再按同一套回复边界展开助手消息
     * （见 `VideoCallCardLinkTest`）。这里钉的是"服务真的把它们交出去了"，不是 helper 自证。
     */
    @Test
    fun `hanging up a video call hands its message ids to the call card`() {
        startVideoCall()
        var submitted = 0
        val firstId = Uuid.random()
        val secondId = Uuid.random()
        service.submitCallMessage = { parts, _, _, _, _, _, _ ->
            submitted += 1
            QueuedMessage(id = if (submitted == 1) firstId else secondId, parts = parts)
        }
        var capturedVideo: Boolean? = null
        var capturedIds: List<String> = emptyList()
        var capturedTranscriptSize = -1
        service.finishCallInteraction = { _, _, _, transcript, _, video, ids ->
            capturedVideo = video
            capturedIds = ids
            capturedTranscriptSize = transcript.size
        }

        assertTrue(service.submitTypedText("第一句"))
        pumpMain { submitted == 1 }
        assertTrue(service.submitTypedText("第二句"))
        pumpMain { submitted == 2 }

        service.endCall()

        assertEquals("视频通话必须在卡上标记出来", true, capturedVideo)
        assertEquals(
            "提交过的用户消息 id（= 落库消息 id）必须原样交给通话卡",
            listOf(firstId.toString(), secondId.toString()),
            capturedIds,
        )
        assertEquals("真实 transcript 也要一起写进卡里", 2, capturedTranscriptSize)
    }

    /** 纯语音通话一个字节都不改：不标视频、不写任何消息关联。 */
    @Test
    fun `a voice call never writes video message links`() {
        service.onBind(null)
        startForegroundCall()
        pumpMain { VoiceCallService.startedAtMillis.value > 0L }
        var capturedVideo: Boolean? = null
        var capturedIds: List<String>? = null
        service.finishCallInteraction = { _, _, _, _, _, video, ids ->
            capturedVideo = video
            capturedIds = ids
        }

        service.endCall()

        assertEquals(false, capturedVideo)
        assertEquals(emptyList<String>(), capturedIds)
    }

    @Test
    fun `a reused service starts the next call with a clean manual voice state`() {
        startVideoCall()
        assertTrue(service.toggleVideoVoiceInput())
        assertEquals(me.rerere.rikkahub.data.video.VideoVoiceStatus.Recording, videoVoice().status)
        service.endCall()

        // 同一台 bound service 承载下一通：状态必须是安全的 Idle（绝不允许"上一段还在录"）。
        assertTrue(service.startBoundCall(Uuid.random().toString(), video = true))
        pumpMain { service.isVideoMode() }
        assertEquals(me.rerere.rikkahub.data.video.VideoVoiceStatus.Idle, videoVoice().status)
        assertFalse(videoVoice().isRecording)
    }

    @Test
    fun `without direct audio and without asr the button is disabled with the real reason`() {
        startVideoCall(directSupported = false, asrConfigured = false)

        assertFalse(service.toggleVideoVoiceInput())
        assertEquals(me.rerere.rikkahub.data.video.VideoVoiceStatus.Unavailable, videoVoice().status)
        assertTrue(videoVoice().reason.contains("不支持音频直传"))
        assertTrue(videoVoice().reason.contains("没有配置语音识别"))
        assertFalse("不能显示成正在聆听", videoVoice().reason.contains("正在聆听"))
        assertEquals("不可用时绝不许开麦克风", 0, recorderInstances.size)
        // 打字仍然可用（初始化不能因为没 ASR 丢掉文字输入）。
        assertTrue(service.submitTypedText("那我就打字"))
        pumpMain { submissions.size == 1 }
    }

    @Test
    fun `asr mode transcribes first and only then submits text without audio`() {
        startVideoCall(directSupported = true, asrConfigured = true)
        // 用户选了「语音转文字」：即使模型能直传也不许偷偷发音频。
        forceVoiceInputMode(me.rerere.rikkahub.data.video.VideoVoiceInputMode.ASR)
        val asr = asrInstances.single()

        assertTrue(service.toggleVideoVoiceInput())
        assertEquals(1, asr.startCount)
        assertEquals(me.rerere.rikkahub.data.video.VideoVoiceRoute.ASR, videoVoice().route)

        // 第二下 = 结束并转写；转写中重复点按必须被忽略（不许重复提交）。
        assertTrue(service.toggleVideoVoiceInput())
        assertEquals(me.rerere.rikkahub.data.video.VideoVoiceStatus.Transcribing, videoVoice().status)
        assertFalse(service.toggleVideoVoiceInput())
        assertTrue("等最后一段完整文字之前不许发送", submissions.isEmpty())

        asr.emitCompleted("你好，我是用语音说的")
        pumpMain { submissions.size == 1 }

        val submission = submissions.single()
        assertNull("ASR 路线只发真实文字，不发音频", submission.audio)
        assertEquals(
            "你好，我是用语音说的",
            submission.parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>().single().text,
        )
        assertEquals(me.rerere.rikkahub.data.video.VideoVoiceStatus.Idle, videoVoice().status)
    }

    @Test
    fun `an old asr idle never becomes the transcript of a new segment`() {
        startVideoCall(asrConfigured = true)
        forceVoiceInputMode(me.rerere.rikkahub.data.video.VideoVoiceInputMode.ASR)
        val asr = asrInstances.single()

        assertTrue(service.toggleVideoVoiceInput())
        // 上一段 provider 的残留 Idle（带旧文字）在本次还没点结束的时候冒出来：
        // 既不能当成本次结果，也不能抢在用户手动结束之前发送。
        asr.emitStaleIdle("上一段的旧文字")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("旧 Idle 不许触发发送", submissions.isEmpty())
        assertEquals(
            me.rerere.rikkahub.data.video.VideoVoiceStatus.Recording,
            videoVoice().status,
        )

        assertTrue(service.toggleVideoVoiceInput())
        assertTrue("还没拿到最终文字之前不许发送", submissions.isEmpty())
        assertEquals(me.rerere.rikkahub.data.video.VideoVoiceStatus.Transcribing, videoVoice().status)

        asr.emitCompleted("这一段的文字")
        pumpMain { submissions.size == 1 }
        assertEquals(
            "这一段的文字",
            submissions.single().parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>().single().text,
        )
    }

    @Test
    fun `asr without a transcript reports it instead of sending an empty turn`() {
        startVideoCall(asrConfigured = true)
        forceVoiceInputMode(me.rerere.rikkahub.data.video.VideoVoiceInputMode.ASR)
        val asr = asrInstances.single()

        assertTrue(service.toggleVideoVoiceInput())
        assertTrue(service.toggleVideoVoiceInput())
        asr.emitCompleted("   ")
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("空转写不许发一条空消息", submissions.isEmpty())
        assertEquals(me.rerere.rikkahub.data.video.VideoVoiceStatus.Unavailable, videoVoice().status)
        assertTrue(videoVoice().reason.contains("没有识别到内容"))
    }

    @Test
    fun `interrupting cancels the recording and never submits it`() {
        startVideoCall()
        assertTrue(service.toggleVideoVoiceInput())
        val recorder = recorderInstances.single()

        service.requestInterrupt()

        assertEquals(1, recorder.abortCount)
        assertEquals(0, submissions.size)
        assertEquals(me.rerere.rikkahub.data.video.VideoVoiceStatus.Idle, videoVoice().status)
    }

    @Test
    fun `asr wall deadline stops capture then transcription deadline rejects late text`() {
        val waits = mutableListOf<Pair<Long, CompletableDeferred<Unit>>>()
        service.videoVoiceDelay = { ms ->
            val gate = CompletableDeferred<Unit>()
            waits += ms to gate
            gate.await()
        }
        startVideoCall(directSupported = false)
        val asr = asrInstances.single()
        assertTrue(service.toggleVideoVoiceInput())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(60_000L, waits.single().first)
        waits[0].second.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(me.rerere.rikkahub.data.video.VideoVoiceStatus.Transcribing, videoVoice().status)
        assertEquals(1, asr.stopCount)
        assertTrue(service.lastVideoVoiceNotice!!.contains("60"))
        assertEquals(30_000L, waits[1].first)
        waits[1].second.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(me.rerere.rikkahub.data.video.VideoVoiceStatus.Unavailable, videoVoice().status)
        assertTrue(videoVoice().reason.contains("超时"))
        assertEquals(1, asr.cleanupCount)
        asr.emitCompleted("超时后的迟到文字")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(submissions.isEmpty())
    }

    @Test
    fun `cancelled asr deadline cannot stop a later segment`() {
        val waits = mutableListOf<CompletableDeferred<Unit>>()
        service.videoVoiceDelay = {
            CompletableDeferred<Unit>().also { waits += it }.await()
        }
        startVideoCall(directSupported = false)
        assertTrue(service.toggleVideoVoiceInput())
        shadowOf(Looper.getMainLooper()).idle()
        service.cancelVideoVoiceInput()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(service.toggleVideoVoiceInput())
        shadowOf(Looper.getMainLooper()).idle()
        waits.first().complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(me.rerere.rikkahub.data.video.VideoVoiceStatus.Recording, videoVoice().status)
        assertTrue(submissions.isEmpty())
    }

    @Test
    fun `immediate restart uses a new recorder and ignores old completion`() {
        startVideoCall()
        assertTrue(service.toggleVideoVoiceInput())
        val old = recorderInstances.single()
        service.cancelVideoVoiceInput()
        assertEquals(1, old.cleanupCount)
        assertTrue(service.toggleVideoVoiceInput())
        assertEquals(2, recorderInstances.size)
        assertNotSame(old, recorderInstances.last())
        old.finishNaturally(false, 3_000L)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(submissions.isEmpty())
        assertEquals(me.rerere.rikkahub.data.video.VideoVoiceStatus.Recording, videoVoice().status)
        assertTrue(service.toggleVideoVoiceInput())
        pumpMain { submissions.size == 1 }
        submissions.single().audio?.release(fromRequest = true)
    }

    @Test
    fun `recording withdraws old generation but preserves submitted id in call card`() {
        startVideoCall()
        val oldId = Uuid.random()
        service.submitCallMessage = { parts, _, _, _, _, _, _ -> QueuedMessage(id = oldId, parts = parts) }
        assertTrue(service.submitTypedText("旧一轮"))
        pumpMain { service.uiState.value.status == VoiceCallStatus.Processing }
        assertTrue(service.toggleVideoVoiceInput())
        assertEquals(listOf(oldId), cancelledRequests)
        var ids = emptyList<String>()
        service.finishCallInteraction = { _, _, _, _, _, _, linked -> ids = linked }
        service.endCall()
        assertEquals(listOf(oldId.toString()), ids)
    }

    // ------------------------------------------------------------------
    // 测试替身：只实现 provider 接口，不参与任何断言逻辑
    // ------------------------------------------------------------------

    /** 视频通话这一段的录音器替身：只发状态，不碰真实麦克风。 */
    private class FakeRecorder(private val directory: java.io.File) :
        me.rerere.rikkahub.ui.hooks.ManualVoiceRecorder {
        override val state = MutableStateFlow(ASRState())
        override var reachedLimit: Boolean = false
            private set

        var startCount = 0
            private set
        var stopCount = 0
            private set
        var abortCount = 0
            private set
        var cleanupCount = 0
            private set

        private var output: java.io.File? = null
        private var stopRequested = false

        override fun start(onTranscriptChange: (String) -> Unit) {
            startCount += 1
            reachedLimit = false
            stopRequested = false
            directory.mkdirs()
            output = java.io.File.createTempFile(
                me.rerere.rikkahub.data.video.TransientAudioClip.FILE_PREFIX + "fake_", ".wav", directory,
            ).apply { writeBytes(me.rerere.rikkahub.ui.hooks.DirectAudioRecorder.wavHeader(4) + ByteArray(4)) }
            state.value = ASRState(status = ASRStatus.Listening, durationMs = 0L)
        }

        override fun stop() {
            stopCount += 1
            stopRequested = true
            publish(finished = true)
        }

        override fun abort() {
            abortCount += 1
            stopRequested = true
            output?.delete()
            output = null
            state.value = ASRState(status = ASRStatus.Idle)
        }

        override fun cleanup() {
            cleanupCount += 1
        }

        /** 模拟"录到 60 秒上限自己停下"。 */
        fun finishNaturally(reachedLimit: Boolean, durationMs: Long) {
            this.reachedLimit = reachedLimit
            state.value = state.value.copy(durationMs = durationMs)
            publish(finished = true, durationMs = durationMs)
        }

        /**
         * 模拟"音频焦点被别的应用抢走"。
         *
         * 真实实现（`DirectAudioRecorder(cancelOnAudioFocusLoss = true)`）不会发布可用路径，
         * 而是删掉文件并把 [me.rerere.rikkahub.ui.hooks.DirectAudioRecorder.FOCUS_LOST_MESSAGE]
         * 写进 Error——这里照抄这条契约。
         */
        fun failForAudioFocusLoss() {
            output?.delete()
            output = null
            stopRequested = true
            state.value = ASRState(
                status = ASRStatus.Error,
                errorMessage = me.rerere.rikkahub.ui.hooks.DirectAudioRecorder.FOCUS_LOST_MESSAGE,
            )
        }

        private fun publish(finished: Boolean, durationMs: Long = 3_000L) {
            val file = output
            if (!finished || file == null || file.length() == 0L) {
                state.value = ASRState(status = ASRStatus.Idle)
                return
            }
            state.value = ASRState(
                status = ASRStatus.Idle,
                audioFilePath = file.absolutePath,
                durationMs = durationMs,
            )
        }
    }

    private class FakeAsr : CustomAsrState {        override val state = MutableStateFlow(ASRState())
        private val callbacks = mutableListOf<(String) -> Unit>()

        var startCount = 0
            private set
        var stopCount = 0
            private set
        var cleanupCount = 0
            private set

        override fun start(onTranscriptChange: (String) -> Unit) {
            startCount += 1
            callbacks += onTranscriptChange
            state.value = ASRState(status = ASRStatus.Listening)
        }

        override fun stop() {
            stopCount += 1
        }

        override fun cleanup() {
            cleanupCount += 1
        }

        /** 模拟 provider 的迟到回调（旧通话的 ASR 不应影响当前 UI）。 */
        fun emitTranscription(text: String) {
            callbacks.lastOrNull()?.invoke(text)
        }

        /** 停止之后 provider 给出的**最终**文字（手动录音那一段的结论）。 */
        fun emitCompleted(text: String) {
            state.value = ASRState(status = ASRStatus.Idle, transcript = text)
        }

        /** 上一段残留的 Idle：它带着旧文字，但绝不能被当成本次结果。 */
        fun emitStaleIdle(text: String) {
            state.value = ASRState(status = ASRStatus.Idle, transcript = text)
        }
    }

    private class FakeTts : CustomTtsState {
        override val isAvailable = MutableStateFlow(true)
        override val isSpeaking = MutableStateFlow(false)
        override val error = MutableStateFlow<String?>(null)
        override val currentChunk = MutableStateFlow(0)
        override val totalChunks = MutableStateFlow(0)
        override val playbackState = MutableStateFlow(PlaybackState())

        var cleanupCount = 0
            private set
        var stopCount = 0
            private set

        override fun speak(text: String, flushCalled: Boolean) = Unit
        override fun stop() {
            stopCount += 1
        }
        override fun pause() = Unit
        override fun resume() = Unit
        override fun skipNext() = Unit
        override fun fastForward(ms: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun cleanup() {
            cleanupCount += 1
        }

        override fun enqueueText(text: String) = Unit
    }
}
