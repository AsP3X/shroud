import SwiftUI

/// Password strength meter for the Sign Up identity card — maps to `Strength Row` in `iOS-App.pen`.
struct PasswordStrengthMeter: View {
    let evaluation: PasswordStrengthEvaluation

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            strengthHeader
            strengthTrack
            requirementChecks
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.strengthPanelBackground)
    }

    private var strengthHeader: some View {
        HStack {
            Text("PASSWORD STRENGTH")
                .font(.system(size: 11, weight: .semibold))
                .kerning(0.8)
                .foregroundStyle(Theme.textSecondary)

            Spacer(minLength: 0)

            if evaluation.level != .empty {
                strengthBadge
            }
        }
    }

    private var strengthBadge: some View {
        HStack(spacing: 3) {
            Image(systemName: badgeIconName)
                .font(.system(size: 11, weight: .semibold))
            Text(evaluation.level.rawValue)
                .font(.system(size: 11, weight: .bold))
        }
        .foregroundStyle(badgeForeground)
        .padding(.horizontal, 7)
        .padding(.vertical, 2)
        .background(badgeBackground)
        .clipShape(Capsule())
    }

    private var strengthTrack: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 3, style: .continuous)
                    .fill(Theme.strengthTrackBackground)

                RoundedRectangle(cornerRadius: 3, style: .continuous)
                    .fill(trackFillStyle)
                    .frame(width: max(geometry.size.width * evaluation.score, evaluation.level == .empty ? 0 : 8))
            }
        }
        .frame(height: 6)
    }

    private var requirementChecks: some View {
        HStack(spacing: 14) {
            requirementRow(label: "12+ characters", isMet: evaluation.hasMinimumLength)
            requirementRow(label: "Symbol & number", isMet: evaluation.hasSymbolAndNumber)
        }
    }

    private func requirementRow(label: String, isMet: Bool) -> some View {
        HStack(spacing: 4) {
            Image(systemName: isMet ? "checkmark.circle.fill" : "circle")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(isMet ? Theme.online : Theme.textSecondary.opacity(0.45))
            Text(label)
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(Theme.textSecondary)
        }
    }

    private var badgeIconName: String {
        switch evaluation.level {
        case .strong, .good:
            "checkmark.shield.fill"
        case .fair:
            "shield.lefthalf.filled"
        case .weak:
            "exclamationmark.shield.fill"
        case .empty:
            "shield"
        }
    }

    private var badgeForeground: Color {
        switch evaluation.level {
        case .strong, .good:
            Theme.online
        case .fair:
            Theme.warningIcon
        case .weak:
            Theme.danger
        case .empty:
            Theme.textSecondary
        }
    }

    private var badgeBackground: Color {
        switch evaluation.level {
        case .strong, .good:
            Theme.successBackground
        case .fair:
            Theme.warningBackground
        case .weak:
            Theme.danger.opacity(0.12)
        case .empty:
            Theme.backgroundGrouped
        }
    }

    private var trackFillStyle: AnyShapeStyle {
        switch evaluation.level {
        case .strong, .good:
            AnyShapeStyle(
                LinearGradient(
                    colors: [
                        Color(red: 90 / 255, green: 217 / 255, blue: 124 / 255),
                        Color(red: 47 / 255, green: 168 / 255, blue: 91 / 255),
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
        case .fair:
            AnyShapeStyle(Theme.warningIcon)
        case .weak:
            AnyShapeStyle(Theme.danger)
        case .empty:
            AnyShapeStyle(Theme.separator.opacity(0.6))
        }
    }
}
