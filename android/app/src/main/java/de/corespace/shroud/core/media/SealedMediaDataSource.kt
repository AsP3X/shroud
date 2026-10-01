package de.corespace.shroud.core.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.util.UUID

/**
 * A Media3 [DataSource] that plays a message's media straight out of the sealed local cache
 * (SHRM1, [LocalMediaCache]): ExoPlayer asks for byte ranges, this decrypts the 64 KiB segments
 * they touch, and no decrypted file is ever written (media-voice-links D4, §6.5, §7.1; plan C7).
 * It replaces iOS's `tmp/shroud-play-<uuid>.mp4` (`ios/shroud/Services/Video/ChatVideoPlayer.swift:39-51`)
 * and `tmp/shroud-thumb-*` copies.
 *
 * Bound to one message through [Factory]; [open] takes a fresh [SealedMediaReader] each time
 * (ExoPlayer opens a source more than once to probe and to seek). Failures surface as
 * [DataSourceException]: no file or chats locked → `ERROR_CODE_IO_FILE_NOT_FOUND`, a position past
 * the end → `ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE`, a segment that does not authenticate or a
 * lock during playback → `ERROR_CODE_IO_UNSPECIFIED`. The URI is the opaque [URI], never a message
 * id or a path (ExoPlayer prints data specs in its errors).
 *
 * Usage (W2-VIDEO's `ChatVideoPlayer`, W2-VOICE's playback):
 * ```
 * val source = ProgressiveMediaSource.Factory(media.dataSourceFactory(messageId))
 *     .createMediaSource(SealedMediaDataSource.mediaItem())
 * ```
 */
@OptIn(markerClass = [UnstableApi::class])
class SealedMediaDataSource(private val openReader: () -> SealedMediaReader?) : BaseDataSource(/* isNetwork = */ false) {
    private var reader: SealedMediaReader? = null
    private var uri: Uri? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val source = openReader() ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        reader = source
        if (dataSpec.position > source.length) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        position = dataSpec.position
        // As FileDataSource: an explicit length is taken as asked; reads stop at the end of the data anyway.
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else source.length - position
        if (bytesRemaining < 0) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val source = reader ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        val wanted = minOf(length.toLong(), bytesRemaining).toInt()
        val read = try {
            source.read(position, buffer, offset, wanted)
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        if (read <= 0) return C.RESULT_END_OF_INPUT
        position += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            reader?.close()
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        } finally {
            reader = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    /** Makes [SealedMediaDataSource]s for [messageId]'s media in [store]. */
    class Factory(private val store: LocalMediaStore, private val messageId: UUID) : DataSource.Factory {
        override fun createDataSource(): DataSource = SealedMediaDataSource { store.openReader(messageId) }
    }

    companion object {
        /**
         * The URI every sealed media item plays under: opaque on purpose (no id, no path). The
         * extractors sniff the container, so no file extension is needed.
         */
        val URI: Uri by lazy { Uri.parse("shroud-media:sealed") }

        /** A [MediaItem] for a source built from a [Factory]. */
        fun mediaItem(): MediaItem = MediaItem.fromUri(URI)
    }
}
