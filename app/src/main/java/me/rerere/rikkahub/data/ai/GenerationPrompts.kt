/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalDate

/**
 * 系统提示词的"主文"：助手自带的 systemPrompt，或者（开关打开时）对话级重写。
 *
 * `allowConversationSystemPrompt = false`（默认值）时对话级 prompt 一律不生效 —— 这是既有语义，
 * 本函数保持它不变。**本轮追加的通话上下文不能走这条路**：它带着"本轮有没有画面/音频"和礼物能力清单，
 * 被那个开关整段丢掉时，模型就会回答"我没有 ws_gift 这个工具"（P9 的真实原因之一）。
 * 追加由 [appendTurnSystemContext] 负责，永远排在主文后面。
 */
internal fun composeSystemPromptText(
    assistantSystemPrompt: String,
    conversationSystemPrompt: String?,
    allowConversationSystemPrompt: Boolean,
): String = if (allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
    conversationSystemPrompt
} else {
    assistantSystemPrompt
}

/** 把"只在这一轮有效"的通话上下文追加在主文后面（不替换主文）。 */
internal fun appendTurnSystemContext(base: String, appendedSystemContext: String?): String {
    if (appendedSystemContext.isNullOrBlank()) return base
    return buildString {
        if (base.isNotBlank()) {
            append(base)
            appendLine()
            appendLine()
        }
        append(appendedSystemContext)
    }
}

internal fun buildMemoryPrompt(memories: List<AssistantMemory>) =
    buildString {
        appendLine()
        append("**Memories**")
        appendLine()
        append("These are memories stored via the memory_tool that you can reference in future conversations.")
        appendLine()
        val json = buildJsonArray {
            memories.forEach { memory ->
                add(buildJsonObject {
                    put("id", memory.id)
                    put("content", memory.content)
                })
            }
        }
        append(JsonInstantPretty.encodeToString(json))
        appendLine()
    }

internal suspend fun buildRecentChatsPrompt(
    assistant: Assistant,
    conversationRepo: ConversationRepository
): String {
    val recentConversations = conversationRepo.getRecentConversationHeaders(
        assistantId = assistant.id,
        limit = 10,
    )
    if (recentConversations.isNotEmpty()) {
        return buildString {
            appendLine()
            append("**Recent Chats**")
            appendLine()
            append("These are some of the user's recent conversations. You can use them to understand user preferences:")
            appendLine()
            val json = buildJsonArray {
                recentConversations.forEach { conversation ->
                    add(buildJsonObject {
                        put("title", conversation.title)
                        put("last_chat", java.time.Instant.ofEpochMilli(conversation.updateAt).toLocalDate())
                    })
                }
            }
            append(JsonInstantPretty.encodeToString(json))
            appendLine()
        }
    }
    return ""
}
