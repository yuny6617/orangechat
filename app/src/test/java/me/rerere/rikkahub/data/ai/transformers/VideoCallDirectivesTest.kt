/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.rikkahub.data.video.VideoCallRuntime
import me.rerere.rikkahub.data.video.VideoGift
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频指令在**真实管线**下的行为。
 *
 * 真实顺序是：
 * `onUpdateMessages → transforms(...)`（Streaming：只记录，正文原样返回）
 * `→ visualTransforms(...)`（Display：剥离后给界面）
 * `→ 生成结束后 onGenerationFinish(...)`（Finish：只消费已经冻结的解析结果）。
 *
 * 这里驱动的是**真实包装**（[VideoCallOutputTransformer.runStage]，三个 override 的共用执行体），
 * 而不是另写一份 helper 逻辑；流式测试按真实形状（每次给完整累积原文）喂数据，
 * 保证"半截标签跨分片到达"这种真实情况不会漏掉礼物。
 */
class VideoCallDirectivesTest {
    private val conversationId = "conversation-1"
    private val requestToken = "vc-token-1"

    @After
    fun tearDown() {
        VideoCallRuntime.reset()
    }

    private fun giftsOf(message: UIMessage) =
        message.annotations.filterIsInstance<UIMessageAnnotation.VideoCallGift>()

    /** 一轮真实管线：使用与生产完全相同的 Transformer 包装与请求快照。 */
    private class Turn(
        private val conversationId: String,
        private val requestToken: String,
    ) {
        /** 请求作用域的指令状态（测试里要拿同一份去验证"没有 token / 令牌已关"的情况）。 */
        val session = VideoCallDirectiveSession()

        private fun request(giftsEnabled: Boolean) = VideoDirectiveRequest(
            giftsEnabled = giftsEnabled,
            conversationId = conversationId,
            requestToken = requestToken,
            session = session,
        )

        /** 模拟 onUpdateMessages：每次拿到模型最新的累积原文。 */
        fun update(messages: List<UIMessage>, giftsEnabled: Boolean = true): List<UIMessage> =
            VideoCallOutputTransformer.runStage(VideoDirectiveStage.Streaming, messages, request(giftsEnabled))

        /** 模拟 generateInternal 返回后、finish 之前的 visualTransforms。 */
        fun display(messages: List<UIMessage>, giftsEnabled: Boolean = true): List<UIMessage> =
            VideoCallOutputTransformer.runStage(VideoDirectiveStage.Display, messages, request(giftsEnabled))

        /** 模拟 onGenerationFinish。 */
        fun finish(messages: List<UIMessage>, giftsEnabled: Boolean = true): List<UIMessage> =
            VideoCallOutputTransformer.runStage(VideoDirectiveStage.Finish, messages, request(giftsEnabled))

        fun close() = session.clear()
    }

    /**
     * 构造 [用户消息, 助手消息] 的对话，并把这轮登记进"本通话以这个 token 发起"。
     *
     * 注意：登记靠的是**请求 token**，不是用户消息 ID —— 这正是 REVIEW_R2 §3 要求的改变。
     */
    private fun conversation(raw: String): Pair<List<UIMessage>, Turn> {
        VideoCallRuntime.enterVideoCall(conversationId)
        VideoCallRuntime.beginTurnRequest(conversationId, requestToken)
        return listOf(UIMessage.user("在吗"), UIMessage.assistant(raw)) to Turn(conversationId, requestToken)
    }

    // ---------------------------------------------------------------------
    // P0：Streaming 绝不改正文；显示层剥离之后礼物/留影仍然必须真的产生
    // ---------------------------------------------------------------------

    @Test
    fun `streaming keeps the raw body and display strips it`() {
        val (messages, turn) = conversation("送你一朵花 <ws_gift id=\"bouquet\"/> 收下吧")
        val afterTransform = turn.update(messages)
        // Streaming 只记录：正文必须**原样**返回。管线把它当成后续分片合并的基准，
        // 这里一旦删掉半截标签，下一次分片就会被拼进已经删过的正文里。
        assertEquals(messages, afterTransform)
        assertTrue(afterTransform.last().toText().contains("<ws_gift"))
        assertTrue(giftsOf(afterTransform.last()).isEmpty())

        // 显示层才剥离，界面/TTS 永远看不到协议。
        val afterDisplay = turn.display(afterTransform)
        assertEquals("送你一朵花 收下吧", afterDisplay.last().toText())
        assertTrue(giftsOf(afterDisplay.last()).isEmpty())

        // 结束阶段：只消费 Streaming 阶段冻结的解析结果，正文已经被剥离也照样发放。
        val finished = turn.finish(afterDisplay)
        assertEquals("送你一朵花 收下吧", finished.last().toText())
        val gifts = giftsOf(finished.last())
        assertEquals(1, gifts.size)
        assertEquals("bouquet", gifts.single().giftId)
        assertEquals(listOf(VideoGift.BOUQUET), VideoCallRuntime.grantedGifts())
        turn.close()
    }

    @Test
    fun `gift survives shards that are split across deltas`() {
        // 真实调用链：每一片 delta 都通过 `handleMessageChunk` 合并进累积消息
        // （`generateModelTurn` 的 `accept()` 就是这么做的），再把**完整累积原文**交给
        // Streaming。所以半截标签不会让整枚礼物丢掉，也不会在任何阶段漏到显示正文里。
        VideoCallRuntime.enterVideoCall(conversationId)
        VideoCallRuntime.beginTurnRequest(conversationId, requestToken)
        val turn = Turn(conversationId, requestToken)
        val user = UIMessage.user("来一段")
        val shards = listOf(
            "好呀 ",
            "<ws",
            "_gift id=\"hea",
            "rt\"/>",
            " 拿着",
        )
        var accumulated = ""
        var current: List<UIMessage> = listOf(user, UIMessage.assistant(""))
        shards.forEachIndexed { index, shard ->
            accumulated += shard
            val chunk = MessageChunk(
                id = "chunk-$index",
                model = "test-model",
                choices = listOf(
                    UIMessageChoice(
                        index = 0,
                        delta = UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(UIMessagePart.Text(shard)),
                        ),
                        message = null,
                        finishReason = null,
                    ),
                ),
            )
            current = current.handleMessageChunk(chunk)
            assertEquals("逐 delta 合并出的就是完整累积原文", accumulated, current.last().toText())
            val streamed = turn.update(current)
            // Streaming 不改正文：累积原文逐字保留（半截标签因此能被下一次分片补全）。
            assertEquals(accumulated, streamed.last().toText())
            // 流式阶段绝不提前发放
            assertTrue(VideoCallRuntime.grantedGifts().isEmpty())
            // 半截标签永远不会出现在显示正文里
            val display = turn.display(streamed)
            assertFalse("shard=$shard 显示了协议", display.last().toText().contains("<ws"))
        }
        val displayed = turn.display(current)
        assertEquals("好呀 拿着", displayed.last().toText())
        val finished = turn.finish(displayed)
        assertEquals(1, giftsOf(finished.last()).size)
        assertEquals(1, VideoCallRuntime.grantedGifts().size)
        // 重复 finish（重放/重入）不重复发放
        val replay = turn.finish(finished)
        assertEquals(1, giftsOf(replay.last()).size)
        assertEquals(1, VideoCallRuntime.grantedGifts().size)
        turn.close()
    }

    @Test
    fun `snapshot request is recorded from the streaming stage`() {
        val (messages, turn) = conversation("看你一眼<ws_vsnap/>")
        val afterTransform = turn.update(messages)
        assertEquals("看你一眼<ws_vsnap/>", afterTransform.last().toText())
        val afterDisplay = turn.display(afterTransform)
        assertEquals("看你一眼", afterDisplay.last().toText())
        val finished = turn.finish(afterDisplay)
        assertFalse(finished.last().toText().contains("ws_vsnap"))
        // 同一消息只登记一次留影请求（重复 finish 不会拍第二次）。
        assertNull(
            VideoCallRuntime.requestSnapshot(conversationId, requestToken, finished.last().id.toString(), 2_000L),
        )
        turn.close()
    }

    @Test
    fun `plain html in the body never executes as a protocol`() {
        // Display 阶段正文里含 '<' 的普通 HTML 不能覆盖/伪造解析结果。
        val (messages, turn) = conversation("看这个 <div class=\"x\">内容</div>")
        val afterTransform = turn.update(messages)
        val finished = turn.finish(turn.display(afterTransform))
        assertTrue(giftsOf(finished.last()).isEmpty())
        assertTrue(VideoCallRuntime.grantedGifts().isEmpty())
        turn.close()
    }

    @Test
    fun `finish without a recorded intent never invents a gift`() {
        val (messages, turn) = conversation("今天聊点别的")
        val afterTransform = turn.update(messages)
        val finished = turn.finish(turn.display(afterTransform))
        assertTrue(giftsOf(finished.last()).isEmpty())
        assertTrue(VideoCallRuntime.grantedGifts().isEmpty())
        turn.close()
    }

    @Test
    fun `session scope is cleared after the request`() {
        val (messages, turn) = conversation("喏 <ws_gift id=\"heart\"/>")
        turn.update(messages)
        turn.close()
        val finished = turn.finish(listOf(messages[0], UIMessage.assistant("哦")))
        assertTrue(giftsOf(finished.last()).isEmpty())
        assertTrue(VideoCallRuntime.grantedGifts().isEmpty())
    }

    // ---------------------------------------------------------------------
    // 授权：不能再靠"最后一条用户消息 ID"这种可复用的伪身份
    // ---------------------------------------------------------------------

    @Test
    fun `gift needs a live request token`() {
        val (messages, turn) = conversation("<ws_gift id=\"heart\"/>")
        val transformed = turn.update(messages)
        // 同一份已冻结的解析结果，只换 token：没有 token 就绝不放行。
        listOf<String?>(null, "", "unknown-token").forEach { token ->
            val anonymous = VideoCallOutputTransformer.runStage(
                VideoDirectiveStage.Finish,
                transformed,
                VideoDirectiveRequest(
                    giftsEnabled = true,
                    conversationId = conversationId,
                    requestToken = token,
                    session = turn.session,
                ),
            )
            assertTrue("token=$token 不该有礼物", giftsOf(anonymous.last()).isEmpty())
        }
        assertNull(VideoCallRuntime.grantGift(null, null, "m1", VideoGift.HEART, 1_000L))
        assertNull(VideoCallRuntime.grantGift(conversationId, null, "m1", VideoGift.HEART, 1_000L))
        assertNull(VideoCallRuntime.grantGift(conversationId, requestToken, "", VideoGift.HEART, 1_000L))
        assertTrue(VideoCallRuntime.grantedGifts().isEmpty())
        assertFalse(VideoCallRuntime.isActive(null))
        turn.close()
    }

    @Test
    fun `a non call request reusing the same conversation and user id still fails`() {
        // 视频通话正在进行，但这一轮的生成不是通话发起的
        // （聊天页手打 / 后台主动消息 / 工具重放）：拿不到 token，就绝不能送礼。
        VideoCallRuntime.enterVideoCall(conversationId)
        VideoCallRuntime.beginTurnRequest(conversationId, requestToken)
        val user = UIMessage.user("后台生成")
        val session = VideoCallDirectiveSession()
        val messages = listOf(user, UIMessage.assistant("<ws_gift id=\"heart\"/>"))
        // 用同一个 conversationId + 同一个用户消息 ID，只是没有 token。
        val transformed = VideoCallOutputTransformer.runStage(
            VideoDirectiveStage.Streaming,
            messages,
            VideoDirectiveRequest(true, conversationId, null, session),
        )
        val finished = VideoCallOutputTransformer.runStage(
            VideoDirectiveStage.Finish,
            VideoCallOutputTransformer.runStage(
                VideoDirectiveStage.Display, transformed, VideoDirectiveRequest(true, conversationId, null, session),
            ),
            VideoDirectiveRequest(true, conversationId, null, session),
        )
        assertTrue(giftsOf(finished.last()).isEmpty())
        assertTrue(VideoCallRuntime.grantedGifts().isEmpty())
        // 换成一个根本没登记过的 token 同样失败
        assertFalse(VideoCallRuntime.isTurnGranted(conversationId, "forged-token"))
        // 只要这一轮真的用通话的 token 发起，就能生效（证明失败关闭的原因是没有授权）。
        assertTrue(VideoCallRuntime.isTurnGranted(conversationId, requestToken))
    }

    @Test
    fun `another conversation cannot receive the gift even with the same token string`() {
        val (messages, turn) = conversation("<ws_gift id=\"heart\"/>")
        val transformed = turn.update(messages)
        val other = VideoCallOutputTransformer.runStage(
            VideoDirectiveStage.Finish,
            transformed,
            VideoDirectiveRequest(true, "conversation-2", requestToken, turn.session),
        )
        assertTrue(giftsOf(other.last()).isEmpty())
        assertTrue(VideoCallRuntime.grantedGifts().isEmpty())
        turn.close()
    }

    @Test
    fun `closing the token stops late callbacks from granting`() {
        val (messages, turn) = conversation("<ws_gift id=\"heart\"/>")
        val transformed = turn.update(messages)
        // 这一轮结束（完成或取消）：token 先关掉
        VideoCallRuntime.completeTurnRequest(requestToken)
        val finished = turn.finish(transformed)
        assertTrue(giftsOf(finished.last()).isEmpty())
        assertTrue(VideoCallRuntime.grantedGifts().isEmpty())
        turn.close()
    }

    @Test
    fun `one gift per call turn even if two replies appear`() {
        val (messages, turn) = conversation("<ws_gift id=\"heart\"/>")
        val first = turn.finish(turn.display(turn.update(messages)))
        assertEquals(1, giftsOf(first.last()).size)
        val secondMessages = listOf(messages[0], UIMessage.assistant("<ws_gift id=\"fireworks\"/>"))
        val second = turn.finish(turn.display(turn.update(secondMessages)))
        assertTrue(giftsOf(second.last()).isEmpty())
        assertEquals(1, VideoCallRuntime.grantedGifts().size)
        turn.close()
    }

    @Test
    fun `gifts disabled or no video call strips the tag without granting`() {
        val (messages, turn) = conversation("<ws_gift id=\"heart\"/>")
        val off = turn.update(messages, giftsEnabled = false)
        val finished = turn.finish(off, giftsEnabled = false)
        assertEquals("", finished.last().toText())
        assertTrue(giftsOf(finished.last()).isEmpty())
        assertTrue(VideoCallRuntime.grantedGifts().isEmpty())
        turn.close()

        // 没有进行中的视频通话：即使设置开着、token 也对，也不发
        VideoCallRuntime.exitVideoCall()
        val result = VideoCallOutputTransformer.runStage(
            VideoDirectiveStage.Finish,
            listOf(UIMessage.user("x"), UIMessage.assistant("<ws_gift id=\"heart\"/>")),
            VideoDirectiveRequest(true, conversationId, requestToken, turn.session),
        )
        assertEquals("", result.last().toText())
        assertTrue(giftsOf(result.last()).isEmpty())
    }

    @Test
    fun `illegal gift id is stripped and never granted`() {
        val (messages, turn) = conversation("嘿 <ws_gift id=\"cash\"/>")
        val transformed = turn.update(messages)
        // 非法 id：显示层只剥离不执行
        val displayed = turn.display(transformed)
        assertEquals("嘿", displayed.last().toText())
        val finished = turn.finish(displayed)
        assertTrue(giftsOf(finished.last()).isEmpty())
        assertTrue(VideoCallRuntime.grantedGifts().isEmpty())
        turn.close()
    }

    @Test
    fun `finished history messages are never rewritten`() {
        val (_, turn) = conversation("x")
        val old = UIMessage.assistant("旧回复 <ws_gift id=\"heart\"/>")
            .copy(finishedAt = LocalDateTime(2024, 1, 1, 0, 0))
        val result = VideoCallOutputTransformer.runStage(
            VideoDirectiveStage.Finish,
            listOf(UIMessage.user("旧"), old),
            VideoDirectiveRequest(true, conversationId, requestToken, VideoCallDirectiveSession()),
        )
        assertEquals(old, result.last())
        assertTrue(VideoCallRuntime.grantedGifts().isEmpty())
        turn.close()
    }

    // ---------------------------------------------------------------------
    // 留影
    // ---------------------------------------------------------------------

    @Test
    fun `snapshot request is registered once per message and carries a permit`() {
        VideoCallRuntime.enterVideoCall(conversationId)
        VideoCallRuntime.beginTurnRequest(conversationId, requestToken)
        val first = VideoCallRuntime.requestSnapshot(conversationId, requestToken, "m1", 1_000L)
        assertNotNull(first)
        assertNotNull(first?.permit)
        assertNull(VideoCallRuntime.requestSnapshot(conversationId, requestToken, "m1", 2_000L))
        assertNotNull(VideoCallRuntime.requestSnapshot(conversationId, requestToken, "m2", 3_000L))
        assertNull(VideoCallRuntime.requestSnapshot("other", requestToken, "m3", 4_000L))
        assertNull(VideoCallRuntime.requestSnapshot(conversationId, "forged", "m4", 4_000L))
        VideoCallRuntime.exitVideoCall()
        assertNull(VideoCallRuntime.requestSnapshot(conversationId, requestToken, "m5", 5_000L))
    }

    @Test
    fun `snapshot permit stays valid after the turn completes but not after hang up`() {
        VideoCallRuntime.enterVideoCall(conversationId)
        VideoCallRuntime.beginTurnRequest(conversationId, requestToken)
        val request = VideoCallRuntime.requestSnapshot(conversationId, requestToken, "m1", 1_000L)
        val permit = request?.permit
        assertNotNull(permit)
        // 请求正常结束：合法留影仍可写
        VideoCallRuntime.completeTurnRequest(requestToken)
        assertTrue(VideoCallRuntime.isSnapshotPermitUsable(permit))
        assertTrue(VideoCallRuntime.consumeSnapshotPermit(permit))
        assertFalse(VideoCallRuntime.isSnapshotPermitUsable(permit))
        // 挂断：一切许可失效
        val second = run {
            VideoCallRuntime.beginTurnRequest(conversationId, "vc-token-2")
            VideoCallRuntime.requestSnapshot(conversationId, "vc-token-2", "m2", 2_000L)?.permit
        }
        VideoCallRuntime.exitVideoCall()
        assertFalse(VideoCallRuntime.isSnapshotPermitUsable(second))
    }

    @Test
    fun `cancelling a turn revokes its snapshot permits but keeps other turns`() {
        VideoCallRuntime.enterVideoCall(conversationId)
        VideoCallRuntime.beginTurnRequest(conversationId, requestToken)
        VideoCallRuntime.beginTurnRequest(conversationId, "vc-token-2")
        val mine = VideoCallRuntime.requestSnapshot(conversationId, requestToken, "m1", 1_000L)?.permit
        val other = VideoCallRuntime.requestSnapshot(conversationId, "vc-token-2", "m2", 2_000L)?.permit
        assertNotNull(mine)
        assertNotNull(other)
        // 打断/取消这一轮：它的留影许可立刻作废，别的请求不受影响。
        VideoCallRuntime.cancelTurnRequest(requestToken)
        assertFalse(VideoCallRuntime.isSnapshotPermitUsable(mine))
        assertTrue(VideoCallRuntime.isSnapshotPermitUsable(other))
    }

    // ---------------------------------------------------------------------
    // 相机作用域与统计
    // ---------------------------------------------------------------------

    @Test
    fun `camera gateway can only be registered by the active call`() {
        VideoCallRuntime.enterVideoCall(conversationId)
        val gateway = object : me.rerere.rikkahub.data.video.VideoCallCameraGateway {
            override val available: Boolean = true
            override val unavailableReason: String = ""
            override suspend fun capture(): me.rerere.rikkahub.data.video.VideoFrame? = null
        }
        // 另一个会话的视频页：不许替换本通话的相机
        assertFalse(VideoCallRuntime.registerCamera("conversation-2", gateway))
        assertFalse(VideoCallRuntime.cameraAvailable())
        assertTrue(VideoCallRuntime.registerCamera(conversationId, gateway))
        assertTrue(VideoCallRuntime.cameraAvailable())
        // 别的会话来注销：不能把本通话的相机摘掉
        VideoCallRuntime.unregisterCamera("conversation-2", gateway)
        assertTrue(VideoCallRuntime.cameraAvailable())
        VideoCallRuntime.unregisterCamera(conversationId, gateway)
        assertFalse(VideoCallRuntime.cameraAvailable())
    }

    @Test
    fun `a pending frame captured before a camera change is rejected`() {
        VideoCallRuntime.enterVideoCall(conversationId)
        val frame = me.rerere.rikkahub.data.video.VideoFrame(
            bytes = ByteArray(1), width = 1, height = 1, capturedAtMillis = 1_000L,
        )
        val epoch = VideoCallRuntime.currentCameraEpoch()
        // 相机换代（面板重建/切镜头/解绑）之后，抓帧之前记下的那一帧不许再塞回待发送队列。
        VideoCallRuntime.unregisterCamera(conversationId, null)
        assertFalse(VideoCallRuntime.holdPendingFrame(frame, conversationId, epoch))
        assertNull(VideoCallRuntime.pendingFrame())
        // 同一代相机 + 同一通电话：接受。
        val currentEpoch = VideoCallRuntime.currentCameraEpoch()
        assertTrue(VideoCallRuntime.holdPendingFrame(frame, conversationId, currentEpoch))
        assertNotNull(VideoCallRuntime.pendingFrame())
        // 别的会话：不接受。
        assertFalse(VideoCallRuntime.holdPendingFrame(frame, "conversation-2", currentEpoch))
    }

    @Test
    fun `entering another call never clears the live one`() {
        VideoCallRuntime.enterVideoCall(conversationId)
        val frame = me.rerere.rikkahub.data.video.VideoFrame(
            bytes = ByteArray(1), width = 1, height = 1, capturedAtMillis = 1_000L,
        )
        assertTrue(VideoCallRuntime.holdPendingFrame(frame, conversationId))
        // 另一个会话试图"进入视频通话"：必须被拒绝，且不能清掉当前通话的待发送帧。
        assertFalse(VideoCallRuntime.enterVideoCall("conversation-2"))
        assertTrue(VideoCallRuntime.isActive(conversationId))
        assertNotNull(VideoCallRuntime.pendingFrame())
        // 别的会话也不能把它挂断。
        assertFalse(VideoCallRuntime.exitVideoCall("conversation-2"))
        assertTrue(VideoCallRuntime.isActive(conversationId))
    }

    @Test
    fun `gift tally resets when the call ends`() {
        val (messages, turn) = conversation("<ws_gift id=\"fireworks\"/>")
        val finished = turn.finish(turn.display(turn.update(messages)))
        assertEquals(1, VideoCallRuntime.tally().total)
        assertFalse(giftsOf(finished.last()).isEmpty())
        VideoCallRuntime.exitVideoCall()
        assertEquals(0, VideoCallRuntime.tally().total)
        assertTrue(VideoCallRuntime.grantedGifts().isEmpty())
        turn.close()
    }

    @Test
    fun `tally keeps counting past the ui list cap`() {
        VideoCallRuntime.enterVideoCall(conversationId)
        repeat(260) { index ->
            val token = "token-$index"
            VideoCallRuntime.beginTurnRequest(conversationId, token)
            assertNotNull(
                VideoCallRuntime.grantGift(
                    conversationId, token, "msg-$index", VideoGift.HEART, index.toLong(),
                ),
            )
        }
        assertEquals(260, VideoCallRuntime.tally().total)
        assertEquals(260, VideoCallRuntime.tally().countOf(VideoGift.HEART))
        assertTrue(VideoCallRuntime.giftCountForUi() <= 100)
    }

    @Test
    fun `assistant messages still carry the persisted gift annotation`() {
        val (messages, turn) = conversation("<ws_gift id=\"meteor\"/>")
        val finished = turn.finish(turn.display(turn.update(messages)))
        val annotation = finished.last().annotations.filterIsInstance<UIMessageAnnotation.VideoCallGift>().single()
        assertEquals("meteor", annotation.giftId)
        assertEquals(conversationId, annotation.conversationId)
        // 正文里不再有任何协议残留
        assertFalse(finished.last().parts.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("ws_") })
        turn.close()
    }
}
