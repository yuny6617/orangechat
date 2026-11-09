/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import java.util.concurrent.atomic.AtomicReference

/**
 * 本次请求新建的永久帧（手动夹帧 / 留影）的**归属状态**。
 *
 * 为什么不能只是一个 `Boolean userMessageSaved`：布尔标记只能在 save() **正常返回之后**
 * 才被置位，而 save() 本身是"先发布进内存历史 → 再挂起写数据库"两段：
 *
 * 1. `updateConversation()` 一旦执行，这条用户消息就已经被会话状态引用（UI 立刻可见）；
 * 2. 之后的 Room 提交是挂起点，取消可能落在它中间 —— 调用方**无法知道**事务到底提交没有；
 * 3. 旧实现在 `catch (CancellationException)` 里按 `!saved` 删文件，于是一条可能已经
 *    提交进数据库的消息，永久指向一个被删掉的文件（历史断图，永远修不回来）。
 *
 * 所以这里用**保守交接**的三态机，而不是布尔：
 *
 * ```
 *            markPublishing()            markPublished()
 *   OWNED ───────────────────> PUBLISHING ──────────────> PUBLISHED
 *     │                             │
 *     │                             └── markSaveFailed() ──> OWNED
 *     │                                 （明确的非取消失败：事务一定没提交）
 *     └── claimDeletion() ──> DISCARDED（只有"从未开始发布"才允许删）
 * ```
 *
 * 规则：
 * - `PUBLISHING` / `PUBLISHED` 一律**不允许**删文件。取消时停在 `PUBLISHING`，
 *   含义是"可能已经发布"，宁可留一张没人引用的孤儿照片，也不能让聊天历史断图；
 * - 只有"从来没走到发布"（[Phase.OWNED]）才允许删，正常失败因此仍然可以清理干净；
 * - [claimDeletion] 是 CAS + 幂等：`finally` 与 `invokeOnCompletion` 两条兜底路径
 *   可能同时跑，但只有一条会真正拿到删除权。
 *
 * 纯 Kotlin（只依赖 `java.util.concurrent`），因此可以在 JVM 单测里把
 * 取消/失败/双重兜底的全部次序钉死。
 */
internal class FrameFileOwnership(alreadyDurable: Boolean = false) {
    enum class Phase {
        /** 文件由本请求拥有，且还没有任何历史引用它 —— 允许删除。 */
        OWNED,

        /** 已经进入"发布"流程，可能已经被内存历史引用 —— 保守起见视为不可删。 */
        PUBLISHING,

        /** 已经确认写进持久历史 —— 永不删除。 */
        PUBLISHED,

        /** 删除权已经被某条兜底路径认领 —— 不再删除。 */
        DISCARDED,
    }

    private val phase = AtomicReference(if (alreadyDurable) Phase.PUBLISHED else Phase.OWNED)

    val current: Phase
        get() = phase.get()

    /** 现在还可以被本请求删除吗（只是快照，真正的删除要过 [claimDeletion]）。 */
    val isDeletable: Boolean
        get() = phase.get() == Phase.OWNED

    /**
     * 进入发布流程之前调用（拿到保存锁之后、第一次发布状态之前）。
     *
     * @return false 表示状态已经不在 [Phase.OWNED]（已经发布过，或已被认领删除）。
     */
    fun markPublishing(): Boolean = phase.compareAndSet(Phase.OWNED, Phase.PUBLISHING)

    /** 确认消息已经写进持久历史。 */
    fun markPublished(): Boolean {
        while (true) {
            when (phase.get()) {
                Phase.OWNED ->
                    if (phase.compareAndSet(Phase.OWNED, Phase.PUBLISHED)) return true
                Phase.PUBLISHING ->
                    if (phase.compareAndSet(Phase.PUBLISHING, Phase.PUBLISHED)) return true
                Phase.PUBLISHED -> return true
                Phase.DISCARDED -> return false
            }
        }
    }

    /**
     * 明确的（非取消）失败：保存抛了普通异常，Room 事务没有提交，消息从来没被引用。
     * 把状态退回 [Phase.OWNED]，让外层 finally 可以正常清理文件。
     *
     * @return true 表示现在处于可删除状态。
     */
    fun markSaveFailed(): Boolean {
        while (true) {
            when (phase.get()) {
                Phase.PUBLISHING -> if (phase.compareAndSet(Phase.PUBLISHING, Phase.OWNED)) return true
                Phase.OWNED -> return true
                // 已经发布过（或已删除）：失败也不能把已发布的引用撤回来。
                Phase.PUBLISHED, Phase.DISCARDED -> return false
            }
        }
    }

    /**
     * 认领"由我负责删除这些文件"。
     *
     * @return true 表示调用方**必须**删除对应文件；false 表示文件可能已经被历史引用
     *         （或已被另一条兜底路径认领），绝对不能删。
     */
    fun claimDeletion(): Boolean {
        while (true) {
            when (phase.get()) {
                Phase.OWNED -> if (phase.compareAndSet(Phase.OWNED, Phase.DISCARDED)) return true
                Phase.DISCARDED -> return false
                Phase.PUBLISHING, Phase.PUBLISHED -> return false
            }
        }
    }
}
