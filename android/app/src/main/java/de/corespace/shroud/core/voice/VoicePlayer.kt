package de.corespace.shroud.core.voice

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource

/**
 * The one audio player behind [VoicePlaybackCoordinator]; [ExoVoicePlayer] on a device, a fake in
 * tests. Main thread only. Events for a load arrive through [Listener] on the main thread.
 */
interface VoicePlayer {
    interface Listener {
        /** The loaded note is ready; [durationMs] is its length, or `null` when the container does not say. */
        fun onReady(durationMs: Long?)

        /** Playback reached the end. */
        fun onEnded()

        /** The bytes could not be played (corrupt, still encrypted, unknown container). */
        fun onError()

        /** The system paused playback for good: audio focus lost (a call), headphones unplugged. */
        fun onPausedBySystem()
    }

    var listener: Listener?

    /** Replaces whatever was loaded with [data] (any container ExoPlayer sniffs) and prepares it, paused. */
    fun load(data: ByteArray)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    /** Playback speed; pitch is kept (Sonic), like `AVAudioPlayer.enableRate`. */
    fun setSpeed(rate: Float)

    /** The current position of the loaded note. */
    val positionMs: Long

    /** Unloads the note. */
    fun stop()
}

/**
 * [VoicePlayer] on Media3 ExoPlayer (media-voice-links §8.3): built once on the main looper and
 * reused; the note plays from memory through a [ByteArrayDataSource] (voice notes are small, no temp
 * file). Audio attributes `USAGE_MEDIA` / `AUDIO_CONTENT_TYPE_SPEECH` with ExoPlayer's own audio-focus
 * handling (iOS `ChatAudioSession.spokenPlayback`, `ChatAudioSession.swift:21`), and a pause when
 * headphones are unplugged (the Android convention). The default extractors sniff every container a
 * note arrives in: `audio/mp4` AAC (iPhone, Android), `audio/wav` 16-bit mono (web), `audio/webm`
 * Opus (web fallback).
 */
@OptIn(UnstableApi::class)
class ExoVoicePlayer(context: Context) : VoicePlayer {
    private val appContext = context.applicationContext
    private var awaitingReady = false

    override var listener: VoicePlayer.Listener? = null

    private val playerLazy = lazy {
        ExoPlayer.Builder(appContext)
            .setLooper(Looper.getMainLooper())
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { it.addListener(events) }
    }

    /** Built on first use, so stopping a player that never played costs nothing. */
    private val player: ExoPlayer by playerLazy

    private val events = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> if (awaitingReady) {
                    awaitingReady = false
                    val duration = player.duration
                    listener?.onReady(if (duration == C.TIME_UNSET || duration < 0) null else duration)
                }
                Player.STATE_ENDED -> listener?.onEnded()
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            awaitingReady = false
            listener?.onError()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) return
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
            ) {
                listener?.onPausedBySystem()
            }
        }
    }

    override fun load(data: ByteArray) {
        val factory = DataSource.Factory { ByteArrayDataSource(data) }
        // The URI only names the item; the bytes come from the data source.
        val source = ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(VOICE_URI))
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

    override fun stop() {
        awaitingReady = false
        if (!playerLazy.isInitialized()) return
        player.stop()
        player.clearMediaItems()
    }

    private companion object {
        val VOICE_URI: Uri = Uri.parse("shroud-voice:note")
    }
}
