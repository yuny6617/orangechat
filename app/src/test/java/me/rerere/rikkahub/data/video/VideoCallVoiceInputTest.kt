/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频通话"手动录音"的纯策略回归。
 *
 * 这一组钉住真机验收里最容易被"看起来能跑"掩盖的几件事：
 * 1. **默认不采音**（初始状态必须是安全 Idle，绝不是"正在聆听"）；
 * 2. 能力裁决：直传 / ASR / 都不可用时按钮必须明确禁用并给出**准确原因**；
 * 3. 60 秒上限（视频）与聊天默认 300 秒互不影响，并且同时限制文件大小；
 * 4. ASR 路线"stop 之后才收结果 + 启动前的旧 Idle 不算结果"；
 * 5. 直传路线的占位文本（历史里只留文字，且明确写着录音不保留）。
 */
class VideoCallVoiceInputTest {

    // ------------------------------------------------------------------
    // §默认：进入视频页不采音
    // ------------------------------------------------------------------

    @Test
    fun `the default state is a safe idle that never claims to be listening`() {
        val state = VideoVoiceUiState()
        assertEquals(VideoVoiceStatus.Idle, state.status)
        assertNull(state.route)
        assertFalse("默认绝不能在录音", state.isRecording)
        assertFalse("默认不能是转写/发送中", state.isBusy)
        assertTrue("空闲时按钮可点（能力裁决另外决定）", state.isActionable)
        assertEquals(VideoVoicePolicy.MAX_RECORDING_MS, 60_000L)
        assertEquals(VideoVoicePolicy.MAX_RECORDING_MS, state.maxDurationMs)
        // 语音通话那边的状态里 voiceInput 也必须是安全 Idle（纯语音完全不受影响）。
        assertEquals(
            VideoVoiceUiState(),
            me.rerere.rikkahub.ui.pages.voice.VoiceCallUiState().voiceInput,
        )
    }

    // ------------------------------------------------------------------
    // §能力裁决
    // ------------------------------------------------------------------

    @Test
    fun `no microphone permission disables recording with a real reason`() {
        val availability = VideoVoicePolicy.resolve(
            mode = VideoVoiceInputMode.AUTO,
            directAudioSupported = true,
            asrConfigured = true,
            microphoneGranted = false,
        )
        assertFalse(availability.enabled)
        assertNull(availability.route)
        assertTrue(availability.reason.contains("没有麦克风权限"))
        assertTrue("打字必须仍然可用", availability.reason.contains("打字"))
    }

    @Test
    fun `auto prefers direct audio when the model and transport support it`() {
        val availability = VideoVoicePolicy.resolve(
            mode = VideoVoiceInputMode.AUTO,
            directAudioSupported = true,
            asrConfigured = true,
            microphoneGranted = true,
        )
        assertTrue(availability.enabled)
        assertEquals(VideoVoiceRoute.DIRECT_AUDIO, availability.route)
    }

    @Test
    fun `auto falls back to configured asr when direct audio is unsupported`() {
        val availability = VideoVoicePolicy.resolve(
            mode = VideoVoiceInputMode.AUTO,
            directAudioSupported = false,
            asrConfigured = true,
            microphoneGranted = true,
        )
        assertTrue(availability.enabled)
        assertEquals(VideoVoiceRoute.ASR, availability.route)
    }

    @Test
    fun `auto without either capability disables the button and says why`() {
        val availability = VideoVoicePolicy.resolve(
            mode = VideoVoiceInputMode.AUTO,
            directAudioSupported = false,
            asrConfigured = false,
            microphoneGranted = true,
        )
        assertFalse(availability.enabled)
        assertNull(availability.route)
        assertTrue(availability.reason.contains("不支持音频直传"))
        assertTrue(availability.reason.contains("没有配置语音识别"))
        // 绝不允许这种文案出现（它等于在骗用户"正在听"）
        assertFalse(availability.reason.contains("正在聆听"))
    }

    @Test
    fun `explicit asr mode refuses to silently fall back to direct audio`() {
        val missingAsr = VideoVoicePolicy.resolve(
            mode = VideoVoiceInputMode.ASR,
            directAudioSupported = true,
            asrConfigured = false,
            microphoneGranted = true,
        )
        assertFalse("用户选了 ASR 但没有 ASR：必须禁用，不能偷偷直传", missingAsr.enabled)
        assertNull(missingAsr.route)
        assertTrue(missingAsr.reason.contains("还没有配置语音识别"))
        assertTrue("必须给出出路", missingAsr.reason.contains("打字"))

        val configured = VideoVoicePolicy.resolve(
            mode = VideoVoiceInputMode.ASR,
            directAudioSupported = true,
            asrConfigured = true,
            microphoneGranted = true,
        )
        assertTrue("有能力的模型也允许用户改选 ASR", configured.enabled)
        assertEquals(VideoVoiceRoute.ASR, configured.route)
    }

    @Test
    fun `explicit direct mode is disabled when the transport cannot carry audio`() {
        val availability = VideoVoicePolicy.resolve(
            mode = VideoVoiceInputMode.DIRECT,
            directAudioSupported = false,
            asrConfigured = true,
            microphoneGranted = true,
        )
        assertFalse(availability.enabled)
        assertNull(availability.route)
        assertTrue(availability.reason.contains("不支持直接上传录音"))
        assertTrue(availability.reason.contains("语音转文字"))
    }

    // ------------------------------------------------------------------
    // §按钮文案 / 状态
    // ------------------------------------------------------------------

    @Test
    fun `the button label walks through start stop and the busy states`() {
        val direct = VideoVoiceUiState(
            status = VideoVoiceStatus.Idle,
            route = VideoVoiceRoute.DIRECT_AUDIO,
        )
        assertEquals("录一段", VideoVoicePolicy.buttonLabel(direct))
        assertEquals("结束并发送", VideoVoicePolicy.buttonLabel(direct.copy(status = VideoVoiceStatus.Recording)))
        assertEquals("转写中…", VideoVoicePolicy.buttonLabel(direct.copy(status = VideoVoiceStatus.Transcribing)))
        assertEquals("发送中…", VideoVoicePolicy.buttonLabel(direct.copy(status = VideoVoiceStatus.Sending)))
        assertEquals("不可用", VideoVoicePolicy.buttonLabel(direct.copy(status = VideoVoiceStatus.Unavailable)))
        assertEquals(
            "语音转文字",
            VideoVoicePolicy.buttonLabel(direct.copy(route = VideoVoiceRoute.ASR)),
        )
        // 转写中/发送中不可点；录音中可点（那就是"结束并发送"）。
        assertFalse(direct.copy(status = VideoVoiceStatus.Transcribing).isActionable)
        assertFalse(direct.copy(status = VideoVoiceStatus.Sending).isActionable)
        assertTrue(direct.copy(status = VideoVoiceStatus.Recording).isActionable)
        assertFalse(direct.copy(status = VideoVoiceStatus.Unavailable).isActionable)
    }

    @Test
    fun `recording hint shows elapsed against the limit and reasons are surfaced`() {
        val recording = VideoVoiceUiState(
            status = VideoVoiceStatus.Recording,
            route = VideoVoiceRoute.DIRECT_AUDIO,
            elapsedMs = 12_000L,
        )
        assertEquals("12秒 / 60秒", VideoVoicePolicy.hintLabel(recording))
        val unavailable = VideoVoiceUiState(
            status = VideoVoiceStatus.Unavailable,
            reason = "还没有配置语音识别",
        )
        assertEquals("还没有配置语音识别", VideoVoicePolicy.hintLabel(unavailable))
        assertEquals("12秒", VideoVoicePolicy.formatDuration(12_000L))
        assertEquals("1分05秒", VideoVoicePolicy.formatDuration(65_000L))
        assertEquals("1分00秒", VideoVoicePolicy.formatDuration(60_000L))
        assertEquals("0秒", VideoVoicePolicy.formatDuration(-5L))
    }

    // ------------------------------------------------------------------
    // §限时与文件大小（视频 60 秒，聊天默认 300 秒不变）
    // ------------------------------------------------------------------

    @Test
    fun `video recordings are capped at sixty seconds and bounded on disk`() {
        // 16k × 2 字节/帧 × 60 秒
        assertEquals(1_920_000, VideoVoicePolicy.maxDataBytes(60_000L))
        assertEquals(0, VideoVoicePolicy.maxDataBytes(0L))
        assertEquals(0, VideoVoicePolicy.maxDataBytes(-1L))
        // 视频这一段的上限就是 60 秒，不再是聊天语音的 300 秒
        assertTrue(VideoVoicePolicy.maxDataBytes(300_000L) <= VideoVoicePolicy.HARD_MAX_FILE_BYTES)
        // 时长参数被调得很大也不会写出巨型文件
        assertEquals(
            VideoVoicePolicy.HARD_MAX_FILE_BYTES,
            VideoVoicePolicy.maxDataBytes(3_600_000L),
        )
        assertTrue(VideoVoicePolicy.limitReachedNotice().contains("60"))
    }

    // ------------------------------------------------------------------
    // §DIRECT：录音器状态 → 这一段的结论
    // ------------------------------------------------------------------

    @Test
    fun `a finished recording becomes a ready segment and a failure stays explicit`() {
        assertNull("还在录：没有结论", directSegmentResult(ASRState(status = ASRStatus.Listening)))
        assertNull("刚启动：没有结论", directSegmentResult(ASRState(status = ASRStatus.Connecting)))
        assertNull("停下来了但还没有文件：没有结论", directSegmentResult(ASRState(status = ASRStatus.Idle)))
        val ready = directSegmentResult(
            ASRState(status = ASRStatus.Idle, audioFilePath = "/tmp/vcvoice_1.wav", durationMs = 12_000L),
        )
        assertTrue(ready is DirectSegmentResult.Ready)
        assertEquals("/tmp/vcvoice_1.wav", (ready as DirectSegmentResult.Ready).path)
        assertEquals(12_000L, ready.durationMs)
        val failed = directSegmentResult(ASRState(status = ASRStatus.Error, errorMessage = "录音为空"))
        assertTrue(failed is DirectSegmentResult.Failed)
        assertEquals("录音为空", (failed as DirectSegmentResult.Failed).reason)
    }

    // ------------------------------------------------------------------
    // §ASR：stop 之后才收结果，旧 Idle/旧 partial 都不算
    // ------------------------------------------------------------------

    @Test
    fun `asr never takes the stale idle that exists before the segment starts`() {
        val watcher = AsrSegmentWatcher()
        // provider 上一段留下的旧 Idle（带着旧文字）：start 之前的那一次绝不能被当成结果。
        assertNull(watcher.observe(ASRState(status = ASRStatus.Idle, transcript = "上一段的旧文字"), false))
        // 刚 start（还在 Connecting）：没有结果。
        assertNull(watcher.observe(ASRState(status = ASRStatus.Connecting), false))
        // 用户还没点结束：即使 provider 自己冒出一个 Idle 也不许自动发送。
        assertNull(watcher.observe(ASRState(status = ASRStatus.Listening, transcript = "半句"), false))
        assertNull(watcher.observe(ASRState(status = ASRStatus.Idle, transcript = "半句"), false))
    }

    @Test
    fun `asr returns the final transcript only after stop`() {
        val watcher = AsrSegmentWatcher()
        assertNull(watcher.observe(ASRState(status = ASRStatus.Listening), false))
        assertNull(watcher.observe(ASRState(status = ASRStatus.Stopping, transcript = "part"), false))
        val result = watcher.observe(ASRState(status = ASRStatus.Idle, transcript = "  你好呀  "), true)
        assertTrue(result is AsrSegmentResult.Transcript)
        assertEquals("你好呀", (result as AsrSegmentResult.Transcript).text)
        // 结果只给一次：再来的 Idle 不会被重复当成新结果（防止重复提交同一段）。
        assertNull(watcher.observe(ASRState(status = ASRStatus.Idle, transcript = "你好呀"), true))
    }

    @Test
    fun `asr empty and failure are reported instead of sending a fake transcript`() {
        val empty = AsrSegmentWatcher()
        assertNull(empty.observe(ASRState(status = ASRStatus.Listening), false))
        assertEquals(AsrSegmentResult.Empty, empty.observe(ASRState(status = ASRStatus.Idle), true))

        val failed = AsrSegmentWatcher()
        val error = failed.observe(ASRState(status = ASRStatus.Error, errorMessage = "网络错误"), true)
        assertTrue(error is AsrSegmentResult.Failed)
        assertEquals("网络错误", (error as AsrSegmentResult.Failed).reason)
    }

    // ------------------------------------------------------------------
    // §历史占位文本（直传没有逐字稿，也不伪造）
    // ------------------------------------------------------------------

    @Test
    fun `direct recordings leave an honest placeholder instead of a transcript`() {
        val placeholder = VideoVoicePolicy.directVoicePlaceholder(12_000L)
        assertTrue(placeholder.contains("12秒"))
        assertTrue("必须写明只在这一轮提供", placeholder.contains("仅本轮提供"))
        assertTrue("必须写明录音不保留", placeholder.contains("录音不保留"))
        // 绝不伪造逐字稿
        assertFalse(placeholder.contains("逐字"))
        val expired = VideoVoicePolicy.expiredVoiceNote(12_000L)
        assertTrue(expired.contains("12秒"))
        assertTrue(expired.contains("没有发送"))
        assertTrue("不许模型假装听过", expired.contains("不要声称听过"))
        assertTrue("也不许编造内容", expired.contains("不要替用户编造"))
    }

    @Test
    fun `the video prompt separates hearing from seeing`() {
        val hearing = VideoCallPrompts.turnContext(
            VideoCallContext(cameraAvailable = true, audioAttached = true),
        )
        assertTrue(hearing.contains(VideoCallPrompts.AUDIO_ATTACHED_NOTE))
        assertFalse(hearing.contains(VideoCallPrompts.NO_AUDIO_THIS_TURN_NOTE))

        val silent = VideoCallPrompts.turnContext(
            VideoCallContext(cameraAvailable = true, audioAttached = false),
        )
        assertTrue("没有录音时明确说没有", silent.contains(VideoCallPrompts.NO_AUDIO_THIS_TURN_NOTE))
        assertFalse(silent.contains(VideoCallPrompts.AUDIO_ATTACHED_NOTE))
        assertNotNull(VideoCallPrompts.NO_AUDIO_THIS_TURN_NOTE)
    }
}
