import SwiftUI

/// Onboarding push destinations only (not the main shell).
/// Human: `main` is presented by `RootView` outside this stack — never push it onto `path`.
enum AppRoute: Hashable {
    case welcome
    case signUp
    case logIn
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
    /// Injected messaging; local caches are wiped on logout.
    var messagingController: MessagingController?
    /// Injected calls; recent list is wiped on logout.
    var callController: CallController?

    /// Human: Server session ≠ messaging unlock. Need phrase-derived keys (or Keychain restore).
    /// Agent: True only after unlockMessages() / cold-start crypto restore.
    var hasUnlockedMessaging = false

    /// True while logout is in flight (disables the Log Out control).
    private(set) var isLoggingOut = false

    /// One-shot toast after returning to Welcome (e.g. "Signed out · local data cleared").
    var postAuthToast: String?

    /// Ready for the main shell: API session present **and** local crypto unlocked.
    var isUnlocked: Bool {
        hasUnlockedMessaging
            && sessionController?.isSignedIn == true
            && cryptoController?.isUnlocked == true
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

    /// Ends the server session, wipes local message caches, locks crypto (identity kept for re-login).
    ///
    /// Local session + caches clear first so a force-quit mid-network never leaves you signed in.
    func logOut() {
        guard !isLoggingOut else { return }
        isLoggingOut = true
        postAuthToast = nil

        Task {
            // Clears Keychain session immediately; server revoke is best-effort in the background.
            await sessionController?.logout()

            // Publish feedback as soon as the session is gone so Welcome can show it on appear.
            postAuthToast = "Signed out · local data cleared"

            // RootView also stops messaging on `isSignedIn` change; call again so endpoint-change
            // logout paths that only use the router still wipe caches.
            messagingController?.stop()
            callController?.clearLocalState()
            // Keep identity material so the same user can unlock with their phrase again.
            cryptoController?.lock(wipeStore: false)
            hasUnlockedMessaging = false
            path = []
            isLoggingOut = false
        }
    }
}
