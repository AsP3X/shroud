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
final class AppRouter {
    var path: [AppRoute] = []
    var isUnlocked = false

    var rootRoute: AppRoute {
        if isUnlocked {
            return .main
        }
        return .welcome
    }

    func showWelcome() {
        path = []
        isUnlocked = false
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

    func unlockMessages() {
        isUnlocked = true
        path = []
    }

    /// Ends the session and returns to Welcome — keys would be wiped here in production.
    func logOut() {
        showWelcome()
    }
}
