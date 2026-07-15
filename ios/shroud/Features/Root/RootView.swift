import SwiftUI

/// Root navigation shell — routes between onboarding and the main tab placeholder.
struct RootView: View {
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
            MainTabPlaceholderView(router: router)
        }
    }
}

#Preview {
    RootView()
}
