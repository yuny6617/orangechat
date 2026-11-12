/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localtasks

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Controlled cancellation/write races. These tests deliberately block the synchronous commit block
 * of [LocalTaskCancellationToken.commitIfActive] or the scheduler's runner so the two orders can be
 * asserted without timing luck:
 *
 *  - cancel first  -> the guarded block never runs (no late localdata write);
 *  - write first   -> cancel waits for the block, the write is retained and no second write runs.
 */
class LocalTaskCancellationTest {
    @Test
    fun `cancel before the commit refuses the write`() {
        val gate = LocalTaskCancellationGate()
        val token = LocalTaskCancellationToken(gate)

        token.cancel()

        var writes = 0
        assertNull(token.commitIfActive { writes++ })
        assertTrue(token.isCancelled)
        assertEquals(0, writes)
    }

    @Test
    fun `commit that started first is retained and cancel waits for it`() {
        val gate = LocalTaskCancellationGate()
        val token = LocalTaskCancellationToken(gate)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var writes = 0

        val writer = thread(name = "lt-writer", isDaemon = true) {
            val result = token.commitIfActive {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "test did not release the write" }
                writes++
                "committed"
            }
            assertEquals("committed", result)
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val cancelFinished = CountDownLatch(1)
        val canceller = thread(name = "lt-canceller", isDaemon = true) {
            token.cancel()
            cancelFinished.countDown()
        }
        // synchronize with the in-flight write block instead of assuming a sleep duration
        assertFalse(cancelFinished.await(200, TimeUnit.MILLISECONDS))

        release.countDown()
        writer.join(5_000)
        canceller.join(5_000)

        assertEquals(1, writes)
        assertTrue(cancelFinished.await(0, TimeUnit.MILLISECONDS))
        assertTrue(token.isCancelled)
        assertNull(token.commitIfActive { writes++ })
        assertEquals(1, writes)
    }

    @Test
    fun `closing the gate waits for the in-flight write then refuses new ones`() {
        val gate = LocalTaskCancellationGate()
        val token = LocalTaskCancellationToken(gate)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var writes = 0

        val writer = thread(name = "lt-writer", isDaemon = true) {
            token.commitIfActive {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "test did not release the write" }
                writes++
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val closed = CountDownLatch(1)
        val closer = thread(name = "lt-closer", isDaemon = true) {
            gate.close()
            closed.countDown()
        }
        assertFalse(closed.await(200, TimeUnit.MILLISECONDS))
        release.countDown()
        writer.join(5_000)
        closer.join(5_000)

        assertEquals(1, writes)
        assertTrue(gate.isClosed)
        assertTrue(token.isCancelled)
        // A host closing the session must never see a later write slip in.
        assertNull(token.commitIfActive { writes++ })
        assertEquals(1, writes)
    }

    @Test
    fun `gate close wakes pending cancellation waiters`() = runBlocking {
        val gate = LocalTaskCancellationGate()
        val token = LocalTaskCancellationToken(gate)
        val resumed = AtomicBoolean(false)
        val job = launch(Dispatchers.Default) {
            token.awaitCancellation()
            resumed.set(true)
        }
        // Give the waiter a chance to suspend before closing; even if it has not, awaitCancellation
        // checks isCancelled first, so the test is not timing-sensitive.
        Thread.sleep(20)
        gate.close()
        withTimeout(5_000) { job.join() }

        assertTrue(gate.isClosed)
        assertTrue(token.isCancelled)
        assertTrue(resumed.get())
    }

    @Test
    fun `closed host refuses a leased review write`() = runBlocking {
        val runner = ConstantRunner(sampleHeldReview())
        val harness = SchedulerHarness(
            initialSettings = testSettings(maxRounds = 1),
            runner = runner,
        )
        val taskId = harness.submitInteraction("post_1").taskIdOrFail()
        harness.scheduler.pump()
        val lease = harness.scheduler.beginReview(taskId)
            ?: error("expected a review lease for a waiting task")

        // Simulates LocalTaskService.close()/release(): the gate is closed before the session.
        harness.gate.close()

        assertTrue(lease.token.isCancelled)
        var writes = 0
        assertNull(lease.token.commitIfActive { writes++ })
        assertEquals(0, writes)
        assertNull(harness.scheduler.beginReview(taskId))
        assertEquals(LocalTaskState.WAITING_REVIEW.wire, harness.scheduler.status(taskId)?.state)
        harness.scheduler.endReview(lease)
    }

    @Test
    fun `review lease cannot write after the task was cancelled`() = runBlocking {
        val runner = ConstantRunner(sampleHeldReview())
        val harness = SchedulerHarness(
            initialSettings = testSettings(maxRounds = 1),
            runner = runner,
        )
        val taskId = harness.submitInteraction("post_1").taskIdOrFail()
        harness.scheduler.pump()
        assertEquals(LocalTaskState.WAITING_REVIEW.wire, harness.scheduler.status(taskId)?.state)

        val lease = harness.scheduler.beginReview(taskId)
            ?: error("expected a review lease for a waiting task")
        assertTrue(lease.record.pendingReview.isNotEmpty())

        assertTrue(harness.scheduler.cancel(taskId))
        assertTrue(lease.token.isCancelled)

        var writes = 0
        assertNull(lease.token.commitIfActive { writes++ })
        assertEquals(0, writes)
        assertNull(harness.scheduler.beginReview(taskId))
        assertTrue(harness.ledger.task(taskId)!!.pendingReview.isEmpty())
        harness.scheduler.endReview(lease)
    }
}

private fun sampleHeldReview(): LocalTaskTurnOutcome.HeldForReview = LocalTaskTurnOutcome.HeldForReview(
    LocalTaskPendingActionDto(
        id = "ltr_test_1",
        kind = LocalTaskActionKinds.REPLY,
        assistantId = ASSISTANT_A.id,
        assistantName = ASSISTANT_A.name,
        postId = "post_1",
        body = "hello",
        like = true,
        createdAt = 1L,
    ),
)

