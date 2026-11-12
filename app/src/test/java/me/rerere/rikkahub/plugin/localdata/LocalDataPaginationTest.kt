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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class LocalDataPaginationTest {
    private val env = LocalDataTestEnvironment()

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun `default page is three and cursor never repeats or skips posts`() {
        val base = env.now
        repeat(7) { index ->
            env.createPost(body = "post-$index", timestamp = base + index * 1_000)
        }

        val first = env.list()
        assertEquals(3, first.items.size)
        assertTrue(first.hasMore)
        assertNotNull(first.nextCursor)

        val seen = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        while (true) {
            val params = buildJsonObject {
                put("limit", 2)
                if (cursor != null) put("cursor", cursor)
            }
            val envelope = env.call(LocalDataMethods.LIST, params)
            check(envelope.ok) { envelope.error?.message ?: "list failed" }
            val page = LocalDataJson.json.decodeFromJsonElement(
                LocalPostPageDto.serializer(),
                envelope.data!!.jsonObject,
            )
            seen += page.ids()
            bodies += page.items.map { it.body }
            pages++
            if (!page.hasMore) break
            cursor = page.nextCursor
            assertNotNull(cursor)
            if (pages > 10) error("pagination did not terminate")
        }
        assertEquals(7, seen.size)
        assertEquals(7, seen.toSet().size)
        assertEquals(
            listOf("post-6", "post-5", "post-4", "post-3", "post-2", "post-1", "post-0"),
            bodies,
        )
    }

    @Test
    fun `equal timestamps stay stable through the id tie breaker`() {
        repeat(4) { index ->
            env.createPost(body = "same-$index", timestamp = env.now)
        }
        val seen = mutableListOf<String>()
        var cursor: String? = null
        while (true) {
            val params = buildJsonObject {
                put("limit", 2)
                if (cursor != null) put("cursor", cursor)
            }
            val page = LocalDataJson.json.decodeFromJsonElement(
                LocalPostPageDto.serializer(),
                env.call(LocalDataMethods.LIST, params).data!!.jsonObject,
            )
            seen += page.ids()
            if (!page.hasMore) break
            cursor = page.nextCursor
        }
        assertEquals(4, seen.size)
        assertEquals(4, seen.toSet().size)
    }

    @Test
    fun `long bodies are truncated in list and detail with an explicit flag`() {
        val body = "x".repeat(5_000)
        val post = env.createPost(body = body)

        val page = env.list(params = buildJsonObject { put("bodyChars", 300) })
        val item = page.items.single()
        assertEquals(300, item.body.length)
        assertTrue(item.bodyTruncated)
        assertTrue(page.truncated)
        assertEquals(300, page.budget.bodyCharsUsed)

        val detailEnvelope = env.call(
            LocalDataMethods.GET,
            buildJsonObject { put("postId", post.id); put("bodyChars", 4_000) },
        )
        val detail = LocalDataJson.json.decodeFromJsonElement(
            LocalPostDetailDto.serializer(),
            detailEnvelope.data!!.jsonObject,
        )
        assertEquals(4_000, detail.post.body.length)
        assertTrue(detail.post.bodyTruncated)
        assertEquals(5_000, env.store.exportSnapshot().posts.single().body.length)
    }

    @Test
    fun `page body budget stops once it is exhausted and keeps the flag`() {
        repeat(3) { index ->
            env.createPost(body = "y".repeat(4_000), timestamp = env.now + index)
        }
        val page = env.list(
            params = buildJsonObject {
                put("bodyChars", 4_000)
                put("limit", 3)
            },
        )
        assertEquals(3, page.items.size)
        assertTrue(page.truncated)
        assertTrue(page.budget.bodyCharsUsed <= LocalDataApi.DEFAULT_PAGE_BODY_BUDGET_CHARS)
        assertEquals(0, page.items.last().body.length)
        assertTrue(page.items.last().bodyTruncated)
    }

    @Test
    fun `invalid cursor and oversized page limits are rejected`() {
        env.createPost()
        assertEquals(
            "invalid_request",
            errorCode(env.call(
                LocalDataMethods.LIST,
                buildJsonObject { put("cursor", "not-a-cursor") },
            )),
        )
        assertEquals(
            "invalid_request",
            errorCode(env.call(
                LocalDataMethods.LIST,
                buildJsonObject { put("limit", 100) },
            )),
        )
        assertEquals(
            "invalid_request",
            errorCode(env.call(
                LocalDataMethods.LIST,
                buildJsonObject { put("limit", 0) },
            )),
        )
    }
}
