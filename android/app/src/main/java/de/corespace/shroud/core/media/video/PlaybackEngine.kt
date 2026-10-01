package de.corespace.shroud.core.media.video

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The player under [ChatVideoPlayer]: ExoPlayer on a device ([ExoPlaybackEngine]), scripted in the
 * JVM tests. Main-thread only.
 */
internal interface PlaybackEngine {
    enum class Status { Preparing, Ready, Failed }

    /** For the surface (`PlayerSurface`); null in tests. */
    val player: Player?

    /** Preparing until the first frame can play (`AVPlayerItem.status`), then Ready or Failed. */
    val status: StateFlow<Status>

    /** Seconds; 0 while unknown. */
    val durationSeconds: Double
    val positionSeconds: Double
    val isPlaying: Boolean

    /** Called once each time playback reaches the end (`didPlayToEndTimeNotification`). */
    var onEnded: (() -> Unit)?

    fun play()
    fun pause()
    fun seekTo(seconds: Double, precise: Boolean)
    fun setVolume(volume: Float)
    fun release()

    fun interface Factory {
        /** A prepared engine, or null when [source] cannot be read (no local media, chats locked). */
        fun create(source: VideoSource): PlaybackEngine?
    }
}

/**
 * ExoPlayer configured like iOS's `.moviePlayback` session (conversation-compose-media §16;
 * media-voice-links §6.5): media usage, movie content, audio focus handled, paused when headphones
 * are unplugged, no repeat, paused at the end. Sealed local media plays through
 * [SealedVideoSources.playerDataSource] — no decrypted file is written (plan C7).
 */
@OptIn(UnstableApi::class)
internal class ExoPlaybackEngine private constructor(context: Context, configure: ExoPlayer.() -> Unit) : PlaybackEngine {
    private val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val _status = MutableStateFlow(PlaybackEngine.Status.Preparing)
    override val status: StateFlow<PlaybackEngine.Status> = _status.asStateFlow()
    override var onEnded: (() -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> if (_status.value == PlaybackEngine.Status.Preparing) _status.value = PlaybackEngine.Status.Ready
                Player.STATE_ENDED -> onEnded?.invoke()
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (_status.value == PlaybackEngine.Status.Preparing) _status.value = PlaybackEngine.Status.Failed
        }
    }

    init {
        exo.repeatMode = Player.REPEAT_MODE_OFF
        exo.playWhenReady = false
        exo.addListener(listener)
        exo.configure()
        exo.prepare()
    }

    override val player: Player get() = exo

    override val durationSeconds: Double
        get() = exo.duration.let { if (it == C.TIME_UNSET || it < 0) 0.0 else it / 1000.0 }

    override val positionSeconds: Double get() = maxOf(0L, exo.currentPosition) / 1000.0

    override val isPlaying: Boolean get() = exo.isPlaying

    override fun play() = exo.play()

    override fun pause() = exo.pause()

    override fun seekTo(seconds: Double, precise: Boolean) {
        exo.setSeekParameters(if (precise) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        exo.seekTo(Math.round(maxOf(0.0, seconds) * 1000))
    }

    override fun setVolume(volume: Float) {
        exo.volume = volume
    }

    override fun release() {
        onEnded = null
        exo.removeListener(listener)
        exo.release()
    }

    class Factory(private val context: Context, private val sources: SealedVideoSources) : PlaybackEngine.Factory {
        override fun create(source: VideoSource): PlaybackEngine? = when (source) {
            is VideoSource.Message -> {
                val data = sources.playerDataSource(source.messageId)
                data?.let { factory ->
                    val media: MediaSource = ProgressiveMediaSource.Factory(factory).createMediaSource(LOCAL_ITEM)
                    ExoPlaybackEngine(context) { setMediaSource(media) }
                }
            }
            is VideoSource.Content -> ExoPlaybackEngine(context) { setMediaItem(MediaItem.fromUri(source.uri)) }
        }
    }

    companion object {
        /**
         * The URI every sealed local video plays under, the same opaque `shroud-media:sealed` as
         * W2-MEDIA-STORE's `SealedMediaDataSource.URI`: the per-message data source decides what is
         * read, and no message id or path reaches a data spec, which ExoPlayer prints in its errors.
         */
        val LOCAL_URI: Uri = "shroud-media:sealed".toUri()

        private val LOCAL_ITEM: MediaItem = MediaItem.Builder().setUri(LOCAL_URI).setMimeType(MimeTypes.VIDEO_MP4).build()
    }
}
