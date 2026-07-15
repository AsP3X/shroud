import SwiftUI

/// Onboarding destinations — maps 1:1 to auth screens in `iOS-App.pen`.
enum AppRoute: Hashable {
    case welcome
    case signUp
    case logIn
    case enterEncryptionPhrase(username: String)
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
        path = [.signUp]
    }

    func showLogIn() {
        path = [.logIn]
    }

    func pop() {
        if !path.isEmpty {
            path.removeLast()
        }
    }

    func completeLogIn(username: String) {
        path.append(.enterEncryptionPhrase(username: username))
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
