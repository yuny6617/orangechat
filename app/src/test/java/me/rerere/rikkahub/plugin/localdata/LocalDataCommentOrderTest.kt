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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `order=newest` is the localdata-side fix for "read the latest comments without scanning every
 * earlier page". The default must stay oldest-first so the existing moments UI is unchanged.
 *
 * Test names in this class stay short on purpose: Robolectric derives its per-test directory name
 * from the class and method name, and that directory hosts the plugin SQLite database. Native
 * Windows SQLite rejects a database path of 252 characters (its `-journal` file then hits
 * `MAX_PATH`), so a long method name can make every database open fail with `SQLITE_CANTOPEN`
 * before a single assertion runs. See `LOCALDATA_TEST_HOST_DIAGNOSIS.md`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class LocalDataCommentOrderTest {
    private val env = LocalDataTestEnvironment()
    private val actorA = LocalDataActor.Assistant(env.assistantA.id, env.assistantA.name, env.assistantA.avatar)
    private val actorB = LocalDataActor.Assistant(env.assistantB.id, env.assistantB.name, env.assistantB.avatar)

    @After
    fun tearDown() {
        env.close()
    }

    private fun commentPage(
        postId: String,
        order: String? = null,
        cursor: String? = null,
        limit: Int? = null,
        actor: LocalDataActor = LocalDataActor.User,
    ): LocalCommentPageDto {
        val params = buildJsonObject {
            put("postId", postId)
            if (order != null) put("order", order)
            if (cursor != null) put("cursor", cursor)
            if (limit != null) put("limit", limit)
        }
        val envelope = env.call(LocalDataMethods.COMMENTS_LIST, params, actor)
        check(envelope.ok) { envelope.error?.message ?: "comments list failed" }
        return LocalDataJson.json.decodeFromJsonElement(
            LocalCommentPageDto.serializer(),
            envelope.data!!.jsonObject,
        )
    }

    private fun detail(
        postId: String,
        order: String? = null,
        commentCursor: String? = null,
        commentLimit: Int = LocalDataApi.DEFAULT_PAGE_SIZE,
        actor: LocalDataActor = LocalDataActor.User,
    ): LocalPostDetailDto {
        val params = buildJsonObject {
            put("postId", postId)
            put("commentLimit", commentLimit)
            if (order != null) put("order", order)
            if (commentCursor != null) put("commentCursor", commentCursor)
        }
        val envelope = env.call(LocalDataMethods.GET, params, actor)
        check(envelope.ok) { envelope.error?.message ?: "get failed" }
        return LocalDataJson.json.decodeFromJsonElement(
            LocalPostDetailDto.serializer(),
            envelope.data!!.jsonObject,
        )
    }

    @Test
    // 45 comments, newest page first: the oldest-first default must still be unchanged.
    fun `newest order returns the latest three of forty five`() {
        val base = env.now
        val post = env.createPost(body = "hot post", timestamp = base)
        repeat(45) { index ->
            assertTrue(
                env.comment(
                    postId = post.id,
                    body = "c-$index",
                    timestamp = base + (index + 1) * 1_000,
                ).ok,
            )
        }

        // The historical default is still forward/oldest-first, which is what the UI expects.
        val oldest = commentPage(post.id, limit = 3)
        assertEquals(listOf("c-0", "c-1", "c-2"), oldest.items.map { it.body })
        assertTrue(oldest.hasMore)
        assertEquals(45, oldest.commentCount)

        val newest = commentPage(post.id, order = "newest", limit = 3)
        assertEquals(listOf("c-44", "c-43", "c-42"), newest.items.map { it.body })
        assertTrue(newest.hasMore)
        assertNotNull(newest.nextCursor)
        assertEquals(45, newest.commentCount)

        val firstIds = newest.items.map { it.id }
        val next = commentPage(post.id, order = "newest", cursor = newest.nextCursor, limit = 3)
        assertEquals(listOf("c-41", "c-40", "c-39"), next.items.map { it.body })
        assertTrue(next.items.map { it.id }.none { it in firstIds })
    }

    @Test
    fun `newest pagination stays stable when every comment shares one timestamp`() {
        val post = env.createPost()
        repeat(9) { index ->
            assertTrue(env.comment(post.id, body = "same-$index", timestamp = env.now).ok)
        }

        val newestIds = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        while (true) {
            val page = commentPage(post.id, order = "newest", cursor = cursor, limit = 2)
            newestIds += page.items.map { it.id }
            if (!page.hasMore) break
            cursor = page.nextCursor
            assertNotNull(cursor)
            if (++pages > 10) error("newest pagination did not terminate")
        }
        assertEquals(9, newestIds.size)
        assertEquals(9, newestIds.toSet().size)
        // DESC ordering is externally stable: createdAt ties are broken by id DESC.
        assertEquals(newestIds.sortedDescending(), newestIds)

        val oldestIds = mutableListOf<String>()
        cursor = null
        pages = 0
        while (true) {
            val page = commentPage(post.id, cursor = cursor, limit = 2)
            oldestIds += page.items.map { it.id }
            if (!page.hasMore) break
            cursor = page.nextCursor
            assertNotNull(cursor)
            if (++pages > 10) error("oldest pagination did not terminate")
        }
        assertEquals(9, oldestIds.size)
        assertEquals(newestIds.reversed(), oldestIds)
    }

    @Test
    fun `get with newest order returns the latest comments and pages backwards`() {
        val base = env.now
        val post = env.createPost(timestamp = base)
        repeat(8) { index ->
            assertTrue(
                env.comment(
                    postId = post.id,
                    body = "c-$index",
                    timestamp = base + index + 1,
                ).ok,
            )
        }

        val newest = detail(post.id, order = "newest", commentLimit = 3)
        assertEquals(listOf("c-7", "c-6", "c-5"), newest.comments.items.map { it.body })
        assertTrue(newest.comments.hasMore)
        assertNotNull(newest.comments.nextCursor)
        assertEquals(8, newest.comments.commentCount)

        val older = detail(
            postId = post.id,
            order = "newest",
            commentCursor = newest.comments.nextCursor,
            commentLimit = 3,
        )
        assertEquals(listOf("c-4", "c-3", "c-2"), older.comments.items.map { it.body })

        // Omitting order keeps the detail comment window ascending, matching the current UI.
        val defaultDetail = detail(post.id, commentLimit = 3)
        assertEquals(listOf("c-0", "c-1", "c-2"), defaultDetail.comments.items.map { it.body })
    }

    @Test
    fun `newest comments keep the same post ACL as the default order`() {
        val post = env.createPost(
            body = "for alice only",
            visibility = "selected",
            selected = listOf(env.assistantA.id),
        )
        assertTrue(env.comment(post.id, body = "from alice", actor = actorA).ok)

        val visible = commentPage(post.id, order = "newest", actor = actorA)
        assertEquals(listOf("from alice"), visible.items.map { it.body })

        val hiddenList = env.call(
            LocalDataMethods.COMMENTS_LIST,
            buildJsonObject {
                put("postId", post.id)
                put("order", "newest")
            },
            actorB,
        )
        assertEquals("not_found", errorCode(hiddenList))

        val hiddenGet = env.call(
            LocalDataMethods.GET,
            buildJsonObject {
                put("postId", post.id)
                put("order", "newest")
            },
            actorB,
        )
        assertEquals("not_found", errorCode(hiddenGet))
        assertTrue(detail(post.id, order = "newest", actor = actorA).comments.items.isNotEmpty())
    }

    @Test
    fun `unsupported comment order is rejected for list and get`() {
        val post = env.createPost()
        assertEquals(
            "invalid_request",
            errorCode(
                env.call(
                    LocalDataMethods.COMMENTS_LIST,
                    buildJsonObject {
                        put("postId", post.id)
                        put("order", "sideways")
                    },
                ),
            ),
        )
        assertEquals(
            "invalid_request",
            errorCode(
                env.call(
                    LocalDataMethods.GET,
                    buildJsonObject {
                        put("postId", post.id)
                        put("order", "sideways")
                    },
                ),
            ),
        )
    }
}
