package me.rerere.rikkahub.data.video

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class VideoCallPrivacyTest {
    @After fun clean() = VideoCallRuntime.reset()

    @Test fun `close releases gateway invalidates pending and inflight snapshots without ending call`() = runBlocking {
        VideoCallRuntime.reset()
        VideoCallRuntime.enterVideoCall("call")
        val result = CompletableDeferred<VideoFrame?>()
        var captures = 0
        var closes = 0
        val camera = object : VideoCallCameraGateway {
            override val available = true // Even a stale gateway cannot bypass user intent.
            override val unavailableReason = ""
            override suspend fun capture(): VideoFrame? { captures++; return result.await() }
            override fun closeCamera() { closes++ }
        }
        VideoCallRuntime.registerCamera("call", camera)
        VideoCallRuntime.beginTurnRequest("call", "turn")
        val permit = VideoCallRuntime.requestSnapshot("call", "turn", "before", 1)!!.permit
        val frame = VideoFrame(byteArrayOf(1), 1, 1, 1)
        val epoch = VideoCallRuntime.currentCameraEpoch()
        assertTrue(VideoCallRuntime.holdPendingFrame(frame, "call", epoch))
        val inFlight = async { VideoCallRuntime.captureFrame() }
        yield()
        assertEquals(1, captures)
        assertFalse(VideoCallRuntime.setCameraEnabled("other", false))
        assertTrue(VideoCallRuntime.setCameraEnabled("call", false))
        assertEquals(1, closes)
        assertNull(VideoCallRuntime.pendingFrame())
        assertFalse(VideoCallRuntime.isSnapshotPermitUsable(permit))
        result.complete(frame)
        assertNull(inFlight.await())
        assertNull(VideoCallRuntime.captureFrame())
        assertFalse(VideoCallRuntime.holdPendingFrame(frame, "call", epoch))
        assertFalse(VideoCallRuntime.holdPendingFrame(frame))
        val denied = VideoCallRuntime.requestSnapshot("call", "turn", "after", 2)!!
        assertFalse(denied.cameraAvailable)
        assertNull(denied.permit)
        assertTrue(VideoCallRuntime.enterVideoCall("call"))
        VideoCallRuntime.registerCamera("call", camera)
        assertFalse(VideoCallRuntime.cameraAvailable())
        assertNull(VideoCallRuntime.captureFrame())
        assertEquals(1, captures)
        assertTrue(VideoCallRuntime.isTurnGranted("call", "turn"))
        assertFalse(VideoCallPolicy.shouldBindCamera(true, true, true, true, cameraEnabled = false))
        assertTrue(VideoCallRuntime.setCameraEnabled("call", true))
        assertTrue(VideoCallRuntime.cameraAvailable())
        assertFalse(VideoCallRuntime.isSnapshotPermitUsable(permit))
    }

    @Test fun `preview mirror flips screen x after every calibration and jpeg honors the same switch`() {
        for (front in listOf(false, true)) for (calibration in listOf(0, 90, 180, 270)) {
            fun transform(mirror: Boolean) = VideoPreviewGeometry.textureViewTransform(
                90, 0, front, 640, 480, 300, 500, calibration, mirror,
            )
            val plain = transform(false).matrix.map(80f, 170f)
            val flipped = transform(true).matrix.map(80f, 170f)
            assertEquals(300f - plain.x, flipped.x, .001f)
            assertEquals(plain.y, flipped.y, .001f)
            // The controller passes the same mirror choice into JPEG normalization.
            val jpeg = VideoPreviewGeometry.uprightImageAffine(0, false, 640, 480)
            val mirroredJpeg = VideoPreviewGeometry.uprightImageAffine(0, true, 640, 480)
            assertTrue(jpeg.map(10f, 20f).x < jpeg.map(30f, 20f).x)
            assertTrue(mirroredJpeg.map(10f, 20f).x > mirroredJpeg.map(30f, 20f).x)
        }
    }
}
