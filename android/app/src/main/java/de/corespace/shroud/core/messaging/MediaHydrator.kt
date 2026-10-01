package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.crypto.MessageCrypto
import de.corespace.shroud.core.crypto.OpenAs
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.core.model.hasLargeLinkImage
import de.corespace.shroud.core.net.wire.MediaMessagePayload
import de.corespace.shroud.core.net.wire.WireText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Media on demand (messaging-core §13, §9.1; media-voice-links §3.5; the [MediaLoader] seam of plan
 * §1.7.7), ported from `MessagingController.swift` (MC below): photos and videos download on an
 * explicit tap, voice notes and large link images when their bubble appears. One job per message
 * id; later callers await it (`mediaHydrateTasks`, MC:137).
 *
 * The blob key comes from the media payload cached when the message was first opened; when that is
 * missing it is recovered from history (`mediaPayloadData`, MC:4013-4086) — our own message through
 * the self box, someone else's opened as recipient **only when no plaintext was ever cached** for
 * the id: the Double Ratchet is one-shot, a second open would desync it.
 *
 * Android (plan C7, C8): "attach" sets [ChatMessage.hasFullMedia] (plus duration, transcript or
 * poster); the decrypted bytes stream from the server into the sealed media cache
 * ([de.corespace.shroud.core.media.MediaTransfers.downloadInto], committed only after the GCM tag
 * verified) and are read back through [mediaBytes] or `LocalMediaStore.openReader`. A message deleted
 * for everyone while its bytes were downloading gets nothing attached and its cache entry removed
 * (`stillHoldsMedia`, MC:4099-4105).
 *
 * Also a [MessageArtifactSinks]: purged messages stop their downloads, a lock stops them all —
 * `MessagingController.registerArtifactSink` registers it (iOS `purgeLocalMessageArtifacts`,
 * MC:1962-1975, `lockSensitiveMemory`).
 */
class MediaHydrator(
    private val state: ThreadState,
    private val deps: SendDependencies,
) : MediaLoader, MessageArtifactSinks {
    private val job = SupervisorJob(deps.scope.coroutineContext[Job])
    private val scope = CoroutineScope(deps.scope.coroutineContext + job)
    private val jobs = HashMap<UUID, Job>()

    /** Downloads a photo's full bytes; only from an explicit tap (`ensureImageLoaded`, MC:3891-3910). */
    override suspend fun ensureImageLoaded(message: ChatMessage) {
        if (message.kind != ChatMessageKind.Image || message.deleted || isOnDevice(message)) return
        deduplicated(message.id) { hydrateImage(message) }
    }

    /** Downloads a video's full bytes; only from an explicit tap (`ensureVideoLoaded`, MC:3004-3023). */
    override suspend fun ensureVideoLoaded(message: ChatMessage) {
        if (message.kind != ChatMessageKind.Video || message.deleted || isOnDevice(message)) return
        deduplicated(message.id) { hydrateVideo(message) }
    }

    /** Loads a voice note for playback; safe from every appearance (`ensureVoiceLoaded`, MC:3802-3821). */
    override suspend fun ensureVoiceLoaded(message: ChatMessage) {
        if (message.kind != ChatMessageKind.Voice || message.deleted || isOnDevice(message)) return
        deduplicated(message.id) { hydrateVoice(message) }
    }

    /**
     * Loads the large image of a link preview when its bubble appears (`ensureLinkImageLoaded`,
     * MC:3968-3983): small, so it downloads on its own, from Shroud's server — the recipient never
     * contacts the website.
     */
    override suspend fun ensureLinkImageLoaded(message: ChatMessage) {
        if (!needsLinkImage(message) || isOnDevice(message)) return
        deduplicated(message.id) { hydrateLinkImage(message) }
    }

    /** Cancels a download (the ring's X) and ends its transfer; uploads cannot be cancelled (`cancelMediaDownload`, MC:2947-2952). */
    override fun cancel(messageId: UUID) {
        val running = jobs.remove(messageId) ?: return
        running.cancel()
        state.transfers.end(messageId)
    }

    /** Stops every download (lock, sign-out). */
    override fun cancelAll() {
        val running = jobs.toMap()
        jobs.clear()
        for ((id, download) in running) {
            download.cancel()
            state.transfers.end(id)
        }
    }

    /** The decrypted bytes on this device, or null (plan C8). */
    override suspend fun mediaBytes(messageId: UUID): ByteArray? = deps.media().readAll(messageId)

    override fun onPurged(messageIds: Collection<UUID>) {
        for (id in messageIds) {
            jobs.remove(id)?.cancel()
            state.transfers.end(id)
        }
    }

    override fun onSensitiveMemoryLocked() = cancelAll()

    // ---- hydrate ----

    /** `hydrateImage` (MC:3912-3959). */
    private suspend fun hydrateImage(message: ChatMessage) {
        if (message.kind != ChatMessageKind.Image || message.deleted) return
        if (cached(message.id)) {
            attach(message) { it.copy(hasFullMedia = true) }
            return
        }
        // Notes and offline-only photos never have a server media id (MC:3923-3927).
        val mediaId = message.mediaObjectId ?: return
        val token = state.session?.token ?: return
        if (!deps.keyring.isUnlocked) return
        val generation = state.lockGeneration
        beginDownload(message.id, message.mediaByteCount)
        try {
            val payload = payload(message) ?: return
            deps.transfers().downloadInto(mediaId, payload.k, token, message.id) { fraction -> progress(message.id, fraction) }
            coroutineContext.ensureActive()
            state.transfers.advance(message.id, MediaTransfer.Phase.Finishing)
            if (!keepDownload(message, generation)) return
            attach(message) { it.copy(hasFullMedia = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Leave the placeholder; a tap or a reopen tries again (MC:3956-3958).
        } finally {
            state.transfers.end(message.id)
        }
    }

    /** `hydrateVideo` (MC:3025-3091) + `updateMessageVideo` (MC:3093-3140). */
    private suspend fun hydrateVideo(message: ChatMessage) {
        if (message.kind != ChatMessageKind.Video || message.deleted) return
        if (cached(message.id)) {
            attach(message) { it.copy(hasFullMedia = true, kind = ChatMessageKind.Video) }
            return
        }
        val mediaId = message.mediaObjectId ?: return
        val token = state.session?.token ?: return
        if (!deps.keyring.isUnlocked) return
        val generation = state.lockGeneration
        beginDownload(message.id, message.mediaByteCount)
        try {
            // Payload missing (a race before the first decrypt finished): a reopen retries (MC:3053-3056).
            val payload = payload(message) ?: return
            deps.transfers().downloadInto(mediaId, payload.k, token, message.id) { fraction -> progress(message.id, fraction) }
            coroutineContext.ensureActive()
            // The poster is seconds of work on a long clip: the ring keeps spinning (MC:3065-3066).
            state.transfers.advance(message.id, MediaTransfer.Phase.Finishing)
            if (!keepDownload(message, generation)) return
            // A still for the bubble (MC:3071-3072).
            val poster = deps.video().posterJpegFromLocal(message.id, POSTER_EDGE)
            if (state.lockGeneration != generation) return
            if (!stillHoldsMedia(message.id, message.peerUserId)) {
                withContext(deps.io) { deps.store().removeCaches(listOf(message.id)) }
                return
            }
            attach(message) { held ->
                // Caption: set at decode; only fill a generic placeholder, in place so reply, preview
                // and reactions survive (MC:3129-3136).
                val caption = payload.c?.let(WireText::trimWhitespacesAndNewlines).orEmpty()
                held.copy(
                    hasFullMedia = true,
                    kind = ChatMessageKind.Video,
                    durationMs = payload.d ?: held.durationMs,
                    imageWidth = payload.w.takeIf { it > 0 } ?: held.imageWidth,
                    imageHeight = payload.h.takeIf { it > 0 } ?: held.imageHeight,
                    posterJpeg = held.posterJpeg ?: poster?.let(Bytes::of),
                    text = if (caption.isNotEmpty() && (held.text == SendPipeline.VIDEO || held.text == MEDIA_LABEL)) caption else held.text,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Leave the placeholder; reopen / tap to retry (MC:3088-3090).
        } finally {
            state.transfers.end(message.id)
        }
    }

    /** `hydrateVoice` (MC:3823-3859) + `updateMessageVoice` (MC:3861-3876). No transfer ring for voice. */
    private suspend fun hydrateVoice(message: ChatMessage) {
        if (message.kind != ChatMessageKind.Voice || message.deleted) return
        if (cached(message.id)) {
            attach(message) { it.copy(hasFullMedia = true) }
            return
        }
        val mediaId = message.mediaObjectId ?: return
        val token = state.session?.token ?: return
        if (!deps.keyring.isUnlocked) return
        val generation = state.lockGeneration
        try {
            val payload = payload(message) ?: return
            deps.transfers().downloadInto(mediaId, payload.k, token, message.id)
            // Deleted while the file was downloading: nothing goes onto the tombstone (MC:3846-3847).
            if (!keepDownload(message, generation)) return
            attach(message) { it.copy(hasFullMedia = true, durationMs = payload.d ?: it.durationMs, transcript = payload.c ?: it.transcript) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Leave the placeholder; reopening retries (MC:3856-3858).
        }
    }

    /** `hydrateLinkImage` (MC:3985-4009). No transfer ring: link images are small. */
    private suspend fun hydrateLinkImage(message: ChatMessage) {
        if (!needsLinkImage(message)) return
        if (cached(message.id)) {
            attach(message) { it.copy(hasFullMedia = true) }
            return
        }
        val mediaId = message.mediaObjectId ?: return
        val token = state.session?.token ?: return
        if (!deps.keyring.isUnlocked) return
        val generation = state.lockGeneration
        try {
            val payload = payload(message) ?: return
            deps.transfers().downloadInto(mediaId, payload.k, token, message.id)
            coroutineContext.ensureActive()
            if (!keepDownload(message, generation)) return
            attach(message) { it.copy(hasFullMedia = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The block keeps its placeholder; the next appearance tries again (MC:4006-4008).
        }
    }

    // ---- payload recovery ----

    /** The media payload of [message] with its blob key (MC:3050-3052, 3840-3842). */
    private suspend fun payload(message: ChatMessage): MediaMessagePayload? =
        payloadData(message)?.let(MediaMessagePayload::parse)

    /**
     * The sealed media payload JSON (with the AES file key) for [message] (`mediaPayloadData`,
     * MC:4013-4086; messaging-core §9.1). Never re-opens an inbound envelope that was opened before:
     * that would advance and desync the Double Ratchet.
     *
     * Android: the history request names the API peer (our own id for Notes; iOS sends the thread
     * key), and the inbound open runs under the sender's peer lock with the cache re-checked inside
     * it (plan §1.4, messaging-core §23 item 2), so a concurrent page decode cannot open it twice.
     */
    internal suspend fun payloadData(message: ChatMessage): ByteArray? {
        cachedPayload(message.id)?.let { return it }
        // A cached text may be the display label if something wrote the wrong blob — ignored (MC:4021).
        val token = state.session?.token ?: return null
        val apiPeer = state.apiPeer(message.peerUserId)
        if (message.isMine) {
            val response = deps.api.messages(token, apiPeer, OWN_RECOVERY_PAGE)
            val dto = response.messages.firstOrNull { it.id == message.id } ?: return null
            val envelope = dto.ciphertext?.let(MessageCrypto::fromWire) ?: return null
            // Our own message: the self box, no ratchet (MC:4032-4040).
            val payload = deps.open(envelope, apiPeer, null, OpenAs.Sender, dto.createdAt) ?: return null
            if (MediaMessagePayload.parse(payload) == null) return null
            savePlaintext(message.id, payload)
            return payload
        }

        // Inbound recovery: the thread was reloaded before the first open saved the payload (MC:4048-4058).
        val response = deps.api.messages(token, apiPeer, INBOUND_RECOVERY_PAGE)
        val dto = response.messages.firstOrNull { it.id == message.id } ?: return null
        val envelope = dto.ciphertext?.let(MessageCrypto::fromWire) ?: return null
        // A concurrent decode of the same message may have cached it meanwhile (MC:4060-4063).
        cachedPayload(message.id)?.let { return it }
        val senderPublic = deps.peerIdentities().resolvePublicKey(dto.senderUserId)
        return deps.peerLocks.withPeer(dto.senderUserId) {
            // Only when no plaintext at all is cached: a non-payload blob must not burn a second open (MC:4069-4073).
            if (withContext(deps.io) { deps.store().plaintext(message.id) } != null) return@withPeer null
            val payload = deps.open(envelope, dto.senderUserId, senderPublic, OpenAs.Recipient, dto.createdAt) ?: return@withPeer null
            if (MediaMessagePayload.parse(payload) == null) return@withPeer null
            savePlaintext(message.id, payload)
            payload
        }
    }

    /** `MessageDecoder.isMediaPayloadData` on the cached plaintext (MC:4018). */
    private suspend fun cachedPayload(messageId: UUID): ByteArray? =
        withContext(deps.io) { deps.store().plaintext(messageId) }?.takeIf { MediaMessagePayload.parse(it) != null }

    private suspend fun savePlaintext(messageId: UUID, plaintext: ByteArray) =
        withContext(deps.io) { deps.store().savePlaintext(messageId, plaintext) }

    // ---- helpers ----

    /** One job per message id; later callers await it (MC:3010-3022). */
    private suspend fun deduplicated(messageId: UUID, work: suspend () -> Unit) {
        jobs[messageId]?.let {
            it.join()
            return
        }
        val download = scope.launch(start = CoroutineStart.LAZY) { work() }
        jobs[messageId] = download
        download.start()
        try {
            download.join()
        } finally {
            if (jobs[messageId] === download) jobs.remove(messageId)
        }
    }

    /**
     * iOS checks `imageData == nil` / `voiceData == nil` / `videoData == nil`: here the flag and the
     * cache must agree, so a flag left behind by a cache that lost the file downloads again.
     */
    private suspend fun isOnDevice(message: ChatMessage): Boolean = message.hasFullMedia && cached(message.id)

    private suspend fun cached(messageId: UUID): Boolean = withContext(deps.io) { deps.media().has(messageId) }

    /** `needsLinkImageDownload` without the flag (MC:318): [isOnDevice] decides that part. */
    private fun needsLinkImage(message: ChatMessage): Boolean = message.hasLargeLinkImage && !message.deleted

    /**
     * After a download landed: false — and the cache entry removed — when chats were locked meanwhile
     * or the message became a tombstone (`stillHoldsMedia`, MC:4099-4105).
     */
    private suspend fun keepDownload(message: ChatMessage, generation: Long): Boolean {
        if (state.lockGeneration == generation && stillHoldsMedia(message.id, message.peerUserId)) return true
        withContext(deps.io) { deps.media().remove(listOf(message.id)) }
        return false
    }

    /** Whether [id] is still a live (not deleted) message, in [peer]'s thread or any other (MC:4099-4105). */
    private fun stillHoldsMedia(id: UUID, peer: UUID): Boolean {
        val resolved = if (state.messages(peer)?.any { it.id == id } == true) peer else state.peerFor(id) ?: peer
        val message = state.messages(resolved)?.firstOrNull { it.id == id } ?: return false
        return !message.deleted
    }

    /**
     * Updates the message where it lives now — the hinted thread, else whichever holds the id (ingest
     * can race a peer remap, MC:3103-3109) — unless it became a tombstone.
     */
    private fun attach(message: ChatMessage, transform: (ChatMessage) -> ChatMessage) {
        val resolved = if (state.messages(message.peerUserId)?.any { it.id == message.id } == true) {
            message.peerUserId
        } else {
            state.peerFor(message.id) ?: return
        }
        state.edit(resolved) { thread -> thread.map { if (it.id == message.id && !it.deleted) transform(it) else it } }
    }

    private fun beginDownload(messageId: UUID, totalBytes: Long?) {
        state.transfers.begin(messageId, isUpload = false)
        state.transfers.advance(messageId, MediaTransfer.Phase.Transferring, totalBytes)
    }

    /** Transport progress, hopped onto the hydrator's scope (MC:2993-3000). */
    private fun progress(messageId: UUID, fraction: Double) {
        scope.launch { state.transfers.update(messageId, fraction) }
    }

    companion object {
        /** iOS lists 50 of our own messages to find the one to recover (MC:4023-4027). */
        const val OWN_RECOVERY_PAGE = 50

        /** And 80 for someone else's (MC:4050-4054). */
        const val INBOUND_RECOVERY_PAGE = 80

        /** The still a downloaded video gets for its bubble (`VideoMedia.thumbnailJPEG`, maxEdge 720; media-voice-links §3.5). */
        const val POSTER_EDGE = 720

        /** The stand-in of media that could not be opened (`MessageDecoder.swift:283`). */
        private const val MEDIA_LABEL = "Media"
    }
}
