/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localtasks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import me.rerere.rikkahub.plugin.localdata.LocalDataApi
import me.rerere.rikkahub.plugin.localdata.LocalDataValidation

/**
 * Stable wire/budget contract for host-owned assistant tasks. See LOCAL_TASKS_API.md.
 *
 * The host, not the plugin page, decides which assistants participate, which model is used and how
 * much may be spent. A page can only ask the host to start/cancel/read a task for a post it could
 * already read through `localData`.
 */
object LocalTasksApi {
    const val VERSION = 1
    const val SETTINGS_VERSION = 1
    const val LEDGER_VERSION = 1

    const val PREFS_PREFIX = "localtasks_v1_"
    const val KEY_SETTINGS = "settings"
    const val KEY_LEDGER = "ledger"

    const val TASK_ID_PREFIX = "lt_"
    const val REVIEW_ID_PREFIX = "ltr_"

    const val DEFAULT_MAX_ROUNDS = 2
    const val MAX_ROUNDS = 10
    const val DEFAULT_MAX_REPLIES_PER_POST = 1
    const val MAX_REPLIES_PER_POST = 5
    const val DEFAULT_MAX_MODEL_CALLS_PER_POST = 8
    const val MAX_MODEL_CALLS_PER_POST = 50
    const val DEFAULT_DAILY_MODEL_CALLS = 200
    const val MAX_DAILY_MODEL_CALLS = 10_000
    const val DEFAULT_MAX_OUTPUT_CHARS = 280
    const val MIN_MAX_OUTPUT_CHARS = 40
    const val MAX_MAX_OUTPUT_CHARS = LocalDataApi.MAX_COMMENT_CHARS
    const val DEFAULT_INTERVAL_MS = 45_000L
    const val MAX_INTERVAL_MS = 3_600_000L
    const val DEFAULT_COMMENT_SCAN_PAGES = 2
    const val MAX_COMMENT_SCAN_PAGES = 4
    const val COMMENT_PAGE_SIZE = LocalDataApi.MAX_PAGE_SIZE
    const val DEFAULT_CONTEXT_COMMENT_LIMIT = 3
    const val MAX_CONTEXT_COMMENT_LIMIT = 10
    const val DEFAULT_CONTEXT_BODY_CHARS = LocalDataApi.DEFAULT_ASSISTANT_BODY_CHARS
    const val DEFAULT_FEED_LIMIT = LocalDataApi.DEFAULT_PAGE_SIZE
    const val MAX_FEED_LIMIT = 5
    const val MAX_PARTICIPANTS = LocalDataApi.MAX_SELECTED_ASSISTANTS
    const val MAX_PENDING_REVIEWS = 50
    const val MAX_TASK_RECORDS = 100
    const val MAX_POST_USAGE_RECORDS = 200
    const val MAX_OUTPUT_SCAN_CHARS = 16_000
    const val MAX_PROMPT_CHARS = 16_000
    const val MAX_COMMENT_SUMMARY_CHARS = 300
    const val MAX_IMAGE_PROMPT_CHARS = 800
    const val MAX_SKIP_REASON_CHARS = 200
    const val MAX_CLIENT_KEY_CHARS = LocalDataApi.MAX_CLIENT_KEY_CHARS
    const val DEFAULT_MAX_TOKENS = 600
    const val DAY_MILLIS = 86_400_000L

    /**
     * Ledger usage key for publish turns. Publishing has no post id, so the `maxModelCallsPerPost`
     * reservation is scoped to the calendar day and shared by every publish task and every
     * assistant. Using the task id (or assistant id) would give each new task/assistant a fresh
     * reservation and bypass the cap; a plain constant would never roll over.
     */
    fun publishUsageKey(now: Long): String = "publish:${now / DAY_MILLIS}"
}

@Serializable
enum class LocalTaskMode(val wire: String) {
    @SerialName("interact")
    INTERACT("interact"),

    @SerialName("publish")
    PUBLISH("publish"),
}

@Serializable
enum class LocalTaskState(val wire: String) {
    @SerialName("queued")
    QUEUED("queued"),

    @SerialName("running")
    RUNNING("running"),

    @SerialName("waiting_review")
    WAITING_REVIEW("waiting_review"),

    @SerialName("completed")
    COMPLETED("completed"),

    @SerialName("cancelled")
    CANCELLED("cancelled"),

    @SerialName("failed")
    FAILED("failed"),

    @SerialName("interrupted")
    INTERRUPTED("interrupted");

    val isTerminal: Boolean
        get() = this == COMPLETED || this == CANCELLED || this == FAILED || this == INTERRUPTED

    companion object {
        fun fromWire(value: String?): LocalTaskState =
            entries.firstOrNull { it.wire == value } ?: QUEUED
    }
}

/**
 * Publishing policy of one assistant. `DRAFT_ONLY` assistants may still interact (comment/like);
 * the policy only controls whether new posts are drafted or published.
 */
@Serializable
enum class LocalAssistantPolicy(val wire: String) {
    @SerialName("disabled")
    DISABLED("disabled"),

    @SerialName("draft_only")
    DRAFT_ONLY("draft_only"),

    @SerialName("auto_publish")
    AUTO_PUBLISH("auto_publish");

    companion object {
        fun fromWire(value: String?): LocalAssistantPolicy? =
            entries.firstOrNull { it.wire == value }
    }
}

/**
 * Host-classified origin of review media. A pending review only ever carries a host-written value;
 * the model cannot add media paths/URIs to the approval structure.
 */
@Serializable
enum class LocalTaskMediaSource(val wire: String) {
    @SerialName("none")
    NONE("none"),

    @SerialName("native_selection")
    NATIVE_SELECTION("native_selection"),

    @SerialName("generated")
    GENERATED("generated"),
}

enum class LocalTaskErrorCode(val wire: String) {
    INVALID_REQUEST("invalid_request"),
    UNSUPPORTED_VERSION("unsupported_version"),
    UNKNOWN_METHOD("unknown_method"),
    DISABLED("disabled"),
    STOPPED("stopped"),
    INTERACTION_DISABLED("interaction_disabled"),
    NO_PARTICIPANTS("no_participants"),
    ASSISTANT_NOT_PARTICIPANT("assistant_not_participant"),
    ASSISTANT_MISSING("assistant_missing"),
    MODEL_UNAVAILABLE("model_unavailable"),
    BUDGET_EXHAUSTED("budget_exhausted"),
    CAPABILITY_DENIED("capability_denied"),
    PUBLISH_DENIED("publish_denied"),
    IMAGE_UNSUPPORTED("image_unsupported"),
    IMAGE_GENERATION_FAILED("image_generation_failed"),
    POST_UNAVAILABLE("post_unavailable"),
    INVALID_ACTION("invalid_action"),
    NOT_FOUND("not_found"),
    CONFLICT("conflict"),
    UNAVAILABLE("unavailable"),
    INTERNAL("internal"),
}

class LocalTaskException(
    val code: LocalTaskErrorCode,
    override val message: String,
) : Exception(message)

/** One assistant that the native settings currently allow to participate. */
data class LocalTaskParticipant(
    val id: String,
    val name: String,
    val avatar: String? = null,
    val policy: LocalAssistantPolicy = LocalAssistantPolicy.DRAFT_ONLY,
)

@Serializable
data class LocalTaskSettings(
    val version: Int = LocalTasksApi.SETTINGS_VERSION,
    val enabled: Boolean = false,
    val globalStop: Boolean = false,
    val commentUserPosts: Boolean = true,
    val commentAssistantPosts: Boolean = true,
    val autoLike: Boolean = true,
    val maxRounds: Int = LocalTasksApi.DEFAULT_MAX_ROUNDS,
    val maxRepliesPerAssistantPerPost: Int = LocalTasksApi.DEFAULT_MAX_REPLIES_PER_POST,
    val maxModelCallsPerPost: Int = LocalTasksApi.DEFAULT_MAX_MODEL_CALLS_PER_POST,
    val dailyModelCallBudget: Int = LocalTasksApi.DEFAULT_DAILY_MODEL_CALLS,
    val maxOutputChars: Int = LocalTasksApi.DEFAULT_MAX_OUTPUT_CHARS,
    val intervalMs: Long = LocalTasksApi.DEFAULT_INTERVAL_MS,
    val requireReview: Boolean = false,
    val allowImages: Boolean = false,
    /**
     * Second, explicit switch for assistant-initiated generation. It is off by default and requires
     * [allowImages]; without a native UI toggle no assistant can spend an image model by accident.
     */
    val allowGeneratedImages: Boolean = false,
    val participants: List<String> = emptyList(),
    val assistantPolicies: Map<String, LocalAssistantPolicy> = emptyMap(),
    val assistantReplyLimits: Map<String, Int> = emptyMap(),
    val contextCommentLimit: Int = LocalTasksApi.DEFAULT_CONTEXT_COMMENT_LIMIT,
    val contextBodyChars: Int = LocalTasksApi.DEFAULT_CONTEXT_BODY_CHARS,
    val feedLimit: Int = LocalTasksApi.DEFAULT_FEED_LIMIT,
    val commentScanPages: Int = LocalTasksApi.DEFAULT_COMMENT_SCAN_PAGES,
) {
    /** Keeps stored/old values inside safe bounds. Never loosens a grant, only narrows numbers. */
    fun normalized(): LocalTaskSettings {
        val ids = participants
            .filter { LocalDataValidation.isSafeAssistantId(it) }
            .distinct()
            .take(LocalTasksApi.MAX_PARTICIPANTS)
        val allowed = ids.toSet()
        return copy(
            version = LocalTasksApi.SETTINGS_VERSION,
            maxRounds = maxRounds.coerceIn(0, LocalTasksApi.MAX_ROUNDS),
            maxRepliesPerAssistantPerPost = maxRepliesPerAssistantPerPost.coerceIn(
                0,
                LocalTasksApi.MAX_REPLIES_PER_POST,
            ),
            maxModelCallsPerPost = maxModelCallsPerPost.coerceIn(1, LocalTasksApi.MAX_MODEL_CALLS_PER_POST),
            dailyModelCallBudget = dailyModelCallBudget.coerceIn(0, LocalTasksApi.MAX_DAILY_MODEL_CALLS),
            maxOutputChars = maxOutputChars.coerceIn(
                LocalTasksApi.MIN_MAX_OUTPUT_CHARS,
                LocalTasksApi.MAX_MAX_OUTPUT_CHARS,
            ),
            intervalMs = intervalMs.coerceIn(0L, LocalTasksApi.MAX_INTERVAL_MS),
            // Generation is never a superset of the base image grant.
            allowGeneratedImages = allowGeneratedImages && allowImages,
            participants = ids,
            assistantPolicies = assistantPolicies.filterKeys { it in allowed },
            assistantReplyLimits = assistantReplyLimits
                .filterKeys { it in allowed }
                .mapValues { (_, value) -> value.coerceIn(0, LocalTasksApi.MAX_REPLIES_PER_POST) },
            contextCommentLimit = contextCommentLimit.coerceIn(0, LocalTasksApi.MAX_CONTEXT_COMMENT_LIMIT),
            contextBodyChars = contextBodyChars.coerceIn(0, LocalDataApi.MAX_BODY_BUDGET_CHARS),
            feedLimit = feedLimit.coerceIn(1, LocalTasksApi.MAX_FEED_LIMIT),
            commentScanPages = commentScanPages.coerceIn(1, LocalTasksApi.MAX_COMMENT_SCAN_PAGES),
        )
    }

    /** Policy for an already [normalized] settings object. */
    fun effectivePolicy(assistantId: String): LocalAssistantPolicy {
        if (assistantId !in participants) return LocalAssistantPolicy.DISABLED
        return assistantPolicies[assistantId] ?: LocalAssistantPolicy.DRAFT_ONLY
    }

    fun replyLimitFor(assistantId: String): Int =
        assistantReplyLimits[assistantId] ?: maxRepliesPerAssistantPerPost
}

/**
 * Host-created task request. `mediaIds` is only populated by native code (publish flow); the JS
 * bridge never accepts media or permission parameters.
 */
data class LocalTaskStartRequest(
    val mode: LocalTaskMode = LocalTaskMode.INTERACT,
    val postId: String? = null,
    val assistantId: String? = null,
    val clientKey: String? = null,
    val mediaIds: List<String> = emptyList(),
)

sealed interface LocalTaskSubmitResult {
    data class Accepted(val taskId: String, val existing: Boolean) : LocalTaskSubmitResult

    data class Rejected(val code: LocalTaskErrorCode, val message: String) : LocalTaskSubmitResult
}

object LocalTaskActionKinds {
    const val REPLY = "reply"
    const val LIKE = "like"
    const val DRAFT = "draft"
    const val PUBLISH = "publish"
}

@Serializable
data class LocalTaskErrorDto(
    val code: String,
    val message: String,
)

/** An action produced under `requireReview`; nothing is written until the host approves it. */
@Serializable
data class LocalTaskPendingActionDto(
    val id: String,
    val kind: String,
    val assistantId: String,
    val assistantName: String,
    val postId: String? = null,
    val body: String = "",
    val replyToId: String? = null,
    val like: Boolean = false,
    val mediaIds: List<String> = emptyList(),
    val mediaSource: LocalTaskMediaSource = LocalTaskMediaSource.NONE,
    val createdAt: Long = 0,
)

@Serializable
data class LocalTaskStatusDto(
    val taskId: String,
    val mode: String,
    val state: String,
    val postId: String? = null,
    val assistantId: String? = null,
    val round: Int = 0,
    val turns: Int = 0,
    val comments: Int = 0,
    val likes: Int = 0,
    val modelCalls: Int = 0,
    val commentsTruncated: Boolean = false,
    val endReason: String? = null,
    val error: LocalTaskErrorDto? = null,
    val pendingReview: List<LocalTaskPendingActionDto> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val finishedAt: Long? = null,
)

@Serializable
data class LocalTaskEnvelope(
    val version: Int = LocalTasksApi.VERSION,
    val ok: Boolean,
    val data: JsonElement? = null,
    val error: LocalTaskErrorDto? = null,
)

@Serializable
data class LocalTaskStartResultDto(
    val taskId: String,
    val existing: Boolean,
    val state: String,
)

@Serializable
data class LocalTaskCancelResultDto(
    val cancelled: Int,
)

@Serializable
data class LocalTaskReviewListDto(
    val items: List<LocalTaskPendingActionDto> = emptyList(),
)

@Serializable
data class LocalTaskReviewResolveDto(
    val reviewId: String,
    val applied: Boolean,
    val approved: Boolean,
    val state: String,
    val detail: String? = null,
)

/** Serializer configuration shared by the task store, the ledger and the bridge. */
object LocalTaskJson {
    val task: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        allowSpecialFloatingPointValues = false
    }

    val wire: Json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
        allowSpecialFloatingPointValues = false
    }
}
