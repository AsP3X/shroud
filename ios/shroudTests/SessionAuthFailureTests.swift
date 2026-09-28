import Foundation
import Testing
@testable import shroud

/// Session force-logout policy: only repeated real auth failures (401), never offline; a
/// removed device (`DEVICE_REMOVED`) wipes on the first answer.
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
    func threeFailuresMarkFullWipeAndKeepTheToken() async {
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

        // The token stays until the wipe revokes it. A fourth 401 must not start a second wipe.
        await controller.recordAuthenticationFailure()
        #expect(controller.isSignedIn)
        #expect(controller.bearerToken == "test-token")
        #expect(controller.pendingFullLocalWipe)
        #expect(controller.consecutiveAuthenticationFailures == 0)
        await controller.recordAuthenticationFailure()
        #expect(controller.isSignedIn)
        #expect(controller.consumePendingFullLocalWipe())
        #expect(!controller.consumePendingFullLocalWipe())
    }

    @Test
    func deviceRemovedMarksFullWipeOnFirstAnswer() async {
        let controller = SessionController()
        controller.applySessionForTests(Self.sampleSession)

        controller.recordDeviceRemoved(token: "test-token")
        #expect(controller.pendingFullLocalWipe)
        // Like the 401 streak, the token stays for the wipe to revoke, and a second report
        // (the socket and a request both saying so) does not queue another wipe.
        #expect(controller.bearerToken == "test-token")
        controller.recordDeviceRemoved(token: "test-token")
        await controller.recordAuthenticationFailure()
        #expect(controller.consumePendingFullLocalWipe())
        #expect(!controller.consumePendingFullLocalWipe())
    }

    @Test
    func deviceRemovedIgnoredWhenSignedOut() {
        let controller = SessionController()
        controller.applySessionForTests(nil)
        controller.recordDeviceRemoved(token: "test-token")
        #expect(!controller.pendingFullLocalWipe)
    }

    @Test
    func deviceRemovedAboutAnotherTokenIsIgnored() {
        // A late reply to a request made before this login: the old session was removed, not
        // this one.
        let controller = SessionController()
        controller.applySessionForTests(Self.sampleSession)
        controller.recordDeviceRemoved(token: "an-older-token")
        #expect(!controller.pendingFullLocalWipe)
    }

    @Test
    func interruptedWipeSuppressesASecondOne() {
        let controller = SessionController()
        controller.applySessionForTests(Self.sampleSession)
        controller.beginInterruptedWipe()
        controller.recordDeviceRemoved(token: "test-token")
        #expect(!controller.pendingFullLocalWipe)
    }

    @Test
    func onlyDeviceRemovedCodeIsARemoval() {
        let removed = Data(#"{"error":{"code":"DEVICE_REMOVED","message":"x"}}"#.utf8)
        let plain = Data(#"{"error":{"code":"UNAUTHORIZED","message":"x"}}"#.utf8)
        #expect(APIError.from(data: removed, statusCode: 401).isDeviceRemoval)
        #expect(APIError.from(data: removed, statusCode: 401).isAuthenticationFailure)
        #expect(!APIError.from(data: plain, statusCode: 401).isDeviceRemoval)
        #expect(!APIError.from(data: Data(), statusCode: 401).isDeviceRemoval)
        #expect(!APIError.from(data: removed, statusCode: 403).isDeviceRemoval)
    }

    @Test
    func removalPushIsRecognisedByType() {
        #expect(DeviceRemovalWake.isRemoval(["aps": ["content-available": 1], "type": "device_removed"]))
        #expect(!DeviceRemovalWake.isRemoval(["aps": ["badge": 3]]))
        #expect(!DeviceRemovalWake.isRemoval(["type": "message"]))
    }

    private static let sampleSession = SessionStore.Session(
        token: "test-token",
        userID: UUID(uuidString: "11111111-1111-1111-1111-111111111111")!,
        username: "tester",
        shareCode: nil,
        deviceID: UUID(uuidString: "22222222-2222-2222-2222-222222222222")!
    )
}
