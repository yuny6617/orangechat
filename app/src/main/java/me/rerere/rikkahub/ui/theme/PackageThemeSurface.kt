/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.theme.packageio.ThemeTokenSchema
import me.rerere.rikkahub.theme.packageio.ThemeTokens

/**
 * Extra declaration-only package roles that have no Material 3 colour slot.
 *
 * Kept separate from [androidx.compose.material3.ColorScheme] so a package can
 * drive card surfaces, borders and muted text without pretending these are
 * Material roles. The shared drawer consumes selection and panel shadow roles.
 * Colours are
 * static values; consumers decide when a user-controlled alpha (for example
 * `interfaceSurfaceOpacity`) applies.
 */
data class PackageSurfaceTokens(
    val cardBackground: Color,
    val cardBorder: Color,
    val mutedText: Color,
    val selected: Color,
    val shadow: Color,
)

/**
 * Non-null only while a package theme is actually driving the UI (package
 * selected and dynamic colour off). `null` means "keep the existing Material
 * colours"; every consumer must preserve its previous fallback in that case.
 */
val LocalPackageSurfaceTokens = compositionLocalOf<PackageSurfaceTokens?> { null }

/**
 * Maps the already-validated `cardBackground` / `cardBorder` / `mutedText` /
 * `selected` / `shadow` tokens.
 *
 * @param mutedTextOverride lets the host keep an explicit global text colour
 *   ahead of the package's muted text, so a user text override is never lost.
 */
fun ThemeTokens.toPackageSurfaceTokens(
    darkMode: Boolean,
    mutedTextOverride: Color? = null,
): PackageSurfaceTokens {
    ThemeTokenSchema.validate(this)
    val values = if (darkMode) dark else light
    fun color(key: String): Color = parseThemeColor(values[key] ?: error("Missing token $key"))
    return PackageSurfaceTokens(
        cardBackground = color("cardBackground"),
        cardBorder = color("cardBorder"),
        mutedText = mutedTextOverride ?: color("mutedText"),
        selected = color("selected"),
        shadow = color("shadow"),
    )
}

/**
 * Static 1dp border tint for package-driven surfaces. The token's own alpha is
 * preserved but reduced so an opaque `cardBorder` does not become a hard line.
 */
fun PackageSurfaceTokens.cardBorderStrokeColor(): Color =
    cardBorder.copy(alpha = cardBorder.alpha * 0.55f)

@Composable
@ReadOnlyComposable
fun packageCardBackground(fallback: Color): Color =
    LocalPackageSurfaceTokens.current?.cardBackground ?: fallback

@Composable
@ReadOnlyComposable
fun packageSelectedColor(fallback: Color): Color =
    LocalPackageSurfaceTokens.current?.selected ?: fallback


