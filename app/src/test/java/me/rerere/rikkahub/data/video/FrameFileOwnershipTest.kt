/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 永久帧文件的**所有权交接**测试（REVIEW_R4 §2/§7）。
 *
 * 这是"取消会不会把历史照片删掉"这条线上唯一的判断点，所以把每种次序都钉死，
 * 尤其是旧 Boolean 实现必错的那一种：
 * **已经发布、但 Room 提交被取消** —— 旧实现会删文件，历史里留下永远修不回来的断图。
 */
class FrameFileOwnershipTest {
    @Test
    fun `freshly written file is deletable`() {
        val ownership = FrameFileOwnership()
        assertTrue(ownership.isDeletable)
        assertEquals(FrameFileOwnership.Phase.OWNED, ownership.current)
        assertTrue(ownership.claimDeletion())
    }

    @Test
    fun `deletion claim is idempotent so both fallbacks cannot delete twice`() {
        val ownership = FrameFileOwnership()
        // 协程体的 finally 与 invokeOnCompletion 兜底可能同时走到这里。
        assertTrue(ownership.claimDeletion())
        assertFalse(ownership.claimDeletion())
        assertFalse(ownership.isDeletable)
    }

    /**
     * 核心回归：取消发生在"已经发布进内存历史、Room 提交被取消"的中间态。
     * 此时谁都不许删文件 —— 宁可留一张孤儿，也不能让历史断图。
     */
    @Test
    fun `cancellation in the middle of publishing must not delete the file`() {
        val ownership = FrameFileOwnership()
        assertTrue(ownership.markPublishing())
        // 假设这里协程被取消：之后既没有 markPublished，也没有 markSaveFailed。
        assertEquals(FrameFileOwnership.Phase.PUBLISHING, ownership.current)
        assertFalse(ownership.claimDeletion())
        assertFalse(ownership.isDeletable)
    }

    @Test
    fun `committed file is never deleted`() {
        val ownership = FrameFileOwnership()
        assertTrue(ownership.markPublishing())
        assertTrue(ownership.markPublished())
        assertEquals(FrameFileOwnership.Phase.PUBLISHED, ownership.current)
        assertFalse(ownership.claimDeletion())
        // 重复调用也不会翻回来。
        assertTrue(ownership.markPublished())
        assertFalse(ownership.claimDeletion())
    }

    @Test
    fun `explicit failure before publishing can still be cleaned up`() {
        val ownership = FrameFileOwnership()
        assertTrue(ownership.markPublishing())
        // 数据库事务明确失败（非取消）：文件从没被引用，交还给 finally 删除。
        assertTrue(ownership.markSaveFailed())
        assertTrue(ownership.claimDeletion())
    }

    @Test
    fun `gift turns start as durable so their frames are never deleted`() {
        val ownership = FrameFileOwnership(alreadyDurable = true)
        assertEquals(FrameFileOwnership.Phase.PUBLISHED, ownership.current)
        assertFalse(ownership.markPublishing())
        assertFalse(ownership.claimDeletion())
    }

    @Test
    fun `a published file cannot be made deletable again`() {
        val ownership = FrameFileOwnership()
        assertTrue(ownership.markPublishing())
        assertTrue(ownership.markPublished())
        // 后续的普通失败不能把"已经发布"这件事撤回来。
        assertFalse(ownership.markSaveFailed())
        assertFalse(ownership.claimDeletion())
    }

    @Test
    fun `claim after discard stays discarded`() {
        val ownership = FrameFileOwnership()
        assertTrue(ownership.claimDeletion())
        assertEquals(FrameFileOwnership.Phase.DISCARDED, ownership.current)
        // 之后再 markPublishing 也不会把状态拉回来（文件已经删了）。
        assertFalse(ownership.markPublishing())
        assertFalse(ownership.claimDeletion())
    }

    /** 并发认领：10 个线程同时抢，必须恰好一个成功（否则会重复删同一个文件）。 */
    @Test
    fun `only one concurrent claim wins`() {
        repeat(50) {
            val ownership = FrameFileOwnership()
            val winners = AtomicInteger(0)
            val start = CountDownLatch(1)
            val done = CountDownLatch(8)
            val pool = Executors.newFixedThreadPool(8)
            try {
                repeat(8) {
                    pool.execute {
                        start.await()
                        if (ownership.claimDeletion()) winners.incrementAndGet()
                        done.countDown()
                    }
                }
                start.countDown()
                assertTrue("并发认领超时", done.await(5, TimeUnit.SECONDS))
            } finally {
                pool.shutdownNow()
            }
            assertEquals(1, winners.get())
        }
    }

    /** 发布与取消认领并发：一旦 markPublishing 成功，删除权就永远拿不到。 */
    @Test
    fun `publishing and deletion race never deletes a referenced file`() {
        repeat(200) {
            val ownership = FrameFileOwnership()
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            var deleted = false
            val pool = Executors.newFixedThreadPool(2)
            try {
                pool.execute {
                    start.await()
                    ownership.markPublishing()
                    done.countDown()
                }
                pool.execute {
                    start.await()
                    if (ownership.claimDeletion()) deleted = true
                    done.countDown()
                }
                start.countDown()
                assertTrue("竞态测试超时", done.await(5, TimeUnit.SECONDS))
            } finally {
                pool.shutdownNow()
            }
            // 只有"抢在发布之前认领"才允许删；一旦发布过就绝不允许。
            if (deleted) {
                assertEquals(FrameFileOwnership.Phase.DISCARDED, ownership.current)
                assertFalse(ownership.markPublishing())
            } else {
                assertTrue(
                    "没删掉就说明它必须处于已发布/正在发布状态",
                    ownership.current == FrameFileOwnership.Phase.PUBLISHING ||
                        ownership.current == FrameFileOwnership.Phase.PUBLISHED,
                )
            }
        }
    }
}
