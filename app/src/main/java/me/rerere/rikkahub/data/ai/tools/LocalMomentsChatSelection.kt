/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.plugin.localdata.LocalDataApi
import me.rerere.rikkahub.plugin.localdata.LocalDataValidation
import me.rerere.rikkahub.plugin.localdata.PostVisibility
import me.rerere.rikkahub.ui.pages.chat.buildMomentsChatSnapshot

/**
 * One user-authorized "let the assistant draft a Moments card" request.
 *
 * The whole point of this object is that it is *host-owned*:
 *  - [messages] is the conversation snapshot taken when the user clicked the action. It is never
 *    re-read from "all private chats" and never widened by the model.
 *  - [selectedMessageIds] is exactly the user's selection at that moment.
 *  - [anchorMessageId] is the user instruction message the host is about to send. The selection is
 *    valid only while that message is the newest user message in the same conversation, so it is
 *    a one-shot turn scope: the next user message retires it.
 *  - [includeImages] is the *effective* image permission (native grant + assistant task setting).
 *    The approval preview and the executed draft therefore agree on what will be written.
 */
data class LocalMomentsChatSelection(
    val conversationId: String,
    val assistantId: String,
    val anchorMessageId: String,
    val title: String,
    val messages: List<UIMessage>,
    val selectedMessageIds: List<String>,
    val includeImagesRequested: Boolean,
    val includeImages: Boolean,
    val identity: LocalMomentsChatDraftIdentity,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val visibility: PostVisibility = PostVisibility.SELF,
) {
    /** The trusted turn handed to the native draft tool. Identity/visibility stay host-decided. */
    fun toTurn(): LocalMomentsChatTurn = LocalMomentsChatTurn(
        conversationId = conversationId,
        assistantId = assistantId,
        title = title,
        messages = messages,
        selectedMessageIds = selectedMessageIds,
        // The tool re-evaluates the live permission; this flag is what the user asked for.
        includeImages = includeImagesRequested,
        visibility = visibility,
        selectedAssistantIds = emptyList(),
    )
}

sealed interface LocalMomentsChatSelectionPrepareResult {
    data class Prepared(
        val anchorMessageId: String,
        val messageCount: Int,
        val imageOmissionReason: String? = null,
    ) : LocalMomentsChatSelectionPrepareResult

    data class Rejected(val message: String) : LocalMomentsChatSelectionPrepareResult
}

/** One row of the approval preview. Public so the tool-approval UI can render it. */
data class LocalMomentsChatPreviewMessage(
    val role: MessageRole,
    val name: String,
    val text: String,
    val imageUrls: List<String>,
)

/**
 * The complete host-generated card preview shown before the native tool approval.
 *
 * [previewMessages] is produced by the same [buildMomentsChatSnapshot] the share sheet and the
 * write tool use, so system messages, reasoning, tool calls and documents are filtered by the
 * same rules, and the 50-message / 20,000-character / 9-image / 32 KB limits are the real limits.
 */
data class LocalMomentsChatDraftPreview(
    val available: Boolean,
    val unavailableReason: String? = null,
    val requestedMessageIds: List<String> = emptyList(),
    val previewMessages: List<LocalMomentsChatPreviewMessage> = emptyList(),
    val selectedCount: Int = 0,
    val totalChars: Int = 0,
    val includeImages: Boolean = false,
    val imageCount: Int = 0,
    val omittedImages: Int = 0,
    val excludedMessages: Int = 0,
    val excludedParts: Int = 0,
    val problems: List<String> = emptyList(),
) {
    val canWrite: Boolean get() = available && problems.isEmpty() && previewMessages.isNotEmpty()

    companion object {
        /** The draft is saved under the local user, who is the only one who can publish it. */
        const val AUTHOR_NOTE =
            "草稿作者是你（本机用户）；由助手整理，助手不能替你发布。批准后只会写入你的本机朋友圈草稿箱，"
    }
}

/**
 * The real user instruction sent through the normal chat pipeline. It names the selected message
 * ids only through the tool prompt; the text itself never carries private chat content.
 */
object LocalMomentsChatDraftInstruction {
    fun text(messageCount: Int, includeImages: Boolean): String = buildString {
        append("请帮我把这次选中的 ")
        append(messageCount)
        append(" 条聊天记录整理成一条本机朋友圈草稿。")
        if (includeImages) append("选中的图片可以一起放进卡片。")
        append("请先按顺序把将要写入的每条消息完整复述成预览（谁说的、正文、图片数量），")
        append("然后调用 ")
        append(LocalMomentsChatDraftContract.TOOL_NAME)
        append(" 保存草稿；messageIds 只能使用本轮选中的消息 id。")
        append("只创建等我审核的草稿，不要发布，也不要声称已经发布；草稿作者是我本人，由你整理。")
    }
}

/**
 * Process-wide one-shot holder. At most one draft selection exists at a time:
 *  - [prepare] replaces any previous request, so a second click never widens the first one.
 *  - [turnFor] only returns the turn when conversation, assistant and the newest user message all
 *    still match. A newer user message retires the selection, so it cannot be replayed in a later
 *    turn, and another assistant/conversation can never pick it up.
 *  - [clear] is called when the user stops generation, withdraws the instruction, or leaves the
 *    conversation.
 *
 * The store is deliberately free of Android dependencies so JVM tests can pin the scope rules.
 */
object LocalMomentsChatSelectionStore {
    private val lock = Any()
    private var active: LocalMomentsChatSelection? = null

    fun prepare(selection: LocalMomentsChatSelection): LocalMomentsChatSelectionPrepareResult {
        if (!isCanonicalUuid(selection.conversationId)) {
            return LocalMomentsChatSelectionPrepareResult.Rejected("会话 id 无效，无法整理朋友圈草稿")
        }
        if (!isCanonicalUuid(selection.anchorMessageId)) {
            return LocalMomentsChatSelectionPrepareResult.Rejected("消息锚点无效，无法整理朋友圈草稿")
        }
        if (!LocalDataValidation.isSafeAssistantId(selection.assistantId)) {
            return LocalMomentsChatSelectionPrepareResult.Rejected("助手 id 无效，无法整理朋友圈草稿")
        }
        val ids = selection.selectedMessageIds.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (ids.isEmpty()) {
            return LocalMomentsChatSelectionPrepareResult.Rejected("请先选择要整理的消息")
        }
        if (ids.size > LocalDataApi.MAX_CHAT_MESSAGES) {
            return LocalMomentsChatSelectionPrepareResult.Rejected(
                "一张朋友圈卡片最多 ${LocalDataApi.MAX_CHAT_MESSAGES} 条消息，请减少选择后重试",
            )
        }
        if (ids.distinct().size != ids.size) {
            return LocalMomentsChatSelectionPrepareResult.Rejected("选择的消息不能重复")
        }
        val byId = selection.messages.associateBy { it.id.toString() }
        for (id in ids) {
            if (!isCanonicalUuid(id)) {
                return LocalMomentsChatSelectionPrepareResult.Rejected("消息 id 不是规范 UUID，无法整理草稿")
            }
            val message = byId[id]
                ?: return LocalMomentsChatSelectionPrepareResult.Rejected("选中的消息已不在当前会话，请重新选择")
            if (message.role != MessageRole.USER && message.role != MessageRole.ASSISTANT) {
                return LocalMomentsChatSelectionPrepareResult.Rejected("只能整理你和助手的聊天消息")
            }
        }
        synchronized(lock) { active = selection.copy(selectedMessageIds = ids) }
        return LocalMomentsChatSelectionPrepareResult.Prepared(
            anchorMessageId = selection.anchorMessageId,
            messageCount = ids.size,
            imageOmissionReason = when {
                !selection.includeImagesRequested -> null
                selection.includeImages -> null
                else -> "image_not_available"
            },
        )
    }

    fun current(conversationId: String? = null): LocalMomentsChatSelection? = synchronized(lock) {
        active?.takeIf { conversationId == null || it.conversationId == conversationId }
    }

    /**
     * Consumes nothing by itself: the turn stays available for every step of the same assistant
     * turn (including the approval resume). It becomes unavailable as soon as the newest user
     * message is no longer the anchor, which is the one-shot rule.
     *
     * [messages] is the host's current conversation window. While the instruction is still queued
     * (the anchor is not in the conversation yet) the selection simply waits; once the anchor is
     * present but a newer user message exists, the one-shot selection is spent and cleared.
     */
    fun turnFor(
        conversationId: String,
        assistantId: String,
        messages: List<UIMessage>,
    ): LocalMomentsChatTurn? {
        return synchronized(lock) {
            val selection = active ?: return@synchronized null
            if (selection.conversationId != conversationId) return@synchronized null
            if (selection.assistantId != assistantId) {
                // The conversation now generates with another assistant; the binding is spent.
                active = null
                return@synchronized null
            }
            val latestUser = messages.lastOrNull { it.role == MessageRole.USER }
            if (latestUser?.id?.toString() == selection.anchorMessageId) {
                selection.toTurn()
            } else if (messages.none { it.id.toString() == selection.anchorMessageId }) {
                // The instruction is still queued (or was never delivered); wait, do not widen scope.
                null
            } else {
                // The anchor was dispatched, but a newer user message already followed it.
                active = null
                null
            }
        }
    }

    fun clear(conversationId: String? = null) {
        synchronized(lock) {
            if (conversationId == null || active?.conversationId == conversationId) active = null
        }
    }

    fun clearIfAnchor(conversationId: String, anchorMessageId: String) {
        synchronized(lock) {
            val selection = active ?: return
            if (selection.conversationId == conversationId && selection.anchorMessageId == anchorMessageId) {
                active = null
            }
        }
    }

    /**
     * Resolves the pending tool call into the full host preview.
     *
     * The model only supplies [arguments]; the messages, names, avatars and image permission come
     * from the host-owned selection. Ids outside the user's selection are rejected here as well as
     * in [LocalMomentsChatDraftPlanner], so a manipulated tool call can never show another chat.
     */
    internal fun preview(
        conversationId: String?,
        arguments: JsonElement,
        now: Long = System.currentTimeMillis(),
    ): LocalMomentsChatDraftPreview {
        val requested = parseMessageIds(arguments)
        // A missing conversation id must never fall back to "whatever selection is active".
        val selection = conversationId?.let { current(it) }
        if (selection == null) {
            return LocalMomentsChatDraftPreview(
                available = false,
                unavailableReason = "当前没有可预览的朋友圈整理请求。可能已经切换会话、停止了生成，或应用已重启；" +
                    "请拒绝后重新选择消息发起。",
                requestedMessageIds = requested.orEmpty(),
                selectedCount = 0,
            )
        }
        if (requested == null || requested.isEmpty()) {
            return LocalMomentsChatDraftPreview(
                available = false,
                unavailableReason = "工具参数里没有可解析的 messageIds，无法生成完整预览。",
                selectedCount = selection.selectedMessageIds.size,
            )
        }
        val selected = selection.selectedMessageIds.toSet()
        if (requested.any { it !in selected }) {
            return LocalMomentsChatDraftPreview(
                available = false,
                unavailableReason = "工具请求的消息不在你本次选中的范围内，宿主拒绝生成预览。",
                requestedMessageIds = requested,
                selectedCount = selection.selectedMessageIds.size,
            )
        }
        if (requested.distinct().size != requested.size) {
            return LocalMomentsChatDraftPreview(
                available = false,
                unavailableReason = "工具重复请求了同一条消息，宿主拒绝生成预览。",
                requestedMessageIds = requested,
                selectedCount = selection.selectedMessageIds.size,
            )
        }
        val wanted = requested.toSet()
        val ordered = selection.messages.filter { wanted.contains(it.id.toString()) }
        if (ordered.size != wanted.size) {
            return LocalMomentsChatDraftPreview(
                available = false,
                unavailableReason = "选中的消息已不在当前会话，无法生成完整预览。",
                requestedMessageIds = requested,
                selectedCount = selection.selectedMessageIds.size,
            )
        }
        val snapshot = buildMomentsChatSnapshot(
            title = selection.title,
            messages = ordered,
            userName = selection.identity.userName,
            assistantName = selection.identity.assistantName,
            includeImages = selection.includeImages,
            now = now,
            // Mirrors the tool: an unapproved image avatar degrades to its text fallback.
            userAvatar = if (selection.includeImages) selection.identity.userAvatar else Avatar.Dummy,
            assistantAvatar = if (selection.includeImages) selection.identity.assistantAvatar else Avatar.Dummy,
        )
        return LocalMomentsChatDraftPreview(
            available = true,
            requestedMessageIds = requested,
            previewMessages = snapshot.previewMessages.map { message ->
                LocalMomentsChatPreviewMessage(
                    role = message.role,
                    name = message.name,
                    text = message.text,
                    imageUrls = message.images.map { image -> image.url },
                )
            },
            selectedCount = snapshot.previewMessages.size,
            totalChars = snapshot.totalChars,
            includeImages = selection.includeImages,
            imageCount = snapshot.images.size,
            omittedImages = snapshot.omittedImages,
            excludedMessages = snapshot.excludedMessages,
            excludedParts = snapshot.excludedParts,
            problems = snapshot.problems,
        )
    }

    private fun parseMessageIds(arguments: JsonElement): List<String>? {
        val objectValue = arguments as? JsonObject ?: return null
        val array = objectValue["messageIds"] as? JsonArray ?: return null
        return array.mapNotNull { item ->
            (item as? JsonPrimitive)?.contentOrNullSafe()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        }
    }
}

private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content
