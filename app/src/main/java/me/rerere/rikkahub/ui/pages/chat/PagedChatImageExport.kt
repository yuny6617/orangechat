package me.rerere.rikkahub.ui.pages.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.components.message.displayedChart
import java.io.File
import java.util.UUID

internal const val EXPORT_PAGE_WIDTH = 1080
internal const val EXPORT_PAGE_HEIGHT = 1920
internal const val EXPORT_MAX_PAGES = 100
internal const val EXPORT_TEXT_WINDOW = 2048
private const val MARGIN = 56
private const val TEXT_WIDTH = EXPORT_PAGE_WIDTH - MARGIN * 2
private const val BOTTOM = EXPORT_PAGE_HEIGHT - MARGIN

internal data class ExportTextBlock(val label: String, val text: String, val imageUrl: String? = null)

/** Only caller-selected messages; never stringify hidden tool results or metadata. */
@Suppress("DEPRECATION")
internal fun flattenExportMessages(messages: List<UIMessage>, includeReasoning: Boolean): Sequence<ExportTextBlock> = sequence {
    for ((index, message) in messages.withIndex()) {
        yield(ExportTextBlock("", "${message.role.name} ${index + 1}"))
        for (part in message.parts) {
            when (part) {
                is UIMessagePart.Text -> yield(ExportTextBlock("", part.text))
                is UIMessagePart.Image -> yield(ExportTextBlock("图片（按页尺寸采样）", "", part.url))
                is UIMessagePart.Reasoning -> {
                    if (includeReasoning) yield(ExportTextBlock("思考", part.reasoning))
                    else yield(ExportTextBlock("", "[思考内容未展开]"))
                }
                is UIMessagePart.Tool -> {
                    yield(ExportTextBlock("", "[工具调用]"))
                    // Same gate as visible ChatChartCard. Export its validated display data, not raw input/output.
                    part.displayedChart()?.let { chart ->
                        yield(ExportTextBlock("图表（完整数据文本视图）", chart.title.orEmpty()))
                        yield(ExportTextBlock("类型", chart.style.name))
                        yield(ExportTextBlock("X 轴", chart.xAxis.title.orEmpty()))
                        yield(ExportTextBlock("Y 轴", chart.yAxis.title.orEmpty()))
                        for (series in chart.series) {
                            yield(ExportTextBlock("系列", series.name.orEmpty()))
                            series.values.forEachIndexed { i, value ->
                                yield(ExportTextBlock("", "${chart.categoryLabel(i)}: $value"))
                            }
                            series.points.forEach { point -> yield(ExportTextBlock("", "${point.x}, ${point.y}")) }
                        }
                    }
                }
                is UIMessagePart.Document -> yield(ExportTextBlock("", "[文档附件]"))
                is UIMessagePart.Video -> yield(ExportTextBlock("", "[视频附件：图片导出不包含播放内容]"))
                is UIMessagePart.Audio -> yield(ExportTextBlock("", "[音频附件：图片导出不包含播放内容]"))
                is UIMessagePart.VoiceMessage -> yield(ExportTextBlock("语音转写", part.transcript))
                is UIMessagePart.ToolCall, is UIMessagePart.ToolResult -> yield(ExportTextBlock("", "[工具调用]"))
                UIMessagePart.Search -> yield(ExportTextBlock("", "[搜索]"))
            }
        }
    }
}

/** Lazy bounded windows, including newline runs and a single enormous paragraph. */
internal fun splitAtCodePoint(text: String, maxChars: Int): Sequence<String> = sequence {
    require(maxChars >= 2)
    var start = 0
    while (start < text.length) {
        var end = minOf(start.toLong() + maxChars, text.length.toLong()).toInt()
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        yield(text.substring(start, end))
        start = end
    }
}

/** One bounded layout; page breaks use actual line bottom, not estimated font metrics/maxLines. */
internal fun exportTextLayout(text: String): StaticLayout {
    require(text.length <= EXPORT_TEXT_WINDOW)
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 34, 42); textSize = 30f }
    return StaticLayout.Builder.obtain(text, 0, text.length, paint, TEXT_WIDTH)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
        .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .build()
}

/** Off-main rendering/encoding. Only one page plus one sampled image is live. */
internal suspend fun renderPagedChatPngs(
    context: Context,
    title: String,
    blocks: Sequence<ExportTextBlock>,
    outputDir: File,
    includePageTitle: Boolean = true,
    maxPages: Int = EXPORT_MAX_PAGES,
    onPage: suspend (Int) -> Unit = {},
    onTextDrawn: (String) -> Unit = {},
    imageLoader: suspend (String) -> Bitmap = { loadExportImage(context, it, TEXT_WIDTH, 1200) },
): List<File> {
    require(maxPages in 1..EXPORT_MAX_PAGES)
    val files = mutableListOf<File>()
    var bitmap: Bitmap? = null
    var canvas: Canvas? = null
    var y = MARGIN
    val prefix = "chat-export-${UUID.randomUUID()}"
    val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(116, 120, 128)
        textSize = 24f
    }
    try {
        withContext(Dispatchers.IO) {
            check(outputDir.isDirectory || outputDir.mkdirs()) { "无法创建临时导出目录" }
            fun pageCanvas(): Canvas {
                if (canvas == null) {
                    check(files.size < maxPages) { "导出内容超过 $maxPages 页上限，请减少所选消息" }
                    bitmap = Bitmap.createBitmap(EXPORT_PAGE_WIDTH, EXPORT_PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
                    canvas = Canvas(checkNotNull(bitmap)).also { it.drawColor(Color.WHITE) }
                }
                return checkNotNull(canvas)
            }
            suspend fun flush() {
                val page = bitmap ?: return
                currentCoroutineContext().ensureActive()
                val file = File(outputDir, "$prefix-page-${(files.size + 1).toString().padStart(3, '0')}.png")
                // Track before opening, so an encoder/write/cancellation failure removes this partial file too.
                files += file
                try {
                    checkNotNull(canvas).drawText(
                        "第 ${files.size} 页",
                        MARGIN.toFloat(),
                        (EXPORT_PAGE_HEIGHT - 20).toFloat(),
                        footerPaint,
                    )
                    file.outputStream().use { check(page.compress(Bitmap.CompressFormat.PNG, 100, it)) { "图片编码失败" } }
                    currentCoroutineContext().ensureActive()
                    onPage(files.size)
                } finally {
                    page.recycle()
                    bitmap = null
                    canvas = null
                    y = MARGIN
                }
            }
            suspend fun text(source: String) {
                for (window in splitAtCodePoint(source, EXPORT_TEXT_WINDOW)) {
                    currentCoroutineContext().ensureActive()
                    val layout = exportTextLayout(window)
                    var first = 0
                    while (first < layout.lineCount) {
                        currentCoroutineContext().ensureActive()
                        val top = layout.getLineTop(first)
                        var end = first
                        while (end < layout.lineCount && layout.getLineBottom(end) - top <= BOTTOM - y) end++
                        if (end == first) {
                            check(y != MARGIN) { "单行文本超过页高" }
                            flush()
                            continue
                        }
                        val height = layout.getLineBottom(end - 1) - top
                        val target = pageCanvas()
                        target.save()
                        target.clipRect(MARGIN, y, MARGIN + TEXT_WIDTH, y + height)
                        target.translate(MARGIN.toFloat(), (y - top).toFloat())
                        layout.draw(target)
                        target.restore()
                        onTextDrawn(window.substring(layout.getLineStart(first), layout.getLineEnd(end - 1)))
                        y += height
                        first = end
                        if (first < layout.lineCount) flush()
                    }
                }
                y += 18
            }
            text("分页清晰模式 · Markdown 保留原文；图表为完整数据文本；长内容自动分张")
            if (includePageTitle) text(title)
            for (block in blocks) {
                currentCoroutineContext().ensureActive()
                if (block.label.isNotEmpty()) text(block.label)
                if (block.imageUrl != null) {
                    val image = imageLoader(block.imageUrl)
                    try {
                        currentCoroutineContext().ensureActive()
                        check(image.width <= TEXT_WIDTH && image.height <= 1200) { "图片解码超出导出尺寸限制" }
                        if (y + image.height > BOTTOM) flush()
                        pageCanvas().drawBitmap(image, MARGIN.toFloat(), y.toFloat(), null)
                        y += image.height + 18
                    } finally { image.recycle() }
                } else text(block.text)
            }
            flush()
        }
        currentCoroutineContext().ensureActive()
        return files
    } catch (error: Throwable) {
        withContext(NonCancellable + Dispatchers.IO) {
            files.forEach { file ->
                if (file.exists() && !file.delete()) error.addSuppressed(IllegalStateException("未能清理临时导出文件"))
            }
        }
        throw error
    } finally { bitmap?.recycle() }
}

internal fun exportImageRequest(context: Context, url: String, maxWidth: Int, maxHeight: Int): ImageRequest =
    ImageRequest.Builder(context).data(url).size(maxWidth, maxHeight)
        .precision(Precision.EXACT).scale(Scale.FIT).allowHardware(false)
        .memoryCachePolicy(CachePolicy.DISABLED).build()

internal suspend fun loadExportImage(context: Context, url: String, maxWidth: Int, maxHeight: Int): Bitmap =
    withContext(Dispatchers.IO) {
        val result = SingletonImageLoader.get(context).execute(exportImageRequest(context, url, maxWidth, maxHeight))
        // Never put private resource URLs, base64 or decoder exception details in the user-visible error.
        val drawable = result.image?.asDrawable(context.resources) ?: error("所选图片加载失败，请检查图片是否仍可访问后重试")
        val iw = drawable.intrinsicWidth.takeIf { it > 0 } ?: maxWidth
        val ih = drawable.intrinsicHeight.takeIf { it > 0 } ?: maxHeight
        val scale = minOf(maxWidth.toDouble() / iw, maxHeight.toDouble() / ih, 1.0)
        val image = Bitmap.createBitmap((iw * scale).toInt().coerceAtLeast(1), (ih * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, image.width, image.height)
            drawable.draw(Canvas(image))
            currentCoroutineContext().ensureActive()
            image
        } catch (error: Throwable) { image.recycle(); throw error }
    }
