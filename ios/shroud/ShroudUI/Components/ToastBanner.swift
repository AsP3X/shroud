import SwiftUI

/// Brief confirmation banner shown above the home indicator (e.g. after copying sensitive data).
struct ToastBanner: View {
    let message: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Theme.online)
                // Draws the checkmark on as the toast lands.
                .symbolEffect(.bounce, options: .nonRepeating)
            Text(message)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
                // Consecutive toasts swap their text in place instead of re-flying the capsule.
                .contentTransition(.opacity)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Theme.background)
        .clipShape(Capsule())
        .shadow(color: Theme.textPrimary.opacity(0.12), radius: 16, y: 8)
    }
}

private struct ToastModifier: ViewModifier {
    @Binding var message: String?

    func body(content: Content) -> some View {
        content.overlay(alignment: .bottom) {
            if let message {
                ToastBanner(message: message)
                    .padding(.bottom, 20)
                    .transition(Motion.riseFromBottom)
            }
        }
        // Bouncy: a toast is a confirmation, it should feel like it "landed".
        .animation(Motion.bouncy, value: message)
    }
}

extension View {
    /// Presents a transient toast above the bottom safe area; set `message` to nil to dismiss.
    func toast(_ message: Binding<String?>) -> some View {
        modifier(ToastModifier(message: message))
    }
}
