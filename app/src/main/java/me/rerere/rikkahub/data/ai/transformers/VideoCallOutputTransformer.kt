/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage

/**
 * 视频通话指令（`<ws_gift id="..."/>` / `<ws_vsnap/>`）的唯一处理点。
 *
 * 三个阶段各自的责任（对应真实管线的调用顺序，见 [VideoDirectiveStage.plan]）：
 * 1. [transform]（`transforms`）：**只记录**——把**唯一能看到原始流**这一步的正文冻结成解析结果
 *    到本次请求的 [VideoCallDirectiveSession]，并**原样返回 messages**（绝不能在这里改正文：
 *    管线把它的结果当成后续分片合并的基准）；**不执行**；
 * 2. [visualTransform]（`visualTransforms`，只影响显示）：剥离协议（末尾半截先藏起来），不解析；
 * 3. [onGenerationFinish]（`onGenerationFinish`）：剥离协议并消费第 1 步冻结的解析结果执行一次。
 *
 * 这样即使正文在显示阶段就被删干净、或者普通 HTML 里恰好有 `<`，也不会让礼物/留影
 * 读不到（命中协议靠冻结结果），更不会把普通 HTML 当成协议执行。
 *
 * [videoDirectiveRequest] 是"把上下文压成这份请求快照"的唯一入口，
 * 单测可以直接喂同一份快照来驱动真实包装（[runStage]），而不是复制粘贴逻辑。
 */
object VideoCallOutputTransformer : OutputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = runStage(VideoDirectiveStage.Streaming, messages, ctx.videoDirectiveRequest())

    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = runStage(VideoDirectiveStage.Display, messages, ctx.videoDirectiveRequest())

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = runStage(VideoDirectiveStage.Finish, messages, ctx.videoDirectiveRequest())

    /** 真实包装的共用执行体：三个 override 都走同一条路径。 */
    internal fun runStage(
        stage: VideoDirectiveStage,
        messages: List<UIMessage>,
        request: VideoDirectiveRequest,
    ): List<UIMessage> = applyVideoCallDirectives(
        messages = messages,
        stage = stage,
        giftsEnabled = request.giftsEnabled,
        conversationId = request.conversationId,
        requestToken = request.requestToken,
        session = request.session,
        giftNames = request.giftNames,
    )
}

/** 一次生成请求里与视频指令有关的全部输入（从 [TransformerContext] 压出来）。 */
data class VideoDirectiveRequest(
    val giftsEnabled: Boolean,
    val conversationId: String?,
    val requestToken: String?,
    val session: VideoCallDirectiveSession?,
    /** 用户自定义的礼物展示名（写入礼物卡时按这份快照落库）。 */
    val giftNames: Map<String, String> = emptyMap(),
)

internal fun TransformerContext.videoDirectiveRequest(): VideoDirectiveRequest = VideoDirectiveRequest(
    giftsEnabled = settings.videoCallSetting.giftsEnabled,
    conversationId = conversationId,
    requestToken = requestToken,
    session = videoDirectives,
    giftNames = me.rerere.rikkahub.data.video.VideoGiftCustomization.sanitizedNames(
        settings.videoCallSetting.giftDisplayNames,
    ),
)
