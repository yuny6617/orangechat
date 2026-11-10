/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 送礼提示词的**契约**测试（真机问题 #6 的回归钉子）。
 *
 * 真机上模型反复回答"我没有 ws_gift 这个工具""没有已审定的礼物 id 白名单"，
 * 并且引用了设置页那句"送礼提示词需要人工审定，未审定前建议保持关闭"。
 * 这不是模型不听话，而是文案本身把人往那个结论上引。
 *
 * 这里钉死四件事：
 * 1. 提示词必须明说"这不是工具/tool 调用，不需要审定"；
 * 2. 必须带上**当前可用清单**（与解析器同一份白名单，不能手写一份会漂移的）；
 * 3. 关闭时的话术必须说明"是那个开关没开"，而不是"没有这个能力"；
 * 4. 提示词与设置文案里都不允许再出现"人工审定/未审定"这类误导措辞。
 */
class VideoGiftPromptContractTest {

    @Test
    fun `送礼提示词明说这不是工具调用`() {
        val prompt = VideoCallPrompts.GIFT_INSTRUCTION
        assertTrue(prompt.contains("不是"))
        assertTrue(prompt.contains("工具"))
        assertTrue(prompt.contains("tool schema"))
        assertTrue(prompt.contains("不需要"))
    }

    @Test
    fun `送礼提示词带上当前可用清单且与白名单一致`() {
        val prompt = VideoCallPrompts.GIFT_INSTRUCTION
        VideoGiftCatalog.all.forEach { gift ->
            assertTrue("缺少礼物 id: ${gift.id}", prompt.contains(gift.id))
            assertTrue("缺少礼物展示名: ${gift.displayName}", prompt.contains(gift.displayName))
        }
        // 一次性把清单整段抄进去，避免"手写名单"与解析器漂移。
        assertTrue(prompt.contains(VideoGiftCatalog.promptList()))
    }

    /**
     * "不再要求人工审定"要钉的是**那句误导文案本身**，不是"审定"两个字。
     *
     * 提示词里故意保留了「也不存在『需要人工审定后才能用』这回事」这种**否定式**说明
     * （那是纠正模型误解的关键句），所以只能断言误导性短语不出现。
     */
    @Test
    fun `送礼提示词不再要求人工审定`() {
        assertFalse(VideoCallPrompts.GIFT_INSTRUCTION.contains("需要人工审定"))
        assertFalse(VideoCallPrompts.GIFT_INSTRUCTION.contains("未审定前建议保持关闭"))
        assertFalse(VideoCallPrompts.GIFT_DISABLED_NOTE.contains("需要人工审定"))
        assertFalse(VideoCallPrompts.GIFT_DISABLED_NOTE.contains("未审定前建议保持关闭"))
    }

    @Test
    fun `关闭礼物时说的是开关没开而不是没有能力`() {
        val note = VideoCallPrompts.GIFT_DISABLED_NOTE
        assertTrue(note.contains("设置"))
        assertTrue(note.contains("视频通话礼物"))
        // 明确禁止把"关着"解释成"没有工具/没有白名单"。
        assertTrue(note.contains("不是"))
        assertTrue(note.contains("ws_gift"))
    }

    @Test
    fun `开启时注入完整提示词 关闭时绝不注入标签`() {
        val enabled = VideoCallPrompts.turnContext(
            VideoCallContext(cameraAvailable = false, giftsEnabled = true),
        )
        assertTrue(enabled.contains(VideoCallPrompts.GIFT_INSTRUCTION))
        assertFalse(enabled.contains(VideoCallPrompts.GIFT_DISABLED_NOTE))

        val disabled = VideoCallPrompts.turnContext(
            VideoCallContext(cameraAvailable = false, giftsEnabled = false),
        )
        assertTrue(disabled.contains(VideoCallPrompts.GIFT_DISABLED_NOTE))
        assertFalse(disabled.contains(VideoCallPrompts.GIFT_INSTRUCTION))
    }

    /** 已经送过礼的那一轮仍然要汇总，避免模型重复堆数量。 */
    @Test
    fun `已送礼物会出现在下一轮提示里`() {
        val tally = VideoGiftTally().plus(VideoGift.HEART).plus(VideoGift.HEART)
        val text = VideoCallPrompts.turnContext(
            VideoCallContext(cameraAvailable = false, giftsEnabled = true, giftsSent = tally),
        )
        assertTrue(text.contains("小心心"))
    }

    /**
     * P9 的真实原因之一：`conversationSystemPrompt` 会被 `allowConversationSystemPrompt`（默认 false）
     * 整段丢掉，而视频提示词过去正是混在那里面 → 模型从没收到礼物清单。
     *
     * 现在视频轮的通话上下文走 `appendedSystemContext`，**追加**在主文后面。
     * 这里用的是真正参与 provider 系统提示词拼装的函数。
     */
    @Test
    fun `视频轮的通话上下文追加在系统提示词后面 不会被开关丢掉`() {
        val giftContext = VideoCallPrompts.turnContext(
            VideoCallContext(
                cameraAvailable = true,
                giftsEnabled = true,
                pictureAttached = true,
                giftNames = mapOf("heart" to "小可爱"),
            ),
        )
        val composed = me.rerere.rikkahub.data.ai.appendTurnSystemContext(
            me.rerere.rikkahub.data.ai.composeSystemPromptText(
                assistantSystemPrompt = "你是助手",
                conversationSystemPrompt = null,
                allowConversationSystemPrompt = false,
            ),
            giftContext,
        )
        assertTrue("主文必须保留（旧实现会被整段替换掉）", composed.contains("你是助手"))
        assertTrue("礼物能力清单必须真的进了系统提示词", composed.contains("heart（小可爱）"))
        assertTrue(composed.contains("<ws_gift id=\"礼物id\"/>"))

        // 既有语义不变：开关打开时对话级重写仍然替换主文。
        assertEquals(
            "对话级重写",
            me.rerere.rikkahub.data.ai.composeSystemPromptText("你是助手", "对话级重写", true),
        )
        assertEquals(
            "你是助手",
            me.rerere.rikkahub.data.ai.composeSystemPromptText("你是助手", "对话级重写", false),
        )
        // 没有本轮上下文时主文原样返回（普通聊天一行都不变）。
        assertEquals(
            "你是助手",
            me.rerere.rikkahub.data.ai.appendTurnSystemContext("你是助手", null),
        )
        assertEquals(
            "你是助手",
            me.rerere.rikkahub.data.ai.appendTurnSystemContext("你是助手", "   "),
        )
    }
}
