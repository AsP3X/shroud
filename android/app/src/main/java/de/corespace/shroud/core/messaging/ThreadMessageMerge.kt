package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.net.wire.WireText
import java.util.UUID

/**
 * Pure helpers for merging a server page with what this device already shows (messaging-core §10;
 * iOS `ThreadMessageMerge.swift`). The Double Ratchet is one-shot: a re-decode of a message whose key
 * is spent comes back as a failed placeholder, so a readable copy must never be replaced by one.
 *
 * Media bytes never live in the model on Android (plan C8): where iOS fills `imageData`,
 * `voiceData` or `videoData` from the prior copy, the merge carries [ChatMessage.hasFullMedia] and
 * [ChatMessage.posterJpeg] instead (iOS `imageData` of a video is its poster).
 */
object ThreadMessageMerge {
    /** The texts a failed decode leaves behind (`ThreadMessageMerge.swift:5-7`). */
    fun isFailedDecryptText(text: String): Boolean =
        text == UNABLE_TO_DECRYPT || text == MEDIA_PLACEHOLDER || text == BINARY_MESSAGE

    /** [preferReadable] with the prior copy looked up in [previous] (`ThreadMessageMerge.swift:10-15`). */
    fun preferReadable(decoded: ChatMessage, previous: List<ChatMessage>): ChatMessage =
        preferReadable(decoded, previous.firstOrNull { it.id == decoded.id })

    /**
     * Never lets a failed re-decrypt replace a readable bubble (`ThreadMessageMerge.swift:18-88`;
     * messaging-core §10.1):
     *
     * - A tombstone keeps nothing the old copy said: `tombstone(prior)`, the decoded wire time when
     *   the prior had none and the higher receipt. Without a prior copy, the decoded tombstone made bare.
     * - A failed decode next to a readable prior keeps the prior, filling only what it lacks.
     * - Otherwise the decode wins and the prior fills its gaps: media, transcript, reply, link preview
     *   (text only), reactions (a fresh decode never carries them), a file's name, and a hydrated
     *   video's or a file's kind.
     */
    fun preferReadable(decoded: ChatMessage, prior: ChatMessage?): ChatMessage {
        if (decoded.deleted) {
            // `:22-31`: the kind is kept from the prior copy (a page only knows "media").
            if (prior == null) return tombstone(decoded)
            var replacement = tombstone(prior)
            if (replacement.createdAtWire == null) replacement = replacement.copy(createdAtWire = decoded.createdAtWire)
            if (decoded.receipt.rank > replacement.receipt.rank) replacement = replacement.copy(receipt = decoded.receipt)
            return replacement
        }
        if (prior == null) return decoded
        val decodedFailed = isFailedDecryptText(decoded.text)
        val priorFailed = isFailedDecryptText(prior.text)

        if (decodedFailed && !priorFailed && !prior.deleted) {
            // `:36-59`. The kind upgrade below can never apply: it needs the decode not to have failed.
            return prior.copy(
                createdAtWire = prior.createdAtWire ?: decoded.createdAtWire,
                receipt = if (decoded.isMine && decoded.receipt.rank > prior.receipt.rank) decoded.receipt else prior.receipt,
                hasFullMedia = prior.hasFullMedia || decoded.hasFullMedia,
                posterJpeg = prior.posterJpeg ?: decoded.posterJpeg,
                previewJpeg = prior.previewJpeg ?: decoded.previewJpeg,
                mediaByteCount = prior.mediaByteCount ?: decoded.mediaByteCount,
                mediaObjectId = prior.mediaObjectId ?: decoded.mediaObjectId,
                transcript = prior.transcript ?: decoded.transcript,
                replyTo = prior.replyTo ?: decoded.replyTo,
                linkPreview = prior.linkPreview ?: decoded.linkPreview,
                fileName = prior.fileName ?: decoded.fileName,
            )
        }

        // `:61-81`.
        var merged = decoded.copy(
            hasFullMedia = decoded.hasFullMedia || prior.hasFullMedia,
            posterJpeg = decoded.posterJpeg ?: prior.posterJpeg,
            previewJpeg = decoded.previewJpeg ?: prior.previewJpeg,
            mediaByteCount = decoded.mediaByteCount ?: prior.mediaByteCount,
            transcript = decoded.transcript ?: prior.transcript,
            // Reactions live on this device's copy until a page reconciles them (`:68-70`).
            reactions = if (decoded.deleted) emptyList() else prior.reactions,
            // A cache written before replies existed has no quote: keep the shown one (`:71-73`).
            replyTo = decoded.replyTo ?: prior.replyTo,
            fileName = decoded.fileName ?: prior.fileName,
        )
        // Same for a link preview: a cache written before previews existed has none (`:74-75`).
        if (merged.linkPreview == null && merged.kind == ChatMessageKind.Text) merged = merged.copy(linkPreview = prior.linkPreview)
        // Don't clobber a hydrated video with a decode that still lacks bytes (`:76-81`).
        if (prior.kind == ChatMessageKind.Video && merged.kind != ChatMessageKind.Video &&
            (prior.hasFullMedia || prior.mediaObjectId != null)
        ) {
            merged = merged.copy(
                kind = ChatMessageKind.Video,
                hasFullMedia = merged.hasFullMedia || prior.hasFullMedia,
                durationMs = merged.durationMs ?: prior.durationMs,
            )
        }
        // Nor a file with a placeholder decode ("Media": its payload is not on this device yet).
        if (prior.kind == ChatMessageKind.File && merged.kind != ChatMessageKind.File &&
            (prior.hasFullMedia || prior.mediaObjectId != null)
        ) {
            merged = merged.copy(kind = ChatMessageKind.File, text = prior.text, hasFullMedia = merged.hasFullMedia || prior.hasFullMedia)
        }
        if (!priorFailed && decodedFailed) {
            // `:82-86` (a deleted prior lands here).
            return prior.copy(createdAtWire = prior.createdAtWire ?: decoded.createdAtWire)
        }
        return merged
    }

    /**
     * Merges a decoded page into the prior thread (`ThreadMessageMerge.swift:91-112`): every decoded
     * message through [preferReadable], then the pending ones the page did not contain, then every
     * prior row it did not replace — a page is not the whole thread — sorted by time (stable).
     */
    fun mergeThread(decoded: List<ChatMessage>, previous: List<ChatMessage>, pendingLocal: List<ChatMessage>): List<ChatMessage> {
        val previousById = HashMap<UUID, ChatMessage>(previous.size * 2)
        for (message in previous) previousById.putIfAbsent(message.id, message)
        val result = ArrayList<ChatMessage>(decoded.size + previous.size)
        val seen = HashSet<UUID>()
        for (message in decoded) {
            result += preferReadable(message, previousById[message.id])
            seen += message.id
        }
        for (pending in pendingLocal) if (seen.add(pending.id)) result += pending
        for (prior in previous) if (seen.add(prior.id)) result += prior
        result.sortBy { it.createdAt }
        return result
    }

    /**
     * What a message deleted for everyone leaves (`ThreadMessageMerge.swift:116-133`): who sent it,
     * when, and which kind it was — photo, video, voice and file keep theirs, anything else is text.
     * Nothing it said remains; the row draws it as the text tombstone (`presentedKind`).
     */
    fun tombstone(message: ChatMessage): ChatMessage = ChatMessage(
        id = message.id,
        peerUserId = message.peerUserId,
        senderUserId = message.senderUserId,
        text = MESSAGE_DELETED,
        createdAt = message.createdAt,
        createdAtWire = message.createdAtWire,
        isMine = message.isMine,
        deleted = true,
        receipt = message.receipt,
        kind = when (message.kind) {
            ChatMessageKind.Image, ChatMessageKind.Video, ChatMessageKind.Voice, ChatMessageKind.File -> message.kind
            ChatMessageKind.Text, ChatMessageKind.Todo -> ChatMessageKind.Text
        },
    )

    /**
     * Tombstones in [decoded] whose content this device may still hold — the thread has the message
     * live, as a tombstone that kept content (older builds), or not at all — and whose caches must
     * therefore be purged (`ThreadMessageMerge.swift:139-149`). A bare tombstone was purged when it
     * became one, so a reload does not purge it again.
     */
    fun tombstonesToPurge(decoded: List<ChatMessage>, previous: List<ChatMessage>): List<UUID> {
        val previousById = HashMap<UUID, ChatMessage>(previous.size * 2)
        for (message in previous) previousById.putIfAbsent(message.id, message)
        return decoded.mapNotNull { message ->
            if (!message.deleted) return@mapNotNull null
            val prior = previousById[message.id]
            if (prior != null && prior == tombstone(prior)) null else message.id
        }
    }

    /**
     * Folds transcripts shared as annotations into the voice notes they point at
     * (`ThreadMessageMerge.swift:155-171`). A sealed or locally made transcript always wins; a
     * shared one only fills a gap. Returns [thread] itself when nothing applies.
     */
    fun applySharedTranscripts(transcripts: Map<UUID, String>, thread: List<ChatMessage>): List<ChatMessage> {
        if (transcripts.isEmpty()) return thread
        var result: MutableList<ChatMessage>? = null
        thread.forEachIndexed { index, message ->
            if (message.kind != ChatMessageKind.Voice || message.deleted) return@forEachIndexed
            if (WireText.trimWhitespacesAndNewlines(message.transcript ?: "").isNotEmpty()) return@forEachIndexed
            val shared = transcripts[message.id] ?: return@forEachIndexed
            val target = result ?: thread.toMutableList().also { result = it }
            target[index] = message.copy(transcript = shared)
        }
        return result ?: thread
    }

    /** Tombstone text (`ThreadMessageMerge.swift:123`, `MessageDecoder.swift:49`). */
    const val MESSAGE_DELETED = "Message deleted"

    /** A text message that could not be opened (`MessageDecoder.swift:175, 283`). */
    const val UNABLE_TO_DECRYPT = "[Unable to decrypt]"

    /** A media message that could not be opened or has no payload (`MessageDecoder.swift:283, 403`). */
    const val MEDIA_PLACEHOLDER = "Media"

    /** Plaintext that is not UTF-8 (`MessageDecoder.swift:151, 231`). */
    const val BINARY_MESSAGE = "[Binary message]"
}
