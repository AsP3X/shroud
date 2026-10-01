package de.corespace.shroud.core.media

import android.media.MediaDataSource
import java.io.IOException
import java.util.UUID

/**
 * The platform's [MediaDataSource] over a message's sealed media (SHRM1, [LocalMediaCache]), for
 * `MediaMetadataRetriever.setDataSource(MediaDataSource)` (video posters and probes, W2-VIDEO's
 * `posterJpegFromLocal`) and `MediaExtractor.setDataSource(MediaDataSource)` (transcription input,
 * W3-TRANSCRIPTION) — media-voice-links §7.1, §7.2, plan C7. It replaces iOS's decrypted
 * `tmp/shroud-thumb-*.mp4` and `tmp/shroud-tx-*.m4a` (`SensitiveTempFiles.swift:13-42`): the
 * framework reads byte ranges through it and no plaintext file exists.
 *
 * The framework may call [readAt] from its own threads; the reader is synchronized. A segment that
 * does not authenticate, or chats locking meanwhile, throws [IOException] (the framework reports
 * the source as unreadable). [close] closes the reader; the framework calls it when the retriever
 * or extractor is released.
 */
class SealedMediaDataSourceMdr(private val reader: SealedMediaReader) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        if (size == 0) 0 else reader.read(position, buffer, offset, size)

    override fun getSize(): Long = reader.length

    override fun close() = reader.close()

    companion object {
        /** A source for [messageId]'s media, or null when there is none (or chats are locked). */
        fun open(store: LocalMediaStore, messageId: UUID): SealedMediaDataSourceMdr? =
            store.openReader(messageId)?.let { SealedMediaDataSourceMdr(it) }
    }
}
