/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localtasks

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException

internal class MemoryLocalTaskStore : LocalTaskKeyValueStore {
    private val values = LinkedHashMap<String, String>()

    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String) {
        values[key] = value
    }

    fun raw(key: String): String? = values[key]
}

internal val ASSISTANT_A = LocalTaskParticipant(
    id = "11111111-1111-1111-1111-111111111111",
    name = "Alice",
    policy = LocalAssistantPolicy.DRAFT_ONLY,
)

internal val ASSISTANT_B = LocalTaskParticipant(
    id = "22222222-2222-2222-2222-222222222222",
    name = "Bob",
    policy = LocalAssistantPolicy.DRAFT_ONLY,
)

internal fun testSettings(
    vararg participants: LocalTaskParticipant = arrayOf(ASSISTANT_A),
    maxRounds: Int = 2,
    maxReplies: Int = 5,
    maxCallsPerPost: Int = 20,
    dailyBudget: Int = 100,
    intervalMs: Long = 0,
): LocalTaskSettings = LocalTaskSettings(
    enabled = true,
    participants = participants.map { it.id },
    assistantPolicies = participants.associate { it.id to it.policy },
    maxRounds = maxRounds,
    maxRepliesPerAssistantPerPost = maxReplies,
    maxModelCallsPerPost = maxCallsPerPost,
    dailyModelCallBudget = dailyBudget,
    intervalMs = intervalMs,
)

internal class RecordingRunner(
    private val outcomes: MutableList<LocalTaskTurnOutcome> = mutableListOf(),
) : LocalTaskTurnRunner {
    val calls = mutableListOf<LocalTaskTurnRequest>()
    var maxConcurrent = 0
    private var concurrent = 0

    override suspend fun runTurn(request: LocalTaskTurnRequest): LocalTaskTurnOutcome {
        calls += request
        concurrent++
        maxConcurrent = maxOf(maxConcurrent, concurrent)
        try {
            return if (outcomes.isEmpty()) LocalTaskTurnOutcome.Skipped else outcomes.removeAt(0)
        } finally {
            concurrent--
        }
    }
}

internal class ConstantRunner(
    private val outcome: LocalTaskTurnOutcome,
) : LocalTaskTurnRunner {
    val calls = mutableListOf<LocalTaskTurnRequest>()

    override suspend fun runTurn(request: LocalTaskTurnRequest): LocalTaskTurnOutcome {
        calls += request
        return outcome
    }
}

/** A runner that blocks inside the model call until the scheduler cancels it. */
internal class GatedRunner : LocalTaskTurnRunner {
    val started = CompletableDeferred<Unit>()
    val gate = CompletableDeferred<Unit>()
    var observedCancellation = false
    var wroteAfterCancellation = false

    override suspend fun runTurn(request: LocalTaskTurnRequest): LocalTaskTurnOutcome {
        started.complete(Unit)
        try {
            gate.await()
        } catch (error: CancellationException) {
            observedCancellation = true
            throw error
        }
        if (request.token.isCancelled) {
            observedCancellation = true
        } else {
            wroteAfterCancellation = true
        }
        return LocalTaskTurnOutcome.Replied(bodyChars = 12, liked = false)
    }
}

/**
 * A runner whose only write goes through [LocalTaskCancellationToken.commitIfActive], so a test can
 * deterministically order "cancel" against a blocked synchronous write.
 *
 * [blockInsideCommit] = true blocks *inside* the guarded critical section (write-first race).
 * [blockInsideCommit] = false blocks *before* entering it (cancel-first race).
 */
internal class BlockingCommitRunner(
    private val blockInsideCommit: Boolean,
    private val outcome: LocalTaskTurnOutcome = LocalTaskTurnOutcome.Replied(7, liked = false),
) : LocalTaskTurnRunner {
    val atCommit = CountDownLatch(1)
    val release = CountDownLatch(1)
    val wrote = AtomicBoolean(false)

    override suspend fun runTurn(request: LocalTaskTurnRequest): LocalTaskTurnOutcome {
        if (!blockInsideCommit) {
            atCommit.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Test did not release the pre-commit gate" }
        }
        val result = request.token.commitIfActive {
            if (blockInsideCommit) {
                atCommit.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Test did not release the commit gate" }
            }
            wrote.set(true)
            outcome
        }
        result?.let { request.token.recordCommitted(it) }
        return result ?: LocalTaskTurnOutcome.Cancelled
    }
}

/**
 * Simulates a durable write that completes just before job cancellation: the commit runs and is
 * recorded, then the runner blocks so the test can cancel the job while the turn has not returned.
 * [LocalTaskScheduler.runGuarded] must still keep the recorded committed result.
 */
internal class CommitThenBlockRunner(
    private val outcome: LocalTaskTurnOutcome = LocalTaskTurnOutcome.Replied(7, liked = false),
) : LocalTaskTurnRunner {
    val committed = CountDownLatch(1)
    val releaseAfterCommit = CountDownLatch(1)

    override suspend fun runTurn(request: LocalTaskTurnRequest): LocalTaskTurnOutcome {
        val result = request.token.commitIfActive {
            request.token.recordCommitted(outcome)
            outcome
        } ?: return LocalTaskTurnOutcome.Cancelled
        committed.countDown()
        check(releaseAfterCommit.await(5, TimeUnit.SECONDS)) { "Test did not release the turn" }
        return result
    }
}

internal class SchedulerHarness(
    initialSettings: LocalTaskSettings = testSettings(),
    val participants: List<LocalTaskParticipant> = listOf(ASSISTANT_A),
    val runner: LocalTaskTurnRunner,
    val store: MemoryLocalTaskStore = MemoryLocalTaskStore(),
    var now: Long = 1_700_000_000_000L,
    /** The same gate the scheduler/tokens use; closing it simulates a host release. */
    val gate: LocalTaskCancellationGate = LocalTaskCancellationGate(),
) {
    var settings: LocalTaskSettings = initialSettings
    val sleeps = mutableListOf<Long>()
    private var counter = 0
    val ledger = LocalTaskLedger(store) { now }
    val scheduler = LocalTaskScheduler(
        settingsProvider = { settings },
        participantsProvider = { participants },
        ledger = ledger,
        runner = runner,
        clock = { now },
        sleeper = { millis ->
            sleeps += millis
            now += millis
        },
        taskIdFactory = { "lt_test_${counter++}" },
        cancellation = gate,
    )

    fun submitInteraction(
        postId: String,
        assistantId: String? = null,
        clientKey: String? = null,
    ): LocalTaskSubmitResult = scheduler.submit(
        LocalTaskStartRequest(
            mode = LocalTaskMode.INTERACT,
            postId = postId,
            assistantId = assistantId,
            clientKey = clientKey,
        ),
    )
}

internal fun LocalTaskSubmitResult.taskIdOrFail(): String {
    check(this is LocalTaskSubmitResult.Accepted) { "Expected an accepted task, got $this" }
    return taskId
}
