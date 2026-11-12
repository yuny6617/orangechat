/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localdata

import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Stable wire contract for the plugin local-data bridge. See LOCALDATA_API.md. */
object LocalDataApi {
    const val VERSION = 1
    const val DB_SCHEMA_VERSION = 1
    const val BACKUP_FORMAT = "orangechat.localdata.backup"
    const val BACKUP_FORMAT_VERSION = 1

    const val DEFAULT_PAGE_SIZE = 3
    const val MAX_PAGE_SIZE = 20
    const val MAX_BODY_CHARS = 20_000
    const val MAX_COMMENT_CHARS = 2_000
    const val MAX_MEDIA_PER_POST = 9
    const val MAX_SELECTED_ASSISTANTS = 50
    const val MAX_CLIENT_KEY_CHARS = 64
    const val MAX_SEARCH_CHARS = 100
    const val MAX_REQUEST_BYTES = 64 * 1024
    const val MAX_CHAT_SNAPSHOT_BYTES = 32 * 1024
    const val MAX_CHAT_MESSAGES = 50
    const val MAX_CHAT_MESSAGE_CHARS = 2_000
    const val MAX_CHAT_TOTAL_CHARS = 20_000
    /** Participant avatars are a separate, tiny budget: at most the two chat participants. */
    const val MAX_CHAT_AVATARS = 2
    const val MAX_CHAT_AVATAR_EMOJI_CHARS = 32

    const val DEFAULT_ASSISTANT_BODY_CHARS = 800
    const val DEFAULT_USER_BODY_CHARS = 4_000
    const val MAX_BODY_BUDGET_CHARS = 4_000
    const val DEFAULT_PAGE_BODY_BUDGET_CHARS = 8_000
    const val MAX_COMMENT_PREVIEW = 3

    const val MEDIA_ORIGIN = "https://localdata.invalid"
    const val MEDIA_URL_PREFIX = "$MEDIA_ORIGIN/media/"
    const val MAX_MEDIA_BYTES = 12 * 1024 * 1024
    const val THUMBNAIL_MAX_PX = 512
}

enum class LocalDataErrorCode(val wire: String) {
    INVALID_REQUEST("invalid_request"),
    UNSUPPORTED_VERSION("unsupported_version"),
    UNKNOWN_METHOD("unknown_method"),
    CAPABILITY_DENIED("capability_denied"),
    FORBIDDEN("forbidden"),
    NOT_FOUND("not_found"),
    CONFLICT("conflict"),
    PAYLOAD_TOO_LARGE("payload_too_large"),
    UNAVAILABLE("unavailable"),
    INTERNAL("internal"),
}

class LocalDataException(
    val code: LocalDataErrorCode,
    override val message: String,
    val retryable: Boolean = false,
) : Exception(message)

object LocalDataMethods {
    const val INFO = "localdata.info"
    const val LIST = "moments.list"
    const val GET = "moments.get"
    const val SEARCH = "moments.search"
    const val COMMENTS_LIST = "moments.comments.list"
    const val COMMENT_CREATE = "moments.comment.create"
    const val LIKE_SET = "moments.like.set"
    const val CREATE = "moments.create"
    const val DELETE = "moments.delete"
    const val DRAFT_SAVE = "moments.draft.save"
    const val DRAFT_LIST = "moments.draft.list"
    const val DRAFT_DELETE = "moments.draft.delete"
    const val AUTHOR_GET = "moments.author.get"
    const val MEDIA_DESCRIBE = "moments.media.describe"
}

enum class LocalDataCapability(val wire: String) {
    READ("localdata.read"),
    WRITE("localdata.write"),
    PUBLISH("localdata.publish"),
    COMMENT("localdata.comment"),
    LIKE("localdata.like"),
    MEDIA("localdata.media"),
    CHAT_SNAPSHOT("localdata.chat_snapshot"),
    BACKUP("localdata.backup"),
}

class LocalDataGrant(capabilities: Set<LocalDataCapability>) {
    private val capabilities: Set<LocalDataCapability> = capabilities.toSet()

    fun allows(capability: LocalDataCapability): Boolean = capability in capabilities

    fun declaredNames(): Set<String> = capabilities.mapTo(linkedSetOf()) { it.wire }

    fun restrictedTo(allowed: Set<LocalDataCapability>): LocalDataGrant =
        LocalDataGrant(capabilities intersect allowed)

    companion object {
        fun all(): LocalDataGrant = LocalDataGrant(LocalDataCapability.entries.toSet())

        fun of(vararg capabilities: LocalDataCapability): LocalDataGrant =
            LocalDataGrant(capabilities.toSet())

        /** Manifest permissions only declare intent; the host still decides what it grants. */
        fun fromManifest(permissions: Collection<String>): LocalDataGrant {
            val declared = LocalDataCapability.entries.filter { it.wire in permissions }.toSet()
            return LocalDataGrant(declared)
        }
    }
}

@Serializable
enum class AuthorKind {
    @SerialName("user")
    USER,

    @SerialName("assistant")
    ASSISTANT,
}

@Serializable
enum class PostVisibility {
    @SerialName("all_assistants")
    ALL_ASSISTANTS,

    @SerialName("selected")
    SELECTED,

    @SerialName("self")
    SELF,
}

data class LocalDataAssistantRef(
    val id: String,
    val name: String,
    val avatar: String? = null,
)

fun interface LocalAssistantDirectory {
    fun find(id: String): LocalDataAssistantRef?

    companion object {
        fun of(assistants: Iterable<LocalDataAssistantRef>): LocalAssistantDirectory {
            val byId = assistants.associateBy { it.id }
            return LocalAssistantDirectory { byId[it] }
        }

        fun empty(): LocalAssistantDirectory = LocalAssistantDirectory { null }
    }
}

/** Actor is created by the host. It is deliberately not serializable from a page request. */
sealed interface LocalDataActor {
    data object User : LocalDataActor

    data class Assistant(
        val id: String,
        val name: String,
        val avatar: String? = null,
    ) : LocalDataActor
}

data class LocalDataCallContext(
    val actor: LocalDataActor,
    val grant: LocalDataGrant,
    val assistantDirectory: LocalAssistantDirectory,
    val assistantAutoPublish: Boolean = false,
    val userDisplayName: String = "我",
    val now: () -> Long = { System.currentTimeMillis() },
) {
    fun requireCapability(capability: LocalDataCapability) {
        if (!grant.allows(capability)) {
            throw LocalDataException(
                LocalDataErrorCode.CAPABILITY_DENIED,
                "The plugin was not granted ${capability.wire}",
            )
        }
    }

    /**
     * Reject an assistant actor that is not in the host-owned directory. A page cannot satisfy this by
     * naming its own assistant id because the actor is not read from request parameters.
     */
    fun requireTrustedAssistant(): LocalDataAssistantRef {
        val actor = actor as? LocalDataActor.Assistant
            ?: throw LocalDataException(LocalDataErrorCode.FORBIDDEN, "Assistant identity required")
        if (!LocalDataValidation.isSafeAssistantId(actor.id)) {
            throw LocalDataException(LocalDataErrorCode.FORBIDDEN, "Invalid assistant identity")
        }
        return assistantDirectory.find(actor.id)
            ?: throw LocalDataException(LocalDataErrorCode.FORBIDDEN, "Assistant is not authorized")
    }
}

@Serializable
data class LocalDataErrorDto(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
)

@Serializable
data class LocalDataEnvelope(
    val version: Int = LocalDataApi.VERSION,
    val ok: Boolean,
    val data: JsonElement? = null,
    val error: LocalDataErrorDto? = null,
)

data class LocalDataRequest(
    val version: Int,
    val method: String,
    val params: JsonObject,
)

object LocalDataRequestParser {
    /** Top-level fields a malicious page might try to use to smuggle a different actor. */
    private val reservedIdentityKeys = setOf(
        "actor", "actorId", "actor_id", "actorKind", "actor_kind",
        "viewer", "viewerId", "viewer_id", "trustedActor", "trusted_actor",
        "assistantId", "assistant_id", "assistantActorId", "assistant_actor_id",
        "actingAssistantId", "acting_assistant_id", "asAssistant", "as_assistant",
        "isAssistant", "is_assistant", "userId", "user_id", "roleOverride",
    )

    fun parse(raw: String): LocalDataRequest {
        if (raw.isBlank()) throw invalid("Request is empty")
        if (raw.length > LocalDataApi.MAX_REQUEST_BYTES) {
            throw LocalDataException(LocalDataErrorCode.PAYLOAD_TOO_LARGE, "Request is too large")
        }
        val root = runCatching { LocalDataJson.json.parseToJsonElement(raw) }
            .getOrElse { throw invalid("Request is not valid JSON") }
        val obj = root as? JsonObject ?: throw invalid("Request must be a JSON object")
        for (key in obj.keys) {
            if (key in reservedIdentityKeys) throw invalid("Identity fields are host-owned")
            if (key !in setOf("version", "method", "params")) throw invalid("Unknown request field")
        }
        val version = obj["version"]?.jsonPrimitive?.intOrNullSafe()
            ?: throw invalid("Missing version")
        if (version != LocalDataApi.VERSION) {
            throw LocalDataException(LocalDataErrorCode.UNSUPPORTED_VERSION, "Unsupported API version")
        }
        val method = obj["method"]?.jsonPrimitive?.contentOrNullSafe()
            ?: throw invalid("Missing method")
        if (!LocalDataValidation.METHOD_PATTERN.matches(method)) throw invalid("Invalid method")
        val paramsElement = obj["params"] ?: JsonObject(emptyMap())
        val params = paramsElement as? JsonObject ?: throw invalid("params must be an object")
        requireNoIdentityFields(params)
        return LocalDataRequest(version = version, method = method, params = params)
    }

    fun requireNoIdentityFields(params: JsonObject) {
        for (key in params.keys) {
            if (key in reservedIdentityKeys) throw invalid("Identity fields are host-owned")
        }
    }

    private fun invalid(message: String) = LocalDataException(LocalDataErrorCode.INVALID_REQUEST, message)
}

/** Small helpers kept here so every layer converts JSON primitives the same way. */
internal fun JsonPrimitive.intOrNullSafe(): Int? = contentOrNullSafe()?.toIntOrNull()
internal fun JsonPrimitive.longOrNullSafe(): Long? = contentOrNullSafe()?.toLongOrNull()
internal fun JsonPrimitive.boolOrNullSafe(): Boolean? = contentOrNullSafe()?.toBooleanStrictOrNull()
internal fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content

object LocalDataJson {
    val json: Json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
        allowSpecialFloatingPointValues = false
    }
}

object LocalDataValidation {
    val METHOD_PATTERN = Regex("^[a-z][a-z0-9_]{1,31}(\\.[a-z][a-z0-9_]{1,31})+$")
    val PLUGIN_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    val ASSISTANT_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,79}$")
    val RECORD_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,79}$")
    val MEDIA_ID_PATTERN = Regex("^ldm_[A-Za-z0-9]{8,64}$")

    fun requirePluginId(pluginId: String) {
        if (!PLUGIN_ID_PATTERN.matches(pluginId) || pluginId == "." || pluginId == "..") {
            throw LocalDataException(LocalDataErrorCode.INVALID_REQUEST, "Invalid plugin id")
        }
    }

    fun isSafeAssistantId(id: String): Boolean = ASSISTANT_ID_PATTERN.matches(id)
    fun isSafeRecordId(id: String): Boolean = RECORD_ID_PATTERN.matches(id)
    fun isSafeMediaId(id: String): Boolean = MEDIA_ID_PATTERN.matches(id)
}

object LocalDataCursorCodec {
    private const val SEPARATOR = ":"
    private val encodedPattern = Regex("^[A-Za-z0-9_-]{4,160}$")

    fun encode(timestamp: Long, id: String): String {
        require(timestamp >= 0)
        require(id.indexOf(SEPARATOR) < 0)
        val bytes = "$timestamp$SEPARATOR$id".toByteArray(Charsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun decode(raw: String?): Pair<Long, String>? {
        if (raw.isNullOrBlank() || !encodedPattern.matches(raw)) return null
        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val index = decoded.indexOf(SEPARATOR)
        if (index <= 0 || index == decoded.lastIndex) return null
        val timestamp = decoded.substring(0, index).toLongOrNull() ?: return null
        val id = decoded.substring(index + 1)
        if (timestamp < 0 || !LocalDataValidation.isSafeRecordId(id)) return null
        return timestamp to id
    }
}

/**
 * Comment window order. [OLDEST] is the historical/default order the moments UI pages forward
 * through; [NEWEST] reads the same stable `(createdAt, id)` ordering backwards so callers can take
 * the latest comments without scanning every earlier page first.
 */
enum class LocalCommentOrder(val wire: String) {
    OLDEST("oldest"),
    NEWEST("newest");

    companion object {
        fun fromWire(value: String?): LocalCommentOrder = when (value) {
            null, "oldest" -> OLDEST
            "newest" -> NEWEST
            else -> throw LocalDataException(
                LocalDataErrorCode.INVALID_REQUEST,
                "Unsupported comment order",
            )
        }
    }
}

@Serializable
data class LocalAuthorDto(
    val kind: AuthorKind,
    val id: String,
    val name: String,
    val avatar: String? = null,
)

@Serializable
data class LocalMediaDto(
    val resourceId: String,
    val mime: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val thumbUrl: String,
    val originalUrl: String,
    val available: Boolean = true,
)

@Serializable
data class LocalChatMessageDto(
    val role: String,
    val name: String = "",
    val text: String = "",
    val media: List<String> = emptyList(),
    /**
     * Optional structured participant avatar: a host-imported localdata media id. A URL, data URI
     * or filesystem path is never a valid value; the host imports the avatar bytes through the
     * bounded media store and persists only this opaque reference. It is not part of the nine
     * body-image slots and counts toward its own [LocalDataApi.MAX_CHAT_AVATARS] budget.
     */
    val avatarMediaId: String? = null,
    /** Optional text/emoji avatar. Mutually exclusive with [avatarMediaId]. */
    val avatarEmoji: String? = null,
)

@Serializable
data class LocalChatSnapshotDto(
    val title: String? = null,
    val messages: List<LocalChatMessageDto> = emptyList(),
    val capturedAt: Long? = null,
)

@Serializable
data class LocalCommentDto(
    val id: String,
    val postId: String,
    val author: LocalAuthorDto,
    val body: String,
    val createdAt: Long,
    val replyToId: String? = null,
)

@Serializable
data class LocalPostDto(
    val id: String,
    val author: LocalAuthorDto,
    val body: String,
    val bodyTruncated: Boolean = false,
    val visibility: PostVisibility,
    val selectedAssistantIds: List<String> = emptyList(),
    val media: List<LocalMediaDto> = emptyList(),
    val mediaOmitted: Boolean = false,
    val chatSnapshot: LocalChatSnapshotDto? = null,
    val chatSnapshotOmitted: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val commentCount: Int = 0,
    val likeCount: Int = 0,
    val likedByMe: Boolean = false,
    val previewComments: List<LocalCommentDto> = emptyList(),
)

@Serializable
data class LocalPageBudgetDto(
    val pageSize: Int,
    val bodyCharsPerItem: Int,
    val bodyCharsUsed: Int,
    val truncated: Boolean,
)

@Serializable
data class LocalPostPageDto(
    val items: List<LocalPostDto> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val truncated: Boolean = false,
    val budget: LocalPageBudgetDto,
)

@Serializable
data class LocalCommentPageDto(
    val items: List<LocalCommentDto> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val commentCount: Int = 0,
)

@Serializable
data class LocalPostDetailDto(
    val post: LocalPostDto,
    val comments: LocalCommentPageDto,
)

@Serializable
data class LocalDraftDto(
    val id: String,
    val author: LocalAuthorDto,
    val body: String,
    val visibility: PostVisibility,
    val selectedAssistantIds: List<String> = emptyList(),
    val media: List<LocalMediaDto> = emptyList(),
    val mediaOmitted: Boolean = false,
    val chatSnapshot: LocalChatSnapshotDto? = null,
    val chatSnapshotOmitted: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class LocalDraftPageDto(
    val items: List<LocalDraftDto> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

@Serializable
data class LocalLikeResultDto(
    val postId: String,
    val liked: Boolean,
    val likeCount: Int,
)

@Serializable
data class LocalCommentCreateResultDto(
    val comment: LocalCommentDto,
    val commentCount: Int,
)

@Serializable
data class LocalPostCreateResultDto(
    val post: LocalPostDto,
)

@Serializable
data class LocalDeleteResultDto(
    val id: String,
    val deleted: Boolean = true,
)

@Serializable
data class LocalInfoDto(
    val apiVersion: Int = LocalDataApi.VERSION,
    val schemaVersion: Int = LocalDataApi.DB_SCHEMA_VERSION,
    val pluginId: String,
    val mediaOrigin: String = LocalDataApi.MEDIA_ORIGIN,
)

@Serializable
data class LocalAuthorSnapshotDto(
    val kind: AuthorKind,
    val id: String,
    val name: String,
    val avatar: String? = null,
    val capturedAt: Long,
)

object LocalDataChatSnapshots {
    fun sanitize(input: LocalChatSnapshotDto?): LocalChatSnapshotDto? {
        if (input == null) return null
        val title = input.title?.trim()?.takeIf { it.isNotEmpty() }
        require(title == null || title.length <= 200) { "Chat snapshot title is too long" }
        require(input.messages.size <= LocalDataApi.MAX_CHAT_MESSAGES) { "Too many chat messages" }
        var totalChars = 0
        val messages = input.messages.map { message ->
            val role = message.role.lowercase()
            require(role == "user" || role == "assistant") { "Only user/assistant messages are allowed" }
            val name = message.name.take(100)
            val text = message.text
            require(text.length <= LocalDataApi.MAX_CHAT_MESSAGE_CHARS) { "Chat message is too long" }
            totalChars += text.length
            require(totalChars <= LocalDataApi.MAX_CHAT_TOTAL_CHARS) { "Chat snapshot is too long" }
            require(message.media.size <= LocalDataApi.MAX_MEDIA_PER_POST) { "Too many chat images" }
            message.media.forEach { require(LocalDataValidation.isSafeMediaId(it)) { "Invalid media id" } }
            val avatarMediaId = message.avatarMediaId?.takeIf { it.isNotBlank() }
            require(avatarMediaId == null || LocalDataValidation.isSafeMediaId(avatarMediaId)) {
                "Invalid avatar media id"
            }
            val avatarEmoji = message.avatarEmoji?.let { raw ->
                val trimmed = raw.trim()
                require(trimmed.length <= LocalDataApi.MAX_CHAT_AVATAR_EMOJI_CHARS) {
                    "Avatar emoji is too long"
                }
                require(trimmed.none { it.isISOControl() }) { "Invalid avatar emoji" }
                trimmed.takeIf { it.isNotEmpty() }
            }
            require(avatarEmoji == null || avatarMediaId == null) {
                "Chat message avatar is ambiguous"
            }
            LocalChatMessageDto(
                role = role,
                name = name,
                text = text,
                media = message.media.distinct(),
                avatarMediaId = avatarMediaId,
                avatarEmoji = avatarEmoji,
            )
        }
        require(messages.mapNotNull { it.avatarMediaId }.distinct().size <= LocalDataApi.MAX_CHAT_AVATARS) {
            "Too many chat avatars"
        }
        return LocalChatSnapshotDto(
            title = title,
            messages = messages,
            capturedAt = input.capturedAt,
        )
    }

    /** Every persistent media id a snapshot keeps alive, including participant avatars. */
    fun mediaReferences(snapshot: LocalChatSnapshotDto?): List<String> =
        snapshot?.messages.orEmpty().flatMap { message ->
            val avatar = message.avatarMediaId?.takeIf { LocalDataValidation.isSafeMediaId(it) }
            avatar?.let { message.media + it } ?: message.media
        }

    fun encode(input: LocalChatSnapshotDto?): String? {
        val sanitized = sanitize(input) ?: return null
        val encoded = LocalDataJson.json.encodeToString(LocalChatSnapshotDto.serializer(), sanitized)
        require(encoded.toByteArray(Charsets.UTF_8).size <= LocalDataApi.MAX_CHAT_SNAPSHOT_BYTES) {
            "Chat snapshot is too large"
        }
        return encoded
    }

    fun decode(raw: String?): LocalChatSnapshotDto? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            LocalDataJson.json.decodeFromString(LocalChatSnapshotDto.serializer(), raw)
        }.getOrNull()
    }
}

/** Parsed media URL; never carries a filesystem path. */
data class LocalMediaUrl(
    val resourceId: String,
    val variant: LocalMediaVariant,
)

enum class LocalMediaVariant(val path: String) {
    ORIGINAL("original"),
    THUMB("thumb"),
}

object LocalDataMediaUrl {
    fun original(resourceId: String): String = build(resourceId, LocalMediaVariant.ORIGINAL)
    fun thumb(resourceId: String): String = build(resourceId, LocalMediaVariant.THUMB)

    private fun build(resourceId: String, variant: LocalMediaVariant): String {
        require(LocalDataValidation.isSafeMediaId(resourceId))
        return "${LocalDataApi.MEDIA_URL_PREFIX}$resourceId/${variant.path}"
    }

    fun parse(url: String?): LocalMediaUrl? {
        if (url.isNullOrBlank()) return null
        val prefix = LocalDataApi.MEDIA_URL_PREFIX
        if (!url.startsWith(prefix)) return null
        val rest = url.substring(prefix.length)
        if (rest.contains('?') || rest.contains('#') || rest.contains('\\')) return null
        val parts = rest.split('/')
        if (parts.size != 2) return null
        val resourceId = parts[0]
        if (!LocalDataValidation.isSafeMediaId(resourceId)) return null
        val variant = LocalMediaVariant.entries.find { it.path == parts[1] } ?: return null
        return LocalMediaUrl(resourceId, variant)
    }
}
