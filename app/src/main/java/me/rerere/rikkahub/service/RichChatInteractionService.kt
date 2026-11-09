package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.interaction.*
import me.rerere.rikkahub.data.model.toMessageNode
import kotlin.uuid.Uuid

suspend fun ChatService.createChatInteraction(
    conversationId: Uuid, assistantId: Uuid, actor: String, kind: String,
    amount: String = "", note: String = "", requestId: String, walletLinked: Boolean? = null,
): ChatInteraction {
    require(actor in setOf("user", "assistant"))
    require(kind in setOf("red_packet", "transfer", "call"))
    require(requestId.length in 1..128 && note.length <= 200)
    val cents = if (kind == "call") 0 else parseChatGiftAmount(kind, amount)
    val id = java.util.UUID.nameUUIDFromBytes("$conversationId:$assistantId:$actor:$requestId".toByteArray()).toString()
    val session = getOrCreateSession(conversationId)
    return session.saveMutex.withLock {
        session.requireActive()
        check(interactionConversationExists(conversationId) || session.state.value.newConversation) { "聊天已删除" }
        val conversation = session.state.value
        check(conversation.assistantId == assistantId) { "助手已改变" }
        val assistant = interactionSettings.settingsFlow.value.assistants.firstOrNull { it.id == assistantId }
            ?: error("助手不存在")
        val existing = conversation.currentMessages.flatMap { it.annotations }.filterIsInstance<ChatInteraction>()
        existing.firstOrNull { it.id == id }?.let {
            require(it.actor == actor && it.kind == kind && it.amountCents == cents && it.note == note) { "请勿重复使用不同内容的单号" }
            require(walletLinked == null || it.walletLinked == walletLinked) { "该单金额模式已确定，请勿以同一单号更改" }
            return@withLock it
        }
        if (kind == "call") {
            check(actor != "assistant" || assistant.allowIncomingVoiceCalls) { "AI 来电已关闭" }
            check(!VoiceCallService.isRunning()) { "当前有通话进行中" }
            existing.firstOrNull { it.kind == "call" && it.status == "pending" }?.let {
                if (it.actor == "assistant") pendingIncomingCall.value = it
                return@withLock it
            }
        }
        val linked = kind != "call" && resolvePaymentWalletMode(actor, assistant.pretendRich, walletLinked)
        var card = ChatInteraction(id, conversationId.toString(), assistantId.toString(), actor, kind,
            status = if (linked) "syncing" else "pending", amountCents = cents, note = note,
            theme = assistant.chatInteractionTheme, walletLinked = linked,
            createdAtMillis = System.currentTimeMillis(), pendingAction = if (linked) "create" else null)
        val message = UIMessage(role = if (actor == "user") MessageRole.USER else MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(card.readableText())), annotations = listOf(card))
        saveConversationLocked(conversationId, conversation.copy(messageNodes = conversation.messageNodes + message.toMessageNode()))
        if (kind == "call" && actor == "assistant") pendingIncomingCall.value = card
        if (linked) {
            card = settleChatWallet(card, "create")
            replaceChatInteractionLocked(conversationId, card)
        }
        card
    }
}

/** Called while holding the conversation's save mutex. */
private suspend fun ChatService.replaceChatInteractionLocked(conversationId: Uuid, card: ChatInteraction) {
    check(interactionConversationExists(conversationId)) { "聊天已删除；钱包记录仍可通过单号核对" }
    val session = getOrCreateSession(conversationId)
    val current = session.state.value
    check(current.assistantId.toString() == card.assistantId)
    saveConversationLocked(conversationId, current.copy(messageNodes = current.messageNodes.map { node ->
        node.copy(messages = node.messages.map { message ->
            if (message.annotations.any { it is ChatInteraction && it.id == card.id })
                message.copy(parts = listOf(UIMessagePart.Text(card.readableText())), annotations = listOf(card))
            else message
        })
    }))
}

private suspend fun ChatService.settleChatWallet(card: ChatInteraction, operation: String): ChatInteraction {
    return try {
        val receipt = interactionPlugins.callChatWallet(buildJsonObject {
            put("operation", operation); put("payment_id", card.id)
            put("scope", "${card.assistantId}:${card.conversationId}")
            put("direction", if (card.actor == "assistant") "expense" else "income")
            put("kind", card.kind); put("amount_cents", card.amountCents); put("note", card.note)
        })
        card.applyWalletReceipt(receipt, operation)
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) {
        card.copy(status = "unconfirmed", pendingAction = card.pendingAction ?: operation,
            error = e.message ?: "钱包调用异常：${e.javaClass.simpleName}")
    }
}

suspend fun ChatService.respondChatInteraction(conversationId: Uuid, id: String, actor: String, action: String): ChatInteraction {
    val session = getOrCreateSession(conversationId)
    return session.saveMutex.withLock {
        session.requireActive()
        val current = session.state.value
        val original = current.currentMessages.flatMap { it.annotations }.filterIsInstance<ChatInteraction>().firstOrNull { it.id == id }
            ?: error("没有找到这条互动")
        check(original.conversationId == conversationId.toString() && original.assistantId == current.assistantId.toString()) { "分支副本不能再次操作原记录" }
        validateChatInteractionAction(original, actor, action)
        if (original.status in setOf("accepted", "returned", "declined", "ended")) return@withLock original
        var card = original
        if (card.walletLinked) {
            val operation = chatWalletOperation(action)
            card = card.copy(status = "syncing", pendingAction = if (action == "sync") card.pendingAction else operation, error = null)
            replaceChatInteractionLocked(conversationId, card)
            card = settleChatWallet(card, operation)
        } else {
            if (card.kind == "call" && action == "accept") check(!VoiceCallService.isRunning()) { "当前有通话进行中" }
            card = card.copy(status = when (action) { "accept" -> if (card.kind == "call") "connecting" else "accepted"; "return" -> "returned"; else -> "declined" },
                connectedAtMillis = null)
        }
        replaceChatInteractionLocked(conversationId, card)
        if (card.kind == "call" && pendingIncomingCall.value?.id == card.id && card.status != "pending") pendingIncomingCall.value = null
        card
    }
}

internal suspend fun ChatService.endChatInteractionCalls(conversationId: Uuid, startedAt: Long? = null,
    endedAt: Long = System.currentTimeMillis(), transcript: List<me.rerere.ai.ui.CallTranscriptEntry>? = null,
    connectingIds: Set<String> = emptySet(), giftSummary: String = "") {
    val session = getOrCreateSession(conversationId)
    session.saveMutex.withLock {
        val cards = session.state.value.currentMessages.flatMap { it.annotations }.filterIsInstance<ChatInteraction>()
            .filter { it.kind == "call" && it.status in setOf("accepted", "connecting") && it.conversationId == conversationId.toString() }
        cards.filter { it.belongsToEndingCall(startedAt, connectingIds) }.forEach {
            replaceChatInteractionLocked(conversationId, it.copy(status = "ended", endedAtMillis = endedAt,
                callTranscript = transcript ?: it.callTranscript,
                giftSummary = giftSummary.ifBlank { it.giftSummary }))
        }
    }
}

internal suspend fun ChatService.beginChatInteractionCall(conversationId: Uuid, startedAt: Long) {
    val session = getOrCreateSession(conversationId)
    session.saveMutex.withLock {
        session.requireActive()
        val current = session.state.value
        val existing = current.currentMessages.flatMap { it.annotations }.filterIsInstance<ChatInteraction>()
            .lastOrNull { it.kind == "call" && it.status == "connecting" && it.conversationId == conversationId.toString() }
        if (existing != null) {
            replaceChatInteractionLocked(conversationId, existing.copy(status = "accepted", connectedAtMillis = startedAt))
        } else {
            val assistant = interactionSettings.settingsFlow.value.assistants.firstOrNull { it.id == current.assistantId } ?: return@withLock
            val card = ChatInteraction(Uuid.random().toString(), conversationId.toString(), current.assistantId.toString(), "user", "call",
                status = "accepted", theme = assistant.chatInteractionTheme, createdAtMillis = startedAt, connectedAtMillis = startedAt)
            val message = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(card.readableText())), annotations = listOf(card))
            saveConversationLocked(conversationId, current.copy(messageNodes = current.messageNodes + message.toMessageNode()))
        }
    }
}
