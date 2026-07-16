import SwiftUI

/// Root navigation shell — routes between onboarding and the main tab shell.
///
/// Onboarding and Main **must not** share one `NavigationStack`: nested stacks under a typed
/// path (e.g. `AppRoute` + `ChatRoute` / `SettingsRoute`) crash with
/// `AnyNavigationPath.Error.comparisonTypeMismatch`.
struct RootView: View {
    @Environment(\.scenePhase) private var scenePhase

    @State private var sessionController = SessionController()
    @State private var cryptoController = CryptoController()
    @State private var messagingController = MessagingController()
    @State private var serverConfig = ServerConfigurationController()
    @State private var router = AppRouter()
    @Namespace private var onboardingNamespace

    var body: some View {
        Group {
            if router.isUnlocked {
                MainTabView(router: router)
            } else {
                onboardingStack
            }
        }
        .environment(\.onboardingNamespace, onboardingNamespace)
        .environment(sessionController)
        .environment(cryptoController)
        .environment(messagingController)
        .environment(serverConfig)
        .task {
            router.sessionController = sessionController
            router.cryptoController = cryptoController
            messagingController.bind(session: sessionController, crypto: cryptoController)
            await sessionController.validateSessionIfNeeded()
            router.restoreUnlockedSessionIfNeeded()
            if router.isUnlocked {
                messagingController.start()
            }
        }
        .onChange(of: sessionController.isSignedIn) { _, signedIn in
            if !signedIn {
                router.hasUnlockedMessaging = false
                cryptoController.lock(wipeStore: false)
                messagingController.stop()
            }
        }
        .onChange(of: router.isUnlocked) { _, unlocked in
            if unlocked {
                // Drop any leftover onboarding path before the main shell appears.
                router.path = []
                messagingController.start()
            } else {
                messagingController.stop()
            }
        }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active, router.isUnlocked else { return }
            messagingController.handleAppBecameActive()
        }
    }

    /// Pre-auth flow only — path elements are always `AppRoute`.
    private var onboardingStack: some View {
        NavigationStack(path: $router.path) {
            WelcomeView(router: router)
                .navigationDestination(for: AppRoute.self) { route in
                    switch route {
                    case .welcome:
                        WelcomeView(router: router)
                    case .signUp:
                        SignUpView(router: router)
                    case .logIn:
                        LogInFlowView(router: router)
                    }
                }
        }
    }
}

#Preview {
    RootView()
}
