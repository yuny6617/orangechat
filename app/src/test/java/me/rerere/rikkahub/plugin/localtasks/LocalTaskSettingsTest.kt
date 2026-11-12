/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localtasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTaskSettingsTest {
    @Test
    fun `normalization clamps every numeric cap`() {
        val normalized = LocalTaskSettings(
            enabled = true,
            maxRounds = 99,
            maxRepliesPerAssistantPerPost = 99,
            maxModelCallsPerPost = 0,
            dailyModelCallBudget = -3,
            maxOutputChars = 0,
            intervalMs = -100,
            contextCommentLimit = 99,
            feedLimit = 0,
            commentScanPages = 99,
            participants = listOf(ASSISTANT_A.id, ASSISTANT_A.id, "bad/id", ""),
            assistantPolicies = mapOf(
                ASSISTANT_A.id to LocalAssistantPolicy.AUTO_PUBLISH,
                "bad/id" to LocalAssistantPolicy.DISABLED,
            ),
            assistantReplyLimits = mapOf(ASSISTANT_A.id to 99, "bad/id" to 3),
        ).normalized()

        assertEquals(LocalTasksApi.MAX_ROUNDS, normalized.maxRounds)
        assertEquals(LocalTasksApi.MAX_REPLIES_PER_POST, normalized.maxRepliesPerAssistantPerPost)
        assertEquals(1, normalized.maxModelCallsPerPost)
        assertEquals(0, normalized.dailyModelCallBudget)
        assertEquals(LocalTasksApi.MIN_MAX_OUTPUT_CHARS, normalized.maxOutputChars)
        assertEquals(0L, normalized.intervalMs)
        assertEquals(LocalTasksApi.MAX_CONTEXT_COMMENT_LIMIT, normalized.contextCommentLimit)
        assertEquals(1, normalized.feedLimit)
        assertEquals(LocalTasksApi.MAX_COMMENT_SCAN_PAGES, normalized.commentScanPages)
        assertEquals(listOf(ASSISTANT_A.id), normalized.participants)
        assertEquals(
            mapOf(ASSISTANT_A.id to LocalAssistantPolicy.AUTO_PUBLISH),
            normalized.assistantPolicies,
        )
        assertEquals(mapOf(ASSISTANT_A.id to LocalTasksApi.MAX_REPLIES_PER_POST), normalized.assistantReplyLimits)
    }

    @Test
    fun `policies default to draft only and non participants are disabled`() {
        val settings = LocalTaskSettings(
            participants = listOf(ASSISTANT_A.id),
        ).normalized()

        assertEquals(LocalAssistantPolicy.DRAFT_ONLY, settings.effectivePolicy(ASSISTANT_A.id))
        assertEquals(LocalAssistantPolicy.DISABLED, settings.effectivePolicy(ASSISTANT_B.id))
        assertEquals(
            LocalAssistantPolicy.AUTO_PUBLISH,
            settings.copy(
                assistantPolicies = mapOf(ASSISTANT_A.id to LocalAssistantPolicy.AUTO_PUBLISH),
            ).effectivePolicy(ASSISTANT_A.id),
        )
    }

    @Test
    fun `per assistant reply limits override the global default`() {
        val settings = testSettings(maxReplies = 3).copy(
            assistantReplyLimits = mapOf(ASSISTANT_A.id to 1),
        ).normalized()

        assertEquals(1, settings.replyLimitFor(ASSISTANT_A.id))
        assertEquals(3, settings.replyLimitFor(ASSISTANT_B.id))
    }

    @Test
    fun `settings survive an encode decode round trip`() {
        val settings = testSettings(ASSISTANT_A, ASSISTANT_B, maxRounds = 4, intervalMs = 1_500)

        val decoded = LocalTaskSettingsCodec.decode(LocalTaskSettingsCodec.encode(settings))

        assertEquals(settings.normalized(), decoded)
    }

    @Test
    fun `corrupt or missing settings fall back to safe defaults`() {
        assertEquals(LocalTaskSettings(), LocalTaskSettingsCodec.decode(null))
        assertEquals(LocalTaskSettings(), LocalTaskSettingsCodec.decode("{oops"))
        assertFalse(LocalTaskSettingsCodec.decode("{oops").enabled)
    }

    @Test
    fun `generated images require the base image switch`() {
        val blocked = LocalTaskSettings(
            allowImages = false,
            allowGeneratedImages = true,
        ).normalized()
        val allowed = LocalTaskSettings(
            allowImages = true,
            allowGeneratedImages = true,
        ).normalized()

        assertFalse(blocked.allowGeneratedImages)
        assertTrue(allowed.allowGeneratedImages)
        assertFalse(LocalTaskSettings().normalized().allowGeneratedImages)
    }

    @Test
    fun `generated image switch survives a settings round trip`() {
        val settings = testSettings(ASSISTANT_A).copy(
            allowImages = true,
            allowGeneratedImages = true,
        )

        val decoded = LocalTaskSettingsCodec.decode(LocalTaskSettingsCodec.encode(settings))

        assertTrue(decoded.allowImages)
        assertTrue(decoded.allowGeneratedImages)
    }
}
