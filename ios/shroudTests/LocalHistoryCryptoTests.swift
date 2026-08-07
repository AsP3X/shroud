import CryptoKit
import XCTest
@testable import shroud

final class LocalHistoryCryptoTests: XCTestCase {
    func testRoundTripSealOpen() throws {
        let master = SymmetricKey(size: .bits256)
        let plain = Data("secret chat body — notes & 🔒".utf8)
        let sealed = try LocalHistoryCrypto.seal(
            plain,
            masterKey: master,
            context: .messagesSnapshot
        )
        XCTAssertTrue(LocalHistoryCrypto.isSealedBlob(sealed))
        XCTAssertNotEqual(sealed, plain)
        // Ciphertext must not contain plaintext.
        XCTAssertFalse(String(data: sealed, encoding: .utf8)?.contains("secret chat") == true)

        let opened = try LocalHistoryCrypto.open(
            sealed,
            masterKey: master,
            context: .messagesSnapshot
        )
        XCTAssertEqual(opened, plain)
    }

    func testWrongKeyFailsClosed() throws {
        let a = SymmetricKey(size: .bits256)
        let b = SymmetricKey(size: .bits256)
        let sealed = try LocalHistoryCrypto.seal(
            Data("hello".utf8),
            masterKey: a,
            context: .mediaFile
        )
        XCTAssertThrowsError(
            try LocalHistoryCrypto.open(sealed, masterKey: b, context: .mediaFile)
        )
    }

    func testContextSeparation() throws {
        let master = SymmetricKey(size: .bits256)
        let sealed = try LocalHistoryCrypto.seal(
            Data("payload".utf8),
            masterKey: master,
            context: .plaintextPayload
        )
        // Same master, different domain — must not open.
        XCTAssertThrowsError(
            try LocalHistoryCrypto.open(sealed, masterKey: master, context: .mediaFile)
        )
    }

    func testTamperDetected() throws {
        let master = SymmetricKey(size: .bits256)
        var sealed = try LocalHistoryCrypto.seal(
            Data("intact".utf8),
            masterKey: master,
            context: .messagesSnapshot
        )
        // Flip a ciphertext byte (after magic).
        sealed[sealed.count - 5] ^= 0xFF
        XCTAssertThrowsError(
            try LocalHistoryCrypto.open(sealed, masterKey: master, context: .messagesSnapshot)
        )
    }

    func testMessageStoreSavesSealedOnly() throws {
        let store = LocalMessageStore()
        let userID = UUID()
        let key = SymmetricKey(size: .bits256)
        var snap = LocalMessageStore.Snapshot()
        snap.conversations = [
            LocalMessageStore.CachedConversation(
                id: UUID(),
                peerID: UUID(),
                peerUsername: "alice",
                createdAt: Date(),
                lastMessageAt: Date()
            ),
        ]
        store.save(snap, userID: userID, historyKey: key)

        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let sealedURL = base
            .appendingPathComponent("shroud/messages/\(userID.uuidString.lowercased())/snapshot.sealed")
        let plainURL = base
            .appendingPathComponent("shroud/messages/\(userID.uuidString.lowercased())/snapshot.json")

        let onDisk = try Data(contentsOf: sealedURL)
        XCTAssertTrue(LocalHistoryCrypto.isSealedBlob(onDisk))
        XCTAssertFalse(FileManager.default.fileExists(atPath: plainURL.path))

        // Wrong key cannot load.
        XCTAssertNil(store.load(userID: userID, historyKey: SymmetricKey(size: .bits256)))
        // Correct key loads.
        let loaded = store.load(userID: userID, historyKey: key)
        XCTAssertEqual(loaded?.conversations.first?.peerUsername, "alice")

        store.clear(userID: userID)
    }
}
