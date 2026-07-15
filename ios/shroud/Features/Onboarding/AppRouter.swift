import SwiftUI

/// Onboarding destinations — maps 1:1 to auth screens in `iOS-App.pen`.
enum AppRoute: Hashable {
    case welcome
    case signUp
    case logIn
    case main
}

/// Central navigation state for the pre-auth onboarding flow.
@Observable
@MainActor
final class AppRouter {
    var path: [AppRoute] = []
    /// Injected session; when set, drives unlock + logout.
    var sessionController: SessionController?

    var isUnlocked: Bool {
        sessionController?.isSignedIn == true
    }

    var rootRoute: AppRoute {
        if isUnlocked {
            return .main
        }
        return .welcome
    }

    func showWelcome() {
        path = []
    }

    func showSignUp() {
        // Human: Spring path change pairs with the Zoom navigation transition from the Welcome logo.
        // Agent: WRITES path = [.signUp] inside spring animation.
        withAnimation(.spring(response: 0.45, dampingFraction: 0.86)) {
            path = [.signUp]
        }
    }

    func showLogIn() {
        withAnimation(.spring(response: 0.45, dampingFraction: 0.86)) {
            path = [.logIn]
        }
    }

    func pop() {
        withAnimation(.spring(response: 0.45, dampingFraction: 0.86)) {
            if !path.isEmpty {
                path.removeLast()
            }
        }
    }

    /// After successful register/login, leave onboarding for the main shell.
    func unlockMessages() {
        path = []
    }

    /// Ends the session and returns to Welcome.
    func logOut() {
        Task {
            await sessionController?.logout()
            path = []
        }
    }
}
