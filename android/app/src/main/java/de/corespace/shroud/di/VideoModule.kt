package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.media.VideoPipeline
import de.corespace.shroud.core.media.video.ChatVideoPlayer
import de.corespace.shroud.core.media.video.SealedVideoSources
import de.corespace.shroud.core.media.video.VideoMedia
import de.corespace.shroud.core.media.video.VideoPlanner

/**
 * Video planning, encoding and playback (00-plan §1.7.9; media-voice-links §6). Owner: W2-VIDEO.
 * Nobody else constructs this package's classes (§2.0 rule 3):
 *
 * - [media] — probe, poster and filmstrip for the compose screen, and the send path's
 *   [VideoPipeline] ([pipeline]): encode (Media3 Transformer through the metadata-clearing muxer)
 *   and posters of sealed local videos;
 * - [newPlayer] — one [ChatVideoPlayer] per overlay or compose screen (main thread);
 * - [VideoPlanner] is a pure object, called directly.
 *
 * Encode outputs are `cacheDir/shroud-export-*.mp4` from the one `SensitiveTempFiles`
 * ([KeysModule.sensitiveTempFiles], swept at launch and on lock); the caller deletes the accepted
 * file once it is sealed.
 *
 * [sealedSources] reads videos in the sealed local media cache (plan C7). They are W2-MEDIA-STORE's
 * `LocalMediaCache` + `SealedMediaDataSource` / `SealedMediaDataSourceMdr`, built in parallel in
 * wave 2, so until W2-INT wires them here they are [SealedVideoSources.Unavailable]: local playback
 * reports `failed` and [VideoPipeline.posterJpegFromLocal] returns null. Picked and captured clips
 * (content and file URIs) need none of it.
 */
class VideoModule(container: AppContainer) : AppModule(container) {
    /** Decrypting sources for sealed local videos; W2-INT replaces the interim value (see the class KDoc). */
    val sealedSources: SealedVideoSources get() = SealedVideoSources.Unavailable

    val media: VideoMedia by lazy {
        VideoMedia.create(
            context = container.appContext,
            tempFiles = container.keys.sensitiveTempFiles,
            clock = container.clock,
            sealedSources = sealedSources,
        )
    }

    /** The seam W2-MSG-SEND encodes through (`core/media/MediaTypes.kt`). */
    val pipeline: VideoPipeline get() = media

    /** A fresh player for one screen; call [ChatVideoPlayer.teardown] when the screen leaves. */
    fun newPlayer(): ChatVideoPlayer = ChatVideoPlayer(container.appContext, sealedSources)
}
