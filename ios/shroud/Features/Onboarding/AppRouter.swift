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

    /// Human: Server session ≠ messaging unlock. Login must enter the 12-word phrase before main.
    /// Agent: Only true after unlockMessages() or cold-start restore of an existing session.
    var hasUnlockedMessaging = false

    /// Ready for the main shell: API session present **and** local phrase unlock completed.
    var isUnlocked: Bool {
        hasUnlockedMessaging && sessionController?.isSignedIn == true
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

    /// After phrase step (login) or account create (sign up), leave onboarding for the main shell.
    func unlockMessages() {
        hasUnlockedMessaging = true
        path = []
    }

    /// Cold start: Keychain already has a session — treat as previously unlocked on this device.
    /// Fresh login still requires the encryption-phrase step before calling `unlockMessages()`.
    func restoreUnlockedSessionIfNeeded() {
        guard sessionController?.isSignedIn == true else { return }
        hasUnlockedMessaging = true
        path = []
    }

    /// Ends the session and returns to Welcome.
    func logOut() {
        Task {
            await sessionController?.logout()
            hasUnlockedMessaging = false
            path = []
        }
    }
}
