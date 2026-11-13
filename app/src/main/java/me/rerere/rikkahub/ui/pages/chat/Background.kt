/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.ui.theme.LocalDarkMode
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalScrollCaptureInProgress
import androidx.compose.ui.res.painterResource
import coil3.compose.AsyncImage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.ui.components.ui.toComposeColor

/**
 * 聊天图片背景的共享视觉参数。
 *
 * AssistantBackground 与气泡实时背景片段共用同一套数值，确保两者逐像素一致：
 * - baseColor：页面基础底色（chatBackgroundColor 优先，否则取当前主题 background）；
 * - imageAlpha：背景纸不透明度（assistant.backgroundOpacity，coerceIn 0..1）；
 * - gradientTopAlpha / gradientBottomAlpha：图片上方垂直渐变遮罩的两个端点 alpha。
 */
data class ChatBackgroundVisuals(
    val baseColor: Color,
    val imageAlpha: Float,
    val gradientTopAlpha: Float = 0.2f,
    val gradientBottomAlpha: Float = 0.5f,
) {
    companion object {
        val Unspecified = ChatBackgroundVisuals(
            baseColor = Color.Unspecified,
            imageAlpha = 1f,
        )
    }
}

/**
 * 计算与 AssistantBackground 完全一致的背景视觉参数（主题实时值）。
 */
@Composable
fun rememberChatBackgroundVisuals(setting: Settings): ChatBackgroundVisuals {
    val assistant = setting.getCurrentAssistant()
    val baseColor = setting.displaySetting.chatBackgroundColor?.let { it.toComposeColor() }
        ?: MaterialTheme.colorScheme.background
    return ChatBackgroundVisuals(
        baseColor = baseColor,
        imageAlpha = assistant.backgroundOpacity.coerceIn(0f, 1f),
        gradientTopAlpha = if (LocalDarkMode.current) 0.2f else 0f,
        gradientBottomAlpha = if (LocalDarkMode.current) 0.5f else 0f,
    )
}

/**
 * 主题自带的聊天底纹。
 *
 * 键是主题包内 theme 的 id：应用主题时 themeId 会被写成 `package:<包名>:<主题id>`，
 * 所以匹配前先用 [themePatternKey] 取最后一段，避免和包名硬绑定。
 * 每个主题可给亮/暗两版纹样：深色模式换用提亮过的线色，保证在深色底上仍看得见。
 */
private data class ThemePattern(
    @DrawableRes val light: Int,
    @DrawableRes val dark: Int = light,
)

private val ThemePatterns: Map<String, ThemePattern> = mapOf(
    "pearltide" to ThemePattern(R.drawable.pearltide_chat_bg),
    "terracotta" to ThemePattern(
        R.drawable.theme_pattern_terracotta,
        R.drawable.theme_pattern_terracotta_dark,
    ),
    "celadon" to ThemePattern(
        R.drawable.theme_pattern_celadon,
        R.drawable.theme_pattern_celadon_dark,
    ),
    "nocturne" to ThemePattern(
        R.drawable.theme_pattern_nocturne,
        R.drawable.theme_pattern_nocturne_dark,
    ),
    "matcha" to ThemePattern(
        R.drawable.theme_pattern_matcha,
        R.drawable.theme_pattern_matcha_dark,
    ),
)

private fun themePatternKey(themeId: String): String =
    if (themeId.startsWith("package:")) themeId.substringAfterLast(':') else themeId

/**
 * 当前应当绘制的主题底纹资源；返回 null 表示不绘制。
 *
 * 只有在「用户没有自备背景」且「设置里打开了主题底纹」时才返回资源：
 * 用户自选背景图片 > 自定义纯色 > 主题底纹 > 主题背景色，避免多层纹理互相透叠。
 */
@Composable
private fun rememberThemePatternRes(setting: Settings, userHasOwnBackground: Boolean): Int? {
    val dark = LocalDarkMode.current
    val key = themePatternKey(setting.themeId)
    val switchOn = setting.displaySetting.themePatternEnabled
    return remember(key, dark, switchOn, userHasOwnBackground) {
        if (userHasOwnBackground || !switchOn) {
            null
        } else {
            ThemePatterns[key]?.let { if (dark) it.dark else it.light }
        }
    }
}

@Composable
fun AssistantBackground(
    setting: Settings,
) {
    val assistant = setting.getCurrentAssistant()
    val visuals = rememberChatBackgroundVisuals(setting)
    val chatBackgroundColor = setting.displaySetting.chatBackgroundColor?.let { it.toComposeColor() }
    val isScrollCaptureInProgress = LocalScrollCaptureInProgress.current
    val themePattern = rememberThemePatternRes(
        setting = setting,
        userHasOwnBackground = assistant.background != null || chatBackgroundColor != null,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // MIUI's scroll capture composites the page by repeated screenshots. Keep image layers
        // out of that capture window for one diagnostic pass; solid colors remain unchanged.
        if (!isScrollCaptureInProgress) {
            themePattern?.let { res ->
                Image(
                    painter = painterResource(id = res),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        when {
            !isScrollCaptureInProgress && assistant.background != null -> {
                // 用户手动为助手设置的背景图，优先级最高
                AsyncImage(
                    model = assistant.background,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(visuals.imageAlpha)
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    visuals.baseColor.copy(alpha = visuals.gradientTopAlpha),
                                    visuals.baseColor.copy(alpha = visuals.gradientBottomAlpha)
                                )
                            )
                        )
                )
            }

            chatBackgroundColor != null -> {
                // 用户设置了自定义纯色背景
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(chatBackgroundColor)
                )
            }
        }
    }
}
