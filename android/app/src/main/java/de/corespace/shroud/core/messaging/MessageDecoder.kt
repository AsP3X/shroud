package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.crypto.MessageCrypto
import de.corespace.shroud.core.crypto.OpenAs
import de.corespace.shroud.core.crypto.PeerLocks
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.model.hasLargeLinkImage
import de.corespace.shroud.core.net.ContentType
import de.corespace.shroud.core.net.ConversationItemDto
import de.corespace.shroud.core.net.MessageDto
import de.corespace.shroud.core.net.wire.LenientJson
import de.corespace.shroud.core.net.wire.LinkPreview
import de.corespace.shroud.core.net.wire.MediaMessagePayload
import de.corespace.shroud.core.net.wire.MessageReplyReference
import de.corespace.shroud.core.net.wire.MessageTextPayload
import de.corespace.shroud.core.net.wire.WireText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

/**
 * Our identity keys, for opening envelopes (`IdentityKeyMaterial` behind `CryptoController.withMaterial`).
 * Never let the arrays escape [withKeys]'s block.
 */
interface OwnKeys {
    val isUnlocked: Boolean

    /** Runs [block] with our agreement private key and identity public key; null while chats are locked. */
    fun <T> withKeys(block: (ourPrivate: ByteArray, ourPublic: ByteArray) -> T): T?
}

/** `MessageCrypto.open` (W1-CRYPTO), behind an interface so tests can count opens. Blocking CPU work. */
fun interface EnvelopeOpener {
    fun open(
        envelope: ByteArray,
        peerUserId: UUID,
        ourPrivate: ByteArray,
        ourPublic: ByteArray,
        senderPublic: ByteArray,
        role: OpenAs,
        sentAt: Instant,
    ): ByteArray
}

/**
 * Decrypts a server [MessageDto] into a bubble (`MessageDecoder.swift`; messaging-core §9).
 *
 * The Double Ratchet is one-shot: the first open as recipient spends the message key, so every later
 * decode of the same id must come from the sealed plaintext cache. The pipeline therefore looks for
 * a held readable copy and the cache before it opens, and saves the plaintext right after a
 * successful open. iOS gets that sequence atomically from `@MainActor`; here it runs under the
 * peer's [PeerLocks] lock with the cache re-checked once the lock is held (messaging-core §23 item 2,
 * D4), so an `ingest` and a page decode of the same message open it once. Fetching the sender's key
 * (network on first use) stays outside the lock.
 *
 * Media is read from the local caches only — never downloaded here (`MessageDecoder.swift:296-300`):
 * [hasMedia] says whether the decrypted bytes are on this device ([ChatMessage.hasFullMedia]).
 *
 * @param store the sealed plaintext cache; its reads and writes are blocking and run on [io].
 * @param compute where [opener] runs (CPU).
 */
class MessageDecoder(
    private val store: MessagingStore,
    private val keys: OwnKeys,
    private val opener: EnvelopeOpener,
    private val peerLocks: PeerLocks,
    private val hasMedia: (UUID) -> Boolean,
    private val io: CoroutineDispatcher,
    private val compute: CoroutineDispatcher,
) {
    /**
     * What a decode reads besides the DTO (`MessageDecoder.Context`, `MessageDecoder.swift:9-18`):
     * snapshots of the chat list and threads taken on the main thread, and the sender's pinned key
     * (`PeerIdentities.resolvePublicKey`; our own key when the sender is us, `MessagingController.swift:4466-4469`).
     */
    class Context(
        val me: UUID,
        val conversations: List<ConversationItemDto>,
        val threads: Map<UUID, List<ChatMessage>>,
        val resolveSenderKey: suspend (UUID) -> ByteArray,
    )

    /**
     * The bubble for [dto] (`decodeMessage`, `MessagingController.swift:4447-4506`). [forcePeer] keys
     * it under that thread whatever the decode guessed: a page decode always knows its thread
     * (messaging-core §8.2 Android improvement; iOS forces only for Notes).
     */
    suspend fun decode(dto: MessageDto, context: Context, forcePeer: UUID? = null): ChatMessage {
        val message = decodeAsIs(dto, context)
        return if (forcePeer != null && message.peerUserId != forcePeer) message.copy(peerUserId = forcePeer) else message
    }

    /** `MessageDecoder.decode`, `MessageDecoder.swift:30-294`. */
    private suspend fun decodeAsIs(dto: MessageDto, context: Context): ChatMessage {
        val me = context.me
        val isMine = dto.senderUserId == me
        // `:36-39`: our own message belongs to the conversation's peer.
        val peer = if (isMine) {
            context.conversations.firstOrNull { it.id == dto.conversationId }?.peer?.id ?: dto.senderUserId
        } else {
            dto.senderUserId
        }
        val receipt = if (isMine) receiptStatus(dto) else ReceiptStatus.Sent
        val isMedia = dto.contentType == ContentType.MEDIA
        val base = Base(dto, peer, isMine, receipt)

        if (dto.deletedForEveryone) {
            // `:44-58`.
            return base.message(
                text = ThreadMessageMerge.MESSAGE_DELETED,
                deleted = true,
                kind = if (isMedia) ChatMessageKind.Image else ChatMessageKind.Text,
                mediaObjectId = dto.mediaObjectId,
            )
        }

        val existing = context.threads[peer]?.firstOrNull { it.id == dto.id }
            ?: context.threads.values.asSequence().flatten().firstOrNull { it.id == dto.id }
        if (existing != null && !existing.deleted && !ThreadMessageMerge.isFailedDecryptText(existing.text)) {
            return fromHeld(existing, base, isMedia)
        }

        // `:138-166`: an earlier open left the plaintext in the cache.
        cachedPlaintext(dto.id, isMedia)?.let { cached ->
            return if (isMedia) decodeMedia(base, cached) else textMessage(base, cached)
        }

        // `:168-184`.
        val envelope = dto.ciphertext?.let(MessageCrypto::fromWire)
            ?: return base.message(
                text = if (isMedia) ChatListFormatting.PHOTO else ThreadMessageMerge.UNABLE_TO_DECRYPT,
                kind = if (isMedia) ChatMessageKind.Image else ChatMessageKind.Text,
                mediaObjectId = dto.mediaObjectId,
            )

        return try {
            val plain = open(dto, envelope, peer, isMine, me, context)
            if (isMedia) decodeMedia(base, plain) else textMessage(base, plain)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // `:246-293`: whatever this device still has, else the placeholder.
            val cached = withContext(io) { store.plaintext(dto.id) }
            if (!isMedia && cached != null) {
                LenientJson.utf8OrNull(cached)?.let { return textMessage(base, MessageTextPayload.parse(it)) }
            }
            if (isMedia && cached != null) return decodeMedia(base, cached)
            if (existing != null && !existing.deleted && !ThreadMessageMerge.isFailedDecryptText(existing.text)) return existing
            base.message(
                text = if (isMedia) ThreadMessageMerge.MEDIA_PLACEHOLDER else ThreadMessageMerge.UNABLE_TO_DECRYPT,
                kind = if (isMedia) ChatMessageKind.Image else ChatMessageKind.Text,
                mediaObjectId = dto.mediaObjectId,
                hasFullMedia = hasMedia(dto.id),
            )
        }
    }

    /**
     * The thread already shows a readable copy (`MessageDecoder.swift:60-136`): keep it, raise our
     * receipt, re-cache a text body the cache lost, read back an envelope an old build stored as text,
     * note media that is on the device by now, and repair a media note an iOS bug stamped as a photo.
     */
    private suspend fun fromHeld(existing: ChatMessage, base: Base, isMedia: Boolean): ChatMessage {
        val dto = base.dto
        var merged = existing
        if (base.isMine) {
            val serverReceipt = receiptStatus(dto)
            if (serverReceipt.rank > existing.receipt.rank) merged = merged.copy(receipt = serverReceipt)
        }
        if (!isMedia) {
            withContext(io) {
                // `:75-86`: re-seal what the bubble carries, quote included.
                if (store.plaintext(dto.id) == null) {
                    store.savePlaintext(
                        dto.id,
                        MessageTextPayload.wire(existing.text, existing.replyTo, existing.linkPreview).toByteArray(Charsets.UTF_8),
                    )
                }
            }
            // `:87-96`: a build without reply support stored the raw envelope as the text.
            if (merged.replyTo == null && merged.linkPreview == null) {
                val parsed = MessageTextPayload.parse(existing.text)
                if (parsed.replyTo != null || parsed.linkPreview != null) {
                    merged = merged.copy(text = parsed.body, replyTo = parsed.replyTo, linkPreview = parsed.linkPreview)
                }
            }
        }
        // `:97-116`: the bytes reached the media cache meanwhile.
        val holdsMedia = existing.kind == ChatMessageKind.Image || existing.kind == ChatMessageKind.Voice ||
            existing.kind == ChatMessageKind.Video || existing.hasLargeLinkImage
        if (!merged.hasFullMedia && holdsMedia && hasMedia(dto.id)) merged = merged.copy(hasFullMedia = true)
        // `:117-133`: iOS 26/27's JSONDecoder failed the payload and stamped every media note a photo.
        if (isMedia && existing.kind == ChatMessageKind.Image) {
            val plain = withContext(io) { store.plaintext(dto.id) }
            val payload = plain?.let(MediaMessagePayload::parse)
            if (plain != null && payload != null && (payload.isVoice || payload.isVideo || payload.isLink)) {
                return decodeMedia(base, plain)
            }
        }
        return merged
    }

    /**
     * Opens [envelope] once (`MessageDecoder.swift:186-216`) and caches the plaintext. Our own
     * messages and notes to self open as [OpenAs.Sender] from our self box and never advance a
     * peer's ratchet; a peer's message opens as [OpenAs.Recipient] with their pinned key.
     */
    private suspend fun open(dto: MessageDto, envelope: ByteArray, peer: UUID, isMine: Boolean, me: UUID, context: Context): ByteArray {
        val isSelfNote = isMine && peer == me
        // Network on first use (TOFU pin), so outside the lock (messaging-core §23 item 2).
        val senderKey = if (isMine) null else context.resolveSenderKey(dto.senderUserId)
        val lockPeer = if (isSelfNote) me else peer
        return peerLocks.withPeer(lockPeer) {
            // A concurrent decode of the same message may have opened it while we waited.
            withContext(io) { store.plaintext(dto.id) }
                ?.takeIf { dto.contentType != ContentType.MEDIA || MediaMessagePayload.parse(it) != null }
                ?.let { return@withPeer it }
            val plain = withContext(compute) {
                keys.withKeys { ourPrivate, ourPublic ->
                    if (isMine) {
                        opener.open(envelope, if (isSelfNote) me else peer, ourPrivate, ourPublic, ourPublic, OpenAs.Sender, dto.createdAt)
                    } else {
                        opener.open(envelope, dto.senderUserId, ourPrivate, ourPublic, senderKey!!, OpenAs.Recipient, dto.createdAt)
                    }
                } ?: throw CryptoError.Locked
            }
            withContext(io) { store.savePlaintext(dto.id, plain) }
            plain
        }
    }

    /** The cached plaintext when it fits the content type (`MessageDecoder.swift:138-140`). */
    private suspend fun cachedPlaintext(id: UUID, isMedia: Boolean): ByteArray? {
        val cached = withContext(io) { store.plaintext(id) } ?: return null
        return if (!isMedia || MediaMessagePayload.parse(cached) != null) cached else null
    }

    /** A text bubble from its plaintext: a reply/preview envelope, or the raw text (`MessageDecoder.swift:151-165, 229-245`). */
    private fun textMessage(base: Base, plain: ByteArray): ChatMessage =
        textMessage(base, MessageTextPayload.parse(LenientJson.utf8OrNull(plain) ?: ThreadMessageMerge.BINARY_MESSAGE))

    private fun textMessage(base: Base, parsed: MessageTextPayload.Parsed): ChatMessage =
        base.message(text = parsed.body, replyTo = parsed.replyTo, linkPreview = parsed.linkPreview)

    /**
     * A media bubble from its payload and the local cache only (`decodeMedia`,
     * `MessageDecoder.swift:301-434`): a large link image (a text bubble), a voice note, a video, or a
     * photo; "Media" when neither a payload nor bytes exist. A video shows its poster only once the
     * video itself is on the device (`:382-383`).
     */
    private fun decodeMedia(base: Base, plain: ByteArray): ChatMessage {
        val dto = base.dto
        val cached = hasMedia(dto.id)
        val payload = MediaMessagePayload.parse(plain)
        val preview = payload?.previewJpeg?.let(Bytes::adopt)

        if (payload != null && payload.isLink) {
            // `:314-337`.
            return base.message(
                text = payload.c ?: "",
                kind = ChatMessageKind.Text,
                mediaObjectId = dto.mediaObjectId,
                imageWidth = payload.w.takeIf { it > 0 },
                imageHeight = payload.h.takeIf { it > 0 },
                hasFullMedia = cached,
                previewJpeg = preview,
                mediaByteCount = payload.s,
                replyTo = payload.re,
                linkPreview = payload.lp,
            )
        }
        if (payload != null && payload.isVoice) {
            // `:339-360`.
            val transcript = payload.c?.let(WireText::trimWhitespacesAndNewlines)
            return base.message(
                text = transcript?.takeIf { it.isNotEmpty() } ?: ChatListFormatting.VOICE_MESSAGE,
                kind = ChatMessageKind.Voice,
                mediaObjectId = dto.mediaObjectId,
                hasFullMedia = cached,
                durationMs = payload.d,
                voiceWaveform = waveform(payload.wf),
                transcript = transcript,
                replyTo = payload.re,
            )
        }
        if (payload != null && payload.isVideo) {
            // `:362-390`.
            val caption = payload.c?.let(WireText::trimWhitespacesAndNewlines) ?: ""
            return base.message(
                text = caption.ifEmpty { ChatListFormatting.VIDEO },
                kind = ChatMessageKind.Video,
                mediaObjectId = dto.mediaObjectId,
                imageWidth = payload.w,
                imageHeight = payload.h,
                posterJpeg = if (cached) preview else null,
                previewJpeg = preview,
                mediaByteCount = payload.s,
                hasFullMedia = cached,
                durationMs = payload.d,
                replyTo = payload.re,
            )
        }
        if (!cached && payload == null) {
            // `:398-412`.
            return base.message(text = ThreadMessageMerge.MEDIA_PLACEHOLDER, kind = ChatMessageKind.Image, mediaObjectId = dto.mediaObjectId)
        }
        // `:392-433`.
        val caption = payload?.c?.let(WireText::trimWhitespacesAndNewlines) ?: ""
        return base.message(
            text = caption.ifEmpty { ChatListFormatting.PHOTO },
            kind = ChatMessageKind.Image,
            mediaObjectId = dto.mediaObjectId,
            imageWidth = payload?.w,
            imageHeight = payload?.h,
            hasFullMedia = cached,
            previewJpeg = preview,
            mediaByteCount = payload?.s,
            replyTo = payload?.re,
        )
    }

    /** The fields every bubble of one DTO shares. */
    private class Base(val dto: MessageDto, val peer: UUID, val isMine: Boolean, val receipt: ReceiptStatus) {
        fun message(
            text: String,
            deleted: Boolean = false,
            kind: ChatMessageKind = ChatMessageKind.Text,
            mediaObjectId: UUID? = null,
            imageWidth: Int? = null,
            imageHeight: Int? = null,
            hasFullMedia: Boolean = false,
            posterJpeg: Bytes? = null,
            previewJpeg: Bytes? = null,
            mediaByteCount: Long? = null,
            durationMs: Int? = null,
            voiceWaveform: Bytes? = null,
            transcript: String? = null,
            replyTo: MessageReplyReference? = null,
            linkPreview: LinkPreview? = null,
        ) = ChatMessage(
            id = dto.id,
            peerUserId = peer,
            senderUserId = dto.senderUserId,
            text = text,
            createdAt = dto.createdAt,
            createdAtWire = dto.createdAtWire,
            isMine = isMine,
            deleted = deleted,
            receipt = receipt,
            kind = kind,
            mediaObjectId = mediaObjectId,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            hasFullMedia = hasFullMedia,
            posterJpeg = posterJpeg,
            previewJpeg = previewJpeg,
            mediaByteCount = mediaByteCount,
            durationMs = durationMs,
            voiceWaveform = voiceWaveform,
            transcript = transcript,
            replyTo = replyTo,
            linkPreview = linkPreview,
        )
    }

    companion object {
        /** `read` → read, else `delivered` → delivered, else sent (`MessageDecoder.swift:20-24`). Our own messages only. */
        fun receiptStatus(dto: MessageDto): ReceiptStatus = when {
            dto.read == true -> ReceiptStatus.Read
            dto.delivered == true -> ReceiptStatus.Delivered
            else -> ReceiptStatus.Sent
        }

        /** `VoiceWaveform.decode` (`ShroudUI/Components/VoiceWaveformView.swift:76-79`): strict Base64, empty → none. */
        private fun waveform(base64: String?): Bytes? =
            base64?.let(B64::decodeStrict)?.takeIf { it.isNotEmpty() }?.let(Bytes::adopt)
    }
}
