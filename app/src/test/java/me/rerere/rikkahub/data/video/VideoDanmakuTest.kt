/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDanmakuTest {
    @Test
    fun `bubbles appear only after a sentence boundary`() {
        val stream = DanmakuStream()
        assertTrue(stream.push("你好").isEmpty())
        assertEquals(listOf("你好。"), stream.push("你好。今天"))
        assertEquals(listOf("今天"), stream.flush("你好。今天"))
        assertTrue(stream.flush("你好。今天").isEmpty())
    }

    @Test
    fun `several finished sentences become several bubbles`() {
        val stream = DanmakuStream()
        assertEquals(listOf("一。", "二？", "三！"), stream.push("一。二？三！接下来"))
        assertEquals(listOf("接下来"), stream.flush("一。二？三！接下来"))
    }

    @Test
    fun `long line folds without losing characters`() {
        val stream = DanmakuStream(maxLineLength = 6)
        val lines = stream.push("一二三四五六七。")
        assertEquals(listOf("一二三四五六", "七。"), lines)
        assertEquals("一二三四五六七。", lines.joinToString(""))
    }

    @Test
    fun `stream reset starts a new turn cleanly`() {
        val stream = DanmakuStream()
        assertEquals(listOf("上一条。"), stream.push("上一条。"))
        stream.reset()
        assertEquals(listOf("新的一条。"), stream.push("新的一条。"))
    }

    @Test
    fun `shrinking text never throws or duplicates`() {
        val stream = DanmakuStream()
        assertEquals(listOf("完整的一句。"), stream.push("完整的一句。"))
        assertTrue(stream.push("完整的一句。").isEmpty())
        assertTrue(stream.push("完整").isEmpty())
    }

    @Test
    fun `blank flush produces nothing`() {
        val stream = DanmakuStream()
        assertTrue(stream.flush("").isEmpty())
        assertTrue(stream.flush("   ").isEmpty())
    }
}
