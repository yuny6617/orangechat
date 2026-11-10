package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.service.ProactiveWakeup
import me.rerere.rikkahub.data.service.ProactiveWakeupRecord
import me.rerere.rikkahub.data.service.ProactiveWakeupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ProactiveWakeupRecordDetailsTest {
    @Test fun `details preserve full multiline reason outcome and all ledger fields`() {
        val reason = "完整约定原因\n" + "不应截断的具体内容。".repeat(100)
        val outcome = "完整执行结果\n" + "结果具体内容。".repeat(100)
        val record = ProactiveWakeupRecord(
            appointment = ProactiveWakeup("role-id", "chat-id", 0, reason, "token-id"),
            status = ProactiveWakeupStatus.Failed,
            outcome = outcome,
            updatedAt = 60_000,
            processId = "process-id",
        )
        val details = proactiveWakeupRecordDetails(record, "角色名称", ZoneId.of("UTC"))
        assertTrue(details.contains("约定原因：\n$reason\n"))
        assertTrue(details.contains("执行结果：\n$outcome\n"))
        listOf("角色：角色名称", "状态：未完成", "约定时间：1970-01-01 00:00:00 Z",
            "更新时间：1970-01-01 00:01:00 Z", "角色 ID：role-id", "会话 ID：chat-id",
            "记录 ID：token-id", "进程 ID：process-id").forEach { assertTrue(it, details.contains(it)) }
        assertEquals(reason, record.appointment.reason)
        assertEquals(outcome, record.outcome)
    }

    @Test fun `pending record has honest missing value labels instead of epoch update time`() {
        val record = ProactiveWakeupRecord(
            ProactiveWakeup("role", "chat", 0, "", "token"), ProactiveWakeupStatus.Pending,
        )
        val details = proactiveWakeupRecordDetails(record, "已删除角色", ZoneId.of("UTC"))
        listOf("状态：等待执行", "更新时间：尚无更新记录", "执行结果：\n暂无执行结果",
            "约定原因：\n未记录", "进程 ID：未记录").forEach { assertTrue(it, details.contains(it)) }
    }

    @Test fun `every persisted status has a distinct display label`() {
        assertEquals(listOf("等待执行", "正在唤醒", "已发送", "未完成", "已取消"),
            ProactiveWakeupStatus.entries.map(::proactiveWakeupStatusLabel))
    }
}
