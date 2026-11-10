package me.rerere.rikkahub.data.ai

import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.StringLoader
import java.io.StringWriter
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.ai.transformers.*
import me.rerere.rikkahub.data.video.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Pebble initializes the production SLF4J Android backend (Log.isLoggable).
// Supply Android runtime rather than stubbing/removing the actual template/provider chain.
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28], manifest = Config.NONE)
class VideoGiftProviderChainTest {
    @Test
    fun `template stripping system used to lose gifts but post transform context reaches provider and queue`() = runBlocking {
        val setting = VideoCallSetting(giftsEnabled = true, giftPrompt = "送一场烟火陪我聊天。")
        val context = VideoCallPrompts.turnContext(videoCallContextFromSetting(
            setting, false, "no camera", false, false, false,
        ))
        val base = composeSystemPromptText("assistant", "ignored", false)
        // Same Pebble evaluation used by TemplateTransformer: a valid user template suppresses SYSTEM.
        val template = PebbleEngine.Builder().loader(StringLoader()).build()
            .getTemplate("{% if role != 'system' %}{{ message }}{% endif %}")
        fun transform(text: String): String = StringWriter().also {
            template.evaluate(it, mapOf("role" to "system", "message" to text))
        }.toString()
        val oldRequest = listOf(UIMessage(role = MessageRole.SYSTEM, parts = listOf(
            UIMessagePart.Text(transform(appendTurnSystemContext(base, context))),
        )), UIMessage.user("送烟火"))
        assertFalse(oldRequest.any { it.toText().contains("ws_gift") })
        val request = listOf(UIMessage(role = MessageRole.SYSTEM, parts = listOf(
            UIMessagePart.Text(transform(base)),
        )), UIMessage.user("送烟火")).withVideoTurnSystemContext(context)
        val queue = VideoGiftEffectQueue()
        val conversation = "r7-provider-chain"
        val token = "r7-turn"
        assertTrue(VideoCallRuntime.enterVideoCall(conversation))
        assertTrue(VideoCallRuntime.beginTurnRequest(conversation, token))
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            VideoCallRuntime.giftEvents.collect { event ->
                if (event.conversationId == conversation) queue.offer(event.id, event.gift)
            }
        }
        try {
            val provider = GiftProvider(context)
            val model = Model()
            val directive = VideoDirectiveRequest(true, conversation, token, VideoCallDirectiveSession())
            var observed = emptyList<UIMessage>()
            generateModelTurn(
                provider, ProviderSetting.OpenAI(), request, listOf(UIMessage.user("送烟火")),
                TextGenerationParams(model), model, stream = true, mergeUsage = true,
                onUpdateMessages = { messages ->
                    observed = VideoCallOutputTransformer.runStage(VideoDirectiveStage.Streaming, messages, directive)
                    // Display strips the tag; Finish must still use frozen raw intent.
                    observed = VideoCallOutputTransformer.runStage(VideoDirectiveStage.Display, observed, directive)
                },
            )
            val final = VideoCallOutputTransformer.runStage(VideoDirectiveStage.Finish, observed, directive)
            kotlinx.coroutines.yield()
            assertTrue(provider.called)
            assertEquals(1, final.last().annotations.filterIsInstance<UIMessageAnnotation.VideoCallGift>().size)
            assertFalse(final.last().toText().contains("ws_gift"))
            assertEquals(VideoGift.FIREWORKS, queue.poll()?.gift)
            VideoCallOutputTransformer.runStage(VideoDirectiveStage.Finish, final, directive)
            kotlinx.coroutines.yield()
            assertTrue(queue.isEmpty)
            assertEquals(1, VideoCallRuntime.tally().total)
        } finally {
            collector.cancelAndJoin()
            VideoCallRuntime.exitVideoCall(conversation)
        }
    }

    @Test
    fun `post transform append preserves literal schema after html escaping and null leaves ordinary path intact`() {
        val context = VideoCallPrompts.turnContext(VideoCallContext(cameraAvailable = false, giftsEnabled = true))
        val engine = PebbleEngine.Builder().loader(StringLoader()).autoEscaping(true).build()
        val escaped = StringWriter().also {
            engine.getTemplate("{{ message }}").evaluate(it, mapOf("message" to context))
        }.toString()
        assertFalse(escaped.contains("<ws_gift"))
        val transformed = listOf(UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text(escaped))))
        val fixed = transformed.withVideoTurnSystemContext(context)
        assertEquals(context, (fixed.single().parts.last() as UIMessagePart.Text).text)
        assertSame(transformed, transformed.withVideoTurnSystemContext(null))
        assertSame(transformed, transformed.withVideoTurnSystemContext(" "))
    }

    private class GiftProvider(private val expectedContext: String) : Provider<ProviderSetting> {
        var called = false
        override suspend fun streamText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): Flow<MessageChunk> = flow {
            called = true
            assertEquals(expectedContext, messages.first { it.role == MessageRole.SYSTEM }.parts.last().let { (it as UIMessagePart.Text).text })
            listOf("陪你看 ", "<ws_gift id=\"fire", "works\"/>").forEach { text ->
                emit(MessageChunk("chunk", "model", listOf(UIMessageChoice(
                    index = 0,
                    delta = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text))),
                    message = null, finishReason = null,
                ))))
            }
        }
        override suspend fun generateText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): MessageChunk = error("unused")
        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = error("unused")
        override suspend fun generateImage(providerSetting: ProviderSetting, params: ImageGenerationParams): ImageGenerationResult = error("unused")
    }
}
