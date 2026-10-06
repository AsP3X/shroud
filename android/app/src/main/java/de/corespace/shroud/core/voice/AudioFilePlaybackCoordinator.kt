package de.corespace.shroud.core.voice

import de.corespace.shroud.core.messaging.MessageArtifactSinks
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.isAudioFile
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

/** What the audio bubbles and the now-playing bar draw (docs/file-sharing.md §11.4–§11.6). */
data class AudioFilePlaybackState(
    /** The file loaded into the player: playing, or paused part-way. */
    val activeId: UUID? = null,
    val isPlaying: Boolean = false,
    /** Seconds into the active file. */
    val currentTime: Double = 0.0,
    /** Length of the active file in seconds; 0 until it is loaded (or when the container does not say). */
    val duration: Double = 0.0,
    /** The chosen speed, sticky for the session; it applies to files of [AudioFilePlaybackCoordinator.SPEED_MIN_DURATION_MS] or more. */
    val rate: Float = 1f,
    /** Files the player could not open in this session: they draw as plain file bubbles (§11.4 "Can't play here"). */
    val unplayableIds: Set<UUID> = emptySet(),
) {
    /** Whether the speed chip shows and the speed applies: the active file is 10 minutes or longer (§11.5). */
    val speedApplies: Boolean get() = activeId != null && duration * 1000 >= AudioFilePlaybackCoordinator.SPEED_MIN_DURATION_MS
}

/**
 * The single owner of audio-file playback (docs/file-sharing.md §11.5), next to
 * [VoicePlaybackCoordinator]: one file at a time, surviving its bubble scrolling away, and never two
 * sounds at once — starting a file stops a voice note ([onStarting]) and a voice note starting stops
 * the file ([stopForOtherSound]). The file is read from the sealed cache by id ([AudioFilePlayer]);
 * no bytes pass through here.
 *
 * - **End** of a file: the next audio file below it in the chat that is on this phone and playable
 *   ([nextAfter]) starts; otherwise playback stops.
 * - **Stops** on [stop] (the conversation closes, a call starts, a recording starts), a purge of the
 *   active message, and the chats locking ([artifactSink]). **Pauses** on [pause] (the app leaves the
 *   foreground) and when the system takes the audio away (focus loss, headphones unplugged).
 * - **Speed** 1× → 1.5× → 2× ([cycleRate]), session-sticky, applied only to files of 10 minutes or
 *   more ([AudioFilePlaybackState.speedApplies]).
 * - A file the player cannot open is [AudioFilePlaybackState.unplayableIds] for the session.
 *
 * ExoPlayer refreshes its position only when its playback loop wakes (a few times a second for
 * audio), so the playhead between reports is estimated from the clock and the speed, exactly as
 * [VoicePlaybackCoordinator.liveProgress] does ([liveProgress]); a ticker moves
 * [AudioFilePlaybackState.currentTime] for the text.
 *
 * Main-confined.
 */
class AudioFilePlaybackCoordinator(
    private val player: AudioFilePlayer,
    private val scope: CoroutineScope,
    /** The next audio file below [current] in its chat, on this phone (§11.5 auto-advance); null at the end. */
    private val nextAfter: (current: UUID) -> UUID? = { null },
    /** Runs before a file starts playing: voice notes stop (one sound at a time). */
    private val onStarting: () -> Unit = {},
    private val tickMs: Long = TICK_MS,
    /** Monotonic clock for the position estimate; tests drive it by hand. */
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val mutableState = MutableStateFlow(AudioFilePlaybackState())
    val state: StateFlow<AudioFilePlaybackState> = mutableState.asStateFlow()

    /** Bumped whenever the loaded file changes, so a late ready event cannot touch the next file. */
    private var generation = 0L
    private var loadedGeneration = -1L
    private var ready = false

    private var pendingFraction = 0.0
    private var wantsPlay = false
    private var ticker: Job? = null

    /** The position estimate between the player's reports (see [VoicePlaybackCoordinator]). */
    private var reportedMs = -1L
    private var baseMs = 0.0
    private var baseAt = 0L
    private var shownMs = 0.0

    /** Files that passed §4's content check this session ([markChecked]); cleared by a lock. */
    private val checkedIds = HashSet<UUID>()

    /**
     * Counts every start of playback (a file starting or resuming) and every other sound taking over
     * ([stopForOtherSound]). A tap that downloads first compares it, so the download only plays the
     * file when nothing else started meanwhile (§11.4).
     */
    var playStarts: Long = 0L
        private set

    init {
        player.listener = object : AudioFilePlayer.Listener {
            override fun onReady(durationMs: Long?) = handleReady(durationMs)
            override fun onEnded() = finishPlayback()
            override fun onError(unsupported: Boolean) = handleError(unsupported)
            override fun onPausedBySystem() = handleSystemPause()
        }
    }

    // ---- Queries ----

    fun isActive(id: UUID): Boolean = mutableState.value.activeId == id

    fun isPlaying(id: UUID): Boolean = mutableState.value.let { it.activeId == id && it.isPlaying }

    fun isUnplayable(id: UUID): Boolean = id in mutableState.value.unplayableIds

    /** Whether [id] passed §4's content check this session; until then the caller checks before playing. */
    fun isChecked(id: UUID): Boolean = id in checkedIds

    fun markChecked(id: UUID) {
        checkedIds += id
    }

    /** 0…1 position of [id] from the state (30 Hz text, not the per-frame playhead), 0 when it is not active. */
    fun progress(id: UUID): Double {
        val s = mutableState.value
        if (s.activeId != id || s.duration <= 0) return 0.0
        return min(1.0, max(0.0, s.currentTime / s.duration))
    }

    /**
     * [id]'s position (0…1) right now: estimated from the player's last report while it plays, else
     * [progress]. Read in the draw phase every frame, so the playhead glides without recomposing.
     */
    fun liveProgress(id: UUID): Double {
        val s = mutableState.value
        if (s.activeId != id || s.duration <= 0) return 0.0
        val seconds = if (ready && s.isPlaying) livePositionMs() / 1000.0 else s.currentTime
        return min(1.0, max(0.0, seconds / s.duration))
    }

    // ---- Transport ----

    /** Play or pause [id]; a file that is not the active one starts from its beginning. */
    fun toggle(id: UUID) {
        val s = mutableState.value
        if (s.activeId == id) {
            if (s.isPlaying) pause() else resume()
            return
        }
        play(id)
    }

    /** Plays [id]: resumes it when it is the active file, else starts it. Unplayable files are refused. */
    fun play(id: UUID) {
        if (isUnplayable(id)) return
        if (mutableState.value.activeId == id) {
            if (!mutableState.value.isPlaying) resume()
            return
        }
        start(id, fraction = 0.0, autoplay = true)
    }

    fun pause() {
        wantsPlay = false
        stopTicker()
        val s = mutableState.value
        if (s.activeId == null || !s.isPlaying) return
        val position = if (ready) livePositionMs() / 1000.0 else s.currentTime
        player.pause()
        update { it.copy(isPlaying = false, currentTime = clampToDuration(position)) }
    }

    fun resume() {
        if (mutableState.value.activeId == null) return
        onStarting()
        playStarts++
        wantsPlay = true
        update { it.copy(isPlaying = true) }
        if (!ready) return
        restartEstimate(mutableState.value.currentTime * 1000)
        player.setSpeed(effectiveRate())
        player.play()
        startTicker()
    }

    /** Scrubs the active file to [fraction] (0…1); the scrubber only shows on the active file. */
    fun seek(id: UUID, fraction: Double) {
        if (mutableState.value.activeId != id) return
        val clamped = if (fraction.isNaN()) 0.0 else min(1.0, max(0.0, fraction))
        if (!ready) {
            pendingFraction = clamped
            return
        }
        val duration = mutableState.value.duration
        if (duration <= 0) return
        val positionMs = (duration * clamped * 1000).roundToLong()
        player.seekTo(positionMs)
        restartEstimate(positionMs.toDouble())
        update { it.copy(currentTime = positionMs / 1000.0) }
    }

    /** 1× → 1.5× → 2× → 1×, live while a long file plays; it holds for the session. */
    fun cycleRate() {
        val index = RATES.indexOf(mutableState.value.rate).let { if (it < 0) 0 else it }
        val next = RATES[(index + 1) % RATES.size]
        if (ready && mutableState.value.isPlaying) {
            baseMs = livePositionMs()
            baseAt = nanoTime()
        }
        update { it.copy(rate = next) }
        if (ready) player.setSpeed(effectiveRate())
    }

    /** Unloads the active file; the speed and the unplayable set stay. */
    fun stop() {
        generation++
        ready = false
        wantsPlay = false
        pendingFraction = 0.0
        stopTicker()
        if (mutableState.value.activeId != null) player.stop()
        restartEstimate(0.0)
        update { it.copy(activeId = null, isPlaying = false, currentTime = 0.0, duration = 0.0) }
    }

    /** Stops if [id] is the active file. */
    fun stopIfActive(id: UUID) {
        if (mutableState.value.activeId == id) stop()
    }

    /** A voice note (or anything else that plays) is starting: the file stops (§11.5 one sound at a time). */
    fun stopForOtherSound() {
        playStarts++
        stop()
    }

    /** The app left the foreground (§11.5): a playing file pauses and stays active. */
    fun pauseForBackground() {
        if (mutableState.value.isPlaying) pause()
    }

    /** Purges, locks and re-keys from messaging (registered through `VoiceModule.artifactSink`). */
    val artifactSink: MessageArtifactSinks = object : MessageArtifactSinks {
        override fun onPurged(messageIds: Collection<UUID>) {
            val active = mutableState.value.activeId
            if (active != null && active in messageIds) stop()
            checkedIds.removeAll(messageIds.toSet())
            if (mutableState.value.unplayableIds.any { it in messageIds }) {
                update { it.copy(unplayableIds = it.unplayableIds - messageIds.toSet()) }
            }
        }

        override fun onSensitiveMemoryLocked() {
            stop()
            checkedIds.clear()
            update { it.copy(unplayableIds = emptySet()) }
        }

        override fun onMessageRekeyed(from: UUID, to: UUID) {
            if (from in checkedIds) {
                checkedIds -= from
                checkedIds += to
            }
            update { s ->
                s.copy(
                    activeId = if (s.activeId == from) to else s.activeId,
                    unplayableIds = if (from in s.unplayableIds) s.unplayableIds - from + to else s.unplayableIds,
                )
            }
        }
    }

    // ---- Internals ----

    private fun start(id: UUID, fraction: Double, autoplay: Boolean) {
        if (autoplay) {
            onStarting()
            playStarts++
        }
        generation++
        loadedGeneration = generation
        ready = false
        pendingFraction = fraction
        wantsPlay = autoplay
        stopTicker()
        restartEstimate(0.0)
        update { it.copy(activeId = id, isPlaying = autoplay, currentTime = 0.0, duration = 0.0) }
        // The speed applies once the length is known (only files of 10 minutes or more).
        player.setSpeed(1f)
        player.load(id)
    }

    private fun handleReady(durationMs: Long?) {
        if (loadedGeneration != generation || ready) return
        if (mutableState.value.activeId == null) return
        ready = true
        val duration = (durationMs ?: 0L) / 1000.0
        val startMs = if (duration > 0) (duration * pendingFraction * 1000).roundToLong() else 0L
        pendingFraction = 0.0
        if (startMs > 0) player.seekTo(startMs)
        restartEstimate(startMs.toDouble())
        update { it.copy(duration = duration, currentTime = startMs / 1000.0) }
        player.setSpeed(effectiveRate())
        if (wantsPlay) {
            player.play()
            update { it.copy(isPlaying = true) }
            startTicker()
        }
    }

    /** The end: the next playable file below plays, else playback stops (and the file is rewound). */
    private fun finishPlayback() {
        val finished = mutableState.value.activeId ?: return
        var next = nextAfter(finished)
        val seen = HashSet<UUID>()
        while (next != null && (isUnplayable(next) || next == finished) && seen.add(next)) next = nextAfter(next)
        if (next != null && !isUnplayable(next) && next != finished) {
            start(next, fraction = 0.0, autoplay = true)
        } else {
            stop()
        }
    }

    private fun handleError(unsupported: Boolean) {
        val id = mutableState.value.activeId
        stop()
        if (unsupported && id != null) update { it.copy(unplayableIds = it.unplayableIds + id) }
    }

    private fun handleSystemPause() {
        if (!mutableState.value.isPlaying) return
        val position = if (ready) livePositionMs() / 1000.0 else mutableState.value.currentTime
        wantsPlay = false
        stopTicker()
        update { it.copy(isPlaying = false, currentTime = clampToDuration(position)) }
    }

    private fun effectiveRate(): Float = if (mutableState.value.speedApplies) mutableState.value.rate else 1f

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(tickMs)
                if (!ready || !mutableState.value.isPlaying) return@launch
                val position = livePositionMs() / 1000.0
                update { it.copy(currentTime = clampToDuration(position)) }
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /**
     * Where the playing file is now, in ms: a new report (or a player that is not moving) restarts
     * the estimate there; in between it runs on at the playback speed, at most [MAX_ESTIMATE_MS] past
     * the report, and never below what it showed.
     */
    private fun livePositionMs(): Double {
        val reported = player.positionMs
        val now = nanoTime()
        if (reported != reportedMs || !player.isAdvancing) {
            reportedMs = reported
            baseMs = reported.toDouble()
            baseAt = now
        }
        val elapsedMs = min((now - baseAt) / 1_000_000.0, MAX_ESTIMATE_MS)
        shownMs = max(shownMs, baseMs + elapsedMs * effectiveRate())
        return shownMs
    }

    private fun restartEstimate(positionMs: Double) {
        reportedMs = -1L
        shownMs = positionMs
    }

    private fun clampToDuration(seconds: Double): Double {
        val duration = mutableState.value.duration
        return if (duration > 0) min(seconds, duration) else seconds
    }

    private inline fun update(transform: (AudioFilePlaybackState) -> AudioFilePlaybackState) {
        val next = transform(mutableState.value)
        if (next != mutableState.value) mutableState.value = next
    }

    companion object {
        /** The speed chip's steps (§11.5). */
        val RATES: List<Float> = listOf(1f, 1.5f, 2f)

        /** Files this long or longer get the speed chip (§11.5). */
        const val SPEED_MIN_DURATION_MS = 10 * 60 * 1000L

        /** How often the elapsed text refreshes while a file plays; the playhead itself is per frame. */
        const val TICK_MS = 100L

        /** How far the estimate may run past the player's last report. */
        const val MAX_ESTIMATE_MS = 1_000.0
    }
}

/** §11.5's auto-advance: where the next file comes from. Pure. */
object AudioFileQueue {
    /**
     * The first audio file below [current] in its chat (either sender) that is on this phone and not a
     * tombstone, or null. [threads] are the open chats, oldest first, as messaging keeps them.
     */
    fun nextAfter(threads: Map<UUID, List<ChatMessage>>, current: UUID): UUID? {
        for (thread in threads.values) {
            val index = thread.indexOfFirst { it.id == current }
            if (index < 0) continue
            return thread.subList(index + 1, thread.size).firstOrNull { it.isAudioFile && it.hasFullMedia && !it.deleted }?.id
        }
        return null
    }
}
