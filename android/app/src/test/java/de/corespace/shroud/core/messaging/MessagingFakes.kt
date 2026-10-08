package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.contacts.Contacts
import de.corespace.shroud.core.contacts.ContactsHooks
import de.corespace.shroud.core.contacts.PeerIdentities
import de.corespace.shroud.core.contacts.Privacy
import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.crypto.OpenAs
import de.corespace.shroud.core.links.LinkPreviewAttachment
import de.corespace.shroud.core.media.MediaComposeQuality
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.files.PickedFile
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.core.media.video.VideoSendPlan
import de.corespace.shroud.core.model.AddContactOutcome
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ContactsListState
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.model.PeerIdentityChange
import de.corespace.shroud.core.model.PeerIdentityEvent
import de.corespace.shroud.core.model.ReactionFailure
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.BlockItemDto
import de.corespace.shroud.core.net.ChatMuteDto
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.ContactRequestDto
import de.corespace.shroud.core.net.ContentType
import de.corespace.shroud.core.net.ConversationDeleteScope
import de.corespace.shroud.core.net.ConversationItemDto
import de.corespace.shroud.core.net.ConversationPeerDto
import de.corespace.shroud.core.net.DeleteConversationResponse
import de.corespace.shroud.core.net.ListMessagesResponse
import de.corespace.shroud.core.net.MessageDeleteScope
import de.corespace.shroud.core.net.MessageDto
import de.corespace.shroud.core.net.MuteChatResponse
import de.corespace.shroud.core.net.PresenceDto
import de.corespace.shroud.core.net.PrivacySettingsDto
import de.corespace.shroud.core.net.ReactionDto
import de.corespace.shroud.core.net.UpdatePrivacySettingsBody
import de.corespace.shroud.core.net.wire.MessageReplyReference
import de.corespace.shroud.core.notifications.MessageNotifier
import de.corespace.shroud.core.notifications.NotificationKind
import de.corespace.shroud.core.realtime.RealtimeEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.time.Instant
import java.util.UUID

// Fakes for the messaging-core tests. Every one records what it was asked, for assertions, and
// none prints content.

/** Builds server DTOs like the server sends them. */
object Dtos {
    fun message(
        id: UUID = UUID.randomUUID(),
        sender: UUID,
        conversation: UUID = UUID.randomUUID(),
        createdAtWire: String = "2026-09-24T12:00:00.123456Z",
        contentType: String = ContentType.TEXT,
        ciphertext: String? = B64.encode("hello".toByteArray()),
        mediaObjectId: UUID? = null,
        deleted: Boolean = false,
        delivered: Boolean? = null,
        read: Boolean? = null,
        reactions: List<ReactionDto>? = null,
    ) = MessageDto(
        id = id,
        conversationId = conversation,
        senderUserId = sender,
        senderDeviceId = UUID.randomUUID(),
        clientMessageId = UUID.randomUUID(),
        contentType = contentType,
        ciphertext = if (deleted) null else ciphertext,
        mediaObjectId = mediaObjectId,
        deletedForEveryone = deleted,
        createdAtWire = createdAtWire,
        delivered = delivered,
        read = read,
        reactions = reactions,
    )

    /** A fake "envelope" that [FakeOpener] opens to [plaintext]. */
    fun sealed(plaintext: String): String = B64.encode(plaintext.toByteArray())

    fun conversation(peer: UUID, id: UUID = UUID.randomUUID(), username: String = "bob", unread: Int? = 0, last: Instant? = null, mute: ChatMuteDto? = null) =
        ConversationItemDto(
            id = id,
            peer = ConversationPeerDto(peer, username),
            createdAt = Instant.parse("2026-09-01T10:00:00Z"),
            lastMessageAt = last,
            unreadCount = unread,
            mute = mute,
        )
}

/** Opens a fake envelope to its own bytes; an envelope starting with `FAIL` does not open. Counts every open. */
class FakeOpener : EnvelopeOpener {
    data class Call(val peerUserId: UUID, val senderPublic: ByteArray, val role: OpenAs)

    val calls = ArrayList<Call>()

    override fun open(
        envelope: ByteArray,
        peerUserId: UUID,
        ourPrivate: ByteArray,
        ourPublic: ByteArray,
        senderPublic: ByteArray,
        role: OpenAs,
    ): ByteArray {
        calls += Call(peerUserId, senderPublic.copyOf(), role)
        if (String(envelope).startsWith("FAIL")) throw CryptoError.OpenFailed
        return envelope.copyOf()
    }
}

class FakeKeys(var unlocked: Boolean = true) : OwnKeys {
    val privateKey = ByteArray(32) { 1 }
    val publicKey = ByteArray(32) { 2 }
    override val isUnlocked: Boolean get() = unlocked
    override fun <T> withKeys(block: (ourPrivate: ByteArray, ourPublic: ByteArray) -> T): T? =
        if (unlocked) block(privateKey, publicKey) else null
}

/** The sealed store in memory. */
class FakeMessagingStore : MessagingStore {
    val plaintexts = HashMap<UUID, ByteArray>()
    val savedPlaintext = ArrayList<UUID>()
    val removedCaches = ArrayList<Collection<UUID>>()
    val annotations = HashMap<UUID, MutableSet<UUID>>()
    val persisted = ArrayList<MessagingSnapshot>()
    val persistedThreads = ArrayList<Pair<UUID, List<ChatMessage>>>()
    var hydrated: HydratedMessages = HydratedMessages(RosterSnapshot(emptyList(), emptyList(), emptyList(), emptyMap()), mapOf(NOTES_PEER_ID to emptyList()))
    var pruneOnPersist: Set<UUID> = emptySet()
    var lockCount = 0
    var flushCount = 0
    val cleared = ArrayList<UUID?>()
    var cursors: Map<UUID, Long>? = emptyMap()
    val events = ArrayList<String>()

    override fun hydrate(userId: UUID): HydratedMessages {
        events += "hydrate"
        return hydrated
    }

    override fun persist(userId: UUID, snapshot: MessagingSnapshot): Set<UUID> {
        events += "persist"
        persisted += snapshot
        return pruneOnPersist
    }

    override fun persistThread(userId: UUID, storePeer: UUID, messages: List<ChatMessage>, roster: RosterSnapshot): Set<UUID> {
        events += "persistThread"
        persistedThreads += storePeer to messages
        return pruneOnPersist
    }

    override suspend fun flush() {
        events += "flush"
        flushCount++
    }

    /** The sender each entry was saved for; an entry a test put in [plaintexts] by hand fits any sender. */
    val plaintextSenders = HashMap<UUID, UUID>()

    override fun plaintext(messageId: UUID, senderUserId: UUID): ByteArray? =
        plaintexts[messageId]?.takeIf { plaintextSenders[messageId].let { it == null || it == senderUserId } }?.copyOf()

    /** A test's by-hand entry for [messageId], bound to [senderUserId] like a real save. */
    fun savePlaintext(messageId: UUID, bytes: ByteArray) {
        plaintexts[messageId] = bytes.copyOf()
        plaintextSenders.remove(messageId)
    }

    override fun savePlaintext(messageId: UUID, senderUserId: UUID, bytes: ByteArray) {
        plaintexts[messageId] = bytes.copyOf()
        plaintextSenders[messageId] = senderUserId
        savedPlaintext += messageId
    }

    /** Test shorthand: the entry whoever sent it. */
    fun plaintext(messageId: UUID): ByteArray? = plaintexts[messageId]?.copyOf()

    override fun removeCaches(messageIds: Collection<UUID>) {
        events += "removeCaches"
        removedCaches += messageIds.toList()
        for (id in messageIds) plaintexts.remove(id)
    }

    override fun noteAnnotation(targetId: UUID, annotationId: UUID) {
        annotations.getOrPut(targetId) { HashSet() } += annotationId
    }

    override fun annotationsFor(targetId: UUID): Set<UUID> = annotations[targetId].orEmpty()

    override fun reactionCursors(userId: UUID): Map<UUID, Long>? = cursors

    override fun saveReactionCursors(userId: UUID, cursors: Map<UUID, Long>) {
        this.cursors = cursors
    }

    override fun lockSensitiveMemory() {
        events += "lockSensitiveMemory"
        lockCount++
    }

    override fun clear(userId: UUID?) {
        events += "clear"
        cleared += userId
        plaintexts.clear()
    }

    /** Every id whose caches were removed, flattened. */
    val removedIds: List<UUID> get() = removedCaches.flatten()
}

/** A scripted server. Each `messages` call goes to [pages]; a [gate] holds the next call until completed. */
class FakeBackend : MessagingBackend {
    data class PageRequest(val peer: UUID, val limit: Int, val beforeCreatedAt: String?, val beforeId: UUID?)

    var conversationList: List<ConversationItemDto> = emptyList()
    var conversationsError: Exception? = null
    var conversationsCalls = 0
    var conversationsGate: CompletableDeferred<Unit>? = null

    val pageRequests = ArrayList<PageRequest>()
    var pages: (PageRequest) -> ListMessagesResponse = { ListMessagesResponse(messages = emptyList(), hasMore = false) }
    var pagesError: Exception? = null
    var gate: CompletableDeferred<Unit>? = null

    val delivered = ArrayList<UUID>()
    val chatReads = ArrayList<UUID>()
    var chatReadError: Exception? = null
    val bulkReads = ArrayList<Pair<UUID, UUID>>()
    val deletedMessages = ArrayList<Pair<UUID, MessageDeleteScope>>()
    var deleteMessageError: Exception? = null
    val deletedConversations = ArrayList<Pair<UUID, ConversationDeleteScope>>()
    var deleteConversationResponse = DeleteConversationResponse(clearedForMe = true, clearedForPeer = false, tombstoned = 0)
    var deleteConversationError: Exception? = null
    val mutes = ArrayList<Pair<UUID, Long?>>()
    var muteError: Exception? = null
    var muteUntil: Instant? = null
    val unmutes = ArrayList<UUID>()

    override suspend fun conversations(token: String): List<ConversationItemDto> {
        conversationsCalls++
        conversationsGate?.let { it.await(); conversationsGate = null }
        conversationsError?.let { throw it }
        return conversationList
    }

    override suspend fun messages(token: String, peer: UUID, limit: Int, beforeCreatedAt: String?, beforeId: UUID?): ListMessagesResponse {
        val request = PageRequest(peer, limit, beforeCreatedAt, beforeId)
        pageRequests += request
        gate?.let { it.await(); gate = null }
        pagesError?.let { throw it }
        return pages(request)
    }

    override suspend fun markDelivered(token: String, messageId: UUID) {
        delivered += messageId
    }

    override suspend fun markChatRead(token: String, peer: UUID) {
        chatReads += peer
        chatReadError?.let { throw it }
    }

    override suspend fun markReadBulk(token: String, peer: UUID, upToMessageId: UUID) {
        bulkReads += peer to upToMessageId
    }

    override suspend fun deleteMessage(token: String, messageId: UUID, scope: MessageDeleteScope) {
        deletedMessages += messageId to scope
        deleteMessageError?.let { throw it }
    }

    override suspend fun deleteConversation(token: String, peer: UUID, scope: ConversationDeleteScope): DeleteConversationResponse {
        deletedConversations += peer to scope
        deleteConversationError?.let { throw it }
        return deleteConversationResponse
    }

    override suspend fun muteChat(token: String, peer: UUID, seconds: Long?): MuteChatResponse {
        mutes += peer to seconds
        muteError?.let { throw it }
        return MuteChatResponse(peer, ChatMuteDto(until = muteUntil))
    }

    override suspend fun unmuteChat(token: String, peer: UUID) {
        unmutes += peer
        muteError?.let { throw it }
    }

    companion object {
        fun notFound() = ApiError.Server("NOT_FOUND", "Not found.", 404)
        fun offline() = ApiError.Transport("The Internet connection appears to be offline.")
    }
}

class FakeSocket : MessagingSocket {
    val eventsFlow = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<RealtimeEvent> = eventsFlow
    val connected = MutableStateFlow(true)
    override val isConnected: StateFlow<Boolean> = connected
    val log = ArrayList<String>()
    var holds = 0
    var releases = 0
    var focus: Boolean? = null
    val typingFrames = ArrayList<Pair<UUID, Boolean>>()
    val recordingFrames = ArrayList<Pair<UUID, Boolean>>()

    override fun hold(token: String) {
        holds++
        log += "hold"
    }

    override fun release() {
        releases++
        log += "release"
    }

    override fun noteFocus(focused: Boolean) {
        focus = focused
        log += "noteFocus:$focused"
    }

    override suspend fun deliverFocus(): Boolean {
        log += "deliverFocus"
        return connected.value
    }

    override fun sendTyping(peer: UUID, isTyping: Boolean) {
        typingFrames += peer to isTyping
    }

    override fun sendRecording(peer: UUID, isRecording: Boolean) {
        recordingFrames += peer to isRecording
    }
}

class FakeNotifier : MessageNotifier {
    data class Announcement(val kind: NotificationKind, val peer: UUID?, val username: String?, val conversationId: UUID?, val text: String?, val muted: Boolean)

    override var activePeerId: UUID? = null
    val announcements = ArrayList<Announcement>()
    val cleared = ArrayList<UUID>()
    val badges = ArrayList<Int>()
    val pushCovers = ArrayList<Boolean>()
    override var badgeIncludesMuted: Boolean = false

    override fun announce(kind: NotificationKind, peerUserId: UUID?, username: String?, conversationId: UUID?, text: String?, muted: Boolean) {
        announcements += Announcement(kind, peerUserId, username, conversationId, text, muted)
    }

    override fun clearDelivered(conversationId: UUID) {
        cleared += conversationId
    }

    override fun setBadge(count: Int) {
        badges += count
    }

    override fun setPushCoversBackground(covers: Boolean) {
        pushCovers += covers
    }

    /** Each `closeSettledChats` call: (read chats, reactions-seen chats). */
    val settled = ArrayList<Pair<Set<UUID>, Set<UUID>>>()

    override fun closeSettledChats(readChats: Collection<UUID>, reactionsSeenChats: Collection<UUID>) {
        settled += readChats.toSet() to reactionsSeenChats.toSet()
    }
}

class FakeContacts : Contacts {
    override val contacts = MutableStateFlow<List<ContactItemDto>>(emptyList())
    override val incomingRequests = MutableStateFlow<List<ContactRequestDto>>(emptyList())
    override val listState = MutableStateFlow(ContactsListState())
    override val presence = MutableStateFlow<Map<UUID, PresenceDto>>(emptyMap())
    override val blocked = MutableStateFlow<List<BlockItemDto>>(emptyList())
    override val rosterChanges = MutableSharedFlow<Unit>()
    override val pendingInvite = MutableStateFlow<String?>(null)
    val log = ArrayList<String>()
    var hooks: ContactsHooks? = null
    val presenceRefreshes = ArrayList<Collection<UUID>>()

    override fun username(of: UUID): String? = contacts.value.firstOrNull { it.userId == of }?.username
    override suspend fun refresh(force: Boolean) {
        log += "refresh:$force"
    }
    override suspend fun refreshPresence(userIds: Collection<UUID>) {
        presenceRefreshes += userIds
    }
    override suspend fun add(invite: String): AddContactOutcome = AddContactOutcome.Failed("unused")
    override suspend fun accept(request: ContactRequestDto): String? = null
    override suspend fun reject(request: ContactRequestDto): String? = null
    override suspend fun refreshBlocks() = Unit
    override suspend fun block(userId: UUID, username: String): String? = null
    override suspend fun unblock(userId: UUID): String? = null
    override fun bind(hooks: ContactsHooks) {
        this.hooks = hooks
    }
    override fun hydrate(contacts: List<ContactItemDto>, requests: List<ContactRequestDto>) {
        log += "hydrate:${contacts.size}"
    }
    override fun start() {
        log += "start"
    }
    override fun onForeground() {
        log += "foreground"
    }
    override fun onBackground() {
        log += "background"
    }
    override fun onConnectivityRegained() {
        log += "regained"
    }
    override fun stop(wipe: Boolean) {
        log += "stop:$wipe"
    }
}

class FakePeerIdentities : PeerIdentities {
    override val identityChanges = MutableStateFlow<Map<UUID, PeerIdentityChange>>(emptyMap())
    override val verifiedPeers = MutableStateFlow<Set<UUID>>(emptySet())
    override val events = MutableSharedFlow<PeerIdentityEvent>()
    val keys = HashMap<UUID, ByteArray>()
    val resolved = ArrayList<UUID>()
    var gate: CompletableDeferred<Unit>? = null
    var cleared = 0
    var wiped = 0

    override suspend fun resolvePublicKey(peer: UUID): ByteArray {
        resolved += peer
        gate?.await()
        return keys[peer]?.copyOf() ?: ByteArray(32) { 7 }
    }
    override suspend fun publicKeyForSending(peer: UUID): ByteArray = resolvePublicKey(peer)
    override suspend fun refresh(peer: UUID) = Unit
    override fun identityChange(peer: UUID): PeerIdentityChange? = null
    override fun isSafetyVerified(peer: UUID): Boolean = false
    override fun confirmSafety(peer: UUID) = Unit
    override fun safetyNumber(peer: UUID): String? = null
    override fun acceptNewIdentity(peer: UUID) = Unit
    override fun clearMemory() {
        cleared++
    }
    override fun wipe() {
        wiped++
    }
}

class FakePrivacy : Privacy {
    override val settings = MutableStateFlow(PrivacySettingsDto(allowPeerChatDelete = false))
    override val hasLoaded = MutableStateFlow(false)
    var refreshes = 0
    var resets = 0
    override suspend fun refresh() {
        refreshes++
    }
    override suspend fun update(change: UpdatePrivacySettingsBody): String? = null
    override suspend fun setAllowsPeerChatDelete(value: Boolean): String? = null
    override suspend fun rotateShareCode(): String? = null
    override fun reset() {
        resets++
    }
}

class FakeSendEngine : SendEngine {
    val log = ArrayList<String>()
    override suspend fun sendText(text: String, storePeer: UUID, replyTo: MessageReplyReference?, linkPreview: LinkPreviewAttachment?) {
        log += "sendText"
    }
    override fun sendTodo(text: String) {
        log += "sendTodo"
    }
    override fun toggleTodo(messageId: UUID) {
        log += "toggleTodo"
    }
    override fun deleteLocalNote(messageId: UUID) {
        log += "deleteLocalNote"
    }
    override suspend fun sendImage(source: MediaImageSource, storePeer: UUID, caption: String, quality: MediaComposeQuality, edits: MediaEdits, replyTo: MessageReplyReference?): String? = null
    override suspend fun sendVideo(plan: VideoSendPlan, storePeer: UUID, replyTo: MessageReplyReference?): String? = null
    override suspend fun sendVoice(
        audio: ByteArray,
        durationMs: Int,
        storePeer: UUID,
        waveform: ByteArray?,
        transcript: String?,
        replyTo: MessageReplyReference?,
        transcriptProvider: (suspend (messageId: UUID) -> String?)?,
    ): String? = null
    override suspend fun sendFile(file: PickedFile, storePeer: UUID, caption: String, replyTo: MessageReplyReference?): String? = null
    override suspend fun retryFailedImage(messageId: UUID, storePeer: UUID): String? = null
    override suspend fun retryFailedVideo(messageId: UUID, storePeer: UUID): String? = null
    override suspend fun retryFailedFile(messageId: UUID, storePeer: UUID): String? = null
    override suspend fun shareTranscript(transcript: String, voiceMessageId: UUID, storePeer: UUID) = Unit
    override suspend fun flushOutbox() {
        log += "flushOutbox"
    }
}

class FakeReactionsEngine : ReactionsEngine {
    override val limit = MutableStateFlow(5)
    override val revisions = MutableStateFlow<Map<UUID, Int>>(emptyMap())
    override val failures = MutableSharedFlow<ReactionFailure>()
    val log = ArrayList<String>()
    val pages = ArrayList<Triple<UUID, List<ReactionDto>, Long?>>()
    val shown = ArrayList<UUID>()
    val catchUps = ArrayList<UUID>()

    override fun canReact(message: ChatMessage): Boolean = !message.deleted
    override fun myReactions(message: ChatMessage): List<String> = emptyList()
    override fun toggle(emoji: String, messageId: UUID, storePeer: UUID) {
        log += "toggle"
    }
    override fun set(emojis: List<String>, messageId: UUID, storePeer: UUID) {
        log += "set"
    }
    override fun hasUnseen(storePeer: UUID): Boolean = false
    override suspend fun refreshServerConfig() {
        log += "refreshServerConfig"
    }
    override fun applyPage(storePeer: UUID, reactions: List<ReactionDto>, snapshotSeq: Long?) {
        pages += Triple(storePeer, reactions, snapshotSeq)
    }
    override fun apply(event: RealtimeEvent.MessageReaction) {
        log += "apply:reaction"
    }
    override fun apply(event: RealtimeEvent.ReactionsSeen) {
        log += "apply:seen"
    }
    override suspend fun catchUp(storePeer: UUID) {
        catchUps += storePeer
    }
    override suspend fun flushPendingSaves() {
        log += "flushPendingSaves"
    }
    override fun reset() {
        log += "reset"
    }
    override fun markSeen(storePeer: UUID, upTo: Long?) {
        shown += storePeer
    }

    /** Chats this device marked seen: their badge reads zero in a list that predates it. */
    val seenLocally = HashSet<UUID>()
    override fun applyingLocalSeen(list: List<ConversationItemDto>): List<ConversationItemDto> =
        list.map { if (it.peer.id in seenLocally && (it.unseenReactions ?: 0) > 0) it.copy(unseenReactions = 0) else it }

    /** Our pending entries as the server confirmed them: here, simply without the pending ones. */
    override fun settled(messages: List<ChatMessage>): List<ChatMessage> =
        messages.map { message -> if (message.reactions.any { it.pending }) message.copy(reactions = message.reactions.filterNot { it.pending }) else message }
}

class FakeMediaLoader : MediaLoader {
    val cancelled = ArrayList<UUID>()
    var cancelledAll = 0
    override suspend fun ensureImageLoaded(message: ChatMessage) = Unit
    override suspend fun ensureVideoLoaded(message: ChatMessage) = Unit
    override suspend fun ensureVoiceLoaded(message: ChatMessage) = Unit
    override suspend fun ensureLinkImageLoaded(message: ChatMessage) = Unit
    override suspend fun ensureFileLoaded(message: ChatMessage) = Unit
    override fun cancel(messageId: UUID) {
        cancelled += messageId
    }
    override fun cancelAll() {
        cancelledAll++
    }
    override suspend fun mediaBytes(messageId: UUID): ByteArray? = null
}

/** A session for [userId]. */
fun testSession(userId: UUID, username: String = "alice"): Session =
    Session(token = "token-1", userId = userId.toString(), username = username, shareCode = null, deviceId = UUID.randomUUID().toString())

/** A sink that records what it heard. */
class RecordingSink : MessageArtifactSinks {
    val purged = ArrayList<UUID>()
    var locked = 0
    val rekeyed = ArrayList<Pair<UUID, UUID>>()
    override fun onPurged(messageIds: Collection<UUID>) {
        purged += messageIds
    }
    override fun onSensitiveMemoryLocked() {
        locked++
    }
    override fun onMessageRekeyed(from: UUID, to: UUID) {
        rekeyed += from to to
    }
}

/**
 * Scopes for the engines on the test's scheduler. Unlike `backgroundScope`, their work counts as
 * foreground, so `advanceUntilIdle` also runs their delayed jobs (read markers, holds, debounces).
 * Cancel them after each test ([cancelAll]); a loop that never ends (the poll) must be stopped first.
 */
class EngineScopes {
    private val scopes = ArrayList<CoroutineScope>()

    fun create(scheduler: TestCoroutineScheduler): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(scheduler) + SupervisorJob()).also { scopes += it }

    fun cancelAll() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }
}
