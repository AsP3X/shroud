import XCTest
@testable import shroud

final class LocalMessageStoreTests: XCTestCase {
    func testPruneDropsPeerMessagesOlderThan90Days() {
        let store = LocalMessageStore()
        let peer = UUID()
        let now = Date()
        let old = Calendar.current.date(byAdding: .day, value: -91, to: now)!
        let recent = Calendar.current.date(byAdding: .day, value: -10, to: now)!

        let oldMsg = sampleStored(id: UUID(), peer: peer, createdAt: old, pending: false)
        let recentMsg = sampleStored(id: UUID(), peer: peer, createdAt: recent, pending: false)
        let pendingOld = sampleStored(id: UUID(), peer: peer, createdAt: old, pending: true)

        var snapshot = LocalMessageStore.Snapshot()
        snapshot.threads = [
            peer.uuidString.lowercased(): [oldMsg, recentMsg, pendingOld],
        ]

        let (pruned, dropped) = store.prune(snapshot, now: now)
        let kept = pruned.threads[peer.uuidString.lowercased()] ?? []

        XCTAssertEqual(Set(dropped), [oldMsg.id])
        XCTAssertEqual(Set(kept.map(\.id)), [recentMsg.id, pendingOld.id])
    }

    func testPruneKeepsNotesRegardlessOfAge() {
        let store = LocalMessageStore()
        let now = Date()
        let old = Calendar.current.date(byAdding: .day, value: -400, to: now)!
        let notesPeer = LocalMessageStore.notesPeerID
        let note = sampleStored(id: UUID(), peer: notesPeer, createdAt: old, pending: false)

        var snapshot = LocalMessageStore.Snapshot()
        snapshot.threads = [
            notesPeer.uuidString.lowercased(): [note],
        ]

        let (pruned, dropped) = store.prune(snapshot, now: now)
        let kept = pruned.threads[notesPeer.uuidString.lowercased()] ?? []

        XCTAssertTrue(dropped.isEmpty)
        XCTAssertEqual(kept.map(\.id), [note.id])
    }

    private func sampleStored(
        id: UUID,
        peer: UUID,
        createdAt: Date,
        pending: Bool
    ) -> LocalMessageStore.StoredMessage {
        LocalMessageStore.StoredMessage(
            id: id,
            peerUserID: peer,
            senderUserID: UUID(),
            text: "hello",
            createdAt: createdAt,
            isMine: true,
            deleted: false,
            receipt: "sent",
            kind: "text",
            pendingSync: pending ? true : nil
        )
    }
}
