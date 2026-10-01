import Foundation
import Testing
@testable import shroud

struct ContactInviteParserTests {
    @Test
    func parsesUUID() {
        let id = UUID(uuidString: "11111111-1111-1111-1111-111111111111")!
        #expect(ContactInviteParser.parse(id.uuidString) == .userID(id))
    }

    @Test
    func parsesShareCode() {
        #expect(ContactInviteParser.parse("abcd-2345-67") == .shareCode("ABCD234567"))
        #expect(ContactInviteParser.parse("  XYZW987654  ") == .shareCode("XYZW987654"))
    }

    @Test
    func parsesUsername() {
        #expect(ContactInviteParser.parse("alice_1") == .username("alice_1"))
        #expect(ContactInviteParser.parse("@Bob") == .username("bob"))
    }

    @Test
    func parsesShareLink() {
        #expect(
            ContactInviteParser.parse("https://shroud.corespace.de/u/ABCD234567")
                == .shareCode("ABCD234567")
        )
        #expect(
            ContactInviteParser.parse("shroud.corespace.de/u/ABCD234567")
                == .shareCode("ABCD234567")
        )
    }

    @Test
    func rejectsEmptyAndTooShort() {
        #expect(ContactInviteParser.parse("") == nil)
        #expect(ContactInviteParser.parse("ab") == nil)
        // Too short for a share code and invalid as a username (symbols).
        #expect(ContactInviteParser.parse("!!!") == nil)
    }

    @Test
    func buildsOfficialShareURL() {
        let url = ContactInviteParser.shareURL(
            code: "abcd234567",
            configuration: ServerConfiguration(
                mode: .official,
                host: "ignored",
                port: "8080",
                apiPath: "/api/v1",
                useHTTPS: true
            )
        )
        #expect(url.absoluteString == "https://shroud.corespace.de/u/ABCD234567")
    }

    // MARK: - Shared goldens (Android `ContactInviteParserTest` reproduces every line)

    /// Generated from this parser (android-port-specs contacts §4.1). The `noahvorberg` rows
    /// are why `MessagingController.lookUpShareCode` falls back to a username on a 404.
    @Test
    func matchesTheSharedParseGoldens() {
        let id = UUID(uuidString: "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")!
        let goldens: [(String, ContactInviteParser.Invite?)] = [
            ("11111111-1111-1111-1111-111111111111", .userID(UUID(uuidString: "11111111-1111-1111-1111-111111111111")!)),
            ("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d", .userID(id)),
            ("  9B1DEB4D-3B7D-4BAD-9BDD-2B0D7B3DCB6D  ", .userID(id)),
            ("abcd-2345-67", .shareCode("ABCD234567")),
            ("  XYZW987654  ", .shareCode("XYZW987654")),
            ("alice_1", .username("alice_1")),
            ("@Bob", .username("bob")),
            ("https://shroud.corespace.de/u/ABCD234567", .shareCode("ABCD234567")),
            ("shroud.corespace.de/u/ABCD234567", .shareCode("ABCD234567")),
            ("https://shroud.corespace.de/u/abcd-2345-67/", .shareCode("ABCD234567")),
            ("http://192.168.1.20:8080/u/ABCD234567", .shareCode("ABCD234567")),
            ("example.com/u/ABCD234567", .shareCode("ABCD234567")),
            ("https://example.com/u/ABC", nil),
            ("https://shroud.corespace.de/api/v1/users/9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d", .userID(id)),
            ("https://example.com/user/ABCD234567", nil),
            ("https://example.com/ABCD234567", nil),
            ("", nil),
            ("   ", nil),
            ("ab", nil),
            ("!!!", nil),
            ("noahvorberg", .shareCode("NOAHVORBERG")),
            ("@noahvorberg", .shareCode("NOAHVORBERG")),
            ("noah_vorberg", .username("noah_vorberg")),
            ("ABCDEFGH", .shareCode("ABCDEFGH")),
            ("ABCDEFG", .username("abcdefg")),
            ("ABCDEFGHIJKLMNOPQ", .username("abcdefghijklmnopq")),
            ("abcdefghijklmnopq", .username("abcdefghijklmnopq")),
            ("@@ABCD234567@@", .shareCode("ABCD234567")),
            ("ab cd 23 45 67", .shareCode("ABCD234567")),
            ("Jane Cooper", .shareCode("JANECOOPER")),
            ("a-b", nil),
            ("shroud.app", nil),
            ("müllerhans", .shareCode("MÜLLERHANS")),
        ]
        for (input, expected) in goldens {
            #expect(ContactInviteParser.parse(input) == expected, "parse(\(input.debugDescription))")
        }
    }

    @Test
    func matchesTheSharedNormalizeGoldens() {
        #expect(ContactInviteParser.normalizeShareCode("abcd-2345-67") == "ABCD234567")
        #expect(ContactInviteParser.normalizeShareCode(" @xyz w98 7654 ") == "XYZW987654")
        #expect(ContactInviteParser.normalizeShareCode("@@ab-cd@@") == "ABCD")
        #expect(ContactInviteParser.normalizeShareCode("ab\ncd") == "AB\nCD")
    }

    /// Generated from this builder (android-port-specs contacts §4.2), code `abcd-2345-67`.
    @Test
    func matchesTheSharedShareURLGoldens() {
        func url(_ mode: ServerConnectionMode, _ host: String, _ port: String, https: Bool) -> String {
            ContactInviteParser.shareURL(
                code: "abcd-2345-67",
                configuration: ServerConfiguration(mode: mode, host: host, port: port, apiPath: "/api/v1", useHTTPS: https)
            ).absoluteString
        }
        #expect(url(.official, "ignored", "8080", https: true) == "https://shroud.corespace.de/u/ABCD234567")
        #expect(url(.selfHosted, "127.0.0.1", "8080", https: false) == "https://shroud.corespace.de/u/ABCD234567")
        #expect(url(.selfHosted, "localhost", "8080", https: false) == "https://shroud.corespace.de/u/ABCD234567")
        #expect(url(.selfHosted, "  ", "8080", https: false) == "https://shroud.corespace.de/u/ABCD234567")
        #expect(url(.selfHosted, "10.0.2.2", "8080", https: false) == "http://10.0.2.2:8080/u/ABCD234567")
        #expect(url(.selfHosted, "chat.example.org", "443", https: true) == "https://chat.example.org/u/ABCD234567")
        #expect(url(.selfHosted, "chat.example.org", "", https: true) == "https://chat.example.org/u/ABCD234567")
        #expect(url(.selfHosted, "chat.example.org", "80", https: false) == "http://chat.example.org/u/ABCD234567")
        #expect(url(.selfHosted, "chat.example.org", "8443", https: true) == "https://chat.example.org:8443/u/ABCD234567")
        #expect(url(.selfHosted, " 192.168.1.20 ", " 8080 ", https: false) == "http://192.168.1.20:8080/u/ABCD234567")
    }

    // MARK: - Share code that is really a username (P10a, web `api/client.ts:389-411`)

    @Test
    func aShareCodeFallsBackToAUsernameOnlyWhenItCouldBeOne() {
        #expect(MessagingController.usernameFallback(forShareCode: "NIKLASVORBERG") == "niklasvorberg")
        #expect(MessagingController.usernameFallback(forShareCode: "JANECOOPER") == "janecooper")
        #expect(MessagingController.usernameFallback(forShareCode: "ABCD234567") == "abcd234567")
        #expect(MessagingController.usernameFallback(forShareCode: "MÜLLERHANS") == nil)
        #expect(MessagingController.usernameFallback(forShareCode: "ABCD-2345!") == nil)
        #expect(MessagingController.usernameFallback(forShareCode: String(repeating: "A", count: 33)) == nil)
    }

    @Test @MainActor
    func aShareCodeThatIsNotFoundIsTriedAsAUsername() async throws {
        let card = UserCardDTO(id: UUID(), username: "niklasvorberg", shareCode: "QWERTY2345")
        let calls = LookupLog()
        let found = try await MessagingController.lookUpShareCode(
            "NIKLASVORBERG",
            byCode: { code in
                await calls.add("by-code/\(code)")
                throw APIError.server(code: "NOT_FOUND", message: "User not found.", statusCode: 404)
            },
            byUsername: { name in
                await calls.add("by-username/\(name)")
                return card
            }
        )
        #expect(found == card)
        #expect(await calls.entries == ["by-code/NIKLASVORBERG", "by-username/niklasvorberg"])
    }

    @Test @MainActor
    func aFoundShareCodeIsNeverTriedAsAUsername() async throws {
        let card = UserCardDTO(id: UUID(), username: "jane_cooper", shareCode: "ABCD234567")
        let calls = LookupLog()
        let found = try await MessagingController.lookUpShareCode(
            "ABCD234567",
            byCode: { code in
                await calls.add("by-code/\(code)")
                return card
            },
            byUsername: { name in
                await calls.add("by-username/\(name)")
                throw APIError.server(code: "NOT_FOUND", message: "User not found.", statusCode: 404)
            }
        )
        #expect(found == card)
        #expect(await calls.entries == ["by-code/ABCD234567"])
    }

    /// Offline, a rate limit or a server error is the answer; only "not found" falls back. A
    /// code that cannot be a username keeps its 404.
    @Test @MainActor
    func onlyANotFoundWithAUsernameShapeFallsBack() async {
        let offline = APIError.transport("The Internet connection appears to be offline.")
        let limited = APIError.server(code: "RATE_LIMITED", message: "Too many requests. Try again later.", statusCode: 429)
        let notFound = APIError.server(code: "NOT_FOUND", message: "User not found.", statusCode: 404)
        for (code, error) in [("NIKLASVORBERG", offline), ("NIKLASVORBERG", limited), ("MÜLLERHANS", notFound)] {
            let calls = LookupLog()
            await #expect(throws: error) {
                try await MessagingController.lookUpShareCode(
                    code,
                    byCode: { _ in throw error },
                    byUsername: { name in
                        await calls.add("by-username/\(name)")
                        throw notFound
                    }
                )
            }
            #expect(await calls.entries.isEmpty)
        }
    }
}

/// Which lookups ran, in order.
private actor LookupLog {
    private(set) var entries: [String] = []

    func add(_ entry: String) {
        entries.append(entry)
    }
}
