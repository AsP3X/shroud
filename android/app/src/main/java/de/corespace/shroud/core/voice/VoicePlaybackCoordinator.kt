package de.corespace.shroud.core.voice

import de.corespace.shroud.core.messaging.MessageArtifactSinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** What the voice bubbles draw (`ios/shroud/Services/Voice/VoicePlaybackCoordinator.swift:21-29`). */
data class VoicePlaybackState(
    /** The note loaded into the player, playing or paused mid-way. */
    val activeId: UUID? = null,
    val isPlaying: Boolean = false,
    /** Seconds into the active note. */
    val currentTime: Double = 0.0,
    /** Length of the active note in seconds; 0 until it is loaded (or when the container does not say). */
    val duration: Double = 0.0,
    /** Sticky across notes, like Telegram's per-chat speed. */
    val rate: Float = 1f,
    /** Notes listened to in this process (in memory only); drives the unplayed dot. */
    val playedIds: Set<UUID> = emptySet(),
)

/**
 * The single owner of voice-note playback — iOS `VoicePlaybackCoordinator`
 * (`ios/shroud/Services/Voice/VoicePlaybackCoordinator.swift:5-198`; media-voice-links §8.3; plan
 * §1.7.9). One note at a time: starting a note stops the previous one, and playback survives a bubble
 * scrolling away. Holds no bytes beyond the player's copy; never fetches or decrypts — the bubble hands
 * in the decrypted audio (`MessagingController.mediaBytes`, plan C8).
 *
 * Differences forced by ExoPlayer: a note is marked active at once and prepared asynchronously;
 * duration, a pending seek and autoplay are applied when the player reports ready (iOS's
 * `AVAudioPlayer(data:)` knows the duration synchronously, `:141-155`). End of playback comes from the
 * player (`STATE_ENDED`) instead of the ticker noticing `isPlaying` dropped (`:176-182`); the 33 ms
 * ticker still drives [VoicePlaybackState.currentTime].
 *
 * Stopped by: a new note, [stopIfActive] (bubble gone while paused, message deleted —
 * [artifactSink] `onPurged`, `MessagingController.swift:1969`), chats locking (`onSensitiveMemoryLocked`),
 * the Log Out wipe (`DeviceWipeController.swift:92, 125`), a call's media starting
 * (`CallController.swift:1948`), recording starting (`ConversationView.swift:1420`) and leaving the
 * thread (`:370`).
 *
 * Main-confined.
 */
class VoicePlaybackCoordinator(
    private val player: VoicePlayer,
    private val scope: CoroutineScope,
    private val tickMs: Long = TICK_MS,
) {
    private val mutableState = MutableStateFlow(VoicePlaybackState())
    val state: StateFlow<VoicePlaybackState> = mutableState.asStateFlow()

    /** Bumped whenever the loaded note changes, so a late ready event cannot touch the next note. */
    private var generation = 0L
    private var loadedGeneration = -1L
    private var ready = false

    /** Applied when the loading note becomes ready: where to start, whether to play. */
    private var pendingFraction = 0.0
    private var wantsPlay = false
    private var ticker: Job? = null

    init {
        player.listener = object : VoicePlayer.Listener {
            override fun onReady(durationMs: Long?) = handleReady(durationMs)
            override fun onEnded() = finishPlayback()
            override fun onError() = stop()
            override fun onPausedBySystem() = handleSystemPause()
        }
    }

    // ---- Queries (`VoicePlaybackCoordinator.swift:38-56`) ----

    /** 0…1 position of [id], or 0 when it is not the active note (`:40-44`). */
    fun progress(id: UUID): Double {
        val s = mutableState.value
        if (s.activeId != id || s.duration <= 0) return 0.0
        return min(1.0, max(0.0, s.currentTime / s.duration))
    }

    fun isActive(id: UUID): Boolean = mutableState.value.activeId == id

    fun isPlaying(id: UUID): Boolean = mutableState.value.let { it.activeId == id && it.isPlaying }

    /** Elapsed seconds while [id] is active, otherwise its own length (`:50-54`). */
    fun displayTime(id: UUID, fallbackMs: Int): Double {
        val s = mutableState.value
        return if (s.activeId == id) s.currentTime else fallbackMs / 1000.0
    }

    fun hasPlayed(id: UUID): Boolean = id in mutableState.value.playedIds

    // ---- Transport (`:58-129`) ----

    /** Play/pause [id], loading [data] when it is not the active note (`:60-71`). */
    fun toggle(id: UUID, data: ByteArray) {
        val s = mutableState.value
        if (s.activeId == id) {
            if (s.isPlaying) pause() else resume()
            return
        }
        start(id, data, fraction = 0.0, autoplay = true)
    }

    fun pause() {
        wantsPlay = false
        stopTicker()
        if (mutableState.value.activeId == null) return
        player.pause()
        val position = if (ready) player.positionMs / 1000.0 else mutableState.value.currentTime
        update { it.copy(isPlaying = false, currentTime = position) }
    }

    /** Resumes the active note at the sticky rate (`:80-90`). */
    fun resume() {
        if (mutableState.value.activeId == null) return
        wantsPlay = true
        update { it.copy(isPlaying = true) }
        if (!ready) return
        player.setSpeed(mutableState.value.rate)
        player.play()
        startTicker()
    }

    /** Scrubs [id] to [fraction] (0…1); a note that is not loaded yet loads paused at that point (`:92-103`). */
    fun seek(id: UUID, data: ByteArray, fraction: Double) {
        val clamped = clampFraction(fraction)
        if (mutableState.value.activeId != id) {
            start(id, data, fraction = clamped, autoplay = false)
            return
        }
        if (!ready) {
            pendingFraction = clamped
            return
        }
        val duration = mutableState.value.duration
        if (duration <= 0) return
        val positionMs = (duration * clamped * 1000).roundToLong()
        player.seekTo(positionMs)
        update { it.copy(currentTime = positionMs / 1000.0) }
    }

    /** Advances to the next speed in [RATES], live if something is playing (`:105-112`). */
    fun cycleRate() {
        val index = RATES.indexOf(mutableState.value.rate).let { if (it < 0) 0 else it }
        val next = RATES[(index + 1) % RATES.size]
        update { it.copy(rate = next) }
        if (ready && mutableState.value.isPlaying) player.setSpeed(next)
    }

    /** Unloads everything; the rate and the played set stay (`:114-123`). */
    fun stop() {
        generation++
        ready = false
        wantsPlay = false
        pendingFraction = 0.0
        stopTicker()
        player.stop()
        update { it.copy(activeId = null, isPlaying = false, currentTime = 0.0, duration = 0.0) }
    }

    /** Stops if [id] is the active note (bubble disappearing while paused, message deleted) (`:125-129`). */
    fun stopIfActive(id: UUID) {
        if (mutableState.value.activeId == id) stop()
    }

    /**
     * Purges, locks and re-keys from the messaging engines (plan §1.7.7 `MessageArtifactSinks`);
     * W2-INT registers it with `MessagingController.registerArtifactSink`.
     */
    val artifactSink: MessageArtifactSinks = object : MessageArtifactSinks {
        override fun onPurged(messageIds: Collection<UUID>) {
            val active = mutableState.value.activeId
            if (active != null && active in messageIds) stop()
            if (mutableState.value.playedIds.any { it in messageIds }) {
                update { it.copy(playedIds = it.playedIds - messageIds.toSet()) }
            }
        }

        override fun onSensitiveMemoryLocked() = stop()

        override fun onMessageRekeyed(from: UUID, to: UUID) {
            update { s ->
                s.copy(
                    activeId = if (s.activeId == from) to else s.activeId,
                    playedIds = if (from in s.playedIds) s.playedIds - from + to else s.playedIds,
                )
            }
        }
    }

    // ---- Internals (`:131-197`) ----

    private fun start(id: UUID, data: ByteArray, fraction: Double, autoplay: Boolean) {
        generation++
        loadedGeneration = generation
        ready = false
        pendingFraction = fraction
        wantsPlay = autoplay
        stopTicker()
        update { it.copy(activeId = id, isPlaying = autoplay, currentTime = 0.0, duration = 0.0) }
        player.setSpeed(mutableState.value.rate)
        player.load(data)
    }

    private fun handleReady(durationMs: Long?) {
        if (loadedGeneration != generation || ready) return
        val id = mutableState.value.activeId ?: return
        ready = true
        val duration = (durationMs ?: 0L) / 1000.0
        val startMs = if (duration > 0) (duration * pendingFraction * 1000).roundToLong() else 0L
        pendingFraction = 0.0
        if (startMs > 0) player.seekTo(startMs)
        update { it.copy(duration = duration, currentTime = startMs / 1000.0, playedIds = it.playedIds + id) }
        if (wantsPlay) {
            player.setSpeed(mutableState.value.rate)
            player.play()
            update { it.copy(isPlaying = true) }
            startTicker()
        }
    }

    /** Reached the end: rewind so the next tap replays from the start (`:192-197`). */
    private fun finishPlayback() {
        if (mutableState.value.activeId == null) return
        wantsPlay = false
        stopTicker()
        player.pause()
        player.seekTo(0)
        update { it.copy(isPlaying = false, currentTime = 0.0) }
    }

    private fun handleSystemPause() {
        if (!mutableState.value.isPlaying) return
        wantsPlay = false
        stopTicker()
        update { it.copy(isPlaying = false, currentTime = player.positionMs / 1000.0) }
    }

    /** 30 Hz is enough for a smooth playhead (`:169-185`). */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(tickMs)
                if (!ready || !mutableState.value.isPlaying) return@launch
                val position = player.positionMs / 1000.0
                update { it.copy(currentTime = position) }
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /** Equality-guarded write (plan §1.1 rule 3). */
    private inline fun update(transform: (VoicePlaybackState) -> VoicePlaybackState) {
        val next = transform(mutableState.value)
        if (next != mutableState.value) mutableState.value = next
    }

    private fun clampFraction(fraction: Double): Double = if (fraction.isNaN()) 0.0 else min(1.0, max(0.0, fraction))

    companion object {
        /** Speeds cycled by the bubble's rate chip (`:18-19`); chip text via [rateLabel]. */
        val RATES: List<Float> = listOf(1f, 1.5f, 2f)

        /** Playhead refresh while playing (`:174`). */
        const val TICK_MS = 33L

        /** `1×`, `1.5×`, `2×`: whole rates without decimals, else one decimal (`VoiceMessageBubble.swift:479-507`). */
        fun rateLabel(rate: Float): String {
            val whole = rate.toInt()
            return if (rate == whole.toFloat()) "$whole×" else String.format(java.util.Locale.ROOT, "%.1f×", rate)
        }
    }
}
