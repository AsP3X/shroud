import SwiftUI

/// Welcome screen — maps to `Welcome` in `iOS-App.pen`.
struct WelcomeView: View {
    let router: AppRouter

    var body: some View {
        GroupedScreen {
            VStack(spacing: 0) {
                ScrollView {
                    VStack(spacing: 28) {
                        hero
                        featureTiles
                    }
                    .screenContent()
                    .padding(.top, 24)
                }

                VStack(spacing: 12) {
                    PrimaryButton(title: "Start Messaging") {
                        router.showSignUp()
                    }
                    SecondaryButton(title: "Log In") {
                        router.showLogIn()
                    }
                }
                .screenContent()
                .padding(.vertical, 12)
            }
        }
        .navigationBarHidden(true)
    }

    private var hero: some View {
        VStack(spacing: 16) {
            BrandLogoMark(size: 80)
                .onboardingHeroSource()
                .shadow(color: Theme.accent.opacity(0.22), radius: 16, y: 10)
            VStack(spacing: 8) {
                Text("Private messaging,\nfully encrypted")
                    .font(.system(size: 32, weight: .bold))
                    .foregroundStyle(Theme.textPrimary)
                    .multilineTextAlignment(.center)
                Text("No phone number. No email. Just your username and a 12-word encryption phrase.")
                    .font(.system(size: 15))
                    .foregroundStyle(Theme.textSecondary)
                    .multilineTextAlignment(.center)
                    .lineSpacing(4)
            }
        }
        .frame(maxWidth: .infinity)
    }

    private var featureTiles: some View {
        VStack(spacing: 10) {
            featureTile(icon: "lock.fill", title: "End-to-end encrypted", subtitle: "Messages decrypt only on your devices")
            featureTile(icon: "waveform", title: "Voice messages", subtitle: "Encrypted audio with on-device transcription")
            featureTile(icon: "phone.fill", title: "Secure calls", subtitle: "Voice and video with WebRTC encryption")
        }
    }

    private func featureTile(icon: String, title: String, subtitle: String) -> some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(Theme.accentSoft)
                    .frame(width: 40, height: 40)
                Image(systemName: icon)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Theme.accent)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
                Text(subtitle)
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
            }
            Spacer(minLength: 0)
        }
        .padding(14)
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

#Preview {
    NavigationStack {
        WelcomeView(router: AppRouter())
    }
}
