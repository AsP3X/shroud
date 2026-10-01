package de.corespace.shroud.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.DarkColors
import de.corespace.shroud.ui.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Glass tiers (design-inventory §2 "Effects vocabulary", shell-chats §6.1, conversation-compose-media
 * §2.4) and the design's *Glass — Without Blur* fallback (Gwp1b): API 30 keeps shape, rim and
 * shadow and swaps the fill to the near-opaque tokens; API 31+ blurs behind the tier fill.
 */
class GlassTest {
    @Test
    fun backdropCaseFollowsSourceAndPlatform() {
        assertEquals(GlassBackdrop.Flat, glassBackdrop(hasSource = false, platformBlurs = true))
        assertEquals(GlassBackdrop.Flat, glassBackdrop(hasSource = false, platformBlurs = false))
        assertEquals(GlassBackdrop.Blurred, glassBackdrop(hasSource = true, platformBlurs = true))
        assertEquals(GlassBackdrop.Opaque, glassBackdrop(hasSource = true, platformBlurs = false))
    }

    @Test
    fun regularAndBarAreTheBarControlTier() {
        listOf(GlassStyle.Regular, GlassStyle.Bar).forEach { style ->
            val blurred = glassRecipe(style, LightColors, GlassBackdrop.Blurred)
            assertEquals(Color(0xB8FFFFFF), blurred.fill)
            assertEquals(Color(0xCCFFFFFF), blurred.stroke)
            assertEquals(1.dp, blurred.strokeWidth)
            assertEquals(18.dp, blurred.blurRadius)
            assertEquals(Color(0x140B0B12), blurred.shadowColor)
            assertEquals(4.dp, blurred.shadowOffsetY)
            assertEquals(12.dp, blurred.shadowRadius)

            val api30 = glassRecipe(style, LightColors, GlassBackdrop.Opaque)
            assertEquals(Color(0xF5FFFFFF), api30.fill)
            assertEquals(0.dp, api30.blurRadius)
            assertEquals(blurred.copy(fill = api30.fill, blurRadius = 0.dp), api30)

            val dark = glassRecipe(style, DarkColors, GlassBackdrop.Opaque)
            assertEquals(Color(0xF52C2C2E), dark.fill)
            assertEquals(Color(0x1FFFFFFF), dark.stroke)
        }
    }

    @Test
    fun softIsTheFloatingTier() {
        val blurred = glassRecipe(GlassStyle.Soft, LightColors, GlassBackdrop.Blurred)
        assertEquals(GlassRecipe(Color(0x99FFFFFF), Color(0x80FFFFFF), 1.dp, 20.dp, Color(0x240B0B12), 8.dp, 24.dp), blurred)
        assertEquals(Color(0x992C2C2E), glassRecipe(GlassStyle.Soft, DarkColors, GlassBackdrop.Blurred).fill)
        assertEquals(Color(0xF52C2C2E), glassRecipe(GlassStyle.Soft, DarkColors, GlassBackdrop.Opaque).fill)
    }

    @Test
    fun lightMenuIsTheCardTier() {
        val blurred = glassRecipe(GlassStyle.LightMenu, LightColors, GlassBackdrop.Blurred)
        assertEquals(GlassRecipe(Color(0xD1FFFFFF), Color(0x99FFFFFF), 1.dp, 24.dp, Color(0x290B0B12), 12.dp, 32.dp), blurred)
        assertEquals(Color(0xF5FFFFFF), glassRecipe(GlassStyle.LightMenu, LightColors, GlassBackdrop.Opaque).fill)
        assertEquals(Color(0xF51F1F24), glassRecipe(GlassStyle.LightMenu, DarkColors, GlassBackdrop.Opaque).fill)
        assertEquals(Color(0x14FFFFFF), glassRecipe(GlassStyle.LightMenu, DarkColors, GlassBackdrop.Blurred).stroke)
    }

    @Test
    fun darkMenuIsFixedInBothAppearances() {
        val expected = GlassRecipe(Color(0xF01F1F24), Color(0x14FFFFFF), 0.5.dp, 0.dp, Color.Transparent, 0.dp, 0.dp)
        GlassBackdrop.entries.forEach { backdrop ->
            assertEquals(expected, glassRecipe(GlassStyle.DarkMenu, LightColors, backdrop))
            assertEquals(expected, glassRecipe(GlassStyle.DarkMenu, DarkColors, backdrop))
        }
    }

    @Test
    fun prominentIsAccentTintedWithoutBlur() {
        GlassBackdrop.entries.forEach { backdrop ->
            val recipe = glassRecipe(GlassStyle.Prominent, LightColors, backdrop)
            assertEquals(LightColors.accent.copy(alpha = 0.9f), recipe.fill)
            assertEquals(Color(0x66FFFFFF), recipe.stroke)
            assertEquals(0.dp, recipe.blurRadius)
            assertEquals(LightColors.accent.copy(alpha = 0.25f), recipe.shadowColor)
            assertEquals(4.dp, recipe.shadowOffsetY)
            assertEquals(12.dp, recipe.shadowRadius)
        }
    }

    @Test
    fun flatBackdropKeepsTheTranslucentFillWithoutBlur() {
        // No backdrop source (onboarding's flat background): nothing to blur, nothing shows through.
        GlassStyle.entries.forEach { style ->
            val flat = glassRecipe(style, LightColors, GlassBackdrop.Flat)
            val blurred = glassRecipe(style, LightColors, GlassBackdrop.Blurred)
            assertEquals(blurred.copy(blurRadius = 0.dp), flat)
        }
    }

    @Test
    fun interactiveGlassSwells() {
        assertEquals(1.06f, INTERACTIVE_SWELL)
    }
}
