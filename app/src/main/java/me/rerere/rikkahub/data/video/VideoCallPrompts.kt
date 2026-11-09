/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

/**
 * 视频通话注入给模型的上下文。
 *
 * 全部是纯文本拼接，没有 Android 依赖，便于单测逐字核对：
 * 礼物提示词在设置里默认关闭，且原文可以直接抄进人工审查清单。
 *
 * [pictureAttached] 是**本轮请求实际有没有带上画面**（抓帧/压缩失败时会 false），
 * 不是"相机理论可用"。只有它为 true 时模型才可以声称自己看到了东西。
 */
data class VideoCallContext(
    val cameraAvailable: Boolean,
    val cameraUnavailableReason: String = "",
    val visionSupported: Boolean = true,
    val giftsEnabled: Boolean = false,
    val captureMode: VideoFrameCaptureMode = VideoFrameCaptureMode.EVERY_REPLY,
    val giftsSent: VideoGiftTally = VideoGiftTally(),
    val pictureAttached: Boolean = false,
) {
    /** 只有本轮真的附了图、并且模型确实能识图，才算"看得见"。 */
    val canSeeUser: Boolean
        get() = pictureAttached && visionSupported
}

object VideoCallPrompts {
    const val SECTION_TITLE: String = "【视频通话】"

    const val CAMERA_UNAVAILABLE_NOTE: String =
        "当前视频通话的相机不可用（原因：{reason}）。你收不到任何画面，只能听到声音、读到文字；不要声称看见了对方或环境。"

    const val VISION_UNAVAILABLE_NOTE: String =
        "当前模型不支持图片输入，相机画面不会传给你；不要声称看见了对方或环境，也不要描述画面内容。"

    /** 相机开着，但本轮确实没有新画面（抓帧失败/定时模式没到点/关闭自动附帧）。 */
    const val NO_PICTURE_THIS_TURN_NOTE: String =
        "本轮没有附带任何画面（没有可用的新照片）。你只能依据文字和声音回答，不要描述对方的样子或环境，也不要假装刚才看到了。"

    /** 人工审查用：完整送礼提示词原文（设置里默认关闭，只有用户主动开启后才注入）。 */
    val GIFT_INSTRUCTION: String = """
        |虚拟礼物（可选，纯屏幕动画）：
        |- 如果想表达心情，可以在回复里送一件虚拟礼物，格式为 <ws_gift id="礼物id"/>。
        |- 可用礼物：heart（小心心）、bouquet（花束）、fireworks（夏日烟火）、meteor（流星雨）、galaxy_train（银河铁道之夜）。
        |- 每条回复最多送一件；同一条回复里重复写标签也只算一次。
        |- 送礼之后请用一句自然的话说明为什么想送，不要重复标签，也不要罗列参数。
        |- 用户如果提出轻松的虚拟互动（例如"你陪我聊完这段我就送你""你答应我一个小心愿才收"），可以顺着演，这只是角色扮演。
        |- 礼物只是通话里的动画，没有真实金额、不能兑换，也不需要付费、充值或打赏。
        |- 严禁把礼物和真实金钱、真实价值、充值、打赏、支付或任何形式的现实交易绑在一起，也不要暗示送礼能换来现实回报。
        |- 不要催促、施压或反复索要礼物；对方不送也照常聊。
        |- 只使用上面列出的 id；其它 id 会被忽略，也不要发明新的礼物格式。
    """.trimMargin()

    val SNAPSHOT_INSTRUCTION: String = """
        |留影（可选）：
        |- 如果想留下此刻的画面，可以在回复里输出 <ws_vsnap/>，系统会把当前相机画面存进聊天。
        |- 每条回复最多一次；只有相机可用并且你确实想留影时才使用。
        |- 留影失败时不要假装成功，也不要描述你没有收到的画面。
    """.trimMargin()

    /** 未开放礼物时的显式约束，避免模型输出无效标签。 */
    const val GIFT_DISABLED_NOTE: String =
        "本次通话没有开放礼物功能；不要输出 <ws_gift ...> 之类的标签，即使输出也会被忽略。"

    /** 一轮请求的完整注入文本；不需要注入时返回空串。 */
    fun turnContext(context: VideoCallContext): String {
        val sections = mutableListOf<String>()
        sections += buildString {
            append(SECTION_TITLE)
            append('\n')
            when {
                context.pictureAttached -> {
                    append("- 这是视频通话：用户已同意使用相机，本轮附带了当前相机画面。")
                }
                context.cameraAvailable -> {
                    append("- 这是视频通话：相机在工作，但")
                    append(NO_PICTURE_THIS_TURN_NOTE)
                }
                else -> {
                    append("- ")
                    append(cameraUnavailableText(context.cameraUnavailableReason))
                }
            }
        }
        if (!context.visionSupported) {
            sections += VISION_UNAVAILABLE_NOTE
        }
        // "留影可用"和"本轮有没有附图"是两件事：
        // 关闭自动附帧 / 定时模式没到点 / 本轮抓帧失败时，相机仍然可用，
        // 模型依然可以主动 `<ws_vsnap/>` 请求留影——只是不许声称已经看见了什么。
        if (context.visionSupported && context.cameraAvailable) {
            captureModeNote(context.captureMode)?.let(sections::add)
            sections += SNAPSHOT_INSTRUCTION
        }
        sections += if (context.giftsEnabled) GIFT_INSTRUCTION else GIFT_DISABLED_NOTE
        if (!context.giftsSent.isEmpty) {
            sections += "本轮通话你已经送出：${context.giftsSent.summary()}。不要重复送同一件礼物来堆数量。"
        }
        return sections.joinToString("\n\n")
    }

    fun cameraUnavailableText(reason: String): String =
        CAMERA_UNAVAILABLE_NOTE.replace("{reason}", reason.ifBlank { "相机不可用" })

    fun captureModeNote(mode: VideoFrameCaptureMode): String? = when (mode) {
        VideoFrameCaptureMode.OFF -> "本次通话不会自动附带画面；你可以主动用 <ws_vsnap/> 请求留影。"
        VideoFrameCaptureMode.EVERY_REPLY -> "每轮对话会附带一张当前画面，可能不是你此刻看到的样子，不要过度推断细节。"
        VideoFrameCaptureMode.EVERY_30S, VideoFrameCaptureMode.EVERY_60S ->
            "画面约每${mode.intervalMillis?.div(1000) ?: 30}秒刷新一次，只有轮到下一轮对话时才会传给你。"
    }

    /** 通知栏/气泡里用的简短状态文案。 */
    fun giftBubbleText(gift: VideoGift): String = "送出 ${gift.emoji} ${gift.displayName}"
}
