/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localdata

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Host-facing entry point. One session belongs to one plugin; the WebView and the chat/task side
 * both call [call], but each passes an actor created by the host.
 */
class LocalDataSession(
    context: Context,
    val pluginId: String,
    val grant: LocalDataGrant,
) : Closeable {
    private val appContext = context.applicationContext
    val database: LocalDataDatabase = LocalDataDatabase(appContext, pluginId)
    val store: LocalDataStore = LocalDataStore(database)
    val media: LocalDataMediaStore = LocalDataMediaStore(database)
    val backup: LocalDataBackup = LocalDataBackup(database, store, media)
    val dispatcher: LocalDataDispatcher = LocalDataDispatcher(store, media)

    fun call(
        requestJson: String,
        actor: LocalDataActor,
        assistantDirectory: LocalAssistantDirectory,
        assistantAutoPublish: Boolean = false,
        userDisplayName: String = "我",
    ): String {
        val context = LocalDataCallContext(
            actor = actor,
            grant = grant,
            assistantDirectory = assistantDirectory,
            assistantAutoPublish = assistantAutoPublish,
            userDisplayName = userDisplayName,
        )
        return dispatcher.dispatchJson(requestJson, context)
    }

    /**
     * Copies a picker result into the persistent media repository. Reads at most the configured
     * maximum and never exposes the source URI/path to plugin code.
     */
    fun importImageFromUri(uri: Uri, displayName: String? = null): LocalImportedMedia {
        val mime = runCatching { appContext.contentResolver.getType(uri) }.getOrNull()
        val bytes = try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                readBounded(input, LocalDataApi.MAX_MEDIA_BYTES)
            } ?: throw LocalDataException(LocalDataErrorCode.INVALID_REQUEST, "Could not open image")
        } catch (error: LocalDataException) {
            throw error
        } catch (error: SecurityException) {
            throw LocalDataException(LocalDataErrorCode.FORBIDDEN, "Image access is not allowed")
        } catch (error: IOException) {
            throw LocalDataException(LocalDataErrorCode.UNAVAILABLE, "Could not read image")
        }
        return media.importImage(bytes, mime, displayName)
    }

    fun importImageBytes(
        bytes: ByteArray,
        mimeHint: String? = null,
        displayName: String? = null,
    ): LocalImportedMedia = media.importImage(bytes, mimeHint, displayName)

    fun openMedia(url: String?): LocalMediaResponse? = media.openUrl(url)

    fun thumbUrl(resourceId: String): String = LocalDataMediaUrl.thumb(resourceId)

    fun originalUrl(resourceId: String): String = LocalDataMediaUrl.original(resourceId)

    /**
     * Explicit maintenance only. Published originals are protected by reference; cache clearing
     * never calls this method.
     */
    fun sweepUnreferencedMedia(): Int = media.sweepUnreferenced { store.isMediaReferenced(it) }

    fun clearTransientCache(): LocalCacheClearReport = media.clearTransientCache()

    fun exportBackup(destination: File, now: Long = System.currentTimeMillis()): LocalBackupManifest =
        backup.export(destination, now)

    fun restoreBackup(
        source: File,
        options: LocalRestoreOptions = LocalRestoreOptions(),
    ): LocalRestoreReport = backup.restore(source, options)

    override fun close() {
        database.close()
    }

    private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw LocalDataException(LocalDataErrorCode.PAYLOAD_TOO_LARGE, "Image is too large")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
