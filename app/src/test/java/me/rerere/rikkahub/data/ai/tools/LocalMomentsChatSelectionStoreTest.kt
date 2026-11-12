/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.plugin.localdata.LocalDataApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the host-owned scope of the "assistant organizes a Moments draft" selection:
 * one shot per conversation/assistant/user anchor, never all-private-chats by default, and the
 * approval preview must be built from the trusted messages rather than the model's id list.
 */
class LocalMomentsChatSelectionStoreTest {
    private val conversationId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val otherConversationId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val assistantId = "11111111-1111-1111-1111-111111111111"
    private val otherAssistantId = "22222222-2222-2222-2222-222222222222"

    private val anchor = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
    private val otherUserMessage = "ffffffff-ffff-4fff-8fff-ffffffffffff"

    private val user1 = "dddddddd-dddd-4ddd-8ddd-000000000001"
    private val assistant1 = "dddddddd-dddd-4ddd-8ddd-000000000002"
    private val user2 = "dddddddd-dddd-4ddd-8ddd-000000000003"
    private val system1 = "dddddddd-dddd-4ddd-8ddd-000000000004"

    @Before
    fun setUp() {
        LocalMomentsChatSelectionStore.clear()
    }

    @After
    fun tearDown() {
        LocalMomentsChatSelectionStore.clear()
    }

    // ------------------------------------------------------------------ prepare

    @Test
    fun `prepare rejects empty or non conversation messages`() {
        val messages = listOf(message(user1, MessageRole.USER, UIMessagePart.Text("a")))

        val empty = LocalMomentsChatSelectionStore.prepare(selection(messages, emptyList()))
        assertTrue(empty is LocalMomentsChatSelectionPrepareResult.Rejected)
        assertTrue((empty as LocalMomentsChatSelectionPrepareResult.Rejected).message.contains("请先选择"))

        val stale = LocalMomentsChatSelectionStore.prepare(selection(messages, listOf(otherUserMessage)))
        assertTrue(stale is LocalMomentsChatSelectionPrepareResult.Rejected)
        assertTrue((stale as LocalMomentsChatSelectionPrepareResult.Rejected).message.contains("已不在当前会话"))
    }

    @Test
    fun `prepare only accepts user and assistant messages`() {
        val messages = listOf(
            message(system1, MessageRole.SYSTEM, UIMessagePart.Text("系统提示词")),
            message(user1, MessageRole.USER, UIMessagePart.Text("可见正文")),
        )

        val rejected = LocalMomentsChatSelectionStore.prepare(selection(messages, listOf(system1, user1)))

        assertTrue(rejected is LocalMomentsChatSelectionPrepareResult.Rejected)
        assertTrue((rejected as LocalMomentsChatSelectionPrepareResult.Rejected).message.contains("只能整理"))
    }

    @Test
    fun `prepare enforces the fifty message card limit`() {
        val messages = (0 until LocalDataApi.MAX_CHAT_MESSAGES + 1).map { index ->
            message(messageId(index), MessageRole.USER, UIMessagePart.Text("m$index"))
        }

        val rejected = LocalMomentsChatSelectionStore.prepare(
            selection(messages, messages.map { it.id.toString() }),
        )

        assertTrue(rejected is LocalMomentsChatSelectionPrepareResult.Rejected)
        assertTrue((rejected as LocalMomentsChatSelectionPrepareResult.Rejected).message.contains("最多"))
    }

    // --------------------------------------------------------------------- scope

    @Test
    fun `turn is bound to conversation assistant and the newest user message`() {
        val messages = listOf(
            message(user1, MessageRole.USER, UIMessagePart.Text("第一条")),
            message(assistant1, MessageRole.ASSISTANT, UIMessagePart.Text("第二条")),
        )
        assertTrue(
            LocalMomentsChatSelectionStore.prepare(selection(messages, listOf(user1, assistant1)))
                is LocalMomentsChatSelectionPrepareResult.Prepared,
        )

        // The instruction message is still queued, so the selection waits instead of leaking.
        assertNull(LocalMomentsChatSelectionStore.turnFor(conversationId, assistantId, messages))
        assertNotNull(LocalMomentsChatSelectionStore.current(conversationId))

        val dispatched = messages + message(anchor, MessageRole.USER, UIMessagePart.Text("让助手整理"))
        assertNotNull(
            LocalMomentsChatSelectionStore.turnFor(conversationId, assistantId, dispatched),
        )
        assertNull(
            LocalMomentsChatSelectionStore.turnFor(conversationId, otherAssistantId, dispatched),
        )
        assertNull(
            LocalMomentsChatSelectionStore.turnFor(otherConversationId, assistantId, dispatched),
        )

        // A newer user message spends the one-shot selection.
        val followed = dispatched + message(otherUserMessage, MessageRole.USER, UIMessagePart.Text("下一轮"))
        assertNull(LocalMomentsChatSelectionStore.turnFor(conversationId, assistantId, followed))
        assertNull(LocalMomentsChatSelectionStore.current())
        assertNull(LocalMomentsChatSelectionStore.turnFor(conversationId, assistantId, dispatched))
    }

    @Test
    fun `turn survives the approval resume and carries only the explicit selection`() {
        val messages = listOf(
            message(user1, MessageRole.USER, UIMessagePart.Text("第一条")),
            message(assistant1, MessageRole.ASSISTANT, UIMessagePart.Text("第二条")),
            message(user2, MessageRole.USER, UIMessagePart.Text("未选中")),
            message(anchor, MessageRole.USER, UIMessagePart.Text("让助手整理")),
        )
        LocalMomentsChatSelectionStore.prepare(selection(messages, listOf(user1)))

        // The approval resume asks again while the same user message is still the newest one.
        val turn = checkNotNull(
            LocalMomentsChatSelectionStore.turnFor(conversationId, assistantId, messages),
        )
        val resumed = LocalMomentsChatSelectionStore.turnFor(conversationId, assistantId, messages)

        assertNotNull(resumed)
        assertEquals(listOf(user1), turn.selectedMessageIds.toList())
        assertEquals(conversationId, turn.conversationId)
        assertEquals(assistantId, turn.assistantId)
        assertEquals(4, turn.messages.size)
    }

    @Test
    fun `clear is scoped to one conversation`() {
        val messages = listOf(message(user1, MessageRole.USER, UIMessagePart.Text("a")))
        LocalMomentsChatSelectionStore.prepare(selection(messages, listOf(user1)))

        LocalMomentsChatSelectionStore.clear(otherConversationId)
        assertNotNull(LocalMomentsChatSelectionStore.current(conversationId))

        LocalMomentsChatSelectionStore.clearIfAnchor(conversationId, otherUserMessage)
        assertNotNull(LocalMomentsChatSelectionStore.current(conversationId))

        LocalMomentsChatSelectionStore.clearIfAnchor(conversationId, anchor)
        assertNull(LocalMomentsChatSelectionStore.current(conversationId))
    }

    // ------------------------------------------------------------------- preview

    @Test
    fun `preview renders the host messages in conversation order and filters non body parts`() {
        val messages = listOf(
            message(system1, MessageRole.SYSTEM, UIMessagePart.Text("系统提示词")),
            message(
                user1,
                MessageRole.USER,
                UIMessagePart.Text("用户正文"),
                UIMessagePart.Reasoning("思考内容"),
                UIMessagePart.Document("file:///secret.pdf", "secret.pdf"),
            ),
            message(assistant1, MessageRole.ASSISTANT, UIMessagePart.Text("助手正文")),
            message(user2, MessageRole.USER, UIMessagePart.Text("未选中")),
        )
        LocalMomentsChatSelectionStore.prepare(selection(messages, listOf(assistant1, user1)))

        val preview = LocalMomentsChatSelectionStore.preview(
            conversationId,
            arguments(assistant1, user1),
        )

        assertTrue(preview.available)
        assertTrue(preview.canWrite)
        assertEquals(listOf("用户正文", "助手正文"), preview.previewMessages.map { it.text })
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), preview.previewMessages.map { it.role })
        assertTrue(preview.excludedParts >= 2)
        assertFalse(preview.previewMessages.any { it.text.contains("思考内容") })
        assertFalse(preview.previewMessages.any { it.text.contains("secret.pdf") })
        assertFalse(preview.previewMessages.any { it.text.contains("未选中") })
    }

    @Test
    fun `preview refuses a tool id outside the user selection`() {
        val messages = listOf(
            message(user1, MessageRole.USER, UIMessagePart.Text("已选中")),
            message(user2, MessageRole.USER, UIMessagePart.Text("未选中")),
        )
        LocalMomentsChatSelectionStore.prepare(selection(messages, listOf(user1)))

        val preview = LocalMomentsChatSelectionStore.preview(conversationId, arguments(user1, user2))

        assertFalse(preview.available)
        assertTrue(preview.unavailableReason.orEmpty().contains("不在你本次选中"))
        assertTrue(preview.previewMessages.isEmpty())
    }

    @Test
    fun `preview has no content once the selection is cleared`() {
        val preview = LocalMomentsChatSelectionStore.preview(conversationId, arguments(user1))

        assertFalse(preview.available)
        assertTrue(preview.unavailableReason.orEmpty().isNotBlank())
    }

    @Test
    fun `preview refuses to resolve without a conversation id`() {
        val messages = listOf(message(user1, MessageRole.USER, UIMessagePart.Text("a")))
        LocalMomentsChatSelectionStore.prepare(selection(messages, listOf(user1)))

        val preview = LocalMomentsChatSelectionStore.preview(null, arguments(user1))

        assertFalse(preview.available)
        assertTrue(preview.previewMessages.isEmpty())
    }

    @Test
    fun `preview keeps all fifty messages complete`() {
        val messages = (0 until LocalDataApi.MAX_CHAT_MESSAGES).map { index ->
            message(messageId(index), MessageRole.USER, UIMessagePart.Text("z".repeat(100) + index))
        }
        LocalMomentsChatSelectionStore.prepare(selection(messages, messages.map { it.id.toString() }))

        val preview = LocalMomentsChatSelectionStore.preview(
            conversationId,
            arguments(*messages.map { it.id.toString() }.toTypedArray()),
        )

        assertTrue(preview.available)
        assertEquals(LocalDataApi.MAX_CHAT_MESSAGES, preview.previewMessages.size)
        assertEquals(
            ("z".repeat(100) + (LocalDataApi.MAX_CHAT_MESSAGES - 1)).length,
            preview.previewMessages.last().text.length,
        )
        assertTrue(preview.problems.isEmpty())
    }

    @Test
    fun `preview surfaces the real thirty two kb snapshot budget`() {
        val messages = (0 until 10).map { index ->
            message(messageId(index), MessageRole.USER, UIMessagePart.Text("汉".repeat(2_000)))
        }
        LocalMomentsChatSelectionStore.prepare(selection(messages, messages.map { it.id.toString() }))

        val preview = LocalMomentsChatSelectionStore.preview(
            conversationId,
            arguments(*messages.map { it.id.toString() }.toTypedArray()),
        )

        assertTrue(preview.available)
        assertEquals(10, preview.previewMessages.size)
        assertTrue(preview.problems.any { it.contains("32") })
        assertFalse(preview.canWrite)
    }

    @Test
    fun `preview hides images when the host permission is off`() {
        val messages = listOf(
            message(
                user1,
                MessageRole.USER,
                UIMessagePart.Text("看图"),
                UIMessagePart.Image("file:///a.png"),
            ),
        )
        LocalMomentsChatSelectionStore.prepare(
            selection(messages, listOf(user1)).copy(
                includeImagesRequested = true,
                includeImages = false,
            ),
        )

        val preview = LocalMomentsChatSelectionStore.preview(conversationId, arguments(user1))

        assertTrue(preview.available)
        assertTrue(preview.previewMessages.all { it.imageUrls.isEmpty() })
        assertEquals(1, preview.omittedImages)
        assertFalse(preview.includeImages)
    }

    @Test
    fun `preview can show images when the host permission is on`() {
        val messages = listOf(
            message(
                user1,
                MessageRole.USER,
                UIMessagePart.Text("看图"),
                UIMessagePart.Image("file:///a.png"),
            ),
        )
        LocalMomentsChatSelectionStore.prepare(
            selection(messages, listOf(user1)).copy(
                includeImagesRequested = true,
                includeImages = true,
            ),
        )

        val preview = LocalMomentsChatSelectionStore.preview(conversationId, arguments(user1))

        assertTrue(preview.available)
        assertEquals(1, preview.imageCount)
        assertEquals(1, preview.previewMessages.first().imageUrls.size)
        assertEquals(0, preview.omittedImages)
    }

    // --------------------------------------------------------------- instruction

    @Test
    fun `instruction names the native tool and never claims publishing`() {
        val text = LocalMomentsChatDraftInstruction.text(messageCount = 3, includeImages = false)

        assertTrue(text.contains(LocalMomentsChatDraftContract.TOOL_NAME))
        assertTrue(text.contains("不要发布"))
        assertTrue(text.contains("不要声称已经发布"))
        assertTrue(text.contains("草稿作者是我本人"))
        assertTrue(text.contains("3 条"))
        assertFalse(text.contains("已发布。"))
    }

    @Test
    fun `draft author stays the local user`() {
        val messages = listOf(message(user1, MessageRole.USER, UIMessagePart.Text("a")))
        LocalMomentsChatSelectionStore.prepare(selection(messages, listOf(user1)))

        val turn = checkNotNull(
            LocalMomentsChatSelectionStore.turnFor(
                conversationId,
                assistantId,
                messages + message(anchor, MessageRole.USER, UIMessagePart.Text("让助手整理")),
            ),
        )

        assertEquals(
            me.rerere.rikkahub.plugin.localdata.PostVisibility.SELF,
            turn.visibility,
        )
        assertTrue(LocalMomentsChatDraftPreview.AUTHOR_NOTE.contains("本机用户"))
        assertTrue(LocalMomentsChatDraftPreview.AUTHOR_NOTE.contains("由助手整理"))
    }

    // ------------------------------------------------------------------- helpers

    private fun selection(
        messages: List<UIMessage>,
        selected: List<String>,
    ): LocalMomentsChatSelection = LocalMomentsChatSelection(
        conversationId = conversationId,
        assistantId = assistantId,
        anchorMessageId = anchor,
        title = "测试会话",
        messages = messages,
        selectedMessageIds = selected,
        includeImagesRequested = false,
        includeImages = false,
        identity = LocalMomentsChatDraftIdentity("小明", "小助手"),
    )

    private fun message(
        id: String,
        role: MessageRole,
        vararg parts: UIMessagePart,
    ): UIMessage = UIMessage(id = Uuid.parse(id), role = role, parts = parts.toList())

    private fun messageId(index: Int): String =
        "dddddddd-dddd-4ddd-8ddd-" + index.toString().padStart(12, '0')

    private fun arguments(vararg ids: String) = buildJsonObject {
        put("messageIds", JsonArray(ids.map { JsonPrimitive(it) }))
    }
}
