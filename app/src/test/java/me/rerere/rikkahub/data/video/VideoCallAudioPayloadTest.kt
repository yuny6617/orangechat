/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import android.app.Application
import android.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.ai.transformers.VoiceMessageTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.testing.HostFileUriShadow
import me.rerere.rikkahub.ui.hooks.DirectAudioRecorder
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 真实 provider 请求构造里"音频 + 图片"同时存在时的 payload 回归。
 *
 * 为什么不能只测纯函数：`inlineData(audio/wav)`（Google）与
 * `input_audio{format:"wav"}`（OpenAI ChatCompletions）是既有 adapter 的编码结果，
 * 只有真的走一遍 `buildContents` / `buildMessages` 才能证明"这一段录音确实被当成音频发出去了"、
 * "WAV 字节一次都没被改写"、"锚点之外的任何历史消息都没有音频"。
 *
 * 这里**不联网、不读用户 key**：只是构造请求体。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class, shadows = [HostFileUriShadow::class])
class VideoCallAudioPayloadTest {

    private fun wavBytes(): ByteArray =
        DirectAudioRecorder.wavHeader(8) + ByteArray(8) { (it + 1).toByte() }

    /**
     * 把宿主文件变成 `file://` 地址。
     *
     * Android 的 `Uri.path` 永远以 `/` 开头，而 Windows 上的绝对路径是 `C:\...`：
     * 统一成正斜杠并保留盘符（`file:///C:/...`）之后，`Uri.parse(...).path` 得到的
     * `/C:/...` 在 Windows 上能落到同一个文件；POSIX 上就是普通的 `file:///tmp/...`。
     */
    private fun hostFileUrl(file: File): String {
        val normalized = file.absolutePath.replace('\\', '/')
        return if (Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
            "file:///$normalized"
        } else {
            "file://$normalized"
        }
    }

    private fun writeAudio(bytes: ByteArray): File {
        val directory = TransientAudioClip.directory(RuntimeEnvironment.getApplication().cacheDir)
        directory.mkdirs()
        return File(directory, TransientAudioClip.FILE_PREFIX + "payload.wav").apply { writeBytes(bytes) }
    }

    private fun writeImage(): File {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "frame.png")
        // 1x1 PNG（真实可解码的最小图，避免测试依赖宿主机的图形栈）。
        file.writeBytes(
            Base64.decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
                Base64.DEFAULT,
            ),
        )
        return file
    }

    /** 构造一轮真正要发出去的消息：历史两条 + 锚点那条（锚点上挂本轮音频与当前帧）。 */
    private fun request(
        audioUrl: String?,
        imageUrl: String?,
    ): Pair<List<UIMessage>, String> {
        val history = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("我们刚聊过")))
        val historyReply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("嗯嗯")))
        val anchor = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(VideoVoicePolicy.directVoicePlaceholder(4_000L))),
        )
        val anchorId = anchor.id.toString()
        val withAudio = listOf(history, historyReply, anchor)
            .withTransientRequestAudio(audioUrl, anchorId)
        val finalMessages = if (imageUrl == null) {
            withAudio
        } else {
            withAudio.mapIndexed { index, message ->
                if (index == withAudio.lastIndex) {
                    message.copy(parts = message.parts + UIMessagePart.Image(imageUrl))
                } else {
                    message
                }
            }
        }
        return finalMessages to anchorId
    }

    @Test
    fun `google request carries wav audio and the current frame in the same user turn`() {
        val bytes = wavBytes()
        val audio = writeAudio(bytes)
        val image = writeImage()
        val (messages, _) = request(hostFileUrl(audio), hostFileUrl(image))
        try {
            val contents = googleContents(messages)

            // 最后一条是锚点用户消息：它必须同时带上音频与画面。
            val anchorParts = contents.last().jsonObject["parts"]!!.jsonArray.map { it.jsonObject }
            val audioParts = anchorParts.filter { it["inlineData"] != null }
                .filter { it["inlineData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content.startsWith("audio/") }
            assertEquals("锚点这一轮必须带上一段音频", 1, audioParts.size)
            val inline = audioParts.single()["inlineData"]!!.jsonObject
            assertEquals("audio/wav", inline["mimeType"]!!.jsonPrimitive.content)
            assertArrayEquals(
                "WAV 字节必须原样发出去（16k/mono/PCM16）",
                bytes,
                Base64.decode(inline["data"]!!.jsonPrimitive.content, Base64.DEFAULT),
            )
            assertTrue(
                "同一轮还要带上当前画面",
                anchorParts.any {
                    it["inlineData"]?.jsonObject?.get("mimeType")?.jsonPrimitive?.content
                        ?.startsWith("image/") == true
                },
            )

            // 历史消息一条都不带音频（否则每轮都会把旧录音再 base64 一次）。
            contents.dropLast(1).forEach { content ->
                val parts = content.jsonObject["parts"]?.jsonArray ?: return@forEach
                assertFalse(
                    "历史消息里不许出现音频",
                    parts.any {
                        it.jsonObject["inlineData"]?.jsonObject
                            ?.get("mimeType")?.jsonPrimitive?.content?.startsWith("audio/") == true
                    },
                )
            }
        } finally {
            audio.delete()
            image.delete()
        }
    }

    @Test
    fun `openai chat completions request carries input_audio only in the anchor user message`() {
        val bytes = wavBytes()
        val audio = writeAudio(bytes)
        val image = writeImage()
        val (messages, _) = request(hostFileUrl(audio), hostFileUrl(image))
        try {
            val payload = openAiMessages(
                messages,
                // 视觉 + 音频都支持的模型：图片会不会被编码进请求由 inputModalities 决定。
                Model(
                    modelId = "gemini-2.5-flash",
                    inputModalities = listOf(me.rerere.ai.provider.Modality.TEXT, me.rerere.ai.provider.Modality.IMAGE),
                ),
            )

            val anchor = payload.last().jsonObject["content"]!!.jsonArray.map { it.jsonObject }
            val audioEntry = anchor.single { it["type"]?.jsonPrimitive?.content == "input_audio" }
            val inputAudio = audioEntry["input_audio"]!!.jsonObject
            assertEquals("wav", inputAudio["format"]!!.jsonPrimitive.content)
            assertArrayEquals(
                bytes,
                Base64.decode(inputAudio["data"]!!.jsonPrimitive.content, Base64.DEFAULT),
            )
            assertTrue(
                "同一轮还要带上当前画面",
                anchor.any { it["type"]?.jsonPrimitive?.content == "image_url" },
            )
            // 历史消息里没有 input_audio。
            assertEquals(
                1,
                payload.count { message ->
                    message.jsonObject["content"]?.let { content ->
                        runCatching {
                            content.jsonArray.any {
                                it.jsonObject["type"]?.jsonPrimitive?.content == "input_audio"
                            }
                        }.getOrDefault(false)
                    } == true
                },
            )
        } finally {
            audio.delete()
            image.delete()
        }
    }

    @Test
    fun `gpt audio model carries wav and the current frame in the same anchor`() {
        val bytes = wavBytes()
        val audio = writeAudio(bytes)
        val image = writeImage()
        val (messages, _) = request(hostFileUrl(audio), hostFileUrl(image))
        try {
            // 官方带日期快照，以及带命名空间前缀的 GPT 音频模型：都走真实 ChatCompletions 编码。
            // 图片是否随行由模型的 inputModalities 决定；这里显式开启视觉，覆盖"视频帧 + 本轮 WAV"
            // 同一锚点的 payload（模型是否真的接受图，属于能力元数据/服务端的事，不在本测试断言范围）。
            listOf(
                "gpt-4o-audio-preview-2024-12-17",
                "openai/gpt-audio-mini-2025-10-06",
            ).forEach { modelId ->
                val payload = openAiMessages(
                    messages,
                    Model(
                        modelId = modelId,
                        inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
                    ),
                )

                val anchor = payload.last().jsonObject["content"]!!.jsonArray.map { it.jsonObject }
                val audioEntries = anchor.filter { it["type"]?.jsonPrimitive?.content == "input_audio" }
                assertEquals("$modelId 的锚点必须恰好一段音频", 1, audioEntries.size)
                val inputAudio = audioEntries.single()["input_audio"]!!.jsonObject
                assertEquals("wav", inputAudio["format"]!!.jsonPrimitive.content)
                assertArrayEquals(
                    "$modelId 的 WAV 字节必须原样发出去",
                    bytes,
                    Base64.decode(inputAudio["data"]!!.jsonPrimitive.content, Base64.DEFAULT),
                )
                assertTrue(
                    "$modelId 同一轮还要带上当前画面",
                    anchor.any { it["type"]?.jsonPrimitive?.content == "image_url" },
                )
                assertEquals(
                    "$modelId 整份请求只有锚点带音频",
                    1,
                    payload.count { message ->
                        message.jsonObject["content"]?.let { content ->
                            runCatching {
                                content.jsonArray.any {
                                    it.jsonObject["type"]?.jsonPrimitive?.content == "input_audio"
                                }
                            }.getOrDefault(false)
                        } == true
                    },
                )
            }
        } finally {
            audio.delete()
            image.delete()
        }
    }

    @Test
    fun `gpt audio without vision capability keeps wav and never attaches a frame`() {
        val bytes = wavBytes()
        val audio = writeAudio(bytes)
        val image = writeImage()
        val (messages, _) = request(hostFileUrl(audio), hostFileUrl(image))
        try {
            // 官方 GPT 音频模型（gpt-audio / gpt-audio-mini）只声明 text+audio：
            // 此时 WAV 照常发，画面必须被能力过滤掉，而不是赌服务端会忽略 image_url。
            val payload = openAiMessages(messages, Model(modelId = "openai/gpt-audio-mini-2025-10-06"))
            val anchor = payload.last().jsonObject["content"]!!.jsonArray.map { it.jsonObject }
            val inputAudio = anchor.single { it["type"]?.jsonPrimitive?.content == "input_audio" }
                .getValue("input_audio").jsonObject
            assertEquals("wav", inputAudio["format"]!!.jsonPrimitive.content)
            assertArrayEquals(
                bytes,
                Base64.decode(inputAudio["data"]!!.jsonPrimitive.content, Base64.DEFAULT),
            )
            assertFalse(
                "文本+音频模型不能收到 image_url",
                anchor.any { it["type"]?.jsonPrimitive?.content == "image_url" },
            )
        } finally {
            audio.delete()
            image.delete()
        }
    }

    @Test
    fun `ordinary gpt never carries input_audio after the request transformer runs`() = runBlocking {
        val audio = writeAudio(wavBytes())
        val image = writeImage()
        try {
            val model = Model(
                modelId = "gpt-4o",
                inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
            )
            val settings = Settings.dummy().copy(
                providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
            )
            val source = UIMessage(
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Text("听听这段"),
                    UIMessagePart.Audio(hostFileUrl(audio)),
                    UIMessagePart.Image(hostFileUrl(image)),
                ),
            )
            val transformed = VoiceMessageTransformer.transform(
                TransformerContext(RuntimeEnvironment.getApplication(), model, Assistant(), settings),
                listOf(source),
            )
            val payload = openAiMessages(transformed, model)
            assertFalse(
                "普通 GPT 的 payload 里不允许出现 input_audio",
                payload.toString().contains("input_audio"),
            )
            val text = transformed.flatMap { it.parts }
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
            assertTrue(
                "音频没有提供时必须明确告诉模型，而不是让它假装听过",
                text.contains("未提供给当前模型"),
            )
            // 只改发出去的副本：UI/历史里那条音频不能被就地改写。
            assertTrue(source.parts[1] is UIMessagePart.Audio)
        } finally {
            audio.delete()
            image.delete()
        }
    }

    @Test
    fun `asr route sends real text only and never invents audio`() {
        // ASR 路线：这一轮根本没有音频，只有真实转写文字。
        val (messages, _) = request(audioUrl = null, imageUrl = null)
        val model = Model(modelId = "gemini-2.5-flash")
        assertTrue(
            "没有音频时整份请求里不许有 audio part",
            messages.flatMap { it.parts }.none { it is UIMessagePart.Audio },
        )
        assertFalse(googleContents(messages).toString().contains("audio/"))
        assertFalse(openAiMessages(messages, model).toString().contains("input_audio"))
        assertTrue(
            "文字必须还在",
            messages.last().parts.filterIsInstance<UIMessagePart.Text>().single().text
                .contains("仅本轮提供"),
        )
    }

    @Test
    fun `same request tool rounds reuse owned audio only on the original anchor`() {
        val bytes = wavBytes()
        val audio = writeAudio(bytes)
        val clip = requireNotNull(TransientAudioClip.adopt(audio, audio.parentFile!!, 4_000L))
        val owner = TransientAudioClip.RequestOwnership(clip)
        try {
            assertTrue(owner.beginRequest())
            val url = hostFileUrl(audio)
            val (messages, anchorId) = request(url, null)
            var rounds = messages
            repeat(3) { round ->
                rounds = (rounds + UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text("工具返回 $round")),
                )).withTransientRequestAudio(url, anchorId)
                assertFalse("工具轮次中队列不能删除录音", clip.release(fromRequest = false))
                assertTrue(audio.exists())
                assertEquals(1, rounds.flatMap { it.parts }.filterIsInstance<UIMessagePart.Audio>().size)
                assertTrue(rounds.last().parts.none { it is UIMessagePart.Audio })
                val googleAudio = googleContents(rounds).flatMap { content ->
                    content.jsonObject["parts"]!!.jsonArray.mapNotNull { part ->
                        part.jsonObject["inlineData"]?.jsonObject
                            ?.takeIf { it["mimeType"]!!.jsonPrimitive.content.startsWith("audio/") }
                    }
                }
                assertEquals(1, googleAudio.size)
                assertArrayEquals(
                    bytes,
                    Base64.decode(googleAudio.single()["data"]!!.jsonPrimitive.content, Base64.DEFAULT),
                )
                val openAi = openAiMessages(rounds, Model(modelId = "gemini-2.5-flash"))
                val openAiAudio = openAi.flatMap { message ->
                    val content = message.jsonObject["content"]
                    if (content is JsonArray) content.mapNotNull { part ->
                        part.jsonObject.takeIf { it["type"]?.jsonPrimitive?.content == "input_audio" }
                    } else emptyList()
                }
                assertEquals(1, openAiAudio.size)
                assertArrayEquals(
                    bytes,
                    Base64.decode(openAiAudio.single()["input_audio"]!!.jsonObject["data"]!!.jsonPrimitive.content, Base64.DEFAULT),
                )
            }
        } finally {
            owner.release()
        }
        assertFalse("全部工具轮次结束才删文件", audio.exists())
    }

    // ------------------------------------------------------------------
    // provider 私有构造函数的取用（与既有 VoiceAudioPayloadTest 同一做法）
    // ------------------------------------------------------------------

    private fun googleContents(messages: List<UIMessage>): JsonArray {
        val google = GoogleProvider(OkHttpClient())
        val method = GoogleProvider::class.java.getDeclaredMethod("buildContents", List::class.java)
        method.isAccessible = true
        return try {
            method.invoke(google, messages) as JsonArray
        } catch (error: java.lang.reflect.InvocationTargetException) {
            // 把 provider 内部的真实报错透出来（否则只会看到反射壳子）。
            throw error.targetException
        }
    }

    private fun openAiMessages(messages: List<UIMessage>, model: Model): JsonArray {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val method = ChatCompletionsAPI::class.java
            .getDeclaredMethod("buildMessages", List::class.java, Model::class.java)
        method.isAccessible = true
        return try {
            method.invoke(api, messages, model) as JsonArray
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw error.targetException
        }
    }
}
