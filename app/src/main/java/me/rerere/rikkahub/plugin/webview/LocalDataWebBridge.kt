package me.rerere.rikkahub.plugin.webview

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.webkit.WebResourceResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.plugin.localdata.*
import me.rerere.rikkahub.plugin.localtasks.LocalTaskHosts
import me.rerere.rikkahub.plugin.localtasks.LocalTaskService
import me.rerere.rikkahub.plugin.localtasks.LocalTaskBridge
import me.rerere.rikkahub.plugin.localtasks.PluginImageToolResultSource
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.data.ai.UsageRecorder
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.pages.chat.LOCAL_MOMENTS_PLUGIN_ID
import me.rerere.rikkahub.ui.theme.WebThemeTokens
import me.rerere.rikkahub.ui.theme.toPackageSurfaceTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.manager.PluginManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

/** Native-owned grant storage. Plugin data/config APIs cannot modify these preferences. */
internal class LocalDataWebBridge(
    private val context: Context,
    private val plugin: PluginInfo,
    private val settingsStore: SettingsStore,
    private val usageRecorder: UsageRecorder,
    private val filesManager: FilesManager,
    private val theme: () -> Map<String, String>,
) : Closeable {
    private val preferences = context.getSharedPreferences("localdata_host_grants", Context.MODE_PRIVATE)
    private val declared = LocalDataGrant.fromManifest(plugin.manifest.permissions).declaredNames()
    private var session: LocalDataSession? = null
    private var closed = false

    fun requestedCapabilities(): Set<String> = declared
    fun grantedCapabilities(): Set<String> = approvedLocalDataCapabilities(
        declared, preferences.getStringSet(plugin.manifest.id, emptySet()).orEmpty(),
    )
    fun needsConsent(): Boolean = !grantedCapabilities().containsAll(declared)

    @Synchronized
    fun approve() {
        val prior = preferences.getStringSet(plugin.manifest.id, emptySet()).orEmpty()
        val next = prior.union(declared)
        if (next != prior) {
            LocalTaskHosts.release(plugin.manifest.id)
            synchronized(taskGrantSignatures) { taskGrantSignatures.remove(plugin.manifest.id) }
            session?.close()
            session = null
        }
        preferences.edit().putStringSet(plugin.manifest.id, next).apply()
    }

    @Synchronized
    private fun session(): LocalDataSession {
        check(!closed) { "Page is closed" }
        return session ?: LocalDataSession(
            context.applicationContext, plugin.manifest.id,
            LocalDataGrant.fromManifest(grantedCapabilities()),
        ).also { session = it }
    }

    @Synchronized
    fun call(request: String): String {
        hostGeneratedImageReply(request)?.let { return it }
        val assistants = settingsStore.settingsFlow.value.assistants.map {
            LocalDataAssistantRef(it.id.toString(), it.name, safeAvatar(it.avatar))
        }
        return session().call(request, LocalDataActor.User, LocalAssistantDirectory.of(assistants),
            userDisplayName = settingsStore.settingsFlow.value.displaySetting.userNickname.ifBlank { "我" })
    }

    /**
     * Host-owned extension methods, reachable through the already-wired
     * `window.OrangeBridge.localData(requestJson)` interface. They expose only
     * the bundled local moments page, require `localdata.media`, and never read
     * plugin key-value storage: the candidates are the host's own generated
     * image files.
     */
    private fun hostGeneratedImageReply(request: String): String? {
        val parsed = runCatching { LocalDataRequestParser.parse(request) }.getOrNull() ?: return null
        return when (parsed.method) {
            METHOD_GENERATED_IMAGES_LIST -> {
                val limit = (parsed.params["limit"] as? JsonPrimitive)?.content?.toIntOrNull()
                    ?: HOST_IMAGE_LIMIT
                listHostGeneratedImages(limit)
            }
            METHOD_GENERATED_IMAGE_IMPORT -> {
                val id = (parsed.params["id"] as? JsonPrimitive)?.content.orEmpty()
                importHostGeneratedImage(id)
            }
            else -> null
        }
    }

    /**
     * Lists generated images that already live in the host's images directory
     * (chat `generate_image`, plugin tool results, the image generation page).
     * Returns opaque ids, timestamps, byte sizes and parsed dimensions only:
     * never paths, file names or pixels.
     */
    @Synchronized
    fun listHostGeneratedImages(limit: Int = HOST_IMAGE_LIMIT): String {
        hostImageAccessDenial()?.let { return it }
        val listing = hostGeneratedImages.list(limit)
        val items = JSONArray()
        listing.items.forEach { image ->
            val item = JSONObject()
                .put("id", image.id)
                .put("source", image.source)
                .put("createdAt", image.lastModified)
                .put("byteSize", image.bytes)
            image.width?.let { item.put("width", it) }
            image.height?.let { item.put("height", it) }
            items.put(item)
        }
        return localDataSuccess(
            JSONObject()
                .put("items", items)
                .put("truncated", listing.total > listing.items.size),
        ).toString()
    }

    /**
     * Copies one already-persisted generated image into this plugin's media
     * store after the page's explicit user confirmation. Never generates an
     * image, never calls a model and never consumes an image key.
     */
    @Synchronized
    fun importHostGeneratedImage(id: String): String {
        hostImageAccessDenial()?.let { return it }
        val image = hostGeneratedImages.find(id)
            ?: return localDataFailure("not_found", "That generated image is no longer available").toString()
        return try {
            val bytes = hostGeneratedImages.readBytes(image)
            val media = JSONObject(Json.encodeToString(session().importImageBytes(bytes, image.mime).media))
            if (!media.has("thumbnailUrl")) media.put("thumbnailUrl", media.optString("thumbUrl"))
            localDataSuccess(JSONObject().put("media", media)).toString()
        } catch (_: Exception) {
            localDataFailure(
                "unavailable",
                "The existing image could not be imported; do not generate again",
            ).toString()
        }
    }

    private fun hostImageAccessDenial(): String? {
        if (closed) return localDataFailure("unavailable", "Page is closed").toString()
        if (plugin.manifest.id != LOCAL_MOMENTS_PLUGIN_ID) {
            return localDataFailure(
                "capability_denied",
                "Host image import is limited to the bundled local moments plugin",
            ).toString()
        }
        if ("localdata.media" !in grantedCapabilities()) {
            return localDataFailure("capability_denied", "Media capability is not granted").toString()
        }
        return null
    }

    @Synchronized
    fun importImages(uris: List<Uri>): JSONObject {
        requireMediaGrant()
        val media = JSONArray()
        uris.take(LocalDataApi.MAX_MEDIA_PER_POST).forEach { uri ->
            // URI comes exclusively from the native picker, never from a JavaScript parameter.
            val imported = session().importImageFromUri(uri)
            val item = JSONObject(Json.encodeToString(imported.media))
            if (!item.has("thumbnailUrl")) item.put("thumbnailUrl", item.optString("thumbUrl"))
            media.put(item)
        }
        return localDataSuccess(JSONObject().put("media", media))
    }

    /** Only native tool-result code may call this; never exposed as a JS bytes/path method. */
    @Synchronized
    fun importGeneratedImage(bytes: ByteArray, mime: String?): String {
        requireMediaGrant()
        return Json.encodeToString(session().importImageBytes(bytes, mime).media)
    }

    /** Imports the existing _orange_image tool output; never starts another generation. */
    @Synchronized
    fun importToolImage(producerPluginId: String, resultJson: String): String {
        val root = runCatching { JSONObject(resultJson) }.getOrNull() ?: return resultJson
        val descriptor = root.optJSONObject("_orange_image") ?: return resultJson
        if ("localdata.media" !in grantedCapabilities()) return resultJson
        return try {
            require(descriptor.optInt("version") == 1)
            val key = descriptor.optString("asset_key")
            require(Regex("[A-Za-z0-9_.-]{1,160}").matches(key))
            val store = me.rerere.rikkahub.plugin.data.PluginDataStore(context, producerPluginId)
            val metadata = store.getData(key) ?: error("Missing image")
            require(metadata.length <= 4096)
            val count = JSONObject(metadata).optInt("chunks")
            require(count in 1..700)
            var index = 0
            var encodedLength = 0
            val chunks = object : java.util.Enumeration<java.io.InputStream> {
                override fun hasMoreElements() = index < count
                override fun nextElement(): java.io.InputStream {
                    val chunk = store.getData("$key.${index++}") ?: error("Missing chunk")
                    encodedLength += chunk.length
                    require(chunk.length <= 24000 && encodedLength <= 16000000)
                    return chunk.toByteArray(Charsets.ISO_8859_1).inputStream()
                }
            }
            val bytes = android.util.Base64InputStream(java.io.SequenceInputStream(chunks), android.util.Base64.DEFAULT)
                .use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val countRead = input.read(buffer); if (countRead < 0) break
                        check(output.size() + countRead <= LocalDataApi.MAX_MEDIA_BYTES)
                        output.write(buffer, 0, countRead)
                    }
                    output.toByteArray()
                }
            val media = JSONObject(importGeneratedImage(bytes, null))
            media.put("thumbnailUrl", media.optString("thumbUrl"))
            root.put("localDataMedia", media)
            root.toString()
        } catch (_: Exception) {
            root.put("localDataMediaError", "Existing image could not be imported; do not generate again").toString()
        }
    }

    @Synchronized
    fun tasks(): LocalTaskService {
        val granted = grantedCapabilities()
        check("localdata.read" in granted) { "Read permission is required" }
        val manager = org.koin.java.KoinJavaComponent.get<PluginManager>(PluginManager::class.java)
        val installed = manager.plugins.value.firstOrNull { info -> info.manifest.id == plugin.manifest.id }
        check(installed?.isEnabled == true && installed.loadError == null) { "Plugin is not enabled" }
        val signature = granted.sorted().joinToString(",")
        val oldSignature = synchronized(taskGrantSignatures) { taskGrantSignatures[plugin.manifest.id] }
        if (oldSignature != null && oldSignature != signature) LocalTaskHosts.release(plugin.manifest.id)
        synchronized(taskGrantSignatures) { taskGrantSignatures[plugin.manifest.id] = signature }
        return LocalTaskHosts.obtain(context.applicationContext, plugin.manifest.id,
            LocalDataGrant.fromManifest(granted), settingsStore,
            org.koin.java.KoinJavaComponent.get(ProviderManager::class.java), usageRecorder, processTaskScope,
            imageSourceFactory = { taskSession ->
                PluginImageToolResultSource(context.applicationContext, filesManager, taskSession)
            })
    }

    suspend fun taskCall(request: String): String = LocalTaskBridge(tasks()).dispatch(request)

    fun themeTokens(): Map<String, String> {
        val base = theme()
        // A wired Compose call site (WebThemeTokens.current()) already resolved
        // the package roles against the live theme; never replace them with a
        // second, possibly stale, dark-mode decision.
        if (WebThemeTokens.CARD_BACKGROUND in base) return base
        return runCatching { packageThemeTokens(base) }.getOrDefault(base)
    }

    /**
     * Fallback for a call site that still only forwards Material roles: resolve
     * the active package theme exactly like Theme.kt (package selected, dynamic
     * colour off) and append its declaration-only roles. User text overrides and
     * `interfaceSurfaceOpacity` keep priority inside the helpers.
     */
    private fun packageThemeTokens(base: Map<String, String>): Map<String, String> {
        val settings = settingsStore.settingsFlow.value
        if (settings.dynamicColor) return base
        val packageTokens = settings.customThemes
            .firstOrNull { it.id == settings.themeId }
            ?.packageTokens
            ?: return base
        val display = settings.displaySetting
        val surfaces = packageTokens.toPackageSurfaceTokens(
            darkMode = resolveDarkTheme(
                colorMode = context.readStringPreference("colorMode", "SYSTEM"),
                systemNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES,
            ),
            mutedTextOverride = display.globalTextColor?.toComposeColor(),
        )
        return WebThemeTokens.appendPackageTokens(
            base = base,
            surfaces = surfaces,
            corners = WebThemeTokens.cornersFromPackage(packageTokens.shape),
            surfaceOpacityPercent = display.interfaceSurfaceOpacity,
        )
    }

    companion object {
        // Reachable through the existing localData(requestJson) bridge; the page
        // still owns the user confirmation before calling the import method.
        const val METHOD_GENERATED_IMAGES_LIST = "localdata.generated_images_list"
        const val METHOD_GENERATED_IMAGE_IMPORT = "localdata.generated_image_import"
        const val HOST_IMAGE_LIMIT = 24

        // Explicit task starts may continue while a page is closed, only for this process lifetime.
        private val processTaskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val taskGrantSignatures = mutableMapOf<String, String>()
    }

    private val hostGeneratedImages by lazy {
        HostGeneratedImageLibrary(filesManager.getImagesDir())
    }

    private fun hostAvatar(avatar: Avatar): String? {
        if (avatar !is Avatar.Image || "localdata.media" !in grantedCapabilities()) return safeAvatar(avatar)
        val uri = Uri.parse(avatar.url)
        if (uri.scheme !in setOf("file", "content")) return safeAvatar(avatar)
        return runCatching { session().importImageFromUri(uri).media.thumbUrl }.getOrNull()
    }

    @Synchronized
    fun hostInfo(): JSONObject {
        check("localdata.read" in grantedCapabilities()) { "Read capability is not granted" }
        val settings = settingsStore.settingsFlow.value
        val assistants = JSONArray()
        settings.assistants.forEach { assistant ->
            assistants.put(JSONObject().put("id", assistant.id.toString()).put("name", assistant.name)
                .put("avatar", hostAvatar(assistant.avatar) ?: JSONObject.NULL))
        }
        return localDataSuccess(JSONObject()
            .put("user", JSONObject().put("name", settings.displaySetting.userNickname.ifBlank { "我" })
                .put("avatar", hostAvatar(settings.displaySetting.userAvatar) ?: JSONObject.NULL))
            .put("assistants", assistants)
            .put("theme", JSONObject(themeTokens()))
            .put("capabilities", JSONArray(grantedCapabilities().sorted())))
    }

    @Synchronized
    fun openMedia(url: String): WebResourceResponse {
        if ("localdata.media" !in grantedCapabilities()) return deniedMedia()
        return runCatching {
            val media = session().openMedia(url) ?: return deniedMedia()
            WebResourceResponse(media.mime, null, 200, "OK", mapOf(
                "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff",
            ), media.file.inputStream())
        }.getOrElse { deniedMedia() }
    }

    @Synchronized
    fun exportBackup(destination: File) {
        requireBackupGrant()
        session().exportBackup(destination)
    }

    @Synchronized
    fun restoreBackup(source: File) {
        requireBackupGrant()
        // Default restore is non-destructive and performs format/schema/hash checks in localdata.
        LocalTaskHosts.release(plugin.manifest.id)
        session().restoreBackup(source, LocalRestoreOptions())
    }

    private fun requireMediaGrant() {
        check("localdata.media" in grantedCapabilities()) { "Media capability is not granted" }
    }
    private fun requireBackupGrant() {
        check("localdata.backup" in grantedCapabilities()) { "Backup capability is not granted" }
    }
    @Synchronized override fun close() { closed = true; session?.close(); session = null }
}

private fun safeAvatar(avatar: Avatar): String? = when (avatar) {
    Avatar.Dummy -> null
    is Avatar.Emoji -> avatar.content
    is Avatar.Image -> avatar.url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
}

internal fun localDataSuccess(data: JSONObject): JSONObject =
    JSONObject().put("version", 1).put("ok", true).put("data", data)

internal fun localDataFailure(code: String, message: String): JSONObject =
    JSONObject().put("version", 1).put("ok", false).put("error", JSONObject()
        .put("code", code).put("message", message).put("retryable", false))

internal fun deniedMedia(): WebResourceResponse = WebResourceResponse(
    "text/plain", "UTF-8", 404, "Not Found", mapOf("Cache-Control" to "no-store"),
    ByteArrayInputStream(ByteArray(0)),
)

/** Persisted `colorMode` wins over the platform night mode, same as the Compose theme. */
internal fun resolveDarkTheme(colorMode: String?, systemNight: Boolean): Boolean = when (colorMode) {
    "LIGHT" -> false
    "DARK" -> true
    else -> systemNight
}

private val generatedImageStem = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,110}")
private val generatedImageExtensions = setOf("png", "jpg", "jpeg", "webp")
private val generatedImageId = Regex("[a-f0-9]{64}")

/** Simple-name gate: no separators, bounded, known image extension. */
internal fun isGeneratedImageName(name: String): Boolean {
    if (name.length !in 5..120) return false
    if (name.any { it == '/' || it == '\\' }) return false
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) return false
    if (name.substring(dot + 1).lowercase() !in generatedImageExtensions) return false
    return generatedImageStem.matches(name.substring(0, dot))
}

/**
 * Host-owned view over the images directory that the app itself writes
 * (`chat_*` from the chat tool, `plugin_*` from plugin tool results, and
 * `<timestamp>_<model>_<i>` from the image generation page).
 *
 * A plugin page never sees paths or file names: it gets a SHA-256 digest of the
 * name as an opaque id, and the host re-resolves that id against the same
 * directory before copying bytes. Plugin key-value storage is never touched.
 */
internal class HostGeneratedImageLibrary(
    private val imagesDir: File,
    private val maxItems: Int = 24,
    private val maxBytes: Long = LocalDataApi.MAX_MEDIA_BYTES.toLong(),
) {
    data class Image(
        val id: String,
        val file: File,
        val mime: String,
        val bytes: Long,
        val lastModified: Long,
        val width: Int?,
        val height: Int?,
        val source: String,
    )

    data class Listing(val items: List<Image>, val total: Int)

    fun list(): Listing = list(maxItems)

    fun list(limit: Int): Listing {
        val bounded = limit.coerceIn(0, maxItems)
        val all = scan()
        return Listing(all.take(bounded), all.size)
    }

    fun find(id: String): Image? {
        if (!generatedImageId.matches(id)) return null
        return scan().firstOrNull { it.id == id }
    }

    fun readBytes(image: Image): ByteArray {
        require(image.file.length() in 1..maxBytes) { "Image changed on disk" }
        val output = ByteArrayOutputStream()
        image.file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= maxBytes) { "Image is too large" }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun scan(): List<Image> {
        val files = imagesDir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile && isGeneratedImageName(it.name) }
            .mapNotNull { runCatching { image(it) }.getOrNull() }
            .sortedWith(compareByDescending<Image> { it.lastModified }.thenByDescending { it.id })
            .toList()
    }

    private fun image(file: File): Image? {
        val length = file.length()
        if (length !in 1..maxBytes) return null
        // Re-resolve and keep the file inside the host images directory; a symlink
        // or odd directory entry must not turn into arbitrary host file reads.
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val root = runCatching { imagesDir.canonicalFile }.getOrNull() ?: return null
        if (canonical.parentFile != root) return null
        val mime = mimeFor(file.name) ?: return null
        val header = runCatching { readHeader(file) }.getOrNull() ?: return null
        if (!ImageHeaders.isRecognized(header)) return null
        val dimensions = runCatching { ImageHeaders.dimensions(header) }.getOrNull()
        return Image(
            id = sha256Hex(file.name),
            file = file,
            mime = mime,
            bytes = length,
            lastModified = file.lastModified(),
            width = dimensions?.first,
            height = dimensions?.second,
            source = when {
                file.name.startsWith("chat_") -> "chat_generated"
                file.name.startsWith("plugin_") -> "plugin_tool"
                else -> "image_generation"
            },
        )
    }

    private fun readHeader(file: File): ByteArray = file.inputStream().use { input ->
        val buffer = ByteArray(HEADER_BYTES)
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count < 0) break
            read += count
        }
        buffer.copyOf(read)
    }

    private fun mimeFor(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> null
    }

    private companion object {
        const val HEADER_BYTES = 64 * 1024
    }
}

internal fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** Minimal PNG/JPEG/GIF dimension reader plus a cheap real-image signature check. */
internal object ImageHeaders {
    fun isRecognized(bytes: ByteArray): Boolean = pngSignature(bytes) || jpegSignature(bytes) ||
        webpSignature(bytes) || gifSignature(bytes)

    fun dimensions(bytes: ByteArray): Pair<Int, Int>? = png(bytes) ?: jpeg(bytes) ?: gif(bytes)

    private fun pngSignature(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        for (index in signature.indices) if (bytes[index] != signature[index]) return false
        return true
    }

    private fun jpegSignature(bytes: ByteArray): Boolean =
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

    private fun webpSignature(bytes: ByteArray): Boolean =
        bytes.size >= 12 && matchesAscii(bytes, 0, "RIFF") && matchesAscii(bytes, 8, "WEBP")

    private fun gifSignature(bytes: ByteArray): Boolean =
        matchesAscii(bytes, 0, "GIF87a") || matchesAscii(bytes, 0, "GIF89a")

    private fun matchesAscii(bytes: ByteArray, offset: Int, text: String): Boolean {
        if (bytes.size < offset + text.length) return false
        for (index in text.indices) {
            if (bytes[offset + index] != text[index].code.toByte()) return false
        }
        return true
    }

    private fun png(bytes: ByteArray): Pair<Int, Int>? {
        if (!pngSignature(bytes) || bytes.size < 24) return null
        if (bytes[12] != 'I'.code.toByte() || bytes[13] != 'H'.code.toByte() ||
            bytes[14] != 'D'.code.toByte() || bytes[15] != 'R'.code.toByte()
        ) return null
        return valid(readInt(bytes, 16), readInt(bytes, 20))
    }

    private fun gif(bytes: ByteArray): Pair<Int, Int>? {
        if (!gifSignature(bytes) || bytes.size < 10) return null
        val width = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)
        val height = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
        return valid(width, height)
    }

    private fun jpeg(bytes: ByteArray): Pair<Int, Int>? {
        if (!jpegSignature(bytes)) return null
        var index = 2
        while (index + 3 < bytes.size) {
            if (bytes[index] != 0xFF.toByte()) {
                index++
                continue
            }
            val marker = bytes[index + 1].toInt() and 0xFF
            if (marker == 0xFF || marker == 0x01 || marker in 0xD0..0xD7) {
                index += 2
                continue
            }
            if (marker == 0xD9) return null
            if (index + 4 > bytes.size) return null
            val length = readShort(bytes, index + 2)
            if (length < 2) return null
            if (isStartOfFrame(marker)) {
                if (index + 9 > bytes.size) return null
                return valid(readShort(bytes, index + 7), readShort(bytes, index + 5))
            }
            index += 2 + length
        }
        return null
    }

    private fun isStartOfFrame(marker: Int): Boolean =
        marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC

    private fun readShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        (readShort(bytes, offset) shl 16) or readShort(bytes, offset + 2)

    private fun valid(width: Int, height: Int): Pair<Int, Int>? =
        if (width in 1..8192 && height in 1..8192) width to height else null
}

