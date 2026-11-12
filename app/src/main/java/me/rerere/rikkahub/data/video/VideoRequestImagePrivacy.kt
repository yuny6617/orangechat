package me.rerere.rikkahub.data.video

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.net.URI

/** Historical camera media remains visible, but is never silently replayed to a provider. */
internal fun isVideoCameraResource(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme.equals("file", ignoreCase = true) && uri.path.split('/').any {
        it == "video_call_frames" || it == "video_call_clips" || it == "video_call_snapshots"
    }
}.getOrDefault(false)

private fun JsonElement.containsCameraResource(): Boolean = when (this) {
    is JsonPrimitive -> isString && isVideoCameraResource(content)
    is JsonObject -> values.any { it.containsCameraResource() }
    is JsonArray -> any { it.containsCameraResource() }
}

/**
 * Run before any transformer can turn a camera file into anonymous base64 or OCR text, and again
 * after decoration/transforms. Metadata can contain complete provider content blocks (including
 * inline image data), so a block carrying a camera source must be discarded together with its data.
 * Unrelated data URIs and ordinary attachments are deliberately retained.
 */
@Suppress("DEPRECATION")
internal fun List<UIMessage>.withoutHistoricalCameraImages(): List<UIMessage> {
    fun clean(parts: List<UIMessagePart>): List<UIMessagePart> = parts.mapNotNull { part ->
        val taintedMetadata = part.metadata?.containsCameraResource() == true
        val metadata = part.metadata.takeUnless { taintedMetadata }
        when (part) {
            is UIMessagePart.Image -> part.takeUnless { isVideoCameraResource(it.url) || taintedMetadata }
            is UIMessagePart.Video -> part.takeUnless { isVideoCameraResource(it.url) || taintedMetadata }
            is UIMessagePart.Audio -> part.takeUnless { isVideoCameraResource(it.url) || taintedMetadata }
            is UIMessagePart.VoiceMessage -> part.takeUnless { isVideoCameraResource(it.url) || taintedMetadata }
            is UIMessagePart.Document -> part.takeUnless { isVideoCameraResource(it.url) || taintedMetadata }
            is UIMessagePart.Tool -> part.copy(output = clean(part.output), metadata = metadata)
            is UIMessagePart.Text -> part.copy(metadata = metadata)
            is UIMessagePart.Reasoning -> part.copy(metadata = metadata)
            is UIMessagePart.ToolCall -> part.copy(metadata = metadata)
            is UIMessagePart.ToolResult -> part.copy(metadata = metadata)
            is UIMessagePart.Search -> part
        }
    }
    return map { it.copy(parts = clean(it.parts)) }
}

/**
 * Last line of defence at the provider boundary for requests that have no camera capability:
 * every [generateText]/[streamText] call - including tool rounds rebuilt from durable history and
 * retry/finalizer turns - is stripped of historical camera media even if a caller forgot to clean
 * the list first. Use [VideoRequestImageAuthorization] only on the authorized live-video request.
 */
internal fun <T : ProviderSetting> Provider<T>.cameraFreeRequests(): Provider<T> {
    val delegate = this
    return object : Provider<T> by delegate {
        override suspend fun generateText(
            providerSetting: T,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk = delegate.generateText(
            providerSetting, messages.withoutHistoricalCameraImages(), params,
        )

        override suspend fun streamText(
            providerSetting: T,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = flow {
            delegate.streamText(providerSetting, messages.withoutHistoricalCameraImages(), params)
                .collect { emit(it) }
        }
    }
}

/**
 * 本轮"确实附带了画面"那句话的原文。
 *
 * 这句话由 [VideoCallPrompts.turnContext] 的 `pictureAttached == true` 分支生成，这里是它唯一的
 * 字面量副本：[synchronizeVideoPictureClaim] 要在**最终 provider 授权点**把它撤回，而提示词的
 * 生成代码不愿被摊在这里改。两边的一致性由 `VideoRequestImagePrivacyTest` 用真实 `turnContext()`
 * 输出钉住——措辞一改，测试立刻红，绝不会静默地改不动。
 */
internal const val VIDEO_PICTURE_ATTACHED_SENTENCE: String =
    "这是视频通话：用户已同意使用相机，本轮附带了当前相机画面。"

/**
 * 整段"附图"说明（含段首标题行）。
 *
 * 撤回时要匹配的是**整段**而不是那一句话：标题行 + "- " 开头的整块只可能由我们自己的
 * `turnContext()` 生成，用户写的文字/自定义赠礼文案几乎不可能整块复制到，从而避免
 * "随手 replace 把用户文字改掉"。
 */
internal val VIDEO_PICTURE_CLAIM_BLOCK: String =
    VideoCallPrompts.SECTION_TITLE + "\n- " + VIDEO_PICTURE_ATTACHED_SENTENCE

/** 撤回画面后写进同一位置的文案：复用既有那句"本轮没有画面"。 */
internal val VIDEO_PICTURE_ABSENT_BLOCK: String =
    VideoCallPrompts.SECTION_TITLE + "\n- " + VideoCallPrompts.NO_PICTURE_THIS_TURN_NOTE

/** 标在"本轮通话上下文"那个 Text part 上的请求作用域标记（不会发给模型，也不会进历史）。 */
private const val VIDEO_TURN_CONTEXT_KEY = "orangechat_video_turn_context"

private fun emptyMetadata(): JsonObject = JsonObject(emptyMap())

private fun UIMessagePart.isRequestScopedVideoContext(): Boolean =
    this is UIMessagePart.Text && metadata?.containsKey(VIDEO_TURN_CONTEXT_KEY) == true

/**
 * 给 `GenerationHandler` 刚刚注入的那段通话上下文打上请求作用域标记。
 *
 * 只认**逐字相等**的那一个 part：注入的就是这个字符串，所以不会把用户写的任何文字误标成
 * 我们的上下文；标记只是 JsonObject 里的一个键，provider 组装请求时不读它。
 */
internal fun List<UIMessage>.markVideoTurnSystemContext(context: String?): List<UIMessage> {
    val injected = context?.takeIf { it.isNotBlank() } ?: return this
    var changed = false
    val marked = map { message ->
        if (message.role != MessageRole.SYSTEM) {
            message
        } else {
            var partChanged = false
            val parts = message.parts.map { part ->
                val text = part as? UIMessagePart.Text
                if (text == null || text.text != injected) {
                    part
                } else {
                    partChanged = true
                    text.copy(
                        metadata = JsonObject(
                            (text.metadata ?: emptyMetadata()) + (VIDEO_TURN_CONTEXT_KEY to JsonPrimitive(true)),
                        ),
                    )
                }
            }
            if (partChanged) {
                changed = true
                message.copy(parts = parts)
            } else {
                message
            }
        }
    }
    return if (changed) marked else this
}

/**
 * 让 system context 与**最终授权结果**保持一致：这一轮实际交付了 0 张画面时，
 * "本轮附带了当前相机画面"必须换成"本轮没有画面"，绝不能反过来。
 *
 * 只改 SYSTEM 消息里我们自己的那一段：
 * 1. 有请求作用域标记时，只改被标记的 part（同一段里用户自定义的赠礼文案原样保留，
 *    因为命中位置是段首标题行 + 附图那一句，且只换第一处）；
 * 2. 没有标记时（暂停/恢复后队列把提示词并进了系统主文，见 `ChatService`），退回按整段
 *    锚点匹配——仍然只碰 SYSTEM、只换第一处，**绝不**碰任何 USER/ASSISTANT 历史消息。
 *
 * 拿到 `pictureDelivered == true` 时原样返回（同一个对象），不做任何复制。
 */
internal fun List<UIMessage>.synchronizeVideoPictureClaim(pictureDelivered: Boolean): List<UIMessage> {
    if (pictureDelivered) return this
    var changed = false
    val rewritten = map { message ->
        if (message.role != MessageRole.SYSTEM) {
            message
        } else {
            val markedContext = message.parts.any { it.isRequestScopedVideoContext() }
            var partChanged = false
            val parts = message.parts.map { part ->
                val text = part as? UIMessagePart.Text
                if (text == null ||
                    (markedContext && !text.isRequestScopedVideoContext()) ||
                    !text.text.contains(VIDEO_PICTURE_CLAIM_BLOCK)
                ) {
                    part
                } else {
                    partChanged = true
                    text.copy(
                        text = text.text.replaceFirst(VIDEO_PICTURE_CLAIM_BLOCK, VIDEO_PICTURE_ABSENT_BLOCK),
                    )
                }
            }
            if (partChanged) {
                changed = true
                message.copy(parts = parts)
            } else {
                message
            }
        }
    }
    return if (changed) rewritten else this
}

/**
 * 授权结果 + 实际插入的图片数量。
 *
 * 数量必须来自"真的插进去了几张"，而不是"准备了几张"：锚点消息缺失、文件校验失败、
 * 或这一轮根本没有临时图时都是 0，system context 必须据此改口。
 */
internal data class AuthorizedVideoImages(
    val messages: List<UIMessage>,
    val imageCount: Int,
)

internal fun List<UIMessage>.authorizeVideoImages(
    urls: List<String>, anchorMessageId: String?, granted: Boolean,
): AuthorizedVideoImages {
    if (!granted || urls.isEmpty() || anchorMessageId.isNullOrBlank()) return AuthorizedVideoImages(this, 0)
    // Duplicate IDs are ambiguous; never guess which message owns the capability.
    val anchors = indices.filter { this[it].role == MessageRole.USER && this[it].id.toString() == anchorMessageId }
    if (anchors.size != 1) return AuthorizedVideoImages(this, 0)
    val index = anchors.single()
    val images = urls.distinct()
    val authorized = mapIndexed { position, message ->
        if (position == index) message.copy(parts = message.parts + images.map { UIMessagePart.Image(it) })
        else message
    }
    return AuthorizedVideoImages(authorized, images.size)
}

internal fun List<UIMessage>.withAuthorizedVideoImages(
    urls: List<String>, anchorMessageId: String?, granted: Boolean,
): List<UIMessage> = authorizeVideoImages(urls, anchorMessageId, granted).messages

/**
 * Rebuild from the camera-free request each time; revocation also removes prepared data URIs.
 *
 * 图片与"本轮有没有画面"的说明共用**同一次**活授权判断：挂断 / 相机换代 / 队列暂停发生在
 * 准备请求之后、provider 调用之前时，两边不可能各说各的。`isGranted` 每次 provider 调用读一次，
 * 且只有它返回 true 时才可能插入图片——token 为 null 时上游根本收集不到相机 URL，
 * 这里也**没有**任何"没有 token 就放过"的兜底。
 */
internal class VideoRequestImageAuthorization(
    private val urls: List<String>,
    private val anchorMessageId: String?,
    private val isGranted: () -> Boolean,
) {
    fun applyTo(cameraFreeMessages: List<UIMessage>): List<UIMessage> {
        val granted = isGranted()
        val authorized = cameraFreeMessages.authorizeVideoImages(urls, anchorMessageId, granted)
        return authorized.messages.synchronizeVideoPictureClaim(
            pictureDelivered = authorized.imageCount > 0,
        )
    }
}

/**
 * 最终 provider 边界：每次 [generateText]/[streamText] 都重新判一次授权，
 * 图片与 system context 从**同一个**判断结果派生（工具多轮、重试、迟到挂断都走这里）。
 */
internal fun <T : ProviderSetting> Provider<T>.withVideoRequestAuthorization(
    authorization: VideoRequestImageAuthorization,
): Provider<T> {
    val delegate = this
    return object : Provider<T> by delegate {
        override suspend fun generateText(
            providerSetting: T,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk = delegate.generateText(
            providerSetting, authorization.applyTo(messages), params,
        )

        override suspend fun streamText(
            providerSetting: T,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = flow {
            delegate.streamText(providerSetting, authorization.applyTo(messages), params)
                .collect { emit(it) }
        }
    }
}
