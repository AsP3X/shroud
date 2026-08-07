import SwiftUI

/// Stands in for a list's empty state when the *load* failed rather than the list being empty.
///
/// Human: "No chats yet" is a lie when the server is unreachable — say what went wrong and
/// offer a way to try again.
/// Agent: Retry runs the caller's forced refresh; the spinner is local to the button.
struct ListLoadErrorView: View {
    let title: String
    let message: String
    let retry: () async -> Void

    @State private var isRetrying = false

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "antenna.radiowaves.left.and.right.slash")
                .font(.system(size: 32, weight: .semibold))
                .foregroundStyle(Theme.danger.opacity(0.85))
                .padding(.bottom, 6)

            Text(title)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)

            Text(message)
                .font(.system(size: 14))
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)

            Button {
                guard !isRetrying else { return }
                Task {
                    isRetrying = true
                    await retry()
                    isRetrying = false
                }
            } label: {
                HStack(spacing: 8) {
                    if isRetrying {
                        ProgressView()
                            .controlSize(.small)
                            .tint(Theme.accent)
                    }
                    Text(isRetrying ? "Retrying…" : "Try Again")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Theme.accent)
                }
                .frame(height: 32)
                .contentShape(Rectangle())
            }
            .disabled(isRetrying)
            .padding(.top, 4)
            .pressable(scale: 0.94)
            .accessibilityLabel("Try again")
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 48)
        .padding(.horizontal, 24)
        .transition(.opacity.combined(with: .offset(y: 8)))
    }
}

#Preview {
    ListLoadErrorView(
        title: "Can't reach the server",
        message: "Could not connect to the server.",
        retry: {}
    )
    .background(Theme.background)
}
