/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localdata

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

data class LocalImageInfo(
    val mime: String,
    val width: Int,
    val height: Int,
)

/** Injected so media validation can be exercised without a graphics device. */
fun interface LocalImageInspector {
    fun inspect(bytes: ByteArray, mimeHint: String?): LocalImageInfo
}

fun interface LocalThumbnailer {
    fun create(source: File, target: File, maxPixels: Int): Boolean
}

data class LocalImportedMedia(
    val media: LocalMediaDto,
    val created: Boolean,
)

data class LocalMediaResponse(
    val file: File,
    val mime: String,
    val length: Long,
)

data class LocalCacheClearReport(
    val thumbnailsDeleted: Int,
    val temporaryFilesDeleted: Int,
    val bytesFreed: Long,
)

class LocalDataMediaStore(
    private val database: LocalDataDatabase,
    private val inspector: LocalImageInspector = AndroidImageInspector(),
    private val thumbnailer: LocalThumbnailer = AndroidThumbnailer(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Imports bytes that the host obtained from a picker, chat attachment, or image plugin. The
     * plugin page never calls this with a path; it only receives the returned resource id.
     */
    fun importImage(
        bytes: ByteArray,
        mimeHint: String? = null,
        displayName: String? = null,
        now: Long = clock(),
    ): LocalImportedMedia {
        if (bytes.size < 16) throw invalid("Image is empty")
        if (bytes.size > LocalDataApi.MAX_MEDIA_BYTES) {
            throw LocalDataException(LocalDataErrorCode.PAYLOAD_TOO_LARGE, "Image is too large")
        }
        val info = try {
            inspector.inspect(bytes, mimeHint)
        } catch (error: Exception) {
            throw invalid("Unsupported or corrupt image")
        }
        if (info.mime !in ALLOWED_MIME) throw invalid("Unsupported image type")
        if (info.width !in 1..MAX_IMAGE_EDGE || info.height !in 1..MAX_IMAGE_EDGE) {
            throw invalid("Invalid image size")
        }
        if (info.width.toLong() * info.height > MAX_IMAGE_PIXELS) {
            throw invalid("Image resolution is too high")
        }
        val hash = sha256Hex(bytes)
        database.readable().rawQuery(
            "SELECT resource_id, mime, byte_size, width, height FROM media_assets WHERE sha256 = ? LIMIT 1",
            arrayOf(hash),
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val existingId = cursor.text("resource_id")
                val existingFile = originalFile(existingId)
                if (existingFile != null && !existingFile.exists()) {
                    writeAtomically(existingFile, bytes)
                }
                return LocalImportedMedia(mediaDto(cursor), created = false)
            }
        }

        val resourceId = "ldm_" + UUID.randomUUID().toString().replace("-", "")
        val extension = EXTENSIONS.getValue(info.mime)
        val fileName = "$resourceId.$extension"
        database.paths.ensureDirectories()
        val target = File(database.paths.originalsDir, fileName)
        writeAtomically(target, bytes)
        try {
            database.transaction { db ->
                db.insertOrThrow(
                    "media_assets",
                    null,
                    ContentValues().apply {
                        put("resource_id", resourceId)
                        put("mime", info.mime)
                        put("byte_size", bytes.size.toLong())
                        put("width", info.width)
                        put("height", info.height)
                        put("sha256", hash)
                        put("display_name", sanitizeDisplayName(displayName))
                        put("file_name", fileName)
                        put("created_at", now)
                    },
                )
            }
        } catch (error: Exception) {
            target.delete()
            throw error
        }
        ensureThumbnail(resourceId)
        val media = mediaMetadata(resourceId)
            ?: throw LocalDataException(LocalDataErrorCode.INTERNAL, "Image metadata was not stored")
        return LocalImportedMedia(media, created = true)
    }

    fun mediaMetadata(resourceId: String): LocalMediaDto? {
        if (!LocalDataValidation.isSafeMediaId(resourceId)) return null
        database.readable().rawQuery(
            "SELECT resource_id, mime, byte_size, width, height FROM media_assets WHERE resource_id = ? LIMIT 1",
            arrayOf(resourceId),
        ).use { cursor ->
            return if (cursor.moveToFirst()) mediaDto(cursor) else null
        }
    }

    fun openOriginal(resourceId: String): LocalMediaResponse? {
        val row = mediaRow(resourceId) ?: return null
        val file = originalFile(resourceId) ?: return null
        if (!file.isFile) return null
        return LocalMediaResponse(file = file, mime = row.mime, length = file.length())
    }

    fun ensureThumbnail(resourceId: String): File? {
        val row = mediaRow(resourceId) ?: return null
        val thumb = thumbnailFile(resourceId)
        if (thumb.isFile && thumb.length() > 0) return thumb
        val original = originalFile(resourceId) ?: return null
        if (!original.isFile) return null
        database.paths.ensureDirectories()
        val temporary = File(database.paths.temporaryDir, "${UUID.randomUUID()}.thumb.jpg")
        val created = runCatching {
            thumbnailer.create(original, temporary, LocalDataApi.THUMBNAIL_MAX_PX)
        }.getOrDefault(false)
        if (!created || !temporary.isFile || temporary.length() == 0L) {
            temporary.delete()
            return null
        }
        if (!temporary.renameTo(thumb)) {
            runCatching { temporary.copyTo(thumb, overwrite = true) }
            temporary.delete()
        }
        if (!thumb.isFile || thumb.length() == 0L) {
            thumb.delete()
            return null
        }
        return thumb
    }

    fun openThumbnail(resourceId: String): LocalMediaResponse? {
        val row = mediaRow(resourceId) ?: return null
        val file = ensureThumbnail(resourceId) ?: return null
        return LocalMediaResponse(file = file, mime = "image/jpeg", length = file.length())
    }

    /** Resolves only the two controlled variants, never a path supplied by the page. */
    fun openUrl(url: String?): LocalMediaResponse? {
        val parsed = LocalDataMediaUrl.parse(url) ?: return null
        return when (parsed.variant) {
            LocalMediaVariant.ORIGINAL -> openOriginal(parsed.resourceId)
            LocalMediaVariant.THUMB -> openThumbnail(parsed.resourceId)
        }
    }

    fun originalFile(resourceId: String): File? {
        if (!LocalDataValidation.isSafeMediaId(resourceId)) return null
        val row = mediaRow(resourceId) ?: return null
        return safeMediaFile(database.paths.originalsDir, row.fileName)
    }

    /** Deleting an original is allowed only through the explicit reference-aware sweep. */
    fun sweepUnreferenced(isReferenced: (String) -> Boolean): Int {
        val resources = mutableListOf<String>()
        database.readable().rawQuery("SELECT resource_id FROM media_assets", emptyArray()).use { cursor ->
            while (cursor.moveToNext()) resources += cursor.text("resource_id")
        }
        var deleted = 0
        resources.forEach { resourceId ->
            if (!isReferenced(resourceId)) {
                val row = mediaRow(resourceId)
                val original = row?.let { safeMediaFile(database.paths.originalsDir, it.fileName) }
                database.transaction { db ->
                    db.delete("media_assets", "resource_id = ?", arrayOf(resourceId))
                }
                original?.delete()
                thumbnailFile(resourceId).delete()
                deleted++
            }
        }
        return deleted
    }

    fun isAvailable(resourceId: String): Boolean =
        mediaRow(resourceId) != null && originalFile(resourceId)?.isFile == true

    /**
     * Ordinary cache maintenance. Thumbnails and temporary files are disposable; published originals
     * are never touched here.
     */
    fun clearTransientCache(): LocalCacheClearReport {
        var thumbnails = 0
        var temporary = 0
        var bytes = 0L
        database.paths.thumbnailsDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                bytes += file.length()
                if (file.delete()) thumbnails++
            }
        }
        database.paths.temporaryDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                bytes += file.length()
                if (file.delete()) temporary++
            }
        }
        return LocalCacheClearReport(
            thumbnailsDeleted = thumbnails,
            temporaryFilesDeleted = temporary,
            bytesFreed = bytes,
        )
    }

    fun allMediaMetadata(): List<LocalMediaDto> {
        val result = mutableListOf<LocalMediaDto>()
        database.readable().rawQuery(
            "SELECT resource_id, mime, byte_size, width, height FROM media_assets ORDER BY created_at ASC",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) result += mediaDto(cursor)
        }
        return result
    }

    private fun thumbnailFile(resourceId: String): File {
        if (!LocalDataValidation.isSafeMediaId(resourceId)) {
            throw invalid("Invalid media id")
        }
        return File(database.paths.thumbnailsDir, "$resourceId.jpg")
    }

    private fun mediaRow(resourceId: String): MediaRow? {
        if (!LocalDataValidation.isSafeMediaId(resourceId)) return null
        database.readable().rawQuery(
            "SELECT resource_id, mime, byte_size, width, height, file_name FROM media_assets " +
                "WHERE resource_id = ? LIMIT 1",
            arrayOf(resourceId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return MediaRow(
                resourceId = cursor.text("resource_id"),
                mime = cursor.text("mime"),
                byteSize = cursor.long("byte_size"),
                width = cursor.int("width"),
                height = cursor.int("height"),
                fileName = cursor.text("file_name"),
            )
        }
    }

    private fun safeMediaFile(directory: File, fileName: String): File? {
        if (!FILE_NAME_PATTERN.matches(fileName)) return null
        val root = runCatching { directory.canonicalFile }.getOrNull() ?: return null
        val target = runCatching { File(root, fileName).canonicalFile }.getOrNull() ?: return null
        return target.takeIf { it.parentFile == root }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        database.paths.ensureDirectories()
        val temporary = File(database.paths.temporaryDir, "${UUID.randomUUID()}.import")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        if (!temporary.renameTo(target)) {
            runCatching { temporary.copyTo(target, overwrite = true) }
            temporary.delete()
        }
        if (!target.isFile || target.length() != bytes.size.toLong()) {
            target.delete()
            throw IOException("Could not persist image")
        }
    }

    private fun sanitizeDisplayName(name: String?): String? {
        val cleaned = name?.replace(Regex("[\\p{Cntrl}]"), "")?.trim()?.take(200)
        return cleaned?.takeIf { it.isNotEmpty() }
    }

    private fun invalid(message: String) =
        LocalDataException(LocalDataErrorCode.INVALID_REQUEST, message)

    private data class MediaRow(
        val resourceId: String,
        val mime: String,
        val byteSize: Long,
        val width: Int,
        val height: Int,
        val fileName: String,
    )

    companion object {
        const val MAX_IMAGE_EDGE = 8_192
        const val MAX_IMAGE_PIXELS = 16_777_216L
        private val FILE_NAME_PATTERN = Regex("^ldm_[A-Za-z0-9]{8,64}\\.(png|jpg|webp|gif)$")
        private val ALLOWED_MIME = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
        private val EXTENSIONS = mapOf(
            "image/png" to "png",
            "image/jpeg" to "jpg",
            "image/webp" to "webp",
            "image/gif" to "gif",
        )
    }
}

class AndroidImageInspector : LocalImageInspector {
    override fun inspect(bytes: ByteArray, mimeHint: String?): LocalImageInfo {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val mime = options.outMimeType ?: throw IllegalArgumentException("Unknown image type")
        return LocalImageInfo(mime = mime, width = options.outWidth, height = options.outHeight)
    }
}

class AndroidThumbnailer : LocalThumbnailer {
    override fun create(source: File, target: File, maxPixels: Int): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        val longest = max(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= maxPixels) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeFile(source.absolutePath, options) ?: return false
        val scale = min(1f, maxPixels.toFloat() / max(decoded.width, decoded.height).toFloat())
        val width = max(1, (decoded.width * scale).toInt())
        val height = max(1, (decoded.height * scale).toInt())
        val scaled = if (width == decoded.width && height == decoded.height) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, width, height, true)
        }
        return try {
            FileOutputStream(target).use { output ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, output)
            }
            target.length() > 0
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }
}
