/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class Model(
    val modelId: String = "",
    val displayName: String = "",
    val id: Uuid = Uuid.random(),
    val type: ModelType = ModelType.CHAT,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val abilities: List<ModelAbility> = emptyList(),
    val tools: Set<BuiltInTools> = emptySet(),
    val providerOverwrite: ProviderSetting? = null,
    // Discovered from Codex; optional for old configurations and manually added models.
    val codexReasoningEfforts: List<String> = emptyList(),
    val audioInputMode: AudioInputMode = AudioInputMode.AUTO,
    // Older saved models keep their existing capabilities until the user opts in.
    val capabilityMode: ModelCapabilityMode = ModelCapabilityMode.LEGACY,
    val capabilitySource: String? = null,
)

@Serializable
enum class ModelCapabilityMode { LEGACY, AUTO, MANUAL }

@Serializable
enum class ModelType {
    CHAT,
    IMAGE,
    EMBEDDING,
}

@Serializable
enum class Modality {
    TEXT,
    IMAGE,
}

@Serializable
enum class ModelAbility {
    TOOL,
    REASONING,
}

// 模型(提供商)提供的内置工具选项
@Serializable
sealed class BuiltInTools {
    // https://ai.google.dev/gemini-api/docs/google-search?hl=zh-cn
    @Serializable
    @SerialName("search")
    data object Search : BuiltInTools()

    // https://ai.google.dev/gemini-api/docs/url-context?hl=zh-cn
    @Serializable
    @SerialName("url_context")
    data object UrlContext : BuiltInTools()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : BuiltInTools()
}




@Serializable
enum class AudioInputMode { AUTO, AUDIO, ASR }

/**
 * 只有真正实现了音频 payload 的通道才允许绕开转写。
 *
 * 通道：
 * - [ProviderSetting.Google]：Gemini 原生 `inlineData`；
 * - [ProviderSetting.OpenAI]（且未使用 Response API）：`input_audio`，包括
 *   OpenAI 兼容通道上的 Gemini（既有支持，必须保留）。
 *
 * [AudioInputMode.AUTO] 只按**模型真实家族**判断，不看显示名：
 * - Gemini 聊天模型（`gemini-` / `gemini_`）；
 * - OpenAI 的 Chat Completions 音频家族：`gpt-audio` / `gpt-audio-mini` /
 *   `gpt-4o-audio-preview` / `gpt-4o-mini-audio-preview`，允许 `-YYYY-MM-DD`
 *   快照后缀和 `openai/` 这类命名空间前缀。
 *
 * 普通 GPT、Realtime、TTS/转写/图像模型一律**不**算——它们收到 `input_audio`
 * 会直接报错。显式 [AudioInputMode.AUDIO] 仍是用户自己的强制覆盖。
 */
fun Model.supportsDirectAudio(provider: ProviderSetting?): Boolean {
    if (type != ModelType.CHAT || audioInputMode == AudioInputMode.ASR) return false
    val transportSupportsAudio = provider is ProviderSetting.Google ||
        (provider is ProviderSetting.OpenAI && !provider.useResponseApi)
    if (!transportSupportsAudio) return false
    if (audioInputMode == AudioInputMode.AUDIO) return true
    if (listOf("embedding", "image", "tts", "live").any { it in modelId.lowercase() }) return false
    val id = modelId.lowercase()
    if (Regex("(^|[^a-z0-9])gemini[-_]").containsMatchIn(id)) return true
    // Audio-capable Chat Completions families only. Ordinary GPT and Realtime
    // models must not receive input_audio through an unsupported endpoint.
    return provider is ProviderSetting.OpenAI &&
        GPT_AUDIO_CHAT_COMPLETIONS_FAMILY.containsMatchIn(id)
}

/**
 * `gpt-audio`、`gpt-audio-mini`、`gpt-4o(-mini)-audio-preview`，可带日期快照后缀。
 *
 * 允许 `/` 命名空间（如 `openai/gpt-4o-audio-preview`），但要求模型名从命名空间
 * 边界开始且到结尾（或日期快照）为止：`my-gpt-audio`、`gpt-4o-audio-preview-extra`
 * 这类自定义/未知后缀不会被误判成官方音频模型。
 */
private val GPT_AUDIO_CHAT_COMPLETIONS_FAMILY =
    Regex("(^|/)(gpt-audio(?:-mini)?|gpt-4o(?:-mini)?-audio-preview)(?:-\\d{4}-\\d{2}-\\d{2})?$")
