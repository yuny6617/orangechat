/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.chat

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import com.dokar.sonner.rememberToasterState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.highlight.Highlighter
import me.rerere.highlight.LocalHighlighter
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.charts.ChartCard
import me.rerere.rikkahub.ui.components.message.MessagePartBlock
import me.rerere.rikkahub.ui.components.message.groupMessageParts
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.BitmapComposer
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.rikkahub.utils.getActivity
import me.rerere.rikkahub.utils.toLocalString
import org.koin.compose.koinInject
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

private const val VISUAL_PAGE_MESSAGE_LIMIT = 8
private const val VISUAL_PAGE_CHAR_LIMIT = 6_000
private const val VISUAL_FALLBACK_CHAR_LIMIT = 20_000
private const val VISUAL_MAX_PAGES = 100

/**
 * The image export users know from before the paging rewrite: rendered header, avatars and chat
 * bubbles instead of a plain text page. Long selections are split into bounded pages so one huge
 * bitmap cannot be allocated; each page still uses the same bubble layout.
 */
internal suspend fun exportVisualChatImages(
    context: Context,
    scope: CoroutineScope,
    density: Density,
    conversation: Conversation,
    messages: List<UIMessage>,
    settings: Settings,
    options: ImageExportOptions,
    outputDir: File = context.appTempFolder,
    onProgress: (Int) -> Unit = {},
): List<File> {
    require(messages.isNotEmpty()) { "没有可导出的已选消息" }
    if (visualCharCount(messages) > VISUAL_FALLBACK_CHAR_LIMIT) {
        // Extremely long text would produce a bitmap taller than the decoder/encoder can handle.
        // Keep the bounded text pager for that case only.
        val pages = renderPagedChatPngs(
            context = context,
            title = conversation.title,
            blocks = flattenExportMessages(messages, options.expandReasoning),
            outputDir = outputDir,
            onPage = { onProgress(it) },
        )
        return pages
    }
    val chunks = visualMessageChunks(messages)
    check(chunks.size <= VISUAL_MAX_PAGES) { "导出内容超过 $VISUAL_MAX_PAGES 页上限，请减少所选消息" }
    val activity = context.getActivity() ?: error("当前页面不可用于图片导出，请回到聊天页重试")
    val composer = BitmapComposer(scope)
    val files = mutableListOf<File>()
    val prefix = "chat-export-${UUID.randomUUID()}"
    try {
        withContext(Dispatchers.IO) { check(outputDir.isDirectory || outputDir.mkdirs()) { "无法创建临时导出目录" } }
        chunks.forEachIndexed { index, chunk ->
            currentCoroutineContext().ensureActive()
            val bitmap = composer.composableToBitmap(
                activity = activity,
                width = 540.dp,
                screenDensity = density,
                content = {
                    CompositionLocalProvider(
                        LocalSettings provides settings,
                        LocalDisplaySettings provides settings.displaySetting,
                    ) {
                        VisualExportPage(
                            conversation = conversation,
                            messages = chunk,
                            options = options,
                            page = index + 1,
                            pageCount = chunks.size,
                        )
                    }
                },
            )
            val file = File(outputDir, "$prefix-page-${(index + 1).toString().padStart(3, '0')}.png")
            files += file
            try {
                withContext(Dispatchers.IO) {
                    file.outputStream().use { output ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 95, output)) { "图片编码失败" }
                    }
                }
            } finally {
                bitmap.recycle()
            }
            currentCoroutineContext().ensureActive()
            onProgress(index + 1)
        }
        return files
    } catch (cancel: CancellationException) {
        withContext(NonCancellable + Dispatchers.IO) { files.forEach { it.delete() } }
        throw cancel
    } catch (error: Throwable) {
        withContext(NonCancellable + Dispatchers.IO) { files.forEach { it.delete() } }
        throw error
    }
}

private fun visualCharCount(messages: List<UIMessage>): Int = messages.sumOf { message ->
    message.parts.sumOf { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.length
            is UIMessagePart.Reasoning -> part.reasoning.length
            else -> 80
        }
    }
}

private fun visualMessageChunks(messages: List<UIMessage>): List<List<UIMessage>> {
    val chunks = mutableListOf<List<UIMessage>>()
    var current = mutableListOf<UIMessage>()
    var chars = 0
    for (message in messages) {
        val size = visualCharCount(listOf(message))
        if (current.isNotEmpty() && (current.size >= VISUAL_PAGE_MESSAGE_LIMIT ||
                chars + size > VISUAL_PAGE_CHAR_LIMIT)
        ) {
            chunks += current
            current = mutableListOf()
            chars = 0
        }
        current += message
        chars += size
    }
    if (current.isNotEmpty()) chunks += current
    return chunks
}

@Composable
private fun VisualExportPage(
    conversation: Conversation,
    messages: List<UIMessage>,
    options: ImageExportOptions,
    page: Int,
    pageCount: Int,
) {
    val highlighter = koinInject<Highlighter>()
    val navBackStack = remember { mutableStateListOf<NavKey>() }
    val navigator = remember { Navigator(navBackStack) }
    val toasterState = rememberToasterState()
    CompositionLocalProvider(
        LocalNavController provides navigator,
        LocalHighlighter provides highlighter,
        LocalToaster provides toasterState,
    ) {
        RikkahubTheme {
            Surface(Modifier.width(540.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = conversation.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${LocalDateTime.now().toLocalString()}  orangechat",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Image(
                        painter = painterResource(id = R.drawable.share_logo),
                        contentDescription = "Logo",
                        modifier = Modifier.size(60.dp),
                    )
                }
                messages.forEachIndexed { index, message ->
                    VisualExportMessage(
                        message = message,
                        prevMessage = messages.getOrNull(index - 1),
                        options = options,
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.export_image_warning),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                    Text(
                        text = "第 $page / $pageCount 页",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun VisualExportMessage(
    message: UIMessage,
    prevMessage: UIMessage?,
    options: ImageExportOptions,
) {
    if (message.parts.isEmpty()) return
    val context = LocalContext.current
    val settings = LocalSettings.current
    val model = message.modelId?.let { settings.findModelById(it) }
    val isUser = message.role == MessageRole.USER
    val isAssistant = message.role == MessageRole.ASSISTANT
    val label = when {
        model?.modelId?.isNotBlank() == true -> model.modelId
        model?.displayName?.isNotBlank() == true -> model.displayName
        else -> "AI"
    }
    val showIdentity = isAssistant && prevMessage?.role != MessageRole.ASSISTANT
    val groupedParts = remember(message.parts) { message.parts.groupMessageParts() }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (showIdentity) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                AutoAIIcon(name = label, modifier = Modifier.size(36.dp))
                Text(label, style = MaterialTheme.typography.titleMedium)
            }
        }
        Column(
            modifier = Modifier.widthIn(max = 486.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            groupedParts.forEach { block ->
                when (block) {
                    is MessagePartBlock.ThinkingBlock -> {
                        if (block.steps.isNotEmpty()) {
                            Card(
                                shape = MaterialTheme.shapes.medium,
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                            ) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("深度思考", style = MaterialTheme.typography.titleSmall)
                                    block.steps.forEach { step ->
                                        if (step is me.rerere.rikkahub.ui.components.message.ThinkingStep.ReasoningStep &&
                                            options.expandReasoning
                                        ) {
                                            MarkdownBlock(
                                                content = step.reasoning.reasoning,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    is MessagePartBlock.ChartBlock -> ChartCard(block.spec)

                    is MessagePartBlock.ContentBlock -> when (val part = block.part) {
                        is UIMessagePart.Text -> if (part.text.isNotBlank()) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                                Card(
                                    shape = MaterialTheme.shapes.medium,
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isUser) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerLow
                                        },
                                    ),
                                ) {
                                    MarkdownBlock(
                                        content = part.text,
                                        modifier = Modifier.padding(8.dp),
                                    )
                                }
                            }
                        }

                        is UIMessagePart.Image -> AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(part.url)
                                .allowHardware(false)
                                .crossfade(false)
                                .build(),
                            contentDescription = "Image",
                            modifier = Modifier.sizeIn(maxHeight = 300.dp).clip(RoundedCornerShape(12.dp)),
                        )

                        is UIMessagePart.VoiceMessage -> Text("语音：${part.transcript}")
                        else -> Unit
                    }
                }
            }
        }
    }
}
