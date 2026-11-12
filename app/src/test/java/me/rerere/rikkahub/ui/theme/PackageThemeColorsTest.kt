package me.rerere.rikkahub.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.theme.packageio.ThemeShapeTokens
import me.rerere.rikkahub.theme.packageio.ThemeTokenSchema
import me.rerere.rikkahub.theme.packageio.ThemeTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PackageThemeColorsTest {
    @Test fun mapsLightTokens() {
        val light = tokenMap("#FFFFE8F1").toMutableMap().apply {
            this["primary"] = "#FF833252"
            this["onPrimary"] = "#FFFFFFFF"
            this["background"] = "#FFFFE8F1"
            this["surface"] = "#FFFFE8F1"
            this["onSurface"] = "#FF833252"
        }
        val scheme = tokens(light = light).toColorScheme(darkMode = false)
        assertEquals(Color(0xFF833252), scheme.primary)
        assertEquals(Color(0xFFFFE8F1), scheme.background)
        assertEquals(Color(0xFF833252), scheme.onSurface)
    }

    @Test fun mapsDarkTokensAndKeepsDarkDefaults() {
        val dark = tokenMap("#FF17191F").toMutableMap().apply {
            this["background"] = "#FF17191F"
            this["surface"] = "#FF17191F"
            this["onSurface"] = "#FFFFE8F1"
        }
        val scheme = tokens(dark = dark).toColorScheme(darkMode = true)
        assertEquals(Color(0xFF17191F), scheme.background)
        assertEquals(Color(0xFFFFE8F1), scheme.onSurface)
    }

    @Test fun sixDigitHexBecomesOpaque() {
        val map = tokenMap("#FFFFFF")
        val scheme = tokens(light = map, dark = map).toColorScheme(darkMode = false)
        assertEquals(Color.White, scheme.surface)
        assertEquals(1f, scheme.surface.alpha, 0f)
    }

    @Test fun eightDigitHexKeepsAlpha() {
        val map = tokenMap("#80FFFFFF")
        val scheme = tokens(light = map, dark = map).toColorScheme(darkMode = false)
        assertEquals(0.5f, scheme.surface.alpha, 0.01f)
    }

    @Test fun surfaceTintFollowsPrimary() {
        val light = tokenMap("#FFFFFFFF").toMutableMap().apply { this["primary"] = "#FF833252" }
        val scheme = tokens(light = light).toColorScheme(darkMode = false)
        assertEquals(Color(0xFF833252), scheme.surfaceTint)
    }

    @Test fun rejectsIncompleteTokenMap() {
        val light = tokenMap("#FFFFFFFF").toMutableMap().apply { remove("outline") }
        assertThrows(IllegalArgumentException::class.java) {
            tokens(light = light).toColorScheme(darkMode = false)
        }
    }

    @Test fun rejectsMalformedColour() {
        val light = tokenMap("#FFFFFFFF").toMutableMap().apply { this["background"] = "#12345" }
        assertThrows(IllegalArgumentException::class.java) {
            tokens(light = light).toColorScheme(darkMode = false)
        }
    }

    @Test fun mapsShapeTokens() {
        val shapes = ThemeShapeTokens(cornerSmallDp = 4f, cornerMediumDp = 10f, cornerLargeDp = 24f).toShapes()
        assertEquals(RoundedCornerShape(2.dp), shapes.extraSmall)
        assertEquals(RoundedCornerShape(4.dp), shapes.small)
        assertEquals(RoundedCornerShape(10.dp), shapes.medium)
        assertEquals(RoundedCornerShape(24.dp), shapes.large)
        assertEquals(RoundedCornerShape(32.dp), shapes.extraLarge)
        assertEquals(RoundedCornerShape(28.dp), shapes.largeIncreased)
        assertEquals(RoundedCornerShape(36.dp), shapes.extraLargeIncreased)
        assertEquals(RoundedCornerShape(40.dp), shapes.extraExtraLarge)
    }

    @Test fun tokenShapesDelegateToShapeBlock() {
        val tokens = tokens(shape = ThemeShapeTokens(cornerSmallDp = 8f, cornerMediumDp = 12f, cornerLargeDp = 20f))
        assertEquals(RoundedCornerShape(12.dp), tokens.toShapes().medium)
        assertEquals(RoundedCornerShape(28.dp), tokens.toShapes().extraLarge)
        assertEquals(RoundedCornerShape(36.dp), tokens.toShapes().extraExtraLarge)
    }

    private fun tokens(
        light: Map<String, String> = tokenMap("#FFFFE8F1"),
        dark: Map<String, String> = tokenMap("#FF17191F"),
        shape: ThemeShapeTokens = ThemeShapeTokens(),
    ) = ThemeTokens(
        schemaVersion = 1,
        id = "sample",
        name = "Sample",
        light = light,
        dark = dark,
        shape = shape,
    )

    private fun tokenMap(fill: String): Map<String, String> =
        ThemeTokenSchema.requiredNames.associateWith { fill }
}
