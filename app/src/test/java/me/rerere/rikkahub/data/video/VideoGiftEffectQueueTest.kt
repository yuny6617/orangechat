/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全屏特效队列 / 事件身份的真实回归（纯 JVM，直接测生产代码，不引入测试专用状态机）。
 *
 * 覆盖的缺陷：旧实现把 [VideoGift] 枚举直接放进 `mutableStateListOf` 与 `activeEffect`。
 * 一次特效播完后 `activeEffect = null`、紧接着下一枚同档位礼物又赋成同一个枚举值，
 * Compose 把这两次写入合并成"值没变"，`remember(gift)` / `LaunchedEffect(gift)` 的 key
 * 没变，动画不重启，`Animatable` 停在上一轮的 1f —— 第二次同档位礼物空屏。
 * 修复后每次出队都带自己的事件身份（key），下面这些用例把身份唯一性钉住。
 */
class VideoGiftEffectQueueTest {
    @Test
    fun `consecutive gifts of the same tier dequeue as distinct identities`() {
        val queue = VideoGiftEffectQueue()
        val first = queue.offer("msg-1:fireworks", VideoGift.FIREWORKS)
        val second = queue.offer("msg-2:fireworks", VideoGift.FIREWORKS)

        val playedFirst = queue.poll()
        val playedSecond = queue.poll()

        assertSame(first, playedFirst)
        assertSame(second, playedSecond)
        // 两枚礼物的档位枚举相同（旧实现就是在这里退化成"状态没变"）。
        assertEquals(VideoGift.FIREWORKS, playedFirst?.gift)
        assertEquals(VideoGift.FIREWORKS, playedSecond?.gift)
        // 但每次播放的事件身份不同，overlay 的 key 一定会换。
        assertNotEquals(playedFirst?.key, playedSecond?.key)
        assertTrue(playedFirst?.key?.startsWith("msg-1:fireworks") == true)
        assertTrue(playedSecond?.key?.startsWith("msg-2:fireworks") == true)
        assertNull(queue.poll())
    }

    @Test
    fun `replayed event id still gets a fresh identity`() {
        // 重放/重入兜底：就算同一个事件 id 被投递两次，也不能复用上一次的 key。
        val queue = VideoGiftEffectQueue()
        queue.offer("msg-1:meteor", VideoGift.METEOR_SHOWER)
        queue.offer("msg-1:meteor", VideoGift.METEOR_SHOWER)

        val first = queue.poll()
        val second = queue.poll()

        assertNotEquals(first?.key, second?.key)
        assertNotSame(first, second)
    }

    @Test
    fun `identity key prefers the gift event id and falls back to the gift id`() {
        assertEquals(
            "msg-9:galaxy_train#1",
            VideoGiftEffectQueue.identityKey("msg-9:galaxy_train", VideoGift.GALAXY_TRAIN, 1L),
        )
        // 空白 id 只可能来自异常投递：退回礼物 id，序号仍然保证唯一。
        assertEquals("fireworks#3", VideoGiftEffectQueue.identityKey("   ", VideoGift.FIREWORKS, 3L))
        assertEquals("meteor#0", VideoGiftEffectQueue.identityKey("", VideoGift.METEOR_SHOWER, -1L))
    }

    @Test
    fun `bubble tier gifts never enter the effect queue`() {
        val queue = VideoGiftEffectQueue()
        assertNull(queue.offer("msg-1:heart", VideoGift.HEART))
        assertNull(queue.offer("msg-2:bouquet", VideoGift.BOUQUET))
        assertTrue(queue.isEmpty)
        assertEquals(0, queue.size)
    }

    @Test
    fun `queue keeps only the newest three effects`() {
        val queue = VideoGiftEffectQueue()
        listOf(VideoGift.FIREWORKS, VideoGift.METEOR_SHOWER, VideoGift.GALAXY_TRAIN, VideoGift.FIREWORKS)
            .forEachIndexed { index, gift -> queue.offer("msg-$index:${gift.id}", gift) }

        assertEquals(VIDEO_GIFT_EFFECT_QUEUE_LIMIT, queue.size)
        val playedOrder = List(queue.size) { queue.poll()?.eventId }
        assertEquals(
            listOf("msg-1:meteor", "msg-2:galaxy_train", "msg-3:fireworks"),
            playedOrder,
        )
        assertTrue(queue.isEmpty)
        assertNull(queue.poll())
    }

    @Test
    fun `clear drops pending effects and the queue can be reused`() {
        // 退后台/挂断/换会话都走 clear()：排队的特效不能留到下一通电话里补播。
        val queue = VideoGiftEffectQueue()
        queue.offer("msg-1:fireworks", VideoGift.FIREWORKS)
        queue.clear()

        assertTrue(queue.isEmpty)
        assertNull(queue.poll())

        val afterClear = queue.offer("msg-2:fireworks", VideoGift.FIREWORKS)
        assertEquals("msg-2:fireworks", afterClear?.eventId)
        assertEquals("msg-2:fireworks", queue.poll()?.eventId)
    }

    @Test
    fun `skip dismisses only current request and preserves pending FIFO including same tier`() {
        val queue = VideoGiftEffectQueue()
        queue.offer("first", VideoGift.METEOR_SHOWER)
        val current = queue.poll()
        val second = queue.offer("second", VideoGift.METEOR_SHOWER)
        val third = queue.offer("third", VideoGift.GALAXY_TRAIN)

        assertNull(current.skipIfMatching(requireNotNull(current).key))
        assertEquals(2, queue.size)
        assertSame(second, queue.poll())
        assertSame(third, queue.poll())
        assertTrue(queue.isEmpty)
    }

    @Test
    fun `stale or repeated skip cannot dismiss the successor`() {
        val queue = VideoGiftEffectQueue()
        val first = requireNotNull(queue.offer("first", VideoGift.FIREWORKS))
        val current = queue.poll()
        assertNull(current.skipIfMatching(first.key))
        queue.offer("next", VideoGift.FIREWORKS)
        val next = queue.poll()
        assertSame(next, next.skipIfMatching(first.key))
        assertSame(next, next.skipIfMatching("unknown"))
        val empty: VideoGiftEffectRequest? = null
        assertNull(empty.skipIfMatching(first.key))
    }

    @Test
    fun `skip last effect does not prevent future gifts`() {
        val queue = VideoGiftEffectQueue()
        queue.offer("last", VideoGift.GALAXY_TRAIN)
        val current = requireNotNull(queue.poll())
        assertNull(current.skipIfMatching(current.key))
        assertNull(queue.poll())
        val future = queue.offer("future", VideoGift.METEOR_SHOWER)
        assertSame(future, queue.poll())
    }

    @Test
    fun `meteor budget spreads more stars without unbounded concurrent drawing`() {
        assertEquals(96, VIDEO_GIFT_METEOR_COUNT)
        assertEquals(24, VIDEO_GIFT_GALAXY_METEOR_COUNT)
        assertEquals(1f, VIDEO_GIFT_METEOR_START_SPAN + VIDEO_GIFT_METEOR_LIFETIME, 0.0001f)
        // Starts are stratified in index order with jitter < one slot.
        val maximumAlive = kotlin.math.ceil(
            VIDEO_GIFT_METEOR_COUNT * VIDEO_GIFT_METEOR_LIFETIME / VIDEO_GIFT_METEOR_START_SPAN,
        ).toInt() + 1
        assertTrue(maximumAlive <= 29)
    }

    @Test
    fun `effect duration and queue limit stay at the documented values`() {
        assertEquals(8_200L, VIDEO_GIFT_EFFECT_DURATION_MS)
        assertEquals(3, VIDEO_GIFT_EFFECT_QUEUE_LIMIT)
    }
}
