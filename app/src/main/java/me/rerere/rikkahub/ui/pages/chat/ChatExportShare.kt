/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.chat

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import java.io.File
import java.io.IOException

/** A user-facing failure whose message is safe to show and never contains a private file path. */
internal class ChatExportException(message: String) : Exception(message)

internal fun chatExportErrorMessage(error: Throwable): String = when (error) {
    is OutOfMemoryError -> "当前可用内存不足，请减少所选消息后重试"
    is ActivityNotFoundException -> "没有找到可用的分享应用，请安装文件管理器或聊天应用后重试"
    is SecurityException -> "没有权限读取所选图片或写入导出文件"
    is ChatExportException -> error.message ?: "导出失败，请稍后重试"
    is IOException -> "写入或读取导出文件失败，请检查存储空间后重试"
    is IllegalStateException -> error.message
        ?.takeIf { it.length in 1..160 && !it.contains("/") }
        ?: "导出失败，请稍后重试"
    is IllegalArgumentException -> error.message
        ?.takeIf { it.length in 1..160 && !it.contains("/") }
        ?: "导出失败，请稍后重试"
    else -> "导出失败，请稍后重试"
}

internal fun chatExportFileUri(context: Context, file: File): Uri = try {
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
} catch (_: IllegalArgumentException) {
    // FileProvider failures expose the private path in their message; replace it with a safe one.
    throw ChatExportException("无法生成分享文件，请重试")
}

internal fun buildChatMarkdownShareIntent(context: Context, uri: Uri): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, uri.lastPathSegment)
        clipData = ClipData.newUri(context.contentResolver, "聊天记录 Markdown", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

internal fun buildChatImageShareIntent(
    context: Context,
    uris: List<Uri>,
    label: String = "聊天记录分页图片",
): Intent {
    require(uris.isNotEmpty()) { "没有可分享的导出图片" }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uris.single())
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/png"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }
    intent.clipData = ClipData.newUri(context.contentResolver, label, uris.first()).also { clip ->
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return intent
}

internal suspend fun shareChatMarkdown(context: Context, file: File) {
    // FileProvider + ClipData MIME lookup touch the filesystem/content resolver; keep them off Main.
    val intent = withContext(Dispatchers.IO) {
        buildChatMarkdownShareIntent(context, chatExportFileUri(context, file))
    }
    startChooser(context, intent)
}

internal suspend fun shareChatImages(context: Context, pages: List<File>) {
    if (pages.isEmpty()) throw ChatExportException("没有可分享的导出图片")
    val intent = withContext(Dispatchers.IO) {
        val uris = pages.map { chatExportFileUri(context, it) }
        buildChatImageShareIntent(context, uris)
    }
    startChooser(context, intent)
}

private suspend fun startChooser(context: Context, intent: Intent) {
    val chooser = Intent.createChooser(
        intent,
        context.getString(R.string.chat_page_export_share_via),
    ).apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    withContext(Dispatchers.Main) {
        context.startActivity(chooser)
    }
}
