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
 * 往"常驻历史"里追加一行，并做**有界截断**（纯函数，便于单测）。
 *
 * 真机反馈：面板上原来只有 6 条、6 秒就淡出的气泡，用户没法回看刚说过什么。改成常驻列表
 * 之后立刻多出一个新风险——长通话会无限攒字符串。所以截断规则写死在这里：
 *
 * - 保留原有顺序与稳定 id（LazyColumn 的 key 靠它，不能重新生成）；
 * - 超过 [limit] 条时**从最旧的开始丢**，永远保留最新的 [limit] 条；
 * - `limit <= 0` 直接返回空列表（调用方写错上限时不至于无限增长）。
 *
 * 真正持久的那份仍然是通话记录卡里的 transcript，这里只是显示缓存。
 */
fun List<DanmakuLine>.appendBounded(line: DanmakuLine, limit: Int): List<DanmakuLine> {
    if (limit <= 0) return emptyList()
    val appended = this + line
    return if (appended.size <= limit) appended else appended.subList(appended.size - limit, appended.size)
}

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
