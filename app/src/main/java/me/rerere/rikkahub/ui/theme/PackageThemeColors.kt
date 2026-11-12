package me.rerere.rikkahub.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.theme.packageio.ThemeShapeTokens
import me.rerere.rikkahub.theme.packageio.ThemeTokenSchema
import me.rerere.rikkahub.theme.packageio.ThemeTokens

/**
 * Maps a validated declaration-only package onto Material 3 roles.
 *
 * The dark factory is used for dark packages so roles that are not part of the
 * v1 schema still get dark defaults instead of light ones. Every token in
 * [ThemeTokenSchema.requiredNames] is validated and mapped here. The extra
 * `cardBackground` / `cardBorder` / `mutedText` / `selected` / `shadow` roles
 * are exposed separately through [toPackageSurfaceTokens] and
 * [LocalPackageSurfaceTokens] because Material 3 has no slot for them.
 */
fun ThemeTokens.toColorScheme(darkMode: Boolean): ColorScheme {
    ThemeTokenSchema.validate(this)
    val values = if (darkMode) dark else light
    fun color(key: String): Color = parseThemeColor(values[key] ?: error("Missing token $key"))
    return (if (darkMode) darkColorScheme() else lightColorScheme()).copy(
        primary = color("primary"), onPrimary = color("onPrimary"),
        primaryContainer = color("primaryContainer"), onPrimaryContainer = color("onPrimaryContainer"),
        secondary = color("secondary"), onSecondary = color("onSecondary"),
        secondaryContainer = color("secondaryContainer"), onSecondaryContainer = color("onSecondaryContainer"),
        tertiary = color("tertiary"), onTertiary = color("onTertiary"),
        tertiaryContainer = color("tertiaryContainer"), onTertiaryContainer = color("onTertiaryContainer"),
        background = color("background"), onBackground = color("onBackground"),
        surface = color("surface"), onSurface = color("onSurface"),
        surfaceVariant = color("surfaceVariant"), onSurfaceVariant = color("onSurfaceVariant"),
        outline = color("outline"), outlineVariant = color("outlineVariant"), scrim = color("scrim"),
        error = color("error"), onError = color("onError"),
        errorContainer = color("errorContainer"), onErrorContainer = color("onErrorContainer"),
        inverseSurface = color("inverseSurface"), inverseOnSurface = color("inverseOnSurface"),
        inversePrimary = color("inversePrimary"), surfaceDim = color("surfaceDim"),
        surfaceBright = color("surfaceBright"), surfaceContainerLowest = color("surfaceContainerLowest"),
        surfaceContainerLow = color("surfaceContainerLow"), surfaceContainer = color("surfaceContainer"),
        surfaceContainerHigh = color("surfaceContainerHigh"), surfaceContainerHighest = color("surfaceContainerHighest"),
        surfaceTint = color("primary"),
    )
}

/**
 * Shape tokens are declaration-only in v1 and stay small on purpose:
 * small/medium/large come straight from the package, extraSmall is half of
 * small (Material default pairing), and the larger variants are derived from
 * large so a package with a larger `large` never gets an inverted hierarchy
 * from Material's global extended defaults.
 */
fun ThemeShapeTokens.toShapes(): Shapes = Shapes(
    extraSmall = RoundedCornerShape((cornerSmallDp / 2f).dp),
    small = RoundedCornerShape(cornerSmallDp.dp),
    medium = RoundedCornerShape(cornerMediumDp.dp),
    large = RoundedCornerShape(cornerLargeDp.dp),
    extraLarge = RoundedCornerShape((cornerLargeDp + 8f).dp),
    largeIncreased = RoundedCornerShape((cornerLargeDp + 4f).dp),
    extraLargeIncreased = RoundedCornerShape((cornerLargeDp + 12f).dp),
    extraExtraLarge = RoundedCornerShape((cornerLargeDp + 16f).dp),
)

fun ThemeTokens.toShapes(): Shapes = shape.toShapes()

/**
 * Parses a token that already passed [ThemeTokenSchema.validate]. Kept separate
 * from the Material mapping so the non-Material surface roles can reuse exactly
 * the same `#RRGGBB` / `#AARRGGBB` handling.
 */
internal fun parseThemeColor(value: String): Color {
    val hex = value.removePrefix("#")
    return Color((if (hex.length == 6) "FF$hex" else hex).toLong(16))
}
