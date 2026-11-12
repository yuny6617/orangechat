package me.rerere.rikkahub.theme.packageio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ThemePackageReaderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val manifestJson =
        """{"schemaVersion":1,"id":"com.orangechat.test","name":"Test","version":"1","themes":["sample"]}"""

    @Test fun readsDeclarationOnlyPackage() {
        val result = ThemePackageReader().read(packageZip())
        assertEquals("com.orangechat.test", result.manifest.id)
        assertEquals("#FFFFFFFF", result.themes.getValue("sample").light.getValue("background"))
    }

    @Test fun rejectsExecutableOrUnknownTokens() {
        val zip = packageZip(theme = themeJson(light = tokenObject("#FFFFFFFF").replace("\"background\"", "\"onClick\"")))
        assertRejected(zip)
    }

    @Test fun rejectsPathTraversal() {
        assertRejected(zipOf("../escape.txt" to "x".toByteArray()))
    }

    @Test fun rejectsDuplicateTopLevelManifestKey() {
        val manifest = """{"schemaVersion":1,"id":"com.orangechat.test","id":"com.orangechat.test","name":"Test","version":"1","themes":["sample"]}"""
        assertRejected(packageZip(manifest = manifest))
    }

    @Test fun rejectsDuplicateTokenKey() {
        val zip = packageZip(theme = themeJson(light = tokenObject("#FFFFFFFF", "\"background\":\"#FF000000\"")))
        assertRejected(zip)
    }

    @Test fun rejectsEscapedEquivalentDuplicateTokenKey() {
        val zip = packageZip(
            theme = themeJson(light = tokenObject("#FFFFFFFF", "\"back\\u0067round\":\"#FF000000\"")),
        )
        assertRejected(zip)
    }

    @Test fun rejectsDuplicateShapeKey() {
        val zip = packageZip(
            theme = themeJson(shape = """{"corner_small_dp":8,"corner_small_dp":9}"""),
        )
        assertRejected(zip)
    }

    @Test fun acceptsEscapedKeysThatAreNotDuplicates() {
        val light = ThemeTokenSchema.requiredNames.joinToString(",", "{", "}") { name ->
            val key = if (name == "background") "back\\u0067round" else name
            "\"$key\":\"#FFFFFFFF\""
        }
        val result = ThemePackageReader().read(packageZip(theme = themeJson(light = light)))
        assertEquals("#FFFFFFFF", result.themes.getValue("sample").light.getValue("background"))
    }

    @Test fun rejectsInvalidUtf8TokenEntry() {
        val bytes = themeJson().toByteArray(Charsets.UTF_8)
        val index = bytes.indexOf('S'.code.toByte())
        assertTrue(index >= 0)
        bytes[index] = 0xFF.toByte()
        assertRejected(zipOf("manifest.json" to manifestJson.toByteArray(), "themes/sample.json" to bytes))
    }

    @Test fun rejectsByteOrderMark() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + themeJson().toByteArray()
        assertRejected(zipOf("manifest.json" to manifestJson.toByteArray(), "themes/sample.json" to bytes))
    }

    @Test fun rejectsOversizedEntry() {
        val bytes = " ".repeat(130_000).toByteArray()
        assertRejected(zipOf("manifest.json" to manifestJson.toByteArray(), "themes/sample.json" to bytes))
    }

    @Test fun rejectsTooManyEntries() {
        val entries = buildList {
            add("manifest.json" to manifestJson.toByteArray())
            repeat(25) { add("themes/t$it.json" to "{}".toByteArray()) }
        }
        assertRejected(zipOf(*entries.toTypedArray()))
    }

    @Test fun rejectsArchiveLargerThanLimit() {
        val randomBytes = ByteArray(600_000).also { Random(7L).nextBytes(it) }
        val zip = zipOf("manifest.json" to manifestJson.toByteArray(), "themes/sample.json" to randomBytes)
        assertTrue(zip.length() > 512_000)
        assertRejected(zip)
    }

    @Test fun rejectsEntryThatExpandsBeyondLimit() {
        val zeros = ByteArray(200_000)
        assertRejected(zipOf("manifest.json" to manifestJson.toByteArray(), "themes/sample.json" to zeros))
    }

    @Test fun rejectsTotalExpansionBeyondLimit() {
        val themes = (0..5).map { "t$it" }
        val manifest = """
            {"schemaVersion":1,"id":"com.orangechat.test","name":"Test","version":"1","themes":[${themes.joinToString(",") { "\"$it\"" }}]}
        """.trimIndent()
        val entries = buildList {
            add("manifest.json" to manifest.toByteArray())
            themes.forEach { add("themes/$it.json" to ByteArray(100_000)) }
        }
        assertRejected(zipOf(*entries.toTypedArray()))
    }

    @Test fun rejectsUndeclaredEntries() {
        val zip = zipOf(
            "manifest.json" to manifestJson.toByteArray(),
            "themes/sample.json" to themeJson().toByteArray(),
            "themes/extra.json" to themeJson().toByteArray(),
        )
        assertRejected(zip)
    }

    @Test fun rejectsMissingDeclaredTheme() {
        assertRejected(zipOf("manifest.json" to manifestJson.toByteArray()))
    }

    @Test fun rejectsDuplicateManifestThemes() {
        val manifest = """{"schemaVersion":1,"id":"com.orangechat.test","name":"Test","version":"1","themes":["sample","sample"]}"""
        assertRejected(packageZip(manifest = manifest))
    }

    @Test fun rejectsThemeIdThatDoesNotMatchFilename() {
        assertRejected(packageZip(theme = themeJson(id = "other")))
    }

    @Test fun rejectsInvalidColour() {
        assertRejected(packageZip(theme = themeJson(light = tokenObject("#GGGGGG"))))
    }

    @Test fun rejectsInvalidShape() {
        assertRejected(packageZip(theme = themeJson(shape = """{"corner_small_dp":40}""")))
    }

    @Test fun rejectsUnknownManifestField() {
        val manifest = """{"schemaVersion":1,"id":"com.orangechat.test","name":"Test","version":"1","themes":["sample"],"run":"x"}"""
        assertRejected(packageZip(manifest = manifest))
    }

    @Test fun rejectsUnknownThemeField() {
        assertRejected(packageZip(theme = themeJson(extraField = "\"execute\":true")))
    }

    @Test fun rejectsUnsafeArchiveNames() {
        listOf(
            "/manifest.json",
            "C:/manifest.json",
            "themes\\sample.json",
            "themes/../manifest.json",
            "themes/./sample.json",
            "themes//sample.json",
            "themes/sample.JSON",
            "themes/sample.txt",
            "themes/",
        ).forEach { name ->
            assertRejected(zipOf(name to "{}".toByteArray()))
        }
    }

    @Test fun rejectsEmptyArchive() {
        assertRejected(temporaryFolder.newFile())
    }

    private fun tokenObject(color: String, extraMembers: String = ""): String = buildString {
        append('{')
        append(ThemeTokenSchema.requiredNames.joinToString(",") { "\"$it\":\"$color\"" })
        if (extraMembers.isNotEmpty()) append(',').append(extraMembers)
        append('}')
    }

    private fun themeJson(
        id: String = "sample",
        name: String = "Sample",
        light: String = tokenObject("#FFFFFFFF"),
        dark: String = tokenObject("#FF000000"),
        shape: String? = null,
        extraField: String? = null,
    ): String = buildString {
        append("""{"schemaVersion":1,"id":"$id","name":"$name","light":$light,"dark":$dark""")
        if (shape != null) append(""","shape":$shape""")
        if (extraField != null) append(',').append(extraField)
        append('}')
    }

    private fun packageZip(
        manifest: String = manifestJson,
        theme: String = themeJson(),
        themePath: String = "themes/sample.json",
    ): File = zipOf("manifest.json" to manifest.toByteArray(), themePath to theme.toByteArray())

    private fun zipOf(vararg entries: Pair<String, ByteArray>): File {
        val zip = temporaryFolder.newFile()
        ZipOutputStream(zip.outputStream()).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return zip
    }

    private fun assertRejected(zip: File) {
        assertThrows(IllegalArgumentException::class.java) { ThemePackageReader().read(zip) }
    }
}
