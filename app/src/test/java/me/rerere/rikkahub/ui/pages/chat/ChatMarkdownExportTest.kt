package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class ChatMarkdownExportTest {
    private fun conversation() = Conversation(
        assistantId = Uuid.random(),
        title = "Long chat",
        messageNodes = emptyList(),
    )

    @Test fun markdownStreamsAllSelectedMessagesAndCountsUnreadableImages() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.cacheDir, "markdown-test-${System.nanoTime()}")
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Text("first **markdown**"),
                    UIMessagePart.Image("content://missing/image"),
                ),
            ),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning("hidden reasoning"),
                    UIMessagePart.Text("second " + "正文".repeat(10_000)),
                ),
            ),
        )
        try {
            val result = exportChatMarkdown(
                context = context,
                conversation = conversation(),
                messages = messages,
                includeReasoning = false,
                outputDir = dir,
                imageMarkdown = { null },
            )
            assertEquals(2, result.messageCount)
            assertEquals(1, result.unreadableImages)
            val text = result.file.readText()
            assertTrue(text.startsWith("# Long chat"))
            assertTrue(text.contains("## User 1"))
            assertTrue(text.contains("first **markdown**"))
            assertTrue(text.contains("![图片未能读取](image-unavailable)"))
            assertTrue(text.contains("## Assistant 2"))
            assertTrue(text.contains("[思考内容未展开]"))
            assertTrue(text.contains("second "))
            assertFalse(text.contains("hidden reasoning"))
            assertEquals(2, Regex("^---$", RegexOption.MULTILINE).findAll(text).count())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun toolOutputUsesAFenceLongerThanItsContent() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.cacheDir, "markdown-tool-${System.nanoTime()}")
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "shell",
                    input = """{"cmd":"echo"}""",
                    output = listOf(UIMessagePart.Text("line with ``` inside")),
                ),
            ),
        )
        try {
            val result = exportChatMarkdown(
                context = context,
                conversation = conversation(),
                messages = listOf(message),
                includeReasoning = true,
                outputDir = dir,
            )
            val text = result.file.readText()
            assertTrue(text.contains("````text"))
            assertTrue(text.contains("line with ``` inside"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun cancellationDeletesThePartialMarkdownFile() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.cacheDir, "markdown-cancel-${System.nanoTime()}")
        val messages = (1..4).map { index ->
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("message-$index")))
        }
        try {
            val failure = runCatching {
                exportChatMarkdown(
                    context = context,
                    conversation = conversation(),
                    messages = messages,
                    includeReasoning = false,
                    outputDir = dir,
                    onProgress = { done, _ ->
                        if (done == 1) throw CancellationException("cancel")
                    },
                )
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertFalse(dir.listFiles()?.any { it.name.endsWith(".md") } == true)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun writeFailureIsReportedWithoutLeavingAFile() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val impossibleDir = File(context.cacheDir, "markdown-not-a-directory-${System.nanoTime()}")
        impossibleDir.writeText("file")
        try {
            val failure = runCatching {
                exportChatMarkdown(
                    context = context,
                    conversation = conversation(),
                    messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("x")))),
                    includeReasoning = false,
                    outputDir = impossibleDir,
                )
            }.exceptionOrNull()
            assertTrue(failure is ChatExportException)
            assertFalse(impossibleDir.listFiles()?.any { it.name.endsWith(".md") } == true)
        } finally {
            impossibleDir.delete()
        }
    }

    @Test fun markdownShareIntentCarriesStreamAndClipData() {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://test.export/page-1.md")
        val intent = buildChatMarkdownShareIntent(context, uri)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/markdown", intent.type)
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM))
        assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertNotNull(intent.clipData)
        assertEquals(1, intent.clipData?.itemCount)
    }

    @Test fun imageShareIntentUsesSendMultipleAndGrantsEveryPage() {
        val context = RuntimeEnvironment.getApplication()
        val uris = listOf(
            Uri.parse("content://test.export/page-1.png"),
            Uri.parse("content://test.export/page-2.png"),
        )
        val intent = buildChatImageShareIntent(context, uris)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals("image/png", intent.type)
        assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertEquals(2, intent.clipData?.itemCount)
        assertEquals(2, intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.size)
    }

    @Test fun exportErrorsAreMappedToSafeUserMessages() {
        assertEquals(
            "当前可用内存不足，请减少所选消息后重试",
            chatExportErrorMessage(OutOfMemoryError()),
        )
        assertTrue(chatExportErrorMessage(ActivityNotFoundException()).contains("分享应用"))
        assertTrue(
            chatExportErrorMessage(ChatExportException("导出内容超过 100 页上限，请减少所选消息"))
                .contains("页上限"),
        )
        assertEquals(
            "导出失败，请稍后重试",
            chatExportErrorMessage(IllegalArgumentException("Failed to find configured root /data/private/file")),
        )
    }
}
