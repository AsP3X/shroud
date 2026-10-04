import SwiftUI

/// Settings list row with tinted icon tile — maps to Settings rows in `iOS-App.pen`.
struct SettingsRowView: View {
    let title: String
    let systemImage: String
    let iconBackground: Color
    /// Secondary text before the chevron (e.g. a count).
    var value: String? = nil
    /// Shows a small accent dot before the value (something new behind the row, like an app
    /// update); the text is what VoiceOver reads for the dot.
    var badge: String? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        Button {
            action?()
        } label: {
            HStack(spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(iconBackground)
                        .frame(width: 30, height: 30)
                    Image(systemName: systemImage)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Color.white)
                }
                // Decorative, like the chevron: VoiceOver reads the title, value or "Soon".
                .accessibilityHidden(true)
                Text(title)
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.textPrimary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if let badge, action != nil {
                    Circle()
                        .fill(Theme.accent)
                        .frame(width: 8, height: 8)
                        .accessibilityElement()
                        .accessibilityLabel(badge)
                }
                if let value, action != nil {
                    Text(value)
                        .font(.system(size: 16))
                        .foregroundStyle(Theme.textSecondary)
                }
                if action != nil {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Theme.chevron)
                        .accessibilityHidden(true)
                } else {
                    Text("Soon")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(Theme.textSecondary)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        // Rows highlight rather than scale — a full-width scale reads as a layout glitch.
        .buttonStyle(HighlightRowButtonStyle())
        .disabled(action == nil)
        .opacity(action == nil ? 0.72 : 1)
    }
}

#Preview {
    VStack(spacing: 0) {
        SettingsRowView(
            title: "Saved Messages",
            systemImage: "bookmark.fill",
            iconBackground: Color(red: 46 / 255, green: 143 / 255, blue: 224 / 255)
        )
        SettingsRowView(
            title: "Privacy and Security",
            systemImage: "lock.fill",
            iconBackground: Theme.textSecondary
        )
    }
    .background(Theme.background)
    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    .padding()
    .background(Theme.backgroundGrouped)
}
