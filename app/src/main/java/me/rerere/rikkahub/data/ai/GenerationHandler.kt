/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.interaction.withInteractionRequestScope
 
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.merge
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.VideoCallDirectiveSession
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.ai.tools.buildWriteFilesTool
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.service.MemoryBankService
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.interaction.ChatInteractionRepository
import me.rerere.rikkahub.data.interaction.ReactionContextTurn
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.time.Clock
 
private const val TAG = "GenerationHandler"
 
// 流式生成时往 UI 推送消息更新的最小间隔。
// AI 的 SSE 增量可能每秒到达几十次，如果每次都原样同步到 UI 的 StateFlow，
// 会导致 Compose 高频重组（Markdown 全量重解析、代码高亮重新分词、
// animateContentSize 的尺寸补间动画被不断打断重启），表现为打字机效果的"抖动/掉帧"。
// 这里把推送频率限制在这个间隔以内，肉眼完全感知不到延迟，但能大幅降低重组频率。
private const val STREAM_UI_THROTTLE_MS = 50L
 
@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}
 
class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val aiLoggingManager: AILoggingManager,
    private val memoryBankService: MemoryBankService,
    private val filesManager: me.rerere.rikkahub.data.files.FilesManager,
    private val chatInteractionRepository: ChatInteractionRepository,
    private val localScenes: me.rerere.rikkahub.data.serein.LocalSceneRepository,
    private val localSceneSettings: me.rerere.rikkahub.data.datastore.SettingsStore,
    private val usageRecorder: UsageRecorder,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationContextSummary: String? = null,
        workspaceCwd: String? = null,
        pluginPromptInjections: List<String> = emptyList(),
        conversationId: String? = null,
        onToolCheckpoint: (suspend (List<UIMessage>) -> Unit)? = null,
        reactionSourceMessages: List<UIMessage> = messages,
        onReactionContextPrepared: (ReactionContextTurn) -> Unit = {},
        /**
         * 只在本次请求里附带的图片（视频通话自动抓帧）。
         *
         * 约定：不写进聊天历史，只作为这一次（含工具多步）请求的临时图片输入，
         * 请求结束后由 ChatService 删除对应临时文件；历史里只留文字。
         */
        transientImageUrls: List<String> = emptyList(),
        /**
         * 这一轮的触发用户消息 ID（视频通话临时图的锚点）。
         *
         * 临时图必须挂到**触发它的那条用户消息**上，而不是"请求里最后一条 USER"：
         * 工具多步会插入新的消息，最后一条 USER 可能已经变成别的（甚至工具结果里的用户内容）。
         */
        transientImageAnchorMessageId: String? = null,
        /**
         * 视频通话这一轮的**请求 token**（授权凭证）。
         *
         * 由 VoiceCallService 生成、随队列项不可变地传下来；礼物/留影只认
         * "有效通话 + 这个 token"，不再用消息历史推断身份。
         */
        requestToken: String? = null,
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = usageRecorder.wrap(
            providerManager.getProviderByType(provider),
            UsageTag(UsageSource.CHAT, assistant.id, conversationId?.let {
                runCatching { kotlin.uuid.Uuid.parse(it) }.getOrNull()
            }),
        )
 
        val reactionTurn = chatInteractionRepository.prepareContext(
            reactionSourceMessages, conversationId, assistant, messages,
        )
        onReactionContextPrepared(reactionTurn)
        var messages: List<UIMessage> = messages
        val recoveredMessages = messages.recoverToolHistoryForRequest(json)
        if (recoveredMessages != messages) {
            messages = reactionTurn.markPending(recoveredMessages)
            withContext(NonCancellable) { onToolCheckpoint?.invoke(messages) }
            emit(GenerationChunk.Messages(messages))
        }
 
        messages = runGenerationToolLoop(
            initialMessages = messages,
            maxSteps = maxSteps,
            resumeApprovedTools = true,
            toolsForStep = { stepIndex ->
                Log.i(TAG, "streamText: build tools for step #$stepIndex (${model.id})")
                buildList {
                    if (!localSceneSettings.settingsFlow.value.sereinHook.usableBy(assistant.id.toString()) &&
                        tools.none { it.name == "local_scene_search" }) {
                        addAll(me.rerere.rikkahub.data.serein.buildLocalSceneTools(assistant, localScenes) {
                            localSceneSettings.settingsFlow.value.let { live ->
                                !live.sereinHook.usableBy(assistant.id.toString()) &&
                                    live.assistants.any { it.id == assistant.id && it.enableLocalSceneTools }
                            }
                        })
                    }
                    if (assistant.enableMemory) {
                        val memoryAssistantId = if (assistant.useGlobalMemory) {
                            MemoryRepository.GLOBAL_MEMORY_ID
                        } else {
                            assistant.id.toString()
                        }
                        addAll(buildMemoryTools(
                            json = json,
                            onCreation = { content -> memoryRepo.addMemory(memoryAssistantId, content) },
                            onUpdate = { id, content -> memoryRepo.updateContent(id, content) },
                            onDelete = { id -> memoryRepo.deleteMemory(id) },
                        ))
                    }
                    add(buildWriteFilesTool(conversationId))
                    addAll(tools)
                }
            },
            generateTurn = { turnMessages, toolsInternal, _ ->
                // 视频通话指令的请求作用域：这一轮生成期间保存协议原文，
                // 结束阶段用它执行礼物/留影；无论正常结束、取消还是打断都在 finally 清空。
                val videoDirectives = VideoCallDirectiveSession()
                try {
                generateInternal(
                    assistant = assistant,
                    settings = settings,
                    messages = turnMessages,
                    pluginPromptInjections = pluginPromptInjections,
                    onUpdateMessages = {
                        val pending = it.dropLast(1) + it.last().withoutGenerationCompletion()
                        messages = reactionTurn.markPending(pending.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings,
                            conversationId = conversationId,
                            videoDirectives = videoDirectives, requestToken = requestToken,
                        ))
                        emit(GenerationChunk.Messages(reactionTurn.markPending(messages.visualTransforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings,
                            conversationId = conversationId,
                            videoDirectives = videoDirectives, requestToken = requestToken,
                        ))))
                    },
                    transformers = inputTransformers,
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    tools = toolsInternal,
                    memories = memories ?: emptyList(),
                    stream = assistant.streamOutput,
                    processingStatus = processingStatus,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationContextSummary = conversationContextSummary,
                    workspaceCwd = workspaceCwd,
                    conversationId = conversationId,
                    reactionTurn = reactionTurn,
                    transientImageUrls = transientImageUrls,
                    transientImageAnchorMessageId = transientImageAnchorMessageId,
                )
                messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings,
                    conversationId = conversationId,
                    videoDirectives = videoDirectives, requestToken = requestToken,
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings,
                    conversationId = conversationId,
                    videoDirectives = videoDirectives, requestToken = requestToken,
                )
                messages = reactionTurn.markPending(messages.dropLast(1) + messages.last().copy(
                    finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
                ))
                emit(GenerationChunk.Messages(messages))
                messages
                } finally {
                    videoDirectives.clear()
                }
            },
            prepareTools = { calls, definitions ->
                prepareGenerationToolApproval(
                    calls = calls,
                    definitions = definitions,
                    autoApproveAllTools = settings.autoApproveAllTools,
                    forceConfirmToolCalls = settings.forceConfirmToolCalls,
                )
            },
            executeTool = { call, definition, _ ->
                executeGenerationToolBatch(
                    approval = GenerationToolApproval(listOf(call)),
                    definitions = listOfNotNull(definition?.withInteractionRequestScope(messages.last().id)),
                    json = json,
                    onCheckpoint = { checkpointedTool ->
                        val checkpointed = reactionTurn.markPending(messages.replaceLastGenerationTools(listOf(checkpointedTool)))
                        withContext(NonCancellable) { onToolCheckpoint?.invoke(checkpointed) }
                        messages = checkpointed
                    },
                ).singleOrNull() ?: call
            },
            onToolsUpdated = { updated, update ->
                messages = reactionTurn.markPending(updated)
                when (update) {
                    GenerationToolUpdate.APPROVAL -> {
                        withContext(NonCancellable) { onToolCheckpoint?.invoke(messages) }
                        emit(GenerationChunk.Messages(messages))
                    }
                    GenerationToolUpdate.RESULTS -> emit(GenerationChunk.Messages(
                        reactionTurn.markPending(messages.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings,
                            conversationId = conversationId,
                        ))
                    ))
                }
            },
            onCompleted = { completed ->
                val finalMessages = completed.dropLast(1) + completed.last().withGenerationCompletion()
                emit(GenerationChunk.Messages(finalMessages))
                finalMessages
            },
        )
        reactionTurn.onGenerationSucceeded(messages.lastOrNull())
 
    }.throttleLatest(STREAM_UI_THROTTLE_MS)
        .flowOn(Dispatchers.IO)
 
    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        pluginPromptInjections: List<String> = emptyList(),
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationContextSummary: String? = null,
        workspaceCwd: String? = null,
        conversationId: String? = null,
        reactionTurn: ReactionContextTurn? = null,
        transientImageUrls: List<String> = emptyList(),
        transientImageAnchorMessageId: String? = null,
    ) {
        val baseMessages = buildList {
            val system = buildString {
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                        conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }
 
                // 记忆
                if (assistant.enableMemory) {
                    appendLine()
                    append(buildMemoryPrompt(memories = memories))
                }
 
 
                if (assistant.enableRecentChatsReference) {
                    appendLine()
                    append(buildRecentChatsPrompt(assistant, conversationRepo))
                }
 
                // 代码文件命名和ZIP打包功能说明
                appendLine()
                append(buildCodeBlockPrompt())
 
                // 工具prompt
                tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, messages))
                }
 
                // 插件提示词注入
                if (pluginPromptInjections.isNotEmpty()) {
                    pluginPromptInjections.forEach { injection ->
                        appendLine()
                        appendLine()
                        append(injection)
                    }
                }
 
                // 允许跳过回复
                if (assistant.allowSkipReply) {
                    appendLine()
                    appendLine()
                    appendLine("## Skip Reply")
                    appendLine("If you determine that no reply is needed (e.g., the user's message doesn't require a response, or you have nothing meaningful to add), you may reply with exactly `[SKIP]` (without any other text). This message will be hidden from the user. Use this sparingly and only when truly appropriate.")
                }

                // 屏幕跳转能力（AI总是可以跳转，不需要开关）
                if (true) {
                    appendLine()
                    appendLine()
                    appendLine("## 屏幕跳转能力")
                    appendLine("你可以在回复末尾追加 [JUMP] 标记（单独一行）来把聊天界面拉到用户屏幕最前面。")
                    appendLine("适用场景：")
                    appendLine("- 用户说要去别的应用，你觉得需要把用户拉回来时")
                    appendLine("- 你觉得接下来的内容需要用户立即看到时")
                    appendLine("不适用场景：")
                    appendLine("- 一般闲聊不需要跳转")
                    appendLine("- 用户正在跟你正常对话时不需要跳转")
                    appendLine("[JUMP] 标记不会展示给用户，仅用于触发屏幕跳转。")
                }
 
                // 分气泡: 告知模型它自己能控制消息如何被拆成多个气泡
                if (assistant.splitBubbleByLine) {
                    appendLine()
                    appendLine()
                    appendLine("## Message Bubbles")
                    appendLine("Your reply will be automatically split into separate chat bubbles at every line break (\\n) you write, similar to how a person sends several short texts in a row instead of one long message. You are fully in control of this: write a line break whenever you want the previous thought/sentence to appear as its own bubble, and keep things on the same line when they belong together. Do not insert blank lines purely for spacing — every line break becomes a new bubble, so use them intentionally. Exception: line breaks inside fenced code blocks (```) and Markdown tables are preserved as-is and will NOT create new bubbles, since those must stay intact as a single block.")
                }
 
            }
            addAll(buildConversationRequestMessages(system, messages, assistant.contextMessageSize, conversationContextSummary))
        }
        val internalMessages = chatInteractionRepository.decorate(
            baseMessages,
            conversationId,
            assistant,
            sourceMessages = messages,
            turn = reactionTurn,
        ).let { filesManager.validAttachments(it, forSending = true) }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
        ).let { filesManager.validAttachments(it, forSending = true) }.filterNot { it.parts.isEmpty() }
            .withTransientRequestImages(transientImageUrls, transientImageAnchorMessageId)
            .let { if (transientImageUrls.isEmpty()) it else filesManager.validAttachments(it, forSending = true) }
 
        var messages: List<UIMessage> = messages
        val params = buildAssistantGenerationParams(
            assistant = assistant,
            model = model,
            provider = provider,
            conversationId = conversationId,
            tools = tools,
        )
        aiLoggingManager.addLog(
            AILogging.Generation(
                params = params,
                // Keep the existing UI message snapshot for the developer page, but also
                // record a one-way fingerprint of the exact transformed request. This is
                // enough to compare cache prefixes without retaining prompt contents.
                messages = messages,
                providerSetting = provider,
                stream = stream,
                requestFingerprint = fingerprintGenerationRequest(
                    provider = provider,
                    params = params,
                    messages = internalMessages,
                    tools = tools,
                ),
            )
        )
        messages = generateModelTurn(
            providerImpl = providerImpl,
            providerSetting = provider,
            requestMessages = internalMessages,
            initialMessages = messages,
            params = params,
            model = model,
            stream = stream,
            mergeUsage = true,
            onUpdateMessages = onUpdateMessages,
            updateIntervalMillis = STREAM_UI_THROTTLE_MS,
        )
    }
 
    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")
 
        val providerHandler = usageRecorder.wrap(
            providerManager.getProviderByType(provider), UsageTag(UsageSource.TRANSLATION),
        )
 
        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )
 
            var messages = listOf(UIMessage.user(prompt))
            var translatedText = ""
 
            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                ),
            ).collect { chunk ->
                messages = messages.handleMessageChunk(chunk)
                translatedText = messages.lastOrNull()?.toText() ?: ""
 
                if (translatedText.isNotBlank()) {
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val chunk = providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.95f,
                    customBody = listOf(
                        CustomBody(
                            key = "translation_options",
                            value = buildJsonObject {
                                put("source_lang", JsonPrimitive("auto"))
                                put(
                                    "target_lang",
                                    JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                )
                            }
                        )
                    )
                ),
            )
            val translatedText = chunk.choices.firstOrNull()?.message?.toText() ?: ""
 
            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)
}
 
/**
 * 把视频通话的自动抓帧作为"仅本次请求"的图片输入追加到**触发这一轮的用户消息**上。
 *
 * 锚点优先用 [anchorMessageId]（提交时分配的队列项 ID，也就是那条用户消息的 ID）。
 * 工具多步之后"最后一条 USER"可能已经变了，所以只有确实找不到锚点时，
 * 才退回到最后一条 USER。
 *
 * 只影响发给模型的 internalMessages：持久化的聊天消息里只有文字，
 * 临时文件在请求结束时由 ChatService 删除（工具多步期间一直有效）。
 */
private fun List<UIMessage>.withTransientRequestImages(
    urls: List<String>,
    anchorMessageId: String? = null,
): List<UIMessage> {
    if (urls.isEmpty()) return this
    val anchor = anchorMessageId?.trim()?.takeIf { it.isNotEmpty() }
    val anchoredIndex = anchor?.let { id ->
        indexOfLast { it.role == MessageRole.USER && it.id.toString() == id }
    } ?: -1
    val index = if (anchoredIndex >= 0) anchoredIndex else indexOfLast { it.role == MessageRole.USER }
    if (index < 0) return this
    val extra = urls.map { UIMessagePart.Image(url = it) }
    return mapIndexed { position, message ->
        if (position == index) message.copy(parts = message.parts + extra) else message
    }
}

/**
 * 把原始 Flow 的高频发射节流成"每 periodMillis 毫秒最多发一次最新值"。
 *
 * 实现方式：对上游调用 conflate()（只保留未被消费的最新一个值，中间值会被丢弃），
 * 然后在 collect 里每处理完一个值就 delay(periodMillis)。这样在上游快速连续发射时，
 * delay 期间产生的多个值会被 conflate 自动合并成"最新一个"，delay 结束后立刻拿到它；
 * 但由于用的是"发一个、等一段时间、再要下一个"的顺序结构，上游结束前最后一次真正的发射
 * 一定会被完整地 collect 到并 emit 出去，不会像 sample() 那样有丢失最终值的风险。
 *
 * 用于把 AI 流式输出的高频消息更新（可能每秒几十次）降频到 UI 友好的节奏，从源头
 * 消除打字机效果的抖动/掉帧，同时保证生成结束时 UI 一定能拿到完整的最终内容。
 */
private fun <T> Flow<T>.throttleLatest(periodMillis: Long): Flow<T> {
    val upstream = this
    return flow {
        upstream.conflate().collect { value ->
            emit(value)
            delay(periodMillis)
        }
    }
}
 
/**
 * 构建代码块提示 - 告知AI代码文件命名和ZIP打包功能
 */
private fun buildCodeBlockPrompt(): String = buildString {
    appendLine("## Code Block Rules (MUST FOLLOW)")
    appendLine()
    appendLine("1. **ALWAYS name code blocks with filenames**: You MUST use the actual filename as the code block language tag instead of just the language name. This is critical for proper file saving and syntax highlighting. Examples:")
    appendLine("   - ✅ Correct: ```MainActivity.kt instead of ```kotlin")
    appendLine("   - ✅ Correct: ```index.html instead of ```html")
    appendLine("   - ✅ Correct: ```styles.css instead of ```css")
    appendLine("   - ✅ Correct: ```package.json instead of ```json")
    appendLine("   - ✅ Correct: ```manifest.xml instead of ```xml")
    appendLine("   - ✅ Correct: ```main.py instead of ```python")
    appendLine("   - ✅ Correct: ```App.vue instead of ```vue")
    appendLine("   - ❌ Wrong: ```kotlin, ```python, ```javascript (these don't provide filenames)")
    appendLine("   - For code without a specific filename, use a descriptive name like ```example.ts, ```helper.py")
    appendLine()
    appendLine("2. **ZIP Download via `write_files` tool**: Users can download code files as a ZIP ONLY when you call this tool.")
    appendLine("   - **Full write** (first time / new files): `{\"zip_name\":\"project.zip\",\"files\":[{\"name\":\"MainActivity.kt\",\"content\":\"...\"}]}`")
    appendLine("   - **Incremental edit** (saves tokens! For modifying existing files): `{\"zip_name\":\"project-v2.zip\",\"base_files\":\"previous\",\"edits\":[{\"name\":\"MainActivity.kt\",\"search\":\"old code\",\"replace\":\"new code\"}]}`")
    appendLine("   - The `edits` mode applies search/replace to the files from your previous `write_files` call. Files not mentioned in `edits` keep their content unchanged.")
    appendLine("   - Always use actual filenames (e.g. `MainActivity.kt`) as code block language tags, not just language names (e.g. `kotlin`).")
}
