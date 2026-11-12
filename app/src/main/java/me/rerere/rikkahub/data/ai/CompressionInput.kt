package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getChatModelForAssistant
import me.rerere.rikkahub.data.video.isVideoCameraResource
import kotlin.uuid.Uuid

internal fun Settings.compressionModelFor(assistantId: Uuid): Model? =
    if (compressModelId != null) findModelById(compressModelId) else getChatModelForAssistant(assistantId)

internal fun Settings.compressionModelLabelFor(assistantId: Uuid): String {
    val selectedOverride = compressModelId?.let(::findModelById)
    if (compressModelId != null && selectedOverride == null) return "指定压缩模型不可用，请重新选择或清除指定项"
    val model = selectedOverride ?: getChatModelForAssistant(assistantId) ?: return "未配置可用压缩模型"
    val provider = model.findProvider(providers)?.name ?: "未知服务商"
    val source = if (selectedOverride != null) "指定压缩模型" else "当前聊天模型"
    return "$source：$provider / ${model.modelId}"
}

@Suppress("DEPRECATION")
private fun UIMessagePart.compressionText(): String = when (this) {
    is UIMessagePart.Text -> text
    is UIMessagePart.Tool -> "[Tool $toolName, id=$toolCallId]\nInput: $input\nOutput:\n" +
        output.joinToString("\n") { it.compressionText() }
    is UIMessagePart.ToolCall -> "[Tool call $toolName, id=$toolCallId]\n$arguments"
    is UIMessagePart.ToolResult -> "[Tool result $toolName, id=$toolCallId]\nArguments: $arguments\n$content"
    is UIMessagePart.VoiceMessage -> "[Voice message]\n$transcript"
    is UIMessagePart.Document -> "[Document: $fileName, $mime] ${compressionReference(url)}"
    is UIMessagePart.Image -> "[Image] ${compressionReference(url)}"
    is UIMessagePart.Audio -> "[Audio] ${compressionReference(url)}"
    is UIMessagePart.Video -> "[Video] ${compressionReference(url)}"
    is UIMessagePart.Reasoning, UIMessagePart.Search -> ""
}

private fun compressionReference(url: String): String = when {
    url.startsWith("data:", ignoreCase = true) -> "[embedded media; content unavailable as text]"
    // Video-call frames/clips/snapshots are private camera media. The summary keeps the fact that
    // there was an image, but never the local file path (which would be re-sent every turn).
    isVideoCameraResource(url) -> "[camera media; file path not included]"
    else -> url
}

/** Camera paths can also appear inside tool input/output text; scrub them without losing the sentence. */
private val CAMERA_FILE_PATH = Regex(
    """file:(?://)?[^\s"'<>]*?/video_call_(?:frames|clips|snapshots)/[^\s"'<>]*""",
    RegexOption.IGNORE_CASE,
)

private fun redactCameraFilePaths(text: String): String =
    text.replace(CAMERA_FILE_PATH, "[camera media; file path not included]")

internal fun UIMessage.compressionTranscript(): String = redactCameraFilePaths(
    "[${role.name}]: " + parts.map { it.compressionText() }.filter { it.isNotBlank() }.joinToString("\n"),
)

/** Submit the complete transcript once. Never split, truncate or automatically retry model requests. */
internal suspend fun compressTextOnce(
    texts: List<String>,
    summarize: suspend (String) -> String,
): String {
    val content = texts.joinToString("\n\n")
    require(content.isNotBlank()) { "没有可压缩的内容" }
    return summarize(content).also {
        check(it.isNotBlank()) { "模型返回了空摘要，原文未修改" }
    }
}

internal fun UIMessage.compressionSummaryText(): String {
    @Suppress("DEPRECATION")
    check(parts.none { it is UIMessagePart.Tool || it is UIMessagePart.ToolCall || it is UIMessagePart.ToolResult }) {
        "模型返回了工具调用，未生成摘要；原文未修改"
    }
    return toText().replace(Regex("<think>[\\s\\S]*?(?:</think>|$)"), "").trim()
        .takeIf { it.isNotBlank() } ?: error("模型未返回有效摘要，原文未修改")
}
