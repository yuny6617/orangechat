package me.rerere.rikkahub.plugin.webview

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HostGeneratedImageLibraryTest {
    private val directory: File = Files.createTempDirectory("host-generated-images").toFile()

    @After
    fun cleanup() {
        directory.deleteRecursively()
    }

    @Test
    fun generatedImageNamesAreSimpleBoundedImageFiles() {
        assertTrue(isGeneratedImageName("chat_1234.png"))
        assertTrue(isGeneratedImageName("plugin_ab-cd.webp"))
        assertTrue(isGeneratedImageName("1700000000000_model_0.jpeg"))
        assertFalse(isGeneratedImageName("notes.txt"))
        assertFalse(isGeneratedImageName("../images/chat_1.png"))
        assertFalse(isGeneratedImageName("chat_1"))
        assertFalse(isGeneratedImageName(".png"))
        assertFalse(isGeneratedImageName("chat_1.png.exe"))
    }

    @Test
    fun darkThemeFollowsExplicitModeBeforeSystem() {
        assertEquals(false, resolveDarkTheme("LIGHT", systemNight = true))
        assertEquals(true, resolveDarkTheme("DARK", systemNight = false))
        assertEquals(true, resolveDarkTheme("SYSTEM", systemNight = true))
        assertEquals(false, resolveDarkTheme(null, systemNight = false))
        assertEquals(true, resolveDarkTheme("garbage", systemNight = true))
    }

    @Test
    fun listingKeepsOnlyRealImagesNewestFirst() {
        write("chat_old.png", png(640, 480), lastModified = 1000L)
        write("chat_new.png", png(800, 600), lastModified = 3000L)
        write("1700000000000_model_0.png", png(512, 512), lastModified = 2000L)
        write("notes.txt", png(10, 10).copyOf(64), lastModified = 4000L)
        write("broken.png", ByteArray(64), lastModified = 5000L)

        val listing = HostGeneratedImageLibrary(directory).list()
        assertEquals(3, listing.items.size)
        assertEquals(3, listing.total)
        assertEquals(
            listOf("chat_new.png", "1700000000000_model_0.png", "chat_old.png"),
            listing.items.map { it.file.name },
        )
        assertEquals(listOf("chat_generated", "image_generation", "chat_generated"), listing.items.map { it.source })
        assertEquals(800, listing.items[0].width)
        assertEquals(600, listing.items[0].height)
        assertEquals("image/png", listing.items[0].mime)
    }

    @Test
    fun idsAreOpaqueDigestsAndResolveOnlyByName() {
        write("chat_secret-name.png", png(10, 10), lastModified = 1000L)
        val library = HostGeneratedImageLibrary(directory)
        val image = library.list().items.single()
        assertEquals(sha256Hex("chat_secret-name.png"), image.id)
        assertTrue(image.id.matches(Regex("[a-f0-9]{64}")))
        assertFalse(image.id.contains("chat"))
        assertNotNull(library.find(image.id))
        assertNull(library.find("../chat_secret-name.png"))
        assertNull(library.find("f".repeat(64)))
        assertNull(library.find(image.file.absolutePath))
    }

    @Test
    fun limitIsBoundedAndTruncationIsReported() {
        write("chat_1.png", png(10, 10), lastModified = 1000L)
        write("chat_2.png", png(10, 10), lastModified = 2000L)
        write("chat_3.png", png(10, 10), lastModified = 3000L)
        val listing = HostGeneratedImageLibrary(directory).list(limit = 2)
        assertEquals(2, listing.items.size)
        assertEquals(3, listing.total)
        assertEquals(listOf("chat_3.png", "chat_2.png"), listing.items.map { it.file.name })
    }

    @Test
    fun readBytesRefusesOversizedSources() {
        write("chat_big.png", png(10, 10), lastModified = 1000L)
        val library = HostGeneratedImageLibrary(directory, maxBytes = 8L)
        assertEquals(0, library.list().items.size)
        val relaxed = HostGeneratedImageLibrary(directory)
        val image = relaxed.list().items.single()
        assertThrows(IllegalArgumentException::class.java) {
            HostGeneratedImageLibrary(directory, maxBytes = 8L).readBytes(image)
        }
    }

    @Test
    fun jpegDimensionsAreParsedWithoutDecoding() {
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08, 0x01, 0xE0.toByte(), 0x02, 0x80.toByte(),
            0x03, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01,
        )
        assertEquals(640 to 480, ImageHeaders.dimensions(jpeg))
        assertEquals(640 to 480, ImageHeaders.dimensions(png(640, 480)))
        assertNull(ImageHeaders.dimensions(ByteArray(3)))
    }

    private fun write(name: String, bytes: ByteArray, lastModified: Long): File {
        val file = File(directory, name)
        file.writeBytes(bytes)
        file.setLastModified(lastModified)
        return file
    }

    /** Minimal 33-byte PNG: real signature + IHDR with the requested dimensions. */
    private fun png(width: Int, height: Int): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        (width ushr 24).toByte(), (width ushr 16).toByte(), (width ushr 8).toByte(), width.toByte(),
        (height ushr 24).toByte(), (height ushr 16).toByte(), (height ushr 8).toByte(), height.toByte(),
        0x08, 0x06, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
    )
}
