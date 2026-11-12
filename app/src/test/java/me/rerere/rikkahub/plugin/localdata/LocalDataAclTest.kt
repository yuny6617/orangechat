/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localdata

import android.app.Application
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
class LocalDataAclTest {
    private val env = LocalDataTestEnvironment()
    private val actorA = LocalDataActor.Assistant(env.assistantA.id, env.assistantA.name, env.assistantA.avatar)
    private val actorB = LocalDataActor.Assistant(env.assistantB.id, env.assistantB.name, env.assistantB.avatar)
    private val actorC = LocalDataActor.Assistant(env.assistantC.id, env.assistantC.name, env.assistantC.avatar)

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun `all assistants can list get search comment like and read media and snapshot`() {
        val media = env.importMedia()
        val snapshot = LocalChatSnapshotDto(
            title = "chat",
            messages = listOf(LocalChatMessageDto(role = "assistant", name = "A", text = "hi")),
            capturedAt = env.now,
        )
        val post = env.createPost(body = "hello everyone", mediaIds = listOf(media.resourceId), chatSnapshot = snapshot)

        val page = env.list(actorB)
        assertEquals(listOf(post.id), page.ids())
        val get = env.call(
            LocalDataMethods.GET,
            buildJsonObject { put("postId", post.id); put("includeMedia", true); put("includeChatSnapshot", true) },
            actorB,
        )
        assertTrue(get.ok)
        val detail = LocalDataJson.json.decodeFromJsonElement(
            LocalPostDetailDto.serializer(),
            get.data!!.jsonObject,
        )
        assertEquals("hello everyone", detail.post.body)
        assertEquals(1, detail.post.media.size)
        assertNotNull(detail.post.chatSnapshot)
        assertTrue(detail.post.selectedAssistantIds.isEmpty())

        assertEquals(1, env.call(
            LocalDataMethods.SEARCH,
            buildJsonObject { put("query", "everyone") },
            actorB,
        ).let { LocalDataJson.json.decodeFromJsonElement(LocalPostPageDto.serializer(), it.data!!.jsonObject) }.items.size)

        assertTrue(env.comment(post.id, actor = actorB).ok)
        assertTrue(env.call(
            LocalDataMethods.LIKE_SET,
            buildJsonObject { put("postId", post.id); put("liked", true) },
            actorB,
        ).ok)
        assertTrue(env.call(
            LocalDataMethods.MEDIA_DESCRIBE,
            buildJsonObject { put("resourceId", media.resourceId) },
            actorB,
        ).ok)
        assertEquals(1, env.list(actorC).items.size)
    }

    @Test
    fun `selected audience covers list get search comment like and media`() {
        val media = env.importMedia()
        val post = env.createPost(
            body = "for alice only",
            visibility = "selected",
            selected = listOf(env.assistantA.id),
            mediaIds = listOf(media.resourceId),
        )

        assertEquals(0, env.list(actorB).items.size)
        assertEquals(
            0,
            LocalDataJson.json.decodeFromJsonElement(
                LocalPostPageDto.serializer(),
                env.call(LocalDataMethods.SEARCH, buildJsonObject { put("query", "alice") }, actorB).data!!.jsonObject,
            ).items.size,
        )
        assertEquals("not_found", errorCode(env.call(
            LocalDataMethods.GET,
            buildJsonObject { put("postId", post.id) },
            actorB,
        )))
        assertEquals("not_found", errorCode(env.comment(post.id, actor = actorB)))
        assertEquals("not_found", errorCode(env.call(
            LocalDataMethods.LIKE_SET,
            buildJsonObject { put("postId", post.id); put("liked", true) },
            actorB,
        )))
        assertEquals("not_found", errorCode(env.call(
            LocalDataMethods.MEDIA_DESCRIBE,
            buildJsonObject { put("resourceId", media.resourceId) },
            actorB,
        )))

        assertEquals(1, env.list(actorA).items.size)
        assertTrue(env.comment(post.id, actor = actorA).ok)
        assertTrue(env.call(
            LocalDataMethods.MEDIA_DESCRIBE,
            buildJsonObject { put("resourceId", media.resourceId) },
            actorA,
        ).ok)
        assertEquals(0, env.list(actorC).items.size)
    }

    @Test
    fun `self audience is visible only to its author assistant and the local user`() {
        val post = env.createPost(
            body = "private note",
            visibility = "self",
            actor = actorA,
        )

        assertEquals(0, env.list(actorB).items.size)
        assertEquals(1, env.list(actorA).items.size)
        assertEquals(1, env.list().items.size)
        assertEquals("not_found", errorCode(env.call(
            LocalDataMethods.GET,
            buildJsonObject { put("postId", post.id) },
            actorB,
        )))
        assertTrue(env.call(
            LocalDataMethods.GET,
            buildJsonObject { put("postId", post.id) },
            actorA,
        ).ok)
    }

    @Test
    fun `deleted assistant keeps the author snapshot but loses access`() {
        val post = env.createPost(body = "history", actor = actorA)
        val withoutA = LocalAssistantDirectory.of(listOf(env.assistantB, env.assistantC))

        val userView = env.dispatcher.dispatch(
            LocalDataRequest(1, LocalDataMethods.GET, buildJsonObject { put("postId", post.id) }),
            LocalDataCallContext(
                actor = LocalDataActor.User,
                grant = LocalDataGrant.all(),
                assistantDirectory = withoutA,
            ),
        )
        assertTrue(userView.ok)
        val detail = LocalDataJson.json.decodeFromJsonElement(
            LocalPostDetailDto.serializer(),
            userView.data!!.jsonObject,
        )
        assertEquals("Alice", detail.post.author.name)
        assertEquals(AuthorKind.ASSISTANT, detail.post.author.kind)

        val forbidden = env.dispatcher.dispatch(
            LocalDataRequest(1, LocalDataMethods.GET, buildJsonObject { put("postId", post.id) }),
            LocalDataCallContext(
                actor = actorA,
                grant = LocalDataGrant.all(),
                assistantDirectory = withoutA,
            ),
        )
        assertFalse(forbidden.ok)
        assertEquals("forbidden", errorCode(forbidden))
    }

    @Test
    fun `comment reply must belong to the same visible post`() {
        val post = env.createPost()
        val first = env.comment(post.id, body = "first")
        assertTrue(first.ok)
        val commentId = LocalDataJson.json.decodeFromJsonElement(
            LocalCommentCreateResultDto.serializer(),
            first.data!!.jsonObject,
        ).comment.id

        assertTrue(env.comment(post.id, body = "reply", replyToId = commentId).ok)
        val other = env.createPost(body = "other")
        assertEquals(
            "invalid_request",
            errorCode(env.comment(other.id, body = "wrong", replyToId = commentId)),
        )
    }

    @Test
    fun `user sees all published posts and assistant selected list stays private`() {
        env.createPost(body = "all", visibility = "all_assistants")
        env.createPost(
            body = "selected",
            visibility = "selected",
            selected = listOf(env.assistantA.id, env.assistantB.id),
        )
        env.createPost(body = "self", visibility = "self", actor = actorA)

        val userPage = env.list()
        assertEquals(3, userPage.items.size)
        val selected = userPage.items.first { it.body == "selected" }
        assertEquals(listOf(env.assistantA.id, env.assistantB.id).sorted(), selected.selectedAssistantIds.sorted())
        val asAssistant = env.list(actorA).items.first { it.body == "selected" }
        assertTrue(asAssistant.selectedAssistantIds.isEmpty())
    }

    @Test
    fun `chat snapshot and media are omitted when the capability is absent`() {
        val media = env.importMedia()
        val snapshot = LocalChatSnapshotDto(messages = listOf(LocalChatMessageDto("user", text = "hi")))
        val post = env.createPost(mediaIds = listOf(media.resourceId), chatSnapshot = snapshot)
        val readOnly = LocalDataGrant.of(LocalDataCapability.READ)

        val envelope = env.call(
            LocalDataMethods.GET,
            buildJsonObject { put("postId", post.id); put("includeMedia", true); put("includeChatSnapshot", true) },
            actorB,
            grant = readOnly,
        )
        assertTrue(envelope.ok)
        val detail = LocalDataJson.json.decodeFromJsonElement(
            LocalPostDetailDto.serializer(),
            envelope.data!!.jsonObject,
        )
        assertTrue(detail.post.mediaOmitted)
        assertTrue(detail.post.media.isEmpty())
        assertTrue(detail.post.chatSnapshotOmitted)
        assertNull(detail.post.chatSnapshot)

        val listed = env.call(LocalDataMethods.LIST, JsonObject(emptyMap()), actorB, grant = readOnly)
        val page = LocalDataJson.json.decodeFromJsonElement(
            LocalPostPageDto.serializer(),
            listed.data!!.jsonObject,
        )
        assertTrue(page.items.single().mediaOmitted)
        assertTrue(page.items.single().media.isEmpty())
    }

    @Test
    fun `chat avatar media follows the post audience`() {
        val avatar = env.importMedia(3)
        env.createPost(
            visibility = "selected",
            selected = listOf(env.assistantA.id),
            chatSnapshot = LocalChatSnapshotDto(
                messages = listOf(
                    LocalChatMessageDto(role = "user", text = "hi", avatarMediaId = avatar.resourceId),
                ),
            ),
        )
        val params = buildJsonObject { put("resourceId", avatar.resourceId) }
        assertTrue(env.call(LocalDataMethods.MEDIA_DESCRIBE, params, actorA).ok)
        assertEquals("not_found", errorCode(env.call(LocalDataMethods.MEDIA_DESCRIBE, params, actorB)))
    }
}
