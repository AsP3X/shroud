package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.contacts.Contacts
import de.corespace.shroud.core.contacts.ContactsHooks
import de.corespace.shroud.core.contacts.PeerIdentities
import de.corespace.shroud.core.contacts.Privacy
import de.corespace.shroud.core.crypto.CryptoController
import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.links.LinkPreviewAttachment
import de.corespace.shroud.core.media.MediaComposeQuality
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.core.media.video.VideoSendPlan
import de.corespace.shroud.core.messaging.EnvelopeOpener
import de.corespace.shroud.core.messaging.HydratedMessages
import de.corespace.shroud.core.messaging.MediaLoader
import de.corespace.shroud.core.messaging.MessagingBackend
import de.corespace.shroud.core.messaging.MessagingController
import de.corespace.shroud.core.messaging.MessagingDependencies
import de.corespace.shroud.core.messaging.MessagingSnapshot
import de.corespace.shroud.core.messaging.MessagingSocket
import de.corespace.shroud.core.messaging.MessagingStore
import de.corespace.shroud.core.messaging.OwnKeys
import de.corespace.shroud.core.messaging.ReactionsEngine
import de.corespace.shroud.core.messaging.RosterSnapshot
import de.corespace.shroud.core.messaging.SendEngine
import de.corespace.shroud.core.model.AddContactOutcome
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ContactsListState
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.model.PeerIdentityChange
import de.corespace.shroud.core.model.PeerIdentityEvent
import de.corespace.shroud.core.model.ReactionFailure
import de.corespace.shroud.core.net.BlockItemDto
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.ContactRequestDto
import de.corespace.shroud.core.net.ConversationDeleteScope
import de.corespace.shroud.core.net.ConversationItemDto
import de.corespace.shroud.core.net.DeleteConversationResponse
import de.corespace.shroud.core.net.ListMessagesResponse
import de.corespace.shroud.core.net.MessageDeleteScope
import de.corespace.shroud.core.net.MuteChatResponse
import de.corespace.shroud.core.net.PresenceDto
import de.corespace.shroud.core.net.PrivacySettingsDto
import de.corespace.shroud.core.net.ReactionDto
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.core.net.UpdatePrivacySettingsBody
import de.corespace.shroud.core.net.wire.MessageReplyReference
import de.corespace.shroud.core.notifications.MessageNotifier
import de.corespace.shroud.core.notifications.NotificationKind
import de.corespace.shroud.core.realtime.RealtimeClient
import de.corespace.shroud.core.realtime.RealtimeEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

/**
 * Messaging engine (00-plan §1.7.7, C6). Owner: W2-MSG-CORE — the one [MessagingController] (chats,
 * threads, reads, mutes, typing, deletes; sends, media and reactions through the W2-MSG-SEND engines)
 * and its own engines (`ThreadStore`, `HistoryPager`, `MessageDecoder`, `DeleteEngine`,
 * `ReadStateEngine`, `TypingSignals`, `PollingLoop`).
 *
 * The parts of the other wave-2 packages are wired by W2-INT: each `Unwired*` stand-in below names
 * the expression that replaces it once those modules exist (00-plan §2.0 rule 3). Until then nothing
 * starts messaging (W2-INT's `ui/ShroudApp.kt` does), and the stand-ins never decrypt, send or store.
 *
 * W2-MSG-SEND's engines also take a `SendHost` (its contract change request CR-1): the controller
 * has every member it asks for — `conversations`, `activePeerId`, `refreshConversations(force)`,
 * `setOffline`, `isMuted`, `username`, `editConversations`, `foldSharedTranscripts` — so W2-INT sets
 * `container.messagingSend.host` to a `SendHost` delegating each member to [controller] (or adds
 * `SendHost` to the controller's supertypes, with `override` on those eight members).
 */
class MessagingModule(container: AppContainer) : AppModule(container) {
    /** The controller every UI area reads (plan §1.7.7). Built on first use, on the main thread. */
    val controller: MessagingController by lazy { MessagingController(dependencies()) }

    private fun dependencies(): MessagingDependencies {
        val keys = container.keys
        val realtime = container.realtime.client
        return MessagingDependencies(
            scope = container.appScope,
            session = container.auth.sessionController.session,
            backend = ShroudApiMessagingBackend(container.net.api),
            socket = RealtimeMessagingSocket(realtime, container),
            keys = CryptoOwnKeys(keys.cryptoController),
            opener = EnvelopeOpener(keys.messageCrypto::open),
            peerLocks = keys.peerLocks,
            store = UnwiredMessagingStore, // W2-INT: container.messagingStore.store
            hasMedia = { false }, // W2-INT: container.media.localMedia::has (LocalMediaStore.has)
            contacts = UnwiredContacts, // W2-INT: container.contacts.controller
            peerIdentities = UnwiredPeerIdentities, // W2-INT: container.contacts.peerIdentities
            privacy = UnwiredPrivacy, // W2-INT: container.contacts.privacy
            notifier = UnwiredNotifier, // W2-INT: W2-NOTIF's NotificationsController (its MessageNotifier)
            sendEngine = { UnwiredSendEngine }, // W2-INT: container.messagingSend::send
            reactionsEngine = { UnwiredReactionsEngine }, // W2-INT: container.messagingSend::reactions
            mediaLoader = { UnwiredMediaLoader }, // W2-INT: container.messagingSend::media
            isOnline = { container.net.connectivity.isOnline.value },
            isResumed = { container.appPhase.isResumed },
            wipeKeyRecords = {
                keys.ratchetSessions.deleteAll()
                keys.senderTags.deleteAll()
            },
            refreshCallSecrets = {}, // W2-INT: W2-CALLS-CORE's CallSecrets refresh of every contact (plan C29)
            clock = container.clock,
        )
    }
}

/** [MessagingBackend] over the one [ShroudApi] (plan C3). */
private class ShroudApiMessagingBackend(private val api: ShroudApi) : MessagingBackend {
    override suspend fun conversations(token: String): List<ConversationItemDto> = api.conversations(token)
    override suspend fun messages(token: String, peer: UUID, limit: Int, beforeCreatedAt: String?, beforeId: UUID?): ListMessagesResponse =
        api.messages(token, peer, limit, beforeCreatedAt, beforeId)
    override suspend fun markDelivered(token: String, messageId: UUID) = api.markDelivered(token, messageId)
    override suspend fun markChatRead(token: String, peer: UUID) {
        api.markChatRead(token, peer)
    }
    override suspend fun markReadBulk(token: String, peer: UUID, upToMessageId: UUID) {
        api.markReadBulk(token, peer, upToMessageId)
    }
    override suspend fun deleteMessage(token: String, messageId: UUID, scope: MessageDeleteScope) = api.deleteMessage(token, messageId, scope)
    override suspend fun deleteConversation(token: String, peer: UUID, scope: ConversationDeleteScope): DeleteConversationResponse =
        api.deleteConversation(token, peer, scope)
    override suspend fun muteChat(token: String, peer: UUID, seconds: Long?): MuteChatResponse = api.muteChat(token, peer, seconds)
    override suspend fun unmuteChat(token: String, peer: UUID) = api.unmuteChat(token, peer)
}

/** [MessagingSocket] over the process's one [RealtimeClient], holder `Messaging`. */
private class RealtimeMessagingSocket(private val client: RealtimeClient, container: AppContainer) : MessagingSocket {
    override val events: SharedFlow<RealtimeEvent> = client.events
    override val isConnected: StateFlow<Boolean> = client.state
        .map { it is RealtimeClient.ConnectionState.Connected }
        .stateIn(container.appScope, SharingStarted.Eagerly, client.isConnected)
    override fun hold(token: String) = client.hold(RealtimeClient.Holder.Messaging, token)
    override fun release() = client.release(RealtimeClient.Holder.Messaging)
    override fun noteFocus(focused: Boolean) = client.noteFocus(focused)
    override suspend fun deliverFocus(): Boolean = client.deliverFocus()
    override fun sendTyping(peer: UUID, isTyping: Boolean) = client.sendTyping(peer, isTyping)
    override fun sendRecording(peer: UUID, isRecording: Boolean) = client.sendRecording(peer, isRecording)
}

/** [OwnKeys] over [CryptoController.withMaterial]: the arrays never leave the block. */
private class CryptoOwnKeys(private val crypto: CryptoController) : OwnKeys {
    override val isUnlocked: Boolean get() = crypto.isUnlocked
    override fun <T> withKeys(block: (ourPrivate: ByteArray, ourPublic: ByteArray) -> T): T? =
        crypto.withMaterial { block(it.agreementPrivateKey, it.identityPublicKey) }
}

// ---- Stand-ins until W2-INT wires the other wave-2 packages -------------------------------------
// They keep the module buildable and inert: nothing is stored, sent, decrypted against an unpinned
// key or announced. Each names its replacement in `dependencies()` above.

private object UnwiredMessagingStore : MessagingStore {
    private val emptyRoster = RosterSnapshot(emptyList(), emptyList(), emptyList(), emptyMap())
    override fun hydrate(userId: UUID) = HydratedMessages(emptyRoster, mapOf(NOTES_PEER_ID to emptyList()))
    override fun persist(userId: UUID, snapshot: MessagingSnapshot): Set<UUID> = emptySet()
    override fun persistThread(userId: UUID, storePeer: UUID, messages: List<ChatMessage>, roster: RosterSnapshot): Set<UUID> = emptySet()
    override suspend fun flush() = Unit
    override fun plaintext(messageId: UUID): ByteArray? = null
    override fun savePlaintext(messageId: UUID, bytes: ByteArray) = Unit
    override fun removeCaches(messageIds: Collection<UUID>) = Unit
    override fun noteAnnotation(targetId: UUID, annotationId: UUID) = Unit
    override fun annotationsFor(targetId: UUID): Set<UUID> = emptySet()
    override fun reactionCursors(userId: UUID): Map<UUID, Long>? = null
    override fun saveReactionCursors(userId: UUID, cursors: Map<UUID, Long>) = Unit
    override fun lockSensitiveMemory() = Unit
    override fun clear(userId: UUID?) = Unit
}

private object UnwiredContacts : Contacts {
    override val contacts: StateFlow<List<ContactItemDto>> = MutableStateFlow(emptyList())
    override val incomingRequests: StateFlow<List<ContactRequestDto>> = MutableStateFlow(emptyList())
    override val listState: StateFlow<ContactsListState> = MutableStateFlow(ContactsListState())
    override val presence: StateFlow<Map<UUID, PresenceDto>> = MutableStateFlow(emptyMap())
    override val blocked: StateFlow<List<BlockItemDto>> = MutableStateFlow(emptyList())
    override val rosterChanges: SharedFlow<Unit> = MutableSharedFlow()
    override val pendingInvite: MutableStateFlow<String?> = MutableStateFlow(null)
    override fun username(of: UUID): String? = null
    override suspend fun refresh(force: Boolean) = Unit
    override suspend fun refreshPresence(userIds: Collection<UUID>) = Unit
    override suspend fun add(invite: String): AddContactOutcome = AddContactOutcome.Failed("Not signed in.")
    override suspend fun accept(request: ContactRequestDto): String? = "Not signed in."
    override suspend fun reject(request: ContactRequestDto): String? = "Not signed in."
    override suspend fun refreshBlocks() = Unit
    override suspend fun block(userId: UUID, username: String): String? = "Sign in to block contacts."
    override suspend fun unblock(userId: UUID): String? = "Sign in to manage blocked contacts."
    override fun bind(hooks: ContactsHooks) = Unit
    override fun hydrate(contacts: List<ContactItemDto>, requests: List<ContactRequestDto>) = Unit
    override fun start() = Unit
    override fun onForeground() = Unit
    override fun onBackground() = Unit
    override fun onConnectivityRegained() = Unit
    override fun stop(wipe: Boolean) = Unit
}

/** Never trusts a server key unpinned: every lookup is refused, so nothing is decrypted or sealed. */
private object UnwiredPeerIdentities : PeerIdentities {
    override val identityChanges: StateFlow<Map<UUID, PeerIdentityChange>> = MutableStateFlow(emptyMap())
    override val verifiedPeers: StateFlow<Set<UUID>> = MutableStateFlow(emptySet())
    override val events: SharedFlow<PeerIdentityEvent> = MutableSharedFlow()
    override suspend fun resolvePublicKey(peer: UUID): ByteArray = throw CryptoError.Locked
    override suspend fun publicKeyForSending(peer: UUID): ByteArray = throw CryptoError.Locked
    override suspend fun refresh(peer: UUID) = Unit
    override fun identityChange(peer: UUID): PeerIdentityChange? = null
    override fun isSafetyVerified(peer: UUID): Boolean = false
    override fun confirmSafety(peer: UUID) = Unit
    override fun safetyNumber(peer: UUID): String? = null
    override fun acceptNewIdentity(peer: UUID) = Unit
    override fun clearMemory() = Unit
    override fun wipe() = Unit
}

private object UnwiredPrivacy : Privacy {
    override val settings: StateFlow<PrivacySettingsDto> = MutableStateFlow(PrivacySettingsDto(allowPeerChatDelete = false))
    override val hasLoaded: StateFlow<Boolean> = MutableStateFlow(false)
    override suspend fun refresh() = Unit
    override suspend fun update(change: UpdatePrivacySettingsBody): String? = "Sign in to change privacy settings."
    override suspend fun setAllowsPeerChatDelete(value: Boolean): String? = "Sign in to change privacy settings."
    override suspend fun rotateShareCode(): String? = "Sign in to reset your QR code."
    override fun reset() = Unit
}

private object UnwiredNotifier : MessageNotifier {
    override var activePeerId: UUID? = null
    override fun announce(kind: NotificationKind, peerUserId: UUID?, username: String?, conversationId: UUID?, text: String?, muted: Boolean) = Unit
    override fun clearDelivered(conversationId: UUID) = Unit
    override fun setBadge(count: Int) = Unit
    override fun setPushCoversBackground(covers: Boolean) = Unit
    override val badgeIncludesMuted: Boolean = false
}

private object UnwiredSendEngine : SendEngine {
    private const val UNAVAILABLE = "Something went wrong. Try again."
    override suspend fun sendText(text: String, storePeer: UUID, replyTo: MessageReplyReference?, linkPreview: LinkPreviewAttachment?) = Unit
    override fun sendTodo(text: String) = Unit
    override fun toggleTodo(messageId: UUID) = Unit
    override fun deleteLocalNote(messageId: UUID) = Unit
    override suspend fun sendImage(
        source: MediaImageSource,
        storePeer: UUID,
        caption: String,
        quality: MediaComposeQuality,
        edits: MediaEdits,
        replyTo: MessageReplyReference?,
    ): String? = UNAVAILABLE
    override suspend fun sendVideo(plan: VideoSendPlan, storePeer: UUID, replyTo: MessageReplyReference?): String? = UNAVAILABLE
    override suspend fun sendVoice(
        audio: ByteArray,
        durationMs: Int,
        storePeer: UUID,
        waveform: ByteArray?,
        transcript: String?,
        replyTo: MessageReplyReference?,
        transcriptProvider: (suspend (messageId: UUID) -> String?)?,
    ): String? = UNAVAILABLE
    override suspend fun retryFailedImage(messageId: UUID, storePeer: UUID): String? = UNAVAILABLE
    override suspend fun retryFailedVideo(messageId: UUID, storePeer: UUID): String? = UNAVAILABLE
    override suspend fun shareTranscript(transcript: String, voiceMessageId: UUID, storePeer: UUID) = Unit
    override suspend fun flushOutbox() = Unit
}

private object UnwiredReactionsEngine : ReactionsEngine {
    override val limit: StateFlow<Int> = MutableStateFlow(5)
    override val revisions: StateFlow<Map<UUID, Int>> = MutableStateFlow(emptyMap())
    override val failures: SharedFlow<ReactionFailure> = MutableSharedFlow()
    override fun canReact(message: ChatMessage): Boolean = false
    override fun myReactions(message: ChatMessage): List<String> = emptyList()
    override fun toggle(emoji: String, messageId: UUID, storePeer: UUID) = Unit
    override fun set(emojis: List<String>, messageId: UUID, storePeer: UUID) = Unit
    override fun hasUnseen(storePeer: UUID): Boolean = false
    override suspend fun refreshServerConfig() = Unit
    override fun applyPage(storePeer: UUID, reactions: List<ReactionDto>, snapshotSeq: Long?) = Unit
    override fun apply(event: RealtimeEvent.MessageReaction) = Unit
    override fun apply(event: RealtimeEvent.ReactionsSeen) = Unit
    override suspend fun catchUp(storePeer: UUID) = Unit
    override suspend fun flushPendingSaves() = Unit
    override fun reset() = Unit
}

private object UnwiredMediaLoader : MediaLoader {
    override suspend fun ensureImageLoaded(message: ChatMessage) = Unit
    override suspend fun ensureVideoLoaded(message: ChatMessage) = Unit
    override suspend fun ensureVoiceLoaded(message: ChatMessage) = Unit
    override suspend fun ensureLinkImageLoaded(message: ChatMessage) = Unit
    override fun cancel(messageId: UUID) = Unit
    override fun cancelAll() = Unit
    override suspend fun mediaBytes(messageId: UUID): ByteArray? = null
}
