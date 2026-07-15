import SwiftUI

/// Compact horizontal two-step progress stepper for the Log In flow.
/// Human: Replaces the old FlowStepsCard — the active pill slides from "Account" to "Phrase" as the single login screen morphs between its two states.
/// Agent: READS activeStep only; purely visual, no side effects. Animate by wrapping the state change in withAnimation at the call site.
struct FlowStepper: View {
    let activeStep: Int

    var body: some View {
        HStack(spacing: 8) {
            stepChip(number: 1, label: "Account", isActive: activeStep == 1, isComplete: activeStep > 1)

            RoundedRectangle(cornerRadius: 1)
                .fill(activeStep > 1 ? Theme.accent : Theme.separator)
                .frame(width: 24, height: 2)

            stepChip(number: 2, label: "Phrase", isActive: activeStep == 2, isComplete: false)
        }
        .frame(maxWidth: .infinity)
    }

    private func stepChip(number: Int, label: String, isActive: Bool, isComplete: Bool) -> some View {
        HStack(spacing: 6) {
            ZStack {
                Circle()
                    .fill(isActive || isComplete ? Theme.accent : Theme.background)
                    .frame(width: 20, height: 20)
                if isComplete {
                    Image(systemName: "checkmark")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(Color.white)
                } else {
                    Text("\(number)")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(isActive ? Color.white : Theme.textSecondary)
                }
            }
            Text(label)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(isActive ? Theme.accent : Theme.textSecondary)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(isActive ? Theme.accentSoft : Color.clear)
        .clipShape(Capsule())
    }
}
