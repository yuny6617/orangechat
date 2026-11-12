package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.theme.packageio.ThemePackageReader
import me.rerere.rikkahub.theme.packageio.ValidatedThemePackage
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.ui.hooks.rememberUserSettingsState
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.toColorScheme
import me.rerere.rikkahub.ui.theme.toShapes
import java.io.File

private const val MAX_THEME_ZIP_BYTES = 512_000

/**
 * Copies a SAF document into a bounded temporary file and validates it as a declarative theme
 * package. Shared by the theme page and the plugin-import entry so a theme ZIP picked from the
 * plugin manager is recognised instead of being parsed as a plugin manifest.
 */
internal suspend fun loadThemePackageFromUri(
    context: android.content.Context,
    uri: android.net.Uri,
): ValidatedThemePackage = withContext(Dispatchers.IO) {
    val temporary = File.createTempFile("theme-import-", ".zip", context.cacheDir)
    try {
        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            temporary.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_THEME_ZIP_BYTES) { "主题包超过 512 KB 限制" }
                    output.write(buffer, 0, count)
                }
            }
        }
        ThemePackageReader().read(temporary)
    } finally {
        temporary.delete()
    }
}

/**
 * Mirrors the explicit display overrides applied in ui/theme/Theme.kt so the
 * package preview matches what the user will actually get. Kept private here
 * until Theme.kt and this preview share one public helper.
 */
private fun ColorScheme.withExplicitUserPreferences(display: DisplaySetting): ColorScheme {
    var scheme = this
    display.primaryColor?.let { argb ->
        val primary = argb.toComposeColor()
        val luminance = 0.299f * primary.red + 0.587f * primary.green + 0.114f * primary.blue
        scheme = scheme.copy(
            primary = primary,
            onPrimary = if (luminance > 0.5f) Color.Black else Color.White,
        )
    }
    display.globalTextColor?.let { argb ->
        val textColor = argb.toComposeColor()
        scheme = scheme.copy(
            onBackground = textColor,
            onSurface = textColor,
            onSurfaceVariant = textColor,
        )
    }
    return scheme
}

@Composable
internal fun ThemePackageImport(onApply: (List<CustomTheme>, String) -> Unit, onRestore: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by rememberUserSettingsState()
    val display = settings.displaySetting
    val currentDarkMode = LocalDarkMode.current
    var preview by remember { mutableStateOf<ValidatedThemePackage?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var dark by remember { mutableStateOf(currentDarkMode) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) scope.launch {
            busy = true
            try {
                val pack = loadThemePackageFromUri(context, uri)
                failure = null
                dark = currentDarkMode
                preview = pack
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                preview = null
                failure = error.message ?: "主题包无法读取"
            } finally {
                busy = false
            }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("声明式主题包", style = MaterialTheme.typography.titleSmall)
        Text("仅配色与材质参数，保留你的背景图片、背景色和自定义文字颜色。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = { launcher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }) {
                Text(if (busy) "正在校验…" else "导入主题 ZIP")
            }
            TextButton(onClick = onRestore) { Text("恢复导入前主题") }
        }
        Text(
            "恢复导入前选择的主题与动态配色；若旧主题已删除则使用樱花预设。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    failure?.let { message ->
        AlertDialog(onDismissRequest = { failure = null }, title = { Text("未应用主题") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text("知道了") } })
    }
    preview?.let { pack ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text("预览：${pack.manifest.name}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row { Text("深色预览", Modifier.weight(1f)); Switch(checked = dark, onCheckedChange = { dark = it }) }
                    Text("预览不会修改设置；应用会关闭动态配色以使用此包，背景与文字覆盖保持不变。")
                    if (display.primaryColor != null || display.globalTextColor != null) {
                        Text("预览已叠加你的显式主色与文字颜色设置。")
                    }
                    pack.themes.values.forEach { tokens ->
                        val scheme = remember(tokens, dark, display.primaryColor, display.globalTextColor) {
                            tokens.toColorScheme(dark).withExplicitUserPreferences(display)
                        }
                        MaterialTheme(colorScheme = scheme, shapes = tokens.toShapes()) {
                            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text(tokens.name, color = MaterialTheme.colorScheme.onSurface)
                                    Text("设置 · 侧边栏 · 卡片", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Button(onClick = {
                                        val themes = pack.themes.values.map { value ->
                                            CustomTheme(id = "package:${pack.manifest.id}:${value.id}", name = value.name, packageTokens = value)
                                        }
                                        onApply(themes, "package:${pack.manifest.id}:${tokens.id}")
                                        preview = null
                                    }) { Text("应用此主题") }
                                }
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { preview = null }) { Text("取消预览") } })
    }
}
