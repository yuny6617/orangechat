/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轮次授权台账：**唯一**的"这通通话允许送礼/留影"的信任来源。
 *
 * 上一轮的问题正是"用最后一条用户消息 ID 当身份"：同一个会话里别的生成、
 * 甚至另一个会话里恰好同名的用户消息，都能沿用那个 ID 冒用通话状态。
 * 这里把它换成显式请求 token + 通话代次，并逐条钉住：
 * - 非通话请求拿同样的 conversation + user ID 依然失败；
 * - 另一个会话拿同样的 token 依然失败；
 * - 取消/结束/换通话后 token 立刻失效；
 * - 留影许可跨"请求结束"仍然有效，但只随通话/相机代次失效，且只能消费一次。
 */
class VideoCallTurnLedgerTest {
    private val conversationId = "conversation-1"

    @Test
    fun `a request token is the only thing that grants a turn`() {
        val ledger = VideoCallTurnLedger()
        ledger.beginCall(conversationId)

        // 没有 token：无论会话是否匹配都不给授权（旧实现用用户消息 ID 冒充）。
        assertFalse(ledger.isGranted(conversationId, null))
        assertFalse(ledger.isGranted(conversationId, " "))
        assertFalse(ledger.isGranted("conversation-2", "token-1"))

        val grant = ledger.beginTurn(conversationId, "token-1")
        assertNotNull(grant)
        assertTrue(ledger.isGranted(conversationId, "token-1"))
        // 同一个 token 换到别的会话 / 别的通话：拒绝
        assertFalse(ledger.isGranted("conversation-2", "token-1"))
        assertFalse(ledger.isGranted(null, "token-1"))
    }

    @Test
    fun `no call means no grant even with a matching conversation`() {
        val ledger = VideoCallTurnLedger()
        assertNull(ledger.beginTurn(conversationId, "token-1"))
        assertFalse(ledger.isGranted(conversationId, "token-1"))
        assertFalse(ledger.consumeGiftGrant(conversationId, "token-1"))
    }

    @Test
    fun `completing a token fails the late callbacks closed`() {
        val ledger = VideoCallTurnLedger()
        ledger.beginCall(conversationId)
        ledger.beginTurn(conversationId, "token-1")
        ledger.completeTurn("token-1")
        assertFalse(ledger.isGranted(conversationId, "token-1"))
        // 关掉之后再登记新的一轮也重新开始（每轮一个新 token）
        ledger.beginTurn(conversationId, "token-2")
        assertTrue(ledger.isGranted(conversationId, "token-2"))
        assertFalse(ledger.isGranted(conversationId, "token-1"))
    }

    @Test
    fun `re-registering the same token never refreshes the consumed gift slot`() {
        val ledger = VideoCallTurnLedger()
        ledger.beginCall(conversationId)
        assertNotNull(ledger.beginTurn(conversationId, "token-1"))
        assertTrue(ledger.consumeGiftGrant(conversationId, "token-1"))
        // 重复登记（旧实现会新建 Grant，把 giftGranted 重置成 false → 同一条回复再送一枚）
        assertNotNull(ledger.beginTurn(conversationId, "token-1"))
        assertFalse(ledger.consumeGiftGrant(conversationId, "token-1"))
        assertFalse(ledger.isGranted("conversation-2", "token-1"))
    }

    @Test
    fun `ending the call invalidates every token and permit`() {
        val ledger = VideoCallTurnLedger()
        ledger.beginCall(conversationId)
        ledger.beginTurn(conversationId, "token-1")
        val permit = ledger.issueSnapshotPermit(conversationId, "token-1", "message-1")
        assertNotNull(permit)
        ledger.endCall()
        assertFalse(ledger.isGranted(conversationId, "token-1"))
        assertFalse(ledger.isSnapshotPermitUsable(permit))
        assertFalse(ledger.consumeSnapshotPermit(permit))
    }

    @Test
    fun `switching conversation discards the previous call authorizations`() {
        val ledger = VideoCallTurnLedger()
        ledger.beginCall(conversationId)
        ledger.beginTurn(conversationId, "token-1")
        ledger.beginCall("conversation-2")
        assertFalse(ledger.isGranted(conversationId, "token-1"))
        // 即便字符串 token 相同，也不属于新通话
        assertFalse(ledger.isGranted("conversation-2", "token-1"))
        assertTrue(ledger.beginTurn("conversation-2", "token-1") != null)
        assertTrue(ledger.isGranted("conversation-2", "token-1"))
        assertFalse(ledger.isGranted(conversationId, "token-1"))
    }

    @Test
    fun `only one gift per turn and it is decided atomically`() {
        val ledger = VideoCallTurnLedger()
        ledger.beginCall(conversationId)
        ledger.beginTurn(conversationId, "token-1")
        assertTrue(ledger.consumeGiftGrant(conversationId, "token-1"))
        assertFalse(ledger.consumeGiftGrant(conversationId, "token-1"))
        // 另一轮有自己的名额
        ledger.beginTurn(conversationId, "token-2")
        assertTrue(ledger.consumeGiftGrant(conversationId, "token-2"))
    }

    @Test
    fun `snapshot permit outlives the request but only for the same camera generation`() {
        val ledger = VideoCallTurnLedger()
        ledger.beginCall(conversationId)
        ledger.beginTurn(conversationId, "token-1")
        val permit = ledger.issueSnapshotPermit(conversationId, "token-1", "message-1")
        assertNotNull(permit)

        // 请求正常结束（关掉 token）：合法的异步留影不能因此失效。
        ledger.completeTurn("token-1")
        assertTrue(ledger.isSnapshotPermitUsable(permit))
        assertTrue(ledger.consumeSnapshotPermit(permit))
        // 一次性：消费过就不能再写第二张
        assertFalse(ledger.isSnapshotPermitUsable(permit))
        assertFalse(ledger.consumeSnapshotPermit(permit))

        // 相机换代（面板离开/回来、切镜头）之后，旧许可不再能写旧画面。
        ledger.beginTurn(conversationId, "token-2")
        val stale = ledger.issueSnapshotPermit(conversationId, "token-2", "message-2")
        assertNotNull(stale)
        ledger.rotateCameraEpoch()
        assertFalse(ledger.isSnapshotPermitUsable(stale))
        assertFalse(ledger.consumeSnapshotPermit(stale))
    }

    @Test
    fun `cancelling a turn revokes exactly the permits of that request`() {
        val ledger = VideoCallTurnLedger()
        ledger.beginCall(conversationId)
        ledger.beginTurn(conversationId, "token-1")
        ledger.beginTurn(conversationId, "token-2")
        val first = ledger.issueSnapshotPermit(conversationId, "token-1", "message-1")
        val second = ledger.issueSnapshotPermit(conversationId, "token-2", "message-2")
        assertNotNull(first)
        assertNotNull(second)

        // 打断/取消失败的一轮：它发出的许可全部作废，token 也关掉。
        assertEquals(1, ledger.cancelTurn("token-1"))
        assertFalse(ledger.isGranted(conversationId, "token-1"))
        assertFalse(ledger.isSnapshotPermitUsable(first))
        assertFalse(ledger.consumeSnapshotPermit(first))
        // 别的请求的许可不受影响（打断一轮不能顺手杀掉另一轮的合法留影）。
        assertTrue(ledger.isSnapshotPermitUsable(second))
        assertTrue(ledger.isGranted(conversationId, "token-2"))
        // 重复取消是幂等的
        assertEquals(0, ledger.cancelTurn("token-1"))
    }

    @Test
    fun `snapshot permit needs a live grant at issue time`() {
        val ledger = VideoCallTurnLedger()
        ledger.beginCall(conversationId)
        assertNull(ledger.issueSnapshotPermit(conversationId, "token-1", "message-1"))
        ledger.beginTurn(conversationId, "token-1")
        assertNull(ledger.issueSnapshotPermit("conversation-2", "token-1", "message-1"))
        assertNull(ledger.issueSnapshotPermit(conversationId, "token-1", " "))
        assertNotNull(ledger.issueSnapshotPermit(conversationId, "token-1", "message-1"))
    }

    @Test
    fun `grant table stays bounded`() {
        val ledger = VideoCallTurnLedger(capacity = 4)
        ledger.beginCall(conversationId)
        (1..20).forEach { index -> ledger.beginTurn(conversationId, "token-$index") }
        // 早期的 token 被淘汰：不能再送礼（容量只防内存膨胀，正常通话远达不到）
        assertFalse(ledger.isGranted(conversationId, "token-1"))
        assertTrue(ledger.isGranted(conversationId, "token-20"))
    }
}
