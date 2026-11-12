/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localtasks

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import me.rerere.rikkahub.plugin.localdata.LocalDataApi

data class LocalTaskBridgeRequest(
    val method: String,
    val params: JsonObject,
)

/**
 * Parses the `localDataTask` bridge request. Only a closed set of methods and parameter names is
 * accepted, so a page can never smuggle in settings, media ids, permissions or a publishing policy.
 */
object LocalTaskBridgeRequestParser {
    const val METHOD_START = "task.start"
    const val METHOD_STATUS = "task.status"
    const val METHOD_CANCEL = "task.cancel"
    const val METHOD_SETTINGS = "task.settings"
    const val METHOD_REVIEW_LIST = "task.review.list"
    const val METHOD_REVIEW_RESOLVE = "task.review.resolve"

    private val allowedParams: Map<String, Set<String>> = mapOf(
        METHOD_START to setOf("postId", "assistantId", "clientKey"),
        METHOD_STATUS to setOf("taskId", "postId"),
        METHOD_CANCEL to setOf("taskId", "all"),
        METHOD_SETTINGS to emptySet(),
        METHOD_REVIEW_LIST to emptySet(),
        METHOD_REVIEW_RESOLVE to setOf("reviewId", "approve", "body"),
    )

    fun parse(raw: String): LocalTaskBridgeRequest {
        if (raw.isBlank()) throw invalid("Request is empty")
        if (raw.length > LocalDataApi.MAX_REQUEST_BYTES) throw invalid("Request is too large")
        val root = runCatching { LocalTaskJson.wire.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: throw invalid("Request must be a JSON object")
        if (root.keys.any { it !in setOf("version", "method", "params") }) {
            throw invalid("Unknown root field")
        }
        val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: throw invalid("Missing version")
        if (version != LocalTasksApi.VERSION) {
            throw LocalTaskException(LocalTaskErrorCode.UNSUPPORTED_VERSION, "Unsupported task API version")
        }
        val method = (root["method"] as? JsonPrimitive)?.contentOrNull ?: throw invalid("Missing method")
        val allowed = allowedParams[method]
            ?: throw LocalTaskException(LocalTaskErrorCode.UNKNOWN_METHOD, "Unknown task method")
        val paramsElement = root["params"]
        if (paramsElement != null && paramsElement !is JsonObject) throw invalid("params must be a JSON object")
        val params = paramsElement as? JsonObject ?: JsonObject(emptyMap())
        if (params.keys.any { it !in allowed }) throw invalid("Unsupported parameter for this method")
        if (method == METHOD_REVIEW_RESOLVE) validateReviewResolve(params)
        return LocalTaskBridgeRequest(method = method, params = params)
    }

    /**
     * The WebView shows a native confirmation dialog *before* dispatch, using the raw body. Reject
     * invalid review edits here so the dialog can never display a blank/oversized body while a
     * different (or truncated) body would be applied.
     */
    private fun validateReviewResolve(params: JsonObject) {
        val approve = params["approve"]
        if (approve !is JsonPrimitive || approve.booleanOrNull == null) {
            throw invalid("approve must be a boolean")
        }
        val reviewIdValue = params["reviewId"]
        if (reviewIdValue !is JsonPrimitive || !reviewIdValue.isString) throw invalid("reviewId is required")
        val reviewId = reviewIdValue.contentOrNull
        if (reviewId.isNullOrBlank() || reviewId.length > 128) throw invalid("reviewId is required")
        val body = params["body"] ?: return
        if (body !is JsonPrimitive || !body.isString) throw invalid("body must be a string")
        val text = body.contentOrNull ?: throw invalid("body must be a string")
        if (text.isBlank()) throw invalid("body must not be blank")
        if (text.length > LocalTasksApi.MAX_MAX_OUTPUT_CHARS) throw invalid("body is too long")
    }

    private fun invalid(message: String) = LocalTaskException(LocalTaskErrorCode.INVALID_REQUEST, message)
}

/**
 * JSON bridge that the WebView/plugin-bridge can expose as `localDataTask(requestJson)`.
 * `task.settings` is read-only: there is no method that can widen publishing, image or budget rights.
 *
 * This bridge does **not** enforce human approval by itself: `task.review.resolve` applies a pending
 * action when called. The WebView layer (`PluginWebViewPage`) is responsible for showing its native
 * confirmation dialog before dispatch; any other native caller must add the same gate.
 */
class LocalTaskBridge(
    private val service: LocalTaskService,
) {
    suspend fun dispatch(requestJson: String): String {
        val envelope = try {
            handle(LocalTaskBridgeRequestParser.parse(requestJson))
        } catch (error: CancellationException) {
            throw error
        } catch (error: LocalTaskException) {
            failure(error.code, error.message)
        } catch (_: Exception) {
            failure(LocalTaskErrorCode.INTERNAL, "Internal error")
        }
        return LocalTaskJson.wire.encodeToString(LocalTaskEnvelope.serializer(), envelope)
    }

    private suspend fun handle(request: LocalTaskBridgeRequest): LocalTaskEnvelope {
        val params = request.params
        return when (request.method) {
            LocalTaskBridgeRequestParser.METHOD_START -> {
                val postId = params.string("postId") ?: throw invalid("postId is required")
                val assistantId = params.string("assistantId")?.takeIf { it.isNotBlank() }
                val clientKey = params.string("clientKey")?.takeIf { it.isNotBlank() }
                when (val result = service.startInteraction(postId, assistantId, clientKey)) {
                    is LocalTaskSubmitResult.Accepted -> ok(
                        LocalTaskStartResultDto(
                            taskId = result.taskId,
                            existing = result.existing,
                            state = service.status(result.taskId)?.state ?: LocalTaskState.QUEUED.wire,
                        ),
                    )

                    is LocalTaskSubmitResult.Rejected -> failure(result.code, result.message)
                }
            }

            LocalTaskBridgeRequestParser.METHOD_STATUS -> {
                val taskId = params.string("taskId")?.takeIf { it.isNotBlank() }
                val postId = params.string("postId")?.takeIf { it.isNotBlank() }
                when {
                    taskId != null -> {
                        val status = service.status(taskId)
                            ?: return failure(LocalTaskErrorCode.NOT_FOUND, "Task does not exist")
                        ok(status)
                    }

                    postId != null -> ok(service.statusesForPost(postId))
                    else -> ok(service.activeStatuses())
                }
            }

            LocalTaskBridgeRequestParser.METHOD_CANCEL -> {
                val all = (params["all"] as? JsonPrimitive)?.booleanOrNull ?: false
                if (all) {
                    ok(LocalTaskCancelResultDto(service.stopAll()))
                } else {
                    val taskId = params.string("taskId") ?: throw invalid("taskId is required")
                    if (service.cancel(taskId)) {
                        ok(LocalTaskCancelResultDto(1))
                    } else {
                        failure(LocalTaskErrorCode.CONFLICT, "Task is already finished or does not exist")
                    }
                }
            }

            LocalTaskBridgeRequestParser.METHOD_SETTINGS -> ok(service.settings())

            LocalTaskBridgeRequestParser.METHOD_REVIEW_LIST ->
                ok(LocalTaskReviewListDto(service.pendingReviews()))

            LocalTaskBridgeRequestParser.METHOD_REVIEW_RESOLVE -> {
                val reviewId = params.string("reviewId") ?: throw invalid("reviewId is required")
                val approve = (params["approve"] as? JsonPrimitive)?.booleanOrNull
                    ?: throw invalid("approve is required")
                val body = params.string("body")
                ok(service.resolveReview(reviewId, approve, body))
            }

            else -> throw LocalTaskException(LocalTaskErrorCode.UNKNOWN_METHOD, "Unknown task method")
        }
    }

    private inline fun <reified T> ok(value: T): LocalTaskEnvelope =
        LocalTaskEnvelope(ok = true, data = LocalTaskJson.wire.encodeToJsonElement(value))

    private fun failure(code: LocalTaskErrorCode, message: String): LocalTaskEnvelope =
        LocalTaskEnvelope(ok = false, error = LocalTaskErrorDto(code.wire, message.take(200)))

    private fun invalid(message: String) =
        LocalTaskException(LocalTaskErrorCode.INVALID_REQUEST, message)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
