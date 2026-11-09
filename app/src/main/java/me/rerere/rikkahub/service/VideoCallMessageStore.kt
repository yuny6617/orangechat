/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import kotlinx.coroutines.sync.withLock
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.video.FrameFileOwnership
import kotlin.uuid.Uuid

/**
 * 视频通话里由系统追加的持久消息（例如 `<ws_vsnap/>` 留影）。
 *
 * 它只写进会话，不触发模型生成，也不占用消息队列。
 *
 * 两个关键点：
 * - [validate] 在**拿到 saveMutex 之后、第一次发布消息之前**执行。留影许可的消费
 *   （`consumeSnapshotPermit`）必须放在这里：在锁外校验时，从校验到落库之间有一段
 *   "等锁"的窗口，期间挂断、面板离开、相机换代都不会取消这个协程（通话本身没挂断），
 *   于是一张已经作废的照片照样写进聊天 —— 而这正是"许可可被 cancelTurn 撤销"要防的事。
 * - 写库走 [ChatService.saveConversationWithOwnership]：文件所有权与数据库取消的交接
 *   在那一处统一处理（发布之后永不删）。
 *
 * @return true 表示消息真的发布了；false 表示 [validate] 在发布之前否决了这次写入
 *         （此时没有任何状态被改动，调用方负责清理自己刚写的文件）。
 */
internal suspend fun ChatService.appendVideoCallMessage(
    conversationId: Uuid,
    message: UIMessage,
    ownership: FrameFileOwnership,
    validate: () -> Boolean = { true },
): Boolean {
    val session = getOrCreateSession(conversationId)
    return session.saveMutex.withLock {
        session.requireActive()
        // 许可校验与消费必须在锁内、发布之前：这里否决就等于"这张照片从来没进过聊天"。
        if (!validate()) return@withLock false
        val current = session.state.value
        saveConversationWithOwnership(
            session = session,
            conversationId = conversationId,
            conversation = current.copy(messageNodes = current.messageNodes + message.toMessageNode()),
            ownership = ownership,
            publishedMessageId = message.id,
        )
        true
    }
}
