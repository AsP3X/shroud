import CryptoKit
import Foundation
import Testing
@testable import shroud

/// The contact username book is sealed under the history key, outside backups, and only open
/// while the chats are (security audit M9).
@Suite(.serialized)
struct ContactNamesTests {
    private struct Scratch {
        let key = SymmetricKey(size: .bits256)
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("names-\(UUID().uuidString)", isDirectory: true)
        let suite = "shroud.tests.names." + UUID().uuidString
        var fileURL: URL { directory.appendingPathComponent("contacts/names.sealed") }
        var defaults: UserDefaults { UserDefaults(suiteName: suite)! }

        func unlock() {
            ContactNames.unlock(historyKey: key, fileURL: fileURL, defaults: defaults)
        }

        func tearDown() {
            ContactNames.lock()
            try? FileManager.default.removeItem(at: directory)
            UserDefaults.standard.removePersistentDomain(forName: suite)
        }
    }

    @Test
    func namesRoundTripWhileUnlocked() {
        let scratch = Scratch()
        defer { scratch.tearDown() }
        let owner = UUID()
        let peer = UUID()
        scratch.unlock()

        ContactNames.remember("alice", peer: peer, owner: owner)
        ContactNames.rememberPublished("fp", peer: peer, owner: owner)
        #expect(ContactNames.name(peer: peer, owner: owner) == "alice")
        #expect(ContactNames.publishedFingerprint(peer: peer, owner: owner) == "fp")
        #expect(ContactNames.name(peer: peer, owner: UUID()) == nil)

        ContactNames.retain(peers: [], owner: owner)
        #expect(ContactNames.name(peer: peer, owner: owner) == nil)
        #expect(ContactNames.publishedFingerprint(peer: peer, owner: owner) == nil)
    }

    @Test
    func lockedBookReadsEmptyAndDropsWrites() {
        let scratch = Scratch()
        defer { scratch.tearDown() }
        let owner = UUID()
        let peer = UUID()
        scratch.unlock()
        ContactNames.remember("alice", peer: peer, owner: owner)
        ContactNames.lock()

        #expect(ContactNames.name(peer: peer, owner: owner) == nil)
        #expect(ContactNames.display(peer: peer, owner: owner) == ContactNames.placeholder)
        let before = try? Data(contentsOf: scratch.fileURL)
        ContactNames.remember("bob", peer: UUID(), owner: owner)
        #expect((try? Data(contentsOf: scratch.fileURL)) == before)

        // Unlocking again brings the sealed book back, without the dropped write.
        scratch.unlock()
        #expect(ContactNames.all(owner: owner) == [peer.uuidString.lowercased(): "alice"])
    }

    @Test
    func theFileIsSealedAndNamesNoOne() throws {
        let scratch = Scratch()
        defer { scratch.tearDown() }
        let owner = UUID()
        let peer = UUID()
        scratch.unlock()
        ContactNames.remember("alice", peer: peer, owner: owner)

        let blob = try Data(contentsOf: scratch.fileURL)
        #expect(LocalHistoryCrypto.isSealedBlob(blob))
        #expect(blob.range(of: Data("alice".utf8)) == nil)
        #expect(blob.range(of: Data(peer.uuidString.lowercased().utf8)) == nil)
        #expect(ContactNames.open(blob, historyKey: SymmetricKey(size: .bits256)) == nil)
        let opened = try #require(ContactNames.open(blob, historyKey: scratch.key))
        #expect(opened["shroud.contact-names.\(owner.uuidString.lowercased())"]?[peer.uuidString.lowercased()] == "alice")
        let excluded = try scratch.fileURL.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup
        #expect(excluded == true)
    }

    @Test
    func plaintextDefaultsAreMigratedThenDeleted() throws {
        let scratch = Scratch()
        defer { scratch.tearDown() }
        let owner = UUID()
        let peer = UUID()
        let namesKey = "shroud.contact-names.\(owner.uuidString.lowercased())"
        let publishedKey = "shroud.contact-names.published.\(owner.uuidString.lowercased())"
        scratch.defaults.set([peer.uuidString.lowercased(): "alice"], forKey: namesKey)
        scratch.defaults.set([peer.uuidString.lowercased(): "fp"], forKey: publishedKey)
        scratch.defaults.set(42, forKey: "shroud.contact-names.garbage")

        scratch.unlock()

        #expect(scratch.defaults.object(forKey: namesKey) == nil)
        #expect(scratch.defaults.object(forKey: publishedKey) == nil)
        #expect(scratch.defaults.object(forKey: "shroud.contact-names.garbage") == nil)
        #expect(ContactNames.name(peer: peer, owner: owner) == "alice")
        #expect(ContactNames.publishedFingerprint(peer: peer, owner: owner) == "fp")
        let blob = try Data(contentsOf: scratch.fileURL)
        #expect(ContactNames.open(blob, historyKey: scratch.key)?[namesKey]?[peer.uuidString.lowercased()] == "alice")
    }
}
