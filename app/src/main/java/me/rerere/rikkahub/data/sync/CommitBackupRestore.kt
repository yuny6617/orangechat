package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Both DataStore write locks must remain held through preparation, commit and any rollback. */
internal suspend fun <S, P> commitBackupRestore(
    transaction: BackupRestoreTransaction,
    files: List<String>,
    databases: List<String>,
    settingsSession: RestorePreferencesSession<S>,
    settings: S?,
    pluginSession: RestorePreferencesSession<P>?,
    plugins: P?,
    pluginRestore: (suspend () -> Unit)? = null,
) {
    currentCoroutineContext().ensureActive()
    // Cancellation may stop extraction, but must not interrupt commit or recovery halfway through.
    withContext(NonCancellable) {
        try {
            val preferences = buildList {
                if (settings != null) add(transaction.relativePath(settingsSession.file))
                if (plugins != null) add(transaction.relativePath(requireNotNull(pluginSession).file))
            }
            transaction.prepare(files, preferences, databases)
            transaction.installFiles()
            if (settings != null) settingsSession.apply(settings)
            if (plugins != null) requireNotNull(pluginSession).apply(plugins)
            // Logical plugin data is applied only after the existing journal owns the file/settings
            // rollback. The plan keeps reverse-order snapshots alive until this transaction has
            // finished; a failure here or in finish() restores them before the journal is discarded.
            // With a staged database the restore is not complete until the next launch activates it,
            // so the durable plugin journal is handed to startup instead of being deleted here.
            PluginRestoreRollbackScope.beginCommitWindow(databases.isNotEmpty())
            pluginRestore?.invoke()
            transaction.finish(databases.isNotEmpty())
            if (databases.isNotEmpty()) {
                PluginRestoreRollbackScope.handOffToStartup()
            } else {
                PluginRestoreRollbackScope.commitActive()
            }
            PluginRestoreRollbackScope.endCommitWindow()
        } catch (error: Throwable) {
            PluginRestoreRollbackScope.endCommitWindow()
            if (transaction.isPrepared) {
                transaction.beginRollback()
                var recoveryFailed = false
                suspend fun recover(block: suspend () -> Unit) {
                    try { block() } catch (recovery: Throwable) {
                        error.addSuppressed(recovery)
                        recoveryFailed = true
                    }
                }
                if (plugins != null) recover { requireNotNull(pluginSession).rollback() }
                if (settings != null) recover { settingsSession.rollback() }
                recover { transaction.rollbackFiles() }
                // A plugin plan that already rolled itself back is a no-op here; one whose
                // rollback was incomplete (or that only failed while finishing the transaction)
                // gets a second chance before the journal is discarded.
                recover { PluginRestoreRollbackScope.rollbackActive()?.let { throw it } }
                // Keep all snapshots for startup recovery if even one rollback step failed.
                if (!recoveryFailed) transaction.discard()
            } else {
                // No live file/settings target was prepared, but a plugin plan should not have run
                // without the journal owning its rollback. Be defensive and restore it if it did.
                PluginRestoreRollbackScope.rollbackActive()?.let(error::addSuppressed)
                transaction.discardUnprepared()
            }
            throw error
        }
    }
}
