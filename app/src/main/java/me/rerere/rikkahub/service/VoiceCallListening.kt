/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus

/**
 * 通话要不要继续听麦克风 —— **纯函数**，不碰 Android，也不碰协程。
 *
 * 单独成文件（而不是留在 VoiceCallMicrophone.kt 里）的原因：
 * `VoiceCallMicrophone` 依赖 ASR 运行态（Android/协程），任何要验证"无麦克风权限/音频不可用
 * 时不监听"的测试都只能连带整个麦克风实现一起 mock。把这条纯判断抽出来之后，
 * `VideoCallPolicy.shouldListen` 与 `VoiceCallPolicyTest` 可以在真正的 JVM 单测里跑。
 *
 * 保持原来的包名与 `internal` 可见性：同模块的调用点（`VoiceCallMicrophone`）不需要 import，
 * 跨包的 `VideoCallPolicy` 用的是模块内可见的顶层函数，也没有复制一份判断。
 */
internal fun voiceCallShouldListen(
    status: VoiceCallStatus,
    muted: Boolean,
    keepListeningDuringReply: Boolean,
): Boolean = !muted && when (status) {
    VoiceCallStatus.Listening -> true
    VoiceCallStatus.Processing, VoiceCallStatus.Speaking -> keepListeningDuringReply
    VoiceCallStatus.Idle, VoiceCallStatus.Error -> false
}
