/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.ui.context.LocalSettingsBackgroundActive
import me.rerere.rikkahub.ui.theme.LocalPackageSurfaceTokens

/**
 * Settings pages use this instead of a raw `containerColor` so the user's
 * settings-background image always wins (transparent scaffold).
 *
 * While a package theme is active, the page body uses the package background
 * rather than the caller's legacy top-bar container colour. Package
 * `cardBackground` is the same tone as `surfaceContainer` in the three shipped
 * packages, so keeping the old fallback would make every CardGroup card
 * invisible against the page. Preset/dynamic themes keep the previous fallback
 * unchanged.
 */
@Composable
fun settingsScaffoldContainerColor(
    fallback: Color = MaterialTheme.colorScheme.background,
): Color {
    if (LocalSettingsBackgroundActive.current) return Color.Transparent
    return if (LocalPackageSurfaceTokens.current != null) {
        MaterialTheme.colorScheme.background
    } else {
        fallback
    }
}
