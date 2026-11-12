/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.content.Context

internal data class ProactiveDecision(
    val message: String,
    val shouldSend: Boolean,
    val shouldJump: Boolean,
    val waitMinutes: Int? = null,
    val stopUntilUserReturns: Boolean = false,
    val activityNote: String = "",
)

private val proactiveNoteRegex = Regex("(?m)^\\s*\\[NOTE[:：]([^\\r\\n]*?)]\\s*$")

internal fun stripProactiveNotes(text: String): String = text.replace(proactiveNoteRegex, "").trim()

internal fun parseProactiveDecision(rawText: String, jumpDetectedDuringStreaming: Boolean): ProactiveDecision {
    return parseProactiveDecision(
        rawText = rawText,
        reasoningText = "",
        jumpDetectedDuringStreaming = jumpDetectedDuringStreaming,
    )
}

internal fun parseProactiveDecision(
    rawText: String,
    @Suppress("UNUSED_PARAMETER") reasoningText: String,
    jumpDetectedDuringStreaming: Boolean,
    alwaysRespond: Boolean = false,
): ProactiveDecision {
    val note = proactiveNoteRegex.find(rawText)?.groupValues?.get(1)?.trim()?.take(240).orEmpty()
    val decisionText = stripProactiveNotes(rawText)
    if (Regex("(?m)^\\s*\\[NOTE[:：]").containsMatchIn(decisionText)) {
        return ProactiveDecision(message = "", shouldSend = false, shouldJump = false)
    }
    val finalLine = decisionText.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
    val bracketWaitRegex = Regex("^\\s*\\[WAIT(?:\\s*[:：]\\s*(\\d+))?]\\s*[。.!！]?\\s*$", RegexOption.IGNORE_CASE)
    val bareWaitRegex = Regex("^\\s*WAIT(?:\\s*[:：]?\\s*(\\d+))?\\s*[。.!！]?\\s*$", RegexOption.IGNORE_CASE)
    val bracketWaitMatch = bracketWaitRegex.matchEntire(finalLine)
    val bareWaitMatch = bareWaitRegex.matchEntire(finalLine)
    val waitMatch = bracketWaitMatch ?: bareWaitMatch
    val waitMinutes = waitMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(5, 1440)

    val bracketStop = Regex("^\\s*\\[STOP]\\s*[。.!！]?\\s*$", RegexOption.IGNORE_CASE).matches(finalLine)
    val bareStop = Regex("^\\s*STOP\\s*[。.!！]?\\s*$", RegexOption.IGNORE_CASE).matches(finalLine)
    val stop = bracketStop || bareStop

    val bracketPass = Regex("^\\s*\\[PASS]\\s*[。.!！]?\\s*$", RegexOption.IGNORE_CASE).matches(finalLine)
    val barePass = Regex("^\\s*PASS\\s*[。.!！]?\\s*$", RegexOption.IGNORE_CASE).matches(finalLine)
    // Control markers are a protocol for the final answer only. Reasoning is deliberately
    // ignored: a model may consider PASS and then decide to send a real message.
    val pass = bracketPass || barePass
    val jump = jumpDetectedDuringStreaming ||
        Regex("\\[JUMP]", RegexOption.IGNORE_CASE).containsMatchIn(decisionText)

    val cleaned = if (
        bracketStop || bareStop || bracketPass || barePass ||
        bracketWaitMatch != null || bareWaitMatch != null
    ) {
        ""
    } else decisionText
        .replace(Regex("\\[JUMP]", RegexOption.IGNORE_CASE), "")
        .trim()

    val forcedMessage = if (alwaysRespond && cleaned.isBlank()) "我来找你啦，刚刚一直在惦记你。" else cleaned
    return ProactiveDecision(
        message = forcedMessage,
        shouldSend = forcedMessage.isNotBlank() && (alwaysRespond || (!pass && !stop && waitMatch == null)),
        shouldJump = jump,
        waitMinutes = waitMinutes,
        stopUntilUserReturns = stop,
        activityNote = note,
    )
}

internal data class ProactiveSessionState(
    val anchorUserMessageId: String = "",
    val followUpCount: Int = 0,
    val recentProactiveMessageIds: Set<String> = emptySet(),
    val lastProactiveText: String = "",
    val stopUntilUserReturns: Boolean = false,
)

internal fun recordProactiveSentOnce(
    state: ProactiveSessionState,
    messageIds: Collection<String>,
    text: String,
): Pair<ProactiveSessionState, Boolean> = recordProactiveCommitOnce(
    state, state.anchorUserMessageId, messageIds, text,
)

internal fun recordProactiveCommitOnce(
    state: ProactiveSessionState,
    taskAnchorUserMessageId: String,
    messageIds: Collection<String>,
    text: String,
): Pair<ProactiveSessionState, Boolean> {
    val ids = messageIds.filter(String::isNotBlank).toSet()
    if (ids.isEmpty()) return state to false
    val newIds = ids - state.recentProactiveMessageIds
    if (newIds.isEmpty()) return state to false
    val sameAnchor = state.anchorUserMessageId == taskAnchorUserMessageId
    return state.copy(
        followUpCount = state.followUpCount + if (sameAnchor) 1 else 0,
        recentProactiveMessageIds = (state.recentProactiveMessageIds + newIds)
            .toList().takeLast(64).toSet(),
        lastProactiveText = if (sameAnchor) text.take(500) else state.lastProactiveText,
        stopUntilUserReturns = if (sameAnchor) false else state.stopUntilUserReturns,
    ) to true
}

/**
 * Small persistent state machine anchored to the latest real USER message.
 * Assistant proactive messages never reset this anchor.
 */
internal class ProactiveMessageStateStore(context: Context, scope: String) {
    private val prefs = context.getSharedPreferences("proactive_state_$scope", Context.MODE_PRIVATE)
    private val legacyPrefs = context.getSharedPreferences(ProactiveMessageService.PREFS_NAME, Context.MODE_PRIVATE)

    fun synchronizeWithUser(lastUserMessageId: String?): ProactiveSessionState {
        var current = read()
        if (current.anchorUserMessageId.isBlank() && !lastUserMessageId.isNullOrBlank()) {
            val legacy = read(legacyPrefs)
            if (legacy.anchorUserMessageId == lastUserMessageId) {
                current = legacy
                write(current)
            }
        }
        if (!lastUserMessageId.isNullOrBlank() && current.anchorUserMessageId != lastUserMessageId) {
            return ProactiveSessionState(anchorUserMessageId = lastUserMessageId).also(::write)
        }
        return current
    }

    fun recordSent(
        state: ProactiveSessionState,
        messageIds: Collection<String>,
        text: String,
    ): ProactiveSessionState {
        val (updated, changed) = recordProactiveSentOnce(state, messageIds, text)
        if (changed) write(updated)
        return updated
    }

    fun stopUntilUserReturns(state: ProactiveSessionState) {
        write(state.copy(stopUntilUserReturns = true))
    }

    internal fun recordCommittedIds(messageIds: Collection<String>) {
        val ids = messageIds.filter(String::isNotBlank).toSet()
        if (ids.isEmpty()) return
        val current = read()
        if (ids.all { it in current.recentProactiveMessageIds }) return
        write(current.copy(recentProactiveMessageIds = (current.recentProactiveMessageIds + ids)
            .toList().takeLast(MAX_RECENT_PROACTIVE_MESSAGE_IDS).toSet()))
    }

    private fun read(prefs: android.content.SharedPreferences = this.prefs): ProactiveSessionState = ProactiveSessionState(
        anchorUserMessageId = prefs.getString(KEY_ANCHOR_USER_ID, "").orEmpty(),
        followUpCount = prefs.getInt(KEY_FOLLOW_UP_COUNT, 0),
        recentProactiveMessageIds = prefs.getString(KEY_RECENT_PROACTIVE_IDS, "")
            .orEmpty()
            .split(',')
            .filter(String::isNotBlank)
            .toSet(),
        lastProactiveText = prefs.getString(KEY_LAST_PROACTIVE_TEXT, "").orEmpty(),
        stopUntilUserReturns = prefs.getBoolean(KEY_STOP_UNTIL_USER, false),
    )

    private fun write(state: ProactiveSessionState) {
        prefs.edit()
            .putString(KEY_ANCHOR_USER_ID, state.anchorUserMessageId)
            .putInt(KEY_FOLLOW_UP_COUNT, state.followUpCount)
            .putString(KEY_RECENT_PROACTIVE_IDS, state.recentProactiveMessageIds.joinToString(","))
            .putString(KEY_LAST_PROACTIVE_TEXT, state.lastProactiveText)
            .putBoolean(KEY_STOP_UNTIL_USER, state.stopUntilUserReturns)
            .apply()
    }

    private companion object {
        const val MAX_RECENT_PROACTIVE_MESSAGE_IDS = 64
        const val KEY_ANCHOR_USER_ID = "state_anchor_user_id"
        const val KEY_FOLLOW_UP_COUNT = "state_follow_up_count"
        const val KEY_RECENT_PROACTIVE_IDS = "state_recent_proactive_ids"
        const val KEY_LAST_PROACTIVE_TEXT = "state_last_proactive_text"
        const val KEY_STOP_UNTIL_USER = "state_stop_until_user"
    }
}
