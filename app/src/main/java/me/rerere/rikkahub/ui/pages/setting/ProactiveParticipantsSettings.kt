package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.AssistantProactiveConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.service.ProactiveMessageService
import me.rerere.rikkahub.data.service.ProactiveTaskExecutor
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.ui.CardGroup
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * The participant list lives on the existing proactive-message page. A participant that has no
 * per-assistant override uses the global proactive settings; expanding a row and turning on
 * "另设该助手" is the only way to reveal the optional cooldown/limit/prompt/active-hours fields.
 */
@Composable
internal fun ProactiveParticipantsSettings(settings: Settings, vm: SettingVM) {
    val context = LocalContext.current
    val repository: ConversationRepository = koinInject()
    val store: SettingsStore = koinInject()
    val chat: ChatService = koinInject()
    LaunchedEffect(settings.init) {
        if (!settings.init) withContext(Dispatchers.IO) {
            ProactiveTaskExecutor(context, store, repository, chat).migrate()
        }
    }
    // The old pause switch was removed from this page. Clear a legacy paused flag so the
    // remaining enable switch and manual trigger are not silently blocked by hidden state.
    LaunchedEffect(Unit) {
        if (settings.proactiveMessageSetting.globalPolicy.paused) {
            vm.updateProactiveSetting { current ->
                current.copy(globalPolicy = current.globalPolicy.copy(paused = false))
            }
        }
    }
    var selecting by remember { mutableStateOf<String?>(null) }
    var windows by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var expandedAssistantId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selecting) {
        windows = selecting?.let { id -> withContext(Dispatchers.IO) {
            repository.getConversationsOfAssistant(Uuid.parse(id)).first()
        } }.orEmpty()
    }
    fun update(config: AssistantProactiveConfig) {
        ProactiveTaskExecutor.cancel(context, config.assistantId)
        vm.updateProactiveSetting(update = { current -> current.copy(
            assistantConfigs = current.assistantConfigs.orEmpty().filterNot { it.assistantId == config.assistantId } + config,
        ) })
    }
    if (selecting != null) {
        AlertDialog(
            onDismissRequest = { selecting = null },
            title = { Text("选择投递窗口") },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    if (windows.isEmpty()) item { Text("请先为该助手创建聊天窗口") }
                    items(windows.size, key = { windows[it].id }) { index ->
                        val window = windows[index]
                        TextButton(onClick = {
                            val id = selecting!!
                            val old = settings.proactiveMessageSetting.assistantConfigs.orEmpty()
                                .singleOrNull { it.assistantId == id }
                            update((old ?: AssistantProactiveConfig(id, window.id.toString())).copy(
                                conversationId = window.id.toString(),
                                enabled = true,
                            ))
                            selecting = null
                            expandedAssistantId = id
                        }) { Text(window.title.ifBlank { window.id.toString() }) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selecting = null }) { Text("关闭") } },
        )
    }

    val enabledCount = settings.proactiveMessageSetting.assistantConfigs.orEmpty().count { it.enabled }
    CardGroup(Modifier.padding(horizontal = 8.dp), title = { Text("参与助手") }) {
        item(
            headlineContent = { Text("参与助手的独立投递窗口") },
            supportingContent = {
                Text("每个助手固定使用自己的一个聊天窗口；目前 $enabledCount 位参与。默认跟随全局间隔、每日上限、提示和活跃时段。")
            },
        )
        settings.assistants.forEach { assistant ->
            val assistantId = assistant.id.toString()
            val config = settings.proactiveMessageSetting.assistantConfigs.orEmpty()
                .singleOrNull { it.assistantId == assistantId }
            val expanded = expandedAssistantId == assistantId
            item(
                headlineContent = { Text(assistant.name.ifBlank { "助手" }) },
                supportingContent = {
                    Text(
                        when {
                            config == null -> "尚未参与；点按选择投递窗口"
                            !config.enabled -> "已暂停参与；点按重新启用"
                            expanded -> "固定窗口：${config.conversationId}"
                            else -> "固定窗口：${config.conversationId} · 点按展开另设"
                        },
                    )
                },
                onClick = {
                    if (config?.enabled == true) {
                        expandedAssistantId = if (expanded) null else assistantId
                    } else {
                        selecting = assistantId
                    }
                },
                trailingContent = {
                    Switch(
                        checked = config?.enabled == true,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                if (config == null) selecting = assistantId
                                else {
                                    update(config.copy(enabled = true))
                                    expandedAssistantId = assistantId
                                }
                            } else {
                                config?.let { update(it.copy(enabled = false)) }
                                expandedAssistantId = null
                            }
                        },
                    )
                },
            )
            if (config != null && config.enabled && expanded) {
                item(
                    headlineContent = { Text("另设该助手") },
                    supportingContent = {
                        Text(
                            if (config.customEnabled) "已启用此助手的独立冷却、每日上限、提示和活跃时段。"
                            else "关闭时使用全局设置；打开后才会显示此助手的单独设置。",
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = config.customEnabled,
                            onCheckedChange = { custom ->
                                update(config.copy(customEnabled = custom))
                            },
                        )
                    },
                )
                if (config.customEnabled) {
                    item(
                        headlineContent = { Text("此助手单独设置") },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                var cooldown by remember(config.cooldownMinutes) {
                                    mutableStateOf(config.cooldownMinutes.toString())
                                }
                                var daily by remember(config.dailyLimit) {
                                    mutableStateOf(config.dailyLimit.toString())
                                }
                                OutlinedTextField(
                                    cooldown,
                                    onValueChange = { value ->
                                        cooldown = value.filter(Char::isDigit).take(5)
                                        cooldown.toIntOrNull()?.takeIf { it in 1..10080 }?.let {
                                            update(config.copy(cooldownMinutes = it))
                                        }
                                    },
                                    label = { Text("冷却分钟（1–10080）") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    daily,
                                    onValueChange = { value ->
                                        daily = value.filter(Char::isDigit).take(3)
                                        daily.toIntOrNull()?.takeIf { it in 1..100 }?.let {
                                            update(config.copy(dailyLimit = it))
                                        }
                                    },
                                    label = { Text("每日任务上限（1–100）") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    config.promptOverride.orEmpty(),
                                    onValueChange = { value ->
                                        update(config.copy(promptOverride = value.take(4000).ifBlank { null }))
                                    },
                                    label = { Text("此助手额外主动消息提示") },
                                    maxLines = 3,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text("另设此助手的活跃时段（仍遵守全局安静时段）", style = MaterialTheme.typography.bodySmall)
                                RowSwitch(
                                    checked = config.activeHoursEnabled == true,
                                    onCheckedChange = { update(config.copy(activeHoursEnabled = it)) },
                                )
                                if (config.activeHoursEnabled == true) {
                                    OutlinedTextField(
                                        (config.activeStartHour ?: settings.proactiveMessageSetting.activeStartHour).toString(),
                                        onValueChange = { value ->
                                            value.toIntOrNull()?.takeIf { it in 0..23 }?.let {
                                                update(config.copy(activeStartHour = it))
                                            }
                                        },
                                        label = { Text("开始小时 0–23") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedTextField(
                                        (config.activeEndHour ?: settings.proactiveMessageSetting.activeEndHour).toString(),
                                        onValueChange = { value ->
                                            value.toIntOrNull()?.takeIf { it in 0..23 }?.let {
                                                update(config.copy(activeEndHour = it))
                                            }
                                        },
                                        label = { Text("结束小时 0–23（相同为全天）") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        },
                    )
                }
                item(
                    headlineContent = { Text("投递窗口") },
                    supportingContent = { Text(config.conversationId) },
                    trailingContent = {
                        TextButton(onClick = { selecting = assistantId }) { Text("更换") }
                    },
                )
            }
        }
    }
}

@Composable
private fun RowSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(checked = checked, onCheckedChange = onCheckedChange)
}
