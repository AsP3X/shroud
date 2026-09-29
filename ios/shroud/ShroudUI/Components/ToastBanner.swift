import SwiftUI

/// A transient confirmation or problem report, presented with `.toast(_:)`.
///
/// Human: The modifier clears it on its own after `duration`; screens only ever set it.
/// Every `Toast` value is distinct (`id`), so showing the same text again restarts the timer
/// instead of being cut short by the previous one.
struct Toast: Equatable {
    enum Style: Equatable {
        /// Something the user asked for happened ("Copied", "Chat deleted").
        case success
        /// Neutral notice ("coming soon", "saved, the server gets it later").
        case info
        /// Something went wrong; the text says what.
        case failure
    }

    let id = UUID()
    var message: String
    var style: Style
    var duration: Duration

    init(_ message: String, style: Style = .success, duration: Duration = .seconds(1.8)) {
        self.message = message
        self.style = style
        self.duration = duration
    }

    /// Errors stay a little longer: they are read, not glanced at.
    static func failure(_ message: String, duration: Duration = .seconds(2.4)) -> Toast {
        Toast(message, style: .failure, duration: duration)
    }

    static func info(_ message: String, duration: Duration = .seconds(1.8)) -> Toast {
        Toast(message, style: .info, duration: duration)
    }
}

/// Brief banner shown above the home indicator (e.g. after copying sensitive data).
///
/// Human: A glass capsule — it floats over whatever screen raised it, the way the other
/// transient surfaces (banner, tab bar) do, instead of a white pill with a shadow.
struct ToastBanner: View {
    let message: String
    var style: Toast.Style = .success

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: symbol)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(tint)
                // Draws the symbol on as the toast lands.
                .symbolEffect(.bounce, options: .nonRepeating)
                .contentTransition(.symbolEffect(.replace))
                .accessibilityHidden(true)
            Text(message)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
                .multilineTextAlignment(.leading)
                // Consecutive toasts swap their text in place instead of re-flying the capsule.
                .contentTransition(.opacity)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .glassEffect(.regular, in: .capsule)
    }

    private var symbol: String {
        switch style {
        case .success: "checkmark.circle.fill"
        case .info: "info.circle.fill"
        case .failure: "exclamationmark.circle.fill"
        }
    }

    private var tint: Color {
        switch style {
        case .success: Theme.online
        case .info: Theme.accent
        case .failure: Theme.danger
        }
    }
}

private struct ToastModifier: ViewModifier {
    @Binding var toast: Toast?
    /// Extra lift over bottom chrome the host draws itself (a chat's composer).
    var bottomInset: CGFloat
    @Environment(\.tabBarClearance) private var tabBarClearance
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content.overlay(alignment: .bottom) {
            if let toast {
                ToastBanner(message: toast.message, style: toast.style)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 20 + tabBarClearance + bottomInset)
                    // Informational only: taps go through to the controls underneath.
                    .allowsHitTesting(false)
                    .transition(reduceMotion ? .opacity : Motion.riseFromBottom)
            }
        }
        // Bouncy: a toast is a confirmation, it should feel like it "landed".
        .animation(Motion.respecting(reduceMotion, Motion.bouncy), value: toast)
        .task(id: toast?.id) {
            guard let shown = toast else { return }
            AccessibilityNotification.Announcement(shown.message).post()
            // A newer toast cancels this task; it must not clear its successor.
            do { try await Task.sleep(for: shown.duration) } catch { return }
            if toast?.id == shown.id { toast = nil }
        }
    }
}

extension View {
    /// Presents a transient toast above the bottom safe area (and the floating tab bar); it
    /// clears itself after `toast.duration`. Set the binding to nil to dismiss it early.
    func toast(_ toast: Binding<Toast?>, bottomInset: CGFloat = 0) -> some View {
        modifier(ToastModifier(toast: toast, bottomInset: bottomInset))
    }
}

#Preview {
    VStack(spacing: 12) {
        ToastBanner(message: "Link copied")
        ToastBanner(message: "Drawing coming soon", style: .info)
        ToastBanner(message: "Could not reach the server.", style: .failure)
    }
    .padding()
    .background(Theme.backgroundGrouped)
}
