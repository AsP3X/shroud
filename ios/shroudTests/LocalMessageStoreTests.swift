import CryptoKit
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

    func testPerPeerSaveDoesNotRequireFullSnapshot() throws {
        let store = LocalMessageStore()
        let userID = UUID()
        let peerA = UUID()
        let peerB = UUID()
        let key = SymmetricKey(size: .bits256)

        let msgA = sampleStored(id: UUID(), peer: peerA, createdAt: Date(), pending: false)
        let msgB = sampleStored(id: UUID(), peer: peerB, createdAt: Date(), pending: false)

        store.saveThread(peerID: peerA, messages: [msgA], userID: userID, historyKey: key)
        store.saveThread(peerID: peerB, messages: [msgB], userID: userID, historyKey: key)
        store.saveRoster(
            LocalMessageStore.Roster(
                conversations: [
                    LocalMessageStore.CachedConversation(
                        id: UUID(),
                        peerID: peerA,
                        peerUsername: "alice",
                        createdAt: Date(),
                        lastMessageAt: Date()
                    ),
                ],
                contacts: [],
                incomingRequests: [],
                unreadByPeer: [:],
                updatedAt: Date()
            ),
            userID: userID,
            historyKey: key
        )

        // Update only peer A — peer B must remain.
        let msgA2 = sampleStored(id: UUID(), peer: peerA, createdAt: Date(), pending: false)
        store.saveThread(peerID: peerA, messages: [msgA, msgA2], userID: userID, historyKey: key)

        let loadedA = store.loadThread(peerID: peerA, userID: userID, historyKey: key)
        let loadedB = store.loadThread(peerID: peerB, userID: userID, historyKey: key)
        XCTAssertEqual(loadedA?.map(\.id), [msgA.id, msgA2.id])
        XCTAssertEqual(loadedB?.map(\.id), [msgB.id])

        let full = store.load(userID: userID, historyKey: key)
        XCTAssertEqual(full?.conversations.first?.peerUsername, "alice")
        XCTAssertEqual(full?.threads[peerB.uuidString.lowercased()]?.map(\.id), [msgB.id])

        store.clear(userID: userID)
    }

    func testLegacySnapshotMigratesToRosterAndThreads() throws {
        let store = LocalMessageStore()
        let userID = UUID()
        let peer = UUID()
        let key = SymmetricKey(size: .bits256)
        let msg = sampleStored(id: UUID(), peer: peer, createdAt: Date(), pending: false)

        // Write a v1 monolithic sealed snapshot via private path simulation:
        // save() now writes roster+threads; force-write legacy file then migrate.
        var snap = LocalMessageStore.Snapshot()
        snap.conversations = [
            LocalMessageStore.CachedConversation(
                id: UUID(),
                peerID: peer,
                peerUsername: "bob",
                createdAt: Date(),
                lastMessageAt: Date()
            ),
        ]
        snap.threads = [peer.uuidString.lowercased(): [msg]]

        // Use public save which already writes split layout — then write a legacy
        // snapshot.sealed next to it and clear roster so migrate path runs.
        store.save(snap, userID: userID, historyKey: key)
        // Already on v2; re-load works.
        let loaded = store.load(userID: userID, historyKey: key)
        XCTAssertEqual(loaded?.threads[peer.uuidString.lowercased()]?.first?.id, msg.id)
        XCTAssertEqual(loaded?.conversations.first?.peerUsername, "bob")

        store.clear(userID: userID)
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

final class NotesLocalTests: XCTestCase {
    func testTodoWireFormatRoundTrip() {
        let wire = NotesLocal.syncedTodoPlaintext(text: "Buy milk", done: false)
        let parsed = NotesLocal.parseSyncedTodo(wire)
        XCTAssertEqual(parsed?.text, "Buy milk")
        XCTAssertEqual(parsed?.done, false)
        let doneWire = NotesLocal.syncedTodoPlaintext(text: "Buy milk", done: true)
        XCTAssertEqual(NotesLocal.parseSyncedTodo(doneWire)?.done, true)
        XCTAssertNil(NotesLocal.parseSyncedTodo("plain note"))
    }

    func testToggleTodo() {
        let note = NotesLocal.makeNote(
            text: "Buy milk",
            kind: .todo,
            senderUserID: UUID(),
            todoDone: false
        )
        let toggled = NotesLocal.toggleTodo(messageID: note.id, in: [note])
        XCTAssertEqual(toggled?.first?.todoDone, true)
        let again = NotesLocal.toggleTodo(messageID: note.id, in: toggled ?? [])
        XCTAssertEqual(again?.first?.todoDone, false)
    }

    func testDeleteNote() {
        let a = NotesLocal.makeNote(text: "a", kind: .text, senderUserID: UUID())
        let b = NotesLocal.makeNote(text: "b", kind: .text, senderUserID: UUID())
        let result = NotesLocal.delete(messageID: a.id, in: [a, b])
        XCTAssertTrue(result.removed)
        XCTAssertEqual(result.messages.map(\.id), [b.id])
    }
}

final class OutboundPendingTests: XCTestCase {
    func testCollectsPendingInChronologicalOrder() {
        let peer = UUID()
        let me = UUID()
        let older = MessagingController.ChatMessage(
            id: UUID(),
            peerUserID: peer,
            senderUserID: me,
            text: "first",
            createdAt: Date().addingTimeInterval(-60),
            isMine: true,
            deleted: false,
            receipt: .sending,
            pendingSync: true
        )
        let newer = MessagingController.ChatMessage(
            id: UUID(),
            peerUserID: peer,
            senderUserID: me,
            text: "second",
            createdAt: Date(),
            isMine: true,
            deleted: false,
            receipt: .sending,
            pendingSync: true
        )
        let synced = MessagingController.ChatMessage(
            id: UUID(),
            peerUserID: peer,
            senderUserID: me,
            text: "done",
            createdAt: Date().addingTimeInterval(-30),
            isMine: true,
            deleted: false,
            pendingSync: false
        )
        let items = OutboundPending.items(from: [peer: [newer, synced, older]])
        XCTAssertEqual(items.count, 2)
        if case let .text(id, _, text) = items[0] {
            XCTAssertEqual(id, older.id)
            XCTAssertEqual(text, "first")
        } else {
            XCTFail("expected first pending text")
        }
        if case let .text(id, _, _) = items[1] {
            XCTAssertEqual(id, newer.id)
        } else {
            XCTFail("expected second pending text")
        }
    }

    func testCollectsPendingVoice() {
        let peer = UUID()
        let me = UUID()
        let voice = MessagingController.ChatMessage(
            id: UUID(),
            peerUserID: peer,
            senderUserID: me,
            text: "Voice message",
            createdAt: Date(),
            isMine: true,
            deleted: false,
            receipt: .failed,
            kind: .voice,
            voiceData: Data("audio".utf8),
            voiceDurationMs: 1200,
            pendingSync: true
        )
        let items = OutboundPending.items(from: [peer: [voice]])
        XCTAssertEqual(items.count, 1)
        if case let .voice(id, peerID) = items[0] {
            XCTAssertEqual(id, voice.id)
            XCTAssertEqual(peerID, peer)
        } else {
            XCTFail("expected pending voice")
        }
    }

    func testSkipsNotesPeer() {
        let notes = LocalMessageStore.notesPeerID
        let me = UUID()
        let note = MessagingController.ChatMessage(
            id: UUID(),
            peerUserID: notes,
            senderUserID: me,
            text: "local only",
            createdAt: Date(),
            isMine: true,
            deleted: false,
            pendingSync: true
        )
        XCTAssertTrue(OutboundPending.items(from: [notes: [note]]).isEmpty)
    }
}

@MainActor
final class ThreadMessageMergeTests: XCTestCase {
    func testPreferReadableKeepsPriorOnDecryptFailure() {
        let id = UUID()
        let peer = UUID()
        let prior = MessagingController.ChatMessage(
            id: id,
            peerUserID: peer,
            senderUserID: peer,
            text: "hello from cache",
            createdAt: Date(),
            isMine: false,
            deleted: false
        )
        let failed = MessagingController.ChatMessage(
            id: id,
            peerUserID: peer,
            senderUserID: peer,
            text: "[Unable to decrypt]",
            createdAt: Date(),
            isMine: false,
            deleted: false
        )
        let merged = ThreadMessageMerge.preferReadable(failed, previous: [prior])
        XCTAssertEqual(merged.text, "hello from cache")
    }

    /// An older page is not the whole thread. A newer bubble whose text is the undecrypted
    /// placeholder must stay; the next refresh will not fetch it again.
    func testMergeThreadKeepsMessagesThePageDidNotInclude() {
        let peer = UUID()
        let older = MessagingController.ChatMessage(
            id: UUID(),
            peerUserID: peer,
            senderUserID: peer,
            text: "from the older page",
            createdAt: Date(timeIntervalSince1970: 1_800_000_000),
            isMine: false,
            deleted: false
        )
        let placeholder = MessagingController.ChatMessage(
            id: UUID(),
            peerUserID: peer,
            senderUserID: peer,
            text: "Media",
            createdAt: Date(timeIntervalSince1970: 1_800_000_100),
            isMine: false,
            deleted: false
        )
        let saidMedia = MessagingController.ChatMessage(
            id: UUID(),
            peerUserID: peer,
            senderUserID: peer,
            text: "Media",
            createdAt: Date(timeIntervalSince1970: 1_800_000_200),
            isMine: false,
            deleted: false,
            kind: .text
        )
        let merged = ThreadMessageMerge.mergeThread(
            decoded: [older],
            previous: [older, placeholder, saidMedia],
            pendingLocal: []
        )
        XCTAssertEqual(merged.map(\.id), [older.id, placeholder.id, saidMedia.id])
    }

    func testMergeThreadKeepsPendingAndLocalOnly() {
        let peer = UUID()
        let me = UUID()
        let server = MessagingController.ChatMessage(
            id: UUID(),
            peerUserID: peer,
            senderUserID: peer,
            text: "from server",
            createdAt: Date().addingTimeInterval(-10),
            isMine: false,
            deleted: false
        )
        let pending = MessagingController.ChatMessage(
            id: UUID(),
            peerUserID: peer,
            senderUserID: me,
            text: "queued offline",
            createdAt: Date(),
            isMine: true,
            deleted: false,
            receipt: .sending,
            pendingSync: true
        )
        let merged = ThreadMessageMerge.mergeThread(
            decoded: [server],
            previous: [server, pending],
            pendingLocal: [pending]
        )
        XCTAssertEqual(merged.map(\.id), [server.id, pending.id])
    }

    // MARK: - Tombstones from a history page (the `message.deleted` event was missed)

    func testTombstoneDropsTheQuoteAndLinkPreview() {
        var prior = chatMessage(text: "see https://example.com")
        prior.replyTo = MessageReplyReference(messageID: UUID(), senderUserID: UUID(), kind: .text, snippet: "where?")
        prior.linkPreview = LinkPreview(url: "https://example.com", title: "Example")
        prior.mediaObjectId = UUID()
        prior.imageData = Data("link image".utf8)
        prior.previewData = Data("blurred".utf8)
        prior.imageWidth = 1200
        prior.imageHeight = 630

        let merged = ThreadMessageMerge.preferReadable(serverTombstone(of: prior, isMedia: true), prior: prior)

        XCTAssertEqual(merged, ThreadMessageMerge.tombstone(of: prior))
        XCTAssertEqual(merged.text, "Message deleted")
        XCTAssertTrue(merged.deleted)
        // A link message is a text bubble, even though its picture made it "media" on the wire.
        XCTAssertEqual(merged.kind, .text)
        XCTAssertNil(merged.replyTo)
        XCTAssertNil(merged.linkPreview)
        XCTAssertNil(merged.mediaObjectId)
        XCTAssertNil(merged.imageData)
        XCTAssertNil(merged.previewData)
        XCTAssertNil(merged.imageWidth)
        let stored = LocalMessageStore.StoredMessage.from(merged)
        XCTAssertNil(stored.replyTo)
        XCTAssertNil(stored.linkPreview)
        XCTAssertNil(stored.mediaObjectId)
    }

    func testVoiceTombstoneDropsTheTranscriptAndAudio() {
        var prior = chatMessage(text: "meet at noon")
        prior.kind = .voice
        prior.mediaObjectId = UUID()
        prior.voiceData = Data("m4a".utf8)
        prior.voiceDurationMs = 4200
        prior.voiceWaveform = [10, 200, 30]
        prior.transcript = "meet at noon"
        prior.replyTo = MessageReplyReference(messageID: UUID(), senderUserID: UUID(), kind: .text, snippet: "when?")

        let merged = ThreadMessageMerge.preferReadable(serverTombstone(of: prior, isMedia: true), prior: prior)

        XCTAssertEqual(merged, ThreadMessageMerge.tombstone(of: prior))
        // Still drawn as a voice bubble: the server only knows it was "media".
        XCTAssertEqual(merged.kind, .voice)
        XCTAssertNil(merged.transcript)
        XCTAssertNil(merged.voiceData)
        XCTAssertNil(merged.voiceDurationMs)
        XCTAssertNil(merged.voiceWaveform)
        XCTAssertNil(merged.replyTo)
        XCTAssertNil(LocalMessageStore.StoredMessage.from(merged).transcript)
    }

    /// The merge keeps a hydrated video from being downgraded by a decode without bytes; a
    /// tombstone must not get the video back that way.
    func testVideoTombstoneKeepsNoVideo() {
        var prior = chatMessage(text: "Video")
        prior.kind = .video
        prior.mediaObjectId = UUID()
        prior.videoData = Data("mp4".utf8)
        prior.previewData = Data("poster".utf8)
        prior.imageData = Data("poster".utf8)
        prior.voiceDurationMs = 9000

        let merged = ThreadMessageMerge.preferReadable(serverTombstone(of: prior, isMedia: true), prior: prior)

        XCTAssertEqual(merged, ThreadMessageMerge.tombstone(of: prior))
        XCTAssertEqual(merged.kind, .video)
        XCTAssertNil(merged.videoData)
        XCTAssertNil(merged.imageData)
        XCTAssertNil(merged.previewData)
        XCTAssertNil(merged.voiceDurationMs)
    }

    func testTombstoneKeepsTheHigherReceipt() {
        var mine = chatMessage(text: "hi", isMine: true)
        mine.receipt = .read
        let fromServer = serverTombstone(of: mine, isMedia: false, receipt: .delivered)
        XCTAssertEqual(ThreadMessageMerge.preferReadable(fromServer, prior: mine).receipt, .read)

        mine.receipt = .delivered
        let readOnServer = serverTombstone(of: mine, isMedia: false, receipt: .read)
        XCTAssertEqual(ThreadMessageMerge.preferReadable(readOnServer, prior: mine).receipt, .read)
    }

    func testMergeThreadReplacesTheLiveMessageAndPurgesItOnce() {
        var deleted = chatMessage(text: "gone")
        deleted.replyTo = MessageReplyReference(messageID: UUID(), senderUserID: UUID(), kind: .text, snippet: "q")
        let kept = chatMessage(text: "still here", createdAt: deleted.createdAt.addingTimeInterval(1))
        let page = [serverTombstone(of: deleted, isMedia: false), kept]

        let merged = ThreadMessageMerge.mergeThread(decoded: page, previous: [deleted, kept], pendingLocal: [])

        XCTAssertEqual(merged, [ThreadMessageMerge.tombstone(of: deleted), kept])
        XCTAssertEqual(ThreadMessageMerge.tombstonesToPurge(decoded: page, previous: [deleted, kept]), [deleted.id])

        // The next poll brings the same page: nothing changes, and nothing is purged again.
        XCTAssertEqual(ThreadMessageMerge.mergeThread(decoded: page, previous: merged, pendingLocal: []), merged)
        XCTAssertEqual(ThreadMessageMerge.tombstonesToPurge(decoded: page, previous: merged), [])
    }

    func testTombstonesToPurge() {
        let live = chatMessage(text: "live")
        let bare = ThreadMessageMerge.tombstone(of: chatMessage(text: "bare"))
        // What an older build merged in: marked deleted, content still attached.
        var kept = ThreadMessageMerge.tombstone(of: chatMessage(text: "kept"))
        kept.transcript = "kept"
        let unseen = chatMessage(text: "unseen")
        let stillLive = chatMessage(text: "not deleted")

        let page = [live, bare, kept, unseen].map { serverTombstone(of: $0, isMedia: false) } + [stillLive]
        let purge = ThreadMessageMerge.tombstonesToPurge(decoded: page, previous: [live, bare, kept, stillLive])

        XCTAssertEqual(purge, [live.id, kept.id, unseen.id])
    }

    /// A tombstone this thread never held comes in bare, even from a row deleted before the
    /// server started clearing `media_object_id`.
    func testUnseenTombstoneIsBare() {
        var decoded = serverTombstone(of: chatMessage(text: "x"), isMedia: true)
        decoded.mediaObjectId = UUID()

        let merged = ThreadMessageMerge.preferReadable(decoded, prior: nil)

        XCTAssertEqual(merged, ThreadMessageMerge.tombstone(of: merged))
        XCTAssertNil(merged.mediaObjectId)
        XCTAssertEqual(merged.kind, .image)
    }

    /// A deleted photo, video, or voice note draws the same text tombstone as a deleted sentence.
    func testDeletedMediaPresentsAsText() {
        for kind in [
            MessagingController.ChatMessageKind.text,
            .image, .video, .voice, .todo,
        ] {
            var message = chatMessage(text: "x")
            message.kind = kind
            XCTAssertEqual(message.presentedKind, kind, "\(kind)")
            let gone = ThreadMessageMerge.tombstone(of: message)
            XCTAssertEqual(gone.presentedKind, .text, "\(kind)")
        }
        // A history page cannot tell a deleted voice note from a deleted photo. It still
        // draws as text; the stored kind stays image.
        let unseen = serverTombstone(of: chatMessage(text: "x"), isMedia: true)
        XCTAssertEqual(unseen.kind, .image)
        XCTAssertEqual(unseen.presentedKind, .text)
    }

    func testTombstoneKinds() {
        for (kind, expected) in [
            (MessagingController.ChatMessageKind.text, MessagingController.ChatMessageKind.text),
            (.image, .image), (.video, .video), (.voice, .voice), (.todo, .text),
        ] {
            var message = chatMessage(text: "x")
            message.kind = kind
            XCTAssertEqual(ThreadMessageMerge.tombstone(of: message).kind, expected, "\(kind)")
        }
    }

    private func chatMessage(
        text: String,
        isMine: Bool = false,
        createdAt: Date = Date(timeIntervalSince1970: 1_800_000_000)
    ) -> MessagingController.ChatMessage {
        let peer = UUID()
        return MessagingController.ChatMessage(
            id: UUID(),
            peerUserID: peer,
            senderUserID: isMine ? UUID() : peer,
            text: text,
            createdAt: createdAt,
            isMine: isMine,
            deleted: false
        )
    }

    /// What `MessageDecoder` makes of a `deleted_for_everyone` message (the server nulls the
    /// ciphertext and the media id; `content_type` still says "media").
    private func serverTombstone(
        of message: MessagingController.ChatMessage,
        isMedia: Bool,
        receipt: MessageReceiptStatus = .sent
    ) -> MessagingController.ChatMessage {
        MessagingController.ChatMessage(
            id: message.id,
            peerUserID: message.peerUserID,
            senderUserID: message.senderUserID,
            text: "Message deleted",
            createdAt: message.createdAt,
            isMine: message.isMine,
            deleted: true,
            receipt: receipt,
            kind: isMedia ? .image : .text
        )
    }
}
