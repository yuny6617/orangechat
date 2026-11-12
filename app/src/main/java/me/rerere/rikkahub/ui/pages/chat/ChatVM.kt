/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.ConversationSummary
import me.rerere.rikkahub.data.model.CompressionUndoMode
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getChatModelForAssistant
import me.rerere.rikkahub.data.datastore.withAssistantEdit
import me.rerere.rikkahub.data.datastore.withSettingsEdit
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.model.withConversation
import me.rerere.rikkahub.data.model.withConversationConfig
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.ai.tools.LocalMomentsChatSelectionStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.interaction.ChatInteractionRepository
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.hooks.readBooleanPreference
import me.rerere.rikkahub.ui.hooks.writeBooleanPreference
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
    private val chatInteractionRepository: ChatInteractionRepository,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
    val reactions = chatInteractionRepository.observe(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun interactWithAvatar(target: UIMessage) =
        chatService.sendAvatarInteraction(_conversationId, target)

    fun toggleReaction(message: UIMessage, emoji: String, quote: String) {
        viewModelScope.launch {
            try {
                val assistant = settings.value.getAssistantById(conversation.value.assistantId) ?: return@launch
                if (!assistant.enableMessageReactions) return@launch
                val customReactionPresets = settings.value.customReactionPresets
                chatInteractionRepository.react(
                    conversationId = _conversationId,
                    assistant = assistant,
                    target = message.id.toString(),
                    actor = "user",
                    emoji = emoji,
                    customReactionPresets = customReactionPresets,
                    quote = quote,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                chatService.addError(error, _conversationId, title = "消息表情")
            }
        }
    }
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部
    private val showCompressedHistoryKey = "compression_show_original_$_conversationId"
    private var showCompressedHistoryState by mutableStateOf(
        context.readBooleanPreference(showCompressedHistoryKey, false)
    )

    /** Per-conversation eye toggle; persisted so switching chats or relaunching keeps the user's choice. */
    var showCompressedHistory: Boolean
        get() = showCompressedHistoryState
        set(value) {
            showCompressedHistoryState = value
            context.writeBooleanPreference(showCompressedHistoryKey, value)
        }

    /** Reveal originals for a one-off jump (e.g. from favorites) without overwriting the saved preference. */
    fun revealCompressedHistoryTemporarily() {
        showCompressedHistoryState = true
    }
    var summaryDialogConversation by mutableStateOf<Conversation?>(null)

    fun openSummary(conversationId: Uuid) {
        if (conversation.value.id == conversationId) summaryDialogConversation = conversation.value
    }

    suspend fun saveSummary(
        snapshot: Conversation,
        summaries: List<ConversationSummary>,
        enabled: Boolean,
        undo: CompressionUndoMode? = null,
    ) = chatService.saveCompressionSettings(snapshot.id, snapshot.compressionRevision, summaries, enabled, undo)

    suspend fun mergeSummaries(
        snapshot: Conversation,
        summaries: List<ConversationSummary>,
        enabled: Boolean,
        additionalPrompt: String,
        targetTokens: Int,
    ) = chatService.mergeExistingSummaries(
        snapshot.id, snapshot.compressionRevision, summaries, enabled, additionalPrompt, targetTokens,
    )

    // 聊天输入状态 - 保存在 ViewModel 中避免 TransactionTooLargeException
    val inputState = ChatInputState()

    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    val messageQueue = chatService.getMessageQueueFlow(_conversationId)
    fun removeQueuedMessage(id: Uuid) = chatService.removeQueuedMessage(_conversationId, id)
    fun beginQueuedMessageEdit(id: Uuid) = chatService.beginQueuedMessageEdit(_conversationId, id)
    fun finishQueuedMessageEdit(id: Uuid, parts: List<UIMessagePart>?) =
        chatService.finishQueuedMessageEdit(_conversationId, id, parts)
    fun pauseMessageQueue() = chatService.pauseMessageQueue(_conversationId)
    fun resumeMessageQueue() = chatService.resumeMessageQueue(_conversationId)

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // 添加对话引用
        chatService.addConversationReference(_conversationId)

        // 初始化对话
        viewModelScope.launch {
            try {
                chatService.initializeConversation(_conversationId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                chatService.addError(e, _conversationId)
            }
        }

        // 记住对话ID, 方便下次启动恢复
        context.writeStringPreference("lastConversationId", _conversationId.toString())
    }

    override fun onCleared() {
        super.onCleared()
        // 换会话/离开聊天页即作废这一次性的朋友圈整理选择，绝不带到别的会话。
        LocalMomentsChatSelectionStore.clear(_conversationId.toString())
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        combine(settingsStore.settingsFlow, conversation) { global, chat ->
            global.withConversationConfig(chat)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    // 网络搜索
    val enableWebSearch = settings.map {
        it.enableWebSearch
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 当前模型
    val currentChatModel = combine(settings, conversation) { settings, conversation ->
        settings.getChatModelOf(conversation)
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    // 错误状态
    val errors: StateFlow<List<ChatError>> = chatService.errors

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    // 生成完成
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP管理器
    val mcpManager = chatService.mcpManager

    // 更新设置
    fun updateSettings(newSettings: Settings) = updateSettings(settings.value, newSettings)

    fun updateSettings(previous: Settings, desired: Settings) {
        viewModelScope.launch {
            val edit = conversationSettingsEdit(previous, desired, conversation.value)
            if (edit.enableWebSearch != null || edit.searchServiceSelected != null || edit.builtInSearch != null) {
                chatService.updateConversationConfig(_conversationId) { current ->
                    current.copy(
                        enableWebSearch = edit.enableWebSearch ?: current.enableWebSearch,
                        searchServiceSelected = edit.searchServiceSelected ?: current.searchServiceSelected,
                        builtInSearch = edit.builtInSearch ?: current.builtInSearch,
                    )
                }
            }
            var replaced: Pair<Settings, Settings>? = null
            settingsStore.update { current ->
                current.withSettingsEdit(previous, edit.globalDesired).also { replaced = current to it }
            }
            replaced?.let { (old, new) -> checkUserAvatarDelete(old, new) }
        }
    }

    fun updateAssistant(previous: Assistant, desired: Assistant) {
        if (previous.id != desired.id) return
        viewModelScope.launch {
            if (ConversationConfig().withAssistant(previous) != ConversationConfig().withAssistant(desired)) {
                chatService.updateConversationConfig(_conversationId) { it.withAssistantEdit(previous, desired) }
            }
            settingsStore.update { current ->
                val stored = current.getAssistantById(desired.id) ?: return@update current
                // Remove conversation request fields from both sides; only persona edits reach the role.
                val requestFields = ConversationConfig().withAssistant(stored)
                current.withAssistantEdit(previous.withConversation(requestFields), desired.withConversation(requestFields))
            }
        }
    }

    fun resetConversationConfig() {
        viewModelScope.launch { chatService.resetConversationConfig(_conversationId) }
    }

    suspend fun selectAssistant(assistantId: Uuid, createNew: Boolean): Uuid = selectAssistantConversation(
        assistantId = assistantId,
        createNew = createNew,
        persistSelection = settingsStore::updateAssistant,
        findConversation = { conversationRepo.getConversationsOfAssistant(it).first().firstOrNull()?.id },
    )

    // 检查用户头像删除
    private suspend fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            conversationRepo.collectReplacedChatAssets(listOf(oldAvatar.url))
        }
    }

    // 设置聊天模型
    fun setChatModel(assistant: Assistant, model: Model) {
        if (model.id == currentChatModel.value?.id) return
        viewModelScope.launch {
            chatService.updateConversationConfig(_conversationId) {
                it.copy(chatModelId = model.id, builtInSearch = BuiltInTools.Search in model.tools)
            }
        }
    }

    // Update checker
    val updateState =
        updateChecker.checkUpdate().stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     */
    fun handleMessageSend(content: List<UIMessagePart>,answer: Boolean = true): Boolean {
        if (content.isEmptyInputMessage()) return false

        return chatService.sendMessage(_conversationId, content, answer) != null
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return

        viewModelScope.launch {
            try {
                chatService.editMessage(_conversationId, messageId, parts)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                chatService.addError(error, _conversationId, title = context.getString(R.string.error_title_operation))
            }
        }
    }

    suspend fun handleCompressContext(
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int,
        mergeWithPrevious: Boolean = true,
        onProgress: (String) -> Unit = {},
    ) {
        chatService.compressConversation(
            _conversationId,
            conversation.value,
            additionalPrompt,
            targetTokens,
            keepRecentMessages,
            mergeWithPrevious,
            onProgress,
        ).getOrThrow()
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        return chatService.forkConversationAtMessage(_conversationId, message.id)
    }

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            chatService.deleteMessage(_conversationId, message)
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException("请先停止生成再删除消息"),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        chatService.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg)
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = ""
    ) {
        chatService.handleToolApproval(_conversationId, toolCallId, approved, reason)
    }

    fun handleToolAnswer(
        toolCallId: String,
        answer: String,
    ) {
        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    fun saveConversationAsync() {
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, conversation.value)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            chatService.updateTitle(_conversationId, title)
        }
    }

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch {
            chatService.deleteConversation(conversation)
        }
    }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            chatService.togglePinStatus(conversation.id)
        }
    }

    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            // 文件夹是助手内分组，切换助手后原文件夹在新助手下不可见，需清空归属避免会话丢失
            val updatedConversation = conversationFull.copy(
                assistantId = targetAssistantId,
                folderId = null,
            )
            if (conversation.id == _conversationId) {
                chatService.saveConversation(_conversationId, updatedConversation)
                settingsStore.updateAssistant(targetAssistantId)
            } else {
                conversationRepo.updateConversation(updatedConversation)
            }
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(conversation.id, conversationFull, force)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateSuggestion(_conversationId, conversation)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        chatService.clearTranslationField(_conversationId, messageId)
    }

    fun updateConversation(newConversation: Conversation) {
        chatService.updateConversationState(_conversationId) {
            newConversation
        }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node
                    )
                )
            }

            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { existingNode ->
                        if (existingNode.id == node.id) {
                            existingNode.copy(isFavorite = !currentlyFavorited)
                        } else {
                            existingNode
                        }
                    }
                )
            }
        }
    }

}

/** Split a visible settings edit without persisting any conversation overlays to global settings. */
internal data class ConversationSettingsEdit(
    val globalDesired: Settings,
    val enableWebSearch: Boolean?,
    val searchServiceSelected: Int?,
    val builtInSearch: Boolean?,
)

internal fun conversationSettingsEdit(previous: Settings, desired: Settings, conversation: Conversation): ConversationSettingsEdit {
    val modelId = previous.getChatModelForAssistant(conversation.assistantId)?.id
    var search: Boolean? = null
    val providers = desired.providers.map { provider ->
        provider.copyProvider(models = provider.models.map modelEdit@{ model ->
            if (model.id != modelId) return@modelEdit model
            val before = previous.providers.flatMap { it.models }.find { it.id == model.id } ?: return@modelEdit model
            val oldSearch = BuiltInTools.Search in before.tools
            val newSearch = BuiltInTools.Search in model.tools
            if (oldSearch != newSearch) search = newSearch
            // Retain edits to every other tool/model/provider while removing this one session delta.
            model.copy(tools = if (oldSearch) model.tools + BuiltInTools.Search else model.tools - BuiltInTools.Search)
        })
    }
    return ConversationSettingsEdit(
        globalDesired = desired.copy(
            enableWebSearch = previous.enableWebSearch,
            searchServiceSelected = previous.searchServiceSelected,
            providers = providers,
        ),
        enableWebSearch = desired.enableWebSearch.takeIf { it != previous.enableWebSearch },
        searchServiceSelected = desired.searchServiceSelected.takeIf { it != previous.searchServiceSelected },
        builtInSearch = search,
    )
}
