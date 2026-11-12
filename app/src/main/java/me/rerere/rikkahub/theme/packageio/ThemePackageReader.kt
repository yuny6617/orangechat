package me.rerere.rikkahub.theme.packageio

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.files.SafeFileResolver
import java.io.File
import java.util.zip.ZipInputStream

/** Reads bounded JSON entries directly from the archive; no archive path is ever written to disk. */
class ThemePackageReader(
    private val maxEntries: Int = 25,
    private val maxEntryBytes: Long = 128_000,
    private val maxArchiveBytes: Long = 512_000,
) {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }

    fun read(zipFile: File): ValidatedThemePackage {
        require(zipFile.isFile && zipFile.length() in 1..maxArchiveBytes) { "Theme archive size is invalid" }
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        var count = 0
        ZipInputStream(zipFile.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                count++
                require(count <= maxEntries) { "Theme archive has too many entries" }
                val normalized = normalizeEntry(entry.name)
                require(!entry.isDirectory) { "Directory entries are not allowed" }
                require(normalized.endsWith(".json")) { "Only JSON entries are allowed" }
                require(normalized == "manifest.json" || normalized.matches(Regex("themes/[a-z0-9][a-z0-9._-]{0,63}\\.json"))) {
                    "Unsupported archive entry"
                }
                require(entries[normalized] == null) { "Duplicate archive entry" }
                val bytes = input.readBounded(maxEntryBytes)
                total += bytes.size
                require(total <= maxArchiveBytes) { "Theme archive expands beyond limit" }
                entries[normalized] = bytes
                input.closeEntry()
            }
        }
        val manifestBytes = requireNotNull(entries["manifest.json"]) { "Missing manifest.json" }
        val manifest = json.decodeFromString<ThemePackageManifest>(manifestBytes.decodeJson())
        require(manifest.schemaVersion == ThemeTokenSchema.PACKAGE_SCHEMA_VERSION) { "Unsupported package schema" }
        require(manifest.id.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "Invalid package id" }
        require(manifest.name.isNotBlank() && manifest.name.length <= 80 && manifest.version.isNotBlank() && manifest.version.length <= 40) {
            "Invalid package metadata"
        }
        require(manifest.author.length <= 80) { "Invalid author" }
        require(manifest.themes.isNotEmpty() && manifest.themes.size <= 24 && manifest.themes.distinct().size == manifest.themes.size) {
            "Invalid theme list"
        }
        val expectedPaths = setOf("manifest.json") + manifest.themes.map { themeId ->
            require(themeId.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "Invalid theme path id" }
            "themes/$themeId.json"
        }
        require(entries.keys == expectedPaths) { "Archive contains undeclared or missing entries" }
        val themes = manifest.themes.associateWith { themeId ->
            val theme = json.decodeFromString<ThemeTokens>(requireNotNull(entries["themes/$themeId.json"]).decodeJson())
            require(theme.id == themeId) { "Theme id does not match filename" }
            ThemeTokenSchema.validate(theme)
            theme
        }
        return ValidatedThemePackage(manifest, themes)
    }

    private fun normalizeEntry(name: String): String {
        require(name.isNotBlank() && '\\' !in name && '\u0000' !in name) { "Invalid archive path" }
        require(!name.startsWith('/') && !name.matches(Regex("^[A-Za-z]:.*"))) { "Absolute archive path is not allowed" }
        require(SafeFileResolver.resolveInside(File("/theme-root"), name) != null) { "Invalid archive path" }
        val pieces = name.split('/')
        require(pieces.none { it.isEmpty() || it == "." || it == ".." }) { "Invalid archive path" }
        return pieces.joinToString("/")
    }

    private fun java.util.zip.ZipInputStream.readBounded(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var size = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            size += read
            require(size <= limit) { "Theme entry expands beyond limit" }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun ByteArray.decodeJson(): String {
        val text = toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(this)) { "Theme JSON must be valid UTF-8" }
        require(!text.startsWith('\uFEFF')) { "Theme JSON must not start with a byte-order mark" }
        StrictJson.requireUniqueKeys(text)
        return text
    }
}
