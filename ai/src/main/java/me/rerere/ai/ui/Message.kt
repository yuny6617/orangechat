/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.ui

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.util.json
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private fun UIMessagePart.hasThoughtSignature(): Boolean =
    listOf("thoughtSignature", "thought_signature").any { key ->
        (metadata?.get(key) as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
    }

// 公共消息抽象, 具体的Provider实现会转换为API接口需要的DTO
@Serializable
data class UIMessage(
    val id: Uuid = Uuid.random(),
    val role: MessageRole,
    val parts: List<UIMessagePart>,
    val annotations: List<UIMessageAnnotation> = emptyList(),
    val createdAt: LocalDateTime = Clock.System.now()
        .toLocalDateTime(TimeZone.currentSystemDefault()),
    val finishedAt: LocalDateTime? = null,
    val modelId: Uuid? = null,
    val usage: TokenUsage? = null,
    val translation: String? = null,
    val modelTimings: List<ModelTurnTiming> = emptyList(),
) {
    private fun appendChunk(chunk: MessageChunk): UIMessage {
        val choice = chunk.choices.getOrNull(0)
        val message = choice?.delta ?: choice?.message
        if (chunk.replaceContent && message != null) {
            return copy(
                parts = parts.dropLast(chunk.replacedParts) + message.parts,
                annotations = annotations.dropLast(chunk.replacedAnnotations) + message.annotations,
            )
        }
        return message?.let { delta ->
            // Handle Parts
            var newParts = delta.parts.fold(parts) { acc, deltaPart ->
                when (deltaPart) {
                    is UIMessagePart.Text -> {
                        // A Gemini streaming part may carry only a thought signature and an
                        // empty text value. Keep that metadata so it survives history replay.
                        if (deltaPart.text.isEmpty() && deltaPart.metadata == null) {
                            acc
                        } else {
                            val lastPart = acc.lastOrNull()
                            // Signed parts must keep their original boundaries, including a
                            // signature-only final streaming part. Never overwrite a signature
                            // or attach it to text from a different part.
                            if (lastPart is UIMessagePart.Text &&
                                !lastPart.hasThoughtSignature() && !deltaPart.hasThoughtSignature()
                            ) {
                                // Append to the last Text part
                                acc.dropLast(1) + lastPart.copy(
                                    text = lastPart.text + deltaPart.text,
                                    metadata = deltaPart.metadata ?: lastPart.metadata
                                )
                            } else {
                                // Create new Text part
                                acc + deltaPart
                            }
                        }
                    }

                    is UIMessagePart.Image -> {
                        val lastPart = acc.lastOrNull()
                        if (lastPart is UIMessagePart.Image) {
                            // Append to the last Image part (for streaming base64)
                            acc.dropLast(1) + lastPart.copy(
                                url = lastPart.url + deltaPart.url,
                                metadata = deltaPart.metadata ?: lastPart.metadata
                            )
                        } else {
                            // Create new Image part
                            acc + UIMessagePart.Image(
                                url = "data:image/png;base64,${deltaPart.url}",
                                metadata = deltaPart.metadata,
                            )
                        }
                    }

                    is UIMessagePart.Reasoning -> {
                        // Skip empty reasoning deltas
                        if (deltaPart.reasoning.isEmpty() && deltaPart.metadata == null) {
                            acc
                        } else {
                            val lastPart = acc.lastOrNull()
                            if (lastPart is UIMessagePart.Reasoning &&
                                !lastPart.hasThoughtSignature() && !deltaPart.hasThoughtSignature()
                            ) {
                                // Append to the last Reasoning part
                                acc.dropLast(1) + UIMessagePart.Reasoning(
                                    reasoning = lastPart.reasoning + deltaPart.reasoning,
                                    createdAt = lastPart.createdAt,
                                    finishedAt = null,
                                ).also {
                                    it.metadata = deltaPart.metadata ?: lastPart.metadata
                                }
                            } else {
                                // Create new Reasoning part
                                acc + deltaPart
                            }
                        }
                    }

                    is UIMessagePart.Tool -> {
                        if (deltaPart.toolCallId.isBlank()) {
                            // No ID yet - append to the last Tool if it also has no ID
                            val lastTool = acc.lastOrNull { it is UIMessagePart.Tool } as? UIMessagePart.Tool
                            if (lastTool != null) {
                                acc.map { part ->
                                    if (part === lastTool) part.merge(deltaPart) else part
                                }
                            } else {
                                acc + deltaPart.copy()
                            }
                        } else {
                            // Has ID - find and update by ID, or insert new
                            val existsPart = acc.find {
                                it is UIMessagePart.Tool && it.toolCallId == deltaPart.toolCallId
                            } as? UIMessagePart.Tool
                            if (existsPart == null) {
                                acc + deltaPart.copy()
                            } else {
                                acc.map { part ->
                                    if (part is UIMessagePart.Tool && part.toolCallId == deltaPart.toolCallId) {
                                        part.merge(deltaPart)
                                    } else part
                                }
                            }
                        }
                    }

                    else -> {
                        println("delta part append not supported: $deltaPart")
                        acc
                    }
                }
            }
            // Handle Reasoning End
            if (parts.filterIsInstance<UIMessagePart.Reasoning>()
                    .isNotEmpty() && delta.parts.filterIsInstance<UIMessagePart.Reasoning>()
                    .isEmpty()
            ) {
                newParts = newParts.map { part ->
                    if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                        part.copy(finishedAt = Clock.System.now())
                    } else part
                }
            }
            // Handle annotations
            val newAnnotations = delta.annotations.ifEmpty {
                annotations
            }
            copy(
                parts = newParts,
                annotations = newAnnotations,
            )
        } ?: this
    }

    fun summaryAsText(): String {
        return "[${role.name}]: " + parts.joinToString(separator = "\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                else -> ""
            }
        }
    }

    fun toText() = parts.joinToString(separator = "\n") { part ->
        when (part) {
            is UIMessagePart.Text -> part.text
            else -> ""
        }
    }

    fun getTools() = parts.filterIsInstance<UIMessagePart.Tool>()

    fun isValidToUpload() = parts.any { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.isNotBlank()
            is UIMessagePart.Image -> part.url.isNotBlank()
            is UIMessagePart.Video -> part.url.isNotBlank()
            is UIMessagePart.Audio -> part.url.isNotBlank()
            is UIMessagePart.Document -> part.url.isNotBlank()
            is UIMessagePart.Reasoning -> part.reasoning.isNotBlank()
            else -> true
        }
    }

    inline fun <reified P : UIMessagePart> hasPart(): Boolean {
        return parts.any {
            it is P
        }
    }

    fun hasBase64Part(): Boolean = parts.any {
        it is UIMessagePart.Image && it.url.startsWith("data:")
    }

    operator fun plus(chunk: MessageChunk): UIMessage {
        return this.appendChunk(chunk)
    }

    companion object {
        fun system(prompt: String) = UIMessage(
            role = MessageRole.SYSTEM,
            parts = listOf(UIMessagePart.Text(prompt))
        )

        fun user(prompt: String) = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(prompt))
        )

        fun assistant(prompt: String) = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(prompt))
        )
    }
}

/**
 * 处理MessageChunk合并
 *
 * @receiver 已有消息列表
 * @param chunk 消息chunk
 * @param model 模型, 可以不传，如果传了，会把模型id写入到消息，标记是哪个模型输出的消息
 * @return 新消息列表
 */
fun List<UIMessage>.handleMessageChunk(chunk: MessageChunk, model: Model? = null): List<UIMessage> {
    require(this.isNotEmpty()) {
        "messages must not be empty"
    }
    val choice = chunk.choices.getOrNull(0) ?: return this
    val message = choice.delta ?: choice.message ?: return this
    if (this.last().role != message.role) {
        return this + (UIMessage(modelId = model?.id, role = message.role, parts = emptyList()) + chunk)
    } else {
        val last = this.last() + chunk
        return this.dropLast(1) + last
    }
}

/**
 * 判断这个消息是否有有任何用户**可输入内容**
 *
 * 例如: 文本，图片, 文档
 */
fun List<UIMessagePart>.isEmptyInputMessage(): Boolean {
    if (this.isEmpty()) return true
    return this.all { message ->
        when (message) {
            is UIMessagePart.Text -> message.text.isBlank()
            is UIMessagePart.Image -> message.url.isBlank()
            is UIMessagePart.Document -> message.url.isBlank()
            is UIMessagePart.Video -> message.url.isBlank()
            is UIMessagePart.Audio -> message.url.isBlank()
            is UIMessagePart.VoiceMessage -> message.url.isBlank()
            else -> true
        }
    }
}

/**
 * 判断这个消息在UI上是否显示任何内容
 */
fun List<UIMessagePart>.isEmptyUIMessage(): Boolean {
    if (this.isEmpty()) return true
    return this.all { message ->
        when (message) {
            // Tool-only turns still contain visible progress, approval controls and receipts.
            is UIMessagePart.Tool -> false
            is UIMessagePart.Text -> message.text.isBlank()
            is UIMessagePart.Image -> message.url.isBlank()
            is UIMessagePart.Document -> message.url.isBlank()
            is UIMessagePart.Reasoning -> message.reasoning.isBlank()
            is UIMessagePart.Video -> message.url.isBlank()
            is UIMessagePart.Audio -> message.url.isBlank()
            is UIMessagePart.VoiceMessage -> message.url.isBlank()
            else -> true
        }
    }
}

fun List<UIMessage>.limitContext(size: Int): List<UIMessage> {
    if (size <= 0 || this.size <= size) return this

    val startIndex = this.size - size
    var adjustedStartIndex = startIndex

    // 循环往前查找，直到满足所有依赖条件
    var needsAdjustment = true
    val visitedIndices = mutableSetOf<Int>()

    while (needsAdjustment && adjustedStartIndex > 0) {
        needsAdjustment = false

        // 防止无限循环
        if (adjustedStartIndex in visitedIndices) break
        visitedIndices.add(adjustedStartIndex)

        val currentMessage = this[adjustedStartIndex]

        // 如果当前消息包含已执行的tool（有output），往前查找对应的tool call
        if (currentMessage.getTools().any { it.isExecuted }) {
            for (i in adjustedStartIndex - 1 downTo 0) {
                if (this[i].getTools().any { !it.isExecuted }) {
                    adjustedStartIndex = i
                    needsAdjustment = true
                    break
                }
            }
        }

        // 如果当前消息包含未执行的tool call，往前查找对应的用户消息
        if (currentMessage.getTools().any { !it.isExecuted }) {
            for (i in adjustedStartIndex - 1 downTo 0) {
                if (this[i].role == MessageRole.USER) {
                    adjustedStartIndex = i
                    needsAdjustment = true
                    break
                }
            }
        }
    }

    return this.subList(adjustedStartIndex, this.size)
}

@Serializable
sealed class ToolApprovalState {
    @Serializable
    @SerialName("auto")
    data object Auto : ToolApprovalState()

    @Serializable
    @SerialName("pending")
    data object Pending : ToolApprovalState()

    @Serializable
    @SerialName("approved")
    data object Approved : ToolApprovalState()

    @Serializable
    @SerialName("denied")
    data class Denied(val reason: String = "") : ToolApprovalState()

    @Serializable
    @SerialName("answered")
    data class Answered(val answer: String) : ToolApprovalState()
}

fun ToolApprovalState.canResumeToolExecution(): Boolean {
    return when (this) {
        ToolApprovalState.Approved -> true
        is ToolApprovalState.Denied -> true
        is ToolApprovalState.Answered -> true
        ToolApprovalState.Auto,
        ToolApprovalState.Pending,
            -> false
    }
}

@Serializable
sealed class UIMessagePart {
    abstract val metadata: JsonObject?

    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("image")
    data class Image(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("video")
    data class Video(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("audio")
    data class Audio(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("voice_message")
    data class VoiceMessage(
        val url: String,
        val duration: Long = 0,
        val transcript: String = "",
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("document")
    data class Document(
        val url: String,
        val fileName: String,
        val mime: String = "text/*",
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        val reasoning: String,
        val createdAt: Instant = Clock.System.now(),
        val finishedAt: Instant? = Clock.System.now(),
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Deprecated("Deprecated")
    @Serializable
    @SerialName("search")
    data object Search : UIMessagePart() {
        override var metadata: JsonObject? = null
    }

    @Deprecated("Use UIMessagePart.Tool instead")
    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val toolCallId: String,
        val toolName: String,
        val arguments: String,
        val approvalState: ToolApprovalState = ToolApprovalState.Auto,
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        fun merge(other: ToolCall): ToolCall {
            return ToolCall(
                toolCallId = toolCallId,
                toolName = toolName + other.toolName,
                arguments = arguments + other.arguments,
                approvalState = approvalState,
                metadata = if (other.metadata != null) other.metadata else metadata,
            )
        }
    }

    @Deprecated("Use UIMessagePart.Tool instead")
    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val content: JsonElement,
        val arguments: JsonElement,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("tool")
    data class Tool(
        val toolCallId: String,
        val toolName: String,
        val input: String,
        val output: List<UIMessagePart> = emptyList(),
        val approvalState: ToolApprovalState = ToolApprovalState.Auto,
        override var metadata: JsonObject? = null,
        val timing: ToolTiming? = null,
    ) : UIMessagePart() {
        /** Whether the tool has been executed (has output) */
        val isExecuted: Boolean get() = output.isNotEmpty()

        /** Whether the tool is pending user approval */
        val isPending: Boolean get() = !isExecuted && approvalState is ToolApprovalState.Pending

        /** Whether generation can resume and handle this tool immediately */
        val canResumeExecution: Boolean get() = !isExecuted && approvalState.canResumeToolExecution()

        /** Parse input string as JsonElement */
        fun inputAsJson(): JsonElement = runCatching {
            json.parseToJsonElement(input.ifBlank { "{}" })
        }.getOrElse { JsonObject(emptyMap()) }

        fun merge(other: Tool): Tool {
            return Tool(
                toolCallId = if (toolCallId.isBlank()) other.toolCallId else toolCallId,
                toolName = if (toolName.isBlank()) other.toolName else toolName,
                input = input + other.input,
                output = output + other.output,
                approvalState = approvalState,
                metadata = if (other.metadata != null) other.metadata else metadata,
                timing = timing ?: other.timing,
            )
        }
    }
}

/**
 * Sort message parts by type priority:
 * - Reasoning (-1): shown first
 * - Text, Tool, ToolCall, ToolResult, Search (0): middle
 * - Image, Video, Audio, Document (1): shown last
 *
 * WARNING: This function is intended for migration only.
 * Do not use for new messages as it may break the semantic order
 * when a message contains multiple Reasoning/Text parts.
 */
@Deprecated(
    message = "Only use for migration. May break semantic order for messages with multiple Reasoning/Text parts.",
    level = DeprecationLevel.WARNING
)
fun List<UIMessagePart>.toSortedMessageParts(): List<UIMessagePart> {
    // Skip sorting if multiple Reasoning or Text parts exist to preserve semantic order
    val reasoningCount = count { it is UIMessagePart.Reasoning }
    val textCount = count { it is UIMessagePart.Text }
    if (reasoningCount > 1 || textCount > 1) {
        return this
    }
    return sortedBy { part ->
        when (part) {
            is UIMessagePart.Reasoning -> -1
            is UIMessagePart.Text -> 0
            is UIMessagePart.Tool -> 0
            is UIMessagePart.ToolCall -> 0
            is UIMessagePart.ToolResult -> 0
            is UIMessagePart.Search -> 0
            is UIMessagePart.Image -> 1
            is UIMessagePart.Video -> 1
            is UIMessagePart.Audio -> 1
            is UIMessagePart.VoiceMessage -> 1
            is UIMessagePart.Document -> 1
        }
    }
}

fun UIMessage.finishReasoning(): UIMessage {
    return copy(
        parts = parts.map { part ->
            when (part) {
                is UIMessagePart.Reasoning -> {
                    if (part.finishedAt == null) {
                        part.copy(
                            finishedAt = Clock.System.now()
                        )
                    } else {
                        part
                    }
                }

                else -> part
            }
        }
    )
}

fun UIMessage.finishPendingTools(
    transform: (UIMessagePart.Tool) -> UIMessagePart.Tool
): UIMessage {
    val updatedParts = parts.map { part ->
        if (part is UIMessagePart.Tool && !part.isExecuted) {
            transform(part)
        } else {
            part
        }
    }

    if (updatedParts == parts) {
        return this
    }

    return copy(
        parts = updatedParts,
        finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    ).finishReasoning()
}

/**
 * Migrate legacy ToolCall parts to new Tool type within a single message.
 * This converts ToolCall parts to Tool parts with empty output.
 */
@Suppress("DEPRECATION")
private fun UIMessage.migrateToolParts(): UIMessage {
    val toolCalls = parts.filterIsInstance<UIMessagePart.ToolCall>()
    if (toolCalls.isEmpty()) {
        // Even if no ToolCall migration needed, ensure parts are sorted
        val sortedParts = parts.toSortedMessageParts()
        return if (sortedParts != parts) copy(parts = sortedParts) else this
    }

    val migratedParts = parts.map { part ->
        if (part is UIMessagePart.ToolCall) {
            UIMessagePart.Tool(
                toolCallId = part.toolCallId,
                toolName = part.toolName,
                input = part.arguments,
                output = emptyList(),
                approvalState = part.approvalState,
                metadata = part.metadata
            )
        } else {
            part
        }
    }
    return copy(parts = migratedParts.toSortedMessageParts())
}

/**
 * Migrate TOOL role messages into previous ASSISTANT messages by
 * merging ToolResult parts into corresponding Tool parts.
 * Returns the migrated list with TOOL messages removed.
 */
@Suppress("DEPRECATION")
fun List<UIMessage>.migrateToolMessages(): List<UIMessage> {
    val result = mutableListOf<UIMessage>()
    var i = 0

    while (i < size) {
        val message = this[i]

        // If this is a TOOL role message, merge its results into previous ASSISTANT message
        if (message.role == MessageRole.TOOL) {
            val toolResults = message.parts.filterIsInstance<UIMessagePart.ToolResult>()
            if (result.isNotEmpty() && result.last().role == MessageRole.ASSISTANT) {
                // Find the last ASSISTANT message and update its Tool parts with results
                val lastAssistant = result.removeAt(result.lastIndex)
                val updatedParts = lastAssistant.parts.map { part ->
                    if (part is UIMessagePart.Tool && !part.isExecuted) {
                        val matchingResult = toolResults.find { result -> result.toolCallId == part.toolCallId }
                        if (matchingResult != null) {
                            part.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(matchingResult.content)
                                    )
                                )
                            )
                        } else {
                            part
                        }
                    } else if (part is UIMessagePart.ToolCall) {
                        // Also handle legacy ToolCall parts
                        val matchingResult = toolResults.find { result -> result.toolCallId == part.toolCallId }
                        if (matchingResult != null) {
                            UIMessagePart.Tool(
                                toolCallId = part.toolCallId,
                                toolName = part.toolName,
                                input = part.arguments,
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(matchingResult.content)
                                    )
                                ),
                                approvalState = part.approvalState,
                                metadata = part.metadata
                            )
                        } else {
                            UIMessagePart.Tool(
                                toolCallId = part.toolCallId,
                                toolName = part.toolName,
                                input = part.arguments,
                                output = emptyList(),
                                approvalState = part.approvalState,
                                metadata = part.metadata
                            )
                        }
                    } else {
                        part
                    }
                }
                result.add(lastAssistant.copy(parts = updatedParts.toSortedMessageParts()))
            }
            // Skip the TOOL message (don't add it to result)
            i++
            continue
        }

        // For other messages, migrate their tool parts first
        result.add(message.migrateToolParts())
        i++
    }

    return result
}

/**
 * Migrate legacy TOOL role messages at the MessageNode level.
 * This handles the case where TOOL messages are stored in separate MessageNodes
 * by merging ToolResult parts into the previous ASSISTANT node's Tool parts.
 *
 * @param MessageNode A container holding one or more UIMessages for branching.
 * @return Migrated list with TOOL nodes removed and their results merged into ASSISTANT nodes.
 */
@Suppress("DEPRECATION")
fun <T> List<T>.migrateToolNodes(
    getMessages: (T) -> List<UIMessage>,
    setMessages: (T, List<UIMessage>) -> T
): List<T> {
    val result = mutableListOf<T>()
    var i = 0

    while (i < size) {
        val node = this[i]
        val messages = getMessages(node)

        // Check if this node contains TOOL role messages
        val isToolNode = messages.any { it.role == MessageRole.TOOL }

        if (isToolNode && result.isNotEmpty()) {
            // Find the previous ASSISTANT node
            val lastIndex = result.lastIndex
            val lastNode = result[lastIndex]
            val lastMessages = getMessages(lastNode)
            val isAssistantNode = lastMessages.any { it.role == MessageRole.ASSISTANT }

            if (isAssistantNode) {
                // Collect all ToolResults from the TOOL node
                val toolResults = messages.flatMap { msg ->
                    msg.parts.filterIsInstance<UIMessagePart.ToolResult>()
                }

                // Update the ASSISTANT node's messages by merging ToolResults
                val updatedMessages = lastMessages.map { assistantMsg ->
                    if (assistantMsg.role != MessageRole.ASSISTANT) return@map assistantMsg

                    val updatedParts = assistantMsg.parts.map { part ->
                        when (part) {
                            is UIMessagePart.Tool -> {
                                if (!part.isExecuted) {
                                    val matchingResult = toolResults.find { it.toolCallId == part.toolCallId }
                                    if (matchingResult != null) {
                                        part.copy(
                                            output = listOf(
                                                UIMessagePart.Text(
                                                    json.encodeToString(matchingResult.content)
                                                )
                                            )
                                        )
                                    } else part
                                } else part
                            }

                            is UIMessagePart.ToolCall -> {
                                val matchingResult = toolResults.find { it.toolCallId == part.toolCallId }
                                if (matchingResult != null) {
                                    UIMessagePart.Tool(
                                        toolCallId = part.toolCallId,
                                        toolName = part.toolName,
                                        input = part.arguments,
                                        output = listOf(
                                            UIMessagePart.Text(
                                                json.encodeToString(matchingResult.content)
                                            )
                                        ),
                                        approvalState = part.approvalState,
                                        metadata = part.metadata
                                    )
                                } else {
                                    UIMessagePart.Tool(
                                        toolCallId = part.toolCallId,
                                        toolName = part.toolName,
                                        input = part.arguments,
                                        output = emptyList(),
                                        approvalState = part.approvalState,
                                        metadata = part.metadata
                                    )
                                }
                            }

                            else -> part
                        }
                    }
                    assistantMsg.copy(parts = updatedParts.toSortedMessageParts())
                }

                result[lastIndex] = setMessages(lastNode, updatedMessages)
                // Skip the TOOL node (don't add it to result)
                i++
                continue
            }
        }

        // For non-TOOL nodes, migrate their internal tool parts
        val migratedMessages = messages.migrateToolMessages()
        result.add(setMessages(node, migratedMessages))
        i++
    }

    return result
}

@Serializable
data class CallTranscriptEntry(
    val role: String,
    val speaker: String,
    val text: String,
    val timestampMillis: Long,
)

@Serializable
sealed class UIMessageAnnotation {
    /** Durable chat interaction; text parts carry a readable model/backup fallback. */
    @Serializable
    @SerialName("chat_interaction")
    data class ChatInteraction(
        val id: String,
        val conversationId: String,
        val assistantId: String,
        val actor: String,
        val kind: String,
        val status: String = "pending",
        val amountCents: Long = 0,
        val note: String = "",
        val theme: String = "ice",
        val walletLinked: Boolean = false,
        val createdAtMillis: Long = 0,
        val pendingAction: String? = null,
        val connectedAtMillis: Long? = null,
        val endedAtMillis: Long? = null,
        val error: String? = null,
        // Null marks legacy calls whose words must be recovered from their time window.
        val callTranscript: List<CallTranscriptEntry>? = null,
        // 视频通话结束汇总："小心心 ×2 · 花束 ×1"；语音通话为空。
        val giftSummary: String = "",
        // 这一通是不是视频通话（纯语音通话保持原显示，不受通话卡收敛影响）。
        val videoCall: Boolean = false,
        /**
         * 这通视频通话覆盖的持久消息 id（逐条文字气泡在聊天页里被隐藏，但原文仍在
         * `Conversation.messagesForGeneration` 里，模型照常读到一次）。
         *
         * 默认空列表是**故意的**：老数据没有关联时一律"不猜、不隐藏"，
         * 用户至少还能看到原来的逐条消息；只有本字段真的写进来了才做显示收敛。
         */
        val videoCallMessageIds: List<String> = emptyList(),
    ) : UIMessageAnnotation()

    /**
     * Purely virtual video-call gift record. It never carries money, wallet ids or payment state:
     * the gift itself is only a tag-driven visual event inside an ongoing video call.
     */
    @Serializable
    @SerialName("video_call_gift")
    data class VideoCallGift(
        val id: String,
        val giftId: String,
        val giftName: String = "",
        val conversationId: String = "",
        val turnMessageId: String = "",
        val createdAtMillis: Long = 0,
    ) : UIMessageAnnotation()

    /** Preserve sibling-fork backup metadata without turning it into a citation or model input. */
    @Serializable
    @SerialName("avatar_interaction")
    data class AvatarInteraction(
        val actor: String,
        val target: String,
        val targetMessageId: String? = null,
    ) : UIMessageAnnotation()

    @Serializable
    @SerialName("url_citation")
    data class UrlCitation(
        val title: String,
        val url: String
    ) : UIMessageAnnotation()
}

@Serializable
data class MessageChunk(
    val id: String,
    val model: String,
    val choices: List<UIMessageChoice>,
    val usage: TokenUsage? = null,
    // Used only by providers emitting complete snapshots of their current request's output.
    val replaceContent: Boolean = false,
    // Snapshot providers replace only their previous emission, preserving earlier tool rounds.
    val replacedParts: Int = 0,
    val replacedAnnotations: Int = 0,
)

@Serializable
data class UIMessageChoice(
    val index: Int,
    val delta: UIMessage?,
    val message: UIMessage?,
    val finishReason: String?
)
