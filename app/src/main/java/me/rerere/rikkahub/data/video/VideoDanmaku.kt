/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

/** 视频面板里逐条上浮的黑玻璃气泡。 */
data class DanmakuLine(
    val id: String,
    val fromUser: Boolean,
    val text: String,
    val atMillis: Long,
)

/**
 * 把流式回复切成"一条一句"的气泡（纯逻辑）。
 *
 * - 只在句末标点后切，避免半句话上浮又立刻改写；
 * - 长句按 [maxLineLength] 折行，避免单条气泡撑爆屏幕；
 * - 一轮结束调用 [flush] 收尾，保证最后一段也能显示。
 */
class DanmakuStream(private val maxLineLength: Int = 90) {
    private var emittedLength = 0

    /** 返回本轮新完成的气泡文本（可能 0 到多条）。 */
    fun push(full: String): List<String> {
        if (full.length <= emittedLength) return emptyList()
        val tail = full.substring(emittedLength)
        val cut = tail.indexOfLast { it in SENTENCE_ENDS }
        if (cut < 0) return emptyList()
        val ready = tail.substring(0, cut + 1)
        emittedLength += cut + 1
        return split(ready)
    }

    /** 生成结束时把剩下的半句也吐出来。 */
    fun flush(full: String): List<String> {
        val rest = if (full.length > emittedLength) full.substring(emittedLength) else ""
        emittedLength = full.length
        return split(rest)
    }

    fun reset() {
        emittedLength = 0
    }

    private fun split(text: String): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        clean.forEach { char ->
            current.append(char)
            if (char in SENTENCE_ENDS && current.isNotBlank()) {
                lines += current.toString().trim()
                current.clear()
            } else if (current.length >= maxLineLength) {
                lines += current.toString().trim()
                current.clear()
            }
        }
        val rest = current.toString().trim()
        if (rest.isNotEmpty()) lines += rest
        return lines.filter { it.isNotEmpty() }
    }

    private companion object {
        const val SENTENCE_ENDS = "。！？!?；;\n"
    }
}
