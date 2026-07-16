import SwiftUI

/// Root navigation shell — routes between onboarding and the main tab shell.
struct RootView: View {
    @State private var sessionController = SessionController()
    @State private var cryptoController = CryptoController()
    @State private var messagingController = MessagingController()
    @State private var serverConfig = ServerConfigurationController()
    @State private var router = AppRouter()
    @Namespace private var onboardingNamespace

    var body: some View {
        NavigationStack(path: $router.path) {
            destination(for: router.rootRoute)
                .navigationDestination(for: AppRoute.self) { route in
                    destination(for: route)
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
            // Validate token, then restore identity keys when Keychain matches.
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
                messagingController.start()
            } else {
                messagingController.stop()
            }
        }
    }

    @ViewBuilder
    private func destination(for route: AppRoute) -> some View {
        switch route {
        case .welcome:
            WelcomeView(router: router)
        case .signUp:
            SignUpView(router: router)
        case .logIn:
            LogInFlowView(router: router)
        case .main:
            MainTabView(router: router)
        }
    }
}

#Preview {
    RootView()
}
