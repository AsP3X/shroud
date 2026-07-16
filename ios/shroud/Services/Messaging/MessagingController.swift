import CryptoKit
import Foundation

/// Live contacts + chats state; seals plaintext with MessageCrypto before send.
@MainActor
@Observable
final class MessagingController {
    private(set) var contacts: [ContactItemDTO] = []
    private(set) var incomingRequests: [ContactRequestDTO] = []
    private(set) var conversations: [ConversationItemDTO] = []
    private(set) var isLoadingContacts = false
    private(set) var isLoadingChats = false
    private(set) var lastError: String?

    /// Decrypted messages by peer user id (newest last).
    private(set) var threads: [UUID: [ChatMessage]] = [:]
    private(set) var typingPeerIDs: Set<UUID> = []
    private(set) var presenceByUser: [UUID: PresenceDTO] = [:]
    /// Unread inbound counts by peer (local; cleared when the thread is opened).
    private(set) var unreadCountByPeer: [UUID: Int] = [:]
    /// Peer whose conversation is currently on screen (suppresses unread increments).
    private(set) var activePeerID: UUID?

    private let contactsService = ContactsService()
    private let messagesService = MessagesService()
    private let keyBundleService = KeyBundleService()
    private let peerKeys = PeerIdentityStore()
    private let plaintextCache = LocalPlaintextCache()
    private let realtime = RealtimeClient()

    private weak var sessionController: SessionController?
    private weak var cryptoController: CryptoController?

    struct ChatMessage: Identifiable, Equatable, Sendable {
        let id: UUID
        let peerUserID: UUID
        let senderUserID: UUID
        let text: String
        let createdAt: Date
        let isMine: Bool
        let deleted: Bool
    }

    func bind(session: SessionController, crypto: CryptoController) {
        sessionController = session
        cryptoController = crypto
        realtime.configure { [weak self] event in
            self?.handleRealtime(event)
        }
    }

    func start() {
        guard let token = sessionController?.bearerToken else { return }
        realtime.connect(token: token)
        Task {
            await refreshContacts()
            await refreshConversations()
        }
    }

    func stop() {
        realtime.disconnect()
        activePeerID = nil
    }

    func setActivePeer(_ peerID: UUID?) {
        activePeerID = peerID
        if let peerID {
            unreadCountByPeer[peerID] = 0
        }
    }

    func unreadCount(for peerID: UUID) -> Int {
        unreadCountByPeer[peerID] ?? 0
    }

    // MARK: - Contacts

    func refreshContacts() async {
        guard let token = sessionController?.bearerToken else { return }
        isLoadingContacts = true
        defer { isLoadingContacts = false }
        do {
            async let list = contactsService.listContacts(token: token)
            async let requests = contactsService.listIncomingRequests(token: token)
            contacts = try await list.sorted {
                $0.username.localizedCaseInsensitiveCompare($1.username) == .orderedAscending
            }
            incomingRequests = try await requests
            await refreshPresence(for: contacts.map(\.userId), token: token)
            lastError = nil
        } catch {
            lastError = SessionController.userMessage(for: error)
        }
    }

    /// Fetches presence for many users (contacts list). Failures are skipped per user.
    func refreshPresence(for userIDs: [UUID], token: String? = nil) async {
        guard let token = token ?? sessionController?.bearerToken else { return }
        let service = contactsService
        var updates: [UUID: PresenceDTO] = [:]
        await withTaskGroup(of: (UUID, PresenceDTO)?.self) { group in
            for userID in userIDs {
                group.addTask {
                    guard let presence = try? await service.presence(
                        userID: userID,
                        token: token
                    ) else { return nil }
                    return (userID, presence)
                }
            }
            for await result in group {
                if let (userID, presence) = result {
                    updates[userID] = presence
                }
            }
        }
        for (userID, presence) in updates {
            presenceByUser[userID] = presence
        }
    }

    /// Resolves share code, username, UUID, or invite link and sends a contact request.
    /// Returns `nil` on success, otherwise a user-facing error string.
    func addContact(fromInvite raw: String) async -> String? {
        guard let token = sessionController?.bearerToken else {
            return "Not signed in."
        }
        guard let invite = ContactInviteParser.parse(raw) else {
            return "Enter a share code, username, link, or user ID."
        }
        do {
            let card: UserCardDTO
            switch invite {
            case let .userID(id):
                card = try await contactsService.getUser(userID: id, token: token)
            case let .shareCode(code):
                card = try await contactsService.getUserByShareCode(code, token: token)
            case let .username(name):
                card = try await contactsService.getUserByUsername(name, token: token)
            }
            if card.id == sessionController?.userID {
                return "You can't add yourself."
            }
            _ = try await contactsService.createRequest(userID: card.id, token: token)
            await refreshContacts()
            return nil
        } catch {
            return SessionController.userMessage(for: error)
        }
    }

    /// Legacy UUID-only entry point (kept for call sites / tests).
    func addContact(byUserIDString raw: String) async -> String? {
        await addContact(fromInvite: raw)
    }

    func acceptRequest(_ request: ContactRequestDTO) async {
        guard let token = sessionController?.bearerToken else { return }
        do {
            try await contactsService.acceptRequest(id: request.id, token: token)
            await refreshContacts()
        } catch {
            lastError = SessionController.userMessage(for: error)
        }
    }

    func rejectRequest(_ request: ContactRequestDTO) async {
        guard let token = sessionController?.bearerToken else { return }
        do {
            try await contactsService.rejectRequest(id: request.id, token: token)
            await refreshContacts()
        } catch {
            lastError = SessionController.userMessage(for: error)
        }
    }

    // MARK: - Chats

    func refreshConversations() async {
        guard let token = sessionController?.bearerToken else { return }
        isLoadingChats = true
        defer { isLoadingChats = false }
        do {
            conversations = try await messagesService.listConversations(token: token)
            lastError = nil
        } catch {
            lastError = SessionController.userMessage(for: error)
        }
    }

    func loadThread(peerUserID: UUID) async {
        guard let token = sessionController?.bearerToken,
              let me = sessionController?.userID,
              let material = cryptoController?.material
        else { return }

        activePeerID = peerUserID
        unreadCountByPeer[peerUserID] = 0

        do {
            let response = try await messagesService.listMessages(peerUserID: peerUserID, token: token)
            var decoded: [ChatMessage] = []
            for dto in response.messages.reversed() {
                // Server returns newest-first; reverse for chronological UI.
                let message = await decodeMessage(dto, me: me, material: material, token: token)
                decoded.append(message)
                if dto.senderUserId != me {
                    try? await messagesService.markDelivered(messageID: dto.id, token: token)
                }
            }
            threads[peerUserID] = decoded
            if let lastFromPeer = decoded.last(where: { !$0.isMine }) {
                try? await messagesService.markRead(messageID: lastFromPeer.id, token: token)
            }
            // Presence for header.
            if let presence = try? await contactsService.presence(userID: peerUserID, token: token) {
                presenceByUser[peerUserID] = presence
            }
            lastError = nil
        } catch {
            lastError = SessionController.userMessage(for: error)
        }
    }

    func sendText(_ text: String, to peerUserID: UUID) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              let token = sessionController?.bearerToken,
              let me = sessionController?.userID,
              let material = cryptoController?.material
        else { return }

        do {
            let peerPub = try await resolvePeerIdentityPublicKey(peerUserID: peerUserID, token: token)
            let sealed = try MessageCrypto.seal(
                plaintext: Data(trimmed.utf8),
                toPeerIdentityPublicKey: peerPub,
                ourIdentityPublicKey: material.identityPublicKeyData
            )
            let ciphertextB64 = sealed.base64EncodedString()
            let clientID = UUID()
            let dto = try await messagesService.send(
                SendMessageRequest(
                    peerUserId: peerUserID,
                    clientMessageId: clientID,
                    contentType: "text",
                    ciphertext: ciphertextB64
                ),
                token: token
            )
            // Optional cache for UI speed; v2 envelopes also decrypt as sender without cache.
            plaintextCache.save(messageID: dto.id, text: trimmed)
            let chat = ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: me,
                text: trimmed,
                createdAt: dto.createdAt,
                isMine: true,
                deleted: false
            )
            var thread = threads[peerUserID] ?? []
            if !thread.contains(where: { $0.id == chat.id }) {
                thread.append(chat)
                threads[peerUserID] = thread
            }
            await refreshConversations()
            lastError = nil
        } catch {
            lastError = SessionController.userMessage(for: error)
        }
    }

    func setTyping(peerUserID: UUID, isTyping: Bool) {
        realtime.sendTyping(peerUserID: peerUserID, isTyping: isTyping)
    }

    func preview(for conversation: ConversationItemDTO) -> String {
        let peerID = conversation.peer.id
        if let last = threads[peerID]?.last {
            return last.deleted ? "Message deleted" : last.text
        }
        return "Encrypted conversation"
    }

    func timeLabel(for date: Date?) -> String {
        guard let date else { return "" }
        let calendar = Calendar.current
        if calendar.isDateInToday(date) {
            return date.formatted(date: .omitted, time: .shortened)
        }
        if calendar.isDateInYesterday(date) {
            return "Yesterday"
        }
        return date.formatted(date: .abbreviated, time: .omitted)
    }

    // MARK: - Private

    private func handleRealtime(_ event: RealtimeEvent) {
        switch event {
        case let .messageNew(dto):
            Task { await ingestIncoming(dto) }
        case let .raw(type, json):
            if type == "typing" {
                handleTyping(json)
            } else if type == "presence.update" {
                handlePresence(json)
            }
        }
    }

    private func handleTyping(_ json: [String: Any]) {
        guard let userString = json["user_id"] as? String,
              let userID = UUID(uuidString: userString),
              let isTyping = json["is_typing"] as? Bool
        else { return }
        if isTyping {
            typingPeerIDs.insert(userID)
        } else {
            typingPeerIDs.remove(userID)
        }
    }

    private func handlePresence(_ json: [String: Any]) {
        guard let userString = json["user_id"] as? String,
              let userID = UUID(uuidString: userString),
              let online = json["online"] as? Bool
        else { return }
        var lastSeen: Date?
        if let last = json["last_seen_at"] as? String {
            lastSeen = ISO8601DateFormatter.apiFlexible.date(from: last)
        }
        presenceByUser[userID] = PresenceDTO(userId: userID, online: online, lastSeenAt: lastSeen)
    }

    private func ingestIncoming(_ dto: MessageDTO) async {
        guard let me = sessionController?.userID,
              let material = cryptoController?.material,
              let token = sessionController?.bearerToken
        else { return }

        let peerID = dto.senderUserId == me
            ? (conversations.first(where: { $0.id == dto.conversationId })?.peer.id
                ?? threads.first(where: { $0.value.contains(where: { $0.id == dto.id }) })?.key)
            : dto.senderUserId

        // Prefer peer from conversation list or sender.
        let resolvedPeer = peerID ?? dto.senderUserId
        if dto.senderUserId != me {
            try? await messagesService.markDelivered(messageID: dto.id, token: token)
        }

        let chat = await decodeMessage(dto, me: me, material: material, token: token)
        // Attach to correct peer thread: if I sent from another device, peer is recipient.
        let threadPeer: UUID
        if dto.senderUserId == me {
            // Multi-device echo — find peer from conversations.
            if let conv = conversations.first(where: { $0.id == dto.conversationId }) {
                threadPeer = conv.peer.id
            } else {
                threadPeer = resolvedPeer
            }
        } else {
            threadPeer = dto.senderUserId
        }

        var thread = threads[threadPeer] ?? []
        if !thread.contains(where: { $0.id == chat.id }) {
            thread.append(chat)
            threads[threadPeer] = thread
            if !chat.isMine, activePeerID != threadPeer {
                unreadCountByPeer[threadPeer, default: 0] += 1
            }
            if !chat.isMine, activePeerID == threadPeer {
                try? await messagesService.markRead(messageID: chat.id, token: token)
            }
        }
        await refreshConversations()
    }

    private func decodeMessage(
        _ dto: MessageDTO,
        me: UUID,
        material: IdentityKeyMaterial,
        token: String
    ) async -> ChatMessage {
        let isMine = dto.senderUserId == me
        let peerUserID = isMine
            ? (conversations.first(where: { $0.id == dto.conversationId })?.peer.id ?? dto.senderUserId)
            : dto.senderUserId

        if dto.deletedForEveryone {
            return ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: dto.senderUserId,
                text: "Message deleted",
                createdAt: dto.createdAt,
                isMine: isMine,
                deleted: true
            )
        }

        guard let ciphertextB64 = dto.ciphertext,
              let envelopeData = Data(base64Encoded: ciphertextB64)
        else {
            return ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: dto.senderUserId,
                text: "[Unable to decrypt]",
                createdAt: dto.createdAt,
                isMine: isMine,
                deleted: false
            )
        }

        do {
            if isMine {
                if let existing = threads[peerUserID]?.first(where: { $0.id == dto.id }) {
                    return existing
                }
                if let cached = plaintextCache.text(for: dto.id) {
                    return ChatMessage(
                        id: dto.id,
                        peerUserID: peerUserID,
                        senderUserID: dto.senderUserId,
                        text: cached,
                        createdAt: dto.createdAt,
                        isMine: true,
                        deleted: false
                    )
                }
                // v2 dual-seal: open self box with our private key.
                if let plain = try? MessageCrypto.open(
                    envelopeData: envelopeData,
                    with: material.agreementPrivateKey,
                    ourIdentityPublicKey: material.identityPublicKeyData,
                    senderIdentityPublicKey: material.identityPublicKeyData,
                    as: .sender
                ), let text = String(data: plain, encoding: .utf8) {
                    plaintextCache.save(messageID: dto.id, text: text)
                    return ChatMessage(
                        id: dto.id,
                        peerUserID: peerUserID,
                        senderUserID: dto.senderUserId,
                        text: text,
                        createdAt: dto.createdAt,
                        isMine: true,
                        deleted: false
                    )
                }
                return ChatMessage(
                    id: dto.id,
                    peerUserID: peerUserID,
                    senderUserID: dto.senderUserId,
                    text: "[Encrypted message]",
                    createdAt: dto.createdAt,
                    isMine: true,
                    deleted: false
                )
            }

            let senderPub = try await resolvePeerIdentityPublicKey(
                peerUserID: dto.senderUserId,
                token: token
            )
            let plain = try MessageCrypto.open(
                envelopeData: envelopeData,
                with: material.agreementPrivateKey,
                ourIdentityPublicKey: material.identityPublicKeyData,
                senderIdentityPublicKey: senderPub,
                as: .recipient
            )
            let text = String(data: plain, encoding: .utf8) ?? "[Binary message]"
            return ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: dto.senderUserId,
                text: text,
                createdAt: dto.createdAt,
                isMine: false,
                deleted: false
            )
        } catch {
            return ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: dto.senderUserId,
                text: "[Unable to decrypt]",
                createdAt: dto.createdAt,
                isMine: isMine,
                deleted: false
            )
        }
    }

    private func resolvePeerIdentityPublicKey(peerUserID: UUID, token: String) async throws -> Data {
        if let cached = peerKeys.publicKeyData(for: peerUserID) {
            return cached
        }
        // Identity-only endpoint — does not consume OTPKs.
        let identity = try await keyBundleService.fetchIdentity(
            userID: peerUserID,
            bearerToken: token
        )
        peerKeys.save(userID: peerUserID, publicKeyBase64: identity.identityKey)
        guard let data = Data(base64Encoded: identity.identityKey) else {
            throw APIError.decoding
        }
        return data
    }
}

private extension ISO8601DateFormatter {
    static let apiFlexible: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
}
