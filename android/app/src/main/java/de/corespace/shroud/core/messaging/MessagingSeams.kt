package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.links.LinkPreviewAttachment
import de.corespace.shroud.core.media.MediaComposeQuality
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.core.media.video.VideoSendPlan
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.core.model.ReactionFailure
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.ContactRequestDto
import de.corespace.shroud.core.net.ConversationItemDto
import de.corespace.shroud.core.net.ReactionDto
import de.corespace.shroud.core.net.wire.MessageReplyReference
import de.corespace.shroud.core.realtime.RealtimeEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.util.UUID

// The seams between the messaging engines (plan §1.7.7). Published by W1-INT with the final
// signatures; W2-MSG-CORE (ThreadStore, MessagingController), W2-MSG-STORE (MessagingStore) and
// W2-MSG-SEND (SendEngine, ReactionsEngine, MediaLoader) implement them. Changing one is a contract
// change request (plan §2.0 rule 4).
//
// Rules every engine keeps (messaging-core §11.1, §23): a sent message is re-keyed to the server id
// through ThreadState.rekey on every send path (Notes included); retries reuse
// client_message_id = optimisticId; message.read events whose user_id is our own are ignored;
// tombstones purge plaintext + media + annotations once; nothing publishes into state after its
// lockGeneration changed.

/**
 * UI caches register here to hear about purges, locks and re-keys: thread caches, voice playback,
 * transcript disclosure (`MessagingController.registerArtifactSink`). Every method has a no-op
 * default, so a sink overrides only what it holds.
 */
interface MessageArtifactSinks {
    /** These messages are gone (tombstones, chat deletes, retention): drop anything derived from them. */
    fun onPurged(messageIds: Collection<UUID>) {}

    /** Chats locked: drop decoded images, transcripts and anything else readable from memory. */
    fun onSensitiveMemoryLocked() {}

    /** An optimistic message became the server's: move whatever was keyed by [from] to [to]. */
    fun onMessageRekeyed(from: UUID, to: UUID) {}
}

/**
 * Main-confined state shared by the engines; implemented by `ThreadStore` (W2-MSG-CORE). Every write
 * is equality-guarded before it publishes (plan §1.1 rule 3); no read-modify-write spans a
 * suspension.
 */
interface ThreadState {
    val session: Session?
    val myUserId: UUID?

    /** Bumped by every lock; engines drop results that started before it changed. */
    val lockGeneration: Long

    /** `storePeer → messages`, oldest first. */
    val threads: StateFlow<Map<UUID, List<ChatMessage>>>

    fun messages(storePeer: UUID): List<ChatMessage>?

    /** Replaces one thread through [transform]; publishes only when the result differs. */
    fun edit(storePeer: UUID, transform: (List<ChatMessage>) -> List<ChatMessage>)

    /** Updates one message wherever it lives; false when no thread holds it. */
    fun update(messageId: UUID, transform: (ChatMessage) -> ChatMessage): Boolean

    /**
     * The server re-keyed an optimistic message (memory: *Server re-keys sent messages*): replace it
     * with [sent], drop the optimistic id's caches, move its transfer and tell the sinks.
     *
     * Call it after the plaintext and media were saved under `sent.id`: the optimistic id's caches
     * are removed here. The bubble keeps its place (appended when it is gone); for Notes any copy
     * of either id under our own user id is dropped too (`MessagingController.swift:4801-4815`), and
     * transcripts shared while it was sending are folded in. Persists the snapshot.
     */
    fun rekey(storePeer: UUID, optimisticId: UUID, sent: ChatMessage)

    fun peerFor(messageId: UUID): UUID?

    /** API peer → thread key: our own id becomes `NOTES_PEER_ID`. */
    fun storePeer(apiPeer: UUID): UUID

    /** Thread key → API peer: `NOTES_PEER_ID` becomes our own id. */
    fun apiPeer(storePeer: UUID): UUID

    fun isNotes(storePeer: UUID): Boolean

    fun persistThread(storePeer: UUID)
    fun persistSnapshot()

    /** Drops caches, annotations and sink state for [messageIds]. */
    fun purge(messageIds: Collection<UUID>)

    val transfers: MediaTransferBoard

    fun setLastError(message: String?)

    val isOffline: Boolean
    val isRealtimeConnected: Boolean

    // ---- Added by W2-MSG-CORE: what the send and reaction engines need from the controller's
    // state (iOS reads it in place, MessagingController.swift). Additive with defaults, so
    // implementations and fakes written against the W1-INT seam keep compiling; ThreadStore
    // overrides every one. Contract change request to W2-MSG-SEND / W2-INT: use these. ----

    /** Live connectivity (`connectivity.isOnline`, `MessagingController.swift:1625`), not the published [isOffline] flag. */
    val isOnline: Boolean get() = !isOffline

    /** A send failed on the network: the chats read offline (`MessagingController.swift:1690`). */
    fun setOffline(offline: Boolean) {}

    /** `refreshConversations(force)`: after a send (messaging-core §11.1 rule 6) or a failed mute. */
    suspend fun refreshConversations(force: Boolean) {}

    /** One conversations refresh for a burst of reaction events, 700 ms debounced (`MessagingController.swift:5856-5866`). */
    fun refreshConversationsSoon() {}

    /** The chat list, server order, Notes not included (the heart badge reads `reaction_seq` / `unseen_reactions`). */
    val conversations: List<ConversationItemDto> get() = emptyList()

    /** Rewrites the chat list, published only when it changed (`MessagingController.swift:5537-5547, 5576-5596`). */
    fun editConversations(transform: (List<ConversationItemDto>) -> List<ConversationItemDto>) {}

    /** The chat on screen. */
    val activePeerId: UUID? get() = null

    /** Who a peer is, for a notification: the chat list, then contacts (`MessagingController.swift:5850-5854`). */
    fun username(storePeer: UUID): String? = null

    /** The chat is muted right now (`MessagingController.swift:5763-5765`). */
    fun isMuted(storePeer: UUID): Boolean = false

    /** The peer reacted to one of our messages: banner or notification (`MessagingController.swift:5531-5540`). */
    fun announceReaction(storePeer: UUID) {}
}

/**
 * In-flight media transfers by message id (`MessagingController.swift:2913-2959`). Progress callbacks
 * come from I/O threads; implementations hop to main before writing.
 */
interface MediaTransferBoard {
    val transfers: StateFlow<Map<UUID, MediaTransfer>>

    fun begin(id: UUID, isUpload: Boolean)

    /** Moves to [phase] and resets the fraction to unknown. */
    fun advance(id: UUID, phase: MediaTransfer.Phase, totalBytes: Long? = null)

    /** Clamped to 0…1. */
    fun update(id: UUID, fraction: Double)

    fun end(id: UUID)

    fun rekey(from: UUID, to: UUID)
}

/**
 * The sealed local store (messaging-core §22); implemented by `MessagingLocalRepository`
 * (W2-MSG-STORE). Blocking: call off the main thread. Everything at rest is under `historyKey`
 * subkeys with `LocalNames` file names (plan §1.5, C10).
 */
interface MessagingStore {
    /** Locked → empty, with the Notes key present. */
    fun hydrate(userId: UUID): HydratedMessages

    /** Writes everything; returns the ids pruned by the retention caps. */
    fun persist(userId: UUID, snapshot: MessagingSnapshot): Set<UUID>

    /** Writes one thread and the roster; returns the pruned ids. */
    fun persistThread(userId: UUID, storePeer: UUID, messages: List<ChatMessage>, roster: RosterSnapshot): Set<UUID>

    /** Waits for every queued write (before a lock drops the key). */
    suspend fun flush()

    fun plaintext(messageId: UUID): ByteArray?
    fun savePlaintext(messageId: UUID, bytes: ByteArray)

    /** Plaintext + local media + annotation index (Android D5). */
    fun removeCaches(messageIds: Collection<UUID>)

    fun noteAnnotation(targetId: UUID, annotationId: UUID)
    fun annotationsFor(targetId: UUID): Set<UUID>

    fun reactionCursors(userId: UUID): Map<UUID, Long>?
    fun saveReactionCursors(userId: UUID, cursors: Map<UUID, Long>)

    /** Flushes, then drops in-memory plaintext and key-derived names. */
    fun lockSensitiveMemory()

    /** Deletes one account's store, or every account's when [userId] is null. */
    fun clear(userId: UUID?)
}

/** A chat as the sealed roster keeps it (messaging-core §22.2). */
data class CachedConversation(
    val id: UUID,
    val peerId: UUID,
    val peerUsername: String,
    val createdAt: Instant,
    val lastMessageAt: Instant?,
    val reactionSeq: Long?,
    val unseenReactions: Int?,
) {
    /** Never prints the peer's name. */
    override fun toString(): String = "CachedConversation(id=$id, peer=$peerId)"
}

data class RosterSnapshot(
    val conversations: List<CachedConversation>,
    val contacts: List<ContactItemDto>,
    val incomingRequests: List<ContactRequestDto>,
    val unreadByPeer: Map<UUID, Int>,
)

data class MessagingSnapshot(val roster: RosterSnapshot, val threads: Map<UUID, List<ChatMessage>>)

data class HydratedMessages(val roster: RosterSnapshot, val threads: Map<UUID, List<ChatMessage>>)

/**
 * The send paths (messaging-core §11); implemented by `SendPipeline` (W2-MSG-SEND). A `String?`
 * result is the user-facing error; null means sent or queued.
 */
interface SendEngine {
    suspend fun sendText(text: String, storePeer: UUID, replyTo: MessageReplyReference?, linkPreview: LinkPreviewAttachment?)

    fun sendTodo(text: String)
    fun toggleTodo(messageId: UUID)
    fun deleteLocalNote(messageId: UUID)

    suspend fun sendImage(
        source: MediaImageSource,
        storePeer: UUID,
        caption: String,
        quality: MediaComposeQuality,
        edits: MediaEdits,
        replyTo: MessageReplyReference?,
    ): String?

    suspend fun sendVideo(plan: VideoSendPlan, storePeer: UUID, replyTo: MessageReplyReference?): String?

    suspend fun sendVoice(
        audio: ByteArray,
        durationMs: Int,
        storePeer: UUID,
        waveform: ByteArray?,
        transcript: String?,
        replyTo: MessageReplyReference?,
        transcriptProvider: (suspend (messageId: UUID) -> String?)?,
    ): String?

    suspend fun retryFailedImage(messageId: UUID, storePeer: UUID): String?
    suspend fun retryFailedVideo(messageId: UUID, storePeer: UUID): String?
    suspend fun shareTranscript(transcript: String, voiceMessageId: UUID, storePeer: UUID)

    /** Sends whatever waited for the network. */
    suspend fun flushOutbox()
}

/** Reactions (messaging-core §19); implemented by `ReactionEngine` (W2-MSG-SEND). */
interface ReactionsEngine {
    /** Emoji per user per message (`GET /config`, default 5). */
    val limit: StateFlow<Int>

    /** Bumped per message when its reactions change, so rows redraw. */
    val revisions: StateFlow<Map<UUID, Int>>

    val failures: SharedFlow<ReactionFailure>

    fun canReact(message: ChatMessage): Boolean
    fun myReactions(message: ChatMessage): List<String>
    fun toggle(emoji: String, messageId: UUID, storePeer: UUID)
    fun set(emojis: List<String>, messageId: UUID, storePeer: UUID)
    fun hasUnseen(storePeer: UUID): Boolean
    suspend fun refreshServerConfig()
    fun applyPage(storePeer: UUID, reactions: List<ReactionDto>, snapshotSeq: Long?)
    fun apply(event: RealtimeEvent.MessageReaction)
    fun apply(event: RealtimeEvent.ReactionsSeen)
    suspend fun catchUp(storePeer: UUID)
    suspend fun flushPendingSaves()
    fun reset()

    // ---- Added by W2-MSG-CORE (additive, defaulted; see ThreadState). The controller calls these
    // where iOS reaches into its reaction state. Contract change request to W2-MSG-SEND / W2-INT. ----

    /**
     * Zeroes the heart badge of chats this device already marked seen, for a list that may predate
     * the seen call (`applyingLocalReactionSeen`, `MessagingController.swift:5598-5609`). Called on
     * every server list before it is published.
     */
    fun applyLocalSeen(conversations: List<ConversationItemDto>): List<ConversationItemDto> = conversations

    /**
     * The open chat's list row or thread was refreshed: if it still counts unseen reactions, mark
     * them seen (`MessagingController.swift:1054-1057, 1412-1414`; messaging-core §19.8).
     */
    fun onChatShown(storePeer: UUID) {}

    /**
     * A thread as it may be written to disk: our unconfirmed entry replaced by the one the server
     * last confirmed (`settledReactions`, `MessagingController.swift:5317-5329`). Applied to every
     * thread the store writes.
     */
    fun settled(messages: List<ChatMessage>): List<ChatMessage> = messages
}

/** Media download / hydrate and payload recovery (messaging-core §13, §9.1); implemented by `MediaHydrator` (W2-MSG-SEND). */
interface MediaLoader {
    suspend fun ensureImageLoaded(message: ChatMessage)
    suspend fun ensureVideoLoaded(message: ChatMessage)
    suspend fun ensureVoiceLoaded(message: ChatMessage)
    suspend fun ensureLinkImageLoaded(message: ChatMessage)

    /** Cancels a download and ends its transfer; uploads cannot be cancelled. */
    fun cancel(messageId: UUID)
    fun cancelAll()

    /** The decrypted bytes on this device, or null (plan C8). */
    suspend fun mediaBytes(messageId: UUID): ByteArray?
}
