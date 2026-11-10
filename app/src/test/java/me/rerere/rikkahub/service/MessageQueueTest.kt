package me.rerere.rikkahub.service

import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class MessageQueueTest {
    @Test fun giftReplyQueuesTheExistingMessageWithoutInventingUserTextOrDuplicatingIt() {
        val queue = MessageQueue()
        try {
            val giftId = kotlin.uuid.Uuid.random()
            assertNull(queue.enqueue(emptyList()))
            val reply = queue.enqueue(emptyList(), existingMessageId = giftId)!!
            assertTrue(reply.parts.isEmpty())
            assertEquals(giftId, reply.existingMessageId)
            assertNull(queue.enqueue(emptyList(), existingMessageId = giftId))
            assertNull(queue.beginEdit(reply.id))
            assertSame(reply, queue.takeNext())
            assertNull(queue.enqueue(emptyList(), existingMessageId = giftId))
            queue.complete(reply.id, "收到了")
            assertFalse(queue.hasPendingOrInFlight)
        } finally { queue.clear() }
    }
    private fun text(value: String) = listOf(UIMessagePart.Text(value))

    @Test fun fifoPreservesAllLocalRequestExtras() {
        val queue = MessageQueue()
        try {
            val interaction = UIMessageAnnotation.AvatarInteraction("user", "assistant")
            val first = queue.enqueue(text("first"), answer = false, interaction = interaction,
                transientSystemPrompt = "hidden context", enableTools = false)!!
            val second = queue.enqueue(text("second"))!!
            assertSame(first, queue.takeNext())
            assertFalse(first.answer)
            assertFalse(first.enableTools)
            assertEquals("hidden context", first.transientSystemPrompt)
            assertEquals(interaction, first.interaction)
            assertSame(second, queue.takeNext())
            assertNull(queue.takeNext())
            assertEquals(listOf(first, second), queue.inFlightMessages)
        } finally { queue.clear() }
    }

    @Test fun snapshotsCallerOwnedParts() {
        val queue = MessageQueue()
        try {
            val parts = mutableListOf<UIMessagePart>(UIMessagePart.Text("original"))
            queue.enqueue(parts)
            parts.clear()
            assertEquals(text("original"), queue.takeNext()!!.parts)
        } finally { queue.clear() }
    }

    @Test fun editingHeadBlocksFifoAndPreservesObservers() {
        val queue = MessageQueue()
        try {
            val first = queue.enqueue(text("first"))!!
            queue.enqueue(text("second"))
            assertSame(first, queue.beginEdit(first.id))
            assertNull(queue.takeNext())
            queue.finishEdit(first.id, text("edited"))
            val edited = queue.takeNext()!!
            assertEquals(first.id, edited.id)
            assertEquals(text("edited"), edited.parts)
            assertSame(first.reply, edited.reply)
            assertSame(first.completion, edited.completion)
            assertEquals(text("second"), queue.takeNext()!!.parts)
        } finally { queue.clear() }
    }

    @Test fun editingLaterDoesNotBlockEarlierAndCancelRestoresOriginal() {
        val queue = MessageQueue()
        try {
            queue.enqueue(text("first"))
            val second = queue.enqueue(text("second"))!!
            queue.beginEdit(second.id)
            assertEquals(text("first"), queue.takeNext()!!.parts)
            assertNull(queue.takeNext())
            assertNull(queue.finishEdit(second.id))
            assertEquals(text("second"), queue.takeNext()!!.parts)
        } finally { queue.clear() }
    }

    @Test fun invalidEditStaysLockedUntilCorrectedOrCancelled() {
        val queue = MessageQueue()
        try {
            val first = queue.enqueue(text("first"))!!
            queue.beginEdit(first.id)
            assertNull(queue.finishEdit(first.id, text(" \n")))
            assertTrue(queue.state.value.messages.single().isEditing)
            assertNull(queue.takeNext())
            queue.finishEdit(first.id)
            assertEquals(first.id, queue.takeNext()!!.id)
        } finally { queue.clear() }
    }

    @Test fun withdrawResolvesOnlyItsOwnObservers() = runBlocking {
        val queue = MessageQueue()
        try {
            val first = queue.enqueue(text("first"))!!
            val second = queue.enqueue(text("second"))!!
            assertSame(first, queue.remove(first.id))
            assertNull(first.reply.await())
            assertTrue(first.completion.isCancelled)
            assertFalse(second.reply.isCompleted)
            assertFalse(second.completion.isCompleted)
            assertNull(queue.remove(first.id))
            assertEquals(second.id, queue.takeNext()!!.id)
        } finally { queue.clear() }
    }

    @Test fun pauseFailsReplyButRetainsContentUntilExplicitResume() = runBlocking {
        val queue = MessageQueue()
        try {
            val first = queue.enqueue(text("first"))!!
            queue.pause()
            assertTrue(runCatching { first.reply.await() }.exceptionOrNull() is MessageQueuePausedException)
            assertFalse(first.completion.isCompleted)
            queue.enqueue(text("second"))
            queue.beginEdit(first.id)
            queue.finishEdit(first.id, text("edited"))
            assertTrue(queue.state.value.paused)
            assertNull(queue.takeNext())
            queue.resume()
            assertEquals(text("edited"), queue.takeNext()!!.parts)
            assertEquals(text("second"), queue.takeNext()!!.parts)
        } finally { queue.clear() }
    }

    @Test fun completionIsPerTurnAndReleasesInFlightOwnership() = runBlocking {
        val queue = MessageQueue()
        try {
            val first = queue.enqueue(text("first"))!!
            val second = queue.enqueue(text("second"))!!
            queue.takeNext()
            queue.complete(first.id, "first reply")
            assertEquals("first reply", first.reply.await())
            first.completion.await()
            assertTrue(queue.inFlightMessages.isEmpty())
            assertFalse(second.reply.isCompleted)
            assertTrue(queue.hasPendingOrInFlight)
            queue.takeNext()
            queue.complete(second.id, "second reply")
            assertFalse(queue.hasPendingOrInFlight)
            queue.complete(first.id, "late replacement")
            assertEquals("first reply", first.reply.await())
        } finally { queue.clear() }
    }

    @Test fun submittingAfterPauseFailsObserverWithoutDroppingItsManualResumeContent() = runBlocking {
        val queue = MessageQueue()
        try {
            val first = queue.enqueue(text("backlog"))!!
            queue.pause()
            val late = queue.enqueue(text("new voice or bot input"), answer = false,
                transientSystemPrompt = "snapshot", enableTools = false)!!
            assertTrue(runCatching { late.reply.await() }.exceptionOrNull() is MessageQueuePausedException)
            assertFalse(late.completion.isCompleted)
            assertEquals(listOf(first.id, late.id), queue.state.value.messages.map { it.id })
            assertNull(queue.takeNext())
            queue.resume()
            queue.complete(queue.takeNext()!!.id, "first reply")
            val dispatched = queue.takeNext()!!
            assertEquals(late.id, dispatched.id)
            assertEquals(text("new voice or bot input"), dispatched.parts)
            assertEquals("snapshot", dispatched.transientSystemPrompt)
            assertFalse(dispatched.answer)
            assertFalse(dispatched.enableTools)
            queue.complete(dispatched.id, "manual completion")
            late.completion.await()
            assertTrue(runCatching { late.reply.await() }.exceptionOrNull() is MessageQueuePausedException)
        } finally { queue.clear() }
    }

    @Test fun failedGenerationCompletesBothObserversWithTheCause() = runBlocking {
        val queue = MessageQueue()
        val entry = queue.enqueue(text("question"))!!
        queue.takeNext()
        val error = IllegalStateException("provider failed")
        queue.complete(entry.id, error = error)
        val replyError = runCatching { entry.reply.await() }.exceptionOrNull()
        val completionError = runCatching { entry.completion.await() }.exceptionOrNull()
        // Coroutine stack recovery can copy an exception while retaining the original as its cause.
        assertTrue(replyError === error || replyError?.cause === error)
        assertTrue(completionError === error || completionError?.cause === error)
        assertFalse(queue.hasPendingOrInFlight)
    }

    @Test fun approvalFailureDoesNotRemoveOrResumePendingContent() = runBlocking {
        val queue = MessageQueue()
        try {
            val entry = queue.enqueue(text("pending"))!!
            queue.failReplyWaiters("approval required")
            assertEquals("approval required", runCatching { entry.reply.await() }.exceptionOrNull()?.message)
            assertEquals(1, queue.state.value.messages.size)
            assertFalse(entry.completion.isCompleted)
            assertFalse(queue.state.value.paused)
        } finally { queue.clear() }
    }

    @Test fun clearWithdrawsPendingAndLeavesInFlightToTheRequest() = runBlocking {
        val queue = MessageQueue()
        val first = queue.enqueue(text("first"))!!
        val second = queue.enqueue(text("second"))!!
        assertSame(first, queue.takeNext())
        assertEquals(2, queue.clear().size)
        assertTrue(queue.state.value.paused)
        // pending：从未提交到历史，可以立刻终结。
        assertNull(second.reply.await())
        assertTrue(second.completion.isCancelled)
        // in-flight：所有权在请求本身（图片与 deferred 都归它），队列不再替它收尾。
        // REVIEW_R3 §3：旧实现在这里把 in-flight 也 withdraw，会删掉已经进入历史的手动照片。
        assertTrue(queue.hasPendingOrInFlight)
        assertFalse(first.reply.isCompleted)
        queue.complete(first.id, error = kotlinx.coroutines.CancellationException("session closed"))
        assertTrue(first.reply.isCompleted)
        assertFalse(queue.hasPendingOrInFlight)
        // 迟到的编辑/完成不会再复活任何内容。
        queue.complete(first.id, "late")
        queue.finishEdit(second.id, text("late edit"))
        assertFalse(queue.hasPendingOrInFlight)
        assertTrue(queue.clear().isEmpty())
    }

    @Test fun rejectsEmptyInputAndKeepsConversationQueuesIsolated() {
        val first = MessageQueue()
        val second = MessageQueue()
        try {
            assertNull(first.enqueue(emptyList()))
            assertNull(first.enqueue(text(" \n")))
            val image = listOf(UIMessagePart.Image("/tmp/queue-image.png"))
            val imageMessage = first.enqueue(image)!!
            assertEquals(image, first.takeNext()!!.parts)
            first.complete(imageMessage.id)
            first.enqueue(text("first"))
            second.enqueue(text("second"))
            first.pause()
            assertNull(first.takeNext())
            assertEquals(text("second"), second.takeNext()!!.parts)
        } finally { first.clear(); second.clear() }
    }

    // ------------------------------------------------------------------
    // "本轮附图"的所有权：REVIEW_R2 §4 要求撤回/清空/暂停都终结临时图
    // ------------------------------------------------------------------

    @Test fun requestTokenTravelsWithTheQueuedEntry() {
        val queue = MessageQueue()
        try {
            val entry = queue.enqueue(text("hi"), requestToken = "vc-token-1")!!
            assertEquals("vc-token-1", entry.requestToken)
            assertSame(entry, queue.takeNext())
            // 令牌不可变：派发出去之后仍然是同一个值
            assertEquals("vc-token-1", queue.inFlightMessages.single().requestToken)
            // 非通话请求没有令牌
            assertNull(queue.enqueue(text("plain"))!!.requestToken)
        } finally { queue.clear() }
    }

    @Test fun removingAQueuedEntryHandsItsTransientImagesToTheOwner() {
        val queue = MessageQueue()
        val discarded = mutableListOf<List<String>>()
        queue.onMessageDiscarded = { message -> discarded += message.transientImageUrls }
        try {
            val entry = queue.enqueue(
                text("with frame"),
                transientImageUrls = listOf("file:///cache/a.jpg"),
            )!!
            assertSame(entry, queue.remove(entry.id))
            assertEquals(listOf(listOf("file:///cache/a.jpg")), discarded)
        } finally { queue.clear() }
    }

    @Test fun clearTerminatesOnlyPendingItemsAndLeavesInFlightToTheRequest() = runBlocking {
        val queue = MessageQueue()
        val discarded = mutableListOf<List<String>>()
        val transient = mutableListOf<List<String>>()
        queue.onMessageDiscarded = { message -> discarded += message.ownedImageUrls }
        queue.onDiscardTransientImages = { message -> transient += message.transientImageUrls }
        // 先进队列的那条会被立刻派发（in-flight），后进的还留在 pending。
        val inFlight = queue.enqueue(
            text("inflight"),
            transientImageUrls = listOf("file:///cache/inflight.jpg"),
            ownedImageUrls = listOf("file:///files/inflight-clip.jpg"),
        )!!
        val stillPending = queue.enqueue(
            text("pending"),
            transientImageUrls = listOf("file:///cache/pending.jpg"),
            ownedImageUrls = listOf("file:///files/pending-clip.jpg"),
        )!!
        assertSame(inFlight, queue.takeNext())
        assertEquals(listOf(stillPending.id, inFlight.id), queue.clear().map { it.id })
        // pending 从未提交到历史：临时图与"本次新建、还没落库"的永久帧都由队列终结。
        assertEquals(listOf(listOf("file:///cache/pending.jpg")), transient)
        assertEquals(listOf(listOf("file:///files/pending-clip.jpg")), discarded)
        assertNull(stillPending.reply.await())
        assertTrue(stillPending.completion.isCancelled)
        // in-flight 的图片所有权在请求本身：队列**绝不**在这里删（否则会删掉已经写进历史的照片）。
        // 直接看被终结的 URL：只有 pending 那一条的两份列表，in-flight 的两种图一个都不许出现。
        assertEquals(1, transient.size)
        assertEquals(1, discarded.size)
        assertTrue(transient.flatten().none { it.contains("inflight") })
        assertTrue(discarded.flatten().none { it.contains("inflight") })
        assertTrue(queue.state.value.paused)
        assertTrue(queue.state.value.messages.isEmpty())
        // 请求仍然持有自己的 deferred：job 结束时由 complete 正式收尾，不会悬空。
        assertFalse(inFlight.reply.isCompleted)
        queue.complete(inFlight.id, error = kotlinx.coroutines.CancellationException("session closed"))
        assertTrue(inFlight.reply.isCompleted)
        assertFalse(queue.hasPendingOrInFlight)
        assertTrue(queue.clear().isEmpty())
    }

    @Test fun pausingRewritesTheAttachedPicturePromptAndRevokesTheToken() {
        val queue = MessageQueue()
        val revoked = mutableListOf<String>()
        queue.onRequestTokenRevoked = { token -> revoked += token }
        try {
            queue.enqueue(
                listOf(UIMessagePart.Text("hi")),
                transientSystemPrompt = "本轮附图：一张自动抓帧",
                transientSystemPromptWithoutImages = "本轮没有附图",
                transientImageUrls = listOf("file:///cache/frame.jpg"),
                requestToken = "vc-token-1",
            )
            queue.enqueue(
                listOf(UIMessagePart.Text("看"), UIMessagePart.Image("file:///files/clip.jpg")),
                transientSystemPrompt = "本轮附图：一张手动夹帧",
                transientSystemPromptWithoutImages = "本轮没有附图",
                ownedImageUrls = listOf("file:///files/clip.jpg"),
                requestToken = "vc-token-2",
            )
            queue.pause()
            val paused = queue.state.value.messages
            // 临时图被摘掉 → "附图"说明必须改成无图版本，token 作废（恢复队列是一次全新请求）。
            assertEquals("本轮没有附图", paused[0].transientSystemPrompt)
            assertTrue(paused[0].transientImageUrls.isEmpty())
            assertNull(paused[0].requestToken)
            // 永久手动夹帧仍在 parts 里 → "附图"说明仍然成立，永久帧本身不受影响。
            assertEquals("本轮附图：一张手动夹帧", paused[1].transientSystemPrompt)
            assertEquals(listOf("file:///files/clip.jpg"), paused[1].ownedImageUrls)
            assertNull(paused[1].requestToken)
            assertEquals(setOf("vc-token-1", "vc-token-2"), revoked.toSet())
            queue.resume()
            assertNull(queue.takeNext()!!.requestToken)
        } finally { queue.clear() }
    }

    @Test fun pausingDropsTheTurnImagesButKeepsTheMessageResumable() {
        val queue = MessageQueue()
        val transientDropped = mutableListOf<String>()
        val terminated = mutableListOf<kotlin.uuid.Uuid>()
        queue.onDiscardTransientImages = { message -> transientDropped += message.transientImageUrls }
        queue.onMessageDiscarded = { message -> terminated += message.id }
        try {
            val entry = queue.enqueue(
                listOf(UIMessagePart.Text("hi"), UIMessagePart.Image("file:///files/clip.jpg")),
                transientImageUrls = listOf("file:///cache/frame.jpg"),
            )!!
            queue.pause()
            // 临时图立刻终结，但队列项本身还在（可以被用户 resume），永久夹帧也保留
            assertEquals(listOf("file:///cache/frame.jpg"), transientDropped)
            assertTrue(terminated.isEmpty())
            assertEquals(1, queue.state.value.messages.size)
            assertTrue(queue.state.value.messages.single().transientImageUrls.isEmpty())
            assertEquals(
                listOf("file:///files/clip.jpg"),
                queue.state.value.messages.single().parts.filterIsInstance<UIMessagePart.Image>().map { it.url },
            )
            queue.resume()
            assertEquals(entry.id, queue.takeNext()?.id)
        } finally { queue.clear() }
    }

    @Test fun discardingIsWiredExactlyOncePerEntry() {
        val queue = MessageQueue()
        var terminated = 0
        queue.onDiscardTransientImages = { }
        queue.onMessageDiscarded = { terminated += 1 }
        try {
            val entry = queue.enqueue(text("once"), transientImageUrls = listOf("file:///cache/x.jpg"))!!
            queue.remove(entry.id)
            queue.remove(entry.id)
            assertEquals(1, terminated)
        } finally { queue.clear() }
    }

    // ------------------------------------------------------------------
    // 视频通话的临时录音：所有权、暂停后不许当"完整语音请求"再发出去
    // ------------------------------------------------------------------

    /** 造一段真实的临时录音（自家目录 + 前缀），返回 clip 与文件。 */
    private fun transientAudio(): Pair<me.rerere.rikkahub.data.video.TransientAudioClip, java.io.File> {
        val directory = java.io.File.createTempFile("vcvoice-queue", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }
        val file = java.io.File(
            directory,
            me.rerere.rikkahub.data.video.TransientAudioClip.FILE_PREFIX + "queue.wav",
        ).apply { writeBytes(ByteArray(64) { 1 }) }
        val clip = requireNotNull(
            me.rerere.rikkahub.data.video.TransientAudioClip.adopt(file, directory, 4_000L),
        )
        return clip to file
    }

    @Test fun withdrawingAQueuedEntryDeletesItsTransientAudioExactlyOnce() {
        val queue = MessageQueue()
        val (clip, file) = transientAudio()
        try {
            val entry = queue.enqueue(text("语音"), transientAudio = clip)!!
            assertSame(clip, entry.transientAudio)
            assertTrue(file.exists())
            assertSame(entry, queue.remove(entry.id))
            assertFalse("撤回之后不留受管音频", file.exists())
            assertEquals(
                me.rerere.rikkahub.data.video.TransientAudioClip.Phase.RELEASED,
                clip.currentPhase,
            )
            // 会话清理/挂断再来一遍：不许二次删除（也不许报错）。
            queue.clear()
            assertEquals(
                me.rerere.rikkahub.data.video.TransientAudioClip.Phase.RELEASED,
                clip.currentPhase,
            )
        } finally { queue.clear() }
    }

    @Test fun pausingDropsTheRecordingAndMarksTheMessageAsUnsentAudio() {
        val queue = MessageQueue()
        val (clip, file) = transientAudio()
        try {
            val placeholder = me.rerere.rikkahub.data.video.VideoVoicePolicy.directVoicePlaceholder(4_000L)
            val entry = queue.enqueue(
                listOf(UIMessagePart.Text(placeholder)),
                transientSystemPrompt = "本轮附带了一段录音",
                transientSystemPromptWithoutImages = "本轮没有录音和画面",
                transientAudio = clip,
            )!!
            queue.pause()

            val paused = queue.state.value.messages.single()
            assertNull("暂停之后队列项不再持有录音", paused.transientAudio)
            assertFalse("文件必须当场删掉，不能等 resume 才发现不存在", file.exists())
            // 恢复队列是一次**全新请求**：说明改成"没有录音"的版本。
            assertEquals("本轮没有录音和画面", paused.transientSystemPrompt)
            // 保留原占位 + 明确写一句"录音已失效"：既不会让模型以为还能回听，
            // 也不会把用户的原话悄悄改掉。
            val texts = paused.parts.filterIsInstance<UIMessagePart.Text>().map { it.text }
            assertEquals(2, texts.size)
            assertTrue(texts.any { it.contains("仅本轮提供") })
            assertTrue("必须写清这一段没有发送", texts.any { it.contains("已失效") && it.contains("没有发送") })
            // 这条消息仍然可以被用户手动恢复成一条**普通文字**消息（不会自动当成语音请求）。
            queue.resume()
            val resumed = queue.takeNext()!!
            assertNull(resumed.transientAudio)
            assertTrue(resumed.parts.none { it is UIMessagePart.Audio })
        } finally { queue.clear() }
    }

    @Test fun anInFlightRecordingIsNeverDeletedByTheQueue() {
        val queue = MessageQueue()
        val (clip, file) = transientAudio()
        try {
            queue.enqueue(text("语音"), transientAudio = clip)
            // 请求认领（ChatService 的 job 一开始就会做）：文件仍在使用中。
            assertTrue(clip.beginRequest())
            val inFlight = queue.takeNext()!!
            assertSame(clip, inFlight.transientAudio)
            // 队列在这里只解除自己的引用：绝不许删掉正在序列化的那一段。
            queue.clear()
            assertTrue("序列化期间的文件必须还在", file.exists())
            assertEquals(
                me.rerere.rikkahub.data.video.TransientAudioClip.Phase.REQUESTING,
                clip.currentPhase,
            )
            // 请求结束：由它自己释放。
            assertTrue(clip.release(fromRequest = true))
            assertFalse(file.exists())
        } finally { queue.clear() }
    }

    @Test fun pausingStillKeepsPermanentClipsAndTransientImagesBehavingAsBefore() {
        // 回归保护：加入临时录音之后，原有的"临时图 + 永久夹帧"暂停语义一个字都不许变。
        val queue = MessageQueue()
        val transientDropped = mutableListOf<String>()
        queue.onDiscardTransientImages = { message -> transientDropped += message.transientImageUrls }
        try {
            val entry = queue.enqueue(
                listOf(UIMessagePart.Text("看图"), UIMessagePart.Image("file:///files/clip.jpg")),
                transientImageUrls = listOf("file:///cache/frame.jpg"),
            )!!
            queue.pause()
            val paused = queue.state.value.messages.single()
            assertEquals(listOf("file:///cache/frame.jpg"), transientDropped)
            assertTrue(paused.transientImageUrls.isEmpty())
            // 没有音频的这条消息**不允许**被塞进"录音已失效"的说明。
            assertEquals(entry.parts.size, paused.parts.size)
            assertTrue(paused.parts.none { it is UIMessagePart.Text && it.text.contains("已失效") })
            assertEquals(
                listOf("file:///files/clip.jpg"),
                paused.parts.filterIsInstance<UIMessagePart.Image>().map { it.url },
            )
            queue.resume()
            assertEquals(entry.id, queue.takeNext()?.id)
        } finally { queue.clear() }
    }
}
