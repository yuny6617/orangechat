/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localtasks

import android.util.Base64
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.UsageRecorder
import me.rerere.rikkahub.data.ai.UsageSource
import me.rerere.rikkahub.data.ai.UsageTag
import me.rerere.rikkahub.data.ai.buildAssistantGenerationParams
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.plugin.localdata.LocalAssistantDirectory
import me.rerere.rikkahub.plugin.localdata.LocalDataActor
import me.rerere.rikkahub.plugin.localdata.LocalDataApi
import me.rerere.rikkahub.plugin.localdata.LocalDataAssistantRef
import me.rerere.rikkahub.plugin.localdata.LocalDataCapability
import me.rerere.rikkahub.plugin.localdata.LocalDataMethods
import me.rerere.rikkahub.plugin.localdata.LocalDataSession
import me.rerere.rikkahub.plugin.localdata.LocalDataValidation
import me.rerere.rikkahub.plugin.localdata.LocalDraftDto
import me.rerere.rikkahub.plugin.localdata.LocalPostCreateResultDto

/**
 * Real host executor: resolves the assistant's actual configured model, uses
 * [ProviderManager] + [UsageRecorder] (source PLUGIN) for one bounded non-streaming turn, and writes
 * only through [LocalDataSession] with a host-created assistant actor.
 *
 * There is no tool loop, no memory, no chat history and no retry: one turn has at most one text
 * model call. A publish turn may additionally request **one** image from the user's configured
 * image model, but only through [LocalTaskImageBudget] and only when the native settings and the
 * localdata media grant allow it.
 */
class LocalTaskExecutor(
    private val settingsProvider: suspend () -> Settings,
    private val session: LocalDataSession,
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
    private val clock: () -> Long = System::currentTimeMillis,
    private val imageBudget: LocalTaskImageBudget = LocalTaskImageBudget.unavailable(),
) : LocalTaskTurnRunner {

    override suspend fun runTurn(request: LocalTaskTurnRequest): LocalTaskTurnOutcome =
        withContext(Dispatchers.IO) { runTurnInternal(request) }

    /**
     * Applies a previously held review action. Only called after the native user approves it.
     *
     * [token] is leased by the scheduler under its cancel lock, and every localdata write goes
     * through [LocalTaskCancellationToken.commitIfActive]. A stop/cancel/release that happens before
     * the commit therefore refuses it; a commit that wins the race is retained. Settings and the
     * assistant are re-read and re-validated here because the action may have been held for a long
     * time: a removed/disabled participant, a stopped service or a revoked publish policy must not
     * still be applied.
     */
    suspend fun applyPending(
        action: LocalTaskPendingActionDto,
        taskSettings: LocalTaskSettings,
        token: LocalTaskCancellationToken,
    ): LocalTaskTurnOutcome = withContext(Dispatchers.IO) {
        val writeSettings = taskSettings.normalized()
        if (writeSettings.globalStop || !writeSettings.enabled) {
            return@withContext failed(LocalTaskErrorCode.STOPPED, "Assistant interaction is stopped")
        }
        val assistantUuid = runCatching { Uuid.parse(action.assistantId) }.getOrNull()
            ?: return@withContext failed(LocalTaskErrorCode.ASSISTANT_MISSING, "Assistant id is invalid")
        val settingsSnapshot = settingsProvider()
        val assistant = settingsSnapshot.getAssistantById(assistantUuid)
            ?: return@withContext failed(LocalTaskErrorCode.ASSISTANT_MISSING, "Assistant no longer exists")
        // The participant list/policy is the native grant: re-check it now, not when the action was
        // generated, so removing an assistant from the task settings also revokes held actions.
        val policy = writeSettings.effectivePolicy(action.assistantId)
        if (policy == LocalAssistantPolicy.DISABLED) {
            return@withContext failed(
                LocalTaskErrorCode.ASSISTANT_NOT_PARTICIPANT,
                "Assistant is no longer an enabled participant",
            )
        }
        // A review can outlive the grant that produced it. Generated media never becomes valid just
        // because its resource id is still in the pending action.
        if (action.mediaIds.isNotEmpty() && !writeSettings.allowImages) {
            return@withContext failed(
                LocalTaskErrorCode.IMAGE_UNSUPPORTED,
                "Images are no longer allowed for assistant tasks",
            )
        }
        if (action.mediaSource == LocalTaskMediaSource.GENERATED && !writeSettings.allowGeneratedImages) {
            return@withContext failed(
                LocalTaskErrorCode.IMAGE_UNSUPPORTED,
                "Assistant image generation is no longer allowed",
            )
        }
        val participant = LocalTaskParticipant(
            id = action.assistantId,
            name = action.assistantName,
            avatar = avatarOf(assistant.avatar),
            policy = policy,
        )
        val actor = actorOf(assistant, participant)
        val data = LocalTaskLocalData(session, directoryOf(settingsSnapshot))
        when (action.kind) {
            LocalTaskActionKinds.REPLY -> {
                val postId = action.postId
                    ?: return@withContext failed(LocalTaskErrorCode.INVALID_REQUEST, "postId missing")
                val body = action.body.trim()
                if (body.isEmpty()) {
                    return@withContext failed(LocalTaskErrorCode.INVALID_ACTION, "Comment body is empty")
                }
                val outcome = token.commitOutcome {
                    var liked = false
                    if (action.like && writeSettings.autoLike) {
                        val likeEnvelope = data.call(
                            LocalDataMethods.LIKE_SET,
                            buildJsonObject {
                                put("postId", postId)
                                put("liked", true)
                            },
                            actor,
                            writeSettings,
                        )
                        if (!likeEnvelope.ok) {
                            return@commitOutcome failed(
                                likeEnvelope.error?.code ?: "internal",
                                likeEnvelope.error?.message ?: "Like was not recorded",
                            )
                        }
                        liked = true
                    }
                    val envelope = data.call(
                        LocalDataMethods.COMMENT_CREATE,
                        buildJsonObject {
                            put("postId", postId)
                            put("body", body)
                            put("clientKey", reviewClientKey(action.id))
                            action.replyToId?.let { put("replyToId", it) }
                        },
                        actor,
                        writeSettings,
                    )
                    if (!envelope.ok) {
                        failed(envelope.error?.code ?: "internal", envelope.error?.message ?: "Comment was not stored")
                    } else {
                        LocalTaskTurnOutcome.Replied(body.length, liked = liked, truncated = false)
                    }
                }
                return@withContext outcome
            }

            LocalTaskActionKinds.LIKE -> {
                val postId = action.postId
                    ?: return@withContext failed(LocalTaskErrorCode.INVALID_REQUEST, "postId missing")
                val outcome = token.commitOutcome {
                    val envelope = data.call(
                        LocalDataMethods.LIKE_SET,
                        buildJsonObject {
                            put("postId", postId)
                            put("liked", true)
                        },
                        actor,
                        writeSettings,
                    )
                    if (!envelope.ok) {
                        failed(envelope.error?.code ?: "internal", envelope.error?.message ?: "Like was not recorded")
                    } else {
                        LocalTaskTurnOutcome.Replied(0, liked = true, truncated = false)
                    }
                }
                return@withContext outcome
            }

            LocalTaskActionKinds.PUBLISH, LocalTaskActionKinds.DRAFT -> {
                if (action.kind == LocalTaskActionKinds.PUBLISH &&
                    policy != LocalAssistantPolicy.AUTO_PUBLISH
                ) {
                    // The native policy no longer allows direct publishing: keep a draft instead.
                    return@withContext saveDraft(
                        data, actor, token, action.body, action.mediaIds, writeSettings, "publish_disabled",
                    )
                }
                if (action.kind == LocalTaskActionKinds.PUBLISH) {
                    val envelope = token.commitIfActive {
                        data.call(
                            LocalDataMethods.CREATE,
                            createParams(action.body, action.mediaIds, reviewClientKey(action.id), writeSettings),
                            actor,
                            writeSettings,
                        )
                    } ?: return@withContext LocalTaskTurnOutcome.Cancelled
                    if (envelope.ok) {
                        val postId = data.decode(LocalPostCreateResultDto.serializer(), envelope)?.post?.id
                        val published = LocalTaskTurnOutcome.PostPublished(postId.orEmpty())
                        token.recordCommitted(published)
                        return@withContext published
                    }
                    if (envelope.error?.code != LocalTaskErrorCode.CAPABILITY_DENIED.wire) {
                        return@withContext failed(
                            envelope.error?.code ?: "internal",
                            envelope.error?.message ?: "Post was not published",
                        )
                    }
                }
                saveDraft(data, actor, token, action.body, action.mediaIds, writeSettings)
            }

            else -> failed(LocalTaskErrorCode.INVALID_REQUEST, "Unknown review action")
        }
    }

    private suspend fun runTurnInternal(request: LocalTaskTurnRequest): LocalTaskTurnOutcome {
        val settings = settingsProvider()
        val assistantUuid = runCatching { Uuid.parse(request.assistant.id) }.getOrNull()
            ?: return failed(LocalTaskErrorCode.ASSISTANT_MISSING, "Assistant id is invalid")
        val assistant = settings.getAssistantById(assistantUuid)
            ?: return failed(LocalTaskErrorCode.ASSISTANT_MISSING, "Assistant no longer exists")
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: return failed(LocalTaskErrorCode.MODEL_UNAVAILABLE, "No model is configured for this assistant")
        val providerSetting = model.findProvider(settings.providers)
            ?: return failed(LocalTaskErrorCode.MODEL_UNAVAILABLE, "Provider is not configured for this model")
        val data = LocalTaskLocalData(session, directoryOf(settings))
        val actor = actorOf(assistant, request.assistant)
        val taskSettings = request.settings.normalized()
        if (taskSettings.globalStop || !taskSettings.enabled) return LocalTaskTurnOutcome.Cancelled
        return when (request.mode) {
            LocalTaskMode.INTERACT -> runInteraction(
                request, taskSettings, assistant, model, providerSetting, data, actor,
            )

            LocalTaskMode.PUBLISH -> runPublish(
                request, taskSettings, assistant, model, providerSetting, settings, data, actor,
            )
        }
    }

    private suspend fun runInteraction(
        request: LocalTaskTurnRequest,
        settings: LocalTaskSettings,
        assistant: Assistant,
        model: Model,
        providerSetting: ProviderSetting,
        data: LocalTaskLocalData,
        actor: LocalDataActor.Assistant,
    ): LocalTaskTurnOutcome {
        val postId = request.postId
            ?: return failed(LocalTaskErrorCode.INVALID_REQUEST, "postId missing")
        val load = LocalTaskContextLoader(data).loadPost(actor, postId, settings)
        val context = when (load) {
            LocalTaskLoad.Unavailable -> return LocalTaskTurnOutcome.PostUnavailable
            is LocalTaskLoad.Failed -> return failed(load.code, load.message)
            is LocalTaskLoad.Post -> load.context
        }
        if (context.authorKind == "assistant" && context.authorId == request.assistant.id) {
            return LocalTaskTurnOutcome.NotAttempted("own_post")
        }
        if (context.authorKind == "user" && !settings.commentUserPosts) {
            return LocalTaskTurnOutcome.NotAttempted("comments_disabled")
        }
        if (context.authorKind == "assistant" && !settings.commentAssistantPosts) {
            return LocalTaskTurnOutcome.NotAttempted("comments_disabled")
        }
        if (request.token.isCancelled) return LocalTaskTurnOutcome.Cancelled
        val text = generate(
            assistant = assistant,
            model = model,
            providerSetting = providerSetting,
            system = LocalTaskPrompts.interactionSystem(request.assistant, settings),
            user = LocalTaskPrompts.interactionUser(context, settings, request.round, request.maxRounds),
        ) ?: return failed(LocalTaskErrorCode.UNAVAILABLE, "The model did not return an answer")
        if (request.token.isCancelled) return LocalTaskTurnOutcome.Cancelled
        // Re-read the live assistant list before parsing/writing: deleting the assistant during the
        // model call must not let this turn write under a stale actor/directory.
        if (!assistantStillExists(assistant)) return LocalTaskTurnOutcome.Cancelled
        return when (
            val action = LocalTaskActionParser.parseInteraction(
                raw = text,
                maxChars = settings.maxOutputChars,
                knownCommentIds = context.knownCommentIds(),
            )
        ) {
            is LocalTaskAction.Invalid -> failed(LocalTaskErrorCode.INVALID_ACTION, action.message)
            is LocalTaskAction.Skip -> LocalTaskTurnOutcome.Skipped
            is LocalTaskAction.Reply -> applyReply(request, settings, data, actor, context, action)
            is LocalTaskAction.DraftPost, is LocalTaskAction.PublishPost ->
                failed(LocalTaskErrorCode.INVALID_ACTION, "Action is not allowed for an interaction task")
        }
    }

    private suspend fun runPublish(
        request: LocalTaskTurnRequest,
        settings: LocalTaskSettings,
        assistant: Assistant,
        model: Model,
        providerSetting: ProviderSetting,
        appSettings: Settings,
        data: LocalTaskLocalData,
        actor: LocalDataActor.Assistant,
    ): LocalTaskTurnOutcome {
        val policy = settings.effectivePolicy(request.assistant.id)
        if (policy == LocalAssistantPolicy.DISABLED) {
            return LocalTaskTurnOutcome.NotAttempted("publish_disabled")
        }
        val canPublish = policy == LocalAssistantPolicy.AUTO_PUBLISH
        val feed = LocalTaskContextLoader(data).loadFeed(actor, settings)
        if (request.token.isCancelled) return LocalTaskTurnOutcome.Cancelled
        // Image generation is a second paid call. It needs the base image switch, the dedicated
        // native switch, the localdata media grant and a configured image model/provider. If any is
        // missing the prompt does not offer it and the parser rejects an image field.
        val imageModel = appSettings.findModelById(appSettings.imageGenerationModelId)
        val imageProviderSetting = imageModel?.findProvider(appSettings.providers)
        val canGenerateImage = LocalTaskImagePolicy.allows(
            settings = settings,
            mediaGranted = session.grant.allows(LocalDataCapability.MEDIA),
            imageModelConfigured = imageModel != null && imageProviderSetting != null,
        )
        val text = generate(
            assistant = assistant,
            model = model,
            providerSetting = providerSetting,
            system = LocalTaskPrompts.publishSystem(request.assistant, settings, canPublish, canGenerateImage),
            user = LocalTaskPrompts.publishUser(feed, settings, canGenerateImage),
        ) ?: return failed(LocalTaskErrorCode.UNAVAILABLE, "The model did not return an answer")
        if (request.token.isCancelled) return LocalTaskTurnOutcome.Cancelled
        if (!assistantStillExists(assistant)) return LocalTaskTurnOutcome.Cancelled
        val action = LocalTaskActionParser.parsePublish(text, settings.maxOutputChars, canGenerateImage)
        val imageRequest = when (action) {
            is LocalTaskAction.DraftPost -> action.imageRequest
            is LocalTaskAction.PublishPost -> action.imageRequest
            else -> null
        }
        var generatedMediaId: String? = null
        if (imageRequest != null) {
            if (!canGenerateImage || imageModel == null || imageProviderSetting == null) {
                return failed(
                    LocalTaskErrorCode.IMAGE_UNSUPPORTED,
                    "Image generation is not enabled for this task",
                )
            }
            val usageKey = request.usageKey ?: LocalTasksApi.publishUsageKey(clock())
            if (!imageBudget.tryCharge(usageKey, request.taskId, settings)) {
                return failed(LocalTaskErrorCode.BUDGET_EXHAUSTED, "Image generation budget is exhausted")
            }
            val imagePrompt = (imageRequest as LocalTaskImageRequest.GenerateImage).prompt
            val bytes = try {
                generateImageBytes(
                    assistant = assistant,
                    imageModel = imageModel,
                    providerSetting = imageProviderSetting,
                    appSettings = appSettings,
                    prompt = imagePrompt,
                )
            } catch (error: CancellationException) {
                imageBudget.refund(usageKey, request.taskId)
                throw error
            } catch (_: Exception) {
                imageBudget.refund(usageKey, request.taskId)
                return failed(
                    LocalTaskErrorCode.IMAGE_GENERATION_FAILED,
                    "Image generation failed; nothing was published",
                )
            }
            if (bytes == null) {
                imageBudget.refund(usageKey, request.taskId)
                return failed(
                    LocalTaskErrorCode.IMAGE_GENERATION_FAILED,
                    "Image generation returned no image; nothing was published",
                )
            }
            if (request.token.isCancelled) return LocalTaskTurnOutcome.Cancelled
            val imported = runCatching { session.importImageBytes(bytes, null) }.getOrElse {
                return failed(
                    LocalTaskErrorCode.IMAGE_GENERATION_FAILED,
                    "The generated image could not be stored; nothing was published",
                )
            }
            generatedMediaId = imported.media.resourceId
        }
        val mediaIds = if (settings.allowImages) {
            (request.mediaIds + listOfNotNull(generatedMediaId))
                .distinct()
                .filter { LocalDataValidation.isSafeMediaId(it) }
                .take(LocalDataApi.MAX_MEDIA_PER_POST)
        } else {
            emptyList()
        }
        val mediaSource = when {
            generatedMediaId != null -> LocalTaskMediaSource.GENERATED
            mediaIds.isNotEmpty() -> LocalTaskMediaSource.NATIVE_SELECTION
            else -> LocalTaskMediaSource.NONE
        }
        return when (action) {
            is LocalTaskAction.Invalid -> failed(LocalTaskErrorCode.INVALID_ACTION, action.message)
            is LocalTaskAction.Skip -> LocalTaskTurnOutcome.Skipped
            is LocalTaskAction.DraftPost -> {
                if (settings.requireReview) {
                    held(
                        LocalTaskActionKinds.DRAFT,
                        request.assistant,
                        null,
                        action.body,
                        null,
                        false,
                        mediaIds,
                        mediaSource,
                    )
                } else {
                    saveDraft(data, actor, request.token, action.body, mediaIds, settings)
                }
            }

            is LocalTaskAction.PublishPost -> {
                if (settings.requireReview) {
                    return held(
                        LocalTaskActionKinds.PUBLISH,
                        request.assistant,
                        null,
                        action.body,
                        null,
                        false,
                        mediaIds,
                        mediaSource,
                    )
                }
                if (!canPublish) {
                    return saveDraft(data, actor, request.token, action.body, mediaIds, settings, "publish_disabled")
                }
                val envelope = request.token.commitIfActive {
                    data.call(
                        LocalDataMethods.CREATE,
                        createParams(action.body, mediaIds, LocalTaskIds.publishClientKey(request.taskId), settings),
                        actor,
                        settings,
                    )
                } ?: return LocalTaskTurnOutcome.Cancelled
                if (envelope.ok) {
                    val postId = data.decode(LocalPostCreateResultDto.serializer(), envelope)?.post?.id
                    val published = LocalTaskTurnOutcome.PostPublished(postId.orEmpty())
                    request.token.recordCommitted(published)
                    published
                } else if (envelope.error?.code == LocalTaskErrorCode.CAPABILITY_DENIED.wire) {
                    // Publish was declared but the native grant does not allow it: keep the draft.
                    saveDraft(data, actor, request.token, action.body, mediaIds, settings, "publish_not_granted")
                } else {
                    failed(envelope.error?.code ?: "internal", envelope.error?.message ?: "Post was not published")
                }
            }

            is LocalTaskAction.Reply -> failed(LocalTaskErrorCode.INVALID_ACTION, "Reply is not valid here")
        }
    }

    private suspend fun applyReply(
        request: LocalTaskTurnRequest,
        settings: LocalTaskSettings,
        data: LocalTaskLocalData,
        actor: LocalDataActor.Assistant,
        context: LocalTaskPostContext,
        action: LocalTaskAction.Reply,
    ): LocalTaskTurnOutcome {
        if (settings.requireReview) {
            return held(
                LocalTaskActionKinds.REPLY,
                request.assistant,
                context.postId,
                action.body,
                action.replyToId,
                action.like,
                emptyList(),
            )
        }
        val outcome = request.token.commitOutcome {
            var liked = false
            if (action.like && settings.autoLike && !context.likedByMe) {
                val likeEnvelope = data.call(
                    LocalDataMethods.LIKE_SET,
                    buildJsonObject {
                        put("postId", context.postId)
                        put("liked", true)
                    },
                    actor,
                    settings,
                )
                if (!likeEnvelope.ok) {
                    return@commitOutcome failed(
                        likeEnvelope.error?.code ?: "internal",
                        likeEnvelope.error?.message ?: "Like was not recorded",
                    )
                }
                liked = true
            }
            val envelope = data.call(
                LocalDataMethods.COMMENT_CREATE,
                buildJsonObject {
                    put("postId", context.postId)
                    put("body", action.body)
                    put(
                        "clientKey",
                        LocalTaskIds.commentClientKey(context.postId, request.assistant.id, request.replyIndex),
                    )
                    action.replyToId?.let { put("replyToId", it) }
                },
                actor,
                settings,
            )
            if (!envelope.ok) {
                failed(
                    envelope.error?.code ?: "internal",
                    envelope.error?.message ?: "Comment was not stored",
                )
            } else {
                LocalTaskTurnOutcome.Replied(action.body.length, liked, action.truncated)
            }
        }
        return outcome
    }

    /** Runs [block] under the gate and remembers a committed result for the cancel/job race. */
    private fun LocalTaskCancellationToken.commitOutcome(
        block: () -> LocalTaskTurnOutcome,
    ): LocalTaskTurnOutcome {
        val outcome = commitIfActive(block) ?: return LocalTaskTurnOutcome.Cancelled
        recordCommitted(outcome)
        return outcome
    }

    private fun saveDraft(
        data: LocalTaskLocalData,
        actor: LocalDataActor.Assistant,
        token: LocalTaskCancellationToken,
        body: String,
        mediaIds: List<String>,
        settings: LocalTaskSettings,
        reason: String? = null,
    ): LocalTaskTurnOutcome {
        val outcome = token.commitOutcome {
            val envelope = data.call(
                LocalDataMethods.DRAFT_SAVE,
                buildJsonObject {
                    put("body", body)
                    put("visibility", "all_assistants")
                    put("mediaIds", jsonIds(if (settings.allowImages) mediaIds else emptyList()))
                },
                actor,
                settings,
            )
            if (!envelope.ok) {
                failed(
                    envelope.error?.code ?: "internal",
                    envelope.error?.message ?: "Draft was not stored",
                )
            } else {
                val draftId = data.decode(LocalDraftDto.serializer(), envelope)?.id.orEmpty()
                LocalTaskTurnOutcome.PostDrafted(draftId, reason)
            }
        }
        return outcome
    }

    private fun createParams(
        body: String,
        mediaIds: List<String>,
        clientKey: String,
        settings: LocalTaskSettings,
    ) = buildJsonObject {
        put("body", body)
        put("visibility", "all_assistants")
        put("mediaIds", jsonIds(if (settings.allowImages) mediaIds else emptyList()))
        put("clientKey", clientKey)
    }

    private fun jsonIds(mediaIds: List<String>) = buildJsonArray {
        mediaIds.filter { LocalDataValidation.isSafeMediaId(it) }.distinct()
            .take(LocalDataApi.MAX_MEDIA_PER_POST)
            .forEach { add(JsonPrimitive(it)) }
    }

    private fun held(
        kind: String,
        participant: LocalTaskParticipant,
        postId: String?,
        body: String,
        replyToId: String?,
        like: Boolean,
        mediaIds: List<String>,
        mediaSource: LocalTaskMediaSource = LocalTaskMediaSource.NONE,
    ): LocalTaskTurnOutcome.HeldForReview = LocalTaskTurnOutcome.HeldForReview(
        LocalTaskPendingActionDto(
            id = LocalTaskIds.newReviewId(),
            kind = kind,
            assistantId = participant.id,
            assistantName = participant.name,
            postId = postId,
            body = body,
            replyToId = replyToId,
            like = like,
            mediaIds = mediaIds,
            mediaSource = mediaSource,
            createdAt = clock(),
        ),
    )

    private suspend fun generateImageBytes(
        assistant: Assistant,
        imageModel: Model,
        providerSetting: ProviderSetting,
        appSettings: Settings,
        prompt: String,
    ): ByteArray? {
        // Same configured image model and provider call as the chat generate_image tool. No key,
        // path or file name is read here; the provider returns base64 bytes only.
        // UsageRecorder currently records text requests; this image call is budgeted in the task
        // ledger and must not be advertised as having a token-usage row.
        val params = ImageGenerationParams(
            model = imageModel,
            prompt = prompt,
            numOfImages = 1,
            aspectRatio = appSettings.imageGenerationAspectRatio,
            customHeaders = imageModel.customHeaders,
            customBody = imageModel.customBodies,
        )
        val provider = usageRecorder.wrap(
            providerManager.getProviderByType(providerSetting),
            UsageTag(UsageSource.PLUGIN, assistant.id, null),
        )
        val result = provider.generateImage(providerSetting, params)
        val item = result.items.firstOrNull() ?: return null
        val encoded = if (item.data.startsWith("data:")) {
            item.data.substringAfter("base64,", "")
        } else {
            item.data
        }
        if (encoded.isBlank()) return null
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
        return bytes.takeIf { it.size in 1..LocalDataApi.MAX_MEDIA_BYTES }
    }

    private suspend fun generate(
        assistant: Assistant,
        model: Model,
        providerSetting: ProviderSetting,
        system: String,
        user: String,
    ): String? {
        val provider = usageRecorder.wrap(
            providerManager.getProviderByType(providerSetting),
            UsageTag(UsageSource.PLUGIN, assistant.id, null),
        )
        val params = buildAssistantGenerationParams(
            assistant = assistant,
            model = model,
            provider = providerSetting,
            tools = emptyList(),
            conversationId = null,
        ).copy(maxTokens = assistant.maxTokens ?: LocalTasksApi.DEFAULT_MAX_TOKENS)
        val chunk = try {
            provider.generateText(
                providerSetting = providerSetting,
                messages = listOf(UIMessage.system(system), UIMessage.user(user)),
                params = params,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        return chunk.choices.firstOrNull()?.message?.toText()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun assistantStillExists(assistant: Assistant): Boolean =
        settingsProvider().getAssistantById(assistant.id) != null

    private fun directoryOf(settings: Settings): LocalAssistantDirectory = LocalAssistantDirectory.of(
        settings.assistants.map { assistant ->
            LocalDataAssistantRef(
                id = assistant.id.toString(),
                name = assistant.name,
                avatar = avatarOf(assistant.avatar),
            )
        },
    )

    private fun actorOf(assistant: Assistant, participant: LocalTaskParticipant): LocalDataActor.Assistant =
        LocalDataActor.Assistant(
            id = assistant.id.toString(),
            name = assistant.name.ifBlank { participant.name }.ifBlank { "Assistant" },
            avatar = avatarOf(assistant.avatar) ?: participant.avatar,
        )

    private fun reviewClientKey(reviewId: String): String =
        LocalTasksApi.TASK_ID_PREFIX + sha256Hex("review|$reviewId").take(32)

    private fun failed(code: String, message: String) = LocalTaskTurnOutcome.Failed(code, message)

    private fun failed(code: LocalTaskErrorCode, message: String) = LocalTaskTurnOutcome.Failed(code.wire, message)

    private fun avatarOf(avatar: Avatar): String? = when (avatar) {
        Avatar.Dummy -> null
        is Avatar.Emoji -> avatar.content
        is Avatar.Image -> avatar.url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }
}
