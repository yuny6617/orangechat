/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.app.Application
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

/**
 * Regression coverage for R2-1: a proactive run records the IDs that the durable commit actually
 * stores, not the request-local streaming IDs that never reach Room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ProactiveMessageCommitRecordTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun assistantMessage(vararg parts: UIMessagePart) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = parts.toList(),
    )

    /**
     * Mirrors [ProactiveTaskExecutor.prepareCommit]: just before the Room transaction every message
     * ID is replaced by `proactiveCommitMessageId(taskId, index)`; the original streaming IDs are
     * thrown away. The conversation therefore contains only the deterministic IDs.
     */
    private fun conversationFromCommit(taskId: String, generated: List<UIMessage>): Conversation {
        val committed = generated.mapIndexed { index, message ->
            message.copy(id = Uuid.parse(proactiveCommitMessageId(taskId, index)))
        }
        return Conversation(
            assistantId = Uuid.random(),
            messageNodes = committed.map { it.toMessageNode() },
        )
    }

    private fun persistedMessageIds(conversation: Conversation): Set<String> =
        conversation.messageNodes.flatMap { node -> node.messages }.map { it.id.toString() }.toSet()

    @Test
    fun `recordSent keeps exactly the deterministic ids that exist in the committed conversation`() {
        val taskId = Uuid.random().toString()
        val scope = "commit-${Uuid.random()}"
        val generated = listOf(
            assistantMessage(
                UIMessagePart.Reasoning("先看看她是不是在忙"),
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "lookup",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("忙碌")),
                ),
                UIMessagePart.Text("刚才想问问你在干嘛"),
            ),
            assistantMessage(UIMessagePart.Text("早点休息呀")),
        )
        val conversation = conversationFromCommit(taskId, generated)
        val persistedIds = persistedMessageIds(conversation)

        val store = ProactiveMessageStateStore(context, scope)
        val recorded = store.recordSent(
            state = store.synchronizeWithUser("user-anchor"),
            messageIds = proactiveCommittedMessageIds(taskId, generated.size),
            text = "早点休息呀",
        )

        // The state store must point at real persisted messages so the next run can sanitize them.
        assertEquals(persistedIds, recorded.recentProactiveMessageIds)
        assertEquals(
            persistedIds,
            ProactiveMessageStateStore(context, scope)
                .synchronizeWithUser("user-anchor")
                .recentProactiveMessageIds,
        )
        // Streaming IDs are request-local: recording them (the R2-1 bug) produces ghost IDs.
        val streamingIds = generated.map { it.id.toString() }.toSet()
        assertTrue(recorded.recentProactiveMessageIds.intersect(streamingIds).isEmpty())
    }

    @Test
    fun `next run sanitizes committed proactive messages down to visible text`() {
        val taskId = Uuid.random().toString()
        val scope = "sanitize-${Uuid.random()}"
        val generated = listOf(
            assistantMessage(
                UIMessagePart.Reasoning("这段话不该进入下一次请求"),
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "lookup",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("原始工具输出")),
                ),
                UIMessagePart.Text(" 最终正文 "),
            ),
            assistantMessage(UIMessagePart.Text("第二句正文")),
        )
        val conversation = conversationFromCommit(taskId, generated)
        val store = ProactiveMessageStateStore(context, scope)
        store.recordSent(
            state = store.synchronizeWithUser("user-anchor"),
            messageIds = proactiveCommittedMessageIds(taskId, generated.size),
            text = "第二句正文",
        )
        // What the following wake-up sees: state reloaded from preferences plus the stored history.
        val nextState = ProactiveMessageStateStore(context, scope).synchronizeWithUser("user-anchor")
        val history = conversation.currentMessages
        assertTrue(
            "fixture must contain reasoning/tool parts",
            history.any { message -> message.parts.any { it is UIMessagePart.Reasoning || it is UIMessagePart.Tool } },
        )

        val sanitized = sanitizeProactiveHistory(history, nextState.recentProactiveMessageIds)

        assertEquals(history.size, sanitized.size)
        sanitized.forEach { message ->
            assertTrue(
                "only visible text may remain: ${message.parts}",
                message.parts.all { it is UIMessagePart.Text },
            )
        }
        assertEquals(listOf("最终正文", "第二句正文"), sanitized.map { it.toText() })
        assertFalse(sanitized.any { message -> message.parts.any { it is UIMessagePart.Tool } })
        assertFalse(sanitized.any { message -> message.parts.any { it is UIMessagePart.Reasoning } })
    }
}
