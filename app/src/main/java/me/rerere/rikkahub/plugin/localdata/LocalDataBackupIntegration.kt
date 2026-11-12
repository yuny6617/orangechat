/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localdata

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One per-plugin, versioned container used by the full local backup (`includePlugins`).
 *
 * The container deliberately wraps [LocalDataBackup] instead of re-using its ZIP directly:
 * the localdata archive is self-contained and validated by [LocalDataBackup], while this outer
 * archive also carries the host-owned `localtasks` settings/ledger snapshot. Native grants, model
 * keys and the hot SQLite database file are never part of this format.
 */
@Serializable
data class PluginBackupEntryInfo(
    val sha256: String,
    val byteSize: Long,
)

@Serializable
data class PluginBackupManifest(
    val format: String = PluginBackupArchive.FORMAT,
    val formatVersion: Int = PluginBackupArchive.FORMAT_VERSION,
    val pluginId: String,
    val pluginVersion: String? = null,
    val createdAt: Long,
    val localDataSchemaVersion: Int? = null,
    val localData: PluginBackupEntryInfo? = null,
    val localTasks: PluginBackupEntryInfo? = null,
    val localTasksSchemaVersion: Int? = null,
)

data class PluginBackupContents(
    val manifest: PluginBackupManifest,
    val localDataZip: File?,
    val localTasksJson: String?,
)

/**
 * Pure file-format helper: write/inspect a per-plugin archive without touching any live database.
 * Every entry is hash checked before a caller is allowed to extract it into staging.
 */
object PluginBackupArchive {
    const val FORMAT = "orangechat.plugin.backup"
    const val FORMAT_VERSION = 1
    const val MANIFEST_ENTRY = "plugin_backup_manifest.json"
    const val LOCAL_DATA_ENTRY = "localdata.zip"
    const val LOCAL_TASKS_ENTRY = "localtasks.json"

    const val LOCAL_TASKS_FORMAT = "orangechat.localtasks.backup"
    const val LOCAL_TASKS_FORMAT_VERSION = 1
    const val LOCAL_TASKS_SCHEMA_VERSION = 1

    const val MAX_MANIFEST_BYTES = 1 * 1024 * 1024
    const val MAX_LOCAL_TASKS_BYTES = 8 * 1024 * 1024
    const val MAX_ARCHIVE_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_PLUGIN_VERSION_CHARS = 64

    private val knownEntries = setOf(MANIFEST_ENTRY, LOCAL_DATA_ENTRY, LOCAL_TASKS_ENTRY)
    private val secretKeyPattern = Regex(
        "(?i).*(api[_-]?key|secret|token|password|credential|authorization|private[_-]?key).*",
    )
    private val terminalTaskStates = setOf("completed", "cancelled", "failed", "interrupted")

    /**
     * Writes the per-plugin archive atomically (temporary file + rename). Any validation failure
     * happens before [destination] exists, so an interrupted snapshot never leaves a half archive.
     */
    fun write(
        destination: File,
        pluginId: String,
        pluginVersion: String?,
        localDataZip: File?,
        localTasksJson: String?,
        createdAt: Long,
        localDataSchemaVersion: Int? = LocalDataApi.DB_SCHEMA_VERSION,
        localTasksSchemaVersion: Int? = LOCAL_TASKS_SCHEMA_VERSION,
    ): PluginBackupManifest {
        LocalDataValidation.requirePluginId(pluginId)
        require(localDataZip != null || localTasksJson != null) { "Plugin backup has no data" }
        require(pluginVersion == null || pluginVersion.length <= MAX_PLUGIN_VERSION_CHARS) {
            "Plugin version is too long"
        }
        val localDataEntry = localDataZip?.let { file ->
            require(file.isFile) { "Local data backup is missing" }
            require(file.length() in 1L..MAX_ARCHIVE_BYTES) { "Local data backup is empty or too large" }
            PluginBackupEntryInfo(sha256(file), file.length())
        }
        val localTasksBytes = localTasksJson?.toByteArray(Charsets.UTF_8)
        if (localTasksBytes != null) {
            require(localTasksBytes.size <= MAX_LOCAL_TASKS_BYTES) { "Local tasks snapshot is too large" }
            validateLocalTasksSnapshot(localTasksJson)
        }
        val manifest = PluginBackupManifest(
            pluginId = pluginId,
            pluginVersion = pluginVersion,
            createdAt = createdAt,
            localDataSchemaVersion = if (localDataZip != null) localDataSchemaVersion else null,
            localData = localDataEntry,
            localTasks = localTasksBytes?.let {
                PluginBackupEntryInfo(sha256(it), it.size.toLong())
            },
            localTasksSchemaVersion = if (localTasksBytes != null) localTasksSchemaVersion else null,
        )
        val manifestBytes = LocalDataJson.json
            .encodeToString(PluginBackupManifest.serializer(), manifest)
            .toByteArray(Charsets.UTF_8)
        require(manifestBytes.size <= MAX_MANIFEST_BYTES) { "Plugin backup manifest is too large" }

        val parent = destination.absoluteFile.parentFile ?: File(".").absoluteFile
        require(parent.isDirectory || parent.mkdirs()) { "Unable to create plugin backup directory" }
        val temporary = File(parent, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            ZipOutputStream(FileOutputStream(temporary)).use { zip ->
                putEntry(zip, MANIFEST_ENTRY, manifestBytes)
                if (localDataZip != null) putFile(zip, LOCAL_DATA_ENTRY, localDataZip)
                if (localTasksBytes != null) putEntry(zip, LOCAL_TASKS_ENTRY, localTasksBytes)
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
        return manifest
    }

    /**
     * Validates the outer container and extracts the localdata archive into [workDirectory].
     * It does not validate the semantic content of the localdata archive; the caller must run
     * [LocalDataBackup] against a staging namespace before any live data is replaced.
     */
    fun inspect(source: File, workDirectory: File): PluginBackupContents {
        require(source.isFile) { "Plugin backup is missing" }
        require(source.length() in 1L..MAX_ARCHIVE_BYTES) { "Plugin backup has an invalid size" }
        require(workDirectory.isDirectory || workDirectory.mkdirs()) { "Unable to create plugin staging" }
        try {
            ZipFile(source).use { zip ->
                val entries = zip.entries().toList()
                require(entries.isNotEmpty()) { "Plugin backup is empty" }
                require(entries.none { it.isDirectory }) { "Plugin backup must not contain directories" }
                val names = entries.map { it.name }
                require(names.toSet().size == names.size) { "Plugin backup contains duplicate entries" }
                require(names.all { it in knownEntries }) { "Plugin backup contains unknown entries" }
                val manifestEntry = zip.getEntry(MANIFEST_ENTRY)
                    ?: throw invalid("Plugin backup manifest is missing")
                val manifestBytes = readBounded(zip, manifestEntry, MAX_MANIFEST_BYTES)
                val manifest = decodeManifest(manifestBytes)
                validateManifest(manifest)
                require((zip.getEntry(LOCAL_DATA_ENTRY) != null) == (manifest.localData != null)) {
                    "Plugin backup local data entry does not match the manifest"
                }
                require((zip.getEntry(LOCAL_TASKS_ENTRY) != null) == (manifest.localTasks != null)) {
                    "Plugin backup local tasks entry does not match the manifest"
                }
                val localDataZip = manifest.localData?.let { expected ->
                    val entry = zip.getEntry(LOCAL_DATA_ENTRY)
                        ?: throw invalid("Plugin backup local data is missing")
                    val target = File(workDirectory, "localdata-${UUID.randomUUID()}.zip")
                    try {
                        extractVerified(zip, entry, target, expected)
                    } catch (error: Throwable) {
                        target.delete()
                        throw error
                    }
                    target
                }
                val localTasksJson = manifest.localTasks?.let { expected ->
                    val entry = zip.getEntry(LOCAL_TASKS_ENTRY)
                        ?: throw invalid("Plugin backup local tasks snapshot is missing")
                    val bytes = readBounded(zip, entry, MAX_LOCAL_TASKS_BYTES)
                    if (bytes.size.toLong() != expected.byteSize || sha256(bytes) != expected.sha256) {
                        throw invalid("Plugin backup local tasks integrity check failed")
                    }
                    val raw = bytes.toString(Charsets.UTF_8)
                    validateLocalTasksSnapshot(raw)
                    raw
                }
                return PluginBackupContents(manifest, localDataZip, localTasksJson)
            }
        } catch (error: LocalDataException) {
            throw error
        } catch (error: java.io.IOException) {
            throw invalid("Plugin backup archive is invalid")
        }
    }

    /**
     * Structural checks for the host-owned localtasks snapshot. In particular, no task may be
     * queued/running/waiting for review and no pending review may be replayed after a restore.
     * These checks are intentionally format-only; [me.rerere.rikkahub.plugin.localtasks] still
     * validates the typed settings/ledger when it writes them.
     */
    fun validateLocalTasksSnapshot(raw: String) {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_LOCAL_TASKS_BYTES) { "Local tasks snapshot is too large" }
        val root = try {
            LocalDataJson.json.parseToJsonElement(raw)
        } catch (_: Exception) {
            throw invalid("Local tasks snapshot is not valid JSON")
        }
        val obj = root as? JsonObject ?: throw invalid("Local tasks snapshot must be an object")
        val allowedRoot = setOf("format", "formatVersion", "settingsVersion", "ledgerVersion", "settings", "ledger")
        require(obj.keys.all { it in allowedRoot }) { "Local tasks snapshot has unknown fields" }
        require(obj["format"]?.jsonPrimitive?.contentOrNull == LOCAL_TASKS_FORMAT) {
            "Unknown local tasks snapshot format"
        }
        require(obj["formatVersion"]?.jsonPrimitive?.intOrNull == LOCAL_TASKS_FORMAT_VERSION) {
            "Unsupported local tasks snapshot version"
        }
        require(obj["settingsVersion"]?.jsonPrimitive?.intOrNull == LOCAL_TASKS_SCHEMA_VERSION) {
            "Unsupported local tasks settings version"
        }
        require(obj["ledgerVersion"]?.jsonPrimitive?.intOrNull == LOCAL_TASKS_SCHEMA_VERSION) {
            "Unsupported local tasks ledger version"
        }
        validateNoSecretFields(root)
        val settings = obj["settings"]
        require(settings == null || settings is JsonObject) { "Local tasks settings must be an object" }
        val ledger = obj["ledger"]
        require(ledger == null || ledger is JsonObject) { "Local tasks ledger must be an object" }
        require(settings != null || ledger != null) { "Local tasks snapshot has no data" }
        val tasks = ledger?.get("tasks")
        require(tasks == null || tasks is JsonObject) { "Local tasks ledger tasks must be an object" }
        tasks?.values?.forEach { value ->
            val task = value as? JsonObject ?: throw invalid("Local tasks record must be an object")
            val state = task["state"]?.jsonPrimitive?.contentOrNull ?: "queued"
            require(state in terminalTaskStates) {
                "Local tasks snapshot contains a task that could be replayed: $state"
            }
            val pending = task["pendingReview"]
            require(pending == null || (pending is JsonArray && pending.isEmpty())) {
                "Local tasks snapshot contains an unresolved review"
            }
        }
    }

    private fun decodeManifest(bytes: ByteArray): PluginBackupManifest = try {
        LocalDataJson.json.decodeFromString(PluginBackupManifest.serializer(), bytes.toString(Charsets.UTF_8))
    } catch (_: Exception) {
        throw invalid("Plugin backup manifest is invalid")
    }

    private fun validateManifest(manifest: PluginBackupManifest) {
        if (manifest.format != FORMAT) throw invalid("Unknown plugin backup format")
        if (manifest.formatVersion != FORMAT_VERSION) {
            throw LocalDataException(LocalDataErrorCode.UNSUPPORTED_VERSION, "Unsupported plugin backup version")
        }
        LocalDataValidation.requirePluginId(manifest.pluginId)
        require(manifest.pluginVersion == null || manifest.pluginVersion.length <= MAX_PLUGIN_VERSION_CHARS) {
            "Plugin version is too long"
        }
        if (manifest.localData != null && manifest.localDataSchemaVersion != LocalDataApi.DB_SCHEMA_VERSION) {
            throw LocalDataException(LocalDataErrorCode.UNSUPPORTED_VERSION, "Unsupported local data schema")
        }
        if (manifest.localTasks != null && manifest.localTasksSchemaVersion != LOCAL_TASKS_SCHEMA_VERSION) {
            throw LocalDataException(LocalDataErrorCode.UNSUPPORTED_VERSION, "Unsupported local tasks schema")
        }
        if (manifest.localData == null && manifest.localTasks == null) {
            throw invalid("Plugin backup has no data")
        }
    }

    private fun extractVerified(
        zip: ZipFile,
        entry: ZipEntry,
        target: File,
        expected: PluginBackupEntryInfo,
    ) {
        require(expected.byteSize in 1L..MAX_ARCHIVE_BYTES) { "Invalid local data size" }
        require(expected.sha256.length == 64) { "Invalid local data hash" }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        zip.getInputStream(entry).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > expected.byteSize || total > MAX_ARCHIVE_BYTES) {
                        throw LocalDataException(LocalDataErrorCode.PAYLOAD_TOO_LARGE, "Local data is too large")
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        }
        if (total != expected.byteSize) throw invalid("Local data size does not match")
        if (hex(digest.digest()) != expected.sha256) throw invalid("Local data integrity check failed")
    }

    private fun validateNoSecretFields(root: JsonElement) {
        when (root) {
            is JsonObject -> root.forEach { (key, value) ->
                require(!secretKeyPattern.matches(key)) { "Local tasks snapshot contains a secret field" }
                validateNoSecretFields(value)
            }

            is JsonArray -> root.forEach(::validateNoSecretFields)
            else -> Unit
        }
    }

    private fun readBounded(zip: ZipFile, entry: ZipEntry, maxBytes: Int): ByteArray {
        if (entry.size > maxBytes) {
            throw LocalDataException(LocalDataErrorCode.PAYLOAD_TOO_LARGE, "Plugin backup entry is too large")
        }
        val output = java.io.ByteArrayOutputStream()
        zip.getInputStream(entry).use { input ->
            copyBounded(input, output, maxBytes)
        }
        return output.toByteArray()
    }

    private fun copyBounded(input: InputStream, output: java.io.OutputStream, maxBytes: Int) {
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw LocalDataException(LocalDataErrorCode.PAYLOAD_TOO_LARGE, "Plugin backup entry is too large")
            }
            output.write(buffer, 0, read)
        }
    }

    private fun putEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun putFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun sha256(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun invalid(message: String) =
        LocalDataException(LocalDataErrorCode.INVALID_REQUEST, message)
}
