import SwiftUI

/// Calls tab — signaling is on the server; UI ships later with WebRTC.
struct CallsView: View {
    var body: some View {
        MainScrollScreen(title: "Calls", collapsesTitle: true) {
            EmptyView()
        } navTrailing: {
            EmptyView()
        } accessory: {
            EmptyView()
        } content: {
            VStack(spacing: 14) {
                Image(systemName: "phone.fill")
                    .font(.system(size: 36, weight: .semibold))
                    .foregroundStyle(Theme.accent.opacity(0.85))
                    .padding(.top, 56)

                Text("Voice & video calls")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)

                Text("Call signaling is ready on the server. The in-app call UI and WebRTC media path ship in a later update.")
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.textSecondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)

                Color.clear.frame(height: 80)
            }
            .frame(maxWidth: .infinity)
        }
        .background(Theme.background)
    }
}

#Preview {
    CallsView()
}
