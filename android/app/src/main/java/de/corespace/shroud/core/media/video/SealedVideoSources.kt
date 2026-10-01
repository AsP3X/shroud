package de.corespace.shroud.core.media.video

import android.media.MediaDataSource
import androidx.media3.datasource.DataSource
import java.util.UUID

/**
 * Read access to videos already in the sealed local media cache (plan C7; media-voice-links D4,
 * §7.1): players and `MediaMetadataRetriever` read the SHRM1 file through decrypting sources, so no
 * decrypted video ever lands on disk. iOS writes `tmp/shroud-play-<uuid>.mp4` and
 * `tmp/shroud-thumb-<uuid>.mp4` instead (`ChatVideoPlayer.swift:39-51`, `VideoMedia.swift:425-436`).
 *
 * The sources themselves are W2-MEDIA-STORE's (`LocalMediaCache.openReader` behind
 * `SealedMediaDataSource` for Media3 and `SealedMediaDataSourceMdr` for `MediaMetadataRetriever`);
 * `VideoModule` adapts them to this interface, so this package never constructs them (plan §2.0
 * rule 3). Both return null when the message has no local media or chats are locked.
 */
interface SealedVideoSources {
    /** A Media3 source for ExoPlayer reading the decrypted bytes of [messageId]. */
    fun playerDataSource(messageId: UUID): DataSource.Factory?

    /** A random-access source for `MediaMetadataRetriever` / `MediaExtractor`; the caller closes it. */
    fun retrieverDataSource(messageId: UUID): MediaDataSource?

    companion object {
        /** No local media can be read (every call returns null): playback fails, posters are null. */
        val Unavailable: SealedVideoSources = object : SealedVideoSources {
            override fun playerDataSource(messageId: UUID): DataSource.Factory? = null
            override fun retrieverDataSource(messageId: UUID): MediaDataSource? = null
        }
    }
}
