/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.manager

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.util.UUID
import me.rerere.rikkahub.plugin.localdata.LocalDataException
import me.rerere.rikkahub.plugin.localdata.LocalDataPaths
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.PluginManifest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class PluginUninstallTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pluginId = "test.localmoments.${UUID.randomUUID()}"
    private val otherPluginId = "test.other.${UUID.randomUUID()}"
    private val storage = PluginLocalDataStorage(context)

    private val databasesDir: File
        get() = context.getDatabasePath("anchor").parentFile!!

    @After
    fun tearDown() {
        runCatching { storage.delete(pluginId) }
        runCatching { storage.delete(otherPluginId) }
        runCatching { PluginHostGrantStore(context).revokeAll(pluginId) }
        runCatching { PluginHostGrantStore(context).revokeAll(otherPluginId) }
    }

    @Test
    fun layoutUsesHashedNamespaceAndConfinedDatabase() {
        val paths = LocalDataPaths(pluginId, context.filesDir)
        val layout = storage.layout(pluginId)

        assertEquals(paths.namespace, layout.namespace)
        assertEquals(32, layout.namespace.length)
        assertTrue(layout.namespace.matches(Regex("^[0-9a-f]{32}$")))
        assertEquals(
            File(context.filesDir, "plugin_localdata/${paths.namespace}").absolutePath,
            layout.root.absolutePath,
        )
        assertEquals("localdata_v1_${paths.namespace}.db", layout.databaseName)
        assertEquals(
            context.getDatabasePath(layout.databaseName).absolutePath,
            layout.databaseFile.absolutePath,
        )
        // Nothing is created merely by resolving the layout.
        assertFalse(layout.exists())
    }

    @Test
    fun rejectsPluginIdsThatCouldEscapeTheNamespace() {
        listOf("", ".", "..", "../escape", "a/b", "a\\b", "id with space", "id|pipe").forEach { invalid ->
            assertThrows(LocalDataException::class.java) {
                PluginLocalDataLayout(invalid, context.filesDir, databasesDir)
            }
        }
    }

    @Test
    fun rejectsSymlinkedNamespacePointingOutsideThePluginDirectory() {
        val layout = storage.layout(pluginId)
        val base = File(context.filesDir, "plugin_localdata").apply { mkdirs() }
        val outside = File(context.filesDir, "outside-${UUID.randomUUID()}").apply { mkdirs() }
        val link = File(base, layout.namespace)
        val created = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        try {
            assumeTrue("Filesystem does not allow symlinks", created)
            assertThrows(IllegalArgumentException::class.java) { layout.requireSafe() }
        } finally {
            runCatching { Files.deleteIfExists(link.toPath()) }
            outside.deleteRecursively()
        }
    }

    @Test
    fun deleteRemovesOnlyItsOwnDatabaseAndMedia() {
        val layout = storage.layout(pluginId)
        val original = File(layout.root, "media/originals/ldm_abcdefgh.png")
        original.parentFile!!.mkdirs()
        original.writeBytes(byteArrayOf(1, 2, 3))
        layout.databaseFile.parentFile!!.mkdirs()
        layout.databaseFile.writeBytes(byteArrayOf(4, 5, 6))
        layout.databaseSidecars.forEach { it.writeBytes(byteArrayOf(7)) }

        val otherLayout = storage.layout(otherPluginId)
        File(otherLayout.root, "keep.txt").apply {
            parentFile!!.mkdirs()
            writeText("keep")
        }
        otherLayout.databaseFile.parentFile!!.mkdirs()
        otherLayout.databaseFile.writeBytes(byteArrayOf(9))

        val result = storage.delete(pluginId)

        assertTrue(result.existed)
        assertTrue(result.complete)
        assertFalse(layout.root.exists())
        assertFalse(layout.databaseFile.exists())
        layout.databaseSidecars.forEach { assertFalse(it.exists()) }
        assertEquals("keep", File(otherLayout.root, "keep.txt").readText())
        assertTrue(otherLayout.databaseFile.exists())
        assertTrue(File(context.filesDir, "plugin_localdata").isDirectory)
    }

    @Test
    fun deleteDoesNotFollowSymlinksInsideTheMediaDirectory() {
        val layout = storage.layout(pluginId)
        val media = File(layout.root, "media/originals").apply { mkdirs() }
        val outside = File(context.filesDir, "outside-${UUID.randomUUID()}").apply { mkdirs() }
        val keep = File(outside, "keep.txt").apply { writeText("keep") }
        val link = File(media, "linked-originals")
        val created = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        try {
            assumeTrue("Filesystem does not allow symlinks", created)
            assertTrue(storage.delete(pluginId).complete)
            assertEquals("keep", keep.readText())
            assertFalse(layout.root.exists())
        } finally {
            runCatching { Files.deleteIfExists(link.toPath()) }
            outside.deleteRecursively()
        }
    }

    @Test
    fun deleteOfUnknownPluginIsANoOp() {
        val emptyPluginId = "test.empty.${UUID.randomUUID()}"
        val result = storage.delete(emptyPluginId)

        assertFalse(result.existed)
        assertTrue(result.complete)
        assertFalse(storage.exists(emptyPluginId))
    }

    @Test
    fun preserveIsTheDefaultAndDeleteIsExplicit() {
        assertFalse(PluginUninstallDataPolicy.PRESERVE_LOCAL_DATA.removesLocalData)
        assertTrue(PluginUninstallDataPolicy.DELETE_LOCAL_DATA.removesLocalData)
    }

    @Test
    fun revokeRemovesLocalDataGrantAndBothDirectionsOfToolGrants() {
        val localDataGrants = context.getSharedPreferences(
            PluginHostGrantStore.LOCAL_DATA_GRANTS_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        localDataGrants.edit()
            .putStringSet(pluginId, setOf("localdata.read"))
            .putStringSet(otherPluginId, setOf("localdata.read"))
            .commit()

        val toolGrants = context.getSharedPreferences(
            PluginHostGrantStore.TOOL_GRANTS_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        val callerKey = "$pluginId|$otherPluginId|toolA"
        val targetKey = "$otherPluginId|$pluginId|toolB"
        val unrelatedKey = "$otherPluginId|third.plugin|toolC"
        toolGrants.edit()
            .putBoolean(callerKey, true)
            .putBoolean(targetKey, true)
            .putBoolean(unrelatedKey, true)
            .commit()

        val revocation = PluginHostGrantStore(context).revokeAll(pluginId)

        assertTrue(revocation.localDataGrantRemoved)
        assertEquals(2, revocation.toolGrantsRemoved)
        assertFalse(localDataGrants.contains(pluginId))
        assertTrue(localDataGrants.contains(otherPluginId))
        assertFalse(toolGrants.contains(callerKey))
        assertFalse(toolGrants.contains(targetKey))
        assertTrue(toolGrants.contains(unrelatedKey))
    }

    @Test
    fun onlyPluginsThatDeclareLocalDataGetTheDataChoice() {
        fun pluginWith(permissions: List<String>): PluginInfo = PluginInfo(
            manifest = PluginManifest(
                id = "test.declares.${UUID.randomUUID()}",
                name = "Test",
                description = "",
                version = "1",
                author = "Test",
                icon = "T",
                entry = "main.js",
                permissions = permissions,
            ),
            directory = File("test"),
            isEnabled = true,
        )

        assertTrue(pluginWith(listOf("localdata.read")).declaresLocalData())
        assertFalse(pluginWith(listOf("ai_chat", "webview")).declaresLocalData())
        assertFalse(pluginWith(emptyList()).declaresLocalData())
    }

    @Test
    fun revokesGrantsForLegacyIdThatLocalDataRejects() {
        val legacyId = "Legacy Plugin ${UUID.randomUUID()}"
        val grants = context.getSharedPreferences(
            PluginHostGrantStore.LOCAL_DATA_GRANTS_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        grants.edit().putStringSet(legacyId, setOf("localdata.read")).commit()

        // Localdata itself rejects this id, but uninstall must still be able to revoke its grants.
        assertThrows(LocalDataException::class.java) {
            PluginLocalDataLayout(legacyId, context.filesDir, databasesDir)
        }
        val revocation = PluginHostGrantStore(context).revokeAll(legacyId)
        assertTrue(revocation.localDataGrantRemoved)
        assertFalse(grants.contains(legacyId))
    }

    @Test
    fun incompleteDeletionOnlyMattersWhenDeleteWasRequested() {
        val kept = PluginUninstallResult(
            pluginId = "p",
            dataPolicy = PluginUninstallDataPolicy.PRESERVE_LOCAL_DATA,
            localDataExisted = true,
            localDataStillPresent = true,
            localDataDeletionComplete = true,
            localDataGrantsRevoked = true,
            revokedToolGrants = 0,
            stoppedActiveTasks = 0,
        )
        assertFalse(kept.localDataDeletionIncomplete)
        assertTrue(
            kept.copy(
                dataPolicy = PluginUninstallDataPolicy.DELETE_LOCAL_DATA,
                localDataDeletionComplete = false,
            ).localDataDeletionIncomplete,
        )
        assertFalse(
            kept.copy(
                dataPolicy = PluginUninstallDataPolicy.DELETE_LOCAL_DATA,
                localDataDeletionComplete = true,
            ).localDataDeletionIncomplete,
        )
    }

    @Test
    fun stagedUninstallPolicyIsConsumedOnce() {
        val stagedId = "test.staged.${UUID.randomUUID()}"

        assertNull(PluginUninstallPolicyRelay.consume(stagedId))
        PluginUninstallPolicyRelay.stage(stagedId, PluginUninstallDataPolicy.DELETE_LOCAL_DATA)
        assertEquals(
            PluginUninstallDataPolicy.DELETE_LOCAL_DATA,
            PluginUninstallPolicyRelay.consume(stagedId),
        )
        assertNull(PluginUninstallPolicyRelay.consume(stagedId))
    }

    @Test
    fun toolGrantKeyOnlyMatchesCompleteThreePartKeys() {
        assertTrue(PluginHostGrantStore.toolGrantKeyBelongsToPlugin("caller|target|tool", "caller"))
        assertTrue(PluginHostGrantStore.toolGrantKeyBelongsToPlugin("caller|target|tool", "target"))
        assertFalse(PluginHostGrantStore.toolGrantKeyBelongsToPlugin("caller|target|tool", "tool"))
        assertFalse(PluginHostGrantStore.toolGrantKeyBelongsToPlugin("caller|target", "caller"))
        assertFalse(PluginHostGrantStore.toolGrantKeyBelongsToPlugin("caller|target|tool|extra", "extra"))
    }
}
