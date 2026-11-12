/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.plugin.localdata.LocalAssistantDirectory
import me.rerere.rikkahub.plugin.localdata.LocalChatSnapshotDto
import me.rerere.rikkahub.plugin.localdata.LocalDataActor
import me.rerere.rikkahub.plugin.localdata.LocalDataApi
import me.rerere.rikkahub.plugin.localdata.LocalDataAssistantRef
import me.rerere.rikkahub.plugin.localdata.LocalDataCapability
import me.rerere.rikkahub.plugin.localdata.LocalDataChatSnapshots
import me.rerere.rikkahub.plugin.localdata.LocalDataEnvelope
import me.rerere.rikkahub.plugin.localdata.LocalDataException
import me.rerere.rikkahub.plugin.localdata.LocalDataGrant
import me.rerere.rikkahub.plugin.localdata.LocalDataJson
import me.rerere.rikkahub.plugin.localdata.LocalDataMethods
import me.rerere.rikkahub.plugin.localdata.LocalDataRequestParser
import me.rerere.rikkahub.plugin.localdata.LocalDataSession
import me.rerere.rikkahub.plugin.localdata.LocalDataValidation
import me.rerere.rikkahub.plugin.localdata.LocalDraftDto
import me.rerere.rikkahub.plugin.localdata.PostVisibility
import me.rerere.rikkahub.plugin.localtasks.LocalAssistantPolicy
import me.rerere.rikkahub.plugin.localtasks.LocalTaskIds
import me.rerere.rikkahub.plugin.localtasks.LocalTaskSettings
import me.rerere.rikkahub.plugin.localtasks.LocalTaskSettingsStore
import me.rerere.rikkahub.plugin.localtasks.SharedPreferencesLocalTaskStore
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.ui.pages.chat.MomentsChatAvatar
import me.rerere.rikkahub.ui.pages.chat.MomentsChatImage
import me.rerere.rikkahub.ui.pages.chat.MomentsSnapshotResult
import me.rerere.rikkahub.ui.pages.chat.attachMomentsAvatars
import me.rerere.rikkahub.ui.pages.chat.attachMomentsMedia
import me.rerere.rikkahub.ui.pages.chat.buildMomentsChatSnapshot

/**
 * `moments_chat_draft` — the host-side write tool for assistant-initiated chat cards.
 *
 * Trust model:
 *  - The assistant id and conversation id come from [ToolInvocationContext] and the host-built
 *    [LocalMomentsChatTurn]. They are never read from tool arguments. A turn created for another
 *    assistant/conversation is rejected by [LocalMomentsChatDraftPolicy].
 *  - The model may only name message ids that the host already marked as the user's explicit
 *    selection for this conversation. It cannot search, enumerate or read messages from another
 *    conversation: [LocalMomentsChatDraftPlanner] resolves ids against [LocalMomentsChatTurn.messages]
 *    only.
 *  - Text and images come from the same pure builder the native share sheet uses
 *    ([buildMomentsChatSnapshot]). System/Tool messages, reasoning, tool calls and documents are
 *    excluded, and images are only copied when the host turned image sharing on, the native
 *    `localdata.media` grant is approved, and `LocalTaskSettings.allowImages` is on.
 *  - The tool always writes a draft via `moments.draft.save`. It never calls `moments.create`,
 *    so it cannot publish even when the assistant's policy is `auto_publish`.
 *  - The draft is written with the native [LocalDataActor.User] after the user approves the tool.
 *    This is deliberate: `moments.draft.list` is owner-isolated, so an assistant-authored draft
 *    would be invisible to the user. A user-authored draft appears in the existing draft list,
 *    where the user can see the card preview, edit it and publish it themselves.
 *
 * The native `Tool.needsApproval` gate is the pre-write approval. It is intentionally not an
 * auto-publish path.
 */
object LocalMomentsChatDraftContract {
    const val TOOL_NAME = "moments_chat_draft"

    /** Must stay in sync with `LocalMomentsContract.PLUGIN_ID` / the bundled plugin manifest. */
    const val PLUGIN_ID = "com.orangechat.localmoments"

    /** Must stay in sync with `LocalMomentsTools.HOST_GRANT_PREFERENCES`. */
    const val HOST_GRANT_PREFERENCES = "localdata_host_grants"

    const val DRAFT_METHOD = LocalDataMethods.DRAFT_SAVE
}

object LocalMomentsChatDraftLimits {
    const val MAX_MESSAGES = LocalDataApi.MAX_CHAT_MESSAGES
    const val MAX_IDS = LocalDataApi.MAX_CHAT_MESSAGES
    const val MAX_SELECTED_ASSISTANTS = LocalDataApi.MAX_SELECTED_ASSISTANTS
    const val MAX_IMAGE_BYTES = LocalDataApi.MAX_MEDIA_BYTES
    const val MAX_BASE64_CHARS = LocalDataApi.MAX_MEDIA_BYTES / 3 * 4 + 8
    const val IMAGE_DOWNLOAD_TIMEOUT_MS = 15_000
}

private val CANONICAL_UUID = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
)

/** A canonical `Uuid.toString()` value, never an arbitrary record id. */
internal fun isCanonicalUuid(value: String): Boolean =
    CANONICAL_UUID.matches(value) &&
        runCatching { Uuid.parse(value).toString() == value }.getOrDefault(false)

data class LocalMomentsChatDraftDenial(val code: String, val message: String)

/**
 * One host-owned chat turn. Every field is supplied by native code; none is derived from tool
 * arguments. [selectedMessageIds] is the user's explicit selection, not "all messages".
 */
data class LocalMomentsChatTurn(
    val conversationId: String,
    val assistantId: String,
    val title: String,
    val messages: List<UIMessage>,
    val selectedMessageIds: Collection<String>,
    val includeImages: Boolean,
    val visibility: PostVisibility = PostVisibility.SELF,
    val selectedAssistantIds: Collection<String> = emptyList(),
)

/** Pure parameter parser. Identity and conversation keys are rejected instead of silently ignored. */
object LocalMomentsChatDraftArgs {
    private val ALLOWED_KEYS = setOf("messageIds")

    private val EXPLICIT_IDENTITY_KEYS = setOf(
        "assistantId", "assistant_id", "conversationId", "conversation_id",
        "actor", "actorId", "actor_id", "viewer", "viewerId", "viewer_id",
        "userId", "user_id", "owner", "ownerId", "owner_id",
        "selectIndex", "index", "start", "limit", "visibility", "title",
    )

    fun parse(element: JsonElement): List<String> {
        val obj = element as? JsonObject
            ?: throw LocalMomentsChatDraftException("invalid_argument", "参数必须是 JSON 对象")
        rejectIdentity(obj)
        val unknown = obj.keys - ALLOWED_KEYS
        if (unknown.isNotEmpty()) {
            throw LocalMomentsChatDraftException(
                "unknown_parameter",
                "不支持的参数：${unknown.sorted().joinToString(",")}；只接受 messageIds",
            )
        }
        val array = obj["messageIds"] as? JsonArray
            ?: throw LocalMomentsChatDraftException("invalid_argument", "缺少 messageIds 数组")
        if (array.isEmpty()) {
            throw LocalMomentsChatDraftException("no_messages", "messageIds 不能为空")
        }
        if (array.size > LocalMomentsChatDraftLimits.MAX_IDS) {
            throw LocalMomentsChatDraftException(
                "too_many_messages",
                "最多 ${LocalMomentsChatDraftLimits.MAX_IDS} 条消息，收到 ${array.size} 条",
            )
        }
        val ids = array.map { item ->
            val primitive = item as? JsonPrimitive
                ?: throw LocalMomentsChatDraftException("invalid_argument", "messageIds 只能是字符串")
            val value = primitive.content.trim()
            if (!isCanonicalUuid(value)) {
                throw LocalMomentsChatDraftException(
                    "invalid_message_id",
                    "messageIds 里必须是小写规范 UUID，拒绝：${value.take(80)}",
                )
            }
            value
        }
        if (ids.distinct().size != ids.size) {
            throw LocalMomentsChatDraftException("duplicate_message_id", "messageIds 不能重复")
        }
        return ids
    }

    private fun rejectIdentity(obj: JsonObject) {
        try {
            LocalDataRequestParser.requireNoIdentityFields(obj)
        } catch (_: LocalDataException) {
            throw LocalMomentsChatDraftException(
                "identity_not_allowed",
                "助手与会话身份由宿主决定，参数里不能出现 actor/viewer/assistantId 等身份字段",
            )
        }
        val explicit = obj.keys.filter { it in EXPLICIT_IDENTITY_KEYS }
        if (explicit.isNotEmpty()) {
            throw LocalMomentsChatDraftException(
                "identity_not_allowed",
                "会话与消息范围由宿主决定，参数里不能出现 ${explicit.sorted().joinToString(",")}",
            )
        }
    }
}

/** Host-supplied display identity. Never read from tool arguments. */
data class LocalMomentsChatDraftIdentity(
    val userName: String,
    val assistantName: String,
    val userAvatar: Avatar = Avatar.Dummy,
    val assistantAvatar: Avatar = Avatar.Dummy,
)

/**
 * Builds the model-facing allowlist for one turn. This is deliberately derived from the trusted
 * [LocalMomentsChatTurn], not from tool arguments: the model can copy an id, but it cannot widen the
 * selection. Only USER/ASSISTANT visible Text and Image counts are listed; system prompts,
 * reasoning, tool calls/results and documents are never included.
 */
object LocalMomentsChatDraftPrompt {
    const val MAX_SNIPPET_CHARS = 280
    const val MAX_CONTEXT_CHARS = 4_000

    fun selectionContext(
        turn: LocalMomentsChatTurn,
        includeImages: Boolean = turn.includeImages,
    ): String {
        val selected = turn.selectedMessageIds
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        val lines = mutableListOf<String>()
        var used = 0
        for (message in turn.messages) {
            val id = message.id.toString()
            if (id !in selected) continue
            val role = when (message.role) {
                me.rerere.ai.core.MessageRole.USER -> "user"
                me.rerere.ai.core.MessageRole.ASSISTANT -> "assistant"
                else -> continue
            }
            val text = message.parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
                .trim()
            val imageCount = message.parts.count {
                it is UIMessagePart.Image && it.url.isNotBlank()
            }
            if (text.isBlank() && imageCount == 0) continue
            val normalized = text.map { character ->
                if (character.isISOControl()) ' ' else character
            }.joinToString("")
            val snippet = normalized
                .replace('"', '\'')
                .take(MAX_SNIPPET_CHARS)
            val truncated = text.length > snippet.length
            val line = buildString {
                append("- id=").append(id)
                append(" role=").append(role)
                append(" images=").append(imageCount)
                if (!includeImages && imageCount > 0) append("(disabled)")
                append(" text=\"")
                append(snippet)
                if (truncated) append('…')
                append('"')
            }
            if (used + line.length > MAX_CONTEXT_CHARS) {
                lines += "- （其余选中消息在此省略；只能使用上面列出的 id）"
                break
            }
            lines += line
            used += line.length
        }
        return buildString {
            appendLine("[本机朋友圈草稿] 本轮用户明确选中的消息如下；只能建议这些 id，不能跨会话查找：")
            if (lines.isEmpty()) {
                appendLine("- （没有可写入的可见正文或图片）")
            } else {
                lines.forEach { appendLine(it) }
            }
        }.trimEnd()
    }
}

internal data class LocalMomentsChatDraftPlan(
    val snapshotResult: MomentsSnapshotResult,
    val selectedMessages: List<UIMessage>,
) {
    val snapshot: LocalChatSnapshotDto
        get() = snapshotResult.snapshot
            ?: throw LocalMomentsChatDraftException("snapshot_unavailable", "聊天记录快照未生成")

    val images: List<MomentsChatImage> get() = snapshotResult.images
    val avatarImages: List<MomentsChatAvatar> get() = snapshotResult.avatarImages
    val messageCount: Int get() = snapshot.messages.size
    val imageCount: Int get() = images.size
    val totalChars: Int get() = snapshotResult.totalChars
}

internal sealed interface LocalMomentsChatDraftPlanResult {
    data class Ready(val plan: LocalMomentsChatDraftPlan) : LocalMomentsChatDraftPlanResult

    data class Denied(
        val code: String,
        val message: String,
        val problems: List<String> = emptyList(),
    ) : LocalMomentsChatDraftPlanResult
}

/**
 * Pure selection + projection layer.
 *
 * It intentionally has no Android dependency so JVM tests can pin the id-scope, filter and limit
 * behaviour. The only way a message can enter the card is: its id is in
 * [LocalMomentsChatTurn.selectedMessageIds], it is requested by the model, and it exists in the
 * trusted [LocalMomentsChatTurn.messages] list.
 */
internal object LocalMomentsChatDraftPlanner {
    fun plan(
        turn: LocalMomentsChatTurn,
        requestedIds: List<String>,
        identity: LocalMomentsChatDraftIdentity,
        includeImages: Boolean,
        now: Long = System.currentTimeMillis(),
    ): LocalMomentsChatDraftPlanResult {
        if (requestedIds.isEmpty()) {
            return denied("no_messages", "messageIds 不能为空")
        }
        if (requestedIds.size > LocalMomentsChatDraftLimits.MAX_IDS) {
            return denied(
                "too_many_messages",
                "最多 ${LocalMomentsChatDraftLimits.MAX_IDS} 条消息，收到 ${requestedIds.size} 条",
            )
        }
        val normalizedIds = requestedIds.map { it.trim().lowercase() }
        if (normalizedIds.distinct().size != normalizedIds.size) {
            return denied("duplicate_message_id", "messageIds 不能重复")
        }
        val selection = turn.selectedMessageIds
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (selection.isEmpty()) {
            return denied("no_user_selection", "当前会话没有用户明确选中的消息，不能创建草稿")
        }

        val indexById = HashMap<String, Int>(turn.messages.size)
        turn.messages.forEachIndexed { index, message ->
            indexById[message.id.toString()] = index
        }

        val indexes = mutableListOf<Int>()
        for (id in normalizedIds) {
            if (!isCanonicalUuid(id)) {
                return denied("invalid_message_id", "消息 id 必须是规范 UUID：${id.take(80)}")
            }
            if (id !in selection) {
                return denied(
                    "message_not_selected",
                    "消息 $id 不在当前会话的用户选择里，不能建议未选中的消息",
                )
            }
            val index = indexById[id]
                ?: return denied(
                    "message_not_found",
                    "消息 $id 不在当前会话中；不能跨会话读取消息",
                )
            indexes += index
        }
        indexes.sort()
        val selectedMessages = indexes.map { turn.messages[it] }

        val visibilityDenial = validateVisibility(turn)
        if (visibilityDenial != null) return visibilityDenial

        val result = buildMomentsChatSnapshot(
            title = turn.title,
            messages = selectedMessages,
            userName = identity.userName,
            assistantName = identity.assistantName,
            includeImages = includeImages,
            now = now,
            // An image avatar is image bytes too. When image sharing is not authorized, degrade it
            // to the text fallback instead of silently importing it behind the user's back.
            userAvatar = gatedAvatar(identity.userAvatar, includeImages),
            assistantAvatar = gatedAvatar(identity.assistantAvatar, includeImages),
        )
        if (!result.canWrite) {
            return LocalMomentsChatDraftPlanResult.Denied(
                code = "snapshot_limit",
                message = result.problems.joinToString("；").ifBlank { "聊天记录卡片未通过本地上限检查" },
                problems = result.problems.toList(),
            )
        }
        return LocalMomentsChatDraftPlanResult.Ready(
            LocalMomentsChatDraftPlan(
                snapshotResult = result,
                selectedMessages = selectedMessages,
            ),
        )
    }

    private fun validateVisibility(turn: LocalMomentsChatTurn): LocalMomentsChatDraftPlanResult.Denied? {
        val selected = turn.selectedAssistantIds.map { it.trim() }.filter { it.isNotEmpty() }
        if (turn.visibility == PostVisibility.SELECTED) {
            if (selected.isEmpty()) {
                return denied("visibility_selection_required", "可见范围是 selected 时必须至少选择一个助手")
            }
            if (selected.size > LocalMomentsChatDraftLimits.MAX_SELECTED_ASSISTANTS) {
                return denied("too_many_selected_assistants", "指定的可见助手过多")
            }
            if (selected.distinct().size != selected.size) {
                return denied("duplicate_selected_assistant", "指定的可见助手不能重复")
            }
            if (selected.any { !LocalDataValidation.isSafeAssistantId(it) }) {
                return denied("invalid_selected_assistant", "指定的可见助手 id 不合法")
            }
        }
        return null
    }

    private fun denied(code: String, message: String) =
        LocalMomentsChatDraftPlanResult.Denied(code = code, message = message)

    private fun gatedAvatar(avatar: Avatar, allowImages: Boolean): Avatar =
        if (!allowImages && avatar is Avatar.Image) Avatar.Dummy else avatar
}

/** Host facts used by the pure draft ACL. */
data class LocalMomentsChatDraftAccessSnapshot(
    val pluginInstalled: Boolean,
    val pluginEnabled: Boolean,
    val pluginLoadError: String? = null,
    val grantedCapabilities: Set<String> = emptySet(),
    val taskSettings: LocalTaskSettings = LocalTaskSettings(),
    val assistantId: String? = null,
    val turnAssistantId: String? = null,
    val assistantKnown: Boolean = false,
    val headless: Boolean = false,
    val conversationId: String? = null,
    val turnConversationId: String? = null,
    val userSelectionAvailable: Boolean = false,
    val imagesRequested: Boolean = false,
)

data class LocalMomentsChatDraftAccess(
    val denial: LocalMomentsChatDraftDenial? = null,
    val canUseImages: Boolean = false,
    val imageOmissionReason: String? = null,
) {
    val allowed: Boolean get() = denial == null
}

/**
 * Pure ACL for draft creation. It requires the write + chat-snapshot native grants (plus media
 * only when images are requested) and the same assistant-task opt-in the read tools use. It never
 * requires `localdata.publish`: this tool cannot publish.
 */
object LocalMomentsChatDraftPolicy {
    fun evaluate(snapshot: LocalMomentsChatDraftAccessSnapshot): LocalMomentsChatDraftAccess {
        fun deny(code: String, message: String) =
            LocalMomentsChatDraftAccess(denial = LocalMomentsChatDraftDenial(code, message))

        if (!snapshot.pluginInstalled) {
            return deny("plugin_not_installed", "本机朋友圈插件未安装，不能创建草稿")
        }
        if (!snapshot.pluginEnabled || snapshot.pluginLoadError != null) {
            return deny("plugin_disabled", "本机朋友圈插件未启用，不能创建草稿")
        }
        if (LocalDataCapability.WRITE.wire !in snapshot.grantedCapabilities) {
            return deny("write_not_granted", "宿主未授权本机朋友圈的 localdata.write，不能创建草稿")
        }
        if (LocalDataCapability.CHAT_SNAPSHOT.wire !in snapshot.grantedCapabilities) {
            return deny(
                "chat_snapshot_not_granted",
                "宿主未授权 localdata.chat_snapshot，不能写入聊天记录卡片草稿",
            )
        }
        if (snapshot.headless) {
            return deny("headless_call", "朋友圈草稿工具只允许在用户正常发起的聊天中调用")
        }
        val assistantId = snapshot.assistantId
        if (assistantId.isNullOrBlank() || !LocalDataValidation.isSafeAssistantId(assistantId)) {
            return deny("assistant_identity_missing", "缺少宿主确认的助手身份，不能创建草稿")
        }
        if (snapshot.turnAssistantId != assistantId) {
            return deny("assistant_mismatch", "工具绑定的助手与当前调用助手不一致，不能创建草稿")
        }
        if (!snapshot.assistantKnown) {
            return deny("assistant_unknown", "当前助手不在宿主助手列表中，不能创建草稿")
        }

        val settings = snapshot.taskSettings.normalized()
        if (!settings.enabled) {
            return deny("assistant_tasks_disabled", "朋友圈助手互动未启用，不能创建草稿")
        }
        if (settings.globalStop) {
            return deny("assistant_tasks_stopped", "朋友圈助手互动已被全局停止，不能创建草稿")
        }
        if (assistantId !in settings.participants) {
            return deny("assistant_not_participant", "当前助手不是朋友圈参与助手，不能创建草稿")
        }
        if (settings.effectivePolicy(assistantId) == LocalAssistantPolicy.DISABLED) {
            return deny("assistant_policy_disabled", "当前助手的朋友圈策略为 disabled，不能创建草稿")
        }

        val conversationId = snapshot.conversationId
        if (conversationId.isNullOrBlank() || !isCanonicalUuid(conversationId)) {
            return deny("conversation_identity_missing", "缺少宿主确认的会话身份，不能创建草稿")
        }
        if (snapshot.turnConversationId != conversationId) {
            return deny("conversation_mismatch", "工具绑定的会话与当前调用会话不一致，不能创建草稿")
        }
        if (!snapshot.userSelectionAvailable) {
            return deny("no_user_selection", "当前会话没有用户明确选中的消息，不能创建草稿")
        }

        val hasMediaGrant = LocalDataCapability.MEDIA.wire in snapshot.grantedCapabilities
        val canUseImages = snapshot.imagesRequested && hasMediaGrant && settings.allowImages
        val omission = when {
            !snapshot.imagesRequested -> null
            !hasMediaGrant -> "media_not_granted"
            !settings.allowImages -> "image_draft_not_allowed"
            else -> null
        }
        return LocalMomentsChatDraftAccess(
            denial = null,
            canUseImages = canUseImages,
            imageOmissionReason = omission,
        )
    }
}

/** Stable JSON result envelope, including the explicit `published=false` receipt. */
internal object LocalMomentsChatDraftOutput {
    fun success(
        draft: LocalDraftDto,
        plan: LocalMomentsChatDraftPlan,
        visibility: PostVisibility,
        imageOmissionReason: String? = null,
    ): String = buildJsonObject {
        put("ok", true)
        put("tool", LocalMomentsChatDraftContract.TOOL_NAME)
        put("published", false)
        put("draft", buildJsonObject {
            put("id", draft.id)
            put("author", "user")
            put("visibility", visibility.wireName())
            put("reviewState", "pending_user_review")
            put("messageCount", plan.messageCount)
            put("imageCount", plan.imageCount)
            put("imagesOmitted", plan.snapshotResult.omittedImages)
            imageOmissionReason?.let { put("imagesOmittedReason", it) }
            put("totalChars", plan.totalChars)
        })
        put("review", buildJsonObject {
            put("where", "moments_drafts")
            put("hint", "已保存到本机朋友圈草稿箱；只有你能看到，请预览、编辑后自行发布。未发布。")
        })
    }.toString()

    fun error(
        code: String,
        message: String,
        problems: List<String> = emptyList(),
    ): String = buildJsonObject {
        put("ok", false)
        put("tool", LocalMomentsChatDraftContract.TOOL_NAME)
        put("published", false)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message.take(500))
            put("retryable", false)
            if (problems.isNotEmpty()) {
                putJsonArray("problems") { problems.take(8).forEach { add(it.take(300)) } }
            }
        })
    }.toString()
}

internal class LocalMomentsChatDraftException(
    val code: String,
    override val message: String,
    val problems: List<String> = emptyList(),
) : Exception(message)

/**
 * Host tool factory. Wire it from `ChatService.buildAvailableTools` with a turn that contains the
 * trusted conversation id/assistant id/messages and the user's explicit message selection.
 *
 * `build` returns an empty list when the turn is missing, headless, bound to a different
 * assistant/conversation, or when any native grant/task-policy gate fails. The model therefore
 * never sees a schema it cannot use.
 */
class LocalMomentsChatDraftTool(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val pluginManager: PluginManager,
) {
    fun build(
        invocationContext: ToolInvocationContext,
        turn: LocalMomentsChatTurn?,
    ): List<Tool> {
        if (turn == null) return emptyList()
        if (invocationContext.isHeadless) return emptyList()
        if (turn.assistantId != invocationContext.callerAssistantId) return emptyList()
        if (turn.conversationId != invocationContext.callerConversationId) return emptyList()
        if (turn.selectedMessageIds.isEmpty()) return emptyList()
        if (turn.messages.isEmpty()) return emptyList()
        val access = LocalMomentsChatDraftPolicy.evaluate(currentSnapshot(invocationContext, turn))
        if (!access.allowed) return emptyList()
        return listOf(draftTool(invocationContext, turn, access))
    }

    private fun draftTool(
        invocationContext: ToolInvocationContext,
        turn: LocalMomentsChatTurn,
        access: LocalMomentsChatDraftAccess,
    ): Tool = Tool(
        name = LocalMomentsChatDraftContract.TOOL_NAME,
        description = """
            把当前会话中用户明确选中的消息保存成本机朋友圈的聊天记录卡片草稿（只创建草稿，绝不发布）。
            必须传 messageIds：只能使用当前会话里用户已经选中的消息 id，不能跨会话搜索或编造 id。
            只会写入用户与助手可见的正文；系统提示词、思考过程、工具调用/结果和文档不会进入卡片。
            图片只有用户已开启分享、宿主已授权 localdata.media 且朋友圈图片开关打开时才会复制。
            调用需要用户原生批准。批准后会写入本机用户的草稿箱（不是发布），用户可预览、编辑并自行发布。
            每张卡片最多 ${LocalMomentsChatDraftLimits.MAX_MESSAGES} 条消息；超限会被拒绝而不是静默截断。
        """.trimIndent().replace("\n", " "),
        systemPrompt = { _, _ ->
            buildString {
                appendLine(
                    """
                    ## 本机朋友圈草稿
                    只有当用户明确要求把选中的聊天记录保存为本机朋友圈卡片时，才调用 ${LocalMomentsChatDraftContract.TOOL_NAME}。
                    只传当前会话中用户明确选中的消息 id；不要猜测、不要跨会话查找、不要把系统/思考/工具内容当成正文。
                    调用前先在回复里按顺序复述将要写入的每条消息（谁说的、正文、图片数量），让用户确认完整预览；
                    不要只给消息 id。该工具只创建待审核草稿，不会发布；不要向用户声称已经发布。
                    写入成功后告诉用户到「本机朋友圈 → 草稿」预览、编辑并自行发布。
                    """.trimIndent(),
                )
                appendLine()
                append(LocalMomentsChatDraftPrompt.selectionContext(turn, access.canUseImages))
            }
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(
                        "messageIds",
                        buildJsonObject {
                            put(
                                "type",
                                "array",
                            )
                            put(
                                "description",
                                "当前会话中用户明确选中的消息 id（规范 UUID），" +
                                    "1..${LocalMomentsChatDraftLimits.MAX_IDS} 条",
                            )
                            put("items", buildJsonObject { put("type", "string") })
                            put("minItems", 1)
                            put("maxItems", LocalMomentsChatDraftLimits.MAX_IDS)
                            put("uniqueItems", true)
                        },
                    )
                },
                required = listOf("messageIds"),
            )
        },
        // Native approval is the pre-write gate. `moments.draft.save` is still the only write.
        needsApproval = true,
        execute = { element ->
            runTool {
                val runtime = requireRuntime(invocationContext, turn)
                val requestedIds = LocalMomentsChatDraftArgs.parse(element)
                val planResult = LocalMomentsChatDraftPlanner.plan(
                    turn = turn,
                    requestedIds = requestedIds,
                    identity = runtime.identity,
                    includeImages = runtime.access.canUseImages,
                )
                val plan = when (planResult) {
                    is LocalMomentsChatDraftPlanResult.Ready -> planResult.plan
                    is LocalMomentsChatDraftPlanResult.Denied -> throw LocalMomentsChatDraftException(
                        planResult.code,
                        planResult.message,
                        planResult.problems,
                    )
                }
                val saved = withContext(Dispatchers.IO) { saveDraft(turn, runtime, plan) }
                listOf(
                    UIMessagePart.Text(
                        LocalMomentsChatDraftOutput.success(
                            draft = saved,
                            plan = plan,
                            visibility = turn.visibility,
                            imageOmissionReason = runtime.access.imageOmissionReason,
                        ),
                    ),
                )
            }
        },
    )

    private data class Runtime(
        val identity: LocalMomentsChatDraftIdentity,
        val access: LocalMomentsChatDraftAccess,
        val directory: LocalAssistantDirectory,
        val userDisplayName: String,
    )

    private fun requireRuntime(
        invocationContext: ToolInvocationContext,
        turn: LocalMomentsChatTurn,
    ): Runtime {
        if (invocationContext.isHeadless) {
            throw LocalMomentsChatDraftException(
                "headless_call",
                "朋友圈草稿工具只允许在用户正常发起的聊天中调用",
            )
        }
        if (turn.assistantId != invocationContext.callerAssistantId) {
            throw LocalMomentsChatDraftException(
                "assistant_mismatch",
                "工具绑定的助手与当前调用助手不一致",
            )
        }
        if (turn.conversationId != invocationContext.callerConversationId) {
            throw LocalMomentsChatDraftException(
                "conversation_mismatch",
                "工具绑定的会话与当前调用会话不一致",
            )
        }
        val snapshot = currentSnapshot(invocationContext, turn)
        val access = LocalMomentsChatDraftPolicy.evaluate(snapshot)
        access.denial?.let {
            throw LocalMomentsChatDraftException(it.code, it.message)
        }
        val settings = settingsStore.settingsFlow.value
        val assistantId = snapshot.assistantId
            ?: throw LocalMomentsChatDraftException("assistant_identity_missing", "缺少助手身份")
        val assistant = settings.assistants.firstOrNull { it.id.toString() == assistantId }
            ?: throw LocalMomentsChatDraftException("assistant_unknown", "当前助手不在宿主助手列表中")
        return Runtime(
            identity = identityOf(settings, assistant),
            access = access,
            directory = directoryOf(settings),
            userDisplayName = settings.displaySetting.userNickname.ifBlank { "我" },
        )
    }

    private fun currentSnapshot(
        invocationContext: ToolInvocationContext,
        turn: LocalMomentsChatTurn,
    ): LocalMomentsChatDraftAccessSnapshot {
        val settings = settingsStore.settingsFlow.value
        val grants = grants()
        val assistantId = invocationContext.callerAssistantId
        val assistant = assistantId?.let { id ->
            settings.assistants.firstOrNull { it.id.toString() == id }
        }
        return LocalMomentsChatDraftAccessSnapshot(
            pluginInstalled = grants.pluginInstalled,
            pluginEnabled = grants.pluginEnabled,
            pluginLoadError = grants.pluginLoadError,
            grantedCapabilities = grants.declaredCapabilities intersect grants.approvedCapabilities,
            taskSettings = taskSettings(),
            assistantId = assistantId,
            turnAssistantId = turn.assistantId,
            assistantKnown = assistant != null,
            headless = invocationContext.isHeadless,
            conversationId = invocationContext.callerConversationId,
            turnConversationId = turn.conversationId,
            userSelectionAvailable = turn.selectedMessageIds.isNotEmpty(),
            imagesRequested = turn.includeImages,
        )
    }

    private data class GrantSnapshot(
        val pluginInstalled: Boolean,
        val pluginEnabled: Boolean,
        val pluginLoadError: String?,
        val declaredCapabilities: Set<String>,
        val approvedCapabilities: Set<String>,
    )

    private fun grants(): GrantSnapshot {
        val plugin = pluginManager.getPlugin(LocalMomentsChatDraftContract.PLUGIN_ID)
        val approved = context.getSharedPreferences(
            LocalMomentsChatDraftContract.HOST_GRANT_PREFERENCES,
            Context.MODE_PRIVATE,
        ).getStringSet(LocalMomentsChatDraftContract.PLUGIN_ID, emptySet()).orEmpty()
        return GrantSnapshot(
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
            LocalTaskIds.storeName(LocalMomentsChatDraftContract.PLUGIN_ID),
        ),
    ).get()

    private fun identityOf(settings: Settings, assistant: Assistant): LocalMomentsChatDraftIdentity =
        LocalMomentsChatDraftIdentity(
            userName = settings.displaySetting.userNickname.ifBlank { "我" },
            assistantName = assistant.name.ifBlank { "助手" },
            userAvatar = settings.displaySetting.userAvatar,
            assistantAvatar = assistant.avatar,
        )

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
        is Avatar.Image -> avatar.url.takeIf {
            it.startsWith("https://") || it.startsWith("http://")
        }
    }

    private fun saveDraft(
        turn: LocalMomentsChatTurn,
        runtime: Runtime,
        plan: LocalMomentsChatDraftPlan,
    ): LocalDraftDto {
        val capabilities = buildSet {
            add(LocalDataCapability.WRITE)
            add(LocalDataCapability.CHAT_SNAPSHOT)
            if (plan.images.isNotEmpty() || plan.avatarImages.isNotEmpty()) {
                add(LocalDataCapability.MEDIA)
            }
        }
        LocalDataSession(
            context = context.applicationContext,
            pluginId = LocalMomentsChatDraftContract.PLUGIN_ID,
            grant = LocalDataGrant.of(*capabilities.toTypedArray()),
        ).use { session ->
            val importer = LocalMomentsChatDraftMediaImporter()
            val resourceIds = plan.images.mapIndexed { index, image ->
                try {
                    importer.import(session, image)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    throw LocalMomentsChatDraftException(
                        "image_import_failed",
                        "第 ${index + 1} 张图片导入失败：${error.message ?: "格式不支持或文件不可读"}",
                    )
                }
            }
            // Avatars are decoration, not body content: a broken avatar never blocks the draft and
            // its source URL/base64 is never written into the snapshot.
            val avatarIds = plan.avatarImages.associate { avatar ->
                avatar.role to importer.importAvatar(session, avatar)
            }
            val snapshot = attachMomentsAvatars(
                snapshot = attachMomentsMedia(plan.snapshot, plan.images, resourceIds),
                userAvatarMediaId = avatarIds[me.rerere.ai.core.MessageRole.USER],
                assistantAvatarMediaId = avatarIds[me.rerere.ai.core.MessageRole.ASSISTANT],
            )
            try {
                LocalDataChatSnapshots.encode(snapshot)
            } catch (error: IllegalArgumentException) {
                throw LocalMomentsChatDraftException(
                    "snapshot_too_large",
                    "聊天记录快照超过 ${LocalDataApi.MAX_CHAT_SNAPSHOT_BYTES / 1024} KB 上限；" +
                        "请减少消息或图片后重试",
                )
            }
            val request = buildDraftSaveRequest(turn, snapshot)
            check(pluginManager.getPlugin(LocalMomentsChatDraftContract.PLUGIN_ID)?.isEnabled == true) {
                "本机朋友圈已停用或卸载，未写入草稿"
            }
            val raw = session.call(
                requestJson = request,
                actor = LocalDataActor.User,
                assistantDirectory = runtime.directory,
                userDisplayName = runtime.userDisplayName,
            )
            val envelope = LocalDataJson.json.decodeFromString(LocalDataEnvelope.serializer(), raw)
            if (!envelope.ok) {
                throw LocalMomentsChatDraftException(
                    envelope.error?.code ?: "localdata_error",
                    envelope.error?.message ?: "草稿写入失败",
                )
            }
            val payload = envelope.data
                ?: throw LocalMomentsChatDraftException("unavailable", "本地朋友圈没有返回草稿")
            return LocalDataJson.json.decodeFromJsonElement(LocalDraftDto.serializer(), payload)
        }
    }

    private fun buildDraftSaveRequest(
        turn: LocalMomentsChatTurn,
        snapshot: LocalChatSnapshotDto,
    ): String {
        val snapshotElement = LocalDataJson.json.encodeToJsonElement(
            LocalChatSnapshotDto.serializer(),
            snapshot,
        )
        val selected = if (turn.visibility == PostVisibility.SELECTED) {
            turn.selectedAssistantIds.map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        return buildJsonObject {
            put("version", LocalDataApi.VERSION)
            put("method", LocalMomentsChatDraftContract.DRAFT_METHOD)
            put("params", buildJsonObject {
                put("visibility", turn.visibility.wireName())
                putJsonArray("selectedAssistantIds") { selected.forEach { add(it) } }
                put("chatSnapshot", snapshotElement)
            })
        }.toString()
    }

    private suspend fun runTool(
        block: suspend () -> List<UIMessagePart>,
    ): List<UIMessagePart> = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: LocalMomentsChatDraftException) {
        toolError(error.code, error.message, error.problems)
    } catch (error: LocalDataException) {
        toolError(error.code.wire, error.message)
    } catch (error: Exception) {
        toolError(
            "unavailable",
            "创建朋友圈草稿失败：${error.message?.take(160) ?: "未知错误"}",
        )
    }

    private fun toolError(
        code: String,
        message: String,
        problems: List<String> = emptyList(),
    ): List<UIMessagePart> =
        listOf(UIMessagePart.Text(LocalMomentsChatDraftOutput.error(code, message, problems)))
}

private fun PostVisibility.wireName(): String = when (this) {
    PostVisibility.ALL_ASSISTANTS -> "all_assistants"
    PostVisibility.SELECTED -> "selected"
    PostVisibility.SELF -> "self"
}

/**
 * Lead-facing wiring helper, mirroring `buildLocalMomentsTools`.
 *
 * The caller must pass the host's own [SettingsStore] and [PluginManager], a [ToolInvocationContext]
 * carrying the real assistant/conversation ids, and a [LocalMomentsChatTurn] built from the trusted
 * conversation plus the user's explicit message selection. Returns an empty list when the turn is
 * missing or any native gate denies draft creation.
 */
fun buildLocalMomentsChatDraftTool(
    context: Context,
    settingsStore: SettingsStore,
    pluginManager: PluginManager,
    invocationContext: ToolInvocationContext,
    turn: LocalMomentsChatTurn?,
): List<Tool> = LocalMomentsChatDraftTool(
    context = context,
    settingsStore = settingsStore,
    pluginManager = pluginManager,
).build(invocationContext, turn)

/**
 * Bounded importer for the same image sources the native share sheet accepts. It never exposes a
 * source path/URL to the plugin: only the opaque `ldm_...` id enters the snapshot.
 */
private class LocalMomentsChatDraftMediaImporter {
    fun import(session: LocalDataSession, image: MomentsChatImage): String {
        val url = image.url
        return when {
            url.startsWith("data:") -> importDataUri(session, url)
            url.startsWith("content:") -> session.importImageFromUri(
                Uri.parse(url),
                "聊天图片",
            ).media.resourceId

            url.startsWith("file:") -> {
                val path = Uri.parse(url).path ?: error("图片文件路径无效")
                val file = File(path)
                require(file.isFile) { "图片文件不存在" }
                require(file.length() <= LocalMomentsChatDraftLimits.MAX_IMAGE_BYTES) {
                    "图片超过 12 MB 上限"
                }
                session.importImageBytes(file.readBytes(), null, "聊天图片").media.resourceId
            }

            url.startsWith("http://") || url.startsWith("https://") -> {
                session.importImageBytes(downloadBounded(url), null, "聊天图片").media.resourceId
            }

            else -> error("不支持的图片来源")
        }
    }

    fun importAvatar(session: LocalDataSession, avatar: MomentsChatAvatar): String? = try {
        import(session, MomentsChatImage(messageIndex = 0, url = avatar.url))
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun importDataUri(session: LocalDataSession, url: String): String {
        val separator = url.indexOf(',')
        require(separator > 5) { "图片 data URI 无效" }
        val header = url.substring(5, separator)
        require(header.endsWith(";base64")) { "图片 data URI 不是 base64" }
        val mime = header.substringBefore(';')
        val payload = url.substring(separator + 1)
        require(payload.length <= LocalMomentsChatDraftLimits.MAX_BASE64_CHARS) {
            "图片超过 12 MB 上限"
        }
        val bytes = Base64.getDecoder().decode(payload)
        require(bytes.size <= LocalMomentsChatDraftLimits.MAX_IMAGE_BYTES) {
            "图片超过 12 MB 上限"
        }
        return session.importImageBytes(bytes, mime, "聊天图片").media.resourceId
    }

    private fun downloadBounded(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = LocalMomentsChatDraftLimits.IMAGE_DOWNLOAD_TIMEOUT_MS
            readTimeout = LocalMomentsChatDraftLimits.IMAGE_DOWNLOAD_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "OrangeChat-MomentsDraft/1.0")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            if (connection.contentLengthLong > LocalMomentsChatDraftLimits.MAX_IMAGE_BYTES) {
                throw IOException("图片超过 12 MB 上限")
            }
            return connection.inputStream.use {
                readBounded(it, LocalMomentsChatDraftLimits.MAX_IMAGE_BYTES)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IOException("图片超过 12 MB 上限")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
