package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.crypto.MessageCrypto
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.ContentType
import de.corespace.shroud.core.net.SendMessageRequest
import de.corespace.shroud.core.net.wire.MessageAnnotation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Voice transcripts shared as sealed annotations (messaging-core §11.8; MC:3557-3660 of
 * `ios/shroud/Services/Messaging/MessagingController.swift`): a transcript made on this device for a
 * received note ([shareTranscript]) or for one we just recorded ([beginOwnTranscript]) is shown here
 * at once and follows to the other side — and our other devices — as `content_type = annotation`
 * `{"t":"transcript","r":<voice id>,"c":<text>}`, so nobody transcribes the same note twice.
 *
 * Best effort by design: a failed share only means the other side transcribes for themselves.
 * Nothing is shared for Notes or for a note the server has not keyed yet (its id would mean nothing
 * to the other side); a transcript that lands while its note is still sending waits for the re-key
 * ([noteVoiceSent]).
 *
 * Android addition (messaging-core D5): every annotation we send is noted in the store's annotation
 * index under its voice note, so deleting the note purges the transcript's plaintext too.
 *
 * Main-confined; owned by [SendPipeline].
 */
class AnnotationSender internal constructor(
    private val state: ThreadState,
    private val deps: SendDependencies,
    private val scope: CoroutineScope,
) {
    /** Own voice notes whose transcript is still being made, by optimistic id (MC:150-151). */
    private val inFlight = HashSet<UUID>()

    /** Server ids those notes were re-keyed to, so a late transcript finds its bubble (MC:152-153). */
    private val sentVoiceIds = HashMap<UUID, UUID>()

    /** Transcripts that landed while their note was still sending, by optimistic id (MC:154-156). */
    private val awaitingSend = HashMap<UUID, String>()

    /** Forgets every pending hand-off (sign-out). */
    fun reset() {
        inFlight.clear()
        sentVoiceIds.clear()
        awaitingSend.clear()
    }

    /**
     * Keeps a transcript made on this device for a voice note that has none and shares it
     * (`shareTranscript`, MC:3580-3593).
     */
    suspend fun shareTranscript(transcript: String, voiceMessageId: UUID, storePeer: UUID) {
        val text = MessageAnnotation.clampTranscript(transcript)
        if (text.isEmpty()) return
        val note = state.messages(storePeer)?.firstOrNull { it.id == voiceMessageId } ?: return
        if (note.kind != ChatMessageKind.Voice || !note.transcript.isNullOrBlankWire()) return
        state.update(voiceMessageId) { it.copy(transcript = text) }
        state.persistThread(storePeer)
        send(text, voiceMessageId, storePeer)
    }

    /**
     * Runs [provider] beside the send of the note [optimisticId] (MC:3419-3428): its result, clamped
     * (empty → none), goes to [applyOwnVoiceTranscript].
     */
    fun beginOwnTranscript(optimisticId: UUID, storePeer: UUID, provider: suspend (messageId: UUID) -> String?) {
        inFlight += optimisticId
        scope.launch {
            val made = try {
                provider(optimisticId)?.let(MessageAnnotation::clampTranscript)?.ifEmpty { null }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            applyOwnVoiceTranscript(made, optimisticId, storePeer)
        }
    }

    /**
     * Shows the transcript of a note we just recorded and shares it once the note is sent
     * (`applyOwnVoiceTranscript`, MC:3597-3618). Null: there is none (no model, no speech, failed).
     */
    internal suspend fun applyOwnVoiceTranscript(text: String?, optimisticId: UUID, storePeer: UUID) {
        inFlight -= optimisticId
        val messageId = sentVoiceIds.remove(optimisticId) ?: optimisticId
        if (text == null) return
        val found = state.messages(storePeer)?.firstOrNull { it.id == messageId } ?: return
        if (found.kind != ChatMessageKind.Voice || found.deleted) return
        if (found.transcript.isNullOrBlankWire()) {
            state.update(messageId) { it.copy(transcript = text) }
            state.persistThread(storePeer)
        }
        val note = state.messages(storePeer)?.firstOrNull { it.id == messageId } ?: found
        if (note.id == optimisticId && (note.pendingSync || note.receipt == ReceiptStatus.Sending || note.receipt == ReceiptStatus.Failed)) {
            // Still on its way (or queued offline): shared once the server has keyed it (MC:3612-3616).
            awaitingSend[optimisticId] = text
            return
        }
        send(text, note.id, storePeer)
    }

    /**
     * The voice note [optimisticId] is now [sentId] on the server (MC:3778-3784): a transcript still
     * being made will find it there, and one that waited goes out as an annotation — unless the
     * payload already carried a transcript.
     */
    internal fun noteVoiceSent(optimisticId: UUID, sentId: UUID, storePeer: UUID, sentWithTranscript: Boolean) {
        if (optimisticId in inFlight) sentVoiceIds[optimisticId] = sentId
        val late = awaitingSend.remove(optimisticId) ?: return
        if (!sentWithTranscript) scope.launch { send(late, sentId, storePeer) }
    }

    /**
     * Seals [text] as the transcript annotation of [voiceId] and posts it (`sendTranscriptAnnotation`,
     * MC:3622-3660): a fresh `client_message_id` (an annotation is never retried), the plaintext cached
     * under the server id (our own v3 body cannot be opened again). Errors are swallowed.
     */
    internal suspend fun send(text: String, voiceId: UUID, storePeer: UUID) {
        if (state.isNotes(storePeer)) return
        val note = state.messages(storePeer)?.firstOrNull { it.id == voiceId } ?: return
        if (note.deleted || note.pendingSync || note.receipt == ReceiptStatus.Sending || note.receipt == ReceiptStatus.Failed) return
        if (!deps.isOnline()) return
        val token = state.session?.token ?: return
        val me = state.myUserId ?: return
        if (!deps.keyring.isUnlocked) return
        try {
            val plaintext = MessageAnnotation.transcript(text, voiceId).encoded()
            val apiPeer = state.apiPeer(storePeer)
            val peerPublic = deps.peerIdentities().publicKeyForSending(apiPeer)
            val sealed = deps.seal(plaintext, apiPeer, me, peerPublic)
            val dto = deps.api.sendMessage(
                token,
                SendMessageRequest(apiPeer, UUID.randomUUID(), ContentType.ANNOTATION, MessageCrypto.toWire(sealed)),
            )
            withContext(deps.io) {
                val store = deps.store()
                store.savePlaintext(dto.id, plaintext)
                store.noteAnnotation(voiceId, dto.id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Kept locally either way (MC:3657-3659).
        }
    }

    /** iOS `(transcript ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty`. */
    private fun String?.isNullOrBlankWire(): Boolean =
        this == null || de.corespace.shroud.core.net.wire.WireText.trimWhitespacesAndNewlines(this).isEmpty()
}
