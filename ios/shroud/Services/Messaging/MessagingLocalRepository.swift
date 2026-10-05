import CryptoKit
import Foundation

/// Owns encrypted local history, decrypt cache, and media cache.
///
/// Human: One place that knows how offline chats are loaded and saved.
/// Agent: MessagingController stays UI/network oriented; this type does disk + keys.
@MainActor
final class MessagingLocalRepository {
    struct HydratedState: Sendable {
        var contacts: [ContactItemDTO] = []
        var incomingRequests: [ContactRequestDTO] = []
        var conversations: [ConversationItemDTO] = []
        var threads: [UUID: [MessagingController.ChatMessage]] = [:]
        var unreadByPeer: [UUID: Int] = [:]
    }

    private let messageStore = LocalMessageStore()
    private let plaintextCache = LocalPlaintextCache()
    private let mediaCache = LocalMediaCache()
    /// SHRF1 blobs of shared files; opened only by the key in the sealed payload cache.
    let fileStore = LocalFileStore()

    private(set) var historyKey: SymmetricKey?

    /// What the roster and thread files hold, as this repository last wrote or read them.
    ///
    /// Human: Switching tabs refreshes Chats or Contacts, and every refresh saved the whole
    /// store — each thread re-encoded, sealed and written even when nothing had changed, on the
    /// main thread. A save that would write what the file already holds is now skipped.
    /// Agent: Only this repository writes those files. Forgotten whenever the key changes or the
    /// store is cleared; unknown (nil) means the next save writes.
    private struct Written {
        var userID: UUID
        var roster: LocalMessageStore.Roster?
        var threads: [String: [LocalMessageStore.StoredMessage]] = [:]
        var peers: Set<String>?
    }

    private var written: Written?

    func setHistoryKey(_ key: SymmetricKey?) {
        historyKey = key
        written = nil
        if key == nil {
            plaintextCache.clearMemory()
        }
    }

    /// Revoke history key + L1 plaintext so sealed disk files cannot be opened until unlock.
    func lockSensitiveMemory() {
        historyKey = nil
        written = nil
        plaintextCache.clearMemory()
    }

    // MARK: - Decrypt cache (one-shot DR payloads)

    func sealedPlaintext(for messageID: UUID, senderUserID: UUID) -> Data? {
        guard let key = historyKey else { return nil }
        return plaintextCache.data(for: messageID, senderUserID: senderUserID, historyKey: key)
    }

    func sealedPlaintextText(for messageID: UUID, senderUserID: UUID) -> String? {
        guard let key = historyKey else { return nil }
        return plaintextCache.text(for: messageID, senderUserID: senderUserID, historyKey: key)
    }

    func saveSealedPlaintext(messageID: UUID, senderUserID: UUID, data: Data) {
        guard let key = historyKey else { return }
        plaintextCache.save(messageID: messageID, senderUserID: senderUserID, data: data, historyKey: key)
    }

    func saveSealedPlaintext(messageID: UUID, senderUserID: UUID, text: String) {
        guard let key = historyKey else { return }
        plaintextCache.save(messageID: messageID, senderUserID: senderUserID, text: text, historyKey: key)
    }

    func sealedMedia(for messageID: UUID) -> Data? {
        guard let key = historyKey else { return nil }
        return mediaCache.data(for: messageID, historyKey: key)
    }

    func saveSealedMedia(messageID: UUID, data: Data) {
        guard let key = historyKey else { return }
        mediaCache.save(messageID: messageID, data: data, historyKey: key)
    }

    func hasFileBlob(_ messageID: UUID) -> Bool {
        fileStore.hasBlob(messageID)
    }

    /// Fills `previewData` / `mediaByteCount` from the sealed media payload when present.
    func attachEnvelopePreview(to message: inout MessagingController.ChatMessage) {
        guard message.kind == .image || message.kind == .video || message.kind == .file || message.hasLargeLinkImage
        else { return }
        guard let plain = sealedPlaintext(for: message.id, senderUserID: message.senderUserID),
              let payload = MediaMessagePayload.parse(plain)
        else { return }
        if message.previewData == nil {
            message.previewData = payload.previewJPEG
        }
        if message.mediaByteCount == nil {
            message.mediaByteCount = payload.s
        }
        if message.kind == .file, message.fileName == nil, let name = payload.n {
            message.fileName = SharedFile.cleanName(name)
        }
        if message.kind == .file, message.filePageCount == nil {
            message.filePageCount = payload.pg
        }
        // Video: keep a poster even when full file is already cached.
        if message.kind == .video, message.imageData == nil, let preview = message.previewData {
            message.imageData = preview
        }
    }

    func removeCaches(messageIDs: [UUID]) {
        plaintextCache.remove(messageIDs: messageIDs)
        mediaCache.remove(messageIDs: messageIDs)
        fileStore.remove(messageIDs: messageIDs)
        for id in messageIDs { FileOpenStaging.remove(for: id) }
    }

    // MARK: - Hydrate / persist

    /// Loads roster + threads from disk. Returns empty threads (with Notes key) if locked/missing.
    func hydrate(userID: UUID?) -> HydratedState {
        var state = HydratedState()
        state.threads[LocalMessageStore.notesPeerID] = []

        guard let userID, let key = historyKey else { return state }

        plaintextCache.migrateLegacyIfNeeded(historyKey: key)

        guard var snapshot = messageStore.load(userID: userID, historyKey: key) else {
            return state
        }

        let pruned = messageStore.prune(snapshot)
        snapshot = pruned.0
        if !pruned.1.isEmpty {
            removeCaches(messageIDs: pruned.1)
            messageStore.save(snapshot, userID: userID, historyKey: key)
        }

        state.contacts = snapshot.contacts.map { $0.toDTO() }
        state.incomingRequests = snapshot.incomingRequests.map { $0.toDTO() }
        state.conversations = snapshot.conversations.map { $0.toDTO() }

        var scrubbed: [UUID] = []
        for (mapKey, stored) in snapshot.threads {
            guard let peerID = UUID(uuidString: mapKey) else { continue }
            let scrubbedBefore = scrubbed.count
            let messages = stored.map { row -> MessagingController.ChatMessage in
                var message = row.toChatMessage(media: mediaCache, files: fileStore, historyKey: key)
                // Bind a pre-sender cache file to the sender this device stored, before any
                // server refresh can ask for the same id under someone else's name.
                if !message.deleted {
                    plaintextCache.bindLegacy(messageID: message.id, senderUserID: message.senderUserID, historyKey: key)
                }
                // Offline open: restore envelope preview/size without downloading full media.
                attachEnvelopePreview(to: &message)
                // Older builds merged a missed delete in with the old content still attached.
                if message.deleted {
                    let tombstone = ThreadMessageMerge.tombstone(of: message)
                    if message != tombstone {
                        scrubbed.append(message.id)
                        message = tombstone
                    }
                }
                return message
            }
            if scrubbed.count > scrubbedBefore {
                let rows = messages.map(LocalMessageStore.StoredMessage.from)
                messageStore.saveThread(peerID: peerID, messages: rows, userID: userID, historyKey: key)
                snapshot.threads[mapKey] = rows
            }
            state.threads[peerID] = messages
            recachePlaintext(of: messages)
        }
        if !scrubbed.isEmpty {
            removeCaches(messageIDs: scrubbed)
        }
        // What was just read (or re-saved after the prune or the scrub) is what the files hold.
        var loaded = Written(userID: userID)
        loaded.roster = Self.comparable(LocalMessageStore.Roster(
            conversations: snapshot.conversations,
            contacts: snapshot.contacts,
            incomingRequests: snapshot.incomingRequests,
            unreadByPeer: snapshot.unreadByPeer
        ))
        loaded.threads = snapshot.threads
        // Left unknown: a thread file that did not decode is on disk but not in the snapshot,
        // and the first save should still clear it out.
        written = loaded
        if state.threads[LocalMessageStore.notesPeerID] == nil {
            state.threads[LocalMessageStore.notesPeerID] = []
        }

        for (mapKey, count) in snapshot.unreadByPeer {
            guard let peerID = UUID(uuidString: mapKey) else { continue }
            state.unreadByPeer[peerID] = count
        }
        return state
    }

    /// Writes roster + all peer threads. Prunes 90-day window; returns IDs dropped from memory.
    @discardableResult
    func persist(
        userID: UUID?,
        contacts: [ContactItemDTO],
        incomingRequests: [ContactRequestDTO],
        conversations: [ConversationItemDTO],
        threads: [UUID: [MessagingController.ChatMessage]],
        unreadByPeer: [UUID: Int]
    ) -> Set<UUID> {
        guard let userID, let key = historyKey else { return [] }

        var snapshot = LocalMessageStore.Snapshot()
        snapshot.conversations = conversations.map(LocalMessageStore.CachedConversation.init)
        snapshot.contacts = contacts.map(LocalMessageStore.CachedContact.init)
        snapshot.incomingRequests = incomingRequests.map(LocalMessageStore.CachedContactRequest.init)

        var threadMap: [String: [LocalMessageStore.StoredMessage]] = [:]
        for (peerID, messages) in threads {
            threadMap[peerID.uuidString.lowercased()] = messages.map(LocalMessageStore.StoredMessage.from)
            recachePlaintext(of: messages)
        }
        snapshot.threads = threadMap

        var unread: [String: Int] = [:]
        for (peerID, count) in unreadByPeer where count > 0 {
            unread[peerID.uuidString.lowercased()] = count
        }
        snapshot.unreadByPeer = unread

        let pruned = messageStore.prune(snapshot)
        if !pruned.1.isEmpty {
            removeCaches(messageIDs: pruned.1)
        }
        writeSnapshot(pruned.0, userID: userID, historyKey: key)
        return Set(pruned.1)
    }

    /// Cheaper path: update one peer thread + roster metadata without rewriting every peer.
    func persistThread(
        peerID: UUID,
        messages: [MessagingController.ChatMessage],
        userID: UUID?,
        conversations: [ConversationItemDTO],
        contacts: [ContactItemDTO],
        incomingRequests: [ContactRequestDTO],
        unreadByPeer: [UUID: Int]
    ) {
        guard let userID, let key = historyKey else { return }

        let stored = messages.map(LocalMessageStore.StoredMessage.from)
        // Prune this peer alone for retention (notes exempt).
        let prunedMessages: [LocalMessageStore.StoredMessage]
        if peerID == LocalMessageStore.notesPeerID {
            prunedMessages = stored
        } else {
            let cutoff = Calendar.current.date(
                byAdding: .day,
                value: -LocalMessageStore.retentionDays,
                to: Date()
            ) ?? Date().addingTimeInterval(-TimeInterval(LocalMessageStore.retentionDays) * 86_400)
            var dropped: [UUID] = []
            prunedMessages = stored.filter { message in
                if message.createdAt >= cutoff { return true }
                if message.pendingSync == true { return true }
                dropped.append(message.id)
                return false
            }
            if !dropped.isEmpty {
                removeCaches(messageIDs: dropped)
            }
        }
        writeThread(peerID.uuidString.lowercased(), messages: prunedMessages, userID: userID, historyKey: key)
        recachePlaintext(of: messages)

        // Roster always saved so list previews / unread stay current.
        var unread: [String: Int] = [:]
        for (id, count) in unreadByPeer where count > 0 {
            unread[id.uuidString.lowercased()] = count
        }
        let roster = LocalMessageStore.Roster(
            version: 1,
            conversations: conversations.map(LocalMessageStore.CachedConversation.init),
            contacts: contacts.map(LocalMessageStore.CachedContact.init),
            incomingRequests: incomingRequests.map(LocalMessageStore.CachedContactRequest.init),
            unreadByPeer: unread,
            updatedAt: Date()
        )
        writeRoster(roster, userID: userID, historyKey: key)
    }

    // MARK: - Writes

    /// Keeps each text message's sealed plaintext equal to the wire it decodes from, quote and
    /// preview included, so a decode from this cache rebuilds the same bubble. A link message
    /// whose picture is a media blob keeps its sealed media payload: that holds the blob key.
    /// Each save is an atomic write plus a read-back, so an entry that already holds these
    /// bytes is left alone.
    private func recachePlaintext(of messages: [MessagingController.ChatMessage]) {
        guard let key = historyKey else { return }
        for message in messages where !message.deleted {
            plaintextCache.bindLegacy(messageID: message.id, senderUserID: message.senderUserID, historyKey: key)
        }
        for message in messages
            where !message.deleted && message.kind == .text && message.mediaObjectId == nil
        {
            guard !ThreadMessageMerge.isFailedDecryptText(message.text) else { continue }
            let wire = MessageTextPayload.wire(
                body: message.text,
                replyTo: message.replyTo,
                linkPreview: message.linkPreview
            )
            if sealedPlaintextText(for: message.id, senderUserID: message.senderUserID) != wire {
                saveSealedPlaintext(messageID: message.id, senderUserID: message.senderUserID, text: wire)
            }
        }
    }

    /// `updatedAt` is stamped on every write; it is not part of what the roster holds.
    private static func comparable(_ roster: LocalMessageStore.Roster) -> LocalMessageStore.Roster {
        var copy = roster
        copy.updatedAt = .distantPast
        return copy
    }

    private func writtenState(for userID: UUID) -> Written {
        if let written, written.userID == userID { return written }
        return Written(userID: userID)
    }

    private func writeRoster(_ roster: LocalMessageStore.Roster, userID: UUID, historyKey: SymmetricKey) {
        var state = writtenState(for: userID)
        let content = Self.comparable(roster)
        guard state.roster != content else { return }
        messageStore.saveRoster(roster, userID: userID, historyKey: historyKey)
        state.roster = content
        written = state
    }

    private func writeThread(
        _ peerKey: String,
        messages: [LocalMessageStore.StoredMessage],
        userID: UUID,
        historyKey: SymmetricKey
    ) {
        guard let peerID = UUID(uuidString: peerKey) else { return }
        var state = writtenState(for: userID)
        guard state.threads[peerKey] != messages else { return }
        messageStore.saveThread(peerID: peerID, messages: messages, userID: userID, historyKey: historyKey)
        state.threads[peerKey] = messages
        state.peers?.insert(peerKey)
        written = state
    }

    /// `LocalMessageStore.save`, one file at a time, skipping the files that would not change.
    private func writeSnapshot(_ snapshot: LocalMessageStore.Snapshot, userID: UUID, historyKey: SymmetricKey) {
        writeRoster(
            LocalMessageStore.Roster(
                conversations: snapshot.conversations,
                contacts: snapshot.contacts,
                incomingRequests: snapshot.incomingRequests,
                unreadByPeer: snapshot.unreadByPeer
            ),
            userID: userID,
            historyKey: historyKey
        )
        for (peerKey, messages) in snapshot.threads {
            writeThread(peerKey, messages: messages, userID: userID, historyKey: historyKey)
        }
        let wanted = Set(snapshot.threads.keys)
        var state = writtenState(for: userID)
        guard state.peers != wanted else { return }
        messageStore.removeThreads(notIn: wanted, userID: userID)
        state.peers = wanted
        state.threads = state.threads.filter { wanted.contains($0.key) }
        written = state
    }

    // MARK: - Reaction catch-up cursors

    /// Peer → highest reaction `seq` already applied. Nil while locked or when the file does not
    /// open — never an empty map a later save would write over every chat's cursor with.
    func reactionCursors(userID: UUID?) -> [UUID: Int64]? {
        guard let userID, let key = historyKey,
              let stored = messageStore.loadReactionCursors(userID: userID, historyKey: key)
        else { return nil }
        var cursors: [UUID: Int64] = [:]
        for (peer, seq) in stored.byPeer {
            if let peerID = UUID(uuidString: peer) { cursors[peerID] = seq }
        }
        return cursors
    }

    func saveReactionCursors(_ cursors: [UUID: Int64], userID: UUID?) {
        guard let userID, let key = historyKey else { return }
        var stored = LocalMessageStore.ReactionCursors()
        for (peerID, seq) in cursors {
            stored.byPeer[peerID.uuidString.lowercased()] = seq
        }
        messageStore.saveReactionCursors(stored, userID: userID, historyKey: key)
    }

    func clearAll() {
        historyKey = nil
        written = nil
        messageStore.clearAll()
        plaintextCache.clearAll()
        mediaCache.clearAll()
        fileStore.clearAll()
    }

    func clear(userID: UUID?) {
        historyKey = nil
        written = nil
        if let userID {
            messageStore.clear(userID: userID)
        } else {
            messageStore.clearAll()
        }
        plaintextCache.clearAll()
        mediaCache.clearAll()
        fileStore.clearAll()
    }
}
