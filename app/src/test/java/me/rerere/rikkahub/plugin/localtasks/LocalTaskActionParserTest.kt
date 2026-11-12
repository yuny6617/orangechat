/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localtasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTaskActionParserTest {
    @Test
    fun `parses a bounded reply action`() {
        val action = LocalTaskActionParser.parseInteraction(
            raw = """{"action":"reply","body":"nice post","like":true}""",
            maxChars = 280,
            knownCommentIds = emptySet(),
        )

        assertTrue(action is LocalTaskAction.Reply)
        action as LocalTaskAction.Reply
        assertEquals("nice post", action.body)
        assertTrue(action.like)
        assertFalse(action.truncated)
        assertNull(action.replyToId)
    }

    @Test
    fun `accepts a single markdown fence`() {
        val action = LocalTaskActionParser.parseInteraction(
            raw = "```json\n{\"action\":\"skip\",\"reason\":\"nothing to add\"}\n```",
            maxChars = 280,
            knownCommentIds = emptySet(),
        )

        assertTrue(action is LocalTaskAction.Skip)
        assertEquals("nothing to add", (action as LocalTaskAction.Skip).reason)
    }

    @Test
    fun `rejects unknown fields instead of guessing`() {
        val action = LocalTaskActionParser.parseInteraction(
            raw = """{"action":"reply","body":"hi","mediaIds":["ldm_12345678"]}""",
            maxChars = 280,
            knownCommentIds = emptySet(),
        )

        assertTrue(action is LocalTaskAction.Invalid)
        assertEquals("invalid_action", (action as LocalTaskAction.Invalid).code)
    }

    @Test
    fun `truncates over long bodies and flags them`() {
        val body = "a".repeat(120)
        val action = LocalTaskActionParser.parseInteraction(
            raw = """{"action":"reply","body":"$body"}""",
            maxChars = 40,
            knownCommentIds = emptySet(),
        )

        assertTrue(action is LocalTaskAction.Reply)
        action as LocalTaskAction.Reply
        assertEquals(40, action.body.length)
        assertTrue(action.truncated)
    }

    @Test
    fun `drops reply targets that were not in the bounded context`() {
        val known = LocalTaskActionParser.parseInteraction(
            raw = """{"action":"reply","body":"hi","replyToId":"cmt_1"}""",
            maxChars = 280,
            knownCommentIds = setOf("cmt_1"),
        )
        val unknown = LocalTaskActionParser.parseInteraction(
            raw = """{"action":"reply","body":"hi","replyToId":"cmt_999"}""",
            maxChars = 280,
            knownCommentIds = setOf("cmt_1"),
        )

        assertEquals("cmt_1", (known as LocalTaskAction.Reply).replyToId)
        assertNull((unknown as LocalTaskAction.Reply).replyToId)
    }

    @Test
    fun `rejects actions that are not valid for interactions`() {
        val action = LocalTaskActionParser.parseInteraction(
            raw = """{"action":"publish","body":"hello"}""",
            maxChars = 280,
            knownCommentIds = emptySet(),
        )

        assertTrue(action is LocalTaskAction.Invalid)
    }

    @Test
    fun `rejects malformed json without evaluating it`() {
        val action = LocalTaskActionParser.parseInteraction(
            raw = "action=reply&body=hi",
            maxChars = 280,
            knownCommentIds = emptySet(),
        )

        assertTrue(action is LocalTaskAction.Invalid)
    }

    @Test
    fun `treats script-like text as literal content`() {
        val action = LocalTaskActionParser.parseInteraction(
            raw = """{"action":"reply","body":"<script>alert(1)</script> && rm -rf /"}""",
            maxChars = 280,
            knownCommentIds = emptySet(),
        )

        assertTrue(action is LocalTaskAction.Reply)
        assertEquals("<script>alert(1)</script> && rm -rf /", (action as LocalTaskAction.Reply).body)
    }

    @Test
    fun `publish parser only accepts publish draft or skip`() {
        val publish = LocalTaskActionParser.parsePublish(
            raw = """{"action":"publish","body":"today"}""",
            maxChars = 280,
        )
        val draft = LocalTaskActionParser.parsePublish(
            raw = """{"action":"draft","body":"today"}""",
            maxChars = 280,
        )
        val reply = LocalTaskActionParser.parsePublish(
            raw = """{"action":"reply","body":"today"}""",
            maxChars = 280,
        )

        assertTrue(publish is LocalTaskAction.PublishPost)
        assertTrue(draft is LocalTaskAction.DraftPost)
        assertTrue(reply is LocalTaskAction.Invalid)
    }

    @Test
    fun `blank bodies are rejected`() {
        val action = LocalTaskActionParser.parseInteraction(
            raw = """{"action":"reply","body":"   "}""",
            maxChars = 280,
            knownCommentIds = emptySet(),
        )

        assertTrue(action is LocalTaskAction.Invalid)
    }

    @Test
    fun `publish accepts a controlled generated image request`() {
        val action = LocalTaskActionParser.parsePublish(
            raw = """{"action":"publish","body":"a quiet evening","image":{"source":"generate_image","prompt":"a lake at dusk"}}""",
            maxChars = 280,
            imagesAllowed = true,
        )

        assertTrue(action is LocalTaskAction.PublishPost)
        action as LocalTaskAction.PublishPost
        assertEquals("a quiet evening", action.body)
        val image = action.imageRequest as LocalTaskImageRequest.GenerateImage
        assertEquals("a lake at dusk", image.prompt)
    }

    @Test
    fun `draft accepts the same controlled image request`() {
        val action = LocalTaskActionParser.parsePublish(
            raw = """{"action":"draft","body":"post","image":{"source":"generate_image","prompt":"rain"}}""",
            maxChars = 280,
            imagesAllowed = true,
        )

        assertTrue(action is LocalTaskAction.DraftPost)
        assertTrue((action as LocalTaskAction.DraftPost).imageRequest is LocalTaskImageRequest.GenerateImage)
    }

    @Test
    fun `image requests are rejected when the task did not enable them`() {
        val action = LocalTaskActionParser.parsePublish(
            raw = """{"action":"publish","body":"x","image":{"source":"generate_image","prompt":"x"}}""",
            maxChars = 280,
            imagesAllowed = false,
        )

        assertTrue(action is LocalTaskAction.Invalid)
        assertEquals("invalid_action", (action as LocalTaskAction.Invalid).code)
    }

    @Test
    fun `model supplied paths uris ids and file names are rejected`() {
        val forbidden = listOf(
            """{"action":"publish","body":"x","image":{"source":"generate_image","prompt":"x","path":"/sdcard/a.png"}}""",
            """{"action":"publish","body":"x","image":{"source":"generate_image","prompt":"x","uri":"file:///a.png"}}""",
            """{"action":"publish","body":"x","image":{"source":"generate_image","prompt":"x","url":"https://a/x.png"}}""",
            """{"action":"publish","body":"x","image":{"source":"generate_image","prompt":"x","mediaId":"ldm_123"}}""",
            """{"action":"publish","body":"x","image":{"source":"generate_image","prompt":"x","fileName":"a.png"}}""",
            """{"action":"publish","body":"x","mediaIds":["ldm_123"]}""",
            """{"action":"publish","body":"x","mediaSource":"generated"}""",
        )

        forbidden.forEach { raw ->
            val action = LocalTaskActionParser.parsePublish(raw, maxChars = 280, imagesAllowed = true)
            assertTrue("$raw must be rejected", action is LocalTaskAction.Invalid)
        }
    }

    @Test
    fun `only the generate_image source is accepted`() {
        listOf("file", "native", "generated", "generate", "").forEach { source ->
            val action = LocalTaskActionParser.parsePublish(
                raw = """{"action":"publish","body":"x","image":{"source":"$source","prompt":"x"}}""",
                maxChars = 280,
                imagesAllowed = true,
            )
            assertTrue("source=$source must be rejected", action is LocalTaskAction.Invalid)
        }
    }

    @Test
    fun `image prompt must be a non blank bounded string`() {
        listOf(
            """{"action":"publish","body":"x","image":{"source":"generate_image"}}""",
            """{"action":"publish","body":"x","image":{"source":"generate_image","prompt":"   "}}""",
            """{"action":"publish","body":"x","image":{"source":"generate_image","prompt":123}}""",
            """{"action":"publish","body":"x","image":{"source":"generate_image","prompt":"${"a".repeat(LocalTasksApi.MAX_IMAGE_PROMPT_CHARS + 1)}"}}""",
        ).forEach { raw ->
            val action = LocalTaskActionParser.parsePublish(raw, maxChars = 280, imagesAllowed = true)
            assertTrue(action is LocalTaskAction.Invalid)
        }
    }

    @Test
    fun `image is not allowed on an interaction or a skip`() {
        val interaction = LocalTaskActionParser.parseInteraction(
            raw = """{"action":"reply","body":"hi","image":{"source":"generate_image","prompt":"x"}}""",
            maxChars = 280,
            knownCommentIds = emptySet(),
        )
        val skip = LocalTaskActionParser.parsePublish(
            raw = """{"action":"skip","reason":"none","image":{"source":"generate_image","prompt":"x"}}""",
            maxChars = 280,
            imagesAllowed = true,
        )

        assertTrue(interaction is LocalTaskAction.Invalid)
        assertTrue(skip is LocalTaskAction.Invalid)
    }
}
