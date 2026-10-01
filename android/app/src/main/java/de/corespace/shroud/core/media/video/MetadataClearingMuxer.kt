package de.corespace.shroud.core.media.video

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4OrientationData
import androidx.media3.container.Mp4TimestampData
import androidx.media3.muxer.Muxer
import androidx.media3.transformer.InAppMp4Muxer
import de.corespace.shroud.core.model.AppClock

/**
 * The MP4 writer every export goes through, re-encodes and passthrough remuxes alike: Media3's
 * in-app muxer with a metadata provider that drops everything the source carried — location
 * (`Mp4LocationData`, `©xyz`), capture time (`Mp4TimestampData`), `mdta` keys such as
 * `com.apple.quicktime.make/model/creationdate`, XMP — and writes only fresh creation and
 * modification times (media-voice-links §5.3; D6).
 *
 * iOS: "No path sends the source file itself. Every output is written by an export session told to
 * carry none of the source's metadata, or by `AVAssetWriter`, which starts empty"
 * (`ios/shroud/Services/Crypto/VideoMedia.swift:190-192`; `MediaMetadataScrubber.stripMetadata`).
 *
 * The one entry kept is [Mp4OrientationData]: it is how the track's display rotation is written,
 * says nothing about where, when or on what a clip was recorded, and dropping it would turn a
 * portrait clip on its side.
 */
@OptIn(UnstableApi::class)
object MetadataClearingMuxer {
    /** A muxer factory for `Transformer.Builder.setMuxerFactory`. [clock] dates the fresh `mvhd` times. */
    fun factory(clock: AppClock): Muxer.Factory = InAppMp4Muxer.Factory(provider(clock))

    /** The provider alone, for tests: clears [Metadata.Entry]s in place, then adds fresh times. */
    internal fun provider(clock: AppClock): InAppMp4Muxer.MetadataProvider =
        InAppMp4Muxer.MetadataProvider { entries -> scrub(entries, clock.nowMillis()) }

    /** Keeps orientation, drops everything else, adds creation = modification = [nowMillis]. */
    internal fun scrub(entries: MutableSet<Metadata.Entry>, nowMillis: Long) {
        entries.retainAll { it is Mp4OrientationData }
        val now = Mp4TimestampData.unixTimeToMp4TimeSeconds(nowMillis)
        entries.add(Mp4TimestampData(now, now))
    }
}
