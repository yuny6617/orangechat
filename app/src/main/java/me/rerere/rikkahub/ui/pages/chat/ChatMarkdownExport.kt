/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.chat

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.message.displayedChart
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalString
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal data class ChatMarkdownExportResult(
    val file: File,
    val messageCount: Int,
    val unreadableImages: Int,
)

/**
 * Streams Markdown directly to a UTF-8 file on [Dispatchers.IO].
 *
 * This intentionally never builds the whole conversation as one String: a long chat can easily
 * contain many inline images, and holding all of them plus the resulting Markdown at once is what
 * made the old main-thread export stall. Each image is sampled, encoded and released before the
 * next one; cancellation removes the partial file. Images that cannot be read are written as an
 * explicit placeholder and counted, never silently dropped.
 */
internal suspend fun exportChatMarkdown(
    context: Context,
    conversation: Conversation,
    messages: List<UIMessage>,
    includeReasoning: Boolean,
    outputDir: File = context.appTempFolder,
    onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    imageMarkdown: suspend (String) -> String? = { url -> encodeMarkdownImage(context, url) },
): ChatMarkdownExportResult = withContext(Dispatchers.IO) {
    if (messages.isEmpty()) throw ChatExportException("没有可导出的已选消息")
    if (!outputDir.isDirectory && !outputDir.mkdirs()) {
        throw ChatExportException("无法创建临时导出目录")
    }

    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
    val file = try {
        File.createTempFile("chat-export-$timestamp-", ".md", outputDir)
    } catch (_: Exception) {
        throw ChatExportException("无法创建 Markdown 导出文件")
    }
    var unreadableImages = 0

    suspend fun BufferedWriter.writePart(
        part: UIMessagePart,
        depth: Int,
        includeReasoning: Boolean,
        toolOutput: Boolean = false,
    ) {
        when (part) {
            is UIMessagePart.Text -> if (toolOutput) block(fenced(part.text, "text")) else block(part.text)
            is UIMessagePart.Image -> {
                val markdown = imageMarkdown(part.url)
                if (markdown == null) {
                    unreadableImages++
                    block("![图片未能读取](image-unavailable)")
                } else {
                    block(markdown)
                }
            }
            is UIMessagePart.Reasoning -> {
                if (includeReasoning) block(part.reasoning.asQuote("> **Thinking:**"))
            }
            is UIMessagePart.Document -> block("[文档: ${part.fileName}](${part.url})")
            is UIMessagePart.Video -> block("[视频](${part.url})")
            is UIMessagePart.Audio -> block("[音频](${part.url})")
            is UIMessagePart.VoiceMessage -> {
                block("语音转写：${part.transcript.ifBlank { "无" }}")
            }
            is UIMessagePart.Tool -> {
                if (depth > 0) return
                block("**Tool**: `${part.toolName}`")
                if (part.toolCallId.isNotBlank()) block("- Call ID: `${part.toolCallId}`")
                block(fenced(JsonInstantPretty.encodeToString(part.inputAsJson()), "json"))
                if (part.output.isNotEmpty()) {
                    block("Output:")
                    for (outputPart in part.output) {
                        currentCoroutineContext().ensureActive()
                        // Tool output is content, not another tool call. Never recurse into it.
                        writePart(outputPart, depth = 1, includeReasoning, toolOutput = true)
                    }
                }
                chartMarkdown(part)?.let { block(it) }
            }
            UIMessagePart.Search -> block("[搜索]")
            is UIMessagePart.ToolCall -> block("[工具调用]")
            is UIMessagePart.ToolResult -> block("[工具结果]")
        }
    }

    try {
        BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8)).use { writer ->
            writer.block("# ${conversation.title.lineSequence().joinToString(" ").trim().ifBlank { "Chat" }}")
            writer.block("*Exported on ${LocalDateTime.now().toLocalString()}*")

            messages.forEachIndexed { index, message ->
                currentCoroutineContext().ensureActive()
                writer.block("## ${message.role.exportLabel()} ${index + 1}")
                for (part in message.parts) {
                    currentCoroutineContext().ensureActive()
                    when (part) {
                        is UIMessagePart.Reasoning -> {
                            if (includeReasoning) {
                                writer.block(part.reasoning.asQuote("> **Thinking:**"))
                            } else {
                                writer.block("[思考内容未展开]")
                            }
                        }
                        else -> writer.writePart(part, depth = 0, includeReasoning = includeReasoning)
                    }
                }
                writer.write("---")
                writer.newLine()
                writer.newLine()
                onProgress(index + 1, messages.size)
            }
            writer.flush()
        }
        currentCoroutineContext().ensureActive()
        ChatMarkdownExportResult(
            file = file,
            messageCount = messages.size,
            unreadableImages = unreadableImages,
        )
    } catch (error: Throwable) {
        file.delete()
        throw error
    }
}

private fun BufferedWriter.block(text: String) {
    if (text.isEmpty()) return
    write(text)
    if (!text.endsWith('\n')) newLine()
    newLine()
}

private fun MessageRole.exportLabel(): String = when (this) {
    MessageRole.USER -> "User"
    MessageRole.ASSISTANT -> "Assistant"
    MessageRole.SYSTEM -> "System"
    MessageRole.TOOL -> "Tool"
}

private fun String.asQuote(header: String): String = buildString {
    appendLine(header)
    lineSequence().forEach { line ->
        if (line.isBlank()) appendLine(">") else appendLine("> $line")
    }
}

private fun String.escapeTableCell(): String =
    replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ")

/** Plain-text table of the chart's full data, matching the visible ChartTable rather than a screenshot. */
internal fun chartMarkdown(part: UIMessagePart.Tool): String? {
    val chart = part.displayedChart() ?: return null
    if (chart.series.isEmpty()) return null

    val rows = mutableListOf<String>()
    if (chart.series.any { it.values.isNotEmpty() }) {
        val header = chart.series.joinToString(" | ") {
            it.name.orEmpty().ifBlank { "系列" }.escapeTableCell()
        }
        rows += "| 类别 | $header |"
        rows += "| --- | ${chart.series.joinToString(" | ") { "---" }} |"
        val count = maxOf(chart.xAxis.data.size, chart.series.maxOfOrNull { it.values.size } ?: 0)
        for (index in 0 until count) {
            val values = chart.series.joinToString(" | ") { series ->
                series.values.getOrNull(index)?.toString().orEmpty()
            }
            rows += "| ${chart.categoryLabel(index).escapeTableCell()} | $values |"
        }
    }
    chart.series.filter { it.points.isNotEmpty() }.forEach { series ->
        if (rows.isNotEmpty()) rows += ""
        rows += "| ${series.name.orEmpty().ifBlank { "系列" }.escapeTableCell()} x | y |"
        rows += "| --- | --- |"
        series.points.forEach { point ->
            rows += "| ${point.x} | ${point.y} |"
        }
    }
    return "#### 图表数据（完整）\n\n${rows.joinToString("\n")}"
}

/** Chooses a fence longer than any backtick run in the content, so JSON/text cannot close it early. */
internal fun fenced(text: String, language: String): String {
    val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(maxOf(3, longest + 1))
    return "$fence$language\n$text\n$fence"
}

/** Inline images are sampled to a bounded JPEG; remote images stay links (no network in export). */
internal suspend fun encodeMarkdownImage(context: Context, url: String): String? {
    if (url.startsWith("http://") || url.startsWith("https://")) {
        return "![Image](<${url.replace(">", "%3E")}>)"
    }
    val bitmap: Bitmap = try {
        loadExportImage(context, url, maxWidth = 1200, maxHeight = 1200)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        return null
    }
    return try {
        val output = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) {
            throw IllegalStateException("图片编码失败")
        }
        val encoded = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        "![Image](data:image/jpeg;base64,$encoded)"
    } catch (error: OutOfMemoryError) {
        throw error
    } catch (_: Exception) {
        null
    } finally {
        bitmap.recycle()
    }
}
