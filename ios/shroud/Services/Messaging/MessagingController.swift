import CryptoKit
import Foundation
import UIKit

// Note: local chat history, media, and plaintext caches are AES-256-GCM sealed with
// `IdentityKeyMaterial.historyKey` (BIP39-derived, never uploaded). See LocalHistoryCrypto.

/// Live contacts + chats state; seals plaintext with MessageCrypto before send.
///
/// Offline: hydrated from `LocalMessageStore` (90 days of peer chats + full Notes history).
/// Network merges replace the cache when online; outbound text queues until connectivity returns.
@MainActor
@Observable
final class MessagingController {
    /// Local-only "Notes to me" peer — never used as an API peer_user_id.
    static let notesPeerID = NotesLocal.peerID
    static let notesDisplayName = NotesLocal.displayName

    private(set) var contacts: [ContactItemDTO] = []
    private(set) var incomingRequests: [ContactRequestDTO] = []
    private(set) var conversations: [ConversationItemDTO] = []
    private(set) var isLoadingContacts = false
    private(set) var isLoadingChats = false
    /// Flips once per sign-in, when the first load *settles* (success or failure). Drives the
    /// skeleton so a background poll can never swap loaded rows back out for placeholders,
    /// and so a failing server shows the empty state instead of shimmering forever.
    private(set) var hasLoadedContacts = false
    private(set) var hasLoadedChats = false
    /// Why the last list load failed, per list — nil once one succeeds. Separate from
    /// `lastError` (which any action can overwrite) because the lists render these directly:
    /// an unreachable server has to say so instead of claiming the account is empty.
    private(set) var contactsError: String?
    private(set) var chatsError: String?
    private(set) var lastError: String?
    /// Device has no usable network path (lists still show local cache).
    private(set) var isOffline: Bool = false

    /// Decrypted messages by peer user id (newest last). Notes use `notesPeerID`.
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
    /// Encrypted offline history + decrypt/media caches (not the network layer).
    private let local = MessagingLocalRepository()
    private let connectivity = ConnectivityMonitor()
    private let realtime = RealtimeClient()
    /// Polling fallback when the WebSocket is down (common behind some reverse proxies).
    private var pollTask: Task<Void, Never>?
    /// Separate poll so contact invites still appear if WS is down.
    private var contactsPollTask: Task<Void, Never>?
    /// Coalesce overlapping refreshes — polling, WS events, and `.task` all fan into these.
    private var contactsRefreshTask: Task<Void, Never>?
    private var conversationsRefreshTask: Task<Void, Never>?
    /// One in-flight thread load per peer.
    private var threadLoadTasks: [UUID: Task<Void, Never>] = [:]
    /// Serializes outbound flush so reconnect + poll don't double-send.
    private let outboundQueue = OutboundSendQueue()
    /// Presence is swept in bulk at most this often; live changes arrive over WS anyway.
    private var lastPresenceSweep: Date?
    private let presenceSweepInterval: TimeInterval = 30
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
        /// Local Notes checklist item (never sent to the server).
        case todo
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
        /// Amplitude envelope captured at record time, 0…255 per bar.
        /// Nil for messages sent before waveforms were part of the payload.
        var voiceWaveform: [UInt8]?
        /// On-device transcript (local or sealed in payload).
        var transcript: String?
        /// Set when an outbound send failed; bubble stays for retry.
        var sendError: String?
        /// Notes todo completion (nil unless `kind == .todo`).
        var todoDone: Bool?
        /// True while this outbound message is waiting for network delivery.
        var pendingSync: Bool

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
            voiceWaveform: [UInt8]? = nil,
            transcript: String? = nil,
            sendError: String? = nil,
            todoDone: Bool? = nil,
            pendingSync: Bool = false
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
            self.voiceWaveform = voiceWaveform
            self.transcript = transcript
            self.sendError = sendError
            self.todoDone = todoDone
            self.pendingSync = pendingSync
        }
    }

    func isNotesChat(_ peerID: UUID) -> Bool {
        NotesLocal.isNotes(peerID)
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
        local.setHistoryKey(cryptoController?.material?.historyKey)
        connectivity.start()
        isOffline = !connectivity.isOnline
        // Paint cached chats/contacts immediately so offline / cold start feels instant.
        hydrateFromDisk()
        realtime.connect(token: token)
        startPollingFallback()
        startContactsPolling()
        Task {
            await refreshContacts()
            await refreshConversations()
            await flushPendingSends()
        }
    }

    /// Stops realtime work and drops in-memory UI state.
    ///
    /// - Parameter wipeDisk: When `true` (logout), also clears the 90-day local store, media,
    ///   plaintext, and ratchet sessions so another account never sees this data.
    ///   When `false`, the durable cache is kept for offline reopen after the same user unlocks.
    func stop(wipeDisk: Bool = true) {
        // Flush latest threads to disk before tearing down (same-user reopen / offline).
        if !wipeDisk {
            persistSnapshot()
        }
        pollTask?.cancel()
        pollTask = nil
        contactsPollTask?.cancel()
        contactsPollTask = nil
        outboundQueue.cancel()
        connectivity.stop()
        realtime.disconnect(reconnect: false)
        activePeerID = nil
        // Drop (don't cancel) in-flight refreshes: without a token they no-op anyway, and
        // cancelling would surface a spurious network error on sign-out.
        contactsRefreshTask = nil
        conversationsRefreshTask = nil
        threadLoadTasks.values.forEach { $0.cancel() }
        threadLoadTasks.removeAll()
        // Next sign-in is a genuine first load again, so the skeleton is allowed back.
        hasLoadedContacts = false
        hasLoadedChats = false
        contactsError = nil
        chatsError = nil
        lastError = nil
        isOffline = false
        lastPresenceSweep = nil
        local.setHistoryKey(nil)
        if wipeDisk {
            clearLocalData()
        } else {
            clearInMemoryState()
        }
    }

    /// Wipes in-memory lists and on-device message caches (plaintext, media, ratchets, peer keys).
    /// Called on sign-out so a restart never resurfaces another account’s data.
    func clearLocalData() {
        local.clear(userID: sessionController?.userID)
        clearInMemoryState()
        peerKeys.clear()
        RatchetSessionStore.deleteAll()
    }

    private func clearInMemoryState() {
        contacts = []
        incomingRequests = []
        conversations = []
        threads = [:]
        typingPeerIDs = []
        presenceByUser = [:]
        unreadCountByPeer = [:]
        isLoadingContacts = false
        isLoadingChats = false
    }

    /// Call when the app returns to the foreground (history key must already be in memory).
    func handleAppBecameActive() {
        guard let token = sessionController?.bearerToken else { return }
        // Re-bind history key after biometry unlock (start may have been skipped).
        if local.historyKey == nil, let key = cryptoController?.material?.historyKey {
            local.setHistoryKey(key)
            hydrateFromDisk()
        }
        isOffline = !connectivity.isOnline
        realtime.connect(token: token)
        Task {
            await refreshContacts()
            await refreshConversations()
            if let peer = activePeerID, !isNotesChat(peer) {
                await loadThread(peerUserID: peer)
            }
            await flushPendingSends()
        }
    }

    /// Clears decrypted threads and history key from RAM (sealed files stay on disk).
    /// Call when the app backgrounds so a seized unlocked device cannot read chats from memory.
    func lockSensitiveMemory() {
        local.lockSensitiveMemory()
        // Drop message bodies; keep conversation list shells for a less jarring re-unlock.
        threads = [:]
        typingPeerIDs = []
        unreadCountByPeer = [:]
        activePeerID = nil
        threadLoadTasks.values.forEach { $0.cancel() }
        threadLoadTasks.removeAll()
    }

    /// Reacts to path changes (wired from RootView / scene phase optional).
    func handleConnectivityChanged(isOnline: Bool) {
        let wasOffline = isOffline
        isOffline = !isOnline
        guard isOnline, wasOffline else { return }
        guard sessionController?.bearerToken != nil else { return }
        Task {
            await refreshContacts(force: true)
            await refreshConversations(force: true)
            if let peer = activePeerID, !isNotesChat(peer) {
                await loadThread(peerUserID: peer)
            }
            await flushPendingSends()
        }
    }

    func setActivePeer(_ peerID: UUID?) {
        if activePeerID != peerID { activePeerID = peerID }
        if let peerID, unreadCountByPeer[peerID] != 0 {
            unreadCountByPeer[peerID] = 0
        }
    }

    private func startPollingFallback() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            // When the WebSocket is down (or never connected), poll so messages still arrive.
            // While connected, a slower safety poll catches any missed events.
            var tick = 0
            var lastOnline = true
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                guard let self, !Task.isCancelled else { return }
                tick += 1
                let online = self.connectivity.isOnline
                if online != lastOnline {
                    lastOnline = online
                    self.handleConnectivityChanged(isOnline: online)
                } else if self.isOffline != !online {
                    self.isOffline = !online
                }
                guard online else { continue }
                let wsUp = self.realtime.isConnected
                if !wsUp {
                    await self.refreshConversations()
                    if let peer = self.activePeerID, !self.isNotesChat(peer) {
                        await self.loadThread(peerUserID: peer)
                    }
                    await self.flushPendingSends()
                } else if tick % 5 == 0 {
                    // ~15s backup while realtime is healthy
                    await self.refreshConversations()
                    if let peer = self.activePeerID, !self.isNotesChat(peer) {
                        await self.loadThread(peerUserID: peer)
                    }
                }
            }
        }
    }

    /// Polls contact requests so invites appear even if the WebSocket is blocked.
    private func startContactsPolling() {
        contactsPollTask?.cancel()
        contactsPollTask = Task { [weak self] in
            var tick = 0
            while !Task.isCancelled {
                // 5s when offline/slow path; still light enough for multi-device invites.
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                guard let self, !Task.isCancelled else { return }
                tick += 1
                // While realtime is healthy, `contact.*` events already push invites through —
                // polling on top of that only churns the list, so keep a 30s safety net.
                guard !self.realtime.isConnected || tick % 6 == 0 else { continue }
                await self.refreshContacts()
            }
        }
    }

    func unreadCount(for peerID: UUID) -> Int {
        unreadCountByPeer[peerID] ?? 0
    }

    // MARK: - Contacts

    /// Reloads contacts + pending invites.
    ///
    /// Overlapping callers (poll, WS `contact.*`, view `.task`) share one fetch instead of
    /// each starting their own. Pass `force` after mutating server state — it waits out the
    /// in-flight fetch and then runs a fresh one, so the caller sees its own write.
    func refreshContacts(force: Bool = false) async {
        if let existing = contactsRefreshTask {
            await existing.value
            if !force { return }
        }
        let task = Task { [weak self] in
            guard let self else { return }
            await self.performContactsRefresh()
        }
        contactsRefreshTask = task
        await task.value
        if contactsRefreshTask == task { contactsRefreshTask = nil }
    }

    /// Every write below is guarded by an equality check: with `@Observable`, assigning an
    /// identical value still invalidates every view that reads it, and a poll that returns
    /// unchanged data would otherwise redraw the list a few times a second.
    private func performContactsRefresh() async {
        guard let token = sessionController?.bearerToken else {
            // Nothing to wait for without a token, so the first load is settled. Returning
            // with the flag still false left the skeleton shimmering with no request in
            // flight to ever clear it.
            hasLoadedContacts = true
            return
        }
        // Loading chrome belongs to the first load only; polls refresh in place.
        let showsLoading = !hasLoadedContacts
        if showsLoading { isLoadingContacts = true }
        defer {
            if showsLoading { isLoadingContacts = false }
            if !hasLoadedContacts { hasLoadedContacts = true }
        }
        do {
            async let listTask = contactsService.listContacts(token: token)
            async let requestsTask = contactsService.listIncomingRequests(token: token)
            // Await both before publishing so a half-failed refresh never lands.
            let sorted = try await listTask.sorted {
                $0.username.localizedCaseInsensitiveCompare($1.username) == .orderedAscending
            }
            let requests = try await requestsTask
            let rosterChanged = contacts.map(\.userId) != sorted.map(\.userId)
            if contacts != sorted { contacts = sorted }
            if incomingRequests != requests { incomingRequests = requests }
            if contactsError != nil { contactsError = nil }
            if lastError != nil { lastError = nil }
            isOffline = false
            // Rows are publishable now — don't hold the skeleton up for the presence fan-out.
            if !hasLoadedContacts { hasLoadedContacts = true }
            persistSnapshot()
            // A new contact needs presence right away; otherwise stay on the slow sweep.
            await sweepPresenceIfNeeded(token: token, force: rosterChanged)
        } catch {
            // Keep showing the last good local roster when the network is gone.
            if !contacts.isEmpty || !incomingRequests.isEmpty {
                if contactsError != nil { contactsError = nil }
                isOffline = true
            } else {
                let message = SessionController.userMessage(for: error)
                if contactsError != message { contactsError = message }
                if lastError != message { lastError = message }
            }
        }
    }

    /// Bulk presence sweep, rate-limited to `presenceSweepInterval`.
    /// Between sweeps, `presence.update` events keep the rows current.
    private func sweepPresenceIfNeeded(token: String, force: Bool = false) async {
        if !force,
           let last = lastPresenceSweep,
           Date().timeIntervalSince(last) < presenceSweepInterval
        {
            return
        }
        lastPresenceSweep = Date()
        await refreshPresence(for: contacts.map(\.userId), token: token)
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
        // One assignment for the whole sweep — writing per user re-rendered the contacts
        // list once per contact, which is what made it strobe.
        var merged = presenceByUser
        for (userID, presence) in updates {
            merged[userID] = presence
        }
        if merged != presenceByUser { presenceByUser = merged }
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
            await refreshContacts(force: true)
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
            await refreshContacts(force: true)
        } catch {
            lastError = SessionController.userMessage(for: error)
        }
    }

    func rejectRequest(_ request: ContactRequestDTO) async {
        guard let token = sessionController?.bearerToken else { return }
        do {
            try await contactsService.rejectRequest(id: request.id, token: token)
            await refreshContacts(force: true)
        } catch {
            lastError = SessionController.userMessage(for: error)
        }
    }

    // MARK: - Chats

    /// See `refreshContacts(force:)` — same coalescing contract.
    func refreshConversations(force: Bool = false) async {
        if let existing = conversationsRefreshTask {
            await existing.value
            if !force { return }
        }
        let task = Task { [weak self] in
            guard let self else { return }
            await self.performConversationsRefresh()
        }
        conversationsRefreshTask = task
        await task.value
        if conversationsRefreshTask == task { conversationsRefreshTask = nil }
    }

    private func performConversationsRefresh() async {
        guard let token = sessionController?.bearerToken else {
            // See performContactsRefresh: settle the flag so the skeleton can't outlive the load.
            hasLoadedChats = true
            return
        }
        let showsLoading = !hasLoadedChats
        if showsLoading { isLoadingChats = true }
        defer {
            if showsLoading { isLoadingChats = false }
            if !hasLoadedChats { hasLoadedChats = true }
        }
        do {
            let list = try await messagesService.listConversations(token: token)
            // Same-value writes still invalidate observers — only publish real changes.
            if conversations != list { conversations = list }
            if chatsError != nil { chatsError = nil }
            if lastError != nil { lastError = nil }
            isOffline = false
            persistSnapshot()
        } catch {
            if !conversations.isEmpty || (threads[Self.notesPeerID]?.isEmpty == false) {
                if chatsError != nil { chatsError = nil }
                isOffline = true
            } else {
                let message = SessionController.userMessage(for: error)
                if chatsError != message { chatsError = message }
                if lastError != message { lastError = message }
            }
        }
    }

    /// Loads (and decrypts) a peer's thread.
    ///
    /// The 3s poll, WS `message.new`, and the chat view's `.task` all land here. They share a
    /// single in-flight load per peer — duplicate fetches decrypt the same page twice and
    /// rewrite `threads`, which redraws every bubble. Callers still await real data.
    /// Loads a peer thread (or Notes). Walks `before_*` pages until the 90-day window is filled.
    func loadThread(peerUserID: UUID) async {
        guard sessionController?.bearerToken != nil,
              sessionController?.userID != nil,
              cryptoController?.material != nil
        else {
            if isNotesChat(peerUserID), threads[peerUserID] == nil {
                threads[peerUserID] = []
            }
            return
        }

        if activePeerID != peerUserID { activePeerID = peerUserID }
        if !isNotesChat(peerUserID), unreadCountByPeer[peerUserID] != 0 {
            unreadCountByPeer[peerUserID] = 0
        }

        // Notes UI peer is a sentinel; API peer is the signed-in user (Saved Messages).
        let apiPeer = isNotesChat(peerUserID)
            ? (sessionController?.userID ?? peerUserID)
            : peerUserID
        let storePeer = peerUserID
        let taskKey = storePeer

        if let existing = threadLoadTasks[taskKey] {
            await existing.value
            return
        }
        let task = Task { [weak self] in
            guard let self else { return }
            await self.performThreadLoad(apiPeerID: apiPeer, storePeerID: storePeer)
        }
        threadLoadTasks[taskKey] = task
        await task.value
        if threadLoadTasks[taskKey] == task { threadLoadTasks[taskKey] = nil }
    }

    /// - Parameters:
    ///   - apiPeerID: Peer id for HTTP (`me` for Notes).
    ///   - storePeerID: Key in `threads` (sentinel for Notes).
    private func performThreadLoad(apiPeerID: UUID, storePeerID: UUID) async {
        guard let token = sessionController?.bearerToken,
              let me = sessionController?.userID,
              let material = cryptoController?.material
        else { return }

        let isNotes = isNotesChat(storePeerID)
        let previousThread = threads[storePeerID] ?? []
        let pendingLocal = previousThread.filter(\.pendingSync)
        let retentionCutoff = Calendar.current.date(
            byAdding: .day,
            value: -LocalMessageStore.retentionDays,
            to: Date()
        ) ?? Date().addingTimeInterval(-TimeInterval(LocalMessageStore.retentionDays) * 86_400)

        do {
            var decoded: [ChatMessage] = []
            var beforeAt: Date?
            var beforeID: UUID?
            var pages = 0
            let maxPages = 40 // 40 × 100 = 4000 msgs hard stop

            repeat {
                let response = try await messagesService.listMessages(
                    peerUserID: apiPeerID,
                    token: token,
                    limit: 100,
                    beforeCreatedAt: beforeAt,
                    beforeID: beforeID
                )
                pages += 1
                // Server returns newest-first; reverse each page for chronological append order.
                for dto in response.messages.reversed() {
                    var message = await decodeMessage(
                        dto,
                        me: me,
                        material: material,
                        token: token,
                        forcePeerUserID: isNotes ? storePeerID : nil
                    )
                    if isNotes {
                        message = notesMessageFromServer(message)
                    }
                    decoded.append(message)
                    if !isNotes, dto.senderUserId != me {
                        try? await messagesService.markDelivered(messageID: dto.id, token: token)
                    }
                }
                let oldest = response.messages.last // still newest-first from server
                if response.hasMore == true || response.messages.count >= 100,
                   let oldest,
                   oldest.createdAt >= retentionCutoff,
                   pages < maxPages
                {
                    beforeAt = oldest.createdAt
                    beforeID = oldest.id
                } else {
                    beforeAt = nil
                    beforeID = nil
                }
            } while beforeAt != nil && beforeID != nil

            // Drop anything older than retention for normal chats (Notes keep all server rows).
            if !isNotes {
                decoded = decoded.filter { $0.createdAt >= retentionCutoff || $0.pendingSync }
            }

            decoded = ThreadMessageMerge.mergeThread(
                decoded: decoded,
                previous: previousThread,
                pendingLocal: pendingLocal
            )
            if threads[storePeerID] != decoded { threads[storePeerID] = decoded }

            if !isNotes {
                if let lastFromPeer = decoded.last(where: { !$0.isMine }) {
                    _ = try? await messagesService.markReadBulk(
                        peerUserID: apiPeerID,
                        upToMessageID: lastFromPeer.id,
                        token: token
                    )
                }
                if let presence = try? await contactsService.presence(
                    userID: apiPeerID,
                    token: token
                ),
                   presenceByUser[apiPeerID] != presence
                {
                    presenceByUser[apiPeerID] = presence
                }
            }
            if lastError != nil { lastError = nil }
            isOffline = false
            persistThread(storePeerID)
        } catch {
            if threads[storePeerID]?.isEmpty != false {
                let message = SessionController.userMessage(for: error)
                if lastError != message { lastError = message }
            } else {
                isOffline = true
                if lastError != nil { lastError = nil }
            }
        }
    }

    /// Map server plaintext to Notes todo markers when present.
    private func notesMessageFromServer(_ message: ChatMessage) -> ChatMessage {
        guard message.kind == .text else { return message }
        if let parsed = NotesLocal.parseSyncedTodo(message.text) {
            return ChatMessage(
                id: message.id,
                peerUserID: Self.notesPeerID,
                senderUserID: message.senderUserID,
                text: parsed.text,
                createdAt: message.createdAt,
                isMine: true,
                deleted: message.deleted,
                receipt: .sent,
                kind: .todo,
                todoDone: parsed.done
            )
        }
        var copy = message
        // Ensure notes always keyed under the local sentinel peer.
        if copy.peerUserID != Self.notesPeerID {
            copy = ChatMessage(
                id: message.id,
                peerUserID: Self.notesPeerID,
                senderUserID: message.senderUserID,
                text: message.text,
                createdAt: message.createdAt,
                isMine: true,
                deleted: message.deleted,
                receipt: .sent,
                kind: message.kind,
                mediaObjectId: message.mediaObjectId,
                imageWidth: message.imageWidth,
                imageHeight: message.imageHeight,
                imageData: message.imageData,
                voiceData: message.voiceData,
                voiceDurationMs: message.voiceDurationMs,
                voiceWaveform: message.voiceWaveform,
                transcript: message.transcript
            )
        }
        return copy
    }

    func sendText(_ text: String, to peerUserID: UUID) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        if isNotesChat(peerUserID) {
            await appendAndSyncNote(text: trimmed, kind: .text, todoDone: nil)
            return
        }

        guard let token = sessionController?.bearerToken,
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
            receipt: .sending,
            pendingSync: true
        )
        var optimisticThread = threads[peerUserID] ?? []
        optimisticThread.append(optimistic)
        threads[peerUserID] = optimisticThread
        persistSnapshot()

        // Offline: keep the bubble and flush when connectivity returns.
        if !connectivity.isOnline {
            isOffline = true
            return
        }

        do {
            try await deliverPendingText(
                messageID: optimisticID,
                text: trimmed,
                peerUserID: peerUserID,
                me: me,
                material: material,
                token: token
            )
            await refreshConversations(force: true)
            lastError = nil
        } catch {
            // Keep the bubble for later flush / retry instead of dropping the draft.
            if var list = threads[peerUserID],
               let idx = list.firstIndex(where: { $0.id == optimisticID })
            {
                list[idx].receipt = .sending
                list[idx].pendingSync = true
                list[idx].sendError = nil
                threads[peerUserID] = list
            }
            isOffline = true
            persistSnapshot()
            lastError = SessionController.userMessage(for: error)
        }
    }

    /// Adds a checklist item to Notes (synced as text marker when online).
    func sendTodo(_ text: String, to peerUserID: UUID = MessagingController.notesPeerID) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, isNotesChat(peerUserID) else { return }
        Task { await appendAndSyncNote(text: trimmed, kind: .todo, todoDone: false) }
    }

    /// Toggles a Notes todo checkbox (local + re-sync body when possible).
    func toggleTodo(messageID: UUID, peerUserID: UUID = MessagingController.notesPeerID) {
        guard isNotesChat(peerUserID),
              let list = threads[peerUserID],
              let updated = NotesLocal.toggleTodo(messageID: messageID, in: list)
        else { return }
        threads[peerUserID] = updated
        persistThread(peerUserID)
        // Todo completion is local-first; multi-device picks it up on next full notes load
        // only if we re-send — skip re-send to avoid duplicates. Local vault is enough.
    }

    /// Deletes a local Notes message (and its media bytes if any).
    func deleteLocalNote(messageID: UUID) {
        let peer = Self.notesPeerID
        guard let list = threads[peer] else { return }
        let result = NotesLocal.delete(messageID: messageID, in: list)
        guard result.removed else { return }
        threads[peer] = result.messages
        local.removeCaches(messageIDs: [messageID])
        persistThread(peer)
    }

    /// Deletes a message here and on the server. Returns a user-facing error, or nil on success.
    ///
    /// The server call goes first: `.me` is durable as a hide row, so applying it locally
    /// before the round-trip would let the next history page resurrect the bubble.
    func deleteMessage(_ message: ChatMessage, scope: MessageDeleteScope) async -> String? {
        if isNotesChat(message.peerUserID) {
            return await deleteNote(message)
        }

        // Never reached the server — nothing to delete there. Dropping it from `threads`
        // also takes it out of the outbound queue, which is derived from these lists.
        if message.pendingSync || message.receipt == .failed {
            removeMessageLocally(messageID: message.id, peerUserID: message.peerUserID)
            await refreshConversations(force: true)
            return nil
        }

        guard let token = sessionController?.bearerToken else {
            return "Sign in to delete messages."
        }

        do {
            try await messagesService.delete(messageID: message.id, scope: scope, token: token)
        } catch {
            let text = SessionController.userMessage(for: error)
            lastError = text
            return text
        }

        switch scope {
        case .me:
            removeMessageLocally(messageID: message.id, peerUserID: message.peerUserID)
        case .everyone:
            // Server keeps the row as a tombstone, so match it rather than dropping the
            // bubble — otherwise the next load would pop "Message deleted" back in.
            tombstoneMessage(messageID: message.id, peerUserID: message.peerUserID)
        }
        lastError = nil
        await refreshConversations(force: true)
        return nil
    }

    /// Deletes a note locally and, when it was mirrored to the server, there too.
    ///
    /// Notes are Saved Messages (`peer_user_id = self`), so a local-only removal comes back
    /// on the next sync. `scope: me` is the right verb for a self-conversation: it hides the
    /// row for the only participant instead of leaving a "Message deleted" tombstone.
    /// A note that never reached the server answers 404, which is nothing left to delete.
    private func deleteNote(_ message: ChatMessage) async -> String? {
        if let token = sessionController?.bearerToken, connectivity.isOnline {
            do {
                try await messagesService.delete(messageID: message.id, scope: .me, token: token)
            } catch let APIError.server(_, _, statusCode) where statusCode == 404 {
                // Local-only note (written offline, or synced under a client id the server
                // re-keyed). Nothing on the server to hide.
            } catch {
                let text = SessionController.userMessage(for: error)
                lastError = text
                return text
            }
        }
        deleteLocalNote(messageID: message.id)
        return nil
    }

    /// Drops a message from its thread and purges its cached plaintext / media bytes.
    private func removeMessageLocally(messageID: UUID, peerUserID: UUID) {
        guard var list = threads[peerUserID] else { return }
        let before = list.count
        list.removeAll { $0.id == messageID }
        guard list.count != before else { return }
        threads[peerUserID] = list
        local.removeCaches(messageIDs: [messageID])
        persistThread(peerUserID)
    }

    /// Replaces a message with the same tombstone a history page would decode for it.
    private func tombstoneMessage(messageID: UUID, peerUserID: UUID) {
        guard var list = threads[peerUserID],
              let idx = list.firstIndex(where: { $0.id == messageID }),
              !list[idx].deleted
        else { return }
        let old = list[idx]
        list[idx] = ChatMessage(
            id: old.id,
            peerUserID: old.peerUserID,
            senderUserID: old.senderUserID,
            text: "Message deleted",
            createdAt: old.createdAt,
            isMine: old.isMine,
            deleted: true,
            receipt: old.receipt,
            kind: old.kind == .image ? .image : .text
        )
        threads[peerUserID] = list
        local.removeCaches(messageIDs: [messageID])
        persistThread(peerUserID)
    }

    /// Which thread holds a message id (delete events carry no peer id).
    private func peerID(forMessage messageID: UUID) -> UUID? {
        threads.first { $0.value.contains { $0.id == messageID } }?.key
    }

    func setTyping(peerUserID: UUID, isTyping: Bool) {
        realtime.sendTyping(peerUserID: peerUserID, isTyping: isTyping)
    }

    /// Encrypts, uploads, and sends an image message to `peerUserID`.
    ///
    /// At `.original` quality a library file is sent byte-for-byte; nothing is resized or
    /// re-encoded. Optional `caption` is sealed in the media payload (Telegram-style).
    /// Returns a user-facing error string, or `nil` on success.
    func sendImage(
        _ source: MediaImageSource,
        to peerUserID: UUID,
        caption: String = "",
        quality: MediaComposeQuality = .original,
        edits: MediaEdits = MediaEdits()
    ) async -> String? {
        let trimmedCaption = caption.trimmingCharacters(in: .whitespacesAndNewlines)
        let displayText = trimmedCaption.isEmpty ? "Photo" : trimmedCaption

        let optimisticID = UUID()
        let encoded: EncodedImage
        do {
            let params = quality.encodeParams
            // Keep a full-resolution render + encode off the main actor — it can take a beat
            // on 48 MP files.
            encoded = try await Task.detached(priority: .userInitiated) {
                // Crop, filters, markup and stickers are baked here, at full resolution. An
                // untouched photo skips this entirely and keeps its pass-through.
                var prepared = source
                if !edits.isIdentity {
                    guard let full = MediaCrypto.fullResolutionImage(from: source, maxEdge: params.maxEdge)
                    else { throw MediaCrypto.MediaError.imageEncodeFailed }
                    prepared = .image(MediaEditRenderer.render(full, edits: edits))
                }
                return try MediaCrypto.encode(
                    prepared,
                    maxEdge: params.maxEdge,
                    compression: params.compression,
                    allowsPassthrough: params.allowsPassthrough && edits.isIdentity
                )
            }.value
        } catch {
            return "Could not prepare that photo."
        }

        if isNotesChat(peerUserID) {
            let me = sessionController?.userID ?? Self.notesPeerID
            local.saveSealedMedia(messageID: optimisticID, data: encoded.data)
            let note = ChatMessage(
                id: optimisticID,
                peerUserID: peerUserID,
                senderUserID: me,
                text: displayText,
                createdAt: Date(),
                isMine: true,
                deleted: false,
                receipt: .sent,
                kind: .image,
                imageWidth: encoded.width,
                imageHeight: encoded.height,
                imageData: encoded.data
            )
            var list = threads[peerUserID] ?? []
            list.append(note)
            threads[peerUserID] = list
            persistThread(peerUserID)

            // Multi-device notes photo when online.
            if connectivity.isOnline,
               let token = sessionController?.bearerToken,
               let realMe = sessionController?.userID,
               let material = cryptoController?.material
            {
                do {
                    try await finishImageSend(
                        optimisticID: optimisticID,
                        peerUserID: realMe,
                        me: realMe,
                        material: material,
                        token: token,
                        encoded: encoded,
                        caption: trimmedCaption
                    )
                    if var notes = threads[peerUserID],
                       let idx = notes.firstIndex(where: { $0.id == optimisticID || $0.mediaObjectId != nil })
                    {
                        // Prefer keeping the Notes-thread bubble (server id may replace optimistic).
                        if let updated = threads[realMe]?.last(where: { $0.kind == .image }) {
                            notes.removeAll { $0.id == optimisticID }
                            var mapped = updated
                            mapped = ChatMessage(
                                id: updated.id,
                                peerUserID: peerUserID,
                                senderUserID: realMe,
                                text: updated.text,
                                createdAt: updated.createdAt,
                                isMine: true,
                                deleted: false,
                                receipt: .sent,
                                kind: .image,
                                mediaObjectId: updated.mediaObjectId,
                                imageWidth: updated.imageWidth,
                                imageHeight: updated.imageHeight,
                                imageData: updated.imageData ?? encoded.data
                            )
                            notes.append(mapped)
                            threads[peerUserID] = notes
                            threads[realMe] = nil
                            persistThread(peerUserID)
                        } else {
                            notes[idx].pendingSync = false
                            threads[peerUserID] = notes
                        }
                    }
                } catch {
                    // Keep local-only photo.
                }
            }
            return nil
        }

        guard let token = sessionController?.bearerToken,
              let me = sessionController?.userID,
              let material = cryptoController?.material
        else { return "Not signed in." }

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
            imageWidth: encoded.width,
            imageHeight: encoded.height,
            imageData: encoded.data,
            pendingSync: true
        )
        var list = threads[peerUserID] ?? []
        list.append(optimistic)
        threads[peerUserID] = list
        local.saveSealedMedia(messageID: optimisticID, data: encoded.data)
        persistSnapshot()

        if !connectivity.isOnline {
            isOffline = true
            markImageFailed(optimisticID: optimisticID, peerUserID: peerUserID, error: "Waiting for connection…")
            return nil
        }

        do {
            try await finishImageSend(
                optimisticID: optimisticID,
                peerUserID: peerUserID,
                me: me,
                material: material,
                token: token,
                encoded: encoded,
                caption: trimmedCaption
            )
            lastError = nil
            return nil
        } catch {
            let message = SessionController.userMessage(for: error)
            markImageFailed(optimisticID: optimisticID, peerUserID: peerUserID, error: message)
            lastError = message
            persistSnapshot()
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
              let data = thread[idx].imageData
        else {
            return "Nothing to retry."
        }

        thread[idx].receipt = .sending
        thread[idx].sendError = nil
        threads[peerUserID] = thread

        // The bytes were already prepared for the first attempt — re-encoding here would throw
        // away whatever quality the first pass chose and add a second generation of JPEG loss.
        let size = MediaCrypto.pixelSize(for: data)
        let encoded = EncodedImage(
            data: data,
            width: size?.width ?? thread[idx].imageWidth ?? 0,
            height: size?.height ?? thread[idx].imageHeight ?? 0,
            mime: MediaCrypto.mimeType(for: data)
        )

        let existingCaption = thread[idx].text
        let caption = (existingCaption == "Photo" || existingCaption.isEmpty) ? "" : existingCaption

        do {
            try await finishImageSend(
                optimisticID: messageID,
                peerUserID: peerUserID,
                me: me,
                material: material,
                token: token,
                encoded: encoded,
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
        encoded: EncodedImage,
        caption: String
    ) async throws {
        let (fileKey, sealedFile) = try MediaCrypto.sealFile(encoded.data)
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
            mime: encoded.mime,
            w: encoded.width,
            h: encoded.height,
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
            ourIdentityPublicKey: material.identityPublicKeyData,
            ourUserID: me
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
        local.saveSealedMedia(messageID: dto.id, data: encoded.data)
        if dto.id != optimisticID {
            local.removeCaches(messageIDs: [optimisticID])
        }
        // Cache sealed media payload (file key), not just the caption — needed for reload.
        local.saveSealedPlaintext(messageID: dto.id, data: payloadData)

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
            imageWidth: encoded.width,
            imageHeight: encoded.height,
            imageData: encoded.data,
            sendError: nil
        )
        if var thread = threads[peerUserID],
           let idx = thread.firstIndex(where: { $0.id == optimisticID })
        {
            thread[idx] = sent
            threads[peerUserID] = thread
        }
        persistSnapshot()
        await refreshConversations(force: true)
    }

    /// Records are done by the view; this encrypts, uploads, and sends a voice message.
    /// Optional on-device transcript is sealed inside the media payload (never sent as plaintext).
    /// - Parameter transcriptProvider: Produces the on-device transcript. Called *after* the
    ///   optimistic bubble is on screen, so a long recording is never held back by transcription
    ///   (which can take seconds for a multi-minute note). The result is still sealed into the
    ///   payload, so the recipient gets it without re-transcribing.
    func sendVoice(
        audioData: Data,
        durationMs: Int,
        to peerUserID: UUID,
        waveform: [UInt8]? = nil,
        transcript: String? = nil,
        transcriptProvider: (() async -> String?)? = nil
    ) async -> String? {
        let optimisticID = UUID()
        let me = sessionController?.userID ?? Self.notesPeerID

        let optimistic = ChatMessage(
            id: optimisticID,
            peerUserID: peerUserID,
            senderUserID: me,
            text: "Voice message",
            createdAt: Date(),
            isMine: true,
            deleted: false,
            receipt: .sending,
            kind: .voice,
            voiceData: audioData,
            voiceDurationMs: durationMs,
            voiceWaveform: waveform,
            transcript: nil,
            pendingSync: !isNotesChat(peerUserID)
        )
        var list = threads[peerUserID] ?? []
        list.append(optimistic)
        threads[peerUserID] = list
        local.saveSealedMedia(messageID: optimisticID, data: audioData)

        // Bubble is visible now; only then pay for transcription.
        var resolved = transcript?.trimmingCharacters(in: .whitespacesAndNewlines)
        if resolved?.isEmpty != false, let transcriptProvider {
            resolved = (await transcriptProvider())?
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }
        let trimmedTranscript = (resolved?.isEmpty == false) ? resolved : nil
        let displayText = trimmedTranscript ?? "Voice message"

        if isNotesChat(peerUserID) {
            if var thread = threads[peerUserID],
               let idx = thread.firstIndex(where: { $0.id == optimisticID })
            {
                thread[idx] = ChatMessage(
                    id: optimisticID,
                    peerUserID: peerUserID,
                    senderUserID: me,
                    text: displayText,
                    createdAt: optimistic.createdAt,
                    isMine: true,
                    deleted: false,
                    receipt: .sent,
                    kind: .voice,
                    voiceData: audioData,
                    voiceDurationMs: durationMs,
                    voiceWaveform: waveform,
                    transcript: trimmedTranscript
                )
                threads[peerUserID] = thread
            }
            persistSnapshot()
            return nil
        }

        guard let token = sessionController?.bearerToken,
              let material = cryptoController?.material,
              let realMe = sessionController?.userID
        else { return "Not signed in." }

        persistSnapshot()

        if !connectivity.isOnline {
            isOffline = true
            if var thread = threads[peerUserID],
               let idx = thread.firstIndex(where: { $0.id == optimisticID })
            {
                var updated = thread[idx]
                updated.receipt = .failed
                updated.sendError = "Waiting for connection…"
                updated.transcript = trimmedTranscript
                updated.pendingSync = true
                // Replace so display text can include the transcript.
                thread[idx] = ChatMessage(
                    id: updated.id,
                    peerUserID: updated.peerUserID,
                    senderUserID: updated.senderUserID,
                    text: displayText,
                    createdAt: updated.createdAt,
                    isMine: true,
                    deleted: false,
                    receipt: .failed,
                    kind: .voice,
                    mediaObjectId: updated.mediaObjectId,
                    voiceData: updated.voiceData,
                    voiceDurationMs: updated.voiceDurationMs,
                    voiceWaveform: updated.voiceWaveform,
                    transcript: trimmedTranscript,
                    sendError: "Waiting for connection…",
                    pendingSync: true
                )
                threads[peerUserID] = thread
            }
            persistSnapshot()
            return nil
        }

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
                d: durationMs,
                wf: waveform.flatMap(VoiceWaveform.encode)
            )
            let payloadData = try JSONEncoder().encode(payload)
            let peerPub = try await resolvePeerIdentityPublicKey(peerUserID: peerUserID, token: token)
            let sealed = try MessageCrypto.seal(
                plaintext: payloadData,
                peerUserID: peerUserID,
                toPeerIdentityPublicKey: peerPub,
                ourPrivateKey: material.agreementPrivateKey,
                ourIdentityPublicKey: material.identityPublicKeyData,
                ourUserID: realMe
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
            local.saveSealedMedia(messageID: dto.id, data: audioData)
            if dto.id != optimisticID {
                local.removeCaches(messageIDs: [optimisticID])
            }
            local.saveSealedPlaintext(messageID: dto.id, data: payloadData)

            let sent = ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: realMe,
                text: displayText,
                createdAt: dto.createdAt,
                isMine: true,
                deleted: false,
                receipt: receiptStatus(from: dto),
                kind: .voice,
                mediaObjectId: upload.mediaObjectId,
                voiceData: audioData,
                voiceDurationMs: durationMs,
                voiceWaveform: waveform,
                transcript: trimmedTranscript
            )
            if var thread = threads[peerUserID],
               let idx = thread.firstIndex(where: { $0.id == optimisticID })
            {
                thread[idx] = sent
                threads[peerUserID] = thread
            }
            await refreshConversations(force: true)
            persistSnapshot()
            lastError = nil
            return nil
        } catch {
            let message = SessionController.userMessage(for: error)
            if var thread = threads[peerUserID],
               let idx = thread.firstIndex(where: { $0.id == optimisticID })
            {
                let existing = thread[idx]
                thread[idx] = ChatMessage(
                    id: existing.id,
                    peerUserID: existing.peerUserID,
                    senderUserID: existing.senderUserID,
                    text: displayText,
                    createdAt: existing.createdAt,
                    isMine: true,
                    deleted: false,
                    receipt: .failed,
                    kind: .voice,
                    mediaObjectId: existing.mediaObjectId,
                    voiceData: existing.voiceData ?? audioData,
                    voiceDurationMs: existing.voiceDurationMs ?? durationMs,
                    voiceWaveform: existing.voiceWaveform ?? waveform,
                    transcript: trimmedTranscript,
                    sendError: message,
                    pendingSync: true
                )
                threads[peerUserID] = thread
            }
            lastError = message
            persistSnapshot()
            return message
        }
    }

    /// Loads decrypted voice bytes for playback.
    func ensureVoiceLoaded(for message: ChatMessage) async {
        guard message.kind == .voice,
              message.voiceData == nil,
              !message.deleted
        else { return }

        if let cached = local.sealedMedia(for: message.id) {
            updateMessageVoice(messageID: message.id, peerID: message.peerUserID, data: cached)
            return
        }

        guard let mediaID = message.mediaObjectId,
              let token = sessionController?.bearerToken,
              let material = cryptoController?.material
        else { return }

        do {
            guard let payloadData = try await mediaPayloadData(for: message, token: token, material: material),
                  let payload = try? JSONDecoder().decode(MediaMessagePayload.self, from: payloadData),
                  let keyData = Data(base64Encoded: payload.k)
            else { return }
            let sealedFile = try await mediaService.downloadContent(mediaID: mediaID, token: token)
            let audio = try MediaCrypto.openFile(sealed: sealedFile, keyData: keyData)
            local.saveSealedMedia(messageID: message.id, data: audio)
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
        thread[idx].pendingSync = true
        threads[peerUserID] = thread
        persistSnapshot()
    }

    /// Loads decrypted image bytes for a media message (caches on success).
    func ensureImageLoaded(for message: ChatMessage) async {
        guard message.kind == .image,
              message.imageData == nil,
              !message.deleted
        else { return }

        if let cached = local.sealedMedia(for: message.id) {
            updateMessageImage(messageID: message.id, peerID: message.peerUserID, data: cached)
            return
        }

        // Notes / offline-only images never have a server media id.
        guard let mediaID = message.mediaObjectId,
              let token = sessionController?.bearerToken,
              let material = cryptoController?.material
        else { return }

        do {
            // Prefer cached media payload (file key). Never re-open as recipient — that
            // advances/desyncs the Double Ratchet after the first successful decrypt.
            guard let payloadData = try await mediaPayloadData(for: message, token: token, material: material),
                  let payload = try? JSONDecoder().decode(MediaMessagePayload.self, from: payloadData),
                  let keyData = Data(base64Encoded: payload.k)
            else { return }

            let sealedFile = try await mediaService.downloadContent(mediaID: mediaID, token: token)
            let jpeg = try MediaCrypto.openFile(sealed: sealedFile, keyData: keyData)
            local.saveSealedMedia(messageID: message.id, data: jpeg)
            updateMessageImage(messageID: message.id, peerID: message.peerUserID, data: jpeg)
        } catch {
            // Leave placeholder; user can reopen thread to retry.
        }
    }

    /// Resolves the sealed media payload JSON (contains AES file key). Never re-opens as recipient.
    private func mediaPayloadData(
        for message: ChatMessage,
        token: String,
        material: IdentityKeyMaterial
    ) async throws -> Data? {
        if let cached = local.sealedPlaintext(for: message.id), MessageDecoder.isMediaPayloadData(cached) {
            return cached
        }
        guard message.isMine else {
            // Inbound: payload must already be cached from the first DR open in decodeMessage.
            return nil
        }
        let response = try await messagesService.listMessages(
            peerUserID: message.peerUserID,
            token: token,
            limit: 50
        )
        guard let dto = response.messages.first(where: { $0.id == message.id }),
              let ciphertextB64 = dto.ciphertext,
              let envelopeData = Data(base64Encoded: ciphertextB64)
        else { return nil }
        let payloadData = try MessageCrypto.open(
            envelopeData: envelopeData,
            peerUserID: message.peerUserID,
            with: material.agreementPrivateKey,
            ourIdentityPublicKey: material.identityPublicKeyData,
            senderIdentityPublicKey: material.identityPublicKeyData,
            as: .sender
        )
        local.saveSealedPlaintext(messageID: message.id, data: payloadData)
        return payloadData
    }

    private func updateMessageImage(messageID: UUID, peerID: UUID, data: Data) {
        guard var thread = threads[peerID],
              let idx = thread.firstIndex(where: { $0.id == messageID })
        else { return }
        thread[idx].imageData = data
        threads[peerID] = thread
    }

    func preview(for conversation: ConversationItemDTO) -> String {
        preview(forPeer: conversation.peer.id)
    }

    /// List subtitle for a peer thread (including Notes).
    func preview(forPeer peerID: UUID) -> String {
        ChatListFormatting.preview(
            forPeer: peerID,
            threads: threads,
            isNotes: isNotesChat(peerID)
        )
    }

    /// Latest activity timestamp for Notes (or nil when empty).
    func notesLastActivity() -> Date? {
        threads[Self.notesPeerID]?.last?.createdAt
    }

    /// Relative day label for list rows (Today → time, Yesterday, else date).
    func timeLabel(for date: Date?) -> String {
        ChatListFormatting.timeLabel(for: date)
    }

    /// Clock time for in-bubble meta (always `11:05`-style).
    func clockTimeLabel(for date: Date?) -> String {
        ChatListFormatting.clockTimeLabel(for: date)
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
            } else if type == "message.deleted" {
                handleDeletedEvent(json)
            } else if type == "typing" {
                handleTyping(json)
            } else if type == "presence.update" {
                handlePresence(json)
            } else if type.hasPrefix("call.") {
                callController?.handleRealtime(type: type, json: json)
            } else if type.hasPrefix("contact.") {
                handleContactRealtime(type: type, json: json)
            }
        }
    }

    /// Apply contact invite / accept / cancel immediately, then refresh from API.
    private func handleContactRealtime(type: String, json: [String: Any]) {
        if type == "contact.request",
           let requestJSON = json["request"] as? [String: Any],
           let data = try? JSONSerialization.data(withJSONObject: requestJSON),
           let request = try? JSONDecoder.api.decode(ContactRequestDTO.self, from: data),
           let me = sessionController?.userID,
           request.toUserId == me,
           request.status == "pending"
        {
            // Optimistic insert so Pending shows before the network round-trip.
            if !incomingRequests.contains(where: { $0.id == request.id }) {
                incomingRequests.insert(request, at: 0)
            }
        }
        Task { await refreshContacts() }
    }

    private func handleDeliveredEvent(_ json: [String: Any]) {
        guard let idString = json["message_id"] as? String,
              let messageID = UUID(uuidString: idString)
        else { return }
        updateReceipt(messageID: messageID, atLeast: .delivered)
    }

    /// Peer (or one of our other devices) deleted for everyone — tombstone it here too.
    private func handleDeletedEvent(_ json: [String: Any]) {
        guard let idString = json["message_id"] as? String,
              let messageID = UUID(uuidString: idString),
              let peer = peerID(forMessage: messageID)
        else { return }
        tombstoneMessage(messageID: messageID, peerUserID: peer)
        Task { await refreshConversations(force: true) }
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
        MessageDecoder.receiptStatus(from: dto)
    }

    private func handleTyping(_ json: [String: Any]) {
        guard let userString = json["user_id"] as? String,
              let userID = UUID(uuidString: userString),
              let isTyping = json["is_typing"] as? Bool
        else { return }
        if isTyping {
            if !typingPeerIDs.contains(userID) { typingPeerIDs.insert(userID) }
        } else if typingPeerIDs.contains(userID) {
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
        let presence = PresenceDTO(userId: userID, online: online, lastSeenAt: lastSeen)
        if presenceByUser[userID] != presence { presenceByUser[userID] = presence }
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
                _ = try? await messagesService.markReadBulk(
                    peerUserID: threadPeer,
                    upToMessageID: chat.id,
                    token: token
                )
            }
        }
        await refreshConversations()
        persistSnapshot()
    }

    private func decodeMessage(
        _ dto: MessageDTO,
        me: UUID,
        material: IdentityKeyMaterial,
        token: String,
        forcePeerUserID: UUID? = nil
    ) async -> ChatMessage {
        var message = await MessageDecoder.decode(
            dto,
            context: MessageDecoder.Context(
                me: me,
                material: material,
                token: token,
                conversations: conversations,
                threads: threads,
                local: local,
                mediaService: mediaService,
                resolvePeerIdentityPublicKey: { [weak self] peerID, tok in
                    guard let self else { throw APIError.decoding }
                    // Notes: peer is self — identity is our own key.
                    if peerID == me {
                        return material.identityPublicKeyData
                    }
                    return try await self.resolvePeerIdentityPublicKey(peerUserID: peerID, token: tok)
                }
            )
        )
        if let forcePeerUserID, message.peerUserID != forcePeerUserID {
            message = ChatMessage(
                id: message.id,
                peerUserID: forcePeerUserID,
                senderUserID: message.senderUserID,
                text: message.text,
                createdAt: message.createdAt,
                isMine: message.isMine,
                deleted: message.deleted,
                receipt: message.receipt,
                kind: message.kind,
                mediaObjectId: message.mediaObjectId,
                imageWidth: message.imageWidth,
                imageHeight: message.imageHeight,
                imageData: message.imageData,
                voiceData: message.voiceData,
                voiceDurationMs: message.voiceDurationMs,
                voiceWaveform: message.voiceWaveform,
                transcript: message.transcript,
                sendError: message.sendError,
                todoDone: message.todoDone,
                pendingSync: message.pendingSync
            )
        }
        return message
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

    // MARK: - Local persistence (delegates to MessagingLocalRepository)

    private func hydrateFromDisk() {
        let state = local.hydrate(userID: sessionController?.userID)

        if contacts.isEmpty, !state.contacts.isEmpty {
            contacts = state.contacts
            hasLoadedContacts = true
        }
        if incomingRequests.isEmpty, !state.incomingRequests.isEmpty {
            incomingRequests = state.incomingRequests
        }
        if conversations.isEmpty, !state.conversations.isEmpty {
            conversations = state.conversations
            hasLoadedChats = true
        }

        var restoredThreads = threads
        for (peerID, messages) in state.threads {
            if restoredThreads[peerID]?.isEmpty == false { continue }
            restoredThreads[peerID] = messages
        }
        if restoredThreads[Self.notesPeerID] == nil {
            restoredThreads[Self.notesPeerID] = []
        }
        threads = restoredThreads

        var unread = unreadCountByPeer
        for (peerID, count) in state.unreadByPeer {
            unread[peerID] = count
        }
        unreadCountByPeer = unread
    }

    private func persistSnapshot() {
        let dropped = local.persist(
            userID: sessionController?.userID,
            contacts: contacts,
            incomingRequests: incomingRequests,
            conversations: conversations,
            threads: threads,
            unreadByPeer: unreadCountByPeer
        )
        if !dropped.isEmpty {
            for (peerID, messages) in threads {
                let kept = messages.filter { !dropped.contains($0.id) }
                if kept.count != messages.count {
                    threads[peerID] = kept
                }
            }
        }
    }

    /// Persist a single peer after send/load without rewriting every thread file.
    private func persistThread(_ peerID: UUID) {
        guard let messages = threads[peerID] else {
            persistSnapshot()
            return
        }
        local.persistThread(
            peerID: peerID,
            messages: messages,
            userID: sessionController?.userID,
            conversations: conversations,
            contacts: contacts,
            incomingRequests: incomingRequests,
            unreadByPeer: unreadCountByPeer
        )
    }

    private func appendAndSyncNote(
        text: String,
        kind: ChatMessageKind,
        todoDone: Bool?
    ) async {
        let peerUserID = Self.notesPeerID
        let me = sessionController?.userID ?? peerUserID
        let message = NotesLocal.makeNote(
            text: text,
            kind: kind,
            senderUserID: me,
            todoDone: todoDone
        )
        var list = threads[peerUserID] ?? []
        list.append(message)
        threads[peerUserID] = list
        let wireText = kind == .todo
            ? NotesLocal.syncedTodoPlaintext(text: text, done: todoDone ?? false)
            : text
        local.saveSealedPlaintext(messageID: message.id, text: wireText)
        persistThread(peerUserID)

        // Multi-device: dual-seal to self when online.
        guard connectivity.isOnline,
              let token = sessionController?.bearerToken,
              let realMe = sessionController?.userID,
              let material = cryptoController?.material
        else { return }

        do {
            let sent = try await deliverPendingText(
                messageID: message.id,
                text: wireText,
                peerUserID: realMe,
                me: realMe,
                material: material,
                token: token
            )
            // deliverPendingText stores under api peer (me); re-map bubble into Notes thread.
            //
            // Re-key to the server's id while doing it: the sealed plaintext is already
            // stored under that id, and both delete and the next reload match on it. Keeping
            // the client id here is what let a synced note come back after it was deleted.
            if var notes = threads[peerUserID],
               let idx = notes.firstIndex(where: { $0.id == message.id })
            {
                notes[idx] = ChatMessage(
                    id: sent.id,
                    peerUserID: peerUserID,
                    senderUserID: sent.senderUserID,
                    text: message.text,
                    createdAt: sent.createdAt,
                    isMine: true,
                    deleted: false,
                    receipt: .sent,
                    kind: message.kind,
                    todoDone: message.todoDone
                )
                threads[peerUserID] = notes
                local.removeCaches(messageIDs: [message.id])
                persistThread(peerUserID)
            }
            // Clear accidental thread keyed by realMe if deliverPendingText wrote there.
            if var mine = threads[realMe] {
                mine.removeAll { $0.id == message.id }
                if mine.isEmpty {
                    threads[realMe] = nil
                } else {
                    threads[realMe] = mine
                }
            }
        } catch {
            if var notes = threads[peerUserID],
               let idx = notes.firstIndex(where: { $0.id == message.id })
            {
                notes[idx].pendingSync = true
                threads[peerUserID] = notes
                persistThread(peerUserID)
            }
        }
    }

    /// Returns the delivered message as the server keyed it (`dto.id` ≠ the client id).
    @discardableResult
    private func deliverPendingText(
        messageID: UUID,
        text: String,
        peerUserID: UUID,
        me: UUID,
        material: IdentityKeyMaterial,
        token: String
    ) async throws -> ChatMessage {
        let peerPub = try await resolvePeerIdentityPublicKey(peerUserID: peerUserID, token: token)
        let sealed = try MessageCrypto.seal(
            plaintext: Data(text.utf8),
            peerUserID: peerUserID,
            toPeerIdentityPublicKey: peerPub,
            ourPrivateKey: material.agreementPrivateKey,
            ourIdentityPublicKey: material.identityPublicKeyData,
            ourUserID: me
        )
        let dto = try await messagesService.send(
            SendMessageRequest(
                peerUserId: peerUserID,
                clientMessageId: messageID,
                contentType: "text",
                ciphertext: sealed.base64EncodedString()
            ),
            token: token
        )
        local.saveSealedPlaintext(messageID: dto.id, text: text)
        let sent = ChatMessage(
            id: dto.id,
            peerUserID: peerUserID,
            senderUserID: me,
            text: text,
            createdAt: dto.createdAt,
            isMine: true,
            deleted: false,
            receipt: receiptStatus(from: dto)
        )
        if var list = threads[peerUserID],
           let idx = list.firstIndex(where: { $0.id == messageID })
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
        persistSnapshot()
        return sent
    }

    /// Flushes queued outbound messages after reconnect.
    private func flushPendingSends() async {
        await outboundQueue.flush { [weak self] in
            await self?.performPendingFlush()
        }
    }

    private func performPendingFlush() async {
        guard connectivity.isOnline,
              let token = sessionController?.bearerToken,
              let me = sessionController?.userID,
              let material = cryptoController?.material
        else { return }

        let pending = OutboundPending.items(from: threads, notesPeerID: Self.notesPeerID)
        for item in pending {
            switch item {
            case let .text(messageID, peerID, text):
                do {
                    try await deliverPendingText(
                        messageID: messageID,
                        text: text,
                        peerUserID: peerID,
                        me: me,
                        material: material,
                        token: token
                    )
                } catch {
                    // Leave pending; try again next reconnect.
                }
            case let .image(messageID, peerID, caption):
                guard let thread = threads[peerID],
                      let message = thread.first(where: { $0.id == messageID }),
                      let data = message.imageData ?? local.sealedMedia(for: messageID)
                else { continue }
                let size = MediaCrypto.pixelSize(for: data)
                let encoded = EncodedImage(
                    data: data,
                    width: size?.width ?? message.imageWidth ?? 0,
                    height: size?.height ?? message.imageHeight ?? 0,
                    mime: MediaCrypto.mimeType(for: data)
                )
                if var list = threads[peerID],
                   let idx = list.firstIndex(where: { $0.id == messageID })
                {
                    list[idx].receipt = .sending
                    list[idx].sendError = nil
                    threads[peerID] = list
                }
                do {
                    try await finishImageSend(
                        optimisticID: messageID,
                        peerUserID: peerID,
                        me: me,
                        material: material,
                        token: token,
                        encoded: encoded,
                        caption: caption
                    )
                } catch {
                    markImageFailed(
                        optimisticID: messageID,
                        peerUserID: peerID,
                        error: SessionController.userMessage(for: error)
                    )
                }
            case .voice:
                // Voice re-send needs a dedicated path (avoid double-append). Leave for retry UI.
                break
            }
        }
        await refreshConversations(force: true)
    }
}

private extension ISO8601DateFormatter {
    static let apiFlexible: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
}
