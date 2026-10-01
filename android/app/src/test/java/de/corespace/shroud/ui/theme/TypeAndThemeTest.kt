package de.corespace.shroud.ui.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** `Type.kt` (conversation-thread §23.4, P13d) and the live Reduce Motion reading of `Theme.kt`. */
class TypeAndThemeTest {
    @Test
    fun tabularDigitsTurnOnTnumInInter() {
        val style = inter(15f, FontWeight.Medium, tabularDigits = true)
        assertEquals("tnum", style.fontFeatureSettings)
        assertSame(Inter, style.fontFamily)
        assertNull(inter(15f).fontFeatureSettings)
    }

    @Test
    fun monospacedIsBundledJetBrainsMono() {
        assertSame(Mono, inter(14f, FontWeight.Medium, monospaced = true).fontFamily)
        assertSame(Mono, mono(12f).fontFamily)
        assertSame(Inter, inter(14f).fontFamily)
        assertEquals(14f.sp, inter(14f).fontSize)
    }

    @Test
    fun lineSpacingAddsToTheFontsOwnLineHeight() {
        assertEquals((17f * 1.21f + 4f).sp, inter(17f, lineSpacing = 4f).lineHeight)
    }

    @Test
    fun reduceMotionIsAnimationsOff() {
        assertTrue(isReduceMotion(0f))
        assertFalse(isReduceMotion(1f))
        assertFalse(isReduceMotion(0.5f))
        assertFalse(isReduceMotion(10f))
    }
}
