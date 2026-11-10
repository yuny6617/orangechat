package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.service.ProactiveWakeupRecord
import me.rerere.rikkahub.data.service.ProactiveWakeupStatus
import me.rerere.rikkahub.data.service.ProactiveWakeupStore
import me.rerere.rikkahub.data.service.nextProactiveActiveTime
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.buildCardGroupItems
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Reads only the small appointment ledger while this settings page is visible. */
@Composable
internal fun ProactiveWakeupSettingsCard(
    setting: ProactiveMessageSetting,
    assistants: List<Assistant>,
    onCancelled: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val store = remember(context) { ProactiveWakeupStore(context) }
    var records by remember { mutableStateOf<List<ProactiveWakeupRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var cancelling by remember { mutableStateOf<String?>(null) }
    var selectedRecord by remember { mutableStateOf<ProactiveWakeupRecord?>(null) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(lifecycle, store) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    records = withContext(Dispatchers.IO) { store.snapshot() }
                    error = ""
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    error = "后台活动记录读取失败，稍后重试"
                }
                loading = false
                delay(3_000)
            }
        }
    }
    val zone = ZoneId.systemDefault()
    val formatter = remember { DateTimeFormatter.ofPattern("MM-dd HH:mm") }
    fun time(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).format(formatter)
    // Resolve the selection against each fresh snapshot so a running record's result stays current.
    selectedRecord?.let { selected ->
        val record = records.firstOrNull { it.appointment.token == selected.appointment.token } ?: selected
        val role = assistants.firstOrNull { it.id.toString() == record.appointment.assistantId }
            ?.name?.ifBlank { "AI" } ?: "已删除角色"
        AlertDialog(
            onDismissRequest = { selectedRecord = null },
            title = { Text("后台活动详情") },
            text = {
                SelectionContainer {
                    Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                        Text(proactiveWakeupRecordDetails(record, role, zone))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedRecord = null }) { Text("关闭") }
            },
        )
    }
    val items = buildCardGroupItems {
        item(
            headlineContent = { Text("查看主动唤醒记录") },
            supportingContent = { Text("AI 约定唤醒的待执行、执行中及最近 10 条已结束记录；点开记录查看完整内容。") },
            trailingContent = { Text(if (expanded) "收起" else "展开") },
            onClick = { expanded = !expanded },
        )
        if (!expanded) return@buildCardGroupItems
        if (error.isNotEmpty() || loading || records.isEmpty()) {
            item(headlineContent = {
                Text(error.ifEmpty { if (loading) "读取中…" else "暂无后台活动记录" })
            })
        }
        records.sortedWith(compareBy<ProactiveWakeupRecord> {
            when (it.status) {
                ProactiveWakeupStatus.Pending -> 0
                ProactiveWakeupStatus.Running -> 1
                else -> 2
            }
        }.thenBy {
            if (it.status == ProactiveWakeupStatus.Pending) it.appointment.timeMillis else -it.updatedAt
        }).forEach { record ->
            val appointment = record.appointment
            val role = assistants.firstOrNull { it.id.toString() == appointment.assistantId }
                ?.name?.ifBlank { "AI" } ?: "已删除角色"
            val status = when (record.status) {
                ProactiveWakeupStatus.Pending -> when {
                    !setting.enabled -> "等待执行 · 主动消息已暂停"
                    !setting.allowAiScheduledWakeup -> "等待执行 · AI 约定唤醒已关闭"
                    else -> {
                        val earliest = appointment.timeMillis.coerceAtLeast(System.currentTimeMillis())
                        val effective = nextProactiveActiveTime(setting, earliest, zone)
                        if (effective > earliest) "等待执行 · 免打扰顺延至 ${time(effective)}"
                        else "等待执行"
                    }
                }
                else -> proactiveWakeupStatusLabel(record.status)
            }
            item(
                onClick = { selectedRecord = record },
                headlineContent = { Text("$role · ${time(appointment.timeMillis)} · $status") },
                supportingContent = {
                    Text(listOf(appointment.reason, record.outcome).filter { it.isNotBlank() }.joinToString("\n"),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                trailingContent = if (record.status == ProactiveWakeupStatus.Pending) ({
                    TextButton(
                        enabled = cancelling == null,
                        onClick = {
                            cancelling = appointment.token
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        store.cancel(appointment.conversationId, appointment.token)
                                        onCancelled()
                                    }
                                    records = withContext(Dispatchers.IO) { store.snapshot() }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    error = "取消或重新安排失败，请刷新记录确认"
                                } finally {
                                    cancelling = null
                                }
                            }
                        },
                    ) { Text("取消") }
                }) else null,
            )
        }
    }
    CardGroup(items = items, modifier = Modifier.padding(horizontal = 8.dp),
        title = { Text("后台活动记录") })
}

internal fun proactiveWakeupStatusLabel(status: ProactiveWakeupStatus): String = when (status) {
    ProactiveWakeupStatus.Pending -> "等待执行"
    ProactiveWakeupStatus.Running -> "正在唤醒"
    ProactiveWakeupStatus.Sent -> "已发送"
    ProactiveWakeupStatus.Failed -> "未完成"
    ProactiveWakeupStatus.Cancelled -> "已取消"
}

/** Full ledger content, deliberately separate from the truncated list preview. */
internal fun proactiveWakeupRecordDetails(
    record: ProactiveWakeupRecord,
    assistantName: String,
    zone: ZoneId,
): String {
    val appointment = record.appointment
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")
    fun time(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).format(formatter)
    return buildString {
        appendLine("类型：AI 约定主动唤醒")
        appendLine("角色：$assistantName")
        appendLine("约定时间：${time(appointment.timeMillis)}")
        appendLine("状态：${proactiveWakeupStatusLabel(record.status)}")
        appendLine("更新时间：${if (record.updatedAt > 0) time(record.updatedAt) else "尚无更新记录"}")
        appendLine()
        appendLine("约定原因：")
        appendLine(appointment.reason.ifBlank { "未记录" })
        appendLine()
        appendLine("执行结果：")
        appendLine(record.outcome.ifBlank { "暂无执行结果" })
        appendLine()
        appendLine("角色 ID：${appointment.assistantId}")
        appendLine("会话 ID：${appointment.conversationId}")
        appendLine("记录 ID：${appointment.token}")
        append("进程 ID：${record.processId.ifBlank { "未记录" }}")
    }
}
