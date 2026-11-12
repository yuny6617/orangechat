/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import kotlinx.serialization.Serializable

/**
 * 视频通话相关设置。
 *
 * 礼物默认关闭，由用户自行开启；不需要工具授权或人工审定。
 * 抓帧默认"连续画面（每秒）"，只影响下一轮请求，不会在后台自动发消息。
 *
 * [frameCaptureMode] 的默认值只作用于**新安装/旧数据缺字段**的情况：
 * 已经保存过 `every_reply` / `every_30s` / `every_60s` / `off` 的用户原样读回，
 * 不做迁移，避免把用户选过的旧模式悄悄改掉。
 *
 * [voiceInputMode] 是"我说一句"的路由偏好（默认 [VideoVoiceInputMode.AUTO]）：
 * 视频通话**不会**自动采音，一律由用户点那一次按钮开始、再点一次结束并发送；
 * 这个开关只决定那一段录音是直接发给模型，还是先转成文字。
 */
@Serializable
data class VideoCallSetting(
    val giftsEnabled: Boolean = false,
    val frameCaptureMode: VideoFrameCaptureMode = VideoFrameCaptureMode.CONTINUOUS_SEQUENCE,
    val frameQuality: VideoFrameQuality = VideoFrameQuality.P512,
    /** Most recent frames in the one-second rolling visual sequence, clamped to 1..9. */
    val rollingFrameCount: Int = 1,
    val showUserDanmaku: Boolean = true,
    val voiceInputMode: VideoVoiceInputMode = VideoVoiceInputMode.AUTO,
    /**
     * 礼物展示名（**有限映射**）：key 只能是 [VideoGiftCatalog] 里那 5 个稳定 id。
     *
     * 用户改的是"看起来叫什么"，不是协议本身：id、特效映射、白名单全部由程序固定，
     * 未知 key 一律被 [VideoGiftCustomization] 丢掉，永远不会把标签 id 变成用户随便写的未知值。
     * 空字符串表示"用内置名"。
     */
    val giftDisplayNames: Map<String, String> = emptyMap(),
    /**
     * 用户偏好的赠礼文案（只是措辞偏好，**不能**改变协议规则：仍然每条一次、只用合法 id、
     * 没有真实金额）。超长会被截断到 [VideoGiftCustomization.MAX_GIFT_MESSAGE_LENGTH]。
     */
    val giftMessage: String = "",
    /** 完整送礼提示词；空白使用内置全文。执行 id/schema、通话作用域与次数限制始终由程序校验。 */
    val giftPrompt: String = "",
    /**
     * 人工校准角度（0/90/180/270），同步应用到预览和发送的抓帧。
     *
     * null 表示自动选择 [PreviewCalibration.deviceDefault] 的窄范围设备建议，未知设备为 0°。
     * 显式角度始终覆盖自动建议；复位保存 0°，不会再次应用设备补偿。
     * 设备建议来自用户实测反馈，不代表已证实的 HAL 行为。
     *
     * 抓帧/落盘 JPEG 在传输前应用同一校准角度和当前预览镜像设置。
     */
    val previewCalibrationDegrees: Int? = null,
) {
    val giftsAvailable: Boolean
        get() = giftsEnabled
}
