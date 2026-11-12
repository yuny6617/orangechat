/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localtasks

/**
 * Budget seam for one assistant-initiated image generation.
 *
 * Image generation is a paid model call, so it must consume the same durable ledger reservation as
 * a text turn. The executor never talks to the ledger directly; [LocalTaskService] supplies an
 * implementation backed by the task's ledger, and a runner without one is simply unable to spend.
 */
interface LocalTaskImageBudget {
    /** Reserves one image-generation call for [usageKey]/[taskId]; false when any cap is hit. */
    fun tryCharge(usageKey: String, taskId: String, settings: LocalTaskSettings): Boolean

    /** Returns the reservation when no provider call was made (start/cancellation failure). */
    fun refund(usageKey: String, taskId: String)

    companion object {
        /** Safe default for executors constructed without a host (tests, previews). */
        fun unavailable(): LocalTaskImageBudget = object : LocalTaskImageBudget {
            override fun tryCharge(
                usageKey: String,
                taskId: String,
                settings: LocalTaskSettings,
            ): Boolean = false

            override fun refund(usageKey: String, taskId: String) = Unit
        }
    }
}

/**
 * Ledger-backed budget: the image call shares the publish turn's per-task and daily model-call
 * caps. A missing task is never charged.
 */
class LedgerLocalTaskImageBudget(
    private val ledger: LocalTaskLedger,
) : LocalTaskImageBudget {
    override fun tryCharge(
        usageKey: String,
        taskId: String,
        settings: LocalTaskSettings,
    ): Boolean {
        if (!ledger.tryChargeModelCall(usageKey, settings)) return false
        val updated = ledger.updateTask(taskId) { it.copy(modelCalls = it.modelCalls + 1) }
        if (updated == null) {
            ledger.refundModelCall(usageKey)
            return false
        }
        return true
    }

    override fun refund(usageKey: String, taskId: String) {
        ledger.refundModelCall(usageKey)
        ledger.updateTask(taskId) { it.copy(modelCalls = (it.modelCalls - 1).coerceAtLeast(0)) }
    }
}
