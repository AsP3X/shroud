package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.contacts.PeerIdentities
import de.corespace.shroud.core.crypto.CryptoController
import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.crypto.MediaCrypto
import de.corespace.shroud.core.crypto.MessageCrypto
import de.corespace.shroud.core.crypto.OpenAs
import de.corespace.shroud.core.crypto.PeerLocks
import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.links.LinkPreviewAttachment
import de.corespace.shroud.core.media.ImagePipeline
import de.corespace.shroud.core.media.LocalMediaStore
import de.corespace.shroud.core.media.MediaComposeQuality
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.MediaTransfers
import de.corespace.shroud.core.media.PlainSource
import de.corespace.shroud.core.media.VideoPipeline
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.core.media.video.VideoSendPlan
import de.corespace.shroud.core.media.video.VideoUploadQuality
import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.model.PeerIdentityChangedException
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ClientConfigDto
import de.corespace.shroud.core.net.ContentType
import de.corespace.shroud.core.net.ConversationItemDto
import de.corespace.shroud.core.net.ErrorCodes
import de.corespace.shroud.core.net.ListMessagesResponse
import de.corespace.shroud.core.net.MarkReactionsSeenResponse
import de.corespace.shroud.core.net.MessageDto
import de.corespace.shroud.core.net.ReactionChangesResponse
import de.corespace.shroud.core.net.ReactionWriteResult
import de.corespace.shroud.core.net.SendMessageRequest
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.core.net.wire.LinkPreview
import de.corespace.shroud.core.net.wire.MediaMessagePayload
import de.corespace.shroud.core.net.wire.MessageAnnotation
import de.corespace.shroud.core.net.wire.MessageReplyReference
import de.corespace.shroud.core.net.wire.MessageTextPayload
import de.corespace.shroud.core.net.wire.WireText
import de.corespace.shroud.core.notifications.MessageNotifier
import de.corespace.shroud.core.storage.SensitiveTempFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.CoroutineContext

// The send paths of the messaging engine (messaging-core §11, media-voice-links §1.6, §3; plan §1.7.7):
// text, link previews with a large image, Notes, todos, photos, videos, voice notes, retries and the
// offline queue. iOS: `ios/shroud/Services/Messaging/MessagingController.swift` (MC below).
//
// Every path keeps the rules of messaging-core §11.1:
//  1. the server re-keys sent messages: the bubble ends under `dto.id`, the optimistic id's caches go
//     (`ThreadState.rekey`), and `client_message_id` is always the optimistic id, retries included
//     (memory: *Server re-keys sent messages*);
//  2. what was sealed is cached under `dto.id` right after the send (text wire, media payload with
//     the blob key, the plaintext media bytes): our own v3 body cannot be opened again;
//  3. the peer's key comes from `PeerIdentities.publicKeyForSending` (a pending change throws
//     `PeerIdentityChangedException`, whose text the user sees);
//  4. sealed envelopes stay ≤ 60 KiB and media payload plaintext ≤ 12 KiB (MC:3289-3293);
//  5. nothing is published into the thread once `ThreadState.lockGeneration` moved on.

/**
 * What the send, media and reaction engines need from `MessagingController` beyond [ThreadState]
 * (messaging-core §11, §19.6, §19.8). Main-confined like [ThreadState].
 *
 * Every member has the name and shape of the member W2-MSG-CORE adds to its `ThreadState` for the
 * same purpose (plain values, read at the moment of use), so W2-INT wires it with a one-line
 * delegate per member — or lets `ThreadStore` implement this interface too (contract change request
 * CR-1 of W2-MSG-SEND). [foldSharedTranscripts] is the one extra: W2-MSG-CORE folds inside
 * `ThreadState.rekey`, so its delegate may return the thread unchanged.
 */
interface SendHost {
    /** The server's chat list, Notes not included; reactions read `reaction_seq`, `unseen_reactions` and ids. */
    val conversations: List<ConversationItemDto>

    /** The chat on screen (`MessagingController.activePeerId`). */
    val activePeerId: UUID?

    /** `MessagingController.refreshConversations(force)`; every successful send forces one (MC:1658, 1679, 2927, 3283, 3785). */
    suspend fun refreshConversations(force: Boolean = false)

    /** iOS `isOffline = true` after a send found no network (MC:1642, 1691, 2536, 2718, 3472); `ContactsHooks.setOffline`. */
    fun setOffline(offline: Boolean)

    fun isMuted(storePeer: UUID): Boolean

    /** Who a peer is, for a banner: the chat list, then contacts (MC:5850-5853). */
    fun username(storePeer: UUID): String?

    /** Replaces the chat list through [transform]; publishes only a real change (reaction badges, MC:5538-5542, 5582-5583). */
    fun editConversations(transform: (List<ConversationItemDto>) -> List<ConversationItemDto>)

    /**
     * Applies shared transcripts whose voice note is now in [thread] and forgets them
     * (`foldSharedTranscripts`, MC:3564-3571). The pending map lives with the ingest path
     * (W2-MSG-CORE); a sent voice note folds through it (MC:3776).
     */
    fun foldSharedTranscripts(thread: List<ChatMessage>): List<ChatMessage>

    /**
     * Stand-in until W2-INT wires `MessagingController`: no chat list, no active chat, refreshes do
     * nothing, transcripts are not folded. Sends, retries, downloads and reactions still work.
     */
    object Detached : SendHost {
        override val conversations: List<ConversationItemDto> get() = emptyList()
        override val activePeerId: UUID? get() = null
        override suspend fun refreshConversations(force: Boolean) = Unit
        override fun setOffline(offline: Boolean) = Unit
        override fun isMuted(storePeer: UUID): Boolean = false
        override fun username(storePeer: UUID): String? = null
        override fun editConversations(transform: (List<ConversationItemDto>) -> List<ConversationItemDto>) = Unit
        override fun foldSharedTranscripts(thread: List<ChatMessage>): List<ChatMessage> = thread
    }
}

/**
 * The REST calls of the send, media and reaction engines — a slice of [ShroudApi] (W1-NET), so the
 * engines can be tested against a scripted server. [of] is the production adapter.
 */
interface SendApi {
    suspend fun sendMessage(token: String, request: SendMessageRequest): MessageDto
    suspend fun messages(token: String, peerUserId: UUID, limit: Int): ListMessagesResponse
    suspend fun clientConfig(token: String): ClientConfigDto
    suspend fun putReaction(token: String, messageId: UUID, ciphertext: ByteArray, baseSeq: Long, added: Boolean): ReactionWriteResult
    suspend fun deleteReaction(token: String, messageId: UUID, baseSeq: Long): ReactionWriteResult
    suspend fun reactionChanges(token: String, peerUserId: UUID, afterSeq: Long, limit: Int): ReactionChangesResponse
    suspend fun markReactionsSeen(token: String, peerUserId: UUID, upToSeq: Long): MarkReactionsSeenResponse

    companion object {
        fun of(api: ShroudApi): SendApi = object : SendApi {
            override suspend fun sendMessage(token: String, request: SendMessageRequest) = api.sendMessage(token, request)
            override suspend fun messages(token: String, peerUserId: UUID, limit: Int) = api.messages(token, peerUserId, limit)
            override suspend fun clientConfig(token: String) = api.clientConfig(token)
            override suspend fun putReaction(token: String, messageId: UUID, ciphertext: ByteArray, baseSeq: Long, added: Boolean) =
                api.putReaction(token, messageId, ciphertext, baseSeq, added)
            override suspend fun deleteReaction(token: String, messageId: UUID, baseSeq: Long) = api.deleteReaction(token, messageId, baseSeq)
            override suspend fun reactionChanges(token: String, peerUserId: UUID, afterSeq: Long, limit: Int) =
                api.reactionChanges(token, peerUserId, afterSeq, limit)
            override suspend fun markReactionsSeen(token: String, peerUserId: UUID, upToSeq: Long) = api.markReactionsSeen(token, peerUserId, upToSeq)
        }
    }
}

/**
 * Our identity keys while chats are unlocked: iOS `cryptoController?.material`
 * (`agreementPrivateKey`, `identityPublicKeyData`). [of] reads them through
 * `CryptoController.withMaterial`; the arrays never leave [withKeys].
 */
interface SendKeyring {
    val isUnlocked: Boolean

    /** Runs [block] with our private and public X25519 identity key; null while chats are locked. */
    fun <T : Any> withKeys(block: (ourPrivate: ByteArray, ourPublic: ByteArray) -> T): T?

    companion object {
        fun of(crypto: CryptoController): SendKeyring = object : SendKeyring {
            override val isUnlocked: Boolean get() = crypto.isUnlocked
            override fun <T : Any> withKeys(block: (ourPrivate: ByteArray, ourPublic: ByteArray) -> T): T? =
                crypto.withMaterial { block(it.agreementPrivateKey, it.identityPublicKey) }
        }
    }
}

/**
 * Everything the engines of this package use, built by `MessagingSendModule`. Ports of packages of
 * the same wave are providers, resolved at use: W2-INT points them at their owners' modules.
 *
 * @param scope the long-lived scope sends run in (`AppContainer.appScope`, `Dispatchers.Main.immediate`):
 *   a send keeps going when the screen that started it goes away, as an iOS `Task` does.
 * @param compute sealing and opening (`Dispatchers.Default`).
 * @param io the blocking stores (`MessagingStore`, `LocalMediaStore` file calls).
 * @param videoTooLarge whether a `VideoPipeline.encode` failure is W2-VIDEO's "too large" (iOS
 *   `VideoMedia.VideoError.tooLarge`, MC:2630); see contract change request CR-4.
 */
class SendDependencies(
    val api: SendApi,
    val keyring: SendKeyring,
    val crypto: MessageCrypto,
    val peerLocks: PeerLocks,
    val peerIdentities: () -> PeerIdentities,
    val store: () -> MessagingStore,
    val media: () -> LocalMediaStore,
    val transfers: () -> MediaTransfers,
    val images: () -> ImagePipeline,
    val video: () -> VideoPipeline,
    val isOnline: () -> Boolean,
    val clock: AppClock,
    val scope: CoroutineScope,
    val compute: CoroutineContext = Dispatchers.Default,
    val io: CoroutineContext = Dispatchers.IO,
    val notifier: () -> MessageNotifier? = { null },
    val videoTooLarge: (Throwable) -> Boolean = { false },
) {
    /**
     * Seals [plaintext] for [apiPeer] (`MessageCrypto.seal`, MC:4834-4841): v3 when a session exists
     * or we initiate, else v2. Under the peer's lock (plan §1.4; Notes: our own id, which is the API
     * peer), on [compute]. Throws `CryptoError.Locked` while chats are locked.
     */
    suspend fun seal(plaintext: ByteArray, apiPeer: UUID, me: UUID, peerPublic: ByteArray): ByteArray =
        peerLocks.withPeer(apiPeer) {
            withContext(compute) {
                keyring.withKeys { ourPrivate, ourPublic -> crypto.seal(plaintext, apiPeer, peerPublic, ourPrivate, ourPublic, me) }
            } ?: throw CryptoError.Locked
        }

    /** v2 identity boxes only, no ratchet (reactions, MC:5271-5278). */
    suspend fun sealIdentityOnly(plaintext: ByteArray, peerPublic: ByteArray): ByteArray =
        withContext(compute) {
            keyring.withKeys { ourPrivate, ourPublic -> crypto.sealV2(plaintext, peerPublic, ourPrivate, ourPublic) }
        } ?: throw CryptoError.Locked

    /** Our identity public key (a copy), or null while locked. */
    fun ourPublicKey(): ByteArray? = keyring.withKeys { _, ourPublic -> ourPublic.copyOf() }

    /** Opens [envelope] (`MessageCrypto.open`); [senderPublic] null = our own key. Null while locked. */
    suspend fun open(envelope: ByteArray, peerUserId: UUID, senderPublic: ByteArray?, role: OpenAs, sentAt: Instant): ByteArray? =
        withContext(compute) {
            keyring.withKeys { ourPrivate, ourPublic -> crypto.open(envelope, peerUserId, ourPrivate, ourPublic, senderPublic ?: ourPublic, role, sentAt) }
        }

    /** Opens a tagged v2 record (`MessageCrypto.openTagged`); [senderPublic] null = our own key. Null while locked. */
    suspend fun openTagged(envelope: ByteArray, senderPublic: ByteArray?, role: OpenAs): ByteArray? =
        withContext(compute) {
            keyring.withKeys { ourPrivate, ourPublic -> crypto.openTagged(envelope, ourPrivate, ourPublic, senderPublic ?: ourPublic, role) }
        }
}

/**
 * The send paths (messaging-core §11; the [SendEngine] seam of plan §1.7.7), ported from
 * `MessagingController.swift`. `MessagingController` (W2-MSG-CORE) delegates to it.
 *
 * Main-confined like [ThreadState]. The suspending entry points run their work in this pipeline's
 * own scope (a child of [SendDependencies.scope]) and await it, so a send survives the screen that
 * started it; [cancelAll] stops them (Log Out, wipe). Results that land after a lock
 * ([ThreadState.lockGeneration]) are not published.
 *
 * Android differences, none visible on the wire:
 * - media bytes live in the sealed media cache, not in the bubble (plan C8): an optimistic bubble
 *   says [ChatMessage.hasFullMedia] and its bytes are saved under the optimistic id; after the send
 *   the cache entry is renamed to the server id ([LocalMediaStore.rename], C10), then
 *   [ThreadState.rekey] drops what is left under the optimistic id;
 * - uploads go through [MediaTransfers.upload], which seals the blob (fresh key per attempt,
 *   MC:2834) and streams it; a video is read from the sealed cache, never from a plaintext file (C28).
 */
class SendPipeline(
    private val state: ThreadState,
    private val host: () -> SendHost,
    private val deps: SendDependencies,
) : SendEngine {
    private val job = SupervisorJob(deps.scope.coroutineContext[Job])
    private val scope = CoroutineScope(deps.scope.coroutineContext + job)

    /** Transcript annotations (MC:3557-3660). */
    val annotations = AnnotationSender(state, deps, scope)

    /** The running outbox flush; overlapping callers await it (`OutboundSendQueue`, `OutboundPending.swift:62-85`). */
    private var flushJob: Deferred<Unit>? = null

    /** Stops every send, retry and flush in flight (sign-out, wipe: `outboundQueue.cancel()`, MC:526). Their bubbles stay queued. */
    override fun cancelAll() {
        job.cancelChildren()
        flushJob = null
        annotations.reset()
    }

    // ---- text ----

    /** `sendText` (MC:1590-1695). */
    override suspend fun sendText(text: String, storePeer: UUID, replyTo: MessageReplyReference?, linkPreview: LinkPreviewAttachment?) =
        detached { performSendText(text, storePeer, replyTo, linkPreview) }

    private suspend fun performSendText(text: String, storePeer: UUID, replyTo: MessageReplyReference?, linkPreview: LinkPreviewAttachment?) {
        val trimmed = WireText.trimWhitespacesAndNewlines(text)
        if (trimmed.isEmpty()) return
        if (state.isNotes(storePeer)) {
            // Notes sync as text, so their preview is always the inline (small) one (MC:1599-1608).
            appendAndSyncNote(trimmed, ChatMessageKind.Text, null, replyTo, linkPreview?.preview)
            return
        }
        val signed = signedIn() ?: return
        val generation = state.lockGeneration
        val online = deps.isOnline()
        // The large layout needs an upload; offline, fall straight back to the inline thumbnail (MC:1616-1617).
        val optimisticId = UUID.randomUUID()
        val wantsLargeImage = if (online) linkPreview?.largeImage else null
        // The sender's copy shows the picture before the upload lands: it waits in the sealed cache (C8).
        val largeImage = wantsLargeImage?.takeIf { saveMedia(optimisticId, it.toByteArray()) }
        if (state.lockGeneration != generation) return
        state.edit(storePeer) { list ->
            list + ChatMessage(
                id = optimisticId,
                peerUserId = storePeer,
                senderUserId = signed.me,
                text = trimmed,
                createdAt = deps.clock.now(),
                isMine = true,
                receipt = ReceiptStatus.Sending,
                imageWidth = largeImage?.let { linkPreview?.largeImageWidth },
                imageHeight = largeImage?.let { linkPreview?.largeImageHeight },
                hasFullMedia = largeImage != null,
                pendingSync = true,
                replyTo = replyTo,
                linkPreview = linkPreview?.preview,
            )
        }
        state.persistSnapshot()

        // Offline: keep the bubble; the outbox flush sends it (MC:1640-1644).
        if (!online) {
            host().setOffline(true)
            return
        }

        if (linkPreview != null && largeImage != null) {
            try {
                deliverLinkWithImage(optimisticId, trimmed, linkPreview, storePeer, signed, replyTo, generation)
                host().refreshConversations(force = true)
                state.setLastError(null)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the message, lose the big picture: the text send carries the small thumbnail (MC:1661-1665).
                if (state.lockGeneration == generation) dropLargeLinkImage(optimisticId)
            }
        }

        try {
            deliverPendingText(optimisticId, trimmed, storePeer, signed, replyTo, linkPreview?.preview, generation)
            host().refreshConversations(force = true)
            state.setLastError(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (state.lockGeneration != generation) return
            // Keep the bubble for a later flush / retry instead of dropping the draft (MC:1681-1694).
            state.update(optimisticId) { it.copy(receipt = ReceiptStatus.Sending, pendingSync = true, sendError = null) }
            host().setOffline(true)
            state.persistSnapshot()
            state.setLastError(userMessage(e))
        }
    }

    /**
     * A text message whose link preview has a large image goes out as a `t: "link"` media message whose
     * blob is that image (`deliverLinkWithImage`, MC:1706-1798). The server sees a captioned photo.
     */
    private suspend fun deliverLinkWithImage(
        messageId: UUID,
        text: String,
        attachment: LinkPreviewAttachment,
        storePeer: UUID,
        signed: Signed,
        replyTo: MessageReplyReference?,
        generation: Long,
    ): ChatMessage {
        val image = attachment.largeImage?.toByteArray() ?: throw IllegalStateException("no large image")
        val images = deps.images()
        val size = if (attachment.largeImageWidth == null || attachment.largeImageHeight == null) withContext(deps.compute) { images.pixelSize(image) } else null
        val width = attachment.largeImageWidth ?: size?.first ?: 0
        val height = attachment.largeImageHeight ?: size?.second ?: 0
        val apiPeer = state.apiPeer(storePeer)
        val blob = deps.transfers().upload(PlainSource.InMemory(image), signed.token)

        // The blob is the big picture; the payload keeps only a tiny blurred placeholder (MC:1727-1729).
        val preview = attachment.preview.withoutThumbnail()
        val placeholder = withContext(deps.compute) { images.chatPreviewJpeg(image) }
        val peerPublic = deps.peerIdentities().publicKeyForSending(apiPeer)
        val sealed = sealMediaPayload(
            kind = MediaMessagePayload.KIND_LINK,
            mime = MIME_JPEG,
            width = width,
            height = height,
            key = blob.keyBase64,
            caption = text,
            durationMs = null,
            previewJpeg = placeholder,
            byteCount = image.size.toLong(),
            apiPeer = apiPeer,
            me = signed.me,
            peerPublic = peerPublic,
            replyTo = replyTo,
            linkPreview = preview,
        )
        val dto = deps.api.sendMessage(
            signed.token,
            SendMessageRequest(apiPeer, messageId, ContentType.MEDIA, MessageCrypto.toWire(sealed.envelope), blob.mediaObjectId),
        )
        moveMedia(messageId, dto.id, image)
        // The payload holds the blob key — keep it, not just the text, so reloads can decode (MC:1762-1763).
        savePlaintext(dto.id, sealed.payload)

        val sent = ChatMessage(
            id = dto.id,
            peerUserId = storePeer,
            senderUserId = signed.me,
            text = text,
            createdAt = dto.createdAt,
            createdAtWire = dto.createdAtWire,
            isMine = true,
            receipt = receipt(dto),
            mediaObjectId = blob.mediaObjectId,
            imageWidth = width,
            imageHeight = height,
            hasFullMedia = true,
            previewJpeg = sealed.usedPreview?.let(Bytes::of),
            mediaByteCount = image.size.toLong(),
            replyTo = replyTo,
            linkPreview = preview,
        )
        if (state.lockGeneration == generation) {
            reKey(storePeer, messageId, sent, appendIfMissing = true)
            state.persistSnapshot()
        }
        return sent
    }

    /** Switches an unsent link bubble to the small layout, its inline thumbnail (`dropLargeLinkImage`, MC:1801-1811). */
    private suspend fun dropLargeLinkImage(messageId: UUID) {
        state.update(messageId) { it.copy(hasFullMedia = false, imageWidth = null, imageHeight = null, mediaObjectId = null) }
        withContext(deps.io) { deps.media().remove(listOf(messageId)) }
        state.persistSnapshot()
    }

    /**
     * Seals and posts a text message whose bubble already exists (`deliverPendingText`, MC:4821-4880):
     * the wire with its quote and preview is cached under `dto.id`, and the bubble is re-keyed — or
     * appended when it went meanwhile (it is on the server now).
     */
    private suspend fun deliverPendingText(
        messageId: UUID,
        text: String,
        storePeer: UUID,
        signed: Signed,
        replyTo: MessageReplyReference?,
        linkPreview: LinkPreview?,
        generation: Long,
    ): ChatMessage {
        val posted = postText(messageId, text, state.apiPeer(storePeer), signed, replyTo, linkPreview)
        val dto = posted.dto
        val sent = ChatMessage(
            id = dto.id,
            peerUserId = storePeer,
            senderUserId = signed.me,
            text = text,
            createdAt = dto.createdAt,
            createdAtWire = dto.createdAtWire,
            isMine = true,
            receipt = receipt(dto),
            replyTo = replyTo,
            linkPreview = posted.sealedPreview,
        )
        if (state.lockGeneration == generation) {
            reKey(storePeer, messageId, sent, appendIfMissing = true)
            state.persistSnapshot()
        }
        return sent
    }

    private class PostedText(val dto: MessageDto, val sealedPreview: LinkPreview?)

    /**
     * The network half of `deliverPendingText` (MC:4831-4852): key, `textWire`, seal, `POST /messages`
     * with `client_message_id` = [messageId], cache the wire under `dto.id`. Touches no thread.
     */
    private suspend fun postText(
        messageId: UUID,
        body: String,
        apiPeer: UUID,
        signed: Signed,
        replyTo: MessageReplyReference?,
        linkPreview: LinkPreview?,
    ): PostedText {
        val peerPublic = deps.peerIdentities().publicKeyForSending(apiPeer)
        // A reply / preview seals body + extras together; a plain message stays raw UTF-8 (MC:4832-4833).
        val wire = MessageTextPayload.textWire(body, replyTo, linkPreview)
        val plaintext = wire.wire.toByteArray(Charsets.UTF_8)
        val sealed = deps.seal(plaintext, apiPeer, signed.me, peerPublic)
        val dto = deps.api.sendMessage(signed.token, SendMessageRequest(apiPeer, messageId, ContentType.TEXT, MessageCrypto.toWire(sealed)))
        // Cache what was sealed (quote included) so a later decode rebuilds the same bubble (MC:4851-4852).
        savePlaintext(dto.id, plaintext)
        return PostedText(dto, wire.sealedPreview)
    }

    // ---- Notes ----

    /**
     * A text or todo note: shown at once, synced to the server's "Saved Messages" (our own id) when
     * online, and re-keyed to the server's id in the Notes thread (`appendAndSyncNote`, MC:4724-4817).
     * A note that cannot sync stays local; one whose sync failed is marked `pendingSync` and never
     * retried (messaging-core D6, iOS parity).
     */
    private suspend fun appendAndSyncNote(
        text: String,
        kind: ChatMessageKind,
        todoDone: Boolean?,
        replyTo: MessageReplyReference? = null,
        linkPreview: LinkPreview? = null,
    ) {
        val me = state.myUserId ?: NOTES_PEER_ID
        // A todo keeps its own marker format; only plain notes carry a quote or a preview (MC:4733-4738).
        val noteWire = MessageTextPayload.textWire(text, replyTo, if (kind == ChatMessageKind.Todo) null else linkPreview)
        val message = ChatMessage(
            id = UUID.randomUUID(),
            peerUserId = NOTES_PEER_ID,
            senderUserId = me,
            text = text,
            createdAt = deps.clock.now(),
            isMine = true,
            receipt = ReceiptStatus.Sent,
            kind = kind,
            todoDone = todoDone,
            replyTo = replyTo,
            linkPreview = noteWire.sealedPreview,
        )
        val generation = state.lockGeneration
        state.edit(NOTES_PEER_ID) { it + message }
        val wireText = if (kind == ChatMessageKind.Todo) todoWire(text, todoDone ?: false) else noteWire.wire
        savePlaintext(message.id, wireText.toByteArray(Charsets.UTF_8))
        if (state.lockGeneration != generation) return
        state.persistThread(NOTES_PEER_ID)

        // Multi-device: sealed to ourselves when online (MC:4756-4761).
        if (!deps.isOnline()) return
        val signed = signedIn() ?: return
        try {
            // The wire already holds the quote and preview: sealed as it is (MC:4764-4771).
            val posted = postText(message.id, wireText, signed.me, signed, null, null)
            if (state.lockGeneration != generation) return
            // Re-key to the server's id inside Notes: the plaintext is stored under it, and delete and
            // the next reload match on it (MC:4772-4798).
            if (state.messages(NOTES_PEER_ID)?.any { it.id == message.id } == true) {
                val dto = posted.dto
                state.rekey(
                    NOTES_PEER_ID,
                    message.id,
                    ChatMessage(
                        id = dto.id,
                        peerUserId = NOTES_PEER_ID,
                        senderUserId = signed.me,
                        text = message.text,
                        createdAt = dto.createdAt,
                        createdAtWire = dto.createdAtWire,
                        isMine = true,
                        receipt = ReceiptStatus.Sent,
                        kind = message.kind,
                        todoDone = message.todoDone,
                        replyTo = message.replyTo,
                        linkPreview = message.linkPreview,
                    ),
                )
                state.persistThread(NOTES_PEER_ID)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (state.lockGeneration != generation) return
            if (state.update(message.id) { it.copy(pendingSync = true) }) state.persistThread(NOTES_PEER_ID)
        }
    }

    /** A todo in Notes, synced as text with its marker (`sendTodo`, MC:1814-1818). */
    override fun sendTodo(text: String) {
        val trimmed = WireText.trimWhitespacesAndNewlines(text)
        if (trimmed.isEmpty()) return
        scope.launch { appendAndSyncNote(trimmed, ChatMessageKind.Todo, false) }
    }

    /**
     * Flips a Notes todo (`toggleTodo`, MC:1821-1830; `NotesLocal.toggleTodo`, `NotesLocal.swift:36-46`).
     * Local only: a re-send would duplicate the note, so other devices keep the old state.
     */
    override fun toggleTodo(messageId: UUID) {
        val list = state.messages(NOTES_PEER_ID) ?: return
        if (list.none { it.id == messageId && it.kind == ChatMessageKind.Todo }) return
        state.edit(NOTES_PEER_ID) { thread ->
            thread.map { if (it.id == messageId && it.kind == ChatMessageKind.Todo) it.copy(todoDone = !(it.todoDone ?: false)) else it }
        }
        state.persistThread(NOTES_PEER_ID)
    }

    /**
     * Deletes a local note and every artifact of it — media, plaintext, transfer — even when the row
     * is already gone (`deleteLocalNote`, MC:1834-1850).
     */
    override fun deleteLocalNote(messageId: UUID) {
        val list = state.messages(NOTES_PEER_ID)
        if (list == null) {
            state.purge(listOf(messageId))
            return
        }
        val removed = list.any { it.id == messageId }
        state.purge(listOf(messageId))
        if (removed) state.edit(NOTES_PEER_ID) { thread -> thread.filterNot { it.id == messageId } }
        state.persistThread(NOTES_PEER_ID)
    }

    // ---- photos ----

    /**
     * Encodes, seals, uploads and sends one photo (`sendImage`, MC:2392-2561; media-voice-links §3.1).
     * At Original quality an untouched library file goes byte for byte (the pipeline decides).
     */
    override suspend fun sendImage(
        source: MediaImageSource,
        storePeer: UUID,
        caption: String,
        quality: MediaComposeQuality,
        edits: MediaEdits,
        replyTo: MessageReplyReference?,
    ): String? = detached { performSendImage(source, storePeer, caption, quality, edits, replyTo) }

    private suspend fun performSendImage(
        source: MediaImageSource,
        storePeer: UUID,
        caption: String,
        quality: MediaComposeQuality,
        edits: MediaEdits,
        replyTo: MessageReplyReference?,
    ): String? {
        val trimmedCaption = WireText.trimWhitespacesAndNewlines(caption)
        val displayText = trimmedCaption.ifEmpty { PHOTO }
        val optimisticId = UUID.randomUUID()
        val encoded = try {
            // Edits are baked by the pipeline at full resolution; untouched photos keep their pass-through (MC:2403-2424).
            deps.images().encode(source, quality, edits)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return COULD_NOT_PREPARE_PHOTO
        }
        val bytes = encoded.data.toByteArray()
        val image = Encoded(bytes, encoded.width, encoded.height, encoded.mime)

        if (state.isNotes(storePeer)) {
            sendNotesImage(optimisticId, storePeer, displayText, trimmedCaption, image, replyTo)
            return null
        }

        val signed = signedIn() ?: return NOT_SIGNED_IN
        val generation = state.lockGeneration
        val cached = saveMedia(optimisticId, bytes)
        if (state.lockGeneration != generation) return null
        state.edit(storePeer) { list ->
            list + ChatMessage(
                id = optimisticId,
                peerUserId = storePeer,
                senderUserId = signed.me,
                text = displayText,
                createdAt = deps.clock.now(),
                isMine = true,
                receipt = ReceiptStatus.Sending,
                kind = ChatMessageKind.Image,
                imageWidth = image.width,
                imageHeight = image.height,
                hasFullMedia = cached,
                pendingSync = true,
                replyTo = replyTo,
            )
        }
        state.persistSnapshot()

        if (!deps.isOnline()) {
            host().setOffline(true)
            markFailed(optimisticId, WAITING_FOR_CONNECTION)
            return null
        }

        return try {
            finishImageSend(optimisticId, storePeer, signed, image, trimmedCaption, replyTo, rekey = true)
            state.setLastError(null)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = userMessage(e)
            if (state.lockGeneration == generation) {
                markFailed(optimisticId, message)
                state.setLastError(message)
                state.persistSnapshot()
            }
            message
        }
    }

    /**
     * A photo in Notes: kept locally at once; online, also synced as a note to ourselves and re-keyed
     * into the Notes thread (MC:2429-2506). A failed sync keeps the local photo.
     */
    private suspend fun sendNotesImage(
        optimisticId: UUID,
        storePeer: UUID,
        displayText: String,
        caption: String,
        image: Encoded,
        replyTo: MessageReplyReference?,
    ) {
        val me = state.myUserId ?: NOTES_PEER_ID
        val generation = state.lockGeneration
        val cached = saveMedia(optimisticId, image.bytes)
        if (state.lockGeneration != generation) return
        state.edit(storePeer) { list ->
            list + ChatMessage(
                id = optimisticId,
                peerUserId = storePeer,
                senderUserId = me,
                text = displayText,
                createdAt = deps.clock.now(),
                isMine = true,
                receipt = ReceiptStatus.Sent,
                kind = ChatMessageKind.Image,
                imageWidth = image.width,
                imageHeight = image.height,
                hasFullMedia = cached,
                replyTo = replyTo,
            )
        }
        state.persistThread(storePeer)

        if (!deps.isOnline()) return
        val signed = signedIn() ?: return
        try {
            val sent = finishImageSend(optimisticId, storePeer, signed, image, caption, replyTo, rekey = false)
            if (state.lockGeneration != generation) return
            rekeyIntoNotes(
                optimisticId,
                ChatMessage(
                    id = sent.id,
                    peerUserId = storePeer,
                    senderUserId = signed.me,
                    text = sent.text,
                    createdAt = sent.createdAt,
                    createdAtWire = sent.createdAtWire,
                    isMine = true,
                    receipt = ReceiptStatus.Sent,
                    kind = ChatMessageKind.Image,
                    mediaObjectId = sent.mediaObjectId,
                    imageWidth = sent.imageWidth,
                    imageHeight = sent.imageHeight,
                    hasFullMedia = true,
                    replyTo = replyTo,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep the local-only photo (MC:2501-2503).
        }
    }

    /**
     * Re-sends a failed photo with the bytes already prepared — no second JPEG generation
     * (`retryFailedImage`, MC:3143-3190).
     */
    override suspend fun retryFailedImage(messageId: UUID, storePeer: UUID): String? = detached {
        val signed = signedIn() ?: return@detached NOTHING_TO_RETRY
        val message = state.messages(storePeer)?.firstOrNull { it.id == messageId && it.isMine && it.kind == ChatMessageKind.Image }
            ?: return@detached NOTHING_TO_RETRY
        val bytes = if (message.hasFullMedia) deps.media().readAll(messageId) else null
        if (bytes == null) return@detached NOTHING_TO_RETRY
        val generation = state.lockGeneration
        state.update(messageId) { it.copy(receipt = ReceiptStatus.Sending, sendError = null) }
        val image = encodedFromBytes(bytes, message)
        val caption = if (message.text == PHOTO || message.text.isEmpty()) "" else message.text
        try {
            // The bubble already carries its quote; a retry re-seals the same one (MC:3170-3171).
            finishImageSend(messageId, storePeer, signed, image, caption, message.replyTo, rekey = true)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val text = userMessage(e)
            if (state.lockGeneration == generation) markFailed(messageId, text)
            text
        }
    }

    /**
     * Upload, seal, send, cache under the server id (`finishImageSend`, MC:3195-3285). With [rekey] the
     * bubble under [storePeer] is replaced in place; Notes re-key themselves.
     */
    private suspend fun finishImageSend(
        optimisticId: UUID,
        storePeer: UUID,
        signed: Signed,
        image: Encoded,
        caption: String,
        replyTo: MessageReplyReference?,
        rekey: Boolean,
    ): ChatMessage {
        val generation = state.lockGeneration
        val apiPeer = state.apiPeer(storePeer)
        val blob = deps.transfers().upload(PlainSource.InMemory(image.bytes), signed.token)
        val trimmedCaption = WireText.trimWhitespacesAndNewlines(caption)
        val displayText = trimmedCaption.ifEmpty { PHOTO }
        val previewJpeg = withContext(deps.compute) { deps.images().chatPreviewJpeg(image.bytes) }
        val peerPublic = deps.peerIdentities().publicKeyForSending(apiPeer)
        val sealed = sealMediaPayload(
            kind = MediaMessagePayload.KIND_IMAGE,
            mime = image.mime,
            width = image.width,
            height = image.height,
            key = blob.keyBase64,
            caption = trimmedCaption.ifEmpty { null },
            durationMs = null,
            previewJpeg = previewJpeg,
            byteCount = image.bytes.size.toLong(),
            apiPeer = apiPeer,
            me = signed.me,
            peerPublic = peerPublic,
            replyTo = replyTo,
        )
        val dto = deps.api.sendMessage(
            signed.token,
            // Idempotency key: the optimistic id, so a retried or raced flush never inserts a second row (MC:3240-3242).
            SendMessageRequest(apiPeer, optimisticId, ContentType.MEDIA, MessageCrypto.toWire(sealed.envelope), blob.mediaObjectId),
        )
        moveMedia(optimisticId, dto.id, image.bytes)
        // The media payload (with the file key), not just the caption: needed for reloads (MC:3253-3254).
        savePlaintext(dto.id, sealed.payload)
        val sent = ChatMessage(
            id = dto.id,
            peerUserId = storePeer,
            senderUserId = signed.me,
            text = displayText,
            createdAt = dto.createdAt,
            createdAtWire = dto.createdAtWire,
            isMine = true,
            receipt = receipt(dto),
            kind = ChatMessageKind.Image,
            mediaObjectId = blob.mediaObjectId,
            imageWidth = image.width,
            imageHeight = image.height,
            hasFullMedia = true,
            previewJpeg = sealed.usedPreview?.let(Bytes::of),
            mediaByteCount = image.bytes.size.toLong(),
            sendError = null,
            replyTo = replyTo,
        )
        if (state.lockGeneration == generation) {
            if (rekey) reKey(storePeer, optimisticId, sent, appendIfMissing = false)
            state.persistSnapshot()
        }
        host().refreshConversations(force = true)
        return sent
    }

    /** `markImageFailed` / `markVoiceFailed` (MC:3878-3887, 3790-3799). */
    private fun markFailed(messageId: UUID, error: String) {
        if (state.update(messageId) { it.copy(receipt = ReceiptStatus.Failed, sendError = error, pendingSync = true) }) {
            state.persistSnapshot()
        }
    }

    // ---- videos ----

    /**
     * One composed video: the bubble lands first with the plan's poster, then compress → upload →
     * envelope fill one ring in place (`sendVideo`, MC:2571-2745; media-voice-links §3.2).
     */
    override suspend fun sendVideo(plan: VideoSendPlan, storePeer: UUID, replyTo: MessageReplyReference?): String? =
        detached { performSendVideo(plan, storePeer, replyTo) }

    private suspend fun performSendVideo(plan: VideoSendPlan, storePeer: UUID, replyTo: MessageReplyReference?): String? {
        val trimmedCaption = WireText.trimWhitespacesAndNewlines(plan.caption)
        val displayText = trimmedCaption.ifEmpty { VIDEO }
        val notes = state.isNotes(storePeer)
        val me = state.myUserId ?: (if (notes) NOTES_PEER_ID else null) ?: return NOT_SIGNED_IN
        if (!notes && (state.session == null || !deps.keyring.isUnlocked)) return NOT_SIGNED_IN

        val optimisticId = UUID.randomUUID()
        val generation = state.lockGeneration
        state.edit(storePeer) { list ->
            list + ChatMessage(
                id = optimisticId,
                peerUserId = storePeer,
                senderUserId = me,
                text = displayText,
                createdAt = deps.clock.now(),
                isMine = true,
                receipt = ReceiptStatus.Sending,
                kind = ChatMessageKind.Video,
                imageWidth = plan.width,
                imageHeight = plan.height,
                posterJpeg = plan.posterJpeg,
                previewJpeg = plan.posterJpeg,
                mediaByteCount = plan.estimatedBytes,
                durationMs = plan.durationMs,
                pendingSync = true,
                replyTo = replyTo,
            )
        }
        beginTransfer(optimisticId, MediaTransfer.Phase.Preparing, plan.estimatedBytes)

        val video = try {
            val encoded = deps.video().encode(plan) { fraction -> progress(optimisticId, fraction) }
            // Into the sealed cache at once: the plaintext file lives only as long as the copy (C28, §1.1 rule 7).
            try {
                storeVideo(optimisticId, encoded.file)
            } finally {
                discardEncodedFile(encoded.file)
            }
            VideoInfo(encoded.width, encoded.height, encoded.durationMs, encoded.mime, encoded.posterJpeg, encoded.sizeBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = when {
                deps.videoTooLarge(e) && plan.quality == VideoUploadQuality.Original -> VIDEO_TOO_LARGE_ORIGINAL
                deps.videoTooLarge(e) -> VIDEO_TOO_LARGE_COMPRESSED
                else -> COULD_NOT_PREPARE_VIDEO
            }
            if (state.lockGeneration == generation) markVideoFailed(optimisticId, message)
            return message
        }
        if (state.lockGeneration != generation) return null

        // The bubble now has real geometry, duration and poster (`applyEncodedVideo`, MC:2748-2766).
        state.update(optimisticId) {
            it.copy(
                imageWidth = video.width,
                imageHeight = video.height,
                durationMs = video.durationMs,
                hasFullMedia = true,
                mediaByteCount = video.sizeBytes,
                posterJpeg = video.poster ?: it.posterJpeg,
                previewJpeg = video.poster ?: it.previewJpeg,
            )
        }

        if (notes) {
            sendNotesVideo(optimisticId, storePeer, trimmedCaption, video, replyTo, generation)
            return null
        }

        val signed = signedIn() ?: run {
            markVideoFailed(optimisticId, NOT_SIGNED_IN)
            return NOT_SIGNED_IN
        }
        state.persistSnapshot()

        if (!deps.isOnline()) {
            host().setOffline(true)
            markVideoFailed(optimisticId, WAITING_FOR_CONNECTION)
            return null
        }

        return try {
            val sent = finishVideoSend(optimisticId, storePeer, signed, video, trimmedCaption, replyTo, trackingTransfer = true, rekey = true)
            endTransfer(optimisticId, sent.id)
            state.setLastError(null)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = userMessage(e)
            if (state.lockGeneration == generation) {
                markVideoFailed(optimisticId, message)
                state.setLastError(message)
                state.persistSnapshot()
            }
            message
        }
    }

    /** A video in Notes: local at once, synced and re-keyed when online (MC:2646-2706). */
    private suspend fun sendNotesVideo(
        optimisticId: UUID,
        storePeer: UUID,
        caption: String,
        video: VideoInfo,
        replyTo: MessageReplyReference?,
        generation: Long,
    ) {
        state.update(optimisticId) { it.copy(receipt = ReceiptStatus.Sent, pendingSync = false) }
        state.persistThread(storePeer)
        var sentId: UUID? = null
        val signed = signedIn()
        if (deps.isOnline() && signed != null) {
            try {
                val sent = finishVideoSend(optimisticId, storePeer, signed, video, caption, replyTo, trackingTransfer = false, rekey = false)
                sentId = sent.id
                if (state.lockGeneration == generation) {
                    rekeyIntoNotes(
                        optimisticId,
                        ChatMessage(
                            id = sent.id,
                            peerUserId = storePeer,
                            senderUserId = signed.me,
                            text = sent.text,
                            createdAt = sent.createdAt,
                            createdAtWire = sent.createdAtWire,
                            isMine = true,
                            receipt = ReceiptStatus.Sent,
                            kind = ChatMessageKind.Video,
                            mediaObjectId = sent.mediaObjectId,
                            imageWidth = sent.imageWidth,
                            imageHeight = sent.imageHeight,
                            hasFullMedia = true,
                            posterJpeg = sent.posterJpeg ?: video.poster,
                            durationMs = sent.durationMs ?: video.durationMs,
                            replyTo = replyTo,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the local-only video (MC:2700-2702).
            }
        }
        endTransfer(optimisticId, sentId)
    }

    /** Re-sends a failed video from the sealed cache (`retryFailedVideo`, MC:2769-2820). */
    override suspend fun retryFailedVideo(messageId: UUID, storePeer: UUID): String? = detached {
        val signed = signedIn() ?: return@detached NOTHING_TO_RETRY
        val message = state.messages(storePeer)?.firstOrNull { it.id == messageId && it.isMine && it.kind == ChatMessageKind.Video }
            ?: return@detached NOTHING_TO_RETRY
        val size = cachedLength(messageId) ?: return@detached NOTHING_TO_RETRY
        val generation = state.lockGeneration
        state.update(messageId) { it.copy(receipt = ReceiptStatus.Sending, sendError = null) }
        val video = videoFromBubble(message, size)
        val caption = if (message.text == VIDEO || message.text.isEmpty()) "" else message.text
        beginTransfer(messageId, MediaTransfer.Phase.Transferring, size)
        try {
            val sent = finishVideoSend(messageId, storePeer, signed, video, caption, message.replyTo, trackingTransfer = true, rekey = true)
            endTransfer(messageId, sent.id)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val text = userMessage(e)
            if (state.lockGeneration == generation) markVideoFailed(messageId, text) else endTransfer(messageId, null)
            text
        }
    }

    /**
     * Upload from the sealed cache, poster thumb, seal, send, cache under the server id
     * (`finishVideoSend`, MC:2823-2929). The ring moves to "transferring" with the sealed size and to
     * "finishing" once the bytes are up (MC:2835-2853).
     */
    private suspend fun finishVideoSend(
        optimisticId: UUID,
        storePeer: UUID,
        signed: Signed,
        video: VideoInfo,
        caption: String,
        replyTo: MessageReplyReference?,
        trackingTransfer: Boolean,
        rekey: Boolean,
    ): ChatMessage {
        val generation = state.lockGeneration
        val apiPeer = state.apiPeer(storePeer)
        if (trackingTransfer) state.transfers.advance(optimisticId, MediaTransfer.Phase.Transferring, video.sizeBytes + SEALED_OVERHEAD_BYTES)
        val blob = deps.transfers().upload(
            PlainSource.LocalMedia(optimisticId),
            signed.token,
            if (trackingTransfer) { fraction -> progress(optimisticId, fraction) } else null,
        )
        // Sealing and sending still follow; the ring spins rather than sitting at 100 % (MC:2849-2853).
        if (trackingTransfer) state.transfers.advance(optimisticId, MediaTransfer.Phase.Finishing)

        val trimmedCaption = WireText.trimWhitespacesAndNewlines(caption)
        val displayText = trimmedCaption.ifEmpty { VIDEO }
        // Shrink the poster into an envelope-safe thumb (MC:2857-2864).
        val rawThumb = video.poster?.toByteArray() ?: deps.video().posterJpegFromLocal(optimisticId, POSTER_THUMB_EDGE)
        val previewJpeg = rawThumb?.let { withContext(deps.compute) { deps.images().chatPreviewJpeg(it) } }
        val peerPublic = deps.peerIdentities().publicKeyForSending(apiPeer)
        val sealed = sealMediaPayload(
            kind = MediaMessagePayload.KIND_VIDEO,
            mime = video.mime,
            width = video.width,
            height = video.height,
            key = blob.keyBase64,
            caption = trimmedCaption.ifEmpty { null },
            durationMs = video.durationMs,
            previewJpeg = previewJpeg,
            byteCount = video.sizeBytes,
            apiPeer = apiPeer,
            me = signed.me,
            peerPublic = peerPublic,
            replyTo = replyTo,
        )
        val dto = deps.api.sendMessage(
            signed.token,
            SendMessageRequest(apiPeer, optimisticId, ContentType.MEDIA, MessageCrypto.toWire(sealed.envelope), blob.mediaObjectId),
        )
        moveMedia(optimisticId, dto.id, null)
        savePlaintext(dto.id, sealed.payload)
        val usedPreview = sealed.usedPreview?.let(Bytes::of)
        val sent = ChatMessage(
            id = dto.id,
            peerUserId = storePeer,
            senderUserId = signed.me,
            text = displayText,
            createdAt = dto.createdAt,
            createdAtWire = dto.createdAtWire,
            isMine = true,
            receipt = receipt(dto),
            kind = ChatMessageKind.Video,
            mediaObjectId = blob.mediaObjectId,
            imageWidth = video.width,
            imageHeight = video.height,
            hasFullMedia = true,
            posterJpeg = usedPreview,
            previewJpeg = usedPreview,
            mediaByteCount = video.sizeBytes,
            durationMs = video.durationMs,
            sendError = null,
            replyTo = replyTo,
        )
        if (state.lockGeneration == generation) {
            if (rekey) reKey(storePeer, optimisticId, sent, appendIfMissing = false)
            state.persistSnapshot()
        }
        host().refreshConversations(force = true)
        return sent
    }

    /** `markVideoFailed` (MC:2931-2941). */
    private fun markVideoFailed(messageId: UUID, error: String) {
        state.transfers.end(messageId)
        markFailed(messageId, error)
    }

    // ---- voice ----

    /**
     * A recorded voice note (`sendVoice`, MC:3381-3555; media-voice-links §3.3). A transcript known up
     * front is sealed in the payload; otherwise [transcriptProvider] runs **beside** the send and its
     * result follows as an annotation ([AnnotationSender]). Voice notes in Notes stay on this device.
     */
    override suspend fun sendVoice(
        audio: ByteArray,
        durationMs: Int,
        storePeer: UUID,
        waveform: ByteArray?,
        transcript: String?,
        replyTo: MessageReplyReference?,
        transcriptProvider: (suspend (messageId: UUID) -> String?)?,
    ): String? = detached { performSendVoice(audio, durationMs, storePeer, waveform, transcript, replyTo, transcriptProvider) }

    private suspend fun performSendVoice(
        audio: ByteArray,
        durationMs: Int,
        storePeer: UUID,
        waveform: ByteArray?,
        transcript: String?,
        replyTo: MessageReplyReference?,
        transcriptProvider: (suspend (messageId: UUID) -> String?)?,
    ): String? {
        val optimisticId = UUID.randomUUID()
        val me = state.myUserId ?: NOTES_PEER_ID
        val notes = state.isNotes(storePeer)
        val generation = state.lockGeneration
        val wave = waveform?.let(Bytes::of)
        val cached = saveMedia(optimisticId, audio)
        if (state.lockGeneration != generation) return null
        val createdAt = deps.clock.now()
        state.edit(storePeer) { list ->
            list + ChatMessage(
                id = optimisticId,
                peerUserId = storePeer,
                senderUserId = me,
                text = VOICE_MESSAGE,
                createdAt = createdAt,
                isMine = true,
                receipt = ReceiptStatus.Sending,
                kind = ChatMessageKind.Voice,
                hasFullMedia = cached,
                durationMs = durationMs,
                voiceWaveform = wave,
                pendingSync = !notes,
                replyTo = replyTo,
            )
        }

        // Capped so a long note's transcript can never push the sealed message past the server's limit (MC:3415-3418).
        val trimmedTranscript = transcript?.let(MessageAnnotation::clampTranscript)?.ifEmpty { null }
        val displayText = trimmedTranscript ?: VOICE_MESSAGE
        if (trimmedTranscript == null && transcriptProvider != null) {
            // The bubble is visible now; transcribe beside the send rather than in front of it (MC:3419-3428).
            annotations.beginOwnTranscript(optimisticId, storePeer, transcriptProvider)
        }
        if (trimmedTranscript != null) state.update(optimisticId) { it.copy(transcript = trimmedTranscript) }

        if (notes) {
            state.update(optimisticId) {
                voiceBubble(it, displayText, ReceiptStatus.Sent, it.transcript ?: trimmedTranscript, sendError = null, pendingSync = false)
            }
            state.persistSnapshot()
            return null
        }

        val signed = signedIn() ?: return NOT_SIGNED_IN
        state.persistSnapshot()

        if (!deps.isOnline()) {
            host().setOffline(true)
            state.update(optimisticId) {
                voiceBubble(it, displayText, ReceiptStatus.Failed, it.transcript ?: trimmedTranscript, WAITING_FOR_CONNECTION, pendingSync = true)
            }
            state.persistSnapshot()
            return null
        }

        return try {
            finishVoiceSend(optimisticId, storePeer, signed, PlainSource.InMemory(audio), durationMs, wave, trimmedTranscript, displayText, replyTo)
            state.setLastError(null)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = userMessage(e)
            if (state.lockGeneration == generation) {
                state.update(optimisticId) {
                    voiceBubble(
                        it.copy(durationMs = it.durationMs ?: durationMs, voiceWaveform = it.voiceWaveform ?: wave),
                        displayText,
                        ReceiptStatus.Failed,
                        it.transcript ?: trimmedTranscript,
                        message,
                        pendingSync = true,
                    )
                }
                state.setLastError(message)
                state.persistSnapshot()
            }
            message
        }
    }

    /**
     * The voice bubble iOS rebuilds for a queued, failed or Notes note (MC:3441-3457, 3481-3500,
     * 3529-3548): only the note's own fields carry over.
     */
    private fun voiceBubble(
        existing: ChatMessage,
        text: String,
        receipt: ReceiptStatus,
        transcript: String?,
        sendError: String?,
        pendingSync: Boolean,
    ): ChatMessage = ChatMessage(
        id = existing.id,
        peerUserId = existing.peerUserId,
        senderUserId = existing.senderUserId,
        text = text,
        createdAt = existing.createdAt,
        createdAtWire = existing.createdAtWire,
        isMine = true,
        receipt = receipt,
        kind = ChatMessageKind.Voice,
        mediaObjectId = existing.mediaObjectId,
        hasFullMedia = existing.hasFullMedia,
        durationMs = existing.durationMs,
        voiceWaveform = existing.voiceWaveform,
        transcript = transcript,
        sendError = sendError,
        pendingSync = pendingSync,
        replyTo = existing.replyTo,
    )

    /**
     * Upload, payload `{t:"voice", mime:"audio/mp4", w:0, h:0, k, c?, d, wf?, re?}`, seal, send, cache,
     * re-key (`finishVoiceSend`, MC:3663-3788). The transcript `c` is the droppable part of the budget.
     */
    private suspend fun finishVoiceSend(
        optimisticId: UUID,
        storePeer: UUID,
        signed: Signed,
        audio: PlainSource,
        durationMs: Int,
        waveform: Bytes?,
        transcript: String?,
        displayText: String,
        replyTo: MessageReplyReference?,
    ): ChatMessage {
        val generation = state.lockGeneration
        val apiPeer = state.apiPeer(storePeer)
        val blob = deps.transfers().upload(audio, signed.token)

        var caption = transcript?.takeIf { it.isNotEmpty() }
        // `VoiceWaveform.encode`: empty → omitted (`VoiceWaveformView.swift:69-81`).
        val wf = waveform?.takeIf { it.size > 0 }?.let { B64.encode(it.toByteArray()) }
        fun payload(c: String?) = MediaMessagePayload(
            t = MediaMessagePayload.KIND_VOICE,
            mime = MIME_VOICE,
            w = 0,
            h = 0,
            k = blob.keyBase64,
            c = c,
            d = durationMs,
            wf = wf,
            re = replyTo,
        ).encoded()
        var payloadData = payload(caption)
        if (payloadData.size > MessageCrypto.MAX_MEDIA_PAYLOAD_PLAINTEXT_BYTES && caption != null) {
            caption = null
            payloadData = payload(null)
        }
        val peerPublic = deps.peerIdentities().publicKeyForSending(apiPeer)
        var sealed = deps.seal(payloadData, apiPeer, signed.me, peerPublic)
        if (sealed.size > MessageCrypto.MAX_SEALED_MEDIA_ENVELOPE_BYTES && caption != null) {
            caption = null
            payloadData = payload(null)
            sealed = deps.seal(payloadData, apiPeer, signed.me, peerPublic)
        }
        if (sealed.size > MessageCrypto.MAX_SEALED_MEDIA_ENVELOPE_BYTES) {
            throw ApiError.Server(ErrorCodes.VALIDATION_ERROR, VOICE_TOO_LARGE, 400)
        }
        val dto = deps.api.sendMessage(
            signed.token,
            SendMessageRequest(apiPeer, optimisticId, ContentType.MEDIA, MessageCrypto.toWire(sealed), blob.mediaObjectId),
        )
        moveMedia(optimisticId, dto.id, (audio as? PlainSource.InMemory)?.data)
        savePlaintext(dto.id, payloadData)

        val sent = ChatMessage(
            id = dto.id,
            peerUserId = storePeer,
            senderUserId = signed.me,
            text = displayText,
            createdAt = dto.createdAt,
            createdAtWire = dto.createdAtWire,
            isMine = true,
            receipt = receipt(dto),
            kind = ChatMessageKind.Voice,
            mediaObjectId = blob.mediaObjectId,
            hasFullMedia = true,
            durationMs = durationMs,
            voiceWaveform = waveform,
            transcript = transcript,
            replyTo = replyTo,
        )
        if (state.lockGeneration == generation) {
            val current = state.messages(storePeer)?.firstOrNull { it.id == optimisticId }
            if (current != null) {
                // Same note, new id: the bubble carries on (sinks hear `onMessageRekeyed`), keeping a
                // transcript that landed while it was uploading (MC:3766-3777).
                state.rekey(storePeer, optimisticId, if (sent.transcript == null) sent.copy(transcript = current.transcript) else sent)
                state.edit(storePeer) { host().foldSharedTranscripts(it) }
            } else {
                state.purge(listOf(optimisticId))
            }
        }
        annotations.noteVoiceSent(optimisticId, dto.id, storePeer, sentWithTranscript = caption != null)
        host().refreshConversations(force = true)
        if (state.lockGeneration == generation) state.persistSnapshot()
        return sent
    }

    // ---- transcripts ----

    /** A transcript made here for a received voice note, shared back as an annotation (`shareTranscript`, MC:3580-3593). */
    override suspend fun shareTranscript(transcript: String, voiceMessageId: UUID, storePeer: UUID) =
        detached { annotations.shareTranscript(transcript, voiceMessageId, storePeer) }

    // ---- offline queue ----

    /**
     * Sends what waited for the network (`flushPendingSends` / `performPendingFlush`, MC:4912-5057):
     * one flush at a time, overlapping callers await the running one (`OutboundSendQueue`). Text
     * always goes as text — a queued link message keeps its inline thumbnail, the large image may not
     * have survived a restart; photos, videos and voice notes re-send from the sealed cache.
     */
    override suspend fun flushOutbox() {
        flushJob?.let {
            it.await()
            return
        }
        val flush = scope.async(start = CoroutineStart.LAZY) { performPendingFlush() }
        flushJob = flush
        try {
            flush.await()
        } finally {
            if (flushJob === flush) flushJob = null
        }
    }

    private suspend fun performPendingFlush() {
        if (!deps.isOnline()) return
        val signed = signedIn() ?: return
        val generation = state.lockGeneration
        for (item in outboundItems(state.threads.value)) {
            if (state.lockGeneration != generation) return
            // The queue is derived from the threads: a bubble deleted since is not sent (messaging-core §11.9).
            val message = state.messages(item.storePeer)?.firstOrNull { it.id == item.messageId } ?: continue
            when (message.kind) {
                ChatMessageKind.Text -> try {
                    deliverPendingText(message.id, message.text, item.storePeer, signed, message.replyTo, message.linkPreview, generation)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Left pending; the next reconnect tries again (MC:4943-4945).
                }
                ChatMessageKind.Image -> {
                    val bytes = deps.media().readAll(message.id) ?: continue
                    val image = encodedFromBytes(bytes, message)
                    state.update(message.id) { it.copy(receipt = ReceiptStatus.Sending, sendError = null) }
                    try {
                        finishImageSend(message.id, item.storePeer, signed, image, item.caption, message.replyTo, rekey = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (state.lockGeneration == generation) markFailed(message.id, userMessage(e))
                    }
                }
                ChatMessageKind.Video -> {
                    val size = cachedLength(message.id) ?: continue
                    state.update(message.id) { it.copy(receipt = ReceiptStatus.Sending, sendError = null) }
                    try {
                        finishVideoSend(message.id, item.storePeer, signed, videoFromBubble(message, size), item.caption, message.replyTo,
                            trackingTransfer = false, rekey = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (state.lockGeneration == generation) markVideoFailed(message.id, userMessage(e))
                    }
                }
                ChatMessageKind.Voice -> {
                    if (!withContext(deps.io) { deps.media().has(message.id) }) continue
                    state.update(message.id) { it.copy(receipt = ReceiptStatus.Sending, sendError = null) }
                    try {
                        finishVoiceSend(message.id, item.storePeer, signed, PlainSource.LocalMedia(message.id), message.durationMs ?: 0,
                            message.voiceWaveform, message.transcript, message.text, message.replyTo)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (state.lockGeneration == generation) markFailed(message.id, userMessage(e))
                    }
                }
                ChatMessageKind.Todo -> Unit
            }
        }
        host().refreshConversations(force = true)
    }

    /** One queued send (`OutboundPendingItem`, `OutboundPending.swift:4-9`). */
    private class Outbound(val messageId: UUID, val storePeer: UUID, val caption: String, val createdAt: Instant)

    /**
     * `OutboundPending.items` (`OutboundPending.swift:14-59`): every `pendingSync` message of ours outside
     * Notes, oldest first, todos excepted; captions without the stand-in label. W2-MSG-CORE owns the
     * shared `OutboundPending`; this is the same rule for the flush (integration note: use theirs once merged).
     */
    private fun outboundItems(threads: Map<UUID, List<ChatMessage>>): List<Outbound> {
        val collected = ArrayList<Outbound>()
        for ((peer, messages) in threads) {
            if (peer == NOTES_PEER_ID) continue
            for (message in messages) {
                if (!message.pendingSync || !message.isMine) continue
                val caption = when (message.kind) {
                    ChatMessageKind.Image -> if (message.text == PHOTO || message.text.isEmpty()) "" else message.text
                    ChatMessageKind.Video -> if (message.text == VIDEO || message.text.isEmpty()) "" else message.text
                    ChatMessageKind.Text, ChatMessageKind.Voice -> message.text
                    ChatMessageKind.Todo -> continue
                }
                collected += Outbound(message.id, peer, caption, message.createdAt)
            }
        }
        return collected.sortedBy { it.createdAt }
    }

    // ---- shared steps ----

    private class Signed(val token: String, val me: UUID)

    /** iOS `guard let token, let me, let material` (MC:1611-1614). */
    private fun signedIn(): Signed? {
        val session = state.session ?: return null
        val me = state.myUserId ?: return null
        if (!deps.keyring.isUnlocked) return null
        return Signed(session.token, me)
    }

    private class Encoded(val bytes: ByteArray, val width: Int, val height: Int, val mime: String)

    /** The size and type of already prepared photo bytes (MC:3160-3166, 4951-4957). */
    private suspend fun encodedFromBytes(bytes: ByteArray, message: ChatMessage): Encoded {
        val images = deps.images()
        val size = withContext(deps.compute) { images.pixelSize(bytes) }
        return Encoded(
            bytes = bytes,
            width = size?.first ?: message.imageWidth ?: 0,
            height = size?.second ?: message.imageHeight ?: 0,
            mime = withContext(deps.compute) { images.mimeType(bytes) },
        )
    }

    private class VideoInfo(val width: Int, val height: Int, val durationMs: Int, val mime: String, val poster: Bytes?, val sizeBytes: Long)

    /** A queued or failed video rebuilt from its bubble and the cache (MC:2786-2793, 4988-4995). */
    private fun videoFromBubble(message: ChatMessage, sizeBytes: Long) = VideoInfo(
        width = message.imageWidth ?: 0,
        height = message.imageHeight ?: 0,
        durationMs = message.durationMs ?: 0,
        mime = MIME_VIDEO,
        poster = message.posterJpeg,
        sizeBytes = sizeBytes,
    )

    /** The plaintext length of a cached media file, or null when none is cached. */
    private suspend fun cachedLength(messageId: UUID): Long? = withContext(deps.io) {
        deps.media().openReader(messageId)?.use { it.length }
    }

    /** Streams an encoded video file into the sealed cache under [messageId]; nothing is visible before commit. */
    private suspend fun storeVideo(messageId: UUID, file: File) = withContext(deps.io) {
        val writer = deps.media().writer(messageId)
        try {
            file.inputStream().use { input ->
                val buffer = ByteArray(COPY_CHUNK_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) writer.write(buffer, 0, read)
                }
            }
            writer.commit()
        } catch (e: Throwable) {
            writer.abort()
            throw e
        } finally {
            writer.close()
        }
    }

    /**
     * Deletes the encoder's output once it is sealed in the cache. Only our own `cacheDir/shroud-*`
     * files are deleted (plan §1.1 rule 7, C25); a passthrough that hands back another file keeps it.
     */
    private suspend fun discardEncodedFile(file: File) = withContext(deps.io) {
        if (file.name.startsWith(SensitiveTempFiles.PREFIX)) file.delete()
    }

    /**
     * The plaintext media cached under [from] now belongs to [to] (iOS `saveSealedMedia(dto.id)` +
     * `removeCaches([optimisticID])`, MC:3249-3252): renamed when cached, else saved from [bytes].
     */
    private suspend fun moveMedia(from: UUID, to: UUID, bytes: ByteArray?) {
        if (from == to) return
        val media = deps.media()
        val moved = withContext(deps.io) {
            if (media.has(from)) {
                media.rename(from, to)
                true
            } else {
                false
            }
        }
        if (!moved && bytes != null) saveMedia(to, bytes)
    }

    /**
     * Saves plaintext media under [messageId] in the sealed cache (iOS `saveSealedMedia`, which never
     * throws): false when the store refused (locked, wiping, disk), and the bubble then says so
     * through [ChatMessage.hasFullMedia].
     */
    private suspend fun saveMedia(messageId: UUID, bytes: ByteArray): Boolean = try {
        deps.media().save(messageId, bytes)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private suspend fun savePlaintext(messageId: UUID, plaintext: ByteArray) =
        withContext(deps.io) { deps.store().savePlaintext(messageId, plaintext) }

    /**
     * After a send: [sent] takes the optimistic bubble's place ([ThreadState.rekey]: caches, transfer,
     * sinks). When the bubble went meanwhile, a text is appended — it is on the server now, as
     * `deliverPendingText` does (MC:4866-4877) — and media are not (MC:3276-3281); the optimistic
     * id's caches go either way.
     */
    private fun reKey(storePeer: UUID, optimisticId: UUID, sent: ChatMessage, appendIfMissing: Boolean) {
        if (state.messages(storePeer)?.any { it.id == optimisticId } == true) {
            state.rekey(storePeer, optimisticId, sent)
            return
        }
        if (appendIfMissing) state.edit(storePeer) { list -> if (list.any { it.id == sent.id }) list else list + sent }
        state.purge(listOf(optimisticId))
    }

    /**
     * Re-keys a synced Notes photo or video into the Notes thread, oldest first (MC:2473-2499,
     * 2672-2698): the server copy replaces the local note, a leftover of either id goes.
     */
    private fun rekeyIntoNotes(optimisticId: UUID, copy: ChatMessage) {
        val notes = state.messages(NOTES_PEER_ID) ?: return
        if (notes.any { it.id == optimisticId }) state.rekey(NOTES_PEER_ID, optimisticId, copy) else state.purge(listOf(optimisticId))
        state.edit(NOTES_PEER_ID) { list ->
            val others = list.filter { it.id != copy.id && it.id != optimisticId }
            (others + copy).sortedBy { it.createdAt }
        }
        state.persistThread(NOTES_PEER_ID)
    }

    private class SealedPayload(val payload: ByteArray, val envelope: ByteArray, val usedPreview: ByteArray?)

    /**
     * Builds and seals a media envelope within the budgets (`sealMediaPayload`, MC:3296-3373): the
     * preview `th` only when ≤ 6 KiB; dropped when the payload passes 12 KiB; dropped and sealed again
     * once when the envelope passes 60 KiB (one unused ratchet step — safe); still over → the iOS error.
     */
    private suspend fun sealMediaPayload(
        kind: String,
        mime: String,
        width: Int,
        height: Int,
        key: String,
        caption: String?,
        durationMs: Int?,
        previewJpeg: ByteArray?,
        byteCount: Long,
        apiPeer: UUID,
        me: UUID,
        peerPublic: ByteArray,
        replyTo: MessageReplyReference?,
        linkPreview: LinkPreview? = null,
    ): SealedPayload {
        val safePreview = previewJpeg?.takeIf { it.size <= MediaCrypto.MAX_ENVELOPE_PREVIEW_BYTES }
        fun encode(includePreview: Boolean): ByteArray = MediaMessagePayload(
            t = kind,
            mime = mime,
            w = width,
            h = height,
            k = key,
            c = caption,
            d = durationMs,
            th = if (includePreview) safePreview?.let(B64::encode) else null,
            s = byteCount,
            re = replyTo,
            lp = linkPreview,
        ).encoded()

        var includePreview = safePreview != null
        var payload = encode(includePreview)
        if (includePreview && payload.size > MessageCrypto.MAX_MEDIA_PAYLOAD_PLAINTEXT_BYTES) {
            includePreview = false
            payload = encode(false)
        }
        var sealed = deps.seal(payload, apiPeer, me, peerPublic)
        if (sealed.size > MessageCrypto.MAX_SEALED_MEDIA_ENVELOPE_BYTES && includePreview) {
            includePreview = false
            payload = encode(false)
            sealed = deps.seal(payload, apiPeer, me, peerPublic)
        }
        if (sealed.size > MessageCrypto.MAX_SEALED_MEDIA_ENVELOPE_BYTES) {
            throw ApiError.Server(ErrorCodes.VALIDATION_ERROR, MEDIA_TOO_LARGE, 400)
        }
        return SealedPayload(payload, sealed, if (includePreview) safePreview else null)
    }

    private fun beginTransfer(messageId: UUID, phase: MediaTransfer.Phase, totalBytes: Long?) {
        state.transfers.begin(messageId, isUpload = true)
        state.transfers.advance(messageId, phase, totalBytes)
    }

    /** Both ids: [ThreadState.rekey] moves a transfer to the server id (`endTransfer`, MC:2986-2988). */
    private fun endTransfer(optimisticId: UUID, sentId: UUID?) {
        state.transfers.end(optimisticId)
        if (sentId != null) state.transfers.end(sentId)
    }

    /** Progress from encoder and transport threads, hopped onto the pipeline's scope (`progressSink`, MC:2993-3000). */
    private fun progress(messageId: UUID, fraction: Double) {
        scope.launch { state.transfers.update(messageId, fraction) }
    }

    /** Runs [block] in the pipeline's scope and awaits it: the work outlives the caller, as an iOS `Task` (see the class KDoc). */
    private suspend fun <T> detached(block: suspend () -> T): T =
        scope.async(start = CoroutineStart.UNDISPATCHED) { block() }.await()

    companion object {
        const val PHOTO = "Photo"
        const val VIDEO = "Video"
        const val VOICE_MESSAGE = "Voice message"
        const val WAITING_FOR_CONNECTION = "Waiting for connection…"
        const val NOT_SIGNED_IN = "Not signed in."
        const val NOTHING_TO_RETRY = "Nothing to retry."
        const val COULD_NOT_PREPARE_PHOTO = "Could not prepare that photo."
        const val COULD_NOT_PREPARE_VIDEO = "Could not prepare that video."
        const val VIDEO_TOO_LARGE_ORIGINAL = "This video is too large to send at original quality. Trim it or choose a lower quality."
        const val VIDEO_TOO_LARGE_COMPRESSED = "This video is too large even after compression. Try a shorter clip."
        const val MEDIA_TOO_LARGE = "Media message is too large to send. Try a shorter video or smaller photo."
        const val VOICE_TOO_LARGE = "Media message is too large to send. Try a shorter voice note."
        const val SOMETHING_WENT_WRONG = "Something went wrong. Try again."

        /** The voice note container every client plays (messaging-core D7, MC:3690). */
        const val MIME_VOICE = "audio/mp4"
        const val MIME_JPEG = "image/jpeg"
        const val MIME_VIDEO = "video/mp4"

        /** Nonce (12) + GCM tag (16): a sealed blob is this much larger than its plaintext (media-voice-links §1.2). */
        const val SEALED_OVERHEAD_BYTES = 28L

        /** The poster edge a video without an encoder thumbnail is sampled at (MC:2862). */
        const val POSTER_THUMB_EDGE = 320

        private const val COPY_CHUNK_BYTES = 64 * 1024

        /** `MessageDecoder.receiptStatus(from:)` (`MessageDecoder.swift:20-24`). */
        internal fun receipt(dto: MessageDto): ReceiptStatus = when {
            dto.read == true -> ReceiptStatus.Read
            dto.delivered == true -> ReceiptStatus.Delivered
            else -> ReceiptStatus.Sent
        }

        /**
         * What a send shows when it failed (`SessionController.userMessage`, `SessionController.swift:210-225`):
         * the server's text, the transport text, the key-change text, else the generic one.
         */
        fun userMessage(error: Throwable): String = when (error) {
            is ApiError -> error.userMessage
            is PeerIdentityChangedException -> PeerIdentityChangedException.MESSAGE
            else -> SOMETHING_WENT_WRONG
        }

        /** The synced todo marker, `[todo:1]` done / `[todo:0]` open (`NotesLocal.syncedTodoPlaintext`, `NotesLocal.swift:62-65`). */
        internal fun todoWire(text: String, done: Boolean): String = "[todo:${if (done) "1" else "0"}]$text"
    }
}
