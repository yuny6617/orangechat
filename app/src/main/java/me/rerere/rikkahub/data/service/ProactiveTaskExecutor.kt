package me.rerere.rikkahub.data.service

import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.datastore.AssistantProactiveConfig
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import java.time.ZoneId
import kotlin.uuid.Uuid
import me.rerere.rikkahub.utils.JsonInstant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.util.UUID
import me.rerere.ai.ui.UIMessage

internal fun proactiveCommitMessageId(taskId: String, index: Int): String =
    UUID.nameUUIDFromBytes("orangechat-proactive:$taskId:$index".toByteArray(Charsets.UTF_8)).toString()

/** Process-wide serial dispatcher. No stale pending work is replayed after a process restart. */
internal class ProactiveTaskExecutor(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val repository: ConversationRepository,
    private val chatService: ChatService,
) {
    val store = ProactiveTaskStore(context)

    suspend fun migrate() = migrationMutex.withLock {
        val settings = settingsStore.settingsFlow.first { !it.init }
        if (settings.proactiveMessageSetting.assistantConfigs != null) {
            if (settings.proactiveMessageSetting.globalPolicy.budgetZoneId.isBlank()) settingsStore.update { latest ->
                latest.copy(proactiveMessageSetting = latest.proactiveMessageSetting.copy(
                    globalPolicy = latest.proactiveMessageSetting.globalPolicy.copy(budgetZoneId = ZoneId.systemDefault().id),
                ))
            }
            return@withLock
        }
        val legacy = settings.proactiveMessageSetting.assistantId
        val assistant = if (legacy.isBlank()) settings.getCurrentAssistant()
            else settings.assistants.singleOrNull { it.id.toString() == legacy }
        val conversation = assistant?.let { repository.getMostRecentConversationId(it.id) }
            ?.let { repository.getConversationById(it) }
        val frozen = if (assistant != null && conversation != null && conversation.assistantId == assistant.id) listOf(
            AssistantProactiveConfig(assistant.id.toString(), conversation.id.toString()),
        ) else emptyList()
        settingsStore.update { latest ->
            val old = latest.proactiveMessageSetting
            if (old.assistantConfigs != null) latest else latest.copy(proactiveMessageSetting = old.copy(
                assistantConfigs = frozen.filter { config -> latest.assistants.any { it.id.toString() == config.assistantId } },
                globalPolicy = old.globalPolicy.copy(budgetZoneId = ZoneId.systemDefault().id),
            ))
        }
    }

    suspend fun host(): ProactiveHostSnapshot {
        val settings = settingsStore.settingsFlow.value
        val conversations = settings.proactiveMessageSetting.assistantConfigs.orEmpty().mapNotNull { config ->
            runCatching { Uuid.parse(config.conversationId) }.getOrNull()?.let { id ->
                repository.getConversationById(id)?.let { persisted ->
                    val live = chatService.getOrCreateSession(id).state.value
                    if (live.messageNodesLoaded) live else persisted
                }
            }
        }
        return snapshot(conversations)
    }

    private fun snapshot(conversations: List<Conversation>): ProactiveHostSnapshot {
        val settings = settingsStore.settingsFlow.value
        return ProactiveHostSnapshot(
            setting = settings.proactiveMessageSetting,
            assistantIds = settings.assistants.mapTo(hashSetOf()) { it.id.toString() },
            conversationOwners = conversations.associate { it.id.toString() to it.assistantId.toString() },
            userAnchors = conversations.mapNotNull { conversation ->
                conversation.currentMessages.lastOrNull { it.role == MessageRole.USER }?.let {
                    conversation.id.toString() to it.id.toString()
                }
            }.toMap(),
            busyConversations = conversations.filter { chatService.getOrCreateSession(it.id).isGenerating }
                .mapTo(hashSetOf()) { it.id.toString() },
        )
    }

    suspend fun drain(trigger: String, run: suspend (ProactiveTask) -> Unit) = dispatchMutex.withLock {
        initialize(context)
        migrate()
        recoverCommits(
            recordSent = ::recordRecoveredSent,
            commit = ::commitRecovered,
        )
        val now = System.currentTimeMillis()
        val host = host()
        if (!host.setting.enabled) return@withLock
        val prefs = context.getSharedPreferences("proactive_dispatch", Context.MODE_PRIVATE)
        val last = prefs.getLong("last_cycle", 0L)
        if (trigger != "manual" && now - last < host.setting.minIntervalMinutes.coerceAtLeast(1) * 60_000L) {
            return@withLock
        }
        check(prefs.edit().putLong("last_cycle", now).commit()) { "Could not persist proactive cycle" }
        // Stale targets must not linger and block a newly configured window for this assistant.
        store.snapshot().tasks.filter { it.status == ProactiveTaskStatus.Pending &&
            !proactiveTaskAllowed(it, host, now) }.map { it.assistantId }.distinct().forEach { store.cancel(it) }
        val appointments = if (host.setting.allowAiScheduledWakeup) ProactiveWakeupStore(context).all() else emptyList()
        host.setting.assistantConfigs.orEmpty().filter { it.enabled }.forEach { config ->
            val appointment = appointments.singleOrNull { it.assistantId == config.assistantId &&
                it.conversationId == config.conversationId }
            if (trigger != "manual" && appointment != null && appointment.timeMillis > now) return@forEach
            val anchor = host.userAnchors[config.conversationId] ?: return@forEach
            // Promote a previously scheduled task instead of leaving it behind its cooldown.
            if (trigger == "manual") store.promotePendingToManual(config.assistantId, now)
            if (store.snapshot().tasks.none { it.assistantId == config.assistantId &&
                    it.status in setOf(ProactiveTaskStatus.Pending, ProactiveTaskStatus.Running) }) {
                store.enqueue(ProactiveTask(
                    assistantId = config.assistantId, conversationId = config.conversationId,
                    anchorUserMessageId = anchor,
                    trigger = if (trigger == "manual") "manual" else if (appointment != null) "appointment" else "automatic",
                    appointmentToken = appointment?.token.takeIf { trigger != "manual" },
                    createdAtMillis = now,
                ))
            }
        }
        while (true) {
            val task = store.claimNext(host(), System.currentTimeMillis()) ?: break
            try { run(task) } finally {
                jobs.remove(task.taskId)
                store.finish(task.taskId, ProactiveTaskStatus.Failed, "no_message")
            }
        }
    }

    suspend fun observeRevocations() {
        settingsStore.settingsFlow.collect { settings ->
            val policy = settings.proactiveMessageSetting
            val active = store.snapshot().tasks.filter { it.status in setOf(ProactiveTaskStatus.Pending, ProactiveTaskStatus.Running) }
            active.filter { task ->
                !policy.enabled || policy.globalPolicy.paused ||
                    settings.assistants.none { it.id.toString() == task.assistantId } ||
                    policy.assistantConfigs?.singleOrNull { it.assistantId == task.assistantId }?.let {
                        !it.enabled || it.conversationId != task.conversationId ||
                            (task.admittedConfig != null && task.admittedConfig != it)
                    } != false
            }.map { it.assistantId }.distinct().forEach { cancel(context, it) }
        }
    }

    suspend fun bindJob(task: ProactiveTask) {
        jobs[task.taskId] = currentCoroutineContext()[Job]!!
        requireAllowed(task)
    }

    suspend fun requireAllowed(task: ProactiveTask) {
        currentCoroutineContext().ensureActive()
        if (!store.canCommit(task.taskId, host(), System.currentTimeMillis())) {
            throw kotlinx.coroutines.CancellationException("Proactive task revoked")
        }
    }

    suspend fun <T> networkTurn(task: ProactiveTask, call: suspend () -> T): T {
        val token = store.reserveTurn(task.taskId, host(), System.currentTimeMillis())
            ?: throw kotlinx.coroutines.CancellationException("Proactive budget or authorization exhausted")
        try {
            if (!store.markDispatched(token, host(), System.currentTimeMillis())) {
                throw kotlinx.coroutines.CancellationException("Proactive task revoked before network dispatch")
            }
            return call()
        } finally { store.settleTurn(token) }
    }

    suspend fun prepareCommit(task: ProactiveTask, messages: List<UIMessage>): Boolean {
        require(messages.isNotEmpty())
        val ids = messages.indices.map { proactiveCommitMessageId(task.taskId, it) }
        val identified = messages.mapIndexed { index, message -> message.copy(id = kotlin.uuid.Uuid.parse(ids[index])) }
        val payload = JsonInstant.encodeToString(identified)
        if (payload.toByteArray(Charsets.UTF_8).size > ProactiveTaskStore.PROACTIVE_COMMIT_MAX_BYTES) return false
        return store.beginCommit(task.taskId, host(), System.currentTimeMillis(), ids, payload)
    }

    fun startCommit(task: ProactiveTask): ProactiveTask? = store.startCommit(task.taskId)

    fun markCommitSent(task: ProactiveTask): Boolean = store.markCommitSent(task.taskId)

    fun decodeCommitPayload(task: ProactiveTask): List<UIMessage>? = runCatching {
        JsonInstant.decodeFromString<List<UIMessage>>(requireNotNull(task.commitPayload))
    }.getOrNull()?.takeIf { messages ->
        messages.isNotEmpty() && messages.size == task.commitMessageIds.size &&
            messages.map { it.id.toString() } == task.commitMessageIds &&
            messages.sumOf { JsonInstant.encodeToString(it).toByteArray(Charsets.UTF_8).size } <=
            ProactiveTaskStore.PROACTIVE_COMMIT_MAX_BYTES
    }

    suspend fun commitRecovered(task: ProactiveTask, messages: List<UIMessage>): Boolean {
        val conversationId = runCatching { Uuid.parse(task.conversationId) }.getOrNull() ?: return false
        val session = chatService.getOrCreateSession(conversationId)
        return session.withStateLock {
            val settings = settingsStore.settingsFlow.value
            val assistantId = runCatching { Uuid.parse(task.assistantId) }.getOrNull() ?: return@withStateLock false
            if (settings.proactiveMessageSetting.assistantConfigs?.singleOrNull { it.assistantId == task.assistantId }
                    ?.let { it.enabled && it.conversationId == task.conversationId } != true ||
                settings.assistants.none { it.id == assistantId } || session.isDeleted
            ) {
                store.abandonCommit(task.taskId, ProactiveTaskStatus.Interrupted, "commit_guard_changed")
                return@withStateLock true
            }
            val conversation = repository.getConversationById(conversationId)
                ?: run {
                    store.abandonCommit(task.taskId, ProactiveTaskStatus.Interrupted, "conversation_missing")
                    return@withStateLock true
                }
            val live = session.state.value
            val current = if (live.messageNodesLoaded) live else conversation
            if (current.assistantId != assistantId ||
                current.currentMessages.lastOrNull { it.role == MessageRole.USER }?.id.toString() != task.anchorUserMessageId
            ) {
                store.abandonCommit(task.taskId, ProactiveTaskStatus.Interrupted, "anchor_changed")
                return@withStateLock true
            }
            val anchorId = runCatching { Uuid.parse(task.anchorUserMessageId) }.getOrNull()
                ?: run {
                    store.abandonCommit(task.taskId, ProactiveTaskStatus.Interrupted, "invalid_anchor")
                    return@withStateLock true
                }
            val saved = repository.appendProactiveMessagesIdempotently(
                conversationId, assistantId, anchorId, messages,
            ) ?: return@withStateLock false
            chatService.updateConversation(saved.id, saved)
            session.persistedConversation = saved
            session.messageJournal.invalidate()
            true
        }
    }

    fun recordRecoveredSent(task: ProactiveTask, messages: List<UIMessage>) {
        val stateStore = ProactiveMessageStateStore(
            context, "${task.assistantId}_${task.conversationId}",
        )
        stateStore.recordCommittedIds(task.commitMessageIds)
    }

    suspend fun recoverCommits(
        recordSent: (ProactiveTask, List<UIMessage>) -> Unit,
        commit: suspend (ProactiveTask, List<UIMessage>) -> Boolean,
    ) {
        for (task in store.pendingCommits()) {
            val payload = decodeCommitPayload(task)
            if (payload == null) {
                store.abandonCommit(task.taskId, ProactiveTaskStatus.Failed, "invalid_commit_payload")
                continue
            }
            // First reconcile an already committed Room transaction, even if settings/anchor
            // changed after it. Never append in that case; only repair the ledger receipt.
            val persisted = repository.getConversationById(Uuid.parse(task.conversationId))
            val persistedById = persisted?.messageNodes?.flatMap { it.messages }?.associateBy { it.id }.orEmpty()
            if (persisted?.assistantId?.toString() == task.assistantId &&
                payload.all { persistedById[it.id] == it }
            ) {
                recordSent(task, payload)
                store.markCommitSent(task.taskId)
                continue
            }
            val host = host()
            val now = System.currentTimeMillis()
            if (!proactiveTaskAllowed(task, host, now)) {
                store.abandonCommit(task.taskId, ProactiveTaskStatus.Interrupted, "commit_guard_changed")
                continue
            }
            val started = store.startCommit(task.taskId) ?: continue
            if (!runCatching { commit(started, payload) }.getOrDefault(false)) {
                // Payload stays durable for the next process launch; never re-run generation.
                return
            }
            recordSent(task, payload)
            store.markCommitSent(task.taskId)
        }
    }

    companion object {
        private val dispatchMutex = Mutex()
        private val migrationMutex = Mutex()
        private val startLock = Any()
        private var started = false
        private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

        fun initialize(context: Context) = synchronized(startLock) {
            if (!started) {
                val store = ProactiveTaskStore(context)
                store.interruptRunning()
                store.cancel()
                val wakeups = ProactiveWakeupStore(context)
                wakeups.all().filter { it.timeMillis <= System.currentTimeMillis() }.forEach {
                    wakeups.cancel(it.conversationId, it.token)
                }
                started = true
            }
        }

        fun cancel(context: Context, assistantId: String? = null) {
            ProactiveTaskStore(context).cancel(assistantId).forEach { jobs[it]?.cancel() }
        }
    }
}
