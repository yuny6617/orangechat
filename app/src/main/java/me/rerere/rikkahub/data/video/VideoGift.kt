/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

/**
 * 视频通话礼物档位。
 *
 * 这些礼物是**纯虚拟的视觉事件**：没有任何真实价值、没有余额、没有钱包调用，
 * 也不产生任何支付/扣款行为。档位只决定气泡样式和是否触发全屏原创特效。
 */
enum class VideoGift(
    val id: String,
    val displayName: String,
    val emoji: String,
    val tier: Int,
    val effect: VideoGiftEffect,
) {
    HEART("heart", "小心心", "💗", 1, VideoGiftEffect.Bubble),
    BOUQUET("bouquet", "花束", "💐", 2, VideoGiftEffect.Bubble),
    FIREWORKS("fireworks", "夏日烟火", "🎆", 3, VideoGiftEffect.Fireworks),
    METEOR_SHOWER("meteor", "流星雨", "🌠", 4, VideoGiftEffect.MeteorShower),
    GALAXY_TRAIN("galaxy_train", "银河铁道之夜", "🚂", 5, VideoGiftEffect.GalaxyTrain),
}

enum class VideoGiftEffect {
    /** 低档礼物只要气泡，不强制全屏特效。 */
    Bubble,
    Fireworks,
    MeteorShower,
    GalaxyTrain,
}

/** 全屏特效固定时长（毫秒）。低档不触发全屏特效。 */
const val VIDEO_GIFT_EFFECT_DURATION_MS: Long = 8_200L

/** 全屏特效队列上限：通话里连续送礼不能无限排队。 */
const val VIDEO_GIFT_EFFECT_QUEUE_LIMIT: Int = 3

val VideoGift.isFullScreen: Boolean
    get() = effect != VideoGiftEffect.Bubble

object VideoGiftCatalog {
    val all: List<VideoGift> = VideoGift.entries

    /** 严格白名单：只有这里列出的 id 会被执行。 */
    val ids: Set<String> = all.map { it.id }.toSet()

    fun byId(id: String?): VideoGift? = id?.let { raw ->
        all.firstOrNull { it.id == raw.trim().lowercase() }
    }

    fun displayNameOf(id: String): String = byId(id)?.displayName ?: id

    /** 给模型/文档看的一行清单。 */
    fun promptList(): String = all.joinToString("、") { "${it.id}（${it.displayName}）" }
}

/**
 * `<ws_gift id="heart"/>` / `<ws_vsnap/>` 协议的纯解析逻辑。
 *
 * 严格规则（都被单测覆盖）：
 * - 执行只认**规范完整标签**：带 id 的 self-closing 形式 `<ws_gift id="heart"/>`
 *   （双/单引号均可）或成对形式 `<ws_gift>heart</ws_gift>`；`id="heart-bad"`、
 *   `data-id="heart"`、未自闭合的 `<ws_gift id="heart">` 一律不执行（但仍会被剥离）；
 * - 按**文本顺序**取第一枚，成对形式不再被单独提前遍历；
 * - 任何 `<` 开头、可能是协议前缀的半截片段都先缓冲，流式期间不会漏到界面/TTS；
 * - 解析幂等：同一段文本重复解析得到同样结果。
 */
object VideoGiftProtocol {
    const val GIFT_TAG: String = "ws_gift"
    const val SNAPSHOT_TAG: String = "ws_vsnap"

    /** 白名单直接写进正则：非法 id 连"截断成合法 id"的机会都没有。 */
    private const val GIFT_IDS = "heart|bouquet|fireworks|meteor|galaxy_train"

    /** 规范形式，单个正则保证匹配结果与文本顺序一致。 */
    private val strictGiftTag = Regex(
        listOf(
            "<ws_gift\\s+id\\s*=\\s*\"($GIFT_IDS)\"\\s*/>",
            "<ws_gift\\s+id\\s*=\\s*'($GIFT_IDS)'\\s*/>",
            "<ws_gift\\s*>\\s*($GIFT_IDS)\\s*</ws_gift\\s*>",
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )

    /** 任意形状（含非法/残缺）的协议片段：只用于剥离，绝不执行。 */
    private val pairedGiftTag = Regex("<ws_gift\\s*>\\s*[A-Za-z0-9_]{1,32}\\s*</ws_gift\\s*>", RegexOption.IGNORE_CASE)
    private val anyGiftTag = Regex("<ws_gift(?:\\s+[^<>]*)?/?>|</ws_gift\\s*>", RegexOption.IGNORE_CASE)
    private val anySnapshotTag = Regex("<ws_vsnap(?:\\s+[^<>]*)?/?>|</ws_vsnap\\s*>", RegexOption.IGNORE_CASE)
    private val snapshotComplete = Regex("<ws_vsnap\\s*/>", RegexOption.IGNORE_CASE)
    private val blankRuns = Regex("[ \\t]{2,}")
    private val newlineRuns = Regex("\\n{3,}")

    /** 所有协议标签的开头；用它判断"末尾这半截是不是协议的前缀"。 */
    private val tagStarts = listOf("<ws_gift", "</ws_gift", "<ws_vsnap", "</ws_vsnap")

    /** 代码块围栏：里面的内容不参与协议解析。 */
    private const val FENCE = "```"

    /** 去掉礼物/留影指令，用于显示、TTS 和持久化正文。 */
    fun strip(text: String, streaming: Boolean = false): String {
        // 只要求出现 '<'：'<' / '<w' 这类半截前缀同样要缓冲，不能短路放行。
        if (text.isEmpty() || text.indexOf('<') < 0) return text
        val segments = splitCodeSegments(text)
        if (segments.size == 1) return tidy(stripTags(text, streaming))
        // 有代码块时不整体 tidy：那会改动代码里的空白；代码块内容原样保留。
        return buildString {
            segments.forEach { segment ->
                append(if (segment.code) segment.text else stripTags(segment.text, streaming))
            }
        }
    }

    /**
     * 按文本顺序返回**合法**礼物；非法/残缺标签不产生任何礼物。
     *
     * 代码块里的标签不算协议：模型在写示例代码时不会真的送礼。
     */
    fun gifts(text: String): List<VideoGift> {
        if (text.indexOf('<') < 0) return emptyList()
        return splitCodeSegments(text)
            .filterNot { it.code }
            .flatMap { segment ->
                strictGiftTag.findAll(segment.text)
                    .mapNotNull { match -> match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } }
                    .toList()
            }
            .mapNotNull(VideoGiftCatalog::byId)
            .toList()
    }

    fun firstGift(text: String): VideoGift? = gifts(text).firstOrNull()

    fun requestsSnapshot(text: String): Boolean =
        splitCodeSegments(text).any { !it.code && snapshotComplete.containsMatchIn(it.text) }

    /** 普通正文里的协议处理：标签一律剥离；流式时末尾半截也先藏起来。 */
    private fun stripTags(text: String, streaming: Boolean): String {
        var result = text.replace(pairedGiftTag, " ")
        result = result.replace(anyGiftTag, " ")
        result = result.replace(anySnapshotTag, " ")
        if (streaming) result = dropUnfinished(result)
        return result
    }

    /**
     * 把正文按"是否在 ``` 代码块里"切段。
     *
     * 明确策略：协议只在**普通正文**里生效；代码块（含 ``` 围栏本身）原样保留，
     * 既不剥离也不执行。流式期间未闭合的代码块视为"后面都还在代码里"
     * （宁可少执行一次，也不要把代码里的示例标签执行成一次真实送礼）。
     */
    private fun splitCodeSegments(text: String): List<CodeSegment> {
        if (!text.contains(FENCE)) return listOf(CodeSegment(code = false, text = text))
        val segments = mutableListOf<CodeSegment>()
        var cursor = 0
        var inCode = false
        while (true) {
            val fenceAt = text.indexOf(FENCE, cursor)
            if (fenceAt < 0) {
                if (cursor < text.length) segments += CodeSegment(inCode, text.substring(cursor))
                break
            }
            if (fenceAt > cursor) segments += CodeSegment(inCode, text.substring(cursor, fenceAt))
            val fenceEnd = minOf(fenceAt + FENCE.length, text.length)
            // 围栏本身算代码片段：不参与协议解析，也不会被剥离
            segments += CodeSegment(code = true, text = text.substring(fenceAt, fenceEnd))
            inCode = !inCode
            cursor = fenceEnd
        }
        return segments
    }

    private class CodeSegment(val code: Boolean, val text: String)

    private fun tidy(text: String): String = newlineRuns.replace(
        blankRuns.replace(text, " "),
        "\n\n",
    ).trimEnd()

    /**
     * 流式输出末尾的半截标签：宁可先藏起来，也不要漏半个标签给用户。
     *
     * 判据是"最后一个 '<' 之后还没出现 '>'"，并且这段尾巴是某个协议标签的前缀
     * （`<`、`<w`、`<ws`、`<ws_`、`<ws_gift id="hea`、`</ws_gif` 都算）。
     */
    private fun dropUnfinished(text: String): String {
        val lastOpen = text.lastIndexOf('<')
        if (lastOpen < 0) return text
        val tail = text.substring(lastOpen)
        if (tail.indexOf('>') >= 0) return text
        val looksLikeProtocol = tagStarts.any { start ->
            tail.startsWith(start, ignoreCase = true) || start.startsWith(tail, ignoreCase = true)
        }
        return if (looksLikeProtocol) text.substring(0, lastOpen) else text
    }
}

/**
 * 每条模型回复最多一枚礼物，按消息 ID 去重（重放/重入/重复回调都不会重复执行）。
 *
 * 这里只是**进程内**的一层；跨重放/重新生成的持久去重靠消息上的
 * `UIMessageAnnotation.VideoCallGift`（见 VideoCallOutputTransformer），
 * 所以台账容量淘汰不会让旧回复再送一次。
 */
class VideoGiftLedger(private val capacity: Int = DEFAULT_CAPACITY) {
    private val grantedByMessage = LinkedHashMap<String, VideoGift>()

    /**
     * 记录"这条回复已经发放过这枚礼物"。
     *
     * 解析在 [VideoCallDirectiveSession] 的 Streaming 阶段完成（那是唯一能看到原始流的阶段），
     * 这里只接受已经白名单校验过的 [gift]；null / 已经发过的消息 ID 都返回 null。
     */
    fun grant(messageId: String, gift: VideoGift?): VideoGift? {
        val key = messageId.trim()
        if (key.isEmpty()) return null
        if (gift == null) return null
        if (grantedByMessage.containsKey(key)) return null
        grantedByMessage[key] = gift
        while (grantedByMessage.size > capacity) {
            val oldest = grantedByMessage.keys.firstOrNull() ?: break
            grantedByMessage.remove(oldest)
        }
        return gift
    }

    fun giftOf(messageId: String): VideoGift? = grantedByMessage[messageId.trim()]

    fun granted(): List<VideoGift> = grantedByMessage.values.toList()

    fun clear() = grantedByMessage.clear()

    companion object {
        /** 单次通话内足够大：淘汰只会影响进程内去重，持久注解仍在。 */
        const val DEFAULT_CAPACITY: Int = 512
    }
}

/** 通话内礼物计数：右上数量统计 + 结束通话卡礼物行汇总共用。 */
data class VideoGiftTally(val counts: Map<VideoGift, Int> = emptyMap()) {
    val total: Int
        get() = counts.values.sum()

    val isEmpty: Boolean
        get() = total == 0

    fun countOf(gift: VideoGift): Int = counts[gift] ?: 0

    fun plus(gift: VideoGift): VideoGiftTally =
        copy(counts = counts + (gift to countOf(gift) + 1))

    fun plusAll(gifts: Iterable<VideoGift>): VideoGiftTally = gifts.fold(this) { tally, gift -> tally.plus(gift) }

    /** "小心心 ×2 · 花束 ×1"，按档位排序，空表示没送过。 */
    fun summary(): String = VideoGift.entries.mapNotNull { gift ->
        countOf(gift).takeIf { it > 0 }?.let { "${gift.displayName} ×$it" }
    }.joinToString(" · ")

    companion object {
        fun of(gifts: Iterable<VideoGift>): VideoGiftTally = VideoGiftTally().plusAll(gifts)
    }
}

/** 通话结束后写进结束通话卡的那一行。 */
fun giftSummaryLine(tally: VideoGiftTally): String =
    if (tally.isEmpty) "" else "视频礼物：${tally.summary()}"
