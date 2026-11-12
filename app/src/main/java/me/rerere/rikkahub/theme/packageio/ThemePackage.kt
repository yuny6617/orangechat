package me.rerere.rikkahub.theme.packageio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ThemePackageManifest(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val version: String,
    val author: String = "",
    val themes: List<String>,
)

@Serializable
data class ThemeTokens(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val light: Map<String, String>,
    val dark: Map<String, String>,
    val shape: ThemeShapeTokens = ThemeShapeTokens(),
)

@Serializable
data class ThemeShapeTokens(
    @SerialName("corner_small_dp") val cornerSmallDp: Float = 8f,
    @SerialName("corner_medium_dp") val cornerMediumDp: Float = 12f,
    @SerialName("corner_large_dp") val cornerLargeDp: Float = 20f,
)

data class ValidatedThemePackage(
    val manifest: ThemePackageManifest,
    val themes: Map<String, ThemeTokens>,
)

/** Stable, executable-code-free token DTO for host UI and same-origin WebView consumers. */
@Serializable
data class ThemeTokenDto(
    val schemaVersion: Int = 1,
    val dark: Boolean,
    val colors: Map<String, String>,
    val shape: ThemeShapeTokens = ThemeShapeTokens(),
)

object ThemeTokenSchema {
    const val PACKAGE_SCHEMA_VERSION = 1
    const val THEME_SCHEMA_VERSION = 1
    val requiredNames = setOf(
        "primary", "onPrimary", "primaryContainer", "onPrimaryContainer",
        "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
        "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
        "error", "onError", "errorContainer", "onErrorContainer",
        "background", "onBackground", "surface", "onSurface", "surfaceVariant", "onSurfaceVariant",
        "outline", "outlineVariant", "scrim", "inverseSurface", "inverseOnSurface", "inversePrimary",
        "surfaceDim", "surfaceBright", "surfaceContainerLowest", "surfaceContainerLow",
        "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest",
        "cardBackground", "cardBorder", "mutedText", "selected", "shadow",
    )
    val allowedNames = requiredNames

    fun validate(theme: ThemeTokens) {
        require(theme.schemaVersion == THEME_SCHEMA_VERSION) { "Unsupported theme schema version" }
        require(theme.id.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "Invalid theme id" }
        require(theme.name.isNotBlank() && theme.name.length <= 80) { "Invalid theme name" }
        require(theme.light.keys == requiredNames && theme.dark.keys == requiredNames) { "Theme must define the complete token schema" }
        (theme.light.values + theme.dark.values).forEach {
            require(it.matches(Regex("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?"))) { "Color must be #RRGGBB or #AARRGGBB" }
        }
        val shape = theme.shape
        require(shape.cornerSmallDp in 0f..32f && shape.cornerMediumDp in 0f..32f && shape.cornerLargeDp in 0f..32f) {
            "Corner radius is outside allowed range"
        }
    }
}
