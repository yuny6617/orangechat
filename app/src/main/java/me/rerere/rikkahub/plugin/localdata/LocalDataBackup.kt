/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localdata

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable

@Serializable
data class LocalBackupAuthor(
    val kind: AuthorKind,
    val id: String,
    val name: String,
    val avatar: String? = null,
    val capturedAt: Long,
)

@Serializable
data class LocalBackupPost(
    val id: String,
    val authorKind: AuthorKind,
    val authorId: String,
    val authorName: String,
    val authorAvatar: String? = null,
    val body: String,
    val visibility: PostVisibility,
    val selectedAssistantIds: List<String> = emptyList(),
    val mediaIds: List<String> = emptyList(),
    val chatSnapshot: LocalChatSnapshotDto? = null,
    val clientKey: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class LocalBackupComment(
    val id: String,
    val postId: String,
    val authorKind: AuthorKind,
    val authorId: String,
    val authorName: String,
    val authorAvatar: String? = null,
    val body: String,
    val replyToId: String? = null,
    val clientKey: String? = null,
    val createdAt: Long,
)

@Serializable
data class LocalBackupLike(
    val postId: String,
    val actorKind: AuthorKind,
    val actorId: String,
    val createdAt: Long,
)

@Serializable
data class LocalBackupDraft(
    val id: String,
    val authorKind: AuthorKind,
    val authorId: String,
    val authorName: String,
    val authorAvatar: String? = null,
    val body: String,
    val visibility: PostVisibility,
    val selectedAssistantIds: List<String> = emptyList(),
    val mediaIds: List<String> = emptyList(),
    val chatSnapshot: LocalChatSnapshotDto? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class LocalBackupMedia(
    val resourceId: String,
    val mime: String,
    val byteSize: Long,
    val width: Int,
    val height: Int,
    val sha256: String,
    val displayName: String? = null,
    val fileName: String,
    val createdAt: Long,
)

@Serializable
data class LocalDataBackupPayload(
    val authors: List<LocalBackupAuthor> = emptyList(),
    val posts: List<LocalBackupPost> = emptyList(),
    val comments: List<LocalBackupComment> = emptyList(),
    val likes: List<LocalBackupLike> = emptyList(),
    val drafts: List<LocalBackupDraft> = emptyList(),
    val media: List<LocalBackupMedia> = emptyList(),
)

@Serializable
data class LocalBackupCounts(
    val authors: Int,
    val posts: Int,
    val comments: Int,
    val likes: Int,
    val drafts: Int,
    val media: Int,
)

@Serializable
data class LocalBackupMediaEntry(
    val resourceId: String,
    val sha256: String,
    val byteSize: Long,
)

@Serializable
data class LocalBackupManifest(
    val format: String = LocalDataApi.BACKUP_FORMAT,
    val formatVersion: Int = LocalDataApi.BACKUP_FORMAT_VERSION,
    val schemaVersion: Int,
    val pluginId: String,
    val createdAt: Long,
    val payloadSha256: String,
    val payloadBytes: Long,
    val counts: LocalBackupCounts,
    val media: List<LocalBackupMediaEntry> = emptyList(),
)

data class LocalRestoreOptions(
    val replaceExisting: Boolean = false,
    val regenerateIds: Boolean = true,
    val assistantIdMap: Map<String, String> = emptyMap(),
)

data class LocalRestoreReport(
    val authors: Int,
    val posts: Int,
    val comments: Int,
    val likes: Int,
    val drafts: Int,
    val media: Int,
    val regeneratedIds: Boolean,
)

/**
 * Versioned logical backup with integrity checks. The archive contains a canonical JSON payload,
 * a manifest with SHA-256/counts, and only the originals referenced by that payload. Model keys are
 * never part of the schema, so they cannot leak through this format.
 */
class LocalDataBackup(
    private val database: LocalDataDatabase,
    private val store: LocalDataStore,
    private val mediaStore: LocalDataMediaStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun export(destination: File, now: Long = clock()): LocalBackupManifest {
        database.paths.ensureDirectories()
        val snapshot = store.exportSnapshot()
        val referenced = store.referencedMediaIds()
        val missingReferenced = referenced.filter { !mediaStore.isAvailable(it) }
        if (missingReferenced.isNotEmpty()) {
            throw LocalDataException(
                LocalDataErrorCode.UNAVAILABLE,
                "Referenced media is missing from storage",
            )
        }
        val usableMedia = snapshot.media.filter { mediaStore.isAvailable(it.resourceId) }
        val payload = snapshot.copy(media = usableMedia)
        usableMedia.forEach { media ->
            val file = mediaStore.originalFile(media.resourceId)
                ?: throw LocalDataException(LocalDataErrorCode.UNAVAILABLE, "Media is missing")
            if (file.length() != media.byteSize || sha256Hex(file.readBytes()) != media.sha256) {
                throw LocalDataException(LocalDataErrorCode.UNAVAILABLE, "Media integrity check failed")
            }
        }
        val payloadBytes = LocalDataJson.json
            .encodeToString(LocalDataBackupPayload.serializer(), payload)
            .toByteArray(Charsets.UTF_8)
        if (payloadBytes.size > MAX_PAYLOAD_BYTES) {
            throw LocalDataException(LocalDataErrorCode.PAYLOAD_TOO_LARGE, "Backup payload is too large")
        }
        val counts = counts(payload)
        val manifest = LocalBackupManifest(
            schemaVersion = database.schemaVersion,
            pluginId = database.pluginId,
            createdAt = now,
            payloadSha256 = sha256Hex(payloadBytes),
            payloadBytes = payloadBytes.size.toLong(),
            counts = counts,
            media = usableMedia.map {
                LocalBackupMediaEntry(
                    resourceId = it.resourceId,
                    sha256 = it.sha256,
                    byteSize = it.byteSize,
                )
            },
        )
        val manifestBytes = LocalDataJson.json
            .encodeToString(LocalBackupManifest.serializer(), manifest)
            .toByteArray(Charsets.UTF_8)

        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile ?: database.paths.root, "${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { zip ->
                writeEntry(zip, MANIFEST_ENTRY, manifestBytes, now)
                writeEntry(zip, PAYLOAD_ENTRY, payloadBytes, now)
                usableMedia.forEach { media ->
                    val file = mediaStore.originalFile(media.resourceId)
                        ?: throw LocalDataException(LocalDataErrorCode.UNAVAILABLE, "Media is missing")
                    val entry = ZipEntry("$MEDIA_PREFIX${media.fileName}").apply { time = now }
                    zip.putNextEntry(entry)
                    file.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
        return manifest
    }

    fun restore(source: File, options: LocalRestoreOptions = LocalRestoreOptions()): LocalRestoreReport {
        if (!source.isFile) throw LocalDataException(LocalDataErrorCode.NOT_FOUND, "Backup file is missing")
        val empty = isEmpty()
        if (!empty && !options.replaceExisting) {
            throw LocalDataException(LocalDataErrorCode.CONFLICT, "Local data is not empty")
        }
        val restoredFiles = mutableListOf<File>()
        val restoreTemp = File(database.paths.restoreTempDir, UUID.randomUUID().toString())
        restoreTemp.mkdirs()
        try {
            ZipFile(source).use { zip ->
                val entries = zip.entries().toList()
                if (entries.size > MAX_ZIP_ENTRIES) {
                    throw LocalDataException(LocalDataErrorCode.PAYLOAD_TOO_LARGE, "Backup has too many entries")
                }
                if (entries.map { it.name }.toSet().size != entries.size) {
                    throw invalid("Backup has duplicate entries")
                }
                val byName = entries.associateBy { it.name }
                val manifestEntry = byName[MANIFEST_ENTRY]
                    ?: throw invalid("Backup manifest is missing")
                val payloadEntry = byName[PAYLOAD_ENTRY]
                    ?: throw invalid("Backup payload is missing")
                val manifestBytes = readBounded(zip, manifestEntry, MAX_MANIFEST_BYTES)
                val payloadBytes = readBounded(zip, payloadEntry, MAX_PAYLOAD_BYTES)
                val manifest = parseManifest(manifestBytes)
                validateManifest(manifest, payloadBytes)
                val payload = parsePayload(payloadBytes)
                if (counts(payload) != manifest.counts) {
                    throw invalid("Backup counts do not match the manifest")
                }
                validatePayload(payload)
                val mediaFiles = verifyAndExtractMedia(zip, manifest, payload, restoreTemp)
                val maps = buildIdMaps(payload, options)
                val remapped = remapPayload(payload, maps, options)
                validatePayload(remapped)
                if (!options.replaceExisting && !empty) {
                    throw LocalDataException(LocalDataErrorCode.CONFLICT, "Local data is not empty")
                }
                database.paths.ensureDirectories()
                val mediaById = payload.media.associateBy { it.resourceId }
                try {
                    mediaFiles.forEach { (oldResourceId, tempFile) ->
                        val media = mediaById.getValue(oldResourceId)
                        val newResourceId = maps.media.getValue(oldResourceId)
                        val extension = media.fileName.substringAfterLast('.', "jpg")
                        val target = File(database.paths.originalsDir, "$newResourceId.$extension")
                        if (!tempFile.renameTo(target)) {
                            tempFile.copyTo(target, overwrite = true)
                            tempFile.delete()
                        }
                        restoredFiles += target
                    }
                    store.replaceAll(remapped)
                } catch (error: Exception) {
                    restoredFiles.forEach { it.delete() }
                    throw error
                }
                return LocalRestoreReport(
                    authors = remapped.authors.size,
                    posts = remapped.posts.size,
                    comments = remapped.comments.size,
                    likes = remapped.likes.size,
                    drafts = remapped.drafts.size,
                    media = remapped.media.size,
                    regeneratedIds = options.regenerateIds,
                )
            }
        } catch (error: java.io.IOException) {
            throw invalid("Backup archive is invalid")
        } finally {
            restoreTemp.deleteRecursively()
        }
    }

    private fun isEmpty(): Boolean {
        val snapshot = store.exportSnapshot()
        return snapshot.authors.isEmpty() && snapshot.posts.isEmpty() && snapshot.comments.isEmpty() &&
            snapshot.likes.isEmpty() && snapshot.drafts.isEmpty() && snapshot.media.isEmpty()
    }

    private fun verifyAndExtractMedia(
        zip: ZipFile,
        manifest: LocalBackupManifest,
        payload: LocalDataBackupPayload,
        tempDir: File,
    ): Map<String, File> {
        val manifestById = manifest.media.associateBy { it.resourceId }
        if (manifestById.size != manifest.media.size) throw invalid("Duplicate media in manifest")
        val allowedNames = linkedSetOf(MANIFEST_ENTRY, PAYLOAD_ENTRY)
        val result = linkedMapOf<String, File>()
        payload.media.forEach { media ->
            if (!LocalDataValidation.isSafeMediaId(media.resourceId)) throw invalid("Invalid media id")
            if (!MEDIA_FILE_NAME_PATTERN.matches(media.fileName)) throw invalid("Invalid media file name")
            if (media.mime !in ALLOWED_MIME) throw invalid("Invalid media type")
            if (media.byteSize < 0 || media.byteSize > LocalDataApi.MAX_MEDIA_BYTES) {
                throw invalid("Invalid media size")
            }
            if (media.sha256.length != 64) throw invalid("Invalid media hash")
            val entryInfo = manifestById[media.resourceId]
                ?: throw invalid("Media is missing from the manifest")
            if (entryInfo.sha256 != media.sha256 || entryInfo.byteSize != media.byteSize) {
                throw invalid("Media manifest does not match payload")
            }
            val entryName = "$MEDIA_PREFIX${media.fileName}"
            allowedNames += entryName
            val entry = zip.getEntry(entryName) ?: throw invalid("Media entry is missing")
            val target = File(tempDir, media.resourceId)
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            zip.getInputStream(entry).use { input ->
                BufferedOutputStream(FileOutputStream(target)).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > media.byteSize || total > LocalDataApi.MAX_MEDIA_BYTES) {
                            throw LocalDataException(
                                LocalDataErrorCode.PAYLOAD_TOO_LARGE,
                                "Media entry is larger than declared",
                            )
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (total != media.byteSize) throw invalid("Media size does not match")
            if (digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } != media.sha256) {
                throw invalid("Media integrity check failed")
            }
            result[media.resourceId] = target
        }
        val extra = zip.entries().toList().filter { !it.isDirectory && it.name !in allowedNames }
        if (extra.isNotEmpty()) throw invalid("Backup contains unexpected entries")
        return result
    }

    private fun validateManifest(manifest: LocalBackupManifest, payloadBytes: ByteArray) {
        if (manifest.format != LocalDataApi.BACKUP_FORMAT) throw invalid("Unknown backup format")
        if (manifest.formatVersion != LocalDataApi.BACKUP_FORMAT_VERSION) {
            throw LocalDataException(LocalDataErrorCode.UNSUPPORTED_VERSION, "Unsupported backup version")
        }
        if (manifest.schemaVersion != database.schemaVersion) {
            throw LocalDataException(LocalDataErrorCode.UNSUPPORTED_VERSION, "Unsupported schema version")
        }
        if (manifest.pluginId != database.pluginId) {
            throw LocalDataException(LocalDataErrorCode.CONFLICT, "Backup belongs to a different plugin")
        }
        if (manifest.payloadBytes != payloadBytes.size.toLong()) throw invalid("Payload size does not match")
        if (manifest.payloadSha256 != sha256Hex(payloadBytes)) throw invalid("Payload integrity check failed")
    }

    private fun validatePayload(payload: LocalDataBackupPayload) {
        try {
            validatePayloadUnchecked(payload)
        } catch (error: IllegalArgumentException) {
            throw invalid(error.message ?: "Backup payload is invalid")
        }
    }

    private fun validatePayloadUnchecked(payload: LocalDataBackupPayload) {
        if (payload.posts.size > MAX_POSTS ||
            payload.comments.size > MAX_COMMENTS ||
            payload.likes.size > MAX_LIKES ||
            payload.drafts.size > MAX_DRAFTS ||
            payload.media.size > MAX_MEDIA ||
            payload.authors.size > MAX_AUTHORS
        ) {
            throw LocalDataException(LocalDataErrorCode.PAYLOAD_TOO_LARGE, "Backup has too many records")
        }
        val authorIds = linkedSetOf<String>()
        payload.authors.forEach { author ->
            if (!LocalDataValidation.isSafeAssistantId(author.id)) throw invalid("Invalid author id")
            if (author.name.length > 100) throw invalid("Author name is too long")
            if (!authorIds.add("${author.kind.wire()}:${author.id}")) throw invalid("Duplicate author")
        }
        val mediaIds = payload.media.mapTo(linkedSetOf()) { media ->
            if (!LocalDataValidation.isSafeMediaId(media.resourceId)) throw invalid("Invalid media id")
            if (!MEDIA_FILE_NAME_PATTERN.matches(media.fileName)) throw invalid("Invalid media file name")
            if (media.mime !in ALLOWED_MIME) throw invalid("Invalid media type")
            if (media.byteSize !in 1..LocalDataApi.MAX_MEDIA_BYTES.toLong()) throw invalid("Invalid media size")
            if (media.width !in 1..8192 || media.height !in 1..8192) throw invalid("Invalid media dimensions")
            if (!media.fileName.startsWith(media.resourceId + ".")) throw invalid("Media name mismatch")
            media.resourceId
        }
        if (mediaIds.size != payload.media.size) throw invalid("Duplicate media")
        fun checkMedia(ids: Collection<String>) {
            ids.forEach { if (it !in mediaIds) throw invalid("Referenced media is missing") }
        }
        val postIds = linkedSetOf<String>()
        payload.posts.forEach { post ->
            if (!LocalDataValidation.isSafeRecordId(post.id)) throw invalid("Invalid post id")
            if (!postIds.add(post.id)) throw invalid("Duplicate post")
            if (!LocalDataValidation.isSafeAssistantId(post.authorId)) throw invalid("Invalid post author")
            if (post.authorName.length > 100) throw invalid("Post author name is too long")
            if (post.body.length > LocalDataApi.MAX_BODY_CHARS) throw invalid("Post body is too long")
            if (post.selectedAssistantIds.size > LocalDataApi.MAX_SELECTED_ASSISTANTS) {
                throw invalid("Too many selected assistants")
            }
            if (post.visibility == PostVisibility.SELECTED && post.selectedAssistantIds.isEmpty()) {
                throw invalid("Selected post has no audience")
            }
            post.selectedAssistantIds.forEach {
                if (!LocalDataValidation.isSafeAssistantId(it)) throw invalid("Invalid selected assistant")
            }
            if (post.mediaIds.size > LocalDataApi.MAX_MEDIA_PER_POST) throw invalid("Too many post media")
            checkMedia(post.mediaIds)
            checkMedia(LocalDataChatSnapshots.mediaReferences(post.chatSnapshot))
            LocalDataChatSnapshots.sanitize(post.chatSnapshot)
        }
        val commentIds = linkedSetOf<String>()
        payload.comments.forEach { comment ->
            if (!LocalDataValidation.isSafeRecordId(comment.id)) throw invalid("Invalid comment id")
            if (!commentIds.add(comment.id)) throw invalid("Duplicate comment")
            if (comment.postId !in postIds) throw invalid("Comment points to a missing post")
            if (comment.body.isEmpty() || comment.body.length > LocalDataApi.MAX_COMMENT_CHARS) {
                throw invalid("Invalid comment body")
            }
            comment.replyToId?.let {
                if (!LocalDataValidation.isSafeRecordId(it)) throw invalid("Invalid reply target")
            }
        }
        val commentsById = payload.comments.associateBy { it.id }
        payload.comments.forEach { comment ->
            comment.replyToId?.let { replyTo ->
                val target = commentsById[replyTo] ?: throw invalid("Reply target is missing")
                if (target.postId != comment.postId) throw invalid("Reply target is on another post")
            }
        }
        val likeKeys = linkedSetOf<String>()
        payload.likes.forEach { like ->
            if (like.postId !in postIds) throw invalid("Like points to a missing post")
            if (!LocalDataValidation.isSafeAssistantId(like.actorId)) throw invalid("Invalid like actor")
            if (!likeKeys.add("${like.postId}:${like.actorKind.wire()}:${like.actorId}")) {
                throw invalid("Duplicate like")
            }
        }
        payload.drafts.forEach { draft ->
            if (!LocalDataValidation.isSafeRecordId(draft.id)) throw invalid("Invalid draft id")
            if (!LocalDataValidation.isSafeAssistantId(draft.authorId)) throw invalid("Invalid draft author")
            if (draft.body.length > LocalDataApi.MAX_BODY_CHARS) throw invalid("Draft body is too long")
            if (draft.visibility == PostVisibility.SELECTED && draft.selectedAssistantIds.isEmpty()) {
                throw invalid("Selected draft has no audience")
            }
            if (draft.selectedAssistantIds.size > LocalDataApi.MAX_SELECTED_ASSISTANTS) {
                throw invalid("Too many selected assistants")
            }
            draft.selectedAssistantIds.forEach {
                if (!LocalDataValidation.isSafeAssistantId(it)) throw invalid("Invalid selected assistant")
            }
            if (draft.mediaIds.size > LocalDataApi.MAX_MEDIA_PER_POST) throw invalid("Too many draft media")
            checkMedia(draft.mediaIds)
            checkMedia(LocalDataChatSnapshots.mediaReferences(draft.chatSnapshot))
            LocalDataChatSnapshots.sanitize(draft.chatSnapshot)
        }
    }

    private fun buildIdMaps(payload: LocalDataBackupPayload, options: LocalRestoreOptions): IdMaps {
        val assistantMap = options.assistantIdMap.mapValues { (old, new) ->
            if (!LocalDataValidation.isSafeAssistantId(old) ||
                !LocalDataValidation.isSafeAssistantId(new)
            ) {
                throw invalid("Invalid assistant id mapping")
            }
            new
        }
        fun map(ids: Collection<String>, prefix: String): Map<String, String> {
            if (!options.regenerateIds) return ids.associateWith { it }
            return ids.associateWith { newId(prefix) }
        }
        return IdMaps(
            media = map(payload.media.map { it.resourceId }, "ldm"),
            posts = map(payload.posts.map { it.id }, "p"),
            comments = map(payload.comments.map { it.id }, "c"),
            drafts = map(payload.drafts.map { it.id }, "d"),
            assistants = assistantMap,
        )
    }

    private fun remapPayload(
        payload: LocalDataBackupPayload,
        maps: IdMaps,
        options: LocalRestoreOptions,
    ): LocalDataBackupPayload {
        fun media(id: String): String = maps.media[id] ?: throw invalid("Unknown media reference")
        fun post(id: String): String = maps.posts[id] ?: throw invalid("Unknown post reference")
        fun comment(id: String): String = maps.comments[id] ?: throw invalid("Unknown comment reference")
        fun draft(id: String): String = maps.drafts[id] ?: throw invalid("Unknown draft reference")
        fun author(kind: AuthorKind, id: String): String =
            if (kind == AuthorKind.ASSISTANT) maps.assistants[id] ?: id else id

        fun snapshot(value: LocalChatSnapshotDto?): LocalChatSnapshotDto? = value?.let {
            it.copy(messages = it.messages.map { message ->
                message.copy(
                    media = message.media.map(::media),
                    avatarMediaId = message.avatarMediaId
                        ?.takeIf { LocalDataValidation.isSafeMediaId(it) }
                        ?.let(::media),
                )
            })
        }
        val media = payload.media.map { item ->
            val newId = media(item.resourceId)
            item.copy(
                resourceId = newId,
                fileName = newId + "." + item.fileName.substringAfterLast('.'),
            )
        }
        val posts = payload.posts.map { item ->
            item.copy(
                id = post(item.id),
                authorId = author(item.authorKind, item.authorId),
                selectedAssistantIds = item.selectedAssistantIds.map { id ->
                    if (id in maps.assistants) maps.assistants.getValue(id) else id
                },
                mediaIds = item.mediaIds.map(::media),
                chatSnapshot = snapshot(item.chatSnapshot),
            )
        }
        val comments = payload.comments.map { item ->
            item.copy(
                id = comment(item.id),
                postId = post(item.postId),
                authorId = author(item.authorKind, item.authorId),
                replyToId = item.replyToId?.let(::comment),
            )
        }
        val likes = payload.likes.map { item ->
            item.copy(
                postId = post(item.postId),
                actorId = author(item.actorKind, item.actorId),
            )
        }
        val drafts = payload.drafts.map { item ->
            item.copy(
                id = draft(item.id),
                authorId = author(item.authorKind, item.authorId),
                selectedAssistantIds = item.selectedAssistantIds.map { id ->
                    if (id in maps.assistants) maps.assistants.getValue(id) else id
                },
                mediaIds = item.mediaIds.map(::media),
                chatSnapshot = snapshot(item.chatSnapshot),
            )
        }
        val authors = payload.authors.map { item ->
            item.copy(id = author(item.kind, item.id))
        }
        if (!options.regenerateIds) {
            val duplicateAuthors = authors.groupBy { "${it.kind.wire()}:${it.id}" }.filterValues { it.size > 1 }
            if (duplicateAuthors.isNotEmpty()) throw invalid("Assistant id mapping merges authors")
        }
        return LocalDataBackupPayload(
            authors = authors.distinctBy { "${it.kind.wire()}:${it.id}" },
            posts = posts,
            comments = comments,
            likes = likes,
            drafts = drafts,
            media = media,
        )
    }

    private fun parseManifest(bytes: ByteArray): LocalBackupManifest {
        return try {
            LocalDataJson.json.decodeFromString(LocalBackupManifest.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw invalid("Backup manifest is invalid")
        }
    }

    private fun parsePayload(bytes: ByteArray): LocalDataBackupPayload {
        return try {
            LocalDataJson.json.decodeFromString(
                LocalDataBackupPayload.serializer(),
                bytes.toString(Charsets.UTF_8),
            )
        } catch (error: Exception) {
            throw invalid("Backup payload is invalid")
        }
    }

    private fun readBounded(zip: ZipFile, entry: ZipEntry, maxBytes: Int): ByteArray {
        if (entry.size > maxBytes) {
            throw LocalDataException(LocalDataErrorCode.PAYLOAD_TOO_LARGE, "Backup entry is too large")
        }
        val output = java.io.ByteArrayOutputStream()
        zip.getInputStream(entry).use { input -> copyBounded(input, output, maxBytes) }
        return output.toByteArray()
    }

    private fun copyBounded(input: InputStream, output: java.io.OutputStream, maxBytes: Int) {
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw LocalDataException(LocalDataErrorCode.PAYLOAD_TOO_LARGE, "Backup entry is too large")
            }
            output.write(buffer, 0, read)
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray, time: Long) {
        val entry = ZipEntry(name).apply { this.time = time }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun counts(payload: LocalDataBackupPayload): LocalBackupCounts = LocalBackupCounts(
        authors = payload.authors.size,
        posts = payload.posts.size,
        comments = payload.comments.size,
        likes = payload.likes.size,
        drafts = payload.drafts.size,
        media = payload.media.size,
    )

    private fun newId(prefix: String): String =
        "${prefix}_${UUID.randomUUID().toString().replace("-", "")}"

    private fun invalid(message: String) =
        LocalDataException(LocalDataErrorCode.INVALID_REQUEST, message)

    private data class IdMaps(
        val media: Map<String, String>,
        val posts: Map<String, String>,
        val comments: Map<String, String>,
        val drafts: Map<String, String>,
        val assistants: Map<String, String>,
    )

    companion object {
        const val MANIFEST_ENTRY = "manifest.json"
        const val PAYLOAD_ENTRY = "payload.json"
        const val MEDIA_PREFIX = "media/"
        const val MAX_ZIP_ENTRIES = 25_000
        const val MAX_MANIFEST_BYTES = 1 * 1024 * 1024
        const val MAX_PAYLOAD_BYTES = 128 * 1024 * 1024
        const val MAX_POSTS = 20_000
        const val MAX_COMMENTS = 100_000
        const val MAX_LIKES = 100_000
        const val MAX_DRAFTS = 20_000
        const val MAX_MEDIA = 20_000
        const val MAX_AUTHORS = 10_000
        val MEDIA_FILE_NAME_PATTERN = Regex("^ldm_[A-Za-z0-9]{8,64}\\.(png|jpg|webp|gif)$")
        val ALLOWED_MIME = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    }
}
