import CryptoKit
import XCTest
@testable import shroud

/// Hydrate runs on the unlock path, so it must not rewrite every cached plaintext each time —
/// but a cache entry that no longer matches the stored message still has to be refreshed.
@MainActor
final class MessagingLocalRepositoryTests: XCTestCase {
    private let userID = UUID()
    private let peerID = UUID()
    private let messageID = UUID()
    private let key = SymmetricKey(size: .bits256)

    override func tearDown() {
        MessagingLocalRepository().removeCaches(messageIDs: [messageID])
        LocalMessageStore().clear(userID: userID)
        super.tearDown()
    }

    func testHydrateLeavesAMatchingPlaintextEntryUntouched() throws {
        let repository = try seededRepository()
        let old = Date(timeIntervalSince1970: 1_000_000)
        try FileManager.default.setAttributes([.modificationDate: old], ofItemAtPath: plaintextURL.path)

        let state = repository.hydrate(userID: userID)

        XCTAssertEqual(state.threads[peerID]?.map(\.text), ["hello"])
        let modified = try FileManager.default.attributesOfItem(atPath: plaintextURL.path)[.modificationDate] as? Date
        XCTAssertEqual(modified, old)
    }

    func testHydrateRefreshesAPlaintextEntryThatDiffers() throws {
        let repository = try seededRepository()
        repository.saveSealedPlaintext(messageID: messageID, senderUserID: userID, text: "stale")

        _ = repository.hydrate(userID: userID)

        XCTAssertEqual(repository.sealedPlaintextText(for: messageID, senderUserID: userID), "hello")
        // A fresh repository reads the disk copy, not the in-memory one.
        let reopened = MessagingLocalRepository()
        reopened.setHistoryKey(key)
        XCTAssertEqual(reopened.sealedPlaintextText(for: messageID, senderUserID: userID), "hello")
    }

    private var plaintextURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("shroud/plaintext", isDirectory: true)
            .appendingPathComponent(messageID.uuidString.lowercased() + ".sealed")
    }

    private func seededRepository() throws -> MessagingLocalRepository {
        let repository = MessagingLocalRepository()
        repository.setHistoryKey(key)
        let stored = LocalMessageStore.StoredMessage(
            id: messageID,
            peerUserID: peerID,
            senderUserID: userID,
            text: "hello",
            createdAt: Date(),
            isMine: true,
            deleted: false,
            receipt: "sent",
            kind: "text",
            pendingSync: nil
        )
        repository.persist(
            userID: userID,
            contacts: [],
            incomingRequests: [],
            conversations: [],
            threads: [peerID: [stored.toChatMessage(media: LocalMediaCache(), historyKey: key)]],
            unreadByPeer: [:]
        )
        XCTAssertTrue(FileManager.default.fileExists(atPath: plaintextURL.path))
        return repository
    }
}
