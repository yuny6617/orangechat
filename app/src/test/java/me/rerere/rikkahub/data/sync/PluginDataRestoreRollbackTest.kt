package me.rerere.rikkahub.data.sync

import android.app.Application
import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.plugin.localdata.LocalDataPaths
import me.rerere.rikkahub.plugin.localdata.LocalDataTestEnvironment
import me.rerere.rikkahub.plugin.manager.PluginLocalDataStorage
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Failure-after-first-plugin coverage for the logical plugin restore.
 *
 * Two valid backups are staged through the production validation path. The second plugin's fault is
 * injected only when its post-apply task-host release runs, so both plugins (and both raw native
 * localtasks preference sets) have already been replaced when the failure is raised. The rollback
 * must put them back exactly as they were before the attempt. The transaction tests also prove the
 * existing files/settings rollback still runs and the file journal is discarded.
 *
 * Test names in this class stay short on purpose: Robolectric derives its per-test directory name
 * from the class and method name, and that directory hosts the plugin SQLite database. Native
 * Windows SQLite rejects a database path of 252 characters (its `-journal` file then hits
 * `MAX_PATH`), so a long method name can make every database open fail with `SQLITE_CANTOPEN`
 * before a single assertion runs. See `LOCALDATA_TEST_HOST_DIAGNOSIS.md`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class PluginDataRestoreRollbackTest {
    private val now = 1_700_000_000_000L
    private val environments = mutableListOf<LocalDataTestEnvironment>()
    private val workDirectories = mutableListOf<File>()

    @After
    fun tearDown() {
        environments.forEach { runCatching { it.close() } }
        workDirectories.forEach { runCatching { it.deleteRecursively() } }
        environments.firstOrNull()?.let { env ->
            runCatching { File(env.context.filesDir, ".plugin_restore_rollback").deleteRecursively() }
        }
        PluginRestoreRollbackScope.commitActive()
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

    private fun snapshotBoth(
        first: LocalDataTestEnvironment,
        second: LocalDataTestEnvironment,
    ): Pair<PluginBackupFile, PluginBackupFile> {
        val directory = File(first.context.cacheDir, "rollback-snapshot-${UUID.randomUUID()}")
            .also { workDirectories += it }
        val files = PluginDataBackupCoordinator(first.context, clock = { now })
            .snapshot(directory, listOf(first.pluginId, second.pluginId))
        return files.single { it.pluginId == first.pluginId } to
            files.single { it.pluginId == second.pluginId }
    }

    private fun stageDirectory(env: LocalDataTestEnvironment): File =
        File(env.context.cacheDir, "rollback-stage-${UUID.randomUUID()}")
            .also { workDirectories += it }

    /**
     * A real two-plugin [PluginDataBackupCoordinator.stage], with a fault injected into the second
     * plugin's third task-host release. In [PluginDataRestorePlan.apply], that is the release after
     * the second plugin's localdata and localtasks have both been written.
     */
    private fun failingPlan(
        first: LocalDataTestEnvironment,
        firstBackup: PluginBackupFile,
        second: LocalDataTestEnvironment,
        secondBackup: PluginBackupFile,
    ): PluginDataRestorePlan {
        var secondPluginReleases = 0
        var injected = false
        val coordinator = PluginDataBackupCoordinator(
            context = first.context,
            clock = { now },
            releaseTasks = { pluginId ->
                if (pluginId == second.pluginId) {
                    secondPluginReleases++
                    if (secondPluginReleases == 3 && !injected) {
                        injected = true
                        throw IllegalStateException("injected second-plugin failure")
                    }
                }
                releasePluginTaskHosts(pluginId)
            },
        )
        return coordinator.stage(
            backups = listOf(
                first.pluginId to firstBackup.file,
                second.pluginId to secondBackup.file,
            ),
            workRoot = stageDirectory(first),
        )
    }

    private fun singlePluginPlan(
        env: LocalDataTestEnvironment,
        backup: PluginBackupFile,
    ): PluginDataRestorePlan = PluginDataBackupCoordinator(env.context, clock = { now }).stage(
        backups = listOf(env.pluginId to backup.file),
        workRoot = stageDirectory(env),
    )

    private data class LiveMismatch(
        val first: LocalDataTestEnvironment,
        val second: LocalDataTestEnvironment,
        val firstBackup: PluginBackupFile,
        val secondBackup: PluginBackupFile,
        val firstRawBefore: Pair<String?, String?>,
        val secondRawBefore: Pair<String?, String?>,
    )

    /** Each snapshot holds one post; each live namespace then gains a second one plus newer settings. */
    private fun prepareLiveMismatch(): LiveMismatch {
        val first = environment()
        val second = environment()
        val originalMedia = first.importMedia(1)
        first.createPost(
            body = "A-original",
            mediaIds = listOf(originalMedia.resourceId),
            clientKey = "a-original",
        )
        second.createPost(body = "B-original", clientKey = "b-original")
        writeLocalTasks(
            env = first,
            settings = LocalTaskSettings(enabled = true, participants = listOf(first.assistantA.id)),
            ledger = LocalTaskLedgerState(
                tasks = mapOf(
                    "lt_original_a" to LocalTaskRecord(
                        taskId = "lt_original_a",
                        state = LocalTaskState.COMPLETED,
                        createdAt = now,
                        updatedAt = now,
                    ),
                ),
            ),
        )
        writeLocalTasks(
            env = second,
            settings = LocalTaskSettings(enabled = false, participants = listOf(second.assistantB.id)),
            ledger = LocalTaskLedgerState(
                tasks = mapOf(
                    "lt_original_b" to LocalTaskRecord(
                        taskId = "lt_original_b",
                        state = LocalTaskState.COMPLETED,
                        createdAt = now,
                        updatedAt = now,
                    ),
                ),
            ),
        )
        val (firstBackup, secondBackup) = snapshotBoth(first, second)

        val liveMedia = first.importMedia(2)
        first.createPost(
            body = "A-live",
            mediaIds = listOf(liveMedia.resourceId),
            clientKey = "a-live",
        )
        second.createPost(body = "B-live", clientKey = "b-live")
        writeLocalTasks(
            env = first,
            settings = LocalTaskSettings(enabled = false, participants = listOf(first.assistantB.id)),
            ledger = LocalTaskLedgerState(
                tasks = mapOf(
                    "lt_live_a" to LocalTaskRecord(
                        taskId = "lt_live_a",
                        state = LocalTaskState.COMPLETED,
                        createdAt = now + 1,
                        updatedAt = now + 1,
                    ),
                ),
            ),
        )
        writeLocalTasks(
            env = second,
            settings = LocalTaskSettings(enabled = true, participants = listOf(second.assistantA.id)),
            ledger = LocalTaskLedgerState(
                tasks = mapOf(
                    "lt_live_b" to LocalTaskRecord(
                        taskId = "lt_live_b",
                        state = LocalTaskState.COMPLETED,
                        createdAt = now + 2,
                        updatedAt = now + 2,
                    ),
                ),
            ),
        )
        return LiveMismatch(
            first = first,
            second = second,
            firstBackup = firstBackup,
            secondBackup = secondBackup,
            firstRawBefore = rawLocalTasks(first),
            secondRawBefore = rawLocalTasks(second),
        )
    }

    @Test
    fun `second plugin failure restores both plugins raw state`() {
        val live = prepareLiveMismatch()
        val plan = failingPlan(live.first, live.firstBackup, live.second, live.secondBackup)

        val failure = runCatching {
            plan.apply(
                restoreLocalTaskSettings = true,
                validAssistantIds = setOf(live.first.assistantA.id, live.first.assistantB.id),
            )
        }

        assertTrue(failure.isFailure)
        val message = failure.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("已按相反顺序回滚"))
        assertFalse(message.contains("不会自动回滚"))
        assertEquals(
            setOf("A-original", "A-live"),
            live.first.store.exportSnapshot().posts.map { it.body }.toSet(),
        )
        assertEquals(
            setOf("B-original", "B-live"),
            live.second.store.exportSnapshot().posts.map { it.body }.toSet(),
        )
        assertEquals(2, live.first.store.exportSnapshot().media.size)
        assertEquals(live.firstRawBefore, rawLocalTasks(live.first))
        assertEquals(live.secondRawBefore, rawLocalTasks(live.second))
        assertTrue(
            File(live.first.context.filesDir, ".plugin_restore_rollback").listFiles().isNullOrEmpty(),
        )
    }

    @Test
    fun `first empty namespace is deleted by rollback`() {
        val first = environment()
        val second = environment()
        first.createPost(body = "only-in-backup", clientKey = "only")
        second.createPost(body = "B-original", clientKey = "b-original")
        val (firstBackup, secondBackup) = snapshotBoth(first, second)

        first.database.close()
        val deletion = PluginLocalDataStorage(first.context).delete(first.pluginId)
        assertTrue(deletion.complete)
        assertFalse(PluginLocalDataStorage(first.context).exists(first.pluginId))

        val plan = failingPlan(first, firstBackup, second, secondBackup)
        val failure = runCatching {
            plan.apply(
                restoreLocalTaskSettings = true,
                validAssistantIds = setOf(first.assistantA.id, second.assistantA.id),
            )
        }

        assertTrue(failure.isFailure)
        assertFalse(PluginLocalDataStorage(first.context).exists(first.pluginId))
        val paths = LocalDataPaths(first.pluginId, first.context.filesDir)
        assertFalse(paths.root.exists())
        assertFalse(first.context.getDatabasePath(paths.databaseName).exists())
        assertEquals(
            setOf("B-original"),
            second.store.exportSnapshot().posts.map { it.body }.toSet(),
        )
    }

    @Test
    fun `plugin failure rolls back settings and files with the journal`() {
        val live = prepareLiveMismatch()
        val plan = failingPlan(live.first, live.firstBackup, live.second, live.secondBackup)
        val root = File(live.first.context.cacheDir, "rollback-tx-${UUID.randomUUID()}")
            .also { workDirectories += it }
            .apply { mkdirs() }
        val settingsFile = File(root, "files/settings.json").apply {
            parentFile!!.mkdirs()
            writeText("original-settings")
        }
        val liveFile = File(root, "files/live.txt").apply { writeText("old-live") }
        val transaction = BackupRestoreTransaction(root).apply { begin() }
        transaction.stagePath("files/live.txt").apply {
            parentFile!!.mkdirs()
            writeText("new-live")
        }
        var settingsRolledBack = false
        val settingsSession = RestorePreferencesSession<String>(
            file = settingsFile,
            apply = { settingsFile.writeText(it) },
            rollback = {
                settingsFile.writeText("original-settings")
                settingsRolledBack = true
            },
        )

        val failure = runCatching {
            runBlocking {
                commitBackupRestore<String, String>(
                    transaction = transaction,
                    files = listOf("files/live.txt"),
                    databases = emptyList(),
                    settingsSession = settingsSession,
                    settings = "restored-settings",
                    pluginSession = null,
                    plugins = null,
                    pluginRestore = {
                        plan.apply(
                            restoreLocalTaskSettings = true,
                            validAssistantIds = setOf(live.first.assistantA.id, live.first.assistantB.id),
                        )
                    },
                )
            }
        }

        assertTrue(failure.isFailure)
        assertTrue(settingsRolledBack)
        assertEquals("original-settings", settingsFile.readText())
        assertEquals("old-live", liveFile.readText())
        assertFalse(transaction.isPrepared)
        assertEquals(
            setOf("A-original", "A-live"),
            live.first.store.exportSnapshot().posts.map { it.body }.toSet(),
        )
        assertEquals(
            setOf("B-original", "B-live"),
            live.second.store.exportSnapshot().posts.map { it.body }.toSet(),
        )
        assertEquals(2, live.first.store.exportSnapshot().media.size)
        assertEquals(live.firstRawBefore, rawLocalTasks(live.first))
        assertEquals(live.secondRawBefore, rawLocalTasks(live.second))
    }

    @Test
    fun `transaction finish failure restores the applied plugin`() {
        val live = prepareLiveMismatch()
        val plan = singlePluginPlan(live.first, live.firstBackup)
        val root = File(live.first.context.cacheDir, "rollback-finish-${UUID.randomUUID()}")
            .also { workDirectories += it }
            .apply { mkdirs() }
        val settingsFile = File(root, "files/settings.json").apply {
            parentFile!!.mkdirs()
            writeText("original-settings")
        }
        val liveFile = File(root, "files/live.txt").apply { writeText("old-live") }
        val transaction = BackupRestoreTransaction(root).apply { begin() }
        transaction.stagePath("files/live.txt").apply {
            parentFile!!.mkdirs()
            writeText("new-live")
        }
        // Make the final READY write fail: finish() writes a file at this path.
        File(root, "files/.backup_restore/.ready").mkdirs()
        var settingsRolledBack = false
        val settingsSession = RestorePreferencesSession<String>(
            file = settingsFile,
            apply = { settingsFile.writeText(it) },
            rollback = {
                settingsFile.writeText("original-settings")
                settingsRolledBack = true
            },
        )

        val failure = runCatching {
            runBlocking {
                commitBackupRestore<String, String>(
                    transaction = transaction,
                    files = listOf("files/live.txt"),
                    databases = listOf("databases/fake"),
                    settingsSession = settingsSession,
                    settings = "restored-settings",
                    pluginSession = null,
                    plugins = null,
                    pluginRestore = {
                        plan.apply(
                            restoreLocalTaskSettings = true,
                            validAssistantIds = setOf(live.first.assistantA.id),
                        )
                    },
                )
            }
        }

        assertTrue(failure.isFailure)
        assertTrue(settingsRolledBack)
        assertEquals("original-settings", settingsFile.readText())
        assertEquals("old-live", liveFile.readText())
        assertFalse(transaction.isPrepared)
        assertEquals(
            setOf("A-original", "A-live"),
            live.first.store.exportSnapshot().posts.map { it.body }.toSet(),
        )
        assertEquals(2, live.first.store.exportSnapshot().media.size)
        assertEquals(live.firstRawBefore, rawLocalTasks(live.first))
    }
}
