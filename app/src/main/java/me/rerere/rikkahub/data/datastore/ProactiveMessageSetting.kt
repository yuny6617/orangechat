/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

@Serializable
data class ProactiveMessageSetting(
    val enabled: Boolean = false,
    // Separate opt-in for AI-created, conversation-scoped appointments.
    val allowAiScheduledWakeup: Boolean = false,
    val minIntervalMinutes: Int = 30,
    val maxIntervalMinutes: Int = 90,
    val activeHoursEnabled: Boolean = true,
    val activeStartHour: Int = 8,
    val activeEndHour: Int = 1,
    val fullToolChancePercent: Int = 20,
    // Separate opt-in for automatic timeline injection, not just on-demand tool access.
    val includeAppTimeline: Boolean = false,
    // 同一条真实用户消息之后，最多允许几次主动追问；用户一回复即清零。
    val maxFollowUpMessages: Int = 2,
    val alwaysRespond: Boolean = false,
    val assistantId: String = "",
    // 是否允许 AI 根据上下文判断后强制跳转屏幕到聊天界面
    val allowForceJump: Boolean = false,
    // 主动消息是否可以按需调用应用使用情况工具；正常聊天不受影响。
    val allowProactiveAppUsage: Boolean = false,
    val jumpIdleThresholdMinutes: Int = 120, // 用户多久没回复(分钟)才允许跳转屏幕，默认2小时
    // 激进模式：每次手机切换应用/开屏锁屏/回桌面都触发AI思考
    val aggressiveModeEnabled: Boolean = false,
    // 激进模式下两次AI思考之间的最小间隔（秒），防抖+限流
    val aggressiveMinIntervalSeconds: Int = 60,
    // 激进模式下，检测到设备事件（切应用/开关屏/回桌面）后，
    // 等待多少秒的防抖时间才真正触发 AI 思考。原来硬编码 30 秒，现在可调节。
    val aggressiveDebounceSeconds: Int = 30,
    // 悬浮球：主动消息到达时以 Telegram 风格悬浮球提醒，点击直接进入聊天页
    // Deprecated compatibility field. The proactive floating bubble is no longer used.
    val floatingBubbleEnabled: Boolean = false,
    // null = pre-migration; empty = explicitly no participants. Never use an empty-list fallback.
    // Migrated once by the dispatcher/settings page; each participant pins one owned window.
    val assistantConfigs: List<AssistantProactiveConfig>? = null,
    val globalPolicy: ProactiveGlobalPolicy = ProactiveGlobalPolicy(),
)

@Serializable
data class AssistantProactiveConfig(
    val assistantId: String,
    val conversationId: String,
    val enabled: Boolean = true,
    // Opt-in for this assistant's own cooldown/daily limit/prompt/active hours. Default false:
    // the global proactive settings are used and the per-assistant values stay dormant.
    val customEnabled: Boolean = false,
    val cooldownMinutes: Int = 30,
    // Number of admitted tasks, including PASS/failed tasks; not the number of model turns.
    val dailyLimit: Int = 4,
    val promptOverride: String? = null,
    val minIntervalMinutes: Int? = null,
    val activeHoursEnabled: Boolean? = null,
    val activeStartHour: Int? = null,
    val activeEndHour: Int? = null,
)

/** Per-assistant overrides only apply when the user explicitly enabled "另设该助手". */
val AssistantProactiveConfig.effectiveCooldownMinutes: Int
    get() = if (customEnabled) cooldownMinutes.coerceAtLeast(0) else 0

val AssistantProactiveConfig.effectiveDailyLimit: Int
    get() = if (customEnabled) dailyLimit.coerceAtLeast(1) else Int.MAX_VALUE

val AssistantProactiveConfig.effectivePromptOverride: String?
    get() = if (customEnabled) promptOverride?.takeIf { it.isNotBlank() } else null

val AssistantProactiveConfig.effectiveActiveHoursEnabled: Boolean
    get() = customEnabled && activeHoursEnabled == true

@Serializable
data class ProactiveGlobalPolicy(
    val paused: Boolean = false,
    // Includes every initial, tool-continuation and finalizer network turn.
    val dailyModelTurnLimit: Int = 40,
    // Written once during migration. Empty/invalid zones fail closed in the new scheduler.
    // Travel does not reset the ledger; use this pinned zone's calendar days (including DST).
    val budgetZoneId: String = "",
)
