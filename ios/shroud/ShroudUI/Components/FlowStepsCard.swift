import SwiftUI

/// Reusable two-step flow card from the Log In / Enter Phrase designs.
struct FlowStepsCard: View {
    enum Style {
        case standard
        case step1Expanded
    }

    let style: Style

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            stepRow(
                number: 1,
                title: "Log in to your account",
                description: style == .step1Expanded ? "Verify username and password on this device" : nil,
                isActive: true
            )
            stepRow(
                number: 2,
                title: "Enter encryption phrase",
                description: style == .step1Expanded ? nil : "Required when returning from logout",
                isActive: false
            )
            if style == .standard {
                Text("Encryption phrase is required when returning from logout.")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.textSecondary)
            }
        }
        .padding(14)
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func stepRow(number: Int, title: String, description: String?, isActive: Bool) -> some View {
        HStack(alignment: .top, spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(isActive ? Theme.accent : Theme.accentSoft)
                    .frame(width: 24, height: 24)
                Text("\(number)")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(isActive ? Color.white : Theme.accent)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
                if let description {
                    Text(description)
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.textSecondary)
                }
            }
        }
    }
}
