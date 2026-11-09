/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 相机代次的**串行化门闩**（REVIEW_R5 §1）。
 *
 * 存在的理由：上一版把 `@Synchronized` 加在 `isCurrent()` 上，只包住了**一次 read**，
 * 而真正的"检查 + 接管资源 + 发布状态"散在锁外：
 *
 * ```
 * onConfigured:  if (!isCurrent(gen)) return   // 锁内读一次
 *                gen.session = configured      // 锁外：stop/switch 可以插在这里
 *                setRepeatingRequest(...)      // 锁外：旧回调用已释放的设备
 *                _status.value = Previewing    // 锁外：把挂断后的状态又改成预览中
 * ```
 *
 * 所以这把锁的契约是：**同一把锁内完成 check 与 act**。控制器里所有状态迁移
 * （开新代 / 结束一代 / 回调接管 / 发布 status 与矩阵）都必须走
 * [exclusive]（或它的两个收窄形式 [adopt] / [end] / [ifCurrent]），
 * 过期回调走锁外的"只释放自己"路径。
 *
 * 资源**释放**不在这把锁里做：释放要调 `CameraDevice.close()` 这类 binder 调用，
 * 一边持锁一边等它，遇到"先拿住 generation 自己的锁再回头要控制器锁"的路径就会交叉死锁。
 * 因此约定是：锁内只做**摘除**（谁已经不是当前代了），锁外再用返回值释放；
 * 代次对象自己的同步（见 `VideoCallCameraController.Generation.release`）只保护它自己的字段，
 * 永远不会在持锁时被等。
 *
 * 纯 Kotlin：没有 Android、没有协程，可以用真实线程做确定性交错测试。
 */
class CameraGenerationGate<G : Any> {
    private val lock = ReentrantLock()

    /** 每次换代 +1。只用于日志/诊断，过期判断一律用**对象身份**。 */
    private var token: Long = 0L

    private var current: G? = null

    /** 互斥区：控制器里任何"读-改-写"状态的地方都在这里面。 */
    fun <R> exclusive(block: () -> R): R = lock.withLock { block() }

    /**
     * 让 [owner] 成为当前这一代。
     *
     * @return 被替换下来的上一代；调用方**必须在锁外**释放它（见类注释）。
     */
    fun adopt(owner: G): G? = exclusive {
        val previous = current
        current = owner
        token += 1
        previous
    }

    /**
     * 结束当前这一代（切镜头 / unbind / 失败 / 挂断）。
     *
     * @return 刚被结束的那一代；调用方在锁外释放它。
     */
    fun end(): G? = exclusive {
        val previous = current
        current = null
        token += 1
        previous
    }

    /** 当前这一代（只读快照）。 */
    fun currentOrNull(): G? = exclusive { current }

    fun isCurrent(owner: G): Boolean = exclusive { current === owner }

    fun currentToken(): Long = exclusive { token }

    /**
     * **同一把锁内**完成 check + act：过期就返回 null，并且**绝不执行** [block]。
     *
     * 这就是 `@Synchronized isCurrent` 没做到的那件事。[block] 里可以接管资源、
     * 调用异步相机 API、发布状态；这些动作与 `stop`/`switchCamera`/新代 adopt 互斥，
     * 不可能出现"检查时还是当前代，动手时已经被换掉"。
     */
    fun <R> ifCurrent(owner: G, block: (G) -> R): R? = exclusive {
        if (current !== owner) return@exclusive null
        block(owner)
    }
}
