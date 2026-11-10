package me.rerere.rikkahub.ui.hooks

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DirectAudioRecorderTest {
    @Test fun createsPlayableMono16BitWavHeader() {
        val header = DirectAudioRecorder.wavHeader(32000)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals(32036, buffer.getInt(4))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals(1, buffer.getShort(20).toInt())
        assertEquals(1, buffer.getShort(22).toInt())
        assertEquals(16000, buffer.getInt(24))
        assertEquals(32000, buffer.getInt(28))
        assertEquals(16, buffer.getShort(34).toInt())
        assertEquals(32000, buffer.getInt(40))
    }

    @Test fun focusLossMessageSaysTheSegmentWasNotSent() {
        // 焦点被抢时（视频通话路线）给用户看的必须是"已取消、没有发送"，
        // 不能是一句听起来像"录完了"的话——否则用户会等一条永远不会来的消息。
        assertTrue(DirectAudioRecorder.FOCUS_LOST_MESSAGE.contains("取消"))
        assertTrue(DirectAudioRecorder.FOCUS_LOST_MESSAGE.contains("没有发送"))
        assertTrue(DirectAudioRecorder.FOCUS_LOST_MESSAGE.contains("占用"))
    }

    @Test fun chatDefaultLimitStaysFiveMinutesWhileVideoUsesSixtySeconds() {
        // 聊天输入框的原行为：300 秒 × 16k × 2 字节 = 9,600,000 字节（与旧 MAX_BYTES 完全一致）。
        assertEquals(9_600_000, DirectAudioRecorder.maxDataBytes(DirectAudioRecorder.DEFAULT_MAX_DURATION_MS))
        assertEquals(300_000, DirectAudioRecorder.DEFAULT_MAX_DURATION_MS)
        // 视频通话手动录音：60 秒上限。
        assertEquals(1_920_000, DirectAudioRecorder.maxDataBytes(60_000))
        // 不合法/零时长不会算出负数，也不会写出文件。
        assertEquals(0, DirectAudioRecorder.maxDataBytes(0))
        assertEquals(0, DirectAudioRecorder.maxDataBytes(-5))
        // 时长参数再大也不会写出巨型文件（硬上限兜底）。
        assertEquals(
            DirectAudioRecorder.HARD_MAX_BYTES,
            DirectAudioRecorder.maxDataBytes(Int.MAX_VALUE),
        )
        assertTrue(DirectAudioRecorder.HARD_MAX_BYTES <= 10 * 1024 * 1024)
    }
}
