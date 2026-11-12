package me.rerere.rikkahub.data.service

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.AssistantProactiveConfig
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.rikkahub.data.datastore.effectiveActiveHoursEnabled
import me.rerere.rikkahub.data.datastore.effectiveCooldownMinutes
import me.rerere.rikkahub.data.datastore.effectiveDailyLimit
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Serializable
internal enum class ProactiveTaskStatus { Pending, Running, CommitPending, Sent, Failed, Cancelled, Interrupted }

@Serializable
internal data class ProactiveTask(
    val taskId: String = UUID.randomUUID().toString(),
    val assistantId: String,
    val conversationId: String,
    val trigger: String,
    val anchorUserMessageId: String,
    val createdAtMillis: Long,
    val dueAtMillis: Long = createdAtMillis,
    // Admission must still consume this exact token via the original wakeup store's CAS.
    val appointmentToken: String? = null,
    val status: ProactiveTaskStatus = ProactiveTaskStatus.Pending,
    val attempt: Int = 0,
    val outcome: String = "",
    val admittedConfig: AssistantProactiveConfig? = null,
    val commitMessageIds: List<String> = emptyList(),
    val commitPayload: String? = null,
    val commitStartedAtMillis: Long = 0,
)

@Serializable
internal data class ProactiveTurnReservation(
    val token: String,
    val taskId: String,
    val day: String,
    val dispatched: Boolean = false,
)

@Serializable
internal data class ProactiveDayUsage(
    val modelTurns: Int = 0,
    val admittedTasks: Map<String, Int> = emptyMap(),
)

@Serializable
internal data class ProactiveSchedulerState(
    val schemaVersion: Int = 1,
    val tasks: List<ProactiveTask> = emptyList(),
    val zoneId: String = "",
    val latestDay: String = "",
    val days: Map<String, ProactiveDayUsage> = emptyMap(),
    val reservations: Map<String, ProactiveTurnReservation> = emptyMap(),
    val lastAdmittedAt: Map<String, Long> = emptyMap(),
    val afterAssistantId: String = "",
)

/** This is an upgrade proposal, not an activated migration. Persist with settings atomically.
 * The caller resolves one legacy target once, including an optional foreground snapshot for
 * an empty legacy assistantId. An explicit but deleted legacy assistant NEVER falls back.
 * Missing/ambiguous/unowned targets freeze an empty enrollment, not a runtime lookup. */
internal fun migrateProactiveParticipants(
    stored: List<AssistantProactiveConfig>?,
    legacyAssistantId: String,
    frozenTargets: List<AssistantProactiveConfig>,
    conversationOwners: Map<String, String>,
): List<AssistantProactiveConfig> {
    if (stored != null) return stored
    val candidates = frozenTargets.filter { legacyAssistantId.isBlank() || it.assistantId == legacyAssistantId }
    val target = candidates.singleOrNull() ?: return emptyList()
    return listOf(target).filter { it.assistantId.isNotBlank() && it.conversationId.isNotBlank() &&
        conversationOwners[it.conversationId] == it.assistantId }
}

/** Call with eligible due tasks only. Ties are stable and deterministic. */
internal fun fairNextTask(tasks: List<ProactiveTask>, afterAssistantId: String = ""): ProactiveTask? {
    val groups = tasks.filter { it.status == ProactiveTaskStatus.Pending }.groupBy { it.assistantId }.toSortedMap()
    if (groups.isEmpty()) return null
    val next = groups.keys.firstOrNull { it > afterAssistantId } ?: groups.keys.first()
    return groups.getValue(next).minWithOrNull(
        compareBy<ProactiveTask> { it.dueAtMillis }.thenBy { it.createdAtMillis }.thenBy { it.taskId },
    )
}

internal fun budgetDay(nowMillis: Long, zoneId: String): String? = runCatching {
    Instant.ofEpochMilli(nowMillis).atZone(ZoneId.of(zoneId)).toLocalDate().toString()
}.getOrNull()

/** Fresh host snapshots are required at admission, each turn and final commit. Missing IDs deny.
 * This DTO does NOT make host conversation/message writes atomic; see the integration handoff. */
internal data class ProactiveHostSnapshot(
    val setting: ProactiveMessageSetting,
    val assistantIds: Set<String>,
    val conversationOwners: Map<String, String>,
    val userAnchors: Map<String, String>,
    val busyConversations: Set<String> = emptySet(),
)

internal fun proactiveTaskAllowed(task: ProactiveTask, host: ProactiveHostSnapshot, now: Long): Boolean {
    val setting = host.setting
    val config = setting.assistantConfigs?.singleOrNull { it.assistantId == task.assistantId } ?: return false
    val zone = runCatching {
        ZoneId.of(setting.globalPolicy.budgetZoneId.ifBlank { ZoneId.systemDefault().id })
    }.getOrNull() ?: return false
    val customActiveHours = config.effectiveActiveHoursEnabled
    return setting.enabled && (task.trigger == "manual" || !setting.globalPolicy.paused) && config.enabled &&
        task.assistantId in host.assistantIds && config.conversationId == task.conversationId &&
        host.conversationOwners[task.conversationId] == task.assistantId &&
        host.userAnchors[task.conversationId] == task.anchorUserMessageId &&
        (task.admittedConfig == null || task.admittedConfig == config) &&
        (task.appointmentToken == null || setting.allowAiScheduledWakeup) &&
        (task.trigger == "manual" || (isProactiveActiveTime(setting, now, zone) &&
            isProactiveActiveTime(setting.copy(
                activeHoursEnabled = customActiveHours,
                activeStartHour = if (customActiveHours) config.activeStartHour ?: setting.activeStartHour else setting.activeStartHour,
                activeEndHour = if (customActiveHours) config.activeEndHour ?: setting.activeEndHour else setting.activeEndHour,
            ), now, zone)))
}

/** Adapters must atomically replace one snapshot or throw. Never reset corrupt data to empty. */
internal interface ProactiveSchedulerStorage {
    fun read(): String?
    fun write(value: String)
}

/** Single Android process only. No provider, Worker, application or legacy settings hooks. */
internal class ProactiveTaskStore(private val storage: ProactiveSchedulerStorage) {
    constructor(context: Context) : this(PreferencesProactiveSchedulerStorage(context))

    private val json = Json { encodeDefaults = true }
    private var failed = false

    fun snapshot(): ProactiveSchedulerState = synchronized(lock) { read() }

    fun enqueue(task: ProactiveTask): Boolean = synchronized(lock) {
        val state = read()
        if (state.tasks.count { it.status in setOf(ProactiveTaskStatus.Pending, ProactiveTaskStatus.Running, ProactiveTaskStatus.CommitPending) } >= MAX_TASKS ||
            state.tasks.any { it.taskId == task.taskId } ||
            task.status != ProactiveTaskStatus.Pending || task.attempt != 0 || task.admittedConfig != null ||
            task.taskId.isBlank() || task.assistantId.isBlank() || task.conversationId.isBlank() ||
            task.anchorUserMessageId.isBlank() || task.appointmentToken?.isBlank() == true ||
            task.trigger !in setOf("automatic", "manual", "appointment") ||
            (task.trigger == "appointment") != (task.appointmentToken != null)
        ) return@synchronized false
        val retained = state.tasks.filter { it.status in setOf(ProactiveTaskStatus.Pending, ProactiveTaskStatus.Running, ProactiveTaskStatus.CommitPending) }
        save(state.copy(tasks = state.tasks.takeLast(255).filterNot { it in retained } + retained + task))
        true
    }

    /** A manual tap must not sit behind an already-scheduled autonomous task for the same assistant. */
    fun promotePendingToManual(assistantId: String, now: Long) = synchronized(lock) {
        val state = read()
        val pending = state.tasks.filter {
            it.assistantId == assistantId && it.status == ProactiveTaskStatus.Pending
        }
        if (pending.isEmpty()) return@synchronized false
        save(state.copy(tasks = state.tasks.map { task ->
            if (task.assistantId == assistantId && task.status == ProactiveTaskStatus.Pending) {
                task.copy(trigger = "manual", dueAtMillis = now, appointmentToken = null)
            } else task
        }))
        true
    }

    /** Serial claim, cooldown, task/day limit and fairness cursor are one durable write.
     * Admission charges a task even if it later fails or PASSes. No automatic retries. */
    fun claimNext(
        host: ProactiveHostSnapshot,
        now: Long,
        manualBatch: Boolean = false,
    ): ProactiveTask? = synchronized(lock) {
        val state = read()
        // Running and durable commits reserve only their own conversation, never the whole queue.
        val occupiedConversations = state.tasks.filter {
            it.status in setOf(ProactiveTaskStatus.Running, ProactiveTaskStatus.CommitPending)
        }.mapTo(hashSetOf()) { it.conversationId }
        val day = validDay(state, host, now) ?: return@synchronized null
        val usage = state.days[day] ?: ProactiveDayUsage()
        // A manual tap is an explicit user request and must not be rejected by the autonomous
        // daily-turn safety budget. Automatic tasks still obey it.
        val hasManualPending = state.tasks.any {
            it.status == ProactiveTaskStatus.Pending && it.trigger == "manual"
        }
        if (!hasManualPending && usage.modelTurns >= host.setting.globalPolicy.dailyModelTurnLimit) {
            return@synchronized null
        }
        val eligible = state.tasks.filter { task ->
            if (manualBatch && task.trigger != "manual") return@filter false
            val config = host.setting.assistantConfigs?.singleOrNull { it.assistantId == task.assistantId }
            val last = state.lastAdmittedAt[task.assistantId]
            val manual = task.trigger == "manual"
            task.status == ProactiveTaskStatus.Pending && task.dueAtMillis <= now &&
                proactiveTaskAllowed(task, host, now) && task.conversationId !in host.busyConversations &&
                task.conversationId !in occupiedConversations &&
                config != null &&
                // A manual tap must act now: the per-assistant cooldown/daily cap exists for the
                // autonomous scheduler, not for an explicit user request.
                (manual || (
                    config.effectiveCooldownMinutes >= 0 && config.effectiveDailyLimit > 0 &&
                        (usage.admittedTasks[task.assistantId] ?: 0) < config.effectiveDailyLimit &&
                        (last == null || (now >= last &&
                            now - last >= config.effectiveCooldownMinutes.toLong() * 60_000))
                    ))
        }
        val task = fairNextTask(eligible, state.afterAssistantId) ?: return@synchronized null
        val claimed = task.copy(
            status = ProactiveTaskStatus.Running, attempt = 1,
            admittedConfig = host.setting.assistantConfigs!!.single { it.assistantId == task.assistantId },
        )
        save(state.copy(
            tasks = state.tasks.map { if (it.taskId == task.taskId) claimed else it },
            zoneId = host.setting.globalPolicy.budgetZoneId, latestDay = day,
            days = state.days + (day to usage.copy(admittedTasks = usage.admittedTasks +
                (task.assistantId to ((usage.admittedTasks[task.assistantId] ?: 0) + 1)))),
            afterAssistantId = task.assistantId,
            lastAdmittedAt = state.lastAdmittedAt + (task.assistantId to now),
        ))
        claimed
    }

    /** Atomically charge BEFORE each initial/continuation/finalizer call. Opaque token prevents
     * a late settlement of turn N from refunding turn N+1. One outstanding turn per task. */
    fun reserveTurn(taskId: String, host: ProactiveHostSnapshot, now: Long): String? = synchronized(lock) {
        val state = read()
        val task = state.tasks.singleOrNull { it.taskId == taskId && it.status == ProactiveTaskStatus.Running }
            ?: return@synchronized null
        if (!proactiveTaskAllowed(task, host, now) || state.reservations.values.any { it.taskId == taskId }) {
            return@synchronized null
        }
        val day = validDay(state, host, now) ?: return@synchronized null
        val usage = state.days[day] ?: ProactiveDayUsage()
        if (task.trigger != "manual" && usage.modelTurns >= host.setting.globalPolicy.dailyModelTurnLimit) {
            return@synchronized null
        }
        val token = UUID.randomUUID().toString()
        save(state.copy(
            zoneId = host.setting.globalPolicy.budgetZoneId, latestDay = day,
            days = state.days + (day to usage.copy(modelTurns = usage.modelTurns + 1)),
            reservations = state.reservations + (token to ProactiveTurnReservation(token, taskId, day)),
        ))
        token
    }

    /** Durable mark immediately BEFORE invoking the provider; after this, even timeout, HTTP
     * failure or cancellation consumes one turn. Unknown dispatch outcomes must not refund. */
    fun markDispatched(token: String, host: ProactiveHostSnapshot, now: Long): Boolean = synchronized(lock) {
        val state = read()
        val reservation = state.reservations[token] ?: return@synchronized false
        val task = state.tasks.singleOrNull { it.taskId == reservation.taskId } ?: return@synchronized false
        if (reservation.dispatched || task.status != ProactiveTaskStatus.Running ||
            !proactiveTaskAllowed(task, host, now) || validDay(state, host, now) != reservation.day
        ) return@synchronized false
        save(state.copy(reservations = state.reservations + (token to reservation.copy(dispatched = true))))
        true
    }

    /** Roll back ONLY a known pre-dispatch failure. Dispatched success/failure both stay charged. */
    fun settleTurn(token: String): Boolean = synchronized(lock) {
        val state = read()
        val reservation = state.reservations[token] ?: return@synchronized false
        val usage = state.days.getValue(reservation.day)
        save(state.copy(
            reservations = state.reservations - token,
            days = if (reservation.dispatched) state.days else state.days +
                (reservation.day to usage.copy(modelTurns = (usage.modelTurns - 1).coerceAtLeast(0))),
        ))
        true
    }

    /** Guard only, NOT a commit authorization surviving this method's return. The host must
     * couple this check to its own task/config/owner/anchor transaction before writing messages. */
    fun canCommit(taskId: String, host: ProactiveHostSnapshot, now: Long): Boolean = synchronized(lock) {
        val state = read()
        state.tasks.singleOrNull { it.taskId == taskId }?.let {
            it.status == ProactiveTaskStatus.Running && proactiveTaskAllowed(it, host, now) &&
                validDay(state, host, now) != null
        } == true
    }

    /** Payload and deterministic IDs become durable before the irreversible Room operation. */
    fun beginCommit(taskId: String, host: ProactiveHostSnapshot, now: Long, messageIds: List<String>, payload: String): Boolean =
        synchronized(lock) {
            val state = read()
            val task = state.tasks.singleOrNull { it.taskId == taskId && it.status == ProactiveTaskStatus.Running }
                ?: return@synchronized false
            if (!proactiveTaskAllowed(task, host, now) || validDay(state, host, now) == null ||
                state.reservations.values.any { it.taskId == taskId } || messageIds.isEmpty() ||
                messageIds.distinct().size != messageIds.size || payload.isBlank() || payload.length > MAX_COMMIT_PAYLOAD_CHARS
            ) return@synchronized false
            val prepared = task.copy(status = ProactiveTaskStatus.CommitPending,
                outcome = "commit_pending", commitMessageIds = messageIds.toList(), commitPayload = payload,
                commitStartedAtMillis = now)
            save(state.copy(tasks = state.tasks.map { if (it.taskId == taskId) prepared else it }))
            true
        }

    fun pendingCommits(): List<ProactiveTask> = synchronized(lock) {
        read().tasks.filter { it.status == ProactiveTaskStatus.CommitPending }
    }

    /** The synchronized callback is the cancellation/Room-commit linearization point. */
    fun commitPayload(taskId: String, commit: (ProactiveTask) -> Boolean): Boolean {
        val task = startCommit(taskId) ?: return false
        if (!commit(task)) return false
        return markCommitSent(taskId)
    }

    /** Durable intent is the cancellation linearization point; recovery always reuses this payload. */
    fun startCommit(taskId: String): ProactiveTask? = synchronized(lock) {
        val state = read()
        val task = state.tasks.singleOrNull { it.taskId == taskId && it.status == ProactiveTaskStatus.CommitPending }
            ?: return@synchronized null
        if (task.outcome == "cancel_requested_commit_recovery_required" ||
            task.commitPayload.isNullOrBlank() || task.commitMessageIds.isEmpty()
        ) return@synchronized null
        val committing = task.copy(outcome = "commit_in_progress")
        save(state.copy(tasks = state.tasks.map { if (it.taskId == taskId) committing else it }))
        committing
    }

    /** Called only after the matching Room transaction committed or exact IDs were verified. */
    fun markCommitSent(taskId: String): Boolean = synchronized(lock) {
        val state = read()
        val task = state.tasks.singleOrNull { it.taskId == taskId && it.status == ProactiveTaskStatus.CommitPending }
            ?: return@synchronized false
        save(state.copy(tasks = state.tasks.map {
            if (it.taskId == taskId) task.copy(status = ProactiveTaskStatus.Sent, outcome = "sent",
                commitPayload = null, commitMessageIds = emptyList()) else it
        }))
        true
    }

    fun abandonCommit(taskId: String, status: ProactiveTaskStatus, outcome: String): Boolean = synchronized(lock) {
        val state = read()
        if (status !in setOf(ProactiveTaskStatus.Interrupted, ProactiveTaskStatus.Cancelled, ProactiveTaskStatus.Failed)) {
            return@synchronized false
        }
        val task = state.tasks.singleOrNull { it.taskId == taskId && it.status == ProactiveTaskStatus.CommitPending }
            ?: return@synchronized false
        save(state.copy(tasks = state.tasks.map { if (it.taskId == taskId) task.copy(
            status = status, outcome = outcome, commitPayload = null, commitMessageIds = emptyList(),
        ) else it }))
        true
    }

    /** Terminal CAS only; no restart/retry transitions. Sent requires host atomic-commit handoff. */
    fun finish(taskId: String, next: ProactiveTaskStatus, outcome: String): Boolean = synchronized(lock) {
        val state = read()
        if (next !in setOf(ProactiveTaskStatus.Sent, ProactiveTaskStatus.Failed, ProactiveTaskStatus.Interrupted, ProactiveTaskStatus.Cancelled)) return@synchronized false
        val task = state.tasks.singleOrNull { it.taskId == taskId && it.status == ProactiveTaskStatus.Running }
            ?: return@synchronized false
        if (state.reservations.values.any { it.taskId == taskId }) return@synchronized false
        save(state.copy(tasks = state.tasks.map {
            if (it.taskId == taskId) task.copy(status = next, outcome = outcome) else it
        }))
        true
    }

    fun cancelTask(taskId: String): Boolean = synchronized(lock) {
        val state = read()
        val task = state.tasks.singleOrNull { it.taskId == taskId &&
            it.status in setOf(ProactiveTaskStatus.Pending, ProactiveTaskStatus.Running) } ?: return@synchronized false
        save(state.copy(tasks = state.tasks.map {
            if (it.taskId == taskId) task.copy(status = ProactiveTaskStatus.Cancelled, outcome = "cancelled") else it
        }))
        true
    }

    /** Returns IDs whose live Jobs the caller MUST cancel after the durable state change.
     * Keep reservations: dispatcher settles them; crash recovery consumes unknown outcomes. */
    fun cancel(assistantId: String? = null): Set<String> = synchronized(lock) {
        val state = read()
        val ids = state.tasks.filter {
            (assistantId == null || it.assistantId == assistantId) &&
                it.status in setOf(ProactiveTaskStatus.Pending, ProactiveTaskStatus.Running)
        }.mapTo(hashSetOf()) { it.taskId }
        // beginCommit is the durable publication intent. Do not destroy its payload or cancel
        // an in-flight Room write: recovery verifies exact IDs before deciding whether to append.
        if (ids.isNotEmpty()) save(state.copy(tasks = state.tasks.map {
            if (it.taskId in ids) it.copy(status = ProactiveTaskStatus.Cancelled, outcome = "cancelled") else it
        }))
        ids
    }

    /** Call ONCE per process startup, before starting jobs, never on each Store construction.
     * Unknown in-flight reservations remain charged; Running never returns to Pending. */
    fun interruptRunning(): Set<String> = synchronized(lock) {
        val state = read()
        val ids = state.tasks.filter { it.status == ProactiveTaskStatus.Running }.mapTo(hashSetOf()) { it.taskId }
        val commits = state.tasks.filter { it.status == ProactiveTaskStatus.CommitPending }
        if (ids.isNotEmpty() || state.reservations.isNotEmpty()) save(state.copy(
            tasks = state.tasks.map {
                if (it.taskId in ids) it.copy(status = ProactiveTaskStatus.Interrupted, outcome = "process_interrupted")
                else it
            },
            reservations = emptyMap(),
        ))
        ids + commits.map { it.taskId }
    }

    private fun validDay(state: ProactiveSchedulerState, host: ProactiveHostSnapshot, now: Long): String? {
        // Older multi-assistant builds could persist configs with an empty budget zone. Treat
        // blank as the local zone instead of silently refusing every claim (all messages fail).
        val zone = host.setting.globalPolicy.budgetZoneId.ifBlank { ZoneId.systemDefault().id }
        if (state.zoneId.isNotBlank() && state.zoneId != zone) return null
        val day = budgetDay(now, zone) ?: return null
        // Clock rollback or zone changes cannot manufacture another daily allowance.
        return day.takeIf { state.latestDay.isBlank() || it >= state.latestDay }
    }

    private fun read(): ProactiveSchedulerState {
        check(!failed) { "Scheduler storage failed; restart/recovery required" }
        return try {
            val state = storage.read()?.let { json.decodeFromString<ProactiveSchedulerState>(it) }
                ?: ProactiveSchedulerState()
            check(state.schemaVersion == 1) { "Unsupported scheduler schema" }
            state
        } catch (error: Exception) {
            failed = true
            throw error
        }
    }

    private fun save(state: ProactiveSchedulerState) {
        try { storage.write(json.encodeToString(state)) } catch (error: Exception) {
            failed = true
            throw error
        }
    }

    companion object {
        private val lock = Any()
        // Bounded R1 storage; explicit audited retention is required before production activation.
        private const val MAX_TASKS = 512
        internal const val MAX_COMMIT_PAYLOAD_CHARS = 512_000
        internal const val PROACTIVE_COMMIT_MAX_BYTES = 512_000
    }
}

private class PreferencesProactiveSchedulerStorage(context: Context) : ProactiveSchedulerStorage {
    private val prefs = context.applicationContext.getSharedPreferences("proactive_task_scheduler_r1", Context.MODE_PRIVATE)
    override fun read(): String? {
        check(!processWriteFailed) { "Scheduler preferences write failed" }
        return prefs.getString("snapshot", null)
    }
    override fun write(value: String) {
        check(!processWriteFailed)
        // commit(false) can still modify SharedPreferences memory: poison ALL adapters until restart.
        try {
            check(prefs.edit().putString("snapshot", value).commit()) { "Scheduler persistence failed" }
        } catch (error: Exception) {
            processWriteFailed = true
            throw error
        }
    }
    companion object { @Volatile private var processWriteFailed = false }
}
