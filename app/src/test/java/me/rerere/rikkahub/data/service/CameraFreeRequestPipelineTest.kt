package me.rerere.rikkahub.data.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.ClaudeProvider
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.video.cameraFreeRequests
import me.rerere.rikkahub.service.buildVoiceCallDeclinedRequestMessages
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end regression for the two background provider paths that used to replay historical
 * camera media: proactive wake-ups (initial request and every tool round) and the
 * "voice call declined" system event. Requests are serialized with the real Google/Claude wire
 * builders so an ordinary attachment has to survive all the way into the provider payload.
 */
class CameraFreeRequestPipelineTest {
    private val cameraClip = "file:///data/user/0/orange/files/video_call_clips/clip.jpg"
    private val cameraSnapshot = "file:///data/user/0/orange/files/video_call_snapshots/snap.jpg"
    private val ordinaryImage = "data:image/png;base64,b3JkaW5hcnktaW1hZ2U="
    private val ordinaryFile = "file:///data/user/0/orange/files/uploads/normal.jpg"

    private fun wire(messages: List<UIMessage>, google: Boolean): JsonArray {
        val provider = if (google) GoogleProvider(OkHttpClient()) else ClaudeProvider(OkHttpClient())
        val method = if (google) {
            provider.javaClass.getDeclaredMethod("buildContents", List::class.java)
        } else {
            provider.javaClass.getDeclaredMethod(
                "buildMessages", List::class.java, Boolean::class.javaPrimitiveType, ClaudePromptCacheTtl::class.java,
            )
        }
        method.isAccessible = true
        return if (google) {
            method.invoke(provider, messages) as JsonArray
        } else {
            method.invoke(provider, messages, false, ClaudePromptCacheTtl.FIVE_MINUTES) as JsonArray
        }
    }

    private fun List<UIMessage>.imageUrls(): List<String> =
        flatMap { message -> message.parts.filterIsInstance<UIMessagePart.Image>() }.map { it.url }

    @Test
    fun `proactive request drops historical camera media and keeps ordinary attachments`() {
        val history = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(
                UIMessagePart.Text("look at this"),
                UIMessagePart.Image(cameraClip),
                UIMessagePart.Image(cameraSnapshot),
                UIMessagePart.Image(ordinaryImage),
                UIMessagePart.Document(ordinaryFile, "normal.jpg"),
            )),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("ok"))),
        )
        val request = buildProactiveRequestMessages(
            systemPrompt = "persona",
            historyMessages = history,
            summary = "earlier summary",
            context = "idle for two hours",
        )

        // The request-only wake-up turn still closes the turn as user.
        assertEquals(MessageRole.USER, request.last().role)
        assertTrue(request.last().toText().contains("<proactive_trigger>"))
        assertEquals(listOf(ordinaryImage), request.imageUrls())
        assertTrue(request.any { message -> message.parts.any { it is UIMessagePart.Document } })

        // The durable snapshot stays intact: only the provider request is cleaned.
        assertEquals(
            listOf(cameraClip, cameraSnapshot, ordinaryImage),
            listOf(history.first()).imageUrls(),
        )

        for (google in listOf(true, false)) {
            val payload = wire(request, google).toString()
            assertFalse(payload.contains("video_call_clips"))
            assertFalse(payload.contains("video_call_snapshots"))
            assertTrue("ordinary image must still be encoded for google=$google", payload.contains("b3JkaW5hcnktaW1hZ2U="))
        }
    }

    @Test
    fun `every tool round rebuilt from history is camera-free at the provider boundary`() = runBlocking {
        val taintedMetadata = JsonObject(mapOf("source" to JsonPrimitive(cameraClip)))
        val history = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(
                UIMessagePart.Text("look"),
                UIMessagePart.Image(cameraClip),
                UIMessagePart.Image(ordinaryImage, taintedMetadata),
                UIMessagePart.Image(cameraSnapshot),
            )),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                UIMessagePart.Tool("call_1", "lookup", "{}", output = listOf(
                    UIMessagePart.Image(cameraClip),
                    UIMessagePart.Image(ordinaryImage),
                    UIMessagePart.Text("done"),
                )),
            )),
        )
        val captured = mutableListOf<List<UIMessage>>()
        val boundary = CapturingProvider(captured).cameraFreeRequests()
        val params = TextGenerationParams(model = Model(modelId = "test-model"))
        val setting = ProviderSetting.OpenAI()

        boundary.generateText(setting, history, params)
        boundary.streamText(setting, history, params).toList()

        assertEquals(2, captured.size)
        captured.forEach { seen ->
            // Every user image is either a camera file or metadata-tainted, so none survives.
            assertTrue(seen.first().parts.none { it is UIMessagePart.Image })
            assertFalse(seen.any { it.parts.any { part -> part.metadata?.toString()?.contains("video_call_clips") == true } })
            val tool = seen.last().parts.filterIsInstance<UIMessagePart.Tool>().single()
            // Nested tool media is cleaned recursively; the ordinary attachment still gets through.
            assertEquals(listOf(ordinaryImage), tool.output.filterIsInstance<UIMessagePart.Image>().map { it.url })
            assertEquals("done", (tool.output.last() as UIMessagePart.Text).text)
            assertTrue(seen.any { it.parts.any { part -> part is UIMessagePart.Text && part.text == "look" } })
        }

        // Cleaning is request-only; the chat history keeps its camera frames for the UI.
        assertEquals(
            listOf(cameraClip, ordinaryImage, cameraSnapshot),
            listOf(history.first()).imageUrls(),
        )
        assertEquals(cameraClip, (history.last().parts.single() as UIMessagePart.Tool)
            .output.filterIsInstance<UIMessagePart.Image>().first().url)
    }

    @Test
    fun `declined voice call request drops historical camera media and keeps ordinary attachments`() {
        val history = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(
                UIMessagePart.Text("这是我视频里的样子"),
                UIMessagePart.Image(cameraSnapshot),
                UIMessagePart.Image(ordinaryImage),
                UIMessagePart.Image(ordinaryFile),
            )),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("我来打给你"))),
        )
        val event = "[系统事件] 你刚刚主动发起的语音通话邀请被用户直接挂断了（拒接，未接听）。"

        val request = buildVoiceCallDeclinedRequestMessages(
            systemPrompt = "persona",
            history = history,
            contextMessageSize = 20,
            summary = null,
            eventDescription = event,
        )

        assertEquals(MessageRole.USER, request.last().role)
        assertEquals(event, request.last().toText())
        assertEquals(listOf(ordinaryImage, ordinaryFile), request.imageUrls())
        // The user's own text meaning is preserved; only the camera pixels/path are withheld.
        assertTrue(request.toString().contains("这是我视频里的样子"))
        assertEquals(
            listOf(cameraSnapshot, ordinaryImage, ordinaryFile),
            listOf(history.first()).imageUrls(),
        )

        for (google in listOf(true, false)) {
            val payload = wire(request, google).toString()
            assertFalse(payload.contains("video_call_snapshots"))
            assertTrue(payload.contains("b3JkaW5hcnktaW1hZ2U="))
        }
    }

    private class CapturingProvider(
        private val captured: MutableList<List<UIMessage>>,
    ) : Provider<ProviderSetting> {
        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

        override suspend fun generateText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk {
            captured += messages
            return MessageChunk(id = "chunk", model = "test-model", choices = emptyList())
        }

        override suspend fun streamText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = flow {
            captured += messages
        }

        override suspend fun generateImage(
            providerSetting: ProviderSetting,
            params: ImageGenerationParams,
        ): ImageGenerationResult = error("not used")
    }
}
