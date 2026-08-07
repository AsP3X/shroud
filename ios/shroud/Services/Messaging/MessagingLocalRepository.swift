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

    private(set) var historyKey: SymmetricKey?

    func setHistoryKey(_ key: SymmetricKey?) {
        historyKey = key
        if key == nil {
            plaintextCache.clearMemory()
        }
    }

    /// Revoke history key + L1 plaintext so sealed disk files cannot be opened until unlock.
    func lockSensitiveMemory() {
        historyKey = nil
        plaintextCache.clearMemory()
    }

    // MARK: - Decrypt cache (one-shot DR payloads)

    func sealedPlaintext(for messageID: UUID) -> Data? {
        guard let key = historyKey else { return nil }
        return plaintextCache.data(for: messageID, historyKey: key)
    }

    func sealedPlaintextText(for messageID: UUID) -> String? {
        guard let key = historyKey else { return nil }
        return plaintextCache.text(for: messageID, historyKey: key)
    }

    func saveSealedPlaintext(messageID: UUID, data: Data) {
        guard let key = historyKey else { return }
        plaintextCache.save(messageID: messageID, data: data, historyKey: key)
    }

    func saveSealedPlaintext(messageID: UUID, text: String) {
        guard let key = historyKey else { return }
        plaintextCache.save(messageID: messageID, text: text, historyKey: key)
    }

    func sealedMedia(for messageID: UUID) -> Data? {
        guard let key = historyKey else { return nil }
        return mediaCache.data(for: messageID, historyKey: key)
    }

    func saveSealedMedia(messageID: UUID, data: Data) {
        guard let key = historyKey else { return }
        mediaCache.save(messageID: messageID, data: data, historyKey: key)
    }

    func removeCaches(messageIDs: [UUID]) {
        plaintextCache.remove(messageIDs: messageIDs)
        mediaCache.remove(messageIDs: messageIDs)
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

        for (mapKey, stored) in snapshot.threads {
            guard let peerID = UUID(uuidString: mapKey) else { continue }
            let messages = stored.map {
                $0.toChatMessage(media: mediaCache, historyKey: key)
            }
            state.threads[peerID] = messages
            for message in messages where !message.deleted && message.kind == .text {
                if !ThreadMessageMerge.isFailedDecryptText(message.text) {
                    saveSealedPlaintext(messageID: message.id, text: message.text)
                }
            }
        }
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
            for message in messages where !message.deleted && message.kind == .text {
                if !ThreadMessageMerge.isFailedDecryptText(message.text) {
                    saveSealedPlaintext(messageID: message.id, text: message.text)
                }
            }
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
        messageStore.save(pruned.0, userID: userID, historyKey: key)
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
        messageStore.saveThread(
            peerID: peerID,
            messages: prunedMessages,
            userID: userID,
            historyKey: key
        )

        for message in messages where !message.deleted && message.kind == .text {
            if !ThreadMessageMerge.isFailedDecryptText(message.text) {
                saveSealedPlaintext(messageID: message.id, text: message.text)
            }
        }

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
        messageStore.saveRoster(roster, userID: userID, historyKey: key)
    }

    func clearAll() {
        historyKey = nil
        messageStore.clearAll()
        plaintextCache.clearAll()
        mediaCache.clearAll()
    }

    func clear(userID: UUID?) {
        historyKey = nil
        if let userID {
            messageStore.clear(userID: userID)
        } else {
            messageStore.clearAll()
        }
        plaintextCache.clearAll()
        mediaCache.clearAll()
    }
}
