/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localtasks

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure scheduler tests: serial execution, round/reply caps, per-post and daily budgets, pacing,
 * idempotency, cancellation (no late write) and restart (no replay).
 */
class LocalTaskSchedulerTest {
    @Test
    fun `submit is idempotent for the same post`() = runBlocking {
        val runner = ConstantRunner(LocalTaskTurnOutcome.Replied(10, false))
        val harness = SchedulerHarness(initialSettings = testSettings(maxRounds = 1), runner = runner)
        val first = harness.submitInteraction("post_1")
        val second = harness.submitInteraction("post_1")

        assertTrue(first is LocalTaskSubmitResult.Accepted)
        assertTrue(second is LocalTaskSubmitResult.Accepted)
        assertEquals(first.taskIdOrFail(), second.taskIdOrFail())
        assertTrue((second as LocalTaskSubmitResult.Accepted).existing)

        harness.scheduler.pump()
        assertEquals(1, runner.calls.size)
    }

    @Test
    fun `zero rounds disables interaction without a model call`() = runBlocking {
        val runner = ConstantRunner(LocalTaskTurnOutcome.Replied(10, false))
        val harness = SchedulerHarness(initialSettings = testSettings(maxRounds = 0), runner = runner)

        val result = harness.submitInteraction("post_1")

        assertTrue(result is LocalTaskSubmitResult.Rejected)
        assertEquals(LocalTaskErrorCode.INTERACTION_DISABLED, (result as LocalTaskSubmitResult.Rejected).code)
        assertEquals(0, harness.scheduler.pump())
        assertEquals(0, runner.calls.size)
    }

    @Test
    fun `assistant outside the native participant list is rejected`() = runBlocking {
        val harness = SchedulerHarness(runner = ConstantRunner(LocalTaskTurnOutcome.Skipped))

        val result = harness.submitInteraction("post_1", assistantId = ASSISTANT_B.id)

        assertTrue(result is LocalTaskSubmitResult.Rejected)
        assertEquals(
            LocalTaskErrorCode.ASSISTANT_NOT_PARTICIPANT,
            (result as LocalTaskSubmitResult.Rejected).code,
        )
    }

    @Test
    fun `global stop rejects new tasks`() = runBlocking {
        val harness = SchedulerHarness(
            initialSettings = testSettings().copy(globalStop = true),
            runner = ConstantRunner(LocalTaskTurnOutcome.Skipped),
        )

        val result = harness.submitInteraction("post_1")

        assertEquals(LocalTaskErrorCode.STOPPED, (result as LocalTaskSubmitResult.Rejected).code)
    }

    @Test
    fun `per post model call budget stops the task`() = runBlocking {
        val runner = ConstantRunner(LocalTaskTurnOutcome.Replied(10, false))
        val harness = SchedulerHarness(
            initialSettings = testSettings(maxRounds = 5, maxReplies = 5, maxCallsPerPost = 2),
            runner = runner,
        )

        val taskId = harness.submitInteraction("post_1").taskIdOrFail()
        harness.scheduler.pump()

        assertEquals(2, runner.calls.size)
        val status = harness.scheduler.status(taskId)!!
        assertEquals(LocalTaskState.COMPLETED.wire, status.state)
        assertEquals("budget_exhausted", status.endReason)
    }

    @Test
    fun `per post model call budget is shared across assistants`() = runBlocking {
        val runner = ConstantRunner(LocalTaskTurnOutcome.Replied(10, false))
        val harness = SchedulerHarness(
            initialSettings = testSettings(ASSISTANT_A, ASSISTANT_B, maxRounds = 1, maxCallsPerPost = 1),
            participants = listOf(ASSISTANT_A, ASSISTANT_B),
            runner = runner,
        )

        harness.submitInteraction("post_1").taskIdOrFail()
        harness.scheduler.pump()

        // A different assistant may not get a fresh per-post reservation.
        assertEquals(1, runner.calls.size)
        assertEquals(1, harness.ledger.modelCallsFor("post_1"))
    }

    @Test
    fun `per post budget is shared by an all-participants and a narrowed task`() = runBlocking {
        val runner = ConstantRunner(LocalTaskTurnOutcome.Replied(10, false))
        val harness = SchedulerHarness(
            initialSettings = testSettings(ASSISTANT_A, ASSISTANT_B, maxRounds = 1, maxCallsPerPost = 1),
            participants = listOf(ASSISTANT_A, ASSISTANT_B),
            runner = runner,
        )

        val all = harness.submitInteraction("post_1")
        val narrowed = harness.submitInteraction("post_1", assistantId = ASSISTANT_B.id)
        assertTrue(all is LocalTaskSubmitResult.Accepted)
        assertTrue(narrowed is LocalTaskSubmitResult.Accepted)
        assertFalse((narrowed as LocalTaskSubmitResult.Accepted).existing)
        harness.scheduler.pump()

        assertEquals(1, runner.calls.size)
        assertEquals(1, harness.ledger.modelCallsFor("post_1"))
    }

    @Test
    fun `publish tasks share one usage budget instead of one per task`() = runBlocking {
        val runner = ConstantRunner(LocalTaskTurnOutcome.PostPublished("post_new"))
        val settings = testSettings(ASSISTANT_A, ASSISTANT_B, maxCallsPerPost = 1).copy(
            assistantPolicies = mapOf(
                ASSISTANT_A.id to LocalAssistantPolicy.AUTO_PUBLISH,
                ASSISTANT_B.id to LocalAssistantPolicy.AUTO_PUBLISH,
            ),
        )
        val harness = SchedulerHarness(
            initialSettings = settings,
            participants = listOf(ASSISTANT_A, ASSISTANT_B),
            runner = runner,
        )

        harness.scheduler.submit(
            LocalTaskStartRequest(mode = LocalTaskMode.PUBLISH, assistantId = ASSISTANT_A.id),
        ).taskIdOrFail()
        harness.scheduler.pump()
        assertEquals(1, runner.calls.size)

        val second = harness.scheduler.submit(
            LocalTaskStartRequest(mode = LocalTaskMode.PUBLISH, assistantId = ASSISTANT_B.id),
        ).taskIdOrFail()
        harness.scheduler.pump()

        // A new task id or a different assistant must not reset the per-post usage key.
        assertEquals(1, runner.calls.size)
        assertEquals(1, harness.ledger.modelCallsFor(LocalTasksApi.publishUsageKey(harness.now)))
        assertEquals("budget_exhausted", harness.scheduler.status(second)?.endReason)
    }

    @Test
    fun `publish usage key rolls over on the next day`() = runBlocking {
        val runner = ConstantRunner(LocalTaskTurnOutcome.PostPublished("post_new"))
        val settings = testSettings(ASSISTANT_A, ASSISTANT_B, maxCallsPerPost = 1).copy(
            assistantPolicies = mapOf(
                ASSISTANT_A.id to LocalAssistantPolicy.AUTO_PUBLISH,
                ASSISTANT_B.id to LocalAssistantPolicy.AUTO_PUBLISH,
            ),
        )
        val harness = SchedulerHarness(
            initialSettings = settings,
            participants = listOf(ASSISTANT_A, ASSISTANT_B),
            runner = runner,
        )

        harness.scheduler.submit(
            LocalTaskStartRequest(mode = LocalTaskMode.PUBLISH, assistantId = ASSISTANT_A.id),
        ).taskIdOrFail()
        harness.scheduler.pump()
        assertEquals(1, runner.calls.size)

        harness.now += LocalTasksApi.DAY_MILLIS
        harness.scheduler.submit(
            LocalTaskStartRequest(mode = LocalTaskMode.PUBLISH, assistantId = ASSISTANT_B.id),
        ).taskIdOrFail()
        harness.scheduler.pump()

        // The shared publish key is per day, so a new day gets a fresh reservation.
        assertEquals(2, runner.calls.size)
        assertEquals(1, harness.ledger.modelCallsFor(LocalTasksApi.publishUsageKey(harness.now)))
    }

    @Test
    fun `pre model skips refund the reserved turn`() = runBlocking {
        val runner = ConstantRunner(LocalTaskTurnOutcome.NotAttempted("own_post"))
        val harness = SchedulerHarness(initialSettings = testSettings(maxRounds = 2), runner = runner)

        val taskId = harness.submitInteraction("post_1").taskIdOrFail()
        harness.scheduler.pump()

        assertEquals(0, harness.ledger.modelCallsFor("post_1"))
        assertEquals(0, harness.scheduler.status(taskId)?.modelCalls ?: -1)
        assertEquals("all_declined", harness.scheduler.status(taskId)?.endReason)
        assertTrue(harness.ledger.hasDailyBudget(testSettings()))
    }

    @Test
    fun `daily budget rejects the second task and rolls over on the next day`() = runBlocking {
        val runner = ConstantRunner(LocalTaskTurnOutcome.Replied(10, false))
        val harness = SchedulerHarness(
            initialSettings = testSettings(maxRounds = 1, dailyBudget = 1),
            runner = runner,
        )

        harness.submitInteraction("post_1").taskIdOrFail()
        harness.scheduler.pump()
        assertEquals(1, runner.calls.size)

        val blocked = harness.submitInteraction("post_2")
        assertEquals(LocalTaskErrorCode.BUDGET_EXHAUSTED, (blocked as LocalTaskSubmitResult.Rejected).code)

        harness.now += LocalTasksApi.DAY_MILLIS
        val accepted = harness.submitInteraction("post_3")
        assertTrue(accepted is LocalTaskSubmitResult.Accepted)
        harness.scheduler.pump()
        assertEquals(2, runner.calls.size)
    }

    @Test
    fun `reply cap stops further model calls for that assistant`() = runBlocking {
        val runner = ConstantRunner(LocalTaskTurnOutcome.Replied(10, false))
        val harness = SchedulerHarness(
            initialSettings = testSettings(maxRounds = 1, maxReplies = 1),
            runner = runner,
        )

        harness.submitInteraction("post_1").taskIdOrFail()
        harness.scheduler.pump()
        assertEquals(1, runner.calls.size)

        val secondTask = harness.submitInteraction("post_1").taskIdOrFail()
        harness.scheduler.pump()

        assertEquals(1, runner.calls.size)
        assertEquals("all_declined", harness.scheduler.status(secondTask)?.endReason)
    }

    @Test
    fun `all assistants declining ends after one round`() = runBlocking {
        val runner = ConstantRunner(LocalTaskTurnOutcome.Skipped)
        val harness = SchedulerHarness(
            initialSettings = testSettings(ASSISTANT_A, ASSISTANT_B, maxRounds = 3),
            participants = listOf(ASSISTANT_A, ASSISTANT_B),
            runner = runner,
        )

        val taskId = harness.submitInteraction("post_1").taskIdOrFail()
        harness.scheduler.pump()

        assertEquals(2, runner.calls.size)
        assertEquals("all_declined", harness.scheduler.status(taskId)?.endReason)
    }

    @Test
    fun `tasks run one at a time`() = runBlocking {
        val runner = RecordingRunner(
            mutableListOf(LocalTaskTurnOutcome.Skipped, LocalTaskTurnOutcome.Skipped),
        )
        val harness = SchedulerHarness(runner = runner)

        harness.submitInteraction("post_1").taskIdOrFail()
        harness.submitInteraction("post_2").taskIdOrFail()
        harness.scheduler.pump()

        assertEquals(1, runner.maxConcurrent)
        assertEquals(2, runner.calls.size)
    }

    @Test
    fun `cancelling a running task prevents the late write`() = runBlocking {
        val runner = GatedRunner()
        val harness = SchedulerHarness(runner = runner)
        val taskId = harness.submitInteraction("post_1").taskIdOrFail()

        val pump = launch { harness.scheduler.pump() }
        runner.started.await()
        assertTrue(harness.scheduler.cancel(taskId))
        pump.join()

        assertTrue(runner.observedCancellation)
        assertFalse(runner.wroteAfterCancellation)
        val status = harness.scheduler.status(taskId)!!
        assertEquals(LocalTaskState.CANCELLED.wire, status.state)
        assertEquals("cancelled", status.endReason)
    }

    @Test
    fun `cancel before a guarded commit refuses the write`() = runBlocking {
        val runner = BlockingCommitRunner(blockInsideCommit = false)
        val harness = SchedulerHarness(initialSettings = testSettings(maxRounds = 1), runner = runner)
        val taskId = harness.submitInteraction("post_1").taskIdOrFail()

        val pump = launch(kotlinx.coroutines.Dispatchers.Default) { harness.scheduler.pump() }
        assertTrue(runner.atCommit.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(harness.scheduler.cancel(taskId))
        runner.release.countDown()
        withTimeout(5_000) { pump.join() }

        assertFalse(runner.wrote.get())
        assertEquals(0, harness.ledger.replyCount("post_1", ASSISTANT_A.id))
        val status = harness.scheduler.status(taskId)!!
        assertEquals(LocalTaskState.CANCELLED.wire, status.state)
        assertEquals(0, status.comments)
    }

    @Test
    fun `committed write survives job cancellation that lands after the commit`() = runBlocking {
        val runner = CommitThenBlockRunner()
        val harness = SchedulerHarness(initialSettings = testSettings(maxRounds = 1), runner = runner)
        val taskId = harness.submitInteraction("post_1").taskIdOrFail()

        val pump = launch(kotlinx.coroutines.Dispatchers.Default) { harness.scheduler.pump() }
        assertTrue(runner.committed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // The write is already committed; cancel only the turn job from here.
        assertTrue(harness.scheduler.cancel(taskId))
        runner.releaseAfterCommit.countDown()
        withTimeout(5_000) { pump.join() }

        val status = harness.scheduler.status(taskId)!!
        assertEquals(LocalTaskState.CANCELLED.wire, status.state)
        assertEquals(1, status.comments)
        assertEquals(1, harness.ledger.replyCount("post_1", ASSISTANT_A.id))
    }

    @Test
    fun `cancel waits for an in-flight guarded commit and keeps its result`() = runBlocking {
        val runner = BlockingCommitRunner(blockInsideCommit = true)
        val harness = SchedulerHarness(initialSettings = testSettings(maxRounds = 1), runner = runner)
        val taskId = harness.submitInteraction("post_1").taskIdOrFail()

        val pump = launch(kotlinx.coroutines.Dispatchers.Default) { harness.scheduler.pump() }
        assertTrue(runner.atCommit.await(5, java.util.concurrent.TimeUnit.SECONDS))

        val cancelFinished = java.util.concurrent.CountDownLatch(1)
        val canceller = kotlin.concurrent.thread(isDaemon = true) {
            harness.scheduler.cancel(taskId)
            cancelFinished.countDown()
        }
        // The commit block is still inside the shared gate: cancel must not return before it ends.
        assertFalse(cancelFinished.await(200, java.util.concurrent.TimeUnit.MILLISECONDS))
        runner.release.countDown()
        withTimeout(5_000) { pump.join() }
        canceller.join(5_000)

        assertTrue(runner.wrote.get())
        assertTrue(cancelFinished.await(0, java.util.concurrent.TimeUnit.MILLISECONDS))
        val status = harness.scheduler.status(taskId)!!
        assertEquals(LocalTaskState.CANCELLED.wire, status.state)
        // Write-first keeps the committed comment/counter even though the task is cancelled.
        assertEquals(1, status.comments)
        assertEquals(1, harness.ledger.replyCount("post_1", ASSISTANT_A.id))
    }

    @Test
    fun `stop all cancels queued tasks and the queue is not replayed`() = runBlocking {
        val runner = ConstantRunner(LocalTaskTurnOutcome.Replied(10, false))
        val harness = SchedulerHarness(runner = runner)

        harness.submitInteraction("post_1").taskIdOrFail()
        harness.submitInteraction("post_2").taskIdOrFail()

        assertEquals(2, harness.scheduler.stopAll())
        assertEquals(0, harness.scheduler.pump())
        assertEquals(0, runner.calls.size)
        assertTrue(harness.scheduler.statuses().all { it.state == LocalTaskState.CANCELLED.wire })
    }

    @Test
    fun `restart marks unfinished tasks interrupted and never replays them`() = runBlocking {
        val store = MemoryLocalTaskStore()
        var now = 1_700_000_000_000L
        val firstLedger = LocalTaskLedger(store) { now }
        val firstScheduler = LocalTaskScheduler(
            settingsProvider = { testSettings() },
            participantsProvider = { listOf(ASSISTANT_A) },
            ledger = firstLedger,
            runner = ConstantRunner(LocalTaskTurnOutcome.Skipped),
            clock = { now },
            sleeper = { },
            taskIdFactory = { "lt_before_restart" },
        )
        val taskId = firstScheduler.submit(
            LocalTaskStartRequest(mode = LocalTaskMode.INTERACT, postId = "post_1"),
        ).taskIdOrFail()

        // A brand new process only sees the persisted ledger.
        val secondLedger = LocalTaskLedger(store) { now }
        assertEquals(1, secondLedger.markInterruptedOnStartup())
        assertEquals(LocalTaskState.INTERRUPTED, secondLedger.task(taskId)?.state)

        val replayRunner = ConstantRunner(LocalTaskTurnOutcome.Replied(10, false))
        val secondScheduler = LocalTaskScheduler(
            settingsProvider = { testSettings() },
            participantsProvider = { listOf(ASSISTANT_A) },
            ledger = secondLedger,
            runner = replayRunner,
            clock = { now },
            sleeper = { },
        )
        assertEquals(0, secondScheduler.pump())
        assertEquals(0, replayRunner.calls.size)
        now += 1_000
    }

    @Test
    fun `interval paces the next assistant`() = runBlocking {
        val runner = ConstantRunner(LocalTaskTurnOutcome.Replied(10, false))
        val harness = SchedulerHarness(
            initialSettings = testSettings(
                ASSISTANT_A, ASSISTANT_B,
                maxRounds = 1,
                intervalMs = 1_000,
            ),
            participants = listOf(ASSISTANT_A, ASSISTANT_B),
            runner = runner,
        )

        harness.submitInteraction("post_1").taskIdOrFail()
        harness.scheduler.pump()

        assertEquals(2, runner.calls.size)
        assertEquals(listOf(1_000L), harness.sleeps)
    }

    @Test
    fun `publish submit is idempotent for the same assistant`() = runBlocking {
        val harness = SchedulerHarness(
            initialSettings = testSettings().copy(
                assistantPolicies = mapOf(ASSISTANT_A.id to LocalAssistantPolicy.AUTO_PUBLISH),
            ),
            runner = ConstantRunner(LocalTaskTurnOutcome.PostDrafted("draft_1")),
        )
        val first = harness.scheduler.submit(
            LocalTaskStartRequest(mode = LocalTaskMode.PUBLISH, assistantId = ASSISTANT_A.id),
        )
        val second = harness.scheduler.submit(
            LocalTaskStartRequest(mode = LocalTaskMode.PUBLISH, assistantId = ASSISTANT_A.id),
        )

        assertEquals(first.taskIdOrFail(), second.taskIdOrFail())
        assertTrue((second as LocalTaskSubmitResult.Accepted).existing)
    }
}

