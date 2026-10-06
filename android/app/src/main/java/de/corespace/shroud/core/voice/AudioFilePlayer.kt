package de.corespace.shroud.core.voice

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import de.corespace.shroud.core.media.SealedMediaDataSource
import java.util.UUID

/**
 * The player behind [AudioFilePlaybackCoordinator] (docs/file-sharing.md §11.5); [ExoAudioFilePlayer]
 * on a device, a fake in tests. Unlike [VoicePlayer] it never takes bytes: it reads a message's file
 * from the sealed cache by id, range by range. Main thread only; [Listener] events arrive there.
 */
interface AudioFilePlayer {
    interface Listener {
        /** The loaded file is ready; [durationMs] is its length, or `null` when the container does not say. */
        fun onReady(durationMs: Long?)

        /** Playback reached the end. */
        fun onEnded()

        /**
         * The file could not be played. [unsupported]: the player cannot read this container or
         * codec (the bubble falls back to the plain file bubble for the session); otherwise the bytes
         * could not be read (not on this phone any more, chats locked).
         */
        fun onError(unsupported: Boolean)

        /** The system paused playback: audio focus lost (a call, another app), headphones unplugged. */
        fun onPausedBySystem()
    }

    var listener: Listener?

    /** Replaces whatever was loaded with [messageId]'s file and prepares it, paused. */
    fun load(messageId: UUID)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    /** Playback speed; pitch is kept. */
    fun setSpeed(rate: Float)

    /**
     * The current position. ExoPlayer refreshes it only when its playback loop wakes (Media3's
     * dynamic scheduling: a few times a second for audio); [AudioFilePlaybackCoordinator.liveProgress]
     * fills in between.
     */
    val positionMs: Long

    /** Whether the position is moving right now: playing, not buffering, not held by a focus loss. */
    val isAdvancing: Boolean

    /** Unloads the file. */
    fun stop()
}

/**
 * [AudioFilePlayer] on Media3 ExoPlayer: a [ProgressiveMediaSource] over [SealedMediaDataSource], so
 * ExoPlayer asks for byte ranges, the SHRM1 cache decrypts the 64 KiB segments they touch, and no
 * decrypted audio is ever written (docs/file-sharing.md §11.5). Audio attributes `USAGE_MEDIA` /
 * `AUDIO_CONTENT_TYPE_MUSIC` with ExoPlayer's own audio-focus handling, a pause when headphones are
 * unplugged, and a pause — not ExoPlayer's resume-later — on a transient focus loss (§11.5: iOS and
 * Android pause on an audio interruption). Built on the main looper on first use and reused.
 */
@OptIn(UnstableApi::class)
class ExoAudioFilePlayer(
    context: Context,
    private val dataSources: (UUID) -> DataSource.Factory,
) : AudioFilePlayer {
    private val appContext = context.applicationContext
    private var awaitingReady = false

    override var listener: AudioFilePlayer.Listener? = null

    private val playerLazy = lazy {
        ExoPlayer.Builder(appContext)
            .setLooper(Looper.getMainLooper())
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { it.addListener(events) }
    }

    private val player: ExoPlayer by playerLazy

    private val events = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> if (awaitingReady) {
                    awaitingReady = false
                    val duration = player.duration
                    listener?.onReady(if (duration == C.TIME_UNSET || duration <= 0) null else duration)
                }
                Player.STATE_ENDED -> listener?.onEnded()
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            awaitingReady = false
            listener?.onError(isUnsupported(error.errorCode))
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) return
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
            ) {
                listener?.onPausedBySystem()
            }
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            // A transient loss (a ringing call, a navigation prompt): pause for good instead of resuming later.
            if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS && player.playWhenReady) {
                player.pause()
                listener?.onPausedBySystem()
            }
        }
    }

    override fun load(messageId: UUID) {
        val source = ProgressiveMediaSource.Factory(dataSources(messageId)).createMediaSource(SealedMediaDataSource.mediaItem())
        player.playWhenReady = false
        awaitingReady = true
        player.setMediaSource(source)
        player.prepare()
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun setSpeed(rate: Float) {
        player.setPlaybackSpeed(rate)
    }

    override val positionMs: Long get() = if (playerLazy.isInitialized()) player.currentPosition else 0L

    override val isAdvancing: Boolean get() = playerLazy.isInitialized() && player.isPlaying

    override fun stop() {
        awaitingReady = false
        if (!playerLazy.isInitialized()) return
        player.stop()
        player.clearMediaItems()
    }

    companion object {
        /**
         * Whether a [PlaybackException] error code says the file itself cannot be played here: the
         * parsing (`3xxx`: no extractor, malformed container) and decoding (`4xxx`: no decoder, format
         * unsupported) groups. I/O errors (`2xxx`) are not: the file may play once it is readable.
         */
        fun isUnsupported(errorCode: Int): Boolean = errorCode / 1000 == 3 || errorCode / 1000 == 4
    }
}
