package de.corespace.shroud.core.auth

import java.text.BreakIterator
import java.util.Locale

/** Levels of the Sign Up strength badge; [label] is the badge text (`PasswordStrengthEvaluator.swift:4-10`). */
enum class PasswordStrengthLevel(val label: String) { Empty(""), Weak("Weak"), Fair("Fair"), Good("Good"), Strong("Strong") }

/**
 * Live strength of a new password (`ios/shroud/Services/Auth/PasswordStrengthEvaluator.swift:4-93`;
 * settings-lock addendum PS). Local only: nothing is stored or sent. The server's own rule is looser
 * (≥ 8 bytes, not a common password); Sign Up's bar — 12 characters, a symbol and a number — is the
 * client's.
 *
 * Counts like Swift: a "character" is an extended grapheme cluster, and each property looks at the
 * cluster's first scalar (PS.1). One residual difference is accepted (PS.2): Unihan numerals such as
 * 三 count as numbers on iOS only (their general category is Lo).
 */
data class PasswordStrength(
    val level: PasswordStrengthLevel,
    /** Fill of the strength track, 0…1. */
    val score: Double,
    val hasMinimumLength: Boolean,
    val hasSymbolAndNumber: Boolean,
) {
    /** Sign Up's own bar, stricter than the server's 8 characters (`:20-22`). */
    val meetsRequirements: Boolean get() = hasMinimumLength && hasSymbolAndNumber

    companion object {
        /** `minimumLength` (`PasswordStrengthEvaluator.swift:27`). */
        const val MINIMUM_LENGTH = 12

        /** `evaluate(_:)` (`PasswordStrengthEvaluator.swift:29-89`), the PS.2 port. */
        fun evaluate(password: String): PasswordStrength {
            if (password.isEmpty()) return PasswordStrength(PasswordStrengthLevel.Empty, 0.0, false, false)
            val clusters = graphemeClusters(password)
            val length = clusters.size
            val hasMinimumLength = length >= MINIMUM_LENGTH
            val hasNumber = clusters.any { isNumber(it.codePointAt(0)) }
            // `isSymbol` (`:91-93`): not a letter, not a number, not whitespace.
            val hasSymbol = clusters.any {
                val c = it.codePointAt(0)
                !Character.isAlphabetic(c) && !isNumber(c) && !isWhiteSpace(c)
            }
            val hasSymbolAndNumber = hasNumber && hasSymbol
            val hasMixedCase = clusters.any(::isUppercase) && clusters.any(::isLowercase)

            // Score (`:49-54`), capped at 1.
            var score = 0.0
            if (length >= 8) score += 0.15
            if (hasMinimumLength) score += 0.35
            if (hasSymbolAndNumber) score += 0.35
            if (hasMixedCase) score += 0.1
            if (length >= 16) score += 0.05

            // Level (`:71-89`).
            val level = when {
                hasMinimumLength && hasSymbolAndNumber ->
                    if (hasMixedCase || length >= 14) PasswordStrengthLevel.Strong else PasswordStrengthLevel.Good
                length >= 8 || hasSymbolAndNumber -> PasswordStrengthLevel.Fair
                else -> PasswordStrengthLevel.Weak
            }
            return PasswordStrength(level, minOf(score, 1.0), hasMinimumLength, hasSymbolAndNumber)
        }

        /** Swift's `Character`s: extended grapheme clusters (ICU on Android, JDK ≥ 20 in JVM tests). */
        private fun graphemeClusters(s: String): List<String> {
            val it = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(s) }
            val out = ArrayList<String>()
            var start = it.first()
            var end = it.next()
            while (end != BreakIterator.DONE) {
                out += s.substring(start, end)
                start = end
                end = it.next()
            }
            return out
        }

        /** Swift `isNumber`: Numeric_Type ≠ None, approximated by Nd, Nl and No (PS.1). */
        private fun isNumber(cp: Int): Boolean = Character.getType(cp).let {
            it == Character.DECIMAL_DIGIT_NUMBER.toInt() || it == Character.LETTER_NUMBER.toInt() || it == Character.OTHER_NUMBER.toInt()
        }

        /** Unicode White_Space exactly: Zs, Zl, Zp plus U+0009…U+000D and U+0085 (includes NBSP, unlike `Character.isWhitespace`). */
        private fun isWhiteSpace(cp: Int): Boolean = Character.isSpaceChar(cp) || cp in 0x09..0x0D || cp == 0x85

        private fun isSingle(g: String): Boolean = Character.charCount(g.codePointAt(0)) == g.length

        /** Swift `Character.isCased`: a single cased scalar, or the cluster changes under upper/lower casing. */
        private fun isCased(g: String): Boolean {
            val c = g.codePointAt(0)
            if (isSingle(g) && (Character.isUpperCase(c) || Character.isLowerCase(c) || Character.isTitleCase(c))) return true
            return g != g.uppercase(Locale.ROOT) || g != g.lowercase(Locale.ROOT)
        }

        /** Swift `Character.isUppercase`: a single Uppercase scalar, or a cased cluster equal to its upper-casing. */
        private fun isUppercase(g: String): Boolean =
            (isSingle(g) && Character.isUpperCase(g.codePointAt(0))) || (g == g.uppercase(Locale.ROOT) && isCased(g))

        /** Swift `Character.isLowercase`, symmetric to [isUppercase]. */
        private fun isLowercase(g: String): Boolean =
            (isSingle(g) && Character.isLowerCase(g.codePointAt(0))) || (g == g.lowercase(Locale.ROOT) && isCased(g))
    }
}
