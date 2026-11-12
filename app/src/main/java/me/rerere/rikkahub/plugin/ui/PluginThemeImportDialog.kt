/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.theme.packageio.ValidatedThemePackage

/**
 * Confirms a theme package that was picked from the plugin manager. Applying goes through the
 * same native settings store as the dedicated theme page; no plugin code is installed or run.
 */
@Composable
internal fun PluginThemeImportDialog(
    themePackage: ValidatedThemePackage,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入主题包") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("这是主题包，不是插件。不会安装任何可执行代码；选择一套配色后会关闭动态配色，并保留你的背景图片、背景色和文字颜色。")
                themePackage.themes.values.forEach { tokens ->
                    OutlinedButton(
                        onClick = { onApply(tokens.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(tokens.name, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
