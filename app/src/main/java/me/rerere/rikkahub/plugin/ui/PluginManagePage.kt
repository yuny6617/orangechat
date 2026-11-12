/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import me.rerere.rikkahub.ui.theme.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.hugeicons.stroke.Reload
import me.rerere.rikkahub.R
import me.rerere.rikkahub.plugin.loader.PluginNetworkPolicy
import me.rerere.rikkahub.plugin.manager.PluginUninstallDataPolicy
import me.rerere.rikkahub.plugin.manager.PluginUninstallPolicyRelay
import me.rerere.rikkahub.plugin.manager.declaresLocalData
import me.rerere.rikkahub.plugin.model.PluginFolder
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.ui.components.ui.RiskConfirmDialog
import me.rerere.rikkahub.ui.pages.setting.settingsScaffoldContainerColor
import org.koin.androidx.compose.koinViewModel

/**
 * 插件管理页面（文件夹列表页）
 * 显示文件夹列表和未分组插件，点进文件夹才看到插件
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginManagePage(
    onNavigateToFolder: (String) -> Unit,
    onNavigateToDetail: (String) -> Unit,
    viewModel: PluginViewModel = koinViewModel()
) {
    val plugins by viewModel.plugins.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val operationState by viewModel.operationState.collectAsState()
    val pendingImport by viewModel.pendingImport.collectAsState()
    val pendingTheme by viewModel.pendingTheme.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showRenameFolderDialog by remember { mutableStateOf<PluginFolder?>(null) }
    var showDeleteFolderConfirm by remember { mutableStateOf<PluginFolder?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.previewPlugin(it) }
    }

    // SAF export offered directly from the uninstall dialog, before any localdata is removed.
    var backupExportPluginId by remember { mutableStateOf<String?>(null) }
    val backupExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val pluginId = backupExportPluginId
        backupExportPluginId = null
        if (uri != null && pluginId != null) {
            viewModel.exportLocalDataBackup(pluginId, uri)
        }
    }

    LaunchedEffect(importState) {
        when (importState) {
            is PluginViewModel.ImportState.Success -> {
                snackbarHostState.showSnackbar(message = "插件导入成功")
                viewModel.resetImportState()
            }
            is PluginViewModel.ImportState.ThemeInstalled -> {
                snackbarHostState.showSnackbar(
                    message = "主题包已应用：${(importState as PluginViewModel.ImportState.ThemeInstalled).themeName}",
                )
                viewModel.resetImportState()
            }
            is PluginViewModel.ImportState.Error -> {
                snackbarHostState.showSnackbar(
                    message = "导入失败: ${(importState as PluginViewModel.ImportState.Error).message}"
                )
                viewModel.resetImportState()
            }
            else -> {}
        }
    }

    LaunchedEffect(operationState) {
        when (operationState) {
            is PluginViewModel.OperationState.Success -> {
                snackbarHostState.showSnackbar(
                    message = (operationState as PluginViewModel.OperationState.Success).message
                )
                viewModel.resetOperationState()
            }
            is PluginViewModel.OperationState.Error -> {
                snackbarHostState.showSnackbar(
                    message = (operationState as PluginViewModel.OperationState.Error).message
                )
                viewModel.resetOperationState()
            }
            else -> {}
        }
    }

    LaunchedEffect(Unit) { viewModel.refreshPlugins() }

    pendingImport?.let { pending ->
        val manifest = pending.manifest
        AlertDialog(
            onDismissRequest = { viewModel.cancelImportPreview() },
            title = { Text("确认安装插件") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("插件名称: ${manifest.name}")
                    Text("版本: ${manifest.version}")
                    Text("作者: ${manifest.author}")
                    if (manifest.description.isNotBlank()) {
                        Text("描述: ${manifest.description}")
                    }
                    if (manifest.tools.isNotEmpty()) {
                        Text("")
                        Text("声明的工具:", style = MaterialTheme.typography.labelLarge)
                        manifest.tools.forEach { tool ->
                            Text("• ${tool.name}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (manifest.permissions.isNotEmpty()) {
                        Text("")
                        Text("申请的权限:", style = MaterialTheme.typography.labelLarge)
                        manifest.permissions.forEach { perm ->
                            Text("• $perm", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (!PluginNetworkPolicy(manifest.allowedHosts).allowsAllHosts) {
                        Text("")
                        Text("允许访问的网络域名:", style = MaterialTheme.typography.labelLarge)
                        manifest.allowedHosts.forEach { host ->
                            Text("• $host", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text("")
                        Text("• 允许联网（不限域名）", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmImport() }) {
                    Text("确认安装")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelImportPreview() }) {
                    Text("取消")
                }
            }
        )
    }

    pendingTheme?.let { pack ->
        PluginThemeImportDialog(
            themePackage = pack,
            onApply = { themeId -> viewModel.applyPendingTheme(themeId) },
            onDismiss = { viewModel.cancelThemePreview() },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("插件管理") },
                actions = {
                    TextButton(onClick = { filePickerLauncher.launch("application/zip") }) {
                        Text("导入")
                    }
                    IconButton(onClick = { viewModel.refreshPlugins() }) {
                        Icon(imageVector = HugeIcons.Reload, contentDescription = "刷新")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateFolderDialog = true },
                icon = { Icon(HugeIcons.PlusSign, null) },
                text = { Text("新建文件夹") }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = settingsScaffoldContainerColor(),
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading && plugins.isEmpty() && folders.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                PluginFolderContent(
                    folders = folders,
                    plugins = plugins,
                    onFolderClick = { folder -> onNavigateToFolder(folder.id) },
                    onFolderLongClick = { folder -> showRenameFolderDialog = folder },
                    onPluginClick = { plugin -> onNavigateToDetail(plugin.manifest.id) },
                    onTogglePlugin = { plugin, enabled ->
                        viewModel.togglePlugin(plugin.manifest.id, enabled)
                    },
                    onDeletePlugin = { plugin, policy ->
                        viewModel.deletePlugin(plugin.manifest.id, policy)
                    },
                    onExportBackup = { plugin ->
                        backupExportPluginId = plugin.manifest.id
                        runCatching {
                            backupExportLauncher.launch("${plugin.manifest.id}-localdata.zip")
                        }.onFailure { backupExportPluginId = null }
                    },
                    onImportPlugin = { filePickerLauncher.launch("application/zip") }
                )
            }

            AnimatedVisibility(
                visible = importState is PluginViewModel.ImportState.Loading,
                modifier = Modifier.align(Alignment.Center)
            ) {
                CircularProgressIndicator()
            }
        }
    }

    if (showCreateFolderDialog) {
        CreateFolderDialog(
            onDismiss = { showCreateFolderDialog = false },
            onConfirm = { name ->
                viewModel.createFolder(name)
                showCreateFolderDialog = false
            }
        )
    }

    showRenameFolderDialog?.let { folder ->
        RenameFolderDialog(
            folder = folder,
            onDismiss = { showRenameFolderDialog = null },
            onConfirm = { newName ->
                viewModel.renameFolder(folder.id, newName)
                showRenameFolderDialog = null
            },
            onDelete = {
                showRenameFolderDialog = null
                showDeleteFolderConfirm = folder
            }
        )
    }

    showDeleteFolderConfirm?.let { folder ->
        val count = plugins.count { it.folderId == folder.id }
        AlertDialog(
            onDismissRequest = { showDeleteFolderConfirm = null },
            title = { Text("删除文件夹") },
            text = {
                Text(
                    if (count > 0) {
                        "确定要删除文件夹「${folder.name}」吗？该文件夹下有 $count 个插件，将自动移到未分组。"
                    } else {
                        "确定要删除空文件夹「${folder.name}」吗？"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFolder(folder.id)
                    showDeleteFolderConfirm = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteFolderConfirm = null }) { Text("取消") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PluginFolderContent(
    folders: List<PluginFolder>,
    plugins: List<PluginInfo>,
    onFolderClick: (PluginFolder) -> Unit,
    onFolderLongClick: (PluginFolder) -> Unit,
    onPluginClick: (PluginInfo) -> Unit,
    onTogglePlugin: (PluginInfo, Boolean) -> Unit,
    onDeletePlugin: (PluginInfo, PluginUninstallDataPolicy) -> Unit,
    onExportBackup: (PluginInfo) -> Unit,
    onImportPlugin: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.plugin_import_security_warning_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = stringResource(R.string.plugin_import_security_warning_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = stringResource(R.string.plugin_import_security_warning_action),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (folders.isNotEmpty()) {
            item {
                Text(
                    text = "文件夹",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            items(items = folders, key = { it.id }) { folder ->
                val count = plugins.count { it.folderId == folder.id }
                FolderCard(
                    folder = folder,
                    pluginCount = count,
                    onClick = { onFolderClick(folder) },
                    onLongClick = { onFolderLongClick(folder) }
                )
            }
        }

        val ungroupedPlugins = plugins.filter { it.folderId == null }
        if (ungroupedPlugins.isNotEmpty() || folders.isEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "未分组插件",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    if (folders.isEmpty() && ungroupedPlugins.isEmpty()) {
                        Text(
                            text = "点击右下角创建文件夹",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (ungroupedPlugins.isEmpty() && folders.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("暂无未分组插件")
                    }
                }
            }
        }

        items(items = ungroupedPlugins, key = { it.manifest.id }) { plugin ->
            PluginCard(
                plugin = plugin,
                onClick = { onPluginClick(plugin) },
                onToggle = { enabled -> onTogglePlugin(plugin, enabled) },
                onDelete = { onDeletePlugin(plugin, PluginUninstallDataPolicy.PRESERVE_LOCAL_DATA) },
                uninstallActions = PluginUninstallActions(
                    onConfirm = { policy -> onDeletePlugin(plugin, policy) },
                    onExportBackup = { onExportBackup(plugin) }
                )
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(
    folder: PluginFolder,
    pluginCount: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = HugeIcons.Folder01,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .padding(end = 16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$pluginCount 个插件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

internal data class PluginUninstallActions(
    val onConfirm: (PluginUninstallDataPolicy) -> Unit,
    val onExportBackup: () -> Unit
)

@Composable
internal fun PluginCard(
    plugin: PluginInfo,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    uninstallActions: PluginUninstallActions? = null
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onClick)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = plugin.manifest.icon,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(end = 16.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plugin.manifest.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "v${plugin.manifest.version} · ${plugin.manifest.author}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (plugin.loadError != null) {
                        Text(
                            text = "加载失败: ${plugin.loadError}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Switch(
                checked = plugin.isEnabled,
                onCheckedChange = onToggle,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(
                    imageVector = HugeIcons.Delete02,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showDeleteConfirm) {
        if (plugin.declaresLocalData()) {
            PluginLocalDataUninstallDialog(
                plugin = plugin,
                onDismiss = { showDeleteConfirm = false },
                onConfirm = { policy ->
                    if (uninstallActions != null) {
                        uninstallActions.onConfirm(policy)
                    } else {
                        // Folder page still calls deletePlugin(id); stage the choice for it.
                        PluginUninstallPolicyRelay.stage(plugin.manifest.id, policy)
                        onDelete()
                    }
                    showDeleteConfirm = false
                },
                onExportBackup = uninstallActions?.onExportBackup
            )
        } else {
            // Plugins without localdata keep the original uninstall dialog/semantics.
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("确认删除") },
                text = { Text("确定要删除插件 \"${plugin.manifest.name}\" 吗？此操作不可撤销。") },
                confirmButton = {
                    TextButton(onClick = { onDelete(); showDeleteConfirm = false }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
                }
            )
        }
    }
}

/**
 * Native uninstall dialog for plugins that own a localdata database/media root.
 *
 * Keeping the data is the preselected choice so a reinstall resumes the moments feed. Deleting it
 * is an explicit opt-in, and the user can export a native SAF backup before deciding either way.
 */
@Composable
private fun PluginLocalDataUninstallDialog(
    plugin: PluginInfo,
    onDismiss: () -> Unit,
    onConfirm: (PluginUninstallDataPolicy) -> Unit,
    onExportBackup: (() -> Unit)?
) {
    var policy by remember(plugin.manifest.id) {
        mutableStateOf(PluginUninstallDataPolicy.PRESERVE_LOCAL_DATA)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("卸载「${plugin.manifest.name}」") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("插件文件会被删除，此操作不可撤销。")
                Text("本地数据", style = MaterialTheme.typography.titleSmall)
                LocalDataPolicyRow(
                    selected = policy == PluginUninstallDataPolicy.PRESERVE_LOCAL_DATA,
                    title = "保留本地数据（推荐）",
                    description = "动态、评论、草稿与图片仍保存在本机；重新安装同一插件后可以继续使用。",
                    onSelect = { policy = PluginUninstallDataPolicy.PRESERVE_LOCAL_DATA }
                )
                LocalDataPolicyRow(
                    selected = policy == PluginUninstallDataPolicy.DELETE_LOCAL_DATA,
                    title = "同时删除本地数据",
                    description = "永久删除该插件的动态、评论、草稿与图片，无法恢复。",
                    onSelect = { policy = PluginUninstallDataPolicy.DELETE_LOCAL_DATA }
                )
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("卸载前先导出备份", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (onExportBackup != null) {
                                "建议先在插件提供的备份入口导出数据；也可以直接在这里选择导出位置。"
                            } else {
                                "建议先在插件提供的备份入口导出数据。"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (onExportBackup != null) {
                            TextButton(onClick = onExportBackup) {
                                Text("导出备份文件…")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val deleting = policy == PluginUninstallDataPolicy.DELETE_LOCAL_DATA
            TextButton(onClick = { onConfirm(policy) }) {
                Text(
                    text = if (deleting) "卸载并删除数据" else "卸载并保留数据",
                    color = if (deleting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun LocalDataPolicyRow(
    selected: Boolean,
    title: String,
    description: String,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CreateFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var folderName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建文件夹") },
        text = {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text("文件夹名称") },
                placeholder = { Text("输入文件夹名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(folderName) },
                enabled = folderName.isNotBlank()
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun RenameFolderDialog(
    folder: PluginFolder,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onDelete: () -> Unit
) {
    var folderName by remember(folder.id) { mutableStateOf(folder.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑文件夹") },
        text = {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text("文件夹名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(folderName) },
                enabled = folderName.isNotBlank() && folderName != folder.name
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}
