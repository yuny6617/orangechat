package me.rerere.rikkahub.data.ai

import android.app.Application
import android.net.Uri
import android.util.Base64
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.ai.transformers.VoiceMessageTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getChatModelForAssistant
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.video.VideoVoiceAvailability
import me.rerere.rikkahub.data.video.VideoVoiceInputMode
import me.rerere.rikkahub.data.video.VideoVoicePolicy
import me.rerere.rikkahub.data.video.VideoVoiceRoute
import me.rerere.rikkahub.ui.hooks.recordedVoicePart
import me.rerere.rikkahub.ui.hooks.completedRecordingAfter
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.rikkahub.ui.hooks.DirectAudioRecorder
import me.rerere.rikkahub.testing.HostFileUriShadow
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class, shadows = [HostFileUriShadow::class])
class VoiceAudioPayloadTest {
    @Test fun routesGeminiThroughBothTransportsButRespectsAsrOverride() {
        val model = Model(modelId = "google/gemini-2.5-flash")
        assertTrue(model.supportsDirectAudio(ProviderSetting.Google()))
        assertTrue(model.supportsDirectAudio(ProviderSetting.OpenAI()))
        assertFalse(model.supportsDirectAudio(ProviderSetting.OpenAI(useResponseApi = true)))
        assertFalse(model.copy(audioInputMode = AudioInputMode.ASR).supportsDirectAudio(ProviderSetting.Google()))
        assertFalse(Model(modelId = "text-model").supportsDirectAudio(ProviderSetting.OpenAI()))
        assertTrue(Model(modelId = "audio-alias", audioInputMode = AudioInputMode.AUDIO)
            .supportsDirectAudio(ProviderSetting.OpenAI()))
    }

    @Test fun conversationModelWinsOverGlobalAssistantAndRespectsProviderOverrides() {
        val textModel = Model(modelId = "text-model")
        val audioModel = Model(modelId = "proxy-gemini-3.8-flash-high")
        val textAssistant = Assistant(chatModelId = textModel.id)
        val audioAssistant = Assistant(chatModelId = audioModel.id)
        val settings = Settings.dummy().copy(
            assistants = listOf(textAssistant, audioAssistant), assistantId = textAssistant.id,
            chatModelId = textModel.id,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(textModel, audioModel))),
        )
        val selected = requireNotNull(settings.getChatModelForAssistant(audioAssistant.id))
        assertEquals(audioModel, selected)
        assertTrue(selected.supportsDirectAudio(selected.findProvider(settings.providers)))
        val override = audioModel.copy(providerOverwrite = ProviderSetting.Google())
        val overriddenSettings = settings.copy(providers = listOf(
            ProviderSetting.OpenAI(models = listOf(override), useResponseApi = true)))
        assertTrue(override.supportsDirectAudio(override.findProvider(overriddenSettings.providers)))
        assertFalse(Model(modelId = "notgemini-model").supportsDirectAudio(ProviderSetting.OpenAI()))
        assertFalse(Model(modelId = "gemini-image").supportsDirectAudio(ProviderSetting.Google()))
    }

    @Test fun gptAudioFamiliesRouteThroughChatCompletionsAcrossSnapshotsAndNamespaces() {
        val chat = ProviderSetting.OpenAI()
        val responseApi = ProviderSetting.OpenAI(useResponseApi = true)
        // 官方名、日期快照、`-mini` 变体、命名空间前缀，以及大小写混写。
        listOf(
            "gpt-audio",
            "gpt-audio-mini",
            "gpt-audio-2025-08-28",
            "gpt-audio-mini-2025-10-06",
            "gpt-4o-audio-preview",
            "gpt-4o-mini-audio-preview",
            "gpt-4o-audio-preview-2024-12-17",
            "gpt-4o-mini-audio-preview-2024-12-17",
            "openai/gpt-audio",
            "openai/gpt-audio-mini-2025-10-06",
            "openai/gpt-4o-audio-preview-2024-12-17",
            "GPT-4O-MINI-AUDIO-PREVIEW-2024-12-17",
        ).forEach { modelId ->
            val model = Model(modelId = modelId)
            assertTrue("$modelId 走 ChatCompletions 应直传音频", model.supportsDirectAudio(chat))
            assertFalse("$modelId 不能走 Response API", model.supportsDirectAudio(responseApi))
            assertFalse("$modelId 不是 Google 原生音频模型", model.supportsDirectAudio(ProviderSetting.Google()))
        }
    }

    @Test fun ordinaryGptRealtimeTtsAndTranscribeModelsNeverBypassTranscription() {
        listOf(
            "gpt-4o",
            "gpt-4o-mini",
            "gpt-4.1",
            "gpt-realtime",
            "gpt-4o-realtime-preview",
            "gpt-4o-audio",
            "gpt-audio-realtime",
            "gpt-4o-transcribe",
            "gpt-4o-mini-transcribe",
            "gpt-4o-mini-tts",
            "tts-1",
            "whisper-1",
            "gpt-image-1",
            "my-gpt-audio",
            "gpt-4o-audio-preview-extra",
            "gpt-4o-audio-preview-2024-12-17-extra",
        ).forEach { modelId ->
            assertFalse("$modelId 不能收到 input_audio", Model(modelId = modelId).supportsDirectAudio(ProviderSetting.OpenAI()))
        }
        assertFalse(
            "图像模型即使叫 gpt-audio 也不算",
            Model(modelId = "gpt-audio", type = ModelType.IMAGE).supportsDirectAudio(ProviderSetting.OpenAI()),
        )
        assertFalse(
            "用户显式选了 ASR 就不再直传",
            Model(modelId = "gpt-audio", audioInputMode = AudioInputMode.ASR).supportsDirectAudio(ProviderSetting.OpenAI()),
        )
        // 显式 AUDIO 是用户自己的强制覆盖（兼容自定义代理别名），与 AUTO 的家族判断分开。
        assertTrue(
            Model(modelId = "gpt-4o", audioInputMode = AudioInputMode.AUDIO)
                .supportsDirectAudio(ProviderSetting.OpenAI()),
        )
    }

    @Test fun perModelTransportOverrideDecidesGptAudioSupport() {
        val model = Model(modelId = "gpt-4o-audio-preview-2024-12-17")
        // 父 provider 是 Response API：单看模型名不足以直传。
        val responseParent = ProviderSetting.OpenAI(models = listOf(model), useResponseApi = true)
        assertFalse(model.supportsDirectAudio(responseParent))

        // 模型级覆盖回 ChatCompletions：同一份设置里必须按覆盖后的真实通道判定。
        val forcedChat = model.copy(providerOverwrite = ProviderSetting.OpenAI(useResponseApi = false))
        val chatSettings = Settings.dummy().copy(
            providers = listOf(responseParent.copy(models = listOf(forcedChat))),
        )
        val resolvedChat = requireNotNull(forcedChat.findProvider(chatSettings.providers))
        assertTrue(resolvedChat is ProviderSetting.OpenAI && !resolvedChat.useResponseApi)
        assertTrue(forcedChat.supportsDirectAudio(resolvedChat))

        // 反过来：父 provider 正常、模型级覆盖成 Response API 时必须禁用直传。
        val forcedResponse = model.copy(providerOverwrite = ProviderSetting.OpenAI(useResponseApi = true))
        val responseSettings = Settings.dummy().copy(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(forcedResponse))),
        )
        val resolvedResponse = requireNotNull(forcedResponse.findProvider(responseSettings.providers))
        assertFalse(forcedResponse.supportsDirectAudio(resolvedResponse))
    }

    @Test fun autoVideoVoiceFollowsTheActualConversationModelCapability() {
        val gemini = Model(modelId = "google/gemini-2.5-flash")
        val gptAudio = Model(modelId = "gpt-4o-audio-preview-2024-12-17")
        val textGpt = Model(modelId = "gpt-4o")
        val geminiAssistant = Assistant(chatModelId = gemini.id)
        val gptAssistant = Assistant(chatModelId = gptAudio.id)
        val textAssistant = Assistant(chatModelId = textGpt.id)
        val settings = Settings(
            assistantId = geminiAssistant.id,
            assistants = listOf(geminiAssistant, gptAssistant, textAssistant),
            chatModelId = textGpt.id,
            providers = listOf(
                ProviderSetting.Google(models = listOf(gemini)),
                ProviderSetting.OpenAI(models = listOf(gptAudio, textGpt)),
            ),
        )

        fun conversation(assistant: Assistant, overrideModel: Model? = null) = Conversation(
            assistantId = assistant.id,
            messageNodes = emptyList(),
            config = overrideModel?.let { ConversationConfig(chatModelId = it.id) },
        )

        fun availability(target: Conversation, asrConfigured: Boolean = true): VideoVoiceAvailability {
            val model = requireNotNull(settings.getChatModelOf(target)) { "会话必须有可用模型" }
            val provider = requireNotNull(model.findProvider(settings.providers)) { "模型必须属于某个 provider" }
            return VideoVoicePolicy.resolve(
                mode = VideoVoiceInputMode.AUTO,
                directAudioSupported = model.supportsDirectAudio(provider),
                asrConfigured = asrConfigured,
                microphoneGranted = true,
            )
        }

        // Gemini 与 GPT 音频家族都要在 AUTO 下真的直传，而不是只认 Gemini。
        assertEquals(VideoVoiceRoute.DIRECT_AUDIO, availability(conversation(geminiAssistant)).route)
        assertEquals(VideoVoiceRoute.DIRECT_AUDIO, availability(conversation(gptAssistant)).route)
        // 普通 GPT 必须退回已配置的 ASR；没有 ASR 时明确禁用而不是假装能听。
        assertEquals(VideoVoiceRoute.ASR, availability(conversation(textAssistant)).route)
        val disabled = availability(conversation(textAssistant), asrConfigured = false)
        assertFalse(disabled.enabled)
        assertTrue(disabled.reason.contains("不支持音频直传"))
        assertTrue("说明里要提到真正支持的家族", disabled.reason.contains("GPT 音频模型"))

        // 会话自己的模型覆盖助手默认模型：同一助手切到文本模型，必须立刻按新能力裁决。
        assertEquals(VideoVoiceRoute.ASR, availability(conversation(geminiAssistant, overrideModel = textGpt)).route)
        assertEquals(VideoVoiceRoute.DIRECT_AUDIO, availability(conversation(textAssistant, overrideModel = gptAudio)).route)

        // 会话模型走 Response API 时，即使名字是 GPT 音频家族也只能退到 ASR。
        val responseSettings = settings.copy(
            providers = listOf(
                ProviderSetting.Google(models = listOf(gemini)),
                ProviderSetting.OpenAI(models = listOf(gptAudio, textGpt), useResponseApi = true),
            ),
        )
        val responseConversation = conversation(gptAssistant)
        val responseModel = requireNotNull(responseSettings.getChatModelOf(responseConversation))
        val responseProvider = requireNotNull(responseModel.findProvider(responseSettings.providers))
        assertFalse(responseModel.supportsDirectAudio(responseProvider))
        assertEquals(
            VideoVoiceRoute.ASR,
            VideoVoicePolicy.resolve(
                mode = VideoVoiceInputMode.AUTO,
                directAudioSupported = responseModel.supportsDirectAudio(responseProvider),
                asrConfigured = true,
                microphoneGranted = true,
            ).route,
        )

        // 用户显式 DIRECT 但不支持时，原因必须指向真实支持范围（Google Gemini / GPT 音频模型）。
        val directDisabled = VideoVoicePolicy.resolve(
            mode = VideoVoiceInputMode.DIRECT,
            directAudioSupported = false,
            asrConfigured = true,
            microphoneGranted = true,
        )
        assertFalse(directDisabled.enabled)
        assertTrue(directDisabled.reason.contains("不支持直接上传录音"))
        assertTrue(directDisabled.reason.contains("GPT 音频模型"))
    }

    @Test fun completionWaitsForIdleAndNeverReusesThePreviousRecording() {
        val previous = "/voice/old.wav"
        val next = "/voice/new.wav"
        assertNull(ASRState(audioFilePath = previous).completedRecordingAfter(previous))
        val saving = ASRState(status = ASRStatus.Stopping, audioFilePath = next)
        assertNull(saving.completedRecordingAfter(previous))
        assertEquals(next, saving.copy(status = ASRStatus.Idle).completedRecordingAfter(previous))
    }

    @Test fun asrModePreservesTranscriptWhileDirectRecordingsStayAudio() = runBlocking {
        val model = Model(modelId = "gemini-3.8-flash-high", audioInputMode = AudioInputMode.ASR)
        val settings = Settings.dummy().copy(providers = listOf(ProviderSetting.OpenAI(models = listOf(model))))
        val ctx = TransformerContext(RuntimeEnvironment.getApplication(), model, Assistant(), settings)
        val asr = recordedVoicePart("/voice.wav", 5000, "transcribed words", sendAudio = false)
        val transformed = VoiceMessageTransformer.transform(ctx, listOf(UIMessage.user("").copy(parts = listOf(asr))))
        assertEquals("transcribed words", transformed.single().toText())
        val direct = recordedVoicePart("/voice.wav", 5000, "ignored transcript", sendAudio = true)
        assertTrue(direct is UIMessagePart.Audio)
        // ASR-only requests disclose missing audio instead of sending unsupported input.
        val adapted = VoiceMessageTransformer.transform(ctx,
            listOf(UIMessage.user("").copy(parts = listOf(direct)))).single().parts.single()
        assertTrue(adapted is UIMessagePart.Text)
        assertTrue((adapted as UIMessagePart.Text).text.contains("没有转写文本"))
    }

    @Test fun switchingAwayFromGeminiDoesNotLetHistoricalAudioBlockNewText() = runBlocking {
        val source = UIMessage.user("").copy(parts = listOf(
            UIMessagePart.Audio("file:///old.m4a"),
            UIMessagePart.Audio("file:///old.mp3"),
            UIMessagePart.VoiceMessage("/old.m4a", 5000, "known transcript"),
        ))
        val next = UIMessage.user("继续聊天")
        val model = Model(modelId = "claude-opus-5-5")
        val settings = Settings.dummy().copy(providers = listOf(ProviderSetting.OpenAI(models = listOf(model))))
        val ctx = TransformerContext(RuntimeEnvironment.getApplication(), model, Assistant(), settings)
        val result = VoiceMessageTransformer.transform(ctx, listOf(source, next))
        assertEquals(next, result[1])
        assertTrue(result[0].parts.all { it is UIMessagePart.Text })
        assertTrue(result[0].toText().contains("known transcript"))
        assertTrue(source.parts[0] is UIMessagePart.Audio)
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildMessages", List::class.java, Model::class.java)
        method.isAccessible = true
        assertFalse((method.invoke(api, result, model) as JsonArray).toString().contains("input_audio"))
    }

    @Test fun sharedProactiveMediaPolicyCapsBothKindsWithoutMutatingHistory() = runBlocking {
        val model = Model(modelId = "gemini-3.8-flash-high", inputModalities = listOf(me.rerere.ai.provider.Modality.TEXT, me.rerere.ai.provider.Modality.IMAGE))
        val settings = Settings.dummy().copy(providers = listOf(ProviderSetting.Google(models = listOf(model))))
        val history = (1..3).map { index -> UIMessage.user("").copy(parts = listOf(
            UIMessagePart.Image("file:///upload/$index.png"),
            UIMessagePart.VoiceMessage("file:///upload/$index.wav", 1000, "transcript-$index"),
        )) }
        val ctx = TransformerContext(RuntimeEnvironment.getApplication(), model,
            Assistant(maxContextImages = 1, maxContextVoices = 1), settings)
        val result = me.rerere.rikkahub.data.ai.transformers.historyMediaTransformers.fold(history) { messages, transformer ->
            transformer.transform(ctx, messages)
        }
        assertEquals(1, result.flatMap { it.parts }.count { it is UIMessagePart.Image })
        assertEquals(1, result.flatMap { it.parts }.count { it is UIMessagePart.Audio })
        assertTrue(result.first().toText().contains("transcript-1"))
        assertEquals(3, history.flatMap { it.parts }.count { it is UIMessagePart.Image })
        assertEquals(3, history.flatMap { it.parts }.count { it is UIMessagePart.VoiceMessage })
    }

    @Test fun unsupportedOpenAiFormatsFallbackButNativeGoogleKeepsOriginalAudio() = runBlocking {
        val model = Model(modelId = "gemini-3.8-flash-high")
        val original = UIMessage.user("").copy(parts = listOf(
            UIMessagePart.Audio("file:///old.m4a"),
            UIMessagePart.VoiceMessage("/old.ogg", 5000, "fallback transcript"),
        ))
        suspend fun transform(provider: ProviderSetting): UIMessage {
            val settings = Settings.dummy().copy(providers = listOf(provider))
            return VoiceMessageTransformer.transform(
                TransformerContext(RuntimeEnvironment.getApplication(), model, Assistant(), settings), listOf(original)
            ).single()
        }
        val openai = transform(ProviderSetting.OpenAI(models = listOf(model)))
        assertTrue(openai.parts.all { it is UIMessagePart.Text })
        assertEquals("fallback transcript", (openai.parts[1] as UIMessagePart.Text).text)
        val google = transform(ProviderSetting.Google(models = listOf(model)))
        assertEquals(original.parts[0], google.parts[0])
        assertTrue(google.parts[1] is UIMessagePart.Audio)
    }

    @Test fun switchingModelsAlsoAdaptsAudioInsideToolResultsWithoutMutatingStoredAttachments() = runBlocking {
        val model = Model(modelId = "text-only")
        val settings = Settings.dummy().copy(providers = listOf(ProviderSetting.OpenAI(models = listOf(model))))
        val image = UIMessagePart.Image("file:///video/frame.jpg")
        val tool = UIMessagePart.Tool("video", "gongdu_read_video", "{}", listOf(
            UIMessagePart.Text("视频信息"), image,
            UIMessagePart.Audio("file:///video/original.wav"),
            UIMessagePart.VoiceMessage("/video/spoken.wav", transcript = "已知口播文字")),
            metadata = buildJsonObject { put("thoughtSignature", "keep-signature") })
        val source = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
        val result = VoiceMessageTransformer.transform(TransformerContext(RuntimeEnvironment.getApplication(), model,
            Assistant(), settings), listOf(source)).single().parts.single() as UIMessagePart.Tool
        assertEquals(tool.toolCallId, result.toolCallId)
        assertEquals(tool.input, result.input)
        assertEquals(tool.metadata, result.metadata)
        assertEquals(image, result.output[1])
        assertTrue((result.output[2] as UIMessagePart.Text).text.contains("没有转写文本"))
        assertEquals(UIMessagePart.Text("已知口播文字"), result.output[3])
        assertTrue(tool.output[2] is UIMessagePart.Audio)
        assertTrue(tool.output[3] is UIMessagePart.VoiceMessage)
    }

    @Test fun newestToolOriginalAudioSurvivesSharedHistoryLimitWithUserRecordings() = runBlocking {
        val model = Model(modelId = "gemini-2.5-flash")
        val settings = Settings.dummy().copy(providers = listOf(ProviderSetting.Google(models = listOf(model))))
        val latest = UIMessagePart.Audio("file:///video/newest.wav")
        val history = listOf(
            UIMessage.user("").copy(parts = listOf(UIMessagePart.VoiceMessage("/old.wav", transcript = "旧口播"))),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool("old", "gongdu_read_video", "{}",
                listOf(UIMessagePart.Audio("file:///video/old.wav"))))),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool("latest", "gongdu_read_video", "{}",
                listOf(UIMessagePart.Text("新视频"), latest)))),
        )
        val result = VoiceMessageTransformer.transform(TransformerContext(RuntimeEnvironment.getApplication(), model,
            Assistant(maxContextVoices = 1), settings), history)
        assertEquals(UIMessagePart.Text("旧口播"), result[0].parts.single())
        assertTrue((result[1].parts.single() as UIMessagePart.Tool).output.single() is UIMessagePart.Text)
        assertEquals(latest, (result[2].parts.single() as UIMessagePart.Tool).output.last())
        assertTrue((history[1].parts.single() as UIMessagePart.Tool).output.single() is UIMessagePart.Audio)
    }

    @Test fun toolAudioUsesTheSameFormatCompatibilityRulesAsUserRecordings() = runBlocking {
        val model = Model(modelId = "gemini-2.5-flash")
        val history = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool("video", "gongdu_read_video", "{}",
            listOf(UIMessagePart.Audio("file:///original.m4a"),
                UIMessagePart.VoiceMessage("/spoken.ogg", transcript = "回退文字"))))))
        suspend fun result(provider: ProviderSetting): List<UIMessagePart> {
            val settings = Settings.dummy().copy(providers = listOf(provider))
            return (VoiceMessageTransformer.transform(TransformerContext(RuntimeEnvironment.getApplication(), model,
                Assistant(), settings), history).single().parts.single() as UIMessagePart.Tool).output
        }
        val openAi = result(ProviderSetting.OpenAI(models = listOf(model)))
        assertTrue(openAi.all { it is UIMessagePart.Text })
        assertEquals(UIMessagePart.Text("回退文字"), openAi.last())
        val google = result(ProviderSetting.Google(models = listOf(model)))
        assertTrue(google.all { it is UIMessagePart.Audio })
    }

    @Test fun gptAudioKeepsRecordedWavWhileOrdinaryGptFallsBackToDisclosureText() = runBlocking {
        val part = UIMessagePart.Audio("file:///voice/clip.wav")
        val message = UIMessage.user("").copy(parts = listOf(part))
        suspend fun transform(model: Model): UIMessage {
            val settings = Settings.dummy().copy(providers = listOf(ProviderSetting.OpenAI(models = listOf(model))))
            return VoiceMessageTransformer.transform(
                TransformerContext(RuntimeEnvironment.getApplication(), model, Assistant(), settings),
                listOf(message),
            ).single()
        }

        // GPT 音频家族（含命名空间与日期快照）必须把 WAV 原样留给请求，而不是转成文字/占位。
        listOf(
            "gpt-audio",
            "gpt-audio-mini-2025-10-06",
            "openai/gpt-4o-mini-audio-preview-2024-12-17",
        ).forEach { modelId ->
            assertEquals("$modelId 必须保留原音频", part, transform(Model(modelId = modelId)).parts.single())
        }

        // 普通 GPT（文本/视觉）没有音频通道：只能明确告诉模型音频未提供，不能伪造逐字稿。
        val textOnly = transform(
            Model(
                modelId = "gpt-4o",
                inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
            ),
        )
        val adapted = textOnly.parts.single()
        assertTrue(adapted is UIMessagePart.Text)
        assertTrue((adapted as UIMessagePart.Text).text.contains("未提供给当前模型"))
        // 只改请求副本，历史里的音频不被就地改写。
        assertEquals(part, message.parts.single())
    }

    @Test fun wavBytesSurviveOpenAiAndGoogleMessageSerialization() = runBlocking {
        val bytes = DirectAudioRecorder.wavHeader(4) + byteArrayOf(0, 0, 1, 0)
        val file = File.createTempFile("voice", ".wav", RuntimeEnvironment.getApplication().cacheDir)
        try {
            file.writeBytes(bytes)
            val model = Model(modelId = "gemini-3.8-flash-high")
            val provider = ProviderSetting.OpenAI(models = listOf(model))
            val settings = Settings.dummy().copy(providers = listOf(provider))
            val ctx = TransformerContext(RuntimeEnvironment.getApplication(), model, Assistant(), settings)
            val recorded = recordedVoicePart(file.absolutePath, 5000, "must not replace audio", sendAudio = true)
            val input = UIMessage(role = MessageRole.USER, parts = listOf(
                UIMessagePart.Text("听听这段音频"), recorded))
            val message = VoiceMessageTransformer.transform(ctx, listOf(input)).single()
            val uploaded = input.copy(parts = listOf(
                UIMessagePart.Text("听听这段音频"), UIMessagePart.Audio(Uri.fromFile(file).toString())))
            assertEquals(uploaded, message)
            val legacy = input.copy(parts = listOf(UIMessagePart.VoiceMessage(
                url = file.absolutePath, duration = 5000, transcript = "must not replace audio")))
            assertEquals(recorded, VoiceMessageTransformer.transform(ctx, listOf(legacy)).single().parts.single())
            val openai = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
            val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildMessages", List::class.java, Model::class.java)
            method.isAccessible = true
            val messages = method.invoke(openai, listOf(message), Model(modelId = "gemini-2.5-flash")) as JsonArray
            val audio = messages[0].jsonObject["content"]!!.jsonArray[1].jsonObject
            assertEquals("input_audio", audio["type"]!!.jsonPrimitive.content)
            val data = audio["input_audio"]!!.jsonObject
            assertEquals("wav", data["format"]!!.jsonPrimitive.content)
            assertArrayEquals(bytes, Base64.decode(data["data"]!!.jsonPrimitive.content, Base64.DEFAULT))
            val google = GoogleProvider(OkHttpClient())
            val googleMethod = GoogleProvider::class.java.getDeclaredMethod("buildContents", List::class.java)
            googleMethod.isAccessible = true
            val contents = googleMethod.invoke(google, listOf(message)) as JsonArray
            val inline = contents[0].jsonObject["parts"]!!.jsonArray[1].jsonObject["inlineData"]!!.jsonObject
            assertEquals("audio/wav", inline["mimeType"]!!.jsonPrimitive.content)
            assertArrayEquals(bytes, Base64.decode(inline["data"]!!.jsonPrimitive.content, Base64.DEFAULT))
        } finally { file.delete() }
    }
}
