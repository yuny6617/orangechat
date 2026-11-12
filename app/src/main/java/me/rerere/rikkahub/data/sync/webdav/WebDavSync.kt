/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync.webdav

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.sync.BackupArchiveRestorer
import me.rerere.rikkahub.data.sync.DatabaseBackupCoordinator
import me.rerere.rikkahub.data.sync.PLUGIN_BACKUP_ENTRY_PREFIX
import me.rerere.rikkahub.data.sync.PluginDataBackupCoordinator
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.plugin.localdata.LocalDataValidation
import me.rerere.rikkahub.plugin.repository.PluginRepository
import me.rerere.rikkahub.plugin.scanner.PluginScanner
import me.rerere.rikkahub.utils.fileSizeToString
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG = "WebDavSync"

class WebDavSync(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val httpClient: HttpClient,
    private val pluginRepository: PluginRepository,
    private val databaseBackupCoordinator: DatabaseBackupCoordinator,
) {
    private fun getClient(config: WebDavConfig): WebDavClient {
        return WebDavClient(config, httpClient)
    }

    suspend fun testConnection(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        // Test by listing the root directory
        client.propfind(depth = 0).getOrThrow()
        Log.i(TAG, "testConnection: Connection successful")
    }

    suspend fun backup(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config, includePlugins = false)
        try {
            val client = getClient(config)

            // Ensure the backup directory exists
            client.ensureCollectionExists().getOrThrow()

            // Upload the backup file
            client.put(
                path = file.name,
                file = file,
                contentType = "application/zip"
            ).getOrThrow()

            Log.i(TAG, "backup: Uploaded ${file.name} (${file.length().fileSizeToString()})")
        } finally {
            file.delete()
        }
    }

    suspend fun listBackupFiles(config: WebDavConfig): List<WebDavBackupItem> = withContext(Dispatchers.IO) {
        val client = getClient(config)

        // Ensure the backup directory exists
        client.ensureCollectionExists().getOrThrow()

        val resources = client.list().getOrThrow()

        resources
            .filter { !it.isCollection && it.displayName.startsWith("backup_") && it.displayName.endsWith(".zip") }
            .map { resource ->
                WebDavBackupItem(
                    href = resource.href,
                    displayName = resource.displayName,
                    size = resource.contentLength,
                    lastModified = resource.lastModified ?: Instant.EPOCH
                )
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restore(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        val backupFile = File(context.cacheDir, item.displayName)

        try {
            // Download backup file directly to file to avoid OOM
            Log.i(TAG, "restore: Downloading ${item.displayName}")
            client.downloadToFile(item.displayName, backupFile).getOrThrow()

            Log.i(TAG, "restore: Downloaded ${backupFile.length().fileSizeToString()}")

            // Restore from backup file
            restoreFromBackupFile(backupFile, config, includePlugins = false)
        } finally {
            // Clean up temp file
            if (backupFile.exists()) {
                backupFile.delete()
                Log.i(TAG, "restore: Cleaned up temporary backup file")
            }
        }
    }

    suspend fun deleteBackupFile(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        client.delete(item.displayName).getOrThrow()
        Log.i(TAG, "deleteBackupFile: Deleted ${item.displayName}")
    }

    suspend fun restoreFromLocalFile(
        file: File,
        config: WebDavConfig,
        restoreLocalTaskSettings: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        Log.i(TAG, "restoreFromLocalFile: Starting restore from ${file.absolutePath}")

        if (!file.exists()) {
            throw Exception("Backup file does not exist")
        }

        if (!file.canRead()) {
            throw Exception("Cannot read backup file")
        }

        try {
            restoreFromBackupFile(
                file,
                config,
                includePlugins = true,
                restoreLocalTaskSettings = restoreLocalTaskSettings,
            )
            Log.i(TAG, "restoreFromLocalFile: Restore completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromLocalFile: Failed to restore from local file", e)
            throw Exception("Restore failed: ${e.message}")
        }
    }

    suspend fun prepareBackupFile(
        config: WebDavConfig,
        includePlugins: Boolean = true
    ): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val backupFile = File(context.cacheDir, "backup_${timestamp}_${java.util.UUID.randomUUID()}.zip")

        if (backupFile.exists()) {
            backupFile.delete()
        }

        try {
            // Create zip file and backup data
            ZipOutputStream(FileOutputStream(backupFile)).use { zipOut ->
                addVirtualFileToZip(
                    zipOut = zipOut,
                    name = "settings.json",
                    content = json.encodeToString(settingsStore.settingsFlow.value)
                )

                // Backup database files
                if (config.items.contains(WebDavConfig.BackupItem.DATABASE)) {
                    val snapshot = databaseBackupCoordinator.createConsistentSnapshot()
                    try {
                        addFileToZip(zipOut, snapshot, "rikka_hub.db")
                    } finally {
                        snapshot.delete()
                    }
                }

                // Backup app files
                if (config.items.contains(WebDavConfig.BackupItem.FILES)) {
                    val uploadFolder = File(context.filesDir, FileFolders.UPLOAD)
                    if (uploadFolder.exists() && uploadFolder.isDirectory) {
                        Log.i(TAG, "prepareBackupFile: Backing up files from ${uploadFolder.absolutePath}")
                        uploadFolder.listFiles()?.forEach { file ->
                            if (file.isFile && !file.name.startsWith(".") && file.length() > 0) {
                                addFileToZip(zipOut, file, "${FileFolders.UPLOAD}/${file.name}")
                            }
                        }
                    } else {
                        Log.w(TAG, "prepareBackupFile: Upload folder does not exist or is not a directory")
                    }

                    val skillsFolder = File(context.filesDir, FileFolders.SKILLS)
                    if (skillsFolder.exists() && skillsFolder.isDirectory) {
                        Log.i(TAG, "prepareBackupFile: Backing up skills from ${skillsFolder.absolutePath}")
                        addDirectoryToZip(
                            zipOut = zipOut,
                            rootDir = skillsFolder,
                            currentDir = skillsFolder,
                            entryPrefix = "${FileFolders.SKILLS}/"
                        )
                    } else {
                        Log.w(TAG, "prepareBackupFile: Skills folder does not exist or is not a directory")
                    }

                    // Backup plugin settings and plugin folders (only for local backup/export)
                    if (includePlugins) {
                        try {
                            val pluginSettings = pluginRepository.exportPluginSettings()
                            addVirtualFileToZip(
                                zipOut = zipOut,
                                name = "plugin_settings.json",
                                content = json.encodeToString(pluginSettings)
                            )
                            Log.i(TAG, "prepareBackupFile: Backed up plugin settings")
                        } catch (e: Exception) {
                            Log.e(TAG, "prepareBackupFile: Failed to back up plugin settings", e)
                        }

                        val pluginsFolder = PluginScanner(context).pluginsDir
                        val pluginIds = pluginsFolder
                            .listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
                            ?.map { it.name }
                            ?.filter { LocalDataValidation.PLUGIN_ID_PATTERN.matches(it) }
                            .orEmpty()
                        if (pluginsFolder.exists() && pluginsFolder.isDirectory) {
                            Log.i(TAG, "prepareBackupFile: Backing up plugins from ${pluginsFolder.absolutePath}")
                            addDirectoryToZip(
                                zipOut = zipOut,
                                rootDir = pluginsFolder,
                                currentDir = pluginsFolder,
                                entryPrefix = "${PluginScanner.PLUGINS_DIR}/"
                            )
                        } else {
                            Log.w(TAG, "prepareBackupFile: Plugins folder does not exist or is not a directory")
                        }

                        // Full local backup: one versioned logical archive per plugin, including
                        // localdata media and native localtasks settings/ledger. A failure aborts
                        // the whole backup instead of producing one without moments data.
                        val pluginBackupDirectory = File(
                            context.cacheDir,
                            "plugin_backup_${java.util.UUID.randomUUID()}"
                        )
                        try {
                            val pluginBackups = PluginDataBackupCoordinator(context).snapshot(
                                destination = pluginBackupDirectory,
                                pluginIds = pluginIds,
                            )
                            pluginBackups.forEach { backup ->
                                addFileToZip(
                                    zipOut = zipOut,
                                    file = backup.file,
                                    entryName = "$PLUGIN_BACKUP_ENTRY_PREFIX${backup.pluginId}.zip",
                                )
                            }
                            if (pluginBackups.isNotEmpty()) {
                                Log.i(TAG, "prepareBackupFile: Backed up ${pluginBackups.size} plugin data archives")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "prepareBackupFile: Failed to back up plugin local data", e)
                            throw IllegalStateException(
                                "插件本地数据备份失败，已中止本次备份（不会生成缺少朋友圈数据的备份）：${e.message}",
                                e,
                            )
                        } finally {
                            pluginBackupDirectory.deleteRecursively()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            backupFile.delete()
            throw e
        }

        Log.i(
            TAG,
            "prepareBackupFile: Created backup file ${backupFile.name} (${backupFile.length().fileSizeToString()})"
        )
        backupFile
    }

    private suspend fun restoreFromBackupFile(
        backupFile: File,
        config: WebDavConfig,
        includePlugins: Boolean = true,
        restoreLocalTaskSettings: Boolean = false,
    ) {
        BackupArchiveRestorer(context, settingsStore, json, databaseBackupCoordinator, pluginRepository).restore(
            archive = backupFile,
            restoreFiles = config.items.contains(WebDavConfig.BackupItem.FILES),
            restoreDatabase = config.items.contains(WebDavConfig.BackupItem.DATABASE),
            includePlugins = includePlugins,
            restoreLocalTaskSettings = restoreLocalTaskSettings,
        )
        Log.i(TAG, "restoreFromBackupFile: Restore committed; restart required for database activation")
    }

    private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        FileInputStream(file).use { fis ->
            val zipEntry = ZipEntry(entryName)
            zipOut.putNextEntry(zipEntry)
            fis.copyTo(zipOut)
            zipOut.closeEntry()
            Log.d(TAG, "addFileToZip: Added $entryName (${file.length()} bytes) to zip")
        }
    }

    private fun addDirectoryToZip(
        zipOut: ZipOutputStream,
        rootDir: File,
        currentDir: File,
        entryPrefix: String,
    ) {
        currentDir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                addDirectoryToZip(
                    zipOut = zipOut,
                    rootDir = rootDir,
                    currentDir = file,
                    entryPrefix = entryPrefix,
                )
            } else if (file.isFile) {
                val relativePath = file.relativeTo(rootDir).invariantSeparatorsPath
                addFileToZip(zipOut, file, "$entryPrefix$relativePath")
            }
        }
    }

    private fun addVirtualFileToZip(zipOut: ZipOutputStream, name: String, content: String) {
        val zipEntry = ZipEntry(name)
        zipOut.putNextEntry(zipEntry)
        zipOut.write(content.toByteArray())
        zipOut.closeEntry()
        Log.i(TAG, "addVirtualFileToZip: $name (${content.length} bytes)")
    }
}

data class WebDavBackupItem(
    val href: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
