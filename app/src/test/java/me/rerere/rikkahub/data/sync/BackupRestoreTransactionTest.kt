package me.rerere.rikkahub.data.sync

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupRestoreTransactionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun installFailureCanRestoreReplacedAndNewFiles() {
        val root = temporary.newFolder("root")
        val original = File(root, "files/upload/old.txt").apply { parentFile!!.mkdirs(); writeText("old") }
        val added = File(root, "files/upload/new.txt")
        val transaction = BackupRestoreTransaction(root).apply { begin() }
        transaction.stagePath("files/upload/old.txt").apply { parentFile!!.mkdirs(); writeText("replacement") }
        transaction.stagePath("files/upload/new.txt").writeText("new")
        transaction.prepare(listOf("files/upload/old.txt", "files/upload/new.txt"), emptyList(), emptyList())

        var moves = 0
        assertTrue(runCatching {
            transaction.installFiles { from, to ->
                moves++
                if (moves == 3) false else from.renameTo(to)
            }
        }.isFailure)
        transaction.beginRollback()
        transaction.rollbackFiles()

        assertEquals("old", original.readText())
        assertFalse(added.exists())
    }

    @Test fun startupBeforeDatabaseReadyRollsBackFilesAndRawPreferences() {
        val root = temporary.newFolder("root")
        val file = File(root, "files/upload/item.txt").apply { parentFile!!.mkdirs(); writeText("old-file") }
        val preferences = File(root, "files/datastore/settings.preferences_pb")
            .apply { parentFile!!.mkdirs(); writeText("old-settings") }
        val transaction = BackupRestoreTransaction(root).apply { begin() }
        transaction.stagePath("files/upload/item.txt").apply { parentFile!!.mkdirs(); writeText("new-file") }
        transaction.stagePath("databases/rikka_hub").apply { parentFile!!.mkdirs(); writeText("new-db") }
        transaction.prepare(
            files = listOf("files/upload/item.txt"),
            preferences = listOf("files/datastore/settings.preferences_pb"),
            databases = listOf("databases/rikka_hub"),
        )
        transaction.installFiles()
        preferences.writeText("new-settings")

        assertEquals(null, BackupRestoreTransaction.recoverBeforeStartup(root))
        assertEquals("old-file", file.readText())
        assertEquals("old-settings", preferences.readText())
        assertFalse(File(root, "databases/rikka_hub").exists())
    }

    /**
     * The ordinary rollback path (a prepared transaction that never reached `.ready`) must persist
     * the plugin verdict itself: the plugin journal is only retired after `onDecision` succeeded.
     * Without this, a crash between file rollback and plugin recovery would keep the restored
     * plugin data next to rolled-back files.
     */
    @Test fun startupBeforeDatabaseReadyRecordsRollbackDecision() {
        val root = temporary.newFolder("root")
        val file = File(root, "files/upload/item.txt").apply { parentFile!!.mkdirs(); writeText("old-file") }
        val preferences = File(root, "files/datastore/settings.preferences_pb")
            .apply { parentFile!!.mkdirs(); writeText("old-settings") }
        val transaction = BackupRestoreTransaction(root).apply { begin() }
        transaction.stagePath("files/upload/item.txt").apply { parentFile!!.mkdirs(); writeText("new-file") }
        transaction.stagePath("databases/rikka_hub").apply { parentFile!!.mkdirs(); writeText("new-db") }
        transaction.prepare(
            files = listOf("files/upload/item.txt"),
            preferences = listOf("files/datastore/settings.preferences_pb"),
            databases = listOf("databases/rikka_hub"),
        )
        transaction.installFiles()
        preferences.writeText("new-settings")

        val decisions = mutableListOf<Boolean>()
        val recovery = BackupRestoreTransaction.recoverBeforeStartupWithOutcome(root) { committed ->
            decisions += committed
            null
        }

        assertEquals(BackupRestoreStartupOutcome.ROLLED_BACK, recovery.outcome)
        assertNull(recovery.error)
        assertNull(recovery.decisionError)
        assertEquals(listOf(false), decisions)
        assertEquals("old-file", file.readText())
        assertEquals("old-settings", preferences.readText())
        assertFalse(File(root, "databases/rikka_hub").exists())
        assertFalse(File(root, "files/.backup_restore").exists())
    }

    /**
     * A failed verdict write must keep the transaction journal and retry on the next launch; the
     * rollback itself is already durable and must remain idempotent when it runs again.
     */
    @Test fun normalRollbackRetriesDecisionWhenPluginVerdictWriteFails() {
        val root = temporary.newFolder("root")
        val file = File(root, "files/upload/item.txt").apply { parentFile!!.mkdirs(); writeText("old-file") }
        val preferences = File(root, "files/datastore/settings.preferences_pb")
            .apply { parentFile!!.mkdirs(); writeText("old-settings") }
        val transaction = BackupRestoreTransaction(root).apply { begin() }
        transaction.stagePath("files/upload/item.txt").apply { parentFile!!.mkdirs(); writeText("new-file") }
        transaction.prepare(
            files = listOf("files/upload/item.txt"),
            preferences = listOf("files/datastore/settings.preferences_pb"),
            databases = emptyList(),
        )
        transaction.installFiles()
        preferences.writeText("new-settings")

        val first = BackupRestoreTransaction.recoverBeforeStartupWithOutcome(root) {
            error("cannot persist plugin verdict")
        }
        assertEquals(BackupRestoreStartupOutcome.ROLLED_BACK, first.outcome)
        assertNull(first.error)
        assertNotNull(first.decisionError)
        assertEquals("old-file", file.readText())
        assertEquals("old-settings", preferences.readText())
        assertTrue(File(root, "files/.backup_restore").exists())

        val decisions = mutableListOf<Boolean>()
        val second = BackupRestoreTransaction.recoverBeforeStartupWithOutcome(root) { committed ->
            decisions += committed
            null
        }
        assertEquals(BackupRestoreStartupOutcome.ROLLED_BACK, second.outcome)
        assertNull(second.error)
        assertNull(second.decisionError)
        assertEquals(listOf(false), decisions)
        assertEquals("old-file", file.readText())
        assertEquals("old-settings", preferences.readText())
        assertFalse(File(root, "files/.backup_restore").exists())
    }

    @Test fun startupActivatesReadyDatabaseAndDropsAbsentOldSidecars() {
        val root = temporary.newFolder("root")
        val database = File(root, "databases/rikka_hub").apply { parentFile!!.mkdirs(); writeText("old-db") }
        val wal = File(root, "databases/rikka_hub-wal").apply { writeText("stale-wal") }
        val transaction = BackupRestoreTransaction(root).apply { begin() }
        transaction.stagePath("databases/rikka_hub").apply { parentFile!!.mkdirs(); writeText("new-db") }
        transaction.prepare(emptyList(), emptyList(), listOf("databases/rikka_hub", "databases/rikka_hub-wal"))
        transaction.finish(hasDatabase = true)

        assertEquals(null, BackupRestoreTransaction.recoverBeforeStartup(root))
        assertEquals("new-db", database.readText())
        assertFalse(wal.exists())
    }

    @Test fun stagePathRejectsTraversal() {
        val transaction = BackupRestoreTransaction(temporary.newFolder("root"))
        assertTrue(runCatching { transaction.stagePath("files/../../outside") }.isFailure)
    }

    @Test fun archiveTraversalIsRejectedBeforeExistingDataChanges() = runBlocking {
        val root = temporary.newFolder("root")
        val original = File(root, "files/upload/item.txt").apply { parentFile!!.mkdirs(); writeText("original") }
        val archive = File(temporary.root, "traversal.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("upload/../../outside"))
            zip.write("bad".toByteArray())
            zip.closeEntry()
        }
        val transaction = BackupRestoreTransaction(root).apply { begin() }

        assertTrue(runCatching {
            stageBackupArchive(archive, transaction, true, false, false)
        }.isFailure)
        transaction.discardUnprepared()
        assertEquals("original", original.readText())
        assertFalse(File(temporary.root, "outside").exists())
    }
}
