import CryptoKit
import Foundation

/// Encrypted on-device message store: **roster** + **per-peer thread** sealed files.
///
/// Human: Offline chats stay available; each conversation is its own sealed file so one
/// new message does not rewrite every other chat.
///
/// Agent: Application Support `shroud/messages/{userId}/`.
/// - `roster.sealed` — conversations, contacts, requests, unread
/// - `threads/{peerId}.sealed` — `[StoredMessage]` for that peer
/// Migrates monolithic `snapshot.sealed` / `snapshot.json` once. Requires `historyKey`.
struct LocalMessageStore: Sendable {
    static let retentionDays: Int = 90
    static let notesPeerID = UUID(uuidString: "00000000-0000-4000-8000-6E6F74657321")!

    private let fileManager: FileManager

    init(fileManager: FileManager = .default) {
        self.fileManager = fileManager
    }

    // MARK: - Models

    /// Full in-memory picture (used for prune / tests / migration).
    struct Snapshot: Codable, Equatable, Sendable {
        var version: Int = 2
        var conversations: [CachedConversation] = []
        var contacts: [CachedContact] = []
        var incomingRequests: [CachedContactRequest] = []
        /// peerUserID.uuidString.lowercased() → messages (oldest first)
        var threads: [String: [StoredMessage]] = [:]
        var unreadByPeer: [String: Int] = [:]
        var updatedAt: Date = Date()
    }

    struct Roster: Codable, Equatable, Sendable {
        var version: Int = 1
        var conversations: [CachedConversation] = []
        var contacts: [CachedContact] = []
        var incomingRequests: [CachedContactRequest] = []
        var unreadByPeer: [String: Int] = [:]
        var updatedAt: Date = Date()
    }

    struct ThreadFile: Codable, Equatable, Sendable {
        var version: Int = 1
        var peerID: UUID
        var messages: [StoredMessage] = []
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
        var todoDone: Bool?
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
            var videoData: Data?
            switch kind {
            case .image:
                imageData = media.data(for: id, historyKey: historyKey)
            case .voice:
                voiceData = media.data(for: id, historyKey: historyKey)
            case .video:
                // Full video lives in the media cache; optional poster frame is not stored separately.
                videoData = media.data(for: id, historyKey: historyKey)
            case .text, .todo:
                break
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
                videoData: videoData,
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

    private func threadsDirectory(for userID: UUID) -> URL {
        let dir = directoryURL(for: userID).appendingPathComponent("threads", isDirectory: true)
        LocalDataProtection.prepareDirectory(dir)
        return dir
    }

    private func rosterURL(for userID: UUID) -> URL {
        directoryURL(for: userID).appendingPathComponent("roster.sealed")
    }

    private func threadURL(peerID: UUID, userID: UUID) -> URL {
        threadsDirectory(for: userID)
            .appendingPathComponent(peerID.uuidString.lowercased() + ".sealed")
    }

    private func legacySnapshotSealedURL(for userID: UUID) -> URL {
        directoryURL(for: userID).appendingPathComponent("snapshot.sealed")
    }

    private func legacySnapshotPlainURL(for userID: UUID) -> URL {
        directoryURL(for: userID).appendingPathComponent("snapshot.json")
    }

    // MARK: - Public load / save

    /// Loads roster + all peer threads (migrates legacy snapshot if needed).
    func load(userID: UUID, historyKey: SymmetricKey) -> Snapshot? {
        migrateLegacySnapshotIfNeeded(userID: userID, historyKey: historyKey)

        guard let roster = loadRoster(userID: userID, historyKey: historyKey) else {
            // Empty install — still OK.
            return Snapshot()
        }

        var snapshot = Snapshot()
        snapshot.version = 2
        snapshot.conversations = roster.conversations
        snapshot.contacts = roster.contacts
        snapshot.incomingRequests = roster.incomingRequests
        snapshot.unreadByPeer = roster.unreadByPeer
        snapshot.updatedAt = roster.updatedAt

        let threadDir = threadsDirectory(for: userID)
        guard let files = try? fileManager.contentsOfDirectory(
            at: threadDir,
            includingPropertiesForKeys: nil
        ) else {
            return snapshot
        }

        for file in files where file.pathExtension == "sealed" {
            let name = file.deletingPathExtension().lastPathComponent
            guard let peerID = UUID(uuidString: name),
                  let messages = loadThread(peerID: peerID, userID: userID, historyKey: historyKey)
            else { continue }
            snapshot.threads[peerID.uuidString.lowercased()] = messages
        }
        return snapshot
    }

    func loadRoster(userID: UUID, historyKey: SymmetricKey) -> Roster? {
        decodeSealed(rosterURL(for: userID), as: Roster.self, historyKey: historyKey)
    }

    func saveRoster(_ roster: Roster, userID: UUID, historyKey: SymmetricKey) {
        var copy = roster
        copy.updatedAt = Date()
        encodeSealed(copy, to: rosterURL(for: userID), historyKey: historyKey)
    }

    func loadThread(peerID: UUID, userID: UUID, historyKey: SymmetricKey) -> [StoredMessage]? {
        guard let file = decodeSealed(
            threadURL(peerID: peerID, userID: userID),
            as: ThreadFile.self,
            historyKey: historyKey
        ) else { return nil }
        return file.messages
    }

    /// Writes a single peer thread without touching other peers or the roster.
    func saveThread(
        peerID: UUID,
        messages: [StoredMessage],
        userID: UUID,
        historyKey: SymmetricKey
    ) {
        let file = ThreadFile(
            version: 1,
            peerID: peerID,
            messages: messages,
            updatedAt: Date()
        )
        encodeSealed(file, to: threadURL(peerID: peerID, userID: userID), historyKey: historyKey)
    }

    /// Persists a full snapshot as roster + per-peer files (drops removed peers from disk).
    func save(_ snapshot: Snapshot, userID: UUID, historyKey: SymmetricKey) {
        let roster = Roster(
            version: 1,
            conversations: snapshot.conversations,
            contacts: snapshot.contacts,
            incomingRequests: snapshot.incomingRequests,
            unreadByPeer: snapshot.unreadByPeer,
            updatedAt: Date()
        )
        saveRoster(roster, userID: userID, historyKey: historyKey)

        let wantedPeers = Set(snapshot.threads.keys)
        for (key, messages) in snapshot.threads {
            guard let peerID = UUID(uuidString: key) else { continue }
            saveThread(peerID: peerID, messages: messages, userID: userID, historyKey: historyKey)
        }

        // Remove thread files for peers no longer in the snapshot.
        let threadDir = threadsDirectory(for: userID)
        if let files = try? fileManager.contentsOfDirectory(
            at: threadDir,
            includingPropertiesForKeys: nil
        ) {
            for file in files where file.pathExtension == "sealed" {
                let name = file.deletingPathExtension().lastPathComponent
                if !wantedPeers.contains(name.lowercased()) {
                    try? fileManager.removeItem(at: file)
                }
            }
        }

        // Drop legacy monolithic files after successful split save.
        try? fileManager.removeItem(at: legacySnapshotSealedURL(for: userID))
        try? fileManager.removeItem(at: legacySnapshotPlainURL(for: userID))
    }

    func clear(userID: UUID) {
        try? fileManager.removeItem(at: directoryURL(for: userID))
    }

    func clearAll() {
        let base = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? fileManager.temporaryDirectory
        let dir = base
            .appendingPathComponent("shroud", isDirectory: true)
            .appendingPathComponent("messages", isDirectory: true)
        try? fileManager.removeItem(at: dir)
    }

    // MARK: - Prune

    /// Returns a pruned snapshot and dropped message IDs (for media/plaintext cleanup).
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
                threads[key] = messages
                continue
            }
            let kept = messages.filter { message in
                if message.createdAt >= cutoff { return true }
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
        copy.conversations = snapshot.conversations.filter { conv in
            let peerKey = conv.peerID.uuidString.lowercased()
            if threads[peerKey] != nil { return true }
            if let last = conv.lastMessageAt { return last >= cutoff }
            return conv.createdAt >= cutoff
        }
        return (copy, dropped)
    }

    // MARK: - Migration

    /// One-shot: `snapshot.sealed` / `snapshot.json` → roster + per-peer thread files.
    private func migrateLegacySnapshotIfNeeded(userID: UUID, historyKey: SymmetricKey) {
        // Already on v2 layout?
        if fileManager.fileExists(atPath: rosterURL(for: userID).path) {
            return
        }

        var legacy: Snapshot?
        let sealedPath = legacySnapshotSealedURL(for: userID)
        if let sealed = try? Data(contentsOf: sealedPath),
           let plain = try? LocalHistoryCrypto.open(
               sealed,
               masterKey: historyKey,
               context: .messagesSnapshot
           ),
           let snap = try? JSONDecoder.localStore.decode(Snapshot.self, from: plain)
        {
            legacy = snap
        } else if let plain = try? Data(contentsOf: legacySnapshotPlainURL(for: userID)),
                  let snap = try? JSONDecoder.localStore.decode(Snapshot.self, from: plain)
        {
            legacy = snap
        }

        guard let snapshot = legacy else { return }
        save(snapshot, userID: userID, historyKey: historyKey)
    }

    // MARK: - Sealed I/O

    private func encodeSealed<T: Encodable>(
        _ value: T,
        to url: URL,
        historyKey: SymmetricKey
    ) {
        guard let plain = try? JSONEncoder.localStore.encode(value),
              let sealed = try? LocalHistoryCrypto.seal(
                  plain,
                  masterKey: historyKey,
                  context: .messagesSnapshot
              )
        else { return }
        try? sealed.write(to: url, options: .atomic)
        LocalDataProtection.lockDown(url: url)
    }

    private func decodeSealed<T: Decodable>(
        _ url: URL,
        as type: T.Type,
        historyKey: SymmetricKey
    ) -> T? {
        guard let sealed = try? Data(contentsOf: url),
              let plain = try? LocalHistoryCrypto.open(
                  sealed,
                  masterKey: historyKey,
                  context: .messagesSnapshot
              ),
              let value = try? JSONDecoder.localStore.decode(type, from: plain)
        else { return nil }
        return value
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
        case .video: "video"
        case .todo: "todo"
        }
    }

    init?(storageKey: String) {
        switch storageKey {
        case "text": self = .text
        case "image": self = .image
        case "voice": self = .voice
        case "video": self = .video
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

// MARK: - Local JSON codec

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
