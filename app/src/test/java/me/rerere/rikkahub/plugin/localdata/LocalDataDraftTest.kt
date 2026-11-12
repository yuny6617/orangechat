/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localdata

import android.app.Application
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class LocalDataDraftTest {
    private val env = LocalDataTestEnvironment()
    private val actorA = LocalDataActor.Assistant(env.assistantA.id, env.assistantA.name, env.assistantA.avatar)
    private val actorB = LocalDataActor.Assistant(env.assistantB.id, env.assistantB.name, env.assistantB.avatar)

    @After
    fun tearDown() {
        env.close()
    }

    private fun saveDraft(
        actor: LocalDataActor,
        body: String = "draft body",
        selected: List<String> = listOf(env.assistantB.id),
    ): LocalDraftDto {
        val params = buildJsonObject {
            put("body", body)
            put("visibility", "selected")
            put("selectedAssistantIds", buildJsonArrayOf(selected))
        }
        val envelope = env.call(
            LocalDataMethods.DRAFT_SAVE,
            params,
            actor,
            grant = LocalDataGrant.of(LocalDataCapability.WRITE),
        )
        check(envelope.ok) { envelope.error?.message ?: "draft save failed" }
        return LocalDataJson.json.decodeFromJsonElement(
            LocalDraftDto.serializer(),
            envelope.data!!.jsonObject,
        )
    }

    private fun buildJsonArrayOf(values: List<String>) = kotlinx.serialization.json.buildJsonArray {
        values.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
    }

    @Test
    fun `drafts are isolated by author and publish carries the selected audience`() {
        val draft = saveDraft(actorA)
        assertEquals("selected", draft.visibility.name.lowercase())
        assertEquals(listOf(env.assistantB.id), draft.selectedAssistantIds)

        val aDrafts = LocalDataJson.json.decodeFromJsonElement(
            LocalDraftPageDto.serializer(),
            env.call(
                LocalDataMethods.DRAFT_LIST,
                grant = LocalDataGrant.of(LocalDataCapability.WRITE),
                actor = actorA,
            ).data!!.jsonObject,
        )
        assertEquals(1, aDrafts.items.size)
        val bDrafts = LocalDataJson.json.decodeFromJsonElement(
            LocalDraftPageDto.serializer(),
            env.call(
                LocalDataMethods.DRAFT_LIST,
                grant = LocalDataGrant.of(LocalDataCapability.WRITE),
                actor = actorB,
            ).data!!.jsonObject,
        )
        assertEquals(0, bDrafts.items.size)

        val publish = env.call(
            LocalDataMethods.CREATE,
            buildJsonObject { put("draftId", draft.id) },
            actorA,
        )
        assertTrue(publish.ok)
        val post = LocalDataJson.json.decodeFromJsonElement(
            LocalPostCreateResultDto.serializer(),
            publish.data!!.jsonObject,
        ).post
        assertEquals("draft body", post.body)
        assertEquals(PostVisibility.SELECTED, post.visibility)
        assertEquals(1, env.list(actorB).items.size)

        val remaining = LocalDataJson.json.decodeFromJsonElement(
            LocalDraftPageDto.serializer(),
            env.call(
                LocalDataMethods.DRAFT_LIST,
                grant = LocalDataGrant.of(LocalDataCapability.WRITE),
                actor = actorA,
            ).data!!.jsonObject,
        )
        assertEquals(0, remaining.items.size)
    }

    @Test
    fun `draft save and delete are author scoped`() {
        val draft = saveDraft(actorA)
        assertEquals(
            "not_found",
            errorCode(env.call(
                LocalDataMethods.DRAFT_DELETE,
                buildJsonObject { put("draftId", draft.id) },
                actorB,
                grant = LocalDataGrant.of(LocalDataCapability.WRITE),
            )),
        )
        assertTrue(
            env.call(
                LocalDataMethods.DRAFT_DELETE,
                buildJsonObject { put("draftId", draft.id) },
                actorA,
                grant = LocalDataGrant.of(LocalDataCapability.WRITE),
            ).ok,
        )
    }

    @Test
    fun `client keys make create and comment retries idempotent`() {
        val first = env.createPost(clientKey = "post-1")
        val second = env.createPost(clientKey = "post-1")
        assertEquals(first.id, second.id)
        assertEquals(1, env.list().items.size)

        val commentParams = buildJsonObject {
            put("postId", first.id)
            put("body", "once")
            put("clientKey", "comment-1")
        }
        val commentOne = env.call(LocalDataMethods.COMMENT_CREATE, commentParams)
        val commentTwo = env.call(LocalDataMethods.COMMENT_CREATE, commentParams)
        assertTrue(commentOne.ok)
        assertTrue(commentTwo.ok)
        val idOne = LocalDataJson.json.decodeFromJsonElement(
            LocalCommentCreateResultDto.serializer(),
            commentOne.data!!.jsonObject,
        ).comment.id
        val idTwo = LocalDataJson.json.decodeFromJsonElement(
            LocalCommentCreateResultDto.serializer(),
            commentTwo.data!!.jsonObject,
        ).comment.id
        assertEquals(idOne, idTwo)
        assertEquals(1, LocalDataJson.json.decodeFromJsonElement(
            LocalCommentPageDto.serializer(),
            env.call(
                LocalDataMethods.COMMENTS_LIST,
                buildJsonObject { put("postId", first.id) },
            ).data!!.jsonObject,
        ).commentCount)
    }

    @Test
    fun `draft media survives until publish and is then owned by the post`() {
        val media = env.importMedia()
        val params = buildJsonObject {
            put("body", "with image")
            put("visibility", "all_assistants")
            put("mediaIds", buildJsonArrayOf(listOf(media.resourceId)))
        }
        val draftEnvelope = env.call(
            LocalDataMethods.DRAFT_SAVE,
            params,
            grant = LocalDataGrant.of(LocalDataCapability.WRITE),
        )
        val draft = LocalDataJson.json.decodeFromJsonElement(
            LocalDraftDto.serializer(),
            draftEnvelope.data!!.jsonObject,
        )
        assertEquals(1, draft.media.size)
        assertTrue(env.store.isMediaReferenced(media.resourceId))

        val publish = env.call(LocalDataMethods.CREATE, buildJsonObject { put("draftId", draft.id) })
        assertTrue(publish.ok)
        assertTrue(env.store.isMediaReferenced(media.resourceId))
    }
}
