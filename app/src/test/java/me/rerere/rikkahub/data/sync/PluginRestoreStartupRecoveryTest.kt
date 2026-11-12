package me.rerere.rikkahub.data.sync

import android.app.Application
import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.plugin.localdata.LocalDataTestEnvironment
import me.rerere.rikkahub.plugin.localtasks.LocalTaskHosts
import me.rerere.rikkahub.plugin.localtasks.LocalTaskIds
import me.rerere.rikkahub.plugin.localtasks.LocalTaskJson
import me.rerere.rikkahub.plugin.localtasks.LocalTaskLedgerState
import me.rerere.rikkahub.plugin.localtasks.LocalTaskRecord
import me.rerere.rikkahub.plugin.localtasks.LocalTaskSettings
import me.rerere.rikkahub.plugin.localtasks.LocalTaskSettingsCodec
import me.rerere.rikkahub.plugin.localtasks.LocalTaskState
import me.rerere.rikkahub.plugin.localtasks.LocalTasksApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Startup consumption of the durable plugin rollback journal.
 *
 * Each test force-commits a real logical restore, then throws away every in-memory object and lets a
 * new "process" recover only from the files on disk. The journal must track the file/settings
 * database transaction: commit the plugin data only after the staged database is active, and restore
 * the plugin namespace plus the raw native localtasks state when activation fails. A crash before
 * the in-process commit must roll back instead of leaving the journal for manual cleanup.
 *
 * Test names in this class stay short on purpose: Robolectric derives its per-test directory name
 * from the class and method name, and that directory hosts the plugin SQLite database. Native
 * Windows SQLite rejects a database path of 252 characters (its `-journal` file then hits
 * `MAX_PATH`), so a long method name can make every database open fail with `SQLITE_CANTOPEN`
 * before a single assertion runs. See `LOCALDATA_TEST_HOST_DIAGNOSIS.md`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class PluginRestoreStartupRecoveryTest {
    private val now = 1_700_000_000_000L
    private val environments = mutableListOf<LocalDataTestEnvironment>()
    private val workDirectories = mutableListOf<File>()

    @After
    fun tearDown() {
        PluginRestoreRollbackScope.endCommitWindow()
        PluginRestoreRollbackScope.commitActive()
        environments.forEach { runCatching { it.close() } }
        environments.forEach { env ->
            runCatching {
                PluginRestoreStartupRecovery.rollbackRoot(env.context.filesDir).deleteRecursively()
            }
        }
        workDirectories.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun environment(): LocalDataTestEnvironment =
        LocalDataTestEnvironment().also { environments += it }

    private fun preferences(env: LocalDataTestEnvironment) = env.context.getSharedPreferences(
        LocalTaskIds.storeName(env.pluginId),
        Context.MODE_PRIVATE,
    )

    private fun writeLocalTasks(
        env: LocalDataTestEnvironment,
        settings: LocalTaskSettings,
        ledger: LocalTaskLedgerState,
    ) {
        val editor = preferences(env).edit()
            .putString(LocalTasksApi.KEY_SETTINGS, LocalTaskSettingsCodec.encode(settings))
            .putString(
                LocalTasksApi.KEY_LEDGER,
                LocalTaskJson.task.encodeToString(LocalTaskLedgerState.serializer(), ledger),
            )
        assertTrue(editor.commit())
    }

    private fun rawLocalTasks(env: LocalDataTestEnvironment): Pair<String?, String?> =
        preferences(env).getString(LocalTasksApi.KEY_SETTINGS, null) to
            preferences(env).getString(LocalTasksApi.KEY_LEDGER, null)

    private fun posts(env: LocalDataTestEnvironment): Set<String> =
        env.store.exportSnapshot().posts.map { it.body }.toSet()

    private fun snapshot(env: LocalDataTestEnvironment): PluginBackupFile {
        val directory = File(env.context.cacheDir, "startup-snapshot-${UUID.randomUUID()}")
            .also { workDirectories += it }
        return PluginDataBackupCoordinator(env.context, clock = { now })
            .snapshot(directory, listOf(env.pluginId))
            .single { it.pluginId == env.pluginId }
    }

    private fun stageDirectory(env: LocalDataTestEnvironment): File =
        File(env.context.cacheDir, "startup-stage-${UUID.randomUUID()}")
            .also { workDirectories += it }

    private fun ledger(taskId: String, at: Long) = LocalTaskLedgerState(
        tasks = mapOf(
            taskId to LocalTaskRecord(
                taskId = taskId,
                state = LocalTaskState.COMPLETED,
                createdAt = at,
                updatedAt = at,
            ),
        ),
    )

    /**
     * Snapshot state is `snapshot-post` + snapshot localtasks; the live state is
     * `snapshot-post` + `live-post` + live localtasks. A successful plugin restore therefore shows
     * only `snapshot-post`, and a rollback shows the live state again.
     */
    private data class AwaitingRestore(
        val env: LocalDataTestEnvironment,
        val root: File,
        val settingsFile: File,
        val liveFile: File,
        val rawBefore: Pair<String?, String?>,
    )

    private fun prepareAwaitingDatabaseRestore(): AwaitingRestore {
        val env = environment()
        writeLocalTasks(
            env = env,
            settings = LocalTaskSettings(enabled = true, participants = listOf(env.assistantA.id)),
            ledger = ledger("lt_snapshot", now),
        )
        env.createPost(body = "snapshot-post", clientKey = "snapshot")
        val backup = snapshot(env)

        writeLocalTasks(
            env = env,
            settings = LocalTaskSettings(enabled = false, participants = listOf(env.assistantB.id)),
            ledger = ledger("lt_live", now + 1),
        )
        env.createPost(body = "live-post", clientKey = "live")
        val rawBefore = rawLocalTasks(env)

        val plan = PluginDataBackupCoordinator(env.context, clock = { now }).stage(
            backups = listOf(env.pluginId to backup.file),
            workRoot = stageDirectory(env),
        )
        val root = File(env.context.cacheDir, "startup-tx-${UUID.randomUUID()}")
            .also { workDirectories += it }
            .apply { mkdirs() }
        val settingsFile = File(root, "files/settings.json").apply {
            parentFile!!.mkdirs()
            writeText("original-settings")
        }
        val liveFile = File(root, "files/upload/live.txt").apply { parentFile!!.mkdirs(); writeText("old-file") }
        val transaction = BackupRestoreTransaction(root).apply { begin() }
        transaction.stagePath("files/upload/live.txt").apply {
            parentFile!!.mkdirs()
            writeText("new-file")
        }
        transaction.stagePath("databases/rikka_hub").apply {
            parentFile!!.mkdirs()
            writeText("new-db")
        }
        runBlocking {
            commitBackupRestore<String, String>(
                transaction = transaction,
                files = listOf("files/upload/live.txt"),
                databases = listOf("databases/rikka_hub"),
                settingsSession = RestorePreferencesSession(
                    file = settingsFile,
                    apply = { settingsFile.writeText(it) },
                    rollback = { settingsFile.writeText("original-settings") },
                ),
                settings = "restored-settings",
                pluginSession = null,
                plugins = null,
                pluginRestore = {
                    plan.apply(
                        restoreLocalTaskSettings = true,
                        validAssistantIds = setOf(env.assistantA.id, env.assistantB.id),
                    )
                },
            )
        }
        assertEquals("new-file", liveFile.readText())
        assertEquals("restored-settings", settingsFile.readText())
        assertEquals(setOf("snapshot-post"), posts(env))
        return AwaitingRestore(env, root, settingsFile, liveFile, rawBefore)
    }

    @Test
    fun `startup database activation commits the plugin journal`() {
        val restore = prepareAwaitingDatabaseRestore()
        val journalRoot = PluginRestoreStartupRecovery.rollbackRoot(restore.env.context.filesDir)
        val attempt = journalRoot.listFiles()!!.single()
        // Self-describing journal: the deferred database activation is the only remaining step.
        assertTrue(File(attempt, "journal.json").isFile)
        assertTrue(File(attempt, "awaitingDatabase").isFile)
        assertTrue(File(attempt, "database-pending").isFile)

        // A new process only reads files; no session or plan object from the upload survives.
        val recovery = BackupRestoreTransaction.recoverBeforeStartupWithOutcome(restore.root) { committed ->
            PluginRestoreStartupRecovery.recordStartupDecision(restore.env.context.filesDir, committed)
        }
        assertEquals(BackupRestoreStartupOutcome.COMMITTED, recovery.outcome)
        assertNull(recovery.error)
        assertNull(recovery.decisionError)
        assertEquals("new-db", File(restore.root, "databases/rikka_hub").readText())

        val failure = PluginRestoreStartupRecovery.recover(
            context = restore.env.context,
            filesDir = restore.env.context.filesDir,
            outcome = recovery.outcome,
        )
        assertNull(failure)
        assertTrue(journalRoot.listFiles().isNullOrEmpty())
        assertEquals(setOf("snapshot-post"), posts(restore.env))
        assertEquals("restored-settings", restore.settingsFile.readText())
    }

    @Test
    fun `failed startup activation rolls back plugin and task state`() {
        val restore = prepareAwaitingDatabaseRestore()
        // A non-empty directory at the database target makes the READY activation fail without
        // touching the transaction's original snapshots.
        val blocker = File(restore.root, "databases/rikka_hub")
        assertTrue(blocker.mkdirs())
        File(blocker, "occupied.txt").writeText("block-activation")

        val recovery = BackupRestoreTransaction.recoverBeforeStartupWithOutcome(restore.root) { committed ->
            PluginRestoreStartupRecovery.recordStartupDecision(restore.env.context.filesDir, committed)
        }
        assertEquals(BackupRestoreStartupOutcome.ROLLED_BACK, recovery.outcome)
        assertNotNull(recovery.error)
        assertNull(recovery.decisionError)
        assertEquals("old-file", restore.liveFile.readText())
        assertEquals("original-settings", restore.settingsFile.readText())

        val failure = PluginRestoreStartupRecovery.recover(
            context = restore.env.context,
            filesDir = restore.env.context.filesDir,
            outcome = recovery.outcome,
        )
        assertNull(failure)
        assertTrue(
            PluginRestoreStartupRecovery.rollbackRoot(restore.env.context.filesDir)
                .listFiles().isNullOrEmpty(),
        )
        assertEquals(setOf("snapshot-post", "live-post"), posts(restore.env))
        assertEquals(restore.rawBefore, rawLocalTasks(restore.env))
        // Raw state is put back; no task host is created, so nothing replays assistant work.
        assertNull(LocalTaskHosts.peek(restore.env.pluginId))

        // The journal was consumed by this launch, not left behind for a human to interpret.
        val second = BackupRestoreTransaction.recoverBeforeStartupWithOutcome(restore.root)
        assertEquals(BackupRestoreStartupOutcome.NONE, second.outcome)
        assertNull(
            PluginRestoreStartupRecovery.recover(
                context = restore.env.context,
                filesDir = restore.env.context.filesDir,
                outcome = second.outcome,
            ),
        )
    }

    @Test
    fun `recorded rollback decision survives a crash before plugin recovery`() {
        val restore = prepareAwaitingDatabaseRestore()
        assertNull(
            PluginRestoreStartupRecovery.recordStartupDecision(restore.env.context.filesDir, false),
        )
        // Simulate the process dying after the file journal was retired but before the plugin
        // rollback ran. The persisted decision, not the missing transaction journal, must win.
        File(restore.root, "files/.backup_restore").deleteRecursively()

        val failure = PluginRestoreStartupRecovery.recover(
            context = restore.env.context,
            filesDir = restore.env.context.filesDir,
            outcome = BackupRestoreStartupOutcome.NONE,
        )
        assertNull(failure)
        assertTrue(
            PluginRestoreStartupRecovery.rollbackRoot(restore.env.context.filesDir)
                .listFiles().isNullOrEmpty(),
        )
        assertEquals(setOf("snapshot-post", "live-post"), posts(restore.env))
        assertEquals(restore.rawBefore, rawLocalTasks(restore.env))
    }

    @Test
    fun `startup rolls back attempt without database restore`() {
        val env = environment()
        writeLocalTasks(
            env = env,
            settings = LocalTaskSettings(enabled = true, participants = listOf(env.assistantA.id)),
            ledger = ledger("lt_snapshot", now),
        )
        env.createPost(body = "snapshot-post", clientKey = "snapshot")
        val backup = snapshot(env)
        writeLocalTasks(
            env = env,
            settings = LocalTaskSettings(enabled = false, participants = listOf(env.assistantB.id)),
            ledger = ledger("lt_live", now + 1),
        )
        env.createPost(body = "live-post", clientKey = "live")
        val rawBefore = rawLocalTasks(env)

        var releases = 0
        var crashCopy: File? = null
        val coordinator = PluginDataBackupCoordinator(
            context = env.context,
            clock = { now },
            releaseTasks = { pluginId ->
                releases++
                // Release 3 happens after localdata and native settings were replaced.
                if (releases == 3) {
                    val source = PluginRestoreStartupRecovery.rollbackRoot(env.context.filesDir)
                        .listFiles()?.singleOrNull()
                    if (source != null) {
                        val destination = File(env.context.cacheDir, "crash-journal-${UUID.randomUUID()}")
                            .also { workDirectories += it }
                        source.copyRecursively(destination, overwrite = true)
                        crashCopy = destination
                    }
                }
                releasePluginTaskHosts(pluginId)
            },
        )
        val plan = coordinator.stage(
            backups = listOf(env.pluginId to backup.file),
            workRoot = stageDirectory(env),
        )
        plan.apply(
            restoreLocalTaskSettings = true,
            validAssistantIds = setOf(env.assistantA.id, env.assistantB.id),
        )
        assertEquals(setOf("snapshot-post"), posts(env))

        // The upload process "dies" here: restore only the copied journal, then rebuild everything.
        val copy = requireNotNull(crashCopy)
        val journalRoot = PluginRestoreStartupRecovery.rollbackRoot(env.context.filesDir)
        journalRoot.deleteRecursively()
        assertTrue(journalRoot.mkdirs())
        check(copy.copyRecursively(File(journalRoot, copy.name), overwrite = true))
        val attempt = journalRoot.listFiles()!!.single()
        assertTrue(File(attempt, "applying").isFile)

        val failure = PluginRestoreStartupRecovery.recover(
            context = env.context,
            filesDir = env.context.filesDir,
            outcome = BackupRestoreStartupOutcome.NONE,
        )
        assertNull(failure)
        assertEquals(setOf("snapshot-post", "live-post"), posts(env))
        assertEquals(rawBefore, rawLocalTasks(env))
        assertTrue(journalRoot.listFiles().isNullOrEmpty())
    }

    @Test
    fun `preparing journal is discarded without touching live data`() {
        val env = environment()
        env.createPost(body = "snapshot-post", clientKey = "snapshot")
        val backup = snapshot(env)
        env.createPost(body = "live-post", clientKey = "live")

        var releases = 0
        var preparingCopy: File? = null
        val coordinator = PluginDataBackupCoordinator(
            context = env.context,
            clock = { now },
            releaseTasks = { pluginId ->
                releases++
                // Release 1 happens after the preparing marker but before any snapshot/live write.
                if (releases == 1) {
                    val source = PluginRestoreStartupRecovery.rollbackRoot(env.context.filesDir)
                        .listFiles()?.singleOrNull()
                    if (source != null) {
                        val destination =
                            File(env.context.cacheDir, "preparing-journal-${UUID.randomUUID()}")
                                .also { workDirectories += it }
                        source.copyRecursively(destination, overwrite = true)
                        preparingCopy = destination
                    }
                }
                releasePluginTaskHosts(pluginId)
            },
        )
        val plan = coordinator.stage(
            backups = listOf(env.pluginId to backup.file),
            workRoot = stageDirectory(env),
        )
        plan.apply(restoreLocalTaskSettings = false)
        assertEquals(setOf("snapshot-post"), posts(env))

        val copy = requireNotNull(preparingCopy)
        val journalRoot = PluginRestoreStartupRecovery.rollbackRoot(env.context.filesDir)
        journalRoot.deleteRecursively()
        assertTrue(journalRoot.mkdirs())
        check(copy.copyRecursively(File(journalRoot, copy.name), overwrite = true))
        val attempt = journalRoot.listFiles()!!.single()
        assertTrue(File(attempt, "preparing").isFile)

        val failure = PluginRestoreStartupRecovery.recover(
            context = env.context,
            filesDir = env.context.filesDir,
            outcome = BackupRestoreStartupOutcome.NONE,
        )
        assertNull(failure)
        assertTrue(journalRoot.listFiles().isNullOrEmpty())
        // No live namespace was touched, so the commit that followed the copy stays restored.
        assertEquals(setOf("snapshot-post"), posts(env))
    }

    @Test
    fun `a new restore is refused while a plugin rollback journal is pending`() {
        val env = environment()
        val root = File(env.context.cacheDir, "blocked-tx-${UUID.randomUUID()}")
            .also { workDirectories += it }
            .apply { mkdirs() }
        val pending = File(
            PluginRestoreStartupRecovery.rollbackRoot(File(root, "files")),
            "pending-attempt",
        )
        assertTrue(pending.mkdirs())

        val error = runCatching { BackupRestoreTransaction(root).begin() }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error?.message.orEmpty().contains("previous plugin restore"))
    }
}
