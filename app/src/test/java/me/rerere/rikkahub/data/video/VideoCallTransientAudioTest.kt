/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.ui.hooks.DirectAudioRecorder
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 临时录音的**所有权**与"只给当前这一条请求"的注入规则。
 *
 * 用户最担心的两件事都在这里被钉住：
 * 1. OOM / 磁盘残留：一段录音最多只存在到"这一次请求结束"，删除是原子的且只发生一次；
 * 2. 删早：请求正在序列化（含工具多步）时，队列/UI/挂断的任何丢弃都**不许**删文件；
 * 以及"音频只挂到锚点那条用户消息上，历史里其他消息一条都不带"。
 */
class VideoCallTransientAudioTest {

    private fun tempDirectory(): File =
        File.createTempFile("vcvoice-test", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }

    private fun wavFile(directory: File, bytes: ByteArray = DirectAudioRecorder.wavHeader(4) + byteArrayOf(0, 1, 2, 3)): File {
        val file = File(directory, TransientAudioClip.FILE_PREFIX + "unit.wav")
        file.writeBytes(bytes)
        return file
    }

    // ------------------------------------------------------------------
    // §所有权状态机
    // ------------------------------------------------------------------

    @Test
    fun `a queued segment is deleted exactly once when the queue discards it`() {
        val directory = tempDirectory()
        val file = wavFile(directory)
        val clip = requireNotNull(TransientAudioClip.adopt(file, directory, 5_000L))
        assertEquals(TransientAudioClip.Phase.QUEUED, clip.currentPhase)
        assertTrue(clip.exists)

        assertTrue("队列丢弃：由它认领删除", clip.release(fromRequest = false))
        assertFalse("文件必须真的删掉", file.exists())
        assertEquals(TransientAudioClip.Phase.RELEASED, clip.currentPhase)
        // 第二个 owner（挂断/onDestroy/二次回调）不许再删一次。
        assertFalse(clip.release(fromRequest = false))
        assertFalse(clip.release(fromRequest = true))
    }

    @Test
    fun `a segment claimed by a running request is never deleted by the queue`() {
        val directory = tempDirectory()
        val file = wavFile(directory)
        val clip = requireNotNull(TransientAudioClip.adopt(file, directory, 5_000L))

        assertTrue(clip.beginRequest())
        assertEquals(TransientAudioClip.Phase.REQUESTING, clip.currentPhase)
        // 序列化期间队列/UI 的任何丢弃都必须被拒绝：删早会让请求读到不存在的文件。
        assertFalse(clip.release(fromRequest = false))
        assertTrue("正在被请求使用：文件必须还在", file.exists())

        // 请求结束（正常/失败/取消）后由请求自己释放。
        assertTrue(clip.release(fromRequest = true))
        assertFalse(file.exists())
        assertFalse("只能删一次", clip.release(fromRequest = true))
    }

    @Test
    fun `two requests racing for one clip only let the owner send and release`() = runBlocking {
        val directory = tempDirectory()
        val file = wavFile(directory)
        val clip = requireNotNull(TransientAudioClip.adopt(file, directory, 5_000L))
        val owners = List(2) { TransientAudioClip.RequestOwnership(clip) }
        val claims = owners.map { owner -> async(Dispatchers.Default) { owner.beginRequest() } }.awaitAll()
        try {
            assertEquals("只能有一个请求获准继续发送", 1, claims.count { it })
            val loser = owners[claims.indexOf(false)]
            assertFalse("失败请求的 finally 不能释放赢家的录音", loser.release())
            assertFalse("失败请求的 completion 不能释放赢家的录音", loser.release())
            assertEquals(TransientAudioClip.Phase.REQUESTING, clip.currentPhase)
            assertTrue(file.exists())
            assertTrue(owners[claims.indexOf(true)].release())
            assertFalse(file.exists())
        } finally {
            owners.forEach { it.release() }
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a released clip cannot be claimed or sent again`() {
        val directory = tempDirectory()
        val clip = requireNotNull(TransientAudioClip.adopt(wavFile(directory), directory, 1_000L))
        assertTrue(clip.release(fromRequest = false))
        val owner = TransientAudioClip.RequestOwnership(clip)
        assertFalse(owner.beginRequest())
        assertFalse(owner.release())
        directory.deleteRecursively()
    }

    @Test
    fun `cancelling a never started lazy request releases its queued clip`() = runBlocking {
        val directory = tempDirectory()
        val file = wavFile(directory)
        val clip = requireNotNull(TransientAudioClip.adopt(file, directory, 1_000L))
        val owner = TransientAudioClip.RequestOwnership(clip)
        var started = false
        val job = launch(start = CoroutineStart.LAZY) {
            started = true
            check(owner.beginRequest())
        }
        job.invokeOnCompletion { owner.release() }
        job.cancel()
        job.join()
        assertFalse(started)
        assertFalse(file.exists())
        assertEquals(TransientAudioClip.Phase.RELEASED, clip.currentPhase)
        assertFalse("取消完成后的凭据不能重新认领", owner.beginRequest())
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun `cancelling a never started duplicate request preserves the active owner's clip`() = runBlocking {
        val directory = tempDirectory()
        val file = wavFile(directory)
        val clip = requireNotNull(TransientAudioClip.adopt(file, directory, 1_000L))
        val activeOwner = TransientAudioClip.RequestOwnership(clip)
        assertTrue(activeOwner.beginRequest())
        val duplicateOwner = TransientAudioClip.RequestOwnership(clip)
        val job = launch(start = CoroutineStart.LAZY) { check(duplicateOwner.beginRequest()) }
        job.invokeOnCompletion { duplicateOwner.release() }
        job.cancel()
        job.join()
        assertEquals(TransientAudioClip.Phase.REQUESTING, clip.currentPhase)
        assertTrue(file.exists())
        assertTrue(activeOwner.release())
        assertFalse(file.exists())
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun `only our own directory is adopted or discarded`() {
        val directory = tempDirectory()
        val userRecordings = tempDirectory()
        // 用户正常的聊天录音目录绝对不许被接管/删除。
        val userFile = wavFile(userRecordings)
        assertNull(TransientAudioClip.adopt(userFile, directory, 1_000L))
        TransientAudioClip.discardFile(userFile, directory)
        assertTrue("别的目录的文件一个字节都不许动", userFile.exists())

        // 自己目录里但前缀不对（不是本功能写的文件）也不接管、不删。
        val foreign = File(directory, "voice_someone_else.wav").apply { writeBytes(byteArrayOf(1, 2)) }
        assertNull(TransientAudioClip.adopt(foreign, directory, 1_000L))
        TransientAudioClip.discardFile(foreign, directory)
        assertTrue(foreign.exists())

        // 自己的文件：正常接管。
        val own = wavFile(directory)
        assertNotNull(TransientAudioClip.adopt(own, directory, 1_000L))
        TransientAudioClip.discardFile(own, directory)
        assertFalse(own.exists())
    }

    @Test
    fun `empty or missing files are never adopted`() {
        val directory = tempDirectory()
        assertNull(TransientAudioClip.adopt(File(directory, "vcvoice_missing.wav"), directory, 1_000L))
        val empty = File(directory, TransientAudioClip.FILE_PREFIX + "empty.wav").apply { createNewFile() }
        assertNull("长度为 0 的文件不能当成一段录音", TransientAudioClip.adopt(empty, directory, 1_000L))
    }

    @Test
    fun `crash leftovers are swept by age without touching user recordings`() {
        val directory = tempDirectory()
        val userRecordings = tempDirectory()
        val old = wavFile(directory).apply { setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000) }
        val fresh = File(directory, TransientAudioClip.FILE_PREFIX + "fresh.wav").apply { writeBytes(byteArrayOf(1)) }
        val foreign = File(directory, "voice_user.wav").apply { writeBytes(byteArrayOf(1)) }
        val userFile = wavFile(userRecordings).apply { setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000) }

        TransientAudioClip.sweepStale(directory)

        assertFalse("超过龄期的遗留录音要清掉", old.exists())
        assertTrue("正在使用的（新的）文件不许清", fresh.exists())
        assertTrue("不是本功能命名的文件不许清", foreign.exists())
        assertTrue("绝不按大目录扫用户的正常录音", userFile.exists())
    }

    // ------------------------------------------------------------------
    // §注入：只给锚点那条用户消息
    // ------------------------------------------------------------------

    private fun conversation(): List<UIMessage> {
        val first = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("历史里的第一句")))
        val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("历史回复")))
        val anchor = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("这一轮的文字")))
        return listOf(first, assistant, anchor)
    }

    @Test
    fun `audio is injected into the anchor message only and the history is untouched`() {
        val messages = conversation()
        val anchorId = messages.last().id.toString()
        val url = "file:///cache/video_voice_transient/vcvoice_anchor.wav"

        val request = messages.withTransientRequestAudio(url, anchorId)

        val audio = request.flatMap { it.parts }.filterIsInstance<UIMessagePart.Audio>()
        assertEquals("整份请求里只能出现一段音频", 1, audio.size)
        assertEquals(url, audio.single().url)
        assertTrue(
            "音频只能挂在锚点那条用户消息上",
            request.last().parts.any { it is UIMessagePart.Audio },
        )
        // 历史里的其他消息一条都不许被改动（下一轮不会再 base64 一次）。
        assertEquals(messages[0], request[0])
        assertEquals(messages[1], request[1])
        // 原始消息本身也没被改写：历史里永远只有文字。
        assertTrue(messages.flatMap { it.parts }.none { it is UIMessagePart.Audio })

        // 工具多步可能插入新的 USER：锚点仍然生效（不会挂到"最后一条 USER"上）。
        val inserted = request + UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("工具结果")))
        val reapplied = inserted.withTransientRequestAudio(url, anchorId)
        assertEquals(
            "工具轮次里同一段音频被复用时也只挂在锚点上",
            1,
            reapplied.flatMap { it.parts }.filterIsInstance<UIMessagePart.Audio>().size,
        )
        assertTrue(
            "插进来的消息不许带上音频",
            reapplied.last().parts.none { it is UIMessagePart.Audio },
        )
    }

    @Test
    fun `no audio url means the request is byte identical`() {
        val messages = conversation()
        assertSameList(messages, messages.withTransientRequestAudio(null, null))
        assertSameList(messages, messages.withTransientRequestAudio("   ", messages.last().id.toString()))
    }

    @Test
    fun `a missing explicit anchor stops instead of attaching audio to the last user`() {
        val messages = conversation()
        val error = runCatching {
            messages.withTransientRequestAudio("file:///cache/a.wav", "not-a-real-id")
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("已停止发送"))
        assertTrue(messages.flatMap { it.parts }.none { it is UIMessagePart.Audio })
    }

    @Test
    fun `a non user anchor stops instead of attaching audio to another user`() {
        val messages = conversation()
        val error = runCatching {
            messages.withTransientRequestAudio("file:///cache/a.wav", messages[1].id.toString())
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("不是用户消息"))
        assertTrue(messages.flatMap { it.parts }.none { it is UIMessagePart.Audio })
    }

    @Test
    fun `audio without an anchor is rejected even when a last user exists`() {
        listOf<String?>(null, "", "   ").forEach { anchor ->
            val error = runCatching {
                conversation().withTransientRequestAudio("file:///cache/a.wav", anchor)
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("缺少用户消息锚点"))
        }
    }

    private fun assertSameList(expected: List<UIMessage>, actual: List<UIMessage>) {
        assertEquals(expected.size, actual.size)
        expected.forEachIndexed { index, message -> assertEquals(message, actual[index]) }
    }

    // ------------------------------------------------------------------
    // §占位文本
    // ------------------------------------------------------------------

    @Test
    fun `the persisted placeholder never contains an audio part`() {
        val text = VideoVoicePolicy.directVoicePlaceholder(30_000L)
        val userMessage = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))
        // 这条消息会被写进历史：序列化之后重新读回来仍然**没有** audio（不会让下一轮 read 失败）。
        val encoded = me.rerere.rikkahub.utils.JsonInstant.encodeToString<UIMessage>(userMessage)
        val decoded = me.rerere.rikkahub.utils.JsonInstant.decodeFromString<UIMessage>(encoded)
        assertFalse(encoded.contains("\"audio\""))
        assertTrue(encoded.contains("仅本轮提供"))
        assertTrue(decoded.parts.none { it is UIMessagePart.Audio })
        assertEquals(text, (decoded.parts.single() as UIMessagePart.Text).text)
        // 新设置项也要能被反序列化（老数据缺这个字段时退回 AUTO，不炸）。
        val legacySetting = me.rerere.rikkahub.utils.JsonInstant
            .decodeFromString<VideoCallSetting>("""{"giftsEnabled":true}""")
        assertEquals(VideoVoiceInputMode.AUTO, legacySetting.voiceInputMode)
        assertEquals(
            VideoVoiceInputMode.ASR,
            me.rerere.rikkahub.utils.JsonInstant.decodeFromString<VideoCallSetting>(
                me.rerere.rikkahub.utils.JsonInstant.encodeToString(
                    VideoCallSetting().copy(voiceInputMode = VideoVoiceInputMode.ASR),
                ),
            ).voiceInputMode,
        )
    }
}
