/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

/**
 * 一次"由视频通话发起"的对话轮次授权。
 *
 * [token] 不是从历史消息里推断出来的：它由 [me.rerere.rikkahub.service.VoiceCallService]
 * 在自己提交这一轮消息之前生成，随队列项不可变地一路传到生成管线
 * （`QueuedMessage.requestToken` → `GenerationHandler` → `TransformerContext.requestToken`）。
 *
 * 因此"同一会话 + 同一用户消息 ID"并不足以获得授权：
 * 非通话请求（聊天页手打、后台主动消息、工具重放）拿不到 token，也无法伪造 token。
 */
data class VideoCallTurnGrant(
    val token: String,
    val conversationId: String,
    val callEpoch: Long,
)

/**
 * 模型 `<ws_vsnap/>` 的一次性留影许可。
 *
 * 留影是异步的（抓帧 → 压缩 → 落盘 → 写消息），而请求本身（生成轮次）往往这时已经结束了。
 * 所以不能用"轮次 token 仍有效"来把关：那样请求一结束，合法的留影全部失效；
 * 也不能只按会话判断：那样面板离开、相机换代之后还会把旧画面插进聊天。
 *
 * 许可绑定 [callEpoch]（这通电话还是这通）与 [cameraEpoch]（还是同一代相机），
 * 并且只能消费一次。
 *
 * [requestToken] 记住是谁申请的：请求被**取消/打断**时（[VideoCallTurnLedger.cancelTurn]）
 * 要把这次请求发出的所有许可一起撤销；正常完成（[VideoCallTurnLedger.completeTurn]）
 * 则保留它们，让已经合法的异步留影仍然能落盘。
 */
data class VideoSnapshotPermit(
    val token: String,
    val conversationId: String,
    val messageId: String,
    val callEpoch: Long,
    val cameraEpoch: Long,
    val requestToken: String = "",
)

/**
 * 视频通话的"轮次授权台账"。
 *
 * 纯 Kotlin（没有 Android 依赖、没有协程），因此可以直接单测：
 * 跨会话复用同一个用户消息 ID、非通话请求拿同样 conversation + user ID、
 * 取消/结束关闭 token、留影许可跨请求结束仍有效但不能重复消费。
 */
class VideoCallTurnLedger(private val capacity: Int = DEFAULT_CAPACITY) {
    private class Grant(
        val conversationId: String,
        val callEpoch: Long,
        var giftGranted: Boolean = false,
    )

    private var callEpoch: Long = 0
    private var cameraEpoch: Long = 0
    private var activeConversation: String? = null

    private val grants = LinkedHashMap<String, Grant>()
    private val snapshotPermits = LinkedHashMap<String, VideoSnapshotPermit>()
    private val consumedPermitTokens = LinkedHashSet<String>()

    @Synchronized
    fun activeConversation(): String? = activeConversation

    @Synchronized
    fun currentCallEpoch(): Long = callEpoch

    @Synchronized
    fun currentCameraEpoch(): Long = cameraEpoch

    /**
     * 进入一通视频通话。换成另一个会话时先清掉上一通的全部授权，
     * 并递增 [callEpoch]：上一通的 token / 许可全部作废（即使字符串碰巧相同）。
     */
    @Synchronized
    fun beginCall(conversationId: String?) {
        val target = conversationId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (activeConversation == target) return
        resetLocked()
        activeConversation = target
        callEpoch += 1
        cameraEpoch += 1
    }

    /** 挂断 / 退后台结束：所有 token 与留影许可立刻失效。 */
    @Synchronized
    fun endCall() {
        resetLocked()
    }

    /**
     * 相机换代（绑定 / 解绑 / 切换镜头 / 面板重建）。
     * 留影许可绑定这一代，换代后旧许可不能再把旧画面写进聊天。
     */
    @Synchronized
    fun rotateCameraEpoch() {
        cameraEpoch += 1
    }

    /** 只有"这通电话 + 这通电话自己这次请求"才是有效授权。 */
    @Synchronized
    fun isGranted(conversationId: String?, token: String?): Boolean =
        liveGrant(conversationId, token) != null

    /**
     * 登记一轮请求的授权。
     *
     * **幂等**：同一个 token 重复登记返回既有凭证，且**保留**已经消耗掉的礼物名额。
     * 旧实现每次登记都新建 Grant（`giftGranted = false`），于是一次重复登记就能
     * 让同一条回复再送一枚礼物 —— 这里必须拒绝刷新名额。
     *
     * @return 登记成功（或已存在）返回授权凭证；不在通话里、会话不匹配或 token 为空返回 null。
     */
    @Synchronized
    fun beginTurn(conversationId: String?, token: String?): VideoCallTurnGrant? {
        val active = activeConversation ?: return null
        if (conversationId?.trim() != active) return null
        val key = token?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val existing = grants[key]
        if (existing != null && existing.callEpoch == callEpoch) {
            // 重复登记：原样返回，绝不重置 giftGranted，也不改变插入顺序。
            return VideoCallTurnGrant(token = key, conversationId = active, callEpoch = callEpoch)
        }
        grants[key] = Grant(conversationId = active, callEpoch = callEpoch)
        while (grants.size > capacity) {
            val oldest = grants.keys.firstOrNull() ?: break
            grants.remove(oldest)
        }
        return VideoCallTurnGrant(token = key, conversationId = active, callEpoch = callEpoch)
    }

    /**
     * 这一轮**正常完成**：关掉 token（之后的回调一律失败关闭），
     * 但保留它已经发出的留影许可 —— 合法的异步留影本来就落在请求结束之后。
     */
    @Synchronized
    fun completeTurn(token: String?) {
        val key = token?.trim()?.takeIf { it.isNotEmpty() } ?: return
        grants.remove(key)
    }

    /**
     * 这一轮被**取消/打断/失败**：关掉 token，并撤销这次请求发出的**全部**留影许可。
     *
     * 与 [completeTurn] 的区别就是这一条：被打断的回复不该还能把画面写进聊天。
     *
     * @return 被撤销的许可数量（0 表示这次请求没有发出过许可）。
     */
    @Synchronized
    fun cancelTurn(token: String?): Int {
        val key = token?.trim()?.takeIf { it.isNotEmpty() } ?: return 0
        grants.remove(key)
        val revoked = snapshotPermits.values.filter { it.requestToken == key }.map { it.token }
        revoked.forEach { permitToken ->
            snapshotPermits.remove(permitToken)
            consumedPermitTokens.remove(permitToken)
        }
        return revoked.size
    }

    /**
     * 每轮最多一枚礼物：这是原子的"检查并占用"。
     *
     * @return true 表示本次调用真正占到了这一轮的发放名额。
     */
    @Synchronized
    fun consumeGiftGrant(conversationId: String?, token: String?): Boolean {
        val grant = liveGrant(conversationId, token) ?: return false
        if (grant.giftGranted) return false
        grant.giftGranted = true
        return true
    }

    /**
     * 发放一次性留影许可。
     *
     * 与礼物不同，留影在请求结束之后才真正落盘，所以许可**不随 [completeTurn] 失效**，
     * 只随 [cancelTurn]（撤销这次请求的全部许可）、挂了电话（[endCall]）
     * 或相机换代（[rotateCameraEpoch]）失效。
     */
    @Synchronized
    fun issueSnapshotPermit(
        conversationId: String?,
        token: String?,
        messageId: String?,
    ): VideoSnapshotPermit? {
        val active = activeConversation ?: return null
        if (conversationId?.trim() != active) return null
        if (liveGrant(conversationId, token) == null) return null
        val message = messageId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val request = token?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val permitId = "$message@$callEpoch:$cameraEpoch"
        val permit = VideoSnapshotPermit(
            token = permitId,
            conversationId = active,
            messageId = message,
            callEpoch = callEpoch,
            cameraEpoch = cameraEpoch,
            requestToken = request,
        )
        snapshotPermits[permit.token] = permit
        while (snapshotPermits.size > capacity) {
            val oldest = snapshotPermits.keys.firstOrNull() ?: break
            snapshotPermits.remove(oldest)
        }
        while (consumedPermitTokens.size > capacity) {
            val oldest = consumedPermitTokens.firstOrNull() ?: break
            consumedPermitTokens.remove(oldest)
        }
        return permit
    }

    /** 许可此刻是否还能用（电话还在、相机没换代、同一会话、没被消费过）。 */
    @Synchronized
    fun isSnapshotPermitUsable(permit: VideoSnapshotPermit?): Boolean {
        val target = permit ?: return false
        val active = activeConversation ?: return false
        if (target.conversationId != active) return false
        if (target.callEpoch != callEpoch) return false
        if (target.cameraEpoch != cameraEpoch) return false
        if (target.token in consumedPermitTokens) return false
        return snapshotPermits[target.token] != null
    }

    /**
     * 消费许可并再次校验时效。写消息之前调用，保证同一许可不会写第二张照片、
     * 也不会在挂断/相机换代之后仍然写入。
     */
    @Synchronized
    fun consumeSnapshotPermit(permit: VideoSnapshotPermit?): Boolean {
        if (!isSnapshotPermitUsable(permit)) return false
        val target = permit ?: return false
        snapshotPermits.remove(target.token)
        consumedPermitTokens += target.token
        return true
    }

    /** 仅供测试与挂断兜底。 */
    @Synchronized
    fun reset() {
        resetLocked()
    }

    private fun resetLocked() {
        activeConversation = null
        callEpoch += 1
        cameraEpoch += 1
        grants.clear()
        snapshotPermits.clear()
        consumedPermitTokens.clear()
    }

    private fun liveGrant(conversationId: String?, token: String?): Grant? {
        val active = activeConversation ?: return null
        if (conversationId?.trim() != active) return null
        val key = token?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val grant = grants[key] ?: return null
        if (grant.callEpoch != callEpoch) return null
        if (grant.conversationId != active) return null
        return grant
    }

    companion object {
        /** 一通电话正常远用不到这么多轮；上限只是防止异常重试把内存撑大。 */
        const val DEFAULT_CAPACITY: Int = 64
    }
}
