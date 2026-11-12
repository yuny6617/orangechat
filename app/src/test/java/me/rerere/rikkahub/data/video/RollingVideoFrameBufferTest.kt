package me.rerere.rikkahub.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RollingVideoFrameBufferTest {
    private fun frame(at: Long, size: Int = 4) = VideoFrame(ByteArray(size), 4, 3, at)

    // Existing fixtures use matching display/monotonic times; clock tests pass them independently.
    private fun RollingVideoFrameBuffer.accept(
        epoch: Long,
        capturedCameraEpoch: Long,
        currentCameraEpoch: Long,
        frame: VideoFrame,
        capacity: Int,
    ): Boolean = accept(epoch, capturedCameraEpoch, currentCameraEpoch, frame, capacity, frame.capturedAtMillis)

    @Test
    fun retainsOnlyLatestBoundedSamplesAndRejectsEarlyOrStaleFrames() {
        val buffer = RollingVideoFrameBuffer()
        val epoch = buffer.epoch()
        assertTrue(buffer.accept(epoch, 7L, 7L, frame(1000), 2))
        assertFalse(buffer.accept(epoch, 7L, 7L, frame(1300), 2))
        assertTrue(buffer.accept(epoch, 7L, 7L, frame(2000), 2))
        assertTrue(buffer.accept(epoch, 7L, 7L, frame(3000), 2))
        assertEquals(listOf(2000L, 3000L), buffer.latest(9, 7L).map { it.capturedAtMillis })
        buffer.clear()
        assertTrue(buffer.latest(9, 7L).isEmpty())
        // clear 之后，抓帧前记下的旧 epoch 不再被接受。
        assertFalse(buffer.accept(epoch, 7L, 7L, frame(4000), 2))
        assertTrue(buffer.accept(buffer.epoch(), 7L, 7L, frame(5000), 1))
        assertEquals(listOf(5000L), buffer.latest(1, 7L).map { it.capturedAtMillis })
    }

    @Test
    fun cameraGenerationIsBoundToTheFrameSetAndLateFramesAreRejected() {
        val buffer = RollingVideoFrameBuffer()
        val epoch = buffer.epoch()
        assertTrue(buffer.accept(epoch, 7L, 7L, frame(1000), 3))
        // 抓帧时还是第 7 代，落进来时已经是第 8 代：迟到帧不许混进新一组。
        assertFalse(buffer.accept(epoch, 7L, 8L, frame(1500), 3))
        assertEquals(listOf(1000L), buffer.latest(3, 7L).map { it.capturedAtMillis })
        assertEquals(emptyList<Long>(), buffer.latest(3, 8L).map { it.capturedAtMillis })
        // 新相机的第一帧让整组原子换绑到第 8 代，旧帧不会留在拼图里。
        assertTrue(buffer.accept(epoch, 8L, 8L, frame(2000), 3))
        assertEquals(listOf(2000L), buffer.latest(3, 8L).map { it.capturedAtMillis })
        // 换绑之后，上一代相机的迟到帧仍然必须被拒。
        assertFalse(buffer.accept(epoch, 7L, 8L, frame(2500), 3))
        // 即使调用方误把"当前代次"读成旧值，也不许把帧集倒退回上一代。
        assertFalse(buffer.accept(epoch, 7L, 7L, frame(2600), 3))
        assertEquals(listOf(2000L), buffer.latest(3, 8L).map { it.capturedAtMillis })
    }

    @Test
    fun byteBudgetDropsOldestFramesEvenWhenCountBudgetRemains() {
        val buffer = RollingVideoFrameBuffer(maxTotalBytes = 100L)
        val epoch = buffer.epoch()
        assertTrue(buffer.accept(epoch, 1L, 1L, frame(1000, 50), 9))
        assertTrue(buffer.accept(epoch, 1L, 1L, frame(2000, 50), 9))
        // 再收 50 字节超过 100 字节上限：只淘汰最旧的一帧，不整组丢弃。
        assertTrue(buffer.accept(epoch, 1L, 1L, frame(3000, 50), 9))
        assertEquals(listOf(2000L, 3000L), buffer.latest(9, 1L).map { it.capturedAtMillis })
        // 单帧本身就超过预算：直接拒收，不能把整组挤空。
        assertFalse(buffer.accept(epoch, 1L, 1L, frame(4000, 101), 9))
        assertEquals(listOf(2000L, 3000L), buffer.latest(9, 1L).map { it.capturedAtMillis })
        // 空帧没有信息量，也不许占预算。
        assertFalse(buffer.accept(epoch, 1L, 1L, frame(5000, 0), 9))
    }

    @Test
    fun capacityOutsideOneToNineAndManualFramesAreRejected() {
        val buffer = RollingVideoFrameBuffer()
        val epoch = buffer.epoch()
        assertFalse(buffer.accept(epoch, 1L, 1L, frame(1000), 0))
        assertFalse(buffer.accept(epoch, 1L, 1L, frame(1000), 10))
        assertFalse(
            buffer.accept(
                epoch,
                1L,
                1L,
                VideoFrame(byteArrayOf(1), 4, 3, 1000, manual = true),
                3,
            ),
        )
        assertTrue(buffer.latest(9, 1L).isEmpty())
    }

    @Test
    fun samplingUsesMonotonicStartTimeIndependentlyOfDisplayClockAndCaptureLatency() {
        val buffer = RollingVideoFrameBuffer()
        val epoch = buffer.epoch()
        assertTrue(buffer.accept(epoch, 1L, 1L, frame(10_900), 9, sampleAtElapsedMillis = 0))
        // Capture latency fell from 900ms to 100ms: callbacks are only 200ms apart,
        // but the capture starts are a full second apart and both must be retained.
        assertTrue(buffer.accept(epoch, 1L, 1L, frame(11_100), 9, sampleAtElapsedMillis = 1_000))
        // A wall-clock jump cannot let an early sample through.
        assertFalse(buffer.accept(epoch, 1L, 1L, frame(3_611_100), 9, sampleAtElapsedMillis = 1_300))
        // Display clock rollback and repeated timestamps do not stop monotonic sampling.
        assertTrue(buffer.accept(epoch, 1L, 1L, frame(9_000), 9, sampleAtElapsedMillis = 2_000))
        assertTrue(buffer.accept(epoch, 1L, 1L, frame(9_000), 9, sampleAtElapsedMillis = 3_000))
        assertFalse(buffer.accept(epoch, 1L, 1L, frame(99_000), 9, sampleAtElapsedMillis = 2_500))
        assertEquals(listOf(10_900L, 11_100L, 9_000L, 9_000L),
            buffer.latest(9, 1L).map { it.capturedAtMillis })
    }

    @Test
    fun automaticAttachmentGuardRejectsModeCameraAndCallChanges() {
        fun allowed(
            expected: VideoFrameCaptureMode = VideoFrameCaptureMode.CONTINUOUS_SEQUENCE,
            current: VideoFrameCaptureMode = expected,
            camera: Long = 7L,
            granted: Boolean = true,
            active: Boolean = true,
        ) = isAutomaticVideoAttachmentCurrent(expected, current, 7L, camera, granted, active)

        assertTrue(allowed())
        assertTrue(allowed(expected = VideoFrameCaptureMode.EVERY_REPLY))
        assertTrue(allowed(expected = VideoFrameCaptureMode.EVERY_30S))
        assertTrue(allowed(expected = VideoFrameCaptureMode.EVERY_60S))
        assertFalse(allowed(current = VideoFrameCaptureMode.OFF))
        assertFalse(allowed(current = VideoFrameCaptureMode.EVERY_30S))
        assertFalse(allowed(expected = VideoFrameCaptureMode.CONTINUOUS_SEQUENCE,
            current = VideoFrameCaptureMode.EVERY_REPLY))
        assertFalse(allowed(expected = VideoFrameCaptureMode.EVERY_30S,
            current = VideoFrameCaptureMode.EVERY_60S))
        assertFalse(allowed(expected = VideoFrameCaptureMode.OFF))
        assertFalse(allowed(camera = 8L))
        assertFalse(allowed(granted = false))
        assertFalse(allowed(active = false))
    }

    @Test
    fun samplingCadenceDeductsCaptureTimeFromTheOneSecondPeriod() {
        val cadence = RollingSampleCadence(periodMillis = 1_000L)
        cadence.reset(0L)
        // 抓帧花了 300 ms：只等 700 ms，下一次采样仍落在 1 s 节拍上。
        assertEquals(700L, cadence.delayAfterSample(startedAtMillis = 0L, nowMillis = 300L))
        assertEquals(700L, cadence.delayAfterSample(startedAtMillis = 1_000L, nowMillis = 1_300L))
        // 抓帧本身用了 2.5 s：不补发一连串抓帧，立刻采下一帧并把节拍重新对齐。
        assertEquals(0L, cadence.delayAfterSample(startedAtMillis = 2_000L, nowMillis = 4_500L))
        assertEquals(900L, cadence.delayAfterSample(startedAtMillis = 4_500L, nowMillis = 4_600L))
    }

    @Test
    fun resetStartsAFreshCadence() {
        val cadence = RollingSampleCadence(periodMillis = 1_000L)
        cadence.reset(10_000L)
        assertEquals(1_000L, cadence.delayAfterSample(startedAtMillis = 10_000L, nowMillis = 10_000L))
        // 空闲一段时间后重新开始：下一次仍从一个完整周期算起，不追赶旧节拍。
        cadence.reset(50_000L)
        assertEquals(800L, cadence.delayAfterSample(startedAtMillis = 50_000L, nowMillis = 50_200L))
    }

    @Test
    fun latestClampsRequestedCount() {
        val buffer = RollingVideoFrameBuffer()
        val epoch = buffer.epoch()
        (1..9).forEach { second ->
            assertTrue(buffer.accept(epoch, 1L, 1L, frame(second * 1_000L), 9))
        }
        assertEquals(1, buffer.latest(0, 1L).size)
        assertEquals(9, buffer.latest(99, 1L).size)
        assertEquals(listOf(8_000L, 9_000L), buffer.latest(2, 1L).map { it.capturedAtMillis })
    }
}
