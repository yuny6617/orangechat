package me.rerere.rikkahub.data.sync

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.plugin.localdata.LocalDataApi
import me.rerere.rikkahub.plugin.localdata.LocalDataCapability
import me.rerere.rikkahub.plugin.localdata.LocalDataGrant
import me.rerere.rikkahub.plugin.localdata.LocalDataPaths
import me.rerere.rikkahub.plugin.localdata.LocalDataSession
import me.rerere.rikkahub.plugin.localdata.LocalDataValidation
import me.rerere.rikkahub.plugin.localdata.LocalRestoreOptions
import me.rerere.rikkahub.plugin.localdata.PluginBackupArchive
import me.rerere.rikkahub.plugin.localdata.PluginBackupContents
import me.rerere.rikkahub.plugin.localdata.PluginBackupManifest
import me.rerere.rikkahub.plugin.manager.PluginLocalDataStorage
import me.rerere.rikkahub.plugin.localtasks.LocalTaskHosts
import me.rerere.rikkahub.plugin.localtasks.LocalTaskIds
import me.rerere.rikkahub.plugin.localtasks.LocalTasksApi

/** A backup failure that must abort the whole backup/restore instead of silently dropping data. */
internal class PluginDataBackupException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal data class PluginBackupFile(
    val pluginId: String,
    val file: File,
    val manifest: PluginBackupManifest,
)

internal data class PluginDataRestoreReport(
    val restoredPlugins: List<String>,
    val localDataRecords: Int,
    val localTasksRestored: Int,
)

internal class StagedPluginData(
    val pluginId: String,
    val localDataZip: File?,
    val localTasks: LocalTasksBackupPayload?,
)

/**
 * Raw localtasks preferences of a plugin. A rollback must put back exactly what was there before
 * the restore, including a settings/ledger pair that the normal restore path would have normalized.
 * The raw strings are only written back unchanged; no credential is part of this store.
 */
internal data class LocalTasksRawSnapshot(
    val settings: String?,
    val ledger: String?,
)

/**
 * Directory that owns the logical rollback snapshots of every in-flight full restore. One attempt
 * lives in one child directory named by its UUID; the journal below is self-describing, so a new
 * process can decide what to do without any in-memory state.
 */
internal const val PLUGIN_RESTORE_ROLLBACK_DIRECTORY = ".plugin_restore_rollback"

private const val PLUGIN_RESTORE_JOURNAL_VERSION = 1
private const val PLUGIN_RESTORE_JOURNAL_FILE = "journal.json"
private const val PLUGIN_RESTORE_PHASE_PREPARING = "preparing"
private const val PLUGIN_RESTORE_PHASE_APPLYING = "applying"
private const val PLUGIN_RESTORE_PHASE_AWAITING_DATABASE = "awaitingDatabase"
private const val PLUGIN_RESTORE_PHASE_COMMITTED = "committed"
private const val PLUGIN_RESTORE_DATABASE_PENDING = "database-pending"
private const val PLUGIN_RESTORE_DECISION_COMMIT = "decision-commit"
private const val PLUGIN_RESTORE_DECISION_ROLLBACK = "decision-rollback"
private const val PLUGIN_RESTORE_LOCAL_TASKS_SETTINGS = "localtasks-settings.txt"
private const val PLUGIN_RESTORE_LOCAL_TASKS_LEDGER = "localtasks-ledger.txt"
private const val PLUGIN_RESTORE_LOCAL_DATA = "localdata.zip"
private const val PLUGIN_RESTORE_PLUGIN_ID = "plugin_id.txt"

/**
 * Phase of one attempt, derived from durable marker files. `preparing` means no live plugin data
 * has been touched yet (the snapshots are still being captured), `applying` means live namespaces
 * may be half-replaced, `awaitingDatabase` means the plugin data was applied and the files/settings
 * transaction is complete but a database restore (if any) only activates on the next launch, and
 * `committed` means the attempt is entirely finished.
 */
private enum class PluginRestorePhase { PREPARING, APPLYING, AWAITING_DATABASE, COMMITTED }

/** Durably recorded startup verdict for an `awaitingDatabase` attempt. */
private enum class PluginRestoreDecision { COMMIT, ROLLBACK }

@Serializable
internal data class PluginRestoreJournalEntry(
    val pluginId: String,
    val namespace: String,
    val localDataExisted: Boolean,
    val localDataRestoreAttempted: Boolean,
)

/** Written only after every rollback snapshot exists; `plugins` is then complete and stable. */
@Serializable
internal data class PluginRestoreJournal(
    val version: Int = PLUGIN_RESTORE_JOURNAL_VERSION,
    val attemptId: String,
    val plugins: List<PluginRestoreJournalEntry> = emptyList(),
)

/**
 * Tiny durable store for the restore attempt. Phase and decision are separate marker files rather
 * than one rewritten JSON blob: every transition is then a single create+fsync, and an interrupted
 * write can never destroy the previous, still-valid description.
 */
private object PluginRestoreJournalStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun phase(directory: File): PluginRestorePhase? = when {
        File(directory, PLUGIN_RESTORE_PHASE_COMMITTED).isFile -> PluginRestorePhase.COMMITTED
        File(directory, PLUGIN_RESTORE_PHASE_AWAITING_DATABASE).isFile ->
            PluginRestorePhase.AWAITING_DATABASE
        File(directory, PLUGIN_RESTORE_PHASE_APPLYING).isFile -> PluginRestorePhase.APPLYING
        File(directory, PLUGIN_RESTORE_PHASE_PREPARING).isFile -> PluginRestorePhase.PREPARING
        else -> null
    }

    fun databasePending(directory: File): Boolean = File(directory, PLUGIN_RESTORE_DATABASE_PENDING).isFile

    fun decision(directory: File): PluginRestoreDecision? = when {
        File(directory, PLUGIN_RESTORE_DECISION_ROLLBACK).isFile -> PluginRestoreDecision.ROLLBACK
        File(directory, PLUGIN_RESTORE_DECISION_COMMIT).isFile -> PluginRestoreDecision.COMMIT
        else -> null
    }

    fun writeDecision(directory: File, committed: Boolean) {
        writeMarker(
            directory,
            if (committed) PLUGIN_RESTORE_DECISION_COMMIT else PLUGIN_RESTORE_DECISION_ROLLBACK,
        )
    }

    fun writeJournal(directory: File, journal: PluginRestoreJournal) {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建插件恢复日志目录" }
        val temporary = File(directory, ".$PLUGIN_RESTORE_JOURNAL_FILE.tmp-${UUID.randomUUID()}")
        FileOutputStream(temporary).use { output ->
            output.write(
                json.encodeToString(PluginRestoreJournal.serializer(), journal).toByteArray(Charsets.UTF_8),
            )
            output.fd.sync()
        }
        val target = File(directory, PLUGIN_RESTORE_JOURNAL_FILE)
        if (target.exists()) check(target.delete()) { "无法替换插件恢复日志" }
        check(temporary.renameTo(target)) { "无法写入插件恢复日志" }
    }

    fun readJournal(directory: File): PluginRestoreJournal {
        val file = File(directory, PLUGIN_RESTORE_JOURNAL_FILE)
        check(file.isFile) { "插件恢复日志缺失：$directory" }
        val journal = json.decodeFromString(PluginRestoreJournal.serializer(), file.readText())
        check(journal.version == PLUGIN_RESTORE_JOURNAL_VERSION) {
            "不支持的插件恢复日志版本：${journal.version}"
        }
        return journal
    }

    /** Create+fsync a marker. The caller only relies on its presence, never on its content. */
    fun writeMarker(directory: File, name: String) {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建插件恢复日志目录" }
        FileOutputStream(File(directory, name)).use { output ->
            output.write(name.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }
}

/** One target namespace's pre-apply state, kept outside the live database until the commit ends. */
internal class PluginRollbackSnapshot(
    val pluginId: String,
    val localDataExisted: Boolean,
    val localDataRestoreAttempted: Boolean,
    val localDataArchive: File?,
    val localTasks: LocalTasksRawSnapshot,
)

/**
 * Reads the raw preferences after the caller has released the task host. `stopAll` has therefore
 * already lowered queued/running turns to a terminal state, so putting these strings back can never
 * resurrect live work; a task record left non-terminal by a previous process is still moved to
 * interrupted by `LocalTaskService` on its next start.
 */
private fun readLocalTasksRaw(context: Context, pluginId: String): LocalTasksRawSnapshot {
    val preferences = context.getSharedPreferences(
        LocalTaskIds.storeName(pluginId),
        Context.MODE_PRIVATE,
    )
    return LocalTasksRawSnapshot(
        settings = preferences.getString(LocalTasksApi.KEY_SETTINGS, null),
        ledger = preferences.getString(LocalTasksApi.KEY_LEDGER, null),
    )
}

/** Writes a small rollback file and waits for the kernel to accept its bytes. */
private fun writeFileDurably(file: File, value: String) {
    FileOutputStream(file).use { output ->
        output.write(value.toByteArray(Charsets.UTF_8))
        output.fd.sync()
    }
}

/** Flushes a file produced by another writer (the logical localdata archive). */
private fun syncFileDurably(file: File) {
    FileOutputStream(file, true).use { output -> output.fd.sync() }
}

/**
 * Reverse-order rollback for the logical plugin data of one restore.
 *
 * The snapshots are created before the first live namespace is opened for writing. Until the
 * surrounding file/settings transaction commits, the coordinator keeps this session alive so a
 * failure in `transaction.finish` can still restore every namespace this restore touched. The same
 * disk state is enough for a later process to finish or roll back the attempt on startup.
 */
internal class PluginRollbackSession(
    private val context: Context,
    val root: File,
    private val releaseTasks: (String) -> Unit,
) {
    private val snapshots = linkedMapOf<String, PluginRollbackSnapshot>()
    private val attempted = mutableListOf<String>()

    /** Journal exists but no live plugin namespace may have been touched yet. */
    fun beginPreparing() {
        PluginRestoreJournalStore.writeMarker(root, PLUGIN_RESTORE_PHASE_PREPARING)
    }

    fun add(snapshot: PluginRollbackSnapshot) {
        snapshots[snapshot.pluginId] = snapshot
    }

    /**
     * Publishes the completed snapshot list and only then marks the attempt as `applying`. The
     * journal is written after every snapshot exists, so a startup rollback never reads a partial
     * description; a crash before this point is cleaned up without touching live data.
     */
    fun beginApplying() {
        PluginRestoreJournalStore.writeJournal(
            root,
            PluginRestoreJournal(
                attemptId = root.name,
                plugins = snapshots.values.map { snapshot ->
                    PluginRestoreJournalEntry(
                        pluginId = snapshot.pluginId,
                        namespace = LocalDataPaths(snapshot.pluginId, context.filesDir).namespace,
                        localDataExisted = snapshot.localDataExisted,
                        localDataRestoreAttempted = snapshot.localDataRestoreAttempted,
                    )
                },
            ),
        )
        PluginRestoreJournalStore.writeMarker(root, PLUGIN_RESTORE_PHASE_APPLYING)
    }

    fun markAttempted(pluginId: String) {
        if (pluginId !in attempted) attempted += pluginId
    }

    fun attemptedPluginIds(): List<String> = attempted.toList()

    /**
     * Plugin data was fully applied. `databasePending` records whether a deferred database
     * activation still has to succeed on the next launch before this attempt can be retired.
     */
    fun markAwaitingDatabase(databasePending: Boolean) {
        if (databasePending) {
            PluginRestoreJournalStore.writeMarker(root, PLUGIN_RESTORE_DATABASE_PENDING)
        }
        PluginRestoreJournalStore.writeMarker(root, PLUGIN_RESTORE_PHASE_AWAITING_DATABASE)
    }

    fun discardSnapshots() {
        // Cleanup failure must not turn a completed restore into a failure; a stale directory only
        // contains logical snapshots of data that is already live again (or a committed attempt that
        // the next launch retires).
        runCatching { root.deleteRecursively() }
    }

    /** Marks the attempt committed before retiring it, so an interrupted cleanup stays harmless. */
    fun commitAndDiscard() {
        if (root.isDirectory) {
            runCatching { PluginRestoreJournalStore.writeMarker(root, PLUGIN_RESTORE_PHASE_COMMITTED) }
        }
        discardSnapshots()
    }

    /** Restores attempted namespaces in reverse order. Returns the first failure or null. */
    fun rollbackAttempted(): Throwable? {
        var failure: Throwable? = null
        attempted.asReversed().forEach { pluginId ->
            val snapshot = snapshots[pluginId] ?: return@forEach
            try {
                restore(snapshot)
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        if (failure == null) discardSnapshots()
        return failure
    }

    private fun restore(snapshot: PluginRollbackSnapshot) {
        runCatching { releaseTasks(snapshot.pluginId) }
        var failure: Throwable? = null
        try {
            try {
                if (snapshot.localDataExisted) {
                    val archive = requireNotNull(snapshot.localDataArchive) {
                        "缺少插件 ${snapshot.pluginId} 的回滚快照"
                    }
                    // Original ids are restored verbatim: a rollback is not a fresh restore, so the
                    // plugin's posts/comments/media must keep the ids they had before this attempt.
                    LocalDataSession(
                        context,
                        snapshot.pluginId,
                        LocalDataGrant.of(LocalDataCapability.BACKUP),
                    ).use { localData ->
                        localData.restoreBackup(
                            archive,
                            LocalRestoreOptions(replaceExisting = true, regenerateIds = false),
                        )
                    }
                } else if (snapshot.localDataRestoreAttempted) {
                    // The namespace did not exist before this attempt and the backup did contain
                    // localdata, so apply created it. Delete only this plugin's strictly validated
                    // directory/database instead of leaving an empty namespace behind. No UI or
                    // plugin manager state is touched.
                    val deletion = PluginLocalDataStorage(context).delete(snapshot.pluginId)
                    check(deletion.complete) {
                        "无法删除插件 ${snapshot.pluginId} 本次新建的本地数据命名空间"
                    }
                }
            } catch (error: Throwable) {
                failure = error
            }
            // Restore preferences even when the namespace restore failed: both parts must be
            // attempted so a later retry cannot silently keep the backup's task settings.
            try {
                restoreLocalTasks(snapshot.pluginId, snapshot.localTasks)
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        } finally {
            runCatching { releaseTasks(snapshot.pluginId) }
        }
        failure?.let { throw it }
    }

    private fun restoreLocalTasks(pluginId: String, snapshot: LocalTasksRawSnapshot) {
        val preferences = context.getSharedPreferences(
            LocalTaskIds.storeName(pluginId),
            Context.MODE_PRIVATE,
        )
        val currentSettings = preferences.getString(LocalTasksApi.KEY_SETTINGS, null)
        val currentLedger = preferences.getString(LocalTasksApi.KEY_LEDGER, null)
        if (currentSettings == snapshot.settings && currentLedger == snapshot.ledger) return
        val editor = preferences.edit()
        if (snapshot.settings == null) {
            editor.remove(LocalTasksApi.KEY_SETTINGS)
        } else {
            editor.putString(LocalTasksApi.KEY_SETTINGS, snapshot.settings)
        }
        if (snapshot.ledger == null) {
            editor.remove(LocalTasksApi.KEY_LEDGER)
        } else {
            editor.putString(LocalTasksApi.KEY_LEDGER, snapshot.ledger)
        }
        check(editor.commit()) { "无法恢复插件 $pluginId 的 localtasks 原始设置" }
    }

    companion object {
        /**
         * Rebuilds the rollback session from the on-disk journal of a crashed process. Every listed
         * plugin is treated as attempted: restoring a namespace that had not actually been replaced
         * yet writes back its own pre-restore snapshot, which is a no-op.
         */
        fun fromJournal(
            context: Context,
            root: File,
            journal: PluginRestoreJournal,
            releaseTasks: (String) -> Unit,
        ): PluginRollbackSession {
            val session = PluginRollbackSession(context, root, releaseTasks)
            journal.plugins.forEach { entry ->
                require(LocalDataValidation.PLUGIN_ID_PATTERN.matches(entry.pluginId)) {
                    "插件恢复日志包含非法插件 ID：${entry.pluginId}"
                }
                val namespace = LocalDataPaths(entry.pluginId, context.filesDir).namespace
                require(namespace == entry.namespace) { "插件恢复日志的命名空间不匹配：${entry.pluginId}" }
                val directory = File(root, entry.namespace)
                require(directory.isDirectory) { "插件恢复快照目录缺失：${entry.pluginId}" }
                val archive = if (entry.localDataExisted) {
                    File(directory, PLUGIN_RESTORE_LOCAL_DATA).also { candidate ->
                        require(candidate.isFile && candidate.length() > 0) {
                            "插件 ${entry.pluginId} 的回滚快照为空"
                        }
                    }
                } else {
                    null
                }
                session.add(
                    PluginRollbackSnapshot(
                        pluginId = entry.pluginId,
                        localDataExisted = entry.localDataExisted,
                        localDataRestoreAttempted = entry.localDataRestoreAttempted,
                        localDataArchive = archive,
                        localTasks = LocalTasksRawSnapshot(
                            settings = File(directory, PLUGIN_RESTORE_LOCAL_TASKS_SETTINGS)
                                .takeIf { it.isFile }?.readText(),
                            ledger = File(directory, PLUGIN_RESTORE_LOCAL_TASKS_LEDGER)
                                .takeIf { it.isFile }?.readText(),
                        ),
                    ),
                )
                session.markAttempted(entry.pluginId)
            }
            return session
        }
    }
}

/**
 * Process-wide hand-off between [PluginDataRestorePlan] and [commitBackupRestore].
 *
 * A successful plugin apply keeps its rollback snapshots until the file/settings transaction has
 * finished; a failure while finishing the transaction can then still restore the plugin data. When
 * the restore also replaces the database, the snapshots stay on disk after the in-process commit
 * and are only retired once the deferred database activation succeeds on the next launch. The
 * restore path is already serialized by [BackupArchiveRestorer.restoreMutex], so one active session
 * is enough.
 */
internal object PluginRestoreRollbackScope {
    @Volatile
    private var active: PluginRollbackSession? = null
    @Volatile
    private var commitWindow = false
    @Volatile
    private var databasePending = false

    /**
     * Marks the span in which [commitBackupRestore] can still finish (and therefore roll back).
     * [awaitingDatabase] is true when the staged database only activates on the next launch.
     */
    fun beginCommitWindow(awaitingDatabase: Boolean) {
        commitWindow = true
        databasePending = awaitingDatabase
    }

    fun endCommitWindow() {
        commitWindow = false
        databasePending = false
    }

    /** Read by [PluginDataRestorePlan.apply] when it publishes the journal phase. */
    fun awaitsDatabaseActivation(): Boolean = databasePending

    fun activate(session: PluginRollbackSession) {
        val previous = active
        active = session
        // A directly-invoked plan (tests, future callers) must not keep snapshots forever.
        previous?.discardSnapshots()
    }

    /**
     * Keeps the snapshots only while the surrounding transaction can still fail. A plan invoked on
     * its own has nobody to hand the snapshots to, so it commits and cleans them up immediately.
     */
    fun releaseAfterApply(session: PluginRollbackSession) {
        if (commitWindow) return
        clear(session)
        session.commitAndDiscard()
    }

    fun clear(session: PluginRollbackSession) {
        if (active === session) active = null
    }

    fun commitActive() {
        val session = active ?: return
        active = null
        session.commitAndDiscard()
    }

    /**
     * The in-process transaction is complete, but the restored database still has to be installed by
     * the next launch. Relinquish the session without deleting the durable journal; startup owns it.
     */
    fun handOffToStartup() {
        active = null
    }

    fun rollbackActive(): Throwable? {
        val session = active ?: return null
        active = null
        return session.rollbackAttempted()
    }
}

/**
 * Consumes the durable rollback journal on the next launch, in the same order as the file/settings
 * transaction: the database is activated (or the files/settings are rolled back) first, then the
 * plugin namespaces and their native localtasks state are committed or restored accordingly.
 *
 * Nothing is left for a human to clean up manually: every attempt directory is either retired after
 * a verified commit/rollback or reported as a startup failure while its snapshots stay available
 * for the next launch. Restoring native task state never replays assistant work: the raw
 * preferences are written back exactly as captured after the task hosts were stopped.
 */
internal object PluginRestoreStartupRecovery {
    fun rollbackRoot(filesDir: File): File =
        File(filesDir, PLUGIN_RESTORE_ROLLBACK_DIRECTORY)

    fun hasPending(filesDir: File): Boolean = attemptDirectories(filesDir).isNotEmpty()

    private fun attemptDirectories(filesDir: File): List<File> =
        rollbackRoot(filesDir).listFiles { file -> file.isDirectory }
            ?.sortedBy { it.name }
            ?: emptyList()

    /**
     * Durably records the file/database verdict before [BackupRestoreTransaction] retires its own
     * journal. If the process dies before the plugin recovery below runs, the next launch still
     * knows whether the attempt must be committed or rolled back.
     */
    fun recordStartupDecision(filesDir: File, committed: Boolean): Throwable? {
        var failure: Throwable? = null
        attemptDirectories(filesDir).forEach { directory ->
            try {
                if (PluginRestoreJournalStore.phase(directory) == PluginRestorePhase.AWAITING_DATABASE &&
                    PluginRestoreJournalStore.decision(directory) == null
                ) {
                    PluginRestoreJournalStore.writeDecision(directory, committed)
                }
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        return failure
    }

    /**
     * Applies the outcome of the file/settings/database startup recovery to every pending plugin
     * attempt. Returns the first failure (snapshots are kept for the next launch) or null.
     */
    fun recover(
        context: Context,
        filesDir: File,
        outcome: BackupRestoreStartupOutcome,
        databaseRestoreFailure: Throwable? = null,
        releaseTasks: (String) -> Unit = ::releasePluginTaskHosts,
    ): Throwable? {
        var failure: Throwable? = null
        attemptDirectories(filesDir).forEach { directory ->
            try {
                recoverAttempt(
                    context = context,
                    directory = directory,
                    outcome = outcome,
                    databaseRestoreFailure = databaseRestoreFailure,
                    releaseTasks = releaseTasks,
                )
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        return failure
    }

    private fun recoverAttempt(
        context: Context,
        directory: File,
        outcome: BackupRestoreStartupOutcome,
        databaseRestoreFailure: Throwable?,
        releaseTasks: (String) -> Unit,
    ) {
        when (PluginRestoreJournalStore.phase(directory)) {
            null, PluginRestorePhase.PREPARING, PluginRestorePhase.COMMITTED -> {
                // No live plugin namespace may have changed: preparation had not finished, or a
                // committed attempt only needs its directory retired.
                check(directory.deleteRecursively()) { "无法清理插件恢复日志：$directory" }
            }

            PluginRestorePhase.APPLYING -> rollback(context, directory, releaseTasks)

            PluginRestorePhase.AWAITING_DATABASE -> {
                val recorded = PluginRestoreJournalStore.decision(directory)
                val rollbackRequired = recorded?.let { it == PluginRestoreDecision.ROLLBACK }
                    ?: when {
                        // The database/files transaction restored the previous state.
                        outcome == BackupRestoreStartupOutcome.ROLLED_BACK -> true
                        // The main files/settings/database transaction committed.
                        outcome == BackupRestoreStartupOutcome.COMMITTED -> false
                        // A deferred database activation failed on this launch: the plugin payload
                        // must not become live without the database it was restored with.
                        databaseRestoreFailure != null &&
                            PluginRestoreJournalStore.databasePending(directory) -> true
                        // No pending database (or it was already activated by an earlier launch).
                        else -> false
                    }
                if (rollbackRequired) {
                    rollback(context, directory, releaseTasks)
                } else {
                    commit(directory)
                }
            }
        }
    }

    private fun rollback(context: Context, directory: File, releaseTasks: (String) -> Unit) {
        val journal = PluginRestoreJournalStore.readJournal(directory)
        val session = PluginRollbackSession.fromJournal(context, directory, journal, releaseTasks)
        session.rollbackAttempted()?.let { failure ->
            throw PluginDataBackupException("插件数据回滚未完成，已保留回滚快照：$directory", failure)
        }
    }

    private fun commit(directory: File) {
        PluginRestoreJournalStore.writeMarker(directory, PLUGIN_RESTORE_PHASE_COMMITTED)
        directory.deleteRecursively()
    }
}

/**
 * Creates one versioned logical snapshot per plugin and applies a previously staged one.
 *
 * Export uses [LocalDataSession.exportBackup] (a logical payload, not a copy of the hot SQLite
 * file). Restore validation uses a sandbox [Context] with a private files/database directory, so a
 * broken archive is rejected before the live namespace is opened for writing.
 */
internal class PluginDataBackupCoordinator(
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val releaseTasks: (String) -> Unit = ::releasePluginTaskHosts,
) {
    private val appContext = context.applicationContext

    /**
     * Snapshots the requested plugins into [destination]. A plugin with neither localdata nor
     * native localtasks state is skipped. Any failure aborts the caller's backup.
     */
    fun snapshot(destination: File, pluginIds: Collection<String>): List<PluginBackupFile> {
        require(destination.isDirectory || destination.mkdirs()) { "无法创建插件备份目录" }
        val result = mutableListOf<PluginBackupFile>()
        (pluginIds + recoverPreservedPluginIds())
            .filter { LocalDataValidation.PLUGIN_ID_PATTERN.matches(it) && it != "." && it != ".." }
            .distinct()
            .sorted()
            .forEach { pluginId ->
                releaseTasks(pluginId)
                val nativeTasks = LocalTasksNativeStore(appContext, pluginId)
                val localTasks = nativeTasks.snapshot(clock())
                val hasLocalData = hasLocalData(pluginId)
                if (!hasLocalData && localTasks == null) return@forEach
                val temporary = File(destination, ".tmp-${pluginId}-${UUID.randomUUID()}")
                check(temporary.mkdirs()) { "无法创建插件备份暂存目录" }
                try {
                    val localDataZip = if (hasLocalData) {
                        exportLocalData(pluginId, temporary)
                    } else {
                        null
                    }
                    val archive = File(destination, "$pluginId.zip")
                    val manifest = PluginBackupArchive.write(
                        destination = archive,
                        pluginId = pluginId,
                        pluginVersion = pluginVersion(pluginId),
                        localDataZip = localDataZip,
                        localTasksJson = localTasks?.let { snapshot -> LocalTasksBackupCodec.encode(snapshot) },
                        createdAt = clock(),
                        localDataSchemaVersion = LocalDataApi.DB_SCHEMA_VERSION,
                        localTasksSchemaVersion = PluginBackupArchive.LOCAL_TASKS_SCHEMA_VERSION,
                    )
                    result += PluginBackupFile(pluginId, archive, manifest)
                } catch (error: PluginDataBackupException) {
                    throw error
                } catch (error: Exception) {
                    throw PluginDataBackupException(
                        "插件 $pluginId 的本地数据快照失败，已中止本次备份（不会生成缺少朋友圈数据的备份）",
                        error,
                    )
                } finally {
                    temporary.deleteRecursively()
                }
            }
        return result
    }

    /**
     * Fully validates every archive into [workRoot] without touching live plugin data. The caller
     * must treat any exception as "no changes were made" and discard its staging journal.
     */
    fun stage(
        backups: List<Pair<String, File>>,
        workRoot: File,
    ): PluginDataRestorePlan {
        require(workRoot.isDirectory || workRoot.mkdirs()) { "无法创建插件恢复暂存目录" }
        require(backups.map { it.first }.toSet().size == backups.size) { "插件备份条目重复" }
        val staged = mutableListOf<StagedPluginData>()
        backups.forEach { (entryPluginId, archive) ->
            val work = File(workRoot, "w-${UUID.randomUUID().toString().take(8)}")
            check(work.mkdirs()) { "无法创建插件恢复暂存目录" }
            val contents: PluginBackupContents
            try {
                contents = PluginBackupArchive.inspect(archive, work)
            } catch (error: Exception) {
                throw PluginDataBackupException("插件备份 $entryPluginId 校验失败，现有数据未修改", error)
            }
            if (contents.manifest.pluginId != entryPluginId) {
                throw PluginDataBackupException(
                    "插件备份 ID 不匹配：条目为 $entryPluginId，清单为 ${contents.manifest.pluginId}",
                )
            }
            val localTasks = contents.localTasksJson?.let { raw ->
                LocalTasksBackupCodec.decode(raw)
            }
            if (contents.localDataZip == null && localTasks == null) {
                throw PluginDataBackupException("插件备份 $entryPluginId 没有任何可恢复数据")
            }
            contents.localDataZip?.let { localDataZip ->
                validateLocalData(entryPluginId, localDataZip)
            }
            staged += StagedPluginData(entryPluginId, contents.localDataZip, localTasks)
        }
        return PluginDataRestorePlan(appContext, staged, releaseTasks)
    }

    private fun exportLocalData(pluginId: String, directory: File): File {
        val target = File(directory, PluginBackupArchive.LOCAL_DATA_ENTRY)
        LocalDataSession(appContext, pluginId, LocalDataGrant.of(LocalDataCapability.BACKUP)).use { session ->
            // The logical reader touches several tables; one transaction keeps the payload
            // internally consistent if a plugin page writes while the backup runs.
            session.database.transaction {
                session.exportBackup(target, clock())
            }
        }
        check(target.isFile && target.length() > 0) { "朋友圈数据快照为空" }
        return target
    }

    /**
     * Validates one staged archive inside a throwaway sandbox that never touches live plugin data.
     *
     * The sandbox layout is intentionally flat and short, and it is a correctness constraint rather
     * than cosmetics: native Windows SQLite refuses to create `<database>-journal` once that path
     * reaches `MAX_PATH` (a 252-character database path already fails, 251 is still accepted). The
     * hosting test run names its temporary directories after the test class and method, so a nested
     * `…/w-…/sandbox-…/databases/<48-char db>` layout overflowed that limit even when the live
     * plugin database of the very same test opened fine. Placing the sandbox database directly in
     * the sandbox root keeps its path one character *shorter* than the live one
     * (`<data>/databases/<db>`), so validating a backup can never fail for a path-length reason the
     * real restore would not also hit. See `LOCALDATA_TEST_HOST_DIAGNOSIS.md`.
     */
    private fun validateLocalData(pluginId: String, archive: File) {
        val sandbox = File(appDataDirectory(appContext), "ld${UUID.randomUUID().toString().take(6)}")
        // A sandbox left behind by a killed process must never be reused: it already contains the
        // previous attempt's restored data and would make this archive look valid.
        sandbox.deleteRecursively()
        check(sandbox.mkdirs()) { "无法创建插件校验沙箱" }
        val files = File(sandbox, "f")
        check(files.mkdirs()) { "无法创建插件校验沙箱" }
        try {
            val sandboxContext = SandboxContext(appContext, files = files, root = sandbox)
            LocalDataSession(
                sandboxContext,
                pluginId,
                LocalDataGrant.of(LocalDataCapability.BACKUP),
            ).use { session ->
                session.restoreBackup(
                    archive,
                    LocalRestoreOptions(replaceExisting = true, regenerateIds = true),
                )
            }
        } catch (error: Exception) {
            throw PluginDataBackupException(
                "插件 $pluginId 的朋友圈备份校验失败，现有数据未修改",
                error,
            )
        } finally {
            sandbox.deleteRecursively()
        }
    }

    /**
     * Plugins uninstalled with "preserve local data" no longer have a plugin directory, but their
     * logical database still exists. Recover the plugin id from the database meta row so a full
     * backup cannot silently omit preserved moments data.
     */
    private fun recoverPreservedPluginIds(): List<String> {
        val directory = appContext.getDatabasePath("localdata_v1_probe.db").parentFile ?: return emptyList()
        val databases = directory.listFiles { file ->
            file.isFile && file.name.startsWith(LOCAL_DATA_DATABASE_PREFIX) && file.name.endsWith(".db")
        } ?: return emptyList()
        return databases.mapNotNull { file ->
            val namespace = file.name.removePrefix(LOCAL_DATA_DATABASE_PREFIX).removeSuffix(".db")
            if (!LOCAL_DATA_NAMESPACE.matches(namespace)) return@mapNotNull null
            val pluginId = runCatching {
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    file.path,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                ).use { database ->
                    database.rawQuery("SELECT value FROM meta WHERE key = 'plugin_id'", null).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }
            }.getOrNull() ?: return@mapNotNull null
            if (!LocalDataValidation.PLUGIN_ID_PATTERN.matches(pluginId)) return@mapNotNull null
            val expected = runCatching { LocalDataPaths(pluginId, appContext.filesDir) }.getOrNull()
                ?: return@mapNotNull null
            pluginId.takeIf { expected.namespace == namespace }
        }
    }

    private fun hasLocalData(pluginId: String): Boolean {
        val paths = runCatching { LocalDataPaths(pluginId, appContext.filesDir) }.getOrNull() ?: return false
        val database = appContext.getDatabasePath(paths.databaseName)
        return paths.root.exists() ||
            database.exists() ||
            File(database.path + "-wal").exists() ||
            File(database.path + "-journal").exists()
    }

    private fun pluginVersion(pluginId: String): String? = runCatching {
        val manifest = File(File(appContext.filesDir, "plugins/$pluginId"), "manifest.json")
        if (!manifest.isFile || manifest.length() > MAX_PLUGIN_MANIFEST_BYTES) return@runCatching null
        Json.parseToJsonElement(manifest.readText())
            .jsonObject["version"]
            ?.jsonPrimitive
            ?.content
            ?.take(PluginBackupArchive.MAX_PLUGIN_VERSION_CHARS)
    }.getOrNull()

    /**
     * Private app data directory, used only to host the short-lived validation sandbox right below
     * it. Every level below it (`cache`, `files`, `databases`) would add characters to the sandbox
     * database path, which must stay at or under the live plugin database path.
     */
    private fun appDataDirectory(context: Context): File {
        val dataDir = context.applicationInfo.dataDir
        if (!dataDir.isNullOrBlank()) return File(dataDir)
        return context.filesDir.parentFile ?: context.cacheDir
    }

    /**
     * A private view of the app directories used to validate a staged archive.
     *
     * `ContextWrapper` would otherwise delegate `applicationContext`, `openOrCreateDatabase(...)`
     * and `deleteDatabase(...)` to the live app context, so a "sandboxed" restore validation could
     * still create (or delete) a real plugin database next to live data. Every path-producing entry
     * point the validated restore can reach is therefore redirected into [root]; only the read-only
     * parts of the base context (resources, content resolver, preferences) are still shared, and the
     * localdata restore code does not write any of those.
     */
    private class SandboxContext(
        base: Context,
        private val files: File,
        private val root: File,
    ) : ContextWrapper(base) {
        /** Keep `context.applicationContext` inside the sandbox; localdata uses it for paths. */
        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = files

        override fun getCacheDir(): File = files

        override fun getNoBackupFilesDir(): File = files

        override fun getDir(name: String, mode: Int): File =
            File(files, "app_${File(name).name}").apply { mkdirs() }

        override fun getDatabasePath(name: String): File = File(root, File(name).name)

        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?,
        ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)

        @Suppress("DEPRECATION")
        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?,
        ): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)

        override fun deleteDatabase(name: String): Boolean =
            SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    }

    private companion object {
        const val MAX_PLUGIN_MANIFEST_BYTES = 256L * 1024
        const val LOCAL_DATA_DATABASE_PREFIX = "localdata_v1_"
        val LOCAL_DATA_NAMESPACE = Regex("^[0-9a-f]{32}$")
    }
}

internal class PluginDataRestorePlan(
    private val context: Context,
    private val staged: List<StagedPluginData>,
    private val releaseTasks: (String) -> Unit,
) {
    private val appContext = context.applicationContext

    /**
     * Applies the already-validated snapshots.
     *
     * Before the first live namespace is opened for writing, every target namespace is exported to
     * a private logical rollback snapshot and its raw localtasks settings/ledger are captured. Each
     * plugin replacement remains atomic inside localdata (`replaceAll` is one SQLite transaction),
     * but a failure in any later plugin now restores all namespaces already modified by this attempt
     * in reverse order. A namespace that did not exist before is deleted rather than left behind.
     *
     * When invoked by [commitBackupRestore], the snapshots are retained until
     * [PluginRestoreRollbackScope.commitActive] runs after the file/settings transaction finishes,
     * so a failure in that finish step can also restore them. A database restore is only activated
     * by the next launch, so in that case the durable journal is handed to
     * [PluginRestoreStartupRecovery] instead of being deleted here. A direct caller has no
     * transaction to hand them to, so the snapshots are removed as soon as apply succeeds.
     */
    fun apply(
        restoreLocalTaskSettings: Boolean,
        validAssistantIds: Set<String>? = null,
    ): PluginDataRestoreReport {
        check(!PluginRestoreStartupRecovery.hasPending(appContext.filesDir)) {
            "上一次插件恢复尚未在启动时完成，请先重启应用"
        }
        val rollbackRoot = File(
            appContext.filesDir,
            "$PLUGIN_RESTORE_ROLLBACK_DIRECTORY/${UUID.randomUUID()}",
        )
        check(rollbackRoot.isDirectory || rollbackRoot.mkdirs()) { "无法创建插件恢复回滚目录" }
        val session = PluginRollbackSession(appContext, rollbackRoot, releaseTasks)
        try {
            session.beginPreparing()
            staged.forEach { item ->
                releaseTasks(item.pluginId)
                session.add(captureRollbackSnapshot(item, rollbackRoot))
            }
            session.beginApplying()
        } catch (error: Throwable) {
            rollbackRoot.deleteRecursively()
            throw PluginDataBackupException(
                "插件恢复的回滚快照创建失败，现有插件数据未修改",
                error,
            )
        }
        PluginRestoreRollbackScope.activate(session)

        val restored = mutableListOf<String>()
        var records = 0
        var tasks = 0
        staged.forEach { item ->
            session.markAttempted(item.pluginId)
            try {
                releaseTasks(item.pluginId)
                item.localDataZip?.let { archive ->
                    LocalDataSession(
                        appContext,
                        item.pluginId,
                        LocalDataGrant.of(LocalDataCapability.BACKUP),
                    ).use { localData ->
                        val report = localData.restoreBackup(
                            archive,
                            LocalRestoreOptions(replaceExisting = true, regenerateIds = true),
                        )
                        records += report.posts + report.comments + report.likes + report.drafts + report.media
                    }
                }
                if (restoreLocalTaskSettings) {
                    item.localTasks?.let { snapshot ->
                        LocalTasksNativeStore(appContext, item.pluginId).apply(snapshot, validAssistantIds)
                        tasks++
                    }
                    // A page may have re-created the host while the snapshot was being written;
                    // release it so the next host reloads the restored settings from disk.
                    releaseTasks(item.pluginId)
                }
                restored += item.pluginId
            } catch (error: Throwable) {
                val rollbackError = session.rollbackAttempted()
                if (rollbackError == null) {
                    PluginRestoreRollbackScope.clear(session)
                } else {
                    error.addSuppressed(rollbackError)
                }
                val attempted = session.attemptedPluginIds().joinToString().ifEmpty { "无" }
                val message = if (rollbackError == null) {
                    "插件 ${item.pluginId} 的本地数据恢复失败；已按相反顺序回滚本次修改过的插件：$attempted，" +
                        "设置与文件也将回滚"
                } else {
                    "插件 ${item.pluginId} 的本地数据恢复失败；自动回滚未完成，已保留回滚快照：$rollbackRoot，" +
                        "设置与文件将回滚"
                }
                throw PluginDataBackupException(message, error)
            }
        }
        session.markAwaitingDatabase(PluginRestoreRollbackScope.awaitsDatabaseActivation())
        PluginRestoreRollbackScope.releaseAfterApply(session)
        return PluginDataRestoreReport(restored, records, tasks)
    }

    private fun captureRollbackSnapshot(item: StagedPluginData, rollbackRoot: File): PluginRollbackSnapshot {
        val localDataExisted = PluginLocalDataStorage(appContext).exists(item.pluginId)
        val namespace = LocalDataPaths(item.pluginId, appContext.filesDir).namespace
        val directory = File(rollbackRoot, namespace)
        check(directory.isDirectory || directory.mkdirs()) { "无法创建插件回滚快照目录" }
        val localTasks = readLocalTasksRaw(appContext, item.pluginId)
        // Keep the raw pre-restore state next to the logical snapshot. The journal describes it, so
        // a later process can put back exactly this state; a failed rollback deliberately leaves the
        // directory in place instead of hiding it. Every file is flushed before the journal is
        // published, because a later process may have to read it after a process kill.
        writeFileDurably(File(directory, PLUGIN_RESTORE_PLUGIN_ID), item.pluginId)
        localTasks.settings?.let {
            writeFileDurably(File(directory, PLUGIN_RESTORE_LOCAL_TASKS_SETTINGS), it)
        }
        localTasks.ledger?.let {
            writeFileDurably(File(directory, PLUGIN_RESTORE_LOCAL_TASKS_LEDGER), it)
        }
        val archive = if (localDataExisted) {
            val target = File(directory, PLUGIN_RESTORE_LOCAL_DATA)
            LocalDataSession(
                appContext,
                item.pluginId,
                LocalDataGrant.of(LocalDataCapability.BACKUP),
            ).use { localData ->
                localData.database.transaction {
                    localData.exportBackup(target, System.currentTimeMillis())
                }
            }
            check(target.isFile && target.length() > 0) {
                "插件 ${item.pluginId} 的回滚快照为空"
            }
            syncFileDurably(target)
            target
        } else {
            null
        }
        return PluginRollbackSnapshot(
            pluginId = item.pluginId,
            localDataExisted = localDataExisted,
            localDataRestoreAttempted = item.localDataZip != null,
            localDataArchive = archive,
            localTasks = localTasks,
        )
    }
}

/**
 * Stops and releases the process-wide task host before its native settings/ledger or localdata are
 * read or replaced. A running turn must not write into a database that is being snapshotted or
 * restored, and a released host reloads stopped state afterwards.
 */
internal fun releasePluginTaskHosts(pluginId: String) {
    LocalTaskHosts.peek(pluginId)?.let { host ->
        runCatching { host.stopAll() }
        LocalTaskHosts.release(pluginId)
    }
}
