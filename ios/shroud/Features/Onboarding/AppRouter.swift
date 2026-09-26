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
    /// Injected; runs the logout wipe and its overlay.
    var deviceWipe: DeviceWipeController?

    /// Human: Server session ≠ messaging unlock. Need phrase-derived keys (or Keychain restore).
    /// Agent: True only after unlockMessages() / cold-start crypto restore.
    var hasUnlockedMessaging = false

    /// True while logout is in flight (disables the Log Out control).
    var isLoggingOut: Bool { deviceWipe?.isPresented == true }

    /// One-shot toast after returning to Welcome (e.g. "Signed out · local data cleared").
    var postAuthToast: String?

    /// Ready for the main shell: API session present **and** local crypto unlocked.
    var isUnlocked: Bool {
        hasUnlockedMessaging
            && sessionController?.isSignedIn == true
            && cryptoController?.isUnlocked == true
    }

    /// The lock screen asked for the main shell to be built, hidden, ahead of its reveal.
    ///
    /// Human: Building Chats (tab shell, lists, glass bar) is ~250 ms of main-thread work. Done
    /// on the reveal's first frame, it swallowed the start of the unlock animation — the mark
    /// jumped instead of lifting away. The lock screen now builds it while it still reads
    /// "Checking…" under the Face ID sheet, and the reveal only fades a view that exists.
    private(set) var prewarmsMainShell = false
    /// Set by the shell itself once it is in the hierarchy.
    var mainShellMounted = false

    /// Whether `RootView` keeps the main shell in the hierarchy: shown, or built and hidden.
    /// Never while the vault is sealed, so a prewarm cannot outlive a lock.
    var mountsMainShell: Bool {
        isUnlocked || (prewarmsMainShell && cryptoController?.isUnlocked == true)
    }

    /// Mounts the shell hidden and waits (briefly) until it has been built.
    func prewarmMainShell() async {
        guard cryptoController?.isUnlocked == true else { return }
        prewarmsMainShell = true
        let deadline = ContinuousClock.now + .milliseconds(600)
        while !mainShellMounted, ContinuousClock.now < deadline {
            try? await Task.sleep(for: .milliseconds(8))
        }
    }

    /// Drops a prewarmed shell the unlock it was for did not use.
    func cancelMainShellPrewarm() {
        prewarmsMainShell = false
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
        prewarmsMainShell = false
        path = []
    }

    /// Cold start: if crypto is already unlocked in memory, enter the main shell.
    /// Otherwise stay on the lock screen so the user can tap Face ID / phrase (no auto biometry prompt).
    func restoreUnlockedSessionIfNeeded() async {
        guard sessionController?.session != nil else { return }
        guard let crypto = cryptoController else {
            hasUnlockedMessaging = false
            return
        }
        // Do not call vault unlock here — automatic Face ID on launch was getting stuck.
        // The lock screen offers Face ID / passcode / phrase for an explicit unlock.
        hasUnlockedMessaging = crypto.isUnlocked
        if crypto.isUnlocked {
            path = []
        }
    }

    /// After a full local wipe (or incomplete login), a Keychain session can remain while
    /// identity/vault keys are gone — that used to trap users on the lock screen forever.
    /// Clears the orphan session so Welcome shows Sign Up / Log In again.
    @discardableResult
    func reconcileOrphanedSessionIfNeeded() async -> Bool {
        guard let session = sessionController?.session else { return false }
        guard let crypto = cryptoController else { return false }
        // Fully unlocked mid-session: nothing to fix.
        if crypto.isUnlocked { return false }
        // A call answered on the lock screen wakes the app while the device is still locked.
        // Identity keys cannot be read then. That used to look like a wiped phone: Shroud
        // signed out and the call it had just accepted was torn down.
        if callController?.isInCall == true { return false }
        if !UIApplication.shared.isProtectedDataAvailable { return false }
        switch crypto.identityPresence(for: session.userID) {
        case .present, .unavailable:
            return false
        case .absent:
            await clearOrphanedLocalSession(
                toast: "Local data was cleared. Sign in or create an account."
            )
            return true
        }
    }

    /// Drops server session + any leftover crypto shell and returns to fresh Welcome.
    private func clearOrphanedLocalSession(toast: String) async {
        postAuthToast = toast
        await sessionController?.logout()
        // Wipe identity leftovers so a half-deleted vault cannot reappear.
        cryptoController?.lock(wipeStore: true)
        messagingController?.stop(wipeDisk: true)
        callController?.clearLocalState()
        hasUnlockedMessaging = false
        path = []
    }

    /// Logs out by clearing this iPhone of the account: server session, messages, media, every
    /// key (identity included — signing in again takes the password and the phrase), settings and
    /// caches, verified, behind `DeviceWipeOverlay`. Returns to Welcome when it is done.
    ///
    /// Human: A pending-wipe marker is written before the first deletion, so a force-quit
    /// mid-way is finished on the next launch rather than leaving a half-cleared device.
    func logOut() {
        guard !isLoggingOut else { return }
        postAuthToast = nil
        deviceWipe?.start(reason: .logout)
    }
}
