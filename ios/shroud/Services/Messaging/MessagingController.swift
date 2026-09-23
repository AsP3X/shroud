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
    /// Peers typing to us right now; each lapses after `typingExpiry` unless refreshed.
    private(set) var typingPeerIDs: Set<UUID> = []
    private var typingExpiryTasks: [UUID: Task<Void, Never>] = [:]
    /// Outgoing typing: the peer we last told we were typing, when, and the idle timer that
    /// says we stopped.
    private var typingSentTo: UUID?
    private var typingSentAt: Date?
    private var typingIdleTask: Task<Void, Never>?
    /// Peers recording a voice note to us right now; same expiry as typing.
    private(set) var recordingPeerIDs: Set<UUID> = []
    private var recordingExpiryTasks: [UUID: Task<Void, Never>] = [:]
    /// Outgoing recording: the peer we last told we were recording, and the keepalive.
    private var recordingSentTo: UUID?
    private var recordingSentAt: Date?
    private var recordingKeepaliveTask: Task<Void, Never>?
    private(set) var presenceByUser: [UUID: PresenceDTO] = [:]
    /// Unread inbound counts by peer (local; cleared when the thread is opened).
    private(set) var unreadCountByPeer: [UUID: Int] = [:]
    /// Peer whose conversation is currently on screen (suppresses unread increments).
    private(set) var activePeerID: UUID?

    /// Server-side consent: when true, a contact's "delete for both" also wipes this
    /// account's copy of the chat. Off until the account opts in — see `PrivacySettingsDTO`.
    private(set) var allowsPeerChatDelete = false
    /// False until the flag has been read from the server once (the toggle stays disabled).
    private(set) var hasLoadedPrivacySettings = false
    /// Users this account has blocked; drives the unblock list in Privacy & Security.
    private(set) var blockedUsers: [BlockItemDTO] = []
    /// Peers whose server identity key no longer matches the first-seen (TOFU) key.
    private(set) var identityChanges: [UUID: PeerIdentityChange] = [:]
    private var verifiedPeerIDs: Set<UUID> = []

    private let contactsService = ContactsService()
    private let messagesService = MessagesService()
    private let privacyService = PrivacyService()
    private let blocksService = BlocksService()
    private let mediaService = MediaService()
    private let keyBundleService = KeyBundleService()
    private let peerKeys = PeerIdentityStore()
    /// Encrypted offline history + decrypt/media caches (not the network layer).
    private let local = MessagingLocalRepository()
    /// `prepareCachedState()` already loaded the sealed cache; the next `start()` skips it.
    private var hydratedAheadOfStart = false
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
    /// One in-flight older-page load per peer, and the background walk that drives it.
    private var olderLoadTasks: [UUID: Task<Void, Never>] = [:]
    private var olderPrefetchTasks: [UUID: Task<Void, Never>] = [:]
    /// Peers whose history is all in `threads` (the server has nothing older to give).
    private(set) var olderHistoryExhausted: Set<UUID> = []
    /// Peers with an older page in flight (the chat shows a spinner at the top).
    private(set) var loadingOlderPeerIDs: Set<UUID> = []
    /// Dedup concurrent image/voice hydrate for the same message id.
    private var mediaHydrateTasks: [UUID: Task<Void, Never>] = [:]
    /// Serializes outbound flush so reconnect + poll don't double-send.
    private let outboundQueue = OutboundSendQueue()
    /// Presence is swept in bulk at most this often; live changes arrive over WS anyway.
    private var lastPresenceSweep: Date?
    private let presenceSweepInterval: TimeInterval = 30
    /// Optional call controller for WS call.* fan-in (bound from RootView).
    private weak var callController: CallController?

    private weak var sessionController: SessionController?
    private weak var cryptoController: CryptoController?
    /// Shared transcripts whose voice note is not in the thread yet (annotation arrived first).
    private var pendingSharedTranscripts: [UUID: String] = [:]
    /// Own voice notes whose transcript is still being made, by optimistic id.
    private var voiceTranscriptsInFlight: Set<UUID> = []
    /// Server ids those notes were re-keyed to, so a late transcript finds its bubble.
    private var sentVoiceIDs: [UUID: UUID] = [:]
    /// Transcripts that landed while their note was still sending, by optimistic id;
    /// shared once the server has keyed the note.
    private var transcriptsAwaitingSend: [UUID: String] = [:]
    /// Our latest wanted reaction per message while a send for it is in flight: taps during
    /// the request collapse into one follow-up with the last choice.
    private var reactionIntents: [UUID: ReactionIntent] = [:]
    private var reactionSendTasks: [UUID: Task<Void, Never>] = [:]
    /// Our reaction as the server last confirmed it, per message with a send in flight.
    private var reactionRollback: [UUID: ReactionRollback] = [:]
    /// Catch-up cursors (store peer → highest reaction `seq` applied), read from disk once.
    private var reactionCursorCache: [UUID: Int64]?
    /// The last reaction of ours that could not be saved; the chip was put back. The chat shows
    /// it as a toast.
    private(set) var reactionFailure: ReactionFailure?
    /// Most emoji one person may leave on one message: the server's setting (`GET /config`),
    /// remembered for offline starts. 5 until the server has said.
    private(set) var reactionLimit: Int = UserDefaults.standard.object(forKey: MessagingController.reactionLimitKey) as? Int ?? 5
    nonisolated static let reactionLimitKey = "shroud.reactions.maxPerUser"
    /// Saves of threads whose reactions changed, batched: a burst of events writes once.
    private var reactionPersistTasks: [UUID: Task<Void, Never>] = [:]
    /// Highest reaction seq this device marked seen per chat. A conversations refresh that
    /// raced the seen call still carries the old badge; this keeps it from coming back.
    private var reactionsSeenLocally: [UUID: Int64] = [:]
    private var conversationsRefreshSoon: Task<Void, Never>?

    /// Expose realtime health for diagnostics UI if needed.
    var isRealtimeConnected: Bool { realtime.isConnected }

    enum ChatMessageKind: Equatable, Sendable {
        case text
        case image
        case voice
        case video
        /// Local Notes checklist item (never sent to the server).
        case todo
    }

    struct ChatMessage: Identifiable, Equatable, Sendable {
        let id: UUID
        let peerUserID: UUID
        let senderUserID: UUID
        /// Caption, list preview ("Photo" / "Video" / "Voice message"), or transcript snippet.
        ///
        /// Mutable only so a bubble stored by a build without reply support can have its body
        /// re-read out of the reply envelope it was saved as (`MessageDecoder`).
        var text: String
        let createdAt: Date
        let isMine: Bool
        let deleted: Bool
        /// Outbound only; ignored for inbound.
        var receipt: MessageReceiptStatus
        var kind: ChatMessageKind
        var mediaObjectId: UUID?
        var imageWidth: Int?
        var imageHeight: Int?
        /// Full-resolution image bytes after explicit download (or local send).
        var imageData: Data?
        /// Small JPEG preview from the sealed envelope — shown before full download.
        var previewData: Data?
        /// Full media size in bytes (from payload) for the download chip.
        var mediaByteCount: Int?
        /// Decrypted voice bytes (m4a) when loaded.
        var voiceData: Data?
        /// Decrypted video bytes (mp4/mov) when loaded.
        var videoData: Data?
        /// Voice or video duration in milliseconds.
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
        /// The message this one quotes (sealed inside the plaintext, never server metadata).
        var replyTo: MessageReplyReference?
        /// Link preview sealed with a text message (`lp`). When the preview has a large image,
        /// that image is this message's media blob: `mediaObjectId` / `imageData` /
        /// `previewData` (blurred placeholder) / `imageWidth` + `imageHeight` describe it.
        var linkPreview: LinkPreview?
        /// Everyone's reactions, oldest change first; removals stay as entries without an emoji
        /// (`MessageReaction`). Chips come from `ReactionMerge.chips`.
        var reactions: [MessageReaction]

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
            previewData: Data? = nil,
            mediaByteCount: Int? = nil,
            voiceData: Data? = nil,
            videoData: Data? = nil,
            voiceDurationMs: Int? = nil,
            voiceWaveform: [UInt8]? = nil,
            transcript: String? = nil,
            sendError: String? = nil,
            todoDone: Bool? = nil,
            pendingSync: Bool = false,
            replyTo: MessageReplyReference? = nil,
            linkPreview: LinkPreview? = nil,
            reactions: [MessageReaction] = []
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
            self.previewData = previewData
            self.mediaByteCount = mediaByteCount
            self.voiceData = voiceData
            self.videoData = videoData
            self.voiceDurationMs = voiceDurationMs
            self.voiceWaveform = voiceWaveform
            self.transcript = transcript
            self.sendError = sendError
            self.todoDone = todoDone
            self.pendingSync = pendingSync
            self.replyTo = replyTo
            self.linkPreview = linkPreview
            self.reactions = reactions
        }

        /// A text message whose link preview carries a large image (Telegram's big layout).
        ///
        /// Human: The sender's copy holds the JPEG in `imageData` before the upload lands;
        /// everyone else knows it by the media id until the blob is downloaded.
        var hasLargeLinkImage: Bool {
            kind == .text && linkPreview != nil && (mediaObjectId != nil || imageData != nil)
        }

        /// The large link-preview image is on the server but not decrypted on this device yet.
        var needsLinkImageDownload: Bool {
            hasLargeLinkImage && imageData == nil && !deleted
        }

        /// Full media is not on device yet — show preview + download (Telegram-style).
        var needsMediaDownload: Bool {
            guard mediaObjectId != nil, !deleted else { return false }
            switch kind {
            case .image: return imageData == nil
            case .video: return videoData == nil
            default: return false
            }
        }

        /// Poster/preview JPEG for the bubble (full image, payload thumb, or video poster).
        var displayPreviewData: Data? {
            if kind == .image, let imageData { return imageData }
            return previewData ?? imageData
        }

        /// Whether this bubble can be quoted at all.
        ///
        /// Human: A message that has not reached the server yet carries a client id the other
        /// side could never resolve, and a tombstone has nothing left to quote — no swipe for
        /// either. Notes are local but keep `.sent`, so replying inside Saved Messages works.
        var canBeQuoted: Bool {
            !deleted && !pendingSync && receipt != .failed && receipt != .sending
        }

        /// The quote a reply to this message carries.
        ///
        /// Agent: Sealed into the reply's plaintext; the snippet is clamped by
        /// `MessageReplyReference.init`.
        var replyReference: MessageReplyReference? {
            guard canBeQuoted else { return nil }
            let quotedKind: MessageReplyReference.Kind = switch kind {
            case .image: .image
            case .video: .video
            case .voice: .voice
            case .text, .todo: .text
            }
            // Media bubbles keep a stand-in label in `text` ("Photo", "Video", "Voice message");
            // the quote derives those from `kind`, so only a real caption is worth sealing.
            let quotedSnippet: String = switch kind {
            case .image: (text == "Photo" || text == "Media") ? "" : text
            case .video: (text == "Video" || text == "Media") ? "" : text
            case .voice: ""
            case .text, .todo: text
            }
            return MessageReplyReference(
                messageID: id,
                senderUserID: senderUserID,
                kind: quotedKind,
                snippet: quotedSnippet
            )
        }
    }

    /// Live progress of one media message's bytes, in either direction.
    ///
    /// Human: A 20 MB video spends seconds compressing and seconds uploading. Telegram shows
    /// one ring that fills across both, so the phases are modelled here rather than in the view.
    struct MediaTransfer: Equatable, Sendable {
        enum Phase: Equatable, Sendable {
            /// Compressing/exporting, before anything touches the network (upload only).
            case preparing
            /// Bytes on the wire.
            case transferring
            /// Decrypting, thumbnailing or sealing — real work, but no byte counter to show.
            case finishing
        }

        var phase: Phase
        var isUpload: Bool
        /// 0…1 within the current phase; nil until a length is known.
        var fraction: Double?
        /// Full payload size, for the "1.2 MB / 4.8 MB" readout.
        var totalBytes: Int?

        /// One 0…1 value for the ring, so compress → upload reads as a single continuous fill.
        var ringFraction: Double {
            switch phase {
            case .preparing: (fraction ?? 0) * Self.prepareShare
            case .transferring: isUpload
                ? Self.prepareShare + (fraction ?? 0) * (1 - Self.prepareShare)
                : (fraction ?? 0)
            case .finishing: 1
            }
        }

        /// No trustworthy number to draw — the ring spins instead of filling.
        var isIndeterminate: Bool {
            phase == .finishing || fraction == nil
        }

        /// Bytes already moved, for the readout under the ring.
        var movedBytes: Int? {
            guard let totalBytes, totalBytes > 0, phase == .transferring, let fraction else { return nil }
            return Int(Double(totalBytes) * fraction)
        }

        /// How much of the ring compression owns before the upload takes over.
        private static let prepareShare = 0.3
    }

    /// In-flight media transfers by message id (empty when nothing is moving).
    private(set) var mediaTransfers: [UUID: MediaTransfer] = [:]

    /// Signed-in account id, for views that have to tell "You" from the peer (reply quotes).
    /// Nil while signed out.
    var myUserID: UUID? { sessionController?.userID }
    var myUsername: String? { sessionController?.username }

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

    /// Loads the sealed offline cache into memory ahead of `start()`.
    ///
    /// Human: The lock screen calls this while it still says "Checking…". The load is
    /// synchronous disk + crypto work; left to `start()` it ran on the frame the unlock
    /// animation hands over to Chats, froze its last beat and inserted Chats empty.
    func prepareCachedState() {
        guard !hydratedAheadOfStart, let key = cryptoController?.material?.historyKey else { return }
        local.setHistoryKey(key)
        hydrateFromDisk()
        hydratedAheadOfStart = true
    }

    /// Drops what `prepareCachedState()` loaded when the unlock it was for did not go through.
    func discardPreparedCachedState() {
        guard hydratedAheadOfStart else { return }
        lockSensitiveMemory()
        clearInMemoryState()
    }

    func start() {
        guard let token = sessionController?.bearerToken else { return }
        local.setHistoryKey(cryptoController?.material?.historyKey)
        connectivity.start()
        isOffline = !connectivity.isOnline
        // Paint cached chats/contacts immediately so offline / cold start feels instant.
        if hydratedAheadOfStart {
            hydratedAheadOfStart = false
        } else {
            hydrateFromDisk()
        }
        realtime.connect(token: token)
        startPollingFallback()
        startContactsPolling()
        Task {
            await refreshContacts()
            await refreshConversations()
            await refreshPrivacySettings()
            await refreshServerConfig()
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
        stopActivity()
        if wipeDisk {
            clearLocalData()
        } else {
            clearInMemoryState()
        }
    }

    /// Stops polling, the socket, queued sends and every disk write, and drops what is in
    /// memory — without saving a last snapshot first.
    ///
    /// Human: The logout wipe calls this before it deletes anything: a snapshot written now would
    /// only be written to be deleted, and a poll landing mid-wipe would refill the stores.
    func haltForDeviceWipe() {
        stopActivity()
        clearInMemoryState()
    }

    private func stopActivity() {
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
        cancelHistoryPaging()
        // Next sign-in is a genuine first load again, so the skeleton is allowed back.
        hasLoadedContacts = false
        hasLoadedChats = false
        hasLoadedPrivacySettings = false
        allowsPeerChatDelete = false
        blockedUsers = []
        identityChanges = [:]
        verifiedPeerIDs = []
        contactsError = nil
        chatsError = nil
        lastError = nil
        isOffline = false
        lastPresenceSweep = nil
        local.setHistoryKey(nil)
    }

    /// Wipes in-memory lists and on-device message caches (plaintext, media, ratchets, peer keys).
    /// Called on sign-out so a restart never resurfaces another account’s data.
    func clearLocalData() {
        local.clear(userID: sessionController?.userID)
        clearInMemoryState()
        peerKeys.clear()
        RatchetSessionStore.deleteAll()
        SenderTagStore.deleteAll()
    }

    private func clearInMemoryState() {
        hydratedAheadOfStart = false
        contacts = []
        incomingRequests = []
        conversations = []
        threads = [:]
        clearAllTyping()
        presenceByUser = [:]
        unreadCountByPeer = [:]
        reactionCursorCache = nil
        reactionsSeenLocally = [:]
        identityChanges = [:]
        verifiedPeerIDs = []
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
            await refreshPrivacySettings()
            await refreshServerConfig()
            if let peer = activePeerID, !isNotesChat(peer) {
                await loadThread(peerUserID: peer)
            }
            await flushPendingSends()
        }
    }

    /// Clears decrypted threads and history key from RAM (sealed files stay on disk).
    /// Call when the app backgrounds so a seized unlocked device cannot read chats from memory.
    func lockSensitiveMemory() {
        // Reaction changes waiting for their batched save go to disk before the key leaves.
        flushReactionPersists()
        local.lockSensitiveMemory()
        hydratedAheadOfStart = false
        // Drop message bodies; keep conversation list shells for a less jarring re-unlock.
        threads = [:]
        clearAllTyping()
        unreadCountByPeer = [:]
        reactionCursorCache = nil
        activePeerID = nil
        threadLoadTasks.values.forEach { $0.cancel() }
        threadLoadTasks.removeAll()
        cancelHistoryPaging()
        DecodedImageCache.removeAll()
        LinkPreviewImageCache.removeAll()
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
            // Saved here: refreshes only save what they changed, so a restart would bring
            // the badge back.
            persistThread(peerID)
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
            let changed = contacts != sorted || incomingRequests != requests
            if contacts != sorted { contacts = sorted }
            if incomingRequests != requests { incomingRequests = requests }
            if contactsError != nil { contactsError = nil }
            if lastError != nil { lastError = nil }
            isOffline = false
            // Rows are publishable now — don't hold the skeleton up for the presence fan-out.
            if !hasLoadedContacts { hasLoadedContacts = true }
            // Every tab switch and poll lands here; an unchanged roster has nothing to save.
            if changed { persistSnapshot() }
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
            let list = applyingLocalReactionSeen(try await messagesService.listConversations(token: token))
            // Same-value writes still invalidate observers — only publish real changes.
            let changed = conversations != list
            if changed { conversations = list }
            if chatsError != nil { chatsError = nil }
            if lastError != nil { lastError = nil }
            isOffline = false
            // Every tab switch lands here; an unchanged list has nothing to save.
            if changed { persistSnapshot() }
            // Something reacted to our messages while this chat is open: it is being seen.
            if let active = activePeerID, hasPendingUnseenReactions(active) {
                markReactionsSeen(peerUserID: active)
            }
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
    /// Loads a peer thread (or Notes): the newest page, then whatever is newer than what we hold.
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
        if activePeerID == storePeer { startOlderPrefetch(storePeer) }
    }

    /// Newest page on open and on each refresh: small, so a chat shows after one short decrypt.
    private static let firstPageSize = 40
    /// Older pages, walked in the background or as the reader scrolls up (server max is 100).
    private static let historyPageSize = 100
    /// Hard stop: 4000 messages (still clamped by 90-day retention).
    private static let historyMaxMessages = 4000
    /// Messages walked in behind the newest page before older ones wait for the reader.
    private static let olderPrefetchTarget = 300
    /// Pause between those background pages, so decrypting never competes with scrolling.
    private static let olderPrefetchPause: Duration = .milliseconds(600)

    /// One decoded history page.
    private struct HistoryPage {
        /// Chronological, annotations left out (they fold into their targets).
        var messages: [ChatMessage] = []
        /// Inbound ids (annotations included) the server may still want a delivery ack for.
        var inboundIDs: [UUID] = []
        /// Cursor for the page before this one; nil once the start (or retention) is reached.
        var older: HistoryCursor?
        /// Every id the server returned, to tell whether a refresh has met what we hold.
        var serverIDs: Set<UUID> = []
        /// Opened reactions per message; a message the page returned without any is absent.
        var reactions: [UUID: [MessageReaction]] = [:]
        /// The server's reaction `seq` as it read the page (`ReactionMerge.reconcile`); nil from
        /// a server without reactions, which leaves held reactions alone.
        var reactionSnapshot: Int64?
    }

    private struct HistoryCursor {
        let createdAt: Date
        let id: UUID
    }

    private var retentionCutoff: Date {
        Calendar.current.date(
            byAdding: .day,
            value: -LocalMessageStore.retentionDays,
            to: Date()
        ) ?? Date().addingTimeInterval(-TimeInterval(LocalMessageStore.retentionDays) * 86_400)
    }

    /// Fetches and decodes the page just older than `before` (the newest page when nil).
    private func fetchHistoryPage(
        apiPeerID: UUID,
        storePeerID: UUID,
        before: HistoryCursor?,
        limit: Int,
        token: String,
        me: UUID,
        material: IdentityKeyMaterial
    ) async throws -> HistoryPage {
        let isNotes = isNotesChat(storePeerID)
        let cutoff = retentionCutoff
        let response = try await messagesService.listMessages(
            peerUserID: apiPeerID,
            token: token,
            limit: limit,
            beforeCreatedAt: before?.createdAt,
            beforeID: before?.id
        )

        // Server returns newest-first; reverse → chronological within this page.
        var page = HistoryPage()
        /// What the thread already shows per message, so a record at the same seq is reused
        /// instead of decrypted again. Built once, on the first message with reactions.
        var heldReactions: [UUID: [MessageReaction]]?
        page.messages.reserveCapacity(response.messages.count)
        for dto in response.messages.reversed() {
            page.serverIDs.insert(dto.id)
            var message = await decodeMessage(
                dto,
                me: me,
                material: material,
                token: token,
                forcePeerUserID: isNotes ? storePeerID : nil
            )
            if dto.contentType == MessageAnnotation.contentType {
                // Not a bubble: it attaches to a message; folded in after the merge.
                if let shared = MessageAnnotation.parseTranscript(message.text) {
                    noteSharedTranscript(shared.text, for: shared.messageID)
                }
                if !isNotes, dto.senderUserId != me {
                    page.inboundIDs.append(dto.id)
                }
                continue
            }
            if isNotes {
                message = notesMessageFromServer(message)
            }
            // Drop over-retention early so we don't publish then strip.
            if !isNotes, message.createdAt < cutoff, !message.pendingSync {
                continue
            }
            page.messages.append(message)
            if !isNotes, dto.senderUserId != me {
                page.inboundIDs.append(dto.id)
            }
            if let reactions = dto.reactions, !reactions.isEmpty, !message.deleted {
                // Only records for this very message, from the two people in this chat: a
                // record listed under another message, or from a stranger, is not shown.
                let reactors: Set<UUID> = [me, apiPeerID]
                var latest: [UUID: ReactionDTO] = [:]
                for record in reactions where record.messageId == dto.id && reactors.contains(record.userId) {
                    if (latest[record.userId]?.seq ?? .min) < record.seq { latest[record.userId] = record }
                }
                heldReactions = heldReactions ?? Dictionary(
                    (threads[storePeerID] ?? []).map { ($0.id, $0.reactions) },
                    uniquingKeysWith: { first, _ in first }
                )
                page.reactions[dto.id] = await openReactions(
                    Array(latest.values),
                    held: heldReactions?[dto.id] ?? [],
                    me: me,
                    material: material,
                    token: token
                )
            }
        }
        page.reactionSnapshot = response.reactionSeq

        let oldest = response.messages.last // newest-first from server → oldest of page
        let mayHaveMore = response.hasMore == true || response.messages.count >= limit
        if mayHaveMore, let oldest, oldest.createdAt >= cutoff {
            page.older = HistoryCursor(createdAt: oldest.createdAt, id: oldest.id)
        }
        return page
    }

    /// Merges a decoded page into the live thread (re-read, so sends made meanwhile stay).
    private func publishHistoryPage(_ page: HistoryPage, storePeerID: UUID) {
        let currentThread = threads[storePeerID] ?? []
        var merged = foldSharedTranscripts(
            into: ThreadMessageMerge.mergeThread(
                decoded: page.messages,
                previous: currentThread,
                pendingLocal: currentThread.filter(\.pendingSync)
            )
        )
        if let snapshot = page.reactionSnapshot {
            for index in merged.indices where page.serverIDs.contains(merged[index].id) {
                guard !merged[index].deleted else { continue }
                let reconciled = ReactionMerge.reconcile(
                    held: merged[index].reactions,
                    page: page.reactions[merged[index].id] ?? [],
                    snapshot: snapshot
                )
                if reconciled != merged[index].reactions {
                    merged[index].reactions = reconciled
                }
            }
            // A change catch-up applied while this page was in flight, to a message the page
            // brings in, is not in the page: go back to the page's snapshot so the next
            // catch-up applies it again.
            if let cursor = reactionCursors()[storePeerID], snapshot < cursor {
                saveReactionCursor(snapshot, for: storePeerID)
            }
        }
        if threads[storePeerID] != merged {
            threads[storePeerID] = merged
        }
    }

    /// - Parameters:
    ///   - apiPeerID: Peer id for HTTP (`me` for Notes).
    ///   - storePeerID: Key in `threads` (sentinel for Notes).
    ///
    /// Opening a chat decodes only the newest page; `startOlderPrefetch` and the chat's scroll
    /// position bring in the rest (`loadOlderMessages`). A refresh walks back from the newest
    /// page only until it meets a message already held, so a poll costs one small page.
    /// Media bytes are **not** fetched here — bubbles call `ensureImageLoaded` /
    /// `ensureVoiceLoaded` when they appear.
    private func performThreadLoad(apiPeerID: UUID, storePeerID: UUID) async {
        guard let token = sessionController?.bearerToken,
              let me = sessionController?.userID,
              let material = cryptoController?.material
        else { return }

        let isNotes = isNotesChat(storePeerID)
        let known = Set((threads[storePeerID] ?? []).lazy.filter { !$0.pendingSync }.map(\.id))

        do {
            var before: HistoryCursor?
            var fetched = 0
            var pendingDeliveryIDs: [UUID] = []
            var newestReactionSnapshot: Int64?

            while true {
                let limit = fetched == 0 ? Self.firstPageSize : Self.historyPageSize
                let page = try await fetchHistoryPage(
                    apiPeerID: apiPeerID,
                    storePeerID: storePeerID,
                    before: before,
                    limit: limit,
                    token: token,
                    me: me,
                    material: material
                )
                if fetched == 0 { newestReactionSnapshot = page.reactionSnapshot }
                fetched += limit
                // Publish each page as it lands so the newest messages show at once.
                publishHistoryPage(page, storePeerID: storePeerID)
                pendingDeliveryIDs += page.inboundIDs.filter { !known.contains($0) }

                guard let older = page.older else {
                    olderHistoryExhausted.insert(storePeerID)
                    break
                }
                // A first open (or one after a clear) stops at the newest page and leaves the
                // rest to older paging; a refresh stops where it meets what we already hold
                // (a long absence may take a few pages).
                if known.isEmpty { olderHistoryExhausted.remove(storePeerID) }
                if known.isEmpty || !page.serverIDs.isDisjoint(with: known)
                    || fetched >= Self.historyMaxMessages
                {
                    break
                }
                before = older
            }

            // Delivery acks after the visible thread is populated (don't stall first paint).
            if !isNotes {
                for id in pendingDeliveryIDs {
                    try? await messagesService.markDelivered(messageID: id, token: token)
                }
                let finalThread = threads[storePeerID] ?? []
                if let lastFromPeer = finalThread.last(where: { !$0.isMine }) {
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
            if let newestReactionSnapshot {
                await catchUpReactions(
                    apiPeerID: apiPeerID,
                    storePeerID: storePeerID,
                    newestSnapshot: newestReactionSnapshot,
                    token: token
                )
            }
            if activePeerID == storePeerID, hasPendingUnseenReactions(storePeerID) {
                markReactionsSeen(peerUserID: storePeerID)
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

    /// Whether the server may still hold messages older than the thread's oldest.
    func hasOlderHistory(for peerUserID: UUID) -> Bool {
        !olderHistoryExhausted.contains(peerUserID)
    }

    /// Whether an older page is being fetched for this chat right now.
    func isLoadingOlderHistory(for peerUserID: UUID) -> Bool {
        loadingOlderPeerIDs.contains(peerUserID)
    }

    /// Fetches the page just older than the thread's oldest message and merges it in.
    /// One in flight per peer; the background prefetch and the reader's scrolling share it.
    func loadOlderMessages(peerUserID: UUID) async {
        guard !olderHistoryExhausted.contains(peerUserID) else { return }
        if let running = olderLoadTasks[peerUserID] {
            await running.value
            return
        }
        let task = Task { [weak self] in
            guard let self else { return }
            await self.performOlderLoad(storePeerID: peerUserID)
        }
        olderLoadTasks[peerUserID] = task
        loadingOlderPeerIDs.insert(peerUserID)
        await task.value
        if olderLoadTasks[peerUserID] == task {
            olderLoadTasks[peerUserID] = nil
            loadingOlderPeerIDs.remove(peerUserID)
        }
    }

    private func performOlderLoad(storePeerID: UUID) async {
        // The newest page decides where older paging starts; let it land first.
        if let initial = threadLoadTasks[storePeerID] { await initial.value }
        guard let token = sessionController?.bearerToken,
              let me = sessionController?.userID,
              let material = cryptoController?.material,
              let thread = threads[storePeerID],
              let oldest = thread.first(where: { !$0.pendingSync })
        else { return }
        let apiPeer = isNotesChat(storePeerID) ? me : storePeerID
        let known = Set(thread.lazy.map(\.id))

        do {
            let page = try await fetchHistoryPage(
                apiPeerID: apiPeer,
                storePeerID: storePeerID,
                before: HistoryCursor(createdAt: oldest.createdAt, id: oldest.id),
                limit: Self.historyPageSize,
                token: token,
                me: me,
                material: material
            )
            guard !Task.isCancelled, threads[storePeerID] != nil else { return } // locked meanwhile
            publishHistoryPage(page, storePeerID: storePeerID)
            if page.older == nil || (threads[storePeerID]?.count ?? 0) >= Self.historyMaxMessages {
                olderHistoryExhausted.insert(storePeerID)
            }
            if !isNotesChat(storePeerID) {
                for id in page.inboundIDs where !known.contains(id) {
                    try? await messagesService.markDelivered(messageID: id, token: token)
                }
            }
            persistThread(storePeerID)
        } catch {
            // The cursor is the thread itself: the next scroll to the top tries again.
        }
    }

    /// Walks a few older pages in behind the newest one while the chat stays open, one at a
    /// time with a pause between them. Past `olderPrefetchTarget` it waits for the reader.
    private func startOlderPrefetch(_ peerID: UUID) {
        guard olderPrefetchTasks[peerID] == nil else { return }
        olderPrefetchTasks[peerID] = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: Self.olderPrefetchPause)
                guard let self, !Task.isCancelled,
                      self.activePeerID == peerID,
                      self.hasOlderHistory(for: peerID)
                else { break }
                let before = self.threads[peerID]?.count ?? 0
                guard before < Self.olderPrefetchTarget else { break }
                await self.loadOlderMessages(peerUserID: peerID)
                // Offline or nothing new: stop rather than spin; scrolling retries.
                if (self.threads[peerID]?.count ?? 0) <= before { break }
            }
            self?.olderPrefetchTasks[peerID] = nil
        }
    }

    /// Stops all history paging and forgets what was learned (threads are being dropped).
    private func cancelHistoryPaging() {
        olderLoadTasks.values.forEach { $0.cancel() }
        olderLoadTasks.removeAll()
        olderPrefetchTasks.values.forEach { $0.cancel() }
        olderPrefetchTasks.removeAll()
        olderHistoryExhausted.removeAll()
        loadingOlderPeerIDs.removeAll()
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
                todoDone: parsed.done,
                replyTo: message.replyTo
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
                previewData: message.previewData,
                mediaByteCount: message.mediaByteCount,
                voiceData: message.voiceData,
                videoData: message.videoData,
                voiceDurationMs: message.voiceDurationMs,
                voiceWaveform: message.voiceWaveform,
                transcript: message.transcript,
                replyTo: message.replyTo,
                linkPreview: message.linkPreview
            )
        }
        return copy
    }

    /// Sends a text message, optionally quoting an earlier one and carrying a link preview.
    ///
    /// Human: A preview with a large image goes out as a media message whose blob is that image
    /// (the envelope is far too small for it). If that upload fails, or we are offline, the
    /// message still goes — as text with the small inline thumbnail — rather than being held
    /// back by a website's picture.
    /// Agent: `replyTo` and `linkPreview` are sealed into the plaintext
    /// (`MessageTextPayload.wire` / `MediaMessagePayload.lp`); the request body gains nothing
    /// the server can read. The preview was fetched by `LinkPreviewFetcher` on this device.
    func sendText(
        _ text: String,
        to peerUserID: UUID,
        replyTo: MessageReplyReference? = nil,
        linkPreview: LinkPreviewAttachment? = nil
    ) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        if isNotesChat(peerUserID) {
            // Notes sync as text, so their preview is always the inline (small) one.
            await appendAndSyncNote(
                text: trimmed,
                kind: .text,
                todoDone: nil,
                replyTo: replyTo,
                linkPreview: linkPreview?.preview
            )
            return
        }

        guard let token = sessionController?.bearerToken,
              let me = sessionController?.userID,
              let material = cryptoController?.material
        else { return }

        // The large layout needs an upload; offline, fall straight back to the inline thumbnail.
        let largeImage = connectivity.isOnline ? linkPreview?.largeImage : nil
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
            imageWidth: largeImage == nil ? nil : linkPreview?.largeImageWidth,
            imageHeight: largeImage == nil ? nil : linkPreview?.largeImageHeight,
            imageData: largeImage,
            pendingSync: true,
            replyTo: replyTo,
            linkPreview: linkPreview?.preview
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

        if let linkPreview, largeImage != nil {
            do {
                try await deliverLinkWithImage(
                    messageID: optimisticID,
                    text: trimmed,
                    attachment: linkPreview,
                    peerUserID: peerUserID,
                    me: me,
                    material: material,
                    token: token,
                    replyTo: replyTo
                )
                await refreshConversations(force: true)
                lastError = nil
                return
            } catch {
                // Keep the message, lose the big picture: the text send below carries the
                // small thumbnail instead.
                dropLargeLinkImage(messageID: optimisticID, peerUserID: peerUserID)
            }
        }

        do {
            try await deliverPendingText(
                messageID: optimisticID,
                text: trimmed,
                peerUserID: peerUserID,
                me: me,
                material: material,
                token: token,
                replyTo: replyTo,
                linkPreview: linkPreview?.preview
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

    /// Sends a text message whose link preview has a large image: the image is encrypted and
    /// uploaded like a photo, and the message goes out as a `t: "link"` media message.
    ///
    /// Human: To the server this is indistinguishable from a photo with a caption. The recipient
    /// downloads the blob from Shroud's server — never from the website.
    /// Agent: CALLS MediaCrypto.sealFile (fresh AES key, sealed into the payload), uploads the
    /// ciphertext, seals `MediaMessagePayload(t: link, c: text, lp: preview)`. Re-keys the
    /// optimistic bubble to `dto.id` (see `server-rekeys-sent-messages`).
    @discardableResult
    private func deliverLinkWithImage(
        messageID: UUID,
        text: String,
        attachment: LinkPreviewAttachment,
        peerUserID: UUID,
        me: UUID,
        material: IdentityKeyMaterial,
        token: String,
        replyTo: MessageReplyReference?
    ) async throws -> ChatMessage {
        guard let image = attachment.largeImage else { throw MediaCrypto.MediaError.imageEncodeFailed }
        let width = attachment.largeImageWidth ?? MediaCrypto.pixelSize(for: image)?.width ?? 0
        let height = attachment.largeImageHeight ?? MediaCrypto.pixelSize(for: image)?.height ?? 0
        let (fileKey, sealedFile) = try MediaCrypto.sealFile(image)
        let upload = try await mediaService.createUpload(
            sizeBytes: sealedFile.count,
            contentType: "application/octet-stream",
            token: token
        )
        try await mediaService.uploadContent(mediaID: upload.mediaObjectId, data: sealedFile, token: token)

        // The blob is the big picture; the payload keeps only a tiny blurred placeholder.
        let preview = attachment.preview.withoutThumbnail()
        let placeholder = MediaCrypto.chatPreviewJPEG(from: image)
        let peerPub = try await peerIdentityForSending(peerUserID: peerUserID, token: token)
        let (payloadData, sealed, usedPlaceholder) = try Self.sealMediaPayload(
            kind: MediaMessagePayload.kindLink,
            mime: "image/jpeg",
            width: width,
            height: height,
            fileKey: fileKey,
            caption: text,
            durationMs: nil,
            previewJPEG: placeholder,
            mediaByteCount: image.count,
            peerUserID: peerUserID,
            peerPub: peerPub,
            material: material,
            me: me,
            replyTo: replyTo,
            linkPreview: preview
        )
        let dto = try await messagesService.send(
            SendMessageRequest(
                peerUserId: peerUserID,
                clientMessageId: messageID,
                contentType: "media",
                ciphertext: sealed.base64EncodedString(),
                mediaObjectId: upload.mediaObjectId
            ),
            token: token
        )
        local.saveSealedMedia(messageID: dto.id, data: image)
        if dto.id != messageID {
            local.removeCaches(messageIDs: [messageID])
        }
        // The payload holds the blob key — keep it, not just the text, so reloads can decode.
        local.saveSealedPlaintext(messageID: dto.id, data: payloadData)

        let sent = ChatMessage(
            id: dto.id,
            peerUserID: peerUserID,
            senderUserID: me,
            text: text,
            createdAt: dto.createdAt,
            isMine: true,
            deleted: false,
            receipt: receiptStatus(from: dto),
            mediaObjectId: upload.mediaObjectId,
            imageWidth: width,
            imageHeight: height,
            imageData: image,
            previewData: usedPlaceholder,
            mediaByteCount: image.count,
            replyTo: replyTo,
            linkPreview: preview
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

    /// Switches an unsent link bubble to the small layout (its inline thumbnail).
    private func dropLargeLinkImage(messageID: UUID, peerUserID: UUID) {
        guard var list = threads[peerUserID],
              let idx = list.firstIndex(where: { $0.id == messageID })
        else { return }
        list[idx].imageData = nil
        list[idx].imageWidth = nil
        list[idx].imageHeight = nil
        list[idx].mediaObjectId = nil
        threads[peerUserID] = list
        persistSnapshot()
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

    /// Deletes a local Notes message and **all** related local artifacts (media, plaintext,
    /// decode cache, in-flight transfer). Nothing may remain on disk for that note.
    func deleteLocalNote(messageID: UUID) {
        let peer = Self.notesPeerID
        guard let list = threads[peer] else {
            // Thread already empty — still scrub caches in case of a half-deleted media note.
            purgeLocalMessageArtifacts(messageIDs: [messageID])
            return
        }
        let result = NotesLocal.delete(messageID: messageID, in: list)
        // Always purge artifacts even if the row was already gone (stale media after re-key).
        purgeLocalMessageArtifacts(messageIDs: [messageID])
        guard result.removed else {
            persistThread(peer)
            return
        }
        threads[peer] = result.messages
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

    /// Deletes a note locally and hard-deletes it on the server (including media blobs).
    ///
    /// Notes are Saved Messages (`peer_user_id = self`). The server permanently removes the
    /// row and linked media so multi-device sync cannot resurrect it. Locally we purge sealed
    /// media, plaintext, decode cache, and transfer state — no bytes may remain.
    /// A note that never reached the server answers 404, which is nothing left to delete.
    private func deleteNote(_ message: ChatMessage) async -> String? {
        // Capture ids before local removal so we scrub every alias we know about.
        let idsToPurge = [message.id]
        if let token = sessionController?.bearerToken, connectivity.isOnline {
            do {
                try await messagesService.delete(messageID: message.id, scope: .me, token: token)
            } catch let APIError.server(_, _, statusCode) where statusCode == 404 {
                // Local-only note (written offline, or never mirrored).
            } catch {
                let text = SessionController.userMessage(for: error)
                lastError = text
                return text
            }
        }
        // Drop from the notes thread + self-API thread if a half-send parked a copy there.
        var peers: [UUID] = [Self.notesPeerID]
        if let me = sessionController?.userID, me != Self.notesPeerID {
            peers.append(me)
        }
        for peer in peers {
            guard var list = threads[peer], list.contains(where: { $0.id == message.id }) else {
                continue
            }
            list.removeAll { $0.id == message.id }
            if peer == Self.notesPeerID {
                threads[peer] = list
            } else {
                // Stray under real user id must not leave an empty ghost thread.
                threads[peer] = list.isEmpty ? nil : list
            }
        }
        purgeLocalMessageArtifacts(messageIDs: idsToPurge)
        persistThread(Self.notesPeerID)
        if let me = sessionController?.userID, me != Self.notesPeerID, threads[me] != nil {
            persistThread(me)
        }
        // Clear list preview residue for Notes.
        await refreshConversations(force: true)
        return nil
    }

    /// Drops a message from its thread and purges its cached plaintext / media bytes.
    private func removeMessageLocally(messageID: UUID, peerUserID: UUID) {
        guard var list = threads[peerUserID] else {
            purgeLocalMessageArtifacts(messageIDs: [messageID])
            return
        }
        let before = list.count
        list.removeAll { $0.id == messageID }
        guard list.count != before else {
            purgeLocalMessageArtifacts(messageIDs: [messageID])
            return
        }
        threads[peerUserID] = list
        purgeLocalMessageArtifacts(messageIDs: [messageID])
        persistThread(peerUserID)
    }

    /// Scrubs every on-device artifact for the given message ids.
    ///
    /// Sealed media files, sealed plaintext (payload keys), decode cache bitmaps, hydrate
    /// tasks, and transfer progress rings — so delete leaves nothing recoverable.
    private func purgeLocalMessageArtifacts(messageIDs: [UUID]) {
        guard !messageIDs.isEmpty else { return }
        local.removeCaches(messageIDs: messageIDs)
        DecodedImageCache.remove(ids: messageIDs)
        LinkPreviewImageCache.remove(ids: messageIDs)
        for id in messageIDs {
            mediaHydrateTasks[id]?.cancel()
            mediaHydrateTasks[id] = nil
            endTransfer(id)
        }
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
            kind: (old.kind == .image || old.kind == .video || old.kind == .voice) ? old.kind : .text
        )
        threads[peerUserID] = list
        // Media + payload keys must not survive an unsend.
        purgeLocalMessageArtifacts(messageIDs: [messageID])
        persistThread(peerUserID)
    }

    /// Which thread holds a message id (delete events carry no peer id).
    private func peerID(forMessage messageID: UUID) -> UUID? {
        threads.first { $0.value.contains { $0.id == messageID } }?.key
    }

    // MARK: - Whole-chat delete

    /// What a chat delete actually did, so the UI can say so honestly.
    enum ChatDeleteOutcome: Equatable, Sendable {
        /// `.me` scope, or a chat that only ever existed on this device.
        case clearedForMe
        /// `.everyone` and the peer allowed it — the chat is gone on both sides.
        case clearedForBoth
        /// `.everyone` but the peer withheld consent: our messages became "Message deleted"
        /// for them, and their own messages stay in their copy of the chat.
        case unsentForPeer
        case failed(String)
    }

    /// Deletes an entire chat here and on the server.
    ///
    /// Human: `.everyone` always drops the contact link, whatever the peer allowed — that is
    /// what makes the next conversation a genuinely new one instead of a continuation.
    /// Saved Messages have no second party, so they only accept `.me`.
    /// Agent: CALLS MessagesService.deleteConversation; WRITES threads/conversations/contacts;
    /// purges the sealed plaintext + media caches of every message it drops.
    func deleteConversation(
        peerUserID: UUID,
        scope: ConversationDeleteScope
    ) async -> ChatDeleteOutcome {
        let isNotes = isNotesChat(peerUserID)
        if isNotes, scope == .everyone {
            return .failed("Saved Messages can only be deleted for you.")
        }
        guard let token = sessionController?.bearerToken, let me = sessionController?.userID else {
            return .failed("Sign in to delete chats.")
        }

        // Notes are a self-conversation on the wire; every other chat is keyed by the peer.
        let apiPeer = isNotes ? me : peerUserID

        let response: DeleteConversationResponse
        do {
            response = try await messagesService.deleteConversation(
                peerUserID: apiPeer,
                scope: scope,
                token: token
            )
        } catch let APIError.server(_, _, statusCode) where statusCode == 404 {
            // Peer account is gone, or the chat never reached the server — clearing the
            // local copy is still the right outcome.
            clearChatLocally(peerUserID: peerUserID)
            return .clearedForMe
        } catch {
            let text = SessionController.userMessage(for: error)
            lastError = text
            return .failed(text)
        }

        clearChatLocally(peerUserID: peerUserID)
        if scope == .everyone {
            // The server dropped the edge both ways; mirror it so Contacts doesn't flash
            // the stale row until the refresh lands.
            contacts.removeAll { $0.userId == peerUserID }
        }
        lastError = nil

        await refreshConversations(force: true)
        if scope == .everyone {
            await refreshContacts(force: true)
        }

        switch scope {
        case .me:
            return .clearedForMe
        case .everyone:
            return response.clearedForPeer ? .clearedForBoth : .unsentForPeer
        }
    }

    /// Drops a whole thread from memory, disk, and the chat list, purging its caches.
    ///
    /// Agent: WRITES threads/conversations/unreadCountByPeer; CALLS local.removeCaches +
    /// persistThread. Notes keep their list row (it is a fixture, not a server conversation).
    private func clearChatLocally(peerUserID: UUID) {
        threadLoadTasks[peerUserID]?.cancel()
        threadLoadTasks[peerUserID] = nil

        let messageIDs = (threads[peerUserID] ?? []).map(\.id)
        threads[peerUserID] = []
        if !messageIDs.isEmpty {
            purgeLocalMessageArtifacts(messageIDs: messageIDs)
        }
        // Notes may also have been mirrored under the real user id during send.
        if isNotesChat(peerUserID), let me = sessionController?.userID, me != peerUserID {
            let stray = (threads[me] ?? []).map(\.id)
            threads[me] = nil
            if !stray.isEmpty {
                purgeLocalMessageArtifacts(messageIDs: stray)
            }
            persistThread(me)
        }
        if unreadCountByPeer[peerUserID] != nil {
            unreadCountByPeer[peerUserID] = nil
        }
        if !isNotesChat(peerUserID) {
            conversations.removeAll { $0.peer.id == peerUserID }
            setPeerTyping(peerUserID, false)
        }
        persistThread(peerUserID)
    }

    // MARK: - Privacy consent

    /// Loads the account's chat-delete consent flag. Silent on failure: an unreachable
    /// server must not flip a consent switch, so the last known value stands.
    func refreshPrivacySettings() async {
        guard let token = sessionController?.bearerToken else { return }
        guard let settings = try? await privacyService.settings(token: token) else { return }
        if allowsPeerChatDelete != settings.allowPeerChatDelete {
            allowsPeerChatDelete = settings.allowPeerChatDelete
        }
        if !hasLoadedPrivacySettings { hasLoadedPrivacySettings = true }
    }

    /// Writes the consent flag. Returns a user-facing error, or nil on success; the local
    /// value only moves once the server confirms, so the toggle can never lie.
    func setAllowsPeerChatDelete(_ value: Bool) async -> String? {
        guard let token = sessionController?.bearerToken else {
            return "Sign in to change privacy settings."
        }
        do {
            let settings = try await privacyService.update(
                allowPeerChatDelete: value,
                token: token
            )
            allowsPeerChatDelete = settings.allowPeerChatDelete
            hasLoadedPrivacySettings = true
            lastError = nil
            return nil
        } catch {
            let text = SessionController.userMessage(for: error)
            lastError = text
            return text
        }
    }

    // MARK: - Blocking

    func refreshBlocks() async {
        guard let token = sessionController?.bearerToken else { return }
        guard let list = try? await blocksService.list(token: token) else { return }
        if blockedUsers != list { blockedUsers = list }
    }

    /// Blocks a user. Returns a user-facing error, or nil on success.
    ///
    /// Human: Deleting a chat for both only unlinks the accounts — either side can send a new
    /// contact request afterwards. Blocking is what stops that, so it stays a separate,
    /// explicit action rather than a side effect of deleting.
    /// Agent: CALLS BlocksService.block (server also drops contacts + cancels requests);
    /// WRITES contacts/blockedUsers; REFRESHES contacts and chats.
    func blockUser(_ userID: UUID, username: String) async -> String? {
        guard let token = sessionController?.bearerToken else {
            return "Sign in to block contacts."
        }
        do {
            try await blocksService.block(userID: userID, token: token)
        } catch {
            let text = SessionController.userMessage(for: error)
            lastError = text
            return text
        }
        contacts.removeAll { $0.userId == userID }
        setPeerTyping(userID, false)
        lastError = nil
        await refreshBlocks()
        await refreshContacts(force: true)
        await refreshConversations(force: true)
        return nil
    }

    /// Lifts a block. Contacts are **not** restored — the pair has to reconnect.
    func unblockUser(_ userID: UUID) async -> String? {
        guard let token = sessionController?.bearerToken else {
            return "Sign in to manage blocked contacts."
        }
        do {
            try await blocksService.unblock(userID: userID, token: token)
        } catch {
            let text = SessionController.userMessage(for: error)
            lastError = text
            return text
        }
        blockedUsers.removeAll { $0.userId == userID }
        lastError = nil
        await refreshBlocks()
        return nil
    }

    // MARK: - Typing / recording
    //
    // Human: The server relays `typing` and `recording` frames to the peer's devices only
    // (routes/ws.rs). These timings are the contract with the web client (`web/src/typing.ts`)
    // — change them together:
    // a sender says `true` when typing starts and again at most every `typingKeepalive` while it
    // goes on, then `false` after `typingIdle` without a keystroke, on send, and on leaving the
    // chat. Recording uses the same keepalive and receiver expiry, but has no idle timeout: it
    // stays on until the sender stops, sends, or leaves. A receiver drops the indicator after
    // `typingExpiry` without a fresh `true`, so a sender that vanishes cannot leave it stuck; a
    // message from that peer clears it at once.

    static let typingKeepalive: TimeInterval = 3
    static let typingIdle: TimeInterval = 3
    static let typingExpiry: TimeInterval = 6

    /// Recording takes precedence over typing when both somehow overlap.
    func peerActivity(for userID: UUID) -> ChatPeerActivity? {
        if recordingPeerIDs.contains(userID) { return .recording }
        if typingPeerIDs.contains(userID) { return .typing }
        return nil
    }

    /// Reports composer activity for `peerUserID`: `isTyping` is false once the draft is empty.
    func setTyping(peerUserID: UUID, isTyping: Bool) {
        if isTyping { stopRecording() }
        if let sentTo = typingSentTo, sentTo != peerUserID || !isTyping {
            stopTyping()
        }
        guard isTyping else { return }
        typingSentTo = peerUserID
        if typingSentAt.map({ Date().timeIntervalSince($0) >= Self.typingKeepalive }) ?? true {
            realtime.sendTyping(peerUserID: peerUserID, isTyping: true)
            typingSentAt = Date()
        }
        typingIdleTask?.cancel()
        typingIdleTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.typingIdle))
            guard !Task.isCancelled else { return }
            self?.stopTyping()
        }
    }

    /// Reports a live voice-note take for `peerUserID`.
    func setRecording(peerUserID: UUID, isRecording: Bool) {
        if !isRecording {
            if recordingSentTo == peerUserID { stopRecording() }
            return
        }
        if let sentTo = recordingSentTo, sentTo != peerUserID {
            stopRecording()
        }
        stopTyping()
        let already = recordingSentTo == peerUserID
        recordingSentTo = peerUserID
        if !already || recordingSentAt.map({ Date().timeIntervalSince($0) >= Self.typingKeepalive }) ?? true {
            realtime.sendRecording(peerUserID: peerUserID, isRecording: true)
            recordingSentAt = Date()
        }
        recordingKeepaliveTask?.cancel()
        recordingKeepaliveTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(Self.typingKeepalive))
                guard !Task.isCancelled else { return }
                guard let self, let peer = self.recordingSentTo else { return }
                self.realtime.sendRecording(peerUserID: peer, isRecording: true)
                self.recordingSentAt = Date()
            }
        }
    }

    /// Says `false` if we last said `true`.
    private func stopTyping() {
        typingIdleTask?.cancel()
        typingIdleTask = nil
        if let peer = typingSentTo {
            realtime.sendTyping(peerUserID: peer, isTyping: false)
        }
        typingSentTo = nil
        typingSentAt = nil
    }

    private func stopRecording() {
        recordingKeepaliveTask?.cancel()
        recordingKeepaliveTask = nil
        if let peer = recordingSentTo {
            realtime.sendRecording(peerUserID: peer, isRecording: false)
        }
        recordingSentTo = nil
        recordingSentAt = nil
    }

    /// A peer started or stopped typing to us; "started" lapses on its own unless refreshed.
    private func setPeerTyping(_ userID: UUID, _ isTyping: Bool) {
        if isTyping, recordingPeerIDs.contains(userID) { setPeerRecording(userID, false) }
        typingExpiryTasks.removeValue(forKey: userID)?.cancel()
        if isTyping {
            if !typingPeerIDs.contains(userID) { typingPeerIDs.insert(userID) }
            typingExpiryTasks[userID] = Task { [weak self] in
                try? await Task.sleep(for: .seconds(Self.typingExpiry))
                guard !Task.isCancelled else { return }
                self?.setPeerTyping(userID, false)
            }
        } else if typingPeerIDs.contains(userID) {
            typingPeerIDs.remove(userID)
        }
    }

    /// A peer started or stopped recording a voice note to us.
    private func setPeerRecording(_ userID: UUID, _ isRecording: Bool) {
        if isRecording, typingPeerIDs.contains(userID) { setPeerTyping(userID, false) }
        recordingExpiryTasks.removeValue(forKey: userID)?.cancel()
        if isRecording {
            if !recordingPeerIDs.contains(userID) { recordingPeerIDs.insert(userID) }
            recordingExpiryTasks[userID] = Task { [weak self] in
                try? await Task.sleep(for: .seconds(Self.typingExpiry))
                guard !Task.isCancelled else { return }
                self?.setPeerRecording(userID, false)
            }
        } else if recordingPeerIDs.contains(userID) {
            recordingPeerIDs.remove(userID)
        }
    }

    private func clearAllTyping() {
        stopTyping()
        stopRecording()
        typingExpiryTasks.values.forEach { $0.cancel() }
        typingExpiryTasks.removeAll()
        typingPeerIDs = []
        recordingExpiryTasks.values.forEach { $0.cancel() }
        recordingExpiryTasks.removeAll()
        recordingPeerIDs = []
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
        edits: MediaEdits = MediaEdits(),
        replyTo: MessageReplyReference? = nil
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
                imageData: encoded.data,
                replyTo: replyTo
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
                    // Re-key the Notes bubble to the server's id. `finishImageSend` can only
                    // swap it in place under the API peer, and this photo lives under the
                    // Notes sentinel — so without this the note keeps its client id and the
                    // next reload shows the server copy *next to* it as a duplicate.
                    let sent = try await finishImageSend(
                        optimisticID: optimisticID,
                        peerUserID: realMe,
                        me: realMe,
                        material: material,
                        token: token,
                        encoded: encoded,
                        caption: trimmedCaption,
                        replyTo: replyTo
                    )
                    if var notes = threads[peerUserID] {
                        notes.removeAll { $0.id == optimisticID || $0.id == sent.id }
                        notes.append(
                            ChatMessage(
                                id: sent.id,
                                peerUserID: peerUserID,
                                senderUserID: realMe,
                                text: sent.text,
                                createdAt: sent.createdAt,
                                isMine: true,
                                deleted: false,
                                receipt: .sent,
                                kind: .image,
                                mediaObjectId: sent.mediaObjectId,
                                imageWidth: sent.imageWidth,
                                imageHeight: sent.imageHeight,
                                imageData: sent.imageData ?? encoded.data,
                                replyTo: replyTo
                            )
                        )
                        notes.sort { $0.createdAt < $1.createdAt }
                        threads[peerUserID] = notes
                        // finishImageSend may have parked a copy under the API peer; Notes owns
                        // this bubble, so that thread must not linger as a second chat.
                        threads[realMe] = nil
                        persistThread(peerUserID)
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
            pendingSync: true,
            replyTo: replyTo
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
                caption: trimmedCaption,
                replyTo: replyTo
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

    /// Encrypts, uploads, and sends a video message (compressed to fit the media size cap).
    /// Optional `caption` is sealed in the media payload.
    /// Returns a user-facing error string, or `nil` on success.
    /// Sends one composed video: the bubble lands first, then compress → upload → envelope.
    ///
    /// Human: Compression alone can take several seconds on a long 4K clip. Blocking the whole
    /// chat behind a modal spinner for that is exactly what Telegram doesn't do — the bubble
    /// appears immediately with its poster and fills a progress ring in place.
    func sendVideo(
        _ plan: VideoSendPlan,
        to peerUserID: UUID,
        replyTo: MessageReplyReference? = nil
    ) async -> String? {
        let trimmedCaption = plan.caption.trimmingCharacters(in: .whitespacesAndNewlines)
        let displayText = trimmedCaption.isEmpty ? "Video" : trimmedCaption
        let notes = isNotesChat(peerUserID)

        guard let me = sessionController?.userID ?? (notes ? Self.notesPeerID : nil) else {
            return "Not signed in."
        }
        if !notes, sessionController?.bearerToken == nil || cryptoController?.material == nil {
            return "Not signed in."
        }

        let optimisticID = UUID()
        var list = threads[peerUserID] ?? []
        list.append(
            ChatMessage(
                id: optimisticID,
                peerUserID: peerUserID,
                senderUserID: me,
                text: displayText,
                createdAt: Date(),
                isMine: true,
                deleted: false,
                receipt: .sending,
                kind: .video,
                imageWidth: plan.width,
                imageHeight: plan.height,
                imageData: plan.posterJPEG,
                previewData: plan.posterJPEG,
                mediaByteCount: plan.estimatedBytes,
                voiceDurationMs: plan.durationMs,
                pendingSync: true,
                replyTo: replyTo
            )
        )
        threads[peerUserID] = list
        beginTransfer(
            optimisticID,
            isUpload: true,
            phase: .preparing,
            totalBytes: plan.estimatedBytes
        )

        let encoded: EncodedVideo
        do {
            let onProgress = progressSink(for: optimisticID)
            encoded = try await Task.detached(priority: .userInitiated) {
                try await VideoMedia.encode(
                    sourceURL: plan.sourceURL,
                    trim: plan.trim,
                    removeAudio: plan.removeAudio,
                    onProgress: onProgress
                )
            }.value
        } catch VideoMedia.VideoError.tooLarge {
            let message = "This video is too large even after compression. Try a shorter clip."
            markVideoFailed(optimisticID: optimisticID, peerUserID: peerUserID, error: message)
            return message
        } catch {
            let message = "Could not prepare that video."
            markVideoFailed(optimisticID: optimisticID, peerUserID: peerUserID, error: message)
            return message
        }

        // The bubble now has real geometry, duration and poster — no more guessing from the plan.
        applyEncodedVideo(optimisticID: optimisticID, peerUserID: peerUserID, encoded: encoded)
        local.saveSealedMedia(messageID: optimisticID, data: encoded.data)

        if notes {
            if var thread = threads[peerUserID],
               let idx = thread.firstIndex(where: { $0.id == optimisticID })
            {
                thread[idx].receipt = .sent
                thread[idx].pendingSync = false
                threads[peerUserID] = thread
            }
            persistThread(peerUserID)

            if connectivity.isOnline,
               let token = sessionController?.bearerToken,
               let realMe = sessionController?.userID,
               let material = cryptoController?.material
            {
                do {
                    let sent = try await finishVideoSend(
                        optimisticID: optimisticID,
                        peerUserID: realMe,
                        me: realMe,
                        material: material,
                        token: token,
                        encoded: encoded,
                        caption: trimmedCaption,
                        replyTo: replyTo
                    )
                    if var notes = threads[peerUserID] {
                        notes.removeAll { $0.id == optimisticID || $0.id == sent.id }
                        notes.append(
                            ChatMessage(
                                id: sent.id,
                                peerUserID: peerUserID,
                                senderUserID: realMe,
                                text: sent.text,
                                createdAt: sent.createdAt,
                                isMine: true,
                                deleted: false,
                                receipt: .sent,
                                kind: .video,
                                mediaObjectId: sent.mediaObjectId,
                                imageWidth: sent.imageWidth,
                                imageHeight: sent.imageHeight,
                                imageData: sent.imageData ?? encoded.thumbnailJPEG,
                                videoData: sent.videoData ?? encoded.data,
                                voiceDurationMs: sent.voiceDurationMs ?? encoded.durationMs,
                                replyTo: replyTo
                            )
                        )
                        notes.sort { $0.createdAt < $1.createdAt }
                        threads[peerUserID] = notes
                        threads[realMe] = nil
                        persistThread(peerUserID)
                    }
                } catch {
                    // Keep local-only video.
                }
            }
            endTransfer(optimisticID)
            return nil
        }

        guard let token = sessionController?.bearerToken,
              let material = cryptoController?.material
        else {
            markVideoFailed(optimisticID: optimisticID, peerUserID: peerUserID, error: "Not signed in.")
            return "Not signed in."
        }

        persistSnapshot()

        if !connectivity.isOnline {
            isOffline = true
            markVideoFailed(optimisticID: optimisticID, peerUserID: peerUserID, error: "Waiting for connection…")
            return nil
        }

        do {
            try await finishVideoSend(
                optimisticID: optimisticID,
                peerUserID: peerUserID,
                me: me,
                material: material,
                token: token,
                encoded: encoded,
                caption: trimmedCaption,
                trackingTransfer: true,
                replyTo: replyTo
            )
            endTransfer(optimisticID)
            lastError = nil
            return nil
        } catch {
            let message = SessionController.userMessage(for: error)
            markVideoFailed(optimisticID: optimisticID, peerUserID: peerUserID, error: message)
            lastError = message
            persistSnapshot()
            return message
        }
    }

    /// Folds a finished encode into the optimistic bubble (poster, geometry, bytes).
    private func applyEncodedVideo(
        optimisticID: UUID,
        peerUserID: UUID,
        encoded: EncodedVideo
    ) {
        guard var thread = threads[peerUserID],
              let idx = thread.firstIndex(where: { $0.id == optimisticID })
        else { return }
        thread[idx].imageWidth = encoded.width
        thread[idx].imageHeight = encoded.height
        thread[idx].voiceDurationMs = encoded.durationMs
        thread[idx].videoData = encoded.data
        thread[idx].mediaByteCount = encoded.data.count
        if let thumb = encoded.thumbnailJPEG {
            thread[idx].imageData = thumb
            thread[idx].previewData = thumb
        }
        threads[peerUserID] = thread
    }

    /// Retries a failed outbound video that still has local video data.
    func retryFailedVideo(messageID: UUID, peerUserID: UUID) async -> String? {
        guard let token = sessionController?.bearerToken,
              let me = sessionController?.userID,
              let material = cryptoController?.material,
              var thread = threads[peerUserID],
              let idx = thread.firstIndex(where: { $0.id == messageID && $0.isMine && $0.kind == .video })
        else {
            return "Nothing to retry."
        }

        let data = thread[idx].videoData ?? local.sealedMedia(for: messageID)
        guard let data else { return "Nothing to retry." }

        thread[idx].receipt = .sending
        thread[idx].sendError = nil
        threads[peerUserID] = thread

        let encoded = EncodedVideo(
            data: data,
            width: thread[idx].imageWidth ?? 0,
            height: thread[idx].imageHeight ?? 0,
            durationMs: thread[idx].voiceDurationMs ?? 0,
            mime: "video/mp4",
            thumbnailJPEG: thread[idx].imageData
        )

        let existingCaption = thread[idx].text
        let caption = (existingCaption == "Video" || existingCaption.isEmpty) ? "" : existingCaption
        // The bubble already carries its quote; a retry must re-seal the same one.
        let replyTo = thread[idx].replyTo

        beginTransfer(messageID, isUpload: true, phase: .transferring, totalBytes: data.count)
        do {
            try await finishVideoSend(
                optimisticID: messageID,
                peerUserID: peerUserID,
                me: me,
                material: material,
                token: token,
                encoded: encoded,
                caption: caption,
                trackingTransfer: true,
                replyTo: replyTo
            )
            endTransfer(messageID)
            return nil
        } catch {
            let message = SessionController.userMessage(for: error)
            markVideoFailed(optimisticID: messageID, peerUserID: peerUserID, error: message)
            return message
        }
    }

    @discardableResult
    private func finishVideoSend(
        optimisticID: UUID,
        peerUserID: UUID,
        me: UUID,
        material: IdentityKeyMaterial,
        token: String,
        encoded: EncodedVideo,
        caption: String,
        trackingTransfer: Bool = false,
        replyTo: MessageReplyReference? = nil
    ) async throws -> ChatMessage {
        let (fileKey, sealedFile) = try MediaCrypto.sealFile(encoded.data)
        if trackingTransfer {
            advanceTransfer(optimisticID, to: .transferring, totalBytes: sealedFile.count)
        }
        let upload = try await mediaService.createUpload(
            sizeBytes: sealedFile.count,
            contentType: "application/octet-stream",
            token: token
        )
        try await mediaService.uploadContent(
            mediaID: upload.mediaObjectId,
            data: sealedFile,
            token: token,
            onProgress: trackingTransfer ? progressSink(for: optimisticID) : nil
        )
        // Envelope sealing + send still have to happen; the ring keeps spinning rather than
        // sitting at 100% while the message quietly finishes.
        if trackingTransfer {
            advanceTransfer(optimisticID, to: .finishing)
        }

        let trimmedCaption = caption.trimmingCharacters(in: .whitespacesAndNewlines)
        let displayText = trimmedCaption.isEmpty ? "Video" : trimmedCaption
        // Shrink the 720px encode poster into an envelope-safe thumb (server 64 KiB CT cap).
        let rawThumb: Data?
        if let thumb = encoded.thumbnailJPEG {
            rawThumb = thumb
        } else {
            rawThumb = await VideoMedia.thumbnailJPEG(from: encoded.data, maxEdge: 320)
        }
        let previewJPEG = rawThumb.flatMap { MediaCrypto.chatPreviewJPEG(from: $0) }
        let peerPub = try await peerIdentityForSending(peerUserID: peerUserID, token: token)
        let (payloadData, sealed, usedPreview) = try Self.sealMediaPayload(
            kind: MediaMessagePayload.kindVideo,
            mime: encoded.mime,
            width: encoded.width,
            height: encoded.height,
            fileKey: fileKey,
            caption: trimmedCaption.isEmpty ? nil : trimmedCaption,
            durationMs: encoded.durationMs,
            previewJPEG: previewJPEG,
            mediaByteCount: encoded.data.count,
            peerUserID: peerUserID,
            peerPub: peerPub,
            material: material,
            me: me,
            replyTo: replyTo
        )
        let dto = try await messagesService.send(
            SendMessageRequest(
                peerUserId: peerUserID,
                clientMessageId: optimisticID,
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
            kind: .video,
            mediaObjectId: upload.mediaObjectId,
            imageWidth: encoded.width,
            imageHeight: encoded.height,
            imageData: usedPreview,
            previewData: usedPreview,
            mediaByteCount: encoded.data.count,
            videoData: encoded.data,
            voiceDurationMs: encoded.durationMs,
            sendError: nil,
            replyTo: replyTo
        )
        if var thread = threads[peerUserID],
           let idx = thread.firstIndex(where: { $0.id == optimisticID })
        {
            thread[idx] = sent
            threads[peerUserID] = thread
        }
        persistSnapshot()
        await refreshConversations(force: true)
        return sent
    }

    private func markVideoFailed(optimisticID: UUID, peerUserID: UUID, error: String) {
        endTransfer(optimisticID)
        guard var thread = threads[peerUserID],
              let idx = thread.firstIndex(where: { $0.id == optimisticID })
        else { return }
        thread[idx].receipt = .failed
        thread[idx].sendError = error
        thread[idx].pendingSync = true
        threads[peerUserID] = thread
        persistSnapshot()
    }

    // MARK: - Transfer progress

    /// Cancels an in-flight media download (the ring's X). Uploads are not cancellable —
    /// the envelope is already committed to by the time bytes move.
    func cancelMediaDownload(messageID: UUID) {
        guard let task = mediaHydrateTasks[messageID] else { return }
        task.cancel()
        mediaHydrateTasks[messageID] = nil
        endTransfer(messageID)
    }

    private func beginTransfer(
        _ messageID: UUID,
        isUpload: Bool,
        phase: MediaTransfer.Phase,
        totalBytes: Int? = nil
    ) {
        mediaTransfers[messageID] = MediaTransfer(
            phase: phase,
            isUpload: isUpload,
            fraction: nil,
            totalBytes: totalBytes
        )
    }

    private func advanceTransfer(
        _ messageID: UUID,
        to phase: MediaTransfer.Phase,
        totalBytes: Int? = nil
    ) {
        guard var transfer = mediaTransfers[messageID] else { return }
        transfer.phase = phase
        transfer.fraction = nil
        if let totalBytes { transfer.totalBytes = totalBytes }
        mediaTransfers[messageID] = transfer
    }

    private func updateTransfer(_ messageID: UUID, fraction: Double) {
        guard var transfer = mediaTransfers[messageID] else { return }
        transfer.fraction = min(1, max(0, fraction))
        mediaTransfers[messageID] = transfer
    }

    private func endTransfer(_ messageID: UUID) {
        mediaTransfers[messageID] = nil
    }

    /// Progress callback safe to hand to the detached encode/transport work.
    ///
    /// The transport calls this off the main actor, so it hops back before touching state.
    private func progressSink(for messageID: UUID) -> @Sendable (Double) -> Void {
        { [weak self] fraction in
            guard let controller = self else { return }
            Task { @MainActor in
                controller.updateTransfer(messageID, fraction: fraction)
            }
        }
    }

    /// Loads decrypted full video bytes for a media message (caches on success).
    /// Call only from an explicit download action — never on bubble appear.
    func ensureVideoLoaded(for message: ChatMessage) async {
        guard message.kind == .video,
              message.videoData == nil,
              !message.deleted
        else { return }

        if let existing = mediaHydrateTasks[message.id] {
            await existing.value
            return
        }
        let task = Task { [weak self] in
            guard let self else { return }
            await self.hydrateVideo(for: message)
        }
        mediaHydrateTasks[message.id] = task
        await task.value
        if mediaHydrateTasks[message.id] == task {
            mediaHydrateTasks[message.id] = nil
        }
    }

    private func hydrateVideo(for message: ChatMessage) async {
        guard message.kind == .video,
              message.videoData == nil,
              !message.deleted
        else { return }

        if let cached = local.sealedMedia(for: message.id) {
            updateMessageVideo(messageID: message.id, peerID: message.peerUserID, data: cached)
            return
        }

        guard let mediaID = message.mediaObjectId,
              let token = sessionController?.bearerToken,
              let material = cryptoController?.material
        else { return }

        beginTransfer(
            message.id,
            isUpload: false,
            phase: .transferring,
            totalBytes: message.mediaByteCount
        )
        defer { endTransfer(message.id) }

        do {
            guard let payloadData = try await mediaPayloadData(for: message, token: token, material: material),
                  let payload = MediaMessagePayload.parse(payloadData),
                  let keyData = Data(base64Encoded: payload.k)
            else {
                // Payload missing (e.g. race before first decrypt finished) — retry once via history.
                return
            }

            let sealedFile = try await mediaService.downloadContent(
                mediaID: mediaID,
                token: token,
                onProgress: progressSink(for: message.id)
            )
            try Task.checkCancellation()

            // Decrypt + poster are seconds of CPU on a 20 MB clip — the ring keeps spinning.
            advanceTransfer(message.id, to: .finishing)
            let video = try MediaCrypto.openFile(sealed: sealedFile, keyData: keyData)
            local.saveSealedMedia(messageID: message.id, data: video)

            // Poster frame for the bubble (image path uses jpeg bytes; video needs a still).
            let poster = await VideoMedia.thumbnailJPEG(from: video)

            updateMessageVideo(
                messageID: message.id,
                peerID: message.peerUserID,
                data: video,
                durationMs: payload.d,
                width: payload.w > 0 ? payload.w : nil,
                height: payload.h > 0 ? payload.h : nil,
                caption: payload.c,
                posterJPEG: poster
            )
        } catch {
            // Leave placeholder; reopen thread / tap to retry.
        }
    }

    private func updateMessageVideo(
        messageID: UUID,
        peerID: UUID,
        data: Data,
        durationMs: Int? = nil,
        width: Int? = nil,
        height: Int? = nil,
        caption: String? = nil,
        posterJPEG: Data? = nil
    ) {
        // Prefer the thread key that actually holds this id (ingest can race with peer remap).
        let resolvedPeer: UUID
        if threads[peerID]?.contains(where: { $0.id == messageID }) == true {
            resolvedPeer = peerID
        } else {
            resolvedPeer = self.peerID(forMessage: messageID) ?? peerID
        }

        guard var thread = threads[resolvedPeer],
              let idx = thread.firstIndex(where: { $0.id == messageID })
        else { return }

        var updated = thread[idx]
        updated.videoData = data
        updated.kind = ChatMessageKind.video
        if let durationMs { updated.voiceDurationMs = durationMs }
        if let width, width > 0 { updated.imageWidth = width }
        if let height, height > 0 { updated.imageHeight = height }
        if let posterJPEG, updated.imageData == nil {
            updated.imageData = posterJPEG
            if let ui = UIImage(data: posterJPEG) {
                DecodedImageCache.store(messageID, image: ui)
            }
        }

        // Caption is normally set at decode; only fill if still the generic placeholder.
        if let caption {
            let trimmed = caption.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty, updated.text == "Video" || updated.text == "Media" {
                updated = ChatMessage(
                    id: updated.id,
                    peerUserID: updated.peerUserID,
                    senderUserID: updated.senderUserID,
                    text: trimmed,
                    createdAt: updated.createdAt,
                    isMine: updated.isMine,
                    deleted: updated.deleted,
                    receipt: updated.receipt,
                    kind: .video,
                    mediaObjectId: updated.mediaObjectId,
                    imageWidth: updated.imageWidth,
                    imageHeight: updated.imageHeight,
                    imageData: updated.imageData,
                    voiceData: updated.voiceData,
                    videoData: data,
                    voiceDurationMs: updated.voiceDurationMs,
                    sendError: updated.sendError,
                    pendingSync: updated.pendingSync
                )
            }
        }

        thread[idx] = updated
        threads[resolvedPeer] = thread
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
        // The bubble already carries its quote; a retry must re-seal the same one.
        let replyTo = thread[idx].replyTo

        do {
            try await finishImageSend(
                optimisticID: messageID,
                peerUserID: peerUserID,
                me: me,
                material: material,
                token: token,
                encoded: encoded,
                caption: caption,
                replyTo: replyTo
            )
            return nil
        } catch {
            let message = SessionController.userMessage(for: error)
            markImageFailed(optimisticID: messageID, peerUserID: peerUserID, error: message)
            return message
        }
    }

    /// Returns the delivered message as the server keyed it, so callers whose bubble lives
    /// under a different thread key (Notes) can re-key theirs instead of guessing.
    @discardableResult
    private func finishImageSend(
        optimisticID: UUID,
        peerUserID: UUID,
        me: UUID,
        material: IdentityKeyMaterial,
        token: String,
        encoded: EncodedImage,
        caption: String,
        replyTo: MessageReplyReference? = nil
    ) async throws -> ChatMessage {
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
        let previewJPEG = MediaCrypto.chatPreviewJPEG(from: encoded.data)
        let peerPub = try await peerIdentityForSending(peerUserID: peerUserID, token: token)
        let (payloadData, sealed, usedPreview) = try Self.sealMediaPayload(
            kind: MediaMessagePayload.kindImage,
            mime: encoded.mime,
            width: encoded.width,
            height: encoded.height,
            fileKey: fileKey,
            caption: trimmedCaption.isEmpty ? nil : trimmedCaption,
            durationMs: nil,
            previewJPEG: previewJPEG,
            mediaByteCount: encoded.data.count,
            peerUserID: peerUserID,
            peerPub: peerPub,
            material: material,
            me: me,
            replyTo: replyTo
        )
        let dto = try await messagesService.send(
            SendMessageRequest(
                peerUserId: peerUserID,
                // Idempotency key. Without it the default is a fresh UUID per attempt, so a
                // retried or raced flush inserts a *second* server row for the same photo.
                clientMessageId: optimisticID,
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
            previewData: usedPreview,
            mediaByteCount: encoded.data.count,
            sendError: nil,
            replyTo: replyTo
        )
        if var thread = threads[peerUserID],
           let idx = thread.firstIndex(where: { $0.id == optimisticID })
        {
            thread[idx] = sent
            threads[peerUserID] = thread
        }
        persistSnapshot()
        await refreshConversations(force: true)
        return sent
    }

    /// Server hard-cap on decoded message ciphertext (see `MAX_CIPHERTEXT_BYTES` = 64 KiB).
    /// Dual-seal / v3+self roughly doubles payload size — keep sealed envelopes under this.
    private static let maxSealedEnvelopeBytes = 60 * 1024
    /// Payload plaintext budget before sealing (thumb Base64 is the usual offender).
    /// v3 envelopes carry DR + peer identity box + self box, so keep plaintext smaller
    /// than the old dual-seal budget or the server 64 KiB cap rejects the send.
    private static let maxMediaPayloadPlaintextBytes = 12 * 1024

    /// Builds + seals a media envelope, dropping the preview if it would exceed the server CT cap.
    private static func sealMediaPayload(
        kind: String,
        mime: String,
        width: Int,
        height: Int,
        fileKey: Data,
        caption: String?,
        durationMs: Int?,
        previewJPEG: Data?,
        mediaByteCount: Int,
        peerUserID: UUID,
        peerPub: Data,
        material: IdentityKeyMaterial,
        me: UUID,
        replyTo: MessageReplyReference? = nil,
        linkPreview: LinkPreview? = nil
    ) throws -> (payloadData: Data, sealed: Data, usedPreview: Data?) {
        let safePreview: Data? = {
            guard let previewJPEG,
                  previewJPEG.count <= MediaCrypto.maxEnvelopePreviewBytes
            else { return nil }
            return previewJPEG
        }()

        func encode(includePreview: Bool) throws -> Data {
            let payload = MediaMessagePayload(
                t: kind,
                mime: mime,
                w: width,
                h: height,
                k: fileKey.base64EncodedString(),
                c: caption,
                d: durationMs,
                th: includePreview ? safePreview?.base64EncodedString() : nil,
                s: mediaByteCount,
                re: replyTo,
                lp: linkPreview
            )
            return try payload.encoded()
        }

        var includePreview = safePreview != nil
        var payloadData = try encode(includePreview: includePreview)
        if includePreview, payloadData.count > maxMediaPayloadPlaintextBytes {
            includePreview = false
            payloadData = try encode(includePreview: false)
        }

        var sealed = try MessageCrypto.seal(
            plaintext: payloadData,
            peerUserID: peerUserID,
            toPeerIdentityPublicKey: peerPub,
            ourPrivateKey: material.agreementPrivateKey,
            ourIdentityPublicKey: material.identityPublicKeyData,
            ourUserID: me
        )
        // Last resort: drop preview and reseal once (advances DR by one unused step — safe).
        if sealed.count > maxSealedEnvelopeBytes, includePreview {
            includePreview = false
            payloadData = try encode(includePreview: false)
            sealed = try MessageCrypto.seal(
                plaintext: payloadData,
                peerUserID: peerUserID,
                toPeerIdentityPublicKey: peerPub,
                ourPrivateKey: material.agreementPrivateKey,
                ourIdentityPublicKey: material.identityPublicKeyData,
                ourUserID: me
            )
        }
        if sealed.count > maxSealedEnvelopeBytes {
            throw APIError.server(
                code: "VALIDATION_ERROR",
                message: "Media message is too large to send. Try a shorter video or smaller photo.",
                statusCode: 400
            )
        }
        return (payloadData, sealed, includePreview ? safePreview : nil)
    }

    /// Records are done by the view; this encrypts, uploads, and sends a voice message.
    /// A `transcript` known up front is sealed inside the media payload (never sent as plaintext).
    /// - Parameter transcriptProvider: Produces the on-device transcript. Runs *beside* the send,
    ///   never in front of it: the note goes out as soon as it is sealed and uploaded, so the
    ///   other side can play it right away. The transcript shows here when ready and follows
    ///   as a sealed annotation, so the recipient still gets it without re-transcribing.
    func sendVoice(
        audioData: Data,
        durationMs: Int,
        to peerUserID: UUID,
        waveform: [UInt8]? = nil,
        transcript: String? = nil,
        replyTo: MessageReplyReference? = nil,
        transcriptProvider: ((UUID) async -> String?)? = nil
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
            pendingSync: !isNotesChat(peerUserID),
            replyTo: replyTo
        )
        var list = threads[peerUserID] ?? []
        list.append(optimistic)
        threads[peerUserID] = list
        local.saveSealedMedia(messageID: optimisticID, data: audioData)

        // Capped so a long note's transcript can never push the sealed message past the
        // server's size limit (which would fail the whole voice message).
        let trimmedTranscript = transcript.map(MessageAnnotation.clampTranscript).flatMap { $0.isEmpty ? nil : $0 }
        let displayText = trimmedTranscript ?? "Voice message"
        if trimmedTranscript == nil, let transcriptProvider {
            // Bubble is visible now; transcribe beside the send rather than in front of it.
            voiceTranscriptsInFlight.insert(optimisticID)
            Task {
                let made = await transcriptProvider(optimisticID)
                    .map(MessageAnnotation.clampTranscript)
                    .flatMap { $0.isEmpty ? nil : $0 }
                await applyOwnVoiceTranscript(made, optimisticID: optimisticID, peerUserID: peerUserID)
            }
        }
        if let trimmedTranscript,
           var thread = threads[peerUserID],
           let idx = thread.firstIndex(where: { $0.id == optimisticID })
        {
            thread[idx].transcript = trimmedTranscript
            threads[peerUserID] = thread
        }

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
                    transcript: thread[idx].transcript ?? trimmedTranscript,
                    replyTo: replyTo
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
                    transcript: updated.transcript ?? trimmedTranscript,
                    sendError: "Waiting for connection…",
                    pendingSync: true,
                    replyTo: replyTo
                )
                threads[peerUserID] = thread
            }
            persistSnapshot()
            return nil
        }

        do {
            try await finishVoiceSend(
                optimisticID: optimisticID,
                peerUserID: peerUserID,
                me: realMe,
                material: material,
                token: token,
                audioData: audioData,
                durationMs: durationMs,
                waveform: waveform,
                transcript: trimmedTranscript,
                displayText: displayText,
                replyTo: replyTo
            )
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
                    transcript: existing.transcript ?? trimmedTranscript,
                    sendError: message,
                    pendingSync: true,
                    replyTo: replyTo
                )
                threads[peerUserID] = thread
            }
            lastError = message
            persistSnapshot()
            return message
        }
    }

    private func noteSharedTranscript(_ text: String, for messageID: UUID) {
        if pendingSharedTranscripts[messageID] == nil {
            pendingSharedTranscripts[messageID] = text
        }
    }

    /// Applies any shared transcripts that now have a matching voice note, and drops those keys.
    private func foldSharedTranscripts(into thread: [ChatMessage]) -> [ChatMessage] {
        guard !pendingSharedTranscripts.isEmpty else { return thread }
        let updated = ThreadMessageMerge.applySharedTranscripts(pendingSharedTranscripts, to: thread)
        for message in updated where message.kind == .voice {
            pendingSharedTranscripts.removeValue(forKey: message.id)
        }
        return updated
    }

    /// Keeps a transcript made on this device and shares it with the chat as an annotation, so
    /// the other side — and our other devices, the web client included — show it without
    /// transcribing again.
    ///
    /// Human: Best effort by design. The transcript is already visible here; a failed share only
    /// means the other side can transcribe for themselves. Nothing is shared for Notes or for a
    /// note the server hasn't keyed yet (its id would mean nothing to the other side).
    func shareTranscript(_ transcript: String, forVoiceMessage messageID: UUID, peerUserID: UUID) async {
        let text = MessageAnnotation.clampTranscript(transcript)
        guard !text.isEmpty,
              var thread = threads[peerUserID],
              let index = thread.firstIndex(where: { $0.id == messageID }),
              thread[index].kind == .voice,
              (thread[index].transcript ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { return }

        thread[index].transcript = text
        threads[peerUserID] = thread
        persistThread(peerUserID)
        await sendTranscriptAnnotation(text, forVoiceMessage: messageID, peerUserID: peerUserID)
    }

    /// Shows the transcript of a note we just recorded, and shares it once the note is sent.
    /// `nil` means there is none (no model yet, no speech, or it failed); the note stays as is.
    private func applyOwnVoiceTranscript(_ text: String?, optimisticID: UUID, peerUserID: UUID) async {
        voiceTranscriptsInFlight.remove(optimisticID)
        let messageID = sentVoiceIDs.removeValue(forKey: optimisticID) ?? optimisticID
        guard let text,
              var thread = threads[peerUserID],
              let index = thread.firstIndex(where: { $0.id == messageID }),
              thread[index].kind == .voice,
              !thread[index].deleted
        else { return }
        if (thread[index].transcript ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            thread[index].transcript = text
            threads[peerUserID] = thread
            persistThread(peerUserID)
        }
        let note = thread[index]
        if note.id == optimisticID, note.pendingSync || note.receipt == .sending || note.receipt == .failed {
            // Still on its way (or queued offline); `finishVoiceSend` shares it once keyed.
            transcriptsAwaitingSend[optimisticID] = text
            return
        }
        await sendTranscriptAnnotation(text, forVoiceMessage: note.id, peerUserID: peerUserID)
    }

    /// Seals `text` as a transcript annotation for a sent voice note. Best effort; see
    /// `shareTranscript`.
    private func sendTranscriptAnnotation(_ text: String, forVoiceMessage messageID: UUID, peerUserID: UUID) async {
        guard !isNotesChat(peerUserID),
              let note = threads[peerUserID]?.first(where: { $0.id == messageID }),
              !note.deleted,
              !note.pendingSync,
              note.receipt != .sending,
              note.receipt != .failed,
              connectivity.isOnline,
              let token = sessionController?.bearerToken,
              let me = sessionController?.userID,
              let material = cryptoController?.material
        else { return }

        do {
            let plaintext = try JSONEncoder().encode(MessageAnnotation.transcript(text, for: messageID))
            let peerPub = try await peerIdentityForSending(peerUserID: peerUserID, token: token)
            let sealed = try MessageCrypto.seal(
                plaintext: plaintext,
                peerUserID: peerUserID,
                toPeerIdentityPublicKey: peerPub,
                ourPrivateKey: material.agreementPrivateKey,
                ourIdentityPublicKey: material.identityPublicKeyData,
                ourUserID: me
            )
            let dto = try await messagesService.send(
                SendMessageRequest(
                    peerUserId: peerUserID,
                    clientMessageId: UUID(),
                    contentType: MessageAnnotation.contentType,
                    ciphertext: sealed.base64EncodedString()
                ),
                token: token
            )
            // DR is one-shot: our own history decode reads this instead of re-opening.
            local.saveSealedPlaintext(messageID: dto.id, data: plaintext)
        } catch {
            // Kept locally either way; see the Human note on `shareTranscript`.
        }
    }

    @discardableResult
    private func finishVoiceSend(
        optimisticID: UUID,
        peerUserID: UUID,
        me: UUID,
        material: IdentityKeyMaterial,
        token: String,
        audioData: Data,
        durationMs: Int,
        waveform: [UInt8]?,
        transcript: String?,
        displayText: String,
        replyTo: MessageReplyReference? = nil
    ) async throws -> ChatMessage {
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

        var payload = MediaMessagePayload(
            t: MediaMessagePayload.kindVoice,
            mime: "audio/mp4",
            w: 0,
            h: 0,
            k: fileKey.base64EncodedString(),
            c: (transcript?.isEmpty == false) ? transcript : nil,
            d: durationMs,
            wf: waveform.flatMap(VoiceWaveform.encode),
            re: replyTo
        )
        var payloadData = try payload.encoded()
        if payloadData.count > Self.maxMediaPayloadPlaintextBytes, payload.c != nil {
            payload.c = nil
            payloadData = try payload.encoded()
        }
        let peerPub = try await peerIdentityForSending(peerUserID: peerUserID, token: token)
        var sealed = try MessageCrypto.seal(
            plaintext: payloadData,
            peerUserID: peerUserID,
            toPeerIdentityPublicKey: peerPub,
            ourPrivateKey: material.agreementPrivateKey,
            ourIdentityPublicKey: material.identityPublicKeyData,
            ourUserID: me
        )
        if sealed.count > Self.maxSealedEnvelopeBytes, payload.c != nil {
            payload.c = nil
            payloadData = try payload.encoded()
            sealed = try MessageCrypto.seal(
                plaintext: payloadData,
                peerUserID: peerUserID,
                toPeerIdentityPublicKey: peerPub,
                ourPrivateKey: material.agreementPrivateKey,
                ourIdentityPublicKey: material.identityPublicKeyData,
                ourUserID: me
            )
        }
        if sealed.count > Self.maxSealedEnvelopeBytes {
            throw APIError.server(
                code: "VALIDATION_ERROR",
                message: "Media message is too large to send. Try a shorter voice note.",
                statusCode: 400
            )
        }
        let dto = try await messagesService.send(
            SendMessageRequest(
                peerUserId: peerUserID,
                clientMessageId: optimisticID,
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
            voiceWaveform: waveform,
            transcript: transcript,
            replyTo: replyTo
        )
        if var thread = threads[peerUserID],
           let idx = thread.firstIndex(where: { $0.id == optimisticID })
        {
            // Same note, new id: its bubble carries on instead of landing again.
            VoiceTranscriptDisclosure.shared.handOff(from: optimisticID, to: dto.id)
            TranscriptionModelInstall.shared.handOff(from: optimisticID, to: dto.id)
            var replacement = sent
            // A transcript that landed while this was uploading.
            if replacement.transcript == nil { replacement.transcript = thread[idx].transcript }
            thread[idx] = replacement
            threads[peerUserID] = foldSharedTranscripts(into: thread)
        }
        if voiceTranscriptsInFlight.contains(optimisticID) {
            sentVoiceIDs[optimisticID] = dto.id
        }
        if let late = transcriptsAwaitingSend.removeValue(forKey: optimisticID), payload.c == nil {
            // Sent without it: follow up with the annotation instead.
            Task { await sendTranscriptAnnotation(late, forVoiceMessage: dto.id, peerUserID: peerUserID) }
        }
        await refreshConversations(force: true)
        persistSnapshot()
        return sent
    }

    private func markVoiceFailed(optimisticID: UUID, peerUserID: UUID, error: String) {
        guard var thread = threads[peerUserID],
              let idx = thread.firstIndex(where: { $0.id == optimisticID })
        else { return }
        thread[idx].receipt = .failed
        thread[idx].sendError = error
        thread[idx].pendingSync = true
        threads[peerUserID] = thread
        persistSnapshot()
    }

    /// Loads decrypted voice bytes for playback (deduped; safe to call from many onAppears).
    func ensureVoiceLoaded(for message: ChatMessage) async {
        guard message.kind == .voice,
              message.voiceData == nil,
              !message.deleted
        else { return }

        if let existing = mediaHydrateTasks[message.id] {
            await existing.value
            return
        }
        let task = Task { [weak self] in
            guard let self else { return }
            await self.hydrateVoice(for: message)
        }
        mediaHydrateTasks[message.id] = task
        await task.value
        if mediaHydrateTasks[message.id] == task {
            mediaHydrateTasks[message.id] = nil
        }
    }

    private func hydrateVoice(for message: ChatMessage) async {
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
                  let payload = MediaMessagePayload.parse(payloadData),
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

    /// Loads decrypted full image bytes for a media message (caches on success).
    /// Call only from an explicit download action — never on bubble appear.
    func ensureImageLoaded(for message: ChatMessage) async {
        guard message.kind == .image,
              message.imageData == nil,
              !message.deleted
        else { return }

        if let existing = mediaHydrateTasks[message.id] {
            await existing.value
            return
        }
        let task = Task { [weak self] in
            guard let self else { return }
            await self.hydrateImage(for: message)
        }
        mediaHydrateTasks[message.id] = task
        await task.value
        if mediaHydrateTasks[message.id] == task {
            mediaHydrateTasks[message.id] = nil
        }
    }

    private func hydrateImage(for message: ChatMessage) async {
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

        beginTransfer(
            message.id,
            isUpload: false,
            phase: .transferring,
            totalBytes: message.mediaByteCount
        )
        defer { endTransfer(message.id) }

        do {
            // Prefer cached media payload (file key). Never re-open as recipient — that
            // advances/desyncs the Double Ratchet after the first successful decrypt.
            guard let payloadData = try await mediaPayloadData(for: message, token: token, material: material),
                  let payload = MediaMessagePayload.parse(payloadData),
                  let keyData = Data(base64Encoded: payload.k)
            else { return }

            let sealedFile = try await mediaService.downloadContent(
                mediaID: mediaID,
                token: token,
                onProgress: progressSink(for: message.id)
            )
            try Task.checkCancellation()
            advanceTransfer(message.id, to: .finishing)
            let jpeg = try MediaCrypto.openFile(sealed: sealedFile, keyData: keyData)
            local.saveSealedMedia(messageID: message.id, data: jpeg)
            updateMessageImage(messageID: message.id, peerID: message.peerUserID, data: jpeg)
        } catch {
            // Leave placeholder; user can reopen thread to retry.
        }
    }

    /// Loads the large image of a link preview when its bubble appears.
    ///
    /// Human: Unlike photos (downloaded on tap), preview images load on their own — they are
    /// small, and Telegram auto-downloads them too. The bytes come from Shroud's server and
    /// are decrypted here; the website itself is never contacted by the recipient.
    /// Agent: READS the sealed `t: "link"` payload for the blob key (never re-opens as
    /// recipient); WRITES the JPEG to the sealed media cache and `imageData`.
    func ensureLinkImageLoaded(for message: ChatMessage) async {
        guard message.needsLinkImageDownload else { return }
        if let existing = mediaHydrateTasks[message.id] {
            await existing.value
            return
        }
        let task = Task { [weak self] in
            guard let self else { return }
            await self.hydrateLinkImage(for: message)
        }
        mediaHydrateTasks[message.id] = task
        await task.value
        if mediaHydrateTasks[message.id] == task {
            mediaHydrateTasks[message.id] = nil
        }
    }

    private func hydrateLinkImage(for message: ChatMessage) async {
        guard message.needsLinkImageDownload else { return }
        if let cached = local.sealedMedia(for: message.id) {
            updateMessageImage(messageID: message.id, peerID: message.peerUserID, data: cached)
            return
        }
        guard let mediaID = message.mediaObjectId,
              let token = sessionController?.bearerToken,
              let material = cryptoController?.material
        else { return }
        do {
            guard let payloadData = try await mediaPayloadData(for: message, token: token, material: material),
                  let payload = MediaMessagePayload.parse(payloadData),
                  let keyData = Data(base64Encoded: payload.k)
            else { return }
            let sealedFile = try await mediaService.downloadContent(mediaID: mediaID, token: token)
            try Task.checkCancellation()
            let jpeg = try MediaCrypto.openFile(sealed: sealedFile, keyData: keyData)
            local.saveSealedMedia(messageID: message.id, data: jpeg)
            updateMessageImage(messageID: message.id, peerID: message.peerUserID, data: jpeg)
        } catch {
            // The block keeps its placeholder; the next appearance tries again.
        }
    }

    /// Resolves the sealed media payload JSON (contains AES file key). Never re-opens as recipient
    /// (that would desync Double Ratchet). Inbound must have been cached on first decrypt.
    private func mediaPayloadData(
        for message: ChatMessage,
        token: String,
        material: IdentityKeyMaterial
    ) async throws -> Data? {
        if let cached = local.sealedPlaintext(for: message.id), MessageDecoder.isMediaPayloadData(cached) {
            return cached
        }
        // Cached text might be the display label if something wrote the wrong blob — ignore it.
        if message.isMine {
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
                as: .sender,
                sentAt: dto.createdAt
            )
            if MessageDecoder.isMediaPayloadData(payloadData) {
                local.saveSealedPlaintext(messageID: message.id, data: payloadData)
                return payloadData
            }
            return nil
        }

        // Inbound recovery: if the thread was reloaded before the first open saved the payload,
        // re-decode this single DTO (open as recipient once) so hydrate can finish.
        let response = try await messagesService.listMessages(
            peerUserID: message.peerUserID,
            token: token,
            limit: 80
        )
        guard let dto = response.messages.first(where: { $0.id == message.id }),
              let ciphertextB64 = dto.ciphertext,
              let envelopeData = Data(base64Encoded: ciphertextB64)
        else { return nil }

        // Prefer already-cached open from a concurrent decode of the same message.
        if let cached = local.sealedPlaintext(for: message.id), MessageDecoder.isMediaPayloadData(cached) {
            return cached
        }

        let senderPub = try await resolvePeerIdentityPublicKey(
            peerUserID: dto.senderUserId,
            token: token
        )
        // Only open if we have no sealed plaintext at all — otherwise a bad non-JSON blob
        // would burn a second open. Skip if *any* sealed bytes exist.
        if local.sealedPlaintext(for: message.id) != nil {
            return nil
        }
        let payloadData = try MessageCrypto.open(
            envelopeData: envelopeData,
            peerUserID: dto.senderUserId,
            with: material.agreementPrivateKey,
            ourIdentityPublicKey: material.identityPublicKeyData,
            senderIdentityPublicKey: senderPub,
            as: .recipient,
            sentAt: dto.createdAt
        )
        guard MessageDecoder.isMediaPayloadData(payloadData) else { return nil }
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
            } else if type == "message.reaction" {
                handleReactionEvent(json)
            } else if type == "reactions.seen" {
                handleReactionsSeenEvent(json)
            } else if type == "conversation.deleted" {
                handleConversationDeletedEvent(json)
            } else if type == "typing" {
                handleTyping(json)
            } else if type == "recording" {
                handleRecording(json)
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

    /// A whole chat was deleted — by the peer, or by one of our own other devices.
    ///
    /// Human: Three cases. Our other device deleted it → drop it here too. The peer deleted
    /// it and we had consented → drop it here too. The peer deleted it and we had not →
    /// keep our own messages and reload, so their bubbles turn into "Message deleted".
    /// Agent: READS json(user_id, peer_user_id, cleared_for_peer, scope); WRITES threads via
    /// clearChatLocally; CALLS loadThread / refreshContacts / refreshConversations.
    private func handleConversationDeletedEvent(_ json: [String: Any]) {
        guard let me = sessionController?.userID,
              let initiatorString = json["user_id"] as? String,
              let initiator = UUID(uuidString: initiatorString),
              let otherString = json["peer_user_id"] as? String,
              let other = UUID(uuidString: otherString)
        else { return }

        let initiatedHere = initiator == me
        // The chat this is about is always "the participant that isn't me".
        let peer = initiatedHere ? other : initiator
        // Saved Messages arrive as a self-conversation; they live under the local sentinel.
        let threadPeer = peer == me ? Self.notesPeerID : peer
        let clearedForPeer = json["cleared_for_peer"] as? Bool ?? false
        let forEveryone = (json["scope"] as? String) == "everyone"

        if initiatedHere || clearedForPeer {
            clearChatLocally(peerUserID: threadPeer)
        } else {
            // We keep our own history; refetch so their messages come back as tombstones.
            Task { await loadThread(peerUserID: threadPeer) }
        }

        Task {
            await refreshConversations(force: true)
            if forEveryone { await refreshContacts(force: true) }
        }
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
            if status.rank > current.rank {
                copy[idx].receipt = status
                threads[peerID] = copy
                persistThread(peerID)
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
                persistThread(peerID)
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
        setPeerTyping(userID, isTyping)
    }

    private func handleRecording(_ json: [String: Any]) {
        guard let userString = json["user_id"] as? String,
              let userID = UUID(uuidString: userString),
              let isRecording = json["is_recording"] as? Bool
        else { return }
        setPeerRecording(userID, isRecording)
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
        if !online {
            setPeerTyping(userID, false)
            setPeerRecording(userID, false)
        }
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

        if dto.contentType == MessageAnnotation.contentType {
            // A transcript shared by the other side (or our other device): not a new message,
            // so no bubble, no unread badge — it fills in the voice note it points at.
            if let shared = MessageAnnotation.parseTranscript(chat.text) {
                noteSharedTranscript(shared.text, for: shared.messageID)
            }
            if let current = threads[threadPeer] {
                let updated = foldSharedTranscripts(into: current)
                if updated != current {
                    threads[threadPeer] = updated
                    persistThread(threadPeer)
                }
            }
            return
        }

        var thread = threads[threadPeer] ?? []
        if !thread.contains(where: { $0.id == chat.id }) {
            thread.append(chat)
            thread = foldSharedTranscripts(into: thread)
            // Their message is what the typing/recording was for: it takes the indicator's place.
            if !chat.isMine {
                setPeerTyping(dto.senderUserId, false)
                setPeerRecording(dto.senderUserId, false)
            }
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
                previewData: message.previewData,
                mediaByteCount: message.mediaByteCount,
                voiceData: message.voiceData,
                videoData: message.videoData,
                voiceDurationMs: message.voiceDurationMs,
                voiceWaveform: message.voiceWaveform,
                transcript: message.transcript,
                sendError: message.sendError,
                todoDone: message.todoDone,
                pendingSync: message.pendingSync,
                replyTo: message.replyTo,
                linkPreview: message.linkPreview
            )
        }
        return message
    }

    /// Cached (TOFU) key for decrypt. Fetches in the background to detect identity rotation.
    private func resolvePeerIdentityPublicKey(peerUserID: UUID, token: String) async throws -> Data {
        if let cached = peerKeys.publicKeyData(for: peerUserID) {
            if !verifiedPeerIDs.contains(peerUserID) {
                verifiedPeerIDs.insert(peerUserID)
                Task { await self.verifyPeerIdentity(peerUserID, token: token) }
            }
            return cached
        }
        let fetched = try await fetchPeerIdentityKey(peerUserID: peerUserID, token: token)
        peerKeys.save(userID: peerUserID, publicKeyBase64: fetched.base64EncodedString())
        return fetched
    }

    /// Sending must not use a superseded key, and must not silently switch to a new one.
    private func peerIdentityForSending(peerUserID: UUID, token: String) async throws -> Data {
        await verifyPeerIdentity(peerUserID, token: token)
        if identityChanges[peerUserID] != nil {
            throw PeerIdentityError.changed
        }
        return try await resolvePeerIdentityPublicKey(peerUserID: peerUserID, token: token)
    }

    func identityChange(for peerUserID: UUID) -> PeerIdentityChange? {
        identityChanges[peerUserID]
    }

    func safetyNumber(for peerUserID: UUID) -> String? {
        guard let local = cryptoController?.material?.identityPublicKeyData,
              let peer = peerKeys.publicKeyData(for: peerUserID)
        else { return nil }
        return IdentitySafetyNumber.displayString(localIdentity: local, peerIdentity: peer)
    }

    func refreshPeerIdentity(_ peerUserID: UUID) async {
        guard let token = sessionController?.bearerToken else { return }
        await verifyPeerIdentity(peerUserID, token: token)
        if peerKeys.publicKeyData(for: peerUserID) == nil {
            _ = try? await resolvePeerIdentityPublicKey(peerUserID: peerUserID, token: token)
        }
    }

    /// Caller has verified the new key (safety number) out of band.
    func acceptNewPeerIdentity(_ peerUserID: UUID) {
        guard let change = identityChanges[peerUserID] else { return }
        peerKeys.save(userID: peerUserID, publicKeyBase64: change.currentKey.base64EncodedString())
        RatchetSessionStore.delete(peerUserID: peerUserID)
        identityChanges[peerUserID] = nil
        verifiedPeerIDs.insert(peerUserID)
    }

    private func verifyPeerIdentity(_ peerUserID: UUID, token: String) async {
        do {
            let fetched = try await fetchPeerIdentityKey(peerUserID: peerUserID, token: token)
            verifiedPeerIDs.insert(peerUserID)
            if let cached = peerKeys.publicKeyData(for: peerUserID), cached != fetched {
                identityChanges[peerUserID] = PeerIdentityChange(previousKey: cached, currentKey: fetched)
            }
        } catch {
            // Unreachable server: keep the cached key; do not invent a change.
        }
    }

    private func fetchPeerIdentityKey(peerUserID: UUID, token: String) async throws -> Data {
        let identity = try await keyBundleService.fetchIdentity(
            userID: peerUserID,
            bearerToken: token
        )
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
        todoDone: Bool?,
        replyTo: MessageReplyReference? = nil,
        linkPreview: LinkPreview? = nil
    ) async {
        let peerUserID = Self.notesPeerID
        let me = sessionController?.userID ?? peerUserID
        // A todo keeps its own marker format; only plain notes can carry a quote or a preview.
        let (noteWire, sealedPreview) = Self.textWire(
            body: text,
            replyTo: replyTo,
            linkPreview: kind == .todo ? nil : linkPreview
        )
        var message = NotesLocal.makeNote(
            text: text,
            kind: kind,
            senderUserID: me,
            todoDone: todoDone,
            replyTo: replyTo
        )
        message.linkPreview = sealedPreview
        var list = threads[peerUserID] ?? []
        list.append(message)
        threads[peerUserID] = list
        let wireText = kind == .todo
            ? NotesLocal.syncedTodoPlaintext(text: text, done: todoDone ?? false)
            : noteWire
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
                    todoDone: message.todoDone,
                    replyTo: message.replyTo,
                    linkPreview: message.linkPreview
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
        token: String,
        replyTo: MessageReplyReference? = nil,
        linkPreview: LinkPreview? = nil
    ) async throws -> ChatMessage {
        let peerPub = try await peerIdentityForSending(peerUserID: peerUserID, token: token)
        // A reply / preview seals body + extras together; a plain message stays raw UTF-8.
        let (wireText, sealedPreview) = Self.textWire(body: text, replyTo: replyTo, linkPreview: linkPreview)
        let sealed = try MessageCrypto.seal(
            plaintext: Data(wireText.utf8),
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
        // Cache what was sealed (quote included) so a later decode rebuilds the same bubble.
        local.saveSealedPlaintext(messageID: dto.id, text: wireText)
        let sent = ChatMessage(
            id: dto.id,
            peerUserID: peerUserID,
            senderUserID: me,
            text: text,
            createdAt: dto.createdAt,
            isMine: true,
            deleted: false,
            receipt: receiptStatus(from: dto),
            replyTo: replyTo,
            linkPreview: sealedPreview
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

    /// Plaintext for a text message, with the link preview trimmed to what fits.
    ///
    /// Human: The preview must never be the reason a message can't be sent. Sealed three times
    /// and base64-expanded, plaintext much past 12 KB overflows the server's 64 KiB envelope
    /// cap, so a long message first loses the preview's thumbnail, then its description, then
    /// the preview itself.
    /// Agent: RETURNS the wire string and the preview actually sealed (nil when dropped).
    static func textWire(
        body: String,
        replyTo: MessageReplyReference?,
        linkPreview: LinkPreview?
    ) -> (wire: String, sealedPreview: LinkPreview?) {
        guard var preview = linkPreview else {
            return (MessageTextPayload.wire(body: body, replyTo: replyTo), nil)
        }
        var candidates: [LinkPreview] = [preview]
        preview.thumbnail = nil
        candidates.append(preview)
        preview.summary = nil
        candidates.append(preview)
        for candidate in candidates {
            let wire = MessageTextPayload.wire(body: body, replyTo: replyTo, linkPreview: candidate)
            if wire.utf8.count <= maxMediaPayloadPlaintextBytes {
                return (wire, candidate)
            }
        }
        return (MessageTextPayload.wire(body: body, replyTo: replyTo), nil)
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
                let queued = threads[peerID]?.first(where: { $0.id == messageID })
                do {
                    // A queued link message always goes as text: the inline thumbnail survives
                    // a restart, the large image may not.
                    try await deliverPendingText(
                        messageID: messageID,
                        text: text,
                        peerUserID: peerID,
                        me: me,
                        material: material,
                        token: token,
                        replyTo: queued?.replyTo,
                        linkPreview: queued?.linkPreview
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
                        caption: caption,
                        replyTo: message.replyTo
                    )
                } catch {
                    markImageFailed(
                        optimisticID: messageID,
                        peerUserID: peerID,
                        error: SessionController.userMessage(for: error)
                    )
                }
            case let .video(messageID, peerID, caption):
                guard let thread = threads[peerID],
                      let message = thread.first(where: { $0.id == messageID }),
                      let data = message.videoData ?? local.sealedMedia(for: messageID)
                else { continue }
                let encoded = EncodedVideo(
                    data: data,
                    width: message.imageWidth ?? 0,
                    height: message.imageHeight ?? 0,
                    durationMs: message.voiceDurationMs ?? 0,
                    mime: "video/mp4",
                    thumbnailJPEG: message.imageData
                )
                if var list = threads[peerID],
                   let idx = list.firstIndex(where: { $0.id == messageID })
                {
                    list[idx].receipt = .sending
                    list[idx].sendError = nil
                    threads[peerID] = list
                }
                do {
                    try await finishVideoSend(
                        optimisticID: messageID,
                        peerUserID: peerID,
                        me: me,
                        material: material,
                        token: token,
                        encoded: encoded,
                        caption: caption,
                        replyTo: message.replyTo
                    )
                } catch {
                    markVideoFailed(
                        optimisticID: messageID,
                        peerUserID: peerID,
                        error: SessionController.userMessage(for: error)
                    )
                }
            case let .voice(messageID, peerID):
                guard let thread = threads[peerID],
                      let message = thread.first(where: { $0.id == messageID }),
                      let data = message.voiceData ?? local.sealedMedia(for: messageID)
                else { continue }
                if var list = threads[peerID],
                   let idx = list.firstIndex(where: { $0.id == messageID })
                {
                    list[idx].receipt = .sending
                    list[idx].sendError = nil
                    threads[peerID] = list
                }
                do {
                    try await finishVoiceSend(
                        optimisticID: messageID,
                        peerUserID: peerID,
                        me: me,
                        material: material,
                        token: token,
                        audioData: data,
                        durationMs: message.voiceDurationMs ?? 0,
                        waveform: message.voiceWaveform,
                        transcript: message.transcript,
                        displayText: message.text,
                        replyTo: message.replyTo
                    )
                } catch {
                    markVoiceFailed(
                        optimisticID: messageID,
                        peerUserID: peerID,
                        error: SessionController.userMessage(for: error)
                    )
                }
            }
        }
        await refreshConversations(force: true)
    }
}

// MARK: - Reactions

extension MessagingController {
    struct ReactionFailure: Equatable, Sendable {
        let id = UUID()
        let messageID: UUID
        let message: String
    }

    fileprivate struct ReactionIntent {
        let storePeerID: UUID
        /// Our whole set; empty removes our record.
        let emojis: [String]
    }

    fileprivate struct ReactionRollback {
        /// Nil: we had no reaction on the message.
        var entry: MessageReaction?
        /// The chat's catch-up cursor when the first tap went out. If the save fails, catch-up
        /// goes back here: a change from our other device ignored meanwhile (our tap was
        /// pending) comes back.
        var cursor: Int64?
    }

    /// A message has a server id and can carry reactions: not deleted, not still sending, and
    /// not in Saved Messages, where much of what shows exists only on this device.
    func canReact(to message: ChatMessage) -> Bool {
        !message.deleted
            && !isNotesChat(message.peerUserID)
            && message.kind != .todo
            && !message.pendingSync
            && message.receipt != .sending
            && message.receipt != .failed
            && message.sendError == nil
    }

    /// The emoji we show on `message`, oldest first, unconfirmed changes included.
    func myReactions(on message: ChatMessage) -> [String] {
        guard let me = sessionController?.userID else { return [] }
        return ReactionMerge.emojis(of: me, in: message.reactions)
    }

    /// Picking an emoji: takes it back when it is ours, otherwise adds it — past the server's
    /// limit our oldest goes (`ReactionMerge.toggled`).
    func toggleReaction(_ emoji: String, on messageID: UUID, peerUserID: UUID) {
        guard let message = threads[peerUserID]?.first(where: { $0.id == messageID }) else { return }
        let next = ReactionMerge.toggled(emoji, in: myReactions(on: message), limit: reactionLimit)
        setMyReactions(next, on: messageID, peerUserID: peerUserID)
    }

    /// Reads the server's settings for clients (the reaction limit); keeps the last answer.
    func refreshServerConfig() async {
        guard let token = sessionController?.bearerToken,
              let config = try? await messagesService.clientConfig(token: token)
        else { return }
        let limit = max(1, config.reactions.maxPerUser)
        guard limit != reactionLimit else { return }
        reactionLimit = limit
        UserDefaults.standard.set(limit, forKey: Self.reactionLimitKey)
    }

    /// Shows our reaction at once and saves it in the background.
    ///
    /// Human: One request per message at a time. Taps while it runs only update what we want;
    /// when it returns, one more request carries the last choice. If that fails the chip goes
    /// back to what the server holds and `reactionFailure` is set.
    /// Agent: WRITES threads[peerUserID] (pending entry); CALLS drainReactionIntents.
    func setMyReactions(_ emojis: [String], on messageID: UUID, peerUserID: UUID) {
        guard let me = sessionController?.userID,
              var thread = threads[peerUserID],
              let index = thread.firstIndex(where: { $0.id == messageID }),
              canReact(to: thread[index]),
              emojis.allSatisfy(MessageReactionPayload.isSingleEmoji),
              Set(emojis).count == emojis.count
        else { return }
        let reactions = thread[index].reactions
        let current = reactions.first(where: { $0.userID == me })
        guard (current?.emojis ?? []) != emojis else { return }

        if reactionSendTasks[messageID] == nil {
            reactionRollback[messageID] = ReactionRollback(entry: current, cursor: reactionCursors()[peerUserID])
        }
        let optimistic = MessageReaction(userID: me, emojis: emojis, seq: current?.seq ?? 0, pending: true)
        thread[index].reactions = ReactionMerge.replacing(me, with: optimistic, in: reactions)
        threads[peerUserID] = thread

        reactionIntents[messageID] = ReactionIntent(storePeerID: peerUserID, emojis: emojis)
        if reactionSendTasks[messageID] == nil {
            reactionSendTasks[messageID] = Task { [weak self] in
                await self?.drainReactionIntents(messageID)
            }
        }
    }

    private func drainReactionIntents(_ messageID: UUID) async {
        var storePeerID: UUID?
        while let intent = reactionIntents.removeValue(forKey: messageID) {
            storePeerID = intent.storePeerID
            guard let me = sessionController?.userID else { break }
            do {
                let dto = try await sendReaction(intent, messageID: messageID)
                // 204 on a removal: the server held none, which is what we wanted.
                let confirmed = dto.map { MessageReaction(userID: me, emojis: intent.emojis, seq: $0.seq) }
                    ?? reactionRollback[messageID]?.entry.map { MessageReaction(userID: me, emojis: [], seq: $0.seq) }
                reactionRollback[messageID]?.entry = confirmed
                if reactionIntents[messageID] == nil {
                    replaceMyReaction(confirmed, on: messageID, peerUserID: intent.storePeerID, me: me)
                }
            } catch {
                // A newer choice is queued; it tries again with its own request.
                if reactionIntents[messageID] != nil { continue }
                replaceMyReaction(
                    reactionRollback[messageID]?.entry,
                    on: messageID,
                    peerUserID: intent.storePeerID,
                    me: me
                )
                if let cursor = reactionRollback[messageID]?.cursor,
                   let now = reactionCursors()[intent.storePeerID], cursor < now
                {
                    saveReactionCursor(cursor, for: intent.storePeerID)
                }
                reactionFailure = ReactionFailure(
                    messageID: messageID,
                    message: connectivity.isOnline
                        ? "Couldn't save your reaction."
                        : "You're offline. Your reaction wasn't saved."
                )
            }
        }
        reactionSendTasks[messageID] = nil
        reactionRollback[messageID] = nil
        if let storePeerID { scheduleReactionPersist(storePeerID) }
    }

    private func sendReaction(_ intent: ReactionIntent, messageID: UUID) async throws -> ReactionDTO? {
        guard connectivity.isOnline,
              let token = sessionController?.bearerToken,
              let material = cryptoController?.material
        else { throw APIError.transport("offline") }
        guard !intent.emojis.isEmpty else {
            return try await messagesService.deleteReaction(messageID: messageID, token: token)
        }
        let plaintext = try JSONEncoder().encode(MessageReactionPayload.make(intent.emojis, for: messageID))
        let peerPublic = isNotesChat(intent.storePeerID)
            ? material.identityPublicKeyData
            : try await peerIdentityForSending(peerUserID: intent.storePeerID, token: token)
        // v2 on purpose: a reaction is overwritten in place, so ratchet steps would be lost, and
        // every device must be able to open it at any time (docs/architecture.md).
        let sealed = try MessageCrypto.seal(
            plaintext: plaintext,
            toPeerIdentityPublicKey: peerPublic,
            ourPrivateKey: material.agreementPrivateKey,
            ourIdentityPublicKey: material.identityPublicKeyData
        )
        return try await messagesService.putReaction(messageID: messageID, ciphertext: sealed, token: token)
    }

    private func replaceMyReaction(_ entry: MessageReaction?, on messageID: UUID, peerUserID: UUID, me: UUID) {
        guard var thread = threads[peerUserID],
              let index = thread.firstIndex(where: { $0.id == messageID }),
              !thread[index].deleted
        else { return }
        let updated = ReactionMerge.replacing(me, with: entry, in: thread[index].reactions)
        guard updated != thread[index].reactions else { return }
        thread[index].reactions = updated
        threads[peerUserID] = thread
    }

    /// Opens sealed reaction records. A record we already hold at the same `seq` is reused
    /// instead of decrypted again; one that does not open counts as no reaction.
    private func openReactions(
        _ dtos: [ReactionDTO],
        held: [MessageReaction],
        me: UUID,
        material: IdentityKeyMaterial,
        token: String
    ) async -> [MessageReaction] {
        var result: [MessageReaction] = []
        result.reserveCapacity(dtos.count)
        for dto in dtos {
            result.append(await openReaction(dto, held: held, me: me, material: material, token: token))
        }
        return result
    }

    private func openReaction(
        _ dto: ReactionDTO,
        held: [MessageReaction],
        me: UUID,
        material: IdentityKeyMaterial,
        token: String
    ) async -> MessageReaction {
        let removed = MessageReaction(userID: dto.userId, emojis: [], seq: dto.seq)
        guard let ciphertext = dto.ciphertext else { return removed }
        if let known = held.first(where: { $0.userID == dto.userId && $0.seq == dto.seq && !$0.pending }) {
            return known
        }
        guard let envelope = Data(base64Encoded: ciphertext) else { return removed }
        do {
            // Tagged v2 only: every build that writes reactions tags its boxes.
            let plaintext: Data
            if dto.userId == me {
                plaintext = try MessageCrypto.openTagged(
                    envelopeData: envelope,
                    with: material.agreementPrivateKey,
                    ourIdentityPublicKey: material.identityPublicKeyData,
                    senderIdentityPublicKey: material.identityPublicKeyData,
                    as: .sender
                )
            } else {
                let senderPublic = try await resolvePeerIdentityPublicKey(peerUserID: dto.userId, token: token)
                plaintext = try MessageCrypto.openTagged(
                    envelopeData: envelope,
                    with: material.agreementPrivateKey,
                    ourIdentityPublicKey: material.identityPublicKeyData,
                    senderIdentityPublicKey: senderPublic,
                    as: .recipient
                )
            }
            let emojis = MessageReactionPayload.parse(plaintext, for: dto.messageId) ?? []
            return MessageReaction(userID: dto.userId, emojis: emojis, seq: dto.seq)
        } catch {
            return removed
        }
    }

    /// Opens changes (WebSocket, catch-up) and applies them to the messages this device holds;
    /// a change for a message it does not hold waits for that message's history page.
    ///
    /// Agent: One id → index map per call (catch-up can bring hundreds of changes against a
    /// thread of thousands); the save is batched (`scheduleReactionPersist`).
    private func applyReactionChanges(_ dtos: [ReactionDTO], storePeerID: UUID) async {
        guard let me = sessionController?.userID,
              let token = sessionController?.bearerToken,
              let material = cryptoController?.material,
              let held = threads[storePeerID]
        else { return }
        let heldByID = Dictionary(held.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        // Only the two people in this chat react in it (in Notes, only us).
        let reactors: Set<UUID> = isNotesChat(storePeerID) ? [me] : [me, storePeerID]
        // Decrypt first, then apply to the thread as it is by then (it may have moved on).
        var opened: [(messageID: UUID, entry: MessageReaction)] = []
        for dto in dtos where reactors.contains(dto.userId) {
            guard let message = heldByID[dto.messageId], !message.deleted else { continue }
            let entry = await openReaction(dto, held: message.reactions, me: me, material: material, token: token)
            opened.append((dto.messageId, entry))
        }
        guard !opened.isEmpty, var thread = threads[storePeerID] else { return }
        var indexByID: [UUID: Int] = [:]
        for (index, message) in thread.enumerated() where indexByID[message.id] == nil {
            indexByID[message.id] = index
        }
        var changed = false
        for (messageID, entry) in opened {
            guard let index = indexByID[messageID],
                  !thread[index].deleted,
                  let updated = ReactionMerge.apply(entry, to: thread[index].reactions)
            else { continue }
            thread[index].reactions = updated
            changed = true
        }
        guard changed else { return }
        threads[storePeerID] = thread
        scheduleReactionPersist(storePeerID)
    }

    /// WS `message.reaction` from the peer or one of our other devices.
    ///
    /// Agent: Never moves the catch-up cursor: an event dropped from the socket queue must still
    /// come back through catch-up, and re-applying one is harmless (same `seq`).
    private func handleReactionEvent(_ json: [String: Any]) {
        guard let body = json["reaction"],
              let data = try? JSONSerialization.data(withJSONObject: body),
              let dto = try? JSONDecoder.api.decode(ReactionDTO.self, from: data)
        else { return }
        let conversationID = (json["conversation_id"] as? String).flatMap(UUID.init(uuidString:))
        let senderID = (json["message_sender_id"] as? String).flatMap(UUID.init(uuidString:))
        // The conversation names the chat directly; Notes (not in the list) falls back to a scan.
        let storePeerID = conversationID.flatMap { id in conversations.first(where: { $0.id == id })?.peer.id }
            ?? peerID(forMessage: dto.messageId)
        Task {
            if let storePeerID {
                await applyReactionChanges([dto], storePeerID: storePeerID)
            }
            noteReactionActivity(dto, storePeerID: storePeerID, messageSenderID: senderID)
        }
    }

    /// The other side reacted to (or took back a reaction on) one of our messages: the open chat
    /// marks it seen, any other chat's heart badge is re-read from the server.
    private func noteReactionActivity(_ dto: ReactionDTO, storePeerID: UUID?, messageSenderID: UUID?) {
        guard let me = sessionController?.userID,
              dto.userId != me,
              messageSenderID == nil || messageSenderID == me
        else { return }
        if let storePeerID, storePeerID == activePeerID {
            if let index = conversations.firstIndex(where: { $0.peer.id == storePeerID }),
               (conversations[index].reactionSeq ?? 0) < dto.seq
            {
                conversations[index].reactionSeq = dto.seq
            }
            markReactionsSeen(peerUserID: storePeerID, upTo: dto.seq)
        } else {
            refreshConversationsSoon()
        }
    }

    /// Another of our devices marked a chat's reactions seen.
    private func handleReactionsSeenEvent(_ json: [String: Any]) {
        guard let peerString = json["peer_user_id"] as? String,
              let peer = UUID(uuidString: peerString),
              let seen = (json["seen_seq"] as? NSNumber)?.int64Value
        else { return }
        reactionsSeenLocally[peer] = max(reactionsSeenLocally[peer] ?? 0, seen)
        let before = conversations
        conversations = applyingLocalReactionSeen(conversations)
        if conversations == before { refreshConversationsSoon() }
    }

    /// A chat has a heart badge: the other side reacted to our messages since we last looked.
    /// The open chat never shows one — it is being looked at.
    func hasUnseenReactions(for peerUserID: UUID) -> Bool {
        guard activePeerID != peerUserID else { return false }
        return hasPendingUnseenReactions(peerUserID)
    }

    private func hasPendingUnseenReactions(_ peerUserID: UUID) -> Bool {
        (conversations.first(where: { $0.peer.id == peerUserID })?.unseenReactions ?? 0) > 0
    }

    /// Clears the chat's heart badge here and asks the server to clear it everywhere.
    ///
    /// Agent: `upTo` defaults to the chat's latest known seq (the list's, or the catch-up cursor,
    /// whichever is further); the server clamps it and never moves it backwards.
    private func markReactionsSeen(peerUserID: UUID, upTo: Int64? = nil) {
        guard !isNotesChat(peerUserID), let token = sessionController?.bearerToken else { return }
        let listed = conversations.first(where: { $0.peer.id == peerUserID })?.reactionSeq ?? 0
        let seq = max(upTo ?? 0, listed, reactionCursors()[peerUserID] ?? 0)
        guard seq > 0 else { return }
        reactionsSeenLocally[peerUserID] = max(reactionsSeenLocally[peerUserID] ?? 0, seq)
        let cleared = applyingLocalReactionSeen(conversations)
        if cleared != conversations { conversations = cleared }
        let service = messagesService
        Task {
            _ = try? await service.markReactionsSeen(peerUserID: peerUserID, upToSeq: seq, token: token)
        }
    }

    /// Zeroes badges this device already marked seen, for a list that may predate the seen call.
    private func applyingLocalReactionSeen(_ list: [ConversationItemDTO]) -> [ConversationItemDTO] {
        guard !reactionsSeenLocally.isEmpty else { return list }
        return list.map { item in
            guard let seen = reactionsSeenLocally[item.peer.id],
                  (item.unseenReactions ?? 0) > 0,
                  (item.reactionSeq ?? 0) <= seen
            else { return item }
            var copy = item
            copy.unseenReactions = 0
            return copy
        }
    }

    /// One conversations refresh for a burst of reaction events.
    private func refreshConversationsSoon() {
        guard conversationsRefreshSoon == nil else { return }
        conversationsRefreshSoon = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(700))
            guard let self, !Task.isCancelled else { return }
            self.conversationsRefreshSoon = nil
            await self.refreshConversations(force: true)
        }
    }

    /// Batches the sealed thread save after reaction changes: rewriting a long thread per event
    /// is the expensive part, and events come in bursts.
    private func scheduleReactionPersist(_ peerID: UUID) {
        reactionPersistTasks[peerID]?.cancel()
        reactionPersistTasks[peerID] = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(600))
            guard let self, !Task.isCancelled else { return }
            self.reactionPersistTasks[peerID] = nil
            self.persistThread(peerID)
        }
    }

    private func flushReactionPersists() {
        let pending = reactionPersistTasks
        reactionPersistTasks = [:]
        for (peerID, task) in pending {
            task.cancel()
            persistThread(peerID)
        }
    }

    /// Empty (and not cached) while the file can't be read: catch-up then starts from zero,
    /// which re-applies but never loses anything.
    private func reactionCursors() -> [UUID: Int64] {
        if let reactionCursorCache { return reactionCursorCache }
        guard let loaded = local.reactionCursors(userID: sessionController?.userID) else { return [:] }
        reactionCursorCache = loaded
        return loaded
    }

    private func saveReactionCursor(_ seq: Int64, for storePeerID: UUID) {
        // Never write a map that wasn't read: it would reset every other chat's cursor.
        _ = reactionCursors()
        guard var cursors = reactionCursorCache, cursors[storePeerID] != seq else { return }
        cursors[storePeerID] = seq
        reactionCursorCache = cursors
        local.saveReactionCursors(cursors, userID: sessionController?.userID)
    }

    /// Brings reactions on messages this device already holds up to date after a refresh.
    ///
    /// Human: A history refresh only re-reads the newest page, so a reaction to an older message
    /// made while this device was away arrives here. A chat without a cursor starts from zero:
    /// the messages it holds from before reactions (or before this device first opened it) may
    /// have some.
    /// Agent: READS/WRITES reaction cursors; CALLS GET /conversations/{peer}/reactions.
    private func catchUpReactions(
        apiPeerID: UUID,
        storePeerID: UUID,
        newestSnapshot: Int64,
        token: String
    ) async {
        let start = reactionCursors()[storePeerID] ?? 0
        guard start < newestSnapshot else { return }
        var after = start
        // A long absence is walked a few pages per refresh; the next one carries on.
        for _ in 0 ..< 5 {
            guard let response = try? await messagesService.reactionChanges(
                peerUserID: apiPeerID,
                afterSeq: after,
                token: token
            ) else { break }
            if !response.reactions.isEmpty {
                await applyReactionChanges(response.reactions, storePeerID: storePeerID)
            }
            after = response.nextSeq
            if !response.hasMore { break }
        }
        // A page that landed meanwhile may have moved the cursor back (see `publishHistoryPage`);
        // its lower value wins, or the change it went back for would be skipped again.
        let current = reactionCursors()[storePeerID]
        if after > start, current == nil || current == start {
            saveReactionCursor(after, for: storePeerID)
        }
    }
}

private extension ISO8601DateFormatter {
    static let apiFlexible: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
}
