/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.files.attachmentReferenceGuard

import android.database.sqlite.SQLiteBlobTooBigException
import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.core.net.toUri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.attachmentUrls
import me.rerere.rikkahub.data.files.localAttachmentFile
import me.rerere.rikkahub.data.files.removeReferencedFileNames
import me.rerere.rikkahub.data.files.DuplicateAttachmentPlan
import me.rerere.rikkahub.data.files.remapDuplicateAttachments
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.serein.RawArchive
import me.rerere.rikkahub.data.serein.RawArchiveRevision
import me.rerere.rikkahub.data.serein.RawArchiveHead
import me.rerere.rikkahub.data.serein.rawArchiveSearchPattern
import me.rerere.rikkahub.data.serein.rawArchiveText
import me.rerere.rikkahub.data.serein.capture
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.data.sync.ChatHistoryRestoreMode
import me.rerere.rikkahub.data.sync.validateChatHistory
import me.rerere.rikkahub.data.favorite.NodeFavoriteAdapter
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import java.time.Instant
import kotlin.uuid.Uuid

private const val TAG = "ConversationRepository"

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val filesManager: FilesManager,
    private val messageFtsManager: MessageFtsManager,
    // Tests that exercise storage alone may omit settings; cleanup then conservatively keeps files.
    private val settingsStore: SettingsStore? = null,
) {
    private val rawArchive by lazy { RawArchive(database.rawArchiveDao()) }
    private data class UndoRawArchive(val revisions: List<RawArchiveRevision>, val heads: List<RawArchiveHead>)
    private val undoRawArchives = java.util.concurrent.ConcurrentHashMap<Uuid, UndoRawArchive>()

    private fun archivesRawMessages(assistantId: Uuid): Boolean =
        settingsStore?.settingsFlow?.value?.assistants?.any { it.id == assistantId && it.saveLocalRawMessages } == true

    /** Runs inside the chat writer transaction, touching only the supplied changed nodes. */
    private suspend fun archiveRawChanges(
        conversation: Conversation,
        upserts: List<Pair<Int, MessageNode>>,
        removedIds: Collection<String> = emptyList(),
        origin: String = "live",
    ) {
        settingsStore?.settingsFlow?.value?.sereinHook?.let { config ->
            if (config.uploadsFor(conversation.assistantId.toString())) {
                database.sereinUploadDao().capture(config, conversation.assistantId.toString(),
                    conversation.id.toString(), upserts, origin)
            }
        }
        if (!archivesRawMessages(conversation.assistantId)) {
            // Do not leave an outdated branch/text visible when editing with archiving switched off.
            (upserts.map { it.second.id.toString() } + removedIds).distinct().chunked(200).forEach {
                database.rawArchiveDao().removeHeads(conversation.assistantId.toString(), conversation.id.toString(), it)
            }
            return
        }
        rawArchive.sync(conversation.assistantId.toString(), conversation.id.toString(), upserts, removedIds, origin)
    }

    suspend fun searchRawOriginals(assistantId: Uuid, query: String = "", limit: Int = 50): List<RawArchiveRevision> =
        withContext(Dispatchers.IO) {
            database.rawArchiveDao().search(assistantId.toString(), rawArchiveSearchPattern(query), limit.coerceIn(1, 100))
        }

    suspend fun rawOriginalHistory(assistantId: Uuid, conversationId: String, messageId: String): List<RawArchiveRevision> =
        withContext(Dispatchers.IO) {
            database.rawArchiveDao().history(assistantId.toString(), conversationId, messageId, 100)
        }

    /** Explicit user action. One conversation per transaction; reads the latest stored chat, never a stale UI copy. */
    suspend fun backfillRawOriginals(assistantId: Uuid, onProgress: suspend (Int, Int) -> Unit = { _, _ -> }): Int = withContext(Dispatchers.IO) {
        check(archivesRawMessages(assistantId)) { "请先开启保存聊天原话" }
        val ids = getConversationsOfAssistant(assistantId).first().map { it.id }
        var count = 0
        onProgress(0, ids.size)
        for (id in ids) {
            database.withTransaction {
                check(archivesRawMessages(assistantId)) { "原话存档已关闭，导入已停止" }
                val current = getConversationById(id)?.takeIf { it.assistantId == assistantId } ?: return@withTransaction
                val nodes = current.messageNodes.map { it.id.toString() }.toSet()
                val removed = database.rawArchiveDao().nodeIds(assistantId.toString(), id.toString()).filterNot { it in nodes }
                archiveRawChanges(current, current.messageNodes.mapIndexed { index, node -> index to node }, removed, "backfill")
                count++
            }
            onProgress(count, ids.size)
        }
        count
    }

    internal suspend fun interruptPendingRawOriginals(conversationId: Uuid, messageIds: Set<String>) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val entity = conversationDAO.getConversationById(conversationId.toString()) ?: return@withTransaction
            if (archivesRawMessages(Uuid.parse(entity.assistantId))) {
                messageIds.toList().chunked(200).forEach { database.rawArchiveDao().interruptPending(entity.assistantId, entity.id, it) }
            }
        }
    }

    private val undoAttachmentNames = java.util.concurrent.ConcurrentHashMap<Uuid, Set<String>>()

    companion object {
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
    }

    suspend fun getRecentConversations(assistantId: Uuid, limit: Int = 10): List<Conversation> {
        return conversationDAO.getRecentConversationsOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit
        ).map { entity ->
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        }
    }

    suspend fun getRecentConversationHeaders(assistantId: Uuid, limit: Int = 10): List<LightConversationEntity> =
        conversationDAO.getRecentConversationHeadersOfAssistant(assistantId.toString(), limit)

    /** Lightweight lookup for background jobs; avoids decoding every node just to obtain an id. */
    suspend fun getMostRecentConversationId(assistantId: Uuid): Uuid? {
        return conversationDAO.getMostRecentConversationIdOfAssistant(assistantId.toString())
            ?.let { Uuid.parse(it) }
    }

    fun getConversationsOfAssistant(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString())
            .map { flow ->
                flow.map { entity ->
                    // 列表视图不需要完整的 nodes，使用空列表
                    conversationEntityToConversation(entity, emptyList(), messageNodesLoaded = false)
                }
            }
    }

    fun getConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getUnfiledConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getConversationsOfFolderPaging(folderId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfFolderPaging(folderId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    suspend fun getConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.getConversationsOfAssistantPaging(assistantId.toString())
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun searchConversationsOfAssistantPage(
        assistantId: Uuid,
        titleKeyword: String,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.searchConversationsOfAssistantPaging(
            assistantId = assistantId.toString(),
            searchText = titleKeyword
        )
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    fun searchConversations(titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversations(titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList(), messageNodesLoaded = false)
                }
            }
    }

    fun searchConversationsPaging(titleKeyword: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.searchConversationsPaging(titleKeyword) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun searchConversationsOfAssistant(assistantId: Uuid, titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversationsOfAssistant(assistantId.toString(), titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList(), messageNodesLoaded = false)
                }
            }
    }

    fun searchConversationsOfAssistantPaging(assistantId: Uuid, titleKeyword: String): Flow<PagingData<Conversation>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = INITIAL_LOAD_SIZE,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                conversationDAO.searchConversationsOfAssistantPaging(
                    assistantId.toString(),
                    titleKeyword
                )
            }
        ).flow.map { pagingData ->
            pagingData.map { entity ->
                conversationSummaryToConversation(entity)
            }
        }

    suspend fun getConversationById(uuid: Uuid): Conversation? = database.withTransaction {
        val entity = conversationDAO.getConversationById(uuid.toString())
        if (entity != null) {
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        } else null
    }

    suspend fun existsConversationById(uuid: Uuid): Boolean {
        return conversationDAO.existsById(uuid.toString())
    }

    /** Idempotently commit the exact prepared assistant messages and verify prior partial commits.
     * All node rows and conversation metadata share one Room transaction. */
    suspend fun appendProactiveMessagesIdempotently(
        conversationId: Uuid,
        assistantId: Uuid,
        anchorId: Uuid,
        messages: List<UIMessage>,
    ): Conversation? = withContext(Dispatchers.IO) {
        require(messages.isNotEmpty() && messages.all { it.role == me.rerere.ai.core.MessageRole.ASSISTANT })
        require(messages.map { it.id }.distinct().size == messages.size)
        require(messages.none { it.hasBase64Part() })
        attachmentReferenceGuard.beginWrite().use {
            database.withTransaction {
                val entity = conversationDAO.getConversationById(conversationId.toString())
                    ?: return@withTransaction null
                if (entity.assistantId != assistantId.toString()) return@withTransaction null
                val nodes = loadMessageNodes(entity.id)
                val current = conversationEntityToConversation(entity, nodes)
                val latestUser = current.currentMessages.lastOrNull {
                    it.role == me.rerere.ai.core.MessageRole.USER
                }?.id
                if (latestUser != anchorId) return@withTransaction null
                val byMessageId = current.messageNodes.flatMap { it.messages }.associateBy { it.id }
                // IDs are reserved to this task: an existing ID with different payload is a conflict.
                val selectedById = current.messageNodes.associate { it.currentMessage.id to it }
                if (messages.any { candidate -> byMessageId[candidate.id]?.let { stored ->
                        if (stored == candidate) false else {
                            val node = selectedById[candidate.id]
                            // Attachment cleanup may have removed a missing local file since payload prep.
                            val withoutFiles = filesManager.withoutDeletedAttachments(listOf(candidate)).single()
                            node?.currentMessage != stored || withoutFiles != stored
                        }
                    } == true }) return@withTransaction null
                val missing = messages.filter { it.id !in byMessageId }
                if (missing.isEmpty()) return@withTransaction current
                val normalizedMissing = missing.map { candidate ->
                    filesManager.withoutDeletedAttachments(listOf(candidate)).single()
                }
                val missingNodes = normalizedMissing.map { it.toMessageNode() }
                val updated = current.copy(messageNodes = current.messageNodes + missingNodes,
                    updateAt = Instant.now())
                val previous = current
                val changes = conversationNodeChanges(previous, updated)
                val storedConfig = checkNotNull(conversationDAO.getConfig(entity.id))
                val upserts = changes.upserts.map { (index, node) ->
                    index to node.copy(messages = filesManager.withoutDeletedAttachments(node.messages))
                }
                conversationDAO.update(conversationMetadataToEntity(updated).copy(config = storedConfig))
                messageNodeDAO.insertAll(upserts.map { (index, node) ->
                    MessageNodeEntity(node.id.toString(), entity.id, index,
                        JsonInstant.encodeToString(node.messages), node.selectIndex)
                })
                archiveRawChanges(updated, upserts)
                messageFtsManager.reindexNodes(entity.id, updated.title, updated.updateAt,
                    changes.changedIds, upserts.map { it.second }, failOnError = true)
                updated.copy(messageNodes = current.messageNodes + missingNodes)
            }
        }
    }

    /** Consistent logical snapshot, without attachment repair or filtering during export. */
    suspend fun exportChatHistory(): List<Conversation> = database.withTransaction {
        conversationDAO.getAllIds().map { id -> readChatHistory(checkNotNull(conversationDAO.getConversationById(id))) }
    }

    private suspend fun readChatHistory(entity: ConversationEntity): Conversation {
        val favorites = favoriteDAO.getFavoriteNodeIdsOfConversation(entity.id).toSet()
        return conversationEntityToConversation(entity, messageNodeDAO.loadCompleteNodes(entity.id).map { node ->
            MessageNode(Uuid.parse(node.id), JsonInstant.decodeFromString(node.messages), node.selectIndex,
                node.id in favorites)
        })
    }

    /** Startup-only: caller must run before ChatService/WorkManager can open sessions. No file collection here. */
    suspend fun restoreChatHistory(
        conversations: List<Conversation>,
        mode: ChatHistoryRestoreMode,
        favoriteNodeIds: Set<Uuid> = emptySet(),
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ) = attachmentReferenceGuard.beginWrite().use {
        database.withTransaction {
        validateChatHistory(conversations)
        var restoredCount = 0
        onProgress(0, conversations.size)
        conversations.forEach { incoming ->
            val existingEntity = conversationDAO.getConversationById(incoming.id.toString())
            if (existingEntity != null && mode == ChatHistoryRestoreMode.INCREMENTAL) return@forEach
            // Replacing a conversation only needs its old node IDs, not its old message payloads.
            val oldNodeIds = if (existingEntity == null) emptyList() else
                messageNodeDAO.getNodeIdsOfConversation(incoming.id.toString())
            val restored = incoming
            restored.messageNodes.forEach { node ->
                val owner = messageNodeDAO.getNodeHeader(node.id.toString())?.conversationId
                check(owner == null || owner == restored.id.toString()) { "备份消息 ID 与其他本机会话冲突，恢复已取消" }
            }
            val folder = restored.folderId?.let { database.folderDao().getFolderById(it.toString()) }
            val entity = conversationToConversationEntity(restored.copy(folderId = restored.folderId.takeIf {
                folder?.assistantId == restored.assistantId.toString()
            }))
            if (existingEntity == null) conversationDAO.insert(entity) else conversationDAO.update(entity)
            messageNodeDAO.deleteByConversation(restored.id.toString())
            saveMessageNodes(restored.id.toString(), restored.messageNodes)
            archiveRawChanges(restored, restored.messageNodes.mapIndexed { index, node -> index to node }, origin = "backfill")
            messageFtsManager.replaceConversationIndex(restored)
            val restoredIds = restored.messageNodes.mapTo(hashSetOf()) { it.id.toString() }
            oldNodeIds.filter { it !in restoredIds }.forEach {
                favoriteDAO.deleteByRefKey("node:${restored.id}:$it")
            }
            restored.messageNodes.forEach { node ->
                val favorite = node.id in favoriteNodeIds
                val key = "node:${restored.id}:${node.id}"
                if (!favorite) favoriteDAO.deleteByRefKey(key) else {
                    favoriteDAO.upsert(NodeFavoriteAdapter.buildFavoriteEntity(
                        NodeFavoriteTarget(restored.id, restored.title, node.id, node),
                        favoriteDAO.getByRefKey(key), System.currentTimeMillis()))
                }
            }
            restoredCount++
            onProgress(restoredCount, conversations.size)
        }
        restoredCount
        }
    }

    // Auxiliary generation never rewrites message nodes or recreates a deleted conversation.
    suspend fun updateGeneratedTitle(id: Uuid, title: String): Boolean {
        return conversationDAO.updateTitle(id.toString(), title) > 0
    }

    suspend fun updateGeneratedSuggestions(id: Uuid, suggestions: List<String>): Boolean {
        return conversationDAO.updateSuggestions(id.toString(), JsonInstant.encodeToString(suggestions)) > 0
    }

    /** Request controls are independent of message checkpoints, including an in-flight response. */
    suspend fun updateConfig(id: Uuid, config: ConversationConfig?): Boolean {
        return conversationDAO.updateConfig(id.toString(), config?.let { JsonInstant.encodeToString(it) }.orEmpty()) > 0
    }

    suspend fun insertConversation(conversation: Conversation): Conversation = withContext(Dispatchers.IO) {
        attachmentReferenceGuard.beginWrite().use {
            conversation.requireMessageNodesLoaded()
            try {
                val saved = database.withTransaction {
                    val conversation = withoutDeletedAttachments(conversation)
                    conversationDAO.insert(
                        conversationToConversationEntity(conversation)
                    )
                    saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
                    val restoredArchive = undoRawArchives[conversation.id]?.takeIf { archive ->
                        archive.revisions.all { it.assistantId == conversation.assistantId.toString() }
                    }
                    restoredArchive?.let { archive ->
                        database.rawArchiveDao().insertRevisions(archive.revisions)
                    }
                    archiveRawChanges(conversation, conversation.messageNodes.mapIndexed { index, node -> index to node })
                    if (!archivesRawMessages(conversation.assistantId) && restoredArchive != null) {
                        // Undo restores existing originals even with recording off. Only reconnect
                        // unchanged sources; a caller may have edited/removed a node before restoring.
                        val revisions = restoredArchive.revisions.associateBy { it.id }
                        val nodes = conversation.messageNodes.withIndex().associateBy { it.value.id.toString() }
                        val heads = restoredArchive.heads.mapNotNull { head ->
                            val node = nodes[head.nodeId] ?: return@mapNotNull null
                            val variant = node.value.messages.indexOfFirst { it.id.toString() == head.messageId }
                            if (variant < 0) return@mapNotNull null
                            val message = node.value.messages[variant]
                            val revision = revisions[head.revisionId] ?: return@mapNotNull null
                            if (message.rawArchiveText() != revision.text ||
                                message.role.name.lowercase() != revision.role ||
                                message.createdAt.toString() != revision.createdAt) return@mapNotNull null
                            head.copy(nodeIndex = node.index, variantIndex = variant, selected = variant == node.value.selectIndex)
                        }
                        database.rawArchiveDao().insertHeads(heads)
                    }
                    conversation
                }
                undoRawArchives.remove(conversation.id)
                messageFtsManager.indexConversation(saved)
                saved
            } catch (e: Exception) {
                Log.e(TAG, "insertConversation failed, conversationId=${conversation.id}", e)
                throw e
            }
        }
    }

    /**
     * 更新对话。
     *
     * 之前的实现是"删掉这个对话下全部消息节点，再把全部节点重新插入"，不管这一轮实际改了几条消息。
     * 对话历史越长，这个开销越大，而且每次保存都要付出跟历史总长度成正比的代价。
     *
     * 现在改成按 MessageNode.id 做精确 diff：
     * - 新出现的 id -> 插入
     * - 消失的 id（比如重新生成时截断历史）-> 删除
     * - 两边都有但内容不同（用 MessageNodeEntity 的 data class 结构相等判断）-> 更新
     * - 完全没变的 -> 跳过，不碰数据库
     *
     * FTS 索引也同步降级为只处理这批发生变化的 node，而不是整个对话重建。
     */
    suspend fun updateConversation(conversation: Conversation, previous: Conversation? = null): Conversation = withContext(Dispatchers.IO) {
        attachmentReferenceGuard.beginWrite().use {
            conversation.requireMessageNodesLoaded()
            previous?.requireMessageNodesLoaded()
            require(previous == null || previous.id == conversation.id)
            val discardedFiles = mutableSetOf<String>()
            try {
                val saved = database.withTransaction {
                    val storedConfig = checkNotNull(conversationDAO.getConfig(conversation.id.toString())) {
                        "Conversation no longer exists"
                    }
                    val conversation = withoutDeletedAttachments(conversation).copy(config = decodeConfig(storedConfig))
                    conversationDAO.update(
                        // Retain unknown future config fields and their original bytes, too.
                        conversationToConversationEntity(conversation).copy(config = storedConfig)
                    )

                    if (previous != null) {
                        // A session supplies its last committed snapshot: serialize only edited
                        // nodes and express deletions explicitly, without reading old message bodies.
                        val changes = conversationNodeChanges(previous, conversation)
                        val oldById = previous.messageNodes.associateBy { it.id.toString() }
                        val retained = attachmentUrls(changes.upserts.flatMap { it.second.messages })
                        (changes.changedIds.mapNotNull { oldById[it] }).forEach { old ->
                            discardedFiles += attachmentUrls(old.messages) - retained
                        }
                        val entities = changes.upserts.map { (index, node) ->
                            MessageNodeEntity(node.id.toString(), conversation.id.toString(), index,
                                JsonInstant.encodeToString(node.messages), node.selectIndex)
                        }
                        changes.deletions.toList().chunked(200).forEach { messageNodeDAO.deleteByIds(it) }
                        if (entities.isNotEmpty()) messageNodeDAO.insertAll(entities)
                        archiveRawChanges(conversation,
                            if (previous.assistantId == conversation.assistantId) changes.upserts
                            else conversation.messageNodes.mapIndexed { index, node -> index to node },
                            changes.deletions)
                        messageFtsManager.reindexNodes(
                            conversation.id.toString(), conversation.title, conversation.updateAt,
                            changes.changedIds, changes.upserts.map { it.second },
                        )
                        return@withTransaction conversation
                    }

                    // Full snapshots from import/edit paths retain the conservative DB comparison.
                    val existingIds = messageNodeDAO.getNodeIdsOfConversation(conversation.id.toString()).toSet()

                    val newEntities = conversation.messageNodes.mapIndexed { index, node ->
                        MessageNodeEntity(
                            id = node.id.toString(),
                            conversationId = conversation.id.toString(),
                            nodeIndex = index,
                            messages = JsonInstant.encodeToString(node.messages),
                            selectIndex = node.selectIndex
                        )
                    }
                    val newById = newEntities.associateBy { it.id }

                    // 只对本轮涉及、且之前已存在的 id 去查内容做比较，缩小命中超大行的范围
                    val idsNeedCompare = newEntities.map { it.id }.filter { it in existingIds }
                    val existingById = if (idsNeedCompare.isEmpty()) {
                        emptyMap()
                    } else {
                        try {
                            idsNeedCompare.chunked(200).flatMap { messageNodeDAO.getNodesByIds(it) }.associateBy { it.id }
                        } catch (e: SQLiteBlobTooBigException) {
                            Log.w(
                                TAG,
                                "updateConversation: blob too big while comparing existing nodes, " +
                                    "conversationId=${conversation.id}, will treat all as changed and overwrite",
                                e
                            )
                            // 读不出旧内容就没法比较，保守地当作全部有变化，直接覆盖写入（REPLACE，安全）
                            emptyMap()
                        }
                    }

                    // 只处理真正发生变化的 node：内容/顺序(nodeIndex)/selectIndex 有任何不同才写库；
                    // 读不到旧内容的（existingById 里没有对应 id）也会被判定为"变化"，直接覆盖写入
                    val toUpsert = newEntities.filter { newEntity ->
                        existingById[newEntity.id] != newEntity
                    }
                    val toDeleteIds = existingIds - newById.keys

                    // This cold path may need old rows to collect attachments. Use the complete
                    // chunk-capable reader; never skip an oversized row and infer it was empty.
                    if (toDeleteIds.isNotEmpty() || toUpsert.isNotEmpty()) {
                        val changed = toUpsert.mapTo(mutableSetOf()) { it.id } + toDeleteIds
                        messageNodeDAO.loadCompleteNodes(conversation.id.toString())
                            .filter { it.id in changed }.forEach { old ->
                                discardedFiles += attachmentUrls(JsonInstant.decodeFromString<List<UIMessage>>(old.messages))
                            }
                    }

                    if (toDeleteIds.isNotEmpty()) {
                        toDeleteIds.toList().chunked(200).forEach { messageNodeDAO.deleteByIds(it) }
                    }
                    if (toUpsert.isNotEmpty()) {
                        messageNodeDAO.insertAll(toUpsert)
                    }

                    val archiveChangedIds = toUpsert.mapTo(hashSetOf()) { it.id }
                    archiveRawChanges(conversation, conversation.messageNodes.mapIndexedNotNull { index, node ->
                        (index to node).takeIf { node.id.toString() in archiveChangedIds }
                    }, toDeleteIds)

                    val changedNodeIds = toUpsert.map { it.id }.toSet() + toDeleteIds
                    if (changedNodeIds.isNotEmpty()) {
                        messageFtsManager.reindexNodes(
                            conversationId = conversation.id.toString(),
                            conversationTitle = conversation.title,
                            updateAt = conversation.updateAt,
                            changedNodeIds = changedNodeIds,
                            currentNodes = conversation.messageNodes,
                        )
                    }
                    conversation
                }
                deleteUnreferencedChatFiles(discardedFiles)
                saved
            } catch (e: Exception) {
                Log.e(TAG, "updateConversation failed, conversationId=${conversation.id}", e)
                throw e
            }
        }
    }

    /** Persist a session-owned append/replace journal without comparing untouched message bodies. */
    internal suspend fun updateConversationWithDelta(
        conversation: Conversation,
        previous: Conversation,
        delta: ConversationNodeDelta,
    ): Conversation = withContext(Dispatchers.IO) {
        attachmentReferenceGuard.beginWrite().use {
            require(delta.appliesTo(previous, conversation)) { "Node delta does not match the committed history" }
            val changes = delta.changes
            require(changes.upserts.none { (_, node) -> node.messages.any { it.hasBase64Part() } })
            val discardedFiles = mutableSetOf<String>()
            try {
                val saved = database.withTransaction {
                    // A late checkpoint must never create nodes after the parent conversation was deleted.
                    val storedConfig = checkNotNull(conversationDAO.getConfig(conversation.id.toString())) {
                        "Conversation no longer exists"
                    }
                    var cleanedNodes: MutableList<MessageNode>? = null
                    val upserts = changes.upserts.map { (index, node) ->
                        if (index < previous.messageNodes.size) {
                            discardedFiles += attachmentUrls(previous.messageNodes[index].messages) - attachmentUrls(node.messages)
                        }
                        val messages = filesManager.withoutDeletedAttachments(node.messages)
                        val cleaned = if (messages === node.messages) node else node.copy(messages = messages)
                        if (cleaned !== node) {
                            if (cleanedNodes == null) cleanedNodes = conversation.messageNodes.toMutableList()
                            cleanedNodes!![index] = cleaned
                        }
                        index to cleaned
                    }
                    val conversation = (cleanedNodes?.let { conversation.copy(messageNodes = it) } ?: conversation)
                        .copy(config = decodeConfig(storedConfig))
                    val entities = upserts.map { (index, node) ->
                        MessageNodeEntity(node.id.toString(), conversation.id.toString(), index,
                            JsonInstant.encodeToString(node.messages), node.selectIndex)
                    }
                    conversationDAO.update(conversationMetadataToEntity(conversation).copy(config = storedConfig))
                    if (entities.isNotEmpty()) messageNodeDAO.insertAll(entities)
                    archiveRawChanges(conversation, upserts, changes.deletions)
                    messageFtsManager.reindexNodes(
                        conversation.id.toString(), conversation.title, conversation.updateAt,
                        changes.changedIds, upserts.map { it.second }, failOnError = true,
                    )
                    conversation
                }
                deleteUnreferencedChatFiles(discardedFiles)
                saved
            } catch (e: Exception) {
                Log.e(TAG, "updateConversationWithDelta failed, conversationId=${conversation.id}", e)
                throw e
            }
        }
    }

    suspend fun deleteConversation(
        conversation: Conversation,
        retainAttachments: Boolean = false,
    ): Conversation = withContext(Dispatchers.IO) {
        attachmentReferenceGuard.beginWrite().use {
            try {
                // Read after generation has stopped; the list projection/old UI snapshot may be stale.
                val fullConversation = getConversationById(conversation.id) ?: conversation
                messageFtsManager.deleteConversation(conversation.id.toString())
                database.withTransaction {
                    if (retainAttachments) {
                        val dao = database.rawArchiveDao()
                        undoRawArchives[conversation.id] = UndoRawArchive(
                            dao.allRevisions(fullConversation.assistantId.toString(), fullConversation.id.toString()),
                            dao.allHeads(fullConversation.assistantId.toString(), fullConversation.id.toString()),
                        )
                        undoAttachmentNames[conversation.id] = fullConversation.files.mapNotNull {
                            localAttachmentFile(it.toString())?.name
                        }.toSet()
                    }
                    // message_node 会通过 CASCADE 自动删除
                    conversationDAO.delete(
                        conversationToConversationEntity(conversation)
                    )
                }
                if (!retainAttachments) releaseDeletedConversationAttachments(fullConversation)
                fullConversation
            } catch (e: Exception) {
                undoAttachmentNames.remove(conversation.id)
                undoRawArchives.remove(conversation.id)
                Log.e(TAG, "deleteConversation failed, conversationId=${conversation.id}", e)
                throw e
            }
        }
    }

    /** Undo keeps bytes until its action completes; recheck references after restore before collection. */
    suspend fun releaseDeletedConversationAttachments(conversation: Conversation) = withContext(Dispatchers.IO) {
        undoAttachmentNames.remove(conversation.id)
        undoRawArchives.remove(conversation.id)
        filesManager.deleteExtraInfoSnapshots(conversation.id)
        filesManager.deleteChatReadMark(conversation.id)
        deleteUnreferencedChatFiles(conversation.files.mapTo(mutableSetOf()) { it.toString() })
    }

    /** Preserve the original conversation/list identity when no deleted attachment is present. */
    fun withoutDeletedAttachments(conversation: Conversation): Conversation {
        if (!filesManager.hasDeletedAttachments) return conversation
        var changed = false
        val nodes = conversation.messageNodes.map { node ->
            val messages = filesManager.withoutDeletedAttachments(node.messages)
            if (messages === node.messages) node else {
                changed = true
                node.copy(messages = messages)
            }
        }
        return if (changed) conversation.copy(messageNodes = nodes) else conversation
    }

    /** Manual deletion only visits rows mentioning the selected filenames; no image decoding/full repair. */
    suspend fun repairDeletedAttachmentReferences(fileNames: Set<String>): Int = withContext(Dispatchers.IO) {
        if (fileNames.isEmpty()) return@withContext 0
        val ids = linkedSetOf<String>()
        fileNames.toList().chunked(50).forEach { batch ->
            val clauses = batch.joinToString(" OR ") { "instr(messages, ?) > 0" }
            ids += messageNodeDAO.findNodeIdsRaw(SimpleSQLiteQuery(
                "SELECT id FROM message_node WHERE $clauses", batch.toTypedArray(),
            ))
        }
        var repaired = 0
        for (id in ids) {
            database.withTransaction {
                val entity = messageNodeDAO.loadCompleteNode(id) ?: return@withTransaction
                val original = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages)
                check(original.isNotEmpty() && entity.selectIndex in original.indices) {
                    "会话包含无法读取的消息，附件清理已停止，原始消息未被覆盖。"
                }
                val cleaned = filesManager.withoutDeletedAttachments(original)
                if (cleaned !== original) repaired += messageNodeDAO.repairAttachments(
                    id, entity.messages, JsonInstant.encodeToString(cleaned),
                )
            }
        }
        repaired
    }

    suspend fun repairInvalidAttachments(): Int = repairInvalidAttachments { filesManager.validAttachments(it) }

    /**
     * Points every message-node reference to a planned duplicate at its byte-identical survivor.
     * Only nodes that mention a duplicate name are read; each is rewritten with the same
     * compare-and-set used by attachment repair, so a concurrent edit to that node wins.
     * Returns the number of rewritten nodes.
     */
    internal suspend fun remapDuplicateAttachmentReferences(plan: DuplicateAttachmentPlan): Int =
        withContext(Dispatchers.IO) {
            if (plan.isEmpty) return@withContext 0
            val ids = linkedSetOf<String>()
            plan.canonicalByDuplicate.keys.toList().chunked(50).forEach { batch ->
                val clauses = batch.joinToString(" OR ") { "instr(messages, ?) > 0" }
                ids += messageNodeDAO.findNodeIdsRaw(SimpleSQLiteQuery(
                    "SELECT id FROM message_node WHERE $clauses", batch.toTypedArray(),
                ))
            }
            var rewritten = 0
            for (id in ids) {
                database.withTransaction {
                    val entity = messageNodeDAO.loadCompleteNode(id) ?: return@withTransaction
                    val original = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages)
                    check(original.isNotEmpty() && entity.selectIndex in original.indices) {
                        "会话包含无法读取的消息，合并已停止，原始消息未被覆盖。"
                    }
                    val remapped = remapDuplicateAttachments(original, plan)
                    if (remapped !== original) rewritten += messageNodeDAO.repairAttachments(
                        id, entity.messages, JsonInstant.encodeToString(remapped),
                    )
                }
            }
            rewritten
        }

    /**
     * Upload file names that no chat branch, favorite snapshot, conversation field or setting
     * mentions. Read-only: the caller shows the result for confirmation before deleting anything.
     *
     * One token pass over every row, linear in history size. Returns empty when settings are
     * unavailable, so a missing reference source can never make files look unused.
     */
    suspend fun findUnreferencedChatFileNames(candidates: Collection<String>): Set<String> =
        withContext(Dispatchers.IO) {
            val store = settingsStore ?: return@withContext emptySet()
            val settings = store.settingsFlow.value
            if (settings.init) return@withContext emptySet()
            val remaining = candidates.filterTo(hashSetOf()) { it.isNotBlank() && !it.startsWith(".") }
            removeReferencedFileNames(JsonInstant.encodeToString(settings), remaining)
            undoAttachmentNames.values.forEach { remaining.removeAll(it) }
            for (conversationId in conversationDAO.getAllIds()) {
                if (remaining.isEmpty()) break
                val entity = conversationDAO.getConversationById(conversationId) ?: continue
                removeReferencedFileNames(entity.nodes, remaining)
                removeReferencedFileNames(entity.compressionSummaries, remaining)
                removeReferencedFileNames(entity.customSystemPrompt, remaining)
                for (id in messageNodeDAO.getNodeIdsOfConversation(conversationId)) {
                    if (remaining.isEmpty()) break
                    val node = database.withTransaction { messageNodeDAO.loadCompleteNode(id) } ?: continue
                    removeReferencedFileNames(node.messages, remaining)
                }
            }
            favoriteDAO.listAll().first().forEach { favorite ->
                removeReferencedFileNames(favorite.snapshotJson, remaining)
                removeReferencedFileNames(favorite.refJson, remaining)
                favorite.metaJson?.let { removeReferencedFileNames(it, remaining) }
            }
            // Settings may have changed during the scan; a plain substring check is cheap here.
            // (A per-file messageNodeDAO.referencesFile re-check would be a full table scan per
            // candidate; the token pass above already read every row.)
            val settingsJson = JsonInstant.encodeToString(store.settingsFlow.value)
            remaining.filterTo(hashSetOf()) { name -> !settingsJson.contains(name) }
        }

    /** A replaced avatar/background can still belong to a chat, favorite, draft or another setting. */
    suspend fun collectReplacedChatAssets(urls: Collection<String>): Pair<Int, Long> = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) return@withContext 0 to 0L
        try {
            val ids = urls.distinct().mapNotNull { url ->
                if (!filesManager.isManagedChatFile(url)) return@mapNotNull null
                val file = localAttachmentFile(url) ?: return@mapNotNull null
                filesManager.getByRelativePath("${me.rerere.rikkahub.data.files.FileFolders.UPLOAD}/${file.name}")?.id
            }.toSet()
            if (ids.isEmpty()) return@withContext 0 to 0L
            me.rerere.rikkahub.data.files.collectUnreferencedAttachments(
                filesManager, ids, ::findUnreferencedChatFileNames,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The edit is already durable. An unreadable reference source must keep old bytes,
            // and must not turn a successful manual avatar change into a UI coroutine failure.
            Log.w(TAG, "Skipped replaced image cleanup because references could not be verified", e)
            0 to 0L
        }
    }

    /** Read a consistent node, scan outside the writer transaction, then compare-and-set its JSON. */
    internal suspend fun repairInvalidAttachments(
        scanAttachments: suspend (List<UIMessage>) -> List<UIMessage>,
    ): Int = withContext(Dispatchers.IO) {
        var repaired = 0
        for (conversationId in conversationDAO.getAllIds()) {
            for (id in messageNodeDAO.getNodeIdsOfConversation(conversationId)) {
                val entity = database.withTransaction { messageNodeDAO.loadCompleteNode(id) } ?: continue
                val original = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages)
                check(original.isNotEmpty() && entity.selectIndex in original.indices) {
                    "会话包含无法读取的消息，附件清理已停止，原始消息未被覆盖。"
                }
                // File reads and native image decoding can be slow. Do not hold Room's writer
                // while checking them; a concurrent stream/branch edit wins the conditional write.
                val cleaned = scanAttachments(original)
                if (cleaned !== original) {
                    repaired += messageNodeDAO.repairAttachments(
                        entity.id, entity.messages, JsonInstant.encodeToString(cleaned),
                    )
                }
            }
        }
        repaired
    }

    /** Lifecycle garbage collection follows durable writes and retains every DB branch/settings reference. */
    private suspend fun deleteUnreferencedChatFiles(urls: Set<String>) {
        if (urls.isEmpty()) return
        val store = settingsStore ?: return
        for (url in urls) {
            val file = localAttachmentFile(url) ?: continue
            if (!filesManager.isManagedChatFile(url)) continue
            if (file.name in attachmentReferenceGuard.protectedNames()) continue
            database.withTransaction {
                val settings = store.settingsFlow.value
                if (undoAttachmentNames.values.none { file.name in it } &&
                    !settings.init && !JsonInstant.encodeToString(settings).contains(file.name) &&
                    !messageNodeDAO.referencesFile(file.name)) {
                    attachmentReferenceGuard.ifUnpinned(file.name) {
                        filesManager.deleteChatFiles(listOf(file.toUri()))
                    }
                }
            }
        }
    }

    suspend fun searchMessages(keyword: String) = messageFtsManager.search(keyword)

    suspend fun rebuildAllIndexes(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
        try {
            messageFtsManager.deleteAll()
            val allIds = conversationDAO.getAllIds()
            val total = allIds.size
            allIds.forEachIndexed { index, id ->
                val entity = conversationDAO.getConversationById(id) ?: return@forEachIndexed
                val nodes = loadMessageNodes(entity.id)
                val conversation = conversationEntityToConversation(entity, nodes)
                messageFtsManager.indexConversation(conversation)
                onProgress(index + 1, total)
            }
        } catch (e: Exception) {
            Log.e(TAG, "rebuildAllIndexes failed", e)
            throw e
        }
    }

    suspend fun deleteConversationOfAssistant(assistantId: Uuid) {
        getConversationsOfAssistant(assistantId).first().forEach { conversation ->
            deleteConversation(conversation)
        }
    }

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        require(conversation.messageNodes.none { it.messages.any { message -> message.hasBase64Part() } })
        return conversationMetadataToEntity(conversation)
    }

    private fun conversationMetadataToEntity(conversation: Conversation): ConversationEntity {
        return ConversationEntity(
            id = conversation.id.toString(),
            title = conversation.title,
            nodes = "[]",  // nodes 现在存储在单独的表中
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            assistantId = conversation.assistantId.toString(),
            chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
            isPinned = conversation.isPinned,
            customSystemPrompt = conversation.customSystemPrompt ?: "",
            folderId = conversation.folderId?.toString() ?: "",
            compressionSummaries = JsonInstant.encodeToString(conversation.compressionSummaries),
            compressedNodeIds = JsonInstant.encodeToString(conversation.compressedMessageNodeIds),
            compressionRevision = conversation.compressionRevision,
            sendCompressedSummary = conversation.sendCompressedSummary,
            legacyCompressedSummary = conversation.compressedSummary,
            compressionHistory = JsonInstant.encodeToString(conversation.compressionHistory),
            config = conversation.config?.let { JsonInstant.encodeToString(it) }.orEmpty(),
        )
    }

    fun conversationEntityToConversation(
        conversationEntity: ConversationEntity,
        messageNodes: List<MessageNode>,
        messageNodesLoaded: Boolean = true,
    ): Conversation {
        return Conversation(
            id = Uuid.parse(conversationEntity.id),
            title = conversationEntity.title,
            messageNodes = messageNodes.also { nodes ->
                check(nodes.all { it.messages.isNotEmpty() && it.selectIndex in it.messages.indices }) {
                    "会话包含无法读取的消息，请先恢复记录。原有记录未被修改。"
                }
            },
            messageNodesLoaded = messageNodesLoaded,
            createAt = Instant.ofEpochMilli(conversationEntity.createAt),
            updateAt = Instant.ofEpochMilli(conversationEntity.updateAt),
            assistantId = Uuid.parse(conversationEntity.assistantId),
            chatSuggestions = JsonInstant.decodeFromString(conversationEntity.chatSuggestions),
            isPinned = conversationEntity.isPinned,
            customSystemPrompt = conversationEntity.customSystemPrompt.ifEmpty { null },
            folderId = conversationEntity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
            compressionSummaries = JsonInstant.decodeFromString(conversationEntity.compressionSummaries),
            compressedMessageNodeIds = JsonInstant.decodeFromString(conversationEntity.compressedNodeIds),
            compressionRevision = conversationEntity.compressionRevision,
            sendCompressedSummary = conversationEntity.sendCompressedSummary,
            compressionHistory = JsonInstant.decodeFromString(conversationEntity.compressionHistory),
            config = decodeConfig(conversationEntity.config),
        )
    }

    private fun decodeConfig(config: String): ConversationConfig? =
        config.takeIf { it.isNotBlank() }?.let { JsonInstant.decodeFromString<ConversationConfig>(it) }

    fun getPinnedConversations(): Flow<List<Conversation>> {
        return conversationDAO
            .getPinnedConversations()
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList(), messageNodesLoaded = false)
                }
            }
    }

    suspend fun togglePinStatus(conversationId: Uuid): Boolean? = database.withTransaction {
        conversationDAO.togglePinStatus(conversationId.toString())
        conversationDAO.getPinStatus(conversationId.toString())
    }

    /**
     * 单列更新会话的文件夹归属，folderId 为 null 表示移出文件夹（未归类）。
     */
    suspend fun updateConversationFolderId(conversationId: Uuid, folderId: Uuid?) {
        conversationDAO.updateFolderId(
            id = conversationId.toString(),
            folderId = folderId?.toString() ?: ""
        )
    }

    private fun conversationSummaryToConversation(entity: LightConversationEntity): Conversation {
        return Conversation(
            id = Uuid.parse(entity.id),
            assistantId = Uuid.parse(entity.assistantId),
            title = entity.title,
            isPinned = entity.isPinned,
            createAt = Instant.ofEpochMilli(entity.createAt),
            updateAt = Instant.ofEpochMilli(entity.updateAt),
            messageNodes = emptyList(),
            messageNodesLoaded = false,
            folderId = entity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
        )
    }

    private suspend fun loadMessageNodes(conversationId: String): List<MessageNode> {
        val favoriteNodeIds = favoriteDAO
            .getFavoriteNodeIdsOfConversation(conversationId)
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .toSet()

        return database.withTransaction {
            messageNodeDAO.mapCompleteNodes(conversationId) { entity ->
                val original = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages)
                val messages = filesManager.validAttachments(original)
                if (messages !== original) {
                    messageNodeDAO.repairAttachments(entity.id, entity.messages, JsonInstant.encodeToString(messages))
                }
                check(messages.isNotEmpty() && entity.selectIndex in messages.indices) {
                    "会话包含无法读取的消息，请先恢复记录。原有记录未被修改。"
                }
                val nodeId = Uuid.parse(entity.id)
                MessageNode(nodeId, messages, entity.selectIndex, favoriteNodeIds.contains(nodeId))
            }
        }
    }

    private suspend fun saveMessageNodes(conversationId: String, nodes: List<MessageNode>) {
        val entities = nodes.mapIndexed { index, node ->
            MessageNodeEntity(
                id = node.id.toString(),
                conversationId = conversationId,
                nodeIndex = index,
                messages = JsonInstant.encodeToString(node.messages),
                selectIndex = node.selectIndex
            )
        }
        messageNodeDAO.insertAll(entities)
    }

    /**
     * 获取指定日期范围内的所有聊天记录（用于日记总结）
     * 从 message_node 表读取，不依赖 memory_bank
     */
    suspend fun getMessagesByDateRange(startDate: String, endDate: String): List<DatedMessage> = withContext(Dispatchers.IO) {
        val allConversationIds = conversationDAO.getAllIds()
        val result = mutableListOf<DatedMessage>()

        for (conversationId in allConversationIds) {
            val nodes = try {
                loadMessageNodes(conversationId)
            } catch (e: Exception) {
                Log.e(TAG, "getMessagesByDateRange: failed to load nodes, conversationId=$conversationId", e)
                continue
            }

            for (node in nodes) {
                // 只取选中的消息（selectIndex 对应的）
                val selectedMessage = node.messages.getOrNull(node.selectIndex) ?: continue
                val dateStr = selectedMessage.createdAt.toString().take(10) // yyyy-MM-dd

                if (dateStr >= startDate && dateStr <= endDate) {
                    val text = selectedMessage.parts.filterIsInstance<UIMessagePart.Text>()
                        .joinToString("\n") { it.text }
                        .trim()
                    if (text.isNotBlank()) {
                        result.add(
                            DatedMessage(
                                date = dateStr,
                                role = selectedMessage.role.name.lowercase(),
                                content = text,
                                assistantId = null, // 无法直接从消息获取
                                conversationId = conversationId
                            )
                        )
                    }
                }
            }
        }

        result.sortedBy { it.date }
    }
}

/**
 * 带日期的聊天记录条目（用于日记总结）
 */
data class DatedMessage(
    val date: String,
    val role: String,
    val content: String,
    val assistantId: String?,
    val conversationId: String
)

/**
 * 轻量级的会话查询结果，不包含 nodes 和 suggestions 字段
 */
data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val folderId: String = "",
)

data class ConversationPageResult(
    val items: List<Conversation>,
    val nextOffset: Int?,
)
