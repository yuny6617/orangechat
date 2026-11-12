/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import me.rerere.rikkahub.theme.packageio.ThemeShapeTokens
import me.rerere.rikkahub.ui.context.LocalDisplaySettings

/**
 * CSS custom properties handed to same-origin plugin WebViews.
 *
 * The Material roles keep the historical `--orange-*` names plus their
 * `--primary`/`--surface` aliases so existing plugin pages keep working byte for
 * byte. Package themes add the declaration-only roles that Material 3 has no
 * slot for (`cardBackground` / `cardBorder` / `mutedText` / `selected` /
 * `shadow`) and the package shape tokens.
 *
 * Precedence rules, matching the native theme:
 *  - user text overrides already live in [ColorScheme] / [PackageSurfaceTokens.mutedText],
 *    so this helper never re-derives text or muted text from the package alone;
 *  - the user's `interfaceSurfaceOpacity` overwrites the card background alpha
 *    (same clamp as [androidx.compose.material3.MaterialTheme] consumers in the host);
 *  - nothing package-specific is emitted when no package theme is active, so
 *    preset/dynamic themes keep the WebView's own fallbacks.
 */
object WebThemeTokens {
    const val CARD_BACKGROUND = "--cardBackground"
    const val CARD_BORDER = "--cardBorder"
    const val MUTED_TEXT = "--mutedText"
    const val SELECTED = "--selected"
    const val SHADOW = "--shadow"

    /** Real radius variables read by `plugins/local-moments/ui/styles.css`. */
    const val RADIUS_XL = "--oc-radius-xl"
    const val RADIUS_LG = "--oc-radius-lg"
    const val RADIUS_MD = "--oc-radius-md"

    /** Corner sizes in CSS px; CSS px and Compose dp share the same logical size. */
    data class CardCorners(val extraLarge: Float, val large: Float, val medium: Float)

    /**
     * Resolves the token map from the final (post user-override) Material scheme,
     * the current package surface roles and the effective shape.
     *
     * This is the single API a call site needs: `PluginWebViewPage` can replace
     * its hand-built map with `WebThemeTokens.current()`; nothing else changes.
     */
    @Composable
    @ReadOnlyComposable
    fun current(): Map<String, String> = cssTokens(
        colorScheme = MaterialTheme.colorScheme,
        surfaces = LocalPackageSurfaceTokens.current,
        corners = cornersFromShapes(MaterialTheme.shapes),
        surfaceOpacityPercent = LocalDisplaySettings.current.interfaceSurfaceOpacity,
    )

    /**
     * Pure mapping used by tests and by the native bridge when a Compose call
     * site has not been wired yet.
     */
    fun cssTokens(
        colorScheme: ColorScheme,
        surfaces: PackageSurfaceTokens?,
        corners: CardCorners,
        surfaceOpacityPercent: Float,
    ): Map<String, String> = appendPackageTokens(
        base = materialTokens(colorScheme),
        surfaces = surfaces,
        corners = corners,
        surfaceOpacityPercent = surfaceOpacityPercent,
    )

    /**
     * Material 3 roles, including the historical aliases. Values intentionally
     * keep the old `#RRGGBB` formatting (opaque RGB) so other plugin pages do
     * not change appearance; only the package roles below carry alpha.
     */
    fun materialTokens(colorScheme: ColorScheme): Map<String, String> {
        val base = linkedMapOf(
            "--orange-primary" to colorScheme.primary,
            "--orange-on-primary" to colorScheme.onPrimary,
            "--orange-primary-container" to colorScheme.primaryContainer,
            "--orange-on-primary-container" to colorScheme.onPrimaryContainer,
            "--orange-secondary" to colorScheme.secondary,
            "--orange-background" to colorScheme.background,
            "--orange-on-background" to colorScheme.onBackground,
            "--orange-surface" to colorScheme.surface,
            "--orange-on-surface" to colorScheme.onSurface,
            "--orange-surface-variant" to colorScheme.surfaceVariant,
            "--orange-on-surface-variant" to colorScheme.onSurfaceVariant,
            "--orange-outline" to colorScheme.outline,
            "--orange-error" to colorScheme.error,
        ).mapValues { (_, color) -> rgbHex(color) }
        return base + base.mapKeys { (key, _) -> key.replace("--orange-", "--") } + mapOf(
            "--surface-container-low" to rgbHex(colorScheme.surfaceContainerLow),
            "--surface-container" to rgbHex(colorScheme.surfaceContainer),
            "--outline-variant" to rgbHex(colorScheme.outlineVariant),
        )
    }

    /**
     * Appends package roles on top of [base]. Returns [base] unchanged when no
     * package theme drives the UI ([surfaces] is `null`).
     *
     * The local moments stylesheet resolves `--oc-*` through its own alias list,
     * which prefers Material outline/surface roles before `cardBorder` /
     * `mutedText`; setting the real `--oc-*` targets here is what makes the
     * package roles win in the plugin without duplicating a theme guess there.
     */
    fun appendPackageTokens(
        base: Map<String, String>,
        surfaces: PackageSurfaceTokens?,
        corners: CardCorners,
        surfaceOpacityPercent: Float,
    ): Map<String, String> {
        if (surfaces == null) return base
        val cardAlpha = (surfaceOpacityPercent / 100f).coerceIn(0f, 0.95f)
        // Overwrite the alpha instead of multiplying token alpha: this mirrors
        // Theme.kt and keeps the user's transparency setting authoritative.
        val card = surfaces.cardBackground.copy(alpha = cardAlpha)
        return base + linkedMapOf(
            CARD_BACKGROUND to cssColor(card),
            CARD_BORDER to cssColor(surfaces.cardBorder),
            MUTED_TEXT to cssColor(surfaces.mutedText),
            SELECTED to cssColor(surfaces.selected),
            SHADOW to cssColor(surfaces.shadow),
            "--oc-surface" to cssColor(card),
            "--oc-card-top" to cssColor(card),
            "--oc-card-bottom" to cssColor(card),
            "--oc-muted" to cssColor(surfaces.mutedText),
            "--oc-border" to cssColor(surfaces.cardBorder),
            "--oc-shadow" to cssColor(surfaces.shadow),
            "--oc-selected" to cssColor(surfaces.selected),
            RADIUS_XL to radius(corners.extraLarge),
            RADIUS_LG to radius(corners.large),
            RADIUS_MD to radius(corners.medium),
        )
    }

    /**
     * Reads the effective Material [Shapes]. Package themes map
     * `extraLarge = large + 8`, so the WebView sees the same card radius as
     * Compose without re-reading the theme package.
     */
    fun cornersFromShapes(shapes: Shapes): CardCorners = CardCorners(
        extraLarge = shapes.extraLarge.cornerDp() ?: 28f,
        large = shapes.large.cornerDp() ?: 16f,
        medium = shapes.medium.cornerDp() ?: 12f,
    )

    /** Mirrors `ThemeShapeTokens.toShapes()` so the bridge needs no Compose tree. */
    fun cornersFromPackage(shape: ThemeShapeTokens): CardCorners = CardCorners(
        extraLarge = shape.cornerLargeDp + 8f,
        large = shape.cornerLargeDp,
        medium = shape.cornerMediumDp,
    )

    /** `#RRGGBB` when opaque, `#RRGGBBAA` (CSS byte order) otherwise. */
    fun cssColor(color: Color): String {
        val alpha = channel(color.alpha)
        val rgb = (channel(color.red) shl 16) or (channel(color.green) shl 8) or channel(color.blue)
        return if (alpha == 0xFF) "#%06X".format(rgb) else "#%06X%02X".format(rgb, alpha)
    }

    private fun rgbHex(color: Color): String =
        "#%06X".format((channel(color.red) shl 16) or (channel(color.green) shl 8) or channel(color.blue))

    /** Same rounding as `Color.toArgb()` for sRGB colours; pure JVM-safe. */
    private fun channel(value: Float): Int = (value * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun radius(dp: Float): String = "${dp.toInt().coerceIn(0, 64)}px"

    private val unitDensity = Density(1f, 1f)

    /**
     * `RoundedCornerShape` stores dp corners as a [androidx.compose.foundation.shape.CornerSize];
     * resolving against a large shape size with density 1 keeps dp corners exact.
     * Percent corners are not used by Material/package shapes and fall back.
     */
    private fun androidx.compose.ui.graphics.Shape.cornerDp(): Float? {
        val rounded = this as? RoundedCornerShape ?: return null
        val px = rounded.topStart.toPx(Size(1000f, 1000f), unitDensity)
        return px.takeIf { it.isFinite() && it in 0f..64f }
    }
}
