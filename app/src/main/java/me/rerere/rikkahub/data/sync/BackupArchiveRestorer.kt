package me.rerere.rikkahub.data.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.plugin.repository.PluginRepository
import me.rerere.rikkahub.plugin.repository.PluginSettingsExport
import java.io.File

/** Shared restore path for S3, WebDAV and local ZIPs. */
internal class BackupArchiveRestorer(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val database: DatabaseBackupCoordinator,
    private val pluginRepository: PluginRepository? = null,
    private val pluginDataBackup: PluginDataBackupCoordinator? = null,
) {
    suspend fun restore(
        archive: File,
        restoreFiles: Boolean,
        restoreDatabase: Boolean,
        includePlugins: Boolean = false,
        restoreLocalTaskSettings: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        restoreMutex.withLock {
            check(!ChatHistoryBackup.pendingFile(context).exists()) { "请先取消待恢复的聊天记录，或重启完成恢复" }
            val transaction = BackupRestoreTransaction(context.dataDir)
            transaction.begin()
            try {
                val databaseDirectory = context.getDatabasePath("rikka_hub").parentFile!!
                    .relativeTo(context.dataDir).invariantSeparatorsPath
                val staged = stageBackupArchive(
                    archive, transaction, restoreFiles, restoreDatabase, includePlugins, databaseDirectory,
                ).let { it.copy(files = it.files + stalePluginFiles(context.dataDir, it.files)) }
                // Validate every per-plugin archive (including a sandbox localdata restore) before
                // settings, files or any live plugin namespace is changed.
                val pluginPlan = if (includePlugins && staged.pluginBackups.isNotEmpty()) {
                    (pluginDataBackup ?: PluginDataBackupCoordinator(context)).stage(
                        backups = staged.pluginBackups.map { it.pluginId to it.file },
                        workRoot = transaction.stagePath(PLUGIN_BACKUP_VALIDATION_DIRECTORY),
                    )
                } else {
                    null
                }
                val settings = staged.settings?.let {
                    json.decodeFromString<Settings>(SettingsJsonMigrator.migrate(it)).let { restored ->
                        check(!restored.init) { "Cannot restore dummy settings" }
                        restored.copy(autoBackupConfig = restored.autoBackupConfig.forRestore())
                    }
                }
                val plugins = staged.pluginSettings?.let { json.decodeFromString<PluginSettingsExport>(it) }
                if (restoreDatabase) {
                    database.validateStagedRestore(transaction.stagePath("$databaseDirectory/rikka_hub"))
                }
                // localtasks settings may only name assistants that exist after this restore; it
                // never adds a participant and never touches the native localdata grant.
                val baseSettings = settings
                    ?: settingsStore.settingsFlow.value.takeIf { !it.init }
                    ?: settingsStore.settingsFlowRaw.first()
                val assistantIds = baseSettings.assistants
                    .map { it.id.toString() }
                    .toSet()
                val pluginRestore: (suspend () -> Unit)? = pluginPlan?.let { plan ->
                    val apply: suspend () -> Unit = {
                        plan.apply(
                            restoreLocalTaskSettings = restoreLocalTaskSettings,
                            validAssistantIds = assistantIds,
                        )
                    }
                    apply
                }
                settingsStore.withRestoreSession { settingsSession ->
                    if (plugins != null) {
                        requireNotNull(pluginRepository).withRestoreSession { pluginSession ->
                            commitBackupRestore(
                                transaction = transaction,
                                files = staged.files,
                                databases = staged.databases,
                                settingsSession = settingsSession,
                                settings = settings,
                                pluginSession = pluginSession,
                                plugins = plugins,
                                pluginRestore = pluginRestore,
                            )
                        }
                    } else {
                        commitBackupRestore<Settings, PluginSettingsExport>(
                            transaction = transaction,
                            files = staged.files,
                            databases = staged.databases,
                            settingsSession = settingsSession,
                            settings = settings,
                            pluginSession = null,
                            plugins = null,
                            pluginRestore = pluginRestore,
                        )
                    }
                }
            } catch (error: Throwable) {
                if (!transaction.isPrepared) {
                    runCatching { transaction.discardUnprepared() }.onFailure(error::addSuppressed)
                }
                throw error
            }
        }
    }

    companion object {
        internal val restoreMutex = Mutex()

        /** Validation workspace inside the restore staging directory; never installed as files. */
        private const val PLUGIN_BACKUP_VALIDATION_DIRECTORY = "files/.plugin_backup_restore/validated"
    }
}
