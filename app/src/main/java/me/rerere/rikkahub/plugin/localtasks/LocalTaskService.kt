/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localtasks

import android.content.Context
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.data.ai.UsageRecorder
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.plugin.localdata.LocalDataGrant
import me.rerere.rikkahub.plugin.localdata.LocalDataSession
import me.rerere.rikkahub.plugin.localdata.LocalDataValidation

/**
 * Host-facing assistant task service. This is the only object the UI and the plugin bridge should
 * talk to: it owns native settings, the durable ledger, the serial scheduler and the real executor.
 *
 * Trust rules:
 *  - `assistantId` is a *host selected* id; a page may only narrow to an assistant that native
 *    settings already list as a participant.
 *  - Task settings, publishing policy, image rights and budgets are read from [LocalTaskSettingsStore]
 *    (SharedPreferences), never from a plugin request.
 *  - Everything the executor writes goes through localdata with an assistant actor built here.
 */
class LocalTaskService(
    context: Context,
    val pluginId: String,
    private val session: LocalDataSession,
    private val settingsStore: SettingsStore,
    providerManager: ProviderManager,
    usageRecorder: UsageRecorder,
    parentScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val imageSource: LocalTaskImageSource = LocalTaskImageSource.unsupported(),
    private val ownsSession: Boolean = false,
) : Closeable {
    /** Capability names of the session this host was built with; used to recreate on re-consent. */
    val grantNames: Set<String> = session.grant.declaredNames()

    private val storage = SharedPreferencesLocalTaskStore(context, LocalTaskIds.storeName(pluginId))
    private val settingsStoreLocal = LocalTaskSettingsStore(storage)
    private val ledger = LocalTaskLedger(storage, clock)
    /**
     * One gate per host. It is shared by every turn token and every review lease, and [close]
     * closes it before the localdata session so an old in-flight synchronous write can never
     * overlap a new host on the same database.
     */
    private val cancellation = LocalTaskCancellationGate()
    private val imageBudget = LedgerLocalTaskImageBudget(ledger)
    private val executor = LocalTaskExecutor(
        settingsProvider = { settingsStore.settingsFlow.value },
        session = session,
        providerManager = providerManager,
        usageRecorder = usageRecorder,
        clock = clock,
        imageBudget = imageBudget,
    )
    private val scheduler = LocalTaskScheduler(
        settingsProvider = { settingsStoreLocal.get() },
        participantsProvider = { participants() },
        ledger = ledger,
        runner = executor,
        clock = clock,
        cancellation = cancellation,
    )
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job + Dispatchers.IO)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val reviewLock = Mutex()

    init {
        // Never replay work from a previous process: queued/running rows become interrupted.
        ledger.markInterruptedOnStartup()
        scope.launch {
            for (ignored in signal) {
                try {
                    scheduler.pump()
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // A failed pump must not kill the worker; the individual task is marked failed.
                }
            }
        }
    }

    fun startInteraction(
        postId: String,
        assistantId: String? = null,
        clientKey: String? = null,
    ): LocalTaskSubmitResult {
        val result = scheduler.submit(
            LocalTaskStartRequest(
                mode = LocalTaskMode.INTERACT,
                postId = postId,
                assistantId = assistantId,
                clientKey = clientKey,
            ),
        )
        if (result is LocalTaskSubmitResult.Accepted) signal.trySend(Unit)
        return result
    }

    /** Native-only entry point for assistant posts; a plugin page cannot call this. */
    fun startPublish(
        assistantId: String,
        mediaIds: List<String> = emptyList(),
    ): LocalTaskSubmitResult {
        val settings = settingsStoreLocal.get()
        if (!LocalDataValidation.isSafeAssistantId(assistantId)) {
            return LocalTaskSubmitResult.Rejected(LocalTaskErrorCode.INVALID_REQUEST, "Invalid assistant id")
        }
        if (settings.effectivePolicy(assistantId) == LocalAssistantPolicy.DISABLED) {
            return LocalTaskSubmitResult.Rejected(
                LocalTaskErrorCode.ASSISTANT_NOT_PARTICIPANT,
                "Assistant is not an enabled participant",
            )
        }
        if (mediaIds.isNotEmpty() && !settings.allowImages) {
            return LocalTaskSubmitResult.Rejected(
                LocalTaskErrorCode.IMAGE_UNSUPPORTED,
                "Image publishing is not enabled in host settings",
            )
        }
        if (mediaIds.any { !LocalDataValidation.isSafeMediaId(it) }) {
            return LocalTaskSubmitResult.Rejected(LocalTaskErrorCode.INVALID_REQUEST, "Invalid media id")
        }
        val result = scheduler.submit(
            LocalTaskStartRequest(
                mode = LocalTaskMode.PUBLISH,
                assistantId = assistantId,
                mediaIds = mediaIds,
            ),
        )
        if (result is LocalTaskSubmitResult.Accepted) signal.trySend(Unit)
        return result
    }

    fun status(taskId: String): LocalTaskStatusDto? = scheduler.status(taskId)

    fun statusesForPost(postId: String): List<LocalTaskStatusDto> = ledger.snapshot().tasks.values
        .filter { it.postId == postId }
        .sortedByDescending { it.createdAt }
        .map { it.toStatus() }

    fun activeStatuses(): List<LocalTaskStatusDto> = ledger.activeTasks().map { it.toStatus() }

    fun cancel(taskId: String): Boolean = scheduler.cancel(taskId)

    fun stopAll(): Int = scheduler.stopAll()

    fun settings(): LocalTaskSettings = settingsStoreLocal.get()

    /** Native settings UI only. A page can read [settings] but has no method to change them. */
    fun updateSettings(settings: LocalTaskSettings): LocalTaskSettings {
        val previous = settingsStoreLocal.get()
        val saved = settingsStoreLocal.set(settings)
        // A write that is already running snapshotted the old grant. Every security-relevant change
        // therefore cancels running/queued turns and drops their held reviews instead of letting a
        // stale participant/policy/comment switch finish its write.
        val grantNarrowed =
            saved.participants.toSet() != previous.participants.toSet() ||
                saved.assistantPolicies != previous.assistantPolicies ||
                saved.assistantReplyLimits != previous.assistantReplyLimits ||
                saved.allowImages != previous.allowImages ||
                saved.allowGeneratedImages != previous.allowGeneratedImages ||
                saved.autoLike != previous.autoLike ||
                saved.requireReview != previous.requireReview ||
                saved.commentUserPosts != previous.commentUserPosts ||
                saved.commentAssistantPosts != previous.commentAssistantPosts
        if (!saved.enabled || saved.globalStop || saved.maxRounds <= 0 || grantNarrowed) {
            scheduler.stopAll()
        }
        return saved
    }

    fun pendingReviews(): List<LocalTaskPendingActionDto> = ledger.pendingReviews()

    suspend fun resolveReview(
        reviewId: String,
        approve: Boolean,
        editedBody: String? = null,
    ): LocalTaskReviewResolveDto = reviewLock.withLock {
        val found = ledger.findReview(reviewId)
            ?: return@withLock LocalTaskReviewResolveDto(
                reviewId = reviewId,
                applied = false,
                approved = approve,
                state = "not_found",
                detail = "Review item does not exist",
            )
        val (record, action) = found
        if (!approve) {
            ledger.removeReview(reviewId)
            finishReviewIfEmpty(record.taskId, "review_rejected")
            return@withLock LocalTaskReviewResolveDto(
                reviewId = reviewId,
                applied = false,
                approved = false,
                state = ledger.task(record.taskId)?.state?.wire ?: LocalTaskState.COMPLETED.wire,
                detail = "Review rejected",
            )
        }
        // An explicit blank edit must never fall back to the original body: the native dialog would
        // otherwise show an empty review while the old body was still published.
        if (editedBody != null && editedBody.isBlank()) {
            return@withLock LocalTaskReviewResolveDto(
                reviewId = reviewId,
                applied = false,
                approved = false,
                state = ledger.task(record.taskId)?.state?.wire ?: record.state.wire,
                detail = "Edited body is empty; the review is still pending",
            )
        }
        val edited = editedBody
            ?.trim()
            ?.take(LocalTasksApi.MAX_MAX_OUTPUT_CHARS)
            ?.takeIf { it.isNotEmpty() }
        val toApply = if (edited != null) action.copy(body = edited) else action
        // Lease the write token under the scheduler's cancel lock. If stop/cancel/release happened
        // after the review was read, beginReview returns null and no write is started; if cancel
        // happens during the write, the token's gate decides whether the write linearized first.
        val lease = scheduler.beginReview(record.taskId)
            ?: return@withLock LocalTaskReviewResolveDto(
                reviewId = reviewId,
                applied = false,
                approved = true,
                state = ledger.task(record.taskId)?.state?.wire ?: record.state.wire,
                detail = "Task is no longer waiting for review; nothing was written",
            )
        try {
            val currentSettings = settingsStoreLocal.get()
            // A review can sit for a long time; the per-post reply cap may have been consumed by
            // another task/assistant in the meantime. Re-check it before applying the held reply.
            if (action.kind == LocalTaskActionKinds.REPLY && action.postId != null &&
                !ledger.canReply(action.postId, action.assistantId, currentSettings)
            ) {
                return@withLock LocalTaskReviewResolveDto(
                    reviewId = reviewId,
                    applied = false,
                    approved = true,
                    state = ledger.task(lease.taskId)?.state?.wire ?: record.state.wire,
                    detail = "Reply limit reached; nothing was written",
                )
            }
            val outcome = executor.applyPending(toApply, currentSettings, lease.token)
            val postId = action.postId
            val applied = when (outcome) {
                is LocalTaskTurnOutcome.Replied -> {
                    if (postId != null && action.kind == LocalTaskActionKinds.REPLY) {
                        ledger.recordReply(postId, action.assistantId)
                    }
                    if (postId != null && outcome.liked) {
                        ledger.recordLike(postId, action.assistantId)
                    }
                    true
                }

                is LocalTaskTurnOutcome.PostDrafted, is LocalTaskTurnOutcome.PostPublished -> true
                else -> false
            }
            if (applied) {
                // A reviewed action was never counted when it was held; mirror the normal turn
                // counters here so status() stays truthful. A cancel that lost the gate race leaves
                // the record cancelled, but the committed counters are still recorded.
                ledger.updateTask(lease.taskId) { task ->
                    when (outcome) {
                        is LocalTaskTurnOutcome.Replied -> task.copy(
                            turns = task.turns + 1,
                            comments = task.comments + if (action.kind == LocalTaskActionKinds.REPLY) 1 else 0,
                            likes = task.likes + if (outcome.liked) 1 else 0,
                        )

                        is LocalTaskTurnOutcome.PostDrafted, is LocalTaskTurnOutcome.PostPublished ->
                            task.copy(turns = maxOf(task.turns, 1))

                        else -> task
                    }
                }
                ledger.removeReview(reviewId)
                finishReviewIfEmpty(lease.taskId, "review_approved")
                return@withLock LocalTaskReviewResolveDto(
                    reviewId = reviewId,
                    applied = true,
                    approved = true,
                    state = ledger.task(lease.taskId)?.state?.wire ?: LocalTaskState.COMPLETED.wire,
                    detail = (outcome as? LocalTaskTurnOutcome.PostDrafted)?.reason,
                )
            }
            val detail = when (outcome) {
                is LocalTaskTurnOutcome.Cancelled -> "Task was cancelled; nothing was written"
                is LocalTaskTurnOutcome.Failed -> outcome.message
                else -> "Action could not be applied"
            }
            LocalTaskReviewResolveDto(
                reviewId = reviewId,
                applied = false,
                approved = true,
                state = ledger.task(lease.taskId)?.state?.wire ?: record.state.wire,
                detail = detail,
            )
        } finally {
            scheduler.endReview(lease)
        }
    }

    /** Imports an already-produced `_orange_image` tool result; never starts a generation. */
    suspend fun importExistingImageResult(
        producerPluginId: String,
        toolResultJson: String,
    ): LocalTaskImageImport = imageSource.importExistingToolResult(producerPluginId, toolResultJson)

    override fun close() {
        // Order matters: close the gate first so every token reports cancelled and every future
        // commitIfActive is refused. close() waits for an in-flight synchronous localdata write,
        // so the session below is never closed under an old write and a new host can never overlap
        // one. This is cooperative cancellation, not a hard socket/thread abort.
        cancellation.close()
        job.cancel()
        ledger.markInterruptedOnStartup()
        if (ownsSession) session.close()
    }

    private fun finishReviewIfEmpty(taskId: String, reason: String) {
        ledger.updateTask(taskId) { record ->
            if (record.pendingReview.isEmpty() && record.state == LocalTaskState.WAITING_REVIEW) {
                record.copy(
                    state = LocalTaskState.COMPLETED,
                    endReason = reason,
                    finishedAt = clock(),
                )
            } else {
                record
            }
        }
    }

    private fun participants(): List<LocalTaskParticipant> {
        val settings = settingsStore.settingsFlow.value
        val byId = settings.assistants.associateBy { it.id.toString() }
        val taskSettings = settingsStoreLocal.get()
        return taskSettings.participants.mapNotNull { id ->
            val assistant = byId[id] ?: return@mapNotNull null
            LocalTaskParticipant(
                id = id,
                name = assistant.name.ifBlank { "助手" },
                avatar = avatarOf(assistant.avatar),
                policy = taskSettings.effectivePolicy(id),
            )
        }.filter { it.policy != LocalAssistantPolicy.DISABLED }
    }

    private fun avatarOf(avatar: Avatar): String? = when (avatar) {
        Avatar.Dummy -> null
        is Avatar.Emoji -> avatar.content
        is Avatar.Image -> avatar.url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }
}

/**
 * One process-wide host per plugin so a page close does not cancel running tasks and two pages
 * cannot race two schedulers for the same plugin.
 */
object LocalTaskHosts {
    private val hosts = LinkedHashMap<String, LocalTaskService>()

    @Synchronized
    fun obtain(
        context: Context,
        pluginId: String,
        grant: LocalDataGrant,
        settingsStore: SettingsStore,
        providerManager: ProviderManager,
        usageRecorder: UsageRecorder,
        parentScope: CoroutineScope,
        clock: () -> Long = System::currentTimeMillis,
        imageSource: LocalTaskImageSource = LocalTaskImageSource.unsupported(),
        imageSourceFactory: ((LocalDataSession) -> LocalTaskImageSource)? = null,
    ): LocalTaskService {
        val existing = hosts[pluginId]
        if (existing != null) {
            if (existing.grantNames == grant.declaredNames()) return existing
            // The user re-consented to a different capability set: rebuild the session.
            existing.close()
            hosts.remove(pluginId)
        }
        val appContext = context.applicationContext
        val session = LocalDataSession(appContext, pluginId, grant)
        return LocalTaskService(
            context = appContext,
            pluginId = pluginId,
            session = session,
            settingsStore = settingsStore,
            providerManager = providerManager,
            usageRecorder = usageRecorder,
            parentScope = parentScope,
            clock = clock,
            imageSource = imageSourceFactory?.invoke(session) ?: imageSource,
            ownsSession = true,
        ).also { hosts[pluginId] = it }
    }

    @Synchronized
    fun peek(pluginId: String): LocalTaskService? = hosts[pluginId]

    @Synchronized
    fun release(pluginId: String) {
        hosts.remove(pluginId)?.close()
    }
}
