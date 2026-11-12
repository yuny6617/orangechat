/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localtasks

import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.plugin.localdata.LocalDataSession
import me.rerere.rikkahub.plugin.provider.PluginImageResults

sealed interface LocalTaskImageImport {
    data class Imported(val resourceId: String, val mime: String?) : LocalTaskImageImport

    data class Unsupported(val reason: String) : LocalTaskImageImport

    data class Failed(val reason: String) : LocalTaskImageImport
}

/**
 * Host-owned bridge for images that already exist as an authorized plugin tool result. It must
 * **never** trigger image generation or consume the user's image key: it only re-reads an
 * `_orange_image` asset that the tool call already produced.
 *
 * The default is [unsupported] -> callers get an explicit "not supported" instead of a fake
 * success.
 */
fun interface LocalTaskImageSource {
    suspend fun importExistingToolResult(producerPluginId: String, toolResultJson: String): LocalTaskImageImport

    companion object {
        fun unsupported(): LocalTaskImageSource = LocalTaskImageSource { _, _ ->
            LocalTaskImageImport.Unsupported(
                "Image generation is not wired to assistant tasks on this build; nothing was generated",
            )
        }
    }
}

/**
 * Reuses the existing [PluginImageResults] converter for an already-produced `_orange_image`
 * result, copies the bytes into the plugin's persistent media store and returns only a resource id.
 */
class PluginImageToolResultSource(
    context: Context,
    filesManager: FilesManager,
    private val session: LocalDataSession,
) : LocalTaskImageSource {
    private val results = PluginImageResults(context.applicationContext, filesManager)

    override suspend fun importExistingToolResult(
        producerPluginId: String,
        toolResultJson: String,
    ): LocalTaskImageImport {
        val element = runCatching { Json.parseToJsonElement(toolResultJson) }.getOrNull()
            ?: return LocalTaskImageImport.Failed("The tool result is not valid JSON")
        val parts = runCatching { results.convert(producerPluginId, element) }.getOrElse {
            return LocalTaskImageImport.Failed("The existing image result could not be read")
        }
        val image = parts.filterIsInstance<UIMessagePart.Image>().firstOrNull()
            ?: return LocalTaskImageImport.Unsupported(
                "The tool result contains no existing image; nothing was generated",
            )
        val uri = Uri.parse(image.url)
        if (uri.scheme != "file") {
            return LocalTaskImageImport.Unsupported("Only existing local tool images can be imported")
        }
        val path = uri.path ?: return LocalTaskImageImport.Failed("The image file has no readable path")
        val file = File(path)
        if (!file.isFile) return LocalTaskImageImport.Failed("The image file has already been removed")
        val bytes = runCatching { file.readBytes() }.getOrElse {
            return LocalTaskImageImport.Failed("The image file could not be read")
        }
        return runCatching {
            val imported = session.importImageBytes(bytes, null)
            LocalTaskImageImport.Imported(imported.media.resourceId, imported.media.mime)
        }.getOrElse {
            LocalTaskImageImport.Failed("The image could not be stored in the plugin media library")
        }
    }
}
