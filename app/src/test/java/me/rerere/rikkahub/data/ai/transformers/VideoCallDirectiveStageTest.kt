/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import me.rerere.rikkahub.data.video.VideoGift
import me.rerere.rikkahub.data.video.VideoGiftProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三阶段职责表 + 请求作用域的**累积原文**解析。
 *
 * 这是 REVIEW_R3 §1 的回归点：注释写着"Streaming 不改正文"，代码却在流式阶段就剥离。
 * 这里用生产代码本身（[VideoDirectiveStage.plan] / [VideoCallDirectiveSession] /
 * [VideoGiftProtocol.strip]）把这条契约钉成可执行的断言。
 *
 * 读代码核实的管线事实（`GenerationHandler` / `generateModelTurn`）：
 * - 每片 delta 通过 `handleMessageChunk` 合并到 `initialMessages`（**原始**消息）上，
 *   `onUpdateMessages` 收到的 `List<UIMessage>` 就是这份累积原文；
 * - `transforms(...)`（Streaming）的输出被上层继续用于 `visualTransforms`/`onGenerationFinish`
 *   并最终持久化，**没有**回流成 `generateModelTurn` 的合并基准。
 *
 * 所以旧代码是"违反契约但侥幸没坏"；如果照注释说的契约走（Streaming 原样返回），
 * 就不会有任何调用方拿到被删过的正文、也就不存在"把剥离结果当合并基准"的隐患。
 * 第二条测试把这个隐患写成了可执行的反例，理由如实标注在生产代码注释里。
 */
class VideoCallDirectiveStageTest {
    @Test
    fun `streaming only records while display strips and finish executes`() {
        val streaming = VideoDirectiveStage.Streaming.plan()
        assertTrue("只有 Streaming 能看到原始流，必须由它解析", streaming.parse)
        assertFalse("Streaming 绝不能改正文", streaming.strip)
        assertFalse(streaming.execute)
        assertFalse(streaming.bufferPartialTags)

        val display = VideoDirectiveStage.Display.plan()
        assertFalse("Display 拿到的是合并后的正文，不能重新解析协议", display.parse)
        assertTrue(display.strip)
        assertFalse(display.execute)
        assertTrue("显示层要把末尾半截标签先藏起来", display.bufferPartialTags)

        val finish = VideoDirectiveStage.Finish.plan()
        assertFalse(finish.parse)
        assertTrue("最终正文必须干净", finish.strip)
        assertTrue(finish.execute)
        assertFalse("结束时不会再有半截标签", finish.bufferPartialTags)
    }

    @Test
    fun `stripping during streaming destroys a tag split across deltas`() {
        val deltas = listOf("好呀 ", "<ws", "_gift id=\"hea", "rt\"/>", " 拿着")
        // 每条 delta 到达时的真实累积原文。
        val accumulated = deltas.scan("") { acc, delta -> acc + delta }.drop(1)

        // 反例（**契约防护**，不是说生产管线已经这样）：一旦剥离结果被当成下一次
        // 合并的基准，半截标签就被删掉，下一片 delta 被拼进已删过的正文 —— 这正是
        // "Streaming 必须原样返回"要避免的形态。
        var wronglyStripped = ""
        deltas.forEach { delta ->
            wronglyStripped = VideoGiftProtocol.strip(wronglyStripped + delta, streaming = true)
        }
        assertNull("剥离结果被当作合并基准时，礼物标签会被永久拆散", VideoGiftProtocol.firstGift(wronglyStripped))
        assertTrue("残片会作为垃圾正文漏出来", wronglyStripped.contains("id=\""))

        // 正确：Streaming 只记录累积原文，最后在显示层剥离。
        val finalRaw = accumulated.last()
        assertEquals(VideoGift.HEART, VideoGiftProtocol.firstGift(finalRaw))
        assertEquals("好呀 拿着", VideoGiftProtocol.strip(finalRaw, streaming = true))
    }

    @Test
    fun `session freezes the intent from cumulative deltas and never loses it later`() {
        val session = VideoCallDirectiveSession()
        val deltas = listOf("好呀 ", "<ws", "_gift id=\"hea", "rt\"/>", " 拿着")
        var accumulated = ""
        var last = VideoDirectiveIntent(messageId = "m1")
        deltas.forEach { delta ->
            accumulated += delta
            last = session.recordStreaming("m1", accumulated)
            // 拿到完整标签之前不能提前识别
            if (!accumulated.contains("/>")) {
                assertNull("半截标签不该被当成礼物", last.gift)
            }
        }
        assertEquals(VideoGift.HEART, last.gift)
        assertEquals(VideoGift.HEART, session.intentOf("m1")?.gift)
        // 冻结之后，即使正文被剥离/变短，解析结果也不会丢（Finish 只读它）。
        session.recordStreaming("m1", "好呀 拿着")
        assertEquals(VideoGift.HEART, session.intentOf("m1")?.gift)
        // 幂等：重复回调不会重复消费，也不会改变结果
        session.recordStreaming("m1", accumulated)
        assertEquals(VideoGift.HEART, session.intentOf("m1")?.gift)
    }

    @Test
    fun `snapshot intent sticks once seen and gift keeps the first one`() {
        val session = VideoCallDirectiveSession()
        session.recordStreaming("m1", "看看")
        session.recordStreaming("m1", "看看<ws_vsnap")
        assertFalse(session.intentOf("m1")!!.snapshotRequested)
        session.recordStreaming("m1", "看看<ws_vsnap/>")
        assertTrue(session.intentOf("m1")!!.snapshotRequested)
        // 后面的正文里没有标签了，之前登记的留影请求仍然有效
        session.recordStreaming("m1", "看看 <ws_vsnap/> 然后继续聊")
        assertTrue(session.intentOf("m1")!!.snapshotRequested)

        session.recordStreaming("m2", "<ws_gift id=\"heart\"/><ws_gift id=\"meteor\"/>")
        assertEquals(VideoGift.HEART, session.intentOf("m2")?.gift)
        session.recordStreaming("m2", "<ws_gift id=\"heart\"/><ws_gift id=\"meteor\"/> 收下吧")
        assertEquals("先到的那一枚不会被后来的顶掉", VideoGift.HEART, session.intentOf("m2")?.gift)
    }

    @Test
    fun `session keeps the raw body and clears with the request`() {
        val session = VideoCallDirectiveSession()
        session.recordStreaming("m1", "原始正文 <ws_gift id=\"heart\"/>")
        assertEquals("原始正文 <ws_gift id=\"heart\"/>", session.rawOf("m1"))
        assertEquals(1, session.size)
        assertFalse(session.isEmpty())
        session.clear()
        assertTrue(session.isEmpty())
        assertNull(session.intentOf("m1"))
        assertNull(session.rawOf("m1"))
        // 空白消息 ID 不登记任何东西
        session.recordStreaming("   ", "x")
        assertTrue(session.isEmpty())
    }

    @Test
    fun `session stays bounded for long tool loops`() {
        val session = VideoCallDirectiveSession()
        repeat(20) { index -> session.recordStreaming("m$index", "<ws_gift id=\"heart\"/>") }
        assertTrue("跟踪的消息数必须有上限", session.size <= 8)
        assertNotNull(session.intentOf("m19"))
        assertNull("最旧的已经被淘汰", session.intentOf("m0"))
    }
}
