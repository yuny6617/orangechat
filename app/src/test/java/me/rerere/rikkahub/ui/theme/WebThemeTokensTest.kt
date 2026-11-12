package me.rerere.rikkahub.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.theme.packageio.ThemeShapeTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebThemeTokensTest {
    private val scheme = lightColorScheme()
    private val surfaces = PackageSurfaceTokens(
        cardBackground = Color(0xFFD5ECFE),
        cardBorder = Color(0x806282A6),
        mutedText = Color(0xFF3B597C),
        selected = Color(0xFFA3C4E2),
        shadow = Color(0x33000000),
    )
    private val corners = WebThemeTokens.CardCorners(extraLarge = 28f, large = 20f, medium = 12f)

    @Test
    fun materialRolesKeepLegacyNamesAndAliases() {
        val tokens = WebThemeTokens.cssTokens(scheme, null, corners, surfaceOpacityPercent = 100f)
        assertEquals(WebThemeTokens.cssColor(scheme.primary), tokens["--orange-primary"])
        assertEquals(tokens["--orange-primary"], tokens["--primary"])
        assertEquals(tokens["--orange-surface"], tokens["--surface"])
        assertTrue(tokens.containsKey("--surface-container-low"))
        assertTrue(tokens.containsKey("--surface-container"))
        assertTrue(tokens.containsKey("--outline-variant"))
        // Material roles stay opaque and keep the exact historical formatting.
        assertTrue(tokens.getValue("--orange-primary").matches(Regex("#[0-9A-F]{6}")))
    }

    @Test
    fun packageRolesUseNamesTheMomentsStylesheetReads() {
        val tokens = WebThemeTokens.cssTokens(scheme, surfaces, corners, surfaceOpacityPercent = 100f)
        assertTrue(tokens.containsKey(WebThemeTokens.CARD_BACKGROUND))
        assertTrue(tokens.containsKey(WebThemeTokens.CARD_BORDER))
        assertTrue(tokens.containsKey(WebThemeTokens.MUTED_TEXT))
        assertTrue(tokens.containsKey(WebThemeTokens.SELECTED))
        assertTrue(tokens.containsKey(WebThemeTokens.SHADOW))
        // Real local-moments targets, so the plugin's alias order cannot drop them.
        assertEquals(tokens[WebThemeTokens.CARD_BACKGROUND], tokens["--oc-surface"])
        assertEquals(tokens[WebThemeTokens.MUTED_TEXT], tokens["--oc-muted"])
        assertEquals(tokens[WebThemeTokens.CARD_BORDER], tokens["--oc-border"])
        assertEquals(tokens[WebThemeTokens.SHADOW], tokens["--oc-shadow"])
        assertEquals("28px", tokens[WebThemeTokens.RADIUS_XL])
        assertEquals("20px", tokens[WebThemeTokens.RADIUS_LG])
        assertEquals("12px", tokens[WebThemeTokens.RADIUS_MD])
    }

    @Test
    fun alphaIsWrittenInCssByteOrder() {
        // The host clamps interface opacity to 0..0.95, so 100% is a 0xF2 alpha.
        val tokens = WebThemeTokens.cssTokens(scheme, surfaces, corners, surfaceOpacityPercent = 100f)
        // #RRGGBBAA, not Android's #AARRGGBB, or CSS would read the wrong bytes.
        assertEquals("#D5ECFEF2", tokens[WebThemeTokens.CARD_BACKGROUND])
        assertEquals("#6282A680", tokens[WebThemeTokens.CARD_BORDER])
        assertEquals("#A3C4E2", tokens[WebThemeTokens.SELECTED])
        assertEquals("#00000033", tokens[WebThemeTokens.SHADOW])
    }

    @Test
    fun userSurfaceOpacityOverwritesCardAlpha() {
        val half = WebThemeTokens.cssTokens(scheme, surfaces, corners, surfaceOpacityPercent = 50f)
        assertEquals("#D5ECFE80", half[WebThemeTokens.CARD_BACKGROUND])
        // Out-of-range input stays inside the same clamp as the native host.
        val clamped = WebThemeTokens.cssTokens(scheme, surfaces, corners, surfaceOpacityPercent = 500f)
        assertEquals("#D5ECFEF2", clamped[WebThemeTokens.CARD_BACKGROUND])
        val transparent = WebThemeTokens.cssTokens(scheme, surfaces, corners, surfaceOpacityPercent = 0f)
        assertEquals("#D5ECFE00", transparent[WebThemeTokens.CARD_BACKGROUND])
    }

    @Test
    fun userMutedTextOverrideWinsOverPackage() {
        val userText = PackageSurfaceTokens(
            cardBackground = surfaces.cardBackground,
            cardBorder = surfaces.cardBorder,
            mutedText = Color(0xFF112233),
            selected = surfaces.selected,
            shadow = surfaces.shadow,
        )
        val tokens = WebThemeTokens.cssTokens(scheme, userText, corners, surfaceOpacityPercent = 100f)
        assertEquals("#112233", tokens[WebThemeTokens.MUTED_TEXT])
        assertEquals("#112233", tokens["--oc-muted"])
    }

    @Test
    fun withoutPackageThemeOnlyMaterialRolesAreEmitted() {
        val tokens = WebThemeTokens.cssTokens(scheme, null, corners, surfaceOpacityPercent = 50f)
        assertFalse(tokens.containsKey(WebThemeTokens.CARD_BACKGROUND))
        assertFalse(tokens.containsKey(WebThemeTokens.SELECTED))
        assertFalse(tokens.containsKey(WebThemeTokens.RADIUS_XL))
        assertFalse(tokens.containsKey("--oc-surface"))
    }

    @Test
    fun cornersFollowMaterialShapesAndPackageTokens() {
        val shapes = Shapes(
            extraLarge = RoundedCornerShape(28.dp),
            large = RoundedCornerShape(20.dp),
            medium = RoundedCornerShape(12.dp),
        )
        assertEquals(WebThemeTokens.CardCorners(28f, 20f, 12f), WebThemeTokens.cornersFromShapes(shapes))
        assertEquals(
            WebThemeTokens.CardCorners(28f, 20f, 12f),
            WebThemeTokens.cornersFromPackage(
                ThemeShapeTokens(cornerSmallDp = 8f, cornerMediumDp = 12f, cornerLargeDp = 20f),
            ),
        )
    }

    @Test
    fun cssColorRoundTripsOpaqueAndTranslucentValues() {
        assertEquals("#D5ECFE", WebThemeTokens.cssColor(Color(0xFFD5ECFE)))
        assertEquals("#D5ECFE80", WebThemeTokens.cssColor(Color(0x80D5ECFE)))
        assertEquals("#00000000", WebThemeTokens.cssColor(Color(0x00000000)))
    }
}
