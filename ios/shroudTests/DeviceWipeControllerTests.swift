import Foundation
import Testing
@testable import shroud

/// Why the wipe overlay says it is clearing this iPhone: Log Out, an ended session, or a
/// removal (the third reason, as the web client's `DeviceWipeDialog`; android-port P11a).
struct DeviceWipeControllerTests {
    @Test
    func eachReasonLeadsTheSubtitle() {
        #expect(DeviceWipeController.lead(for: .logout, device: "iPhone") == "")
        #expect(DeviceWipeController.lead(for: .sessionEnded, device: "iPhone") == "Your session ended. ")
        #expect(
            DeviceWipeController.lead(for: .removed, device: "iPhone")
                == "This iPhone was removed from your account. "
        )
        #expect(
            DeviceWipeController.lead(for: .removed, device: "iPad")
                == "This iPad was removed from your account. "
        )
        #expect(DeviceWipeController.lead(for: .accountDeleted, device: "iPhone") == "This account was deleted. ")
        #expect(DeviceWipeController.lead(for: .accountDeleted, device: "iPad") == "This account was deleted. ")
        #expect(DeviceWipeController.showsOtherDevicesFootnote(reason: .removed))
        #expect(DeviceWipeController.showsOtherDevicesFootnote(reason: .logout))
        #expect(DeviceWipeController.showsOtherDevicesFootnote(reason: .sessionEnded))
        #expect(!DeviceWipeController.showsOtherDevicesFootnote(reason: .accountDeleted))
    }

    /// The server's answer to the wipe's own logout: `DEVICE_REMOVED` is a removal, any other
    /// answer ends the session, only "could not connect" is offline.
    @Test
    func theLogoutAnswerSaysWhetherThisDeviceWasRemoved() {
        let removed = APIError.server(code: "DEVICE_REMOVED", message: "This device was removed from your account.", statusCode: 401, reason: nil)
        let revoked = APIError.server(code: "UNAUTHORIZED", message: "This session was signed out.", statusCode: 401, reason: nil)
        let serverError = APIError.server(code: "INTERNAL", message: "Something went wrong.", statusCode: 500, reason: nil)
        let deleted = APIError.server(
            code: "DEVICE_REMOVED",
            message: "This account was deleted.",
            statusCode: 401,
            reason: "account_deleted"
        )
        #expect(DeviceWipeController.serverSessionOutcome(of: removed) == .removed)
        #expect(DeviceWipeController.serverSessionOutcome(of: deleted) == .accountDeleted)
        #expect(DeviceWipeController.serverSessionOutcome(of: revoked) == .ended)
        #expect(DeviceWipeController.serverSessionOutcome(of: serverError) == .ended)
        #expect(DeviceWipeController.serverSessionOutcome(of: .transport("offline")) == .offline)
        #expect(DeviceWipeController.serverSessionOutcome(of: .decoding) == .ended)
    }

    /// A removal reaches the wipe as an ended session (`SessionController` keeps no reason);
    /// the server's `DEVICE_REMOVED` turns it into the removal. Log Out stays Log Out.
    @Test
    func onlyAnEndedSessionBecomesARemoval() {
        #expect(DeviceWipeController.reason(.sessionEnded, after: .removed) == .removed)
        #expect(DeviceWipeController.reason(.sessionEnded, after: .ended) == .sessionEnded)
        #expect(DeviceWipeController.reason(.sessionEnded, after: .offline) == .sessionEnded)
        #expect(DeviceWipeController.reason(.logout, after: .removed) == .logout)
        #expect(DeviceWipeController.reason(.removed, after: .offline) == .removed)
        #expect(DeviceWipeController.reason(.sessionEnded, after: .accountDeleted) == .accountDeleted)
        #expect(DeviceWipeController.reason(.removed, after: .accountDeleted) == .accountDeleted)
        #expect(DeviceWipeController.reason(.logout, after: .accountDeleted) == .logout)
        #expect(DeviceWipeController.reason(.accountDeleted, after: .removed) == .accountDeleted)
        #expect(DeviceWipeController.reason(.accountDeleted, after: .offline) == .accountDeleted)
        #expect(DeviceWipeController.reason(.removed, whenStarting: .accountDeleted) == .accountDeleted)
        #expect(DeviceWipeController.reason(.sessionEnded, whenStarting: .accountDeleted) == .accountDeleted)
        #expect(DeviceWipeController.reason(.logout, whenStarting: .accountDeleted) == .logout)
        #expect(DeviceWipeController.reason(.accountDeleted, whenStarting: .removed) == .accountDeleted)
    }

    /// Whole subtitle as it reads while the wipe runs (web-parity §22.7 for the phone wording).
    @Test
    func theRemovalSubtitleReadsAsOneSentencePair() {
        let lead = DeviceWipeController.lead(for: .removed, device: "iPhone")
        #expect(
            "\(lead)Removing everything Shroud stored for @x."
                == "This iPhone was removed from your account. Removing everything Shroud stored for @x."
        )
        let deletedLead = DeviceWipeController.lead(for: .accountDeleted, device: "iPhone")
        #expect(
            "\(deletedLead)Removing everything Shroud stored for @ada."
                == "This account was deleted. Removing everything Shroud stored for @ada."
        )
    }
}
