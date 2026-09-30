package de.corespace.shroud.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors `ios/shroudTests/PasswordStrengthEvaluatorTests.swift`. */
class PasswordStrengthTest {
    @Test
    fun empty() {
        val e = PasswordStrength.evaluate("")
        assertEquals(PasswordStrengthLevel.Empty, e.level)
        assertEquals(0.0, e.score, 0.0)
    }

    @Test
    fun levels() {
        assertEquals(PasswordStrengthLevel.Weak, PasswordStrength.evaluate("abc").level)
        assertEquals(PasswordStrengthLevel.Fair, PasswordStrength.evaluate("longpassword").level)
        assertTrue(PasswordStrength.evaluate("longpassword1!").level in setOf(PasswordStrengthLevel.Good, PasswordStrengthLevel.Strong))
        val strong = PasswordStrength.evaluate("LongPassword1!")
        assertEquals(PasswordStrengthLevel.Strong, strong.level)
        assertTrue(strong.score >= 0.8)
    }

    @Test
    fun requirements() {
        assertFalse(PasswordStrength.evaluate("short1!").meetsRequirements)
        assertFalse(PasswordStrength.evaluate("longpassword1").meetsRequirements)
        assertTrue(PasswordStrength.evaluate("longpassword1!").meetsRequirements)
        // Whitespace is not a symbol.
        assertFalse(PasswordStrength.evaluate("long password1").meetsRequirements)
    }
}
