import SwiftUI

/// Placeholder main shell until Chats / Contacts / Settings are implemented.
struct MainTabPlaceholderView: View {
    let router: AppRouter

    @Environment(SessionController.self) private var sessionController

    var body: some View {
        VStack(spacing: 16) {
            BrandLogoMark(size: 64)
            Text("Chats")
                .font(.system(size: 32, weight: .bold))
                .foregroundStyle(Theme.textPrimary)

            if let username = sessionController.username {
                Text("@\(username)")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Theme.accent)
            }

            Text("Signed in against the local API. Chats UI coming soon.")
                .font(.system(size: 16))
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)

            SecondaryButton(title: "Log Out") {
                router.logOut()
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.backgroundGrouped)
    }
}

#Preview {
    MainTabPlaceholderView(router: AppRouter())
        .environment(SessionController())
}
