package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.theme.packageio.ThemeShapeTokens
import me.rerere.rikkahub.theme.packageio.ThemeTokenSchema
import me.rerere.rikkahub.theme.packageio.ThemeTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PackageThemeSurfaceTest {
    @Test fun mapsAllExtraSurfaceRoles() {
        val light = tokenMap("#FFECF8FF").toMutableMap().apply {
            this["cardBackground"] = "#FFD5ECFE"
            this["cardBorder"] = "#FF6282A6"
            this["mutedText"] = "#FF3B597C"
            this["selected"] = "#FFA3C4E2"
            this["shadow"] = "#33000000"
        }
        val surfaces = tokens(light = light).toPackageSurfaceTokens(darkMode = false)
        assertEquals(Color(0xFFD5ECFE), surfaces.cardBackground)
        assertEquals(Color(0xFF6282A6), surfaces.cardBorder)
        assertEquals(Color(0xFF3B597C), surfaces.mutedText)
        assertEquals(Color(0xFFA3C4E2), surfaces.selected)
        assertEquals(0x33 / 255f, surfaces.shadow.alpha, 0.01f)
    }

    @Test fun usesDarkMapWhenDark() {
        val dark = tokenMap("#FF1E2126").toMutableMap().apply {
            this["cardBackground"] = "#FF31343A"
            this["cardBorder"] = "#FFB3C8DD"
            this["mutedText"] = "#FFB5BABE"
            this["selected"] = "#FF41464B"
            this["shadow"] = "#66000000"
        }
        val surfaces = tokens(dark = dark).toPackageSurfaceTokens(darkMode = true)
        assertEquals(Color(0xFF31343A), surfaces.cardBackground)
        assertEquals(Color(0xFFB3C8DD), surfaces.cardBorder)
        assertEquals(Color(0xFFB5BABE), surfaces.mutedText)
        assertEquals(Color(0xFF41464B), surfaces.selected)
        assertEquals(0x66 / 255f, surfaces.shadow.alpha, 0.01f)
    }

    @Test fun explicitGlobalTextOverrideBeatsMutedText() {
        val globalText = Color(0xFF112233)
        val surfaces = tokens().toPackageSurfaceTokens(
            darkMode = false,
            mutedTextOverride = globalText,
        )
        assertEquals(globalText, surfaces.mutedText)
    }

    @Test fun staticCardBorderPreservesTokenAlphaAtReducedStrength() {
        val map = tokenMap("#FF000000").toMutableMap().apply {
            this["cardBorder"] = "#80FFFFFF"
        }
        val surfaces = tokens(light = map).toPackageSurfaceTokens(darkMode = false)
        val border = surfaces.cardBorderStrokeColor()
        assertEquals(0.5f * 0.55f, border.alpha, 0.001f)
        assertEquals(Color.White.red, border.red, 0.001f)
    }

    @Test fun rejectsIncompleteSurfaceTokenMap() {
        val light = tokenMap("#FFFFFFFF").toMutableMap().apply {
            remove("selected")
        }
        assertThrows(IllegalArgumentException::class.java) {
            tokens(light = light).toPackageSurfaceTokens(darkMode = false)
        }
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
