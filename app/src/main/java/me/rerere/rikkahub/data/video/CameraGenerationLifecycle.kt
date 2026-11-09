/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

/**
 * 一代相机资源"结束/换代"的**唯一顺序**（REVIEW_R6 §3）。
 *
 * 旧实现先结束门闩、锁外释放相机，**释放之后**才让 `VideoCallRuntime` 的相机代次失效。
 * 在那段窗口里，运行时仍然认为旧代是当前代：排队中的抓帧、已经发出的留影许可、
 * `holdPendingFrame` 都还能消费旧画面。所以顺序必须是：
 *
 * 1. 门闩内摘除当前代；
 * 2. **仍在同一个临界区里**让运行时相机代次失效（epoch +1、清待发送帧、撤销许可）；
 * 3. 临界区结束之后才释放 `CameraDevice` / `CaptureSession`（释放是 binder 调用，
 *    持锁等它就是死锁源，见 [CameraGenerationGate] 的锁序约定）。
 *
 * 锁序固定为 **gate → runtime**：这条路径持门闩去要 runtime 的监视器；
 * 反过来 runtime 侧读 gateway（`available` / `capture`）时**绝不持 runtime 监视器**
 * （见 `VideoCallRuntime.requestSnapshot`），因此不存在反向锁序。
 *
 * 纯 Kotlin 的 production 组件：控制器里所有 stop / 切镜头 / 失败 / 换代都经过它，
 * 因此这段顺序可以用真实线程做确定性交错测试。
 */
class CameraGenerationLifecycle<G : Any>(
    private val gate: CameraGenerationGate<G>,
    private val invalidateRuntime: () -> Unit,
    private val release: (G) -> Unit,
) {
    /**
     * 结束当前这一代。**必须在门闩内调用**（[CameraGenerationGate.exclusive]）；
     * 只摘除 + 让运行时失效，**不释放**资源，返回值由调用方在锁外释放。
     */
    fun endLocked(): G? {
        val ended = gate.end() ?: return null
        // 与 gate.end() 同一个临界区：没有任何线程能在这个窗口里看到"门闩已经换代、
        // runtime 还以为旧代有效"的状态。
        invalidateRuntime()
        return ended
    }

    /** 门闩内结束一代（失效同临界区），**锁外**释放资源。 */
    fun end(): G? {
        val ended = gate.exclusive { endLocked() }
        ended?.let(release)
        return ended
    }

    /**
     * 新一代上线。**必须在门闩内调用**：同时让运行时相机代次失效
     * （旧代抓到的帧/许可一律作废）。返回上一代，由调用方在锁外释放。
     */
    fun adoptLocked(generation: G): G? {
        val previous = gate.adopt(generation)
        invalidateRuntime()
        return previous
    }

    /** 门闩内换代，锁外释放上一代。 */
    fun adopt(generation: G): G? {
        val previous = gate.exclusive { adoptLocked(generation) }
        previous?.let(release)
        return previous
    }
}

/**
 * `startInternal` 的门禁（REVIEW_R6 §3）：开相机是排队执行的，排队期间 UI 可能已经
 * 拆掉 Surface / 关了预览门禁。这时**必须**重验"仍然允许预览 + 仍然是同一个 surface/texture"，
 * 否则会拿着旧 Surface 再开一次相机（新相机会挂在已经销毁的 texture 上）。
 */
internal fun cameraStartAllowed(
    previewAllowed: Boolean,
    ownedSurface: Any?,
    surface: Any?,
    ownedTexture: Any?,
    texture: Any?,
): Boolean =
    previewAllowed &&
        surface != null &&
        texture != null &&
        ownedSurface === surface &&
        ownedTexture === texture
