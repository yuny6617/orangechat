/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localtasks

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.plugin.localdata.AuthorKind
import me.rerere.rikkahub.plugin.localdata.LocalAssistantDirectory
import me.rerere.rikkahub.plugin.localdata.LocalCommentDto
import me.rerere.rikkahub.plugin.localdata.LocalCommentPageDto
import me.rerere.rikkahub.plugin.localdata.LocalDataActor
import me.rerere.rikkahub.plugin.localdata.LocalDataApi
import me.rerere.rikkahub.plugin.localdata.LocalDataEnvelope
import me.rerere.rikkahub.plugin.localdata.LocalDataErrorDto
import me.rerere.rikkahub.plugin.localdata.LocalDataJson
import me.rerere.rikkahub.plugin.localdata.LocalDataMethods
import me.rerere.rikkahub.plugin.localdata.LocalDataSession
import me.rerere.rikkahub.plugin.localdata.LocalPostDetailDto
import me.rerere.rikkahub.plugin.localdata.LocalPostPageDto

/**
 * Thin host-side gateway to one localdata session. The actor and the assistant directory are always
 * supplied by the host, never by plugin parameters; `assistantAutoPublish` follows the native
 * publishing policy.
 */
class LocalTaskLocalData(
    private val session: LocalDataSession,
    private val directory: LocalAssistantDirectory,
) {
    fun call(
        method: String,
        params: JsonObject,
        actor: LocalDataActor.Assistant,
        settings: LocalTaskSettings,
    ): LocalDataEnvelope {
        val request = buildJsonObject {
            put("version", LocalDataApi.VERSION)
            put("method", method)
            put("params", params)
        }.toString()
        val autoPublish = settings.effectivePolicy(actor.id) == LocalAssistantPolicy.AUTO_PUBLISH
        val raw = runCatching {
            session.call(request, actor, directory, assistantAutoPublish = autoPublish)
        }.getOrElse {
            return LocalDataEnvelope(
                ok = false,
                error = LocalDataErrorDto(LocalTaskErrorCode.UNAVAILABLE.wire, "Local data is unavailable"),
            )
        }
        return runCatching {
            LocalDataJson.json.decodeFromString(LocalDataEnvelope.serializer(), raw)
        }.getOrElse {
            LocalDataEnvelope(
                ok = false,
                error = LocalDataErrorDto(LocalTaskErrorCode.INTERNAL.wire, "Invalid local data response"),
            )
        }
    }

    fun <T> decode(serializer: DeserializationStrategy<T>, envelope: LocalDataEnvelope): T? {
        val data = envelope.data ?: return null
        return runCatching { LocalDataJson.json.decodeFromJsonElement(serializer, data) }.getOrNull()
    }
}

sealed interface LocalTaskLoad {
    data class Post(val context: LocalTaskPostContext) : LocalTaskLoad

    data object Unavailable : LocalTaskLoad

    data class Failed(val code: String, val message: String) : LocalTaskLoad
}

/**
 * Builds the *only* context an assistant task may see: the current post plus a bounded window of
 * its latest visible comments. It never reads private chats, memory, tool results or other posts.
 * Every call goes through localdata with the host-created assistant actor, so the ACL is exactly
 * the same one the plugin page has to obey.
 */
class LocalTaskContextLoader(
    private val data: LocalTaskLocalData,
) {
    fun loadPost(
        actor: LocalDataActor.Assistant,
        postId: String,
        settings: LocalTaskSettings,
    ): LocalTaskLoad {
        val envelope = data.call(
            LocalDataMethods.GET,
            buildJsonObject {
                put("postId", postId)
                put("bodyChars", settings.contextBodyChars)
                put("commentLimit", settings.contextCommentLimit)
                put("order", "newest")
                put("includeMedia", true)
                put("includeChatSnapshot", false)
            },
            actor,
            settings,
        )
        if (!envelope.ok) return mapError(envelope)
        val detail = data.decode(LocalPostDetailDto.serializer(), envelope) ?: return failed()
        val post = detail.post
        // The storage query selects the latest bounded page directly, even on long discussions.
        // Present that page in chronological order; omitted comments are older, never unseen newer ones.
        val window = CommentWindow(
            comments = detail.comments.items.asReversed().map(::summarize),
            truncated = post.commentCount > detail.comments.items.size,
            unseenNewer = 0,
        )
        val body = truncateForPrompt(post.body, settings.contextBodyChars)
        return LocalTaskLoad.Post(
            LocalTaskPostContext(
                postId = post.id,
                authorKind = kindOf(post.author.kind),
                authorId = post.author.id,
                authorName = post.author.name,
                body = body.first,
                bodyTruncated = post.bodyTruncated || body.second,
                createdAt = post.createdAt,
                commentCount = post.commentCount,
                likedByMe = post.likedByMe,
                mediaCount = post.media.size,
                mediaOmitted = post.mediaOmitted,
                comments = window.comments,
                commentsTruncated = window.truncated,
                unseenNewerComments = window.unseenNewer,
            ),
        )
    }

    /** Latest visible posts, used only as inspiration for a publish task. */
    fun loadFeed(actor: LocalDataActor.Assistant, settings: LocalTaskSettings): List<LocalTaskFeedItem> {
        val envelope = data.call(
            LocalDataMethods.LIST,
            buildJsonObject {
                put("limit", settings.feedLimit)
                put("bodyChars", settings.contextBodyChars)
                put("commentPreview", 0)
            },
            actor,
            settings,
        )
        if (!envelope.ok) return emptyList()
        val page = data.decode(LocalPostPageDto.serializer(), envelope) ?: return emptyList()
        return page.items.map { post ->
            val body = truncateForPrompt(post.body, settings.contextBodyChars)
            LocalTaskFeedItem(
                postId = post.id,
                authorKind = kindOf(post.author.kind),
                authorName = post.author.name,
                body = body.first,
                bodyTruncated = post.bodyTruncated || body.second,
                createdAt = post.createdAt,
                commentCount = post.commentCount,
                mediaCount = post.media.size,
            )
        }
    }

    private fun summarize(comment: LocalCommentDto): LocalTaskCommentSummary {
        val body = truncateForPrompt(comment.body, LocalTasksApi.MAX_COMMENT_SUMMARY_CHARS)
        return LocalTaskCommentSummary(
            id = comment.id,
            authorKind = kindOf(comment.author.kind),
            authorName = comment.author.name,
            body = body.first,
            replyToId = comment.replyToId,
        )
    }

    private fun mapError(envelope: LocalDataEnvelope): LocalTaskLoad {
        val code = envelope.error?.code.orEmpty()
        if (code == "not_found" || code == "forbidden") return LocalTaskLoad.Unavailable
        return LocalTaskLoad.Failed(
            code = code.ifEmpty { LocalTaskErrorCode.INTERNAL.wire },
            message = envelope.error?.message ?: "Local data request failed",
        )
    }

    private fun failed(): LocalTaskLoad =
        LocalTaskLoad.Failed(LocalTaskErrorCode.INTERNAL.wire, "Invalid local data response")

    private fun kindOf(kind: AuthorKind): String = when (kind) {
        AuthorKind.USER -> "user"
        AuthorKind.ASSISTANT -> "assistant"
    }

    private data class CommentWindow(
        val comments: List<LocalTaskCommentSummary>,
        val truncated: Boolean,
        val unseenNewer: Int,
    )
}

internal fun truncateForPrompt(value: String, maxChars: Int): Pair<String, Boolean> {
    val cleaned = value.trim()
    if (maxChars <= 0) return "" to cleaned.isNotEmpty()
    if (cleaned.length <= maxChars) return cleaned to false
    return cleaned.substring(0, maxChars).trimEnd() to true
}
