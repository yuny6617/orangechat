package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.rikkahub.plugin.localdata.LocalDataValidation
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Per-plugin logical snapshot embedded in a full local backup. */
internal data class StagedPluginBackup(
    val pluginId: String,
    val file: File,
)

internal data class StagedBackup(
    val settings: String?,
    val pluginSettings: String?,
    val files: List<String>,
    val databases: List<String>,
    val pluginBackups: List<StagedPluginBackup> = emptyList(),
)

/** Archive entry prefix produced by [PluginDataBackupCoordinator] and consumed by the stager. */
internal const val PLUGIN_BACKUP_ENTRY_PREFIX = "Orangechat/plugin_backups/"

/** Staging-only path for the per-plugin archive; it is never installed as a live file. */
private const val PLUGIN_BACKUP_STAGING = "files/.plugin_backup_restore"

/** Fully consume and validate the ZIP before any existing file or preference is changed. */
internal suspend fun stageBackupArchive(
    archive: File,
    transaction: BackupRestoreTransaction,
    restoreFiles: Boolean,
    restoreDatabase: Boolean,
    includePlugins: Boolean,
    databaseDirectory: String = "databases",
): StagedBackup {
    val seen = mutableSetOf<String>()
    val files = mutableListOf<String>()
    val pluginBackups = mutableListOf<StagedPluginBackup>()
    var settings: String? = null
    var pluginSettings: String? = null
    ZipInputStream(archive.inputStream().buffered()).use { input ->
        while (true) {
            currentCoroutineContext().ensureActive()
            val entry = input.nextEntry ?: break
            val name = entry.name.replace('\\', '/')
            require(!name.startsWith('/') && name.split('/').none { it == ".." || it == "." }) {
                "Invalid backup entry: ${entry.name}"
            }
            if (!entry.isDirectory) {
                require(seen.add(name)) { "Duplicate backup entry: $name" }
                when (name) {
                    "settings.json" -> settings = input.readBytes().toString(Charsets.UTF_8)
                    "plugin_settings.json" -> if (includePlugins) {
                        pluginSettings = input.readBytes().toString(Charsets.UTF_8)
                    }
                    else -> {
                        if (includePlugins && restoreFiles && name.startsWith(PLUGIN_BACKUP_ENTRY_PREFIX)) {
                            val relative = name.removePrefix(PLUGIN_BACKUP_ENTRY_PREFIX)
                            require(relative.endsWith(".zip") && '/' !in relative) {
                                "Invalid plugin backup entry: $name"
                            }
                            val pluginId = relative.removeSuffix(".zip")
                            require(LocalDataValidation.PLUGIN_ID_PATTERN.matches(pluginId) &&
                                pluginId != "." && pluginId != ".."
                            ) { "Invalid plugin backup id: $pluginId" }
                            val destination = "$PLUGIN_BACKUP_STAGING/$pluginId.zip"
                            val target = transaction.stagePath(destination)
                            require(!target.exists()) { "Conflicting backup target: $name" }
                            copyEntry(input, target)
                            // These bytes are input for the logical restore and must not become
                            // live files, so they are not listed in `files`.
                            pluginBackups += StagedPluginBackup(pluginId, target)
                        } else {
                            val destination = when {
                                restoreDatabase && name in listOf("rikka_hub.db", "rikka_hub-wal", "rikka_hub-shm") ->
                                    "$databaseDirectory/${if (name == "rikka_hub.db") "rikka_hub" else name}"
                                restoreFiles && (name.startsWith("upload/") || name.startsWith("skills/")) ->
                                    "files/$name"
                                restoreFiles && includePlugins && name.startsWith("plugins/") -> "files/$name"
                                restoreFiles && includePlugins && name.startsWith("Orangechat/plugins/") ->
                                    "files/${name.removePrefix("Orangechat/")}"
                                else -> null
                            }
                            if (destination != null) {
                                copyEntry(input, transaction.stagePath(destination))
                                if (!destination.startsWith("$databaseDirectory/")) files += destination
                            }
                        }
                    }
                }
            }
            // closeEntry also consumes/checks ignored entries, including their CRC.
            input.closeEntry()
        }
    }
    require(settings != null || files.isNotEmpty() || pluginBackups.isNotEmpty() ||
        transaction.stagePath("$databaseDirectory/rikka_hub").isFile
    ) {
        "Backup contains no restorable data"
    }
    val databases = if (restoreDatabase) {
        require(transaction.stagePath("$databaseDirectory/rikka_hub").isFile) { "Backup has no database" }
        // Missing sidecars mean remove old live sidecars, never replay them against the restored DB.
        listOf("rikka_hub", "rikka_hub-wal", "rikka_hub-shm").map { "$databaseDirectory/$it" }
    } else emptyList()
    return StagedBackup(settings, pluginSettings, files, databases, pluginBackups)
}

private suspend fun copyEntry(input: InputStream, target: File) {
    require(!target.exists()) { "Conflicting backup target: ${target.name}" }
    check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
    FileOutputStream(target).use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        output.fd.sync()
    }
}

/**
 * Live files inside every plugin directory the backup restores, but that the backup does not
 * contain. Listing them as restore targets without a staged replacement makes the journal move
 * them aside (and back on rollback), so each restored plugin directory ends up exactly as it was
 * backed up. Otherwise files added by a newer plugin version survive next to the older backup's
 * files and `.integrity`, and the plugin fails its checksum ("插件文件已被篡改或损坏").
 */
internal fun stalePluginFiles(root: File, stagedFiles: List<String>): List<String> {
    val prefix = "files/plugins/"
    val staged = stagedFiles.toHashSet()
    val pluginIds = stagedFiles.filter { it.startsWith(prefix) }
        .map { it.removePrefix(prefix).substringBefore('/') }
        .filter { it.isNotBlank() && !it.startsWith(".") }
        .toSet()
    return pluginIds.flatMap { id ->
        val directory = File(root, prefix + id)
        if (!directory.isDirectory) return@flatMap emptyList()
        directory.walkTopDown().filter { it.isFile }
            .map { prefix + id + "/" + it.relativeTo(directory).invariantSeparatorsPath }
            .filter { it !in staged }
            .toList()
    }.sorted()
}
