/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

enum class CameraFacing {
    Front,
    Back;

    fun toggled(): CameraFacing = if (this == Front) Back else Front
}

enum class VideoCameraState {
    /** 还没开始，或已经彻底停止。 */
    Idle,

    /** 正在打开相机 / 重建预览。 */
    Requesting,

    /** 预览在跑，可以抓帧。 */
    Previewing,
    PermissionDenied,
    Unavailable,
    Failed,
}

data class VideoCameraStatus(
    val state: VideoCameraState = VideoCameraState.Idle,
    val facing: CameraFacing = CameraFacing.Front,
    val message: String = "",
) {
    val isPreviewing: Boolean
        get() = state == VideoCameraState.Previewing
}

/**
 * 相机 + 采样生命周期状态机（纯逻辑）。
 *
 * 注意：这个状态机**已经删除**。
 *
 * 原来的 [VideoCallSessionStateMachine] 只在测试里被调用，生产代码（相机控制器 +
 * `VideoCallPolicy.shouldBindCamera` + 页面生命周期观察）从来不使用它，
 * 于是"测试通过"并不能证明真实生命周期正确（REVIEW_R2 §6 指出）。
 * 现在相机生命周期由生产代码本身承担：
 * - 门禁：`VideoCallPolicy.shouldBindCamera(surface/权限/STARTED/通话中)`；
 * - 执行：`VideoCallCameraController.setPreviewAllowed()` + `attachSurface/detachSurface`；
 * - 验证：`VideoCallPolicyTest` 直接测这套生产判断，`VideoPreviewGeometryTest` 测真实旋转计算。
 */
