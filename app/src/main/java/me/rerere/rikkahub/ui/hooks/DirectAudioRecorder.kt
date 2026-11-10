package me.rerere.rikkahub.ui.hooks

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * 手动录制"一段"的录音器契约。
 *
 * [DirectAudioRecorder] 是生产实现；视频通话的服务集成回归注入自己的替身，
 * 这样"点一次开始 / 再点一次结束并发送 / 到上限自动结束 / 取消不发送 / 挂断清理"
 * 全部能在 JVM 里跑真实的服务代码，而不用真的打开麦克风。
 */
internal interface ManualVoiceRecorder : CustomAsrState {
    /** 这一段是否因为到达上限而自己结束（调用方必须明确提示用户）。 */
    val reachedLimit: Boolean

    /** 取消这一段：停止采集，并且**删掉**已经写出的文件（不发布可用路径）。 */
    fun abort()
}

/**
 * Disk-backed PCM/WAV recording; no transcription API or growing in-memory audio buffer.
 *
 * 录制上限与输出目录都是**参数**（视频通话手动录音用 60 秒 + 自家临时目录；
 * 聊天输入框保持原来的 300 秒 + `filesDir/voice_messages` 不变）。
 * 上限同时限制**字节数**：即使时长参数被调大，也不会写出一个巨大文件。
 */
internal class DirectAudioRecorder(
    context: Context,
    /** 这一段最长录多久；到点自动结束（结束由调用方按"已收到 Idle + 文件路径"处理）。 */
    private val maxDurationMs: Int = DEFAULT_MAX_DURATION_MS,
    /** 录音落盘目录；null 表示聊天输入框的默认目录（`filesDir/voice_messages`）。 */
    private val outputDirectory: File? = null,
    /** 文件名前缀（视频通话用自己的前缀，方便只清理自己的临时文件）。 */
    private val filePrefix: String = "voice_",
    /**
     * 音频焦点被抢（来电 / 另一个通话 / 别的应用开麦）时怎么处理这一段。
     *
     * - `false`（聊天输入框的既有行为，也是默认值）：只停止采集，与"用户点停"完全一样，
     *   已经录到的部分照常可用——这正是旧实现 `if (it < 0) stop()` 的语义，**不改变**；
     * - `true`（视频通话手动录音）：**取消**这一段——文件删掉、不发布路径，并把状态置为
     *   [ASRStatus.Error] 带上明确原因，调用方据此提示"被别的应用占用，这段没有发送"。
     *   焦点丢失绝不能走普通 stop 的另一半原因：视频直传路线会把"已收到 Idle + 有文件路径"
     *   当成用户说完了，从而把一个被中断的半段录音自动发出去。
     */
    private val cancelOnAudioFocusLoss: Boolean = false,
) : ManualVoiceRecorder {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(ASRState(isAvailable = true))
    override val state = mutableState.asStateFlow()
    private var job: Job? = null
    @Volatile private var stopRequested = false
    @Volatile private var discardOutput = false
    @Volatile private var disposed = false
    /** 这一段是否因为到达上限而自己结束的（调用方据此提示"已录满"）。 */
    @Volatile
    override var reachedLimit: Boolean = false
        private set
    private val maxBytes: Int = maxDataBytes(maxDurationMs)

    /** 这一段是否因为**音频焦点被抢**而作废（区别于用户点停/到达上限）。 */
    @Volatile private var focusLost = false

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build())
        .setOnAudioFocusChangeListener { change ->
            if (change >= 0) return@setOnAudioFocusChangeListener
            if (cancelOnAudioFocusLoss) cancelForAudioFocusLoss() else stop()
        }
        .build()

    @SuppressLint("MissingPermission") // ChatInput requests RECORD_AUDIO before start.
    override fun start(onTranscriptChange: (String) -> Unit) {
        if (disposed || job?.isActive == true) return
        stopRequested = false
        discardOutput = false
        focusLost = false
        reachedLimit = false
        mutableState.value = ASRState(status = ASRStatus.Connecting, isAvailable = true)
        job = scope.launch {
            var recorder: AudioRecord? = null
            var output: File? = null
            var completed = false
            var ownsVideoCapture = false
            try {
                if (cancelOnAudioFocusLoss) {
                    videoCaptureMutex.lock()
                    ownsVideoCapture = true
                }
                ensureActive()
                check(audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    "无法获得麦克风音频焦点，请结束其他通话后重试"
                }
                val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minBuffer > 0) { "设备不支持此录音格式" }
                val activeRecorder = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, 4096))
                recorder = activeRecorder
                check(activeRecorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
                val directory = outputDirectory
                    ?: File(context.filesDir, "voice_messages")
                directory.mkdirs()
                output = File.createTempFile(filePrefix, ".wav", directory)
                var bytes = 0
                RandomAccessFile(output, "rw").use { file ->
                    file.write(wavHeader(0))
                    activeRecorder.startRecording()
                    check(activeRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "无法开始录音" }
                    mutableState.value = ASRState(status = ASRStatus.Listening, isAvailable = true)
                    val buffer = ByteArray(4096)
                    while (!stopRequested && bytes < maxBytes) {
                        ensureActive()
                        val read = activeRecorder.read(buffer, 0, minOf(buffer.size, maxBytes - bytes),
                            AudioRecord.READ_NON_BLOCKING)
                        check(read >= 0) { "录音读取失败 ($read)" }
                        if (read == 0) { delay(10); continue }
                        file.write(buffer, 0, read)
                        bytes += read
                        var peak = 0
                        for (i in 0 until read - 1 step 2) {
                            val sample = ((buffer[i].toInt() and 255) or (buffer[i + 1].toInt() shl 8)).toShort()
                            peak = maxOf(peak, abs(sample.toInt()))
                        }
                        mutableState.value = mutableState.value.copy(
                            durationMs = bytes * 1000L / (SAMPLE_RATE * 2),
                            amplitudes = (mutableState.value.amplitudes + peak / 32768f).takeLast(50),
                        )
                    }
                    // 音频焦点被抢：这一段**作废**，而且必须让调用方看到明确失败。
                    // 绝不能走下面"发布 Idle + 文件路径"的收尾——视频直传路线会把它当成
                    // 用户说完了，于是一个被来电/别的通话打断的半段录音会被自动发送。
                    if (focusLost) {
                        // 文件由 finally 的 `if (!completed) output?.delete()` 删掉：
                        // 这里还在 use 块里（句柄未关），不在这里删。
                        mutableState.value = ASRState(
                            status = ASRStatus.Error,
                            isAvailable = true,
                            errorMessage = FOCUS_LOST_MESSAGE,
                        )
                        return@launch
                    }
                    // 到字节上限自己停下来 = 到达录制上限（调用方要明确提示并发送这一段）。
                    reachedLimit = !stopRequested && bytes >= maxBytes
                    mutableState.value = mutableState.value.copy(status = ASRStatus.Stopping)
                    check(bytes > 0) { "录音为空，请重新录制" }
                    file.seek(0)
                    file.write(wavHeader(bytes))
                }
                // Release the microphone/focus before publishing a reusable Idle + path.
                // A collector may immediately begin another segment on seeing completion.
                runCatching { recorder?.stop() }
                recorder?.release()
                recorder = null
                audioManager.abandonAudioFocusRequest(focusRequest)
                ensureActive()
                completed = true
                if (discardOutput) {
                    // 这一段被取消了：文件直接删掉，绝不发布一个可用的路径。
                    output.delete()
                    mutableState.value = ASRState(status = ASRStatus.Idle, isAvailable = true)
                } else {
                    mutableState.value = mutableState.value.copy(status = ASRStatus.Idle,
                        audioFilePath = output.absolutePath)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = ASRState(status = ASRStatus.Error, isAvailable = true,
                    errorMessage = e.message ?: "录音失败")
            } finally {
                runCatching { recorder?.stop() }
                recorder?.release()
                audioManager.abandonAudioFocusRequest(focusRequest)
                if (!completed) output?.delete()
                if (ownsVideoCapture) videoCaptureMutex.unlock()
            }
        }
    }

    override fun stop() { stopRequested = true }

    /**
     * 焦点被抢时的取消路径（只有 [cancelOnAudioFocusLoss] 为 true 的路线会走到）。
     *
     * 与 [abort] 的区别只有一个：它还会把状态收尾成带明确原因的 [ASRStatus.Error]，
     * 调用方据此提示用户"被别的应用占用，这段没有发送"，而不是静默回到 Idle。
     */
    private fun cancelForAudioFocusLoss() {
        focusLost = true
        discardOutput = true
        stopRequested = true
    }

    override fun abort() {
        discardOutput = true
        stopRequested = true
    }
    override fun cleanup() {
        disposed = true
        stopRequested = true
        scope.cancel()
    }

    companion object {
        // Only video segments serialize teardown/startup; ordinary chat behavior is unchanged.
        private val videoCaptureMutex = kotlinx.coroutines.sync.Mutex()
        private const val SAMPLE_RATE = 16000

        /** 聊天输入框的默认上限：5 分钟（保持不变）。视频通话用手动传入的 60 秒。 */
        const val DEFAULT_MAX_DURATION_MS: Int = 300_000

        /** 单段文件的硬上限：10 MB（低于 base64 之后的常见请求上限）。 */
        const val HARD_MAX_BYTES: Int = 10 * 1024 * 1024

        /**
         * 音频焦点被抢时给用户看的原文（视频通话路线）。
         *
         * 必须说清"没有发送"：用户看到的是一段被中断的录音，如果只是回到 Idle，
         * 面板会像"什么也没发生"一样，下一次说话还会接着记录，容易误以为上一段已经发出去了。
         */
        const val FOCUS_LOST_MESSAGE: String = "麦克风被其他应用占用，这一段录音已取消（没有发送）"

        /**
         * 时长 → 允许写入的 PCM 字节数（16k/mono/16bit）。
         *
         * 纯函数，方便单测把"60 秒 → 1920000 字节"这类边界直接钉住；
         * 聊天默认的 300 秒结果仍然是 9600000 字节，与旧常量 [MAX_BYTES] 完全一致。
         */
        internal fun maxDataBytes(durationMs: Int): Int {
            if (durationMs <= 0) return 0
            val bytes = SAMPLE_RATE.toLong() * 2L * durationMs / 1000L
            return bytes.coerceAtMost(HARD_MAX_BYTES.toLong()).toInt()
        }

        internal fun wavHeader(dataBytes: Int): ByteArray = ByteBuffer.allocate(44)
            .order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + dataBytes)
                put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
                putShort(1); putShort(1); putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2)
                putShort(2); putShort(16)
                put("data".toByteArray(Charsets.US_ASCII)); putInt(dataBytes)
            }.array()
    }
}
