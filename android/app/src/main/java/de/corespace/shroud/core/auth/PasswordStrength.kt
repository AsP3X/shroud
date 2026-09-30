package de.corespace.shroud.core.auth

/** Levels of the Sign Up strength badge. */
enum class PasswordStrengthLevel(val label: String) { Empty(""), Weak("Weak"), Fair("Fair"), Good("Good"), Strong("Strong") }

/** Live strength of a new password (`PasswordStrengthEvaluator.swift`). Local only. */
data class PasswordStrength(
    val level: PasswordStrengthLevel,
    /** Fill of the strength track, 0…1. */
    val score: Double,
    val hasMinimumLength: Boolean,
    val hasSymbolAndNumber: Boolean,
) {
    /** Sign Up's own bar, stricter than the server's 8 characters. */
    val meetsRequirements: Boolean get() = hasMinimumLength && hasSymbolAndNumber

    companion object {
        private const val MINIMUM_LENGTH = 12

        fun evaluate(password: String): PasswordStrength {
            if (password.isEmpty()) return PasswordStrength(PasswordStrengthLevel.Empty, 0.0, false, false)
            val codePoints = password.codePoints().toArray()
            val length = codePoints.size
            val hasMinimumLength = length >= MINIMUM_LENGTH
            val hasNumber = codePoints.any(Character::isDigit)
            val hasSymbol = codePoints.any { !Character.isLetter(it) && !Character.isDigit(it) && !Character.isWhitespace(it) }
            val hasSymbolAndNumber = hasNumber && hasSymbol
            val hasMixedCase = codePoints.any(Character::isUpperCase) && codePoints.any(Character::isLowerCase)

            var score = 0.0
            if (length >= 8) score += 0.15
            if (hasMinimumLength) score += 0.35
            if (hasSymbolAndNumber) score += 0.35
            if (hasMixedCase) score += 0.1
            if (length >= 16) score += 0.05

            val level = when {
                hasMinimumLength && hasSymbolAndNumber ->
                    if (hasMixedCase || length >= 14) PasswordStrengthLevel.Strong else PasswordStrengthLevel.Good
                length >= 8 || hasSymbolAndNumber -> PasswordStrengthLevel.Fair
                else -> PasswordStrengthLevel.Weak
            }
            return PasswordStrength(level, minOf(score, 1.0), hasMinimumLength, hasSymbolAndNumber)
        }
    }
}
