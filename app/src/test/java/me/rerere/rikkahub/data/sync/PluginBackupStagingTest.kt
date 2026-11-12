package me.rerere.rikkahub.data.sync

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginBackupStagingTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun archiveOf(pluginBackup: ByteArray?): File {
        val archive = File(temporary.root, "backup-${System.nanoTime()}.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
            if (pluginBackup != null) {
                zip.putNextEntry(ZipEntry("${PLUGIN_BACKUP_ENTRY_PREFIX}demo.zip"))
                zip.write(pluginBackup)
                zip.closeEntry()
            }
        }
        return archive
    }

    @Test
    fun pluginBackupIsStagedForValidationAndNeverInstalledAsLiveFile() = runBlocking {
        val root = temporary.newFolder("root")
        val transaction = BackupRestoreTransaction(root).apply { begin() }
        val archive = archiveOf(byteArrayOf(1, 2, 3, 4))

        val staged = stageBackupArchive(archive, transaction, true, false, true)

        assertEquals(listOf("demo"), staged.pluginBackups.map { it.pluginId })
        assertTrue(staged.files.isEmpty())
        assertTrue(staged.pluginBackups.single().file.isFile)
        assertFalse(File(root, "files/.plugin_backup_restore/demo.zip").exists())
        transaction.discardUnprepared()
        assertFalse(File(root, "files/.plugin_backup_restore").exists())
    }

    @Test
    fun pluginBackupIsIgnoredWhenPluginsAreNotRestored() = runBlocking {
        val root = temporary.newFolder("root")
        val transaction = BackupRestoreTransaction(root).apply { begin() }
        val archive = archiveOf(byteArrayOf(1, 2, 3, 4))

        val staged = stageBackupArchive(archive, transaction, true, false, false)

        assertTrue(staged.pluginBackups.isEmpty())
        assertTrue(staged.files.isEmpty())
        transaction.discardUnprepared()
    }

    @Test
    fun pluginBackupPathTraversalIsRejectedBeforeStaging() = runBlocking {
        val root = temporary.newFolder("root")
        val transaction = BackupRestoreTransaction(root).apply { begin() }
        val archive = File(temporary.root, "traversal.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("${PLUGIN_BACKUP_ENTRY_PREFIX}../escape.zip"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }

        assertTrue(runCatching {
            stageBackupArchive(archive, transaction, true, false, true)
        }.isFailure)
        transaction.discardUnprepared()
        assertFalse(File(temporary.root, "escape.zip").exists())
    }
}
