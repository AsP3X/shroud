import Foundation
import Testing
@testable import shroud

/// Session force-logout policy: only repeated real auth failures (401), never offline.
@MainActor
struct SessionAuthFailureTests {
    @Test
    func ignoresFailuresWhenNotSignedIn() async {
        let controller = SessionController()
        controller.applySessionForTests(nil)
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
        controller.applySessionForTests(Self.sampleSession)
        controller.resetAuthenticationFailures()
        #expect(controller.consecutiveAuthenticationFailures == 0)
    }

    @Test
    func consumePendingFullLocalWipeIsOneShot() {
        let controller = SessionController()
        controller.applySessionForTests(nil)
        #expect(!controller.consumePendingFullLocalWipe())
        #expect(!controller.consumePendingFullLocalWipe())
    }

    @Test
    func thresholdConstantIsAtLeastTwo() {
        #expect(SessionController.authenticationFailureLogoutThreshold >= 2)
    }

    @Test
    func threeFailuresForceLogoutAndWipe() async {
        let controller = SessionController()
        controller.applySessionForTests(Self.sampleSession)
        #expect(controller.isSignedIn)

        await controller.recordAuthenticationFailure()
        #expect(controller.isSignedIn)
        #expect(controller.consecutiveAuthenticationFailures == 1)
        #expect(!controller.pendingFullLocalWipe)

        await controller.recordAuthenticationFailure()
        #expect(controller.isSignedIn)
        #expect(controller.consecutiveAuthenticationFailures == 2)

        await controller.recordAuthenticationFailure()
        #expect(!controller.isSignedIn)
        #expect(controller.pendingFullLocalWipe)
        #expect(controller.consecutiveAuthenticationFailures == 0)
        #expect(controller.consumePendingFullLocalWipe())
        #expect(!controller.consumePendingFullLocalWipe())
    }

    private static let sampleSession = SessionStore.Session(
        token: "test-token",
        userID: UUID(uuidString: "11111111-1111-1111-1111-111111111111")!,
        username: "tester",
        shareCode: nil,
        deviceID: UUID(uuidString: "22222222-2222-2222-2222-222222222222")!,
        deviceName: "Tests"
    )
}
