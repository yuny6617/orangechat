package me.rerere.rikkahub.data.service

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.AssistantProactiveConfig
import me.rerere.rikkahub.data.datastore.ProactiveGlobalPolicy
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ProactiveTaskStoreTest {
    private val now = Instant.parse("2026-06-01T12:00:00Z").toEpochMilli()
    private val configs = listOf(AssistantProactiveConfig("a", "ca", cooldownMinutes = 0),
        AssistantProactiveConfig("b", "cb", cooldownMinutes = 0))
    private fun host(limit: Int = 3) = ProactiveHostSnapshot(
        ProactiveMessageSetting(enabled = true, activeHoursEnabled = false, assistantConfigs = configs,
            globalPolicy = ProactiveGlobalPolicy(dailyModelTurnLimit = limit, budgetZoneId = "UTC")),
        setOf("a", "b"), mapOf("ca" to "a", "cb" to "b"), mapOf("ca" to "anchor", "cb" to "anchor"),
    )
    private fun task(id: String, assistant: String = "a") = ProactiveTask(
        taskId = id, assistantId = assistant, conversationId = "c$assistant", trigger = "automatic",
        anchorUserMessageId = "anchor", createdAtMillis = now,
    )
    private class MemoryStorage : ProactiveSchedulerStorage {
        var value: String? = null
        var fail = false
        override fun read() = value
        override fun write(value: String) { check(!fail); this.value = value }
    }

    @Test fun legacySerializationAndExplicitEmptyRemainDistinct() {
        val legacy = Json.decodeFromString<ProactiveMessageSetting>("{\"enabled\":true}")
        val empty = Json.decodeFromString<ProactiveMessageSetting>("{\"assistantConfigs\":[]}")
        assertNull(legacy.assistantConfigs)
        assertEquals(emptyList<AssistantProactiveConfig>(), empty.assistantConfigs)
        val owner = mapOf("ca" to "a")
        assertEquals(listOf(configs[0]), migrateProactiveParticipants(null, "a", configs, owner))
        assertEquals(listOf(configs[0]), migrateProactiveParticipants(null, "", listOf(configs[0]), owner))
        assertTrue(migrateProactiveParticipants(emptyList(), "a", configs, owner).isEmpty())
        assertTrue(migrateProactiveParticipants(null, "gone", configs, owner).isEmpty())
        assertTrue(migrateProactiveParticipants(null, "a", configs, mapOf("ca" to "b")).isEmpty())
        assertTrue(migrateProactiveParticipants(null, "", configs, owner).isEmpty())
        // After persist, changed foreground candidates cannot remigrate an existing enrollment.
        assertEquals(listOf(configs[0]), migrateProactiveParticipants(listOf(configs[0]), "b", configs, owner))
    }

    @Test fun fairClaimsReserveEachConversationAndTerminalCas() {
        val store = ProactiveTaskStore(MemoryStorage())
        listOf(task("a1"), task("a2"), task("b1", "b")).forEach { assertTrue(store.enqueue(it)) }
        assertFalse(store.enqueue(task("a1")))
        assertEquals("a1", store.claimNext(host(), now)?.taskId)
        assertEquals("b1", store.claimNext(host(), now)?.taskId)
        assertNull(store.claimNext(host(), now)) // a2 shares a1's busy conversation.
        assertTrue(store.finish("a1", ProactiveTaskStatus.Failed, "no_provider"))
        assertFalse(store.finish("a1", ProactiveTaskStatus.Sent, "late"))
        assertEquals("a2", store.claimNext(host(), now)?.taskId)
    }

    @Test fun everyTurnIsChargedAndOnlyPreDispatchCanRollback() {
        val store = ProactiveTaskStore(MemoryStorage())
        store.enqueue(task("a")); store.claimNext(host(), now)
        val first = requireNotNull(store.reserveTurn("a", host(1), now))
        assertNull(store.reserveTurn("a", host(1), now))
        assertTrue(store.settleTurn(first)) // preparation failure: no provider call
        val second = requireNotNull(store.reserveTurn("a", host(1), now))
        assertFalse(store.settleTurn(first)) // stale token cannot refund second turn
        assertTrue(store.markDispatched(second, host(1), now))
        assertTrue(store.settleTurn(second)) // includes HTTP failure/timeouts, still charged
        assertNull(store.reserveTurn("a", host(1), now))
        assertEquals(1, store.snapshot().days.getValue("2026-06-01").modelTurns)
        val continuation = requireNotNull(store.reserveTurn("a", host(2), now))
        assertTrue(store.markDispatched(continuation, host(2), now))
        assertTrue(store.settleTurn(continuation))
        assertNull(store.reserveTurn("a", host(2), now)) // finalizer also needs allowance
    }

    @Test fun cancellationAndRestartRejectLateResultsWithoutReplay() {
        val disk = MemoryStorage()
        val store = ProactiveTaskStore(disk)
        store.enqueue(task("a")); store.claimNext(host(), now)
        val token = requireNotNull(store.reserveTurn("a", host(), now))
        val restarted = ProactiveTaskStore(disk)
        assertEquals(setOf("a"), restarted.interruptRunning())
        assertFalse(restarted.settleTurn(token)) // unknown reservation conservatively charged
        assertFalse(restarted.canCommit("a", host(), now))
        assertNull(restarted.claimNext(host(), now))
        assertEquals(1, restarted.snapshot().days.getValue("2026-06-01").modelTurns)
        restarted.enqueue(task("b", "b")); restarted.claimNext(host(), now)
        assertEquals(setOf("b"), restarted.cancel("b"))
        assertFalse(restarted.canCommit("b", host(), now))
        assertFalse(restarted.finish("b", ProactiveTaskStatus.Sent, "late"))
    }

    @Test fun freshOwnerAssistantConfigAndPauseChecksFailClosed() {
        val store = ProactiveTaskStore(MemoryStorage())
        store.enqueue(task("a")); store.claimNext(host(), now)
        val h = host()
        assertFalse(store.canCommit("a", h.copy(conversationOwners = mapOf("ca" to "b")), now))
        assertFalse(store.canCommit("a", h.copy(assistantIds = setOf("b")), now))
        assertFalse(store.canCommit("a", h.copy(userAnchors = emptyMap()), now))
        assertFalse(store.canCommit("a", h.copy(setting = h.setting.copy(
            globalPolicy = h.setting.globalPolicy.copy(paused = true))), now))
        assertNull(store.reserveTurn("a", h.copy(setting = h.setting.copy(assistantConfigs = emptyList())), now))
        assertFalse(store.canCommit("a", h.copy(setting = h.setting.copy(
            assistantConfigs = listOf(configs[0].copy(promptOverride = "changed")))), now))
    }

    @Test fun cooldownDailyLimitBusyWindowAndFutureTaskAreSkippedFairly() {
        val store = ProactiveTaskStore(MemoryStorage())
        val h = host().let { it.copy(setting = it.setting.copy(
            assistantConfigs = listOf(
                configs[0].copy(customEnabled = true, cooldownMinutes = 60, dailyLimit = 1),
                configs[1],
            ))) }
        store.enqueue(task("a1")); store.enqueue(task("a2")); store.enqueue(task("b1", "b"))
        assertEquals("b1", store.claimNext(h.copy(busyConversations = setOf("ca")), now)?.taskId)
        store.finish("b1", ProactiveTaskStatus.Failed, "pass")
        assertEquals("a1", store.claimNext(h, now)?.taskId)
        store.finish("a1", ProactiveTaskStatus.Failed, "pass")
        assertNull(store.claimNext(h, now + 61 * 60_000)) // daily task count, not network count
        assertEquals("a2", store.claimNext(h, now + 24 * 60 * 60_000)?.taskId)
    }

    @Test fun blankLegacyBudgetZoneStillAllowsClaims() {
        val store = ProactiveTaskStore(MemoryStorage())
        val h = host().let { it.copy(setting = it.setting.copy(
            globalPolicy = it.setting.globalPolicy.copy(budgetZoneId = ""),
        )) }
        store.enqueue(task("a"))
        assertEquals("a", store.claimNext(h, now)?.taskId)
    }

    @Test fun pinnedZoneDstAndClockRollbackCannotResetBudget() {
        assertEquals("2025-03-08", budgetDay(Instant.parse("2025-03-09T07:30:00Z").toEpochMilli(), "America/Los_Angeles"))
        assertNull(budgetDay(now, "invalid-zone"))
        val store = ProactiveTaskStore(MemoryStorage())
        store.enqueue(task("a")); store.claimNext(host(), now)
        assertNull(store.reserveTurn("a", host(), now - 86_400_000))
        val changedZone = host().let { it.copy(setting = it.setting.copy(
            globalPolicy = it.setting.globalPolicy.copy(budgetZoneId = "Asia/Tokyo"))) }
        assertNull(store.reserveTurn("a", changedZone, now))
    }

    @Test fun durablePayloadSurvivesCancelAndRestartUntilAnchorInvalidation() {
        val disk = MemoryStorage()
        val store = ProactiveTaskStore(disk)
        store.enqueue(task("a")); store.claimNext(host(), now)
        val payload = "serialized-final-messages"
        assertTrue(store.beginCommit("a", host(), now, listOf("id-1"), payload))
        val restarted = ProactiveTaskStore(disk)
        assertEquals(setOf("a"), restarted.interruptRunning())
        assertEquals(payload, restarted.pendingCommits().single().commitPayload)
        assertEquals(listOf("id-1"), restarted.pendingCommits().single().commitMessageIds)
        restarted.cancel()
        assertNotNull(restarted.startCommit("a"))
        assertEquals(payload, restarted.pendingCommits().single().commitPayload)
        assertTrue(restarted.abandonCommit("a", ProactiveTaskStatus.Interrupted, "anchor_changed"))
        assertTrue(restarted.pendingCommits().isEmpty())
        assertEquals(ProactiveTaskStatus.Interrupted, restarted.snapshot().tasks.single().status)
    }

    @Test fun commitMarkSentOccursOnlyAfterRoomCallbackSucceeds() {
        val store = ProactiveTaskStore(MemoryStorage())
        store.enqueue(task("a")); store.claimNext(host(), now)
        assertTrue(store.beginCommit("a", host(), now, listOf("stable-id"), "payload"))
        assertFalse(store.commitPayload("a") { false })
        assertEquals(ProactiveTaskStatus.CommitPending, store.snapshot().tasks.single().status)
        assertEquals("payload", store.snapshot().tasks.single().commitPayload)
        assertTrue(store.commitPayload("a") { it.commitMessageIds == listOf("stable-id") })
        assertEquals(ProactiveTaskStatus.Sent, store.snapshot().tasks.single().status)
        assertNull(store.snapshot().tasks.single().commitPayload)
    }

    @Test fun sentPreferenceFailureAfterRoomCommitRetainsExactRecoveryPayload() {
        val disk = MemoryStorage()
        val store = ProactiveTaskStore(disk)
        store.enqueue(task("a")); store.claimNext(host(), now)
        assertTrue(store.beginCommit("a", host(), now, listOf("stable-id"), "same-final-payload"))
        assertNotNull(store.startCommit("a"))
        // Simulate Room commit already succeeded, then the Sent prefs write fails.
        disk.fail = true
        assertTrue(runCatching { store.markCommitSent("a") }.isFailure)
        disk.fail = false
        val restarted = ProactiveTaskStore(disk)
        restarted.interruptRunning()
        assertEquals("same-final-payload", restarted.pendingCommits().single().commitPayload)
        assertEquals(listOf("stable-id"), restarted.pendingCommits().single().commitMessageIds)
        // Recovery verifies those exact Room IDs instead of generating or appending again.
        assertTrue(restarted.markCommitSent("a"))
        assertEquals(ProactiveTaskStatus.Sent, restarted.snapshot().tasks.single().status)
        assertTrue(restarted.pendingCommits().isEmpty())
    }

    @Test fun commitPayloadLimitIsFailClosedAndNoTaskStateTransition() {
        val store = ProactiveTaskStore(MemoryStorage())
        store.enqueue(task("a")); store.claimNext(host(), now)
        assertFalse(store.beginCommit("a", host(), now, listOf("x"),
            "x".repeat(ProactiveTaskStore.PROACTIVE_COMMIT_MAX_BYTES + 1)))
        assertEquals(ProactiveTaskStatus.Running, store.snapshot().tasks.single().status)
    }

    @Test fun publicationIsSingleShotAndRevocationCannotWriteLateOutput() {
        val store = ProactiveTaskStore(MemoryStorage())
        store.enqueue(task("a")); store.claimNext(host(), now)
        var publications = 0
        assertTrue(store.beginCommit("a", host(), now, listOf("id"), "payload").also { if (it) { publications++; store.startCommit("a"); store.markCommitSent("a") } })
        assertFalse(store.beginCommit("a", host(), now, listOf("id"), "payload").also { if (it) { publications++; store.startCommit("a"); store.markCommitSent("a") } })
        assertEquals(1, publications)
        assertEquals(ProactiveTaskStatus.Sent, store.snapshot().tasks.single().status)
        store.enqueue(task("b", "b")); store.claimNext(host(), now)
        store.cancel("b")
        assertFalse(store.beginCommit("b", host(), now, listOf("id-b"), "payload"))
        assertEquals(1, publications)
    }

    @Test fun outstandingFinalizerAndFreshPauseBlockPublication() {
        val store = ProactiveTaskStore(MemoryStorage())
        store.enqueue(task("a")); store.claimNext(host(), now)
        val token = requireNotNull(store.reserveTurn("a", host(), now))
        assertFalse(store.beginCommit("a", host(), now, listOf("id"), "payload"))
        store.markDispatched(token, host(), now)
        store.settleTurn(token)
        val paused = host().let { h -> h.copy(setting = h.setting.copy(
            globalPolicy = h.setting.globalPolicy.copy(paused = true))) }
        assertFalse(store.beginCommit("a", paused, now, listOf("id"), "payload"))
        assertTrue(store.beginCommit("a", host(), now, listOf("id"), "payload"))
    }

    @Test fun assistantQuietHoursCannotBypassGlobalQuietHours() {
        val h = host().let { it.copy(setting = it.setting.copy(assistantConfigs = listOf(
            configs[0].copy(customEnabled = true, activeHoursEnabled = true, activeStartHour = 13, activeEndHour = 14), configs[1],
        ))) }
        assertFalse(proactiveTaskAllowed(task("a"), h, now)) // UTC noon
        assertTrue(proactiveTaskAllowed(task("a").copy(trigger = "manual"), h, now))
        val globalQuiet = h.copy(setting = h.setting.copy(activeHoursEnabled = true,
            activeStartHour = 15, activeEndHour = 16))
        assertFalse(proactiveTaskAllowed(task("b", "b"), globalQuiet, now))
    }

    @Test fun terminalHistoryRetentionDoesNotPermanentlyFillScheduler() {
        val store = ProactiveTaskStore(MemoryStorage())
        repeat(600) { index ->
            assertTrue(store.enqueue(task("task-$index")))
            assertNotNull(store.claimNext(host().let { h -> h.copy(setting = h.setting.copy(
                assistantConfigs = configs.map { it.copy(dailyLimit = 1000) })) }, now))
            assertTrue(store.finish("task-$index", ProactiveTaskStatus.Failed, "pass"))
        }
        assertTrue(store.snapshot().tasks.size <= 257)
    }

    @Test fun corruptOrFailedStorageNeverResetsToFreshBudget() {
        val disk = MemoryStorage()
        val store = ProactiveTaskStore(disk)
        store.enqueue(task("a")); store.claimNext(host(), now)
        disk.fail = true
        assertTrue(runCatching { store.reserveTurn("a", host(), now) }.isFailure)
        disk.fail = false
        assertTrue(runCatching { store.reserveTurn("a", host(), now) }.isFailure)
        disk.value = "invalid json"
        assertTrue(runCatching { ProactiveTaskStore(disk).snapshot() }.isFailure)
    }
}
