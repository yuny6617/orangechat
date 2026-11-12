/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import androidx.core.net.toUri
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.plugin.localdata.LocalAssistantDirectory
import me.rerere.rikkahub.plugin.localdata.LocalChatSnapshotDto
import me.rerere.rikkahub.plugin.localdata.LocalCommentDto
import me.rerere.rikkahub.plugin.localdata.LocalAuthorDto
import me.rerere.rikkahub.plugin.localdata.LocalCommentOrder
import me.rerere.rikkahub.plugin.localdata.LocalCommentPageDto
import me.rerere.rikkahub.plugin.localdata.AuthorKind
import me.rerere.rikkahub.plugin.localdata.LocalDataActor
import me.rerere.rikkahub.plugin.localdata.LocalDataApi
import me.rerere.rikkahub.plugin.localdata.LocalDataAssistantRef
import me.rerere.rikkahub.plugin.localdata.LocalDataCapability
import me.rerere.rikkahub.plugin.localdata.LocalDataCursorCodec
import me.rerere.rikkahub.plugin.localdata.LocalDataEnvelope
import me.rerere.rikkahub.plugin.localdata.LocalDataException
import me.rerere.rikkahub.plugin.localdata.LocalDataGrant
import me.rerere.rikkahub.plugin.localdata.LocalDataJson
import me.rerere.rikkahub.plugin.localdata.LocalDataMethods
import me.rerere.rikkahub.plugin.localdata.LocalDataRequestParser
import me.rerere.rikkahub.plugin.localdata.LocalDataSession
import me.rerere.rikkahub.plugin.localdata.LocalDataValidation
import me.rerere.rikkahub.plugin.localdata.LocalMediaDto
import me.rerere.rikkahub.plugin.localdata.LocalPostDetailDto
import me.rerere.rikkahub.plugin.localdata.LocalPostDto
import me.rerere.rikkahub.plugin.localdata.LocalPostPageDto
import me.rerere.rikkahub.plugin.localtasks.LocalAssistantPolicy
import me.rerere.rikkahub.plugin.localtasks.LocalTaskIds
import me.rerere.rikkahub.plugin.localtasks.LocalTaskSettings
import me.rerere.rikkahub.plugin.localtasks.LocalTaskSettingsStore
import me.rerere.rikkahub.plugin.localtasks.SharedPreferencesLocalTaskStore
import me.rerere.rikkahub.plugin.manager.PluginManager

/**
 * Read-only model tools for the host's local Moments data.
 *
 * Trust model:
 *  - The assistant id comes from [ToolInvocationContext], never from tool arguments. Identity-like
 *    parameter keys are rejected by [LocalMomentsArgs], so a model/page cannot claim to be another
 *    assistant.
 *  - The plugin must be installed and enabled, the native `localdata.read` grant must be approved,
 *    and the assistant must be a participant in [LocalTaskSettings]. Image bytes add the native
 *    `allowImages` switch plus a real image-capable model; chat excerpts add the native
 *    `localdata.chat_snapshot` grant.
 *  - Only a normal foreground chat may call these tools (`isHeadless == false`). Headless callers
 *    (workflow/cron/proactive) must mark their [ToolInvocationContext] accordingly.
 */
object LocalMomentsContract {
    const val PLUGIN_ID = "com.orangechat.localmoments"
    const val TOOL_LIST = "moments_list"
    const val TOOL_GET = "moments_get"
    const val TOOL_SEARCH = "moments_search"
    const val TOOL_COMMENTS = "moments_comments"
    const val TOOL_MEDIA_GET = "moments_media_get"
    const val TOOL_CHAT_EXCERPT_GET = "moments_chat_excerpt_get"
}

/** Hard limits. They are deliberately lower than the plugin UI's own page size of 3..20. */
object LocalMomentsLimits {
    const val DEFAULT_PAGE_SIZE = 3
    const val MAX_PAGE_SIZE = 5
    const val DEFAULT_LIST_BODY_CHARS = 220
    const val MAX_LIST_BODY_CHARS = 260
    const val DEFAULT_GET_BODY_CHARS = 600
    const val MAX_GET_BODY_CHARS = 1_000
    const val DEFAULT_COMMENT_LIMIT = 3
    const val MAX_COMMENT_LIMIT = 5
    const val MAX_COMMENT_BODY_CHARS = 160
    const val DEFAULT_EXCERPT_MESSAGES = 10
    const val MAX_EXCERPT_MESSAGES = 20
    const val DEFAULT_EXCERPT_MESSAGE_CHARS = 180
    const val MAX_EXCERPT_MESSAGE_CHARS = 260
    const val MAX_EXCERPT_TOTAL_CHARS = 1_600
    const val MAX_OUTPUT_CHARS = 2_600
    const val OUTPUT_JSON_RESERVE_CHARS = 400
    const val MAX_TOOL_CALLS_PER_TURN = 6
    const val MAX_PAGES_PER_TURN = 4
    const val MAX_SEARCHES_PER_TURN = 3
    const val MAX_MEDIA_METADATA_PER_PAGE = 4
    const val MAX_ATTACHED_IMAGES_PER_CALL = 1
    const val MAX_ATTACHED_IMAGE_BYTES = 1_500_000L
    const val THUMBNAIL_MAX_PX = LocalDataApi.THUMBNAIL_MAX_PX
}

data class LocalMomentsDenial(val code: String, val message: String)

/** Host facts used by the pure ACL helper. */
data class LocalMomentsAccessSnapshot(
    val pluginInstalled: Boolean,
    val pluginEnabled: Boolean,
    val pluginLoadError: String? = null,
    val grantedCapabilities: Set<String> = emptySet(),
    val taskSettings: LocalTaskSettings = LocalTaskSettings(),
    val assistantId: String? = null,
    val assistantKnown: Boolean = false,
    val headless: Boolean = false,
    val modelCanSeeImages: Boolean = false,
)

data class LocalMomentsAccess(
    val denial: LocalMomentsDenial? = null,
    val canReadMedia: Boolean = false,
    val canReadChatSnapshot: Boolean = false,
    val canAttachImages: Boolean = false,
) {
    val allowed: Boolean get() = denial == null
}

/**
 * Pure ACL evaluation. No Android types here so JVM tests can pin every deny path.
 *
 * The native participant list is the read ACL: `LocalTaskSettings.enabled` only controls automatic
 * interaction, so turning it off must not quietly revoke a participant's read tools. A participant
 * with a `disabled` policy, a global stop, or no participant entry is denied.
 */
object LocalMomentsAccessPolicy {
    fun evaluate(snapshot: LocalMomentsAccessSnapshot): LocalMomentsAccess {
        fun deny(code: String, message: String) = LocalMomentsAccess(
            denial = LocalMomentsDenial(code, message),
        )

        if (!snapshot.pluginInstalled) {
            return deny("plugin_not_installed", "本机朋友圈插件未安装，不能读取动态")
        }
        if (!snapshot.pluginEnabled || snapshot.pluginLoadError != null) {
            return deny("plugin_disabled", "本机朋友圈插件未启用，不能读取动态")
        }
        if (LocalDataCapability.READ.wire !in snapshot.grantedCapabilities) {
            return deny("read_not_granted", "宿主未授权本机朋友圈的 localdata.read，不能读取动态")
        }
        if (snapshot.headless) {
            return deny("headless_call", "朋友圈读取工具只允许在用户正常发起的聊天中调用")
        }
        val assistantId = snapshot.assistantId
        if (assistantId.isNullOrBlank() || !LocalDataValidation.isSafeAssistantId(assistantId)) {
            return deny("assistant_identity_missing", "缺少宿主确认的助手身份，不能读取动态")
        }
        if (!snapshot.assistantKnown) {
            return deny("assistant_unknown", "当前助手不在宿主助手列表中，不能读取动态")
        }

        val settings = snapshot.taskSettings.normalized()
        if (settings.globalStop) {
            return deny("assistant_tasks_stopped", "朋友圈助手互动已被全局停止，不能读取动态")
        }
        if (assistantId !in settings.participants) {
            return deny("assistant_not_participant", "当前助手不是朋友圈参与助手，不能读取动态")
        }
        if (settings.effectivePolicy(assistantId) == LocalAssistantPolicy.DISABLED) {
            return deny("assistant_policy_disabled", "当前助手的朋友圈策略为 disabled，不能读取动态")
        }

        val canReadMedia = LocalDataCapability.MEDIA.wire in snapshot.grantedCapabilities
        val canReadChatSnapshot = LocalDataCapability.CHAT_SNAPSHOT.wire in snapshot.grantedCapabilities
        return LocalMomentsAccess(
            denial = null,
            canReadMedia = canReadMedia,
            canReadChatSnapshot = canReadChatSnapshot,
            // Image bytes are a separate native permission and require an actually vision-capable
            // model. Metadata is still available when either condition is false.
            canAttachImages = canReadMedia && settings.allowImages && snapshot.modelCanSeeImages,
        )
    }
}

/**
 * R2-3 regression seam: host ACL facts + the per-turn model capability, nothing from the
 * assistant's default model. ChatService has already resolved the provider and the conversation
 * override into [ToolInvocationContext.modelCanSeeImages]; consulting
 * `assistant.chatModelId ?: settings.chatModelId` here would reject a conversation explicitly
 * switched to a vision model. Android-free so the override regression is JVM-testable.
 */
internal fun localMomentsAccessSnapshot(
    invocationContext: ToolInvocationContext,
    pluginInstalled: Boolean,
    pluginEnabled: Boolean,
    pluginLoadError: String?,
    grantedCapabilities: Set<String>,
    taskSettings: LocalTaskSettings,
    assistantId: String?,
    assistantKnown: Boolean,
): LocalMomentsAccessSnapshot = LocalMomentsAccessSnapshot(
    pluginInstalled = pluginInstalled,
    pluginEnabled = pluginEnabled,
    pluginLoadError = pluginLoadError,
    grantedCapabilities = grantedCapabilities,
    taskSettings = taskSettings,
    assistantId = assistantId,
    assistantKnown = assistantKnown,
    headless = invocationContext.isHeadless,
    modelCanSeeImages = invocationContext.modelCanSeeImages,
)

data class LocalMomentsLimitedText(
    val text: String,
    val truncated: Boolean,
    val originalChars: Int,
)

/** Character based, worst-case token accounting: one output character is charged as one token. */
class LocalMomentsTextBudget(
    val maxChars: Int = LocalMomentsLimits.MAX_OUTPUT_CHARS,
    val reservedChars: Int = LocalMomentsLimits.OUTPUT_JSON_RESERVE_CHARS,
) {
    val usableChars: Int = (maxChars - reservedChars).coerceAtLeast(0)

    var usedChars: Int = 0
        private set

    val remainingChars: Int get() = (usableChars - usedChars).coerceAtLeast(0)
    val truncated: Boolean get() = usedChars >= usableChars

    fun canAfford(chars: Int): Boolean = remainingChars >= chars.coerceAtLeast(0)

    fun charge(chars: Int) {
        usedChars = (usedChars + chars.coerceAtLeast(0)).coerceAtMost(usableChars)
    }

    fun take(value: String, requestedMax: Int): LocalMomentsLimitedText {
        val allowed = minOf(requestedMax.coerceAtLeast(0), remainingChars)
        val text = if (value.length <= allowed) value else value.take(allowed)
        usedChars += text.length
        return LocalMomentsLimitedText(
            text = text,
            truncated = text.length < value.length,
            originalChars = value.length,
        )
    }
}

enum class LocalMomentsCallKind {
    LIST,
    GET,
    SEARCH,
    COMMENTS,
    MEDIA,
    CHAT_EXCERPT,
}

class LocalMomentsBudget(
    private val maxCalls: Int = LocalMomentsLimits.MAX_TOOL_CALLS_PER_TURN,
    private val maxPages: Int = LocalMomentsLimits.MAX_PAGES_PER_TURN,
    private val maxSearches: Int = LocalMomentsLimits.MAX_SEARCHES_PER_TURN,
    val text: LocalMomentsTextBudget = LocalMomentsTextBudget(),
) {
    var calls: Int = 0
        private set
    var pages: Int = 0
        private set
    var searches: Int = 0
        private set

    /** Returns a denial when this call would exceed a hard limit; otherwise consumes the budget. */
    fun beginCall(kind: LocalMomentsCallKind): LocalMomentsDenial? {
        if (calls >= maxCalls) {
            return LocalMomentsDenial(
                "tool_call_budget_exhausted",
                "本轮朋友圈读取调用已达上限（$maxCalls 次），请基于已有结果回答",
            )
        }
        val pageKind = kind == LocalMomentsCallKind.LIST ||
            kind == LocalMomentsCallKind.SEARCH ||
            kind == LocalMomentsCallKind.COMMENTS ||
            kind == LocalMomentsCallKind.CHAT_EXCERPT
        if (pageKind && pages >= maxPages) {
            return LocalMomentsDenial(
                "page_budget_exhausted",
                "本轮朋友圈翻页已达上限（$maxPages 页），请基于已有结果回答",
            )
        }
        if (kind == LocalMomentsCallKind.SEARCH && searches >= maxSearches) {
            return LocalMomentsDenial(
                "search_budget_exhausted",
                "本轮朋友圈搜索已达上限（$maxSearches 次），请基于已有结果回答",
            )
        }
        calls++
        if (pageKind) pages++
        if (kind == LocalMomentsCallKind.SEARCH) searches++
        return null
    }

    fun status(): JsonObject = buildJsonObject {
        put("toolCallsUsed", calls)
        put("toolCallsLimit", maxCalls)
        put("pagesUsed", pages)
        put("pagesLimit", maxPages)
        put("searchesUsed", searches)
        put("searchesLimit", maxSearches)
        put("outputCharsUsed", text.usedChars)
        put("outputCharsLimit", text.usableChars)
        // Worst-case estimator: one CJK/output character is charged as one token.
        put("estimatedOutputTokens", text.usedChars)
        put("outputTokenLimit", text.usableChars)
        put("outputTruncated", text.truncated)
    }
}

/**
 * R2-4: one read budget per **user turn**, not per tool-surface build.
 *
 * A tool approval pauses the assistant turn, and resuming it rebuilds the whole tool surface via
 * ChatService.buildAvailableTools. A budget created inside `build()` would therefore be handed a
 * fresh allowance after every approval, letting the same user task spend 6 reads before the
 * approval and another 6 after it. The registry keeps the same [LocalMomentsBudget] instance for
 * as long as the conversation's newest user message is unchanged, so generation, tool-loop
 * rebuilds and approval resumes all charge the same counters.
 *
 * Scope key is `conversationId + latest USER message uuid`. A new user message in the same
 * conversation discards the previous budget (`每个对话新 anchor 清理旧预算`). The map is an
 * access-ordered LRU bounded by conversation count, so dead conversations cannot accumulate
 * budgets. It is deliberately not a wall-clock TTL: a user may legitimately take minutes to
 * answer an approval, and time-based expiry would silently reopen the budget mid-turn.
 *
 * Owned by ChatService (one instance per service, i.e. once per process), not a global singleton.
 * Android-free so the cross-build / approval behaviour is JVM-testable.
 */
class LocalMomentsBudgetRegistry(
    private val maxConversations: Int = 32,
) {
    private data class Entry(val anchorUserId: String, val budget: LocalMomentsBudget)

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    /** Returns the budget for [conversationId]'s current user turn, creating/replacing as needed. */
    fun budgetFor(conversationId: String, anchorUserId: String): LocalMomentsBudget =
        synchronized(entries) {
            val existing = entries[conversationId]
            if (existing != null && existing.anchorUserId == anchorUserId) {
                existing.budget
            } else {
                LocalMomentsBudget().also { fresh ->
                    entries[conversationId] = Entry(anchorUserId, fresh)
                    // Access-ordered map: first entry is the least recently used conversation.
                    while (entries.size > maxConversations) {
                        val eldest = entries.keys.firstOrNull() ?: break
                        entries.remove(eldest)
                    }
                }
            }
        }

    /** Visible for tests: number of conversations currently holding a budget. */
    internal fun trackedConversations(): Int = synchronized(entries) { entries.size }

    /** Drop a deleted conversation's budget immediately instead of waiting for LRU eviction. */
    fun remove(conversationId: String) {
        synchronized(entries) { entries.remove(conversationId) }
    }

    /** Drop every budget. Used when the owning service shuts down. */
    fun clear() = synchronized(entries) { entries.clear() }
}

/** The same resolver used by buildLocalMomentsTools, kept Android-free for wiring regression tests. */
internal fun localMomentsTurnBudget(
    registry: LocalMomentsBudgetRegistry?,
    invocationContext: ToolInvocationContext,
    anchorUserId: String?,
): LocalMomentsBudget? {
    val conversationId = invocationContext.callerConversationId?.takeIf(String::isNotBlank) ?: return null
    val anchor = anchorUserId?.takeIf(String::isNotBlank) ?: return null
    return registry?.budgetFor(conversationId, anchor)
}

class LocalMomentsArgumentException(
    val code: String,
    override val message: String,
) : Exception(message)

data class LocalMomentsFilterArgs(
    val authorId: String? = null,
    val authorKind: String? = null,
    val type: String? = null,
    val dateFrom: Long? = null,
    val dateTo: Long? = null,
)

data class LocalMomentsListArgs(
    val cursor: String?,
    val limit: Int,
    val bodyChars: Int,
    val filter: LocalMomentsFilterArgs,
)

data class LocalMomentsSearchArgs(
    val query: String,
    val cursor: String?,
    val limit: Int,
    val bodyChars: Int,
    val filter: LocalMomentsFilterArgs,
)

data class LocalMomentsGetArgs(
    val postId: String,
    val bodyChars: Int,
)

data class LocalMomentsCommentsArgs(
    val postId: String,
    val cursor: String?,
    val limit: Int,
    val order: LocalCommentOrder,
)

data class LocalMomentsMediaArgs(val resourceId: String)

data class LocalMomentsChatExcerptArgs(
    val postId: String,
    val start: Int,
    val limit: Int,
)

/** Pure parameter parser. Identity keys are rejected; unknown keys are rejected (no silent drops). */
object LocalMomentsArgs {
    private val LIST_KEYS = setOf(
        "cursor", "limit", "bodyChars", "authorId", "authorKind", "type", "dateFrom", "dateTo",
    )
    private val SEARCH_KEYS = LIST_KEYS + "query"
    private val GET_KEYS = setOf("postId", "bodyChars")
    private val COMMENTS_KEYS = setOf("postId", "cursor", "limit", "order")
    private val MEDIA_KEYS = setOf("resourceId")
    private val CHAT_EXCERPT_KEYS = setOf("postId", "start", "limit")

    fun list(element: JsonElement): LocalMomentsListArgs {
        val obj = requireObject(element)
        rejectIdentity(obj)
        rejectUnknown(obj, LIST_KEYS)
        return LocalMomentsListArgs(
            cursor = cursor(obj),
            limit = int(obj, "limit", LocalMomentsLimits.DEFAULT_PAGE_SIZE, 1, LocalMomentsLimits.MAX_PAGE_SIZE),
            bodyChars = int(
                obj,
                "bodyChars",
                LocalMomentsLimits.DEFAULT_LIST_BODY_CHARS,
                80,
                LocalMomentsLimits.MAX_LIST_BODY_CHARS,
            ),
            filter = filter(obj),
        )
    }

    fun search(element: JsonElement): LocalMomentsSearchArgs {
        val obj = requireObject(element)
        rejectIdentity(obj)
        rejectUnknown(obj, SEARCH_KEYS)
        val query = requiredString(obj, "query", LocalDataApi.MAX_SEARCH_CHARS)
        return LocalMomentsSearchArgs(
            query = query,
            cursor = cursor(obj),
            limit = int(obj, "limit", LocalMomentsLimits.DEFAULT_PAGE_SIZE, 1, LocalMomentsLimits.MAX_PAGE_SIZE),
            bodyChars = int(
                obj,
                "bodyChars",
                LocalMomentsLimits.DEFAULT_LIST_BODY_CHARS,
                80,
                LocalMomentsLimits.MAX_LIST_BODY_CHARS,
            ),
            filter = filter(obj),
        )
    }

    fun get(element: JsonElement): LocalMomentsGetArgs {
        val obj = requireObject(element)
        rejectIdentity(obj)
        rejectUnknown(obj, GET_KEYS)
        return LocalMomentsGetArgs(
            postId = requiredRecordId(obj, "postId"),
            bodyChars = int(
                obj,
                "bodyChars",
                LocalMomentsLimits.DEFAULT_GET_BODY_CHARS,
                120,
                LocalMomentsLimits.MAX_GET_BODY_CHARS,
            ),
        )
    }

    fun comments(element: JsonElement): LocalMomentsCommentsArgs {
        val obj = requireObject(element)
        rejectIdentity(obj)
        rejectUnknown(obj, COMMENTS_KEYS)
        val order = when (val raw = optionalString(obj, "order", 16)) {
            null -> LocalCommentOrder.NEWEST
            "newest" -> LocalCommentOrder.NEWEST
            "oldest" -> LocalCommentOrder.OLDEST
            else -> invalid("order 只能是 newest 或 oldest")
        }
        return LocalMomentsCommentsArgs(
            postId = requiredRecordId(obj, "postId"),
            cursor = cursor(obj),
            limit = int(
                obj,
                "limit",
                LocalMomentsLimits.DEFAULT_COMMENT_LIMIT,
                1,
                LocalMomentsLimits.MAX_COMMENT_LIMIT,
            ),
            order = order,
        )
    }

    fun media(element: JsonElement): LocalMomentsMediaArgs {
        val obj = requireObject(element)
        rejectIdentity(obj)
        rejectUnknown(obj, MEDIA_KEYS)
        val resourceId = requiredString(obj, "resourceId", 80)
        if (!LocalDataValidation.isSafeMediaId(resourceId)) invalid("resourceId 不是受控媒体 id")
        return LocalMomentsMediaArgs(resourceId)
    }

    fun chatExcerpt(element: JsonElement): LocalMomentsChatExcerptArgs {
        val obj = requireObject(element)
        rejectIdentity(obj)
        rejectUnknown(obj, CHAT_EXCERPT_KEYS)
        return LocalMomentsChatExcerptArgs(
            postId = requiredRecordId(obj, "postId"),
            start = int(obj, "start", 0, 0, LocalDataApi.MAX_CHAT_MESSAGES - 1),
            limit = int(
                obj,
                "limit",
                LocalMomentsLimits.DEFAULT_EXCERPT_MESSAGES,
                1,
                LocalMomentsLimits.MAX_EXCERPT_MESSAGES,
            ),
        )
    }

    private fun requireObject(element: JsonElement): JsonObject =
        element as? JsonObject ?: invalid("参数必须是 JSON 对象")

    private fun rejectIdentity(obj: JsonObject) {
        try {
            LocalDataRequestParser.requireNoIdentityFields(obj)
        } catch (_: LocalDataException) {
            throw LocalMomentsArgumentException(
                "identity_not_allowed",
                "助手身份由宿主决定，参数里不能出现 assistantId/actor/viewer 等身份字段",
            )
        }
    }

    private fun rejectUnknown(obj: JsonObject, allowed: Set<String>) {
        val unknown = obj.keys - allowed
        if (unknown.isNotEmpty()) {
            throw LocalMomentsArgumentException(
                "unknown_parameter",
                "不支持的参数：${unknown.sorted().joinToString(",")}",
            )
        }
    }

    private fun filter(obj: JsonObject): LocalMomentsFilterArgs {
        val authorKind = optionalString(obj, "authorKind", 16)?.also {
            if (it !in setOf("user", "assistant")) invalid("authorKind 只能是 user 或 assistant")
        }
        val type = optionalString(obj, "type", 24)?.also {
            if (it !in setOf("text", "image", "chat_snapshot")) {
                invalid("type 只能是 text、image 或 chat_snapshot")
            }
        }
        return LocalMomentsFilterArgs(
            authorId = optionalString(obj, "authorId", 80)?.also {
                if (!LocalDataValidation.isSafeRecordId(it)) invalid("authorId 格式不合法")
            },
            authorKind = authorKind,
            type = type,
            dateFrom = optionalEpochMillis(obj, "dateFrom"),
            dateTo = optionalEpochMillis(obj, "dateTo"),
        )
    }

    private fun cursor(obj: JsonObject): String? {
        val raw = optionalString(obj, "cursor", 160) ?: return null
        if (LocalDataCursorCodec.decode(raw) == null) {
            throw LocalMomentsArgumentException("invalid_cursor", "cursor 无效或已过期，请重新从第一页读取")
        }
        return raw
    }

    private fun requiredRecordId(obj: JsonObject, name: String): String {
        val value = requiredString(obj, name, 80)
        if (!LocalDataValidation.isSafeRecordId(value)) invalid("$name 格式不合法")
        return value
    }

    private fun requiredString(obj: JsonObject, name: String, maxChars: Int): String {
        val value = optionalString(obj, name, maxChars) ?: invalid("缺少参数 $name")
        if (value.isBlank()) invalid("$name 不能为空")
        return value
    }

    private fun optionalString(obj: JsonObject, name: String, maxChars: Int): String? {
        val element = obj[name] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: invalid("$name 必须是字符串")
        val value = primitive.content
        if (value.length > maxChars) invalid("$name 超过 $maxChars 字符上限")
        return value
    }

    private fun int(obj: JsonObject, name: String, default: Int, min: Int, max: Int): Int {
        val element = obj[name] ?: return default
        if (element is JsonNull) return default
        val primitive = element as? JsonPrimitive ?: invalid("$name 必须是整数")
        val value = primitive.intOrNull ?: invalid("$name 必须是整数")
        if (value !in min..max) invalid("$name 必须在 $min..$max 之间")
        return value
    }

    private fun optionalEpochMillis(obj: JsonObject, name: String): Long? {
        val element = obj[name] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: invalid("$name 必须是毫秒时间戳")
        val value = primitive.longOrNull ?: invalid("$name 必须是毫秒时间戳")
        if (value < 0) invalid("$name 不能为负数")
        return value
    }

    private fun invalid(message: String): Nothing =
        throw LocalMomentsArgumentException("invalid_argument", message)
}

data class LocalMomentsMediaViews(
    val items: List<JsonObject>,
    val omitted: Boolean,
    val omittedReason: String?,
)

data class LocalMomentsListData(
    val items: List<JsonObject>,
    val nextCursor: String?,
    val hasMore: Boolean,
    val truncated: Boolean,
    val truncationReason: String?,
)

data class LocalMomentsGetData(
    val data: JsonObject,
    val truncated: Boolean,
    val truncationReason: String?,
)

data class LocalMomentsCommentsData(
    val items: List<JsonObject>,
    val nextCursor: String?,
    val hasMore: Boolean,
    val truncated: Boolean,
    val truncationReason: String?,
)

data class LocalMomentsChatExcerptPage(
    val data: JsonObject,
    val shownMessages: Int,
    val truncated: Boolean,
    val truncationReason: String?,
)

/** Pure JSON projection layer. It intentionally never exposes a filesystem path or an original URL. */
object LocalMomentsOutput {
    private const val POST_OVERHEAD_CHARS = 120
    private const val COMMENT_OVERHEAD_CHARS = 90
    private const val MEDIA_VIEW_COST_CHARS = 130
    private const val MESSAGE_OVERHEAD_CHARS = 60

    fun envelope(
        tool: String,
        data: JsonObject,
        budget: LocalMomentsBudget,
        truncated: Boolean,
        truncationReason: String?,
    ): String {
        val payload = buildJsonObject {
            put("ok", true)
            put("tool", tool)
            put("readOnly", true)
            put("budget", budget.status())
            if (truncated) {
                put("truncated", true)
                put("truncationReason", truncationReason ?: "output_budget")
            }
            put("data", data)
        }.toString()
        return hardLimit(payload, tool)
    }

    /** Last-resort bound. It stays valid JSON and says plainly that details were dropped. */
    fun hardLimit(payload: String, tool: String): String {
        if (payload.length <= LocalMomentsLimits.MAX_OUTPUT_CHARS) return payload
        return buildJsonObject {
            put("ok", true)
            put("tool", tool)
            put("readOnly", true)
            put("truncated", true)
            put("truncationReason", "output_budget")
            put("data", buildJsonObject {
                put("omitted", true)
                put("note", "本次结果超过硬上限，已丢弃明细；请减少 limit 或翻页后重试")
            })
        }.toString()
    }

    fun listData(
        page: LocalPostPageDto,
        bodyChars: Int,
        budget: LocalMomentsBudget,
        includeMedia: Boolean,
    ): LocalMomentsListData {
        val items = mutableListOf<JsonObject>()
        var lastShown: LocalPostDto? = null
        var stoppedByBudget = false
        var bodyTruncated = false
        var mediaTruncated = false

        for (post in page.items) {
            if (!budget.text.canAfford(POST_OVERHEAD_CHARS)) {
                stoppedByBudget = true
                break
            }
            budget.text.charge(POST_OVERHEAD_CHARS)
            val body = budget.text.take(post.body, bodyChars)
            bodyTruncated = bodyTruncated || body.truncated
            val media = mediaViews(post.media, budget.text, includeMedia, "media_not_granted")
            mediaTruncated = mediaTruncated ||
                (media.omitted && media.omittedReason != "media_not_granted")
            items += postView(post, body, media)
            lastShown = post
        }

        val truncated = page.truncated || bodyTruncated || stoppedByBudget || mediaTruncated
        val reason = when {
            stoppedByBudget -> "output_budget"
            bodyTruncated -> "body_limit"
            mediaTruncated -> "media_limit"
            page.truncated -> "store_budget"
            else -> null
        }
        return LocalMomentsListData(
            items = items,
            nextCursor = nextCursor(page, lastShown, stoppedByBudget),
            hasMore = page.hasMore || stoppedByBudget,
            truncated = truncated,
            truncationReason = reason,
        )
    }

    fun getData(
        detail: LocalPostDetailDto,
        bodyChars: Int,
        budget: LocalMomentsBudget,
        includeMedia: Boolean,
    ): LocalMomentsGetData {
        budget.text.charge(POST_OVERHEAD_CHARS)
        val body = budget.text.take(detail.post.body, bodyChars)
        val media = mediaViews(detail.post.media, budget.text, includeMedia, "media_not_granted")
        val data = buildJsonObject {
            put("post", postView(detail.post, body, media))
            put("commentCount", detail.post.commentCount)
            put("commentPreviewOmitted", true)
            put("commentPreviewHint", "moments_comments")
        }
        val reason = when {
            body.truncated -> "body_limit"
            media.omitted && media.omittedReason != "media_not_granted" -> "media_limit"
            else -> null
        }
        return LocalMomentsGetData(data = data, truncated = reason != null, truncationReason = reason)
    }

    fun commentsData(
        page: LocalCommentPageDto,
        budget: LocalMomentsBudget,
    ): LocalMomentsCommentsData {
        val items = mutableListOf<JsonObject>()
        var stopped = false
        var bodyTruncated = false
        var lastShown: LocalCommentDto? = null
        for (comment in page.items) {
            if (!budget.text.canAfford(COMMENT_OVERHEAD_CHARS)) {
                stopped = true
                break
            }
            budget.text.charge(COMMENT_OVERHEAD_CHARS)
            val body = budget.text.take(comment.body, LocalMomentsLimits.MAX_COMMENT_BODY_CHARS)
            bodyTruncated = bodyTruncated || body.truncated
            items += commentView(comment, body)
            lastShown = comment
        }
        val truncated = stopped || bodyTruncated
        return LocalMomentsCommentsData(
            items = items,
            nextCursor = if (stopped && lastShown != null) {
                LocalDataCursorCodec.encode(lastShown.createdAt, lastShown.id)
            } else {
                page.nextCursor
            },
            hasMore = page.hasMore || stopped,
            truncated = truncated,
            truncationReason = when {
                stopped -> "output_budget"
                bodyTruncated -> "comment_body_limit"
                else -> null
            },
        )
    }

    fun mediaViews(
        media: List<LocalMediaDto>,
        budget: LocalMomentsTextBudget,
        includeMedia: Boolean,
        notGrantedReason: String,
    ): LocalMomentsMediaViews {
        if (!includeMedia) {
            return LocalMomentsMediaViews(
                items = emptyList(),
                omitted = media.isNotEmpty(),
                omittedReason = notGrantedReason,
            )
        }
        val items = mutableListOf<JsonObject>()
        var reason: String? = null
        for ((index, item) in media.withIndex()) {
            if (index >= LocalMomentsLimits.MAX_MEDIA_METADATA_PER_PAGE) {
                reason = "metadata_limit"
                break
            }
            if (!budget.canAfford(MEDIA_VIEW_COST_CHARS)) {
                reason = "output_budget"
                break
            }
            budget.charge(MEDIA_VIEW_COST_CHARS)
            items += mediaView(item)
        }
        return LocalMomentsMediaViews(
            items = items,
            omitted = items.size < media.size,
            omittedReason = reason,
        )
    }

    fun mediaView(media: LocalMediaDto): JsonObject = buildJsonObject {
        put("resourceId", media.resourceId)
        put("mime", media.mime)
        put("width", media.width)
        put("height", media.height)
        put("byteSize", media.byteSize)
        // Controlled host URL only. The original URL is deliberately not exposed to the model.
        put("thumbUrl", media.thumbUrl)
        put("available", media.available)
    }

    fun mediaGetData(
        media: LocalMediaDto,
        budget: LocalMomentsBudget,
        imageAttached: Boolean,
        imageOmittedReason: String?,
    ): JsonObject {
        budget.text.charge(MEDIA_VIEW_COST_CHARS)
        return buildJsonObject {
            put("media", mediaView(media))
            put("image", buildJsonObject {
                put("attached", imageAttached)
                put("variant", "thumbnail")
                put("maxEdgePx", LocalMomentsLimits.THUMBNAIL_MAX_PX)
                put("maxAttached", LocalMomentsLimits.MAX_ATTACHED_IMAGES_PER_CALL)
                if (!imageAttached && imageOmittedReason != null) {
                    put("omittedReason", imageOmittedReason)
                }
            })
        }
    }

    fun chatExcerptData(
        postId: String,
        snapshot: LocalChatSnapshotDto,
        start: Int,
        limit: Int,
        budget: LocalMomentsBudget,
        includeMediaIds: Boolean,
    ): LocalMomentsChatExcerptPage {
        val all = snapshot.messages
        val slice = all.drop(start).take(limit)
        val messages = mutableListOf<JsonObject>()
        var stopped = false
        var textTruncated = false
        var excerptChars = 0

        for (message in slice) {
            if (excerptChars >= LocalMomentsLimits.MAX_EXCERPT_TOTAL_CHARS ||
                !budget.text.canAfford(MESSAGE_OVERHEAD_CHARS)
            ) {
                stopped = true
                break
            }
            val remainingTotal = LocalMomentsLimits.MAX_EXCERPT_TOTAL_CHARS - excerptChars
            budget.text.charge(MESSAGE_OVERHEAD_CHARS)
            val text = budget.text.take(
                message.text,
                minOf(LocalMomentsLimits.DEFAULT_EXCERPT_MESSAGE_CHARS, remainingTotal),
            )
            textTruncated = textTruncated || text.truncated
            excerptChars += text.text.length
            messages += buildJsonObject {
                put("role", message.role)
                put("name", message.name)
                put("text", text.text)
                put("textTruncated", text.truncated)
                put("mediaCount", message.media.size)
                if (includeMediaIds && message.media.isNotEmpty()) {
                    put("mediaIds", JsonArray(
                        message.media
                            .filter { LocalDataValidation.isSafeMediaId(it) }
                            .take(LocalMomentsLimits.MAX_MEDIA_METADATA_PER_PAGE)
                            .map { JsonPrimitive(it) },
                    ))
                } else if (message.media.isNotEmpty()) {
                    put("mediaOmittedReason", "media_not_granted")
                }
                message.avatarEmoji?.let { put("avatarEmoji", it) }
            }
        }

        val consumed = start + messages.size
        val hasMore = consumed < all.size
        val truncated = stopped || textTruncated || excerptChars >= LocalMomentsLimits.MAX_EXCERPT_TOTAL_CHARS
        val data = buildJsonObject {
            put("postId", postId)
            snapshot.title?.let { put("title", it) }
            put("totalMessages", all.size)
            put("start", start)
            put("count", messages.size)
            put("hasMore", hasMore)
            if (hasMore) put("nextStart", consumed)
            put("messages", JsonArray(messages))
            put("imagePolicy", "text-only; call moments_media_get for a specific thumbnail")
        }
        return LocalMomentsChatExcerptPage(
            data = data,
            shownMessages = messages.size,
            truncated = truncated,
            truncationReason = when {
                stopped -> "output_budget"
                textTruncated -> "message_text_limit"
                excerptChars >= LocalMomentsLimits.MAX_EXCERPT_TOTAL_CHARS -> "excerpt_total_limit"
                else -> null
            },
        )
    }

    fun postView(
        post: LocalPostDto,
        body: LocalMomentsLimitedText,
        media: LocalMomentsMediaViews,
    ): JsonObject = buildJsonObject {
        put("id", post.id)
        put("author", authorView(post.author))
        put("createdAt", post.createdAt)
        put("body", body.text)
        put("bodyTruncated", body.truncated)
        put("bodyOriginalChars", body.originalChars)
        put("commentCount", post.commentCount)
        put("likeCount", post.likeCount)
        put("likedByMe", post.likedByMe)
        put("media", buildJsonObject {
            put("count", post.media.size)
            put("countKnown", !post.mediaOmitted)
            put("omitted", media.omitted || post.mediaOmitted)
            if (media.omitted || post.mediaOmitted) {
                put(
                    "omittedReason",
                    media.omittedReason ?: if (post.mediaOmitted) "media_not_granted" else "unknown",
                )
            }
            put("items", JsonArray(media.items))
        })
        // Chat excerpts are never part of list/get. The model must opt in explicitly, and even then
        // only the controlled excerpt tool may read them.
        put("chatExcerpt", buildJsonObject {
            put("omitted", true)
            put("hint", LocalMomentsContract.TOOL_CHAT_EXCERPT_GET)
        })
    }

    fun commentView(comment: LocalCommentDto, body: LocalMomentsLimitedText): JsonObject = buildJsonObject {
        put("id", comment.id)
        put("author", authorView(comment.author))
        put("createdAt", comment.createdAt)
        put("body", body.text)
        put("bodyTruncated", body.truncated)
        put("bodyOriginalChars", body.originalChars)
        comment.replyToId?.let { put("replyToId", it) }
    }

    fun authorView(author: LocalAuthorDto): JsonObject = buildJsonObject {
        put("kind", when (author.kind) {
            AuthorKind.USER -> "user"
            AuthorKind.ASSISTANT -> "assistant"
        })
        put("id", author.id)
        put("name", author.name)
    }

    private fun nextCursor(
        page: LocalPostPageDto,
        lastShown: LocalPostDto?,
        stoppedByBudget: Boolean,
    ): String? {
        if (!stoppedByBudget) return page.nextCursor
        // The store cursor points past the whole fetched page. When output budget cut the page
        // short, resume right after the last shown row so nothing is silently skipped.
        return lastShown?.let { LocalDataCursorCodec.encode(it.createdAt, it.id) }
    }
}

private data class LocalMomentsGrantSnapshot(
    val pluginInstalled: Boolean,
    val pluginEnabled: Boolean,
    val pluginLoadError: String?,
    val declaredCapabilities: Set<String>,
    val approvedCapabilities: Set<String>,
)

private data class LocalMomentsRuntime(
    val access: LocalMomentsAccess,
    val snapshot: LocalMomentsAccessSnapshot,
    val assistantId: String,
    val assistantName: String,
    val directory: LocalAssistantDirectory,
    val grant: LocalDataGrant,
    val userDisplayName: String,
)

class LocalMomentsTools(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val pluginManager: PluginManager,
    private val filesManager: FilesManager,
) {
    /**
     * Build the read-only tool set for one assistant turn. Returns an empty list when any native
     * gate fails, so the model never even sees a schema it cannot use.
     *
     * [budget] is the user-turn scoped budget supplied by ChatService when this surface is
     * rebuilt during one turn (tool loop, approval resume). Callers that cannot scope a turn may
     * omit it; a fresh one is then created for this build only.
     */
    fun build(
        invocationContext: ToolInvocationContext,
        budget: LocalMomentsBudget = LocalMomentsBudget(),
    ): List<Tool> {
        if (invocationContext.isHeadless || invocationContext.callerAssistantId.isNullOrBlank()) {
            return emptyList()
        }
        val snapshot = currentSnapshot(invocationContext)
        val access = LocalMomentsAccessPolicy.evaluate(snapshot)
        if (!access.allowed) return emptyList()

        val tools = mutableListOf<Tool>()
        tools += listTool(invocationContext, budget)
        tools += getTool(invocationContext, budget)
        tools += searchTool(invocationContext, budget)
        tools += commentsTool(invocationContext, budget)
        if (access.canReadMedia) {
            tools += mediaTool(invocationContext, budget)
        }
        if (access.canReadChatSnapshot) {
            tools += chatExcerptTool(invocationContext, budget)
        }
        return tools
    }

    private fun listTool(
        invocationContext: ToolInvocationContext,
        budget: LocalMomentsBudget,
    ): Tool = Tool(
        name = LocalMomentsContract.TOOL_LIST,
        description = """
            读取本机朋友圈的动态（只读，不会发帖/评论/点赞）。
            默认返回最新 3 条可见动态的摘要；用 nextCursor + cursor 翻页读取旧动态。
            每条正文默认最多 ${LocalMomentsLimits.DEFAULT_LIST_BODY_CHARS} 字，截断会标记 bodyTruncated。
            不返回聊天记录卡片正文；需要时用 ${LocalMomentsContract.TOOL_CHAT_EXCERPT_GET}。
            图片只返回受控元数据（不返回原图）；需要看图用 ${LocalMomentsContract.TOOL_MEDIA_GET}。
            每轮最多 ${LocalMomentsLimits.MAX_TOOL_CALLS_PER_TURN} 次调用、${LocalMomentsLimits.MAX_PAGES_PER_TURN} 页。
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("cursor", stringParam("上一页返回的 nextCursor；省略表示第一页"))
                    put("limit", intParam("本页条数，默认 3，最大 ${LocalMomentsLimits.MAX_PAGE_SIZE}", 1, LocalMomentsLimits.MAX_PAGE_SIZE))
                    put("bodyChars", intParam("每条正文最多字符数，默认 ${LocalMomentsLimits.DEFAULT_LIST_BODY_CHARS}", 80, LocalMomentsLimits.MAX_LIST_BODY_CHARS))
                    put("authorId", stringParam("可选：只看某个作者（用户或助手 id）"))
                    put("authorKind", enumParam("可选：authorKind", listOf("user", "assistant")))
                    put("type", enumParam("可选：动态类型", listOf("text", "image", "chat_snapshot")))
                    put("dateFrom", longParam("可选：起始时间，Unix 毫秒"))
                    put("dateTo", longParam("可选：结束时间，Unix 毫秒"))
                },
                required = emptyList(),
            )
        },
        execute = { element ->
            runTool(LocalMomentsContract.TOOL_LIST) {
                val runtime = requireRuntime(invocationContext)
                val args = LocalMomentsArgs.list(element)
                budget.beginCall(LocalMomentsCallKind.LIST)?.let { throw toolDenied(it) }
                withSession(runtime) { session ->
                    val envelope = call(
                        session = session,
                        runtime = runtime,
                        method = LocalDataMethods.LIST,
                        params = buildJsonObject {
                            args.cursor?.let { put("cursor", it) }
                            put("limit", args.limit)
                            put("bodyChars", args.bodyChars)
                            put("commentPreview", 0)
                            putFilter(args.filter)
                        },
                    )
                    val page = envelope.decode(LocalPostPageDto.serializer())
                    val view = LocalMomentsOutput.listData(
                        page = page,
                        bodyChars = args.bodyChars,
                        budget = budget,
                        includeMedia = runtime.access.canReadMedia,
                    )
                    val data = buildJsonObject {
                        put("items", JsonArray(view.items))
                        put("nextCursor", view.nextCursor?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("hasMore", view.hasMore)
                        put("count", view.items.size)
                    }
                    listOf(UIMessagePart.Text(
                        LocalMomentsOutput.envelope(
                            tool = LocalMomentsContract.TOOL_LIST,
                            data = data,
                            budget = budget,
                            truncated = view.truncated,
                            truncationReason = view.truncationReason,
                        ),
                    ))
                }
            }
        },
    )

    private fun getTool(
        invocationContext: ToolInvocationContext,
        budget: LocalMomentsBudget,
    ): Tool = Tool(
        name = LocalMomentsContract.TOOL_GET,
        description = """
            读取本机朋友圈中的一条动态（只读）。不返回评论列表，也不返回聊天记录卡片正文。
            正文默认最多 ${LocalMomentsLimits.DEFAULT_GET_BODY_CHARS} 字，超出会标记 bodyTruncated。
            需要评论用 ${LocalMomentsContract.TOOL_COMMENTS}；需要聊天卡片用 ${LocalMomentsContract.TOOL_CHAT_EXCERPT_GET}。
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("postId", stringParam("动态 id（moments_list/search 返回的 id）"))
                    put("bodyChars", intParam("正文最多字符数，默认 ${LocalMomentsLimits.DEFAULT_GET_BODY_CHARS}", 120, LocalMomentsLimits.MAX_GET_BODY_CHARS))
                },
                required = listOf("postId"),
            )
        },
        execute = { element ->
            runTool(LocalMomentsContract.TOOL_GET) {
                val runtime = requireRuntime(invocationContext)
                val args = LocalMomentsArgs.get(element)
                budget.beginCall(LocalMomentsCallKind.GET)?.let { throw toolDenied(it) }
                withSession(runtime) { session ->
                    val envelope = call(
                        session = session,
                        runtime = runtime,
                        method = LocalDataMethods.GET,
                        params = buildJsonObject {
                            put("postId", args.postId)
                            put("bodyChars", args.bodyChars)
                            put("commentLimit", 0)
                            put("includeMedia", true)
                            put("includeChatSnapshot", false)
                        },
                    )
                    val detail = envelope.decode(LocalPostDetailDto.serializer())
                    val view = LocalMomentsOutput.getData(
                        detail = detail,
                        bodyChars = args.bodyChars,
                        budget = budget,
                        includeMedia = runtime.access.canReadMedia,
                    )
                    listOf(UIMessagePart.Text(
                        LocalMomentsOutput.envelope(
                            tool = LocalMomentsContract.TOOL_GET,
                            data = view.data,
                            budget = budget,
                            truncated = view.truncated,
                            truncationReason = view.truncationReason,
                        ),
                    ))
                }
            }
        },
    )

    private fun searchTool(
        invocationContext: ToolInvocationContext,
        budget: LocalMomentsBudget,
    ): Tool = Tool(
        name = LocalMomentsContract.TOOL_SEARCH,
        description = """
            搜索本机朋友圈的旧动态（只读）。query 必填，最长 ${LocalDataApi.MAX_SEARCH_CHARS} 字。
            可按作者、日期、内容类型过滤；用 nextCursor + cursor 翻页。
            默认返回最新 3 条命中摘要，正文截断会明确标记。不会搜索私聊内容。
            每轮最多 ${LocalMomentsLimits.MAX_SEARCHES_PER_TURN} 次搜索、${LocalMomentsLimits.MAX_PAGES_PER_TURN} 页。
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", stringParam("搜索关键词，最长 ${LocalDataApi.MAX_SEARCH_CHARS} 字"))
                    put("cursor", stringParam("上一页返回的 nextCursor；省略表示第一页"))
                    put("limit", intParam("本页条数，默认 3，最大 ${LocalMomentsLimits.MAX_PAGE_SIZE}", 1, LocalMomentsLimits.MAX_PAGE_SIZE))
                    put("bodyChars", intParam("每条正文最多字符数，默认 ${LocalMomentsLimits.DEFAULT_LIST_BODY_CHARS}", 80, LocalMomentsLimits.MAX_LIST_BODY_CHARS))
                    put("authorId", stringParam("可选：只看某个作者（用户或助手 id）"))
                    put("authorKind", enumParam("可选：authorKind", listOf("user", "assistant")))
                    put("type", enumParam("可选：动态类型", listOf("text", "image", "chat_snapshot")))
                    put("dateFrom", longParam("可选：起始时间，Unix 毫秒"))
                    put("dateTo", longParam("可选：结束时间，Unix 毫秒"))
                },
                required = listOf("query"),
            )
        },
        execute = { element ->
            runTool(LocalMomentsContract.TOOL_SEARCH) {
                val runtime = requireRuntime(invocationContext)
                val args = LocalMomentsArgs.search(element)
                budget.beginCall(LocalMomentsCallKind.SEARCH)?.let { throw toolDenied(it) }
                withSession(runtime) { session ->
                    val envelope = call(
                        session = session,
                        runtime = runtime,
                        method = LocalDataMethods.SEARCH,
                        params = buildJsonObject {
                            put("query", args.query)
                            args.cursor?.let { put("cursor", it) }
                            put("limit", args.limit)
                            put("bodyChars", args.bodyChars)
                            put("commentPreview", 0)
                            putFilter(args.filter)
                        },
                    )
                    val page = envelope.decode(LocalPostPageDto.serializer())
                    val view = LocalMomentsOutput.listData(
                        page = page,
                        bodyChars = args.bodyChars,
                        budget = budget,
                        includeMedia = runtime.access.canReadMedia,
                    )
                    val data = buildJsonObject {
                        put("query", args.query)
                        put("items", JsonArray(view.items))
                        put("nextCursor", view.nextCursor?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("hasMore", view.hasMore)
                        put("count", view.items.size)
                    }
                    listOf(UIMessagePart.Text(
                        LocalMomentsOutput.envelope(
                            tool = LocalMomentsContract.TOOL_SEARCH,
                            data = data,
                            budget = budget,
                            truncated = view.truncated,
                            truncationReason = view.truncationReason,
                        ),
                    ))
                }
            }
        },
    )

    private fun commentsTool(
        invocationContext: ToolInvocationContext,
        budget: LocalMomentsBudget,
    ): Tool = Tool(
        name = LocalMomentsContract.TOOL_COMMENTS,
        description = """
            读取一条本机朋友圈动态的评论（只读）。默认按最新优先返回 3 条，最多 ${LocalMomentsLimits.MAX_COMMENT_LIMIT} 条；
            用 nextCursor + cursor 继续翻旧评论。每条评论正文最多 ${LocalMomentsLimits.MAX_COMMENT_BODY_CHARS} 字，截断会标记。
            不返回未授权动态的评论。
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("postId", stringParam("动态 id"))
                    put("cursor", stringParam("上一页返回的 nextCursor；省略表示第一页"))
                    put("limit", intParam("本页条数，默认 3，最大 ${LocalMomentsLimits.MAX_COMMENT_LIMIT}", 1, LocalMomentsLimits.MAX_COMMENT_LIMIT))
                    put("order", enumParam("排序：newest（默认）或 oldest", listOf("newest", "oldest")))
                },
                required = listOf("postId"),
            )
        },
        execute = { element ->
            runTool(LocalMomentsContract.TOOL_COMMENTS) {
                val runtime = requireRuntime(invocationContext)
                val args = LocalMomentsArgs.comments(element)
                budget.beginCall(LocalMomentsCallKind.COMMENTS)?.let { throw toolDenied(it) }
                withSession(runtime) { session ->
                    val envelope = call(
                        session = session,
                        runtime = runtime,
                        method = LocalDataMethods.COMMENTS_LIST,
                        params = buildJsonObject {
                            put("postId", args.postId)
                            args.cursor?.let { put("cursor", it) }
                            put("limit", args.limit)
                            put("order", if (args.order == LocalCommentOrder.NEWEST) "newest" else "oldest")
                        },
                    )
                    val page = envelope.decode(LocalCommentPageDto.serializer())
                    val view = LocalMomentsOutput.commentsData(page, budget)
                    val data = buildJsonObject {
                        put("postId", args.postId)
                        put("items", JsonArray(view.items))
                        put("commentCount", page.commentCount)
                        put("nextCursor", view.nextCursor?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("hasMore", view.hasMore)
                        put("count", view.items.size)
                    }
                    listOf(UIMessagePart.Text(
                        LocalMomentsOutput.envelope(
                            tool = LocalMomentsContract.TOOL_COMMENTS,
                            data = data,
                            budget = budget,
                            truncated = view.truncated,
                            truncationReason = view.truncationReason,
                        ),
                    ))
                }
            }
        },
    )

    private fun mediaTool(
        invocationContext: ToolInvocationContext,
        budget: LocalMomentsBudget,
    ): Tool = Tool(
        name = LocalMomentsContract.TOOL_MEDIA_GET,
        description = """
            读取一条动态图片的受控元数据（只读）。默认只返回缩略图元数据与受控 thumbUrl，永不返回原图路径。
            只有宿主已授权 localdata.media、朋友圈图片聊天开关打开、且当前模型能看图时，才附带一张
            ${LocalMomentsLimits.THUMBNAIL_MAX_PX}px 以内的缩略图；否则 image.attached=false 并说明原因。
            不要声称看过未附带图像的内容。
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("resourceId", stringParam("受控媒体 id（ldm_...，来自列表/详情的 media.items）"))
                },
                required = listOf("resourceId"),
            )
        },
        execute = { element ->
            runTool(LocalMomentsContract.TOOL_MEDIA_GET) {
                val runtime = requireRuntime(invocationContext)
                val args = LocalMomentsArgs.media(element)
                budget.beginCall(LocalMomentsCallKind.MEDIA)?.let { throw toolDenied(it) }
                withSession(runtime) { session ->
                    val envelope = call(
                        session = session,
                        runtime = runtime,
                        method = LocalDataMethods.MEDIA_DESCRIBE,
                        params = buildJsonObject { put("resourceId", args.resourceId) },
                    )
                    val media = envelope.decode(LocalMediaDto.serializer())
                    val attachDenial = when {
                        !runtime.access.canReadMedia -> "media_not_granted"
                        !runtime.snapshot.taskSettings.normalized().allowImages -> "image_chat_not_allowed"
                        !runtime.snapshot.modelCanSeeImages -> "model_cannot_see_images"
                        else -> null
                    }
                    var attachedFile: File? = null
                    var attachReason = attachDenial
                    if (attachDenial == null && LocalMomentsLimits.MAX_ATTACHED_IMAGES_PER_CALL > 0) {
                        val thumbnail = session.media.openThumbnail(media.resourceId)
                        if (thumbnail == null || thumbnail.length <= 0L ||
                            thumbnail.length > LocalMomentsLimits.MAX_ATTACHED_IMAGE_BYTES
                        ) {
                            attachReason = "thumbnail_unavailable"
                        } else {
                            attachedFile = persistThumbnail(thumbnail.file, media.resourceId)
                            if (attachedFile == null) attachReason = "thumbnail_unavailable"
                        }
                    }
                    val data = LocalMomentsOutput.mediaGetData(
                        media = media,
                        budget = budget,
                        imageAttached = attachedFile != null,
                        imageOmittedReason = attachReason,
                    )
                    val text = LocalMomentsOutput.envelope(
                        tool = LocalMomentsContract.TOOL_MEDIA_GET,
                        data = data,
                        budget = budget,
                        truncated = false,
                        truncationReason = null,
                    )
                    buildList<UIMessagePart> {
                        add(UIMessagePart.Text(text))
                        attachedFile?.let { add(UIMessagePart.Image(it.toUri().toString())) }
                    }
                }
            }
        },
    )

    private fun chatExcerptTool(
        invocationContext: ToolInvocationContext,
        budget: LocalMomentsBudget,
    ): Tool = Tool(
        name = LocalMomentsContract.TOOL_CHAT_EXCERPT_GET,
        description = """
            显式读取一条动态内嵌的聊天记录卡片（只读）。这不是默认能力：moments_list/get 不会返回卡片正文。
            默认从第 0 条开始返回最多 10 条消息，每条最多 ${LocalMomentsLimits.DEFAULT_EXCERPT_MESSAGE_CHARS} 字；
            用 start + limit 翻页。返回的 imagePolicy 只是文字说明，图片需另调 ${LocalMomentsContract.TOOL_MEDIA_GET}。
            不返回系统提示词、工具结果、思考内容或未选中消息。
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("postId", stringParam("聊天记录卡片动态的 id"))
                    put("start", intParam("从第几条消息开始，默认 0", 0, LocalDataApi.MAX_CHAT_MESSAGES - 1))
                    put("limit", intParam("本页最多消息数，默认 10，最大 ${LocalMomentsLimits.MAX_EXCERPT_MESSAGES}", 1, LocalMomentsLimits.MAX_EXCERPT_MESSAGES))
                },
                required = listOf("postId"),
            )
        },
        execute = { element ->
            runTool(LocalMomentsContract.TOOL_CHAT_EXCERPT_GET) {
                val runtime = requireRuntime(invocationContext, needsChatSnapshot = true)
                val args = LocalMomentsArgs.chatExcerpt(element)
                budget.beginCall(LocalMomentsCallKind.CHAT_EXCERPT)?.let { throw toolDenied(it) }
                withSession(runtime) { session ->
                    val envelope = call(
                        session = session,
                        runtime = runtime,
                        method = LocalDataMethods.GET,
                        params = buildJsonObject {
                            put("postId", args.postId)
                            put("bodyChars", 0)
                            put("commentLimit", 0)
                            put("includeMedia", false)
                            put("includeChatSnapshot", true)
                        },
                    )
                    val detail = envelope.decode(LocalPostDetailDto.serializer())
                    val snapshot = detail.post.chatSnapshot
                    if (snapshot == null) {
                        throw LocalMomentsToolException(
                            "not_a_chat_snapshot",
                            "这条动态不是聊天记录卡片，或卡片正文已被隐藏",
                        )
                    }
                    val page = LocalMomentsOutput.chatExcerptData(
                        postId = args.postId,
                        snapshot = snapshot,
                        start = args.start,
                        limit = args.limit,
                        budget = budget,
                        includeMediaIds = runtime.access.canReadMedia,
                    )
                    listOf(UIMessagePart.Text(
                        LocalMomentsOutput.envelope(
                            tool = LocalMomentsContract.TOOL_CHAT_EXCERPT_GET,
                            data = page.data,
                            budget = budget,
                            truncated = page.truncated,
                            truncationReason = page.truncationReason,
                        ),
                    ))
                }
            }
        },
    )

    private fun requireRuntime(
        invocationContext: ToolInvocationContext,
        needsChatSnapshot: Boolean = false,
    ): LocalMomentsRuntime {
        val snapshot = currentSnapshot(invocationContext)
        val access = LocalMomentsAccessPolicy.evaluate(snapshot)
        access.denial?.let { throw toolDenied(it) }
        if (needsChatSnapshot && !access.canReadChatSnapshot) {
            throw LocalMomentsToolException(
                "chat_snapshot_not_granted",
                "宿主未授权 localdata.chat_snapshot，不能读取聊天记录卡片",
            )
        }
        val settings = settingsStore.settingsFlow.value
        val assistantId = snapshot.assistantId
            ?: throw LocalMomentsToolException("assistant_identity_missing", "缺少助手身份")
        val assistant = settings.assistants.firstOrNull { it.id.toString() == assistantId }
            ?: throw LocalMomentsToolException("assistant_unknown", "当前助手不在宿主助手列表中")
        // Least privilege: these tools are read-only, so never hand the full native grant to the
        // session even when the plugin approved write/comment/like/backup as well.
        val capabilities = buildSet {
            add(LocalDataCapability.READ)
            if (access.canReadMedia) add(LocalDataCapability.MEDIA)
            if (access.canReadChatSnapshot) add(LocalDataCapability.CHAT_SNAPSHOT)
        }
        return LocalMomentsRuntime(
            access = access,
            snapshot = snapshot,
            assistantId = assistantId,
            assistantName = assistant.name.ifBlank { "助手" },
            directory = directoryOf(settings),
            grant = LocalDataGrant.of(*capabilities.toTypedArray()),
            userDisplayName = settings.displaySetting.userNickname.ifBlank { "我" },
        )
    }

    private fun currentSnapshot(invocationContext: ToolInvocationContext): LocalMomentsAccessSnapshot {
        val settings = settingsStore.settingsFlow.value
        val grants = grants()
        val assistantId = invocationContext.callerAssistantId
        val assistant = assistantId?.let { id -> settings.assistants.firstOrNull { it.id.toString() == id } }
        return localMomentsAccessSnapshot(
            invocationContext = invocationContext,
            pluginInstalled = grants.pluginInstalled,
            pluginEnabled = grants.pluginEnabled,
            pluginLoadError = grants.pluginLoadError,
            grantedCapabilities = grants.declaredCapabilities intersect grants.approvedCapabilities,
            taskSettings = taskSettings(),
            assistantId = assistantId,
            assistantKnown = assistant != null,
        )
    }

    private fun grants(): LocalMomentsGrantSnapshot {
        val plugin = pluginManager.getPlugin(LocalMomentsContract.PLUGIN_ID)
        val approved = context.getSharedPreferences(HOST_GRANT_PREFERENCES, Context.MODE_PRIVATE)
            .getStringSet(LocalMomentsContract.PLUGIN_ID, emptySet())
            .orEmpty()
        return LocalMomentsGrantSnapshot(
            pluginInstalled = plugin != null,
            pluginEnabled = plugin?.isEnabled == true,
            pluginLoadError = plugin?.loadError,
            declaredCapabilities = plugin?.manifest?.permissions?.toSet().orEmpty(),
            approvedCapabilities = approved,
        )
    }

    private fun taskSettings(): LocalTaskSettings = LocalTaskSettingsStore(
        SharedPreferencesLocalTaskStore(
            context,
            LocalTaskIds.storeName(LocalMomentsContract.PLUGIN_ID),
        ),
    ).get()

    private fun directoryOf(settings: Settings): LocalAssistantDirectory =
        LocalAssistantDirectory.of(
            settings.assistants.map {
                LocalDataAssistantRef(
                    id = it.id.toString(),
                    name = it.name.ifBlank { "助手" },
                    avatar = avatarOf(it.avatar),
                )
            },
        )

    private fun avatarOf(avatar: Avatar): String? = when (avatar) {
        Avatar.Dummy -> null
        is Avatar.Emoji -> avatar.content
        is Avatar.Image -> avatar.url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    private suspend fun <T> withSession(
        runtime: LocalMomentsRuntime,
        block: (LocalDataSession) -> T,
    ): T = withContext(Dispatchers.IO) {
        LocalDataSession(context.applicationContext, LocalMomentsContract.PLUGIN_ID, runtime.grant)
            .use { session -> block(session) }
    }

    private fun call(
        session: LocalDataSession,
        runtime: LocalMomentsRuntime,
        method: String,
        params: JsonObject,
    ): LocalDataEnvelope {
        val request = buildJsonObject {
            put("version", LocalDataApi.VERSION)
            put("method", method)
            put("params", params)
        }.toString()
        val raw = session.call(
            requestJson = request,
            actor = LocalDataActor.Assistant(runtime.assistantId, runtime.assistantName),
            assistantDirectory = runtime.directory,
            userDisplayName = runtime.userDisplayName,
        )
        val envelope = LocalDataJson.json.decodeFromString(LocalDataEnvelope.serializer(), raw)
        if (!envelope.ok) {
            throw LocalMomentsToolException(
                envelope.error?.code ?: "localdata_error",
                envelope.error?.message ?: "本地朋友圈读取失败",
            )
        }
        return envelope
    }

    private fun <T> LocalDataEnvelope.decode(deserializer: DeserializationStrategy<T>): T {
        val payload = data ?: throw LocalMomentsToolException("unavailable", "本地朋友圈没有返回数据")
        return LocalDataJson.json.decodeFromJsonElement(deserializer, payload)
    }

    private fun persistThumbnail(source: File, resourceId: String): File? = runCatching {
        if (!LocalDataValidation.isSafeMediaId(resourceId)) return@runCatching null
        if (!source.isFile || source.length() <= 0L ||
            source.length() > LocalMomentsLimits.MAX_ATTACHED_IMAGE_BYTES
        ) {
            return@runCatching null
        }
        val target = File(filesManager.getImagesDir(), "moments_$resourceId.jpg")
        if (target.isFile && target.length() == source.length()) return@runCatching target
        source.copyTo(target, overwrite = true)
        target.takeIf { it.isFile && it.length() == source.length() }
    }.getOrNull()

    private suspend fun runTool(
        tool: String,
        block: suspend () -> List<UIMessagePart>,
    ): List<UIMessagePart> = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: LocalMomentsToolException) {
        toolError(tool, error.code, error.message)
    } catch (error: LocalMomentsArgumentException) {
        toolError(tool, error.code, error.message)
    } catch (error: LocalDataException) {
        toolError(tool, error.code.wire, error.message)
    } catch (error: Exception) {
        toolError(
            tool,
            "unavailable",
            "读取本机朋友圈失败：${error.message?.take(120) ?: "未知错误"}",
        )
    }

    private fun toolDenied(denial: LocalMomentsDenial): LocalMomentsToolException =
        LocalMomentsToolException(denial.code, denial.message)

    private fun toolError(tool: String, code: String, message: String): List<UIMessagePart> {
        val payload = buildJsonObject {
            put("ok", false)
            put("tool", tool)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message.take(300))
                put("retryable", false)
            })
        }.toString()
        return listOf(UIMessagePart.Text(payload))
    }

    companion object {
        /** Must stay in sync with [me.rerere.rikkahub.ui.pages.chat.LOCAL_MOMENTS_PLUGIN_ID]. */
        const val HOST_GRANT_PREFERENCES = "localdata_host_grants"
    }
}

class LocalMomentsToolException(
    val code: String,
    override val message: String,
) : Exception(message)

/**
 * Lead-facing wiring helper. Callers must pass the host's own [SettingsStore], [PluginManager] and
 * [FilesManager]; [invocationContext] must carry the real assistant id and must be non-headless for
 * a normal user-triggered chat. The returned list is empty when any native gate denies access.
 *
 * [budgetRegistry] is the user-turn scoped registry supplied by ChatService. [budgetAnchorUserId]
 * identifies the same real user message across tool-surface rebuilds and approval resumes; the
 * helper resolves the actual shared budget itself rather than accepting an unrelated prebuilt one.
 */
fun buildLocalMomentsTools(
    context: Context,
    settingsStore: SettingsStore,
    pluginManager: PluginManager,
    filesManager: FilesManager,
    invocationContext: ToolInvocationContext,
    budgetRegistry: LocalMomentsBudgetRegistry? = null,
    budgetAnchorUserId: String? = null,
): List<Tool> {
    val budget = localMomentsTurnBudget(budgetRegistry, invocationContext, budgetAnchorUserId)
        ?: LocalMomentsBudget()
    return LocalMomentsTools(
        context = context,
        settingsStore = settingsStore,
        pluginManager = pluginManager,
        filesManager = filesManager,
    ).build(invocationContext, budget)
}

private fun JsonObjectBuilder.putFilter(filter: LocalMomentsFilterArgs): JsonObjectBuilder = apply {
    filter.authorId?.let { put("authorId", it) }
    filter.authorKind?.let { put("authorKind", it) }
    filter.type?.let { put("type", it) }
    filter.dateFrom?.let { put("dateFrom", it) }
    filter.dateTo?.let { put("dateTo", it) }
}

private fun stringParam(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun intParam(description: String, minimum: Int, maximum: Int): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
    put("minimum", minimum)
    put("maximum", maximum)
}

private fun longParam(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
    put("minimum", 0)
}

private fun enumParam(description: String, values: List<String>): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    put("enum", buildJsonArray { values.forEach { add(it) } })
}
