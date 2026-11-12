/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localtasks

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.plugin.localdata.LocalDataApi
import me.rerere.rikkahub.plugin.localdata.LocalDataValidation

/** One assistant turn. The runner is the only place that may write through localdata. */
data class LocalTaskTurnRequest(
    val taskId: String,
    val mode: LocalTaskMode,
    val postId: String?,
    val round: Int,
    val maxRounds: Int,
    val assistant: LocalTaskParticipant,
    val settings: LocalTaskSettings,
    val token: LocalTaskCancellationToken,
    val replyIndex: Int = 0,
    val mediaIds: List<String> = emptyList(),
    /** Durable ledger key charged by this turn; publish-only, null for interactions. */
    val usageKey: String? = null,
)

sealed interface LocalTaskTurnOutcome {
    /**
     * True only for outcomes that are produced *after* a durable localdata write committed. The
     * scheduler keeps such a result even when a cancel lands immediately afterwards, because the
     * write won the shared-gate race (write-first means the existing result is retained).
     */
    val committed: Boolean get() = false

    data class Replied(
        val bodyChars: Int,
        val liked: Boolean,
        val truncated: Boolean = false,
    ) : LocalTaskTurnOutcome {
        override val committed: Boolean get() = true
    }

    data object Skipped : LocalTaskTurnOutcome

    /** The runner decided before any model call (policy/ACL switch); the reservation is refunded. */
    data class NotAttempted(val reason: String) : LocalTaskTurnOutcome

    data class PostDrafted(val draftId: String, val reason: String? = null) : LocalTaskTurnOutcome {
        override val committed: Boolean get() = true
    }

    data class PostPublished(val postId: String) : LocalTaskTurnOutcome {
        override val committed: Boolean get() = true
    }

    data class HeldForReview(val action: LocalTaskPendingActionDto) : LocalTaskTurnOutcome

    data object PostUnavailable : LocalTaskTurnOutcome

    data class Failed(val code: String, val message: String) : LocalTaskTurnOutcome

    data object Cancelled : LocalTaskTurnOutcome
}

fun interface LocalTaskTurnRunner {
    suspend fun runTurn(request: LocalTaskTurnRequest): LocalTaskTurnOutcome
}

/**
 * The monitor shared by a token's [LocalTaskCancellationToken.cancel] and every localdata write it
 * guards through [LocalTaskCancellationToken.commitIfActive]. This is the *linearization point*
 * between "the task was cancelled" and "the write happened":
 *
 *  - if cancel (or [close]) acquires the lock first, the token is marked cancelled and the guarded
 *    block never runs;
 *  - if the write acquired the lock first, it runs to completion and cancel waits for it, so the
 *    committed result is retained instead of being half-applied.
 *
 * The guarded block must be a short, synchronous localdata commit. It must never contain a model or
 * network call: holding this lock while waiting on a socket would block cancellation (and a later
 * [close]) for the whole request. This is cooperative cancellation, not socket abort.
 */
class LocalTaskCancellationGate {
    private val lock = ReentrantLock()
    private val live = HashSet<LocalTaskCancellationToken>()
    private var closed = false

    internal fun register(token: LocalTaskCancellationToken) = lock.withLock {
        if (!closed) live += token
    }

    internal fun unregister(token: LocalTaskCancellationToken) = lock.withLock {
        live -= token
    }

    internal fun markCancelled(token: LocalTaskCancellationToken) = lock.withLock {
        token.cancelled = true
    }

    internal fun isCancelled(token: LocalTaskCancellationToken): Boolean = lock.withLock {
        closed || token.cancelled
    }

    val isClosed: Boolean get() = lock.withLock { closed }

    /** Runs [block] only if neither the token nor the gate is cancelled; synchronous by contract. */
    fun <T> commitIfActive(token: LocalTaskCancellationToken, block: () -> T): T? = lock.withLock {
        if (closed || token.cancelled) null else block()
    }

    /**
     * Refuses every current and future write and returns only after the in-flight guarded block has
     * finished. A host must call this *before* closing the localdata session, otherwise a new host
     * could open the same database while an old synchronous write is still running.
     */
    fun close() {
        val liveTokens = lock.withLock {
            closed = true
            live.toList()
        }
        // Completing the signals outside the monitor wakes `awaitCancellation()` waiters without
        // running arbitrary continuations while holding the lock.
        liveTokens.forEach { it.wake() }
    }
}

/**
 * Cooperative cancellation shared by the scheduler and the executor. [cancel] and
 * [commitIfActive] use the same gate lock, so a durable write can never be committed after a
 * successful cancel, and a write that started first is never lost to a concurrent cancel.
 */
class LocalTaskCancellationToken internal constructor(
    private val gate: LocalTaskCancellationGate = LocalTaskCancellationGate(),
) {
    internal var cancelled: Boolean = false

    private val committedOutcome = java.util.concurrent.atomic.AtomicReference<LocalTaskTurnOutcome?>(null)
    private val signal = CompletableDeferred<Unit>()

    init {
        gate.register(this)
    }

    val isCancelled: Boolean get() = gate.isCancelled(this)

    internal fun cancel() {
        gate.markCancelled(this)
        wake()
    }

    internal fun wake() {
        signal.complete(Unit)
    }

    internal suspend fun awaitCancellation() {
        if (isCancelled) return
        signal.await()
    }

    /**
     * Commits one synchronous localdata write atomically with cancellation. `null` means the token
     * was already cancelled (or the host was closed), so nothing ran. Callers must treat `null` as
     * [LocalTaskTurnOutcome.Cancelled] and must not do any other side effect before/after inside
     * [block] that could not be repeated.
     */
    fun <T> commitIfActive(block: () -> T): T? = gate.commitIfActive(this, block)

    /**
     * Remembers the outcome of a commit that already ran. A cancel can win the job-cancellation race
     * just after the gate released but before the turn coroutine resumes; [runGuarded] then reads
     * this so the committed result is still counted instead of being reported as a late write.
     */
    internal fun recordCommitted(outcome: LocalTaskTurnOutcome) {
        if (!outcome.committed) return
        committedOutcome.compareAndSet(null, outcome)
    }

    internal fun takeCommittedOutcome(): LocalTaskTurnOutcome? = committedOutcome.getAndSet(null)

    internal fun dispose() {
        gate.unregister(this)
    }

    fun ensureActive() {
        if (isCancelled) throw CancellationException("Local task was cancelled")
    }
}

/** A write token leased to one host-approved review apply, so cancel/stop can refuse it. */
data class LocalTaskReviewLease(
    val taskId: String,
    val record: LocalTaskRecord,
    val token: LocalTaskCancellationToken,
)

/**
 * Serial, budgeted, reviewable task queue. Every runtime value is injected so the whole scheduling
 * policy (rounds, caps, budgets, pacing, cancel, restart) can be tested without Android.
 *
 * One scheduler instance must drive one plugin: tasks run strictly one at a time via [pump].
 */
class LocalTaskScheduler(
    private val settingsProvider: () -> LocalTaskSettings,
    private val participantsProvider: () -> List<LocalTaskParticipant>,
    private val ledger: LocalTaskLedger,
    private val runner: LocalTaskTurnRunner,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val taskIdFactory: () -> String = { LocalTaskIds.newTaskId() },
    /**
     * Shared by every turn token and every review lease. The host closes it before closing the
     * localdata session, so no old write can overlap a new host.
     */
    private val cancellation: LocalTaskCancellationGate = LocalTaskCancellationGate(),
) {
    private val queue = ArrayDeque<String>()
    private val queueLock = Any()
    private val tokens = ConcurrentHashMap<String, LocalTaskCancellationToken>()
    private val tokenLock = ReentrantLock()
    private val pumpLock = Mutex()

    fun submit(request: LocalTaskStartRequest): LocalTaskSubmitResult {
        val settings = settingsProvider().normalized()
        if (settings.globalStop) {
            return LocalTaskSubmitResult.Rejected(LocalTaskErrorCode.STOPPED, "Assistant interaction is stopped")
        }
        if (!settings.enabled) {
            return LocalTaskSubmitResult.Rejected(
                LocalTaskErrorCode.DISABLED,
                "Assistant interaction is disabled in host settings",
            )
        }
        val postId = request.postId
        if (request.mode == LocalTaskMode.INTERACT) {
            if (postId == null || !LocalDataValidation.isSafeRecordId(postId)) {
                return LocalTaskSubmitResult.Rejected(LocalTaskErrorCode.INVALID_REQUEST, "Invalid post id")
            }
            if (settings.maxRounds <= 0) {
                return LocalTaskSubmitResult.Rejected(
                    LocalTaskErrorCode.INTERACTION_DISABLED,
                    "Automatic interaction is configured for 0 rounds",
                )
            }
        }
        val assistantId = request.assistantId
        if (assistantId != null && !LocalDataValidation.isSafeAssistantId(assistantId)) {
            return LocalTaskSubmitResult.Rejected(LocalTaskErrorCode.INVALID_REQUEST, "Invalid assistant id")
        }
        val clientKey = request.clientKey?.trim()?.takeIf { it.isNotEmpty() }
        if (clientKey != null && !CLIENT_KEY_PATTERN.matches(clientKey)) {
            return LocalTaskSubmitResult.Rejected(LocalTaskErrorCode.INVALID_REQUEST, "Invalid client key")
        }
        val available = participantsProvider().filter { it.policy != LocalAssistantPolicy.DISABLED }
        val selected = if (assistantId != null) available.filter { it.id == assistantId } else available
        if (selected.isEmpty()) {
            return LocalTaskSubmitResult.Rejected(
                if (assistantId != null) {
                    LocalTaskErrorCode.ASSISTANT_NOT_PARTICIPANT
                } else {
                    LocalTaskErrorCode.NO_PARTICIPANTS
                },
                "No assistant is allowed to run this task",
            )
        }
        if (clientKey != null) {
            ledger.findByClientKey(clientKey)?.let { return LocalTaskSubmitResult.Accepted(it.taskId, true) }
        }
        if (postId != null) {
            ledger.activeTask(postId, assistantId)?.let { return LocalTaskSubmitResult.Accepted(it.taskId, true) }
        }
        if (request.mode == LocalTaskMode.PUBLISH) {
            ledger.activePublishTask(assistantId)?.let {
                return LocalTaskSubmitResult.Accepted(it.taskId, true)
            }
        }
        if (!ledger.hasDailyBudget(settings)) {
            return LocalTaskSubmitResult.Rejected(
                LocalTaskErrorCode.BUDGET_EXHAUSTED,
                "Daily model call budget is exhausted",
            )
        }
        val record = LocalTaskRecord(
            taskId = taskIdFactory(),
            mode = request.mode,
            postId = postId,
            assistantId = assistantId,
            clientKey = clientKey,
            mediaIds = request.mediaIds
                .filter { LocalDataValidation.isSafeMediaId(it) }
                .distinct()
                .take(LocalDataApi.MAX_MEDIA_PER_POST),
            state = LocalTaskState.QUEUED,
            createdAt = clock(),
        )
        ledger.createTask(record)
        synchronized(queueLock) { queue.addLast(record.taskId) }
        return LocalTaskSubmitResult.Accepted(record.taskId, false)
    }

    /** Drains the queue serially. Safe to call repeatedly; concurrent calls are serialized. */
    suspend fun pump(): Int = pumpLock.withLock {
        var ran = 0
        while (true) {
            val taskId = synchronized(queueLock) { queue.removeFirstOrNull() } ?: break
            runTask(taskId)
            ran++
        }
        ran
    }

    /**
     * Cancels [taskId] atomically with respect to writes. The token is marked cancelled under
     * [tokenLock] *before* the ledger is updated, and [LocalTaskCancellationToken.cancel] waits for
     * an in-flight guarded write to finish. Therefore, once this returns, no new write for this
     * task can commit; a write that already committed is retained. Held review actions are dropped
     * with the task so the native UI cannot offer a cancelled action.
     */
    fun cancel(taskId: String): Boolean = tokenLock.withLock {
        val record = ledger.task(taskId) ?: return false
        if (record.state.isTerminal) return false
        tokens[taskId]?.cancel()
        ledger.updateTask(taskId) {
            it.copy(
                state = LocalTaskState.CANCELLED,
                endReason = "cancelled",
                finishedAt = clock(),
                pendingReview = emptyList(),
            )
        }
        true
    }

    /**
     * Leases a write token for a host-approved review apply. This is the same cancellation race as
     * a normal turn: either the task is still `waiting_review` and [cancel]/[stopAll] will see the
     * leased token, or the task is already cancelled/terminal and `null` is returned so nothing is
     * written. A concurrent review resolve is serialized by the service's `reviewLock`.
     */
    fun beginReview(taskId: String): LocalTaskReviewLease? = tokenLock.withLock {
        if (cancellation.isClosed) return null
        val record = ledger.task(taskId) ?: return null
        if (record.state != LocalTaskState.WAITING_REVIEW) return null
        val token = LocalTaskCancellationToken(cancellation)
        tokens.put(taskId, token)?.dispose()
        LocalTaskReviewLease(taskId, record, token)
    }

    /** Releases the review lease token; safe to call after the write path has returned. */
    fun endReview(lease: LocalTaskReviewLease) {
        tokenLock.withLock {
            if (tokens[lease.taskId] === lease.token) tokens.remove(lease.taskId)
        }
        lease.token.dispose()
    }

    fun stopAll(): Int {
        val active = ledger.activeTasks()
        active.forEach { cancel(it.taskId) }
        synchronized(queueLock) { queue.clear() }
        return active.size
    }

    fun status(taskId: String): LocalTaskStatusDto? = ledger.task(taskId)?.toStatus()

    fun statuses(): List<LocalTaskStatusDto> = ledger.snapshot().tasks.values
        .sortedByDescending { it.createdAt }
        .map { it.toStatus() }

    fun markInterrupted(): Int = ledger.markInterruptedOnStartup()

    private suspend fun runTask(taskId: String) {
        val initial = ledger.task(taskId) ?: return
        if (initial.state != LocalTaskState.QUEUED) return
        val settings = settingsProvider().normalized()
        if (settings.globalStop || !settings.enabled) {
            finish(taskId, LocalTaskState.CANCELLED, "stopped")
            return
        }
        val participants = resolveParticipants(initial.assistantId)
        if (participants.isEmpty()) {
            finish(taskId, LocalTaskState.COMPLETED, "no_participants")
            return
        }
        val token = LocalTaskCancellationToken(cancellation)
        tokenLock.withLock { tokens.put(taskId, token)?.dispose() }
        try {
            val running = ledger.updateTask(taskId) { record ->
                if (record.state == LocalTaskState.QUEUED) record.copy(state = LocalTaskState.RUNNING) else record
            }
            if (running?.state != LocalTaskState.RUNNING) return
            when (initial.mode) {
                LocalTaskMode.INTERACT -> runInteraction(taskId, initial, settings, participants, token)
                LocalTaskMode.PUBLISH -> runPublish(taskId, initial, settings, participants.firstOrNull(), token)
            }
        } catch (error: CancellationException) {
            if (isCancelled(taskId, token)) {
                finish(taskId, LocalTaskState.CANCELLED, "cancelled")
            } else {
                throw error
            }
        } catch (error: Exception) {
            finish(
                taskId,
                LocalTaskState.FAILED,
                "internal",
                LocalTaskErrorCode.INTERNAL.wire,
                error.message?.take(200) ?: "Task failed",
            )
        } finally {
            tokenLock.withLock {
                if (tokens[taskId] === token) tokens.remove(taskId)
            }
            token.dispose()
        }
    }

    private suspend fun runInteraction(
        taskId: String,
        record: LocalTaskRecord,
        settings: LocalTaskSettings,
        participants: List<LocalTaskParticipant>,
        token: LocalTaskCancellationToken,
    ) {
        val postId = record.postId
        if (postId == null) {
            finish(taskId, LocalTaskState.FAILED, "invalid_request", LocalTaskErrorCode.INVALID_REQUEST.wire, "postId missing")
            return
        }
        if (settings.maxRounds <= 0) {
            finish(taskId, LocalTaskState.COMPLETED, "interaction_disabled")
            return
        }
        var round = 0
        while (round < settings.maxRounds) {
            if (isCancelled(taskId, token)) {
                finish(taskId, LocalTaskState.CANCELLED, "cancelled")
                return
            }
            round++
            ledger.updateTask(taskId) { it.copy(round = round) }
            var spoken = 0
            var budgetBlocked = false
            var postGone = false
            for (participant in participants) {
                if (isCancelled(taskId, token)) {
                    finish(taskId, LocalTaskState.CANCELLED, "cancelled")
                    return
                }
                val live = settingsProvider().normalized()
                if (live.globalStop || !live.enabled) {
                    finish(taskId, LocalTaskState.CANCELLED, "stopped")
                    return
                }
                if (!ledger.canReply(postId, participant.id, live)) continue
                if (!ledger.hasDailyBudget(live)) {
                    budgetBlocked = true
                    break
                }
                if (pace(postId, live, token)) {
                    finish(taskId, LocalTaskState.CANCELLED, "cancelled")
                    return
                }
                if (!ledger.tryChargeModelCall(postId, live)) {
                    budgetBlocked = true
                    break
                }
                val replyIndex = ledger.replyCount(postId, participant.id)
                ledger.updateTask(taskId) { it.copy(modelCalls = it.modelCalls + 1) }
                val outcome = runGuarded(
                    LocalTaskTurnRequest(
                        taskId = taskId,
                        mode = LocalTaskMode.INTERACT,
                        postId = postId,
                        round = round,
                        maxRounds = live.maxRounds,
                        assistant = participant,
                        settings = live,
                        token = token,
                        replyIndex = replyIndex,
                    ),
                )
                when (outcome) {
                    is LocalTaskTurnOutcome.Replied -> {
                        ledger.recordReply(postId, participant.id)
                        if (outcome.liked) ledger.recordLike(postId, participant.id)
                        ledger.updateTask(taskId) {
                            it.copy(
                                turns = it.turns + 1,
                                comments = it.comments + 1,
                                likes = it.likes + if (outcome.liked) 1 else 0,
                                commentsTruncated = it.commentsTruncated || outcome.truncated,
                            )
                        }
                        spoken++
                    }

                    LocalTaskTurnOutcome.Skipped -> ledger.updateTask(taskId) {
                        it.copy(turns = it.turns + 1)
                    }

                    is LocalTaskTurnOutcome.NotAttempted -> {
                        ledger.refundModelCall(postId)
                        ledger.updateTask(taskId) {
                            it.copy(modelCalls = (it.modelCalls - 1).coerceAtLeast(0))
                        }
                    }

                    is LocalTaskTurnOutcome.HeldForReview -> {
                        ledger.updateTask(taskId) { current ->
                            if (current.state == LocalTaskState.CANCELLED) {
                                current
                            } else {
                                current.copy(
                                    state = LocalTaskState.WAITING_REVIEW,
                                    pendingReview = current.pendingReview + outcome.action,
                                    endReason = "review",
                                    finishedAt = clock(),
                                )
                            }
                        }
                        return
                    }

                    LocalTaskTurnOutcome.PostUnavailable -> {
                        postGone = true
                        break
                    }

                    is LocalTaskTurnOutcome.Failed -> {
                        finish(
                            taskId,
                            LocalTaskState.FAILED,
                            outcome.code,
                            outcome.code,
                            outcome.message,
                        )
                        return
                    }

                    LocalTaskTurnOutcome.Cancelled -> {
                        finish(taskId, LocalTaskState.CANCELLED, "cancelled")
                        return
                    }

                    is LocalTaskTurnOutcome.PostDrafted, is LocalTaskTurnOutcome.PostPublished -> {
                        // Not a valid interaction outcome; ignore rather than invent a comment.
                    }
                }
            }
            if (postGone) {
                finish(taskId, LocalTaskState.COMPLETED, "post_unavailable")
                return
            }
            if (spoken == 0) {
                finish(taskId, LocalTaskState.COMPLETED, if (budgetBlocked) "budget_exhausted" else "all_declined")
                return
            }
        }
        finish(taskId, LocalTaskState.COMPLETED, "rounds_done")
    }

    private suspend fun runPublish(
        taskId: String,
        record: LocalTaskRecord,
        settings: LocalTaskSettings,
        participant: LocalTaskParticipant?,
        token: LocalTaskCancellationToken,
    ) {
        if (participant == null) {
            finish(taskId, LocalTaskState.COMPLETED, "no_participants")
            return
        }
        // One day-scoped usage key for all publish turns: budgeting a publish task by its own
        // taskId (or by assistant) would let every new task/different assistant reset the
        // per-post cap. A fixed key would never roll over, so the key includes the day bucket.
        val usageKey = LocalTasksApi.publishUsageKey(clock())
        if (!ledger.hasDailyBudget(settings)) {
            finish(taskId, LocalTaskState.COMPLETED, "budget_exhausted")
            return
        }
        if (pace(usageKey, settings, token)) {
            finish(taskId, LocalTaskState.CANCELLED, "cancelled")
            return
        }
        if (!ledger.tryChargeModelCall(usageKey, settings)) {
            finish(taskId, LocalTaskState.COMPLETED, "budget_exhausted")
            return
        }
        ledger.updateTask(taskId) { it.copy(round = 1, modelCalls = it.modelCalls + 1) }
        val outcome = runGuarded(
            LocalTaskTurnRequest(
                taskId = taskId,
                mode = LocalTaskMode.PUBLISH,
                postId = null,
                round = 1,
                maxRounds = 1,
                assistant = participant,
                settings = settings,
                token = token,
                replyIndex = 0,
                mediaIds = record.mediaIds,
                usageKey = usageKey,
            ),
        )
        when (outcome) {
            is LocalTaskTurnOutcome.PostDrafted -> ledger.updateTask(taskId) { current ->
                if (current.state == LocalTaskState.CANCELLED) {
                    // The draft write won the gate race; keep the record cancelled, not completed.
                    current.copy(turns = 1)
                } else {
                    current.copy(
                        turns = 1,
                        state = LocalTaskState.COMPLETED,
                        endReason = outcome.reason ?: "drafted",
                        finishedAt = clock(),
                    )
                }
            }

            is LocalTaskTurnOutcome.PostPublished -> ledger.updateTask(taskId) { current ->
                if (current.state == LocalTaskState.CANCELLED) {
                    current.copy(turns = 1)
                } else {
                    current.copy(
                        turns = 1,
                        state = LocalTaskState.COMPLETED,
                        endReason = "published",
                        finishedAt = clock(),
                    )
                }
            }

            is LocalTaskTurnOutcome.HeldForReview -> ledger.updateTask(taskId) { current ->
                if (current.state == LocalTaskState.CANCELLED) {
                    current
                } else {
                    current.copy(
                        turns = 1,
                        state = LocalTaskState.WAITING_REVIEW,
                        pendingReview = current.pendingReview + outcome.action,
                        endReason = "review",
                        finishedAt = clock(),
                    )
                }
            }

            LocalTaskTurnOutcome.Skipped -> finish(taskId, LocalTaskState.COMPLETED, "declined")

            is LocalTaskTurnOutcome.NotAttempted -> {
                ledger.refundModelCall(usageKey)
                ledger.updateTask(taskId) {
                    it.copy(modelCalls = (it.modelCalls - 1).coerceAtLeast(0))
                }
                finish(taskId, LocalTaskState.COMPLETED, outcome.reason)
            }

            LocalTaskTurnOutcome.PostUnavailable -> finish(taskId, LocalTaskState.COMPLETED, "post_unavailable")
            is LocalTaskTurnOutcome.Failed -> finish(
                taskId,
                LocalTaskState.FAILED,
                outcome.code,
                outcome.code,
                outcome.message,
            )

            LocalTaskTurnOutcome.Cancelled -> finish(taskId, LocalTaskState.CANCELLED, "cancelled")
            is LocalTaskTurnOutcome.Replied -> finish(taskId, LocalTaskState.COMPLETED, "unexpected_action")
        }
    }

    private suspend fun pace(usageKey: String, settings: LocalTaskSettings, token: LocalTaskCancellationToken): Boolean {
        if (settings.intervalMs <= 0) return false
        val wait = settings.intervalMs - (clock() - ledger.lastTurnAt(usageKey))
        if (wait <= 0) return false
        sleeper(wait)
        return token.isCancelled
    }

    private suspend fun runGuarded(request: LocalTaskTurnRequest): LocalTaskTurnOutcome {
        if (request.token.isCancelled) return LocalTaskTurnOutcome.Cancelled
        return coroutineScope {
            val turn = async(start = CoroutineStart.UNDISPATCHED) { runner.runTurn(request) }
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                request.token.awaitCancellation()
                turn.cancel()
            }
            try {
                val outcome = turn.await()
                // A committed localdata write (Replied/PostDrafted/PostPublished) is retained even
                // when a cancel lands right after it: cancel and commitIfActive share the gate
                // lock, so the write linearized first. Non-write outcomes are still discarded.
                if (request.token.isCancelled && !outcome.committed) {
                    LocalTaskTurnOutcome.Cancelled
                } else {
                    outcome
                }
            } catch (error: CancellationException) {
                if (request.token.isCancelled) {
                    // The write may have committed and then lost the race with job cancellation;
                    // keep the recorded result instead of misreporting it as a late write.
                    request.token.takeCommittedOutcome() ?: LocalTaskTurnOutcome.Cancelled
                } else {
                    throw error
                }
            } finally {
                watcher.cancel()
            }
        }
    }

    private fun isCancelled(taskId: String, token: LocalTaskCancellationToken): Boolean =
        token.isCancelled || ledger.task(taskId)?.state == LocalTaskState.CANCELLED

    private fun resolveParticipants(assistantId: String?): List<LocalTaskParticipant> {
        val available = participantsProvider().filter { it.policy != LocalAssistantPolicy.DISABLED }
        return if (assistantId == null) available else available.filter { it.id == assistantId }
    }

    private fun finish(
        taskId: String,
        state: LocalTaskState,
        reason: String,
        errorCode: String? = null,
        errorMessage: String? = null,
    ) {
        ledger.updateTask(taskId) { record ->
            if (record.state == LocalTaskState.CANCELLED) {
                record
            } else {
                record.copy(
                    state = state,
                    endReason = reason,
                    errorCode = errorCode,
                    errorMessage = errorMessage,
                    finishedAt = clock(),
                )
            }
        }
    }

    private companion object {
        val CLIENT_KEY_PATTERN = Regex("^[A-Za-z0-9._:-]{1,64}$")
    }
}
