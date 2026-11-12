/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.plugin.localdata.AuthorKind
import me.rerere.rikkahub.plugin.localdata.LocalAuthorDto
import me.rerere.rikkahub.plugin.localdata.LocalChatMessageDto
import me.rerere.rikkahub.plugin.localdata.LocalChatSnapshotDto
import me.rerere.rikkahub.plugin.localdata.LocalCommentDto
import me.rerere.rikkahub.plugin.localdata.LocalCommentPageDto
import me.rerere.rikkahub.plugin.localdata.LocalMediaDto
import me.rerere.rikkahub.plugin.localdata.LocalPageBudgetDto
import me.rerere.rikkahub.plugin.localdata.LocalPostDto
import me.rerere.rikkahub.plugin.localdata.LocalPostPageDto
import me.rerere.rikkahub.plugin.localdata.PostVisibility
import me.rerere.rikkahub.plugin.localtasks.LocalAssistantPolicy
import me.rerere.rikkahub.plugin.localtasks.LocalTaskSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMomentsToolsTest {
    private val assistantId = "11111111-1111-1111-1111-111111111111"
    private val otherAssistantId = "22222222-2222-2222-2222-222222222222"

    // ---------------------------------------------------------------- ACL

    @Test
    fun `allowed assistant needs install enable read grant enabled tasks and participation`() {
        val access = LocalMomentsAccessPolicy.evaluate(snapshot())

        assertTrue(access.allowed)
        assertNull(access.denial)
        assertFalse(access.canReadMedia)
        assertFalse(access.canReadChatSnapshot)
        assertFalse(access.canAttachImages)
    }

    @Test
    fun `missing disabled or broken plugin denies`() {
        assertEquals(
            "plugin_not_installed",
            access(snapshot(installed = false)).denial?.code,
        )
        assertEquals(
            "plugin_disabled",
            access(snapshot(enabled = false)).denial?.code,
        )
        assertEquals(
            "plugin_disabled",
            access(snapshot(loadError = "bad manifest")).denial?.code,
        )
    }

    @Test
    fun `read grant is mandatory`() {
        val access = access(snapshot(grants = emptySet()))

        assertEquals("read_not_granted", access.denial?.code)
    }

    @Test
    fun `headless callers are rejected`() {
        val access = access(snapshot(headless = true))

        assertEquals("headless_call", access.denial?.code)
    }

    @Test
    fun `assistant identity must be host supplied safe and known`() {
        assertEquals(
            "assistant_identity_missing",
            access(snapshot(assistantId = null)).denial?.code,
        )
        assertEquals(
            "assistant_identity_missing",
            access(snapshot(assistantId = "bad/id")).denial?.code,
        )
        assertEquals(
            "assistant_unknown",
            access(snapshot(assistantKnown = false)).denial?.code,
        )
    }

    @Test
    fun `auto interaction switch does not revoke participant read`() {
        assertTrue(access(snapshot(taskSettings = taskSettings(enabled = false))).allowed)
        assertEquals(
            "assistant_tasks_stopped",
            access(snapshot(taskSettings = taskSettings(globalStop = true))).denial?.code,
        )
    }

    @Test
    fun `assistant must be an enabled participant`() {
        assertEquals(
            "assistant_not_participant",
            access(snapshot(taskSettings = taskSettings(participant = otherAssistantId))).denial?.code,
        )
        assertEquals(
            "assistant_policy_disabled",
            access(
                snapshot(
                    taskSettings = taskSettings(policy = LocalAssistantPolicy.DISABLED),
                ),
            ).denial?.code,
        )
    }

    @Test
    fun `media metadata needs the native media grant`() {
        val access = access(snapshot(grants = setOf("localdata.read", "localdata.media")))

        assertTrue(access.allowed)
        assertTrue(access.canReadMedia)
        assertFalse(access.canReadChatSnapshot)
        // The image-chat switch defaults to false; metadata may be read, bytes may not.
        assertFalse(access.canAttachImages)
    }

    @Test
    fun `image bytes need allowImages and a vision model`() {
        val grants = setOf("localdata.read", "localdata.media")
        assertFalse(
            access(
                snapshot(
                    grants = grants,
                    taskSettings = taskSettings(allowImages = true),
                    modelCanSeeImages = false,
                ),
            ).canAttachImages,
        )
        assertFalse(
            access(
                snapshot(
                    grants = grants,
                    taskSettings = taskSettings(allowImages = false),
                    modelCanSeeImages = true,
                ),
            ).canAttachImages,
        )
        assertTrue(
            access(
                snapshot(
                    grants = grants,
                    taskSettings = taskSettings(allowImages = true),
                    modelCanSeeImages = true,
                ),
            ).canAttachImages,
        )
    }

    @Test
    fun `chat excerpts need their own grant`() {
        val access = access(snapshot(grants = setOf("localdata.read", "localdata.chat_snapshot")))

        assertTrue(access.allowed)
        assertFalse(access.canReadMedia)
        assertTrue(access.canReadChatSnapshot)
    }

    // ------------------------------------------------------------- budget

    @Test
    fun `call page and search budgets are hard limits`() {
        val pageBudget = LocalMomentsBudget(maxCalls = 3, maxPages = 1, maxSearches = 3)
        assertNull(pageBudget.beginCall(LocalMomentsCallKind.LIST))
        assertEquals("page_budget_exhausted", pageBudget.beginCall(LocalMomentsCallKind.LIST)?.code)
        assertEquals(1, pageBudget.pages)

        val searchBudget = LocalMomentsBudget(maxCalls = 3, maxPages = 3, maxSearches = 1)
        assertNull(searchBudget.beginCall(LocalMomentsCallKind.SEARCH))
        assertEquals("search_budget_exhausted", searchBudget.beginCall(LocalMomentsCallKind.SEARCH)?.code)
        assertEquals(1, searchBudget.searches)

        val callBudget = LocalMomentsBudget(maxCalls = 2, maxPages = 3, maxSearches = 3)
        assertNull(callBudget.beginCall(LocalMomentsCallKind.GET))
        assertNull(callBudget.beginCall(LocalMomentsCallKind.GET))
        assertEquals("tool_call_budget_exhausted", callBudget.beginCall(LocalMomentsCallKind.GET)?.code)
        assertEquals(2, callBudget.calls)
    }

    @Test
    fun `text budget truncates explicitly and never overdraws`() {
        val budget = LocalMomentsTextBudget(maxChars = 10, reservedChars = 0)

        val first = budget.take("abcdef", 4)
        assertEquals("abcd", first.text)
        assertTrue(first.truncated)
        assertEquals(6, first.originalChars)
        assertEquals(6, budget.remainingChars)

        budget.charge(100)
        assertEquals(0, budget.remainingChars)
        assertTrue(budget.truncated)

        val exhausted = budget.take("xyz", 3)
        assertEquals("", exhausted.text)
        assertTrue(exhausted.truncated)
        assertEquals(3, exhausted.originalChars)
    }

    // ---------------------------------------------------------- arguments

    @Test
    fun `list arguments default to latest three and accept filters`() {
        val args = LocalMomentsArgs.list(
            buildJsonObject {
                put("authorKind", "assistant")
                put("type", "image")
                put("dateFrom", 1_700_000_000_000L)
                put("authorId", otherAssistantId)
            },
        )

        assertEquals(LocalMomentsLimits.DEFAULT_PAGE_SIZE, args.limit)
        assertEquals(LocalMomentsLimits.DEFAULT_LIST_BODY_CHARS, args.bodyChars)
        assertEquals("assistant", args.filter.authorKind)
        assertEquals("image", args.filter.type)
        assertEquals(1_700_000_000_000L, args.filter.dateFrom ?: 0L)
        assertEquals(otherAssistantId, args.filter.authorId)
    }

    @Test
    fun `identity parameters are rejected before anything else`() {
        val error = assertThrowsArgumentException {
            LocalMomentsArgs.list(buildJsonObject { put("assistantId", otherAssistantId) })
        }

        assertEquals("identity_not_allowed", error.code)
    }

    @Test
    fun `unknown parameters are rejected instead of silently dropped`() {
        val error = assertThrowsArgumentException {
            LocalMomentsArgs.get(buildJsonObject { put("postId", "p1"); put("includeEverything", true) })
        }

        assertEquals("unknown_parameter", error.code)
    }

    @Test
    fun `out of range limits and invalid cursors are rejected`() {
        assertEquals(
            "invalid_argument",
            assertThrowsArgumentException {
                LocalMomentsArgs.list(buildJsonObject { put("limit", 99) })
            }.code,
        )
        assertEquals(
            "invalid_cursor",
            assertThrowsArgumentException {
                LocalMomentsArgs.list(buildJsonObject { put("cursor", "not-a-valid-cursor") })
            }.code,
        )
        assertEquals(
            "invalid_argument",
            assertThrowsArgumentException {
                LocalMomentsArgs.chatExcerpt(
                    buildJsonObject { put("postId", "p1"); put("limit", 99) },
                )
            }.code,
        )
    }

    @Test
    fun `search requires a query and media requires a controlled id`() {
        assertEquals(
            "invalid_argument",
            assertThrowsArgumentException {
                LocalMomentsArgs.search(buildJsonObject { put("query", "   ") })
            }.code,
        )
        assertEquals(
            "invalid_argument",
            assertThrowsArgumentException {
                LocalMomentsArgs.media(buildJsonObject { put("resourceId", "../../etc/passwd") })
            }.code,
        )
    }

    // ------------------------------------------------------------- output

    @Test
    fun `post projection never exposes original media URLs or chat excerpts`() {
        val media = LocalMediaDto(
            resourceId = "ldm_12345678",
            mime = "image/jpeg",
            width = 100,
            height = 80,
            byteSize = 1234,
            thumbUrl = "https://localdata.invalid/media/ldm_12345678/thumb",
            originalUrl = "https://localdata.invalid/media/ldm_12345678/original",
        )
        val body = LocalMomentsLimitedText("hello", truncated = false, originalChars = 5)
        val mediaViews = LocalMomentsOutput.mediaViews(
            media = listOf(media),
            budget = LocalMomentsTextBudget(maxChars = 1_000, reservedChars = 0),
            includeMedia = true,
            notGrantedReason = "media_not_granted",
        )
        val view = LocalMomentsOutput.postView(post(), body, mediaViews)

        assertFalse(view.toString().contains("originalUrl"))
        assertFalse(view.toString().contains("/original"))
        assertNull(view["chatSnapshot"])
        val chatExcerpt = view["chatExcerpt"] as JsonObject
        assertTrue(chatExcerpt["omitted"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `list output marks truncation and resumes after the last shown row`() {
        val page = LocalPostPageDto(
            items = listOf(post(id = "p1", body = "a".repeat(100)), post(id = "p2", body = "b".repeat(100))),
            nextCursor = "page-cursor",
            hasMore = true,
            truncated = false,
            budget = LocalPageBudgetDto(2, 100, 200, false),
        )
        val budget = LocalMomentsBudget(text = LocalMomentsTextBudget(maxChars = 200, reservedChars = 0))
        val view = LocalMomentsOutput.listData(page, bodyChars = 220, budget = budget, includeMedia = false)

        assertTrue(view.truncated)
        assertEquals(1, view.items.size)
        assertEquals("output_budget", view.truncationReason)
        assertTrue(view.hasMore)
        assertNotNull(view.nextCursor)
        assertFalse(view.nextCursor == "page-cursor")
    }

    @Test
    fun `media metadata is capped and has no original variant`() {
        val media = (1..6).map { index ->
            LocalMediaDto(
                resourceId = "ldm_0000000$index",
                mime = "image/png",
                width = 10,
                height = 10,
                byteSize = 10,
                thumbUrl = "https://localdata.invalid/media/ldm_0000000$index/thumb",
                originalUrl = "https://localdata.invalid/media/ldm_0000000$index/original",
            )
        }
        val view = LocalMomentsOutput.mediaViews(
            media = media,
            budget = LocalMomentsTextBudget(maxChars = 2_000, reservedChars = 0),
            includeMedia = true,
            notGrantedReason = "media_not_granted",
        )

        assertEquals(LocalMomentsLimits.MAX_MEDIA_METADATA_PER_PAGE, view.items.size)
        assertTrue(view.omitted)
        assertEquals("metadata_limit", view.omittedReason)
        assertFalse(view.items.toString().contains("original"))
    }

    @Test
    fun `chat excerpt pagination is bounded and text only`() {
        val snapshot = LocalChatSnapshotDto(
            title = "chat",
            messages = (1..5).map { index ->
                LocalChatMessageDto(
                    role = if (index % 2 == 0) "assistant" else "user",
                    name = "n$index",
                    text = "m$index".repeat(100),
                    media = listOf("ldm_12345678"),
                )
            },
        )
        val budget = LocalMomentsBudget(text = LocalMomentsTextBudget(maxChars = 700, reservedChars = 0))
        val page = LocalMomentsOutput.chatExcerptData(
            postId = "p1",
            snapshot = snapshot,
            start = 0,
            limit = 5,
            budget = budget,
            includeMediaIds = false,
        )

        assertTrue(page.truncated)
        assertTrue(page.shownMessages < 5)
        val data = page.data.toString()
        assertFalse(data.contains("ldm_12345678"))
        assertTrue(data.contains("mediaOmittedReason"))
    }

    @Test
    fun `comments output truncates body and resumes after the last shown comment`() {
        val comments = (1..4).map { index ->
            LocalCommentDto(
                id = "c$index",
                postId = "p1",
                author = LocalAuthorDto(AuthorKind.USER, "local-user", "我"),
                body = "x".repeat(300),
                createdAt = index.toLong(),
            )
        }
        val page = LocalCommentPageDto(
            items = comments,
            nextCursor = "page-cursor",
            hasMore = true,
            commentCount = 4,
        )
        val budget = LocalMomentsBudget(text = LocalMomentsTextBudget(maxChars = 300, reservedChars = 0))
        val view = LocalMomentsOutput.commentsData(page, budget)

        assertTrue(view.truncated)
        assertTrue(view.items.size < 4)
        assertFalse(view.nextCursor == "page-cursor")
        assertTrue(view.items.first().toString().contains("\"bodyTruncated\":true"))
    }

    @Test
    fun `hard limit replaces oversized payload with valid bounded json`() {
        val output = LocalMomentsOutput.hardLimit("x".repeat(LocalMomentsLimits.MAX_OUTPUT_CHARS + 1), "moments_list")

        assertTrue(output.length <= LocalMomentsLimits.MAX_OUTPUT_CHARS)
        assertTrue(output.contains("\"truncated\":true"))
        assertTrue(output.contains("output_budget"))
    }

    @Test
    fun `plugin id matches the native moments plugin constant`() {
        assertEquals(
            me.rerere.rikkahub.ui.pages.chat.LOCAL_MOMENTS_PLUGIN_ID,
            LocalMomentsContract.PLUGIN_ID,
        )
    }

    // ------------------------------------------------------------ helpers

    private fun access(snapshot: LocalMomentsAccessSnapshot): LocalMomentsAccess =
        LocalMomentsAccessPolicy.evaluate(snapshot)

    private fun snapshot(
        installed: Boolean = true,
        enabled: Boolean = true,
        loadError: String? = null,
        grants: Set<String> = setOf("localdata.read"),
        taskSettings: LocalTaskSettings = taskSettings(),
        assistantId: String? = this.assistantId,
        assistantKnown: Boolean = true,
        headless: Boolean = false,
        modelCanSeeImages: Boolean = false,
    ): LocalMomentsAccessSnapshot = LocalMomentsAccessSnapshot(
        pluginInstalled = installed,
        pluginEnabled = enabled,
        pluginLoadError = loadError,
        grantedCapabilities = grants,
        taskSettings = taskSettings,
        assistantId = assistantId,
        assistantKnown = assistantKnown,
        headless = headless,
        modelCanSeeImages = modelCanSeeImages,
    )

    private fun taskSettings(
        enabled: Boolean = true,
        globalStop: Boolean = false,
        participant: String = assistantId,
        policy: LocalAssistantPolicy = LocalAssistantPolicy.DRAFT_ONLY,
        allowImages: Boolean = false,
    ): LocalTaskSettings = LocalTaskSettings(
        enabled = enabled,
        globalStop = globalStop,
        participants = listOf(participant),
        assistantPolicies = mapOf(participant to policy),
        allowImages = allowImages,
    ).normalized()

    private fun post(
        id: String = "p1",
        body: String = "hello",
        media: List<LocalMediaDto> = emptyList(),
        mediaOmitted: Boolean = false,
    ): LocalPostDto = LocalPostDto(
        id = id,
        author = LocalAuthorDto(AuthorKind.USER, "local-user", "我"),
        body = body,
        visibility = PostVisibility.ALL_ASSISTANTS,
        media = media,
        mediaOmitted = mediaOmitted,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private inline fun assertThrowsArgumentException(block: () -> Unit): LocalMomentsArgumentException {
        try {
            block()
        } catch (error: LocalMomentsArgumentException) {
            return error
        }
        throw AssertionError("Expected LocalMomentsArgumentException")
    }
}
