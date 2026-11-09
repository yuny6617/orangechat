/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通话能力状态机的**顺序**行为（REVIEW_R5 §5）。
 *
 * 这不是"再查一张纯函数表"：`CallCapabilityState` 是 `VoiceCallService` 里真正用来决定
 * 要不要刷新的那个对象（前台是否在跑、声明了哪些类型、语音链路建好没有），
 * 所以这里按真实顺序驱动——补授权 → 刷新一次 → 重复调用幂等 → 权限撤回 → 降级 → 再授权。
 */
class VideoCallCapabilityStateTest {
    private val camera = CallForegroundCapability.Camera
    private val microphone = CallForegroundCapability.Microphone

    @Test
    fun `a camera only foreground call builds the audio pipeline exactly once when the mic arrives`() {
        val state = CallCapabilityState()
        // camera-only 的视频通话：前台已经在跑，只声明了 CAMERA。
        state.onForegroundRunning(setOf(camera))
        // 用户补授麦克风：必须刷新（加 MICROPHONE 类型 + 补建 ASR/TTS）。
        assertEquals(
            VideoCallRefreshAction.RefreshSession,
            state.refreshAction(cameraGranted = true, micGranted = true, videoMode = true),
        )
        // 服务真的 apply 之后：类型刷新 + 语音链路建好。
        state.onForegroundRunning(setOf(camera, microphone))
        state.setAudioPipelineReady(true)
        // 幂等：页面重组、权限回调重复触发多少次都什么都不做（不重复初始化）。
        repeat(5) {
            assertEquals(
                VideoCallRefreshAction.None,
                state.refreshAction(cameraGranted = true, micGranted = true, videoMode = true),
            )
        }
    }

    @Test
    fun `revoking the microphone shrinks the type and revoking both drops the foreground service`() {
        val state = CallCapabilityState()
        state.onForegroundRunning(setOf(camera, microphone))
        state.setAudioPipelineReady(true)

        // 麦克风被撤回：前台类型必须减回 CAMERA。
        assertEquals(
            VideoCallRefreshAction.RefreshSession,
            state.refreshAction(cameraGranted = true, micGranted = false, videoMode = true),
        )
        state.onForegroundRunning(setOf(camera))
        assertEquals(
            VideoCallRefreshAction.None,
            state.refreshAction(cameraGranted = true, micGranted = false, videoMode = true),
        )

        // 相机也被撤回：一个前台能力都没有，不能继续挂着 FGS。
        assertEquals(
            VideoCallRefreshAction.DropForeground,
            state.refreshAction(cameraGranted = false, micGranted = false, videoMode = true),
        )
        // 服务降级成绑定模式之后：
        state.onForegroundStopped()
        assertFalse(state.isForegroundRunning())
        assertTrue(state.declaredCapabilities().isEmpty())
        // 再授权仍然可以升回前台（状态没有被"一次性"锁死）。
        assertEquals(
            VideoCallRefreshAction.RefreshSession,
            state.refreshAction(cameraGranted = true, micGranted = true, videoMode = true),
        )
    }

    @Test
    fun `a bound session is refreshable and a finished call starts from scratch`() {
        val state = CallCapabilityState()
        // 绑定模式（还没有前台）：一有权限就要升级。
        assertEquals(
            VideoCallRefreshAction.RefreshSession,
            state.refreshAction(cameraGranted = true, micGranted = false, videoMode = true),
        )
        state.onForegroundRunning(setOf(camera))
        state.setAudioPipelineReady(false)
        // 挂断 / 换通话：服务把状态归零，下一次通话从"没有任何能力"重新开始。
        state.reset()
        assertEquals(
            VideoCallRefreshAction.DropForeground,
            state.refreshAction(cameraGranted = false, micGranted = false, videoMode = true),
        )
        assertEquals(
            VideoCallRefreshAction.RefreshSession,
            state.refreshAction(cameraGranted = false, micGranted = true, videoMode = true),
        )
        assertFalse(state.isForegroundRunning())
        assertTrue(state.declaredCapabilities().isEmpty())
        assertFalse(state.isAudioPipelineReady())
    }

    // ------------------------------------------------------------------
    // REVIEW_R6 §2：初始化在飞时不许被重复刷新取消/重启
    // ------------------------------------------------------------------

    @Test
    fun `an in flight initialization is not restarted by repeated refreshes`() {
        val state = CallCapabilityState()
        state.onForegroundRunning(setOf(microphone))
        // 第一次：语音链路还没建好 → 必须补建一次。
        assertEquals(
            VideoCallRefreshAction.RefreshSession,
            state.refreshAction(cameraGranted = false, micGranted = true, videoMode = false),
        )
        state.beginInitialization(epoch = 1L, targetCapabilities = setOf(microphone))
        assertTrue(state.isInitializationInFlight())
        // 初始化还在飞（suspend 的 provider 工厂）：页面重组/权限回调重复刷新都不许重启。
        repeat(5) {
            assertEquals(
                VideoCallRefreshAction.None,
                state.refreshAction(cameraGranted = false, micGranted = true, videoMode = false),
            )
        }
        // 提交成功之后依然是幂等的。
        state.setAudioPipelineReady(true)
        state.endInitialization(1L)
        assertFalse(state.isInitializationInFlight())
        assertEquals(
            VideoCallRefreshAction.None,
            state.refreshAction(cameraGranted = false, micGranted = true, videoMode = false),
        )
    }

    @Test
    fun `a permission change while initializing still asks for a refresh and stale epochs cannot clear it`() {
        val state = CallCapabilityState()
        state.onForegroundRunning(setOf(camera, microphone))
        state.beginInitialization(epoch = 7L, targetCapabilities = setOf(camera, microphone))
        // 能力组合变了（麦克风被撤回）：即使有初始化在飞也必须刷新，
        // 服务要靠它取消旧初始化、按新能力重来。
        assertEquals(
            VideoCallRefreshAction.RefreshSession,
            state.refreshAction(cameraGranted = true, micGranted = false, videoMode = true),
        )
        state.cancelInitialization()
        state.onForegroundRunning(setOf(camera))
        state.setAudioPipelineReady(false)
        state.beginInitialization(epoch = 8L, targetCapabilities = setOf(camera, microphone))
        // 旧代次（7）的结束回调不许清掉新代次（8）的在飞标记。
        state.endInitialization(7L)
        assertTrue("过期代次不许清掉新的 in-flight 标记", state.isInitializationInFlight())
        state.endInitialization(8L)
        assertFalse(state.isInitializationInFlight())
    }

    @Test
    fun `reset clears the in flight initialization of a finished call`() {
        val state = CallCapabilityState()
        state.onForegroundRunning(setOf(microphone))
        state.beginInitialization(epoch = 1L, targetCapabilities = setOf(microphone))
        state.reset()
        assertFalse(state.isInitializationInFlight())
        assertFalse(state.isAudioPipelineReady())
        // 新会话从头开始：有麦克风权限就要求补建。
        assertEquals(
            VideoCallRefreshAction.RefreshSession,
            state.refreshAction(cameraGranted = false, micGranted = true, videoMode = false),
        )
    }
}
