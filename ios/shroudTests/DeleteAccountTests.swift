import Foundation
import Testing
@testable import shroud

/// Delete Account: `reason` on `DEVICE_REMOVED`, the §3.2 answer map, and the 401-streak exclusion.
struct DeleteAccountTests {
    @Test
    func decodesAccountDeletionReason() {
        let deleted = Data(
            #"{"error":{"code":"DEVICE_REMOVED","message":"This account was deleted.","reason":"account_deleted"}}"#.utf8
        )
        let removed = Data(
            #"{"error":{"code":"DEVICE_REMOVED","message":"This device was removed from your account."}}"#.utf8
        )
        let other = Data(
            #"{"error":{"code":"DEVICE_REMOVED","message":"x","reason":"other"}}"#.utf8
        )
        let deletion = APIError.from(data: deleted, statusCode: 401)
        #expect(deletion.isAccountDeletion)
        #expect(deletion.isDeviceRemoval)
        #expect(deletion == .server(
            code: "DEVICE_REMOVED",
            message: "This account was deleted.",
            statusCode: 401,
            reason: "account_deleted"
        ))
        let plainRemoval = APIError.from(data: removed, statusCode: 401)
        #expect(!plainRemoval.isAccountDeletion)
        #expect(plainRemoval.isDeviceRemoval)
        #expect(!APIError.from(data: other, statusCode: 401).isAccountDeletion)
        #expect(!APIError.from(data: deleted, statusCode: 403).isAccountDeletion)
        #expect(!APIError.server(
            code: "UNAUTHORIZED",
            message: "x",
            statusCode: 401,
            reason: "account_deleted"
        ).isAccountDeletion)
    }

    @Test
    func mapsDeleteAccountAnswers() {
        #expect(DeleteAccount.answer(status: 204, body: Data()) == .deleted)
        let wrong = Data(#"{"error":{"code":"INVALID_CREDENTIALS","message":"Invalid username or password."}}"#.utf8)
        #expect(DeleteAccount.answer(status: 401, body: wrong) == .wrongPassword)
        #expect(DeleteAccount.message(for: .wrongPassword) == "That password isn't right.")
        let removed = Data(#"{"error":{"code":"DEVICE_REMOVED","message":"This device was removed from your account."}}"#.utf8)
        #expect(DeleteAccount.answer(status: 401, body: removed) == .deleted)
        let deleted = Data(
            #"{"error":{"code":"DEVICE_REMOVED","message":"This account was deleted.","reason":"account_deleted"}}"#.utf8
        )
        #expect(DeleteAccount.answer(status: 401, body: deleted) == .deleted)
        let otherReason = Data(#"{"error":{"code":"DEVICE_REMOVED","message":"x","reason":"other"}}"#.utf8)
        #expect(DeleteAccount.answer(status: 401, body: otherReason) == .deleted)
        let limited = Data(#"{"error":{"code":"RATE_LIMITED","message":"slow down"}}"#.utf8)
        #expect(DeleteAccount.answer(status: 429, body: limited) == .tooManyTries)
        #expect(DeleteAccount.message(for: .tooManyTries) == "Too many tries. Try again later.")
        let server = Data(#"{"error":{"code":"INTERNAL","message":"no"}}"#.utf8)
        #expect(DeleteAccount.answer(status: 500, body: server) == .unreachable)
        #expect(DeleteAccount.answer(status: 503, body: Data()) == .unreachable)
        #expect(DeleteAccount.message(for: .unreachable) == "Couldn't reach the server. Your account wasn't deleted.")
        #expect(DeleteAccount.answer(of: APIError.transport("The request timed out.")) == .unreachable)
        #expect(DeleteAccount.answer(of: URLError(.timedOut)) == .unreachable)
        #expect(DeleteAccount.answer(of: APIError.decoding) == .unreachable)
    }

    @Test
    func copyMatchesThePlan() {
        #expect(DeleteAccountCopy.rowTitle == "Delete Account")
        #expect(DeleteAccountCopy.rowSubtitle == "Deletes your account and erases it from every device.")
        #expect(DeleteAccountCopy.heading == "Delete your account?")
        #expect(DeleteAccountCopy.consequences.map(\.text) == [
            "Your messages are replaced with \u{201C}Message deleted\u{201D} for everyone.",
            "Contacts who let you clear chats for them lose those chats. Everyone else keeps their own messages.",
            "Your contacts, Saved Messages, photos, files and call history are deleted.",
            "Your username and share code are released, so someone else can take them.",
            "Every device signed in to this account is signed out and erased.",
        ])
        #expect(DeleteAccountCopy.confirmPrompt == "This can't be undone. Enter your password to confirm.")
        #expect(DeleteAccountCopy.passwordLabel == "Password")
        #expect(DeleteAccountCopy.passwordPlaceholder == "Your account password")
        #expect(DeleteAccountCopy.deleting == "Deleting\u{2026}")
        #expect(DeleteAccountCopy.cancel == "Cancel")
        #expect(DeleteAccountCopy.accountDeletedLead == "This account was deleted. ")
        #expect(DeleteAccountCopy.wrongPassword.unicodeScalars.contains { $0 == "'" })
        #expect(!DeleteAccountCopy.wrongPassword.unicodeScalars.contains { $0 == "\u{2019}" })
    }

    @Test
    func pushReasonDoesNotSkipTheConfirm() {
        #expect(DeviceRemovalWake.wipeReason(confirmed: .unanswered, pushReason: "account_deleted") == nil)
        #expect(DeviceRemovalWake.wipeReason(confirmed: .present, pushReason: "account_deleted") == nil)
        #expect(DeviceRemovalWake.wipeReason(confirmed: .removed, pushReason: "account_deleted") == .removed)
        #expect(DeviceRemovalWake.wipeReason(confirmed: .removed, pushReason: nil) == .removed)
        #expect(DeviceRemovalWake.wipeReason(confirmed: .accountDeleted, pushReason: nil) == .accountDeleted)
        #expect(DeviceRemovalWake.wipeReason(confirmed: .accountDeleted, pushReason: "account_deleted") == .accountDeleted)
    }
}

/// A wrong password on `DELETE /auth/account` must not move the 401 streak. A normal 401 still
/// does, and `DEVICE_REMOVED` still wipes. Serialized: both tests write `SessionAuthBridge.controller`.
@Suite(.serialized)
@MainActor
struct DeleteAccountStreakTests {
    @Test
    func threeWrongDeletePasswordsDoNotWipe() async {
        let controller = SessionController()
        controller.applySessionForTests(Self.sampleSession)
        SessionAuthBridge.controller = controller
        defer { SessionAuthBridge.controller = nil }

        let wrong = Data(#"{"error":{"code":"INVALID_CREDENTIALS","message":"Invalid username or password."}}"#.utf8)
        for _ in 0 ..< 3 {
            APIClient.noteOutcome(
                method: "DELETE",
                path: "auth/account",
                status: 401,
                data: wrong,
                bearerToken: "test-token"
            )
        }
        await Self.flushNotes()
        #expect(controller.consecutiveAuthenticationFailures == 0)
        #expect(!controller.pendingFullLocalWipe)
        #expect(!controller.sessionEndedByAccountDeletion)

        let plain = Data(#"{"error":{"code":"UNAUTHORIZED","message":"nope"}}"#.utf8)
        #expect(APIClient.sessionAuthNote(
            method: "GET", path: "auth/me", status: 401, data: plain, hasBearer: true
        ) == .authenticationFailure)
        APIClient.noteOutcome(method: "GET", path: "auth/me", status: 401, data: plain, bearerToken: "test-token")
        await Self.flushNotes()
        #expect(controller.consecutiveAuthenticationFailures == 1)
        #expect(!controller.pendingFullLocalWipe)
    }

    @Test
    func deviceRemovedStillWipesAndAccountDeletionIsSeparate() async {
        let removed = Data(#"{"error":{"code":"DEVICE_REMOVED","message":"This device was removed from your account."}}"#.utf8)
        let deleted = Data(
            #"{"error":{"code":"DEVICE_REMOVED","message":"This account was deleted.","reason":"account_deleted"}}"#.utf8
        )

        let removal = SessionController()
        removal.applySessionForTests(Self.sampleSession)
        SessionAuthBridge.controller = removal
        defer { SessionAuthBridge.controller = nil }
        #expect(APIClient.sessionAuthNote(
            method: "GET", path: "chats", status: 401, data: removed, hasBearer: true
        ) == .deviceRemoved)
        APIClient.noteOutcome(method: "GET", path: "chats", status: 401, data: removed, bearerToken: "test-token")
        await Self.flushNotes()
        #expect(removal.pendingFullLocalWipe)
        #expect(removal.sessionEndedByDeviceRemoval)
        #expect(!removal.sessionEndedByAccountDeletion)

        let account = SessionController()
        account.applySessionForTests(Self.sampleSession)
        SessionAuthBridge.controller = account
        #expect(APIClient.sessionAuthNote(
            method: "GET", path: "auth/me", status: 401, data: deleted, hasBearer: true
        ) == .accountDeleted)
        #expect(APIClient.sessionAuthNote(
            method: "delete", path: "/auth/account", status: 401, data: removed, hasBearer: true
        ) == .accountDeleted)
        APIClient.noteOutcome(method: "GET", path: "auth/me", status: 401, data: deleted, bearerToken: "test-token")
        await Self.flushNotes()
        #expect(account.pendingFullLocalWipe)
        #expect(account.sessionEndedByAccountDeletion)
    }

    @Test
    func wrongPasswordOnAnotherCallStillCounts() {
        let wrong = Data(#"{"error":{"code":"INVALID_CREDENTIALS","message":"Invalid username or password."}}"#.utf8)
        #expect(APIClient.sessionAuthNote(
            method: "POST", path: "auth/login", status: 401, data: wrong, hasBearer: true
        ) == .authenticationFailure)
        #expect(APIClient.sessionAuthNote(
            method: "DELETE", path: "auth/account", status: 401, data: wrong, hasBearer: false
        ) == .none)
        #expect(APIClient.isDeleteAccountWrongPassword(
            method: "DELETE",
            path: "auth/account",
            error: .server(code: "INVALID_CREDENTIALS", message: "x", statusCode: 401, reason: nil)
        ))
        #expect(!APIClient.isDeleteAccountWrongPassword(
            method: "GET",
            path: "auth/me",
            error: .server(code: "INVALID_CREDENTIALS", message: "x", statusCode: 401, reason: nil)
        ))
    }

    private static func flushNotes() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            Task { @MainActor in
                continuation.resume()
            }
        }
    }

    private static let sampleSession = SessionStore.Session(
        token: "test-token",
        userID: UUID(uuidString: "11111111-1111-1111-1111-111111111111")!,
        username: "tester",
        shareCode: nil,
        deviceID: UUID(uuidString: "22222222-2222-2222-2222-222222222222")!
    )
}
