import CryptoKit
import Foundation

/// Durable on-device message + list snapshot for offline use (90-day window for peer chats).
///
/// Human: Open the app on a plane and your recent chats still load; notes stay forever.
/// Disk is **always** AES-256-GCM ciphertext under the phrase-derived history key — a filesystem
/// dump alone cannot read chats.
///
/// Agent: Application Support sealed blob per user. Requires `historyKey` to load/save.
/// Peer threads pruned at 90 days; Notes exempt. Media binaries live in `LocalMediaCache`.
struct LocalMessageStore: Sendable {
    /// Peer conversations older than this are dropped from the local index.
    static let retentionDays: Int = 90

    /// Stable local-only peer id for the "Notes to me" chat (never sent to the API).
    static let notesPeerID = UUID(uuidString: "00000000-0000-4000-8000-6E6F74657321")!

    private let fileManager: FileManager

    init(fileManager: FileManager = .default) {
        self.fileManager = fileManager
    }

    // MARK: - Snapshot types

    struct Snapshot: Codable, Equatable, Sendable {
        var version: Int = 1
        var conversations: [CachedConversation] = []
        var contacts: [CachedContact] = []
        var incomingRequests: [CachedContactRequest] = []
        /// peerUserID.uuidString.lowercased() → messages (oldest first)
        var threads: [String: [StoredMessage]] = [:]
        var unreadByPeer: [String: Int] = [:]
        var updatedAt: Date = Date()
    }

    struct CachedConversation: Codable, Equatable, Sendable {
        let id: UUID
        let peerID: UUID
        let peerUsername: String
        let createdAt: Date
        let lastMessageAt: Date?
    }

    struct CachedContact: Codable, Equatable, Sendable {
        let userId: UUID
        let username: String
        let createdAt: Date
    }

    struct CachedContactRequest: Codable, Equatable, Sendable {
        let id: UUID
        let fromUserId: UUID
        let toUserId: UUID
        let status: String
        let createdAt: Date
        let respondedAt: Date?
        let username: String?
    }

    /// Message row without large media blobs (those live in `LocalMediaCache`).
    struct StoredMessage: Codable, Equatable, Sendable {
        let id: UUID
        let peerUserID: UUID
        let senderUserID: UUID
        let text: String
        let createdAt: Date
        let isMine: Bool
        let deleted: Bool
        var receipt: String
        var kind: String
        var mediaObjectId: UUID?
        var imageWidth: Int?
        var imageHeight: Int?
        var voiceDurationMs: Int?
        var voiceWaveform: [UInt8]?
        var transcript: String?
        var sendError: String?
        /// Local Notes todo completion (nil for non-todos).
        var todoDone: Bool?
        /// True while waiting for a network send (outbound queue).
        var pendingSync: Bool?

        static func from(_ message: MessagingController.ChatMessage) -> StoredMessage {
            StoredMessage(
                id: message.id,
                peerUserID: message.peerUserID,
                senderUserID: message.senderUserID,
                text: message.text,
                createdAt: message.createdAt,
                isMine: message.isMine,
                deleted: message.deleted,
                receipt: message.receipt.storageKey,
                kind: message.kind.storageKey,
                mediaObjectId: message.mediaObjectId,
                imageWidth: message.imageWidth,
                imageHeight: message.imageHeight,
                voiceDurationMs: message.voiceDurationMs,
                voiceWaveform: message.voiceWaveform,
                transcript: message.transcript,
                sendError: message.sendError,
                todoDone: message.todoDone,
                pendingSync: message.pendingSync ? true : nil
            )
        }

        func toChatMessage(media: LocalMediaCache, historyKey: SymmetricKey) -> MessagingController.ChatMessage {
            let kind = MessagingController.ChatMessageKind(storageKey: kind) ?? .text
            var imageData: Data?
            var voiceData: Data?
            if kind == .image {
                imageData = media.data(for: id, historyKey: historyKey)
            } else if kind == .voice {
                voiceData = media.data(for: id, historyKey: historyKey)
            }
            return MessagingController.ChatMessage(
                id: id,
                peerUserID: peerUserID,
                senderUserID: senderUserID,
                text: text,
                createdAt: createdAt,
                isMine: isMine,
                deleted: deleted,
                receipt: MessageReceiptStatus(storageKey: receipt) ?? .sent,
                kind: kind,
                mediaObjectId: mediaObjectId,
                imageWidth: imageWidth,
                imageHeight: imageHeight,
                imageData: imageData,
                voiceData: voiceData,
                voiceDurationMs: voiceDurationMs,
                voiceWaveform: voiceWaveform,
                transcript: transcript,
                sendError: sendError,
                todoDone: todoDone,
                pendingSync: pendingSync == true
            )
        }
    }

    // MARK: - Paths

    private func directoryURL(for userID: UUID) -> URL {
        let base = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? fileManager.temporaryDirectory
        let dir = base
            .appendingPathComponent("shroud", isDirectory: true)
            .appendingPathComponent("messages", isDirectory: true)
            .appendingPathComponent(userID.uuidString.lowercased(), isDirectory: true)
        LocalDataProtection.prepareDirectory(dir)
        return dir
    }

    private func sealedURL(for userID: UUID) -> URL {
        directoryURL(for: userID).appendingPathComponent("snapshot.sealed")
    }

    /// Legacy plaintext path (migrated once, then deleted).
    private func legacyPlainURL(for userID: UUID) -> URL {
        directoryURL(for: userID).appendingPathComponent("snapshot.json")
    }

    // MARK: - Load / save (encrypted)

    /// Decrypts and returns the snapshot. Requires the phrase-derived history key.
    func load(userID: UUID, historyKey: SymmetricKey) -> Snapshot? {
        let sealedPath = sealedURL(for: userID)
        if let sealed = try? Data(contentsOf: sealedPath),
           let plain = try? LocalHistoryCrypto.open(
               sealed,
               masterKey: historyKey,
               context: .messagesSnapshot
           ),
           let snapshot = try? JSONDecoder.localStore.decode(Snapshot.self, from: plain)
        {
            return snapshot
        }

        // One-shot migration: legacy plaintext → sealed, then wipe the plaintext file.
        let legacy = legacyPlainURL(for: userID)
        if let plain = try? Data(contentsOf: legacy),
           let snapshot = try? JSONDecoder.localStore.decode(Snapshot.self, from: plain)
        {
            save(snapshot, userID: userID, historyKey: historyKey)
            try? fileManager.removeItem(at: legacy)
            return snapshot
        }
        return nil
    }

    /// Encrypts and atomically writes the snapshot. Never writes plaintext JSON.
    func save(_ snapshot: Snapshot, userID: UUID, historyKey: SymmetricKey) {
        var copy = snapshot
        copy.updatedAt = Date()
        guard let plain = try? JSONEncoder.localStore.encode(copy),
              let sealed = try? LocalHistoryCrypto.seal(
                  plain,
                  masterKey: historyKey,
                  context: .messagesSnapshot
              )
        else { return }
        let url = sealedURL(for: userID)
        try? sealed.write(to: url, options: .atomic)
        LocalDataProtection.lockDown(url: url)
        // Ensure no leftover plaintext from older builds.
        try? fileManager.removeItem(at: legacyPlainURL(for: userID))
    }

    /// Removes the entire on-disk snapshot for a user (logout wipe).
    func clear(userID: UUID) {
        let dir = directoryURL(for: userID)
        try? fileManager.removeItem(at: dir)
    }

    /// Drops every user's local message store (full local wipe).
    func clearAll() {
        let base = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? fileManager.temporaryDirectory
        let dir = base
            .appendingPathComponent("shroud", isDirectory: true)
            .appendingPathComponent("messages", isDirectory: true)
        try? fileManager.removeItem(at: dir)
    }

    // MARK: - Prune

    /// Returns a pruned snapshot and the message IDs that were dropped (for media/plaintext cleanup).
    func prune(_ snapshot: Snapshot, now: Date = Date()) -> (Snapshot, [UUID]) {
        let cutoff = Calendar.current.date(
            byAdding: .day,
            value: -Self.retentionDays,
            to: now
        ) ?? now.addingTimeInterval(-TimeInterval(Self.retentionDays) * 86_400)

        var dropped: [UUID] = []
        var threads: [String: [StoredMessage]] = [:]
        let notesKey = Self.notesPeerID.uuidString.lowercased()

        for (key, messages) in snapshot.threads {
            if key == notesKey {
                // Notes are personal storage — keep until the user deletes them.
                threads[key] = messages
                continue
            }
            let kept = messages.filter { message in
                if message.createdAt >= cutoff { return true }
                // Keep unsynced outbound so a long offline period still flushes.
                if message.pendingSync == true { return true }
                dropped.append(message.id)
                return false
            }
            if !kept.isEmpty {
                threads[key] = kept
            }
        }

        var copy = snapshot
        copy.threads = threads
        // Drop conversation rows whose peer thread is gone and last activity is old.
        copy.conversations = snapshot.conversations.filter { conv in
            let peerKey = conv.peerID.uuidString.lowercased()
            if threads[peerKey] != nil { return true }
            if let last = conv.lastMessageAt { return last >= cutoff }
            return conv.createdAt >= cutoff
        }
        return (copy, dropped)
    }
}

// MARK: - DTO bridges

extension LocalMessageStore.CachedConversation {
    init(_ dto: ConversationItemDTO) {
        id = dto.id
        peerID = dto.peer.id
        peerUsername = dto.peer.username
        createdAt = dto.createdAt
        lastMessageAt = dto.lastMessageAt
    }

    func toDTO() -> ConversationItemDTO {
        ConversationItemDTO(
            id: id,
            peer: ConversationPeerDTO(id: peerID, username: peerUsername),
            createdAt: createdAt,
            lastMessageAt: lastMessageAt
        )
    }
}

extension LocalMessageStore.CachedContact {
    init(_ dto: ContactItemDTO) {
        userId = dto.userId
        username = dto.username
        createdAt = dto.createdAt
    }

    func toDTO() -> ContactItemDTO {
        ContactItemDTO(userId: userId, username: username, createdAt: createdAt)
    }
}

extension LocalMessageStore.CachedContactRequest {
    init(_ dto: ContactRequestDTO) {
        id = dto.id
        fromUserId = dto.fromUserId
        toUserId = dto.toUserId
        status = dto.status
        createdAt = dto.createdAt
        respondedAt = dto.respondedAt
        username = dto.user?.username
    }

    func toDTO() -> ContactRequestDTO {
        ContactRequestDTO(
            id: id,
            fromUserId: fromUserId,
            toUserId: toUserId,
            status: status,
            createdAt: createdAt,
            respondedAt: respondedAt,
            user: username.map { UserCardDTO(id: fromUserId, username: $0, shareCode: nil) }
        )
    }
}

// MARK: - Kind / receipt storage keys

extension MessagingController.ChatMessageKind {
    var storageKey: String {
        switch self {
        case .text: "text"
        case .image: "image"
        case .voice: "voice"
        case .todo: "todo"
        }
    }

    init?(storageKey: String) {
        switch storageKey {
        case "text": self = .text
        case "image": self = .image
        case "voice": self = .voice
        case "todo": self = .todo
        default: return nil
        }
    }
}

extension MessageReceiptStatus {
    var storageKey: String {
        switch self {
        case .failed: "failed"
        case .sending: "sending"
        case .sent: "sent"
        case .delivered: "delivered"
        case .read: "read"
        }
    }

    init?(storageKey: String) {
        switch storageKey {
        case "failed": self = .failed
        case "sending": self = .sending
        case "sent": self = .sent
        case "delivered": self = .delivered
        case "read": self = .read
        default: return nil
        }
    }
}

// MARK: - Local JSON codec (ISO-8601 fractional)

extension JSONDecoder {
    static let localStore: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let string = try container.decode(String.self)
            if let date = ISO8601DateFormatter.localStoreFractional.date(from: string) {
                return date
            }
            if let date = ISO8601DateFormatter.localStore.date(from: string) {
                return date
            }
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "Invalid date: \(string)"
            )
        }
        return decoder
    }()
}

extension JSONEncoder {
    static let localStore: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .custom { date, encoder in
            var container = encoder.singleValueContainer()
            try container.encode(ISO8601DateFormatter.localStoreFractional.string(from: date))
        }
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }()
}

private extension ISO8601DateFormatter {
    static let localStoreFractional: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    static let localStore: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()
}
