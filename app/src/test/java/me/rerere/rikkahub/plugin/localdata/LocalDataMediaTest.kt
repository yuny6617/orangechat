/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localdata

import android.app.Application
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class LocalDataMediaTest {
    private val env = LocalDataTestEnvironment()

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun `import writes an opaque persistent original and a rebuildable thumbnail`() {
        val imported = env.media.importImage(ByteArray(80) { it.toByte() }, "image/png", "photo.png")
        assertTrue(imported.created)
        assertTrue(imported.media.resourceId.matches(Regex("^ldm_[A-Za-z0-9]{8,64}$")))
        val original = env.media.originalFile(imported.media.resourceId)
        assertNotNull(original)
        assertTrue(original!!.isFile)
        assertEquals(env.database.paths.originalsDir.canonicalFile, original.parentFile?.canonicalFile)
        assertNotNull(env.media.ensureThumbnail(imported.media.resourceId))
        assertEquals(
            LocalDataMediaUrl.thumb(imported.media.resourceId),
            imported.media.thumbUrl,
        )
        assertNotNull(env.media.openUrl(imported.media.thumbUrl))
        assertNotNull(env.media.openUrl(imported.media.originalUrl))
    }

    @Test
    fun `duplicate bytes reuse the same resource id`() {
        val first = env.media.importImage(ByteArray(80) { it.toByte() }, "image/png", "a.png")
        val second = env.media.importImage(ByteArray(80) { it.toByte() }, "image/png", "b.png")
        assertFalse(second.created)
        assertEquals(first.media.resourceId, second.media.resourceId)
    }

    @Test
    fun `clearing the cache removes thumbnails but never published originals`() {
        val media = env.importMedia()
        val post = env.createPost(mediaIds = listOf(media.resourceId))
        val original = env.media.originalFile(media.resourceId)!!
        assertTrue(env.media.ensureThumbnail(media.resourceId)!!.isFile)

        val report = env.media.clearTransientCache()
        assertTrue(report.thumbnailsDeleted >= 1)
        assertTrue(original.isFile)
        assertTrue(env.media.ensureThumbnail(media.resourceId)?.isFile == true)

        assertEquals(0, env.media.sweepUnreferenced { env.store.isMediaReferenced(it) })
        assertTrue(original.isFile)
        assertTrue(env.call(
            LocalDataMethods.DELETE,
            buildJsonObject { put("postId", post.id) },
        ).ok)
        assertEquals(1, env.media.sweepUnreferenced { env.store.isMediaReferenced(it) })
        assertFalse(original.isFile)
    }

    @Test
    fun `unreferenced import can be swept without touching referenced media`() {
        val referenced = env.importMedia(1)
        env.createPost(mediaIds = listOf(referenced.resourceId))
        val orphan = env.importMedia(2)
        val orphanFile = env.media.originalFile(orphan.resourceId)!!

        assertEquals(1, env.media.sweepUnreferenced { env.store.isMediaReferenced(it) })
        assertFalse(orphanFile.isFile)
        assertTrue(env.media.originalFile(referenced.resourceId)!!.isFile)
    }

    @Test
    fun `resource ids never become path segments`() {
        assertNull(env.media.originalFile("../../etc/passwd"))
        assertNull(env.media.originalFile("ldm_short"))
        assertNull(LocalDataMediaUrl.parse("https://localdata.invalid/media/../x/thumb"))
        assertNull(LocalDataMediaUrl.parse("https://localdata.invalid/media/ldm_12345678/thumb?x=1"))
        assertNull(LocalDataMediaUrl.parse("https://evil.invalid/media/ldm_12345678/thumb"))
        assertNull(LocalDataMediaUrl.parse("https://localdata.invalid/media/ldm_12345678/other"))
        assertNotNull(LocalDataMediaUrl.parse("https://localdata.invalid/media/ldm_12345678/thumb"))
    }

    @Test
    fun `oversized empty and unsupported images are rejected`() {
        assertEquals(
            "payload_too_large",
            runCatching {
                env.media.importImage(ByteArray(LocalDataApi.MAX_MEDIA_BYTES + 1), "image/png")
            }.exceptionOrNull()?.let { (it as LocalDataException).code.wire },
        )
        assertEquals(
            "invalid_request",
            runCatching { env.media.importImage(ByteArray(3), "image/png") }
                .exceptionOrNull()?.let { (it as LocalDataException).code.wire },
        )
        val svgStore = LocalDataMediaStore(
            env.database,
            LocalImageInspector { _, _ -> LocalImageInfo("image/svg+xml", 4, 4) },
            env.thumbnailer,
        ) { env.now }
        assertEquals(
            "invalid_request",
            runCatching { svgStore.importImage(ByteArray(64) { 1 }, "image/svg+xml") }
                .exceptionOrNull()?.let { (it as LocalDataException).code.wire },
        )
    }

    @Test
    fun `chat avatar media is referenced and survives cache clearing and sweep`() {
        val avatar = env.importMedia(3)
        val post = env.createPost(
            body = "avatar card",
            chatSnapshot = LocalChatSnapshotDto(
                messages = listOf(
                    LocalChatMessageDto(role = "user", text = "hi", avatarMediaId = avatar.resourceId),
                ),
            ),
        )
        assertTrue(env.store.isMediaReferenced(avatar.resourceId))
        assertTrue(avatar.resourceId in env.store.referencedMediaIds())
        val original = env.media.originalFile(avatar.resourceId)!!
        assertTrue(env.media.ensureThumbnail(avatar.resourceId)!!.isFile)

        env.media.clearTransientCache()
        assertTrue(original.isFile)
        assertEquals(0, env.media.sweepUnreferenced { env.store.isMediaReferenced(it) })
        assertTrue(original.isFile)

        assertTrue(env.call(
            LocalDataMethods.DELETE,
            buildJsonObject { put("postId", post.id) },
        ).ok)
        assertEquals(1, env.media.sweepUnreferenced { env.store.isMediaReferenced(it) })
        assertFalse(original.isFile)
    }

    @Test
    fun `avatar references must be controlled media ids or emoji`() {
        fun sanitizeSnapshot(snapshot: LocalChatSnapshotDto): Throwable? = runCatching {
            LocalDataChatSnapshots.sanitize(snapshot)
        }.exceptionOrNull()

        fun sanitize(message: LocalChatMessageDto): Throwable? =
            sanitizeSnapshot(LocalChatSnapshotDto(messages = listOf(message)))

        assertTrue(
            sanitize(LocalChatMessageDto(role = "user", avatarMediaId = "https://evil.example/a.png"))
                is IllegalArgumentException,
        )
        assertTrue(
            sanitize(LocalChatMessageDto(role = "user", avatarMediaId = "data:image/png;base64,AA=="))
                is IllegalArgumentException,
        )
        assertTrue(
            sanitize(
                LocalChatMessageDto(
                    role = "user",
                    avatarEmoji = "😀",
                    avatarMediaId = "ldm_abcdefgh",
                ),
            ) is IllegalArgumentException,
        )
        assertTrue(
            sanitize(LocalChatMessageDto(role = "user", avatarEmoji = "😀".repeat(17)))
                is IllegalArgumentException,
        )
        assertTrue(
            sanitizeSnapshot(
                LocalChatSnapshotDto(
                    messages = listOf(
                        LocalChatMessageDto(role = "user", avatarMediaId = "ldm_11111111"),
                        LocalChatMessageDto(role = "assistant", avatarMediaId = "ldm_22222222"),
                        LocalChatMessageDto(role = "assistant", avatarMediaId = "ldm_33333333"),
                    ),
                ),
            ) is IllegalArgumentException,
        )

        val sanitized = LocalDataChatSnapshots.sanitize(
            LocalChatSnapshotDto(
                messages = listOf(LocalChatMessageDto(role = "user", avatarEmoji = " 🙂 ")),
            ),
        )
        assertEquals("🙂", sanitized!!.messages.single().avatarEmoji)

        val blank = LocalDataChatSnapshots.sanitize(
            LocalChatSnapshotDto(
                messages = listOf(
                    LocalChatMessageDto(role = "user", avatarMediaId = "", avatarEmoji = ""),
                ),
            ),
        )
        assertNull(blank!!.messages.single().avatarMediaId)
        assertNull(blank.messages.single().avatarEmoji)

        val legacy = LocalDataChatSnapshots.decode(
            """{"messages":[{"role":"user","name":"a","text":"hi","media":[]}]}""",
        )
        assertNull(legacy!!.messages.single().avatarMediaId)
        assertNull(legacy.messages.single().avatarEmoji)
    }

    @Test
    fun `chat avatar media must already exist in the local store`() {
        val envelope = env.call(
            LocalDataMethods.CREATE,
            buildJsonObject {
                put("body", "card")
                put("visibility", "all_assistants")
                put("chatSnapshot", buildJsonObject {
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("role", "user")
                            put("text", "hi")
                            put("avatarMediaId", "ldm_99999999")
                        })
                    })
                })
            },
        )
        assertEquals("invalid_request", errorCode(envelope))
    }

    @Test
    fun `media metadata is hidden from assistants that cannot see the post`() {
        val media = env.importMedia()
        env.createPost(
            visibility = "selected",
            selected = listOf(env.assistantA.id),
            mediaIds = listOf(media.resourceId),
        )
        val actorB = LocalDataActor.Assistant(env.assistantB.id, env.assistantB.name)
        val envelope = env.call(
            LocalDataMethods.MEDIA_DESCRIBE,
            buildJsonObject { put("resourceId", media.resourceId) },
            actorB,
        )
        assertEquals("not_found", errorCode(envelope))

        val actorA = LocalDataActor.Assistant(env.assistantA.id, env.assistantA.name)
        assertTrue(env.call(
            LocalDataMethods.MEDIA_DESCRIBE,
            buildJsonObject { put("resourceId", media.resourceId) },
            actorA,
        ).ok)
    }
}
