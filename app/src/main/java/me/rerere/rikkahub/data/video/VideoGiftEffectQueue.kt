/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

/**
 * 一次全屏礼物特效的播放请求（出队快照）。
 *
 * [key] 是**这一次播放**的唯一身份：由礼物事件 id（[VideoGiftEvent.id]，即"消息 id + 礼物 id"）
 * 加单调序号组成。连续两次送同档位礼物时 `gift` 枚举相等，但两次出队的 [key] 一定不同，
 * 面板用它做 overlay 的 key（整段动画按 key 分组），第二次一定重新起动画，
 * 不会复用上一次已经跑完的进度而空屏。
 */
data class VideoGiftEffectRequest(
    val key: String,
    val eventId: String,
    val gift: VideoGift,
)

/** Skip only the rendered request. A delayed click must not dismiss its successor. */
fun VideoGiftEffectRequest?.skipIfMatching(renderedKey: String): VideoGiftEffectRequest? =
    if (this?.key == renderedKey) null else this

/**
 * 视频面板的全屏礼物特效队列（纯逻辑，无 Compose/Android 依赖，可直接 JVM 单测）。
 *
 * 行为与面板原来的内联实现一致：
 * - 只收全屏档位（`gift.isFullScreen`），低档位只出气泡，[offer] 返回 null；
 * - 上限 `VIDEO_GIFT_EFFECT_QUEUE_LIMIT`，满了丢**最旧**的一条（新礼物优先，不无限排队）；
 * - 退后台/挂断/换会话直接 [clear]，队列整体作废。
 *
 * 关键改动：每次入队都分配新的 [VideoGiftEffectRequest.key]（事件 id + 单调序号），
 * 出队的永远是**新实例**。这样"出队 → null → 立刻同枚举"这条旧路径不再产生
 * "状态值没变、Compose 合并、动画不重启"的结果。
 *
 * 线程模型：主线程独占（视频面板的两个 LaunchedEffect 都在 Compose 主调度器上），
 * 内部不加锁。
 */
class VideoGiftEffectQueue(private val limit: Int = VIDEO_GIFT_EFFECT_QUEUE_LIMIT) {
    private val pending = mutableListOf<VideoGiftEffectRequest>()
    private var sequence = 0L

    /** 当前排队数量（不含正在播的那一条）。 */
    val size: Int
        get() = pending.size

    val isEmpty: Boolean
        get() = pending.isEmpty()

    /**
     * 入队一次礼物事件；[eventId] 用 [VideoGiftEvent.id]（已有"消息 id + 礼物 id"，优先采用）。
     *
     * @return 新入队的请求；低档位礼物（不触发全屏特效）返回 null。
     */
    fun offer(eventId: String, gift: VideoGift): VideoGiftEffectRequest? {
        if (!gift.isFullScreen) return null
        val capacity = limit.coerceAtLeast(1)
        while (pending.size >= capacity) pending.removeAt(0)
        sequence += 1L
        val request = VideoGiftEffectRequest(
            key = identityKey(eventId, gift, sequence),
            eventId = eventId,
            gift = gift,
        )
        pending.add(request)
        return request
    }

    /** 出队下一条要播的特效；空队列返回 null。 */
    fun poll(): VideoGiftEffectRequest? =
        if (pending.isEmpty()) null else pending.removeAt(0)

    /** 退后台/挂断/换会话：排队的特效整体作废（正在播的那条由调用方自己清）。 */
    fun clear() {
        pending.clear()
    }

    companion object {
        /**
         * 事件身份 = 礼物事件 id（优先"消息 id + 礼物 id"）+ 单调序号。
         *
         * 事件 id 为空（不该发生）时退回礼物 id；序号保证同一事件 id 被重复投递
         * （重放/重入的兜底）也不会得到同一个 key。
         */
        fun identityKey(eventId: String, gift: VideoGift, sequence: Long): String {
            val base = eventId.trim().ifEmpty { gift.id }
            return "$base#${sequence.coerceAtLeast(0L)}"
        }
    }
}
