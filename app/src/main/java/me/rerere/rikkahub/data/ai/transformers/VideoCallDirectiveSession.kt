/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import me.rerere.rikkahub.data.video.VideoGift
import me.rerere.rikkahub.data.video.VideoGiftProtocol

/*
 * 视频指令的**请求作用域状态与三阶段策略**。
 *
 * 单独成文件的原因：这一整块不依赖 ai 模块的消息类型，是纯 Kotlin，于是可以在真正的
 * JVM 单测里编译执行（RUN_CORE_TESTS.ps1），而不是靠"括号看起来对"来验收。
 * 消息改写（[applyVideoCallDirectives]）仍然留在 VideoCallDirectives.kt。
 */

/** 真实生成管线里视频指令的三个阶段。 */
internal enum class VideoDirectiveStage {
    /** `transforms`：唯一能看到**模型原始流**的阶段；只记录，**不改正文**。 */
    Streaming,

    /** `visualTransforms`：只影响显示，正文可以在这里剥离（半截标签先藏起来）。 */
    Display,

    /** `onGenerationFinish`：生成结束，真正执行礼物/留影。 */
    Finish,
}

/**
 * 每个阶段到底该做什么。
 *
 * 这是"Streaming 不许改正文"这条约定的**唯一**落点。为什么必须原样返回（读代码验证过）：
 *
 * - **职责上**：Streaming 是唯一能看到模型原始流的阶段（`transforms` 拿到的是
 *   `generateModelTurn` 每次 `handleMessageChunk` 之后的完整累积原文），解析必须基于原文；
 *   而"改正文"是 Display/Finish 的事。
 * - **依赖上**：`transforms(...)` 的输出就是 `onUpdateMessages` 交给上层并**持久化/显示**的那一份，
 *   `generateInternal` 之后还会在它上面继续跑 `visualTransforms`/`onGenerationFinish`。
 *   在 Streaming 里剥离等于让"当前状态"变成被删过的正文，与阶段契约相反：
 *   一旦有调用方（或未来重构）把它当作下一片 delta 的合并基准，跨分片的标签就会被永久拆散。
 *   当前实现的合并基准确实是 raw（`initialMessages` + `handleMessageChunk`），所以旧代码
 *   是"违反契约但侥幸没坏"；这里按契约修好，并由
 *   `VideoCallDirectiveStageTest` 把这张表钉住。
 */
internal data class VideoDirectiveStagePlan(
    /** 读正文解析协议（只有 Streaming 能读到原始流）。 */
    val parse: Boolean,
    /** 把协议标签从正文里剥离。 */
    val strip: Boolean,
    /** 执行礼物/留影。 */
    val execute: Boolean,
    /** 剥离时是否要缓冲末尾的半截标签（显示阶段需要，最终正文不需要）。 */
    val bufferPartialTags: Boolean,
)

/** 阶段 → 职责。表里的每一列都有对应的单测（VideoCallDirectiveStageTest）。 */
internal fun VideoDirectiveStage.plan(): VideoDirectiveStagePlan = when (this) {
    VideoDirectiveStage.Streaming ->
        VideoDirectiveStagePlan(parse = true, strip = false, execute = false, bufferPartialTags = false)
    VideoDirectiveStage.Display ->
        VideoDirectiveStagePlan(parse = false, strip = true, execute = false, bufferPartialTags = true)
    VideoDirectiveStage.Finish ->
        VideoDirectiveStagePlan(parse = false, strip = true, execute = true, bufferPartialTags = false)
}

/**
 * 一条回复的协议**解析结果**（不可变）。
 *
 * 为什么最终阶段不重新解析正文：真实管线 `onUpdateMessages` 里先跑 `transforms(...)`，
 * 那是会改正文的路径；而且流式分片会让"半截标签"出现在某一次回调里。所以解析必须发生在
 * **唯一能看到原始流**的 Streaming 阶段，并把结果冻结成这里的数据；Finish 只消费这个结果。
 *
 * 这也避免了"普通 HTML 里恰好有 `<`"污染协议：Finish 根本不再看正文。
 */
data class VideoDirectiveIntent(
    val messageId: String,
    /** 白名单里的那一枚礼物；null 表示这条回复没有（或没有合法）礼物。 */
    val gift: VideoGift? = null,
    val snapshotRequested: Boolean = false,
) {
    val hasAny: Boolean
        get() = gift != null || snapshotRequested
}

/**
 * 一次生成请求的"待执行指令状态"（请求作用域）。
 *
 * 每个生成轮次（含工具多步的每一轮）各自持有实例，正常结束、取消、打断、挂断都在
 * `try/finally` 里 [clear]。
 */
class VideoCallDirectiveSession {
    private val rawByMessage = LinkedHashMap<String, String>()
    private val intents = LinkedHashMap<String, VideoDirectiveIntent>()

    /**
     * 记录一次 Streaming 阶段的原始输出正文（**未剥离**的累积正文）。
     *
     * 意图按 `||` 合并：任何一次回调看到完整标签都算数，重复回调也不会重复执行。
     */
    @Synchronized
    fun recordStreaming(messageId: String, rawBody: String): VideoDirectiveIntent {
        val key = messageId.trim()
        if (key.isEmpty()) return VideoDirectiveIntent(messageId = messageId)
        rawByMessage[key] = rawBody.takeLast(MAX_RAW_CHARS)
        val previous = intents[key]
        val intent = VideoDirectiveIntent(
            messageId = key,
            gift = previous?.gift ?: VideoGiftProtocol.firstGift(rawBody),
            snapshotRequested = previous?.snapshotRequested == true ||
                VideoGiftProtocol.requestsSnapshot(rawBody),
        )
        intents[key] = intent
        trim()
        return intent
    }

    @Synchronized
    fun intentOf(messageId: String): VideoDirectiveIntent? = intents[messageId.trim()]

    @Synchronized
    fun rawOf(messageId: String): String? = rawByMessage[messageId.trim()]

    @Synchronized
    fun clear() {
        rawByMessage.clear()
        intents.clear()
    }

    @Synchronized
    fun isEmpty(): Boolean = intents.isEmpty()

    val size: Int
        @Synchronized get() = intents.size

    private fun trim() {
        while (intents.size > MAX_TRACKED_MESSAGES) {
            val oldest = intents.keys.firstOrNull() ?: break
            intents.remove(oldest)
            rawByMessage.remove(oldest)
        }
    }

    private companion object {
        const val MAX_TRACKED_MESSAGES = 8
        const val MAX_RAW_CHARS = 16_384
    }
}
