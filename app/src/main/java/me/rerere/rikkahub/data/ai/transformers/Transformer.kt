/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant

class TransformerContext(
    val context: Context,
    val model: Model,
    val assistant: Assistant,
    val settings: Settings,
    val processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    val workspaceCwd: String? = null,
    /** 当前生成所属会话；视频通话的礼物/留影指令只在对应会话里生效。 */
    val conversationId: String? = null,
    /**
     * 本次生成请求的待执行协议原文。
     *
     * 真实管线先跑 `transforms`（Streaming：**只记录、不改正文**），再跑
     * `visualTransforms`（Display：剥离给界面）/`onGenerationFinish`（Finish：执行）；
     * 因此协议原文要在这里留到结束阶段才执行。由 GenerationHandler 按"每个生成轮次一个"
     * 创建，并在请求结束/取消时清空。
     */
    val videoDirectives: VideoCallDirectiveSession? = null,
    /**
     * 本次请求的**授权 token**（视频通话生成）。
     *
     * 礼物/留影不再靠“最后一条用户消息 ID”这种可复用的伪身份授权：token 由
     * VoiceCallService 在提交这一轮时生成，随队列项不可变地传到这里，
     * 只有“有效 call epoch + 这个 token”才允许执行。非通话请求拿不到 token。
     */
    val requestToken: String? = null,
)

interface MessageTransformer {
    /**
     * 消息转换器，用于对消息进行转换
     *
     * 对于输入消息，消息会转换被提供给API模块
     *
     * 对于输出消息，会对消息输出chunk进行转换
     */
    suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }
}

interface InputMessageTransformer : MessageTransformer

interface OutputMessageTransformer : MessageTransformer {
    /**
     * 一个视觉的转换，例如转换think tag为reasoning parts
     * 但是不实际转换消息，因为流式输出需要处理消息delta chunk
     * 不能还没结束生成就transform，因此提供一个visualTransform
     */
    suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }

    /**
     * 消息生成完成后调用
     */
    suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }
}

suspend fun List<UIMessage>.transforms(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
    processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    workspaceCwd: String? = null,
    conversationId: String? = null,
    videoDirectives: VideoCallDirectiveSession? = null,
    requestToken: String? = null,
): List<UIMessage> {
    val ctx = TransformerContext(
        context = context,
        model = model,
        assistant = assistant,
        settings = settings,
        processingStatus = processingStatus,
        workspaceCwd = workspaceCwd,
        conversationId = conversationId,
        videoDirectives = videoDirectives,
        requestToken = requestToken,
    )
    return transformers.fold(this) { acc, transformer ->
        transformer.transform(ctx, acc)
    }
}

suspend fun List<UIMessage>.visualTransforms(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
    conversationId: String? = null,
    videoDirectives: VideoCallDirectiveSession? = null,
    requestToken: String? = null,
): List<UIMessage> {
    val ctx = TransformerContext(
        context, model, assistant, settings,
        conversationId = conversationId,
        videoDirectives = videoDirectives,
        requestToken = requestToken,
    )
    return transformers.fold(this) { acc, transformer ->
        if (transformer is OutputMessageTransformer) {
            transformer.visualTransform(ctx, acc)
        } else {
            acc
        }
    }
}

suspend fun List<UIMessage>.onGenerationFinish(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
    conversationId: String? = null,
    videoDirectives: VideoCallDirectiveSession? = null,
    requestToken: String? = null,
): List<UIMessage> {
    val ctx = TransformerContext(
        context, model, assistant, settings,
        conversationId = conversationId,
        videoDirectives = videoDirectives,
        requestToken = requestToken,
    )
    return transformers.fold(this) { acc, transformer ->
        if (transformer is OutputMessageTransformer) {
            transformer.onGenerationFinish(ctx, acc)
        } else {
            acc
        }
    }
}
