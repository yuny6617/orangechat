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

    /**
     * 常驻历史（真机要求"文字常驻可回看"）的边界：内容按顺序保留、只截掉最旧的、
     * 稳定 id 不重建。这几条同时是"长通话不会无限攒字符串"的证据。
     */
    @Test
    fun `常驻历史保留顺序并只截掉最旧的`() {
        var history = emptyList<DanmakuLine>()
        repeat(5) { index ->
            history = history.appendBounded(
                DanmakuLine(id = "line-$index", fromUser = index % 2 == 0, text = "第 $index 句", atMillis = index.toLong()),
                limit = 3,
            )
        }
        assertEquals(listOf("line-2", "line-3", "line-4"), history.map { it.id })
        assertEquals("第 4 句", history.last().text)
    }

    @Test
    fun `常驻历史在未超上限时逐条追加`() {
        val first = DanmakuLine("a", true, "一", 1L)
        val second = DanmakuLine("b", false, "二", 2L)
        val two = emptyList<DanmakuLine>().appendBounded(first, 10).appendBounded(second, 10)
        assertEquals(listOf("a", "b"), two.map { it.id })
    }

    @Test
    fun `常驻历史上限非法时退化成空而不是无限增长`() {
        var history = emptyList<DanmakuLine>()
        repeat(3) { index ->
            history = history.appendBounded(DanmakuLine("x$index", false, "字", index.toLong()), limit = 0)
        }
        assertTrue(history.isEmpty())
    }

    /** 同一句话被流式改写时用同一个 key 顶替，历史里不会出现两条同 id（LazyColumn key 冲突）。 */
    @Test
    fun `流式改写用稳定 id 不会产生重复 key`() {
        val key = "assistant:1:0"
        val history = emptyList<DanmakuLine>()
            .appendBounded(DanmakuLine(key, false, "半句", 1L), 10)
        val updated = history.dropLast(1) + DanmakuLine(key, false, "完整的一句", 1L)
        assertEquals(1, updated.count { it.id == key })
        assertEquals("完整的一句", updated.single().text)
    }
}
