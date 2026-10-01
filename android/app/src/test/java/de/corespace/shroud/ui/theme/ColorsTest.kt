package de.corespace.shroud.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tokens plan §1.7.12 adds, light / dark, with the values of shell-chats §15.1,
 * design-inventory §2 / addendum Theme / §9 and conversation-compose-media §2.3.
 */
class ColorsTest {
    private fun assertPair(name: String, light: Long, dark: Long, pick: (ShroudColors) -> Color) {
        assertEquals("$name light", Color(light), pick(LightColors))
        assertEquals("$name dark", Color(dark), pick(DarkColors))
    }

    @Test
    fun shellTokensMatchShellChats15_1() {
        assertPair("mutedBadge", 0xFF6D6D72, 0xFF4E4E51) { it.mutedBadge }
        assertPair("chevron", 0xFFC7C7CC, 0xFF48484A) { it.chevron }
        assertPair("rowPressed", 0xFFE5E5EA, 0xFF2C2C2E) { it.rowPressed }
        assertPair("tabSelected", 0x120B0B12, 0x1FFFFFFF) { it.tabSelected }
        assertPair("tabSelectedLifted", 0x1A0B0B12, 0x33FFFFFF) { it.tabSelectedLifted }
        assertPair("glassBarStroke", 0xCCFFFFFF, 0x1FFFFFFF) { it.glassBarStroke }
        assertPair("glassOpaque", 0xF5FFFFFF, 0xF52C2C2E) { it.glassOpaque }
        assertPair("cardGlass", 0xD1FFFFFF, 0xF01F1F24) { it.cardGlass }
        assertPair("cardStroke", 0x99FFFFFF, 0x14FFFFFF) { it.cardStroke }
        assertPair("cardOpaque", 0xF5FFFFFF, 0xF51F1F24) { it.cardOpaque }
        assertPair("menuScrim", 0x59F2F2F7, 0x470F0F14) { it.menuScrim }
        assertPair("menuScrimOpaque", 0x590B0B12, 0x590B0B12) { it.menuScrimOpaque }
        assertPair("sheetScrim", 0x470B0B12, 0x470B0B12) { it.sheetScrim }
        assertPair("dimPredictiveBack", 0x330B0B12, 0x330B0B12) { it.dimPredictiveBack }
    }

    @Test
    fun designVariablesKeepTheirValues() {
        assertPair("accent", 0xFF5E5CE6, 0xFF6B6BF2) { it.accent }
        assertPair("accentText", 0xFF5E5CE6, 0xFFA7A7FA) { it.accentText }
        assertPair("glass", 0xB8FFFFFF, 0xB82C2C2E) { it.glass }
        assertPair("glassSoft", 0x99FFFFFF, 0x992C2C2E) { it.glassSoft }
        assertPair("glassStroke", 0x80FFFFFF, 0x1FFFFFFF) { it.glassStroke }
        // Outgoing bubbles keep the light accent in dark mode so white text keeps 4.5:1 (`Theme.swift:30-32`).
        assertPair("bubbleOutgoing", 0xFF5E5CE6, 0xFF5E5CE6) { it.bubbleOutgoing }
        assertFalse(LightColors.isDark)
        assertTrue(DarkColors.isDark)
    }

    @Test
    fun brandGradientIsTheAvatarGradient() {
        // `Theme.swift:49-56`: (124, 122, 255) → (94, 92, 230), top to bottom.
        assertEquals(Color(0xFF7C7AFF), BrandColors.gradientTop)
        assertEquals(Color(0xFF5E5CE6), BrandColors.gradientBottom)
    }

    @Test
    fun mediaAndCallColoursAreFixed() {
        assertEquals(Color(0xFF2C2C2E), MediaColors.chrome)
        assertEquals(Color(0xFF3390EC), MediaColors.blue)
        assertEquals(Color(0xFFFF9E8C), MediaColors.warningText)
        assertEquals(Color(0x590B0B12), MediaColors.sheetScrim)
        assertEquals(Color(0xF01F1F24), MediaColors.toastDark)
        assertEquals(Color(0xFF141A29), CallColors.stageTop)
        assertEquals(Color(0xFF0D0F1A), CallColors.stageBottom)
        assertEquals(Color(0xFFFFD9A8), CallColors.unverified)
    }

    @Test
    fun tilePaletteHasTheTwelveDesignTints() {
        val expected = listOf(
            0xFF2E8FE0, 0xFF2FA85B, 0xFFF76B1C, 0xFF4AC7FA, 0xFFE64A72, 0xFF9B4AE6,
            0xFFFF3B30, 0xFFFF6B6B, 0xFFFF9F0A, 0xFF3432B8, 0xFFE69A1C, 0xFF0FA3A3,
        ).map { Color(it) }
        assertEquals(expected, TilePalette.all)
        assertEquals(12, TilePalette.all.toSet().size)
    }
}
