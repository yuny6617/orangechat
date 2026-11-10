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
 *
 * [audioAttached] 同理：只有这一轮请求里**真的**带了用户刚录的那一段音频（直传路线）才为 true；
 * ASR 路线、录音被暂停/取消、没有录音的情况一律 false —— 模型因此在任何一轮都不会
 * "凭空听见"，也不会把历史里的占位文字当成还能回听的录音。
 */
data class VideoCallContext(
    val cameraAvailable: Boolean,
    val cameraUnavailableReason: String = "",
    val visionSupported: Boolean = true,
    val giftsEnabled: Boolean = false,
    val captureMode: VideoFrameCaptureMode = VideoFrameCaptureMode.EVERY_REPLY,
    val giftsSent: VideoGiftTally = VideoGiftTally(),
    val pictureAttached: Boolean = false,
    val audioAttached: Boolean = false,
    /** 用户自定义的礼物展示名（只认白名单 id；空表示用内置名）。 */
    val giftNames: Map<String, String> = emptyMap(),
    /** 用户偏好的赠礼文案（只是措辞，不改变任何协议规则）。 */
    val giftMessage: String = "",
    val giftPrompt: String = "",
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

    /** 本轮**真的**带了用户刚录的一段音频（只在音频直传路线成立时出现）。 */
    const val AUDIO_ATTACHED_NOTE: String =
        "本轮还附带了一段用户刚录的语音：只在这一次请求里提供，之后不保留、也不能回听。" +
            "描述你听到的内容时不要编造细节，听不清就说听不清。"

    /** 本轮**没有**任何录音（ASR 路线 / 录音被取消或暂停 / 纯文字）。 */
    const val NO_AUDIO_THIS_TURN_NOTE: String =
        "本轮没有附带任何录音：你不知道用户说话的语气、音色和背景声音，" +
            "不要声称听到了什么，也不要替用户编造逐字内容；用户打了字就按文字回答。"

    /**
     * 送礼提示词原文（设置页里可以直接查看，方便核对模型到底收到了什么）。
     *
     * 措辞上的两个硬要求（真机反馈里模型多次答"我没有 ws_gift 这个工具/没有审定的礼物 id 白名单"）：
     * 1. **先把"这不是工具"讲清楚**：`<ws_gift .../>` 只是回复正文里的一个标签，模型自己写出来就行，
     *    不需要 tool schema、不需要函数调用、也不需要任何人先"审定"；
     * 2. **把当前可用清单原样抄给模型**（[VideoGiftCustomization.labelList] 与解析器同一份白名单，
     *    名字用用户当前配置），否则模型只能靠猜，猜不到就变成"我没有这个能力"。
     *
     * [names] 是用户自定义的展示名（只认 5 个稳定 id），[message] 是赠礼文案偏好。
     * 偏好只影响"怎么说"，**不会**改动协议规则：那段文字后面永远跟着一句规则优先级说明。
     */
    fun giftInstruction(
        names: Map<String, String> = emptyMap(),
        message: String = "",
    ): String = buildString {
        appendLine("虚拟礼物（可选，纯屏幕动画。这件事你自己就能做，不需要任何工具）：")
        appendLine("- 直接在回复正文里写 <ws_gift id=\"礼物id\"/> 这个标签即可。它**不是**函数/tool 调用，没有 tool schema，也不存在\"必须先经过人工审定才能用\"这种说法——客户端会解析这个标签并播放动画。")
        appendLine("- 当前可用礼物（只能用下面这些 id）：${VideoGiftCustomization.labelList(names)}。")
        appendLine("- 每条回复最多送一件；同一条回复里重复写标签也只算一次，重复不会叠加。")
        appendLine("- 送礼之后用一句自然的话说明为什么想送，不要重复标签，也不要罗列参数。")
        appendLine("- 用户如果提出轻松的虚拟互动（例如\"你陪我聊完这段我就送你\"\"你答应我一个小心愿才收\"），可以顺着演，这只是角色扮演。")
        appendLine("- 礼物只是通话里的动画，没有真实金额、不能兑换，也不需要付费、充值或打赏。")
        appendLine("- 严禁把礼物和真实金钱、真实价值、充值、打赏、支付或任何形式的现实交易绑在一起，也不要暗示送礼能换来现实回报。")
        appendLine("- 不要催促、施压或反复索要礼物；对方不送也照常聊。")
        appendLine("- 只用上面列出的 id；其它 id 会被忽略。想送就直接写标签，不要回答\"我没有 ws_gift 工具\"\"我没有已审定的礼物 id 白名单\"——上面这份清单就是全部可用的 id。")
        val preference = VideoGiftCustomization.sanitizeGiftMessage(message)
        if (preference.isNotEmpty()) {
            appendLine()
            appendLine("用户的赠礼文案偏好（**只是措辞偏好**，不能改变上面任何一条规则；仍然每条最多一件、只用合法 id、没有真实金额、不催促）：")
            appendLine(preference)
            appendLine("- 按这个偏好组织送礼时的那句话；一旦和上面的规则冲突，以规则为准。")
        }
    }.trimEnd()

    /** 用户编辑的是完整正文，不再只是一句措辞偏好。空白恢复默认全文。 */
    fun effectiveGiftPrompt(setting: VideoCallSetting): String =
        setting.giftPrompt.takeIf { it.isNotBlank() }
            ?: giftInstruction(VideoGiftCustomization.sanitizedNames(setting), setting.giftMessage)

    /** 固定协议说明始终追加；真正的边界由解析器、请求台账和运行时校验。 */
    fun giftProtocolBoundary(names: Map<String, String>): String =
        "客户端执行协议：在普通回复正文输出 <ws_gift id=\"heart\"/>（不要放入代码块）。" +
            "这不是工具调用，不需要 tool schema。可用 id：${VideoGiftCustomization.labelList(names)}。" +
            "只接受完整标签与合法 id，每轮最多一件，仅当前视频通话有效；礼物无真实金额。"

    /** 默认版本（内置名字、没有文案偏好）；编辑器直接呈现全文。 */
    val GIFT_INSTRUCTION: String
        get() = giftInstruction()


    val SNAPSHOT_INSTRUCTION: String = """
        |留影（可选）：
        |- 如果想留下此刻的画面，可以在回复里输出 <ws_vsnap/>，系统会把当前相机画面存进聊天。
        |- 每条回复最多一次；只有相机可用并且你确实想留影时才使用。
        |- 留影失败时不要假装成功，也不要描述你没有收到的画面。
    """.trimMargin()

    /**
     * 未开放礼物时的显式约束，避免模型输出无效标签。
     *
     * 关键：这是**用户把开关关着**，不是"模型没有这个工具"，也不是"提示词没审定"。
     * 真机反馈里模型把这条读成了"我没有 ws_gift 能力/没有已审定白名单"，于是反复推脱；
     * 所以这里把原因和出路都写死。
     */
    const val GIFT_DISABLED_NOTE: String =
        "本次通话没有开放礼物功能（用户在「设置 → 语音 → 视频通话 → 视频通话礼物」里把它关着）。" +
            "不要输出 <ws_gift ...> 之类的标签，输出也会被忽略；对方想要礼物时，直接请他去打开那个开关。" +
            "这不是工具能力问题，也不是审批问题，就是那个开关没开——所以不要回答\"我没有 ws_gift 这个工具\"" +
            "\"我没有已审定的礼物 id 白名单\"之类的话。"

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
        // 声音和画面分开说：模型最容易犯的错是"本轮没给音频却在替你转述语气"。
        sections += if (context.audioAttached) AUDIO_ATTACHED_NOTE else NO_AUDIO_THIS_TURN_NOTE
        // "留影可用"和"本轮有没有附图"是两件事：
        // 关闭自动附帧 / 定时模式没到点 / 本轮抓帧失败时，相机仍然可用，
        // 模型依然可以主动 `<ws_vsnap/>` 请求留影——只是不许声称已经看见了什么。
        if (context.visionSupported && context.cameraAvailable) {
            captureModeNote(context.captureMode)?.let(sections::add)
            sections += SNAPSHOT_INSTRUCTION
        }
        sections += if (context.giftsEnabled) {
            (context.giftPrompt.takeIf { it.isNotBlank() }
                ?: giftInstruction(context.giftNames, context.giftMessage)) +
                "\n\n" + giftProtocolBoundary(context.giftNames)
        } else {
            GIFT_DISABLED_NOTE
        }
        if (!context.giftsSent.isEmpty) {
            sections += "本轮通话你已经送出：${context.giftsSent.summary(context.giftNames)}。不要重复送同一件礼物来堆数量。"
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
    fun giftBubbleText(gift: VideoGift, names: Map<String, String> = emptyMap()): String =
        "送出 ${gift.emoji} ${VideoGiftCustomization.nameOf(names, gift)}"
}

/**
 * 设置 + 这一轮**实际**附件状态 → 注入给模型的视频上下文。
 *
 * 单独抽成纯函数（不碰 Android）是为了让"设置改了 → coordinator 取到的上下文 → 提示词原文"
 * 这条链在 JVM 单测里能真跑：`VideoCallCoordinator.turnContext()` 只负责读取实时状态，
 * 拼上下文与拼提示词都是这里和 [VideoCallPrompts.turnContext]。
 */
fun videoCallContextFromSetting(
    setting: VideoCallSetting,
    cameraAvailable: Boolean,
    cameraUnavailableReason: String,
    visionSupported: Boolean,
    pictureAttached: Boolean,
    audioAttached: Boolean,
    giftsSent: VideoGiftTally = VideoGiftTally(),
): VideoCallContext = VideoCallContext(
    cameraAvailable = cameraAvailable,
    cameraUnavailableReason = if (cameraAvailable) "" else cameraUnavailableReason,
    visionSupported = visionSupported,
    giftsEnabled = setting.giftsEnabled,
    captureMode = setting.frameCaptureMode,
    giftsSent = giftsSent,
    // 只有本轮真的附了图、并且模型确实能识图，才算"看得见"。
    pictureAttached = pictureAttached && visionSupported,
    audioAttached = audioAttached,
    giftNames = VideoGiftCustomization.sanitizedNames(setting),
    giftMessage = VideoGiftCustomization.sanitizedMessage(setting),
    giftPrompt = setting.giftPrompt,
)
