/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localdata

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

data class LocalPostInput(
    val body: String,
    val visibility: PostVisibility,
    val selectedAssistantIds: List<String> = emptyList(),
    val mediaIds: List<String> = emptyList(),
    val chatSnapshot: LocalChatSnapshotDto? = null,
    val clientKey: String? = null,
)

data class LocalCommentInput(
    val postId: String,
    val body: String,
    val replyToId: String? = null,
    val clientKey: String? = null,
)

data class LocalDraftInput(
    val draftId: String? = null,
    val body: String,
    val visibility: PostVisibility,
    val selectedAssistantIds: List<String> = emptyList(),
    val mediaIds: List<String> = emptyList(),
    val chatSnapshot: LocalChatSnapshotDto? = null,
)

private data class PreparedPost(
    val body: String,
    val visibility: PostVisibility,
    val selectedAssistantIds: List<String>,
    val mediaIds: List<String>,
    val chatSnapshotJson: String?,
    val clientKey: String?,
)

/**
 * Transactional write side and the facade used by the dispatcher/session. Reads delegate to
 * [LocalDataReadStore] so ACL/pagination logic has exactly one implementation.
 */
class LocalDataStore(internal val database: LocalDataDatabase) {
    private val reader = LocalDataReadStore(database)

    // ------------------------------------------------------------------ posts

    fun createPost(author: LocalAuthorDto, input: LocalPostInput, now: Long): LocalPostDto {
        requireAuthor(author)
        return database.transaction { db ->
            val existingId = input.clientKey
                ?.let { normalizedClientKey(it) }
                ?.let { findPostIdByClientKey(db, author, it) }
            if (existingId != null) {
                return@transaction reader.readPostForResult(existingId)
                    ?: throw LocalDataException(LocalDataErrorCode.NOT_FOUND, "Post does not exist")
            }
            val prepared = preparePost(db, input)
            val id = newId("p")
            insertPost(
                db = db,
                id = id,
                author = author,
                prepared = prepared,
                createdAt = now,
                updatedAt = now,
            )
            reader.readPostForResult(id)
                ?: throw LocalDataException(LocalDataErrorCode.INTERNAL, "Post was not stored")
        }
    }

    fun publishDraft(author: LocalAuthorDto, draftId: String, now: Long): LocalPostDto {
        requireAuthor(author)
        if (!LocalDataValidation.isSafeRecordId(draftId)) {
            throw LocalDataException(LocalDataErrorCode.INVALID_REQUEST, "Invalid draft id")
        }
        return database.transaction { db ->
            val row = findOwnedDraft(db, author, draftId)
                ?: throw LocalDataException(LocalDataErrorCode.NOT_FOUND, "Draft does not exist")
            val mediaIds = loadMediaIds(db, "draft_media", "draft_id", draftId)
            val prepared = preparePost(
                db,
                LocalPostInput(
                    body = row.body,
                    visibility = row.visibility,
                    selectedAssistantIds = row.selectedAssistantIds,
                    mediaIds = mediaIds,
                    chatSnapshot = row.chatSnapshot,
                    clientKey = null,
                ),
            )
            val postId = newId("p")
            insertPost(
                db = db,
                id = postId,
                author = author,
                prepared = prepared,
                createdAt = now,
                updatedAt = now,
            )
            db.delete("draft_media", "draft_id = ?", arrayOf(draftId))
            db.delete("draft_acl", "draft_id = ?", arrayOf(draftId))
            db.delete("drafts", "id = ?", arrayOf(draftId))
            reader.readPostForResult(postId)
                ?: throw LocalDataException(LocalDataErrorCode.INTERNAL, "Post was not stored")
        }
    }

    fun deletePost(actor: LocalDataActor, postId: String, now: Long): Boolean {
        if (!LocalDataValidation.isSafeRecordId(postId)) {
            throw LocalDataException(LocalDataErrorCode.INVALID_REQUEST, "Invalid post id")
        }
        return database.transaction { db ->
            val values = ContentValues()
            db.rawQuery(
                "SELECT author_kind, author_id FROM posts WHERE id = ? AND deleted_at IS NULL LIMIT 1",
                arrayOf(postId),
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    throw LocalDataException(LocalDataErrorCode.NOT_FOUND, "Post does not exist")
                }
                val authorKind = authorKindFromWire(cursor.text("author_kind"))
                val authorId = cursor.text("author_id")
                val allowed = actor is LocalDataActor.User ||
                    (actor is LocalDataActor.Assistant && authorKind == AuthorKind.ASSISTANT && actor.id == authorId)
                if (!allowed) {
                    throw LocalDataException(LocalDataErrorCode.FORBIDDEN, "Only the author or user can delete this post")
                }
            }
            values.put("deleted_at", now)
            values.putNull("client_key")
            db.update("posts", values, "id = ?", arrayOf(postId))
            true
        }
    }

    // --------------------------------------------------------------- comments

    fun createComment(
        actor: LocalDataActor,
        author: LocalAuthorDto,
        input: LocalCommentInput,
        now: Long,
    ): LocalCommentCreateResultDto {
        requireAuthor(author)
        if (!LocalDataValidation.isSafeRecordId(input.postId)) {
            throw LocalDataException(LocalDataErrorCode.INVALID_REQUEST, "Invalid post id")
        }
        val body = input.body.trim()
        if (body.isEmpty()) throw invalid("Comment must not be empty")
        if (body.length > LocalDataApi.MAX_COMMENT_CHARS) {
            throw LocalDataException(LocalDataErrorCode.PAYLOAD_TOO_LARGE, "Comment is too long")
        }
        val clientKey = input.clientKey?.let { normalizedClientKey(it) }
        return database.transaction { db ->
            reader.requireVisible(actor, input.postId)
            input.replyToId?.let { replyTo ->
                if (!LocalDataValidation.isSafeRecordId(replyTo)) throw invalid("Invalid reply target")
                db.rawQuery(
                    "SELECT 1 FROM comments WHERE id = ? AND post_id = ? AND deleted_at IS NULL LIMIT 1",
                    arrayOf(replyTo, input.postId),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) throw invalid("Reply target does not exist")
                }
            }
            if (clientKey != null) {
                findCommentIdByClientKey(db, author, clientKey)?.let { existing ->
                    val comment = readComment(db, existing)
                        ?: throw LocalDataException(LocalDataErrorCode.NOT_FOUND, "Comment does not exist")
                    return@transaction LocalCommentCreateResultDto(
                        comment = comment,
                        commentCount = reader.commentCount(input.postId),
                    )
                }
            }
            val id = newId("c")
            val values = ContentValues().apply {
                put("id", id)
                put("post_id", input.postId)
                put("author_kind", author.kind.wire())
                put("author_id", author.id)
                put("author_name", author.name)
                put("author_avatar", author.avatar)
                put("body", body)
                put("reply_to_id", input.replyToId)
                put("client_key", clientKey)
                put("created_at", now)
            }
            db.insertOrThrow("comments", null, values)
            updateAuthorSnapshot(db, author, now)
            val comment = readComment(db, id)
                ?: throw LocalDataException(LocalDataErrorCode.INTERNAL, "Comment was not stored")
            LocalCommentCreateResultDto(
                comment = comment,
                commentCount = reader.commentCount(input.postId),
            )
        }
    }

    // ------------------------------------------------------------------ likes

    fun setLike(actor: LocalDataActor, postId: String, liked: Boolean, now: Long): LocalLikeResultDto {
        if (!LocalDataValidation.isSafeRecordId(postId)) {
            throw LocalDataException(LocalDataErrorCode.INVALID_REQUEST, "Invalid post id")
        }
        return database.transaction { db ->
            reader.requireVisible(actor, postId)
            if (liked) {
                val values = ContentValues().apply {
                    put("post_id", postId)
                    put("actor_kind", actorKind(actor))
                    put("actor_id", actorId(actor))
                    put("created_at", now)
                }
                db.insertWithOnConflict("likes", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            } else {
                db.delete(
                    "likes",
                    "post_id = ? AND actor_kind = ? AND actor_id = ?",
                    arrayOf(postId, actorKind(actor), actorId(actor)),
                )
            }
            LocalLikeResultDto(
                postId = postId,
                liked = liked,
                likeCount = likeCount(db, postId),
            )
        }
    }

    // ----------------------------------------------------------------- drafts

    fun saveDraft(author: LocalAuthorDto, input: LocalDraftInput, now: Long): LocalDraftDto {
        requireAuthor(author)
        if (input.body.length > LocalDataApi.MAX_BODY_CHARS) {
            throw LocalDataException(LocalDataErrorCode.PAYLOAD_TOO_LARGE, "Draft is too long")
        }
        input.draftId?.let {
            if (!LocalDataValidation.isSafeRecordId(it)) throw invalid("Invalid draft id")
        }
        val selected = normalizeSelectedAssistants(input.visibility, input.selectedAssistantIds)
        val mediaIds = normalizeMediaIds(input.mediaIds)
        val snapshotJson = encodeSnapshot(input.chatSnapshot)
        return database.transaction { db ->
            ensureMediaExist(db, mediaIds)
            ensureSnapshotMediaExist(db, LocalDataChatSnapshots.mediaReferences(input.chatSnapshot))
            val existing = input.draftId?.let { findOwnedDraft(db, author, it) }
            if (input.draftId != null && existing == null) {
                throw LocalDataException(LocalDataErrorCode.NOT_FOUND, "Draft does not exist")
            }
            val id = existing?.id ?: newId("d")
            val createdAt = existing?.createdAt ?: now
            val values = ContentValues().apply {
                put("id", id)
                put("author_kind", author.kind.wire())
                put("author_id", author.id)
                put("author_name", author.name)
                put("author_avatar", author.avatar)
                put("body", input.body)
                put("visibility", input.visibility.wire())
                put("chat_snapshot_json", snapshotJson)
                put("created_at", createdAt)
                put("updated_at", now)
            }
            if (existing == null) {
                db.insertOrThrow("drafts", null, values)
            } else {
                db.update("drafts", values, "id = ?", arrayOf(id))
            }
            db.delete("draft_media", "draft_id = ?", arrayOf(id))
            mediaIds.forEachIndexed { index, resourceId ->
                db.insertOrThrow(
                    "draft_media",
                    null,
                    ContentValues().apply {
                        put("draft_id", id)
                        put("ordinal", index)
                        put("resource_id", resourceId)
                    },
                )
            }
            db.delete("draft_acl", "draft_id = ?", arrayOf(id))
            selected.forEach { assistantId ->
                db.insertOrThrow(
                    "draft_acl",
                    null,
                    ContentValues().apply {
                        put("draft_id", id)
                        put("assistant_id", assistantId)
                    },
                )
            }
            updateAuthorSnapshot(db, author, now)
            readDraft(db, id) ?: throw LocalDataException(LocalDataErrorCode.INTERNAL, "Draft was not stored")
        }
    }

    fun deleteDraft(author: LocalAuthorDto, draftId: String): Boolean {
        requireAuthor(author)
        if (!LocalDataValidation.isSafeRecordId(draftId)) {
            throw LocalDataException(LocalDataErrorCode.INVALID_REQUEST, "Invalid draft id")
        }
        return database.transaction { db ->
            findOwnedDraft(db, author, draftId)
                ?: throw LocalDataException(LocalDataErrorCode.NOT_FOUND, "Draft does not exist")
            db.delete("draft_media", "draft_id = ?", arrayOf(draftId))
            db.delete("draft_acl", "draft_id = ?", arrayOf(draftId))
            db.delete("drafts", "id = ?", arrayOf(draftId))
            true
        }
    }

    fun listDrafts(actor: LocalDataActor, cursor: String?, limit: Int): LocalDraftPageDto {
        val where = mutableListOf("author_kind = ?", "author_id = ?")
        val args = mutableListOf(actorKind(actor), actorId(actor))
        val decoded = if (cursor.isNullOrBlank()) null else {
            LocalDataCursorCodec.decode(cursor)
                ?: throw LocalDataException(LocalDataErrorCode.INVALID_REQUEST, "Invalid cursor")
        }
        if (decoded != null) {
            where += "(updated_at < ? OR (updated_at = ? AND id < ?))"
            args += decoded.first.toString()
            args += decoded.first.toString()
            args += decoded.second
        }
        val pageSize = limit.coerceIn(1, LocalDataApi.MAX_PAGE_SIZE)
        val db = database.readable()
        val drafts = mutableListOf<LocalDraftDto>()
        db.rawQuery(
            "SELECT * FROM drafts WHERE ${where.joinToString(" AND ")} " +
                "ORDER BY updated_at DESC, id DESC LIMIT ?",
            (args + (pageSize + 1).toString()).toTypedArray(),
        ).use { c ->
            while (c.moveToNext()) drafts += mapDraft(db, c)
        }
        val hasMore = drafts.size > pageSize
        val items = if (hasMore) drafts.subList(0, pageSize).toList() else drafts
        val last = items.lastOrNull()
        return LocalDraftPageDto(
            items = items,
            nextCursor = if (hasMore && last != null) {
                LocalDataCursorCodec.encode(last.updatedAt, last.id)
            } else {
                null
            },
            hasMore = hasMore,
        )
    }

    // ------------------------------------------------------------------ reads

    fun listPosts(
        viewer: LocalDataActor,
        filter: LocalPostFilter,
        request: LocalPageRequest,
    ): LocalPostPageDto = reader.listPosts(viewer, filter, request)

    fun searchPosts(
        viewer: LocalDataActor,
        query: String,
        filter: LocalPostFilter,
        request: LocalPageRequest,
    ): LocalPostPageDto {
        val normalized = query.trim()
        if (normalized.isEmpty()) throw invalid("Search query must not be empty")
        if (normalized.length > LocalDataApi.MAX_SEARCH_CHARS) {
            throw invalid("Search query is too long")
        }
        return reader.listPosts(viewer, filter, request, normalized)
    }

    fun getPost(
        viewer: LocalDataActor,
        postId: String,
        bodyChars: Int,
        commentLimit: Int,
        commentCursor: String?,
        includeMedia: Boolean,
        includeChatSnapshot: Boolean,
        commentOrder: LocalCommentOrder = LocalCommentOrder.OLDEST,
    ): LocalPostDetailDto {
        if (!LocalDataValidation.isSafeRecordId(postId)) {
            throw LocalDataException(LocalDataErrorCode.INVALID_REQUEST, "Invalid post id")
        }
        return reader.getPost(
            viewer = viewer,
            postId = postId,
            bodyChars = bodyChars,
            commentLimit = commentLimit,
            commentCursor = commentCursor,
            includeMedia = includeMedia,
            includeChatSnapshot = includeChatSnapshot,
            commentOrder = commentOrder,
        )
    }

    fun listComments(
        viewer: LocalDataActor,
        postId: String,
        cursor: String?,
        limit: Int,
        order: LocalCommentOrder = LocalCommentOrder.OLDEST,
    ): LocalCommentPageDto {
        if (!LocalDataValidation.isSafeRecordId(postId)) {
            throw LocalDataException(LocalDataErrorCode.INVALID_REQUEST, "Invalid post id")
        }
        return reader.listComments(viewer, postId, cursor, limit, order)
    }

    fun getAuthorSnapshot(kind: AuthorKind, id: String): LocalAuthorSnapshotDto? =
        reader.getAuthorSnapshot(kind, id)

    fun mediaMetadata(resourceId: String): LocalMediaDto? {
        if (!LocalDataValidation.isSafeMediaId(resourceId)) return null
        return reader.mediaMetadata(resourceId)
    }

    fun referencedMediaIds(): Set<String> = reader.referencedMediaIds()

    fun isMediaReferenced(resourceId: String): Boolean = reader.isMediaReferenced(resourceId)

    fun canAccessMedia(viewer: LocalDataActor, resourceId: String): Boolean =
        reader.canAccessMedia(viewer, resourceId)

    fun exportSnapshot(): LocalDataBackupPayload = reader.exportSnapshot()

    // -------------------------------------------------------------- restore

    /**
     * Replace every row with a payload that has already been verified and remapped by
     * [LocalDataBackup]. Media files are copied before this call.
     */
    fun replaceAll(payload: LocalDataBackupPayload) {
        database.transaction { db ->
            database.deleteAllRows(db)
            payload.authors.forEach { author ->
                db.insertOrThrow(
                    "author_snapshots",
                    null,
                    ContentValues().apply {
                        put("author_kind", author.kind.wire())
                        put("author_id", author.id)
                        put("name", author.name)
                        put("avatar", author.avatar)
                        put("captured_at", author.capturedAt)
                    },
                )
            }
            payload.media.forEach { media ->
                db.insertOrThrow(
                    "media_assets",
                    null,
                    ContentValues().apply {
                        put("resource_id", media.resourceId)
                        put("mime", media.mime)
                        put("byte_size", media.byteSize)
                        put("width", media.width)
                        put("height", media.height)
                        put("sha256", media.sha256)
                        put("display_name", media.displayName)
                        put("file_name", media.fileName)
                        put("created_at", media.createdAt)
                    },
                )
            }
            payload.posts.forEach { post ->
                db.insertOrThrow(
                    "posts",
                    null,
                    ContentValues().apply {
                        put("id", post.id)
                        put("author_kind", post.authorKind.wire())
                        put("author_id", post.authorId)
                        put("author_name", post.authorName)
                        put("author_avatar", post.authorAvatar)
                        put("body", post.body)
                        put("visibility", post.visibility.wire())
                        put("chat_snapshot_json", LocalDataChatSnapshots.encode(post.chatSnapshot))
                        put("client_key", post.clientKey)
                        put("created_at", post.createdAt)
                        put("updated_at", post.updatedAt)
                    },
                )
                post.selectedAssistantIds.forEach { assistantId ->
                    db.insertOrThrow(
                        "post_acl",
                        null,
                        ContentValues().apply {
                            put("post_id", post.id)
                            put("assistant_id", assistantId)
                        },
                    )
                }
                post.mediaIds.forEachIndexed { index, resourceId ->
                    db.insertOrThrow(
                        "post_media",
                        null,
                        ContentValues().apply {
                            put("post_id", post.id)
                            put("ordinal", index)
                            put("resource_id", resourceId)
                        },
                    )
                }
            }
            payload.comments.forEach { comment ->
                db.insertOrThrow(
                    "comments",
                    null,
                    ContentValues().apply {
                        put("id", comment.id)
                        put("post_id", comment.postId)
                        put("author_kind", comment.authorKind.wire())
                        put("author_id", comment.authorId)
                        put("author_name", comment.authorName)
                        put("author_avatar", comment.authorAvatar)
                        put("body", comment.body)
                        put("reply_to_id", comment.replyToId)
                        put("client_key", comment.clientKey)
                        put("created_at", comment.createdAt)
                    },
                )
            }
            payload.likes.forEach { like ->
                db.insertOrThrow(
                    "likes",
                    null,
                    ContentValues().apply {
                        put("post_id", like.postId)
                        put("actor_kind", like.actorKind.wire())
                        put("actor_id", like.actorId)
                        put("created_at", like.createdAt)
                    },
                )
            }
            payload.drafts.forEach { draft ->
                db.insertOrThrow(
                    "drafts",
                    null,
                    ContentValues().apply {
                        put("id", draft.id)
                        put("author_kind", draft.authorKind.wire())
                        put("author_id", draft.authorId)
                        put("author_name", draft.authorName)
                        put("author_avatar", draft.authorAvatar)
                        put("body", draft.body)
                        put("visibility", draft.visibility.wire())
                        put("chat_snapshot_json", LocalDataChatSnapshots.encode(draft.chatSnapshot))
                        put("created_at", draft.createdAt)
                        put("updated_at", draft.updatedAt)
                    },
                )
                draft.mediaIds.forEachIndexed { index, resourceId ->
                    db.insertOrThrow(
                        "draft_media",
                        null,
                        ContentValues().apply {
                            put("draft_id", draft.id)
                            put("ordinal", index)
                            put("resource_id", resourceId)
                        },
                    )
                }
                draft.selectedAssistantIds.forEach { assistantId ->
                    db.insertOrThrow(
                        "draft_acl",
                        null,
                        ContentValues().apply {
                            put("draft_id", draft.id)
                            put("assistant_id", assistantId)
                        },
                    )
                }
            }
        }
    }

    // --------------------------------------------------------------- helpers

    private fun preparePost(db: SQLiteDatabase, input: LocalPostInput): PreparedPost {
        val body = input.body
        if (body.length > LocalDataApi.MAX_BODY_CHARS) {
            throw LocalDataException(LocalDataErrorCode.PAYLOAD_TOO_LARGE, "Post is too long")
        }
        val mediaIds = normalizeMediaIds(input.mediaIds)
        val snapshotJson = encodeSnapshot(input.chatSnapshot)
        if (body.isBlank() && mediaIds.isEmpty() && snapshotJson == null) {
            throw invalid("Post needs text, an image, or a chat snapshot")
        }
        ensureMediaExist(db, mediaIds)
        ensureSnapshotMediaExist(db, LocalDataChatSnapshots.mediaReferences(input.chatSnapshot))
        return PreparedPost(
            body = body,
            visibility = input.visibility,
            selectedAssistantIds = normalizeSelectedAssistants(input.visibility, input.selectedAssistantIds),
            mediaIds = mediaIds,
            chatSnapshotJson = snapshotJson,
            clientKey = input.clientKey?.let { normalizedClientKey(it) },
        )
    }

    private fun insertPost(
        db: SQLiteDatabase,
        id: String,
        author: LocalAuthorDto,
        prepared: PreparedPost,
        createdAt: Long,
        updatedAt: Long,
    ) {
        db.insertOrThrow(
            "posts",
            null,
            ContentValues().apply {
                put("id", id)
                put("author_kind", author.kind.wire())
                put("author_id", author.id)
                put("author_name", author.name)
                put("author_avatar", author.avatar)
                put("body", prepared.body)
                put("visibility", prepared.visibility.wire())
                put("chat_snapshot_json", prepared.chatSnapshotJson)
                put("client_key", prepared.clientKey)
                put("created_at", createdAt)
                put("updated_at", updatedAt)
            },
        )
        prepared.selectedAssistantIds.forEach { assistantId ->
            db.insertOrThrow(
                "post_acl",
                null,
                ContentValues().apply {
                    put("post_id", id)
                    put("assistant_id", assistantId)
                },
            )
        }
        prepared.mediaIds.forEachIndexed { index, resourceId ->
            db.insertOrThrow(
                "post_media",
                null,
                ContentValues().apply {
                    put("post_id", id)
                    put("ordinal", index)
                    put("resource_id", resourceId)
                },
            )
        }
        updateAuthorSnapshot(db, author, createdAt)
    }

    private fun normalizeSelectedAssistants(
        visibility: PostVisibility,
        selected: List<String>,
    ): List<String> {
        if (visibility != PostVisibility.SELECTED) return emptyList()
        val ids = selected.distinct()
        if (ids.isEmpty()) throw invalid("Selected visibility needs at least one assistant")
        if (ids.size > LocalDataApi.MAX_SELECTED_ASSISTANTS) {
            throw LocalDataException(LocalDataErrorCode.PAYLOAD_TOO_LARGE, "Too many selected assistants")
        }
        ids.forEach { if (!LocalDataValidation.isSafeAssistantId(it)) throw invalid("Invalid assistant id") }
        return ids
    }

    private fun normalizeMediaIds(mediaIds: List<String>): List<String> {
        val ids = mediaIds.distinct()
        if (ids.size > LocalDataApi.MAX_MEDIA_PER_POST) {
            throw LocalDataException(LocalDataErrorCode.PAYLOAD_TOO_LARGE, "Too many media attachments")
        }
        ids.forEach { if (!LocalDataValidation.isSafeMediaId(it)) throw invalid("Invalid media id") }
        return ids
    }

    private fun ensureMediaExist(db: SQLiteDatabase, mediaIds: Collection<String>) {
        mediaIds.forEach { resourceId ->
            db.rawQuery(
                "SELECT 1 FROM media_assets WHERE resource_id = ? LIMIT 1",
                arrayOf(resourceId),
            ).use { cursor ->
                if (!cursor.moveToFirst()) throw invalid("Media does not exist")
            }
        }
    }

    private fun ensureSnapshotMediaExist(db: SQLiteDatabase, mediaIds: Collection<String>) {
        val unique = mediaIds.distinct()
        val maxRefs = LocalDataApi.MAX_MEDIA_PER_POST * LocalDataApi.MAX_CHAT_MESSAGES +
            LocalDataApi.MAX_CHAT_AVATARS
        if (unique.size > maxRefs) {
            throw LocalDataException(LocalDataErrorCode.PAYLOAD_TOO_LARGE, "Too many chat media references")
        }
        ensureMediaExist(db, unique)
    }

    private fun encodeSnapshot(snapshot: LocalChatSnapshotDto?): String? {
        return try {
            LocalDataChatSnapshots.encode(snapshot)
        } catch (error: IllegalArgumentException) {
            throw invalid(error.message ?: "Invalid chat snapshot")
        }
    }

    private fun updateAuthorSnapshot(db: SQLiteDatabase, author: LocalAuthorDto, now: Long) {
        db.insertWithOnConflict(
            "author_snapshots",
            null,
            ContentValues().apply {
                put("author_kind", author.kind.wire())
                put("author_id", author.id)
                put("name", author.name)
                put("avatar", author.avatar)
                put("captured_at", now)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun findPostIdByClientKey(
        db: SQLiteDatabase,
        author: LocalAuthorDto,
        clientKey: String,
    ): String? {
        db.rawQuery(
            "SELECT id FROM posts WHERE author_kind = ? AND author_id = ? AND client_key = ? " +
                "AND deleted_at IS NULL LIMIT 1",
            arrayOf(author.kind.wire(), author.id, clientKey),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.text("id") else null
        }
    }

    private fun findCommentIdByClientKey(
        db: SQLiteDatabase,
        author: LocalAuthorDto,
        clientKey: String,
    ): String? {
        db.rawQuery(
            "SELECT id FROM comments WHERE author_kind = ? AND author_id = ? AND client_key = ? LIMIT 1",
            arrayOf(author.kind.wire(), author.id, clientKey),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.text("id") else null
        }
    }

    private fun readComment(db: SQLiteDatabase, commentId: String): LocalCommentDto? {
        db.rawQuery(
            "SELECT * FROM comments WHERE id = ? AND deleted_at IS NULL LIMIT 1",
            arrayOf(commentId),
        ).use { cursor ->
            return if (cursor.moveToFirst()) mapComment(cursor) else null
        }
    }

    private fun likeCount(db: SQLiteDatabase, postId: String): Int {
        db.rawQuery("SELECT COUNT(*) FROM likes WHERE post_id = ?", arrayOf(postId)).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun findOwnedDraft(db: SQLiteDatabase, author: LocalAuthorDto, draftId: String): DraftRow? {
        db.rawQuery(
            "SELECT * FROM drafts WHERE id = ? AND author_kind = ? AND author_id = ? LIMIT 1",
            arrayOf(draftId, author.kind.wire(), author.id),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return DraftRow(
                id = cursor.text("id"),
                body = cursor.text("body"),
                visibility = visibilityFromWire(cursor.text("visibility")),
                selectedAssistantIds = loadDraftAcl(db, cursor.text("id")),
                chatSnapshot = LocalDataChatSnapshots.decode(cursor.nullableText("chat_snapshot_json")),
                createdAt = cursor.long("created_at"),
            )
        }
    }

    private fun loadDraftAcl(db: SQLiteDatabase, draftId: String): List<String> {
        val result = mutableListOf<String>()
        db.rawQuery(
            "SELECT assistant_id FROM draft_acl WHERE draft_id = ? ORDER BY assistant_id",
            arrayOf(draftId),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.text("assistant_id")
        }
        return result
    }

    private fun loadMediaIds(
        db: SQLiteDatabase,
        table: String,
        idColumn: String,
        id: String,
    ): List<String> {
        val result = mutableListOf<String>()
        db.rawQuery(
            "SELECT resource_id FROM $table WHERE $idColumn = ? ORDER BY ordinal ASC",
            arrayOf(id),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.text("resource_id")
        }
        return result
    }

    private fun readDraft(db: SQLiteDatabase, draftId: String): LocalDraftDto? {
        db.rawQuery("SELECT * FROM drafts WHERE id = ? LIMIT 1", arrayOf(draftId)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return mapDraft(db, cursor)
        }
    }

    private fun mapDraft(db: SQLiteDatabase, cursor: Cursor): LocalDraftDto {
        val id = cursor.text("id")
        val mediaIds = loadMediaIds(db, "draft_media", "draft_id", id)
        val media = mediaIds.mapNotNull { mediaDtoById(db, it) }
        return LocalDraftDto(
            id = id,
            author = LocalAuthorDto(
                kind = authorKindFromWire(cursor.text("author_kind")),
                id = cursor.text("author_id"),
                name = cursor.text("author_name"),
                avatar = cursor.nullableText("author_avatar"),
            ),
            body = cursor.text("body"),
            visibility = visibilityFromWire(cursor.text("visibility")),
            selectedAssistantIds = loadDraftAcl(db, id),
            media = media,
            chatSnapshot = LocalDataChatSnapshots.decode(cursor.nullableText("chat_snapshot_json")),
            createdAt = cursor.long("created_at"),
            updatedAt = cursor.long("updated_at"),
        )
    }

    private fun mediaDtoById(db: SQLiteDatabase, resourceId: String): LocalMediaDto? {
        db.rawQuery(
            "SELECT resource_id, mime, byte_size, width, height FROM media_assets WHERE resource_id = ? LIMIT 1",
            arrayOf(resourceId),
        ).use { cursor ->
            return if (cursor.moveToFirst()) mediaDto(cursor) else null
        }
    }

    private fun requireAuthor(author: LocalAuthorDto) {
        if (!LocalDataValidation.isSafeAssistantId(author.id)) throw invalid("Invalid author id")
        if (author.name.length > 100) throw invalid("Author name is too long")
        if ((author.avatar?.length ?: 0) > 2_048) throw invalid("Author avatar is too long")
    }

    private fun normalizedClientKey(raw: String): String {
        val key = raw.trim()
        if (key.isEmpty() || key.length > LocalDataApi.MAX_CLIENT_KEY_CHARS) {
            throw invalid("Invalid client key")
        }
        if (!Regex("^[A-Za-z0-9._:-]{1,64}$").matches(key)) throw invalid("Invalid client key")
        return key
    }

    private fun newId(prefix: String): String =
        "${prefix}_${UUID.randomUUID().toString().replace("-", "")}"

    private fun invalid(message: String) =
        LocalDataException(LocalDataErrorCode.INVALID_REQUEST, message)
}

private data class DraftRow(
    val id: String,
    val body: String,
    val visibility: PostVisibility,
    val selectedAssistantIds: List<String>,
    val chatSnapshot: LocalChatSnapshotDto?,
    val createdAt: Long,
)
