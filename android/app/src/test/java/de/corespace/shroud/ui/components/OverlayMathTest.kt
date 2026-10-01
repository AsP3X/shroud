package de.corespace.shroud.ui.components

import android.view.inputmethod.EditorInfo
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The small pure rules of the sheets, the alert, the slider and the overlay palette. */
class OverlayMathTest {
    @Test
    fun sheetsCloseFarEnoughDownOrOnAFlick() {
        // As ShroudSheet always did: past 120 dp or faster than 2000 px/s.
        assertFalse(SheetDrag.shouldDismiss(offsetPx = 100f, velocityPx = 0f, dismissDistancePx = 120f))
        assertFalse(SheetDrag.shouldDismiss(offsetPx = 120f, velocityPx = 0f, dismissDistancePx = 120f))
        assertTrue(SheetDrag.shouldDismiss(offsetPx = 121f, velocityPx = 0f, dismissDistancePx = 120f))
        assertTrue(SheetDrag.shouldDismiss(offsetPx = 10f, velocityPx = 2001f, dismissDistancePx = 120f))
        assertFalse(SheetDrag.shouldDismiss(offsetPx = 10f, velocityPx = 2000f, dismissDistancePx = 120f))
        // An upward flick never closes.
        assertFalse(SheetDrag.shouldDismiss(offsetPx = 60f, velocityPx = -5000f, dismissDistancePx = 120f))
    }

    @Test
    fun sheetsNeverMoveAboveTheirRestingPlace() {
        assertEquals(30f, SheetDrag.dragged(10f, 20f), 0.001f)
        assertEquals(0f, SheetDrag.dragged(10f, -20f), 0.001f)
        assertEquals(0f, SheetDrag.dragged(0f, -5f), 0.001f)
    }

    @Test
    fun sheetLooksFollowTheDesign() {
        // aNX3S onboarding sheet: r22 top; nUbf0 / l14KDf / w4lZ1 floating: r38, 8 dp inset.
        assertEquals(22.dp, SheetLook.of(SheetStyle.Full).cornerRadius)
        assertEquals(38.dp, SheetLook.of(SheetStyle.Inset).cornerRadius)
        assertEquals(38.dp, SheetLook.of(SheetStyle.Compact).cornerRadius)
        assertEquals(8.dp, SheetLook.of(SheetStyle.Inset).margin)
        assertEquals(8.dp, SheetLook.of(SheetStyle.Compact).margin)
        assertEquals((-4).dp, SheetLook.of(SheetStyle.Inset).shadowOffsetY)
        assertEquals((-8).dp, SheetLook.of(SheetStyle.Compact).shadowOffsetY)
    }

    @Test
    fun alertCardIsAtMost320AndKeeps40FromEachSide() {
        // settings-lock §2.4: width min(320, screen − 2·40).
        assertEquals(320.dp, AlertMetrics.cardWidth(412.dp))
        assertEquals(280.dp, AlertMetrics.cardWidth(360.dp))
        assertEquals(320.dp, AlertMetrics.cardWidth(400.dp))
    }

    @Test
    fun alertFieldsKeepTypingOutOfTheKeyboardsLearning() {
        val options = EditorInfo.IME_ACTION_DONE
        val flagged = withoutPersonalizedLearning(options)
        assertEquals(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, flagged and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertEquals(EditorInfo.IME_ACTION_DONE, flagged and EditorInfo.IME_MASK_ACTION)
    }

    @Test
    fun sliderMapsValuesToTheThumbAndBack() {
        val range = 0f..1f
        assertEquals(0.25f, SliderMath.fraction(0.25f, range), 0.0001f)
        assertEquals(0f, SliderMath.fraction(-1f, range), 0.0001f)
        assertEquals(1f, SliderMath.fraction(3f, range), 0.0001f)
        assertEquals(0.5f, SliderMath.fraction(15f, 10f..20f), 0.0001f)
        assertEquals(0f, SliderMath.fraction(5f, 5f..5f), 0.0001f)
        // 200 px of travel.
        assertEquals(50f, SliderMath.touchX(0.25f, 200f, rtl = false), 0.001f)
        assertEquals(150f, SliderMath.touchX(0.25f, 200f, rtl = true), 0.001f)
        assertEquals(0.25f, SliderMath.valueAt(50f, 200f, range, rtl = false), 0.0001f)
        assertEquals(0.25f, SliderMath.valueAt(150f, 200f, range, rtl = true), 0.0001f)
        assertEquals(1f, SliderMath.valueAt(500f, 200f, range, rtl = false), 0.0001f)
        assertEquals(15f, SliderMath.valueAt(100f, 200f, 10f..20f, rtl = false), 0.0001f)
        assertEquals(0.1f, SliderMath.valueDelta(20f, 200f, range, rtl = false), 0.0001f)
        assertEquals(-0.1f, SliderMath.valueDelta(20f, 200f, range, rtl = true), 0.0001f)
    }

    @Test
    fun overlayPalettesMatchTheThemeTokens() {
        // shell-chats §15.1 / W1-UI-THEME ShroudColors: same values, so W1-INT can swap them 1:1.
        assertEquals(0x59F2F2F7, LightOverlayPalette.menuScrim.toArgbInt())
        assertEquals(0x470F0F14, DarkOverlayPalette.menuScrim.toArgbInt())
        assertEquals(0x590B0B12, LightOverlayPalette.menuScrimNoBlur.toArgbInt())
        assertEquals(0x470B0B12, LightOverlayPalette.sheetScrim.toArgbInt())
        assertEquals(0x470B0B12, DarkOverlayPalette.sheetScrim.toArgbInt())
        assertEquals(0x590B0B12, LightOverlayPalette.compactSheetScrim.toArgbInt())
        assertEquals(0xD1FFFFFF.toInt(), LightOverlayPalette.cardGlass.toArgbInt())
        assertEquals(0xF01F1F24.toInt(), DarkOverlayPalette.cardGlass.toArgbInt())
        assertEquals(0xF5FFFFFF.toInt(), LightOverlayPalette.cardOpaque.toArgbInt())
        assertEquals(0xFFC7C7CC.toInt(), LightOverlayPalette.chevron.toArgbInt())
        assertEquals(0xFF2C2C2E.toInt(), DarkOverlayPalette.rowPressed.toArgbInt())
    }

    private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int {
        val a = (alpha * 255f + 0.5f).toInt()
        val r = (red * 255f + 0.5f).toInt()
        val g = (green * 255f + 0.5f).toInt()
        val b = (blue * 255f + 0.5f).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
