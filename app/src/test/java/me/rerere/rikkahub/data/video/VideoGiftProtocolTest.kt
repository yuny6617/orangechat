/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoGiftProtocolTest {
    @Test
    fun `complete gift tag is parsed and stripped`() {
        val text = "送你一朵花 <ws_gift id=\"bouquet\"/> 收下吧"
        assertEquals("送你一朵花 收下吧", VideoGiftProtocol.strip(text))
        assertEquals(listOf(VideoGift.BOUQUET), VideoGiftProtocol.gifts(text))
        assertEquals(VideoGift.BOUQUET, VideoGiftProtocol.firstGift(text))
    }

    @Test
    fun `paired form and single quotes are accepted`() {
        assertEquals(listOf(VideoGift.HEART), VideoGiftProtocol.gifts("<ws_gift>heart</ws_gift>"))
        assertEquals(listOf(VideoGift.HEART), VideoGiftProtocol.gifts("<ws_gift id='heart'/>"))
        assertEquals("", VideoGiftProtocol.strip("<ws_gift>heart</ws_gift>").trim())
    }

    @Test
    fun `unknown gift id is stripped but never executed`() {
        val text = "试试 <ws_gift id=\"diamond\"/>"
        assertEquals("试试", VideoGiftProtocol.strip(text).trim())
        assertTrue(VideoGiftProtocol.gifts(text).isEmpty())
    }

    @Test
    fun `streaming partial tags never leak`() {
        assertEquals("嗨", VideoGiftProtocol.strip("嗨<ws_gift id=\"hea", streaming = true))
        assertEquals("嗨", VideoGiftProtocol.strip("嗨<ws_gif", streaming = true))
        assertEquals("嗨", VideoGiftProtocol.strip("嗨<ws_", streaming = true))
        assertEquals("嗨", VideoGiftProtocol.strip("嗨<ws", streaming = true))
        assertEquals("嗨", VideoGiftProtocol.strip("嗨<w", streaming = true))
        // 连单个 '<' 也必须缓冲：否则 '<<' 之后的半截标签会提前漏进 TTS
        assertEquals("嗨", VideoGiftProtocol.strip("嗨<", streaming = true))
        assertEquals("嗨", VideoGiftProtocol.strip("嗨<ws_vsnap", streaming = true))
        assertEquals("嗨", VideoGiftProtocol.strip("嗨</ws_gift", streaming = true))
        assertEquals("嗨", VideoGiftProtocol.strip("嗨</ws_", streaming = true))
        // 完整标签之后的半截也要藏
        assertEquals("嗨 然后", VideoGiftProtocol.strip("嗨<ws_gift id=\"heart\"/>然后<ws", streaming = true))
        // 非流式（最终正文）不藏普通文本末尾的 '<'
        assertEquals("3 <", VideoGiftProtocol.strip("3 <"))
    }

    @Test
    fun `only canonical complete tags are executed`() {
        // 宽松正则会把这些截成合法 id —— 现在一律不执行
        assertTrue(VideoGiftProtocol.gifts("<ws_gift id=\"heart-bad\"/>").isEmpty())
        assertTrue(VideoGiftProtocol.gifts("<ws_gift data-id=\"heart\"/>").isEmpty())
        assertTrue(VideoGiftProtocol.gifts("<ws_gift id=\"heart\" extra=\"1\"/>").isEmpty())
        assertTrue(VideoGiftProtocol.gifts("<ws_gift id=\"heart\">").isEmpty())
        assertTrue(VideoGiftProtocol.gifts("<ws_gift id=\"heart\"/>").isNotEmpty())
        assertTrue(VideoGiftProtocol.gifts("<ws_gift id='heart'/>").isNotEmpty())
        assertTrue(VideoGiftProtocol.gifts("<ws_gift id = \"heart\" />").isNotEmpty())
        // 非法标签仍然被剥离，不留在正文里
        assertEquals("", VideoGiftProtocol.strip("<ws_gift id=\"heart-bad\"/>").trim())
    }

    @Test
    fun `gifts are parsed in text order not by tag shape`() {
        // 旧实现先遍历成对标签，会把后面的 meteor 当成第一枚
        val text = "<ws_gift id=\"heart\"/> 然后 <ws_gift>meteor</ws_gift>"
        assertEquals(listOf(VideoGift.HEART, VideoGift.METEOR_SHOWER), VideoGiftProtocol.gifts(text))
        assertEquals(VideoGift.HEART, VideoGiftProtocol.firstGift(text))
        val pairedFirst = "<ws_gift>fireworks</ws_gift> 然后 <ws_gift id=\"heart\"/>"
        assertEquals(VideoGift.FIREWORKS, VideoGiftProtocol.firstGift(pairedFirst))
    }

    @Test
    fun `snapshot tag is stripped and detected`() {
        assertEquals("看这里", VideoGiftProtocol.strip("看这里<ws_vsnap/>"))
        assertTrue(VideoGiftProtocol.requestsSnapshot("看这里<ws_vsnap/>"))
        assertTrue(VideoGiftProtocol.requestsSnapshot("<ws_vsnap />"))
        assertFalse(VideoGiftProtocol.requestsSnapshot("看这里"))
        // 只有 "<ws_vsnap" 而没有自闭合，不算请求
        assertFalse(VideoGiftProtocol.requestsSnapshot("中间有 <ws_vsnap 和 /> 但不是完整标签"))
    }

    @Test
    fun `plain text with a bare less than sign is untouched`() {
        // "<3" 不是协议前缀：既不能被剥离，也不能被当成半截标签藏起来
        assertEquals("好可爱 <3", VideoGiftProtocol.strip("好可爱 <3", streaming = true))
        assertEquals("好可爱 <3", VideoGiftProtocol.strip("好可爱 <3"))
        assertEquals("1 < 2 > 0", VideoGiftProtocol.strip("1 < 2 > 0"))
        assertTrue(VideoGiftProtocol.gifts("好可爱 <3").isEmpty())
        assertFalse(VideoGiftProtocol.requestsSnapshot("a < b"))
    }

    @Test
    fun `protocol inside a fenced code block is neither stripped nor executed`() {
        val code = "示例：\n```html\n<ws_gift id=\"heart\"/>\n```\n就这样"
        // 代码块原样保留（不要因为代码里的标签把代码本身改掉）
        assertTrue(VideoGiftProtocol.strip(code).contains("<ws_gift id=\"heart\"/>"))
        // 也不执行
        assertTrue(VideoGiftProtocol.gifts(code).isEmpty())
        assertTrue(VideoGiftProtocol.gifts("```\n<ws_vsnap/>\n```").isEmpty())
        // 代码块之外的协议照常生效
        val mixed = "先看代码：\n```\n<ws_gift id=\"heart\"/>\n```\n然后真的送你 <ws_gift id=\"meteor\"/>"
        assertEquals(listOf(VideoGift.METEOR_SHOWER), VideoGiftProtocol.gifts(mixed))
        assertTrue(VideoGiftProtocol.strip(mixed).contains("<ws_gift id=\"heart\"/>"))
        assertFalse(VideoGiftProtocol.strip(mixed).contains("meteor"))
    }

    @Test
    fun `unclosed code fence stops protocol execution`() {
        // 流式期间只开了代码块：后面的示例标签不执行（宁可少执行，也不错误送礼）
        val partial = "看这段代码\n```kotlin\n<ws_gift id=\"heart\"/>"
        assertTrue(VideoGiftProtocol.gifts(partial).isEmpty())
        assertEquals(partial, VideoGiftProtocol.strip(partial, streaming = true))
    }

    @Test
    fun `plain text is returned untouched`() {
        val text = "今天天气不错，你在做什么？"
        assertEquals(text, VideoGiftProtocol.strip(text))
        assertTrue(VideoGiftProtocol.gifts(text).isEmpty())
    }

    @Test
    fun `ledger grants at most one gift per reply and blocks replay`() {
        val ledger = VideoGiftLedger()
        val text = "<ws_gift id=\"fireworks\"/><ws_gift id=\"meteor\"/>"
        // 解析只发生在 Streaming 阶段；台账只接受已经白名单校验过的结果。
        assertEquals(VideoGift.FIREWORKS, ledger.grant("m1", VideoGiftProtocol.firstGift(text)))
        assertNull(ledger.grant("m1", VideoGiftProtocol.firstGift(text)))
        assertNull(ledger.grant("m1", VideoGiftProtocol.firstGift("<ws_gift id=\"heart\"/>")))
        assertEquals(VideoGift.FIREWORKS, ledger.giftOf("m1"))
        // 另一条回复重新按文本顺序取第一枚（m2 的正文只有 meteor，不是上面那两枚的拼串）。
        assertEquals(VideoGift.METEOR_SHOWER, ledger.grant("m2", VideoGiftProtocol.firstGift("<ws_gift id=\"meteor\"/>")))
        assertEquals(listOf(VideoGift.FIREWORKS, VideoGift.METEOR_SHOWER), ledger.granted())
        ledger.clear()
        assertTrue(ledger.granted().isEmpty())
    }

    @Test
    fun `ledger ignores blank ids illegal gifts and tag-free text`() {
        val ledger = VideoGiftLedger()
        assertNull(ledger.grant("", VideoGift.HEART))
        assertNull(ledger.grant("m1", VideoGiftProtocol.firstGift("<ws_gift id=\"not_a_gift\"/>")))
        assertNull(ledger.grant("m1", VideoGiftProtocol.firstGift("根本没有标签")))
        assertNull(ledger.grant("m1", null))
        assertTrue(ledger.granted().isEmpty())
    }

    @Test
    fun `ledger keeps memory bounded`() {
        val ledger = VideoGiftLedger(capacity = 3)
        (1..6).forEach { index -> ledger.grant("m$index", VideoGift.HEART) }
        assertEquals(3, ledger.granted().size)
        assertNull(ledger.giftOf("m1"))
        assertEquals(VideoGift.HEART, ledger.giftOf("m6"))
    }

    @Test
    fun `tally counts by tier and builds the end-of-call summary`() {
        val tally = VideoGiftTally.of(
            listOf(VideoGift.HEART, VideoGift.HEART, VideoGift.BOUQUET, VideoGift.GALAXY_TRAIN),
        )
        assertEquals(4, tally.total)
        assertEquals(2, tally.countOf(VideoGift.HEART))
        assertEquals(0, tally.countOf(VideoGift.FIREWORKS))
        assertEquals("小心心 ×2 · 花束 ×1 · 银河铁道之夜 ×1", tally.summary())
        assertEquals("视频礼物：小心心 ×2 · 花束 ×1 · 银河铁道之夜 ×1", giftSummaryLine(tally))
        assertEquals("", giftSummaryLine(VideoGiftTally()))
        assertTrue(VideoGiftTally().isEmpty)
    }

    @Test
    fun `catalog is a strict whitelist of five virtual gifts`() {
        assertEquals(5, VideoGiftCatalog.all.size)
        assertEquals(
            setOf("heart", "bouquet", "fireworks", "meteor", "galaxy_train"),
            VideoGiftCatalog.ids,
        )
        assertNull(VideoGiftCatalog.byId("red_packet"))
        assertNull(VideoGiftCatalog.byId(null))
        assertTrue(VideoGift.HEART.tier < VideoGift.GALAXY_TRAIN.tier)
        assertFalse(VideoGift.HEART.isFullScreen)
        assertTrue(VideoGift.FIREWORKS.isFullScreen)
        assertTrue(VideoGift.GALAXY_TRAIN.isFullScreen)
    }
}
