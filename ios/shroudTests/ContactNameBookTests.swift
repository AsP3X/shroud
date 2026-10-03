import CryptoKit
import Foundation
import Testing
@testable import shroud

struct ContactNameBookTests {
    private let historyKey = SymmetricKey(data: Data((1 ... 32).map { UInt8($0) }))
    private let owner = UUID(uuidString: "0190A3B4-0000-7000-8000-000000000001")!
    private let names = [
        "0190a3b4-0000-7000-8000-0000000000aa": "alice",
        "0190a3b4-0000-7000-8000-0000000000bb": "bob_2",
    ]

    /// Shared with web `src/crypto/contactBook.selftest.ts` and Android `ContactNameBookTest`:
    /// SHA-256 of the Base64 text. Change all three or none.
    @Test
    func sealsTheSameBytesAsTheWebAndAndroid() throws {
        let nonce = try AES.GCM.Nonce(data: Data((0 ..< 12).map { UInt8(0xA0 + $0) }))
        let sealed = try ContactNameBook.seal(names, owner: owner, historyKey: historyKey, nonce: nonce)
        #expect(sealed.count == 1404)
        let digest = SHA256.hash(data: Data(sealed.utf8)).map { String(format: "%02x", $0) }.joined()
        #expect(digest == "b346b39ba8ba513d4ee111f8a44ae5441df440965245bb7a5e0157bbbfe158e8")
        #expect(ContactNameBook.open(sealed, owner: owner, historyKey: historyKey) == names)
    }

    @Test
    func anotherAccountsBookDoesNotOpen() throws {
        let sealed = try ContactNameBook.seal(names, owner: owner, historyKey: historyKey)
        let other = UUID(uuidString: "0190A3B4-0000-7000-8000-000000000002")!
        #expect(ContactNameBook.open(sealed, owner: other, historyKey: historyKey) == nil)
    }

    @Test
    func malformedEntriesAreDropped() throws {
        let sealed = try ContactNameBook.seal(
            ["not-an-id": "alice", "0190a3b4-0000-7000-8000-0000000000cc": "Bad Name"],
            owner: owner,
            historyKey: historyKey
        )
        #expect(ContactNameBook.open(sealed, owner: owner, historyKey: historyKey) == [:])
    }
}
