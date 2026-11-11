package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class PagedChatImageExportTest {
    @Test fun splitterPreservesLongContentAndNeverSplitsSurrogatePairs() {
        val source = ("a😀漢字".repeat(1700)) + "終"
        val pieces = splitAtCodePoint(source, 2048)
        assertEquals(source, pieces.joinToString(""))
        assertTrue(pieces.all { it.length <= 2048 })
        val pieceList = pieces.toList()
        assertTrue(pieceList.dropLast(1).none { it.lastOrNull()?.isHighSurrogate() == true })
        assertTrue(pieceList.drop(1).none { it.firstOrNull()?.isLowSurrogate() == true })
    }

    @Test fun selectedTextAndMediaMetadataAreRetainedAndReasoningIsOptIn() {
        val msg = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("**markdown**"),
                UIMessagePart.Reasoning("private reasoning"),
                UIMessagePart.Image("content://selected-image"),
            ),
        )
        val normal = flattenExportMessages(listOf(msg), includeReasoning = false)
        assertEquals(listOf("ASSISTANT 1", "**markdown**", "[思考内容未展开]", ""), normal.map { it.text }.toList())
        assertEquals("content://selected-image", normal.last().imageUrl)
        val withReasoning = flattenExportMessages(listOf(msg), includeReasoning = true)
        assertTrue(withReasoning.any { it.text == "private reasoning" })
    }

    @Test fun oneVeryLongMessageSpansPagesAndDrawsEverySourceCharacter() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.cacheDir, "export-test-${System.nanoTime()}")
        val source = "Long message 😀 paragraph\n".repeat(5000)
        val drawn = StringBuilder()
        try {
            val pages = renderPagedChatPngs(
                context, "Title", sequenceOf(ExportTextBlock("Assistant 1.1", source)), dir,
                onTextDrawn = { drawn.append(it) },
            )
            assertTrue(pages.size > 1)
            assertTrue(pages.size <= EXPORT_MAX_PAGES)
            assertTrue(pages.all { it.isFile && it.length() > 100 })
            assertTrue("Selected text must be fully laid out and drawn", drawn.toString().contains(source))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun selectedImageIsSampledAndPixelsAreEmbeddedInPage() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.cacheDir, "export-image-${System.nanoTime()}")
        val source = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        var loadedWidth = 0
        var loadedHeight = 0
        try {
            val pages = renderPagedChatPngs(
                context, "Title", sequenceOf(ExportTextBlock("", "", "selected://image")), dir,
                imageLoader = {
                    Bitmap.createScaledBitmap(source, 360, 270, false).also {
                        loadedWidth = it.width
                        loadedHeight = it.height
                    }
                },
            )
            assertTrue(loadedWidth <= EXPORT_PAGE_WIDTH && loadedHeight <= 1200)
            val page = BitmapFactory.decodeFile(pages.single().absolutePath)
            try {
                val pixels = IntArray(page.width * page.height)
                page.getPixels(pixels, 0, page.width, 0, 0, page.width, page.height)
                assertTrue("Sampled 360x270 image pixels must be embedded, below the variable-height title",
                    pixels.count { it == Color.RED } >= 360 * 270)
            }
            finally { page.recycle() }
        } finally {
            source.recycle()
            dir.deleteRecursively()
        }
    }

    @Test fun cancellationMidWriteRemovesPartialPngFiles() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.cacheDir, "export-cancel-${System.nanoTime()}")
        var pagesEncoded = 0
        try {
            val failure = runCatching {
                renderPagedChatPngs(
                    context, "Title", sequenceOf(ExportTextBlock("Assistant", "word ".repeat(20_000))), dir,
                    onPage = { pagesEncoded++; throw CancellationException("cancel export") },
                )
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertTrue(pagesEncoded > 0)
            assertFalse(dir.listFiles()?.any { it.name.endsWith(".png") } == true)
        } finally { dir.deleteRecursively() }
    }

    @Test fun tooManyPagesFailsAndRemovesPartialFiles() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.cacheDir, "export-overflow-${System.nanoTime()}")
        try {
            val huge = "word\n".repeat(1000)
            val failure = runCatching {
                renderPagedChatPngs(context, "Title", sequenceOf(ExportTextBlock("Assistant", huge)), dir, maxPages = 2)
            }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("页上限"))
            assertFalse(dir.listFiles()?.any { it.name.endsWith(".png") } == true)
        } finally {
            dir.deleteRecursively()
        }
    }
}
