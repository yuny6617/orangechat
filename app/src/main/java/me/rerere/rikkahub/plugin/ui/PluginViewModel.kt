/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.plugin.manager.PluginUninstallDataPolicy
import me.rerere.rikkahub.plugin.manager.PluginUninstallPolicyRelay
import me.rerere.rikkahub.plugin.manager.PluginUninstallResult
import me.rerere.rikkahub.plugin.model.PluginFolder
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.PluginManifest
import me.rerere.rikkahub.theme.packageio.ValidatedThemePackage
import me.rerere.rikkahub.ui.pages.setting.loadThemePackageFromUri
import me.rerere.rikkahub.ui.theme.CustomTheme
import java.io.File

/**
 * 插件ViewModel
 */
class PluginViewModel(
    private val pluginManager: PluginManager,
    private val settingsStore: SettingsStore,
    private val context: android.content.Context,
) : ViewModel() {

    // 插件列表
    val plugins: StateFlow<List<PluginInfo>> = pluginManager.plugins

    // 加载状态
    val isLoading: StateFlow<Boolean> = pluginManager.isLoading

    val folders: StateFlow<List<PluginFolder>> = pluginManager.folders

    // 导入状态
    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    // 预览导入状态（用于权限确认对话框）
    private val _pendingImport = MutableStateFlow<PendingImport?>(null)
    val pendingImport: StateFlow<PendingImport?> = _pendingImport.asStateFlow()

    // A ZIP that is actually a declarative theme package. The plugin page can apply it instead
    // of surfacing a misleading PluginManifest deserialization error.
    private val _pendingTheme = MutableStateFlow<ValidatedThemePackage?>(null)
    val pendingTheme: StateFlow<ValidatedThemePackage?> = _pendingTheme.asStateFlow()

    // 操作状态
    private val _operationState = MutableStateFlow<OperationState>(OperationState.Idle)
    val operationState: StateFlow<OperationState> = _operationState.asStateFlow()

    data class PendingImport(
        val manifest: PluginManifest,
        val tempDir: File
    )

    /**
     * 刷新插件列表
     */
    fun refreshPlugins() {
        viewModelScope.launch {
            pluginManager.refreshPlugins()
        }
    }

    /**
     * 预览插件（解析 manifest，不解压到插件目录）
     */
    fun previewPlugin(uri: android.net.Uri) {
        viewModelScope.launch {
            _importState.value = ImportState.Loading
            try {
                if (tryPreviewTheme(uri)) return@launch
                val result = pluginManager.previewPlugin(uri)
                if (result.isSuccess) {
                    val pair = result.getOrThrow()
                    _pendingImport.value = PendingImport(pair.first, pair.second)
                    _importState.value = ImportState.Idle
                } else {
                    _importState.value = ImportState.Error(
                        result.exceptionOrNull()?.message ?: "解析插件失败"
                    )
                }
            } catch (e: Exception) {
                _importState.value = ImportState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 确认导入插件（在 previewPlugin 后调用）
     */
    fun confirmImport() {
        viewModelScope.launch {
            val pending = _pendingImport.value ?: return@launch
            _importState.value = ImportState.Loading
            try {
                val result = pluginManager.confirmImport(pending.manifest, pending.tempDir)
                _importState.value = if (result.isSuccess) {
                    ImportState.Success(result.getOrThrow())
                } else {
                    ImportState.Error(result.exceptionOrNull()?.message ?: "导入失败")
                }
            } catch (e: Exception) {
                _importState.value = ImportState.Error(e.message ?: "Unknown error")
            } finally {
                _pendingImport.value = null
            }
        }
    }

    /**
     * 取消导入预览
     */
    fun cancelImportPreview() {
        val directory = _pendingImport.value?.tempDir
        _pendingImport.value = null
        viewModelScope.launch(Dispatchers.IO) { directory?.deleteRecursively() }
    }

    /**
     * 导入插件
     */
    fun importPlugin(uri: android.net.Uri) {
        viewModelScope.launch {
            _importState.value = ImportState.Loading
            try {
                if (tryPreviewTheme(uri)) return@launch
                val result = pluginManager.importPlugin(uri)
                _importState.value = if (result.isSuccess) {
                    ImportState.Success(result.getOrThrow())
                } else {
                    ImportState.Error(result.exceptionOrNull()?.message ?: "Unknown error")
                }
            } catch (e: Exception) {
                _importState.value = ImportState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 获取指定插件
     */
    fun getPlugin(pluginId: String): PluginInfo? {
        return plugins.value.find { it.manifest.id == pluginId }
    }

    /**
     * 删除插件
     *
     * 默认保留 localdata（重新安装可恢复）；只有用户在原生卸载对话框里显式选择
     * [PluginUninstallDataPolicy.DELETE_LOCAL_DATA] 时才删除独立数据库与媒体目录。
     */
    fun deletePlugin(
        pluginId: String,
        dataPolicy: PluginUninstallDataPolicy =
            PluginUninstallPolicyRelay.consume(pluginId)
                ?: PluginUninstallDataPolicy.PRESERVE_LOCAL_DATA,
    ) {
        // The default argument above already consumed a staged folder-page choice; this also drops
        // any stale choice when a caller passes an explicit policy.
        PluginUninstallPolicyRelay.consume(pluginId)
        viewModelScope.launch {
            _operationState.value = OperationState.Loading
            try {
                val result = pluginManager.deletePlugin(pluginId, dataPolicy)
                _operationState.value = result.fold(
                    onSuccess = { OperationState.Success(uninstallMessage(it)) },
                    onFailure = { OperationState.Error(it.message ?: "删除插件失败") }
                )
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 通过系统 SAF 导出该插件的 localdata 备份（卸载前恢复入口）。
     */
    fun exportLocalDataBackup(pluginId: String, destination: android.net.Uri) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading
            try {
                val result = pluginManager.exportLocalDataBackup(pluginId, destination)
                _operationState.value = result.fold(
                    onSuccess = { OperationState.Success("备份已导出，可继续卸载或保留数据") },
                    onFailure = { OperationState.Error("导出备份失败：${it.message ?: "未知错误"}") }
                )
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun uninstallMessage(result: PluginUninstallResult): String = when {
        result.localDataDeletionIncomplete -> "插件已卸载，但部分本地数据未能删除"
        result.dataPolicy.removesLocalData -> "插件已卸载，本地数据已删除"
        else -> "插件已卸载，本地数据已保留（重新安装可恢复）"
    }

    /**
     * 切换插件启用状态
     */
    fun togglePlugin(pluginId: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                pluginManager.togglePlugin(pluginId, enabled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(e.message ?: "切换插件失败")
            }
        }
    }

    /**
     * 更新插件配置
     */
    fun updatePluginConfig(pluginId: String, config: Map<String, JsonElement>) {
        viewModelScope.launch {
            try {
                pluginManager.updatePluginConfig(pluginId, config)
                _operationState.value = OperationState.Success("配置已保存")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(e.message ?: "保存配置失败")
            }
        }
    }

    /**
     * 获取插件目录
     */
    fun getPluginsDirectory() = pluginManager.getPluginsDirectory()

    /**
     * Recognises a declarative theme ZIP before the plugin scanner tries to decode it as a
     * PluginManifest. Returns true when the URI was a valid theme package.
     */
    private suspend fun tryPreviewTheme(uri: android.net.Uri): Boolean {
        val theme = runCatching { loadThemePackageFromUri(context, uri) }.getOrNull() ?: return false
        _pendingTheme.value = theme
        _importState.value = ImportState.Idle
        return true
    }

    fun cancelThemePreview() {
        _pendingTheme.value = null
    }

    fun applyPendingTheme(themeId: String) {
        val pack = _pendingTheme.value ?: return
        val selected = pack.themes[themeId] ?: return
        viewModelScope.launch {
            val installed = pack.themes.values.map { tokens ->
                CustomTheme(
                    id = "package:${pack.manifest.id}:${tokens.id}",
                    name = tokens.name,
                    packageTokens = tokens,
                )
            }
            val current = settingsStore.settingsFlow.value
            context.getSharedPreferences("theme_package_restore", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("themeId", current.themeId)
                .putBoolean("dynamicColor", current.dynamicColor)
                .apply()
            settingsStore.update { latest ->
                val ids = installed.map { it.id }.toSet()
                latest.copy(
                    customThemes = latest.customThemes.filterNot { it.id in ids } + installed,
                    themeId = "package:${pack.manifest.id}:${selected.id}",
                    dynamicColor = false,
                )
            }
            _pendingTheme.value = null
            _importState.value = ImportState.ThemeInstalled(selected.name)
        }
    }

    /**
     * 重置导入状态
     */
    fun resetImportState() {
        _importState.value = ImportState.Idle
    }

    /**
     * 重置操作状态
     */
    fun resetOperationState() {
        _operationState.value = OperationState.Idle
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading
            try {
                pluginManager.createFolder(name)
                _operationState.value = OperationState.Success("ok")
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(e.message ?: "err")
            }
        }
    }

    fun renameFolder(folderId: String, newName: String) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading
            try {
                pluginManager.renameFolder(folderId, newName)
                _operationState.value = OperationState.Success("ok")
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(e.message ?: "err")
            }
        }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading
            try {
                pluginManager.deleteFolder(folderId)
                _operationState.value = OperationState.Success("ok")
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(e.message ?: "err")
            }
        }
    }

    fun movePluginToFolder(pluginId: String, folderId: String?) {
        viewModelScope.launch {
            pluginManager.movePluginToFolder(pluginId, folderId)
        }
    }

    fun importPlugin(uri: android.net.Uri, folderId: String?) {
        viewModelScope.launch {
            _importState.value = ImportState.Loading
            try {
                if (tryPreviewTheme(uri)) return@launch
                val result = pluginManager.importPlugin(uri, folderId)
                _importState.value = if (result.isSuccess) {
                    ImportState.Success(result.getOrThrow())
                } else {
                    ImportState.Error(result.exceptionOrNull()?.message ?: "Unknown error")
                }
            } catch (e: Exception) {
                _importState.value = ImportState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun getPluginsByFolder(folderId: String?): List<PluginInfo> {
        return pluginManager.getPluginsByFolder(folderId)
    }

    /**
     * 导入状态
     */
    sealed class ImportState {
        data object Idle : ImportState()
        data object Loading : ImportState()
        data class Success(val plugin: PluginInfo) : ImportState()
        data class ThemeInstalled(val themeName: String) : ImportState()
        data class Error(val message: String) : ImportState()
    }

    /**
     * 操作状态
     */
    sealed class OperationState {
        data object Idle : OperationState()
        data object Loading : OperationState()
        data class Success(val message: String) : OperationState()
        data class Error(val message: String) : OperationState()
    }
}
