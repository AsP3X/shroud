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
}
