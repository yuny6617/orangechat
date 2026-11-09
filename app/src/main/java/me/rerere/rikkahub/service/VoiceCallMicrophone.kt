package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.rikkahub.ui.hooks.CustomAsrState
import me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus

// "要不要继续听"这条纯判断在 VoiceCallListening.kt（纯 Kotlin，可直接 JVM 单测）；
// 这里只负责把它落到真实的 ASR 生命周期上。

/** Owns call recording on the main thread, including callbacks delivered after stop(). */
internal class VoiceCallMicrophone(
    private val asr: CustomAsrState,
    private val scope: CoroutineScope,
    private val onTranscript: (String) -> Unit,
) {
    private var wanted = false
    private var sessionActive = false
    private var observedRecording = false
    private var generation = 0L

    val isListening: Boolean get() = wanted && sessionActive

    fun update(status: VoiceCallStatus, muted: Boolean, keepListeningDuringReply: Boolean) {
        val next = voiceCallShouldListen(status, muted, keepListeningDuringReply)
        val wasWanted = wanted
        wanted = next
        if (!next) {
            if (wasWanted || sessionActive) {
                sessionActive = false
                observedRecording = false
                generation++ // Invalidate before stop: some providers finalize a transcript here.
                asr.stop()
            }
        } else {
            resumeIfPending()
        }
    }

    /** A provider can still be Stopping when a mute/settings toggle is reversed. */
    fun resumeIfPending() {
        if (!wanted || sessionActive || asr.state.value.isRecording) return
        val token = ++generation
        sessionActive = true
        observedRecording = false
        try {
            asr.start { transcript ->
                scope.launch {
                    if (wanted && sessionActive && generation == token) onTranscript(transcript)
                }
            }
        } catch (error: Exception) {
            sessionActive = false
            generation++
            throw error
        }
    }

    /** Ignore the previous session's Idle while the forwarded state catches up with start(). */
    fun completedTranscript(state: ASRState): String? {
        if (!isListening) return null
        if (state.isRecording) {
            if (state.status == ASRStatus.Connecting || state.status == ASRStatus.Listening) {
                observedRecording = true
            }
            return null
        }
        if (!observedRecording || state.status != ASRStatus.Idle) return null
        sessionActive = false
        observedRecording = false
        generation++
        return state.transcript
    }
}
