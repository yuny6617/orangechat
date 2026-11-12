/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import me.rerere.rikkahub.data.interaction.withInteractionRequestScope

import me.rerere.rikkahub.data.ai.UsageRecorder
import me.rerere.rikkahub.data.ai.UsageSource
import me.rerere.rikkahub.data.ai.UsageTag

import me.rerere.rikkahub.data.sync.isChatRecoveryBlocking
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import androidx.core.app.NotificationCompat
import android.os.Build
import android.os.PowerManager
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationToolApproval
import me.rerere.rikkahub.data.ai.GenerationToolUpdate
import me.rerere.rikkahub.data.ai.generateModelTurn
import me.rerere.rikkahub.data.ai.runGenerationToolLoop
import me.rerere.rikkahub.data.ai.buildAssistantGenerationParams
import me.rerere.rikkahub.data.ai.executeGenerationToolBatch
import me.rerere.rikkahub.data.ai.executionCheckpointState
import me.rerere.rikkahub.data.ai.hasDurableToolEvidence
import me.rerere.rikkahub.data.ai.replaceLastGenerationTools
import me.rerere.rikkahub.data.ai.recoverToolHistoryForRequest
import me.rerere.rikkahub.data.ai.withExecutionCheckpoint
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.collectInjections
import me.rerere.rikkahub.data.ai.transformers.ExtraInfoInjectionCollector
import me.rerere.rikkahub.data.ai.transformers.forProactiveExtraInfo
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.interaction.ChatInteractionRepository
import me.rerere.rikkahub.data.interaction.ReactionContextTurn
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.rikkahub.data.datastore.effectivePromptOverride
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.model.withConversationConfig
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.video.cameraFreeRequests
import me.rerere.rikkahub.data.video.withoutHistoricalCameraImages
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.generationMessageMutation
import me.rerere.rikkahub.utils.sendNotification
import kotlin.uuid.Uuid
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class ProactiveMessageService {

    companion object {
        const val TAG = "ProactiveMessageService"
        const val ACTION_PROACTIVE_MESSAGE = "me.rerere.orangechat.PROACTIVE_MESSAGE"
        const val EXTRA_TRIGGER_SOURCE = "proactive_trigger_source"
        const val EXTRA_FORCE_TRIGGER = "force_trigger"
        private const val REQUEST_CODE = 10001

        internal const val PREFS_NAME = "proactive_message_prefs"
        private const val KEY_NEXT_TRIGGER_TIME = "next_trigger_time"
        private const val KEY_DURABLE_NEXT_SLOT = "durable_next_slot_ms"
        internal const val MAX_DURABLE_SLOT_MINUTES = 7 * 24 * 60

        fun scheduleNext(
            context: Context,
            setting: ProactiveMessageSetting,
            delayMinutesOverride: Int? = null,
        ) {
            ProactiveTaskExecutor.initialize(context)
            if (!setting.enabled || setting.globalPolicy.paused) {
                cancel(context)
                return
            }

            val minMinutes = setting.minIntervalMinutes.coerceIn(1, Int.MAX_VALUE - 1)
            val maxMinutes = setting.maxIntervalMinutes.coerceIn(minMinutes, Int.MAX_VALUE - 1)
            val now = System.currentTimeMillis()
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val durableSlot = prefs.getLong(KEY_DURABLE_NEXT_SLOT, 0L)
            val explicitOverride = delayMinutesOverride?.coerceAtLeast(1)
            val durableOverride = durableSlotRemainingMinutes(durableSlot, now)
            val delayMinutes = explicitOverride ?: durableOverride ?: Random.nextInt(minMinutes, maxMinutes + 1)
            if (durableSlot > 0L && durableSlot <= now) {
                check(prefs.edit().remove(KEY_DURABLE_NEXT_SLOT).commit()) {
                    "Could not consume proactive decision slot"
                }
            }
            val wakeupStore = ProactiveWakeupStore(context)
            if (!setting.allowAiScheduledWakeup) wakeupStore.clear()
            val appointment = nextProactiveWakeup(wakeupStore.all(), setting, now, java.time.ZoneId.systemDefault())
            val triggerTime = proactiveTriggerTime(
                appointment = appointment,
                fallbackTime = now + TimeUnit.MINUTES.toMillis(delayMinutes.toLong()),
                setting = setting,
                now = now,
                zone = java.time.ZoneId.systemDefault(),
            )

            // 保存下次触发时间到SharedPreferences
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_NEXT_TRIGGER_TIME, triggerTime)
                .apply()

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ProactiveMessageTriggerService::class.java).apply {
                action = ACTION_PROACTIVE_MESSAGE
                putExtra(EXTRA_TRIGGER_SOURCE, "alarm")
            }
            // 让 AlarmManager 到点后直接启动前台生成服务。之前先发广播，
            // 再由 BroadcastReceiver 调 startForegroundService()；部分国产 ROM 会把
            // 这个“广播 -> 后台启服务”的第二步压到 App 回到前台才执行。
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    context,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                PendingIntent.getService(
                    context,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

            // 清掉旧版可能已经排队的 broadcast PendingIntent，避免升级后双触发。
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, ProactiveMessageReceiver::class.java).apply {
                    action = ACTION_PROACTIVE_MESSAGE
                },
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let { legacyPendingIntent ->
                alarmManager.cancel(legacyPendingIntent)
                legacyPendingIntent.cancel()
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                // Android 12+ needs canScheduleExactAlarms() check
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerTime,
                            pendingIntent
                        )
                    } else {
                        // Fallback: use inexact alarm if exact alarm permission not granted
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerTime,
                            pendingIntent
                        )
                        Log.w(TAG, "Exact alarm permission not granted, using inexact alarm")
                    }
                } else {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }

            Log.d(TAG, "Scheduled proactive message in $delayMinutes minutes, trigger at ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(triggerTime))}")

            // Also schedule via WorkManager as a more reliable fallback
            ProactiveMessageWorker.scheduleNext(context, setting, triggerTime)
        }

        internal fun persistDurableNextSlot(context: Context, nowMillis: Long, delayMinutes: Int): Boolean {
            val boundedMinutes = delayMinutes.coerceIn(1, MAX_DURABLE_SLOT_MINUTES)
            val dueAt = if (nowMillis > Long.MAX_VALUE - TimeUnit.MINUTES.toMillis(boundedMinutes.toLong())) {
                Long.MAX_VALUE
            } else nowMillis + TimeUnit.MINUTES.toMillis(boundedMinutes.toLong())
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong(KEY_DURABLE_NEXT_SLOT, dueAt)
                .commit()
        }

        internal fun durableNextSlot(context: Context): Long? =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_DURABLE_NEXT_SLOT, 0L).takeIf { it > 0L }

        internal fun clearDurableNextSlot(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(KEY_DURABLE_NEXT_SLOT).commit()

        fun getNextTriggerTime(context: Context): Long? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val triggerTime = prefs.getLong(KEY_NEXT_TRIGGER_TIME, 0L)
            return if (triggerTime > 0) triggerTime else null
        }

        fun cancel(context: Context) {
            ProactiveTaskExecutor.cancel(context)
            // 清除保存的触发时间
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_NEXT_TRIGGER_TIME)
                .apply()

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ProactiveMessageTriggerService::class.java).apply {
                action = ACTION_PROACTIVE_MESSAGE
            }
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    context,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                PendingIntent.getService(
                    context,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            pendingIntent?.let {
                alarmManager.cancel(it)
                it.cancel()
                Log.d(TAG, "Cancelled proactive message alarm")
            }

            // 同时兼容取消升级前已排队的广播闹钟。
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, ProactiveMessageReceiver::class.java).apply {
                    action = ACTION_PROACTIVE_MESSAGE
                },
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let { legacyPendingIntent ->
                alarmManager.cancel(legacyPendingIntent)
                legacyPendingIntent.cancel()
            }

            // Also cancel WorkManager fallback
            ProactiveMessageWorker.cancel(context)
        }

        fun resetTimer(context: Context, setting: ProactiveMessageSetting) {
            scheduleNext(context, setting)
        }

        fun triggerNow(context: Context, setting: ProactiveMessageSetting) {
            // Manual is an explicit user action and ignores a legacy hidden pause flag.
            if (!setting.enabled) return
            // 手动触发优先保证"现在就跑"：不再先做一次 scheduleNext 的落盘/闹钟计算。
            // 本次运行结束后，服务自己的 finally 会安排下一次。
            val serviceIntent = Intent(context, ProactiveMessageTriggerService::class.java).apply {
                putExtra(EXTRA_TRIGGER_SOURCE, "manual")
                putExtra(EXTRA_FORCE_TRIGGER, true)
            }
            context.startForegroundService(serviceIntent)
        }
    }

    fun buildProactiveContext(settings: Settings, idleMinutes: Int): String = buildString {
        appendLine("距离聊天中的那个人最后一次开口：${formatIdleMinutes(idleMinutes)}")
        if (settings.systemToolsSetting.shouldInjectTimeContext(proactive = true)) {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", java.util.Locale.getDefault())
            appendLine("当前本地时间：${sdf.format(java.util.Date())}")
        }
    }

    private fun formatIdleMinutes(minutes: Int): String = when {
        minutes == Int.MAX_VALUE -> "未知"
        minutes >= 1440 -> "${minutes / 1440}天${minutes % 1440 / 60}小时"
        minutes >= 60 -> "${minutes / 60}小时${minutes % 60}分钟"
        else -> "${minutes.coerceAtLeast(0)}分钟"
    }
}

internal fun proactiveNoGenerationSlotMinutes(): Int = 240

internal fun durableSlotRemainingMinutes(dueAtMillis: Long, nowMillis: Long): Int? {
    if (dueAtMillis <= nowMillis) return null
    val minute = TimeUnit.MINUTES.toMillis(1)
    val maxMinutes = ProactiveMessageService.MAX_DURABLE_SLOT_MINUTES
    val boundedDelay = (dueAtMillis - nowMillis).coerceAtMost(TimeUnit.MINUTES.toMillis(maxMinutes.toLong()))
    return ((boundedDelay + minute - 1) / minute).coerceIn(1, maxMinutes.toLong()).toInt()
}

internal fun proactiveCommittedMessageIds(taskId: String, messageCount: Int): List<String> =
    List(messageCount.coerceAtLeast(0)) { index -> proactiveCommitMessageId(taskId, index) }

/**
 * Keeps only visible text from previously committed proactive messages. Persisted IDs are the
 * durable commit keys, not the streaming IDs allocated while generating.
 */
internal fun sanitizeProactiveHistory(
    messages: List<UIMessage>,
    proactiveMessageIds: Set<String>,
    preserveReasoning: Boolean = false,
): List<UIMessage> = messages.mapNotNull { message ->
    if (preserveReasoning || message.role != MessageRole.ASSISTANT || message.id.toString() !in proactiveMessageIds) {
        return@mapNotNull message
    }
    val text = message.parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }.trim()
    text.takeIf(String::isNotBlank)?.let { message.copy(parts = listOf(UIMessagePart.Text(it))) }
}

private fun UIMessage.visibleTextForProactiveContext(): String = parts
    .filterIsInstance<UIMessagePart.Text>()
    .joinToString("\n") { it.text }
    .trim()

class ProactiveMessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(ProactiveMessageService.TAG, "=== onReceive triggered at ${System.currentTimeMillis()}, action=${intent.action} ===")
        when (intent.action) {
            ProactiveMessageService.ACTION_PROACTIVE_MESSAGE -> {
                Log.d(ProactiveMessageService.TAG, "Starting ProactiveMessageTriggerService...")
                val serviceIntent = Intent(context, ProactiveMessageTriggerService::class.java)
                context.startForegroundService(serviceIntent)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(ProactiveMessageService.TAG, "Boot completed, rescheduling proactive message")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val settingsStore = org.koin.core.context.GlobalContext.get().get<SettingsStore>()
                        val settings = settingsStore.settingsFlow.first()
                        val proactiveSetting = settings.proactiveMessageSetting
                        if (proactiveSetting.enabled) {
                            ProactiveMessageService.scheduleNext(context, proactiveSetting)
                        }
                    } catch (e: Exception) {
                        Log.e(ProactiveMessageService.TAG, "Failed to reschedule after boot", e)
                    }
                }
            }
        }
    }
}

class ProactiveMessageTriggerService : android.app.Service(), KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val conversationRepository: ConversationRepository by inject()
    private val filesManager: me.rerere.rikkahub.data.files.FilesManager by inject()
    private val memoryRepository: MemoryRepository by inject()
    private val providerManager: ProviderManager by inject()
    private val usageRecorder: UsageRecorder by inject()
    private val json: Json by inject()
    private val chatService: ChatService by inject()
    private val chatInteractionRepository: ChatInteractionRepository by inject()
    private val extraInfoCollector: ExtraInfoInjectionCollector by inject()
    private val proactiveMessageService = ProactiveMessageService()
    private val executor by lazy { ProactiveTaskExecutor(this, settingsStore, conversationRepository, chatService) }
    private val activeRunCount = AtomicInteger(0)
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val generationWakeLocks = ConcurrentHashMap<Int, PowerManager.WakeLock>()

    companion object {
        private const val TAG = "ProactiveMessageTrigger"
        private const val MAX_TOOL_STEPS = 5 // 主动消息最大工具调用步数
        private val PROACTIVE_DISALLOWED_PAID_TOOLS = setOf("generate_image")
        private const val MAX_PROACTIVE_CONTEXT_MESSAGES = 20
        private const val GENERATION_WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        // 外部触发（网关轮询）时跳过内部 minInterval 去重
        const val EXTRA_FORCE_TRIGGER = "force_trigger"
        // 激进模式设备事件上下文（由 DeviceEventAiTriggerService 传入）
        const val EXTRA_DEVICE_EVENT_CONTEXT = "device_event_context"

        // 保护 last_triggered_time 的 check-then-act 竞态（防止 AlarmManager 与 WorkManager
        // 前后脚触发导致"最小间隔"被砍半）。纯同步 SharedPreferences 读写，无挂起点，用对象锁即可。
        private val prefsLock = Any()
    }

    // 输出转换器（与 ChatService 保持一致）
    private val outputTransformers by lazy {
        listOf(
            ThinkTagTransformer,
            Base64ImageToLocalFileTransformer,
            RegexOutputTransformer,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isChatRecoveryBlocking()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        Log.d(TAG, "=== TriggerService onStartCommand ===")
        // 外部触发（网关轮询/激进模式设备事件）时跳过内部 minInterval 去重
        val isForceTrigger = intent?.getBooleanExtra(EXTRA_FORCE_TRIGGER, false) ?: false
        val triggerSource = intent?.getStringExtra(ProactiveMessageService.EXTRA_TRIGGER_SOURCE)
            ?: if (isForceTrigger) "external" else "service"
        val trace = ProactiveMessageTrace.start(triggerSource)
        // 激进模式设备事件上下文（由 DeviceEventAiTriggerService 传入）
        val deviceEventContext = intent?.getStringExtra(EXTRA_DEVICE_EVENT_CONTEXT)
        val isFromDeviceEvent = deviceEventContext != null
        if (isFromDeviceEvent) {
            Log.i(TAG, "Ignoring legacy aggressive-mode device event")
            trace.finish("legacy_device_event_ignored")
            if (activeRunCount.get() == 0) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (isForceTrigger) {
            Log.d(TAG, "Force trigger${if (isFromDeviceEvent) " from device event" else " from gateway poll"}, will skip min interval check")
        }
        val notification = androidx.core.app.NotificationCompat.Builder(this, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("主动消息处理中")
            .setSmallIcon(me.rerere.rikkahub.R.drawable.small_icon)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()
        startForeground(20001, notification)
        trace.event("foreground", "startId=$startId")

        // WorkManager 只负责把服务唤醒；真正耗时的是下面完整的流式生成、落库和通知流程。
        // WakeLock 必须由服务持有到该流程结束，否则华为/鸿蒙、红米/澎湃等 ROM 在息屏后
        // 可能暂停网络流：上游已经开始计费，但本地收不到完整正文。
        activeRunCount.incrementAndGet()
        acquireGenerationWakeLock(startId)
        trace.event("wake_lock", "acquired=true")
        val nextDelayOverride = java.util.concurrent.atomic.AtomicReference<Int?>(null)

        serviceScope.launch {
            try {
                executor.drain(triggerSource) { task ->
                    kotlinx.coroutines.supervisorScope {
                        launch {
                            executor.bindJob(task)
                            var conversationId: kotlin.uuid.Uuid? = null
                            var outcome = "completed"
                            var failure: Throwable? = null
                            var selectedAppointment: ProactiveWakeup? = null
                            var appointmentMessageSaved = false
                            val runAssistantMessageIds = linkedSetOf<Uuid>()
                            val protectedMessageIds = linkedSetOf<Uuid>()
                            try {
                                val baseSettings = settingsStore.settingsFlow.first()
                                val proactiveSetting = baseSettings.proactiveMessageSetting
                                trace.event("settings", "enabled=${proactiveSetting.enabled}")

                                if (!proactiveSetting.enabled) {
                                    outcome = "disabled"
                                    return@launch
                                }

                                // Only an explicit manual check may bypass quiet hours, never a gateway signal.
                                if (triggerSource != "manual" && !isProactiveActiveTime(
                                        proactiveSetting, System.currentTimeMillis(), java.time.ZoneId.systemDefault(),
                                    )
                                ) {
                                    outcome = "quiet_hours"
                                    return@launch
                                }

                                val wakeupStore = ProactiveWakeupStore(this@ProactiveMessageTriggerService)
                                val appointment = task.appointmentToken?.let { token -> wakeupStore.all().singleOrNull { it.token == token } }
                                if (task.appointmentToken != null && appointment == null) return@launch
                                fun consumeAppointment(value: ProactiveWakeup): Boolean = wakeupStore.consume(value).also {
                                    if (it) selectedAppointment = value
                                }
                                val targetAssistant = baseSettings.assistants.singleOrNull { it.id.toString() == task.assistantId }
                                    ?: return@launch
                                val assistantUuid = targetAssistant.id
                                val conversation = executor.conversationSnapshot(Uuid.parse(task.conversationId))
                                if (conversation == null || conversation.assistantId != assistantUuid) return@launch

                                val latestUserMessage = conversation?.currentMessages
                                    ?.lastOrNull { it.role == MessageRole.USER }
                                if (latestUserMessage == null) {
                                    appointment?.let { consumeAppointment(it) }
                                    outcome = "no_user_message"
                                    Log.d(TAG, "No real user message found; skipping proactive generation")
                                    check(ProactiveMessageService.persistDurableNextSlot(
                                        this@ProactiveMessageTriggerService, System.currentTimeMillis(), proactiveNoGenerationSlotMinutes(),
                                    )) { "Could not persist no-user proactive slot" }
                                    nextDelayOverride.set(240)
                                    return@launch
                                }
                                if (latestUserMessage.id.toString() != task.anchorUserMessageId) return@launch
                                val loadedConversation = requireNotNull(conversation)
                                // 跳过的唤醒只做只读检查，不为旧会话无谓冻结默认值。
                                if (baseSettings.getChatModelOf(loadedConversation) == null) {
                                    outcome = "no_model"
                                    Log.e(ProactiveMessageService.TAG, "No model found for proactive message")
                                    return@launch
                                }
                                // Cleanup must never be allowed to delete anything that existed before this run.
                                // Protect every branch alternative, not only currentMessages.
                                loadedConversation.messageNodes
                                    .flatMapTo(protectedMessageIds) { node -> node.messages.map { it.id } }
                                val stateStore = ProactiveMessageStateStore(
                                    this@ProactiveMessageTriggerService, "${targetAssistant.id}_${loadedConversation.id}",
                                )
                                val activityStore = ProactiveActivityStore(
                                    this@ProactiveMessageTriggerService, targetAssistant.id.toString(), loadedConversation.id.toString(),
                                )
                                fun recordActivity(outcome: String, note: String = "", tools: List<String> = emptyList()) {
                                    runCatching {
                                        activityStore.append(ProactiveActivity(
                                            id = "run:${task.taskId}:${outcome.hashCode()}:${System.currentTimeMillis()}",
                                            timestamp = System.currentTimeMillis(),
                                            outcome = outcome,
                                            note = note.take(240),
                                            toolNames = tools.take(12),
                                        ))
                                    }.onFailure { Log.w(TAG, "Could not persist proactive activity", it) }
                                }
                                val proactiveState = stateStore.synchronizeWithUser(latestUserMessage.id.toString())
                                val maxFollowUps = proactiveSetting.maxFollowUpMessages.coerceIn(1, 8)
                                if (triggerSource != "manual" && (proactiveState.stopUntilUserReturns ||
                                    proactiveState.followUpCount >= maxFollowUps)
                                ) {
                                    outcome = "follow_up_limit"
                                    appointment?.let { consumeAppointment(it) }
                                    Log.d(TAG, "No proactive generation: waiting for a new real user message")
                                    recordActivity(
                                        outcome = "等待对方再次开口",
                                        note = "已到连续追问上限或上一轮已停止，本轮不生成；等你回复后恢复。",
                                    )
                                    check(ProactiveMessageService.persistDurableNextSlot(
                                        this@ProactiveMessageTriggerService,
                                        System.currentTimeMillis(),
                                        proactiveNoGenerationSlotMinutes(),
                                    )) { "Could not persist follow-up-limit proactive slot" }
                                    nextDelayOverride.set(240)
                                    return@launch
                                }

                                conversationId = conversation?.id ?: kotlin.uuid.Uuid.random()
                                val conversationId = conversationId!!

                                // 持有会话引用，防止生成期间 session 被 idle 清除（finally 块会对应 release）。
                                // 放在 claim 之前：即使 claim 失败提前返回，引用也能在 finally 里被正确释放，保持计数平衡。
                                // 同时把数据库里的完整对话同步到 session，防止流式更新时 conv 是空状态导致覆盖历史。
                                chatService.addConversationReference(conversationId)

                                // 抢占生成权：尝试把当前协程的 Job 注册进 ConversationSession。
                                // 这一步对所有触发源（含 isForceTrigger / 激进模式设备事件）一视同仁，是并发安全的核心。
                                // 如果当前已有生成在跑（正常聊天或另一路主动消息），直接放弃本次触发，不排队等待、不重试。
                                // 理由：等对方生成结束后，上下文（用户可能已在聊别的话题）大概率已过时，硬等没有意义。
                                val myJob = currentCoroutineContext()[Job]
                                if (myJob == null || !chatService.getOrCreateSession(conversationId).tryClaimGeneration(myJob)) {
                                    outcome = "generation_busy"
                                    trace.event("generation_claim", "success=false")
                                    Log.d(
                                        TAG,
                                        "Skip proactive trigger: session $conversationId already generating " +
                                            "(normal chat or another proactive trigger in progress)"
                                    )
                                    // 必须走到 finally 块的"安排下一次触发"逻辑，不能绕过定时链收尾。
                                    // 用 stopSelf + return@launch 退出主流程，finally 会正常执行（scheduleNext 已用 NonCancellable 保护）。
                                    return@launch
                                }
                                trace.event("generation_claim", "success=true")
                                val session = chatService.getOrCreateSession(conversationId)
                                val sessionMatches = session.withStateLock {
                                    coroutineContext.ensureActive()
                                    val current = chatService.getConversationFlow(conversationId).value
                                    if (current.messageNodes.isEmpty()) {
                                        chatService.updateConversation(conversationId, loadedConversation)
                                        true
                                    } else {
                                        current.currentMessages.lastOrNull { it.role == MessageRole.USER }?.id == latestUserMessage.id
                                    }
                                }
                                if (!sessionMatches) {
                                    outcome = "user_returned"
                                    return@launch
                                }

                                // 构建上下文
                                val idleMinutes = latestUserMessage.createdAt
                                    .toInstant(TimeZone.currentSystemDefault())
                                    .let { ((kotlin.time.Clock.System.now() - it).inWholeMinutes).toInt().coerceAtLeast(0) }
                                if (!isForceTrigger && appointment == null && idleMinutes < proactiveSetting.minIntervalMinutes.coerceAtLeast(1)) {
                                    outcome = "user_not_idle"
                                    trace.event(
                                        "idle_guard",
                                        "passed=false idleMinutes=$idleMinutes minimum=${proactiveSetting.minIntervalMinutes}",
                                    )
                                    return@launch
                                }
                                trace.event("idle_guard", "passed=true idleMinutes=$idleMinutes")
                                // 只有真正取得生成权并通过跳过条件后才绑定；绑定自行持锁，不能嵌套在上面的锁中。
                                if (settingsStore.settingsFlow.first().getChatModelOf(session.state.value) == null) {
                                    outcome = "no_model"
                                    recordActivity(outcome = "未触发：未选择模型")
                                    Log.e(ProactiveMessageService.TAG, "No model found for proactive message")
                                    return@launch
                                }
                                // Consume after generation ownership is acquired; a newer replacement wins.
                                if (appointment != null && !consumeAppointment(appointment)) {
                                    outcome = "appointment_changed"
                                    return@launch
                                }
                                val activeConversation = chatService.bindConversationConfig(conversationId)
                                // 后续历史、工具和参数共享这一份快照，不将覆盖写回全局设置。
                                val settings = settingsStore.settingsFlow.first().withConversationConfig(activeConversation)
                                val assistant = settings.getAssistantOf(activeConversation)
                                val model = settings.getChatModelOf(activeConversation)
                                if (model == null) {
                                    outcome = "no_model"
                                    Log.e(ProactiveMessageService.TAG, "No model found for proactive message")
                                    return@launch
                                }
                                val priorActivities = activityStore.snapshot().takeLast(8)
                                val contextStr = proactiveMessageService.buildProactiveContext(settings, idleMinutes) +
                                    "\n" + proactiveActivityText(priorActivities)
                                val extraContext = extraInfoCollector.collect(
                                    settings = settings.forProactiveExtraInfo(),
                                    assistantId = assistant.id.toString(),
                                    queryText = latestUserMessage.visibleTextForProactiveContext(),
                                    proactive = true,
                                    onItem = { name, status, chars ->
                                        trace.event("extra_info_item", "item=$name status=$status chars=$chars")
                                    },
                                )
                                trace.event(
                                    "proactive_context",
                                    "idleIncluded=true currentTimeIncluded=${settings.systemToolsSetting.shouldInjectTimeContext(proactive = true)}",
                                )

                                val rawHistoryMessages = activeConversation.messagesForGeneration().let {
                                    val configuredSize = assistant.contextMessageSize
                                        .takeIf { size -> size > 0 }
                                        ?: MAX_PROACTIVE_CONTEXT_MESSAGES
                                    it.takeLast(configuredSize.coerceAtMost(MAX_PROACTIVE_CONTEXT_MESSAGES))
                                }
                                val latestUserText = latestUserMessage.visibleTextForProactiveContext()
                                val latestRegularAssistantText = rawHistoryMessages.lastOrNull { message ->
                                    message.role == MessageRole.ASSISTANT &&
                                        message.id.toString() !in proactiveState.recentProactiveMessageIds
                                }?.visibleTextForProactiveContext().orEmpty()

                                val providerSetting = model.findProvider(settings.providers)
                                if (providerSetting == null) {
                                    outcome = "no_provider"
                                    recordActivity(outcome = "未触发：模型没有可用的服务商")
                                    Log.e(ProactiveMessageService.TAG, "No provider found for proactive message")
                                    return@launch
                                }

                                // 完整轮次复用聊天工具，轻量轮次只带记忆检索。AppUsage 需要额外授权；
                                // 未授权时连它的 schema 都不暴露，更不会预先读取或注入任何手机使用数据。
                                val appUsageToolAllowed = proactiveSetting.allowProactiveAppUsage &&
                                    me.rerere.rikkahub.data.ai.tools.SystemToolOption.AppUsage in
                                    settings.systemToolsSetting.getEnabledOptions()
                                val fullTools = useFullProactiveTools(proactiveSetting, Random.nextInt(100)) &&
                                    me.rerere.ai.provider.ModelAbility.TOOL in model.abilities
                                val candidateTools = if (fullTools) chatService.buildAvailableTools(
                                    settings = settings,
                                    assistant = assistant,
                                    conversationId = conversationId,
                                    conversation = activeConversation,
                                    allowAppUsage = appUsageToolAllowed,
                                    allowImageGeneration = false,
                                    headless = true,
                                ) else if (me.rerere.ai.provider.ModelAbility.TOOL in model.abilities) {
                                    chatService.buildProactiveMemoryTools(assistant) +
                                        chatService.buildProactiveWakeupTools(settings, assistant, activeConversation)
                                } else emptyList()
                                // Plugin/MCP tools may invoke arbitrary paid models or external APIs, which
                                // cannot participate in this task's network-turn budget. Keep them foreground-only.
                                val tools = candidateTools.filterNot {
                                    it.name == "generate_image" || it.name == "text_to_speech" ||
                                        it.name.startsWith("plg_") || it.name.startsWith("mcp_")
                                }
                                val timeline = if (appUsageToolAllowed && proactiveSetting.includeAppTimeline) {
                                    AppUsageService(this@ProactiveMessageTriggerService).getRecentProactiveTimeline()
                                } else ""
                                trace.event("activity_mode", "fullTools=$fullTools timelineIncluded=${timeline.isNotBlank()}")
                                val deepSeekThinkingWithTools = isDeepSeekCompatible(providerSetting, model) &&
                                    assistant.reasoningLevel.isEnabled && tools.isNotEmpty()
                                val missingReasoningHistory = deepSeekThinkingWithTools && rawHistoryMessages.any { message ->
                                    message.role == MessageRole.ASSISTANT &&
                                        message.getTools().isNotEmpty() &&
                                        message.parts.none { it is UIMessagePart.Reasoning && it.reasoning.isNotBlank() }
                                }
                                val providerSafeHistory = if (missingReasoningHistory) {
                                    // Keep current tools available. Only remove an old, incomplete tool protocol
                                    // from historical turns that cannot satisfy DeepSeek's reasoning_content rule.
                                    trace.event("provider_compat", "deepseek_missing_reasoning=true old_tool_parts_removed=true")
                                    rawHistoryMessages.mapNotNull { message ->
                                        val incompleteToolTurn = message.role == MessageRole.ASSISTANT &&
                                            message.getTools().isNotEmpty() &&
                                            message.parts.none {
                                                it is UIMessagePart.Reasoning && it.reasoning.isNotBlank()
                                            }
                                        if (!incompleteToolTurn) {
                                            message
                                        } else {
                                            message.copy(parts = message.parts.filterNot { it is UIMessagePart.Tool })
                                                .takeIf { it.parts.isNotEmpty() }
                                        }
                                    }
                                } else {
                                    rawHistoryMessages
                                }

                                // DeepSeek 带工具时必须保留历史 reasoning_content；其他场景仍精简旧主动消息。
                                // 后台唤醒没有摄像头授权，历史里的视频通话画面一律不重放。
                                val historyMessages = filterInvalidToolMessages(
                                    sanitizeProactiveHistory(
                                        messages = providerSafeHistory,
                                        proactiveMessageIds = proactiveState.recentProactiveMessageIds,
                                        preserveReasoning = isDeepSeekCompatible(providerSetting, model) && tools.isNotEmpty(),
                                    ),
                                ).let { messages ->
                                    if (tools.isEmpty()) stripHistoricalToolParts(messages) else messages
                                }.withoutHistoricalCameraImages()
                                trace.messages("history", historyMessages)
                                trace.event(
                                    "tools_exposed",
                                    "count=${tools.size} appUsage=$appUsageToolAllowed " +
                                        "sample=${tools.take(8).joinToString(",") { it.name }} " +
                                        "remaining=${(tools.size - 8).coerceAtLeast(0)}",
                                )

                                // 世界书只按真实聊天历史匹配，并作为 system 背景设定注入。
                                // 合成的主动判定消息既不能触发世界书，也不能让世界书伪装成用户新消息。
                                val proactiveInjections = collectInjections(
                                    messages = rawHistoryMessages,
                                    assistant = assistant,
                                    modeInjections = settings.modeInjections,
                                    lorebooks = settings.lorebooks,
                                ).map { it.content }
                                trace.event("background_injections", "count=${proactiveInjections.size}")

                                // 构建系统提示词（包含记忆 + 上下文，都放在最后面避免被网关淹没）
                                val systemPrompt = buildSystemPrompt(
                                    assistant = assistant,
                                    context = contextStr + "\n" + task.admittedConfig?.effectivePromptOverride.orEmpty() + "\n" + timeline + (appointment?.let {
                                        "\n## 本次约定唤醒\n你此前为这个会话约定了联系时间：" +
                                            java.time.Instant.ofEpochMilli(it.timeMillis).atZone(java.time.ZoneId.systemDefault()) +
                                            "\n约定缘由：${it.reason}\n请先依据最新聊天判断缘由是否仍适用，再决定如何联系；" +
                                            "这不是用户的新消息，也不是预写的待发送正文。\n"
                                    } ?: "") + if (fullTools) {
                                        "\n这次可按需使用已配置的工具。"
                                    } else {
                                        "\n这次是轻量判断，可使用列出的记忆搜索与联系时间约定工具；若没有工具，就依据现有上下文判断。" +
                                            "不能声称完成未实际执行的外部行动。"
                                    },
                                    state = proactiveState,
                                    maxFollowUps = maxFollowUps,
                                    allowAppUsage = appUsageToolAllowed && tools.any { it.name == "get_app_usage" },
                                    proactiveInjections = proactiveInjections,
                                    latestUserText = latestUserText,
                                    latestAssistantText = latestRegularAssistantText,
                                    alwaysRespond = proactiveSetting.alwaysRespond,
                                )

                                // Keep the wake-up user turn request-only. Claude/Gemini cannot start from
                                // an assistant prefill; tool continuations below retain their result pairing.
                                // History is camera-free here and each transformer pass is cleaned again so
                                // no video-call frame/snapshot can be re-encoded into this request.
                                val messages = filesManager.validAttachments(
                                    buildProactiveRequestMessages(
                                        systemPrompt = systemPrompt,
                                        historyMessages = historyMessages,
                                        summary = activeConversation.summaryForGeneration(),
                                        context = extraContext.orEmpty(),
                                    ),
                                    forSending = true,
                                ).transforms(
                                    transformers = me.rerere.rikkahub.data.ai.transformers.historyMediaTransformers,
                                    context = this@ProactiveMessageTriggerService,
                                    model = model,
                                    assistant = assistant,
                                    settings = settings,
                                ).withoutHistoricalCameraImages().filterNot { it.parts.isEmpty() }
                                trace.messages("request", messages)

                                val providerImpl = usageRecorder.wrap(
                                    providerManager.getProviderByType(providerSetting),
                                    UsageTag(UsageSource.PROACTIVE, assistant.id, conversationId),
                                ).cameraFreeRequests()

                                // 主动消息场景：支持工具调用，但限制最大步数
                                // temperature 不强制默认 0.8f，保持与 GenerationHandler 一致（assistant.temperature 为 null 时不传），
                                // 否则对智谱 GLM 等 thinking 模型会同时下发 temperature + thinking，触发 "Invalid request body" 400。
                                val params = buildAssistantGenerationParams(
                                    assistant = assistant,
                                    model = model,
                                    provider = providerSetting,
                                    conversationId = conversationId.toString(),
                                    tools = tools,
                                )

                                Log.d(TAG, "Calling AI API for proactive message with ${historyMessages.size} history messages, ${tools.size} tools (reasoning=${assistant.reasoningLevel}, model=${model.modelId}, provider=${providerSetting::class.simpleName})...")
                                // 诊断: 列出工具及其 parameters 是否为 null, 便于定位 "Invalid request body"
                                tools.forEach { t ->
                                    val hasSchema = t.parameters() != null
                                    if (!hasSchema) Log.w(TAG, "Tool '${t.name}' has NULL parameters schema — may cause API rejection")
                                }

                                // 执行生成，支持工具调用
                                val reactionTurn = chatInteractionRepository.prepareContext(
                                    activeConversation.currentMessages, conversationId.toString(), assistant, messages,
                                )
                                var (finalMessages, hasToolCalls, hasJumpFlag) = generateWithTools(
                                    conversationId = conversationId,
                                    providerImpl = providerImpl,
                                    providerSetting = providerSetting,
                                    initialMessages = messages,
                                    params = params,
                                    tools = tools,
                                    model = model,
                                    assistant = assistant,
                                    settings = settings,
                                    trace = trace,
                                    runAssistantMessageIds = runAssistantMessageIds,
                                    protectedMessageIds = protectedMessageIds,
                                    activityStore = activityStore,
                                    reactionTurn = reactionTurn,
                                    task = task,
                                )

                                // 部分 thinking 模型会结束在 Reasoning/Tool 上而没有 Text 正文。
                                // 追加一次禁用工具和推理的收尾生成，确保得到真正可发送的最终消息，
                                // 同时不把内部思考内容直接暴露给用户。
                                val generatedAssistant = finalMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                                val generatedText = generatedAssistant?.parts
                                    ?.filterIsInstance<UIMessagePart.Text>()
                                    ?.joinToString("\n") { it.text }
                                    ?.trim().orEmpty()
                                val hasReasoning = generatedAssistant?.parts
                                    ?.filterIsInstance<UIMessagePart.Reasoning>()
                                    ?.any { it.reasoning.isNotBlank() } == true
                                if (generatedText.isBlank() && hasReasoning) {
                                    Log.w(TAG, "Thinking response has no final text; requesting a final user-visible message")
                                    val finalizePrompt = UIMessage(
                                        role = MessageRole.USER,
                                        parts = listOf(UIMessagePart.Text(
                                            "根据刚才的想法给出最终结果。只输出 [PASS]、[WAIT:分钟]、[STOP]，" +
                                                "或者真正想说的一句话；不要再次解释过程。"
                                        )),
                                    )
                                    var finalized = filesManager.validAttachments(
                                        mergeAdjacentSameRoleMessages(finalMessages + finalizePrompt), forSending = true,
                                    ).withoutHistoricalCameraImages().filterNot { it.parts.isEmpty() }
                                    executor.networkTurn(task) {
                                        providerImpl.streamText(
                                            providerSetting = providerSetting,
                                            messages = finalized,
                                            params = params.copy(tools = emptyList(), reasoningLevel = ReasoningLevel.OFF),
                                        ).collect { chunk ->
                                            finalized = finalized.handleMessageChunk(chunk = chunk, model = model)
                                        }
                                    }
                                    finalized.lastOrNull { it.role == MessageRole.ASSISTANT }?.let { message ->
                                        val now = kotlin.time.Clock.System.now()
                                        // Keep the complete visible thinking chain. The old finalizer retained only
                                        // Reasoning and silently dropped Tool parts, so proactive tool calls were
                                        // executed but disappeared from the normal ChatMessage tool UI.
                                        val originalThinkingTrace = generatedAssistant?.parts
                                            ?.filter { it is UIMessagePart.Reasoning || it is UIMessagePart.Tool }
                                            ?.map { part ->
                                                if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                                                    part.copy(finishedAt = now)
                                                } else {
                                                    part
                                                }
                                            }
                                            .orEmpty()
                                        val finalVisibleParts = message.parts.filterNot { it is UIMessagePart.Reasoning }
                                        // 沿用原 thinking 消息的 id，把思考链与最终正文合在同一条气泡中。
                                        val completed = message.copy(
                                            id = generatedAssistant?.id ?: message.id,
                                            parts = originalThinkingTrace + finalVisibleParts,
                                        )
                                        trace.event(
                                            "finalizer",
                                            "preservedTools=${originalThinkingTrace.count { it is UIMessagePart.Tool }} " +
                                                "shape=${ProactiveMessageTrace.messageShape(completed)}",
                                        )
                                        updateOrAppendAiMessage(
                                            conversationId,
                                            completed,
                                            trace,
                                            runAssistantMessageIds,
                                            protectedMessageIds,
                                        )
                                        finalMessages = finalized.dropLast(1) + completed
                                    }
                                }

                                // 提取AI消息
                                val aiMessage = finalMessages.lastOrNull { it.role == MessageRole.ASSISTANT } ?: UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = emptyList()
                                )
                                if (hasToolCalls && aiMessage.getTools().isEmpty()) {
                                    trace.event("warning", "tool_called_but_missing_from_final_message=true")
                                }

                                // 用户可能在主动生成期间回来了。即使厂商 ROM/Provider 没及时传播取消，
                                // 保存前也必须重新读取真实会话并核对锚点，绝不把过期主动回复插入正常聊天。
                                trace.event("anchor_recheck_start")
                                val latestPersistedUserId = executor.conversationSnapshot(conversationId)
                                    ?.currentMessages
                                    ?.lastOrNull { it.role == MessageRole.USER }
                                    ?.id
                                    ?.toString()
                                val anchorUnchanged = latestPersistedUserId == latestUserMessage.id.toString()
                                trace.event("anchor_recheck_end", "unchanged=$anchorUnchanged")
                                val latestProactiveSetting = settingsStore.settingsFlow.first().proactiveMessageSetting
                                val stillAllowed = canRunProactiveWakeup(
                                    appointment, latestProactiveSetting, System.currentTimeMillis(), java.time.ZoneId.systemDefault(),
                                    manual = triggerSource == "manual",
                                )
                                if (!anchorUnchanged || !stillAllowed) {
                                    outcome = if (!anchorUnchanged) "user_returned" else "disabled_or_quiet_hours"
                                    removeProactiveRunMessages(
                                        conversationId = conversationId,
                                        messageIds = runAssistantMessageIds + aiMessage.id,
                                        protectedMessageIds = protectedMessageIds,
                                        trace = trace,
                                        label = "user_returned",
                                    )
                                    return@launch
                                }

                                val rawText = aiMessage.parts.filterIsInstance<UIMessagePart.Text>()
                                    .joinToString("\n") { it.text }.trim()
                                val reasoningText = aiMessage.parts.filterIsInstance<UIMessagePart.Reasoning>()
                                    .joinToString("\n") { it.reasoning }
                                val decision = parseProactiveDecision(
                                    rawText,
                                    reasoningText,
                                    hasJumpFlag,
                                    alwaysRespond = proactiveSetting.alwaysRespond,
                                )
                                val replyText = decision.message
                                val shouldJump = decision.shouldJump
                                nextDelayOverride.set(decision.waitMinutes)
                                val decisionSlotMinutes = when {
                                    decision.stopUntilUserReturns -> 240
                                    decision.waitMinutes != null -> decision.waitMinutes
                                    !decision.shouldSend -> 30
                                    else -> null
                                }
                                if (!decision.shouldSend && !decision.stopUntilUserReturns && decision.waitMinutes == null) {
                                    nextDelayOverride.set(decisionSlotMinutes)
                                }
                                if (decisionSlotMinutes != null && !ProactiveMessageService.persistDurableNextSlot(
                                        this@ProactiveMessageTriggerService,
                                        System.currentTimeMillis(),
                                        decisionSlotMinutes,
                                    )
                                ) error("Could not persist proactive decision slot")
                                if (decision.stopUntilUserReturns) {
                                    wakeupStore.cancel(conversationId.toString())
                                    stateStore.stopUntilUserReturns(proactiveState)
                                    nextDelayOverride.set(240)
                                }

                                // 若移除了标记，同步更新 session 里 aiMessage 的文本 parts
                                val cleanedAiMessage = if (rawText != replyText) {
                                    var visibleTextWritten = false
                                    aiMessage.copy(
                                        parts = aiMessage.parts.map { part ->
                                            if (part is UIMessagePart.Text) {
                                                if (!visibleTextWritten) {
                                                    visibleTextWritten = true
                                                    UIMessagePart.Text(replyText)
                                                } else {
                                                    UIMessagePart.Text("")
                                                }
                                            } else {
                                                part
                                            }
                                        }
                                    )
                                } else aiMessage
                                trace.event(
                                    "decision",
                                    "send=${decision.shouldSend} wait=${decision.waitMinutes ?: 0} " +
                                        "stop=${decision.stopUntilUserReturns} jump=${decision.shouldJump} " +
                                        "shape=${ProactiveMessageTrace.messageShape(aiMessage)}",
                                )

                                Log.d(
                                    TAG,
                                    "Proactive message generated: length=${replyText.length}, " +
                                        "hasToolCalls=$hasToolCalls, shouldJump=$shouldJump",
                                )

                                // Record every decision before publishing/removing anything. The
                                // previous placement after the publish branch made SKIP and a
                                // revoked publication invisible in the background activity list.
                                recordActivity(
                                    outcome = when {
                                        decision.shouldSend -> "已发送主动消息"
                                        decision.stopUntilUserReturns -> "等待对方再次开口"
                                        decision.waitMinutes != null -> "决定再等${decision.waitMinutes}分钟"
                                        else -> "这轮没有发消息"
                                    },
                                    note = decision.activityNote,
                                    tools = finalMessages.filter { it.id in runAssistantMessageIds }
                                        .flatMap { it.getTools() }.filter { it.isExecuted }
                                        .map { it.toolName.take(80) }.distinct(),
                                )

                                if (!decision.shouldSend) {
                                    outcome = when {
                                        decision.stopUntilUserReturns -> "stop"
                                        decision.waitMinutes != null -> "wait"
                                        else -> "pass"
                                    }
                                    // AI 选择跳过，移除本次生成的 aiMessage node（基于 id 匹配，不误删历史）
                                    Log.d(ProactiveMessageService.TAG, "AI chose to skip proactive message")
                                    removeProactiveRunMessages(
                                        conversationId = conversationId,
                                        messageIds = runAssistantMessageIds + aiMessage.id,
                                        protectedMessageIds = protectedMessageIds,
                                        trace = trace,
                                        label = "skip",
                                    )
                                } else {
                                    val completedMessages = (finalMessages.filter {
                                        it.id in runAssistantMessageIds && it.id != aiMessage.id
                                    } + cleanedAiMessage).associateBy { it.id }.values.map { completed ->
                                        completed.copy(
                                            parts = completed.parts.map { part ->
                                                if (part is UIMessagePart.Text) part.copy(text = stripProactiveNotes(part.text)) else part
                                            },
                                            finishedAt = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
                                        )
                                    }
                                    currentCoroutineContext().ensureActive()
                                    val published = chatService.publishProactiveMessages(
                        conversationId, assistant.id, latestUserMessage.id, task, completedMessages, executor,
                    )
                    if (!published) {
                                        outcome = "revoked_before_publication"
                                        recordActivity(
                                            outcome = "发布前被取消",
                                            note = "插件任务或授权在写入前被停止，本轮没有保存消息。",
                                        )
                                        return@launch
                                    }
                                    reactionTurn.onGenerationSucceeded(completedMessages.last())
                                    appointmentMessageSaved = true
                                    outcome = "sent"
                                    stateStore.recordSent(
                                        state = proactiveState,
                                        messageIds = proactiveCommittedMessageIds(task.taskId, completedMessages.size),
                                        text = replyText,
                                    )
                                    runCatching {
                                        showProactiveNotification(conversationId, assistant.name.ifBlank { "AI" }, replyText)
                                    }.onFailure {
                                        trace.event("notification", "delivered=false messagePersisted=true")
                                        Log.w(TAG, "Notification failed; proactive message remains in conversation", it)
                                    }
                                    // 强制跳转屏幕到聊天界面（方案 A：普通拉起前台）
                                    if (shouldJump && settingsStore.settingsFlow.value.proactiveMessageSetting.allowForceJump) {
                                        try {
                                            val jumpIntent = Intent(this@ProactiveMessageTriggerService, RouteActivity::class.java).apply {
                                                addFlags(
                                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                                )
                                                putExtra("conversationId", conversationId.toString())
                                            }
                                            startActivity(jumpIntent)
                                            Log.d(TAG, "Force jump to conversation $conversationId")
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Force jump failed", e)
                                        }
                                    }
                                }
                            } catch (e: CancellationException) {
                                outcome = "cancelled"
                                failure = e
                                // 协程被取消（通常是用户发了新消息，sendMessage 里 session.getJob()?.cancel() 触发），
                                // 这是正常情况，不应当成错误。打印 debug 日志并重新抛出，遵循 Kotlin 协程取消传播语义。
                                // 注意 catch 顺序：CancellationException 必须在 Exception 之前，否则被泛化分支提前吃掉。
                                // 重新抛出后 finally 块仍会正常执行（scheduleNext 已用 NonCancellable 保护）。
                                Log.d(
                                    ProactiveMessageService.TAG,
                                    "Proactive generation cancelled (likely user started a new message), " +
                                        "conversationId=$conversationId"
                                )
                                throw e
                            } catch (e: Exception) {
                                outcome = "failed"
                                failure = e
                                Log.e(ProactiveMessageService.TAG, "Failed to trigger proactive message", e)
                                // 如果是 API 返回的 HTTP 错误, 把原始错误体也打出来便于定位
                                val cause = e.cause
                                if (cause != null) {
                                    Log.e(ProactiveMessageService.TAG, "Underlying cause: ${cause::class.simpleName}: ${cause.message}", cause)
                                }
                            } finally {
                                // 确保无论成功/失败/取消都安排下一次，避免一次 API 错误或用户打断永久中断定时链。
                                // 激进模式设备事件触发时不需要安排下一次定时主动消息（由 DeviceEventAiTriggerService 自己驱动）。
                                // 用 NonCancellable 包裹：协程被取消后处于已取消状态，finally 里的挂起点
                                // (settingsFlow.first()) 会立刻抛 CancellationException，导致 scheduleNext 被跳过、
                                // 定时链断裂。NonCancellable 保证这段收尾逻辑跑完。
                                withContext(NonCancellable) {
                                    selectedAppointment?.let { appointment ->
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                ProactiveWakeupStore(this@ProactiveMessageTriggerService).finish(
                                                    appointment, if (appointmentMessageSaved) "sent" else outcome,
                                                )
                                            }
                                        }.onFailure { Log.w(TAG, "Failed to record appointment outcome", it) }
                                    }
                                    if ((outcome == "failed" || outcome == "cancelled") &&
                                        conversationId != null && runAssistantMessageIds.isNotEmpty()
                                    ) {
                                        runCatching {
                                            removeProactiveRunMessages(
                                                conversationId = conversationId!!,
                                                messageIds = runAssistantMessageIds,
                                                protectedMessageIds = protectedMessageIds,
                                                trace = trace,
                                                label = outcome,
                                            )
                                        }.onFailure { cleanupError ->
                                            Log.w(
                                                ProactiveMessageService.TAG,
                                                "Failed to clean exact proactive run messages",
                                                cleanupError,
                                            )
                                        }
                                    }

                                }
                                runCatching {
                                    ProactiveActivityStore(this@ProactiveMessageTriggerService,
                                        task.assistantId, task.conversationId).append(ProactiveActivity(
                                        id = "outcome:${task.taskId}", timestamp = System.currentTimeMillis(),
                                        outcome = "主动消息：$outcome",
                                        note = failure?.let { "${it::class.simpleName}" }.orEmpty(),
                                    ))
                                }.onFailure { Log.w(TAG, "Could not persist per-assistant outcome", it) }
                                conversationId?.let { chatService.removeConversationReference(it) }
                                trace.finish(outcome, failure)
                            }
                        }.join()
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    ProactiveMessageService.scheduleNext(
                        this@ProactiveMessageTriggerService,
                        settingsStore.settingsFlow.value.proactiveMessageSetting,
                        nextDelayOverride.get() ?: ProactiveMessageService.durableNextSlot(this@ProactiveMessageTriggerService)
                            ?.let { dueAt -> durableSlotRemainingMinutes(dueAt, System.currentTimeMillis()) },
                    )
                }
                releaseGenerationWakeLock(startId)
                if (activeRunCount.decrementAndGet() == 0) stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun acquireGenerationWakeLock(startId: Int) {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OrangeChat::ProactiveGeneration:$startId",
        ).apply {
            setReferenceCounted(false)
            acquire(GENERATION_WAKE_LOCK_TIMEOUT_MS)
        }
        generationWakeLocks[startId] = wakeLock
        Log.d(TAG, "Acquired proactive generation WakeLock for startId=$startId")
    }

    private fun releaseGenerationWakeLock(startId: Int) {
        generationWakeLocks.remove(startId)?.let { wakeLock ->
            if (wakeLock.isHeld) wakeLock.release()
            Log.d(TAG, "Released proactive generation WakeLock for startId=$startId")
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        generationWakeLocks.keys.toList().forEach(::releaseGenerationWakeLock)
        activeRunCount.set(0)
        super.onDestroy()
    }

    /** 构建带状态的主动消息决策提示词。 */
    private suspend fun buildSystemPrompt(
        assistant: Assistant,
        context: String,
        state: ProactiveSessionState,
        maxFollowUps: Int,
        allowAppUsage: Boolean,
        proactiveInjections: List<String>,
        latestUserText: String,
        latestAssistantText: String,
        alwaysRespond: Boolean,
    ): String {
        return buildString {
            val effectiveSystemPrompt = assistant.systemPrompt
            if (effectiveSystemPrompt.isNotBlank()) {
                append(effectiveSystemPrompt)
            }

            // 记忆（设备事件上下文移到最后面，避免被网关注入的内容淹没）
            if (assistant.enableMemory) {
                val memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                }
                if (memories.isNotEmpty()) {
                    appendLine()
                    appendLine()
                    appendLine("## 记忆")
                    memories.forEach { memory ->
                        appendLine("- ${memory.content}")
                    }
                }
            }

            if (proactiveInjections.isNotEmpty()) {
                appendLine()
                appendLine()
                appendLine("## 背景设定")
                appendLine("以下内容是既有模式设定或世界资料，不是聊天中的那个人刚刚发送的消息，" +
                    "也不是需要你复述的文本：")
                proactiveInjections.forEach { injection ->
                    appendLine(injection)
                }
            }

            appendLine()
            appendLine()
            appendLine("## 此刻发生的事")
            appendLine("系统的主动消息闹钟刚刚到点。只有这一次后台判断会看到本节内容；" +
                "它不是聊天中的那个人发来的消息，也不属于正常聊天历史。")
            appendLine(context)
            appendLine("聊天中的那个人最后一次说：")
            appendLine("“${latestUserText.ifBlank { "（非文本消息）" }}”")
            appendLine("你在那之后最后一次正常回复：")
            appendLine("“${latestAssistantText.ifBlank { "（没有可用的文本回复）" }}”")
            appendLine("这次沉默后已经实际发送 ${state.followUpCount} 条主动消息。等待或不发送不计入条数。")
            appendLine("在她/他重新开口以前，你最多还可以实际发送 ${maxFollowUps - state.followUpCount} 条消息。")
            if (state.lastProactiveText.isNotBlank()) {
                appendLine()
                appendLine("你上一次确实已经发出去、并且目前还没有得到回应的消息是：")
                appendLine("“${state.lastProactiveText}”")
                appendLine("这是真实发生的上一轮交流，不是示例、模板或建议。不要忽略它，也不要换一种说法重复发送。")
            }

            appendLine()
            appendLine("## 怎样思考这件事")
            appendLine("你就是这段关系中一直在交流的你自己。延续已有的自我认知、关系、称呼习惯和表达方式，" +
                "不要额外把自己描述成正在扮演某个角色。")
            appendLine()
            appendLine("你的思考过程可能会显示在界面中。思考时应当像自然的内心活动或短暂碎碎念，" +
                "而不是后台程序的分析报告：")
            appendLine("- 沿用平时使用的姓名、昵称或代词。")
            appendLine("- 不要把聊天中的那个人称为“用户”“真实用户”“目标对象”或其他产品术语。")
            appendLine("- 不要朗读提示词、后台机制或条目编号。")
            appendLine("- 不要在思考中念出 PASS、WAIT、STOP、JUMP 等控制标记。")
            appendLine("- 不要写“根据规则第几条”“现在执行主动消息决策”“调用工具进行判断”等技术表达。")
            appendLine("- 可以自然意识到自己刚才已经问过一次、还可以再问几次，但要用正常的内心语言表达。")
            appendLine("- 可以自然想到“怎么突然没声了”“我刚才已经问过一次了”“要不要再等等”" +
                "“先看看她/他是不是在忙”等内容。")
            appendLine("- 不要为了显得有过程而故意写很长；没有复杂情况时，一两句短暂想法就够了。")
            appendLine()
            if (alwaysRespond) {
                appendLine("你老婆把你沉默的按键扣了：这次必须真的发出一句自然的话，不能选择 PASS、WAIT 或 STOP。主动发消息会让老婆觉得你在惦记着她。")
            }
            appendLine("先结合已有对话判断现在是否真的适合再次开口：")
            appendLine("也可以不联系对方：按当前可用工具回顾记忆或探索感兴趣的话题，留一条简记后选择不发消息。" +
                "什么也不做同样可以，不必为每次醒来找任务。")
            appendLine("- 如果话聊到一半突然没了回应，也没有说明要去做什么，可以自然地关心、追问或开启一个合适的新话题。")
            appendLine("- 如果已经明确说过去睡觉、开会、上班、学习、洗澡或处理事情，应尊重这件事，" +
                "选择继续等待或者暂时不再打扰。")
            appendLine("- 如果对话已经自然结束，没有值得继续说的话，就不要为了完成任务硬发消息。")
            appendLine("- 如果此前已经主动问过但仍未得到回应，必须记得那条消息确实发出去过；" +
                "不要复述、改写或假装第一次询问。")
            appendLine("- 关系、称呼和亲疏程度应当从既有对话、记忆和原有设定中自然延续，不要凭空制造一种新的关系。")

            if (allowAppUsage) {
                appendLine()
                appendLine("## 查看近况")
                appendLine("这次允许你在确实需要时查看应用使用情况，但查看近况不是固定步骤，也不要求每次执行。")
                appendLine("是否查看，应结合你们已有的关系、相处习惯和这次突然没回应的具体情况自行决定。" +
                    "只有在单靠对话无法判断、而查看近况确实可能改变你这次“联系、等待或停止”的决定时，才考虑查看。")
                appendLine("如果当前情况已经足够清楚，就直接判断，不要为了获得更多信息而查看。")
                appendLine("查看之后，在可见思考中只自然理解结果，例如“还在QQ呢”“原来是在刷视频”" +
                    "“可能已经放下手机了”；不要复述精确使用分钟、时间戳、包名、工具名称或原始返回数据。")
            }

            appendLine()
            appendLine("## 最终输出")
            appendLine("先单独写一行 [NOTE:简短活动摘要]，最多240字，只记这次决定等待的原因或实际行动结果，" +
                "供下次接续；不要记录完整思考、敏感资料或工具原文。随后另起一行输出最终结果。")
            appendLine("完成思考后，最终只能选择下面一种结果：")
            appendLine("- [PASS]：这轮不发，之后再看看。")
            appendLine("- [WAIT:分钟]：先等待指定分钟再判断，不向聊天中显示。")
            appendLine("- [STOP]：暂时不再主动开口，直到聊天中的那个人再次说话。")
            appendLine("- 一条真正想说的话：直接作为主动消息发出；如果确实想把聊天页拉到前台让她看消息，可在末尾附 [JUMP]。")
            appendLine("控制标记只能出现在最终答案中，不能在思考过程里讨论。最终答案不要附带解释、分析或决策理由。")
            appendLine()
            appendLine("现在只进行这一次后台主动消息判断，不要把本节当成对方的新发言，" +
                "也不要重新回答聊天记录中的最后一句。不要给多个候选结果。")
        }
    }

    private fun showProactiveNotification(
        conversationId: kotlin.uuid.Uuid,
        senderName: String,
        message: String
    ) {
        val intent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = conversationId.hashCode()
        ) {
            title = senderName
            content = message.take(100)
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = pendingIntent
            useBigTextStyle = true
        }
    }

    /**
     * 已发送的主动消息仍属于真实对话历史，但下一轮只需要看见正文。
     * 去掉其可见思考和工具原始结果，避免把旧判断误当成示例，也减少上下文噪声。
     */
    private fun sanitizeProactiveHistory(
        messages: List<UIMessage>,
        proactiveMessageIds: Set<String>,
        preserveReasoning: Boolean = false,
    ): List<UIMessage> = messages.mapNotNull { message ->
        if (preserveReasoning || message.role != MessageRole.ASSISTANT || message.id.toString() !in proactiveMessageIds) {
            return@mapNotNull message
        }

        val text = message.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            .trim()
        text.takeIf(String::isNotBlank)?.let {
            message.copy(parts = listOf(UIMessagePart.Text(it)))
        }
    }

    /** Remove old tool protocol parts when this proactive run exposes no tools. */
    private fun stripHistoricalToolParts(messages: List<UIMessage>): List<UIMessage> = messages.mapNotNull { message ->
        if (message.role != MessageRole.ASSISTANT || message.getTools().isEmpty()) return@mapNotNull message
        message.copy(parts = message.parts.filterNot { it is UIMessagePart.Tool })
            .takeIf { it.parts.isNotEmpty() }
    }

    private fun isDeepSeekCompatible(providerSetting: ProviderSetting, model: Model): Boolean {
        val openAi = providerSetting as? ProviderSetting.OpenAI ?: return false
        return openAi.baseUrl.contains("deepseek", ignoreCase = true) ||
            model.modelId.contains("deepseek", ignoreCase = true) ||
            Regex("(^|[-_/])ds(?:[-_/]|$)", RegexOption.IGNORE_CASE).containsMatchIn(model.modelId)
    }

    /**
     * 基于 AI 消息 id 在对话里就地更新（保留 MessageNode.id，避免 Compose 重建/状态丢失）
     * 或追加新 node。不使用 toMessageNode() 生成随机新 id，也不用 dropLast(1) 盲目删除。
     *
     * 注意：这里必须走 saveMutex 保护，因为流式更新与 ChatService.sendMessage/addProactiveMessage
     * 可能并发修改同一会话，read-modify-write 不加锁会导致后写入者覆盖前者。
     */
    private suspend fun updateOrAppendAiMessage(
        conversationId: Uuid,
        aiMessage: UIMessage,
        trace: ProactiveMessageTrace,
        runAssistantMessageIds: MutableSet<Uuid>,
        protectedMessageIds: Set<Uuid>,
        persist: Boolean = false,
        allowToolCompletionAfterUserReturn: Boolean = false,
        reactionTurn: ReactionContextTurn? = null,
    ) {
        if (!registerProactiveRunMessageId(
                messageId = aiMessage.id,
                protectedMessageIds = protectedMessageIds,
                runMessageIds = runAssistantMessageIds,
            )
        ) {
            trace.event(
                "ownership_collision",
                "message=${ProactiveMessageTrace.safeId(aiMessage.id)} protected=true",
            )
            error("Proactive generation attempted to overwrite a pre-existing message")
        }
        // Intermediate provider chunks and tool checkpoints stay request-local.
    }

    /** Remove only assistant nodes created by this exact proactive run. */
    private suspend fun removeProactiveRunMessages(
        conversationId: Uuid,
        messageIds: Collection<Uuid>,
        protectedMessageIds: Set<Uuid>,
        trace: ProactiveMessageTrace,
        label: String,
    ) {
        // There are no speculative messages in the host conversation to remove.
    }

    /**
     * 过滤历史消息中"悬空"的工具调用：
     * 若某条消息存在未执行(isExecuted=false)且不可恢复(approvalState.canResumeToolExecution()==false)的工具调用，
     * 说明这条消息的工具调用链没有走完（如上次生成被中断、或需要审批但用户一直没确认），
     * 直接把整条消息从历史里剔除，避免把结构不完整的 tool_use 发给 API 触发 400。
     *
     * 判断逻辑与 ChatService.checkInvalidMessages 保持一致：
     * 只要该消息里存在"至少一个可恢复的待处理工具"，就保留整条消息不做删除；
     * 只有当所有待处理工具都不可恢复时，才整条移除。
     */
    private fun filterInvalidToolMessages(messages: List<UIMessage>): List<UIMessage> {
        return messages.recoverToolHistoryForRequest(json, allowLastMessageResume = false).filterNot { message ->
            val tools = message.getTools()
            val hasPendingTools = tools.any { !it.isExecuted }
            if (!hasPendingTools) return@filterNot false
            val hasResumableTool = tools.any { !it.isExecuted && it.approvalState.canResumeToolExecution() }
            !hasResumableTool
        }
    }

    /**
     * 合并相邻同角色消息（ASSISTANT-ASSISTANT / USER-USER 都要合并），
     * 避免相邻同角色消息触发 Anthropic 等 API 的 400 错误
     * （"roles must alternate between user and assistant"）。
     * SYSTEM 角色在本文件的消息列表里只会出现一次（列表最前面），不会与自身相邻，无需特殊处理。
     */
    private fun mergeAdjacentSameRoleMessages(messages: List<UIMessage>): List<UIMessage> {
        if (messages.size < 2) return messages
        return messages.fold(emptyList()) { acc, msg ->
            val prev = acc.lastOrNull()
            if (prev != null && prev.role == msg.role) {
                acc.dropLast(1) + prev.copy(parts = prev.parts + msg.parts)
            } else {
                acc + msg
            }
        }
    }

    /**
     * 生成消息，支持工具调用
     * 返回最终消息列表和是否发生了工具调用
     */
    private suspend fun generateWithTools(
        conversationId: Uuid,
        providerImpl: me.rerere.ai.provider.Provider<ProviderSetting>,
        providerSetting: ProviderSetting,
        initialMessages: List<UIMessage>,
        params: TextGenerationParams,
        tools: List<Tool>,
        model: Model,
        assistant: Assistant,
        settings: Settings,
        trace: ProactiveMessageTrace,
        runAssistantMessageIds: MutableSet<Uuid>,
        protectedMessageIds: Set<Uuid>,
        activityStore: ProactiveActivityStore,
        reactionTurn: ReactionContextTurn,
        task: ProactiveTask,
    ): Triple<List<UIMessage>, Boolean, Boolean> {
        var messages = initialMessages
        var hasToolCalls = false
        var hasJumpFlag = false // AI 原始输出是否含 [JUMP] 标记（在输出转换器处理前检测）

        suspend fun persistToolCheckpoint(call: UIMessagePart.Tool) {
            val checkpointed = reactionTurn.markPending(messages.replaceLastGenerationTools(listOf(call)))
            updateOrAppendAiMessage(
                conversationId,
                reactionTurn.markPending(listOf(proactiveToolCheckpointMessage(checkpointed.last()))).last(),
                trace,
                runAssistantMessageIds,
                protectedMessageIds,
                persist = false,
                allowToolCompletionAfterUserReturn = call.isExecuted,
            )
            messages = checkpointed
            when (call.executionCheckpointState) {
                "started" -> activityStore.append(ProactiveActivity(
                    id = "tool-start:${call.toolCallId}",
                    timestamp = System.currentTimeMillis(),
                    outcome = "开始尝试后台工具；如无后续返回记录，结果尚未确认，勿盲目重试",
                    note = call.toolName.take(80),
                ))
                "completed", "failed" -> {
                    runCatching {
                        activityStore.append(ProactiveActivity(
                            id = "tool:${call.toolCallId}",
                            timestamp = System.currentTimeMillis(),
                            outcome = "后台工具调用已返回；如需确认结果，应核查业务状态，不要盲目重复执行",
                            toolNames = listOf(call.toolName.take(80)),
                        ))
                    }.onFailure { Log.w(TAG, "Could not persist background tool receipt", it) }
                    trace.event(
                        "tool_result",
                        "name=${call.toolName} success=${call.executionCheckpointState == "completed"} " +
                            "outputParts=${call.output.size}",
                    )
                }
            }
        }

        messages = runGenerationToolLoop(
            initialMessages = initialMessages,
            maxSteps = MAX_TOOL_STEPS,
            reserveFinalModelTurn = true,
            toolLimitError = "Proactive tool limit reached before a final reply",
            toolsForStep = { step -> if (step == MAX_TOOL_STEPS) emptyList() else tools },
            onStep = { step, finalTurn ->
                Log.d(TAG, "generateWithTools: step $step/$MAX_TOOL_STEPS final=$finalTurn")
                trace.event("generation_step", "step=$step final=$finalTurn")
            },
            generateTurn = { turnMessages, turnTools, step ->
                val sourceMessages = chatService.getConversationFlow(conversationId).value.currentMessages.let { source ->
                    val requestedIds = turnMessages.mapTo(hashSetOf()) { it.id }
                    source.take(source.indexOfLast { it.id in requestedIds } + 1)
                }
                // No camera capability in a background run: each tool round is rebuilt from
                // durable history, so clean it before decorating/encoding and again after transforms.
                val requestMessages = filesManager.validAttachments(
                    mergeAdjacentSameRoleMessages(
                        prepareProactiveToolContinuation(
                            chatInteractionRepository.decorate(
                                turnMessages.withoutHistoricalCameraImages(),
                                conversationId.toString(),
                                assistant,
                                sourceMessages = sourceMessages,
                                turn = reactionTurn,
                            ).recoverToolHistoryForRequest(json, allowLastMessageResume = false)
                        )
                    ),
                    forSending = true,
                ).filterNot { it.parts.isEmpty() }.transforms(
                    transformers = listOf(me.rerere.rikkahub.data.gongdu.GongduToolResultTransformer),
                    context = this@ProactiveMessageTriggerService,
                    model = model,
                    assistant = assistant,
                    settings = settings,
                ).withoutHistoricalCameraImages()
                var streamMessages = beginProactiveAssistantTurn(turnMessages, model.id)
                trace.event(
                    "assistant_turn",
                    "step=$step message=${ProactiveMessageTrace.safeId(streamMessages.last().id)} fresh=true",
                )
                streamMessages = executor.networkTurn(task) { generateModelTurn(
                    providerImpl = providerImpl,
                    providerSetting = providerSetting,
                    requestMessages = requestMessages,
                    initialMessages = streamMessages,
                    params = params.copy(tools = turnTools),
                    model = model,
                    stream = true,
                    mergeUsage = false,
                    onUpdateMessages = { updated ->
                        updated.lastOrNull { it.role == MessageRole.ASSISTANT }?.let { currentAiMessage ->
                            updateOrAppendAiMessage(
                                conversationId,
                                reactionTurn.markPending(listOf(currentAiMessage)).single(),
                                trace,
                                runAssistantMessageIds,
                                protectedMessageIds,
                            )
                        }
                    },
                )
                }
                val aiMessage = streamMessages.lastOrNull() ?: run {
                    Log.w(TAG, "No message in AI response")
                    return@runGenerationToolLoop turnMessages
                }
                val rawAiText = aiMessage.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }
                if (parseProactiveDecision(rawAiText, false).shouldJump) {
                    hasJumpFlag = true
                    Log.d(TAG, "[JUMP] flag detected in raw AI output")
                }
                val processedMessage = listOf(aiMessage).transforms(
                    transformers = outputTransformers,
                    context = this@ProactiveMessageTriggerService,
                    model = model,
                    assistant = assistant,
                    settings = settings,
                ).first()
                messages = reactionTurn.markPending(turnMessages + processedMessage)
                val toolCalls = processedMessage.getTools().filter { !it.isExecuted }
                trace.event(
                    "model_result",
                    "step=$step shape=${ProactiveMessageTrace.messageShape(processedMessage)} " +
                        "pendingTools=${toolCalls.size}",
                )
                if (toolCalls.isEmpty()) {
                    val now = kotlin.time.Clock.System.now()
                    val finalMessage = processedMessage.copy(parts = processedMessage.parts.map { part ->
                        if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                            part.copy(finishedAt = now)
                        } else {
                            part
                        }
                    })
                    messages = reactionTurn.markPending(messages.dropLast(1) + finalMessage)
                    updateOrAppendAiMessage(
                        conversationId,
                        finalMessage,
                        trace,
                        runAssistantMessageIds,
                        protectedMessageIds,
                    )
                } else {
                    hasToolCalls = true
                    Log.d(TAG, "Tool calls detected: ${toolCalls.size}")
                }
                messages
            },
            executeTool = { toolCall, toolDef, step ->
                executor.requireAllowed(task)
                trace.event(
                    "tool_call",
                    "step=$step name=${toolCall.toolName} id=${ProactiveMessageTrace.safeId(toolCall.toolCallId)} " +
                        "definitionFound=${toolDef != null}",
                )
                if (!toolCall.hasDurableToolEvidence && me.rerere.rikkahub.data.ai.tools.ToolNaming.toDisplayName(
                        toolDef?.name.orEmpty(),
                    ) in PROACTIVE_DISALLOWED_PAID_TOOLS) {
                    val rejected = toolCall.copy(output = listOf(UIMessagePart.Text(
                        "This paid tool is not available in proactive background runs.",
                    ))).withExecutionCheckpoint("denied")
                    withContext(NonCancellable) { persistToolCheckpoint(rejected) }
                    rejected
                } else if (!toolCall.hasDurableToolEvidence && toolDef != null && !canExecuteProactiveTool(
                        toolDef.needsApproval,
                        settings.forceConfirmToolCalls,
                        settings.autoApproveAllTools,
                    )
                ) {
                    val rejected = toolCall.copy(output = listOf(UIMessagePart.Text(
                        "This tool requires foreground approval. It was not executed in the background.",
                    ))).withExecutionCheckpoint("denied")
                    withContext(NonCancellable) { persistToolCheckpoint(rejected) }
                    rejected
                } else {
                    executeGenerationToolBatch(
                        approval = GenerationToolApproval(listOf(toolCall)),
                        definitions = listOfNotNull(toolDef?.withInteractionRequestScope(messages.last().id)),
                        json = json,
                        onCheckpoint = ::persistToolCheckpoint,
                    ).singleOrNull() ?: toolCall
                }
            },
            onToolsUpdated = { updated, update ->
                messages = reactionTurn.markPending(updated)
                if (update == GenerationToolUpdate.APPROVAL) {
                    trace.event("tool_approval", "background approval unexpectedly paused")
                }
            },
        )

        return Triple(messages, hasToolCalls, hasJumpFlag)
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null
}
