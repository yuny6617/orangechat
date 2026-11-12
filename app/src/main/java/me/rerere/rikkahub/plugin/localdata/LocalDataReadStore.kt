/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localdata

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

data class LocalPostFilter(
    val authorId: String? = null,
    val authorKind: AuthorKind? = null,
    val type: String? = null,
    val dateFrom: Long? = null,
    val dateTo: Long? = null,
)

data class LocalPageRequest(
    val cursor: String? = null,
    val limit: Int = LocalDataApi.DEFAULT_PAGE_SIZE,
    val bodyChars: Int = LocalDataApi.DEFAULT_USER_BODY_CHARS,
    val pageBodyBudget: Int = LocalDataApi.DEFAULT_PAGE_BODY_BUDGET_CHARS,
    val commentPreview: Int = 0,
    val includeMedia: Boolean = true,
)

/**
 * Read side of the store. Every entry point applies the same viewer predicate, so list/get/search/
 * comments/media cannot drift apart. The dispatcher is still responsible for capability checks.
 */
internal class LocalDataReadStore(private val database: LocalDataDatabase) {

    fun listPosts(
        viewer: LocalDataActor,
        filter: LocalPostFilter,
        request: LocalPageRequest,
        searchText: String? = null,
    ): LocalPostPageDto {
        val db = database.readable()
        val limit = request.limit.coerceIn(1, LocalDataApi.MAX_PAGE_SIZE)
        val bodyChars = request.bodyChars.coerceIn(0, LocalDataApi.MAX_BODY_BUDGET_CHARS)
        val where = mutableListOf("p.deleted_at IS NULL")
        val args = mutableListOf<String>()

        viewerPredicate(viewer).let { (clause, clauseArgs) ->
            if (clause.isNotBlank()) {
                where += clause
                args += clauseArgs
            }
        }
        filter.authorId?.let {
            where += "p.author_id = ?"
            args += it
        }
        filter.authorKind?.let {
            where += "p.author_kind = ?"
            args += it.wire()
        }
        filter.type?.let { type ->
            when (type) {
                "text" -> where += "(p.chat_snapshot_json IS NULL OR p.chat_snapshot_json = '') AND " +
                    "NOT EXISTS (SELECT 1 FROM post_media pm WHERE pm.post_id = p.id)"
                "image" -> where += "EXISTS (SELECT 1 FROM post_media pm WHERE pm.post_id = p.id)"
                "chat_snapshot" -> where += "p.chat_snapshot_json IS NOT NULL AND p.chat_snapshot_json != ''"
                else -> throw LocalDataException(LocalDataErrorCode.INVALID_REQUEST, "Unsupported type")
            }
        }
        filter.dateFrom?.let {
            where += "p.created_at >= ?"
            args += it.toString()
        }
        filter.dateTo?.let {
            where += "p.created_at <= ?"
            args += it.toString()
        }
        if (!searchText.isNullOrBlank()) {
            val escaped = escapeLike(searchText)
            where += "(p.body LIKE ? ESCAPE '\\' OR p.author_name LIKE ? ESCAPE '\\')"
            args += "%$escaped%"
            args += "%$escaped%"
        }
        val decoded = decodeCursor(request.cursor)
        if (decoded != null) {
            where += "(p.created_at < ? OR (p.created_at = ? AND p.id < ?))"
            args += decoded.first.toString()
            args += decoded.first.toString()
            args += decoded.second
        }

        val sql = buildString {
            append("SELECT p.*, ")
            append("(SELECT COUNT(*) FROM comments c WHERE c.post_id = p.id AND c.deleted_at IS NULL) AS comment_count, ")
            append("(SELECT COUNT(*) FROM likes l WHERE l.post_id = p.id) AS like_count, ")
            append("EXISTS(SELECT 1 FROM likes l2 WHERE l2.post_id = p.id AND l2.actor_kind = ? ")
            append("AND l2.actor_id = ?) AS liked_by_me ")
            append("FROM posts p WHERE ")
            append(where.joinToString(" AND "))
            append(" ORDER BY p.created_at DESC, p.id DESC LIMIT ?")
        }
        val queryArgs = ArrayList<String>(args.size + 3)
        queryArgs += actorKind(viewer)
        queryArgs += actorId(viewer)
        queryArgs += args
        queryArgs += (limit + 1).toString()

        val rows = ArrayList<LocalPostDto>(limit + 1)
        var truncated = false
        var usedBody = 0
        var hasMore = false
        var index = 0
        db.rawQuery(sql, queryArgs.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                if (index >= limit) {
                    hasMore = true
                    break
                }
                val body = cursor.text("body")
                val allowed = minOf(bodyChars, (request.pageBodyBudget - usedBody).coerceAtLeast(0))
                val shown = if (body.length > allowed) body.substring(0, allowed) else body
                val bodyTruncated = shown.length < body.length
                usedBody += shown.length
                truncated = truncated || bodyTruncated
                val mapped = mapPost(
                    cursor = cursor,
                    database = database,
                    viewer = viewer,
                    body = shown,
                    bodyTruncated = bodyTruncated,
                    previewLimit = request.commentPreview.coerceIn(0, LocalDataApi.MAX_COMMENT_PREVIEW),
                    includeChatSnapshot = false,
                )
                rows += if (request.includeMedia) {
                    mapped
                } else {
                    mapped.copy(media = emptyList(), mediaOmitted = true)
                }
                index++
            }
        }
        val items = rows
        val last = items.lastOrNull()
        return LocalPostPageDto(
            items = items,
            nextCursor = if (hasMore && last != null) {
                LocalDataCursorCodec.encode(last.createdAt, last.id)
            } else {
                null
            },
            hasMore = hasMore,
            truncated = truncated,
            budget = LocalPageBudgetDto(
                pageSize = limit,
                bodyCharsPerItem = bodyChars,
                bodyCharsUsed = usedBody,
                truncated = truncated,
            ),
        )
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
        val db = database.readable()
        val (post, fullBodyLength) = readPost(db, viewer, postId)
            ?: throw LocalDataException(LocalDataErrorCode.NOT_FOUND, "Post does not exist")
        val allowed = bodyChars.coerceIn(0, LocalDataApi.MAX_BODY_BUDGET_CHARS)
        val body = if (post.body.length > allowed) post.body.substring(0, allowed) else post.body
        val mapped = post.copy(
            body = body,
            bodyTruncated = body.length < fullBodyLength,
            media = if (includeMedia) {
                mediaForPost(db, postId)
            } else {
                emptyList()
            },
            mediaOmitted = !includeMedia,
            chatSnapshot = if (includeChatSnapshot) post.chatSnapshot else null,
            chatSnapshotOmitted = !includeChatSnapshot,
            previewComments = commentsPreview(db, postId, 0, LocalDataApi.MAX_COMMENT_PREVIEW),
        )
        val comments = if (commentLimit <= 0) {
            LocalCommentPageDto(commentCount = commentCount(db, postId))
        } else {
            listCommentsInternal(
                db = db,
                postId = postId,
                cursor = commentCursor,
                limit = commentLimit,
                order = commentOrder,
            )
        }
        return LocalPostDetailDto(post = mapped, comments = comments)
    }

    fun listComments(
        viewer: LocalDataActor,
        postId: String,
        cursor: String?,
        limit: Int,
        order: LocalCommentOrder = LocalCommentOrder.OLDEST,
    ): LocalCommentPageDto {
        val db = database.readable()
        requireVisible(viewer, postId)
        return listCommentsInternal(db, postId, cursor, limit, order)
    }

    fun getAuthorSnapshot(kind: AuthorKind, id: String): LocalAuthorSnapshotDto? {
        database.readable().rawQuery(
            "SELECT author_kind, author_id, name, avatar, captured_at FROM author_snapshots " +
                "WHERE author_kind = ? AND author_id = ? LIMIT 1",
            arrayOf(kind.wire(), id),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return LocalAuthorSnapshotDto(
                kind = authorKindFromWire(cursor.text("author_kind")),
                id = cursor.text("author_id"),
                name = cursor.text("name"),
                avatar = cursor.nullableText("avatar"),
                capturedAt = cursor.long("captured_at"),
            )
        }
    }

    fun mediaMetadata(resourceId: String): LocalMediaDto? {
        database.readable().rawQuery(
            "SELECT resource_id, mime, byte_size, width, height FROM media_assets WHERE resource_id = ? LIMIT 1",
            arrayOf(resourceId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return mediaDto(cursor)
        }
    }

    fun referencedMediaIds(): Set<String> {
        val result = linkedSetOf<String>()
        val db = database.readable()
        db.rawQuery(
            """
            SELECT resource_id FROM post_media
            WHERE post_id IN (SELECT id FROM posts WHERE deleted_at IS NULL)
            UNION
            SELECT resource_id FROM draft_media
            """.trimIndent(),
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.text("resource_id")
        }
        db.rawQuery(
            "SELECT chat_snapshot_json FROM posts WHERE deleted_at IS NULL AND chat_snapshot_json IS NOT NULL",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                LocalDataChatSnapshots.decode(cursor.text("chat_snapshot_json"))
                    ?.let { result += it.allMediaReferences() }
            }
        }
        db.rawQuery(
            "SELECT chat_snapshot_json FROM drafts WHERE chat_snapshot_json IS NOT NULL",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                LocalDataChatSnapshots.decode(cursor.text("chat_snapshot_json"))
                    ?.let { result += it.allMediaReferences() }
            }
        }
        return result
    }

    /**
     * Media has no ACL of its own: access is granted only while some post/draft the viewer can read
     * still references it. This covers list/get/comment/media and prevents probing resource ids.
     */
    fun canAccessMedia(viewer: LocalDataActor, resourceId: String): Boolean {
        if (!LocalDataValidation.isSafeMediaId(resourceId)) return false
        if (mediaMetadata(resourceId) == null) return false
        if (viewer is LocalDataActor.User) return true
        val assistant = viewer as LocalDataActor.Assistant
        val db = database.readable()
        val (clause, clauseArgs) = viewerPredicate(viewer)
        db.rawQuery(
            """
            SELECT 1 FROM post_media pm JOIN posts p ON p.id = pm.post_id
            WHERE pm.resource_id = ? AND p.deleted_at IS NULL AND $clause LIMIT 1
            """.trimIndent(),
            (listOf(resourceId) + clauseArgs).toTypedArray(),
        ).use { cursor ->
            if (cursor.moveToFirst()) return true
        }
        db.rawQuery(
            "SELECT chat_snapshot_json FROM posts p WHERE p.deleted_at IS NULL " +
                "AND p.chat_snapshot_json IS NOT NULL AND $clause",
            clauseArgs.toTypedArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (LocalDataChatSnapshots.decode(cursor.text("chat_snapshot_json"))
                        ?.referencesMedia(resourceId) == true
                ) {
                    return true
                }
            }
        }
        db.rawQuery(
            "SELECT chat_snapshot_json FROM drafts WHERE author_kind = ? AND author_id = ? " +
                "AND chat_snapshot_json IS NOT NULL",
            arrayOf(AuthorKind.ASSISTANT.wire(), assistant.id),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (LocalDataChatSnapshots.decode(cursor.text("chat_snapshot_json"))
                        ?.referencesMedia(resourceId) == true
                ) {
                    return true
                }
            }
        }
        db.rawQuery(
            "SELECT 1 FROM draft_media dm JOIN drafts d ON d.id = dm.draft_id " +
                "WHERE dm.resource_id = ? AND d.author_kind = ? AND d.author_id = ? LIMIT 1",
            arrayOf(resourceId, AuthorKind.ASSISTANT.wire(), assistant.id),
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    fun isMediaReferenced(resourceId: String): Boolean {
        if (!LocalDataValidation.isSafeMediaId(resourceId)) return false
        val db = database.readable()
        db.rawQuery(
            """
            SELECT 1 FROM post_media WHERE resource_id = ?
              AND post_id IN (SELECT id FROM posts WHERE deleted_at IS NULL)
            UNION ALL
            SELECT 1 FROM draft_media WHERE resource_id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(resourceId, resourceId),
        ).use { cursor ->
            if (cursor.moveToFirst()) return true
        }
        db.rawQuery(
            "SELECT chat_snapshot_json FROM posts WHERE deleted_at IS NULL AND chat_snapshot_json IS NOT NULL",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (LocalDataChatSnapshots.decode(cursor.text("chat_snapshot_json"))
                        ?.referencesMedia(resourceId) == true
                ) {
                    return true
                }
            }
        }
        db.rawQuery(
            "SELECT chat_snapshot_json FROM drafts WHERE chat_snapshot_json IS NOT NULL",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (LocalDataChatSnapshots.decode(cursor.text("chat_snapshot_json"))
                        ?.referencesMedia(resourceId) == true
                ) {
                    return true
                }
            }
        }
        return false
    }

    fun exportSnapshot(): LocalDataBackupPayload {
        val db = database.readable()
        val authors = mutableListOf<LocalBackupAuthor>()
        db.rawQuery(
            "SELECT author_kind, author_id, name, avatar, captured_at FROM author_snapshots",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                authors += LocalBackupAuthor(
                    kind = authorKindFromWire(cursor.text("author_kind")),
                    id = cursor.text("author_id"),
                    name = cursor.text("name"),
                    avatar = cursor.nullableText("avatar"),
                    capturedAt = cursor.long("captured_at"),
                )
            }
        }

        val posts = mutableListOf<LocalBackupPost>()
        val acl = linkedMapOf<String, MutableList<String>>()
        db.rawQuery("SELECT post_id, assistant_id FROM post_acl", emptyArray()).use { cursor ->
            while (cursor.moveToNext()) {
                acl.getOrPut(cursor.text("post_id")) { mutableListOf() } += cursor.text("assistant_id")
            }
        }
        val postMedia = linkedMapOf<String, MutableList<String>>()
        db.rawQuery("SELECT post_id, resource_id FROM post_media ORDER BY post_id, ordinal", emptyArray()).use { cursor ->
            while (cursor.moveToNext()) {
                postMedia.getOrPut(cursor.text("post_id")) { mutableListOf() } += cursor.text("resource_id")
            }
        }
        db.rawQuery(
            "SELECT * FROM posts WHERE deleted_at IS NULL ORDER BY created_at ASC, id ASC",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.text("id")
                posts += LocalBackupPost(
                    id = id,
                    authorKind = authorKindFromWire(cursor.text("author_kind")),
                    authorId = cursor.text("author_id"),
                    authorName = cursor.text("author_name"),
                    authorAvatar = cursor.nullableText("author_avatar"),
                    body = cursor.text("body"),
                    visibility = visibilityFromWire(cursor.text("visibility")),
                    selectedAssistantIds = acl[id].orEmpty(),
                    mediaIds = postMedia[id].orEmpty(),
                    chatSnapshot = LocalDataChatSnapshots.decode(cursor.nullableText("chat_snapshot_json")),
                    clientKey = cursor.nullableText("client_key"),
                    createdAt = cursor.long("created_at"),
                    updatedAt = cursor.long("updated_at"),
                )
            }
        }

        val comments = mutableListOf<LocalBackupComment>()
        db.rawQuery(
            "SELECT * FROM comments WHERE deleted_at IS NULL " +
                "AND post_id IN (SELECT id FROM posts WHERE deleted_at IS NULL) " +
                "ORDER BY created_at ASC, id ASC",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                comments += LocalBackupComment(
                    id = cursor.text("id"),
                    postId = cursor.text("post_id"),
                    authorKind = authorKindFromWire(cursor.text("author_kind")),
                    authorId = cursor.text("author_id"),
                    authorName = cursor.text("author_name"),
                    authorAvatar = cursor.nullableText("author_avatar"),
                    body = cursor.text("body"),
                    replyToId = cursor.nullableText("reply_to_id"),
                    clientKey = cursor.nullableText("client_key"),
                    createdAt = cursor.long("created_at"),
                )
            }
        }

        val likes = mutableListOf<LocalBackupLike>()
        db.rawQuery(
            "SELECT post_id, actor_kind, actor_id, created_at FROM likes " +
                "WHERE post_id IN (SELECT id FROM posts WHERE deleted_at IS NULL) ORDER BY created_at ASC",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                likes += LocalBackupLike(
                    postId = cursor.text("post_id"),
                    actorKind = authorKindFromWire(cursor.text("actor_kind")),
                    actorId = cursor.text("actor_id"),
                    createdAt = cursor.long("created_at"),
                )
            }
        }

        val drafts = mutableListOf<LocalBackupDraft>()
        val draftMedia = linkedMapOf<String, MutableList<String>>()
        db.rawQuery("SELECT draft_id, resource_id FROM draft_media ORDER BY draft_id, ordinal", emptyArray()).use { cursor ->
            while (cursor.moveToNext()) {
                draftMedia.getOrPut(cursor.text("draft_id")) { mutableListOf() } += cursor.text("resource_id")
            }
        }
        val draftAcl = linkedMapOf<String, MutableList<String>>()
        db.rawQuery("SELECT draft_id, assistant_id FROM draft_acl", emptyArray()).use { cursor ->
            while (cursor.moveToNext()) {
                draftAcl.getOrPut(cursor.text("draft_id")) { mutableListOf() } += cursor.text("assistant_id")
            }
        }
        db.rawQuery("SELECT * FROM drafts ORDER BY created_at ASC, id ASC", emptyArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.text("id")
                drafts += LocalBackupDraft(
                    id = id,
                    authorKind = authorKindFromWire(cursor.text("author_kind")),
                    authorId = cursor.text("author_id"),
                    authorName = cursor.text("author_name"),
                    authorAvatar = cursor.nullableText("author_avatar"),
                    body = cursor.text("body"),
                    visibility = visibilityFromWire(cursor.text("visibility")),
                    selectedAssistantIds = draftAcl[id].orEmpty(),
                    mediaIds = draftMedia[id].orEmpty(),
                    chatSnapshot = LocalDataChatSnapshots.decode(cursor.nullableText("chat_snapshot_json")),
                    createdAt = cursor.long("created_at"),
                    updatedAt = cursor.long("updated_at"),
                )
            }
        }

        val media = mutableListOf<LocalBackupMedia>()
        db.rawQuery(
            "SELECT * FROM media_assets ORDER BY created_at ASC, resource_id ASC",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                media += LocalBackupMedia(
                    resourceId = cursor.text("resource_id"),
                    mime = cursor.text("mime"),
                    byteSize = cursor.long("byte_size"),
                    width = cursor.int("width"),
                    height = cursor.int("height"),
                    sha256 = cursor.text("sha256"),
                    displayName = cursor.nullableText("display_name"),
                    fileName = cursor.text("file_name"),
                    createdAt = cursor.long("created_at"),
                )
            }
        }

        return LocalDataBackupPayload(
            authors = authors,
            posts = posts,
            comments = comments,
            likes = likes,
            drafts = drafts,
            media = media,
        )
    }

    private fun listCommentsInternal(
        db: SQLiteDatabase,
        postId: String,
        cursor: String?,
        limit: Int,
        order: LocalCommentOrder,
    ): LocalCommentPageDto {
        val pageSize = limit.coerceIn(1, LocalDataApi.MAX_PAGE_SIZE)
        val newestFirst = order == LocalCommentOrder.NEWEST
        val where = mutableListOf("post_id = ?", "deleted_at IS NULL")
        val args = mutableListOf(postId)
        val decoded = decodeCursor(cursor)
        if (decoded != null) {
            // Cursors are only valid for the order that produced them: each page ends at the
            // boundary item and the next query continues strictly past it in the same direction.
            where += if (newestFirst) {
                "(created_at < ? OR (created_at = ? AND id < ?))"
            } else {
                "(created_at > ? OR (created_at = ? AND id > ?))"
            }
            args += decoded.first.toString()
            args += decoded.first.toString()
            args += decoded.second
        }
        val direction = if (newestFirst) "DESC" else "ASC"
        val comments = mutableListOf<LocalCommentDto>()
        val total = commentCount(db, postId)
        db.rawQuery(
            "SELECT * FROM comments WHERE ${where.joinToString(" AND ")} " +
                "ORDER BY created_at $direction, id $direction LIMIT ?",
            (args + (pageSize + 1).toString()).toTypedArray(),
        ).use { c ->
            while (c.moveToNext()) {
                comments += mapComment(c)
            }
        }
        val hasMore = comments.size > pageSize
        val items = if (hasMore) comments.subList(0, pageSize).toList() else comments
        val last = items.lastOrNull()
        return LocalCommentPageDto(
            items = items,
            nextCursor = if (hasMore && last != null) {
                LocalDataCursorCodec.encode(last.createdAt, last.id)
            } else {
                null
            },
            hasMore = hasMore,
            commentCount = total,
        )
    }

    private fun mediaForPost(db: SQLiteDatabase, postId: String): List<LocalMediaDto> {
        val result = mutableListOf<LocalMediaDto>()
        db.rawQuery(
            """
            SELECT m.resource_id, m.mime, m.byte_size, m.width, m.height
            FROM post_media pm JOIN media_assets m ON m.resource_id = pm.resource_id
            WHERE pm.post_id = ? ORDER BY pm.ordinal ASC
            """.trimIndent(),
            arrayOf(postId),
        ).use { cursor ->
            while (cursor.moveToNext()) result += mediaDto(cursor)
        }
        return result
    }

    private fun commentsPreview(
        db: SQLiteDatabase,
        postId: String,
        offset: Int,
        limit: Int,
    ): List<LocalCommentDto> {
        if (limit <= 0) return emptyList()
        val result = mutableListOf<LocalCommentDto>()
        db.rawQuery(
            "SELECT * FROM comments WHERE post_id = ? AND deleted_at IS NULL " +
                "ORDER BY created_at ASC, id ASC LIMIT ? OFFSET ?",
            arrayOf(postId, limit.toString(), offset.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) result += mapComment(cursor)
        }
        return result
    }

    fun commentCount(postId: String): Int = commentCount(database.readable(), postId)

    private fun commentCount(db: SQLiteDatabase, postId: String): Int {
        db.rawQuery(
            "SELECT COUNT(*) FROM comments WHERE post_id = ? AND deleted_at IS NULL",
            arrayOf(postId),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun readPost(
        db: SQLiteDatabase,
        viewer: LocalDataActor,
        postId: String,
    ): Pair<LocalPostDto, Int>? {
        if (!LocalDataValidation.isSafeRecordId(postId)) return null
        val (viewerClause, viewerArgs) = viewerPredicate(viewer)
        val where = mutableListOf("p.id = ?", "p.deleted_at IS NULL")
        if (viewerClause.isNotBlank()) where += viewerClause
        val args = mutableListOf<String>()
        args += actorKind(viewer)
        args += actorId(viewer)
        args += postId
        args += viewerArgs
        db.rawQuery(
            """
            SELECT p.*,
              (SELECT COUNT(*) FROM comments c WHERE c.post_id = p.id AND c.deleted_at IS NULL) AS comment_count,
              (SELECT COUNT(*) FROM likes l WHERE l.post_id = p.id) AS like_count,
              EXISTS(SELECT 1 FROM likes l2 WHERE l2.post_id = p.id AND l2.actor_kind = ? AND l2.actor_id = ?) AS liked_by_me
            FROM posts p WHERE ${where.joinToString(" AND ")} LIMIT 1
            """.trimIndent(),
            args.toTypedArray(),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val fullBody = cursor.text("body")
            val post = mapPost(
                cursor = cursor,
                database = database,
                viewer = viewer,
                body = fullBody,
                bodyTruncated = false,
                previewLimit = 0,
                includeChatSnapshot = true,
            )
            return post to fullBody.length
        }
    }

    fun requireVisible(viewer: LocalDataActor, postId: String) {
        if (readPost(database.readable(), viewer, postId) == null) {
            throw LocalDataException(LocalDataErrorCode.NOT_FOUND, "Post does not exist")
        }
    }

    /** Used for the write result after an ACL check already happened in the write path. */
    fun readPostForResult(postId: String): LocalPostDto? {
        if (!LocalDataValidation.isSafeRecordId(postId)) return null
        database.readable().rawQuery(
            """
            SELECT p.*,
              (SELECT COUNT(*) FROM comments c WHERE c.post_id = p.id AND c.deleted_at IS NULL) AS comment_count,
              (SELECT COUNT(*) FROM likes l WHERE l.post_id = p.id) AS like_count,
              0 AS liked_by_me
            FROM posts p WHERE p.id = ? AND p.deleted_at IS NULL LIMIT 1
            """.trimIndent(),
            arrayOf(postId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return mapPost(
                cursor = cursor,
                database = database,
                viewer = LocalDataActor.User,
                body = cursor.text("body"),
                bodyTruncated = false,
                previewLimit = 0,
                includeChatSnapshot = true,
            )
        }
    }

    private fun mapPost(
        cursor: Cursor,
        database: LocalDataDatabase,
        viewer: LocalDataActor,
        body: String,
        bodyTruncated: Boolean,
        previewLimit: Int,
        includeChatSnapshot: Boolean,
    ): LocalPostDto {
        val id = cursor.text("id")
        val snapshot = LocalDataChatSnapshots.decode(cursor.nullableText("chat_snapshot_json"))
        return LocalPostDto(
            id = id,
            author = LocalAuthorDto(
                kind = authorKindFromWire(cursor.text("author_kind")),
                id = cursor.text("author_id"),
                name = cursor.text("author_name"),
                avatar = cursor.nullableText("author_avatar"),
            ),
            body = body,
            bodyTruncated = bodyTruncated,
            visibility = visibilityFromWire(cursor.text("visibility")),
            selectedAssistantIds = if (viewer is LocalDataActor.User) {
                selectedAssistantIds(database.readable(), id)
            } else {
                emptyList()
            },
            media = mediaForPost(database.readable(), id),
            chatSnapshot = if (includeChatSnapshot) snapshot else null,
            createdAt = cursor.long("created_at"),
            updatedAt = cursor.long("updated_at"),
            commentCount = cursor.int("comment_count"),
            likeCount = cursor.int("like_count"),
            likedByMe = cursor.int("liked_by_me") != 0,
            previewComments = commentsPreview(database.readable(), id, 0, previewLimit),
        )
    }

    private fun selectedAssistantIds(db: SQLiteDatabase, postId: String): List<String> {
        val result = mutableListOf<String>()
        db.rawQuery(
            "SELECT assistant_id FROM post_acl WHERE post_id = ? ORDER BY assistant_id",
            arrayOf(postId),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.text("assistant_id")
        }
        return result
    }

    private fun viewerPredicate(viewer: LocalDataActor): Pair<String, List<String>> = when (viewer) {
        LocalDataActor.User -> "" to emptyList()
        is LocalDataActor.Assistant -> {
            val id = viewer.id
            "(p.visibility = '${PostVisibility.ALL_ASSISTANTS.wire()}' OR " +
                "(p.visibility = '${PostVisibility.SELECTED.wire()}' AND EXISTS (" +
                "SELECT 1 FROM post_acl a WHERE a.post_id = p.id AND a.assistant_id = ?)) OR " +
                "(p.visibility = '${PostVisibility.SELF.wire()}' AND p.author_kind = " +
                "'${AuthorKind.ASSISTANT.wire()}' AND p.author_id = ?))" to listOf(id, id)
        }
    }

    private fun decodeCursor(cursor: String?): Pair<Long, String>? {
        if (cursor.isNullOrBlank()) return null
        return LocalDataCursorCodec.decode(cursor)
            ?: throw LocalDataException(LocalDataErrorCode.INVALID_REQUEST, "Invalid cursor")
    }

    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}

private fun LocalChatSnapshotDto.allMediaReferences(): List<String> =
    LocalDataChatSnapshots.mediaReferences(this)

private fun LocalChatSnapshotDto.referencesMedia(resourceId: String): Boolean =
    messages.any { message -> resourceId in message.media || message.avatarMediaId == resourceId }

internal fun mapComment(cursor: Cursor): LocalCommentDto = LocalCommentDto(
    id = cursor.text("id"),
    postId = cursor.text("post_id"),
    author = LocalAuthorDto(
        kind = authorKindFromWire(cursor.text("author_kind")),
        id = cursor.text("author_id"),
        name = cursor.text("author_name"),
        avatar = cursor.nullableText("author_avatar"),
    ),
    body = cursor.text("body"),
    createdAt = cursor.long("created_at"),
    replyToId = cursor.nullableText("reply_to_id"),
)

internal fun mediaDto(cursor: Cursor): LocalMediaDto {
    val resourceId = cursor.text("resource_id")
    return LocalMediaDto(
        resourceId = resourceId,
        mime = cursor.text("mime"),
        width = cursor.int("width"),
        height = cursor.int("height"),
        byteSize = cursor.long("byte_size"),
        thumbUrl = LocalDataMediaUrl.thumb(resourceId),
        originalUrl = LocalDataMediaUrl.original(resourceId),
    )
}

internal fun actorKind(actor: LocalDataActor): String = when (actor) {
    LocalDataActor.User -> AuthorKind.USER.wire()
    is LocalDataActor.Assistant -> AuthorKind.ASSISTANT.wire()
}

internal fun actorId(actor: LocalDataActor): String = when (actor) {
    LocalDataActor.User -> "local-user"
    is LocalDataActor.Assistant -> actor.id
}

internal fun AuthorKind.wire(): String = when (this) {
    AuthorKind.USER -> "user"
    AuthorKind.ASSISTANT -> "assistant"
}

internal fun PostVisibility.wire(): String = when (this) {
    PostVisibility.ALL_ASSISTANTS -> "all_assistants"
    PostVisibility.SELECTED -> "selected"
    PostVisibility.SELF -> "self"
}

internal fun authorKindFromWire(value: String): AuthorKind = when (value) {
    "assistant" -> AuthorKind.ASSISTANT
    else -> AuthorKind.USER
}

internal fun visibilityFromWire(value: String): PostVisibility = when (value) {
    "selected" -> PostVisibility.SELECTED
    "self" -> PostVisibility.SELF
    else -> PostVisibility.ALL_ASSISTANTS
}

internal fun Cursor.text(column: String): String = getString(getColumnIndexOrThrow(column)) ?: ""

internal fun Cursor.nullableText(column: String): String? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getString(index)
}

internal fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

internal fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))
