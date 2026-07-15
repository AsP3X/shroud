import SwiftUI

/// Root navigation shell — routes between onboarding and the main tab shell.
struct RootView: View {
    @State private var sessionController = SessionController()
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
        .environment(serverConfig)
        .onAppear {
            router.sessionController = sessionController
            // Human: Persisted session resumes main; a new login still shows the phrase step.
            // Agent: Must NOT unlock on every isSignedIn flip — that skipped encryption phrase on login.
            router.restoreUnlockedSessionIfNeeded()
        }
        .onChange(of: sessionController.isSignedIn) { _, signedIn in
            if !signedIn {
                // Session cleared (logout / revoke) — drop unlock so Welcome is root again.
                router.hasUnlockedMessaging = false
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
