/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

/**
 * "这通电话现在到底有什么前台能力"的**唯一状态机**（REVIEW_R5 §5）。
 *
 * 服务里原本散着三个布尔/集合：前台是否在跑、声明了哪些前台服务类型、语音链路是否已经建好。
 * 把它们收在一起，是为了让"通话进行中权限组合变化该做什么"这件事可以被 JVM 单测**按顺序**
 * 验证（补授权 → 刷新一次 → 重复调用幂等 → 权限撤回 → 降级），而不只是查一张纯函数表。
 *
 * 服务在 `startForeground` 成功 / `stopForeground` / 初始化协程提交语音链路时更新这里，
 * 刷新时直接从这里的快照决定动作，因此"已经做过的事不会再做一遍"是状态本身的性质。
 *
 * ## 正在初始化（REVIEW_R6 §2）
 * 语音链路的初始化是 suspend 的。初始化还没提交时 `audioPipelineReady` 仍然是 false，
 * 如果只看它，页面的每一次重组/权限回调都会让服务**取消并重启**同一个初始化
 * （旧协程不断被换掉，ASR 可能永远起不来）。所以这里额外记录
 * "有没有一次针对**这组能力**的初始化正在飞"：
 * - 同一组能力正在初始化 → 不需要再补建（[refreshAction] 返回 None）；
 * - 能力组合变了（撤权/重新授权）→ 仍然要求立刻刷新，让服务取消旧初始化、按新能力重来。
 */
class CallCapabilityState {
    private var foreground: Boolean = false
    private var capabilities: Set<CallForegroundCapability> = emptySet()
    private var audioPipelineReady: Boolean = false

    /** 是否有一次初始化正在飞；[initTargetCapabilities] 是它要建成的能力组合。 */
    private var initInFlight: Boolean = false
    private var initEpoch: Long = 0L
    private var initTargetCapabilities: Set<CallForegroundCapability> = emptySet()

    @Synchronized
    fun isForegroundRunning(): Boolean = foreground

    @Synchronized
    fun declaredCapabilities(): Set<CallForegroundCapability> = capabilities

    @Synchronized
    fun isAudioPipelineReady(): Boolean = audioPipelineReady

    /** 是否有一次语音链路初始化正在飞（诊断/测试用）。 */
    @Synchronized
    fun isInitializationInFlight(): Boolean = initInFlight

    @Synchronized
    fun initializationTargetCapabilities(): Set<CallForegroundCapability> = initTargetCapabilities

    /**
     * 语音链路"已经不需要再补建"：已经建好，或者**正按同一组能力**初始化中。
     *
     * 与 [isAudioPipelineReady] 的区别：后者只表示"已经提交成功"。判断要不要触发补建时
     * 必须用这个，否则初始化期间每次刷新都会取消/重启一次。
     */
    @Synchronized
    fun isAudioPipelineSettledFor(
        cameraGranted: Boolean,
        micGranted: Boolean,
        videoMode: Boolean,
    ): Boolean {
        if (audioPipelineReady) return true
        if (!initInFlight) return false
        val target = VideoCallPolicy.servicePlan(cameraGranted, micGranted, videoMode).capabilities
        return initTargetCapabilities == target
    }

    /** 同一组能力是否已经有初始化在飞（服务用来避免重复启动初始化协程）。 */
    @Synchronized
    fun isInitializationInFlightFor(target: Set<CallForegroundCapability>): Boolean =
        initInFlight && initTargetCapabilities == target

    /** `startForeground` 成功了：记住真的声明了哪些类型。 */
    @Synchronized
    fun onForegroundRunning(declared: Set<CallForegroundCapability>) {
        foreground = true
        capabilities = declared
    }

    /** 停掉前台服务（挂断 / 降级成绑定模式）。 */
    @Synchronized
    fun onForegroundStopped() {
        foreground = false
        capabilities = emptySet()
    }

    /** 语音链路（ASR/TTS/麦克风）是否已经建好：只有它决定要不要补建**一次**。 */
    @Synchronized
    fun setAudioPipelineReady(ready: Boolean) {
        audioPipelineReady = ready
    }

    /**
     * 开始一次初始化（[epoch] 由服务提供，用来丢弃过期的提交）。
     *
     * 注意：这里**不清** [audioPipelineReady]。正在补建时旧链路可能仍然可用，
     * 由服务在换掉/释放旧链路时显式 [setAudioPipelineReady]`(false)`。
     */
    @Synchronized
    fun beginInitialization(epoch: Long, targetCapabilities: Set<CallForegroundCapability>) {
        initInFlight = true
        initEpoch = epoch
        initTargetCapabilities = targetCapabilities
    }

    /** 初始化结束（成功提交 / 失败 / 被取消）。只有最新一代才有权清掉在飞标记。 */
    @Synchronized
    fun endInitialization(epoch: Long) {
        if (epoch != initEpoch) return
        initInFlight = false
        initTargetCapabilities = emptySet()
    }

    /**
     * 立刻作废在飞的初始化（权限被撤回 / 换会话）。
     *
     * 与 [endInitialization] 不同：不比对代次，永远无条件清掉，并把代次 +1，
     * 旧协程即使恢复也拿不到 [endInitialization] 的权限。
     */
    @Synchronized
    fun cancelInitialization() {
        initInFlight = false
        initEpoch += 1
        initTargetCapabilities = emptySet()
    }

    @Synchronized
    fun reset() {
        foreground = false
        capabilities = emptySet()
        audioPipelineReady = false
        initInFlight = false
        initEpoch += 1
        initTargetCapabilities = emptySet()
    }

    /**
     * 通话进行中、权限组合变化时该做什么（REVIEW_R5 §5 的入口）。
     *
     * 调用方必须先确认"这还是当前这通通话"，本状态机只管能力组合。
     * 内部状态决定幂等性：能力组合没变、语音链路也建好了 → [VideoCallRefreshAction.None]；
     * 同一组能力正在初始化中 → 同样 [VideoCallRefreshAction.None]（不取消、不重启）。
     */
    @Synchronized
    fun refreshAction(
        cameraGranted: Boolean,
        micGranted: Boolean,
        videoMode: Boolean,
    ): VideoCallRefreshAction = VideoCallPolicy.refreshAction(
        callActive = true,
        foreground = foreground,
        currentCapabilities = capabilities,
        cameraGranted = cameraGranted,
        micGranted = micGranted,
        videoMode = videoMode,
        audioPipelineReady = isAudioPipelineSettledFor(cameraGranted, micGranted, videoMode),
    )
}
