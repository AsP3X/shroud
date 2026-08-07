import Foundation

/// Discrete labels shown in the Sign Up strength badge.
nonisolated enum PasswordStrengthLevel: String, Equatable, Sendable {
    case empty = ""
    case weak = "Weak"
    case fair = "Fair"
    case good = "Good"
    case strong = "Strong"
}

/// Live password strength snapshot for the Sign Up identity card.
nonisolated struct PasswordStrengthEvaluation: Equatable, Sendable {
    let level: PasswordStrengthLevel
    /// Normalized fill amount for the strength track (`0`…`1`).
    let score: Double
    let hasMinimumLength: Bool
    let hasSymbolAndNumber: Bool

    var meetsRequirements: Bool {
        hasMinimumLength && hasSymbolAndNumber
    }
}

/// Evaluates registration passwords against the Sign Up screen requirements.
nonisolated enum PasswordStrengthEvaluator {
    private static let minimumLength = 12

    // Human: Scores passwords for the Sign Up meter using length and character-class checks only.
    // Agent: READS plaintext password locally, RETURNS score/level/requirement flags; never persists or transmits password.
    static func evaluate(_ password: String) -> PasswordStrengthEvaluation {
        guard !password.isEmpty else {
            return PasswordStrengthEvaluation(
                level: .empty,
                score: 0,
                hasMinimumLength: false,
                hasSymbolAndNumber: false
            )
        }

        let hasMinimumLength = password.count >= minimumLength
        let hasNumber = password.contains(where: \.isNumber)
        let hasSymbol = password.contains(where: isSymbol)
        let hasSymbolAndNumber = hasNumber && hasSymbol
        let hasMixedCase = password.contains(where: \.isUppercase)
            && password.contains(where: \.isLowercase)
        let hasLongLength = password.count >= 16

        var score = 0.0
        if password.count >= 8 { score += 0.15 }
        if hasMinimumLength { score += 0.35 }
        if hasSymbolAndNumber { score += 0.35 }
        if hasMixedCase { score += 0.1 }
        if hasLongLength { score += 0.05 }

        let level = strengthLevel(
            password: password,
            hasMinimumLength: hasMinimumLength,
            hasSymbolAndNumber: hasSymbolAndNumber,
            hasMixedCase: hasMixedCase
        )

        return PasswordStrengthEvaluation(
            level: level,
            score: min(score, 1),
            hasMinimumLength: hasMinimumLength,
            hasSymbolAndNumber: hasSymbolAndNumber
        )
    }

    private static func strengthLevel(
        password: String,
        hasMinimumLength: Bool,
        hasSymbolAndNumber: Bool,
        hasMixedCase: Bool
    ) -> PasswordStrengthLevel {
        if hasMinimumLength && hasSymbolAndNumber {
            if hasMixedCase || password.count >= 14 {
                return .strong
            }
            return .good
        }

        if password.count >= 8 || hasSymbolAndNumber {
            return .fair
        }

        return .weak
    }

    private static func isSymbol(_ character: Character) -> Bool {
        !character.isLetter && !character.isNumber && !character.isWhitespace
    }
}
