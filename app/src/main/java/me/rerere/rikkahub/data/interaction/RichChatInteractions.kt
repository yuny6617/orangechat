package me.rerere.rikkahub.data.interaction

import java.math.BigDecimal
import java.math.RoundingMode
import me.rerere.ai.ui.UIMessageAnnotation

typealias ChatInteraction = UIMessageAnnotation.ChatInteraction

fun parseChatAmount(value: String): Long {
    val amount = value.trim().toBigDecimalOrNull() ?: error("请输入有效金额")
    require(amount.signum() > 0) { "金额必须大于 0" }
    require(amount <= BigDecimal("999999999.99")) { "金额过大" }
    return try {
        amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
    } catch (_: ArithmeticException) {
        error("金额最多保留两位小数")
    }
}

fun formatChatAmount(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

/** Shared by manual entry, model tools and persistence, including pretend-rich mode. */
fun parseChatGiftAmount(kind: String, value: String): Long {
    require(kind in setOf("red_packet", "transfer"))
    return parseChatAmount(value).also {
        require(kind != "red_packet" || it <= 20_000L) { "单个红包最高 200 元" }
    }
}

fun ChatInteraction.readableText(): String {
    val title = when {
        kind == "red_packet" -> "红包"
        kind == "transfer" -> "转账"
        // 视频通话的卡与语音通话分开说：用户看到的是"同一张 P4 通话记录卡"，但类型要写对。
        videoCall -> "视频通话"
        else -> "语音通话"
    }
    val state = when (status) {
        "syncing" -> "正在核对钱包"
        "unconfirmed" -> "钱包结果待核实"
        "failed" -> "未发出"
        "connecting" -> "正在连接"
        "accepted" -> if (kind == "call") "通话中" else "已领取"
        "returned" -> "已退回"
        "declined" -> "已拒绝"
        "ended" -> "已结束"
        else -> if (kind == "call") "等待接听" else "待领取"
    }
    val money = if (kind == "call") "" else " ¥${formatChatAmount(amountCents)}（${if (walletLinked) "关联小钱包" else "虚拟金额"}）"
    return "[${if (actor == "assistant") "AI" else "用户"}发起$title]$money · $state" +
        note.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty() + " [互动单号:$id]"
}

/** The recipient alone accepts/returns pending gifts. Final states are idempotent. */
fun validateChatInteractionAction(card: ChatInteraction, actor: String, action: String) {
    require(actor in setOf("user", "assistant"))
    require(action in setOf("accept", "return", "decline", "sync"))
    if (action == "sync") {
        require(card.walletLinked && card.kind != "call") { "无需同步钱包" }
        return
    }
    require(actor != card.actor) { "只有接收方可以处理这条邀请" }
    if (card.kind == "call") {
        require(action in setOf("accept", "decline"))
    } else {
        require(action in setOf("accept", "return"))
    }
    require(card.status in setOf("pending", "accepted", "returned", "declined")) { "请先核实这条记录的状态" }
}
