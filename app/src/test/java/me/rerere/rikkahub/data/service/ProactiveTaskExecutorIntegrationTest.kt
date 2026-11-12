package me.rerere.rikkahub.data.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.datastore.AssistantProactiveConfig
import me.rerere.rikkahub.data.datastore.ProactiveGlobalPolicy
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

/** Exercises the production dispatcher and reservation wrapper with only Android I/O replaced. */
class ProactiveTaskExecutorIntegrationTest {
    private class Disk : ProactiveSchedulerStorage {
        private var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
    }
    private class Host(assistants: List<String>) : ProactiveExecutorPorts {
        var snapshot = ProactiveHostSnapshot(
            setting = ProactiveMessageSetting(enabled = true, activeHoursEnabled = false,
                assistantConfigs = assistants.map { AssistantProactiveConfig(it, "chat-$it") },
                globalPolicy = ProactiveGlobalPolicy(budgetZoneId = "UTC")),
            assistantIds = assistants.toSet(),
            conversationOwners = assistants.associate { "chat-$it" to it },
            userAnchors = assistants.associate { "chat-$it" to "user-$it" },
        )
        var recoveryCount = 0
        var diagnosticThrows = false
        val outcomes = Collections.synchronizedList(mutableListOf<String>())
        override suspend fun host() = snapshot
        override fun now() = 1_800_000_000_000L
        override suspend fun recover() { recoveryCount++ }
        override fun diagnostic(task: ProactiveTask?, outcome: String) {
            if (diagnosticThrows) error("activity disk unavailable")
            outcomes += outcome
        }
    }
    private class Provider {
        val calls = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        suspend fun generate(assistant: String, window: String) { calls += assistant to window }
    }

    @Test fun singleNonForegroundAssistantReachesItsOwnProviderAndWindow() = runBlocking {
        val host = Host(listOf("non-foreground"))
        val store = ProactiveTaskStore(Disk())
        val executor = ProactiveTaskExecutor(store, host)
        val provider = Provider()
        executor.drain("manual") { task ->
            executor.networkTurn(task) { provider.generate(task.assistantId, task.conversationId) }
        }
        assertEquals(listOf("non-foreground" to "chat-non-foreground"), provider.calls)
        assertEquals(1, host.recoveryCount) // Manual uses the same recovery entry.
        assertTrue(store.snapshot().reservations.isEmpty())
        assertTrue(store.snapshot().tasks.none { it.status == ProactiveTaskStatus.Running })
    }

    @Test fun manualClaimsAllParticipantsAndHangingOneDoesNotHideAnother() = runBlocking {
        val host = Host(listOf("a", "b"))
        val store = ProactiveTaskStore(Disk())
        val executor = ProactiveTaskExecutor(store, host, taskTimeoutMillis = 250)
        val enteredA = CompletableDeferred<Unit>()
        val enteredB = CompletableDeferred<Unit>()
        executor.drain("manual") { task -> executor.networkTurn(task) {
            if (task.assistantId == "a") {
                enteredA.complete(Unit)
                withTimeout(1_000) { enteredB.await() }
                awaitCancellation()
            } else {
                enteredB.complete(Unit)
                withTimeout(1_000) { enteredA.await() }
            }
        } }
        assertTrue(enteredA.isCompleted)
        assertTrue(enteredB.isCompleted)
        assertTrue(host.outcomes.contains("generation_timeout"))
        assertTrue(store.snapshot().reservations.isEmpty())
        assertTrue(store.snapshot().tasks.none { it.status == ProactiveTaskStatus.Running })
    }

    @Test fun networkInFirstDrainDoesNotHoldDispatcherLockForSecondDrain() = runBlocking {
        val host = Host(listOf("a"))
        val store = ProactiveTaskStore(Disk())
        val executor = ProactiveTaskExecutor(store, host, taskTimeoutMillis = 2_000)
        val enteredA = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val first = launch { executor.drain("manual") { task -> executor.networkTurn(task) {
            enteredA.complete(Unit); releaseA.await()
        } } }
        withTimeout(1_000) { enteredA.await() }
        host.snapshot = Host(listOf("a", "b")).snapshot
        val provider = Provider()
        withTimeout(1_000) {
            executor.drain("manual") { task ->
                executor.networkTurn(task) { provider.generate(task.assistantId, task.conversationId) }
            }
        }
        assertEquals(listOf("b" to "chat-b"), provider.calls)
        releaseA.complete(Unit)
        first.join()
    }

    @Test fun pendingCommitReservesOnlyItsOwnConversation() = runBlocking {
        val host = Host(listOf("a", "b"))
        val store = ProactiveTaskStore(Disk())
        val pending = ProactiveTask(taskId = "old", assistantId = "a", conversationId = "chat-a",
            anchorUserMessageId = "user-a", trigger = "manual", createdAtMillis = host.now())
        assertTrue(store.enqueue(pending))
        assertEquals("old", store.claimNext(host.snapshot, host.now(), manualBatch = true)?.taskId)
        assertTrue(store.beginCommit("old", host.snapshot, host.now(), listOf("stable-id"), "durable-payload"))
        val executor = ProactiveTaskExecutor(store, host)
        val provider = Provider()
        executor.drain("manual") { task ->
            executor.networkTurn(task) { provider.generate(task.assistantId, task.conversationId) }
        }
        assertEquals(listOf("b" to "chat-b"), provider.calls)
        assertEquals("durable-payload", store.pendingCommits().single().commitPayload)
    }

    @Test fun failedProviderAndThrowingDiagnosticDoNotCancelOtherAssistant() = runBlocking {
        val host = Host(listOf("a", "b"))
        host.diagnosticThrows = true
        val store = ProactiveTaskStore(Disk())
        val executor = ProactiveTaskExecutor(store, host)
        val provider = Provider()
        executor.drain("manual") { task -> executor.networkTurn(task) {
            if (task.assistantId == "a") error("provider failed")
            provider.generate(task.assistantId, task.conversationId)
        } }
        assertEquals(listOf("b" to "chat-b"), provider.calls)
        assertTrue(store.snapshot().reservations.isEmpty())
        assertTrue(store.snapshot().tasks.none { it.status == ProactiveTaskStatus.Running })
    }

    @Test fun automaticPeersAlsoProgressWhileAnotherNetworkTurnIsSuspended() = runBlocking {
        val host = Host(listOf("a", "b"))
        val store = ProactiveTaskStore(Disk())
        val executor = ProactiveTaskExecutor(store, host, taskTimeoutMillis = 250)
        val b = CompletableDeferred<Unit>()
        executor.drain("automatic") { task -> executor.networkTurn(task) {
            if (task.assistantId == "a") { withTimeout(1_000) { b.await() }; awaitCancellation() }
            else b.complete(Unit)
        } }
        assertTrue(b.isCompleted)
        assertTrue(store.snapshot().reservations.isEmpty())
    }
}
