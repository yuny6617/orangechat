/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localtasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTaskImageBudgetTest {
    private var now = 1_700_000_000_000L
    private val ledger = LocalTaskLedger(MemoryLocalTaskStore()) { now }
    private val budget = LedgerLocalTaskImageBudget(ledger)
    private val usageKey = LocalTasksApi.publishUsageKey(now)

    private fun seedTask() {
        ledger.createTask(
            LocalTaskRecord(
                taskId = "lt_image_test",
                mode = LocalTaskMode.PUBLISH,
                assistantId = ASSISTANT_A.id,
                state = LocalTaskState.RUNNING,
                modelCalls = 1,
                createdAt = now,
            ),
        )
    }

    @Test
    fun `image generation shares the publish per-task and daily budget`() {
        seedTask()
        val settings = testSettings(maxCallsPerPost = 3, dailyBudget = 10).copy(allowImages = true)

        assertTrue(budget.tryCharge(usageKey, "lt_image_test", settings))
        assertEquals(1, ledger.modelCallsFor(usageKey))
        assertEquals(2, ledger.task("lt_image_test")?.modelCalls)
        assertEquals(1, ledger.snapshot().modelCallsToday)
    }

    @Test
    fun `per-task cap blocks a second image generation`() {
        seedTask()
        val settings = testSettings(maxCallsPerPost = 1, dailyBudget = 10).copy(allowImages = true)

        assertTrue(budget.tryCharge(usageKey, "lt_image_test", settings))
        assertFalse(budget.tryCharge(usageKey, "lt_image_test", settings))
        assertEquals(1, ledger.modelCallsFor(usageKey))
        assertEquals(2, ledger.task("lt_image_test")?.modelCalls)
    }

    @Test
    fun `daily cap blocks image generation`() {
        seedTask()
        val settings = testSettings(maxCallsPerPost = 5, dailyBudget = 1).copy(allowImages = true)

        assertTrue(budget.tryCharge(usageKey, "lt_image_test", settings))
        assertFalse(budget.tryCharge(usageKey, "lt_image_test", settings))
        assertEquals(1, ledger.snapshot().modelCallsToday)
    }

    @Test
    fun `a failed generation can refund the reservation`() {
        seedTask()
        val settings = testSettings(maxCallsPerPost = 3, dailyBudget = 10).copy(allowImages = true)

        assertTrue(budget.tryCharge(usageKey, "lt_image_test", settings))
        budget.refund(usageKey, "lt_image_test")

        assertEquals(0, ledger.modelCallsFor(usageKey))
        assertEquals(1, ledger.task("lt_image_test")?.modelCalls)
        assertEquals(0, ledger.snapshot().modelCallsToday)
    }

    @Test
    fun `a missing task is not charged`() {
        val settings = testSettings(maxCallsPerPost = 3, dailyBudget = 10).copy(allowImages = true)

        assertFalse(budget.tryCharge(usageKey, "lt_missing", settings))
        assertEquals(0, ledger.modelCallsFor(usageKey))
        assertEquals(0, ledger.snapshot().modelCallsToday)
        assertNull(ledger.task("lt_missing"))
    }

    @Test
    fun `the default budget cannot spend anything`() {
        seedTask()
        val settings = testSettings(maxCallsPerPost = 3, dailyBudget = 10).copy(allowImages = true)

        assertFalse(LocalTaskImageBudget.unavailable().tryCharge(usageKey, "lt_image_test", settings))
        assertEquals(0, ledger.modelCallsFor(usageKey))
    }
}
