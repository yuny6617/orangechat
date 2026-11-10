/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.loader
 
import android.content.Context
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.service.MemoryBankService
import me.rerere.rikkahub.plugin.data.PluginDataStore
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.resolvePluginConfig
import okhttp3.OkHttpClient
import kotlin.uuid.Uuid
 
/**
 * 插件加载器
 * 负责加载和管理插件生命周期
 */
class PluginLoader(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val memoryBankService: MemoryBankService? = null,
    private val settingsStore: SettingsStore? = null
) {
 
    companion object {
        private const val TAG = "PluginLoader"
        private const val CALL_TIMEOUT_MS = 65_000L
    }
 
    private val runtimes = PluginRuntimeRegistry<LoadedPlugin> { plugin ->
        runCatching { plugin.sandbox.destroy() }
            .onFailure { Log.e(TAG, "Failed to destroy plugin ${plugin.id}", it) }
    }
 
    /**
     * 加载插件
     */
    fun loadPlugin(pluginInfo: PluginInfo, onResult: (Result<LoadedPlugin>) -> Unit) {
        runtimes.load(pluginInfo.manifest.id, { createPlugin(pluginInfo) }, onResult)
    }

    private fun createPlugin(pluginInfo: PluginInfo): LoadedPlugin {
        var sandbox: PluginSandbox? = null
        try {
            check(pluginInfo.isEnabled) { "Plugin is disabled" }
 
            val entryFile = pluginInfo.getEntryFile()
            if (entryFile == null || !entryFile.isFile) {
                error("Entry file not found: ${pluginInfo.manifest.entry}")
            }
 
            // 为此插件创建独立的 PluginDataStore，并注入沙箱
            val dataStore = PluginDataStore(context, pluginInfo.manifest.id)

            val pluginSandbox = PluginSandbox(context, okHttpClient, memoryBankService, dataStore)
            sandbox = pluginSandbox
            pluginSandbox.allowedHosts = pluginInfo.manifest.allowedHosts
            pluginSandbox.initialize()
 
            val resolvedConfig = resolveModelConfig(pluginInfo)
            pluginSandbox.injectConfig(resolvedConfig)

            pluginSandbox.evaluateFile(entryFile)
 
            val loadedPlugin = LoadedPlugin(
                info = pluginInfo,
                sandbox = pluginSandbox
            )

            val exportedNames = pluginSandbox.getExportedFunctionNames()
            Log.i(TAG, "Plugin ${pluginInfo.manifest.id} exported functions: $exportedNames")
 
            pluginInfo.manifest.tools.forEach { tool ->
                if (!pluginSandbox.hasFunction(tool.name)) {
                    Log.w(TAG, "Tool '${tool.name}' declared in manifest but not found in exports (available: $exportedNames)")
                } else {
                    Log.i(TAG, "Tool '${tool.name}' registered successfully")
                }
            }
 
            return loadedPlugin
        } catch (e: Throwable) {
            runCatching { sandbox?.destroy() }
            Log.e(TAG, "Failed to load plugin ${pluginInfo.manifest.id}", e)
            throw e
        }
    }

    fun unloadPlugin(pluginId: String) = runtimes.remove(pluginId)

    fun isLoading(pluginId: String): Boolean = runtimes.isLoading(pluginId)

    fun getRuntimeIds(): Set<String> = runtimes.ids()

    fun getLoadedPlugin(pluginId: String): LoadedPlugin? = runtimes.get(pluginId)

    fun getAllLoadedPlugins(): List<LoadedPlugin> = runtimes.values()

    fun getEnabledPlugins(): List<LoadedPlugin> = runtimes.values().filter { it.info.isEnabled }
 
    /**
     * 调用插件工具
     */
    suspend fun callTool(pluginId: String, toolName: String, params: JsonElement): Result<JsonElement> {
        return runtimes.call(pluginId, CALL_TIMEOUT_MS) { plugin ->
            require(plugin.hasTool(toolName)) { "Tool not found: $toolName" }
            plugin.sandbox.callFunction(toolName, params)
        }
    }
 
    /**
     * 触发插件事件
     *
     * 同一插件的操作保持串行，不同插件互不等待。
     * 超时停止等待结果；底层同步 JS 返回后才会回收其执行线程。
     */
    suspend fun callEvent(event: String, params: JsonElement) {
        supervisorScope {
            // Include pending loads so events emitted just after startup are not lost.
            runtimes.ids().map { pluginId ->
                async {
                    runtimes.call(pluginId, CALL_TIMEOUT_MS) { plugin ->
                        plugin.info.manifest.hooks.filter { it.event == event }.forEach { hook ->
                            runCatching {
                                require(plugin.sandbox.hasFunction(hook.handler)) {
                                    "Hook handler '${hook.handler}' not found in plugin $pluginId"
                                }
                                plugin.sandbox.callFunction(hook.handler, params)
                            }.onFailure {
                                Log.e(TAG, "Failed to handle event='$event' in $pluginId.${hook.handler}", it)
                            }
                        }
                    }.onFailure {
                        Log.e(TAG, "Failed to handle event='$event' in plugin=$pluginId", it)
                    }
                }
            }.awaitAll()
        }
    }
 
    fun getPluginsWithDailyCron(): List<Pair<LoadedPlugin, String>> {
        return getEnabledPlugins().flatMap { plugin ->
            plugin.info.manifest.hooks
                .filter { it.event == "daily_cron" }
                .map { hook -> plugin to hook.handler }
        }
    }
 
    private fun resolveModelConfig(pluginInfo: PluginInfo): Map<String, JsonElement> = resolvePluginConfig(
        pluginInfo.manifest.config, pluginInfo.config
    ) { config ->
        val store = settingsStore ?: return@resolvePluginConfig
        val settings = store.settingsFlow.value
 
        pluginInfo.manifest.config.forEach { field ->
            if (field.type == "model") {
                val modelUuidStr = (config[field.name] as? JsonPrimitive)?.contentOrNull
                if (modelUuidStr.isNullOrBlank()) return@forEach
                try {
                    val modelUuid = Uuid.parse(modelUuidStr)
                    val model = settings.findModelById(modelUuid) ?: return@forEach
                    val provider = model.findProvider(settings.providers) ?: return@forEach
 
                    val baseUrl = when (provider) {
                        is ProviderSetting.OpenAI -> provider.baseUrl
                        is ProviderSetting.Google -> provider.baseUrl
                        is ProviderSetting.Codex -> "" // Subscription credentials are never exposed to plugins.
                        is ProviderSetting.Claude -> provider.baseUrl
                    }
                    val apiKey = when (provider) {
                        is ProviderSetting.OpenAI -> provider.apiKey
                        is ProviderSetting.Google -> provider.apiKey
                        is ProviderSetting.Codex -> ""
                        is ProviderSetting.Claude -> provider.apiKey
                    }
 
                    config[field.name] = JsonPrimitive(model.modelId)
                    config["${field.name}_base_url"] = JsonPrimitive(baseUrl)
                    if (pluginInfo.manifest.permissions.contains("provider_credentials")) {
                        config["${field.name}_api_key"] = JsonPrimitive(apiKey)
                    }
                    Log.d(TAG, "Resolved model config '${field.name}': modelId=${model.modelId}, baseUrl=$baseUrl")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to resolve model config '${field.name}': ${e.message}")
                }
            }
        }
    }
 
    fun unloadAll() = runtimes.clear()

    /** Release the QuickJS thread and all sandboxes when the host process is torn down. */
    fun close() = runtimes.close()
}
