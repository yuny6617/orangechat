/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.DisplayMaterialMode
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
import me.rerere.rikkahub.ui.hooks.rememberColorMode
import me.rerere.rikkahub.ui.hooks.rememberUserSettingsState

private val ExtendLightColors = lightExtendColors()
private val ExtendDarkColors = darkExtendColors()
val LocalExtendColors = compositionLocalOf { ExtendLightColors }

val LocalDarkMode = compositionLocalOf { false }
val LocalMaterialMode = compositionLocalOf { DisplayMaterialMode.FLAT }
val LocalPopupSurfaceOpacity = compositionLocalOf { 90f }

@Composable
@ReadOnlyComposable
fun popupContainerColor(baseContainerColor: Color): Color {
    val popupAlpha =
        (LocalPopupSurfaceOpacity.current / 100f).coerceIn(0.6f, 1f)
    return baseContainerColor.copy(alpha = popupAlpha)
}

private val AMOLED_DARK_BACKGROUND = Color(0xFF000000)

@Serializable
enum class ColorMode {
    SYSTEM,
    LIGHT,
    DARK
}

@Composable
fun RikkahubTheme(
    content: @Composable () -> Unit
) {
    val settings by rememberUserSettingsState()

    val colorMode by rememberColorMode()
    val darkTheme = when (colorMode) {
        ColorMode.SYSTEM -> isSystemInDarkTheme()
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }
    val amoledDarkMode by rememberAmoledDarkMode()

    val selectedTheme = findThemeById(settings.themeId, settings.customThemes)
        ?: findPresetTheme(settings.themeId)
    val packageTokens = settings.customThemes.firstOrNull { it.id == settings.themeId }
        ?.packageTokens.takeUnless { settings.dynamicColor }

    val colorScheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> {
            selectedTheme.getColorScheme(dark = darkTheme)
        }
    }
    val colorSchemeConverted = remember(darkTheme, amoledDarkMode, colorScheme) {
        if (darkTheme && amoledDarkMode) {
            colorScheme.copy(
                background = AMOLED_DARK_BACKGROUND,
                surface = AMOLED_DARK_BACKGROUND,
            )
        } else {
            colorScheme
        }
    }
    val extendColors = if (darkTheme) ExtendDarkColors else ExtendLightColors

    // 显式颜色覆盖只解析一次，主题包扩展 surface 也要遵守同样的优先级。
    val explicitPrimaryColor = settings.displaySetting.primaryColor?.toComposeColor()
    val explicitGlobalTextColor = settings.displaySetting.globalTextColor?.toComposeColor()

    // 颜色自定义覆盖
    val finalColorScheme = remember(
        colorSchemeConverted,
        explicitPrimaryColor,
        explicitGlobalTextColor,
        settings.displaySetting.interfaceSurfaceOpacity,
        settings.themeId,
    ) {
        var scheme = colorSchemeConverted
        explicitPrimaryColor?.let { primaryColor ->
            val luminance = 0.299f * primaryColor.red + 0.587f * primaryColor.green + 0.114f * primaryColor.blue
            val onPrimary = if (luminance > 0.5f) Color.Black else Color.White
            scheme = scheme.copy(
                primary = primaryColor,
                onPrimary = onPrimary,
            )
        }
        explicitGlobalTextColor?.let { textColor ->
            scheme = scheme.copy(
                onBackground = textColor,
                onSurface = textColor,
                onSurfaceVariant = textColor,
            )
        }
        val interfaceSurfaceAlpha =
            (settings.displaySetting.interfaceSurfaceOpacity / 100f).coerceIn(0f, 0.95f)
        // 直接覆盖容器 token 的 alpha，保留 RGB，避免与主题或组件已有 alpha 相乘。
        // background 保持不透明，使聊天页外层 Surface 能完整隔离导航根部的设置背景。
        scheme = scheme.copy(
            surface = scheme.surface.copy(alpha = interfaceSurfaceAlpha),
            surfaceBright = scheme.surfaceBright.copy(alpha = interfaceSurfaceAlpha),
            surfaceDim = scheme.surfaceDim.copy(alpha = interfaceSurfaceAlpha),
            surfaceContainerLowest = scheme.surfaceContainerLowest.copy(alpha = interfaceSurfaceAlpha),
            surfaceContainerLow = scheme.surfaceContainerLow.copy(alpha = interfaceSurfaceAlpha),
            surfaceContainer = scheme.surfaceContainer.copy(alpha = interfaceSurfaceAlpha),
            surfaceContainerHigh = scheme.surfaceContainerHigh.copy(alpha = interfaceSurfaceAlpha),
            surfaceContainerHighest = scheme.surfaceContainerHighest.copy(alpha = interfaceSurfaceAlpha),
            surfaceVariant = scheme.surfaceVariant.copy(alpha = interfaceSurfaceAlpha),
        )
        scheme
    }

    // 包主题额外 surface 角色：仅在实际由包配色驱动时存在，动态色/预设主题保持 null。
    // 全局文字色优先于 mutedText，避免用户显式文字覆盖被包覆盖。
    val packageSurfaceTokens = remember(packageTokens, darkTheme, explicitGlobalTextColor) {
        runCatching {
            packageTokens?.toPackageSurfaceTokens(
                darkMode = darkTheme,
                mutedTextOverride = explicitGlobalTextColor,
            )
        }.getOrNull()
    }

    val resolvedMaterialMode = when (settings.displaySetting.materialMode) {
        DisplayMaterialMode.FOLLOW_THEME -> {
            if (settings.themeId == "liquid_glass") DisplayMaterialMode.GLASS else DisplayMaterialMode.FLAT
        }
        else -> settings.displaySetting.materialMode
    }

    // 更新状态栏图标颜色
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalDarkMode provides darkTheme,
        LocalMaterialMode provides resolvedMaterialMode,
        LocalPopupSurfaceOpacity provides settings.displaySetting.popupSurfaceOpacity,
        LocalPackageSurfaceTokens provides packageSurfaceTokens,
        LocalExtendColors provides extendColors,
        LocalOverscrollFactory provides null
    ) {
        MaterialExpressiveTheme(
            colorScheme = finalColorScheme,
            typography = Typography,
            shapes = packageTokens?.toShapes() ?: androidx.compose.material3.Shapes(),
            content = content,
            motionScheme = MotionScheme.expressive()
        )
    }
}

val MaterialTheme.extendColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendColors.current
