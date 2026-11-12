/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.chat

import android.content.Context
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.MagicWand01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import androidx.compose.material3.Switch
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Job
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.data.ai.tools.LocalMomentsChatSelectionPrepareResult
import org.koin.compose.koinInject
import java.io.File

@Composable
fun ChatExportSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    conversation: Conversation,
    selectedMessages: List<UIMessage>
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val settings: Settings = LocalSettings.current
    val pluginManager = koinInject<PluginManager>()
    val chatService = koinInject<ChatService>()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var imageExportOptions by remember { mutableStateOf(ImageExportOptions()) }
    var activeExport by remember { mutableStateOf<ChatExportKind?>(null) }
    var exportProgress by remember { mutableStateOf("") }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    val exporting = activeExport != null
    var showMomentsShare by remember { mutableStateOf(false) }
    var assistantDraftIncludeImages by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { exportJob?.cancel() } }
    LaunchedEffect(visible) {
        if (!visible) {
            exportJob?.cancel()
            showMomentsShare = false
        }
    }

    if (visible && !showMomentsShare) {
        ModalBottomSheet(
            onDismissRequest = { exportJob?.cancel(); onDismissRequest() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = stringResource(id = R.string.chat_page_export_format))

                if (activeExport != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = exportProgress.ifBlank {
                                if (activeExport == ChatExportKind.Image) "正在准备分页图片…" else "正在准备 Markdown…"
                            },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { exportJob?.cancel() }) {
                            Text("取消导出")
                        }
                    }
                }

                val markdownSuccessMessage =
                    stringResource(id = R.string.chat_page_export_success, "Markdown")
                OutlinedCard(
                    onClick = {
                        if (activeExport != null) {
                            toaster.show("正在导出，请稍后再试", ToastType.Error)
                        } else {
                            val snapshot = selectedMessages.toList()
                            if (snapshot.isEmpty()) {
                                toaster.show("没有可导出的已选消息", ToastType.Error)
                            } else {
                                activeExport = ChatExportKind.Markdown
                                exportProgress = "正在写入 Markdown…"
                                exportJob = scope.launch {
                                    try {
                                        val result = exportChatMarkdown(
                                            context = context,
                                            conversation = conversation,
                                            messages = snapshot,
                                            includeReasoning = true,
                                            onProgress = { done, total ->
                                                withContext(Dispatchers.Main) {
                                                    exportProgress = "正在写入 Markdown $done/$total 条消息…"
                                                }
                                            },
                                        )
                                        try {
                                            currentCoroutineContext().ensureActive()
                                            shareChatMarkdown(context, result.file)
                                        } catch (error: Throwable) {
                                            withContext(NonCancellable + Dispatchers.IO) { result.file.delete() }
                                            throw error
                                        }
                                        val warning = if (result.unreadableImages > 0) {
                                            "；${result.unreadableImages} 张图片无法读取，正文仍已导出"
                                        } else {
                                            ""
                                        }
                                        toaster.show(
                                            "$markdownSuccessMessage（${result.messageCount} 条消息）$warning",
                                            type = ToastType.Success,
                                        )
                                        onDismissRequest()
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Throwable) {
                                        toaster.show(chatExportErrorMessage(error), ToastType.Error)
                                    } finally {
                                        activeExport = null
                                        exportProgress = ""
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(id = R.string.chat_page_export_markdown))
                        },
                        supportingContent = {
                            Text(
                                if (activeExport == ChatExportKind.Markdown) {
                                    exportProgress.ifBlank { "正在写入 Markdown…" }
                                } else {
                                    stringResource(id = R.string.chat_page_export_markdown_desc)
                                }
                            )
                        },
                        leadingContent = {
                            Icon(HugeIcons.File02, contentDescription = null)
                        }
                    )
                }

                val imageSuccessMessage =
                    stringResource(id = R.string.chat_page_export_success, "Image")
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        ListItem(
                            headlineContent = {
                                Text(stringResource(id = R.string.chat_page_export_image))
                            },
                            supportingContent = {
                                Text(stringResource(id = R.string.chat_page_export_image_desc))
                            },
                            leadingContent = {
                                Icon(HugeIcons.Image02, contentDescription = null)
                            }
                        )

                        HorizontalDivider()

                        ListItem(
                            headlineContent = { Text(stringResource(R.string.chat_page_export_image_expand_reasoning)) },
                            trailingContent = {
                                Switch(
                                    checked = imageExportOptions.expandReasoning,
                                    onCheckedChange = {
                                        imageExportOptions = imageExportOptions.copy(expandReasoning = it)
                                    }
                                )
                            }
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                enabled = !exporting,
                                onClick = {
                                    if (activeExport == null) {
                                        val snapshot = selectedMessages.toList()
                                        if (snapshot.isEmpty()) {
                                            toaster.show("没有可导出的已选消息", ToastType.Error)
                                        } else {
                                            activeExport = ChatExportKind.Image
                                            exportProgress = "正在准备分页图片…"
                                            val title = conversation.title
                                            val options = imageExportOptions
                                            exportJob = scope.launch {
                                                try {
                                                    val pages = exportVisualChatImages(
                                                        context = context,
                                                        scope = scope,
                                                        density = density,
                                                        conversation = conversation,
                                                        messages = snapshot,
                                                        settings = settings,
                                                        options = options,
                                                        onProgress = { page ->
                                                            exportProgress = "已生成 $page 页（最多 $EXPORT_MAX_PAGES 页）"
                                                        },
                                                    )
                                                    try {
                                                        currentCoroutineContext().ensureActive()
                                                        shareChatImages(context, pages)
                                                    } catch (shareError: Throwable) {
                                                        withContext(NonCancellable + Dispatchers.IO) { pages.forEach(File::delete) }
                                                        throw shareError
                                                    }
                                                    val count = pages.size
                                                    val detail = if (count > 1) {
                                                        "共 $count 张（第 1–$count 页），按页序分享"
                                                    } else {
                                                        "共 1 张"
                                                    }
                                                    toaster.show("$imageSuccessMessage · $detail", type = ToastType.Success)
                                                    onDismissRequest()
                                                } catch (error: CancellationException) {
                                                    throw error
                                                } catch (error: Throwable) {
                                                    toaster.show(chatExportErrorMessage(error), ToastType.Error)
                                                } finally {
                                                    activeExport = null
                                                    exportProgress = ""
                                                }
                                            }
                                        }
                                    }
                                }
                            ) {
                                Text(if (exporting) "正在生成图片" else "分页清晰模式（Markdown 保留原文）")
                            }
                        }
                    }
                }

                OutlinedCard(
                    onClick = {
                        if (exporting) {
                            toaster.show("正在导出，请稍后再试", ToastType.Error)
                        } else {
                            val plugin = pluginManager.getPlugin(LOCAL_MOMENTS_PLUGIN_ID)
                            if (plugin == null) {
                                if (pluginManager.isLoading.value) {
                                    toaster.show("插件列表正在加载，请稍后重试", ToastType.Error)
                                } else {
                                    toaster.show(
                                        "请先在插件管理安装本机朋友圈（$LOCAL_MOMENTS_PLUGIN_ID）；未创建任何本地数据",
                                        ToastType.Error,
                                    )
                                }
                            } else if (!plugin.isEnabled) {
                                toaster.show("本机朋友圈已安装但未启用，请先在插件管理启用", ToastType.Error)
                            } else {
                                showMomentsShare = true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListItem(
                        headlineContent = { Text("分享到本机朋友圈") },
                        supportingContent = {
                            Text("原生预览选中消息的结构化快照，选择可见范围后保存草稿或确认发布")
                        },
                        leadingContent = {
                            Icon(HugeIcons.Earth, contentDescription = null)
                        }
                    )
                }

                // 让当前助手用真实模型 + moments_chat_draft 工具整理；草稿仍由用户确认写入/发布。
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        ListItem(
                            headlineContent = { Text("助手整理草稿（调用真实模型）") },
                            supportingContent = {
                                Text(
                                    "把选中的 ${selectedMessages.size} 条消息作为真实用户指令发给当前助手；" +
                                        "助手会先给出完整预览，需要你在工具审批里批准后才写入你的草稿箱（作者是你，不会代你发布）"
                                )
                            },
                            leadingContent = {
                                Icon(HugeIcons.MagicWand01, contentDescription = null)
                            }
                        )

                        HorizontalDivider()

                        ListItem(
                            headlineContent = { Text("包含选中图片") },
                            supportingContent = {
                                Text("需要本机朋友圈已授权图片，且朋友圈助手设置允许图片；否则草稿只写正文")
                            },
                            trailingContent = {
                                Switch(
                                    checked = assistantDraftIncludeImages,
                                    onCheckedChange = { assistantDraftIncludeImages = it },
                                )
                            }
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                enabled = selectedMessages.isNotEmpty() && !exporting,
                                onClick = {
                                    when (
                                        val result = chatService.prepareMomentsChatSelection(
                                            conversation = conversation,
                                            selectedMessages = selectedMessages,
                                            includeImages = assistantDraftIncludeImages,
                                        )
                                    ) {
                                        is LocalMomentsChatSelectionPrepareResult.Prepared -> {
                                            val imageHint = if (result.imageOmissionReason != null) {
                                                "；图片未获授权，草稿只会写正文"
                                            } else {
                                                ""
                                            }
                                            toaster.show(
                                                "已发送整理请求；助手先给出完整预览，批准后才写入草稿箱$imageHint",
                                                ToastType.Success,
                                            )
                                            onDismissRequest()
                                        }

                                        is LocalMomentsChatSelectionPrepareResult.Rejected -> {
                                            toaster.show(result.message, ToastType.Error)
                                        }
                                    }
                                }
                            ) {
                                Text("让助手整理朋友圈草稿")
                            }
                        }
                    }
                }
            }
        }
    }

    if (visible && showMomentsShare) {
        MomentsChatShareSheet(
            visible = true,
            onDismissRequest = { showMomentsShare = false },
            onFinished = {
                showMomentsShare = false
                onDismissRequest()
            },
            conversation = conversation,
            selectedMessages = selectedMessages,
            settings = settings,
        )
    }
}

internal suspend fun exportToImage(
    context: Context,
    title: String,
    messages: List<UIMessage>,
    options: ImageExportOptions,
    outputDir: File = context.appTempFolder,
    share: suspend (Context, List<File>) -> Unit = { ctx, pages -> shareChatImages(ctx, pages) },
    progressDispatcher: CoroutineDispatcher = Dispatchers.Main,
    onProgress: (Int) -> Unit,
): Int {
    currentCoroutineContext().ensureActive()
    require(messages.isNotEmpty()) { "没有可导出的已选消息" }
    val blocks = flattenExportMessages(messages, options.expandReasoning)
    val pages = renderPagedChatPngs(context, title, blocks, outputDir, onPage = { page ->
        withContext(progressDispatcher) { onProgress(page) }
    })
    try {
        currentCoroutineContext().ensureActive()
        onProgress(pages.size)
        share(context, pages)
        return pages.size
    } catch (error: Throwable) {
        withContext(NonCancellable + Dispatchers.IO) { pages.forEach(File::delete) }
        throw error
    }
}

data class ImageExportOptions(val expandReasoning: Boolean = false)

private enum class ChatExportKind { Markdown, Image }
