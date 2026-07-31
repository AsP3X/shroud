import CryptoKit
import Foundation
import UIKit

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
    private let mediaService = MediaService()
    private let keyBundleService = KeyBundleService()
    private let peerKeys = PeerIdentityStore()
    private let plaintextCache = LocalPlaintextCache()
    private let mediaCache = LocalMediaCache()
    private let realtime = RealtimeClient()
    /// Polling fallback when the WebSocket is down (common behind some reverse proxies).
    private var pollTask: Task<Void, Never>?
    /// Optional call controller for WS call.* fan-in (bound from RootView).
    private weak var callController: CallController?

    private weak var sessionController: SessionController?
    private weak var cryptoController: CryptoController?

    /// Expose realtime health for diagnostics UI if needed.
    var isRealtimeConnected: Bool { realtime.isConnected }

    enum ChatMessageKind: Equatable, Sendable {
        case text
        case image
        case voice
    }

    struct ChatMessage: Identifiable, Equatable, Sendable {
        let id: UUID
        let peerUserID: UUID
        let senderUserID: UUID
        /// Caption, list preview ("Photo" / "Voice message"), or transcript snippet.
        let text: String
        let createdAt: Date
        let isMine: Bool
        let deleted: Bool
        /// Outbound only; ignored for inbound.
        var receipt: MessageReceiptStatus
        var kind: ChatMessageKind
        var mediaObjectId: UUID?
        var imageWidth: Int?
        var imageHeight: Int?
        var imageData: Data?
        /// Decrypted voice bytes (m4a) when loaded.
        var voiceData: Data?
        /// Voice duration in milliseconds.
        var voiceDurationMs: Int?
        /// On-device transcript (local or sealed in payload).
        var transcript: String?
        /// Set when an outbound send failed; bubble stays for retry.
        var sendError: String?

        init(
            id: UUID,
            peerUserID: UUID,
            senderUserID: UUID,
            text: String,
            createdAt: Date,
            isMine: Bool,
            deleted: Bool,
            receipt: MessageReceiptStatus = .sent,
            kind: ChatMessageKind = .text,
            mediaObjectId: UUID? = nil,
            imageWidth: Int? = nil,
            imageHeight: Int? = nil,
            imageData: Data? = nil,
            voiceData: Data? = nil,
            voiceDurationMs: Int? = nil,
            transcript: String? = nil,
            sendError: String? = nil
        ) {
            self.id = id
            self.peerUserID = peerUserID
            self.senderUserID = senderUserID
            self.text = text
            self.createdAt = createdAt
            self.isMine = isMine
            self.deleted = deleted
            self.receipt = isMine ? receipt : .sent
            self.kind = kind
            self.mediaObjectId = mediaObjectId
            self.imageWidth = imageWidth
            self.imageHeight = imageHeight
            self.imageData = imageData
            self.voiceData = voiceData
            self.voiceDurationMs = voiceDurationMs
            self.transcript = transcript
            self.sendError = sendError
        }
    }

    func bind(session: SessionController, crypto: CryptoController, calls: CallController? = nil) {
        sessionController = session
        cryptoController = crypto
        callController = calls
        realtime.configure { [weak self] event in
            self?.handleRealtime(event)
        }
    }

    func start() {
        guard let token = sessionController?.bearerToken else { return }
        realtime.connect(token: token)
        startPollingFallback()
        Task {
            await refreshContacts()
            await refreshConversations()
        }
    }

    func stop() {
        pollTask?.cancel()
        pollTask = nil
        realtime.disconnect(reconnect: false)
        activePeerID = nil
    }

    /// Call when the app returns to the foreground.
    func handleAppBecameActive() {
        guard let token = sessionController?.bearerToken else { return }
        realtime.connect(token: token)
        Task {
            await refreshConversations()
            if let peer = activePeerID {
                await loadThread(peerUserID: peer)
            }
        }
    }

    func setActivePeer(_ peerID: UUID?) {
        activePeerID = peerID
        if let peerID {
            unreadCountByPeer[peerID] = 0
        }
    }

    private func startPollingFallback() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            // When the WebSocket is down (or never connected), poll so messages still arrive.
            // While connected, a slower safety poll catches any missed events.
            var tick = 0
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                guard let self, !Task.isCancelled else { return }
                tick += 1
                let wsUp = self.realtime.isConnected
                if !wsUp {
                    await self.refreshConversations()
                    if let peer = self.activePeerID {
                        await self.loadThread(peerUserID: peer)
                    }
                } else if tick % 5 == 0 {
                    // ~15s backup while realtime is healthy
                    await self.refreshConversations()
                    if let peer = self.activePeerID {
                        await self.loadThread(peerUserID: peer)
                    }
                }
            }
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
            // Mark all inbound up to the latest so the peer gets read receipts.
            if let lastFromPeer = decoded.last(where: { !$0.isMine }) {
                try? await messagesService.markReadBulk(
                    peerUserID: peerUserID,
                    upToMessageID: lastFromPeer.id,
                    token: token
                )
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

        let optimisticID = UUID()
        let optimistic = ChatMessage(
            id: optimisticID,
            peerUserID: peerUserID,
            senderUserID: me,
            text: trimmed,
            createdAt: Date(),
            isMine: true,
            deleted: false,
            receipt: .sending
        )
        var optimisticThread = threads[peerUserID] ?? []
        optimisticThread.append(optimistic)
        threads[peerUserID] = optimisticThread

        do {
            let peerPub = try await resolvePeerIdentityPublicKey(peerUserID: peerUserID, token: token)
            let sealed = try MessageCrypto.seal(
                plaintext: Data(trimmed.utf8),
                peerUserID: peerUserID,
                toPeerIdentityPublicKey: peerPub,
                ourPrivateKey: material.agreementPrivateKey,
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
            plaintextCache.save(messageID: dto.id, text: trimmed)
            let sent = ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: me,
                text: trimmed,
                createdAt: dto.createdAt,
                isMine: true,
                deleted: false,
                receipt: receiptStatus(from: dto)
            )
            if var list = threads[peerUserID],
               let idx = list.firstIndex(where: { $0.id == optimisticID })
            {
                list[idx] = sent
                threads[peerUserID] = list
            } else {
                var list = threads[peerUserID] ?? []
                if !list.contains(where: { $0.id == sent.id }) {
                    list.append(sent)
                    threads[peerUserID] = list
                }
            }
            await refreshConversations()
            lastError = nil
        } catch {
            if var list = threads[peerUserID] {
                list.removeAll { $0.id == optimisticID }
                threads[peerUserID] = list
            }
            lastError = SessionController.userMessage(for: error)
        }
    }

    func setTyping(peerUserID: UUID, isTyping: Bool) {
        realtime.sendTyping(peerUserID: peerUserID, isTyping: isTyping)
    }

    /// Compresses, encrypts, uploads, and sends an image message to `peerUserID`.
    /// Optional `caption` is sealed in the media payload (Telegram-style).
    /// Returns a user-facing error string, or `nil` on success.
    func sendImage(
        _ image: UIImage,
        to peerUserID: UUID,
        caption: String = "",
        quality: MediaComposeQuality = .sd
    ) async -> String? {
        guard let token = sessionController?.bearerToken,
              let me = sessionController?.userID,
              let material = cryptoController?.material
        else { return "Not signed in." }

        let trimmedCaption = caption.trimmingCharacters(in: .whitespacesAndNewlines)
        let displayText = trimmedCaption.isEmpty ? "Photo" : trimmedCaption

        let optimisticID = UUID()
        let jpeg: (data: Data, width: Int, height: Int)
        do {
            let params = quality.encodeParams
            jpeg = try MediaCrypto.jpegData(
                from: image,
                maxEdge: params.maxEdge,
                quality: params.quality
            )
        } catch {
            return "Could not prepare that photo."
        }

        let optimistic = ChatMessage(
            id: optimisticID,
            peerUserID: peerUserID,
            senderUserID: me,
            text: displayText,
            createdAt: Date(),
            isMine: true,
            deleted: false,
            receipt: .sending,
            kind: .image,
            imageWidth: jpeg.width,
            imageHeight: jpeg.height,
            imageData: jpeg.data
        )
        var list = threads[peerUserID] ?? []
        list.append(optimistic)
        threads[peerUserID] = list

        do {
            try await finishImageSend(
                optimisticID: optimisticID,
                peerUserID: peerUserID,
                me: me,
                material: material,
                token: token,
                jpeg: jpeg,
                caption: trimmedCaption
            )
            lastError = nil
            return nil
        } catch {
            let message = SessionController.userMessage(for: error)
            markImageFailed(optimisticID: optimisticID, peerUserID: peerUserID, error: message)
            lastError = message
            return message
        }
    }

    /// Retries a failed outbound photo that still has local image data.
    func retryFailedImage(messageID: UUID, peerUserID: UUID) async -> String? {
        guard let token = sessionController?.bearerToken,
              let me = sessionController?.userID,
              let material = cryptoController?.material,
              var thread = threads[peerUserID],
              let idx = thread.firstIndex(where: { $0.id == messageID && $0.isMine && $0.kind == .image }),
              let data = thread[idx].imageData,
              let image = UIImage(data: data)
        else {
            return "Nothing to retry."
        }

        thread[idx].receipt = .sending
        thread[idx].sendError = nil
        threads[peerUserID] = thread

        let jpeg: (data: Data, width: Int, height: Int)
        do {
            jpeg = try MediaCrypto.jpegData(from: image)
        } catch {
            markImageFailed(optimisticID: messageID, peerUserID: peerUserID, error: "Could not prepare that photo.")
            return "Could not prepare that photo."
        }

        let existingCaption = thread[idx].text
        let caption = (existingCaption == "Photo" || existingCaption.isEmpty) ? "" : existingCaption

        do {
            try await finishImageSend(
                optimisticID: messageID,
                peerUserID: peerUserID,
                me: me,
                material: material,
                token: token,
                jpeg: jpeg,
                caption: caption
            )
            return nil
        } catch {
            let message = SessionController.userMessage(for: error)
            markImageFailed(optimisticID: messageID, peerUserID: peerUserID, error: message)
            return message
        }
    }

    private func finishImageSend(
        optimisticID: UUID,
        peerUserID: UUID,
        me: UUID,
        material: IdentityKeyMaterial,
        token: String,
        jpeg: (data: Data, width: Int, height: Int),
        caption: String
    ) async throws {
        let (fileKey, sealedFile) = try MediaCrypto.sealFile(jpeg.data)
        let upload = try await mediaService.createUpload(
            sizeBytes: sealedFile.count,
            contentType: "application/octet-stream",
            token: token
        )
        try await mediaService.uploadContent(
            mediaID: upload.mediaObjectId,
            data: sealedFile,
            token: token
        )

        let trimmedCaption = caption.trimmingCharacters(in: .whitespacesAndNewlines)
        let displayText = trimmedCaption.isEmpty ? "Photo" : trimmedCaption
        let payload = MediaMessagePayload(
            t: MediaMessagePayload.kindImage,
            mime: "image/jpeg",
            w: jpeg.width,
            h: jpeg.height,
            k: fileKey.base64EncodedString(),
            c: trimmedCaption.isEmpty ? nil : trimmedCaption
        )
        let payloadData = try JSONEncoder().encode(payload)
        let peerPub = try await resolvePeerIdentityPublicKey(peerUserID: peerUserID, token: token)
        let sealed = try MessageCrypto.seal(
            plaintext: payloadData,
            peerUserID: peerUserID,
            toPeerIdentityPublicKey: peerPub,
            ourPrivateKey: material.agreementPrivateKey,
            ourIdentityPublicKey: material.identityPublicKeyData
        )
        let dto = try await messagesService.send(
            SendMessageRequest(
                peerUserId: peerUserID,
                contentType: "media",
                ciphertext: sealed.base64EncodedString(),
                mediaObjectId: upload.mediaObjectId
            ),
            token: token
        )
        mediaCache.save(messageID: dto.id, data: jpeg.data)
        plaintextCache.save(messageID: dto.id, text: displayText)

        let sent = ChatMessage(
            id: dto.id,
            peerUserID: peerUserID,
            senderUserID: me,
            text: displayText,
            createdAt: dto.createdAt,
            isMine: true,
            deleted: false,
            receipt: receiptStatus(from: dto),
            kind: .image,
            mediaObjectId: upload.mediaObjectId,
            imageWidth: jpeg.width,
            imageHeight: jpeg.height,
            imageData: jpeg.data,
            sendError: nil
        )
        if var thread = threads[peerUserID],
           let idx = thread.firstIndex(where: { $0.id == optimisticID })
        {
            thread[idx] = sent
            threads[peerUserID] = thread
        }
        await refreshConversations()
    }

    /// Records are done by the view; this encrypts, uploads, and sends a voice message.
    /// Optional on-device transcript is sealed inside the media payload (never sent as plaintext).
    func sendVoice(
        audioData: Data,
        durationMs: Int,
        to peerUserID: UUID,
        transcript: String? = nil
    ) async -> String? {
        guard let token = sessionController?.bearerToken,
              let me = sessionController?.userID,
              let material = cryptoController?.material
        else { return "Not signed in." }

        let trimmedTranscript = transcript?.trimmingCharacters(in: .whitespacesAndNewlines)
        let displayText: String = {
            if let t = trimmedTranscript, !t.isEmpty { return t }
            return "Voice message"
        }()

        let optimisticID = UUID()
        let optimistic = ChatMessage(
            id: optimisticID,
            peerUserID: peerUserID,
            senderUserID: me,
            text: displayText,
            createdAt: Date(),
            isMine: true,
            deleted: false,
            receipt: .sending,
            kind: .voice,
            voiceData: audioData,
            voiceDurationMs: durationMs,
            transcript: trimmedTranscript
        )
        var list = threads[peerUserID] ?? []
        list.append(optimistic)
        threads[peerUserID] = list

        do {
            let (fileKey, sealedFile) = try MediaCrypto.sealFile(audioData)
            let upload = try await mediaService.createUpload(
                sizeBytes: sealedFile.count,
                contentType: "application/octet-stream",
                token: token
            )
            try await mediaService.uploadContent(
                mediaID: upload.mediaObjectId,
                data: sealedFile,
                token: token
            )

            let payload = MediaMessagePayload(
                t: MediaMessagePayload.kindVoice,
                mime: "audio/mp4",
                w: 0,
                h: 0,
                k: fileKey.base64EncodedString(),
                c: (trimmedTranscript?.isEmpty == false) ? trimmedTranscript : nil,
                d: durationMs
            )
            let payloadData = try JSONEncoder().encode(payload)
            let peerPub = try await resolvePeerIdentityPublicKey(peerUserID: peerUserID, token: token)
            let sealed = try MessageCrypto.seal(
                plaintext: payloadData,
                peerUserID: peerUserID,
                toPeerIdentityPublicKey: peerPub,
                ourPrivateKey: material.agreementPrivateKey,
                ourIdentityPublicKey: material.identityPublicKeyData
            )
            let dto = try await messagesService.send(
                SendMessageRequest(
                    peerUserId: peerUserID,
                    contentType: "media",
                    ciphertext: sealed.base64EncodedString(),
                    mediaObjectId: upload.mediaObjectId
                ),
                token: token
            )
            mediaCache.save(messageID: dto.id, data: audioData)
            plaintextCache.save(messageID: dto.id, text: displayText)

            let sent = ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: me,
                text: displayText,
                createdAt: dto.createdAt,
                isMine: true,
                deleted: false,
                receipt: receiptStatus(from: dto),
                kind: .voice,
                mediaObjectId: upload.mediaObjectId,
                voiceData: audioData,
                voiceDurationMs: durationMs,
                transcript: trimmedTranscript
            )
            if var thread = threads[peerUserID],
               let idx = thread.firstIndex(where: { $0.id == optimisticID })
            {
                thread[idx] = sent
                threads[peerUserID] = thread
            }
            await refreshConversations()
            lastError = nil
            return nil
        } catch {
            let message = SessionController.userMessage(for: error)
            if var thread = threads[peerUserID],
               let idx = thread.firstIndex(where: { $0.id == optimisticID })
            {
                thread[idx].receipt = .failed
                thread[idx].sendError = message
                threads[peerUserID] = thread
            }
            lastError = message
            return message
        }
    }

    /// Loads decrypted voice bytes for playback.
    func ensureVoiceLoaded(for message: ChatMessage) async {
        guard message.kind == .voice,
              message.voiceData == nil,
              !message.deleted,
              let mediaID = message.mediaObjectId,
              let token = sessionController?.bearerToken,
              let material = cryptoController?.material
        else { return }

        if let cached = mediaCache.data(for: message.id) {
            updateMessageVoice(messageID: message.id, peerID: message.peerUserID, data: cached)
            return
        }

        do {
            let response = try await messagesService.listMessages(
                peerUserID: message.peerUserID,
                token: token,
                limit: 50
            )
            guard let dto = response.messages.first(where: { $0.id == message.id }),
                  let ciphertextB64 = dto.ciphertext,
                  let envelopeData = Data(base64Encoded: ciphertextB64)
            else { return }

            let me = sessionController?.userID
            let isMine = dto.senderUserId == me
            let payloadData: Data
            if isMine {
                payloadData = try MessageCrypto.open(
                    envelopeData: envelopeData,
                    peerUserID: message.peerUserID,
                    with: material.agreementPrivateKey,
                    ourIdentityPublicKey: material.identityPublicKeyData,
                    senderIdentityPublicKey: material.identityPublicKeyData,
                    as: .sender
                )
            } else {
                let senderPub = try await resolvePeerIdentityPublicKey(
                    peerUserID: dto.senderUserId,
                    token: token
                )
                payloadData = try MessageCrypto.open(
                    envelopeData: envelopeData,
                    peerUserID: dto.senderUserId,
                    with: material.agreementPrivateKey,
                    ourIdentityPublicKey: material.identityPublicKeyData,
                    senderIdentityPublicKey: senderPub,
                    as: .recipient
                )
            }
            let payload = try JSONDecoder().decode(MediaMessagePayload.self, from: payloadData)
            guard let keyData = Data(base64Encoded: payload.k) else { return }
            let sealedFile = try await mediaService.downloadContent(mediaID: mediaID, token: token)
            let audio = try MediaCrypto.openFile(sealed: sealedFile, keyData: keyData)
            mediaCache.save(messageID: message.id, data: audio)
            updateMessageVoice(
                messageID: message.id,
                peerID: message.peerUserID,
                data: audio,
                durationMs: payload.d,
                transcript: payload.c
            )
        } catch {
            // Leave placeholder; user can retry by reopening.
        }
    }

    private func updateMessageVoice(
        messageID: UUID,
        peerID: UUID,
        data: Data,
        durationMs: Int? = nil,
        transcript: String? = nil
    ) {
        guard var thread = threads[peerID],
              let idx = thread.firstIndex(where: { $0.id == messageID })
        else { return }
        thread[idx].voiceData = data
        if let durationMs { thread[idx].voiceDurationMs = durationMs }
        if let transcript { thread[idx].transcript = transcript }
        threads[peerID] = thread
    }

    private func markImageFailed(optimisticID: UUID, peerUserID: UUID, error: String) {
        guard var thread = threads[peerUserID],
              let idx = thread.firstIndex(where: { $0.id == optimisticID })
        else { return }
        thread[idx].receipt = .failed
        thread[idx].sendError = error
        threads[peerUserID] = thread
    }

    /// Loads decrypted image bytes for a media message (caches on success).
    func ensureImageLoaded(for message: ChatMessage) async {
        guard message.kind == .image,
              message.imageData == nil,
              !message.deleted,
              let mediaID = message.mediaObjectId,
              let token = sessionController?.bearerToken,
              let material = cryptoController?.material
        else { return }

        if let cached = mediaCache.data(for: message.id) {
            updateMessageImage(messageID: message.id, peerID: message.peerUserID, data: cached)
            return
        }

        do {
            // Re-open sealed payload from history to get the file key.
            let response = try await messagesService.listMessages(
                peerUserID: message.peerUserID,
                token: token,
                limit: 50
            )
            guard let dto = response.messages.first(where: { $0.id == message.id }),
                  let ciphertextB64 = dto.ciphertext,
                  let envelopeData = Data(base64Encoded: ciphertextB64)
            else { return }

            let me = sessionController?.userID
            let isMine = dto.senderUserId == me
            let payloadData: Data
            if isMine {
                payloadData = try MessageCrypto.open(
                    envelopeData: envelopeData,
                    peerUserID: message.peerUserID,
                    with: material.agreementPrivateKey,
                    ourIdentityPublicKey: material.identityPublicKeyData,
                    senderIdentityPublicKey: material.identityPublicKeyData,
                    as: .sender
                )
            } else {
                let senderPub = try await resolvePeerIdentityPublicKey(
                    peerUserID: dto.senderUserId,
                    token: token
                )
                payloadData = try MessageCrypto.open(
                    envelopeData: envelopeData,
                    peerUserID: dto.senderUserId,
                    with: material.agreementPrivateKey,
                    ourIdentityPublicKey: material.identityPublicKeyData,
                    senderIdentityPublicKey: senderPub,
                    as: .recipient
                )
            }
            let payload = try JSONDecoder().decode(MediaMessagePayload.self, from: payloadData)
            guard let keyData = Data(base64Encoded: payload.k) else { return }

            let sealedFile = try await mediaService.downloadContent(mediaID: mediaID, token: token)
            let jpeg = try MediaCrypto.openFile(sealed: sealedFile, keyData: keyData)
            mediaCache.save(messageID: message.id, data: jpeg)
            updateMessageImage(messageID: message.id, peerID: message.peerUserID, data: jpeg)
        } catch {
            // Leave placeholder; user can reopen thread to retry.
        }
    }

    private func updateMessageImage(messageID: UUID, peerID: UUID, data: Data) {
        guard var thread = threads[peerID],
              let idx = thread.firstIndex(where: { $0.id == messageID })
        else { return }
        thread[idx].imageData = data
        threads[peerID] = thread
    }

    func preview(for conversation: ConversationItemDTO) -> String {
        let peerID = conversation.peer.id
        if let last = threads[peerID]?.last {
            if last.deleted { return "Message deleted" }
            switch last.kind {
            case .image:
                return last.text.isEmpty || last.text == "Photo" ? "Photo" : last.text
            case .voice:
                if let t = last.transcript, !t.isEmpty { return t }
                return "Voice message"
            case .text:
                return last.text
            }
        }
        return "Encrypted conversation"
    }

    /// Relative day label for list rows (Today → time, Yesterday, else date).
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

    /// Clock time for in-bubble meta (always `11:05`-style).
    func clockTimeLabel(for date: Date?) -> String {
        guard let date else { return "" }
        return date.formatted(date: .omitted, time: .shortened)
    }

    // MARK: - Private

    private func handleRealtime(_ event: RealtimeEvent) {
        switch event {
        case let .messageNew(dto):
            Task { await ingestIncoming(dto) }
        case let .raw(type, json):
            if type == "message.new" {
                Task {
                    await refreshConversations()
                    if let peer = activePeerID {
                        await loadThread(peerUserID: peer)
                    }
                }
            } else if type == "message.delivered" {
                handleDeliveredEvent(json)
            } else if type == "message.read" {
                handleReadEvent(json)
            } else if type == "typing" {
                handleTyping(json)
            } else if type == "presence.update" {
                handlePresence(json)
            } else if type.hasPrefix("call.") {
                callController?.handleRealtime(type: type, json: json)
            }
        }
    }

    private func handleDeliveredEvent(_ json: [String: Any]) {
        guard let idString = json["message_id"] as? String,
              let messageID = UUID(uuidString: idString)
        else { return }
        updateReceipt(messageID: messageID, atLeast: .delivered)
    }

    private func handleReadEvent(_ json: [String: Any]) {
        // Single-message read or bulk up_to.
        if let upToString = json["up_to_message_id"] as? String,
           let upTo = UUID(uuidString: upToString)
        {
            markOwnMessagesRead(upToMessageID: upTo)
            return
        }
        guard let idString = json["message_id"] as? String,
              let messageID = UUID(uuidString: idString)
        else { return }
        // Single read also implies all earlier own messages in that thread are read
        // once the peer has opened the chat; mark this one and promote earlier.
        markOwnMessagesRead(upToMessageID: messageID)
    }

    /// Raises receipt status for a message (never lowers it).
    private func updateReceipt(messageID: UUID, atLeast status: MessageReceiptStatus) {
        for (peerID, thread) in threads {
            guard let idx = thread.firstIndex(where: { $0.id == messageID && $0.isMine }) else {
                continue
            }
            var copy = thread
            let current = copy[idx].receipt
            if status.rank >= current.rank {
                copy[idx].receipt = status
                threads[peerID] = copy
            }
            return
        }
    }

    /// Marks every outbound message at or before `upToMessageID` (by createdAt) as read.
    private func markOwnMessagesRead(upToMessageID: UUID) {
        for (peerID, thread) in threads {
            guard let anchor = thread.first(where: { $0.id == upToMessageID }) else { continue }
            var copy = thread
            var changed = false
            for i in copy.indices where copy[i].isMine {
                if copy[i].createdAt <= anchor.createdAt || copy[i].id == upToMessageID {
                    if copy[i].receipt != .read {
                        copy[i].receipt = .read
                        changed = true
                    }
                }
            }
            if changed {
                threads[peerID] = copy
            }
        }
    }

    private func receiptStatus(from dto: MessageDTO) -> MessageReceiptStatus {
        if dto.read == true { return .read }
        if dto.delivered == true { return .delivered }
        return .sent
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
                try? await messagesService.markReadBulk(
                    peerUserID: threadPeer,
                    upToMessageID: chat.id,
                    token: token
                )
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
        let receipt = isMine ? receiptStatus(from: dto) : MessageReceiptStatus.sent
        let isMedia = dto.contentType == "media"

        if dto.deletedForEveryone {
            return ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: dto.senderUserId,
                text: "Message deleted",
                createdAt: dto.createdAt,
                isMine: isMine,
                deleted: true,
                receipt: receipt,
                kind: isMedia ? .image : .text,
                mediaObjectId: dto.mediaObjectId
            )
        }

        // Prefer in-memory message (optimistic send) with upgraded receipt.
        if isMine, let existing = threads[peerUserID]?.first(where: { $0.id == dto.id }) {
            var merged = existing
            let serverReceipt = receiptStatus(from: dto)
            if serverReceipt.rank > existing.receipt.rank {
                merged.receipt = serverReceipt
            }
            return merged
        }

        guard let ciphertextB64 = dto.ciphertext,
              let envelopeData = Data(base64Encoded: ciphertextB64)
        else {
            return ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: dto.senderUserId,
                text: isMedia ? "Photo" : "[Unable to decrypt]",
                createdAt: dto.createdAt,
                isMine: isMine,
                deleted: false,
                receipt: receipt,
                kind: isMedia ? .image : .text,
                mediaObjectId: dto.mediaObjectId
            )
        }

        do {
            let plain: Data
            if isMine {
                plain = try MessageCrypto.open(
                    envelopeData: envelopeData,
                    peerUserID: peerUserID,
                    with: material.agreementPrivateKey,
                    ourIdentityPublicKey: material.identityPublicKeyData,
                    senderIdentityPublicKey: material.identityPublicKeyData,
                    as: .sender
                )
            } else {
                let senderPub = try await resolvePeerIdentityPublicKey(
                    peerUserID: dto.senderUserId,
                    token: token
                )
                plain = try MessageCrypto.open(
                    envelopeData: envelopeData,
                    peerUserID: dto.senderUserId,
                    with: material.agreementPrivateKey,
                    ourIdentityPublicKey: material.identityPublicKeyData,
                    senderIdentityPublicKey: senderPub,
                    as: .recipient
                )
            }

            if isMedia {
                return await decodeMediaMessage(
                    dto: dto,
                    plain: plain,
                    peerUserID: peerUserID,
                    isMine: isMine,
                    receipt: receipt,
                    token: token
                )
            }

            let text = String(data: plain, encoding: .utf8) ?? "[Binary message]"
            if isMine {
                plaintextCache.save(messageID: dto.id, text: text)
            }
            return ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: dto.senderUserId,
                text: text,
                createdAt: dto.createdAt,
                isMine: isMine,
                deleted: false,
                receipt: receipt
            )
        } catch {
            if isMine, let cached = plaintextCache.text(for: dto.id), !isMedia {
                return ChatMessage(
                    id: dto.id,
                    peerUserID: peerUserID,
                    senderUserID: dto.senderUserId,
                    text: cached,
                    createdAt: dto.createdAt,
                    isMine: true,
                    deleted: false,
                    receipt: receipt
                )
            }
            return ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: dto.senderUserId,
                text: isMedia ? "Media" : "[Unable to decrypt]",
                createdAt: dto.createdAt,
                isMine: isMine,
                deleted: false,
                receipt: receipt,
                kind: isMedia ? .image : .text,
                mediaObjectId: dto.mediaObjectId,
                imageData: mediaCache.data(for: dto.id)
            )
        }
    }

    private func decodeMediaMessage(
        dto: MessageDTO,
        plain: Data,
        peerUserID: UUID,
        isMine: Bool,
        receipt: MessageReceiptStatus,
        token: String
    ) async -> ChatMessage {
        let cached = mediaCache.data(for: dto.id)
        let payload = try? JSONDecoder().decode(MediaMessagePayload.self, from: plain)

        if payload?.isVoice == true {
            var voiceData = cached
            if voiceData == nil,
               let payload,
               let mediaID = dto.mediaObjectId,
               let keyData = Data(base64Encoded: payload.k)
            {
                if let sealed = try? await mediaService.downloadContent(mediaID: mediaID, token: token),
                   let audio = try? MediaCrypto.openFile(sealed: sealed, keyData: keyData)
                {
                    mediaCache.save(messageID: dto.id, data: audio)
                    voiceData = audio
                }
            }
            let transcript = payload?.c?.trimmingCharacters(in: .whitespacesAndNewlines)
            let mediaText = (transcript?.isEmpty == false)
                ? transcript!
                : (plaintextCache.text(for: dto.id) ?? "Voice message")
            if let transcript, !transcript.isEmpty {
                plaintextCache.save(messageID: dto.id, text: transcript)
            }
            return ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: dto.senderUserId,
                text: mediaText,
                createdAt: dto.createdAt,
                isMine: isMine,
                deleted: false,
                receipt: receipt,
                kind: .voice,
                mediaObjectId: dto.mediaObjectId,
                voiceData: voiceData,
                voiceDurationMs: payload?.d,
                transcript: transcript
            )
        }

        var imageData = cached
        var width = payload?.w
        var height = payload?.h

        // Best-effort download when we have a payload key (don't block the thread forever).
        if imageData == nil,
           let payload,
           let mediaID = dto.mediaObjectId,
           let keyData = Data(base64Encoded: payload.k)
        {
            if let sealed = try? await mediaService.downloadContent(mediaID: mediaID, token: token),
               let jpeg = try? MediaCrypto.openFile(sealed: sealed, keyData: keyData)
            {
                mediaCache.save(messageID: dto.id, data: jpeg)
                imageData = jpeg
            }
        }

        let caption = payload?.c?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let mediaText = caption.isEmpty
            ? (plaintextCache.text(for: dto.id) ?? "Photo")
            : caption
        if !caption.isEmpty {
            plaintextCache.save(messageID: dto.id, text: caption)
        }

        return ChatMessage(
            id: dto.id,
            peerUserID: peerUserID,
            senderUserID: dto.senderUserId,
            text: mediaText,
            createdAt: dto.createdAt,
            isMine: isMine,
            deleted: false,
            receipt: receipt,
            kind: .image,
            mediaObjectId: dto.mediaObjectId,
            imageWidth: width,
            imageHeight: height,
            imageData: imageData
        )
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
