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
    /// Injected crypto; messaging unlock requires identity material.
    var cryptoController: CryptoController?

    /// Human: Server session ≠ messaging unlock. Need phrase-derived keys (or Keychain restore).
    /// Agent: True only after unlockMessages() / cold-start crypto restore.
    var hasUnlockedMessaging = false

    /// Ready for the main shell: API session present **and** local crypto unlocked.
    var isUnlocked: Bool {
        hasUnlockedMessaging
            && sessionController?.isSignedIn == true
            && cryptoController?.isUnlocked == true
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

    /// After phrase setup (login) or account create (sign up), leave onboarding for the main shell.
    func unlockMessages() {
        guard cryptoController?.isUnlocked == true else { return }
        hasUnlockedMessaging = true
        path = []
    }

    /// Cold start: session + Keychain identity for same user → main without re-entering phrase.
    func restoreUnlockedSessionIfNeeded() {
        guard let session = sessionController?.session else { return }
        guard cryptoController?.restoreIfPossible(for: session.userID) == true else {
            hasUnlockedMessaging = false
            return
        }
        hasUnlockedMessaging = true
        path = []
    }

    /// Ends the server session, locks crypto (keeps Keychain keys for re-login), returns to Welcome.
    func logOut() {
        Task {
            await sessionController?.logout()
            cryptoController?.lock(wipeStore: false)
            hasUnlockedMessaging = false
            path = []
        }
    }
}
