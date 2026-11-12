package me.rerere.rikkahub.ui.pages.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.plugin.localdata.LocalChatMessageDto
import me.rerere.rikkahub.plugin.localdata.LocalChatSnapshotDto
import me.rerere.rikkahub.plugin.localdata.PostVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MomentsChatShareTest {
    @Test
    fun onlySelectedUserAssistantTextIsKept() {
        val result = buildMomentsChatSnapshot(
            title = "标题",
            messages = listOf(
                message(MessageRole.SYSTEM, UIMessagePart.Text("系统提示词")),
                message(
                    MessageRole.USER,
                    UIMessagePart.Text("你好"),
                    UIMessagePart.Reasoning("思考过程"),
                    UIMessagePart.Image("file:///not-included.png"),
                ),
                message(
                    MessageRole.ASSISTANT,
                    UIMessagePart.Text("你好呀"),
                    UIMessagePart.Tool("call-1", "search", "{}"),
                ),
                message(MessageRole.TOOL, UIMessagePart.Text("工具原始结果")),
            ),
            userName = "小明",
            assistantName = "小助手",
            includeImages = false,
            now = 123L,
        )

        assertTrue(result.problems.toString(), result.canWrite)
        val snapshot = requireNotNull(result.snapshot)
        assertEquals(listOf("user", "assistant"), snapshot.messages.map { it.role })
        assertEquals(listOf("你好", "你好呀"), snapshot.messages.map { it.text })
        assertEquals(listOf("小明", "小助手"), snapshot.messages.map { it.name })
        assertEquals(123L, snapshot.capturedAt ?: -1L)
        assertEquals(2, result.excludedMessages)
        assertEquals(1, result.omittedImages)
        assertTrue(result.excludedParts >= 2)
    }

    @Test
    fun imagesAreOptInAndAttachedToTheReferencingMessage() {
        val messages = listOf(
            message(
                MessageRole.USER,
                UIMessagePart.Text("两张图"),
                UIMessagePart.Image("file:///a.png"),
                UIMessagePart.Image("data:image/png;base64,AA=="),
            ),
            message(MessageRole.ASSISTANT, UIMessagePart.Text("收到")),
        )
        val withoutImages = buildMomentsChatSnapshot(
            title = "标题",
            messages = messages,
            userName = "小明",
            assistantName = "小助手",
            includeImages = false,
            now = 1L,
        )
        assertEquals(0, withoutImages.images.size)
        assertEquals(2, withoutImages.omittedImages)
        assertTrue(withoutImages.snapshot!!.messages.all { it.media.isEmpty() })

        val withImages = buildMomentsChatSnapshot(
            title = "标题",
            messages = messages,
            userName = "小明",
            assistantName = "小助手",
            includeImages = true,
            now = 1L,
        )
        assertEquals(2, withImages.images.size)
        val attached = attachMomentsMedia(
            snapshot = requireNotNull(withImages.snapshot),
            images = withImages.images,
            resourceIds = listOf("ldm_12345678", "ldm_abcdefgh"),
        )
        assertEquals(listOf("ldm_12345678", "ldm_abcdefgh"), attached.messages[0].media)
        assertEquals(emptyList<String>(), attached.messages[1].media)
    }

    @Test
    fun overMessageLimitIsRejectedWithoutASnapshot() {
        val result = buildMomentsChatSnapshot(
            title = "标题",
            messages = (0 until 51).map { message(MessageRole.USER, UIMessagePart.Text("m$it")) },
            userName = "小明",
            assistantName = "小助手",
            includeImages = false,
            now = 1L,
        )

        assertFalse(result.canWrite)
        assertNull(result.snapshot)
        assertTrue(result.problems.any { it.contains("50") })
    }

    @Test
    fun tooManyImagesAreRejected() {
        val result = buildMomentsChatSnapshot(
            title = "标题",
            messages = listOf(
                message(
                    MessageRole.USER,
                    *Array<UIMessagePart>(10) { UIMessagePart.Image("file:///$it.png") },
                ),
            ),
            userName = "小明",
            assistantName = "小助手",
            includeImages = true,
            now = 1L,
        )

        assertFalse(result.canWrite)
        assertNull(result.snapshot)
        assertTrue(result.problems.any { it.contains("9") })
    }

    @Test
    fun overLongSingleMessageIsRejectedWithoutTruncation() {
        val result = buildMomentsChatSnapshot(
            title = "标题",
            messages = listOf(message(MessageRole.USER, UIMessagePart.Text("a".repeat(2_001)))),
            userName = "小明",
            assistantName = "小助手",
            includeImages = false,
            now = 1L,
        )

        assertFalse(result.canWrite)
        assertNull(result.snapshot)
        assertTrue(result.problems.any { it.contains("2000") })
    }

    @Test
    fun encodedSnapshotByteBudgetIsEnforced() {
        // 50 x 400 quotes = 20,000 chars, but JSON escaping pushes the snapshot over 32 KB.
        val result = buildMomentsChatSnapshot(
            title = "标题",
            messages = (0 until 50).map {
                message(MessageRole.USER, UIMessagePart.Text("\"".repeat(400)))
            },
            userName = "小明",
            assistantName = "小助手",
            includeImages = false,
            now = 1L,
        )

        assertFalse(result.canWrite)
        assertNull(result.snapshot)
        assertTrue(result.problems.any { it.contains("32 KB") })
    }

    @Test
    fun writeRequestCarriesDraftMethodVisibilityAndSnapshot() {
        val snapshot = LocalChatSnapshotDto(
            title = "标题",
            messages = listOf(
                LocalChatMessageDto(role = "user", name = "小明", text = "你好"),
            ),
            capturedAt = 1L,
        )
        val request = buildMomentsWriteRequest(
            method = "moments.draft.save",
            visibility = PostVisibility.SELECTED,
            selectedAssistantIds = listOf("assistant-1"),
            snapshot = snapshot,
        )
        val root = Json.parseToJsonElement(request).jsonObject
        assertEquals("moments.draft.save", root["method"]!!.jsonPrimitive.content)
        val params = root["params"]!!.jsonObject
        assertEquals("selected", params["visibility"]!!.jsonPrimitive.content)
        assertEquals(1, params["selectedAssistantIds"]!!.jsonArray.size)
        assertTrue(params.containsKey("chatSnapshot"))
    }

    @Test
    fun participantAvatarsUseEmojiOrControlledMediaReferencesOnly() {
        val result = buildMomentsChatSnapshot(
            title = "标题",
            messages = listOf(
                message(MessageRole.USER, UIMessagePart.Text("你好")),
                message(MessageRole.ASSISTANT, UIMessagePart.Text("你好呀")),
            ),
            userName = "小明",
            assistantName = "小助手",
            includeImages = false,
            now = 1L,
            userAvatar = Avatar.Emoji("🙂"),
            assistantAvatar = Avatar.Image("file:///avatar.png"),
        )

        assertTrue(result.problems.toString(), result.canWrite)
        val snapshot = requireNotNull(result.snapshot)
        assertEquals("🙂", snapshot.messages[0].avatarEmoji)
        assertNull(snapshot.messages[0].avatarMediaId)
        assertNull(snapshot.messages[1].avatarEmoji)
        assertEquals(listOf(MessageRole.ASSISTANT), result.avatarImages.map { it.role })

        val attached = attachMomentsAvatars(
            snapshot,
            userAvatarMediaId = null,
            assistantAvatarMediaId = "ldm_abcdefgh",
        )
        assertEquals("ldm_abcdefgh", attached.messages[1].avatarMediaId)
        assertNull(attached.messages[0].avatarMediaId)
        assertEquals("🙂", attached.messages[0].avatarEmoji)
    }

    @Test
    fun defaultsCarryNoAvatarFields() {
        val result = buildMomentsChatSnapshot(
            title = "标题",
            messages = listOf(message(MessageRole.USER, UIMessagePart.Text("你好"))),
            userName = "小明",
            assistantName = "小助手",
            includeImages = false,
            now = 1L,
        )
        val snapshot = requireNotNull(result.snapshot)
        assertTrue(result.avatarImages.isEmpty())
        assertNull(snapshot.messages.single().avatarMediaId)
        assertNull(snapshot.messages.single().avatarEmoji)
    }

    private fun message(role: MessageRole, vararg parts: UIMessagePart): UIMessage =
        UIMessage(role = role, parts = parts.toList())
}
