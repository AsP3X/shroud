package de.corespace.shroud.core.media.video

import android.content.Context
import android.net.Uri
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.math.floor

/**
 * Playback for one chat video with a custom transport (`ChatVideoPlayer`,
 * `ios/shroud/Services/Video/ChatVideoPlayer.swift`; conversation-compose-media §16;
 * media-voice-links §6.5). Owns a bare player and publishes exactly what the overlay and the
 * compose screen draw: a playhead, a duration and a play state. One instance per overlay / compose
 * screen, from `VideoModule.newPlayer()`; main-thread only.
 *
 * - [start] with a message id plays the sealed local file through a decrypting source (plan C7) —
 *   iOS writes `tmp/shroud-play-<uuid>.mp4` instead (`:38-51`); with a URI it plays a picked or
 *   captured file (compose preview, `:53`). Safe to call twice: a no-op while started or failed.
 * - Ready wait: 8 s, then ready only if the player is (`:114-153`); otherwise [State.failed].
 * - Once ready: autoplay; the playhead is published about 30 times a second while playing, not
 *   while [isScrubbing]; with [loopRange] playback wraps to its start 0.03 s before its end (`:84-98`).
 * - [teardown] is mandatory: it releases the player and resets everything, `failed` included, so one
 *   bad clip does not poison the next in compose (`:189-213`); an in-flight [start] cannot attach
 *   after it.
 */
class ChatVideoPlayer internal constructor(
    private val engines: PlaybackEngine.Factory,
    private val scope: CoroutineScope,
) {
    /** The production player; [sources] reads sealed local videos (`VideoModule`). */
    constructor(context: Context, sources: SealedVideoSources) :
        this(ExoPlaybackEngine.Factory(context.applicationContext, sources), MainScope())

    /** What the transport draws (`ChatVideoPlayer.swift:14-36`). */
    data class State(
        val duration: Double = 0.0,
        val currentTime: Double = 0.0,
        val isPlaying: Boolean = false,
        val isReady: Boolean = false,
        val failed: Boolean = false,
    ) {
        /** Playhead as 0…1 for the scrubber. */
        val progress: Double get() = if (duration > 0) (currentTime / duration).coerceIn(0.0, 1.0) else 0.0
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _player = MutableStateFlow<Player?>(null)

    /** For the surface (`PlayerSurface`, TextureView); null until [start] created one. */
    val player: StateFlow<Player?> = _player.asStateFlow()

    /** True while a finger owns the playhead; ticks are ignored until it lifts (`:20-21`). */
    var isScrubbing: Boolean = false

    /** When set, playback wraps inside this window — the compose screen's trim preview (`:22-23`). */
    var loopRange: ClosedFloatingPointRange<Double>? = null

    private var engine: PlaybackEngine? = null
    private var ticker: Job? = null
    private var muted = false

    /** Bumped by [teardown] so an in-flight [start] cannot attach a player after the screen left (`:29-30`). */
    private var startId = 0

    /** Plays the sealed local video of [messageId]. */
    suspend fun start(messageId: UUID) = start(PlaybackSource.Local(messageId))

    /** Plays a file that already exists (compose preview); it is never deleted here (`:53`). */
    suspend fun start(uri: Uri) = start(PlaybackSource.Content(uri))

    internal suspend fun start(source: PlaybackSource) {
        if (engine != null || _state.value.failed) return
        startId += 1
        val id = startId
        val created = engines.create(source)
        if (id != startId || engine != null) {
            created?.release()
            return
        }
        if (created == null) {
            _state.update { it.copy(failed = true) }
            return
        }
        engine = created
        created.onEnded = { if (engine === created) reachedEnd() }
        created.setVolume(if (muted) 0f else 1f)
        _player.value = created.player

        val ready = withTimeoutOrNull(READY_TIMEOUT_MS) {
            created.status.first { it != PlaybackEngine.Status.Preparing }
        }.let { it == PlaybackEngine.Status.Ready || (it == null && created.status.value == PlaybackEngine.Status.Ready) }
        if (id != startId || engine !== created) return
        if (!ready) {
            _state.update { it.copy(failed = true) }
            return
        }
        _state.update { it.copy(duration = created.durationSeconds.takeIf { d -> d.isFinite() && d > 0 } ?: 0.0) }
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick(created)
            }
        }
        _state.update { it.copy(isReady = true) }
        play()
    }

    /** Replaying after the end, or outside the loop window, rewinds first (`:155-165`). */
    fun play() {
        val current = engine ?: return
        val loop = loopRange
        val now = _state.value
        if (loop != null && (now.currentTime < loop.start || now.currentTime >= loop.endInclusive - 0.05)) {
            seek(loop.start, precise = true)
        } else if (now.duration > 0 && now.currentTime >= now.duration - 0.05) {
            seek(0.0, precise = true)
        }
        current.play()
        _state.update { it.copy(isPlaying = true) }
    }

    fun pause() {
        engine?.pause()
        _state.update { it.copy(isPlaying = false) }
    }

    fun toggle() {
        if (_state.value.isPlaying) pause() else play()
    }

    /** Moves the playhead: tolerant (nearest sync frame) while dragging, exact on release (`:176-187`). */
    fun seek(seconds: Double, precise: Boolean = false) {
        val current = engine ?: return
        val duration = _state.value.duration
        if (duration <= 0 || seconds.isNaN()) return
        val clamped = seconds.coerceIn(0.0, duration)
        _state.update { it.copy(currentTime = clamped) }
        current.seekTo(clamped, precise)
    }

    /** Sound off/on for the compose preview (iOS sets `player.isMuted`, `VideoComposeOverlay.swift:637, 661`). */
    fun setMuted(muted: Boolean) {
        this.muted = muted
        engine?.setVolume(if (muted) 0f else 1f)
    }

    /** Releases the player and resets every value, `failed` included (`:189-213`). */
    fun teardown() {
        startId += 1
        ticker?.cancel()
        ticker = null
        engine?.let {
            it.onEnded = null
            it.pause()
            it.release()
        }
        engine = null
        _player.value = null
        muted = false
        loopRange = null
        _state.value = State()
    }

    /** The periodic observer (`:84-98`), polled while playing. */
    private fun tick(source: PlaybackEngine) {
        if (engine !== source || isScrubbing || !source.isPlaying) return
        val seconds = source.positionSeconds
        if (!seconds.isFinite()) return
        val loop = loopRange
        if (loop != null && seconds >= loop.endInclusive - 0.03) {
            seek(loop.start)
            return
        }
        _state.update { it.copy(currentTime = maxOf(0.0, seconds)) }
    }

    /** `didPlayToEndTime` (`:215-218`). */
    private fun reachedEnd() {
        _state.update { it.copy(isPlaying = false, currentTime = if (it.duration > 0) it.duration else it.currentTime) }
    }

    companion object {
        /** `waitUntilReady` gives up after 8 s (`:148-151`). */
        internal const val READY_TIMEOUT_MS = 8_000L

        /** The periodic observer's 1/30 s (`:84-86`). */
        internal const val TICK_MS = 33L

        /** `m:ss` for the transport labels, rounded down; non-finite or negative → "0:00" (`:220-225`). */
        fun timeLabel(seconds: Double): String {
            if (!seconds.isFinite() || seconds < 0) return "0:00"
            val total = floor(seconds).toLong()
            val rest = total % 60
            return "${total / 60}:${if (rest < 10) "0" else ""}$rest"
        }
    }
}
