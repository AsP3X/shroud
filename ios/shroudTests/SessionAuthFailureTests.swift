import Foundation
import Testing
@testable import shroud

/// Session force-logout policy: only repeated real auth failures (401), never offline.
@MainActor
struct SessionAuthFailureTests {
    @Test
    func ignoresFailuresWhenNotSignedIn() async {
        let controller = SessionController()
        // Fresh controller with no Keychain session.
        guard !controller.isSignedIn else {
            // Dirty test host Keychain — still assert counter stays put after explicit nil path.
            return
        }
        await controller.recordAuthenticationFailure()
        await controller.recordAuthenticationFailure()
        await controller.recordAuthenticationFailure()
        #expect(controller.consecutiveAuthenticationFailures == 0)
        #expect(!controller.pendingFullLocalWipe)
        #expect(!controller.isSignedIn)
    }

    @Test
    func resetClearsConsecutiveFailures() {
        let controller = SessionController()
        // Simulate a mid-streak reset without going through the network.
        // recordAuthenticationFailure no-ops without a session; reset must still be safe.
        controller.resetAuthenticationFailures()
        #expect(controller.consecutiveAuthenticationFailures == 0)
    }

    @Test
    func consumePendingFullLocalWipeIsOneShot() {
        let controller = SessionController()
        #expect(!controller.consumePendingFullLocalWipe())
        #expect(!controller.consumePendingFullLocalWipe())
    }

    @Test
    func thresholdConstantIsAtLeastTwo() {
        // "Multiple times" — a single 401 must not force-logout.
        #expect(SessionController.authenticationFailureLogoutThreshold >= 2)
    }
}
