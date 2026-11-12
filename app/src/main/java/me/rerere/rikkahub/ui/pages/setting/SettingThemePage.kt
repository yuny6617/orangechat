/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.HorizontalDivider
import me.rerere.rikkahub.ui.theme.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ColorPickerDialog
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

private val themeJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
}

@Composable
fun SettingThemePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current

    val themeRestorePreferences = remember(context) {
        context.getSharedPreferences("theme_package_restore", android.content.Context.MODE_PRIVATE)
    }
    var editingTheme by remember { mutableStateOf<CustomTheme?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showPrimaryPicker by remember { mutableStateOf(false) }
    var showSecondaryPicker by remember { mutableStateOf(false) }
    var showTertiaryPicker by remember { mutableStateOf(false) }
    val packageThemes = settings.customThemes.filter { it.packageTokens != null }
    val editableCustomThemes = settings.customThemes.filter { it.packageTokens == null }
    fun restorePreviousTheme() {
        val previousId = themeRestorePreferences.getString("themeId", "sakura") ?: "sakura"
        val validId = previousId.takeIf { id ->
            PresetThemes.any { it.id == id } || settings.customThemes.any { it.id == id }
        } ?: "sakura"
        vm.updateSettings(settings.copy(
            themeId = validId,
            dynamicColor = themeRestorePreferences.getBoolean("dynamicColor", false),
        ))
    }
    fun selectPackageTheme(theme: CustomTheme) {
        if (!settings.themeId.startsWith("package:")) {
            themeRestorePreferences.edit()
                .putString("themeId", settings.themeId)
                .putBoolean("dynamicColor", settings.dynamicColor)
                .apply()
        }
        vm.updateSettings(settings.copy(themeId = theme.id, dynamicColor = false))
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("自定义主题管理") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = settingsScaffoldContainerColor(CustomColors.topBarColors.containerColor)
    ) { contentPadding ->
        // Keep the importer composed while the whole page scrolls, preserving an in-flight
        // import and its preview even when the user scrolls down to the theme list.
        Column(
            Modifier.fillMaxSize().padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            ThemePackageImport(
                onApply = { imported, selected ->
                    if (!settings.themeId.startsWith("package:") || !themeRestorePreferences.contains("themeId")) {
                        themeRestorePreferences.edit().putString("themeId", settings.themeId)
                            .putBoolean("dynamicColor", settings.dynamicColor).apply()
                    }
                    val ids = imported.map { it.id }.toSet()
                    vm.updateSettings(settings.copy(
                        customThemes = settings.customThemes.filterNot { it.id in ids } + imported,
                        themeId = selected,
                        dynamicColor = false,
                    ))
                },
                onRestore = {
                    val previousId = themeRestorePreferences.getString("themeId", "sakura") ?: "sakura"
                    val validId = previousId.takeIf { id ->
                        PresetThemes.any { it.id == id } || settings.customThemes.any { it.id == id }
                    } ?: "sakura"
                    vm.updateSettings(settings.copy(themeId = validId,
                        dynamicColor = themeRestorePreferences.getBoolean("dynamicColor", false)))
                },
            )
            if (packageThemes.isNotEmpty()) {
                Column {
                    Text(
                        text = "当前正在使用主题包",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    Text(
                        text = packageThemes.firstOrNull { it.id == settings.themeId }?.name
                            ?: "未启用主题包",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { restorePreviousTheme() }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = !settings.themeId.startsWith("package:"),
                                    onClick = { restorePreviousTheme() },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text("不启用主题包", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "恢复导入前选择的主题与动态配色",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            packageThemes.forEach { theme ->
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                val packageId = theme.id.removePrefix("package:").substringBeforeLast(":")
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable { selectPackageTheme(theme) }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = settings.themeId == theme.id,
                                        onClick = { selectPackageTheme(theme) },
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(theme.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "主题包：$packageId",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (settings.themeId == theme.id) {
                                        Text(
                                            "使用中",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            if (settings.themeId == theme.id) restorePreviousTheme()
                                            vm.deleteCustomTheme(theme.id)
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                    ) {
                                        Text(
                                            "删除",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { editingTheme = CustomTheme(name = "新主题") },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp),
                    ) { Text("新建", style = MaterialTheme.typography.labelMedium) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { showImportDialog = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp),
                    ) { Text("导入", style = MaterialTheme.typography.labelMedium) }
                }
            }

            Column {
                Text(
                    text = "预设主题",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PresetThemes.forEach { preset ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { vm.updateSettings(settings.copy(themeId = preset.id)) }) {
                            Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(preset.standardLight.primary).then(if (settings.themeId == preset.id) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)))
                            Spacer(Modifier.height(4.dp))
                            Text(text = preset.id, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }

            if (editableCustomThemes.isNotEmpty()) {
                Column {
                    Text(
                        text = "自定义主题",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
                    )
                }
                Column {
                    Surface(shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column {
                            editableCustomThemes.forEachIndexed { index, custom ->
                                if (index > 0) { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
                                val lightScheme = remember(custom) { custom.generateColorScheme(dark = false) }
                                Row(modifier = Modifier.fillMaxWidth().clickable { vm.updateSettings(settings.copy(themeId = custom.id)) }.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box {
                                        Box(Modifier.offset(x = 0.dp).size(20.dp).clip(CircleShape).background(lightScheme.primary).border(1.dp, MaterialTheme.colorScheme.surface, CircleShape))
                                        Box(Modifier.offset(x = 13.dp).size(20.dp).clip(CircleShape).background(lightScheme.secondary).border(1.dp, MaterialTheme.colorScheme.surface, CircleShape))
                                        Box(Modifier.offset(x = 26.dp).size(20.dp).clip(CircleShape).background(lightScheme.tertiary).border(1.dp, MaterialTheme.colorScheme.surface, CircleShape))
                                    }
                                    Spacer(Modifier.width(52.dp))
                                    Text(custom.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    if (settings.themeId == custom.id) {
                                        Surface(shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)) {
                                            Text("当前", style = MaterialTheme.typography.labelSmall, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    TextButton(onClick = { editingTheme = custom }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("编辑", style = MaterialTheme.typography.labelMedium) }
                                    TextButton(onClick = {
                                        val json = themeJson.encodeToString(custom)
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("theme", json))
                                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                    }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("导出", style = MaterialTheme.typography.labelMedium) }
                                    TextButton(onClick = { vm.deleteCustomTheme(custom.id) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("删除", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editingTheme?.let { theme ->
        AlertDialog(
            onDismissRequest = { editingTheme = null },
            title = { Text(if (settings.customThemes.any { it.id == theme.id }) "编辑主题" else "新建主题") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = theme.name,
                        onValueChange = { editingTheme = theme.copy(name = it) },
                        label = { Text("主题名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier=Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically,) {
                        Text("主色", Modifier.weight(1f))
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(theme.primaryColorArgb.toComposeColor()).clickable { showPrimaryPicker = true })
                    }
                    Row(modifier=Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically,) {
                        Text("二级色", Modifier.weight(1f))
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(theme.secondaryColorArgb?.toComposeColor() ?: Color.Gray.copy(alpha = 0.3f)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)).clickable { showSecondaryPicker = true })
                        if (theme.secondaryColorArgb != null) {
                            TextButton(onClick = { editingTheme = theme.copy(secondaryColorArgb = null) }) { Text("重置") }
                        }
                    }
                    Row(modifier=Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically,) {
                        Text("三级色", Modifier.weight(1f))
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(theme.tertiaryColorArgb?.toComposeColor() ?: Color.Gray.copy(alpha = 0.3f)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)).clickable { showTertiaryPicker = true })
                        if (theme.tertiaryColorArgb != null) {
                            TextButton(onClick = { editingTheme = theme.copy(tertiaryColorArgb = null) }) { Text("重置") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = editingTheme!!
                    if (settings.customThemes.any { it.id == t.id }) vm.updateCustomTheme(t) else vm.addCustomTheme(t)
                    editingTheme = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingTheme = null }) { Text("取消") } }
        )
    }

    if (showPrimaryPicker) {
        editingTheme?.let { theme ->
            ColorPickerDialog(
                initialColor = theme.primaryColorArgb,
                defaultColor = MaterialTheme.colorScheme.primary,
                onConfirm = { color -> editingTheme = theme.copy(primaryColorArgb = color ?: 0xFF6750A4L) },
                onDismiss = { showPrimaryPicker = false }
            )
        }
    }
    if (showSecondaryPicker) {
        editingTheme?.let { theme ->
            ColorPickerDialog(
                initialColor = theme.secondaryColorArgb,
                defaultColor = MaterialTheme.colorScheme.secondary,
                onConfirm = { color -> editingTheme = theme.copy(secondaryColorArgb = color) },
                onDismiss = { showSecondaryPicker = false }
            )
        }
    }
    if (showTertiaryPicker) {
        editingTheme?.let { theme ->
            ColorPickerDialog(
                initialColor = theme.tertiaryColorArgb,
                defaultColor = MaterialTheme.colorScheme.tertiary,
                onConfirm = { color -> editingTheme = theme.copy(tertiaryColorArgb = color) },
                onDismiss = { showTertiaryPicker = false }
            )
        }
    }

    if (showImportDialog) {
        var jsonText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("导入主题") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("粘贴主题JSON（从导出的剪贴板内容粘贴）：")
                    OutlinedTextField(value = jsonText, onValueChange = { jsonText = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val imported = themeJson.decodeFromString<CustomTheme>(jsonText).copy(id = Uuid.random().toString())
                        vm.addCustomTheme(imported)
                        Toast.makeText(context, "主题导入成功", Toast.LENGTH_SHORT).show()
                        showImportDialog = false
                    } catch (e: Exception) {
                        Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showImportDialog = false }) { Text("取消") } }
        )
    }
}