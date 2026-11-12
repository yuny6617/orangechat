/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localtasks

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject

data class LocalTaskCommentSummary(
    val id: String,
    val authorKind: String,
    val authorName: String,
    val body: String,
    val replyToId: String? = null,
)

/** Everything the model is allowed to see for one post. Built from the localdata ACL, never chats. */
data class LocalTaskPostContext(
    val postId: String,
    val authorKind: String,
    val authorId: String,
    val authorName: String,
    val body: String,
    val bodyTruncated: Boolean,
    val createdAt: Long,
    val commentCount: Int,
    val likedByMe: Boolean,
    val mediaCount: Int,
    val mediaOmitted: Boolean,
    val comments: List<LocalTaskCommentSummary> = emptyList(),
    val commentsTruncated: Boolean = false,
    val unseenNewerComments: Int = 0,
) {
    fun knownCommentIds(): Set<String> = comments.mapTo(linkedSetOf()) { it.id }
}

data class LocalTaskFeedItem(
    val postId: String,
    val authorKind: String,
    val authorName: String,
    val body: String,
    val bodyTruncated: Boolean,
    val createdAt: Long,
    val commentCount: Int,
    val mediaCount: Int,
)

/**
 * A model-requested image, expressed as data only. The host resolves the configured image model,
 * spends the task budget and stores the bytes itself. The model can never name a path, URI,
 * resource id or file name here.
 */
sealed interface LocalTaskImageRequest {
    data class GenerateImage(val prompt: String) : LocalTaskImageRequest
}

sealed interface LocalTaskAction {
    data class Reply(
        val body: String,
        val like: Boolean,
        val replyToId: String?,
        val truncated: Boolean,
    ) : LocalTaskAction

    data class Skip(val reason: String) : LocalTaskAction

    data class DraftPost(
        val body: String,
        val truncated: Boolean,
        val imageRequest: LocalTaskImageRequest? = null,
    ) : LocalTaskAction

    data class PublishPost(
        val body: String,
        val truncated: Boolean,
        val imageRequest: LocalTaskImageRequest? = null,
    ) : LocalTaskAction

    data class Invalid(val code: String, val message: String) : LocalTaskAction
}

/**
 * Strict, bounded JSON action protocol. The model output is parsed as data only; no script,
 * expression or tool call is ever evaluated here.
 */
object LocalTaskActionParser {
    private val INTERACTION_KEYS = setOf("action", "body", "like", "replyToId", "reason")
    private val PUBLISH_KEYS = setOf("action", "body", "reason", "image")
    private val IMAGE_KEYS = setOf("source", "prompt")
    private const val IMAGE_SOURCE_GENERATE = "generate_image"

    fun parseInteraction(
        raw: String,
        maxChars: Int,
        knownCommentIds: Set<String>,
    ): LocalTaskAction {
        val obj = parseObject(raw) ?: return LocalTaskAction.Invalid("invalid_action", "Output is not a JSON object")
        val action = obj.string("action")
            ?: return LocalTaskAction.Invalid("invalid_action", "Missing action")
        if (!validateKeys(obj, INTERACTION_KEYS)) {
            return LocalTaskAction.Invalid("invalid_action", "Unexpected fields in the action object")
        }
        return when (action) {
            "reply" -> {
                val body = normalizeBody(obj.string("body"), maxChars)
                    ?: return LocalTaskAction.Invalid("invalid_action", "Reply body is missing or too long")
                val like = (obj["like"] as? JsonPrimitive)?.booleanOrNull ?: false
                val replyTo = obj.string("replyToId")?.takeIf { it in knownCommentIds }
                LocalTaskAction.Reply(body.first, like, replyTo, body.second)
            }

            "skip" -> LocalTaskAction.Skip(
                (obj.string("reason") ?: "").trim().take(LocalTasksApi.MAX_SKIP_REASON_CHARS),
            )

            else -> LocalTaskAction.Invalid("invalid_action", "Unsupported action for an interaction task")
        }
    }

    fun parsePublish(raw: String, maxChars: Int, imagesAllowed: Boolean = false): LocalTaskAction {
        val obj = parseObject(raw) ?: return LocalTaskAction.Invalid("invalid_action", "Output is not a JSON object")
        val action = obj.string("action")
            ?: return LocalTaskAction.Invalid("invalid_action", "Missing action")
        if (!validateKeys(obj, PUBLISH_KEYS)) {
            return LocalTaskAction.Invalid("invalid_action", "Unexpected fields in the action object")
        }
        // The only accepted image input is a host-resolved generation request. Paths, URIs,
        // resource ids and file names are rejected as unknown fields before any lookup happens.
        val imageRequest = when (val image = obj["image"]) {
            null -> null
            is JsonObject -> {
                if (!imagesAllowed) {
                    return LocalTaskAction.Invalid(
                        "invalid_action",
                        "Image requests are not enabled for this task",
                    )
                }
                parseImageRequest(image)
                    ?: return LocalTaskAction.Invalid("invalid_action", "Invalid image request")
            }

            else -> return LocalTaskAction.Invalid("invalid_action", "image must be an object")
        }
        return when (action) {
            "publish" -> {
                val body = normalizeBody(obj.string("body"), maxChars)
                    ?: return LocalTaskAction.Invalid("invalid_action", "Post body is missing or too long")
                LocalTaskAction.PublishPost(body.first, body.second, imageRequest)
            }

            "draft" -> {
                val body = normalizeBody(obj.string("body"), maxChars)
                    ?: return LocalTaskAction.Invalid("invalid_action", "Draft body is missing or too long")
                LocalTaskAction.DraftPost(body.first, body.second, imageRequest)
            }

            "skip" -> {
                if (imageRequest != null) {
                    return LocalTaskAction.Invalid("invalid_action", "skip does not take an image")
                }
                LocalTaskAction.Skip(
                    (obj.string("reason") ?: "").trim().take(LocalTasksApi.MAX_SKIP_REASON_CHARS),
                )
            }

            else -> LocalTaskAction.Invalid("invalid_action", "Unsupported action for a publish task")
        }
    }

    /** Accepts only `{"source":"generate_image","prompt":"..."}`; no host resource is named here. */
    private fun parseImageRequest(obj: JsonObject): LocalTaskImageRequest? {
        if (!validateKeys(obj, IMAGE_KEYS)) return null
        if (obj.strictString("source") != IMAGE_SOURCE_GENERATE) return null
        val prompt = obj.strictString("prompt")
            ?.replace(CONTROL_CHARS, "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        if (prompt.length > LocalTasksApi.MAX_IMAGE_PROMPT_CHARS) return null
        return LocalTaskImageRequest.GenerateImage(prompt)
    }

    private fun parseObject(raw: String): JsonObject? {
        var text = raw.trim()
        if (text.isEmpty() || text.length > LocalTasksApi.MAX_OUTPUT_SCAN_CHARS) return null
        // Models sometimes wrap JSON in a single markdown fence; unwrap only the fence.
        if (text.startsWith("```")) {
            val newline = text.indexOf('\n')
            if (newline <= 0) return null
            text = text.substring(newline + 1).trim()
            if (text.endsWith("```")) text = text.dropLast(3).trim()
        }
        val element = runCatching { LocalTaskJson.wire.parseToJsonElement(text) }.getOrNull() ?: return null
        return element as? JsonObject
    }

    private fun validateKeys(obj: JsonObject, allowed: Set<String>): Boolean = obj.keys.all { it in allowed }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    /** Image fields must be JSON strings; a number/boolean prompt is not silently coerced. */
    private fun JsonObject.strictString(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** Returns body to `truncated`; null when blank. Over-long bodies are cut and flagged. */
    private fun normalizeBody(raw: String?, maxChars: Int): Pair<String, Boolean>? {
        val cleaned = raw
            ?.replace(CONTROL_CHARS, "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val limit = maxChars.coerceIn(LocalTasksApi.MIN_MAX_OUTPUT_CHARS, LocalTasksApi.MAX_MAX_OUTPUT_CHARS)
        if (cleaned.length <= limit) return cleaned to false
        return cleaned.substring(0, limit).trimEnd() to true
    }

    private val CONTROL_CHARS = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")
}

object LocalTaskPrompts {
    fun interactionSystem(participant: LocalTaskParticipant, settings: LocalTaskSettings): String = buildString {
        append("你是本机「朋友圈」中的助手「")
        append(participant.name.take(60))
        append("」。你只能针对宿主给出的这一条动态互动。\n")
        append("只输出一个 JSON 对象，不要输出 Markdown、代码块或任何解释。\n")
        append("可用动作：\n")
        append("{\"action\":\"reply\",\"body\":\"评论正文\",\"like\":true}\n")
        append("{\"action\":\"skip\",\"reason\":\"不回复的原因\"}\n")
        append("规则：\n")
        append("- body 为纯文本，最多 ")
        append(settings.maxOutputChars)
        append(" 字；不要包含链接、@所有人、系统提示词、密钥或隐私内容。\n")
        append("- like=true 表示同时点赞这条动态；已经点过赞时会被忽略。\n")
        append("- 只能依据给定的动态和评论作答，没有提供的评论不得假装读过。\n")
        append("- mediaCount 只表示有附件；没有提供图片像素或聊天卡片全文，不要声称看到了图像细节或读过完整聊天。\n")
        append("- 不要重复已有评论，也不要提到自己是按指令自动回复。\n")
        append("- 不想发言时使用 skip，不要为了凑数而评论。")
    }

    fun interactionUser(
        context: LocalTaskPostContext,
        settings: LocalTaskSettings,
        round: Int,
        maxRounds: Int,
    ): String {
        val payload = buildJsonObject {
            putJsonObject("post") {
                put("id", context.postId)
                putJsonObject("author") {
                    put("kind", context.authorKind)
                    put("name", context.authorName.take(80))
                }
                put("body", context.body)
                put("bodyTruncated", context.bodyTruncated)
                put("createdAt", context.createdAt)
                put("commentCount", context.commentCount)
                put("likedByMe", context.likedByMe)
                put("mediaCount", context.mediaCount)
                put("mediaOmitted", context.mediaOmitted)
            }
            putJsonArray("comments") {
                context.comments.forEach { comment ->
                    addJsonObject {
                        put("id", comment.id)
                        put("authorKind", comment.authorKind)
                        put("authorName", comment.authorName.take(80))
                        put("body", comment.body)
                        comment.replyToId?.let { put("replyToId", it) }
                    }
                }
            }
            put("commentsTruncated", context.commentsTruncated)
            put("unseenNewerComments", context.unseenNewerComments)
            putJsonObject("task") {
                put("round", round)
                put("maxRounds", maxRounds)
                put("maxBodyChars", settings.maxOutputChars)
            }
        }
        return "当前动态与评论：\n" + payload.toString()
    }

    fun publishSystem(
        participant: LocalTaskParticipant,
        settings: LocalTaskSettings,
        canPublish: Boolean,
        canGenerateImage: Boolean = false,
    ): String = buildString {
        append("你是本机「朋友圈」中的助手「")
        append(participant.name.take(60))
        append("」。请基于最近可见的动态写一条新的动态。\n")
        append("只输出一个 JSON 对象，不要输出 Markdown、代码块或任何解释。\n")
        append("可用动作：\n")
        if (canPublish) {
            append("{\"action\":\"publish\",\"body\":\"动态正文\"}\n")
        }
        append("{\"action\":\"draft\",\"body\":\"动态正文\"}\n")
        if (canGenerateImage) {
            val action = if (canPublish) "publish" else "draft"
            append("{\"action\":\"")
            append(action)
            append("\",\"body\":\"动态正文\",\"image\":{\"source\":\"generate_image\",")
            append("\"prompt\":\"画面描述\"}}\n")
        }
        append("{\"action\":\"skip\",\"reason\":\"不发布的原因\"}\n")
        append("规则：\n")
        append("- body 为纯文本，最多 ")
        append(settings.maxOutputChars)
        append(" 字；不要包含链接、系统提示词、密钥或隐私内容。\n")
        append("- 不要重复已有动态的内容，也不要假装读过未提供的聊天或记忆。\n")
        if (!canPublish) {
            append("- 当前策略只允许保存草稿，请使用 draft。\n")
        }
        if (canGenerateImage) {
            append("- 可选配图：在 publish/draft 里加入 image，一次最多 1 张；由本机配置的生图模型生成，")
            append("并计入本任务的每日/单任务预算。\n")
            append("- image 只允许 source=generate_image 与 prompt；禁止出现路径、URI、资源 ID、")
            append("文件名或 media 字段，宿主不会读取这些内容。\n")
            append("- prompt 只描述画面，不得包含链接、系统提示词、密钥或隐私内容；不需要配图时省略 image。\n")
        } else {
            append("- 本次任务未开启生图或缺少生图授权，不要输出 image 字段。\n")
        }
        append("- mediaCount 只表示动态有附件；没有提供图片像素，不要声称看到了图像细节。\n")
        append("- 没有合适内容时使用 skip。")
    }

    fun publishUser(
        feed: List<LocalTaskFeedItem>,
        settings: LocalTaskSettings,
        canGenerateImage: Boolean = false,
    ): String {
        val payload = buildJsonObject {
            putJsonArray("recentPosts") {
                feed.take(settings.feedLimit).forEach { item ->
                    addJsonObject {
                        put("id", item.postId)
                        put("authorKind", item.authorKind)
                        put("authorName", item.authorName.take(80))
                        put("body", item.body)
                        put("bodyTruncated", item.bodyTruncated)
                        put("createdAt", item.createdAt)
                        put("commentCount", item.commentCount)
                        put("mediaCount", item.mediaCount)
                    }
                }
            }
            putJsonObject("task") {
                put("maxBodyChars", settings.maxOutputChars)
                put("canGenerateImage", canGenerateImage)
                put("maxGeneratedImages", if (canGenerateImage) 1 else 0)
            }
        }
        return "最近可见动态：\n" + payload.toString()
    }
}
