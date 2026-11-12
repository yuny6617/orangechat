/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localdata

import android.app.Application
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class LocalDataBackupTest {
    private val env = LocalDataTestEnvironment()
    private val actorA = LocalDataActor.Assistant(env.assistantA.id, env.assistantA.name, env.assistantA.avatar)

    @After
    fun tearDown() {
        env.close()
    }

    private fun backupFile(): File = File(env.context.cacheDir, "backup-${UUID.randomUUID()}.zip")

    private fun readZipEntries(file: File): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipFile(file).use { zip ->
            zip.entries().toList().forEach { entry ->
                result[entry.name] = zip.getInputStream(entry).use { it.readBytes() }
            }
        }
        return result
    }

    private fun rewriteZip(
        source: File,
        replacements: Map<String, ByteArray> = emptyMap(),
        extra: Map<String, ByteArray> = emptyMap(),
    ): File {
        val destination = backupFile()
        ZipOutputStream(destination.outputStream()).use { output ->
            readZipEntries(source).forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(replacements[name] ?: bytes)
                output.closeEntry()
            }
            extra.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return destination
    }

    @Test
    fun `export contains a versioned manifest payload and media but no keys`() {
        val media = env.importMedia()
        val post = env.createPost(
            body = "backup body",
            mediaIds = listOf(media.resourceId),
            chatSnapshot = LocalChatSnapshotDto(
                messages = listOf(LocalChatMessageDto("assistant", text = "card")),
            ),
        )
        env.comment(post.id, body = "comment")
        env.call(
            LocalDataMethods.LIKE_SET,
            buildJsonObject { put("postId", post.id); put("liked", true) },
        )
        env.call(
            LocalDataMethods.DRAFT_SAVE,
            buildJsonObject { put("body", "draft"); put("visibility", "all_assistants") },
        )

        val file = backupFile()
        val manifest = env.backup.export(file, 42L)
        assertEquals(LocalDataApi.BACKUP_FORMAT, manifest.format)
        assertEquals(1, manifest.formatVersion)
        assertEquals(1, manifest.counts.posts)
        assertEquals(1, manifest.counts.comments)
        assertEquals(1, manifest.counts.likes)
        assertEquals(1, manifest.counts.drafts)
        assertEquals(1, manifest.counts.media)

        val entries = readZipEntries(file)
        assertNotNull(entries[LocalDataBackup.MANIFEST_ENTRY])
        val payload = String(entries[LocalDataBackup.PAYLOAD_ENTRY]!!, Charsets.UTF_8)
        assertTrue(payload.contains("backup body"))
        assertFalse(payload.contains("apiKey"))
        assertFalse(payload.contains("api_key"))
        assertFalse(payload.contains("password"))
        assertFalse(payload.contains("secret"))
        assertTrue(entries.keys.any { it.startsWith(LocalDataBackup.MEDIA_PREFIX) })
    }

    @Test
    fun `restore regenerates ids and preserves posts comments likes drafts and media`() {
        val media = env.importMedia()
        val oldBytes = env.media.originalFile(media.resourceId)!!.readBytes()
        val post = env.createPost(body = "restore me", mediaIds = listOf(media.resourceId))
        env.comment(post.id, body = "kept comment")
        env.call(
            LocalDataMethods.LIKE_SET,
            buildJsonObject { put("postId", post.id); put("liked", true) },
        )
        env.call(
            LocalDataMethods.DRAFT_SAVE,
            buildJsonObject { put("body", "kept draft"); put("visibility", "all_assistants") },
        )
        val file = backupFile()
        env.backup.export(file)

        val report = env.backup.restore(
            file,
            LocalRestoreOptions(replaceExisting = true, regenerateIds = true),
        )
        assertEquals(1, report.posts)
        assertEquals(1, report.comments)
        assertEquals(1, report.likes)
        assertEquals(1, report.drafts)
        assertEquals(1, report.media)

        val snapshot = env.store.exportSnapshot()
        assertEquals(1, snapshot.posts.size)
        assertNotEquals(post.id, snapshot.posts.single().id)
        assertEquals("restore me", snapshot.posts.single().body)
        assertNotEquals(media.resourceId, snapshot.media.single().resourceId)
        val newFile = env.media.originalFile(snapshot.media.single().resourceId)
        assertNotNull(newFile)
        assertArrayEquals(oldBytes, newFile!!.readBytes())
        assertEquals(1, snapshot.comments.size)
        assertEquals(1, snapshot.likes.size)
        assertEquals(1, snapshot.drafts.size)

        val detail = env.store.getPost(
            LocalDataActor.User,
            snapshot.posts.single().id,
            4_000,
            10,
            null,
            true,
            true,
        )
        assertEquals(1, detail.post.commentCount)
        assertEquals(1, detail.post.likeCount)
        assertEquals(1, detail.post.media.size)
    }

    @Test
    fun `restore remaps chat avatar media references`() {
        val avatar = env.importMedia(3)
        env.createPost(
            chatSnapshot = LocalChatSnapshotDto(
                messages = listOf(
                    LocalChatMessageDto(role = "user", text = "hi", avatarMediaId = avatar.resourceId),
                ),
            ),
        )
        val file = backupFile()
        env.backup.export(file)

        env.backup.restore(
            file,
            LocalRestoreOptions(replaceExisting = true, regenerateIds = true),
        )
        val snapshot = env.store.exportSnapshot()
        val restored = snapshot.posts.single()
        val newAvatarId = snapshot.media.single().resourceId
        assertNotEquals(avatar.resourceId, newAvatarId)
        assertEquals(newAvatarId, restored.chatSnapshot!!.messages.single().avatarMediaId)
        assertTrue(env.media.isAvailable(newAvatarId))
        assertTrue(env.store.isMediaReferenced(newAvatarId))
    }

    @Test
    fun `assistant id map rewrites authors on restore`() {
        env.createPost(body = "assistant post", actor = actorA)
        val file = backupFile()
        env.backup.export(file)
        val newAssistantId = "99999999-9999-9999-9999-999999999999"
        env.backup.restore(
            file,
            LocalRestoreOptions(
                replaceExisting = true,
                regenerateIds = true,
                assistantIdMap = mapOf(env.assistantA.id to newAssistantId),
            ),
        )
        val post = env.store.exportSnapshot().posts.single()
        assertEquals(newAssistantId, post.authorId)
    }

    @Test
    fun `corrupt payload fails integrity and keeps existing data`() {
        env.createPost(body = "keep me")
        val file = backupFile()
        env.backup.export(file)
        val entries = readZipEntries(file)
        val corrupted = rewriteZip(
            file,
            replacements = mapOf(
                LocalDataBackup.PAYLOAD_ENTRY to "{\"posts\":[]}".toByteArray(Charsets.UTF_8),
            ),
        )
        val error = runCatching {
            env.backup.restore(corrupted, LocalRestoreOptions(replaceExisting = true))
        }.exceptionOrNull()
        assertTrue(error is LocalDataException)
        assertEquals("invalid_request", (error as LocalDataException).code.wire)
        assertEquals(1, env.list().items.size)
    }

    @Test
    fun `unsupported backup version is rejected before writing`() {
        env.createPost(body = "keep me")
        val file = backupFile()
        env.backup.export(file)
        val entries = readZipEntries(file)
        val manifest = LocalDataJson.json
            .parseToJsonElement(String(entries[LocalDataBackup.MANIFEST_ENTRY]!!, Charsets.UTF_8))
            .jsonObject
        val changed = JsonObject(
            manifest.toMutableMap().apply { put("formatVersion", JsonPrimitive(2)) },
        )
        val rewritten = rewriteZip(
            file,
            replacements = mapOf(
                LocalDataBackup.MANIFEST_ENTRY to changed.toString().toByteArray(Charsets.UTF_8),
            ),
        )
        val error = runCatching {
            env.backup.restore(rewritten, LocalRestoreOptions(replaceExisting = true))
        }.exceptionOrNull()
        assertTrue(error is LocalDataException)
        assertEquals("unsupported_version", (error as LocalDataException).code.wire)
        assertEquals(1, env.list().items.size)
    }

    @Test
    fun `unexpected traversal entry is rejected`() {
        env.createPost(body = "keep me")
        val file = backupFile()
        env.backup.export(file)
        val malicious = rewriteZip(file, extra = mapOf("../evil.txt" to byteArrayOf(1, 2, 3)))
        val error = runCatching {
            env.backup.restore(malicious, LocalRestoreOptions(replaceExisting = true))
        }.exceptionOrNull()
        assertTrue(error is LocalDataException)
        assertEquals("invalid_request", (error as LocalDataException).code.wire)
        assertEquals(1, env.list().items.size)
    }

    @Test
    fun `backup belongs to its plugin`() {
        env.createPost(body = "keep me")
        val file = backupFile()
        env.backup.export(file)
        val other = LocalDataTestEnvironment()
        try {
            val error = runCatching { other.backup.restore(file) }.exceptionOrNull()
            assertTrue(error is LocalDataException)
            assertEquals("conflict", (error as LocalDataException).code.wire)
        } finally {
            other.close()
        }
    }
}
