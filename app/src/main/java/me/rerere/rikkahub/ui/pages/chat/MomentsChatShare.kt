/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.plugin.localdata.LocalAssistantDirectory
import me.rerere.rikkahub.plugin.localdata.LocalChatMessageDto
import me.rerere.rikkahub.plugin.localdata.LocalChatSnapshotDto
import me.rerere.rikkahub.plugin.localdata.LocalDataActor
import me.rerere.rikkahub.plugin.localdata.LocalDataApi
import me.rerere.rikkahub.plugin.localdata.LocalDataAssistantRef
import me.rerere.rikkahub.plugin.localdata.LocalDataCapability
import me.rerere.rikkahub.plugin.localdata.LocalDataChatSnapshots
import me.rerere.rikkahub.plugin.localdata.LocalDataEnvelope
import me.rerere.rikkahub.plugin.localdata.LocalDataGrant
import me.rerere.rikkahub.plugin.localdata.LocalDataJson
import me.rerere.rikkahub.plugin.localdata.LocalDataMethods
import me.rerere.rikkahub.plugin.localdata.LocalDataSession
import me.rerere.rikkahub.plugin.localdata.LocalDataValidation
import me.rerere.rikkahub.plugin.localdata.PostVisibility
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

internal const val LOCAL_MOMENTS_PLUGIN_ID = "com.orangechat.localmoments"

/** A single image that must be imported into the plugin media store before the snapshot is written. */
internal data class MomentsChatImage(
    val messageIndex: Int,
    val url: String,
)

/** A participant avatar that must be imported before the snapshot can point at a media id. */
internal data class MomentsChatAvatar(
    val role: MessageRole,
    val url: String,
)

internal data class MomentsPreviewMessage(
    val role: MessageRole,
    val name: String,
    val text: String,
    val images: List<MomentsChatImage>,
)

internal data class MomentsSnapshotResult(
    val previewMessages: List<MomentsPreviewMessage>,
    val images: List<MomentsChatImage>,
    val avatarImages: List<MomentsChatAvatar>,
    val snapshot: LocalChatSnapshotDto?,
    val excludedMessages: Int,
    val excludedParts: Int,
    val omittedImages: Int,
    val totalChars: Int,
    val problems: List<String>,
) {
    val canWrite: Boolean get() = problems.isEmpty() && snapshot != null
}

/**
 * Builds the card-only snapshot for a local Moments post.
 *
 * Only selected USER/ASSISTANT visible Text parts and (optionally) explicit Image parts are kept.
 * System/Tool messages, reasoning text and tool output are never copied into the snapshot. The
 * LocalData chat-card budget is enforced by rejecting the whole selection; this function never
 * silently truncates a message or drops a whole message to make the snapshot fit.
 */
internal fun buildMomentsChatSnapshot(
    title: String,
    messages: List<UIMessage>,
    userName: String,
    assistantName: String,
    includeImages: Boolean,
    now: Long = System.currentTimeMillis(),
    userAvatar: Avatar = Avatar.Dummy,
    assistantAvatar: Avatar = Avatar.Dummy,
): MomentsSnapshotResult {
    val problems = mutableListOf<String>()
    val previewMessages = mutableListOf<MomentsPreviewMessage>()
    val images = mutableListOf<MomentsChatImage>()
    var excludedMessages = 0
    var excludedParts = 0
    var omittedImages = 0
    var totalChars = 0

    val participantAvatars = mapOf(
        MessageRole.USER to userAvatar,
        MessageRole.ASSISTANT to assistantAvatar,
    )
    val avatarEmojis = participantAvatars.mapValues { (_, avatar) -> avatarEmojiText(avatar) }
    val avatarImages = participantAvatars.entries.mapNotNull { (role, avatar) ->
        val url = (avatar as? Avatar.Image)?.url
        if (url.isNullOrBlank()) null else MomentsChatAvatar(role, url)
    }
    val avatarImageRoles = avatarImages.mapTo(mutableSetOf<MessageRole>()) { it.role }
    // Reserve a valid, distinct media id with the same width as a generated id so the encoded-size
    // check below already sees the final cost.
    val avatarPlaceholders = mapOf(
        MessageRole.USER to "ldm_" + "1".repeat(32),
        MessageRole.ASSISTANT to "ldm_" + "2".repeat(32),
    )

    messages.forEachIndexed { index, message ->
        if (message.role != MessageRole.USER && message.role != MessageRole.ASSISTANT) {
            excludedMessages += 1
            excludedParts += message.parts.size
            return@forEachIndexed
        }

        val text = buildString {
            message.parts.forEach { part ->
                if (part is UIMessagePart.Text && part.text.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(part.text)
                }
            }
        }.trim()
        val messageImages = if (includeImages) {
            message.parts.filterIsInstance<UIMessagePart.Image>().filter { it.url.isNotBlank() }
        } else {
            emptyList()
        }
        message.parts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> Unit
                is UIMessagePart.Image -> {
                    if (includeImages && part.url.isBlank()) excludedParts += 1
                    if (!includeImages || part.url.isBlank()) omittedImages += 1
                }

                else -> excludedParts += 1
            }
        }

        if (text.isBlank() && messageImages.isEmpty()) {
            return@forEachIndexed
        }
        if (text.length > LocalDataApi.MAX_CHAT_MESSAGE_CHARS) {
            problems += "第 ${index + 1} 条选中消息正文 ${text.length} 字，超过单条 " +
                "${LocalDataApi.MAX_CHAT_MESSAGE_CHARS} 字上限；请分选或缩短后重试"
        }
        totalChars += text.length
        val messageIndex = previewMessages.size
        val displayName = if (message.role == MessageRole.USER) {
            userName.ifBlank { "我" }
        } else {
            assistantName.ifBlank { "助手" }
        }
        previewMessages += MomentsPreviewMessage(
            role = message.role,
            name = displayName,
            text = text,
            images = messageImages.map { MomentsChatImage(messageIndex, it.url) },
        )
        messageImages.forEach { images += MomentsChatImage(messageIndex, it.url) }
    }

    if (previewMessages.isEmpty()) {
        problems += "没有可分享的正文或图片；系统消息、思考内容、工具结果和未选中消息已排除"
    }
    if (previewMessages.size > LocalDataApi.MAX_CHAT_MESSAGES) {
        problems += "选中消息 ${previewMessages.size} 条，超过单张卡片 " +
            "${LocalDataApi.MAX_CHAT_MESSAGES} 条上限；请减少选择后分多条发布"
    }
    if (totalChars > LocalDataApi.MAX_CHAT_TOTAL_CHARS) {
        problems += "正文合计 $totalChars 字，超过 ${LocalDataApi.MAX_CHAT_TOTAL_CHARS} 字上限；" +
            "请减少选择后分多条发布"
    }
    if (images.size > LocalDataApi.MAX_MEDIA_PER_POST) {
        problems += "图片 ${images.size} 张，超过单张卡片 ${LocalDataApi.MAX_MEDIA_PER_POST} 张上限；" +
            "请减少图片后分多条发布"
    }
    if (userName.length > 100) {
        problems += "本机用户昵称超过 100 字，无法写入参与者快照"
    }
    if (assistantName.length > 100) {
        problems += "助手昵称超过 100 字，无法写入参与者快照"
    }

    var snapshot: LocalChatSnapshotDto? = null
    if (problems.isEmpty()) {
        // Reserve the same byte size as the final imported media ids so the preview check cannot
        // pass a snapshot that becomes too large only after the ids are attached.
        val placeholderMedia = images.groupBy { it.messageIndex }
            .mapValues { (messageIndex, messageImages) ->
                messageImages.mapIndexed { imageIndex, _ ->
                    "ldm_" + (messageIndex * 100 + imageIndex).toString().padStart(32, '0')
                }
            }
        val candidate = LocalChatSnapshotDto(
            // The title is optional metadata; omit it instead of silently cutting a long title.
            title = title.trim().takeIf { it.isNotEmpty() && it.length <= 200 },
            messages = previewMessages.mapIndexed { index, message ->
                LocalChatMessageDto(
                    role = if (message.role == MessageRole.USER) "user" else "assistant",
                    name = message.name.take(100),
                    text = message.text,
                    media = placeholderMedia[index].orEmpty(),
                    avatarMediaId = avatarPlaceholders[message.role]
                        ?.takeIf { message.role in avatarImageRoles },
                    avatarEmoji = avatarEmojis[message.role],
                )
            },
            capturedAt = now,
        )
        try {
            LocalDataChatSnapshots.encode(candidate)
            snapshot = candidate
        } catch (error: IllegalArgumentException) {
            problems += "聊天记录快照 JSON 超过 ${LocalDataApi.MAX_CHAT_SNAPSHOT_BYTES / 1024} KB 上限；" +
                "请减少消息或图片后重试"
        }
    }

    return MomentsSnapshotResult(
        previewMessages = previewMessages,
        images = images,
        avatarImages = avatarImages.filter { avatar ->
            previewMessages.any { message -> message.role == avatar.role }
        },
        snapshot = snapshot,
        excludedMessages = excludedMessages,
        excludedParts = excludedParts,
        omittedImages = omittedImages,
        totalChars = totalChars,
        problems = problems,
    )
}

/** Binds imported persistent media ids back to the snapshot message that referenced each image. */
internal fun attachMomentsMedia(
    snapshot: LocalChatSnapshotDto,
    images: List<MomentsChatImage>,
    resourceIds: List<String>,
): LocalChatSnapshotDto {
    require(images.size == resourceIds.size) { "Media ids must match imported images" }
    require(resourceIds.all(LocalDataValidation::isSafeMediaId)) { "Invalid media id" }
    val byMessage = images.mapIndexed { index, image -> image.messageIndex to resourceIds[index] }
        .groupBy({ it.first }, { it.second })
    return snapshot.copy(
        messages = snapshot.messages.mapIndexed { index, message ->
            message.copy(media = byMessage[index].orEmpty())
        },
    )
}

/**
 * Replaces the reserved avatar placeholders with the media ids the host imported. A null id means
 * the avatar could not be imported; the avatar is omitted instead of leaking its source URL.
 */
internal fun attachMomentsAvatars(
    snapshot: LocalChatSnapshotDto,
    userAvatarMediaId: String?,
    assistantAvatarMediaId: String?,
): LocalChatSnapshotDto {
    listOfNotNull(userAvatarMediaId, assistantAvatarMediaId).forEach { resourceId ->
        require(LocalDataValidation.isSafeMediaId(resourceId)) { "Invalid avatar media id" }
    }
    return snapshot.copy(
        messages = snapshot.messages.map { message ->
            when (message.role) {
                "user" -> message.copy(avatarMediaId = userAvatarMediaId)
                "assistant" -> message.copy(avatarMediaId = assistantAvatarMediaId)
                else -> message
            }
        },
    )
}

private fun avatarEmojiText(avatar: Avatar): String? {
    val text = (avatar as? Avatar.Emoji)?.content?.trim().orEmpty()
    return text.takeIf {
        it.isNotEmpty() &&
            it.length <= LocalDataApi.MAX_CHAT_AVATAR_EMOJI_CHARS &&
            it.none { character -> character.isISOControl() }
    }
}

internal fun buildMomentsWriteRequest(
    method: String,
    visibility: PostVisibility,
    selectedAssistantIds: List<String>,
    snapshot: LocalChatSnapshotDto,
    clientKey: String? = null,
): String {
    val snapshotElement = LocalDataJson.json.encodeToJsonElement(
        LocalChatSnapshotDto.serializer(),
        snapshot,
    )
    return buildJsonObject {
        put("version", LocalDataApi.VERSION)
        put("method", method)
        put("params", buildJsonObject {
            put("visibility", visibility.momentsWireName())
            putJsonArray("selectedAssistantIds") {
                selectedAssistantIds.forEach { add(it) }
            }
            put("chatSnapshot", snapshotElement)
            if (method == LocalDataMethods.CREATE && clientKey != null) put("clientKey", clientKey)
        })
    }.toString()
}

private fun PostVisibility.momentsWireName(): String = when (this) {
    PostVisibility.ALL_ASSISTANTS -> "all_assistants"
    PostVisibility.SELECTED -> "selected"
    PostVisibility.SELF -> "self"
}

@Composable
internal fun MomentsChatShareSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    onFinished: () -> Unit,
    conversation: Conversation,
    selectedMessages: List<UIMessage>,
    settings: Settings,
) {
    if (!visible) return

    val context = LocalContext.current
    val toaster = LocalToaster.current
    val pluginManager = koinInject<PluginManager>()
    val scope = rememberCoroutineScope()
    val userAvatar = settings.displaySetting.userAvatar
    val userName = settings.displaySetting.userNickname.ifBlank { "我" }
    val assistant = remember(conversation.assistantId, settings.assistants) {
        settings.assistants.find { it.id == conversation.assistantId }
    }
    val assistantName = assistant?.name?.ifBlank { "助手" } ?: "助手"
    val assistantAvatar = assistant?.avatar ?: Avatar.Dummy

    val publishClientKey = remember(selectedMessages) { "chatshare_" + java.util.UUID.randomUUID().toString() }
    var includeImages by remember { mutableStateOf(false) }
    var visibility by remember { mutableStateOf(PostVisibility.SELF) }
    var selectedAssistantIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var writing by remember { mutableStateOf(false) }
    var writeError by remember { mutableStateOf<String?>(null) }
    var pendingPublish by remember { mutableStateOf(false) }
    var writeJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { writeJob?.cancel() } }

    val snapshotResult = remember(
        selectedMessages,
        includeImages,
        conversation.title,
        userName,
        assistantName,
        userAvatar,
        assistantAvatar,
    ) {
        buildMomentsChatSnapshot(
            title = conversation.title,
            messages = selectedMessages,
            userName = userName,
            assistantName = assistantName,
            includeImages = includeImages,
            userAvatar = userAvatar,
            assistantAvatar = assistantAvatar,
        )
    }
    val selectedVisibilityValid = visibility != PostVisibility.SELECTED || selectedAssistantIds.isNotEmpty()
    val canWrite = snapshotResult.canWrite && selectedVisibilityValid && !writing

    fun submit(asDraft: Boolean) {
        if (writing) return
        val latest = buildMomentsChatSnapshot(
            title = conversation.title,
            messages = selectedMessages,
            userName = userName,
            assistantName = assistantName,
            includeImages = includeImages,
            userAvatar = userAvatar,
            assistantAvatar = assistantAvatar,
        )
        if (!latest.canWrite) {
            toaster.show("聊天记录卡片超过上限，未写入；请按提示减少选择后分条发布", ToastType.Error)
            return
        }
        if (!selectedVisibilityValid) {
            toaster.show("请至少选择一个可见助手", ToastType.Error)
            return
        }
        val plugin = pluginManager.getPlugin(LOCAL_MOMENTS_PLUGIN_ID)
        if (plugin == null) {
            toaster.show("请先在插件管理安装本机朋友圈（$LOCAL_MOMENTS_PLUGIN_ID）；未创建任何本地数据", ToastType.Error)
            return
        }
        if (!plugin.isEnabled) {
            toaster.show("本机朋友圈已安装但未启用，请先在插件管理启用", ToastType.Error)
            return
        }

        val targetVisibility = visibility
        val targetAssistants = if (targetVisibility == PostVisibility.SELECTED) {
            selectedAssistantIds.toList()
        } else {
            emptyList()
        }
        val targetSnapshot = requireNotNull(latest.snapshot)
        val targetImages = latest.images
        val targetAvatarImages = latest.avatarImages
        writing = true
        writeError = null
        writeJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // LocalDataSession creates the plugin database on first use; it must only be
                    // constructed after the installed/enabled check above.
                    val capabilities = buildSet {
                        add(LocalDataCapability.WRITE)
                        add(LocalDataCapability.CHAT_SNAPSHOT)
                        if (targetImages.isNotEmpty() || targetAvatarImages.isNotEmpty()) {
                            add(LocalDataCapability.MEDIA)
                        }
                    }
                    LocalDataSession(
                        context = context.applicationContext,
                        pluginId = LOCAL_MOMENTS_PLUGIN_ID,
                        grant = LocalDataGrant.of(*capabilities.toTypedArray()),
                    ).use { session ->
                        val resourceIds = targetImages.mapIndexed { index, image ->
                            try {
                                importMomentsImage(session, image)
                            } catch (error: Exception) {
                                throw IllegalStateException(
                                    "第 ${index + 1} 张图片导入失败：${error.message ?: "格式不支持或文件不可读"}",
                                    error,
                                )
                            }
                        }
                        // Avatars are decoration, not body content: a broken avatar must not block
                        // the post, and its source URL/base64 is never written to the snapshot.
                        val avatarMediaIds = targetAvatarImages.associate { avatar ->
                            avatar.role to importMomentsAvatar(session, avatar)
                        }
                        val snapshot = attachMomentsAvatars(
                            snapshot = attachMomentsMedia(targetSnapshot, targetImages, resourceIds),
                            userAvatarMediaId = avatarMediaIds[MessageRole.USER],
                            assistantAvatarMediaId = avatarMediaIds[MessageRole.ASSISTANT],
                        )
                        try {
                            LocalDataChatSnapshots.encode(snapshot)
                        } catch (error: IllegalArgumentException) {
                            throw IllegalStateException(
                                "聊天记录快照超过 " +
                                    "${LocalDataApi.MAX_CHAT_SNAPSHOT_BYTES / 1024} KB 上限；" +
                                    "请减少消息或图片后重试",
                            )
                        }
                        val request = buildMomentsWriteRequest(
                            method = if (asDraft) {
                                LocalDataMethods.DRAFT_SAVE
                            } else {
                                LocalDataMethods.CREATE
                            },
                            visibility = targetVisibility,
                            selectedAssistantIds = targetAssistants,
                            snapshot = snapshot,
                            clientKey = publishClientKey,
                        )
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        check(pluginManager.getPlugin(LOCAL_MOMENTS_PLUGIN_ID)?.isEnabled == true) {
                            "本机朋友圈已停用或卸载，未发布"
                        }
                        val response = session.call(
                            requestJson = request,
                            actor = LocalDataActor.User,
                            assistantDirectory = assistantDirectory(settings),
                            userDisplayName = userName,
                        )
                        val envelope = LocalDataJson.json.decodeFromString(
                            LocalDataEnvelope.serializer(),
                            response,
                        )
                        if (!envelope.ok) {
                            val code = envelope.error?.code ?: "internal"
                            val message = envelope.error?.message ?: "写入被拒绝"
                            throw IllegalStateException("$code: $message")
                        }
                    }
                }
                toaster.show(
                    if (asDraft) "已保存到本机朋友圈草稿" else "已发布到本机朋友圈",
                    ToastType.Success,
                )
                onFinished()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                writeError = "写入失败：${error.message ?: "请稍后重试"}"
            } finally {
                writing = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (!writing) {
                writeJob?.cancel()
                onDismissRequest()
            }
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("分享到本机朋友圈", style = MaterialTheme.typography.titleLarge)
            Text(
                "先预览要写入的结构化卡片快照，再选择可见范围。不会生成聊天长图，也不会调用模型。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MomentAvatar(
                            name = userName,
                            avatar = userAvatar,
                            modifier = Modifier.size(30.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        MomentAvatar(
                            name = assistantName,
                            avatar = assistantAvatar,
                            modifier = Modifier.size(30.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(conversation.title.ifBlank { "聊天记录" }, fontWeight = FontWeight.SemiBold)
                            Text(
                                "$userName 与 $assistantName",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (conversation.title.trim().length > 200) {
                                Text(
                                    "会话标题超过 200 字，快照不保存标题；正文不受影响。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                    if (snapshotResult.previewMessages.isEmpty()) {
                        Text(
                            "没有可显示的正文或图片。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        snapshotResult.previewMessages.forEach { message ->
                            Row(verticalAlignment = Alignment.Top) {
                                MomentAvatar(
                                    name = message.name,
                                    avatar = if (message.role == MessageRole.USER) {
                                        userAvatar
                                    } else {
                                        assistantAvatar
                                    },
                                    modifier = Modifier.size(26.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        if (message.role == MessageRole.USER) "我 · ${message.name}" else message.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    if (message.text.isNotBlank()) {
                                        Text(
                                            message.text,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 12,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (message.images.isNotEmpty()) {
                                        Row(
                                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            message.images.forEach { image ->
                                                AsyncImage(
                                                    model = image.url,
                                                    contentDescription = "聊天图片",
                                                    modifier = Modifier
                                                        .size(64.dp)
                                                        .clip(RoundedCornerShape(8.dp)),
                                                    contentScale = ContentScale.Crop,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (snapshotResult.previewMessages.any { it.text.length > 600 }) {
                        Text(
                            "长正文在预览中折叠显示；未超过上限时写入的是完整正文。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "已选 ${snapshotResult.previewMessages.size} 条 · 正文 ${snapshotResult.totalChars} 字 · " +
                            "图片 ${if (includeImages) snapshotResult.images.size else snapshotResult.omittedImages} 张",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "上限：${LocalDataApi.MAX_CHAT_MESSAGES} 条 / " +
                            "${LocalDataApi.MAX_CHAT_TOTAL_CHARS} 字 / " +
                            "${LocalDataApi.MAX_MEDIA_PER_POST} 图 / 快照 " +
                            "${LocalDataApi.MAX_CHAT_SNAPSHOT_BYTES / 1024} KB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (snapshotResult.excludedMessages > 0 || snapshotResult.excludedParts > 0) {
                        Text(
                            "已自动排除 ${snapshotResult.excludedMessages} 条系统/工具消息、" +
                                "${snapshotResult.excludedParts} 处非正文内容（思考/工具/空图片等）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (snapshotResult.problems.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("未写入：超过本机朋友圈卡片上限", fontWeight = FontWeight.SemiBold)
                        snapshotResult.problems.forEach { problem ->
                            Text("• $problem", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "不会静默截断；请减少选中消息，或分多条动态发布。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("包含明确选中的图片", fontWeight = FontWeight.SemiBold)
                            Text(
                                "开启并保存/发布后，才会把所选消息中的图片导入本机朋友圈持久媒体；" +
                                    "最多 ${LocalDataApi.MAX_MEDIA_PER_POST} 张。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = includeImages, onCheckedChange = { includeImages = it })
                    }
                    if (!includeImages && snapshotResult.omittedImages > 0) {
                        Text(
                            "当前已排除 ${snapshotResult.omittedImages} 张图片（开关关闭）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Text("可见范围", fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VisibilityChip(
                    label = "仅自己",
                    selected = visibility == PostVisibility.SELF,
                    onClick = { visibility = PostVisibility.SELF },
                )
                VisibilityChip(
                    label = "全部助手",
                    selected = visibility == PostVisibility.ALL_ASSISTANTS,
                    onClick = { visibility = PostVisibility.ALL_ASSISTANTS },
                )
                VisibilityChip(
                    label = "指定助手",
                    selected = visibility == PostVisibility.SELECTED,
                    onClick = { visibility = PostVisibility.SELECTED },
                )
            }
            if (visibility == PostVisibility.SELECTED) {
                if (settings.assistants.isEmpty()) {
                    Text("没有可选择的助手", style = MaterialTheme.typography.bodySmall)
                } else {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        settings.assistants.forEach { item ->
                            val id = item.id.toString()
                            FilterChip(
                                selected = id in selectedAssistantIds,
                                onClick = {
                                    selectedAssistantIds = if (id in selectedAssistantIds) {
                                        selectedAssistantIds - id
                                    } else {
                                        selectedAssistantIds + id
                                    }
                                },
                                label = { Text(item.name.ifBlank { "助手" }) },
                            )
                        }
                    }
                    if (selectedAssistantIds.isEmpty()) {
                        Text(
                            "请至少选择一个助手。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            HorizontalDivider()
            Text(
                "本机用户身份写入；仅授予 localdata.write、localdata.chat_snapshot，" +
                    "导入图片或头像图片时再加 localdata.media。不调用模型，不授予助手发布或读取权限。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (writeError != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        writeError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            if (writing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在写入本机朋友圈…", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { submit(asDraft = true) },
                    enabled = canWrite,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("保存草稿")
                }
                Button(
                    onClick = { pendingPublish = true },
                    enabled = canWrite,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (writing) "写入中" else "发布")
                }
            }
            TextButton(
                onClick = { if (!writing) onDismissRequest() },
                enabled = !writing,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("取消")
            }
        }
    }

    if (pendingPublish) {
        val visibilityLabel = when (visibility) {
            PostVisibility.SELF -> "仅自己"
            PostVisibility.ALL_ASSISTANTS -> "全部助手"
            PostVisibility.SELECTED -> "指定 ${selectedAssistantIds.size} 位助手"
        }
        AlertDialog(
            onDismissRequest = { pendingPublish = false },
            title = { Text("确认发布这条聊天记录卡片？") },
            text = {
                Text(
                    "将只把当前预览的结构化正文" +
                        (if (includeImages) "和 ${snapshotResult.images.size} 张图片" else "（不含图片）") +
                        "写入本机朋友圈，可见范围：$visibilityLabel。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingPublish = false
                        submit(asDraft = false)
                    },
                ) {
                    Text("确认发布")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPublish = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun MomentAvatar(
    name: String,
    avatar: Avatar,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (avatar) {
                is Avatar.Image -> {
                    AsyncImage(
                        model = avatar.url,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }

                is Avatar.Emoji -> {
                    Text(avatar.content, style = MaterialTheme.typography.titleMedium)
                }

                is Avatar.Dummy -> {
                    Text(
                        name.take(1).uppercase().ifBlank { "?" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun VisibilityChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

private fun assistantDirectory(settings: Settings): LocalAssistantDirectory =
    LocalAssistantDirectory.of(
        settings.assistants.map { assistant ->
            LocalDataAssistantRef(
                id = assistant.id.toString(),
                name = assistant.name.ifBlank { "助手" },
                avatar = (assistant.avatar as? Avatar.Image)?.url,
            )
        },
    )

private fun importMomentsImage(
    session: LocalDataSession,
    image: MomentsChatImage,
    displayName: String = "聊天图片",
): String {
    return when {
        image.url.startsWith("data:") -> {
            val separator = image.url.indexOf(',')
            require(separator > 5) { "图片 data URI 无效" }
            val header = image.url.substring(5, separator)
            require(header.endsWith(";base64")) { "图片 data URI 不是 base64" }
            val mime = header.substringBefore(';')
            val payload = image.url.substring(separator + 1)
            require(payload.length <= maxBase64Chars()) { "图片超过 12 MB 上限" }
            val bytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
            session.importImageBytes(bytes, mime, displayName).media.resourceId
        }

        image.url.startsWith("content:") -> {
            session.importImageFromUri(Uri.parse(image.url), displayName).media.resourceId
        }

        image.url.startsWith("file:") -> {
            val path = Uri.parse(image.url).path ?: error("图片文件路径无效")
            val file = File(path)
            require(file.isFile) { "图片文件不存在" }
            require(file.length() <= LocalDataApi.MAX_MEDIA_BYTES) { "图片超过 12 MB 上限" }
            session.importImageBytes(file.readBytes(), null, displayName).media.resourceId
        }

        image.url.startsWith("http://") || image.url.startsWith("https://") -> {
            session.importImageBytes(downloadBounded(image.url), null, displayName).media.resourceId
        }

        else -> error("不支持的图片来源")
    }
}

/**
 * Importing a participant avatar is best effort: it must never fail the whole share, and a failed
 * import simply means the card is written without that avatar.
 */
private fun importMomentsAvatar(session: LocalDataSession, avatar: MomentsChatAvatar): String? =
    try {
        importMomentsImage(
            session = session,
            image = MomentsChatImage(messageIndex = 0, url = avatar.url),
            displayName = "参与者头像",
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }

private fun maxBase64Chars(): Int = ((LocalDataApi.MAX_MEDIA_BYTES.toLong() + 2) / 3 * 4).toInt()

private fun downloadBounded(url: String): ByteArray {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        instanceFollowRedirects = true
        requestMethod = "GET"
        setRequestProperty("User-Agent", "OrangeChat-MomentsShare/1.0")
    }
    try {
        val code = connection.responseCode
        if (code !in 200..299) throw IOException("HTTP $code")
        if (connection.contentLengthLong > LocalDataApi.MAX_MEDIA_BYTES) {
            throw IOException("图片超过 12 MB 上限")
        }
        return connection.inputStream.use { readBounded(it, LocalDataApi.MAX_MEDIA_BYTES) }
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
