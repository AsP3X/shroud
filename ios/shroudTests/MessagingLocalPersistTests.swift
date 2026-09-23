import CryptoKit
import XCTest
@testable import shroud

/// Saving the store runs on the main thread after every refresh, send and load. A save that
/// would write what the files already hold must leave them alone, and a per-thread save must
/// cache the same plaintext the full save does.
@MainActor
final class MessagingLocalPersistTests: XCTestCase {
    private let userID = UUID()
    private let peerA = UUID()
    private let peerB = UUID()
    private let key = SymmetricKey(size: .bits256)
    private var messageIDs: [UUID] = []
    private let old = Date(timeIntervalSince1970: 1_000_000)

    override func tearDown() {
        MessagingLocalRepository().removeCaches(messageIDs: messageIDs)
        LocalMessageStore().clear(userID: userID)
        super.tearDown()
    }

    func testAnUnchangedSaveWritesNothing() throws {
        let repository = unlockedRepository()
        let threads = [peerA: messages(3, peer: peerA), peerB: messages(3, peer: peerB)]
        persist(repository, threads: threads)
        let files = [threadURL(peerA), threadURL(peerB), rosterURL] + messageIDs.map(plaintextURL)
        try backdate(files)

        persist(repository, threads: threads)

        for file in files {
            XCTAssertEqual(try modified(file), old, file.lastPathComponent)
        }
    }

    func testAChangedThreadIsTheOnlyFileRewritten() throws {
        let repository = unlockedRepository()
        var threads = [peerA: messages(2, peer: peerA), peerB: messages(2, peer: peerB)]
        persist(repository, threads: threads)
        try backdate([threadURL(peerA), threadURL(peerB), rosterURL])

        threads[peerA]?.append(message(peer: peerA, text: "new"))
        persist(repository, threads: threads)

        XCTAssertNotEqual(try modified(threadURL(peerA)), old)
        XCTAssertEqual(try modified(threadURL(peerB)), old)
        XCTAssertEqual(try modified(rosterURL), old)
        let reloaded = unlockedRepository().hydrate(userID: userID)
        XCTAssertEqual(reloaded.threads[peerA]?.last?.text, "new")
    }

    func testADroppedPeerLosesItsThreadFile() {
        let repository = unlockedRepository()
        persist(repository, threads: [peerA: messages(1, peer: peerA), peerB: messages(1, peer: peerB)])

        persist(repository, threads: [peerA: messages(1, peer: peerA)])

        XCTAssertFalse(FileManager.default.fileExists(atPath: threadURL(peerB).path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: threadURL(peerA).path))
    }

    /// A save after the files changed under a fresh unlock still lands: the record of what was
    /// written is forgotten with the key.
    func testANewKeyForgetsWhatWasWritten() throws {
        let repository = unlockedRepository()
        let threads = [peerA: messages(1, peer: peerA)]
        persist(repository, threads: threads)
        try FileManager.default.removeItem(at: threadURL(peerA))

        repository.setHistoryKey(key)
        persist(repository, threads: threads)

        XCTAssertTrue(FileManager.default.fileExists(atPath: threadURL(peerA).path))
    }

    /// The link message's cache holds its media payload (and the key to its picture).
    func testThreadSaveKeepsALinkImagePayload() {
        let repository = unlockedRepository()
        var link = message(peer: peerA, text: "https://example.com")
        link.mediaObjectId = UUID()
        let payload = Data(#"{"t":"link","k":"blob-key"}"#.utf8)
        repository.saveSealedPlaintext(messageID: link.id, data: payload)

        persistThread(repository, peer: peerA, messages: [link])

        XCTAssertEqual(repository.sealedPlaintext(for: link.id), payload)
    }

    /// A reply's cache holds its quote, as the full save writes it.
    func testThreadSaveCachesTheReplyQuote() {
        let repository = unlockedRepository()
        var reply = message(peer: peerA, text: "yes")
        reply.replyTo = MessageReplyReference(messageID: UUID(), senderUserID: peerA, kind: .text, snippet: "ok?")
        let wire = MessageTextPayload.wire(body: reply.text, replyTo: reply.replyTo, linkPreview: nil)
        XCTAssertNotEqual(wire, reply.text)

        persistThread(repository, peer: peerA, messages: [reply])

        XCTAssertEqual(repository.sealedPlaintextText(for: reply.id), wire)
    }

    // MARK: - Helpers

    private func unlockedRepository() -> MessagingLocalRepository {
        let repository = MessagingLocalRepository()
        repository.setHistoryKey(key)
        return repository
    }

    private func message(peer: UUID, text: String) -> MessagingController.ChatMessage {
        let id = UUID()
        messageIDs.append(id)
        return MessagingController.ChatMessage(
            id: id,
            peerUserID: peer,
            senderUserID: peer,
            text: text,
            createdAt: Date(),
            isMine: false,
            deleted: false
        )
    }

    private func messages(_ count: Int, peer: UUID) -> [MessagingController.ChatMessage] {
        (0..<count).map { message(peer: peer, text: "message \($0)") }
    }

    private func persist(
        _ repository: MessagingLocalRepository,
        threads: [UUID: [MessagingController.ChatMessage]]
    ) {
        repository.persist(
            userID: userID,
            contacts: [],
            incomingRequests: [],
            conversations: [],
            threads: threads,
            unreadByPeer: [:]
        )
    }

    private func persistThread(
        _ repository: MessagingLocalRepository,
        peer: UUID,
        messages: [MessagingController.ChatMessage]
    ) {
        repository.persistThread(
            peerID: peer,
            messages: messages,
            userID: userID,
            conversations: [],
            contacts: [],
            incomingRequests: [],
            unreadByPeer: [:]
        )
    }

    private var supportDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("shroud", isDirectory: true)
    }

    private var rosterURL: URL {
        supportDirectory.appendingPathComponent("messages/\(userID.uuidString.lowercased())/roster.sealed")
    }

    private func threadURL(_ peer: UUID) -> URL {
        supportDirectory.appendingPathComponent(
            "messages/\(userID.uuidString.lowercased())/threads/\(peer.uuidString.lowercased()).sealed"
        )
    }

    private func plaintextURL(_ id: UUID) -> URL {
        supportDirectory.appendingPathComponent("plaintext/\(id.uuidString.lowercased()).sealed")
    }

    private func backdate(_ files: [URL]) throws {
        for file in files {
            try FileManager.default.setAttributes([.modificationDate: old], ofItemAtPath: file.path)
        }
    }

    private func modified(_ file: URL) throws -> Date? {
        try FileManager.default.attributesOfItem(atPath: file.path)[.modificationDate] as? Date
    }
}
