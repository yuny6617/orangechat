/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localdata

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Typed method+params dispatcher. The actor and grant come from [LocalDataCallContext], never from
 * `params`; this is the boundary that prevents a plugin page from claiming to be an assistant.
 */
class LocalDataDispatcher(
    private val store: LocalDataStore,
    private val media: LocalDataMediaStore,
) {
    fun dispatchJson(raw: String, context: LocalDataCallContext): String {
        val envelope = try {
            dispatch(LocalDataRequestParser.parse(raw), context)
        } catch (error: LocalDataException) {
            errorEnvelope(error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorEnvelope(LocalDataException(LocalDataErrorCode.INTERNAL, "Internal error"))
        }
        return LocalDataJson.json.encodeToString(LocalDataEnvelope.serializer(), envelope)
    }

    fun dispatch(request: LocalDataRequest, context: LocalDataCallContext): LocalDataEnvelope {
        return try {
            if (request.version != LocalDataApi.VERSION) {
                throw LocalDataException(LocalDataErrorCode.UNSUPPORTED_VERSION, "Unsupported API version")
            }
            if (!LocalDataValidation.METHOD_PATTERN.matches(request.method)) {
                throw LocalDataException(LocalDataErrorCode.INVALID_REQUEST, "Invalid method")
            }
            LocalDataRequestParser.requireNoIdentityFields(request.params)
            val actor = trustedActor(context)
            val data = route(request.method, request.params, context, actor)
            LocalDataEnvelope(ok = true, data = data)
        } catch (error: LocalDataException) {
            errorEnvelope(error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            errorEnvelope(LocalDataException(LocalDataErrorCode.INVALID_REQUEST, "Invalid request"))
        } catch (error: Exception) {
            errorEnvelope(LocalDataException(LocalDataErrorCode.INTERNAL, "Internal error"))
        }
    }

    private fun route(
        method: String,
        rawParams: JsonObject,
        context: LocalDataCallContext,
        actor: LocalDataActor,
    ): JsonElement {
        val params = Params(rawParams)
        if (method in METHODS_WITH_ACTOR_IDENTITY) {
            params.rejectAuthorIdentity()
        }
        val now = context.now()
        return when (method) {
            LocalDataMethods.INFO -> encode(
                LocalInfoDto(pluginId = store.database.pluginId),
            )

            LocalDataMethods.LIST -> {
                context.requireCapability(LocalDataCapability.READ)
                val page = store.listPosts(
                    viewer = actor,
                    filter = params.filter(),
                    request = params.pageRequest(context, actor),
                )
                encode(page)
            }

            LocalDataMethods.SEARCH -> {
                context.requireCapability(LocalDataCapability.READ)
                val query = params.requiredString("query", LocalDataApi.MAX_SEARCH_CHARS)
                val page = store.searchPosts(
                    viewer = actor,
                    query = query,
                    filter = params.filter(),
                    request = params.pageRequest(context, actor),
                )
                encode(page)
            }

            LocalDataMethods.GET -> {
                context.requireCapability(LocalDataCapability.READ)
                val postId = params.requiredId("postId")
                val comments = params.int("commentLimit", LocalDataApi.DEFAULT_PAGE_SIZE, 0, 10)
                val includeMedia = (params.boolean("includeMedia", true) ?: true) &&
                    context.grant.allows(LocalDataCapability.MEDIA)
                val includeChat = (params.boolean("includeChatSnapshot", true) ?: true) &&
                    context.grant.allows(LocalDataCapability.CHAT_SNAPSHOT)
                val detail = store.getPost(
                    viewer = actor,
                    postId = postId,
                    bodyChars = params.int(
                        "bodyChars",
                        LocalDataApi.MAX_BODY_BUDGET_CHARS,
                        0,
                        LocalDataApi.MAX_BODY_BUDGET_CHARS,
                    ),
                    commentLimit = comments,
                    commentCursor = params.optionalString("commentCursor"),
                    includeMedia = includeMedia,
                    includeChatSnapshot = includeChat,
                    commentOrder = params.commentOrder("order"),
                )
                encode(detail)
            }

            LocalDataMethods.COMMENTS_LIST -> {
                context.requireCapability(LocalDataCapability.READ)
                val postId = params.requiredId("postId")
                val page = store.listComments(
                    viewer = actor,
                    postId = postId,
                    cursor = params.optionalString("cursor"),
                    limit = params.int("limit", LocalDataApi.DEFAULT_PAGE_SIZE, 1, LocalDataApi.MAX_PAGE_SIZE),
                    order = params.commentOrder("order"),
                )
                encode(page)
            }

            LocalDataMethods.COMMENT_CREATE -> {
                context.requireCapability(LocalDataCapability.COMMENT)
                val comment = store.createComment(
                    actor = actor,
                    author = authorOf(context, actor),
                    input = LocalCommentInput(
                        postId = params.requiredId("postId"),
                        body = params.requiredString("body", LocalDataApi.MAX_COMMENT_CHARS),
                        replyToId = params.optionalId("replyToId"),
                        clientKey = params.optionalString("clientKey"),
                    ),
                    now = now,
                )
                encode(comment)
            }

            LocalDataMethods.LIKE_SET -> {
                context.requireCapability(LocalDataCapability.LIKE)
                val result = store.setLike(
                    actor = actor,
                    postId = params.requiredId("postId"),
                    liked = params.requiredBoolean("liked"),
                    now = now,
                )
                encode(result)
            }

            LocalDataMethods.CREATE -> {
                context.requireCapability(LocalDataCapability.WRITE)
                if (actor is LocalDataActor.Assistant &&
                    (!context.grant.allows(LocalDataCapability.PUBLISH) || !context.assistantAutoPublish)
                ) {
                    throw LocalDataException(
                        LocalDataErrorCode.CAPABILITY_DENIED,
                        "Assistant publishing is not enabled",
                    )
                }
                val author = authorOf(context, actor)
                val draftId = params.optionalId("draftId")
                val post = if (draftId != null) {
                    store.publishDraft(author, draftId, now)
                } else {
                    store.createPost(
                        author = author,
                        input = LocalPostInput(
                            body = params.optionalString("body", LocalDataApi.MAX_BODY_CHARS).orEmpty(),
                            visibility = params.visibility("visibility", PostVisibility.ALL_ASSISTANTS),
                            selectedAssistantIds = params.selectedAssistants(context),
                            mediaIds = params.idList("mediaIds", LocalDataApi.MAX_MEDIA_PER_POST),
                            chatSnapshot = params.chatSnapshot(context),
                            clientKey = params.optionalString("clientKey"),
                        ),
                        now = now,
                    )
                }
                encode(LocalPostCreateResultDto(post))
            }

            LocalDataMethods.DELETE -> {
                context.requireCapability(LocalDataCapability.WRITE)
                val postId = params.requiredId("postId")
                store.deletePost(actor, postId, now)
                encode(LocalDeleteResultDto(id = postId))
            }

            LocalDataMethods.DRAFT_SAVE -> {
                context.requireCapability(LocalDataCapability.WRITE)
                val draft = store.saveDraft(
                    author = authorOf(context, actor),
                    input = LocalDraftInput(
                        draftId = params.optionalId("draftId"),
                        body = params.optionalString("body", LocalDataApi.MAX_BODY_CHARS).orEmpty(),
                        visibility = params.visibility("visibility", PostVisibility.ALL_ASSISTANTS),
                        selectedAssistantIds = params.selectedAssistants(context),
                        mediaIds = params.idList("mediaIds", LocalDataApi.MAX_MEDIA_PER_POST),
                        chatSnapshot = params.chatSnapshot(context),
                    ),
                    now = now,
                )
                encode(draft)
            }

            LocalDataMethods.DRAFT_LIST -> {
                context.requireCapability(LocalDataCapability.WRITE)
                val drafts = store.listDrafts(
                    actor = actor,
                    cursor = params.optionalString("cursor"),
                    limit = params.int("limit", LocalDataApi.DEFAULT_PAGE_SIZE, 1, LocalDataApi.MAX_PAGE_SIZE),
                )
                encode(drafts)
            }

            LocalDataMethods.DRAFT_DELETE -> {
                context.requireCapability(LocalDataCapability.WRITE)
                val draftId = params.requiredId("draftId")
                store.deleteDraft(authorOf(context, actor), draftId)
                encode(LocalDeleteResultDto(id = draftId))
            }

            LocalDataMethods.AUTHOR_GET -> {
                context.requireCapability(LocalDataCapability.READ)
                val kind = params.authorKind("authorKind")
                val id = params.requiredId("authorId")
                val snapshot = store.getAuthorSnapshot(kind, id)
                    ?: throw LocalDataException(LocalDataErrorCode.NOT_FOUND, "Author does not exist")
                encode(snapshot)
            }

            LocalDataMethods.MEDIA_DESCRIBE -> {
                context.requireCapability(LocalDataCapability.MEDIA)
                val resourceId = params.requiredMediaId("resourceId")
                if (!store.canAccessMedia(actor, resourceId)) {
                    throw LocalDataException(LocalDataErrorCode.NOT_FOUND, "Media does not exist")
                }
                val metadata = store.mediaMetadata(resourceId)
                    ?: throw LocalDataException(LocalDataErrorCode.NOT_FOUND, "Media does not exist")
                encode(metadata)
            }

            else -> throw LocalDataException(LocalDataErrorCode.UNKNOWN_METHOD, "Unknown method")
        }
    }

    private fun trustedActor(context: LocalDataCallContext): LocalDataActor {
        return when (val actor = context.actor) {
            LocalDataActor.User -> LocalDataActor.User
            is LocalDataActor.Assistant -> {
                val ref = context.requireTrustedAssistant()
                actor.copy(
                    name = actor.name.ifBlank { ref.name },
                    avatar = actor.avatar ?: ref.avatar,
                )
            }
        }
    }

    private fun authorOf(context: LocalDataCallContext, actor: LocalDataActor): LocalAuthorDto = when (actor) {
        LocalDataActor.User -> LocalAuthorDto(
            kind = AuthorKind.USER,
            id = "local-user",
            name = context.userDisplayName.take(100).ifBlank { "我" },
        )
        is LocalDataActor.Assistant -> LocalAuthorDto(
            kind = AuthorKind.ASSISTANT,
            id = actor.id,
            name = actor.name.take(100).ifBlank { "Assistant" },
            avatar = actor.avatar,
        )
    }

    private fun errorEnvelope(error: LocalDataException): LocalDataEnvelope {
        val retryable = error.retryable || error.code == LocalDataErrorCode.UNAVAILABLE
        return LocalDataEnvelope(
            ok = false,
            error = LocalDataErrorDto(
                code = error.code.wire,
                message = error.message.take(200),
                retryable = retryable,
            ),
        )
    }

    private fun encode(value: LocalPostPageDto): JsonElement =
        LocalDataJson.json.encodeToJsonElement(LocalPostPageDto.serializer(), value)

    private fun encode(value: LocalPostDetailDto): JsonElement =
        LocalDataJson.json.encodeToJsonElement(LocalPostDetailDto.serializer(), value)

    private fun encode(value: LocalCommentPageDto): JsonElement =
        LocalDataJson.json.encodeToJsonElement(LocalCommentPageDto.serializer(), value)

    private fun encode(value: LocalCommentCreateResultDto): JsonElement =
        LocalDataJson.json.encodeToJsonElement(LocalCommentCreateResultDto.serializer(), value)

    private fun encode(value: LocalLikeResultDto): JsonElement =
        LocalDataJson.json.encodeToJsonElement(LocalLikeResultDto.serializer(), value)

    private fun encode(value: LocalPostCreateResultDto): JsonElement =
        LocalDataJson.json.encodeToJsonElement(LocalPostCreateResultDto.serializer(), value)

    private fun encode(value: LocalDeleteResultDto): JsonElement =
        LocalDataJson.json.encodeToJsonElement(LocalDeleteResultDto.serializer(), value)

    private fun encode(value: LocalDraftDto): JsonElement =
        LocalDataJson.json.encodeToJsonElement(LocalDraftDto.serializer(), value)

    private fun encode(value: LocalDraftPageDto): JsonElement =
        LocalDataJson.json.encodeToJsonElement(LocalDraftPageDto.serializer(), value)

    private fun encode(value: LocalAuthorSnapshotDto): JsonElement =
        LocalDataJson.json.encodeToJsonElement(LocalAuthorSnapshotDto.serializer(), value)

    private fun encode(value: LocalMediaDto): JsonElement =
        LocalDataJson.json.encodeToJsonElement(LocalMediaDto.serializer(), value)

    private fun encode(value: LocalInfoDto): JsonElement =
        LocalDataJson.json.encodeToJsonElement(LocalInfoDto.serializer(), value)

    private companion object {
        /** Author identity is always derived from the host actor, never from these write params. */
        val METHODS_WITH_ACTOR_IDENTITY = setOf(
            LocalDataMethods.CREATE,
            LocalDataMethods.DELETE,
            LocalDataMethods.COMMENT_CREATE,
            LocalDataMethods.LIKE_SET,
            LocalDataMethods.DRAFT_SAVE,
            LocalDataMethods.DRAFT_DELETE,
        )
    }
}

private class Params(private val values: JsonObject) {
    fun rejectAuthorIdentity() {
        val forbidden = setOf(
            "authorId", "author_id",
            "authorName", "author_name",
            "authorKind", "author_kind",
            "authorAvatar", "author_avatar",
        )
        if (values.keys.any { it in forbidden }) invalid("Author identity is host-owned")
    }

    fun filter(): LocalPostFilter = LocalPostFilter(
        authorId = optionalId("authorId"),
        authorKind = optionalAuthorKind("authorKind"),
        type = optionalString("type")?.also {
            if (it !in setOf("text", "image", "chat_snapshot")) invalid("Unsupported type")
        },
        dateFrom = optionalLong("dateFrom"),
        dateTo = optionalLong("dateTo"),
    )

    fun pageRequest(context: LocalDataCallContext, actor: LocalDataActor): LocalPageRequest {
        val assistant = actor is LocalDataActor.Assistant
        val defaultBody = if (assistant) {
            LocalDataApi.DEFAULT_ASSISTANT_BODY_CHARS
        } else {
            LocalDataApi.DEFAULT_USER_BODY_CHARS
        }
        return LocalPageRequest(
            cursor = optionalString("cursor"),
            limit = int("limit", LocalDataApi.DEFAULT_PAGE_SIZE, 1, LocalDataApi.MAX_PAGE_SIZE),
            bodyChars = int("bodyChars", defaultBody, 0, LocalDataApi.MAX_BODY_BUDGET_CHARS),
            pageBodyBudget = LocalDataApi.DEFAULT_PAGE_BODY_BUDGET_CHARS,
            commentPreview = int("commentPreview", 0, 0, LocalDataApi.MAX_COMMENT_PREVIEW),
            includeMedia = context.grant.allows(LocalDataCapability.MEDIA),
        )
    }

    fun selectedAssistants(context: LocalDataCallContext): List<String> {
        val ids = idList("selectedAssistantIds", LocalDataApi.MAX_SELECTED_ASSISTANTS)
        ids.forEach { id ->
            if (!LocalDataValidation.isSafeAssistantId(id)) invalid("Invalid selected assistant")
            if (context.assistantDirectory.find(id) == null) invalid("Unknown selected assistant")
        }
        return ids
    }

    fun chatSnapshot(context: LocalDataCallContext): LocalChatSnapshotDto? {
        val element = values["chatSnapshot"] ?: return null
        if (element is JsonNull) return null
        if (!context.grant.allows(LocalDataCapability.CHAT_SNAPSHOT)) {
            throw LocalDataException(LocalDataErrorCode.CAPABILITY_DENIED, "Chat snapshot is not granted")
        }
        val snapshot = try {
            LocalDataJson.json.decodeFromJsonElement(LocalChatSnapshotDto.serializer(), element)
        } catch (error: Exception) {
            invalid("Invalid chat snapshot")
        }
        val sanitized = try {
            LocalDataChatSnapshots.sanitize(snapshot)
        } catch (error: IllegalArgumentException) {
            invalid(error.message ?: "Invalid chat snapshot")
        }
        return sanitized
    }

    fun visibility(name: String, default: PostVisibility): PostVisibility {
        val raw = optionalString(name) ?: return default
        return when (raw) {
            "all_assistants" -> PostVisibility.ALL_ASSISTANTS
            "selected" -> PostVisibility.SELECTED
            "self" -> PostVisibility.SELF
            else -> invalid("Unsupported visibility")
        }
    }

    fun authorKind(name: String): AuthorKind {
        return when (requiredString(name, 20)) {
            "user" -> AuthorKind.USER
            "assistant" -> AuthorKind.ASSISTANT
            else -> invalid("Unsupported author kind")
        }
    }

    fun requiredId(name: String): String {
        val value = requiredString(name, 80)
        if (!LocalDataValidation.isSafeRecordId(value)) invalid("Invalid $name")
        return value
    }

    fun optionalId(name: String): String? {
        val value = optionalString(name, 80) ?: return null
        if (!LocalDataValidation.isSafeRecordId(value)) invalid("Invalid $name")
        return value
    }

    fun requiredMediaId(name: String): String {
        val value = requiredString(name, 80)
        if (!LocalDataValidation.isSafeMediaId(value)) invalid("Invalid $name")
        return value
    }

    fun idList(name: String, max: Int): List<String> {
        val element = values[name] ?: return emptyList()
        if (element is JsonNull) return emptyList()
        val array = element as? JsonArray ?: invalid("$name must be an array")
        if (array.size > max) {
            throw LocalDataException(LocalDataErrorCode.PAYLOAD_TOO_LARGE, "$name has too many items")
        }
        return array.map { item ->
            val value = (item as? JsonPrimitive)?.contentOrNullSafe() ?: invalid("Invalid $name item")
            if (value.length > 80) invalid("Invalid $name item")
            value
        }
    }

    fun requiredString(name: String, maxChars: Int): String {
        val value = optionalString(name, maxChars) ?: invalid("Missing $name")
        if (value.isBlank()) invalid("$name must not be empty")
        return value
    }

    fun optionalString(name: String, maxChars: Int = 200): String? {
        val element = values[name] ?: return null
        if (element is JsonNull) return null
        val value = (element as? JsonPrimitive)?.contentOrNullSafe() ?: invalid("$name must be a string")
        if (value.length > maxChars) {
            throw LocalDataException(LocalDataErrorCode.PAYLOAD_TOO_LARGE, "$name is too long")
        }
        return value
    }

    fun int(name: String, default: Int, min: Int, max: Int): Int {
        val element = values[name] ?: return default
        if (element is JsonNull) return default
        val value = (element as? JsonPrimitive)?.intOrNullSafe() ?: invalid("$name must be an integer")
        if (value !in min..max) invalid("$name is out of range")
        return value
    }

    fun requiredBoolean(name: String): Boolean =
        boolean(name, null) ?: invalid("Missing $name")

    fun boolean(name: String, default: Boolean?): Boolean? {
        val element = values[name] ?: return default
        if (element is JsonNull) return default
        return (element as? JsonPrimitive)?.boolOrNullSafe() ?: invalid("$name must be a boolean")
    }

    fun optionalLong(name: String): Long? {
        val element = values[name] ?: return null
        if (element is JsonNull) return null
        val value = (element as? JsonPrimitive)?.longOrNullSafe() ?: invalid("$name must be a number")
        if (value < 0) invalid("$name must be positive")
        return value
    }

    fun optionalAuthorKind(name: String): AuthorKind? {
        val value = optionalString(name) ?: return null
        return when (value) {
            "user" -> AuthorKind.USER
            "assistant" -> AuthorKind.ASSISTANT
            else -> invalid("Unsupported author kind")
        }
    }

    /** Missing order keeps the historical oldest-first comment window; unknown values are rejected. */
    fun commentOrder(name: String): LocalCommentOrder =
        LocalCommentOrder.fromWire(optionalString(name, 16))

    private fun invalid(message: String): Nothing =
        throw LocalDataException(LocalDataErrorCode.INVALID_REQUEST, message)
}
