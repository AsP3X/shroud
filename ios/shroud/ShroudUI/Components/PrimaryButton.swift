import SwiftUI

/// Full-width capsule primary action from the design system recipe.
///
/// Human: The label swaps through a numeric-text style content transition so changing the
/// title mid-flow (e.g. "Continue" → "Creating…") morphs instead of popping.
struct PrimaryButton: View {
    let title: String
    var showsArrow = true
    /// Shows an inline spinner and blocks taps while work is in flight.
    var isLoading = false
    /// Fill and glow. `Theme.danger` for a destructive confirmation.
    var tint: Color = Theme.accent
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if isLoading {
                    ProgressView()
                        .tint(Color.white)
                        .transition(Motion.iconSwap)
                }
                Text(title)
                    .font(.system(size: 17, weight: .semibold))
                    .contentTransition(.opacity)
                if showsArrow, !isLoading {
                    Image(systemName: "arrow.right")
                        .font(.system(size: 18, weight: .semibold))
                        .transition(Motion.iconSwap)
                }
            }
            .foregroundStyle(Color.white)
            .frame(maxWidth: .infinity)
            .frame(height: 54)
            .background(tint)
            .clipShape(Capsule())
            .shadow(color: tint.opacity(0.25), radius: 20, y: 8)
            .animation(Motion.snappy, value: isLoading)
            .animation(Motion.snappy, value: title)
        }
        // Hero CTA: a slightly deeper press than compact controls, with a medium tick.
        .pressable(scale: 0.975, dimming: 0.05, haptic: .medium)
        .disabled(isLoading)
        .accessibilityLabel(title)
    }
}

#Preview {
    VStack(spacing: 16) {
        PrimaryButton(title: "Get Started", action: {})
        PrimaryButton(title: "Creating account…", isLoading: true, action: {})
    }
    .padding()
    .background(Theme.backgroundGrouped)
}
