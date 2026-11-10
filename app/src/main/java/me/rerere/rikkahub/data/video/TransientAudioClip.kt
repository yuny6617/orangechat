/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * 视频通话里**只为当前这一条请求**提供的一段录音。
 *
 * 为什么不是一个 `String path`（用户最担心的是 OOM 与残留文件）：
 *
 * 1. **所有权**：文件从"录好（QUEUED）"到"正在序列化（REQUESTING）"到"删除（RELEASED）"
 *    只能走一次。序列化期间（含工具多步）任何丢弃路径都**不许**删它 —— 那会让请求在
 *    构造 payload 时读到一个已经消失的文件（历史上就是"删早 → 下一轮 read 失败"）；
 * 2. **只删一次**：队列撤回、取消、挂断、请求结束、`onDestroy` 五六条路径都会来收尾，
 *    [release] 是原子的"我来删"，别的人拿到 false 就不再动这个文件；
 * 3. **只认自己的目录**：文件名带前缀 + 构造时校验父目录，[sweepStale] 也只在自家临时目录里扫，
 *    **绝不**按大目录把用户上传/录制的正常语音删掉。
 *
 * 内容本身不在这里：文件是磁盘上的 16k/mono/PCM16 WAV，请求构造时由既有
 * `UIMessagePart.Audio` → provider adapter 编码一次，本类不缓存任何 base64。
 */
class TransientAudioClip private constructor(
    val id: String,
    val file: File,
    val durationMs: Long,
    private val directory: File,
) {
    enum class Phase {
        /** 刚录好 / 在队列里等：可以被队列丢弃。 */
        QUEUED,

        /** 请求已经认领：序列化期间**绝不**删除。 */
        REQUESTING,

        /** 已经被释放（文件已删或本来就没了）。 */
        RELEASED,
    }

    private val phase = AtomicReference(Phase.QUEUED)

    val currentPhase: Phase get() = phase.get()

    /** provider 适配器要的 `file://` 地址（与用户上传音频同一种形式）。 */
    val fileUrl: String get() = "file://${file.absolutePath}"

    val path: String get() = file.absolutePath

    /** 文件是否真的还在（payload 构造前的最后一道检查）。 */
    val exists: Boolean get() = file.isFile && file.length() > 0L

    /** 请求（含工具多步）开始前认领：之后队列侧的任何丢弃都不会删文件。 */
    fun beginRequest(): Boolean = phase.compareAndSet(Phase.QUEUED, Phase.REQUESTING)

    /**
     * 释放所有权并按需删除文件。
     *
     * @param fromRequest true 只能是**请求自己**在结束（正常/失败/取消）时传；
     *        队列/UI 侧的丢弃必须传 false —— 正在序列化的那一段会拿到 false 而**不动文件**。
     * @return true 表示这次调用真的认领了释放（文件由它删除）；false 表示早就释放过了，
     *         或者文件正在被请求使用。
     */
    fun release(fromRequest: Boolean): Boolean {
        while (true) {
            val current = phase.get()
            if (current == Phase.RELEASED) return false
            if (current == Phase.REQUESTING && !fromRequest) return false
            if (phase.compareAndSet(current, Phase.RELEASED)) {
                runCatching { if (file.isFile) file.delete() }
                return true
            }
        }
    }

    /**
     * 每个发送 job 独有的认领凭据；协程体与未启动取消的兜底必须共用这一份。
     * 未认领的 job 只能清理 QUEUED，不能释放另一个 job 正在使用的 REQUESTING。
     */
    class RequestOwnership(private val clip: TransientAudioClip) {
        private var claimed = false
        private var finished = false

        @Synchronized
        fun beginRequest(): Boolean {
            if (finished || claimed) return false
            claimed = clip.beginRequest()
            return claimed
        }

        @Synchronized
        fun release(): Boolean {
            if (finished) return false
            finished = true
            return clip.release(fromRequest = claimed)
        }
    }

    override fun toString(): String = "TransientAudioClip(${file.name}, ${durationMs}ms, ${phase.get()})"

    companion object {
        /** 视频通话临时录音**专属**目录（相对 `cacheDir`）。绝不与用户录音目录混用。 */
        const val DIRECTORY_NAME: String = "video_voice_transient"

        /** 文件名前缀：扫描/清理只认它。 */
        const val FILE_PREFIX: String = "vcvoice_"

        /** 崩溃遗留的判定龄期：远大于"录 60 秒 + 一次请求"。 */
        const val ORPHAN_MIN_AGE_MILLIS: Long = 30 * 60 * 1000L

        /** 自家临时目录。 */
        fun directory(root: File): File = File(root, DIRECTORY_NAME)

        /** 新建一个空文件名（录音器自己写入 WAV，这里只占名字与所有权）。 */
        fun create(directory: File, durationMs: Long = 0L): TransientAudioClip? = runCatching {
            if (!directory.exists() && !directory.mkdirs()) return null
            val file = File(directory, "$FILE_PREFIX${UUID.randomUUID()}.wav")
            TransientAudioClip(
                id = UUID.randomUUID().toString(),
                file = file,
                durationMs = durationMs,
                directory = directory,
            )
        }.getOrNull()

        /**
         * 接管一个**已经写好**的文件（录音器落盘的路径）。
         *
         * 只接受自家目录里的文件：拿别的目录（例如用户上传的语音）来"接管"会被拒绝，
         * 这样就不存在"某个 bug 让清理逻辑删到用户历史录音"的可能。
         */
        fun adopt(file: File, directory: File, durationMs: Long): TransientAudioClip? {
            if (!file.isFile || file.length() <= 0L) return null
            val expected = runCatching { directory.canonicalFile }.getOrNull() ?: return null
            val actual = runCatching { file.canonicalFile.parentFile }.getOrNull() ?: return null
            if (actual != expected) return null
            if (!file.name.startsWith(FILE_PREFIX)) return null
            return TransientAudioClip(
                id = UUID.randomUUID().toString(),
                file = file,
                durationMs = durationMs,
                directory = directory,
            )
        }

        /**
         * 删掉一段**还没被接管**的录音文件（录音刚写完、还没来得及建 clip 就被取消）。
         *
         * 只删自家目录里的文件；别的目录（用户正常录音）一律不碰。
         */
        fun discardFile(file: File, directory: File) {
            val expected = runCatching { directory.canonicalFile }.getOrNull() ?: return
            val actual = runCatching { file.canonicalFile.parentFile }.getOrNull() ?: return
            if (actual != expected) return
            if (!file.name.startsWith(FILE_PREFIX)) return
            runCatching { if (file.isFile) file.delete() }
        }

        /**
         * 应用崩溃/进程被杀之后的安全清理：只删自家目录里足够旧的文件。
         *
         * 不用"整目录删"：同一时刻可能还有别的请求正在使用其中一段音频。
         * 也不扫用户录音目录（`filesDir/voice_messages`）：那些是正常的聊天录音。
         */
        fun sweepStale(directory: File, minAgeMillis: Long = ORPHAN_MIN_AGE_MILLIS) {
            runCatching {
                if (!directory.isDirectory) return@runCatching
                val now = System.currentTimeMillis()
                directory.listFiles()?.forEach { candidate ->
                    if (!candidate.isFile) return@forEach
                    if (!candidate.name.startsWith(FILE_PREFIX)) return@forEach
                    if (now - candidate.lastModified() >= minAgeMillis) candidate.delete()
                }
            }
        }
    }
}
