/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.components.ui.buildCardGroupItems
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.RiskConfirmDialog
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.LargeFlexibleTopAppBar
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.data.service.ProactiveMessageService
import me.rerere.rikkahub.data.service.ProactiveMessageWorker
import me.rerere.rikkahub.data.service.ProactiveActivity
import me.rerere.rikkahub.data.service.ProactiveActivityStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid
import org.koin.compose.koinInject
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingProactiveMessagePage(vm: SettingVM = koinInject()) {
    val context = LocalContext.current
    var hasExactAlarm by remember { mutableStateOf(ProactiveMessageWorker.canScheduleExactAlarms(context)) }
    var ignoresBatteryOptimization by remember {
        mutableStateOf(ProactiveMessageWorker.isIgnoringBatteryOptimizations(context))
    }
    var showBackgroundHelp by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasExactAlarm = ProactiveMessageWorker.canScheduleExactAlarms(context)
        ignoresBatteryOptimization = ProactiveMessageWorker.isIgnoringBatteryOptimizations(context)
    }
    val toaster = LocalToaster.current
    fun openSystemSetting(action: String, fallback: String) {
        val packageUri = Uri.fromParts("package", context.packageName, null)
        runCatching { context.startActivity(Intent(action, packageUri)) }
            .recoverCatching {
                val intent = Intent(fallback)
                if (fallback == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) intent.data = packageUri
                context.startActivity(intent)
            }.onFailure { toaster.show("无法打开系统设置，请在系统设置中手动调整") }
    }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val conversationRepository: ConversationRepository = koinInject()
    var showActivities by remember { mutableStateOf(false) }
    var activityRecords by remember { mutableStateOf<List<ProactiveActivity>>(emptyList()) }
    var activityStatus by remember { mutableStateOf("") }
    LaunchedEffect(showActivities) {
        if (!showActivities) return@LaunchedEffect
        activityStatus = "读取中…"
        activityRecords = emptyList()
        runCatching {
            withContext(Dispatchers.IO) {
                settings.proactiveMessageSetting.assistantConfigs.orEmpty().flatMap { config ->
                    ProactiveActivityStore(context, config.assistantId, config.conversationId).snapshot().map { record ->
                        val name = settings.assistants.find { it.id.toString() == config.assistantId }?.name.orEmpty()
                        record.copy(outcome = "$name：${record.outcome}")
                    }
                }.sortedByDescending { it.timestamp }
            }
        }.onSuccess {
            activityRecords = it
            activityStatus = if (it.isEmpty()) "暂无活动记录" else ""
        }.onFailure { activityStatus = "活动记录读取失败" }
    }
    if (showActivities) {
        AlertDialog(
            onDismissRequest = { showActivities = false },
            title = { Text("主动消息运行记录") },
            text = {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    if (activityStatus.isNotBlank()) item { Text(activityStatus) }
                    items(activityRecords.size, key = { activityRecords[it].id }) { index ->
                        val record = activityRecords[index]
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(java.text.SimpleDateFormat("MM-dd HH:mm", LocalConfiguration.current.locales[0])
                                .format(java.util.Date(record.timestamp)), style = MaterialTheme.typography.labelMedium)
                            Text(record.outcome)
                            if (record.note.isNotBlank()) Text(record.note)
                            if (record.toolNames.isNotEmpty()) Text(record.toolNames.joinToString())
                            Text(if (record.claimedByUserId == null) "待下次聊天接续" else "已接续",
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showActivities = false }) { Text("关闭") } },
        )
    }

    var showProactiveRiskDialog by remember { mutableStateOf(false) }
    var minIntervalDraft by remember { mutableStateOf(settings.proactiveMessageSetting.minIntervalMinutes.toString()) }
    var maxIntervalDraft by remember { mutableStateOf(settings.proactiveMessageSetting.maxIntervalMinutes.toString()) }
    var maxFollowUpDraft by remember { mutableStateOf(settings.proactiveMessageSetting.maxFollowUpMessages.toString()) }
    var editingMinInterval by remember { mutableStateOf(false) }
    var editingMaxInterval by remember { mutableStateOf(false) }
    var editingMaxFollowUps by remember { mutableStateOf(false) }

    LaunchedEffect(settings.proactiveMessageSetting.minIntervalMinutes) {
        if (!editingMinInterval) {
            minIntervalDraft = settings.proactiveMessageSetting.minIntervalMinutes.toString()
        }
    }

    LaunchedEffect(settings.proactiveMessageSetting.maxIntervalMinutes) {
        if (!editingMaxInterval) {
            maxIntervalDraft = settings.proactiveMessageSetting.maxIntervalMinutes.toString()
        }
    }

    LaunchedEffect(settings.proactiveMessageSetting.maxFollowUpMessages) {
        if (!editingMaxFollowUps) {
            maxFollowUpDraft = settings.proactiveMessageSetting.maxFollowUpMessages.toString()
        }
    }

    if (showProactiveRiskDialog) {
        RiskConfirmDialog(
            title = stringResource(R.string.risk_proactive_message_title),
            message = stringResource(R.string.risk_proactive_message_message),
            onConfirm = {
                showProactiveRiskDialog = false
                val newSetting = settings.proactiveMessageSetting.copy(
                    enabled = true,
                    aggressiveModeEnabled = false,
                    globalPolicy = settings.proactiveMessageSetting.globalPolicy.copy(paused = false),
                )
                vm.updateSettings(settings.copy(proactiveMessageSetting = newSetting))
                me.rerere.rikkahub.data.service.DeviceEventAiTriggerService.stop(context)
                ProactiveMessageService.scheduleNext(context, newSetting)
            },
            onDismiss = { showProactiveRiskDialog = false }
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("主动消息") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = settingsScaffoldContainerColor(CustomColors.topBarColors.containerColor),
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("主动联系") },
                ) {
                    item(
                        headlineContent = { Text("启用主动消息") },
                        supportingContent = { Text("按设定时段与间隔判断是否联系") },
                        trailingContent = {
                            Switch(
                                checked = settings.proactiveMessageSetting.enabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        showProactiveRiskDialog = true
                                    } else {
                                        val newSetting = settings.proactiveMessageSetting.copy(enabled = false)
                                        vm.updateSettings(settings.copy(proactiveMessageSetting = newSetting))
                                        ProactiveMessageService.cancel(context)
                                    }
                                }
                            )
                        }
                    )
                    item(
                        headlineContent = { Text("允许 AI 约定主动唤醒") },
                        supportingContent = {
                            Text("开启后，各角色可为当前会话约定下次几点几分来找你；到点读取最新聊天生成回复。" +
                                "每个会话保留一个待执行约定，遵守总开关和免打扰；有约定时等待约定时间，无约定时按原间隔检查。")
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.proactiveMessageSetting.allowAiScheduledWakeup,
                                enabled = !settings.init,
                                onCheckedChange = { enabled ->
                                    vm.updateProactiveSetting(
                                        update = { it.copy(allowAiScheduledWakeup = enabled) },
                                        onUpdated = { updated ->
                                            if (!updated.allowAiScheduledWakeup) {
                                                me.rerere.rikkahub.data.service.ProactiveWakeupStore(context).clear()
                                            }
                                            ProactiveMessageService.scheduleNext(context, updated)
                                        },
                                    )
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("手动触发一次") },
                        supportingContent = {
                            Text("本次忽略活跃时段，仍遵守连续追问上限。")
                        },
                        trailingContent = {
                            Button(
                                onClick = {
                                    ProactiveMessageService.triggerNow(
                                        context,
                                        settings.proactiveMessageSetting,
                                    )
                                },
                                enabled = settings.proactiveMessageSetting.enabled,
                            ) {
                                Text("立即触发")
                            }
                        },
                    )
                    if (settings.proactiveMessageSetting.enabled) {
                        var nextTime by remember { mutableStateOf(ProactiveMessageService.getNextTriggerTime(context)) }
                        LaunchedEffect(settings.proactiveMessageSetting) {
                            // The trigger timestamp may be written by the service just after
                            // this page opens. Poll quickly during that handoff, then settle
                            // into a low-frequency refresh while the page remains visible.
                            var attempts = 0
                            while (nextTime == null && attempts < 12) {
                                nextTime = ProactiveMessageService.getNextTriggerTime(context)
                                attempts++
                                if (nextTime == null) {
                                    kotlinx.coroutines.delay(500L)
                                }
                            }
                            while (true) {
                                kotlinx.coroutines.delay(10_000L)
                                nextTime = ProactiveMessageService.getNextTriggerTime(context)
                            }
                        }
                        item(
                            headlineContent = { Text("下次触发时间") },
                            supportingContent = {
                                val currentTime = System.currentTimeMillis()
                                val triggerTime = nextTime
                                if (triggerTime != null && triggerTime > currentTime) {
                                    val remaining = triggerTime - currentTime
                                    val remainMinutes = remaining / 60_000
                                    val remainSeconds = (remaining % 60_000) / 1000
                                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", LocalConfiguration.current.locales[0])
                                    Text("🕐 ${sdf.format(java.util.Date(triggerTime))}（剩余 ${remainMinutes}分${remainSeconds}秒）")
                                } else {
                                    Text("等待调度中...")
                                }
                            }
                        )
                    }
                }
            }
            item { ProactiveParticipantsSettings(settings, vm) }
            item {
                ProactiveWakeupSettingsCard(settings.proactiveMessageSetting, settings.assistants) {
                    ProactiveMessageService.scheduleNext(context, vm.settings.value.proactiveMessageSetting)
                }
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("活跃时段与工具") },
                ) {
                    item(
                        headlineContent = { Text("限定活跃时段") },
                        trailingContent = {
                            Switch(
                                checked = settings.proactiveMessageSetting.activeHoursEnabled,
                                onCheckedChange = { enabled ->
                                    val updated = settings.proactiveMessageSetting.copy(activeHoursEnabled = enabled)
                                    vm.updateSettings(settings.copy(proactiveMessageSetting = updated))
                                    ProactiveMessageService.scheduleNext(context, updated)
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("查看主动消息运行记录") },
                        onClick = { showActivities = true },
                    )
                    if (settings.proactiveMessageSetting.activeHoursEnabled) {
                        item(
                            headlineContent = { Text("活跃时段（本地时间；起止相同为全天）") },
                            supportingContent = {
                                val current = settings.proactiveMessageSetting
                                var start by remember(current.activeStartHour) { mutableStateOf(current.activeStartHour.toFloat()) }
                                var end by remember(current.activeEndHour) { mutableStateOf(current.activeEndHour.toFloat()) }
                                Column {
                                    Text("开始 ${start.toInt().toString().padStart(2, '0')}:00")
                                    Slider(
                                        value = start, onValueChange = { start = it }, valueRange = 0f..23f, steps = 22,
                                        onValueChangeFinished = {
                                            val updated = current.copy(activeStartHour = start.toInt())
                                            vm.updateSettings(settings.copy(proactiveMessageSetting = updated))
                                            ProactiveMessageService.scheduleNext(context, updated)
                                        },
                                    )
                                    Text("结束 ${end.toInt().toString().padStart(2, '0')}:00")
                                    Slider(
                                        value = end, onValueChange = { end = it }, valueRange = 0f..23f, steps = 22,
                                        onValueChangeFinished = {
                                            val updated = current.copy(activeEndHour = end.toInt())
                                            vm.updateSettings(settings.copy(proactiveMessageSetting = updated))
                                            ProactiveMessageService.scheduleNext(context, updated)
                                        },
                                    )
                                }
                            },
                        )
                    }
                    item(
                        headlineContent = { Text("完整工具轮次比例") },
                        supportingContent = {
                            val current = settings.proactiveMessageSetting
                            var chance by remember(current.fullToolChancePercent) {
                                mutableStateOf(current.fullToolChancePercent.toFloat())
                            }
                            Column {
                                Text("${chance.toInt()}% 完整工具 / ${100 - chance.toInt()}% 轻量")
                                Slider(
                                    value = chance, onValueChange = { chance = it }, valueRange = 0f..100f, steps = 9,
                                    onValueChangeFinished = {
                                        vm.updateSettings(settings.copy(
                                            proactiveMessageSetting = current.copy(fullToolChancePercent = chance.toInt()),
                                        ))
                                    },
                                )
                            }
                        },
                    )
                }
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("间隔与连续追问") },
                ) {
                    item(
                        headlineContent = { Text("最小间隔 (分钟)") },
                        supportingContent = {
                            OutlinedTextField(
                                value = minIntervalDraft,
                                onValueChange = { value ->
                                    minIntervalDraft = value.filter(Char::isDigit)
                                },
                                placeholder = { Text("30") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .onFocusChanged { state ->
                                        editingMinInterval = state.isFocused
                                        if (!state.isFocused) {
                                            val current = settings.proactiveMessageSetting
                                            val minutes = minIntervalDraft.toIntOrNull()
                                                ?.coerceAtLeast(1)
                                                ?.coerceAtMost(current.maxIntervalMinutes.coerceAtLeast(1))
                                                ?: current.minIntervalMinutes
                                            minIntervalDraft = minutes.toString()
                                            if (minutes != current.minIntervalMinutes) {
                                                vm.updateSettings(
                                                    settings.copy(
                                                        proactiveMessageSetting = current.copy(
                                                            minIntervalMinutes = minutes,
                                                        ),
                                                    ),
                                                )
                                            }
                                        }
                                    },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("最大间隔 (分钟)") },
                        supportingContent = {
                            OutlinedTextField(
                                value = maxIntervalDraft,
                                onValueChange = { value ->
                                    maxIntervalDraft = value.filter(Char::isDigit)
                                },
                                placeholder = { Text("90") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .onFocusChanged { state ->
                                        editingMaxInterval = state.isFocused
                                        if (!state.isFocused) {
                                            val current = settings.proactiveMessageSetting
                                            val minutes = maxIntervalDraft.toIntOrNull()
                                                ?.coerceAtLeast(current.minIntervalMinutes)
                                                ?: current.maxIntervalMinutes
                                            maxIntervalDraft = minutes.toString()
                                            if (minutes != current.maxIntervalMinutes) {
                                                vm.updateSettings(
                                                    settings.copy(
                                                        proactiveMessageSetting = current.copy(
                                                            maxIntervalMinutes = minutes,
                                                        ),
                                                    ),
                                                )
                                            }
                                        }
                                    },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("最多连续追问") },
                        supportingContent = {
                            OutlinedTextField(
                                value = maxFollowUpDraft,
                                enabled = !settings.init,
                                onValueChange = { value ->
                                    val digitsOnly = value.filter(Char::isDigit).take(1)
                                    maxFollowUpDraft = digitsOnly
                                    digitsOnly.toIntOrNull()?.takeIf { it in 1..8 }?.let { count ->
                                        vm.updateProactiveSetting { it.copy(maxFollowUpMessages = count) }
                                    }
                                },
                                placeholder = { Text("1～8") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .onFocusChanged { state ->
                                        if (editingMaxFollowUps && !state.isFocused) {
                                            maxFollowUpDraft = settings.proactiveMessageSetting.maxFollowUpMessages.toString()
                                        }
                                        editingMaxFollowUps = state.isFocused
                                    },
                            )
                            Text("同一次沉默后最多发送几次，可设置 1～8 次；重新开口后自动清零。")
                        },
                    )
                    item(
                        headlineContent = { Text("不许沉默") },
                        supportingContent = { Text("你老婆把你沉默的按键扣了。主动发消息会让老婆觉得你在惦记着她。仍遵守活跃时段和追问上限。") },
                        trailingContent = {
                            Switch(
                                checked = settings.proactiveMessageSetting.alwaysRespond,
                                enabled = !settings.init,
                                onCheckedChange = { enabled ->
                                    vm.updateProactiveSetting { it.copy(alwaysRespond = enabled) }
                                },
                            )
                        },
                    )
                }
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("应用使用信息") },
                ) {
                    item(
                        headlineContent = { Text("允许主动消息查岗") },
                        supportingContent = {
                            Text(
                                "开启后，AI 可在主动消息判断确有需要时调用应用使用工具；" +
                                    "关闭后，主动消息不会读取应用使用情况。此设置不影响正常聊天中的工具使用。",
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.proactiveMessageSetting.allowProactiveAppUsage,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings(
                                        settings.copy(
                                            proactiveMessageSetting = settings.proactiveMessageSetting.copy(
                                                allowProactiveAppUsage = enabled,
                                            ),
                                        ),
                                    )
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("自动附带最近应用时间线") },
                        supportingContent = { Text("最近6小时的应用切换会发给当前模型；需同时允许查岗和系统应用使用权限。") },
                        trailingContent = {
                            Switch(
                                checked = settings.proactiveMessageSetting.includeAppTimeline,
                                enabled = settings.proactiveMessageSetting.allowProactiveAppUsage,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings(settings.copy(
                                        proactiveMessageSetting = settings.proactiveMessageSetting.copy(
                                            includeAppTimeline = enabled,
                                        ),
                                    ))
                                },
                            )
                        },
                    )
                }
            }
            item {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp), title = { Text("后台运行") },
                    items = buildCardGroupItems {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        item(
                            headlineContent = { Text("精确闹钟权限") },
                            supportingContent = {
                                Text(if (hasExactAlarm) "已授予 · 定时触发更准确" else "未授予 · 已启用备用调度，可能延迟")
                            },
                            onClick = if (hasExactAlarm) null else ({
                                openSystemSetting(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            }),
                        )
                    }
                    item(
                        headlineContent = { Text("电池优化") },
                        supportingContent = {
                            Text(if (ignoresBatteryOptimization) "已忽略 · 后台触发更稳定" else "未忽略 · 系统可能限制后台触发")
                        },
                        onClick = if (ignoresBatteryOptimization) null else ({
                            openSystemSetting(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        }),
                    )
                    item(
                        headlineContent = { Text("后台活动说明") },
                        supportingContent = if (showBackgroundHelp) ({
                            Text("等待和行动简记保存在本机，下一次聊天接续；发送过的消息保留在原对话。自动时间线默认关闭。后台触发仍受手机省电与权限限制。")
                        }) else null,
                        trailingContent = { Text(if (showBackgroundHelp) "收起" else "展开",
                            style = MaterialTheme.typography.labelMedium) },
                        onClick = { showBackgroundHelp = !showBackgroundHelp },
                    )
                })
            }
        }
    }
}
