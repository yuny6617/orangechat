package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.data.files.QueueAttachmentReferences
import kotlin.uuid.Uuid

data class QueuedMessage(
    val id: Uuid = Uuid.random(),
    val parts: List<UIMessagePart>,
    val answer: Boolean = true,
    val interaction: UIMessageAnnotation.AvatarInteraction? = null,
    val isEditing: Boolean = false,
    val transientSystemPrompt: String? = null,
    /**
     * 暂停后"本轮附件"被摘掉时改用的 system prompt（视频 context 的无附件版本）。
     *
     * 暂停会把 [transientImageUrls]/[transientAudio] 摘掉，同时把 [requestToken] 作废：
     * 恢复队列是一次**全新请求**，没有 token 就没有任何相机授权，所以永久手动夹帧即使
     * 还留在 [parts] 里（**历史里仍然看得见**），也绝不会被重放给 provider。
     * 既然如此，[transientSystemPrompt] 里"本轮附了图 / 附了录音"的话就不再成立，
     * 一律换成这个无附件版本。
     *
     * 最后一道防线在 provider 边界（`GenerationHandler` 的授权 apply）：那里按**实际交付**
     * 的图片数量再同步一次 system context。就算这个变体本身还写着"有图"（提交时确实夹了
     * 永久帧），发请求前也会被改成"本轮没有画面"。
     */
    val transientSystemPromptWithoutImages: String? = null,
    val enableTools: Boolean = true,
    val existingMessageId: Uuid? = null,
    /**
     * 只用于本次请求的图片地址（视频通话自动抓帧）。
     *
     * 约定：不进聊天历史、不持久化；请求结束（完成/失败/取消/撤回/暂停/清空）后
     * 由 [MessageQueue] 的终结回调删除文件，之后的历史里只剩文字。
     */
    val transientImageUrls: List<String> = emptyList(),
    /**
     * 本次**新建**、还没有被聊天历史引用的永久帧（用户手动夹住的那一帧）。
     *
     * 只有"这条消息最终没有写进历史"时才允许删除：一旦落库，文件就是历史的一部分，
     * 任何队列清理都不许再动它。因此这里存的是**显式的所有权**，
     * 而不是"扫一下 parts 里所有 Image 再按目录删"（那样会把用户重发的历史截图删掉）。
     */
    val ownedImageUrls: List<String> = emptyList(),
    /**
     * 这一轮的授权 token（视频通话）。
     *
     * 随队列项不可变地传给生成管线；送礼/留影只认"有效通话 + 这个 token"，
     * 不再用"最后一条用户消息"这种可复用的伪身份。
     */
    val requestToken: String? = null,
    /**
     * 这一轮的**临时录音**（视频通话手动录的那一段，直传路线）。
     *
     * 约定与 `transientImageUrls` 一致：不进聊天历史、不持久化；只在这一条请求里
     * 作为音频输入出现一次。所有权在 [me.rerere.rikkahub.data.video.TransientAudioClip]：
     * 队列丢弃只能删"还没交给请求"的段，正在序列化的那一段由请求自己释放。
     */
    val transientAudio: me.rerere.rikkahub.data.video.TransientAudioClip? = null,
    val completion: CompletableDeferred<Unit> = CompletableDeferred(),
    val reply: CompletableDeferred<String?> = CompletableDeferred(),
) {
    /**
     * 本消息是否还带着**永久**图片（手动夹帧）。
     *
     * 它描述的是"消息里看得见什么"，不是"会发给模型什么"：暂停后 token 作废，
     * 这些帧仍留在历史里可见，但 provider 边界不会重放（见 [transientSystemPromptWithoutImages]）。
     */
    val hasPermanentImage: Boolean
        get() = parts.any { it is UIMessagePart.Image }

    /** 这一条是否带着只在本轮有效的附件（临时图或临时录音）。 */
    val hasTransientAttachment: Boolean
        get() = transientImageUrls.isNotEmpty() || transientAudio != null
}

data class MessageQueueState(
    val messages: List<QueuedMessage> = emptyList(),
    val paused: Boolean = false,
)

class MessageQueuePausedException : IllegalStateException("发送队列已暂停，请继续或撤回待发消息")

/** Pending input remains outside chat history; dispatched attachments stay pinned until completion. */
class MessageQueue internal constructor(
    private val attachmentReferences: QueueAttachmentReferences = QueueAttachmentReferences(),
) {
    private val mutableState = MutableStateFlow(MessageQueueState())
    val state = mutableState.asStateFlow()
    private val inFlight = linkedMapOf<Uuid, QueuedMessage>()

    /**
     * 临时图片的**唯一终结回调**（由 ChatService 注入，删除请求作用域的临时文件）。
     *
     * 队列是这些文件的持有者：谁把队列项丢掉（撤回 / 清空 / 暂停后放弃），谁就必须
     * 在**同一时刻**通知这里，否则文件没人再知道存在。正常的"派发出去"不走这里——
     * 那时文件的所有权已经移交给请求本身（job 的 finally 负责删）。
     */
    var onDiscardTransientImages: ((QueuedMessage) -> Unit)? = null

    /**
     * 队列项被彻底放弃（撤回 / 清空 / 会话删除）时的终结回调。
     *
     * 与 [onDiscardTransientImages] 的区别：暂停只是把"本轮附图"摘掉，消息本体仍会
     * resume（它的永久夹帧必须留着），所以暂停不会走这里。
     *
     * 实现方只允许删除 [QueuedMessage.transientImageUrls] 与 [QueuedMessage.ownedImageUrls]
     * 这两个**显式所有权**列表；绝不能按目录把 parts 里的 Image 都删掉 ——
     * 用户重发一张历史里的截图时，那也是一条 `UIMessagePart.Image`。
     */
    var onMessageDiscarded: ((QueuedMessage) -> Unit)? = null

    /**
     * 队列项放弃/暂停时撤销它携带的通话授权。
     *
     * 恢复队列是一次**全新的请求**，绝不能复用暂停前那个 token 的送礼/留影权限。
     */
    var onRequestTokenRevoked: ((String) -> Unit)? = null

    val hasPendingOrInFlight: Boolean get() = synchronized(this) {
        state.value.messages.isNotEmpty() || inFlight.isNotEmpty()
    }
    val inFlightMessages: List<QueuedMessage> get() = synchronized(this) { inFlight.values.toList() }

    @Synchronized
    fun enqueue(
        parts: List<UIMessagePart>,
        answer: Boolean = true,
        interaction: UIMessageAnnotation.AvatarInteraction? = null,
        transientSystemPrompt: String? = null,
        transientSystemPromptWithoutImages: String? = null,
        enableTools: Boolean = true,
        existingMessageId: Uuid? = null,
        transientImageUrls: List<String> = emptyList(),
        ownedImageUrls: List<String> = emptyList(),
        requestToken: String? = null,
        transientAudio: me.rerere.rikkahub.data.video.TransientAudioClip? = null,
        /**
         * Host-assigned id for the new user message. A caller that already bound a request to this
         * id (the Moments draft selection anchor) can pass it so the binding exists before the
         * queue is dispatched. Null keeps the normal random id.
         */
        newMessageId: Uuid? = null,
    ): QueuedMessage? {
        if (parts.isEmptyInputMessage() && existingMessageId == null) return null
        if (existingMessageId != null && (state.value.messages + inFlight.values).any { it.existingMessageId == existingMessageId }) return null
        val message = QueuedMessage(
            id = newMessageId ?: Uuid.random(),
            parts = parts.toList(), answer = answer, interaction = interaction,
            transientSystemPrompt = transientSystemPrompt,
            transientSystemPromptWithoutImages = transientSystemPromptWithoutImages,
            enableTools = enableTools,
            existingMessageId = existingMessageId,
            transientImageUrls = transientImageUrls.toList(),
            ownedImageUrls = ownedImageUrls.toList(),
            requestToken = requestToken,
            transientAudio = transientAudio,
        )
        // Resolve a blocked caller before publishing the entry: no observer can attach under this monitor.
        // Content and completion remain available for an explicit user resume.
        if (state.value.paused) message.reply.completeExceptionally(MessageQueuePausedException())
        publish(state.value.copy(messages = state.value.messages + message))
        return message
    }

    @Synchronized
    fun takeNext(): QueuedMessage? {
        val current = state.value
        if (current.paused) return null
        val next = current.messages.firstOrNull()?.takeUnless { it.isEditing } ?: return null
        inFlight[next.id] = next
        publish(current.copy(messages = current.messages.drop(1)))
        return next
    }

    fun complete(id: Uuid, result: String? = null, error: Throwable? = null) {
        val message = synchronized(this) {
            val removed = inFlight.remove(id) ?: return
            refreshPins(state.value.messages)
            removed
        }
        if (error == null) {
            message.reply.complete(result)
            message.completion.complete(Unit)
        } else {
            message.reply.completeExceptionally(error)
            message.completion.completeExceptionally(error)
        }
    }

    fun remove(id: Uuid): QueuedMessage? {
        val removed = synchronized(this) {
            val current = state.value
            val message = current.messages.firstOrNull { it.id == id } ?: return null
            publish(current.copy(messages = current.messages.filterNot { it.id == id }))
            message
        }
        withdraw(removed)
        return removed
    }

    @Synchronized
    fun beginEdit(id: Uuid): QueuedMessage? {
        val current = state.value
        val message = current.messages.firstOrNull { it.id == id && !it.isEditing && it.existingMessageId == null } ?: return null
        publish(current.copy(messages = current.messages.map {
            if (it.id == id) it.copy(isEditing = true) else it
        }))
        return message
    }

    /** Null parts cancel editing and preserve the original attachments and observers. */
    @Synchronized
    fun finishEdit(id: Uuid, parts: List<UIMessagePart>? = null): QueuedMessage? {
        if (parts != null && parts.isEmptyInputMessage()) return null
        val current = state.value
        val previous = current.messages.firstOrNull { it.id == id } ?: return null
        publish(current.copy(messages = current.messages.map {
            if (it.id == id) it.copy(parts = parts?.toList() ?: it.parts, isEditing = false) else it
        }))
        return previous.takeIf { parts != null }
    }

    /**
     * 暂停队列。
     *
     * 仍然留在队列里、尚未派发的项：它们的"本轮附图/本轮录音"其实已经不属于任何一个请求了
     * （将来 resume 时是一次全新的请求），所以在这里就把临时文件删掉、把地址从队列项上
     * 摘掉。否则 resume/重试会带着一个已经不存在的路径，让模型以为有图/有音频其实没有。
     *
     * 同时还要做三件事（否则恢复的是一个"看起来很合法的旧请求"）：
     * 1. 把 [QueuedMessage.transientSystemPrompt] 换成**无附件版本**：暂停同时作废了 token，
     *    没有 token 就没有相机授权，永久手动夹帧只留在历史里可见、不会被重放，
     *    所以"本轮有图/有录音"的说明一律不能留（真正的兜底在 provider 边界按实际交付数量再同步）；
     * 2. 撤销这个 token 的送礼/留影授权并把它从队列项上摘掉：
     *    恢复队列是一次新请求，不能复用暂停前的权限；
     * 3. 录了一段但没发出去的那条消息，**追加一句"录音已失效"的说明**：
     *    它仍然可以恢复成一条普通文字消息，但绝不能在被当成"完整语音请求"自动送出去。
     */
    fun pause() {
        val (stripped, waiters) = synchronized(this) {
            val pending = state.value.messages
            val updated = pending.map { message -> stripTransientLocked(message) }
            publish(state.value.copy(paused = true, messages = updated))
            // 回调拿到的是**原始的**队列项（带着 token 与临时附件地址），便于终结方准确清理。
            pending.zip(updated).filter { (before, after) -> before !== after } to pending.toList()
        }
        stripped.forEach { (before, _) ->
            discardTransientImages(before)
            discardTransientAudio(before)
            // 暂停之后这一轮不会再被执行：token 的送礼/留影授权必须当场撤销，不能留着。
            revokeRequestToken(before)
        }
        waiters.forEach { it.reply.completeExceptionally(MessageQueuePausedException()) }
    }

    @Synchronized
    fun resume() {
        publish(state.value.copy(paused = false))
    }

    fun failReplyWaiters(message: String) {
        val pending = synchronized(this) { state.value.messages.toList() }
        pending.forEach { it.reply.completeExceptionally(IllegalStateException(message)) }
    }

    /**
     * 会话删除/清理时清空队列。
     *
     * **只有 pending（还没提交到历史）的项可以在这里终结。**
     *
     * in-flight 的图片所有权在**请求本身**（`ChatService` job 的 finally：它才知道这条用户消息
     * 到底有没有落库）。旧实现在这里把 in-flight 也 `withdraw`，于是 `onMessageDiscarded`
     * 会删掉一张**已经进入持久历史**的手动照片，用户聊天里的图就永久断链了。
     * 现在这里只解除队列自己的引用并保持条目在 in-flight 里，等 job 结束时由
     * `complete(...)`（失败/取消时带 cause）正式收尾 —— 顺带保证 deferred 不会悬空。
     *
     * @return 被清掉的项（pending 已终结；in-flight 只是不再属于队列）。
     */
    fun clear(): List<QueuedMessage> {
        val (pending, running) = synchronized(this) {
            val messages = state.value.messages
            val inFlightNow = inFlight.values.toList()
            mutableState.value = MessageQueueState(paused = true)
            // in-flight 的附件仍然在被发送，必须继续钉住；只释放 pending 的引用。
            refreshPins(emptyList())
            messages to inFlightNow
        }
        // pending 从未落库：临时图与新夹的永久帧都还没有任何持有者，可以安全终结。
        pending.forEach(::withdraw)
        return pending + running
    }

    private fun publish(next: MessageQueueState) {
        refreshPins(next.messages)
        mutableState.value = next
    }

    private fun refreshPins(pending: List<QueuedMessage>) {
        attachmentReferences.update((pending + inFlight.values).flatMap { it.parts })
    }

    /**
     * 撤回 / 弃置一个**从未提交到历史**的队列项。
     *
     * 顺序：先让等待方拿到结果，再把"这次新创建、还没人引用"的文件交出去删除，
     * 最后撤销这一轮的通话授权。
     */
    private fun withdraw(message: QueuedMessage) {
        message.reply.complete(null)
        message.completion.cancel(CancellationException("Queued message was withdrawn"))
        discardTransientImages(message)
        discardTransientAudio(message)
        revokeRequestToken(message)
        onMessageDiscarded?.invoke(message)
    }

    /**
     * 暂停时把一个队列项上的"本轮临时附件"摘干净。
     *
     * 永久手动夹帧仍留在 [QueuedMessage.parts] 里（历史可见），但它**不会被重放**：
     * token 在这里被作废，恢复时又是一次没有 token 的新请求，provider 边界只会按活跃
     * 授权加图。所以提示词不能再说"本轮有图/有录音"，一律改用无附件版本；
     * 万一那个版本仍带着永久帧的附图说明，由 provider 边界按实际交付数量兜底同步。
     */
    private fun stripTransientLocked(message: QueuedMessage): QueuedMessage {
        val hasTransient = message.hasTransientAttachment
        val hasToken = message.requestToken != null
        if (!hasTransient && !hasToken) return message
        return message.copy(
            transientImageUrls = emptyList(),
            transientAudio = null,
            // 追加"录音已失效"的说明：这条消息仍然会作为**普通文字**送出去，
            // 但不能让模型以为它还能听到那段（或者替用户编一段逐字稿）。
            parts = if (message.transientAudio != null) {
                message.parts + UIMessagePart.Text(
                    me.rerere.rikkahub.data.video.VideoVoicePolicy.expiredVoiceNote(
                        message.transientAudio.durationMs,
                    ),
                )
            } else {
                message.parts
            },
            // 恢复队列是一次新请求，这一轮没有任何附件授权：说明必须换成"没有附件"的版本。
            transientSystemPrompt = message.transientSystemPromptWithoutImages
                ?: message.transientSystemPrompt,
            // 恢复队列是一次新请求：旧 token 绝不能复用。
            requestToken = null,
        )
    }

    /** 队列项被放弃时，这一轮的临时图片再没有任何持有者：交给终结回调删掉。 */
    private fun discardTransientImages(message: QueuedMessage) {
        if (message.transientImageUrls.isEmpty()) return
        onDiscardTransientImages?.invoke(message)
    }

    /**
     * 队列项被放弃/暂停时终结这一轮的临时录音。
     *
     * 走录音自己的所有权：`release(fromRequest = false)` 在"请求已经认领"时会返回 false
     * 而**不动文件**，所以不存在"队列把正在序列化的那段删掉"。
     * 也正因为所有权在 [me.rerere.rikkahub.data.video.TransientAudioClip] 自己身上，
     * 这里不需要（也不允许）再按目录扫一遍录音文件。
     */
    private fun discardTransientAudio(message: QueuedMessage) {
        message.transientAudio?.release(fromRequest = false)
    }

    /** 这一轮不会再有生成了：撤销它的通话授权（礼物/留影）。 */
    private fun revokeRequestToken(message: QueuedMessage) {
        val token = message.requestToken ?: return
        onRequestTokenRevoked?.invoke(token)
    }
}
