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
 * 礼物默认关闭：功能与提示词在被人类审定之前不会主动启用（设置页里写明了原因）。
 * 抓帧默认"每轮一帧"，只影响下一轮请求，不会在后台自动发消息。
 */
@Serializable
data class VideoCallSetting(
    val giftsEnabled: Boolean = false,
    val frameCaptureMode: VideoFrameCaptureMode = VideoFrameCaptureMode.EVERY_REPLY,
    val frameQuality: VideoFrameQuality = VideoFrameQuality.P512,
    val showUserDanmaku: Boolean = true,
) {
    val giftsAvailable: Boolean
        get() = giftsEnabled
}
