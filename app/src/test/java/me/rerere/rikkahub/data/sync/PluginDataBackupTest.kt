package me.rerere.rikkahub.data.sync

import android.app.Application
import android.content.Context
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import me.rerere.rikkahub.plugin.localdata.LocalDataTestEnvironment
import me.rerere.rikkahub.plugin.localdata.PluginBackupArchive
import me.rerere.rikkahub.plugin.localtasks.LocalAssistantPolicy
import me.rerere.rikkahub.plugin.localtasks.LocalTaskIds
import me.rerere.rikkahub.plugin.localtasks.LocalTaskJson
import me.rerere.rikkahub.plugin.localtasks.LocalTaskLedgerState
import me.rerere.rikkahub.plugin.localtasks.LocalTaskMode
import me.rerere.rikkahub.plugin.localtasks.LocalTaskPendingActionDto
import me.rerere.rikkahub.plugin.localtasks.LocalTaskPostUsage
import me.rerere.rikkahub.plugin.localtasks.LocalTaskRecord
import me.rerere.rikkahub.plugin.localtasks.LocalTaskSettings
import me.rerere.rikkahub.plugin.localtasks.LocalTaskSettingsCodec
import me.rerere.rikkahub.plugin.localtasks.LocalTaskState
import me.rerere.rikkahub.plugin.localtasks.LocalTasksApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Staging-first tests: a bad archive must be rejected while live moments and native task
 * preferences are byte-for-byte unchanged. No Gradle-driven instrumentation is required.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class PluginDataBackupTest {
    private val env = LocalDataTestEnvironment()
    private val now = 1_700_000_000_000L
    private val workDirectories = mutableListOf<File>()

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
        workDirectories.forEach { it.deleteRecursively() }
        env.close()
    }

    private fun coordinator() = PluginDataBackupCoordinator(env.context, clock = { now })

    private fun prefs() = env.context.getSharedPreferences(
        LocalTaskIds.storeName(env.pluginId),
        Context.MODE_PRIVATE,
    )

    private fun writeLocalTasks(
        settings: LocalTaskSettings,
        ledger: LocalTaskLedgerState? = null,
    ) {
        val editor = prefs().edit()
            .putString(LocalTasksApi.KEY_SETTINGS, LocalTaskSettingsCodec.encode(settings))
        ledger?.let {
            val encoded = LocalTaskJson.task.encodeToString(LocalTaskLedgerState.serializer(), it)
            editor.putString(LocalTasksApi.KEY_LEDGER, encoded)
        }
        assertTrue(editor.commit())
    }

    private fun readSettings(): LocalTaskSettings = LocalTaskSettingsCodec.decode(
        prefs().getString(LocalTasksApi.KEY_SETTINGS, null),
    )

    private fun readLedger(): LocalTaskLedgerState? = prefs()
        .getString(LocalTasksApi.KEY_LEDGER, null)
        ?.let { LocalTaskJson.task.decodeFromString(LocalTaskLedgerState.serializer(), it) }

    private fun snapshot(): PluginBackupFile {
        val directory = File(env.context.cacheDir, "pb-${UUID.randomUUID()}")
        workDirectories += directory
        return coordinator().snapshot(directory, listOf(env.pluginId))
            .single { it.pluginId == env.pluginId }
    }

    private fun stage(backup: PluginBackupFile): PluginDataRestorePlan {
        val workRoot = File(env.context.cacheDir, "stage-${UUID.randomUUID()}")
        workDirectories += workRoot
        return coordinator().stage(listOf(env.pluginId to backup.file), workRoot)
    }

    private fun readEntries(source: File): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipFile(source).use { zip ->
            zip.entries().toList().forEach { entry ->
                result[entry.name] = zip.getInputStream(entry).use { it.readBytes() }
            }
        }
        return result
    }

    private fun rewrite(source: File, replacements: Map<String, ByteArray>): File {
        val destination = File(env.context.cacheDir, "rewritten-${UUID.randomUUID()}.zip")
        workDirectories += destination
        ZipOutputStream(destination.outputStream()).use { output ->
            readEntries(source).forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(replacements[name] ?: bytes)
                output.closeEntry()
            }
        }
        return destination
    }

    private fun runningLedger(postId: String): LocalTaskLedgerState = LocalTaskLedgerState(
        dayKey = 1,
        modelCallsToday = 2,
        posts = mapOf(postId to LocalTaskPostUsage(modelCalls = 2, lastTurnAt = now)),
        tasks = mapOf(
            "lt_running" to LocalTaskRecord(
                taskId = "lt_running",
                mode = LocalTaskMode.INTERACT,
                postId = postId,
                assistantId = env.assistantA.id,
                state = LocalTaskState.RUNNING,
                pendingReview = listOf(
                    LocalTaskPendingActionDto(
                        id = "ltr_1",
                        kind = "reply",
                        assistantId = env.assistantA.id,
                        assistantName = env.assistantA.name,
                        postId = postId,
                        body = "pending review body",
                    ),
                ),
                createdAt = now,
                updatedAt = now,
            ),
            "lt_done" to LocalTaskRecord(
                taskId = "lt_done",
                state = LocalTaskState.COMPLETED,
                createdAt = now,
                updatedAt = now,
                finishedAt = now,
            ),
        ),
    )

    @Test
    fun `snapshot contains localdata media and a stopped localtasks ledger`() {
        val media = env.importMedia(1)
        val post = env.createPost(
            body = "hello moments",
            mediaIds = listOf(media.resourceId),
            clientKey = "post-1",
        )
        writeLocalTasks(
            settings = LocalTaskSettings(
                enabled = true,
                participants = listOf(env.assistantA.id, env.assistantB.id),
            ),
            ledger = runningLedger(post.id),
        )

        val backup = snapshot()
        val inspectDir = File(env.context.cacheDir, "inspect-${UUID.randomUUID()}")
        workDirectories += inspectDir
        inspectDir.mkdirs()
        val contents = PluginBackupArchive.inspect(backup.file, inspectDir)

        assertEquals(env.pluginId, contents.manifest.pluginId)
        assertNotNull(contents.localDataZip)
        assertNotNull(contents.localTasksJson)
        val payload = LocalTasksBackupCodec.decode(contents.localTasksJson!!)
        val ledger = payload.ledger!!
        val running = ledger.tasks.getValue("lt_running")
        assertEquals(LocalTaskState.INTERRUPTED, running.state)
        assertTrue(running.pendingReview.isEmpty())
        assertEquals(LocalTaskState.COMPLETED, ledger.tasks.getValue("lt_done").state)
        assertFalse(contents.localTasksJson.contains("apiKey", ignoreCase = true))
        assertFalse(contents.localTasksJson.contains("grant", ignoreCase = true))
    }

    @Test
    fun `tampered localdata hash is rejected during staging without touching live data`() {
        env.createPost(body = "keep me", clientKey = "keep")
        val backup = snapshot()
        val postsBefore = env.store.exportSnapshot().posts.map { it.id }.toSet()
        val tampered = rewrite(
            backup.file,
            mapOf(PluginBackupArchive.LOCAL_DATA_ENTRY to byteArrayOf(9, 9, 9)),
        )

        val workRoot = File(env.context.cacheDir, "stage-tampered-${UUID.randomUUID()}")
        workDirectories += workRoot
        val result = runCatching {
            coordinator().stage(listOf(env.pluginId to tampered), workRoot)
        }

        assertTrue(result.isFailure)
        assertEquals(postsBefore, env.store.exportSnapshot().posts.map { it.id }.toSet())
    }

    @Test
    fun `restore replaces localdata and keeps native settings unless asked`() {
        val media = env.importMedia(1)
        val onlyPost = env.createPost(
            body = "snapshot only",
            mediaIds = listOf(media.resourceId),
            clientKey = "only",
        )
        writeLocalTasks(
            settings = LocalTaskSettings(
                enabled = true,
                participants = listOf(env.assistantA.id, env.assistantB.id),
                assistantPolicies = mapOf(
                    env.assistantA.id to LocalAssistantPolicy.AUTO_PUBLISH,
                    env.assistantB.id to LocalAssistantPolicy.DRAFT_ONLY,
                ),
            ),
            ledger = runningLedger(onlyPost.id),
        )
        val backup = snapshot()

        env.createPost(body = "added after snapshot", clientKey = "later")
        writeLocalTasks(
            settings = LocalTaskSettings(enabled = false, participants = listOf(env.assistantB.id)),
        )
        assertEquals(2, env.store.exportSnapshot().posts.size)

        val plan = stage(backup)
        plan.apply(
            restoreLocalTaskSettings = false,
            validAssistantIds = setOf(env.assistantA.id),
        )
        assertEquals(1, env.store.exportSnapshot().posts.size)
        assertEquals(1, env.store.exportSnapshot().media.size)
        assertFalse(readSettings().enabled)

        plan.apply(
            restoreLocalTaskSettings = true,
            validAssistantIds = setOf(env.assistantA.id),
        )
        val restored = readSettings()
        assertTrue(restored.enabled)
        assertEquals(listOf(env.assistantA.id), restored.participants)
        assertEquals(LocalAssistantPolicy.AUTO_PUBLISH, restored.assistantPolicies[env.assistantA.id])
        assertFalse(restored.assistantPolicies.containsKey(env.assistantB.id))
        val ledger = readLedger()!!
        assertEquals(LocalTaskState.INTERRUPTED, ledger.tasks.getValue("lt_running").state)
        assertTrue(ledger.tasks.getValue("lt_running").pendingReview.isEmpty())
    }

    @Test
    fun `plugin id mismatch is rejected before the live namespace is opened`() {
        val backup = snapshot()
        val workRoot = File(env.context.cacheDir, "stage-id-${UUID.randomUUID()}")
        workDirectories += workRoot

        val result = runCatching {
            coordinator().stage(listOf("other.plugin" to backup.file), workRoot)
        }

        assertTrue(result.isFailure)
        assertTrue(env.store.exportSnapshot().posts.isEmpty())
    }
}
