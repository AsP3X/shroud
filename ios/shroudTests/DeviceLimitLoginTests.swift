import Foundation
import Testing
@testable import shroud

/// Logging in when every device slot is signed in: the `409 DEVICE_LIMIT` answer names the
/// oldest device and the account's identity key. The login moves on to the phrase step without
/// a session, and only a phrase that derives that key offers to log the device out.
@MainActor
struct DeviceLimitLoginTests {
    private static let oldestID = UUID(uuidString: "33333333-3333-3333-3333-333333333333")!
    private static let newerID = UUID(uuidString: "44444444-4444-4444-4444-444444444444")!
    private static let limitMessage =
        "This account already has the maximum number of devices (5). Remove a device and try again."

    /// A fixed phrase and the identity key it derives, standing in for the account's.
    nonisolated private static let phrase = EncryptionPhraseGenerator.generate(entropy: Data(repeating: 7, count: 16))
    nonisolated private static let otherPhrase = EncryptionPhraseGenerator.generate(entropy: Data(repeating: 9, count: 16))
    nonisolated private static let accountKey = try! IdentityKeyMaterial.identityPublicKeyData(fromMnemonic: phrase)
    nonisolated private static let identityKeyJSON = #", "identity_key": "\#(accountKey.base64EncodedString())""#

    private static func limitBody(oldest: String?, identityKey: String? = identityKeyJSON) -> Data {
        let device = oldest.map { #", "oldest_device": \#($0)"# } ?? ""
        return """
        {
          "error": { "code": "DEVICE_LIMIT", "message": "\(limitMessage)" }\(device)\(identityKey ?? "")
        }
        """.data(using: .utf8)!
    }

    private static let oldestJSON = """
    { "id": "33333333-3333-3333-3333-333333333333", "created_at": "2025-03-12T12:00:00Z" }
    """

    private static func device(_ id: UUID, lastSeen: Date? = nil) -> OldestDeviceDTO {
        OldestDeviceDTO(id: id, createdAt: Date(timeIntervalSince1970: 1_741_780_800), lastSeenAt: lastSeen)
    }

    private static func limitError(_ id: UUID, identityKey: Data = accountKey) -> DeviceLimitError {
        DeviceLimitError(oldestDevice: device(id), identityKey: identityKey, message: limitMessage)
    }

    /// A login at the limit with the right phrase checked: the sheet is up.
    private static func confirmationWithSheet() throws -> DeviceLimitConfirmation {
        let confirmation = DeviceLimitConfirmation()
        confirmation.begin(limitError(oldestID), username: "alice", password: "pw")
        try confirmation.checkPhrase(phrase)
        return confirmation
    }

    // MARK: - Parsing the 409

    @Test
    func deviceLimitAnswerNamesTheOldestDevice() {
        let body = Self.limitBody(oldest: """
        { "id": "33333333-3333-3333-3333-333333333333", "created_at": "2025-03-12T12:00:00.123456Z", "last_seen_at": "2026-10-01T08:30:00Z" }
        """)
        let limit = #expect(throws: DeviceLimitError.self) {
            _ = try AuthService.loginAnswer(status: 409, data: body)
        }
        #expect(limit?.oldestDevice.id == Self.oldestID)
        #expect(limit?.oldestDevice.createdAt == ISO8601DateFormatter.date(fromAPI: "2025-03-12T12:00:00.123456Z"))
        #expect(limit?.oldestDevice.lastSeenAt == ISO8601DateFormatter.date(fromAPI: "2026-10-01T08:30:00Z"))
        #expect(limit?.identityKey == Self.accountKey)
        #expect(limit?.message == Self.limitMessage)
    }

    @Test
    func deviceThatWasNeverActiveHasNoLastSeen() {
        let body = Self.limitBody(oldest: """
        { "id": "33333333-3333-3333-3333-333333333333", "created_at": "2025-03-12T12:00:00Z" }
        """)
        let limit = #expect(throws: DeviceLimitError.self) {
            _ = try AuthService.loginAnswer(status: 409, data: body)
        }
        #expect(limit?.oldestDevice.id == Self.oldestID)
        #expect(limit?.oldestDevice.lastSeenAt == nil)
    }

    /// An older server sends no `oldest_device`: the plain error, shown inline as before.
    @Test
    func deviceLimitWithoutOldestDeviceStaysAPlainError() {
        #expect(throws: APIError.server(code: "DEVICE_LIMIT", message: Self.limitMessage, statusCode: 409)) {
            _ = try AuthService.loginAnswer(status: 409, data: Self.limitBody(oldest: nil))
        }
    }

    /// No device of the account published keys: no phrase to check, so no sheet — the inline
    /// error on the credentials step.
    @Test
    func deviceLimitWithoutIdentityKeyStaysAPlainError() {
        let expected = APIError.server(code: "DEVICE_LIMIT", message: Self.limitMessage, statusCode: 409)
        #expect(throws: expected) {
            _ = try AuthService.loginAnswer(status: 409, data: Self.limitBody(oldest: Self.oldestJSON, identityKey: nil))
        }
        #expect(throws: expected) {
            _ = try AuthService.loginAnswer(
                status: 409,
                data: Self.limitBody(oldest: Self.oldestJSON, identityKey: #", "identity_key": "not base64!""#)
            )
        }
    }

    @Test
    func otherLoginFailuresStayAPIErrors() {
        let wrongPassword = """
        { "error": { "code": "INVALID_CREDENTIALS", "message": "Invalid username or password." } }
        """.data(using: .utf8)!
        #expect(throws: APIError.server(
            code: "INVALID_CREDENTIALS",
            message: "Invalid username or password.",
            statusCode: 401
        )) {
            _ = try AuthService.loginAnswer(status: 401, data: wrongPassword)
        }
        #expect(throws: APIError.decoding) {
            _ = try AuthService.loginAnswer(status: 200, data: Data("{}".utf8))
        }
    }

    @Test
    func successfulLoginDecodesTheSession() throws {
        let json = """
        {
          "token": "opaque-token-value",
          "user": { "id": "11111111-1111-1111-1111-111111111111", "share_code": "ABCD234567" },
          "device": { "id": "22222222-2222-2222-2222-222222222222" }
        }
        """.data(using: .utf8)!
        let response = try AuthService.loginAnswer(status: 200, data: json)
        #expect(response.token == "opaque-token-value")
        #expect(response.device.id.uuidString == "22222222-2222-2222-2222-222222222222")
    }

    // MARK: - The retry's body

    @Test
    func retryNamesTheDeviceToReplaceAndKeepsTheAnchor() throws {
        let anchor = UUID(uuidString: "55555555-5555-5555-5555-555555555555")!
        let retry = LoginRequest(usernameHash: "hash", password: "pw", deviceId: anchor, replaceDeviceId: Self.oldestID)
        let object = try #require(
            JSONSerialization.jsonObject(with: JSONEncoder.api.encode(retry)) as? [String: Any]
        )
        #expect((object["replace_device_id"] as? String)?.lowercased() == Self.oldestID.uuidString.lowercased())
        #expect((object["device_id"] as? String)?.lowercased() == anchor.uuidString.lowercased())
    }

    @Test
    func firstAttemptSendsNoReplaceDeviceID() throws {
        let first = LoginRequest(usernameHash: "hash", password: "pw", deviceId: nil)
        let object = try #require(
            JSONSerialization.jsonObject(with: JSONEncoder.api.encode(first)) as? [String: Any]
        )
        #expect(object["replace_device_id"] == nil)
        #expect(object.keys.sorted() == ["password", "username_hash"])
    }

    // MARK: - Phrase first, then the sheet

    @Test
    func limitWaitsForThePhraseWithoutASheet() {
        let confirmation = DeviceLimitConfirmation()
        confirmation.begin(Self.limitError(Self.oldestID), username: "alice", password: "pw")
        #expect(confirmation.isPending)
        #expect(!confirmation.isPresented)
        #expect(confirmation.device?.id == Self.oldestID)
    }

    /// A wrong or invalid phrase gets the phrase step's usual error: no sheet, and nothing can
    /// be retried, so no device is logged out.
    @Test
    func wrongPhraseOpensNoSheetAndSendsNothing() async {
        let confirmation = DeviceLimitConfirmation()
        confirmation.begin(Self.limitError(Self.oldestID), username: "alice", password: "pw")

        #expect(throws: CryptoControllerError.phraseDoesNotMatchAccount) {
            try confirmation.checkPhrase(Self.otherPhrase)
        }
        #expect(throws: BIP39Seed.SeedError.self) {
            try confirmation.checkPhrase(Array(repeating: "ability", count: 12))
        }
        #expect(!confirmation.isPresented)
        #expect(confirmation.isPending)

        var called = false
        let outcome = await confirmation.confirm { _, _, _ in called = true }
        #expect(outcome == nil)
        #expect(!called)
    }

    @Test
    func rightPhraseOpensTheSheet() throws {
        let confirmation = try Self.confirmationWithSheet()
        #expect(confirmation.isPresented)
        #expect(confirmation.verifiedWords == Self.phrase)
    }

    @Test
    func confirmRetriesWithReplaceDeviceIDThenFinishesWithTheSameWords() async throws {
        let confirmation = try Self.confirmationWithSheet()

        var retries: [(String, String, UUID)] = []
        let outcome = await confirmation.confirm { username, password, deviceID in
            // Busy while the retry runs: the sheet can't be cancelled meanwhile.
            #expect(confirmation.isLoggingOut)
            confirmation.cancel()
            #expect(confirmation.isPresented)
            retries.append((username, password, deviceID))
        }

        #expect(outcome == .loggedIn(words: Self.phrase))
        #expect(retries.count == 1)
        #expect(retries.first?.0 == "alice")
        #expect(retries.first?.1 == "pw")
        #expect(retries.first?.2 == Self.oldestID)
        #expect(!confirmation.isPresented)
        #expect(!confirmation.isLoggingOut)
        // The session exists now: the attempt is forgotten.
        #expect(!confirmation.isPending)
        #expect(confirmation.password.isEmpty)
    }

    /// Cancel keeps the phrase step and the pending login; submitting again asks again.
    @Test
    func cancelStaysOnThePhraseStep() throws {
        let confirmation = try Self.confirmationWithSheet()
        confirmation.cancel()
        #expect(!confirmation.isPresented)
        #expect(confirmation.isPending)

        try confirmation.checkPhrase(Self.phrase)
        #expect(confirmation.isPresented)
    }

    @Test
    func backToCredentialsDropsTheAttempt() {
        let confirmation = DeviceLimitConfirmation()
        confirmation.begin(Self.limitError(Self.oldestID), username: "alice", password: "pw")
        confirmation.reset()
        #expect(!confirmation.isPending)
        #expect(confirmation.password.isEmpty)
        #expect(confirmation.device == nil)
    }

    /// The first device is gone and the account filled up again: show the new one, log out nothing.
    @Test
    func aDifferentOldestDeviceSwapsInAndWaitsForTheUser() async throws {
        let confirmation = try Self.confirmationWithSheet()

        let outcome = await confirmation.confirm { _, _, _ in throw Self.limitError(Self.newerID) }

        #expect(outcome == .anotherDevice)
        #expect(confirmation.isPresented)
        #expect(!confirmation.isLoggingOut)
        #expect(confirmation.device?.id == Self.newerID)

        var replaced: [UUID] = []
        let second = await confirmation.confirm { _, _, deviceID in replaced.append(deviceID) }
        #expect(second == .loggedIn(words: Self.phrase))
        #expect(replaced == [Self.newerID])
    }

    /// The retry's answer names another identity key than the checked phrase derives.
    @Test
    func aChangedIdentityKeyClosesWithTheWrongPhraseError() async throws {
        let confirmation = try Self.confirmationWithSheet()
        let otherKey = try IdentityKeyMaterial.identityPublicKeyData(fromMnemonic: Self.otherPhrase)

        let outcome = await confirmation.confirm { _, _, _ in
            throw Self.limitError(Self.newerID, identityKey: otherKey)
        }

        #expect(outcome == .phraseMismatch)
        #expect(!confirmation.isPresented)
        #expect(throws: CryptoControllerError.phraseDoesNotMatchAccount) {
            try confirmation.checkPhrase(Self.phrase)
        }
        try confirmation.checkPhrase(Self.otherPhrase)
        #expect(confirmation.isPresented)
        #expect(confirmation.device?.id == Self.newerID)
    }

    @Test
    func otherErrorsCloseAndGoInline() async throws {
        let cases: [(Error, String)] = [
            (APIError.server(code: "INVALID_CREDENTIALS", message: "Invalid username or password.", statusCode: 401),
             "Invalid username or password."),
            (APIError.transport("The Internet connection appears to be offline."),
             "The Internet connection appears to be offline."),
            (APIError.decoding, "Could not read the server response."),
        ]
        for (error, message) in cases {
            let confirmation = try Self.confirmationWithSheet()
            let outcome = await confirmation.confirm { _, _, _ in throw error }
            #expect(outcome == .failed(message))
            #expect(!confirmation.isPresented)
            #expect(!confirmation.isLoggingOut)
            // Still no session: submitting the phrase again asks again.
            #expect(confirmation.isPending)
        }
    }

    // MARK: - Copy

    @Test
    func cardLinesUseTheDevicesListFormatting() {
        let lastSeen = Date(timeIntervalSince1970: 1_759_307_400)
        #expect(DeviceLimitSheet.lastActiveLine(for: Self.device(Self.oldestID, lastSeen: lastSeen))
            == "Last active \(ChatListFormatting.timeLabel(for: lastSeen))")
        #expect(DeviceLimitSheet.lastActiveLine(for: Self.device(Self.oldestID)) == "Never active")
        let created = Self.device(Self.oldestID).createdAt
        #expect(DeviceLimitSheet.linkedLine(for: Self.device(Self.oldestID))
            == "Linked \(created.formatted(date: .long, time: .omitted))")
        #expect(DeviceLimitSheet.message.contains("logged in on \(DevicesService.deviceLimit) devices"))
    }
}
