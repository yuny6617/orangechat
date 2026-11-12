package me.rerere.rikkahub.data.sync

import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import me.rerere.rikkahub.plugin.localdata.PluginBackupArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Pure file-format tests: no Android context, no live database and no Gradle-only fixtures. */
class PluginBackupArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun localTasksJson(state: String = "interrupted"): String =
        """
        {
          "format": "${PluginBackupArchive.LOCAL_TASKS_FORMAT}",
          "formatVersion": ${PluginBackupArchive.LOCAL_TASKS_FORMAT_VERSION},
          "settingsVersion": ${PluginBackupArchive.LOCAL_TASKS_SCHEMA_VERSION},
          "ledgerVersion": ${PluginBackupArchive.LOCAL_TASKS_SCHEMA_VERSION},
          "settings": {"enabled": false},
          "ledger": {"version": 1, "tasks": {"lt_1": {"taskId": "lt_1", "state": "$state"}}}
        }
        """.trimIndent()

    private fun writeArchive(
        pluginId: String = "demo.plugin",
        localTasks: String? = localTasksJson(),
        localData: File? = null,
    ): File = File(temporary.root, "plugin-${UUID.randomUUID()}.zip").also { destination ->
        PluginBackupArchive.write(
            destination = destination,
            pluginId = pluginId,
            pluginVersion = "1.2.3",
            localDataZip = localData,
            localTasksJson = localTasks,
            createdAt = 1_700_000_000_000L,
        )
    }

    @Test
    fun `local tasks only archive round trips with manifest hashes`() {
        val archive = writeArchive(localTasks = localTasksJson())
        val work = temporary.newFolder("work")
        val contents = PluginBackupArchive.inspect(archive, work)

        assertEquals("demo.plugin", contents.manifest.pluginId)
        assertEquals("1.2.3", contents.manifest.pluginVersion)
        assertEquals(PluginBackupArchive.FORMAT, contents.manifest.format)
        assertEquals(1, contents.manifest.formatVersion)
        assertEquals(null, contents.localDataZip)
        assertEquals(null, contents.manifest.localData)
        assertTrue(contents.localTasksJson!!.contains(PluginBackupArchive.LOCAL_TASKS_FORMAT))
    }

    @Test
    fun `tampered local tasks bytes fail the manifest hash`() {
        val archive = writeArchive()
        val work = temporary.newFolder("work")
        val rewritten = File(temporary.root, "tampered.zip")
        ZipFile(archive).use { zip ->
            ZipOutputStream(rewritten.outputStream()).use { output ->
                zip.entries().toList().forEach { entry ->
                    output.putNextEntry(ZipEntry(entry.name))
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    output.write(
                        if (entry.name == PluginBackupArchive.LOCAL_TASKS_ENTRY) {
                            bytes + " ".toByteArray()
                        } else {
                            bytes
                        },
                    )
                    output.closeEntry()
                }
            }
        }

        assertTrue(runCatching { PluginBackupArchive.inspect(rewritten, work) }.isFailure)
    }

    @Test
    fun `unknown or duplicate outer entries are rejected`() {
        val archive = writeArchive()
        val work = temporary.newFolder("work")
        val rewritten = File(temporary.root, "extra.zip")
        ZipFile(archive).use { zip ->
            ZipOutputStream(rewritten.outputStream()).use { output ->
                zip.entries().toList().forEach { entry ->
                    output.putNextEntry(ZipEntry(entry.name))
                    zip.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
                output.putNextEntry(ZipEntry("extra.bin"))
                output.write(byteArrayOf(1, 2, 3))
                output.closeEntry()
            }
        }

        assertTrue(runCatching { PluginBackupArchive.inspect(rewritten, work) }.isFailure)
    }

    @Test
    fun `replayable task state and unresolved review are rejected`() {
        assertTrue(runCatching {
            PluginBackupArchive.validateLocalTasksSnapshot(localTasksJson(state = "running"))
        }.isFailure)
        assertTrue(runCatching {
            PluginBackupArchive.validateLocalTasksSnapshot(
                """
                {
                  "format": "${PluginBackupArchive.LOCAL_TASKS_FORMAT}",
                  "formatVersion": 1,
                  "settingsVersion": 1,
                  "ledgerVersion": 1,
                  "ledger": {
                    "version": 1,
                    "tasks": {
                      "lt_1": {
                        "taskId": "lt_1",
                        "state": "interrupted",
                        "pendingReview": [{"id": "ltr_1"}]
                      }
                    }
                  }
                }
                """.trimIndent(),
            )
        }.isFailure)
    }

    @Test
    fun `secret fields are not allowed in the native snapshot`() {
        val withSecret = """
            {
              "format": "${PluginBackupArchive.LOCAL_TASKS_FORMAT}",
              "formatVersion": 1,
              "settingsVersion": 1,
              "ledgerVersion": 1,
              "settings": {"apiKey": "secret"}
            }
        """.trimIndent()
        assertTrue(runCatching {
            PluginBackupArchive.validateLocalTasksSnapshot(withSecret)
        }.isFailure)
        assertTrue(runCatching {
            writeArchive(localTasks = withSecret)
        }.isFailure)
    }

    @Test
    fun `invalid plugin id is rejected before the archive is created`() {
        val destination = File(temporary.root, "bad.zip")
        assertTrue(runCatching {
            PluginBackupArchive.write(
                destination = destination,
                pluginId = "../escape",
                pluginVersion = null,
                localDataZip = null,
                localTasksJson = localTasksJson(),
                createdAt = 1L,
            )
        }.isFailure)
        assertFalse(destination.exists())
    }
}
