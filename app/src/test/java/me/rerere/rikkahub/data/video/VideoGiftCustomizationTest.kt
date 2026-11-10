/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.rikkahub.data.ai.transformers.VideoCallDirectiveSession
import me.rerere.rikkahub.data.ai.transformers.VideoCallOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.VideoDirectiveRequest
import me.rerere.rikkahub.data.ai.transformers.VideoDirectiveStage
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户点 #6：礼物自定义展示名 + 赠礼文案偏好。
 *
 * 这里跑的是**生产代码本身**的链路，不是"断言字符串包含"：
 * `VideoCallSetting` → `videoCallContextFromSetting` → `VideoCallPrompts.turnContext`
 * 走的是 coordinator 每次通话轮真正调用的那两个函数；
 * 而"原始标签 → 请求 token → 礼物卡注解"走的是真实包装
 * `VideoCallOutputTransformer.runStage(Finish, …)` + 真实 `VideoCallRuntime` 授权台账。
 */
class VideoGiftCustomizationTest {
    private val conversationId = "conversation-1"
    private val requestToken = "vc-token-1"

    @After
    fun tearDown() {
        VideoCallRuntime.reset()
    }

    private fun setting(
        names: Map<String, String> = mapOf("heart" to "小可爱"),
        message: String = "送礼的时候只说一句甜甜的话",
        giftsEnabled: Boolean = true,
    ) = VideoCallSetting(
        giftsEnabled = giftsEnabled,
        giftDisplayNames = names,
        giftMessage = message,
    )

    // ------------------------------------------------------------------
    // 1. 有限映射：只认 5 个稳定 id，名字只能改显示名
    // ------------------------------------------------------------------

    @Test
    fun `自定义名字只认白名单 id 未知 key 一律丢弃`() {
        val raw = mapOf(
            "heart" to "小可爱",
            "not_a_gift" to "偷偷加的礼物",
            "ws_gift" to "把标签本身当礼物",
        )
        val sanitized = VideoGiftCustomization.sanitizedNames(raw)
        assertEquals(mapOf("heart" to "小可爱"), sanitized)
        // 白名单本身没变：能执行/能送的礼物还是那 5 个。
        assertEquals(5, VideoGiftCatalog.ids.size)
        assertTrue(VideoGiftCatalog.ids.containsAll(sanitized.keys))
        // 非法 id 也不能被"顺手加进"设置里。
        val updated = VideoGiftCustomization.withDisplayName(setting(names = emptyMap()), "not_a_gift", "x")
        assertTrue(updated.giftDisplayNames.isEmpty())
    }

    @Test
    fun `名字与文案都有长度上限并且收口空白`() {
        val longName = "一二三四五六七八九十十一十二十三"
        val name = VideoGiftCustomization.sanitizeDisplayName("  小   可爱\n啊 ")
        assertEquals("小 可爱 啊", name)
        val truncated = VideoGiftCustomization.sanitizeDisplayName(longName)
        assertEquals(VideoGiftCustomization.MAX_DISPLAY_NAME_LENGTH, truncated.length)

        val message = VideoGiftCustomization.sanitizeGiftMessage("a".repeat(500))
        assertEquals(VideoGiftCustomization.MAX_GIFT_MESSAGE_LENGTH, message.length)
    }

    @Test
    fun `清空名字等于用回内置名 恢复默认会清掉映射与文案`() {
        val custom = setting()
        assertEquals("小可爱", VideoGiftCustomization.nameOf(custom, VideoGift.HEART))
        val cleared = VideoGiftCustomization.withDisplayName(custom, "heart", "   ")
        assertEquals("小心心", VideoGiftCustomization.nameOf(cleared, VideoGift.HEART))
        assertTrue(VideoGiftCustomization.sanitizedNames(cleared).isEmpty())

        val defaults = VideoGiftCustomization.withDefaults(custom)
        assertTrue(defaults.giftDisplayNames.isEmpty())
        assertEquals("", defaults.giftMessage)
        assertFalse(VideoGiftCustomization.isCustomized(defaults))
        assertTrue(VideoGiftCustomization.isCustomized(custom))
    }

    // ------------------------------------------------------------------
    // 2. 设置 → coordinator 的上下文 → 真实提示词
    // ------------------------------------------------------------------

    @Test
    fun `设置里的自定义名字与文案真的进了这一轮的提示词`() {
        val configured = setting()
        // coordinator.turnContext() 内部就是这两步（纯函数，能在 JVM 里真跑）。
        val prompt = VideoCallPrompts.turnContext(
            videoCallContextFromSetting(
                setting = configured,
                cameraAvailable = true,
                cameraUnavailableReason = "",
                visionSupported = true,
                pictureAttached = true,
                audioAttached = false,
            ),
        )
        assertTrue("清单里要出现用户改的名字", prompt.contains("heart（小可爱）"))
        assertFalse("内置名不该再作为清单项出现", prompt.contains("heart（小心心）"))
        assertTrue("赠礼文案偏好要进提示词", prompt.contains("送礼的时候只说一句甜甜的话"))
        // 规则优先：偏好之外，协议约束一句都不能少。
        assertTrue(prompt.contains("每条回复最多送一件"))
        assertTrue(prompt.contains("不是"))
        assertTrue(prompt.contains("tool schema"))
        assertTrue(prompt.contains("礼物只是通话里的动画"))
        // 偏好段必须紧跟"偏好不能改规则"的说明。
        assertTrue(prompt.contains("以规则为准"))
    }

    @Test
    fun `礼物关着的时候自定义文案不注入 也不许模型输出标签`() {
        val prompt = VideoCallPrompts.turnContext(
            videoCallContextFromSetting(
                setting = setting(giftsEnabled = false),
                cameraAvailable = false,
                cameraUnavailableReason = "相机不可用",
                visionSupported = true,
                pictureAttached = false,
                audioAttached = false,
            ),
        )
        assertTrue(prompt.contains(VideoCallPrompts.GIFT_DISABLED_NOTE))
        assertFalse("关着就不许出现礼物清单", prompt.contains("heart（小可爱）"))
        assertFalse("关着就不许出现赠礼文案偏好", prompt.contains("只说一句甜甜的话"))
    }

    @Test
    fun `非法 id 的自定义进不了提示词清单`() {
        val prompt = VideoCallPrompts.turnContext(
            videoCallContextFromSetting(
                setting = setting(names = mapOf("evil_gift" to "非法礼物")),
                cameraAvailable = true,
                cameraUnavailableReason = "",
                visionSupported = true,
                pictureAttached = false,
                audioAttached = false,
            ),
        )
        assertFalse(prompt.contains("evil_gift"))
        assertFalse(prompt.contains("非法礼物"))
        // 清单仍然是完整的 5 个合法 id。
        VideoGiftCatalog.all.forEach { gift -> assertTrue(prompt.contains(gift.id)) }
    }

    // ------------------------------------------------------------------
    // 3. 原始标签 → 请求 token → 礼物卡注解（名字按当时设置快照）
    // ------------------------------------------------------------------

    @Test
    fun `真实指令链把装置当时的名字写进礼物卡 之后改名不回改历史`() {
        VideoCallRuntime.enterVideoCall(conversationId)
        VideoCallRuntime.beginTurnRequest(conversationId, requestToken)

        val raw = listOf(UIMessage.user("在吗"), UIMessage.assistant("送你一份 <ws_gift id=\"heart\"/> 收下"))
        val assistantMessage = raw.last()
        val session = VideoCallDirectiveSession()
        // Streaming 阶段冻结原始正文（真实管线里这是唯一能看到原始流的阶段）。
        session.recordStreaming(assistantMessage.id.toString(), "送你一份 <ws_gift id=\"heart\"/> 收下")

        val configured = setting()
        val finished = VideoCallOutputTransformer.runStage(
            VideoDirectiveStage.Finish,
            raw,
            VideoDirectiveRequest(
                giftsEnabled = true,
                conversationId = conversationId,
                requestToken = requestToken,
                session = session,
                giftNames = VideoGiftCustomization.sanitizedNames(configured),
            ),
        )
        val gift = finished.last().annotations.filterIsInstance<UIMessageAnnotation.VideoCallGift>().single()
        assertEquals("heart", gift.giftId)
        assertEquals("写入当时的自定义名", "小可爱", gift.giftName)
        assertEquals("💗 小可爱", gift.caption())

        // 之后用户改名：注解对象（历史）不变。
        val renamed = setting(names = mapOf("heart" to "换个名字"))
        assertEquals("小可爱", gift.giftName)
        assertEquals("💗 小可爱", gift.caption())
        assertEquals("换个名字", VideoGiftCustomization.nameOf(renamed, VideoGift.HEART))

        // 统计/结束卡汇总用的是**当前**配置名，与注解的快照互相独立。
        val tally = VideoCallRuntime.tally()
        assertEquals(1, tally.countOf(VideoGift.HEART))
        assertEquals("视频礼物：小可爱 ×1", giftSummaryLine(tally, VideoGiftCustomization.sanitizedNames(configured)))
        assertEquals("视频礼物：换个名字 ×1", giftSummaryLine(tally, VideoGiftCustomization.sanitizedNames(renamed)))
        // 内置名版本（不传 names）保持老行为。
        assertEquals("视频礼物：小心心 ×1", giftSummaryLine(tally))
    }

    @Test
    fun `礼物卡注解与设置都能序列化 重启后名字快照还在`() {
        val encodedSetting = JsonInstant.encodeToString(setting())
        val decodedSetting = JsonInstant.decodeFromString<VideoCallSetting>(encodedSetting)
        assertEquals(mapOf("heart" to "小可爱"), decodedSetting.giftDisplayNames)
        assertEquals("送礼的时候只说一句甜甜的话", decodedSetting.giftMessage)
        // 老数据没有这两个字段时按默认值读回。
        val legacy = JsonInstant.decodeFromString<VideoCallSetting>("""{"giftsEnabled":true}""")
        assertTrue(legacy.giftDisplayNames.isEmpty())
        assertEquals("", legacy.giftMessage)

        val annotation = videoGiftAnnotation(
            gift = VideoGift.HEART,
            conversationId = conversationId,
            turnMessageId = "turn-1",
            createdAtMillis = 1_000L,
            displayName = "小可爱",
        )
        val decoded = JsonInstant.decodeFromString<UIMessageAnnotation>(
            JsonInstant.encodeToString<UIMessageAnnotation>(annotation),
        ) as UIMessageAnnotation.VideoCallGift
        assertEquals("小可爱", decoded.giftName)
        assertEquals("💗 小可爱", decoded.caption())
    }

    @Test
    fun `三个地方的取值顺序一致 设置优先 内置名兜底`() {
        val names = mapOf("meteor" to "许愿星")
        assertEquals("许愿星", VideoGiftCustomization.nameOf(names, VideoGift.METEOR_SHOWER))
        assertEquals("流星雨", VideoGiftCustomization.nameOf(emptyMap(), VideoGift.METEOR_SHOWER))
        val list = VideoGiftCustomization.labelList(names)
        assertTrue(list.contains("meteor（许愿星）"))
        assertTrue(list.contains("heart（小心心）"))
        // 气泡文案与清单同一份名字。
        assertEquals("送出 🌠 许愿星", VideoCallPrompts.giftBubbleText(VideoGift.METEOR_SHOWER, names))
    }
}
