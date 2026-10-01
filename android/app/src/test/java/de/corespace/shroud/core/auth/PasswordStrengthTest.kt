package de.corespace.shroud.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ios/shroudTests/PasswordStrengthEvaluatorTests.swift` (the five iOS tests, verbatim) plus the
 * PS.3 vectors of settings-lock (computed by running the iOS logic in Swift).
 */
class PasswordStrengthTest {
    @Test
    fun emptyPasswordIsUnset() {
        val e = PasswordStrength.evaluate("")
        assertEquals(PasswordStrengthLevel.Empty, e.level)
        assertEquals(0.0, e.score, 0.0)
        assertFalse(e.hasMinimumLength)
        assertFalse(e.hasSymbolAndNumber)
        assertFalse(e.meetsRequirements)
    }

    @Test
    fun shortPasswordIsWeak() {
        val e = PasswordStrength.evaluate("abc")
        assertEquals(PasswordStrengthLevel.Weak, e.level)
        assertFalse(e.hasMinimumLength)
        assertFalse(e.hasSymbolAndNumber)
    }

    @Test
    fun lengthOnlyPasswordIsFair() {
        val e = PasswordStrength.evaluate("longpassword")
        assertEquals(PasswordStrengthLevel.Fair, e.level)
        assertTrue(e.hasMinimumLength)
        assertFalse(e.hasSymbolAndNumber)
    }

    @Test
    fun meetingBothRequirementsIsAtLeastGood() {
        val e = PasswordStrength.evaluate("longpassword1!")
        assertTrue(e.level == PasswordStrengthLevel.Good || e.level == PasswordStrengthLevel.Strong)
        assertTrue(e.hasMinimumLength)
        assertTrue(e.hasSymbolAndNumber)
        assertTrue(e.meetsRequirements)
    }

    @Test
    fun complexPasswordIsStrong() {
        val e = PasswordStrength.evaluate("LongPassword1!")
        assertEquals(PasswordStrengthLevel.Strong, e.level)
        assertTrue(e.meetsRequirements)
        assertTrue(e.score >= 0.8)
    }

    @Test
    fun whitespaceIsNotASymbol() {
        assertFalse(PasswordStrength.evaluate("short1!").meetsRequirements)
        assertFalse(PasswordStrength.evaluate("longpassword1").meetsRequirements)
        assertFalse(PasswordStrength.evaluate("long password1").meetsRequirements)
    }

    /** settings-lock PS.3: input → (level, score, hasMinimumLength, hasSymbolAndNumber) as iOS computes them. */
    @Test
    fun countsGraphemesAndUnicodePropertiesLikeSwift() {
        val vectors = listOf(
            Vector("short1!", PasswordStrengthLevel.Fair, 0.35, false, true),
            Vector("long password1", PasswordStrengthLevel.Fair, 0.50, true, false),
            Vector("abcdefgh", PasswordStrengthLevel.Fair, 0.15, false, false),
            Vector("a1!", PasswordStrengthLevel.Fair, 0.35, false, true),
            Vector("abcdefghijk1!", PasswordStrengthLevel.Good, 0.85, true, true),
            Vector("abcdefghijkl1!", PasswordStrengthLevel.Strong, 0.85, true, true),
            Vector("Abcdefghijklmn1!", PasswordStrengthLevel.Strong, 1.00, true, true),
            Vector("passwordpassword", PasswordStrengthLevel.Fair, 0.55, true, false),
            // ½ is a number (No), not a symbol: without a symbol the pair is still missing.
            Vector("pässwörd½½½½", PasswordStrengthLevel.Fair, 0.50, true, false),
            // 6 clusters of é + combining acute, not 14 code points.
            Vector("é́".repeat(6) + "1!", PasswordStrengthLevel.Fair, 0.50, false, true),
            // One family emoji is one character.
            Vector("👨‍👩‍👧‍👦".repeat(4) + "1a", PasswordStrengthLevel.Fair, 0.35, false, true),
            // NBSP is whitespace, not a symbol.
            Vector("abcdefghij 1", PasswordStrengthLevel.Fair, 0.50, true, false),
            // ² is a number.
            Vector("abcdefghijk²!", PasswordStrengthLevel.Good, 0.85, true, true),
            Vector("ＡＢＣｄｅｆ１２３！！！", PasswordStrengthLevel.Strong, 0.95, true, true),
            // ª is both upper and lower case for Swift: mixed case.
            Vector("ªbcdefghijk1!", PasswordStrengthLevel.Strong, 0.95, true, true),
            Vector("𝐀".repeat(11) + "1!", PasswordStrengthLevel.Strong, 0.95, true, true),
            Vector("Straße12345!", PasswordStrengthLevel.Strong, 0.95, true, true),
            // Titlecase ǅ is neither upper nor lower.
            Vector("ǅabcdefghij1!", PasswordStrengthLevel.Good, 0.85, true, true),
            // Ⅷ is a letter and a number, so no symbol.
            Vector("abcdefghijk1Ⅷ", PasswordStrengthLevel.Fair, 0.60, true, false),
            Vector("Ⅷ".repeat(11) + "!a", PasswordStrengthLevel.Strong, 0.95, true, true),
        )
        for (v in vectors) {
            val e = PasswordStrength.evaluate(v.input)
            assertEquals("level of ${v.input}", v.level, e.level)
            assertEquals("score of ${v.input}", v.score, e.score, 1e-9)
            assertEquals("minimum length of ${v.input}", v.minimum, e.hasMinimumLength)
            assertEquals("symbol and number of ${v.input}", v.symbolAndNumber, e.hasSymbolAndNumber)
        }
    }

    private data class Vector(
        val input: String,
        val level: PasswordStrengthLevel,
        val score: Double,
        val minimum: Boolean,
        val symbolAndNumber: Boolean,
    )
}
