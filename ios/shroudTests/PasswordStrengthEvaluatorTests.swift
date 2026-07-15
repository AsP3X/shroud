import Testing
@testable import shroud

struct PasswordStrengthEvaluatorTests {
    @Test
    func emptyPasswordIsUnset() {
        let evaluation = PasswordStrengthEvaluator.evaluate("")

        #expect(evaluation.level == .empty)
        #expect(evaluation.score == 0)
        #expect(evaluation.hasMinimumLength == false)
        #expect(evaluation.hasSymbolAndNumber == false)
        #expect(evaluation.meetsRequirements == false)
    }

    @Test
    func shortPasswordIsWeak() {
        let evaluation = PasswordStrengthEvaluator.evaluate("abc")

        #expect(evaluation.level == .weak)
        #expect(evaluation.hasMinimumLength == false)
        #expect(evaluation.hasSymbolAndNumber == false)
    }

    @Test
    func lengthOnlyPasswordIsFair() {
        let evaluation = PasswordStrengthEvaluator.evaluate("longpassword")

        #expect(evaluation.level == .fair)
        #expect(evaluation.hasMinimumLength == true)
        #expect(evaluation.hasSymbolAndNumber == false)
    }

    @Test
    func meetingBothRequirementsIsAtLeastGood() {
        let evaluation = PasswordStrengthEvaluator.evaluate("longpassword1!")

        #expect(evaluation.level == .good || evaluation.level == .strong)
        #expect(evaluation.hasMinimumLength == true)
        #expect(evaluation.hasSymbolAndNumber == true)
        #expect(evaluation.meetsRequirements == true)
    }

    @Test
    func complexPasswordIsStrong() {
        let evaluation = PasswordStrengthEvaluator.evaluate("LongPassword1!")

        #expect(evaluation.level == .strong)
        #expect(evaluation.meetsRequirements == true)
        #expect(evaluation.score >= 0.8)
    }
}
