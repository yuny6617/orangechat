/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.files.attachmentReferenceGuard

import me.rerere.rikkahub.data.security.SecretCrypto

import android.content.Context
import android.util.Log
import androidx.datastore.core.IOException
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.preferencesDataStoreFile
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV2Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV3Migration
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.resolveCompressPrompt
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.LEARNING_MODE_PROMPT
import me.rerere.rikkahub.data.interaction.normalizeCustomChatReactionPresets
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.ExternalMemory
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.MiniApp
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.data.sync.RestorePreferencesSession
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val TAG = "PreferencesStore"

private val Context.settingsStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { context ->
        listOf(
            PreferenceStoreV1Migration(),
            PreferenceStoreV2Migration(),
            PreferenceStoreV3Migration()
        )
    }
)

class SettingsStore(
    private val context: Context,
    scope: AppScope,
) : KoinComponent {
    companion object {
        // 版本号
        val VERSION = intPreferencesKey("data_version")

        // UI设置
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_ID = stringPreferencesKey("theme_id")
        val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        val GONGDU_SETTING = stringPreferencesKey("gongdu_setting")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
        val CUSTOM_REACTION_PRESETS = stringPreferencesKey("custom_reaction_presets")

        // 免责声明与用户协议
        val DISCLAIMER_ACCEPTED = booleanPreferencesKey("disclaimer_accepted")
        val DISCLAIMER_ACCEPTED_AT = intPreferencesKey("disclaimer_accepted_at")

        // 模型选择
        val ENABLE_WEB_SEARCH = booleanPreferencesKey("enable_web_search")
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val TITLE_MODEL = stringPreferencesKey("title_model")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val SUGGESTION_MODEL = stringPreferencesKey("suggestion_model")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val IMAGE_GENERATION_ASPECT_RATIO = stringPreferencesKey("image_generation_aspect_ratio")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        val TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
        val TRANSLATE_THINKING_BUDGET = intPreferencesKey("translate_thinking_budget")
        val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val OCR_PROMPT = stringPreferencesKey("ocr_prompt")
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")

        // 提供商
        val PROVIDERS = stringPreferencesKey("providers")

        // 助手
        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        val ASSISTANTS = stringPreferencesKey("assistants")
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")

        // 搜索
        val SEARCH_SERVICES = stringPreferencesKey("search_services")
        val SEARCH_COMMON = stringPreferencesKey("search_common")
        val SEARCH_SELECTED = intPreferencesKey("search_selected")

        // MCP
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")

        // WebDAV
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")

        // S3
        val S3_CONFIG = stringPreferencesKey("s3_config")

        // TTS
        val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")

        // ASR
        val ASR_PROVIDERS = stringPreferencesKey("asr_providers")
        val SELECTED_ASR_PROVIDER = stringPreferencesKey("selected_asr_provider")
        val VOICE_CALL_KEEP_LISTENING_DURING_REPLY = booleanPreferencesKey("voice_call_keep_listening_during_reply")

        // 视频通话（礼物默认关闭）
        val VIDEO_CALL_SETTING = stringPreferencesKey("video_call_setting")

        // Web Server
        val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        val WEB_SERVER_PORT = intPreferencesKey("web_server_port")
        val WEB_SERVER_JWT_ENABLED = booleanPreferencesKey("web_server_jwt_enabled")
        val WEB_SERVER_ACCESS_PASSWORD = stringPreferencesKey("web_server_access_password")
        val WEB_SERVER_LOCALHOST_ONLY = booleanPreferencesKey("web_server_localhost_only")

        // 提示词注入
        val MODE_INJECTIONS = stringPreferencesKey("mode_injections")
        val LOREBOOKS = stringPreferencesKey("lorebooks")
        val QUICK_MESSAGES = stringPreferencesKey("quick_messages")

        // 备份提醒
        val BACKUP_REMINDER_CONFIG = stringPreferencesKey("backup_reminder_config")
        val AUTO_BACKUP_CONFIG = stringPreferencesKey("auto_backup_config")

        // 统计
        val LAUNCH_COUNT = intPreferencesKey("launch_count")

        // 赞助提醒
        val SPONSOR_ALERT_DISMISSED_AT = intPreferencesKey("sponsor_alert_dismissed_at")

        // 系统工具设置
        val SYSTEM_TOOLS_SETTING = stringPreferencesKey("system_tools_setting")

        // 主动消息设置
        val PROACTIVE_MESSAGE_SETTING = stringPreferencesKey("proactive_message_setting")

        // 保活服务设置
        val KEEP_ALIVE_ENABLED = booleanPreferencesKey("keep_alive_enabled")

        // 外部记忆库
        val EXTERNAL_MEMORIES = stringPreferencesKey("external_memories")
        val SEREIN_HOOK = stringPreferencesKey("serein_hook")

        // 微信 Bot (iLink 协议)
        val WECHAT_BOT_SETTING = stringPreferencesKey("wechat_bot_setting")

        // QQ Bot (API v2, WebSocket 网关)
        val QQ_BOT_SETTING = stringPreferencesKey("qq_bot_setting")

        // Mini App
        val MINI_APPS = stringPreferencesKey("mini_apps")

        // 强制确认所有工具调用
        val FORCE_CONFIRM_TOOL_CALLS = booleanPreferencesKey("force_confirm_tool_calls")

        // 后台触发工作流时拦截敏感工具（needsApproval=true）
        val WORKFLOW_HEADLESS_BLOCK_SENSITIVE = booleanPreferencesKey("workflow_headless_block_sensitive")

        // 自动批准所有工具调用（懒人模式）
        val AUTO_APPROVE_ALL_TOOLS = booleanPreferencesKey("auto_approve_all_tools")

        /** Composite settings that contain credentials or authentication tokens. */
        private val PROTECTED_STRING_KEYS: Set<Preferences.Key<String>> = setOf(
            PROVIDERS,
            ASSISTANTS,
            SEARCH_SERVICES,
            MCP_SERVERS,
            WEBDAV_CONFIG,
            S3_CONFIG,
            TTS_PROVIDERS,
            ASR_PROVIDERS,
            WEB_SERVER_ACCESS_PASSWORD,
            SYSTEM_TOOLS_SETTING,
            WECHAT_BOT_SETTING,
            QQ_BOT_SETTING,
            SEREIN_HOOK,
            MINI_APPS,
        )
    }

    private val dataStore = context.settingsStore
    private val updateMutex = Mutex()
    private val legacyMemoryRetirement = LegacyExternalMemoryRetirement()
    private val protectedDecodeCache = ProtectedSettingsDecodeCache()

    init {
        // Upgrade existing plaintext secrets in the background. The read path below still
        // understands both formats while this migration is pending or unable to run.
        scope.launch(Dispatchers.IO) {
            try {
                updateMutex.withLock {
                    dataStore.edit { preferences ->
                        retireExternalMemoryPreferences(preferences)
                        PROTECTED_STRING_KEYS.forEach { key ->
                            val stored = preferences[key]
                            if (!stored.isNullOrEmpty() && !SecretCrypto.isEncrypted(stored)) {
                                try {
                                    val encrypted = SecretCrypto.encrypt(stored, key.name)
                                    if (!encrypted.isNullOrEmpty()) {
                                        preferences[key] = encrypted
                                    }
                                } catch (error: Exception) {
                                    Log.w(TAG, "Failed to encrypt protected preference '${key.name}'", error)
                                }
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Protected preference encryption migration failed", error)
            }
        }
    }


    // 用于检测 assistants 列表是否真正变化，避免无关设置写入触发 Pebble 模板缓存清空
    @Volatile
    private var lastAssistantsForCacheInvalidation: List<Assistant>? = null

    private val unreadableProtectedSettingKeys = ConcurrentHashMap.newKeySet<String>()

    val settingsFlowRaw = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }.map { preferences ->
            Settings(
                gongdu = preferences[GONGDU_SETTING]?.let {
                    runCatching { JsonInstant.decodeFromString<me.rerere.rikkahub.data.gongdu.GongduSetting>(it) }.getOrNull()
                } ?: me.rerere.rikkahub.data.gongdu.GongduSetting(),
                disclaimerAccepted = preferences[DISCLAIMER_ACCEPTED] == true,
                disclaimerAcceptedAt = preferences[DISCLAIMER_ACCEPTED_AT] ?: 0,
                enableWebSearch = preferences[ENABLE_WEB_SEARCH] == true,
                favoriteModels = preferences[FAVORITE_MODELS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                chatModelId = preferences[SELECT_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                titleModelId = preferences[TITLE_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                translateModeId = preferences[TRANSLATE_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                suggestionModelId = preferences[SUGGESTION_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                imageGenerationModelId = preferences[IMAGE_GENERATION_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                imageGenerationAspectRatio = preferences[IMAGE_GENERATION_ASPECT_RATIO]
                    ?.let { saved -> ImageAspectRatio.entries.firstOrNull { it.name == saved } }
                    ?: ImageAspectRatio.SQUARE,
                titlePrompt = preferences[TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
                translatePrompt = preferences[TRANSLATION_PROMPT] ?: DEFAULT_TRANSLATION_PROMPT,
                translateThinkingBudget = preferences[TRANSLATE_THINKING_BUDGET] ?: 0,
                suggestionPrompt = preferences[SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
                ocrModelId = preferences[OCR_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                ocrPrompt = preferences[OCR_PROMPT] ?: DEFAULT_OCR_PROMPT,
                compressModelId = preferences[COMPRESS_MODEL]?.let { Uuid.parse(it) },
                compressPrompt = resolveCompressPrompt(preferences[COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT),
                assistantId = preferences[SELECT_ASSISTANT]?.let { Uuid.parse(it) }
                    ?: DEFAULT_ASSISTANT_ID,
                assistantTags = preferences[ASSISTANT_TAGS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                providers = preferences.readPlaintextOrMigrate(PROVIDERS, emptyList()) { JsonInstant.decodeFromString(it) },
                assistants = preferences.readPlaintextOrMigrate(ASSISTANTS, emptyList()) { JsonInstant.decodeFromString(it) },
                dynamicColor = preferences[DYNAMIC_COLOR] != false,
                themeId = preferences[THEME_ID] ?: PresetThemes[0].id,
                customThemes = runCatching { preferences[CUSTOM_THEMES]?.let { JsonInstant.decodeFromString<List<CustomTheme>>(it) } }.getOrNull() ?: emptyList(),
                developerMode = preferences[DEVELOPER_MODE] == true,
                customReactionPresets = runCatching {
                    preferences[CUSTOM_REACTION_PRESETS]?.let { JsonInstant.decodeFromString<List<String>>(it) }
                }.getOrNull()?.let(::normalizeCustomChatReactionPresets).orEmpty(),
                displaySetting = runCatching { JsonInstant.decodeFromString<DisplaySetting>(preferences[DISPLAY_SETTING] ?: "{}") }.getOrElse { DisplaySetting() },
                searchServices = preferences.readPlaintextOrMigrate<List<SearchServiceOptions>>(
                    SEARCH_SERVICES,
                    listOf<SearchServiceOptions>(SearchServiceOptions.DEFAULT),
                ) { JsonInstant.decodeFromString(it) },
                searchCommonOptions = runCatching { preferences[SEARCH_COMMON]?.let { JsonInstant.decodeFromString<SearchCommonOptions>(it) } }.getOrNull() ?: SearchCommonOptions(),
                searchServiceSelected = preferences[SEARCH_SELECTED] ?: 0,
                mcpServers = preferences.readPlaintextOrMigrate(MCP_SERVERS, emptyList()) { JsonInstant.decodeFromString(it) },
                webDavConfig = preferences.readPlaintextOrMigrate(WEBDAV_CONFIG, WebDavConfig()) { JsonInstant.decodeFromString(it) },
                s3Config = preferences.readPlaintextOrMigrate(S3_CONFIG, S3Config()) { JsonInstant.decodeFromString(it) },
                ttsProviders = preferences.readPlaintextOrMigrate(TTS_PROVIDERS, emptyList()) { JsonInstant.decodeFromString(it) },
                selectedTTSProviderId = preferences[SELECTED_TTS_PROVIDER]?.let { Uuid.parse(it) }
                    ?: DEFAULT_SYSTEM_TTS_ID,
                asrProviders = preferences.readPlaintextOrMigrate(ASR_PROVIDERS, emptyList()) { JsonInstant.decodeFromString(it) },
                selectedASRProviderId = preferences[SELECTED_ASR_PROVIDER]?.let { Uuid.parse(it) },
                voiceCallKeepListeningDuringReply = preferences[VOICE_CALL_KEEP_LISTENING_DURING_REPLY] ?: true,
                videoCallSetting = runCatching {
                    preferences[VIDEO_CALL_SETTING]?.let {
                        JsonInstant.decodeFromString<me.rerere.rikkahub.data.video.VideoCallSetting>(it)
                    }
                }.getOrNull() ?: me.rerere.rikkahub.data.video.VideoCallSetting(),
                modeInjections = runCatching { preferences[MODE_INJECTIONS]?.let { JsonInstant.decodeFromString<List<PromptInjection.ModeInjection>>(it) } }.getOrNull() ?: emptyList(),
                lorebooks = runCatching { preferences[LOREBOOKS]?.let { JsonInstant.decodeFromString<List<Lorebook>>(it) } }.getOrNull() ?: emptyList(),
                quickMessages = runCatching { preferences[QUICK_MESSAGES]?.let { JsonInstant.decodeFromString<List<QuickMessage>>(it) } }.getOrNull() ?: emptyList(),
                webServerEnabled = preferences[WEB_SERVER_ENABLED] == true,
                webServerPort = preferences[WEB_SERVER_PORT] ?: 8080,
                webServerJwtEnabled = preferences[WEB_SERVER_JWT_ENABLED] == true,
                webServerAccessPassword = preferences.readPlaintextOrMigrate(WEB_SERVER_ACCESS_PASSWORD, "") { it },
                webServerLocalhostOnly = preferences[WEB_SERVER_LOCALHOST_ONLY] == true,
                backupReminderConfig = preferences[BACKUP_REMINDER_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: BackupReminderConfig(),
                autoBackupConfig = runCatching {
                    preferences[AUTO_BACKUP_CONFIG]?.let { JsonInstant.decodeFromString<AutoBackupConfig>(it) }
                }.getOrNull() ?: AutoBackupConfig(),
                launchCount = preferences[LAUNCH_COUNT] ?: 0,
                sponsorAlertDismissedAt = preferences[SPONSOR_ALERT_DISMISSED_AT] ?: 0,
                systemToolsSetting = preferences.readPlaintextOrMigrate(SYSTEM_TOOLS_SETTING, SystemToolsSetting()) { JsonInstant.decodeFromString(it) },
                proactiveMessageSetting = runCatching { preferences[PROACTIVE_MESSAGE_SETTING]?.let { JsonInstant.decodeFromString<ProactiveMessageSetting>(it) } }.getOrNull() ?: ProactiveMessageSetting(),
                wechatBotSetting = preferences.readPlaintextOrMigrate(WECHAT_BOT_SETTING, WechatBotSetting()) { JsonInstant.decodeFromString(it) },
                qqBotSetting = preferences.readPlaintextOrMigrate(QQ_BOT_SETTING, QqBotSetting()) { JsonInstant.decodeFromString(it) },
                keepAliveEnabled = preferences[KEEP_ALIVE_ENABLED] == true,
                externalMemories = emptyList(),
                sereinHook = preferences.readPlaintextOrMigrate(SEREIN_HOOK, me.rerere.rikkahub.data.serein.SereinHookConfig()) { JsonInstant.decodeFromString(it) },
                miniApps = preferences.readPlaintextOrMigrate(MINI_APPS, emptyList()) { JsonInstant.decodeFromString(it) },
                forceConfirmToolCalls = preferences[FORCE_CONFIRM_TOOL_CALLS] != false,
                workflowHeadlessBlockSensitive = preferences[WORKFLOW_HEADLESS_BLOCK_SENSITIVE] != false,
                autoApproveAllTools = preferences[AUTO_APPROVE_ALL_TOOLS] == true,
            ).withoutLegacyExternalMemories()
        }
        .map {
            var providers = it.providers.ifEmpty { DEFAULT_PROVIDERS }.toMutableList()
            DEFAULT_PROVIDERS.forEach { defaultProvider ->
                if (providers.none { it.id == defaultProvider.id }) {
                    providers.add(defaultProvider.copyProvider())
                }
            }
            providers = providers.map { provider ->
                val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
                if (defaultProvider != null) {
                    provider.copyProvider(
                        builtIn = defaultProvider.builtIn,
                        description = defaultProvider.description,
                        shortDescription = defaultProvider.shortDescription,
                    )
                } else provider
            }.toMutableList()
            val assistants = it.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
            DEFAULT_ASSISTANTS.forEach { defaultAssistant ->
                if (assistants.none { it.id == defaultAssistant.id }) {
                    assistants.add(defaultAssistant.copy())
                }
            }
            val ttsProviders = it.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
            DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
                if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
                    ttsProviders.add(defaultTTSProvider.copyProvider())
                }
            }
            it.copy(
                providers = providers,
                assistants = assistants,
                ttsProviders = ttsProviders,
            )
        }
        .map { settings ->
            // 去重并清理无效引用
            val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
            val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
            val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
            val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
            val asrProviders = settings.asrProviders.distinctBy { it.id }
            settings.copy(
                providers = settings.providers.distinctBy { it.id }.map { provider ->
                    when (provider) {
                        is ProviderSetting.OpenAI -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Google -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Codex -> provider.copy(models = provider.models.distinctBy { it.id })
                        is ProviderSetting.Claude -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )
                    }
                },
                assistants = settings.assistants.distinctBy { it.id }.map { assistant ->
                    assistant.copy(
                        // 过滤掉不存在的 MCP 服务器 ID
                        mcpServers = assistant.mcpServers.filter { serverId ->
                            serverId in validMcpServerIds
                        }.toSet(),
                        // 过滤掉不存在的模式注入 ID
                        modeInjectionIds = assistant.modeInjectionIds.filter { id ->
                            id in validModeInjectionIds
                        }.toSet(),
                        // 过滤掉不存在的 Lorebook ID
                        lorebookIds = assistant.lorebookIds.filter { id ->
                            id in validLorebookIds
                        }.toSet(),
                        // 过滤掉不存在的快捷消息 ID
                        quickMessageIds = assistant.quickMessageIds.filter { id ->
                            id in validQuickMessageIds
                        }.toSet()
                    )
                },
                ttsProviders = settings.ttsProviders.distinctBy { it.id },
                asrProviders = asrProviders,
                selectedASRProviderId = settings.selectedASRProviderId
                    ?.takeIf { id -> asrProviders.any { provider -> provider.id == id } }
                    ?: asrProviders.firstOrNull()?.id,
                favoriteModels = settings.favoriteModels.filter { uuid ->
                    settings.providers.flatMap { it.models }.any { it.id == uuid }
                },
                modeInjections = settings.modeInjections.distinctBy { it.id },
                lorebooks = settings.lorebooks.distinctBy { it.id },
                quickMessages = settings.quickMessages.distinctBy { it.id },
                miniApps = settings.miniApps.distinctBy { it.id },
            )
        }
        .flowOn(Dispatchers.IO)
        .onEach {
            // 只在助手列表变化时才清空 Pebble 模板缓存（assistant 的 messageTemplate 字段决定模板内容）
            // 避免无关设置变化（如显示设置、provider 设置等）触发不必要的模板重新编译
            if (it.assistants != lastAssistantsForCacheInvalidation) {
                lastAssistantsForCacheInvalidation = it.assistants
                get<PebbleEngine>().templateCache.invalidateAll()
            }
        }

    val settingsFlow = MutableStateFlow(Settings.dummy())
    private val pendingWrites = AtomicInteger()

    init {
        scope.launch {
            runCatching {
                settingsFlowRaw.distinctUntilChanged().collect { committed ->
                    // A slow disk emission must not visually undo a newer optimistic write.
                    if (pendingWrites.get() == 0) {
                        attachmentReferenceGuard.beginWrite().use {
                            settingsFlow.value = committed
                        }
                    }
                }
            }.onFailure {
                it.printStackTrace()
                Log.e(TAG, "Error while collecting settings: ${it.message}", it)
            }
        }
    }

    suspend fun update(settings: Settings) {
        if(settings.init) {
            Log.w(TAG, "Cannot update dummy settings")
            return
        }
        updateMutex.withLock {
            publishAndPersist(settings, settingsFlowRaw.first())
        }
    }

    internal suspend fun <T> withRestoreSession(block: suspend (RestorePreferencesSession<Settings>) -> T): T =
        updateMutex.withLock {
            val previous = dataStore.data.first()
            // Finish migrations and validate the current snapshot before touching any restore targets.
            settingsFlowRaw.first()
            block(RestorePreferencesSession(
                file = context.preferencesDataStoreFile("settings"),
                apply = { restored ->
                    check(!restored.init) { "Cannot restore dummy settings" }
                    publishAndPersist(restored, settingsFlowRaw.first())
                },
                rollback = {
                    attachmentReferenceGuard.beginWrite().use {
                        dataStore.updateData { previous }
                        settingsFlow.value = settingsFlowRaw.first()
                    }
                },
            ))
        }

    /** Apply a change to the latest snapshot and persist it atomically. */
    suspend fun update(fn: (Settings) -> Settings) {
        updateMutex.withLock {
            val current = settingsFlowRaw.first()
            publishAndPersist(fn(current), current)
        }
    }

    /** Merge an editor's changes under the same lock as the write, preserving newer unrelated fields. */
    suspend fun updateFrom(previous: Settings, desired: Settings) {
        if (previous.init || desired.init) {
            Log.w(TAG, "Cannot update from dummy settings")
            return
        }
        // Three full settings JSON trees may be merged here. Never do that on the UI thread.
        withContext(Dispatchers.Default) {
            update { current -> current.withSettingsEdit(previous, desired) }
        }
    }

    private suspend fun publishAndPersist(requested: Settings, previous: Settings) {
        val settings = requested.withoutLegacyExternalMemories()
        attachmentReferenceGuard.beginWrite().use {
            if(settings.init) {
                Log.w(TAG, "Cannot update dummy settings")
                return
            }
            pendingWrites.incrementAndGet()
            settingsFlow.value = settings
            try {
                withContext(Dispatchers.IO) {
                    persistSettings(settings, previous)
                }
            } finally {
                if (pendingWrites.decrementAndGet() == 0) {
                    withContext(NonCancellable) {
                        runCatching { settingsFlow.value = settingsFlowRaw.first() }
                            .onFailure {
                                Log.w(TAG, "Failed to refresh settings after write", it)
                            }
                    }
                }
            }
        }
    }

    private suspend fun persistSettings(
        settings: Settings,
        previous: Settings,
    ) {
        val loadedSettings = previous
        fun canWriteProtected(
            key: Preferences.Key<String>,
            value: Any?,
            loadedValue: Any?,
        ): Boolean {
            if (key.name !in unreadableProtectedSettingKeys) return true
            if (value == loadedValue) return false
            unreadableProtectedSettingKeys.remove(key.name)
            return true
        }
        dataStore.edit { preferences ->
            preferences[DYNAMIC_COLOR] = settings.dynamicColor
            preferences[THEME_ID] = settings.themeId
            preferences[CUSTOM_THEMES] = JsonInstant.encodeToString(settings.customThemes)
            preferences[DEVELOPER_MODE] = settings.developerMode
            preferences[CUSTOM_REACTION_PRESETS] =
                JsonInstant.encodeToString(normalizeCustomChatReactionPresets(settings.customReactionPresets))
            preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(settings.displaySetting)
            if (settings.gongdu != previous.gongdu) preferences[GONGDU_SETTING] = JsonInstant.encodeToString(settings.gongdu)

            preferences[DISCLAIMER_ACCEPTED] = settings.disclaimerAccepted
            preferences[DISCLAIMER_ACCEPTED_AT] = settings.disclaimerAcceptedAt

            preferences[ENABLE_WEB_SEARCH] = settings.enableWebSearch
            preferences[FAVORITE_MODELS] = JsonInstant.encodeToString(settings.favoriteModels)
            preferences[SELECT_MODEL] = settings.chatModelId.toString()
            preferences[TITLE_MODEL] = settings.titleModelId.toString()
            preferences[TRANSLATE_MODEL] = settings.translateModeId.toString()
            preferences[SUGGESTION_MODEL] = settings.suggestionModelId.toString()
            preferences[IMAGE_GENERATION_MODEL] = settings.imageGenerationModelId.toString()
            preferences[IMAGE_GENERATION_ASPECT_RATIO] = settings.imageGenerationAspectRatio.name
            preferences[TITLE_PROMPT] = settings.titlePrompt
            preferences[TRANSLATION_PROMPT] = settings.translatePrompt
            preferences[TRANSLATE_THINKING_BUDGET] = settings.translateThinkingBudget
            preferences[SUGGESTION_PROMPT] = settings.suggestionPrompt
            preferences[OCR_MODEL] = settings.ocrModelId.toString()
            preferences[OCR_PROMPT] = settings.ocrPrompt
            settings.compressModelId?.let { preferences[COMPRESS_MODEL] = it.toString() }
                ?: preferences.remove(COMPRESS_MODEL)
            preferences[COMPRESS_PROMPT] = settings.compressPrompt

            if (canWriteProtected(PROVIDERS, settings.providers, loadedSettings.providers)) {
                preferences.putSecretIfChanged(
                    PROVIDERS,
                    settings.providers != previous.providers,
                ) { JsonInstant.encodeToString(settings.providers) }
            }

            if (canWriteProtected(ASSISTANTS, settings.assistants, loadedSettings.assistants)) {
                preferences.putSecretIfChanged(
                    ASSISTANTS,
                    settings.assistants != previous.assistants,
                ) { JsonInstant.encodeToString(settings.assistants) }
            }
            preferences[SELECT_ASSISTANT] = settings.assistantId.toString()
            preferences[ASSISTANT_TAGS] = JsonInstant.encodeToString(settings.assistantTags)

            val canWriteSearchServices = canWriteProtected(
                SEARCH_SERVICES,
                settings.searchServices,
                loadedSettings.searchServices,
            )
            if (canWriteSearchServices) {
                preferences.putSecretIfChanged(
                    SEARCH_SERVICES,
                    settings.searchServices != previous.searchServices,
                ) { JsonInstant.encodeToString(settings.searchServices) }
            }
            preferences[SEARCH_COMMON] = JsonInstant.encodeToString(settings.searchCommonOptions)
            val maxSearchIndex = (settings.searchServices.size - 1).coerceAtLeast(0)
            if (canWriteSearchServices || settings.searchServiceSelected != loadedSettings.searchServiceSelected) {
                preferences[SEARCH_SELECTED] = settings.searchServiceSelected.coerceIn(0, maxSearchIndex)
            }

            if (canWriteProtected(MCP_SERVERS, settings.mcpServers, loadedSettings.mcpServers)) {
                preferences.putSecretIfChanged(
                    MCP_SERVERS,
                    settings.mcpServers != previous.mcpServers,
                ) { JsonInstant.encodeToString(settings.mcpServers) }
            }
            if (canWriteProtected(WEBDAV_CONFIG, settings.webDavConfig, loadedSettings.webDavConfig)) {
                preferences.putSecretIfChanged(
                    WEBDAV_CONFIG,
                    settings.webDavConfig != previous.webDavConfig,
                ) { JsonInstant.encodeToString(settings.webDavConfig) }
            }
            if (canWriteProtected(S3_CONFIG, settings.s3Config, loadedSettings.s3Config)) {
                preferences.putSecretIfChanged(
                    S3_CONFIG,
                    settings.s3Config != previous.s3Config,
                ) { JsonInstant.encodeToString(settings.s3Config) }
            }
            if (canWriteProtected(TTS_PROVIDERS, settings.ttsProviders, loadedSettings.ttsProviders)) {
                preferences.putSecretIfChanged(
                    TTS_PROVIDERS,
                    settings.ttsProviders != previous.ttsProviders,
                ) { JsonInstant.encodeToString(settings.ttsProviders) }
            }
            settings.selectedTTSProviderId?.let {
                preferences[SELECTED_TTS_PROVIDER] = it.toString()
            } ?: preferences.remove(SELECTED_TTS_PROVIDER)
            if (canWriteProtected(ASR_PROVIDERS, settings.asrProviders, loadedSettings.asrProviders)) {
                preferences.putSecretIfChanged(
                    ASR_PROVIDERS,
                    settings.asrProviders != previous.asrProviders,
                ) { JsonInstant.encodeToString(settings.asrProviders) }
            }
            settings.selectedASRProviderId?.let {
                preferences[SELECTED_ASR_PROVIDER] = it.toString()
            } ?: preferences.remove(SELECTED_ASR_PROVIDER)
            preferences[VOICE_CALL_KEEP_LISTENING_DURING_REPLY] = settings.voiceCallKeepListeningDuringReply
            preferences[VIDEO_CALL_SETTING] = JsonInstant.encodeToString(settings.videoCallSetting)
            preferences[MODE_INJECTIONS] = JsonInstant.encodeToString(settings.modeInjections)
            preferences[LOREBOOKS] = JsonInstant.encodeToString(settings.lorebooks)
            preferences[QUICK_MESSAGES] = JsonInstant.encodeToString(settings.quickMessages)
            preferences[WEB_SERVER_ENABLED] = settings.webServerEnabled
            preferences[WEB_SERVER_PORT] = settings.webServerPort
            preferences[WEB_SERVER_JWT_ENABLED] = settings.webServerJwtEnabled
            if (canWriteProtected(
                    WEB_SERVER_ACCESS_PASSWORD,
                    settings.webServerAccessPassword,
                    loadedSettings.webServerAccessPassword,
                )
            ) {
                preferences.putSecretIfChanged(
                    WEB_SERVER_ACCESS_PASSWORD,
                    settings.webServerAccessPassword != previous.webServerAccessPassword,
                ) { settings.webServerAccessPassword }
            }
            preferences[WEB_SERVER_LOCALHOST_ONLY] = settings.webServerLocalhostOnly
            preferences[BACKUP_REMINDER_CONFIG] = JsonInstant.encodeToString(settings.backupReminderConfig)
            preferences[AUTO_BACKUP_CONFIG] = JsonInstant.encodeToString(settings.autoBackupConfig)
            preferences[LAUNCH_COUNT] = settings.launchCount
            preferences[SPONSOR_ALERT_DISMISSED_AT] = settings.sponsorAlertDismissedAt
            if (canWriteProtected(SYSTEM_TOOLS_SETTING, settings.systemToolsSetting, loadedSettings.systemToolsSetting)) {
                preferences.putSecretIfChanged(
                    SYSTEM_TOOLS_SETTING,
                    settings.systemToolsSetting != previous.systemToolsSetting,
                ) { JsonInstant.encodeToString(settings.systemToolsSetting) }
            }
            preferences[PROACTIVE_MESSAGE_SETTING] = JsonInstant.encodeToString(settings.proactiveMessageSetting)
            if (canWriteProtected(WECHAT_BOT_SETTING, settings.wechatBotSetting, loadedSettings.wechatBotSetting)) {
                preferences.putSecretIfChanged(
                    WECHAT_BOT_SETTING,
                    settings.wechatBotSetting != previous.wechatBotSetting,
                ) { JsonInstant.encodeToString(settings.wechatBotSetting) }
            }
            if (canWriteProtected(QQ_BOT_SETTING, settings.qqBotSetting, loadedSettings.qqBotSetting)) {
                preferences.putSecretIfChanged(
                    QQ_BOT_SETTING,
                    settings.qqBotSetting != previous.qqBotSetting,
                ) { JsonInstant.encodeToString(settings.qqBotSetting) }
            }
            preferences[KEEP_ALIVE_ENABLED] = settings.keepAliveEnabled
            retireExternalMemoryPreferences(preferences)
            if (canWriteProtected(MINI_APPS, settings.miniApps, loadedSettings.miniApps)) {
                preferences.putSecretIfChanged(
                    MINI_APPS,
                    settings.miniApps != previous.miniApps,
                ) { JsonInstant.encodeToString(settings.miniApps) }
            }
            if (canWriteProtected(SEREIN_HOOK, settings.sereinHook, loadedSettings.sereinHook)) {
                preferences.putSecretIfChanged(SEREIN_HOOK, settings.sereinHook != previous.sereinHook) {
                    JsonInstant.encodeToString(settings.sereinHook)
                }
            }
            preferences[FORCE_CONFIRM_TOOL_CALLS] = settings.forceConfirmToolCalls
            preferences[WORKFLOW_HEADLESS_BLOCK_SENSITIVE] = settings.workflowHeadlessBlockSensitive
            preferences[AUTO_APPROVE_ALL_TOOLS] = settings.autoApproveAllTools
        }
    }

    suspend fun updateAssistant(assistantId: Uuid) {
        update { it.copy(assistantId = assistantId) }
    }

    suspend fun updateAssistantModel(assistantId: Uuid, modelId: Uuid) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(chatModelId = modelId)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantReasoningLevel(assistantId: Uuid, reasoningLevel: ReasoningLevel) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(reasoningLevel = reasoningLevel)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantMcpServers(assistantId: Uuid, mcpServers: Set<Uuid>) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(mcpServers = mcpServers)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantInjections(
        assistantId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
        quickMessageIds: Set<Uuid> = emptySet(),
    ) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(
                            modeInjectionIds = modeInjectionIds,
                            lorebookIds = lorebookIds,
                            quickMessageIds = quickMessageIds,
                        )
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    private fun retireExternalMemoryPreferences(preferences: MutablePreferences) {
        legacyMemoryRetirement.retire(
            preferences,
            decrypt = { stored, name ->
                if (SecretCrypto.isEncrypted(stored)) SecretCrypto.decrypt(stored, name) else stored
            },
            encrypt = { plain, name -> SecretCrypto.encrypt(plain, name) },
        )
    }

    private inline fun <reified T> Preferences.readPlaintextOrMigrate(
        key: Preferences.Key<String>,
        default: T,
        crossinline decode: (String) -> T,
    ): T {
        val raw = this[key] ?: run {
            protectedDecodeCache.forget(key.name)
            unreadableProtectedSettingKeys.remove(key.name)
            return default
        }
        return runCatching {
            protectedDecodeCache.read(key.name, raw) {
                val plaintext = if (SecretCrypto.isEncrypted(raw)) SecretCrypto.decrypt(raw, key.name) else raw
                checkNotNull(plaintext) { "Protected preference unavailable" }
                decode(plaintext)
            }
        }
            .onSuccess { unreadableProtectedSettingKeys.remove(key.name) }
            .getOrElse {
                unreadableProtectedSettingKeys.add(key.name)
                Log.w(TAG, "Failed to decode protected preference '${key.name}', preserving raw value", it)
                default
            }
    }

    private inline fun MutablePreferences.putSecretIfChanged(
        key: Preferences.Key<String>,
        changed: Boolean,
        plaintext: () -> String,
    ) {
        val stored = this[key]
        if (!changed && (stored == null || SecretCrypto.isEncrypted(stored))) return
        SecretCrypto.encrypt(plaintext(), key.name)?.let { encrypted ->
            this[key] = encrypted
        }
    }
}

@Serializable
data class Settings(
    val gongdu: me.rerere.rikkahub.data.gongdu.GongduSetting = me.rerere.rikkahub.data.gongdu.GongduSetting(),
    @Transient
    val init: Boolean = false,
    // Legacy backup fields; no longer gate application startup.
    val disclaimerAccepted: Boolean = false,
    val disclaimerAcceptedAt: Int = 0,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    val developerMode: Boolean = false,
    val customReactionPresets: List<String> = emptyList(),
    val displaySetting: DisplaySetting = DisplaySetting(),
    val enableWebSearch: Boolean = false,
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val titleModelId: Uuid = Uuid.random(),
    val imageGenerationModelId: Uuid = Uuid.random(),
    val imageGenerationAspectRatio: ImageAspectRatio = ImageAspectRatio.SQUARE,
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    val suggestionModelId: Uuid = Uuid.random(),
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val compressModelId: Uuid? = null,
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val selectedASRProviderId: Uuid? = null,
    val voiceCallKeepListeningDuringReply: Boolean = true,
    val videoCallSetting: me.rerere.rikkahub.data.video.VideoCallSetting = me.rerere.rikkahub.data.video.VideoCallSetting(),
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val lorebooks: List<Lorebook> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    val webServerLocalhostOnly: Boolean = false,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val autoBackupConfig: AutoBackupConfig = AutoBackupConfig(),
    val launchCount: Int = 0,
    val sponsorAlertDismissedAt: Int = 0,
    val systemToolsSetting: SystemToolsSetting = SystemToolsSetting(),
    val proactiveMessageSetting: ProactiveMessageSetting = ProactiveMessageSetting(),
    val wechatBotSetting: WechatBotSetting = WechatBotSetting(),
    val qqBotSetting: QqBotSetting = QqBotSetting(),
    val keepAliveEnabled: Boolean = false,
    val externalMemories: List<ExternalMemory> = emptyList(),
    val sereinHook: me.rerere.rikkahub.data.serein.SereinHookConfig = me.rerere.rikkahub.data.serein.SereinHookConfig(),
    val miniApps: List<MiniApp> = emptyList(),
    val forceConfirmToolCalls: Boolean = true,
    val workflowHeadlessBlockSensitive: Boolean = true,
    val autoApproveAllTools: Boolean = false,
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,
    @SerialName("serif")
    SERIF,
    @SerialName("monospace")
    MONOSPACE,
    @SerialName("custom")
    CUSTOM,
}

@Serializable
enum class DisplayMaterialMode {
    @SerialName("follow_theme")
    FOLLOW_THEME,
    @SerialName("flat")
    FLAT,
    @SerialName("translucent")
    TRANSLUCENT,
    @SerialName("glass")
    GLASS,
}

@Serializable
data class AnniversaryEntry(
    val id: String,
    val title: String,
    /** ISO-8601 local date, for example 2023-05-20. */
    val startDate: String,
    /** true 表示未来倒数日；false 表示从开始日累计天数。 */
    val countdown: Boolean = false,
)

@Serializable
data class DisplaySetting(
    val chatDisplayMode: ChatDisplayMode = ChatDisplayMode.STANDARD,
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val useAppIconStyleLoadingIndicator: Boolean = true,
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateBelowName: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val showUpdates: Boolean = true,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = false,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableBlurEffect: Boolean = false,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val materialMode: DisplayMaterialMode = DisplayMaterialMode.FOLLOW_THEME,
    val interfaceRealtimeRendering: Boolean = false,
    // 聊天气泡实时背景模糊（默认关闭，避免旧用户升级后自动增加每气泡 GPU 成本；固定 3 dp）
    val chatBubbleRealtimeBlur: Boolean = false,
    // 抽屉实时模糊强度（单位 dp，设置页 Slider 约束 3..20）
    val interfaceBlurRadius: Float = 8f,
    val interfaceSurfaceOpacity: Float = 82f,
    val popupSurfaceOpacity: Float = 90f,
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
    val chatBubbleTransparency: Float = 0f,
    val thinkingChainTransparency: Float = 0f,
    // 自定义字体
    val customFontPath: String = "",
    // 设置页自定义背景
    val settingsBackgroundPath: String = "",
    // 输入框自定义背景
    val inputBackgroundPath: String = "",
    // 头像框（QQ挂件风格）
    val userAvatarFramePath: String = "",
    val aiAvatarFramePath: String = "",
    val userAvatarFrameOffsetX: Float = 0f,
    val userAvatarFrameOffsetY: Float = 0f,
    val userAvatarFrameScale: Float = 1f,
    val aiAvatarFrameOffsetX: Float = 0f,
    val aiAvatarFrameOffsetY: Float = 0f,
    val aiAvatarFrameScale: Float = 1f,
    // 侧边栏背景
    val drawerBackgroundPath: String = "",
    // 侧边栏根面板不透明度（百分比）
    val drawerSurfaceOpacity: Float = 100f,
    // 侧边栏元素透明度
    val drawerItemAlpha: Float = 1f,
    // 显示在聊天侧边栏“插件”页中的快捷插件 ID
    val drawerPluginShortcutIds: Set<String> = emptySet(),
    // 已移除功能的历史字段，仅用于兼容旧设置；不再提供界面或读取执行路径。
    val watchControlColor: Long? = null,
    val watchControlAlpha: Float = 0.18f,
    // 纪念日：完全保存在本机；保留旧选中 ID 以兼容历史设置
    val anniversaries: List<AnniversaryEntry> = emptyList(),
    val anniversaryAiInjectionEnabled: Boolean = false,
    val anniversaryAiInjectionId: String? = null,
    // 颜色自定义
    val chatTextColor: Long? = null,
    val globalTextColor: Long? = null,
    val userBubbleColor: Long? = null,
    val assistantBubbleColor: Long? = null,
    val thinkingFontSizeRatio: Float = 1.0f,
    val thinkingBubbleColor: Long? = null,
    val chatBackgroundColor: Long? = null,
    val primaryColor: Long? = null,
    val inputFieldColor: Long? = null,
    // 气泡背景图 & 圆角
    val userBubbleImagePath: String = "",
    val assistantBubbleImagePath: String = "",
    val userBubbleFrame: BubbleFrameSetting = BubbleFrameSetting(),
    val assistantBubbleFrame: BubbleFrameSetting = BubbleFrameSetting(),
    val bubbleCornerRadius: Float = 16f,
    val bubbleImageOverlayEnabled: Boolean = false, // 关=纯图片, 开=图片+主题色遮罩
)

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "rikkahub_backups",
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.FILES
    ),
) {
    @Serializable
    enum class BackupItem {
        DATABASE,
        FILES,
    }
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)

fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }

fun Settings.findModelById(uuid: Uuid): Model? {
    return this.providers.findModelById(uuid)
}

fun List<ProviderSetting>.findModelById(uuid: Uuid): Model? {
    this.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == uuid) {
                return model
            }
        }
    }
    return null
}

fun Settings.getCurrentChatModel(): Model? {
    return findModelById(this.getCurrentAssistant().chatModelId ?: this.chatModelId)
}

/** The open conversation can belong to a different assistant from the global selection. */
fun Settings.getChatModelForAssistant(assistantId: Uuid): Model? {
    val assistant = getAssistantById(assistantId) ?: getCurrentAssistant()
    return findModelById(assistant.chatModelId ?: chatModelId)
}

fun Settings.getCurrentAssistant(): Assistant {
    return this.assistants.find { it.id == assistantId } ?: this.assistants.first()
}

fun Settings.getAssistantById(id: Uuid): Assistant? {
    return this.assistants.find { it.id == id }
}

fun Settings.getQuickMessagesOfAssistant(assistant: Assistant) =
    quickMessages.filter { it.id in assistant.quickMessageIds }

fun Settings.getSelectedTTSProvider(): TTSProviderSetting? {
    return selectedTTSProviderId?.let { id ->
        ttsProviders.find { it.id == id }
    } ?: ttsProviders.firstOrNull()
}

fun Settings.getSelectedASRProvider(): ASRProviderSetting? {
    return selectedASRProviderId?.let { id ->
        asrProviders.find { it.id == id }
    } ?: asrProviders.firstOrNull()
}

fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers) ?: return null
    val providerOverwrite = this.providerOverwrite
    if (checkOverwrite && providerOverwrite != null) {
        return providerOverwrite.copyProvider(models = emptyList())
    }
    return provider
}

private fun Model.findModelProviderFromList(providers: List<ProviderSetting>): ProviderSetting? {
    providers.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == this.id) {
                return setting
            }
        }
    }
    return null
}

internal val DEFAULT_ASSISTANT_ID = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
internal val DEFAULT_ASSISTANTS = listOf(
    Assistant(
        id = DEFAULT_ASSISTANT_ID,
        name = "",
        systemPrompt = ""
    ),
    Assistant(
        id = Uuid.parse("3d47790c-c415-4b90-9388-751128adb0a0"),
        name = "",
        systemPrompt = """
            You are a helpful assistant, called {{char}}, based on model {{model_name}}.

            ## Info
            - Time: {{cur_datetime}}
            - Locale: {{locale}}
            - Timezone: {{timezone}}
            - Device Info: {{device_info}}
            - System Version: {{system_version}}
            - User Nickname: {{user}}

            ## Hint
            - If the user does not specify a language, reply in the user's primary language.
            - Remember to use Markdown syntax for formatting, and use latex for mathematical expressions.
        """.trimIndent()
    ),
)

val DEFAULT_SYSTEM_TTS_ID = Uuid.parse("026a01a2-c3a0-4fd5-8075-80e03bdef200")
private val DEFAULT_TTS_PROVIDERS = listOf(
    TTSProviderSetting.SystemTTS(
        id = DEFAULT_SYSTEM_TTS_ID,
        name = "",
    ),
    TTSProviderSetting.OpenAI(
        id = Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c"),
        name = "AiHubMix",
        baseUrl = "https://aihubmix.com/v1",
        model = "gpt-4o-mini-tts",
        voice = "alloy",
    )
)

internal val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id }

val DEFAULT_MODE_INJECTIONS = listOf(
    PromptInjection.ModeInjection(
        id = Uuid.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74"),
        content = LEARNING_MODE_PROMPT,
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "Learning Mode"
    )
)
