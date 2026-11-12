/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.manager

import android.content.Context
import android.net.Uri
import me.rerere.rikkahub.plugin.localdata.LocalDataGrant
import me.rerere.rikkahub.plugin.localdata.LocalDataPaths
import me.rerere.rikkahub.plugin.localdata.LocalDataSession
import me.rerere.rikkahub.plugin.localdata.LocalDataValidation
import me.rerere.rikkahub.plugin.model.PluginInfo
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

/**
 * What the native uninstall dialog asks the user to do with the plugin's local business data.
 *
 * Preserving is the default so that reinstalling the same plugin id can keep using the moments
 * feed/media. Deleting is an explicit, destructive second choice.
 */
enum class PluginUninstallDataPolicy {
    PRESERVE_LOCAL_DATA,
    DELETE_LOCAL_DATA;

    val removesLocalData: Boolean get() = this == DELETE_LOCAL_DATA
}

/** Manifest permissions are declarations only; this asks whether the plugin could own localdata. */
fun PluginInfo.declaresLocalData(): Boolean =
    manifest.permissions.any { it.startsWith(LOCAL_DATA_PERMISSION_PREFIX) }

/**
 * One-shot handoff for the explicit data choice made in the shared plugin card dialog.
 *
 * The card is rendered by two pages. The manager page passes the choice directly to the view model;
 * the folder page still calls its pre-existing `deletePlugin(id)` callback (that call site is outside
 * this change), so the card stages the choice here immediately before invoking it. The view model
 * consumes the staged value once and otherwise falls back to preserving localdata.
 */
object PluginUninstallPolicyRelay {
    private val staged = ConcurrentHashMap<String, PluginUninstallDataPolicy>()

    fun stage(pluginId: String, policy: PluginUninstallDataPolicy) {
        staged[pluginId] = policy
    }

    fun consume(pluginId: String): PluginUninstallDataPolicy? = staged.remove(pluginId)
}

/**
 * Outcome of a successful uninstall. The plugin files are always gone when this is returned;
 * [localDataDeletionIncomplete] only reports that the explicit data wipe could not finish.
 */
data class PluginUninstallResult(
    val pluginId: String,
    val dataPolicy: PluginUninstallDataPolicy,
    val localDataExisted: Boolean,
    val localDataStillPresent: Boolean,
    val localDataDeletionComplete: Boolean,
    val localDataGrantsRevoked: Boolean,
    val revokedToolGrants: Int,
    val stoppedActiveTasks: Int,
) {
    val localDataDeletionIncomplete: Boolean
        get() = dataPolicy.removesLocalData && !localDataDeletionComplete
}

data class PluginLocalDataDeletion(
    val existed: Boolean,
    val complete: Boolean,
)

/**
 * Resolved on-device locations for one plugin's localdata namespace.
 *
 * The namespace is the SHA-256 prefix produced by [LocalDataPaths]; the raw plugin id never becomes
 * a path segment. [requireSafe] re-checks the canonical paths so a tampered/symlinked namespace or
 * database file can never make the uninstaller delete anything outside this plugin's own directory.
 */
class PluginLocalDataLayout(
    val pluginId: String,
    private val filesDir: File,
    private val databasesDir: File,
) {
    init {
        LocalDataValidation.requirePluginId(pluginId)
    }

    private val localDataBase = File(filesDir, LOCAL_DATA_DIRECTORY)
    private val paths = LocalDataPaths(pluginId, filesDir)

    val namespace: String = paths.namespace
    val root: File = paths.root
    val databaseName: String = paths.databaseName
    val databaseFile: File = File(databasesDir, databaseName)
    val databaseSidecars: List<File> = DATABASE_SIDECARS.map { suffix ->
        File(databasesDir, databaseName + suffix)
    }

    fun exists(): Boolean =
        root.exists() || databaseFile.exists() || databaseSidecars.any { it.exists() }

    /** Fail closed unless every target still resolves inside its own namespace/database directory. */
    fun requireSafe(): PluginLocalDataLayout {
        require(NAMESPACE_PATTERN.matches(namespace)) { "Invalid local data namespace" }
        require(DATABASE_NAME_PATTERN.matches(databaseName)) { "Invalid local data database name" }

        val base = localDataBase.canonicalFile
        require(base == File(filesDir.canonicalFile, LOCAL_DATA_DIRECTORY).canonicalFile) {
            "Local data base escapes the app files directory"
        }
        val canonicalRoot = root.canonicalFile
        require(canonicalRoot.parentFile == base && canonicalRoot.name == namespace) {
            "Local data directory escapes its plugin namespace"
        }

        val databaseRoot = databasesDir.canonicalFile
        val canonicalDatabase = databaseFile.canonicalFile
        require(canonicalDatabase.parentFile == databaseRoot && canonicalDatabase.name == databaseName) {
            "Local data database escapes the app database directory"
        }
        databaseSidecars.forEach { sidecar ->
            val canonical = sidecar.canonicalFile
            require(canonical.parentFile == databaseRoot && canonical.name.startsWith(databaseName)) {
                "Local data journal escapes the app database directory"
            }
        }
        return this
    }

    private companion object {
        const val LOCAL_DATA_DIRECTORY = "plugin_localdata"
        val NAMESPACE_PATTERN = Regex("^[0-9a-f]{32}$")
        val DATABASE_NAME_PATTERN = Regex("^localdata_v1_[0-9a-f]{32}\\.db$")
        val DATABASE_SIDECARS = listOf("-wal", "-shm", "-journal")
    }
}

/**
 * Host-owned eraser for one plugin's independent localdata database and media root. It never
 * instantiates [me.rerere.rikkahub.plugin.localdata.LocalDataDatabase] (that would recreate the
 * directories it is about to delete) and it leaves the shared `plugin_localdata` parent in place.
 */
class PluginLocalDataStorage(context: Context) {
    private val appContext = context.applicationContext

    private val databasesDir: File
        get() = appContext.getDatabasePath(DATABASE_DIRECTORY_ANCHOR).parentFile
            ?: throw IllegalStateException("App database directory is unavailable")

    fun layout(pluginId: String): PluginLocalDataLayout =
        PluginLocalDataLayout(pluginId, appContext.filesDir, databasesDir).requireSafe()

    fun exists(pluginId: String): Boolean = layout(pluginId).exists()

    fun delete(pluginId: String): PluginLocalDataDeletion {
        val layout = layout(pluginId)
        val existed = layout.exists()
        var complete = true
        layout.databaseSidecars.forEach { sidecar ->
            if (sidecar.exists() && !sidecar.delete()) complete = false
        }
        if (layout.databaseFile.exists() && !layout.databaseFile.delete()) complete = false
        if (layout.root.exists() && !deleteTreeWithoutFollowingLinks(layout.root)) complete = false
        return PluginLocalDataDeletion(existed = existed, complete = complete && !layout.exists())
    }

    /**
     * Native SAF export used by the uninstall dialog. The data is written to a cache file first and
     * only copied to the user-picked [destination] on success, so a cancelled/broken picker never
     * leaves a partial backup behind.
     */
    fun export(pluginId: String, destination: Uri) {
        val layout = layout(pluginId)
        check(layout.exists()) { "该插件没有可导出的本地数据" }
        val temporary = File.createTempFile("plugin-localdata-", ".zip", appContext.cacheDir)
        try {
            // Host-initiated export of the owner's data; no plugin JS is involved, so the manifest's
            // capability declarations must not block saving data the user is about to keep/delete.
            LocalDataSession(appContext, pluginId, LocalDataGrant.all()).use { session ->
                session.exportBackup(temporary)
            }
            appContext.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                temporary.inputStream().use { input -> input.copyTo(output) }
            } ?: error("无法写入所选导出文件")
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        const val DATABASE_DIRECTORY_ANCHOR = "plugin_localdata_probe"
    }

    /**
     * Deletes the media/root tree without following symlinks: a link inside the plugin's directory
     * is removed as a link, never as its target (`File.deleteRecursively` would descend into it).
     */
    private fun deleteTreeWithoutFollowingLinks(root: File): Boolean {
        return try {
            Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            })
            !root.exists()
        } catch (_: IOException) {
            false
        }
    }
}

data class PluginGrantRevocation(
    val localDataGrantRemoved: Boolean,
    val toolGrantsRemoved: Int,
)

/**
 * Revokes the native grants keyed by plugin id. Without this, uninstalling a plugin and installing
 * a different package that happens to reuse the same id would inherit localdata/tool authority that
 * the user approved for the previous plugin. Updates never call this.
 *
 * The preference names and key formats are the storage used by the WebView bridge; they are
 * duplicated here instead of modified there because the uninstaller must revoke grants without
 * opening a plugin page.
 */
class PluginHostGrantStore(context: Context) {
    private val appContext = context.applicationContext

    fun revokeAll(pluginId: String): PluginGrantRevocation {
        // Do not apply localdata's strict id pattern here: a legacy plugin may have an id that
        // predates it, and its grants must still be revoked on uninstall. These operations only
        // remove the exact preference keys and never build a filesystem path.
        require(pluginId.isNotBlank()) { "Plugin id is required" }

        val localDataPreferences = appContext.getSharedPreferences(
            LOCAL_DATA_GRANTS_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        val localDataGrantRemoved = localDataPreferences.contains(pluginId)
        if (localDataGrantRemoved) {
            localDataPreferences.edit().remove(pluginId).commit()
        }

        val toolPreferences = appContext.getSharedPreferences(
            TOOL_GRANTS_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        val keys = toolPreferences.all.keys.filter { key ->
            toolGrantKeyBelongsToPlugin(key, pluginId)
        }
        if (keys.isNotEmpty()) {
            val editor = toolPreferences.edit()
            keys.forEach { key -> editor.remove(key) }
            editor.commit()
        }
        return PluginGrantRevocation(
            localDataGrantRemoved = localDataGrantRemoved,
            toolGrantsRemoved = keys.size,
        )
    }

    companion object {
        const val LOCAL_DATA_GRANTS_PREFERENCES = "localdata_host_grants"
        const val TOOL_GRANTS_PREFERENCES = "localdata_host_tool_grants"

        /**
         * Tool consent keys are `callerPluginId|targetPluginId|toolName`; the grant belongs to the
         * uninstalled plugin when either side is that plugin.
         */
        fun toolGrantKeyBelongsToPlugin(key: String, pluginId: String): Boolean {
            val parts = key.split('|', limit = 3)
            return parts.size == 3 && (parts[0] == pluginId || parts[1] == pluginId)
        }
    }
}

private const val LOCAL_DATA_PERMISSION_PREFIX = "localdata."
