import SwiftUI

/// Placeholder main shell until Chats / Contacts / Settings are implemented.
struct MainTabPlaceholderView: View {
    let router: AppRouter

    var body: some View {
        VStack(spacing: 16) {
            BrandLogoMark(size: 64)
            Text("Chats")
                .font(.system(size: 32, weight: .bold))
                .foregroundStyle(Theme.textPrimary)
            Text("Main tab shell coming soon.")
                .font(.system(size: 16))
                .foregroundStyle(Theme.textSecondary)

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
}
