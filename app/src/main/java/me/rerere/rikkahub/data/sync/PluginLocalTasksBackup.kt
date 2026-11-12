package me.rerere.rikkahub.data.sync

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.plugin.localdata.PluginBackupArchive
import me.rerere.rikkahub.plugin.localtasks.LocalTaskIds
import me.rerere.rikkahub.plugin.localtasks.LocalTaskJson
import me.rerere.rikkahub.plugin.localtasks.LocalTaskLedgerState
import me.rerere.rikkahub.plugin.localtasks.LocalTaskRecord
import me.rerere.rikkahub.plugin.localtasks.LocalTaskSettings
import me.rerere.rikkahub.plugin.localtasks.LocalTaskSettingsCodec
import me.rerere.rikkahub.plugin.localtasks.LocalTaskState
import me.rerere.rikkahub.plugin.localtasks.LocalTasksApi

/**
 * Host-owned snapshot of the native `localtasks` settings and ledger. It is bundled inside the
 * per-plugin archive, never inside the plugin page's own storage, and it never contains a grant,
 * API key or model credential.
 */
@Serializable
internal data class LocalTasksBackupPayload(
    val format: String = PluginBackupArchive.LOCAL_TASKS_FORMAT,
    val formatVersion: Int = PluginBackupArchive.LOCAL_TASKS_FORMAT_VERSION,
    val settingsVersion: Int = LocalTasksApi.SETTINGS_VERSION,
    val ledgerVersion: Int = LocalTasksApi.LEDGER_VERSION,
    val settings: LocalTaskSettings? = null,
    val ledger: LocalTaskLedgerState? = null,
)

internal object LocalTasksBackupCodec {
    private val json = LocalTaskJson.task
    private val strict = LocalTaskJson.wire

    /**
     * Reads the raw `SharedPreferences` strings into a stopped snapshot: any queued/running task
     * becomes [LocalTaskState.INTERRUPTED] and pending reviews are dropped, so restoring a backup
     * can never replay assistant work.
     */
    fun snapshot(settingsRaw: String?, ledgerRaw: String?, now: Long): LocalTasksBackupPayload? {
        if (settingsRaw.isNullOrBlank() && ledgerRaw.isNullOrBlank()) return null
        val settings = settingsRaw
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                try {
                    strict.decodeFromString(LocalTaskSettings.serializer(), raw).also {
                        if (it.version != LocalTasksApi.SETTINGS_VERSION) {
                            throw PluginDataBackupException("不支持的 localtasks 设置版本：${it.version}")
                        }
                    }.normalized()
                } catch (error: PluginDataBackupException) {
                    throw error
                } catch (error: Exception) {
                    // The settings store falls back to defaults, but a backup must not silently
                    // replace the user's native settings with defaults.
                    throw PluginDataBackupException("localtasks 原生设置损坏，已中止备份", error)
                }
            }
        val ledger = ledgerRaw
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                try {
                    strict.decodeFromString(LocalTaskLedgerState.serializer(), raw).also {
                        if (it.version != LocalTasksApi.LEDGER_VERSION) {
                            throw PluginDataBackupException("不支持的 localtasks 账本版本：${it.version}")
                        }
                    }
                } catch (error: PluginDataBackupException) {
                    throw error
                } catch (error: Exception) {
                    throw PluginDataBackupException("localtasks 任务历史损坏，已中止备份", error)
                }
            }
        if (settings == null && ledger == null) return null
        return LocalTasksBackupPayload(
            settings = settings,
            ledger = ledger?.let { normalizeLedger(it, now) },
        )
    }

    fun encode(payload: LocalTasksBackupPayload): String =
        json.encodeToString(LocalTasksBackupPayload.serializer(), payload)

    fun decode(raw: String): LocalTasksBackupPayload {
        val payload = try {
            json.decodeFromString(LocalTasksBackupPayload.serializer(), raw)
        } catch (error: Exception) {
            throw PluginDataBackupException("localtasks 快照损坏", error)
        }
        if (payload.format != PluginBackupArchive.LOCAL_TASKS_FORMAT ||
            payload.formatVersion != PluginBackupArchive.LOCAL_TASKS_FORMAT_VERSION
        ) {
            throw PluginDataBackupException("不支持的 localtasks 快照版本")
        }
        if (payload.settingsVersion != LocalTasksApi.SETTINGS_VERSION ||
            payload.ledgerVersion != LocalTasksApi.LEDGER_VERSION
        ) {
            throw PluginDataBackupException("不支持的 localtasks schema 版本")
        }
        if (payload.settings != null && payload.settings.version != LocalTasksApi.SETTINGS_VERSION) {
            throw PluginDataBackupException("不支持的 localtasks 设置版本")
        }
        if (payload.ledger != null && payload.ledger.version != LocalTasksApi.LEDGER_VERSION) {
            throw PluginDataBackupException("不支持的 localtasks 账本版本")
        }
        return payload
    }

    /**
     * Narrows the backed-up native settings to assistants that still exist after the restore and
     * re-applies the stopped-task normalization as defence in depth. It never adds a participant.
     */
    fun normalizeForRestore(
        payload: LocalTasksBackupPayload,
        validAssistantIds: Set<String>?,
        now: Long,
    ): LocalTasksBackupPayload {
        val settings = payload.settings?.normalized()?.let { normalized ->
            if (validAssistantIds == null) normalized else normalized.copy(
                participants = normalized.participants.filter { it in validAssistantIds },
                assistantPolicies = normalized.assistantPolicies.filterKeys { it in validAssistantIds },
                assistantReplyLimits = normalized.assistantReplyLimits.filterKeys { it in validAssistantIds },
            )
        }
        return payload.copy(
            settings = settings,
            ledger = payload.ledger?.let { normalizeLedger(it, now) },
        )
    }

    private fun normalizeLedger(state: LocalTaskLedgerState, now: Long): LocalTaskLedgerState =
        state.copy(
            tasks = state.tasks.mapValues { (_, task) ->
                normalizeTask(task, now)
            },
        )

    private fun normalizeTask(task: LocalTaskRecord, now: Long): LocalTaskRecord {
        val terminal = task.state.isTerminal
        return if (terminal && task.pendingReview.isEmpty()) {
            task
        } else {
            task.copy(
                state = if (terminal) task.state else LocalTaskState.INTERRUPTED,
                pendingReview = emptyList(),
                endReason = task.endReason ?: "restored_backup",
                finishedAt = task.finishedAt ?: now,
                updatedAt = now,
            )
        }
    }
}

/**
 * Access to the native localtasks preferences of one plugin. The keys are owned by the host; a
 * plugin page has no path to this class.
 */
internal class LocalTasksNativeStore(
    context: Context,
    private val pluginId: String,
) {
    private val appContext = context.applicationContext
    private val storeName = LocalTaskIds.storeName(pluginId)

    fun exists(): Boolean {
        val dataDir = appContext.applicationInfo.dataDir?.let(::File)
            ?: appContext.filesDir.parentFile
            ?: return false
        return File(File(dataDir, PREFERENCES_DIRECTORY), "$storeName.xml").isFile
    }

    fun snapshot(now: Long): LocalTasksBackupPayload? {
        if (!exists()) return null
        val preferences = appContext.getSharedPreferences(storeName, Context.MODE_PRIVATE)
        return LocalTasksBackupCodec.snapshot(
            settingsRaw = preferences.getString(LocalTasksApi.KEY_SETTINGS, null),
            ledgerRaw = preferences.getString(LocalTasksApi.KEY_LEDGER, null),
            now = now,
        )
    }

    /** Writes the snapshot with a synchronous commit; callers must hold the restore gate. */
    fun apply(payload: LocalTasksBackupPayload, validAssistantIds: Set<String>?) {
        val normalized = LocalTasksBackupCodec.normalizeForRestore(
            payload,
            validAssistantIds,
            System.currentTimeMillis(),
        )
        val editor = appContext.getSharedPreferences(storeName, Context.MODE_PRIVATE).edit()
        normalized.settings?.let {
            editor.putString(LocalTasksApi.KEY_SETTINGS, LocalTaskSettingsCodec.encode(it))
        }
        normalized.ledger?.let {
            editor.putString(
                LocalTasksApi.KEY_LEDGER,
                LocalTaskJson.task.encodeToString(LocalTaskLedgerState.serializer(), it),
            )
        }
        check(editor.commit()) { "无法写入 localtasks 原生设置" }
    }

    private companion object {
        const val PREFERENCES_DIRECTORY = "shared_prefs"
    }
}
