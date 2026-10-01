package de.corespace.shroud.core.voice

import de.corespace.shroud.core.lifecycle.AppPhase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToLong

/** Why a take could not start or finish; [message] is the iOS copy, shown to the user as is. */
class VoiceRecorderException(val reason: Reason, cause: Throwable? = null) : Exception(reason.message, cause) {
    /** iOS `VoiceRecorder.RecorderError` (`ios/shroud/Services/Voice/VoiceRecorder.swift:19-33`). */
    enum class Reason(val message: String) {
        /** `RECORD_AUDIO` is not granted. The composer asks for it before the gesture starts a take (conversation-compose-media §4.7). */
        PermissionDenied("Microphone access is required for voice messages."),
        AlreadyRecording("Already recording."),
        NotRecording("Not recording."),

        /**
         * The microphone or encoder failed, or the file came out empty. Also used when the microphone
         * cannot start at all (audio focus refused during a phone call, `AudioRecord` busy): iOS maps
         * `recorder.record()` returning false to this case too (`VoiceRecorder.swift:92`).
         */
        EncodeFailed("Could not finish the recording."),
    }
}

/**
 * Records voice messages (AAC-LC, 44.1 kHz, mono, `audio/mp4`) and the amplitude envelope that the
 * live bar animates and the sent note carries — iOS `VoiceRecorder`
 * (`ios/shroud/Services/Voice/VoiceRecorder.swift:5-212`; media-voice-links §8.1, D8; plan §1.7.9).
 * One per process (one microphone), built by `VoiceModule`.
 *
 * Every take is a fresh [PcmSource] → [VoiceEncoder] pair writing `cacheDir/shroud-voice-*.m4a`
 * (from [createTempFile]); a recording loop on [ioDispatcher] reads 10 ms of PCM at a time, encodes
 * it and cuts 50 ms metering windows ([VoiceLevel.Meter]): one envelope level per window, appended to
 * the take's envelope and to [state]'s `liveLevels` (the newest [LIVE_WINDOW]). The file is always
 * deleted: after [finish] read it, on [cancel], on failure; a crash leaves it to
 * `SensitiveTempFiles.prepareAtLaunch`.
 *
 * The take is cancelled when the app goes to the background (Android 14+ silences background
 * capture without a microphone foreground service; iOS tears the thread down) and when another app
 * or a call takes the audio focus (iOS: a call's media starting cancels, `ConversationView.swift:380-383`).
 * [state] then reads `recording = false`; the composer folds its bar on that.
 *
 * Main-confined API ([start], [finish], [cancel] on `Dispatchers.Main.immediate`); the loop
 * publishes levels under [lock], gated per take so a cancelled take never writes again.
 *
 * @param scope the app scope (main): runs the recording loop's launch and the app-phase watch.
 * @param hasPermission whether `RECORD_AUDIO` is granted.
 * @param createTempFile a new `SensitiveTempFiles.create("voice", "m4a")` file per take.
 * @param appPhase the process phase; [AppPhase.Background] cancels a take.
 */
class VoiceRecorder(
    private val scope: CoroutineScope,
    private val capture: VoiceCaptureFactory,
    private val audioFocus: AudioFocusCoordinator,
    private val hasPermission: () -> Boolean,
    private val createTempFile: () -> File,
    appPhase: StateFlow<AppPhase>? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The recorder as the composer sees it (web `RecState`, `web/src/voice/recorder.ts:27-33`). */
    data class RecState(
        val recording: Boolean = false,
        /** Seconds of audio captured so far; refreshed every 50 ms so the timer can show centiseconds (`VoiceRecorder.swift:49-50`). */
        val elapsedSeconds: Double = 0.0,
        /** The newest levels (0…1), oldest first — the live waveform (`VoiceRecorder.swift:51-52`). */
        val liveLevels: List<Float> = emptyList(),
        /** Levels metered so far this take; keys the live bars so they keep their identity while scrolling (web). */
        val levelCount: Int = 0,
    )

    /** A finished take ready to seal and upload (`VoiceRecorder.swift:35-41`). */
    class Recording(
        /** The `.m4a` bytes (`audio/mp4`). */
        val data: ByteArray,
        val durationMs: Int,
        /** [WAVEFORM_BUCKETS] bytes, 0…255 each, for the payload's `wf`; empty when nothing was metered. */
        val waveform: ByteArray,
    ) {
        override fun toString(): String = "Recording(bytes=${data.size}, durationMs=$durationMs, buckets=${waveform.size})"
    }

    private val lock = Any()
    private val mutableState = MutableStateFlow(RecState())

    /** Recording, elapsed, live levels (media-voice-links §8.1). */
    val state: StateFlow<RecState> = mutableState.asStateFlow()

    /** iOS `isRecording`. */
    val isRecording: Boolean get() = state.value.recording

    private var active: Take? = null
    private var starting = false

    /** Bumped by [start], [finish] and [cancel]; a start that finds it changed was abandoned. */
    private var generation = 0L

    /** The last take's loop: a new take waits for it so the microphone is free again. */
    private var lastLoop: Job? = null

    init {
        if (appPhase != null) {
            scope.launch {
                appPhase.collect { if (it == AppPhase.Background) cancel() }
            }
        }
    }

    /**
     * Starts a take (`VoiceRecorder.swift:75-102`). Returns true when recording; false when [cancel]
     * ran while the microphone was opening (the user let go, or the app left the foreground) — the
     * take is then already gone. Throws [VoiceRecorderException]: [VoiceRecorderException.Reason.AlreadyRecording],
     * [VoiceRecorderException.Reason.PermissionDenied] (asked before the gesture, not here),
     * [VoiceRecorderException.Reason.EncodeFailed] when the microphone or encoder cannot start.
     */
    suspend fun start(): Boolean {
        if (active != null || starting) throw VoiceRecorderException(VoiceRecorderException.Reason.AlreadyRecording)
        if (!hasPermission()) throw VoiceRecorderException(VoiceRecorderException.Reason.PermissionDenied)
        starting = true
        val attempt = ++generation
        try {
            lastLoop?.join()
            if (attempt != generation) return false
            if (!audioFocus.requestRecording(onLost = ::cancel)) {
                throw VoiceRecorderException(VoiceRecorderException.Reason.EncodeFailed)
            }
            // Not cancellable: an opened microphone must never be dropped on the floor.
            val take = try {
                withContext(ioDispatcher + NonCancellable) { openTake() }
            } catch (e: Exception) {
                if (attempt == generation) audioFocus.abandonRecording()
                throw e
            }
            val callerActive = currentCoroutineContext().isActive
            if (attempt != generation || !callerActive) {
                // Abandoned while the microphone opened: a tap, not a message.
                withContext(ioDispatcher + NonCancellable) { take.discard() }
                if (attempt == generation) audioFocus.abandonRecording()
                currentCoroutineContext().ensureActive()
                return false
            }
            active = take
            synchronized(lock) {
                take.publishing = true
                mutableState.value = RecState(recording = true)
            }
            val loop = scope.launch(ioDispatcher) { runLoop(take) }
            take.loop = loop
            lastLoop = loop
            return true
        } finally {
            starting = false
        }
    }

    /**
     * Stops, keeps the audio and returns it with its duration and waveform; null when the take was
     * shorter than [MINIMUM_DURATION_S] — a mis-tap, not a message (`VoiceRecorder.swift:104-128`).
     * The duration and waveform are read before anything is torn down (an iOS build once shipped flat
     * waveforms because teardown cleared the envelope first, `:111-113`, `:197-206`). Throws
     * [VoiceRecorderException.Reason.NotRecording] without a take, [VoiceRecorderException.Reason.EncodeFailed]
     * when the file cannot be completed or is empty. The temp file is deleted in every case.
     */
    suspend fun finish(): Recording? {
        val take = active ?: throw VoiceRecorderException(VoiceRecorderException.Reason.NotRecording)
        active = null
        generation++
        take.stopRequested = true
        stopPublishing(take)
        audioFocus.abandonRecording()
        // Not cancellable: the temp file is deleted however the caller ends.
        return withContext(NonCancellable) {
            take.loop?.join()
            withContext(ioDispatcher) { complete(take) }
        }
    }

    /**
     * Stops and throws the take away (slide to cancel, discard, leaving the thread, the app going to
     * the background, a call) (`VoiceRecorder.swift:130-139`). Also abandons a [start] in progress.
     * Safe to call at any time; the loop deletes the file.
     */
    fun cancel() {
        generation++
        // Also releases the focus a start in progress already took.
        audioFocus.abandonRecording()
        val take = active
        active = null
        if (take == null) {
            synchronized(lock) { mutableState.value = RecState() }
            return
        }
        take.cancelled = true
        stopPublishing(take)
    }

    private fun stopPublishing(take: Take) {
        synchronized(lock) {
            take.publishing = false
            mutableState.value = RecState()
        }
    }

    /** Opens the microphone, the encoder and the temp file (IO). Cleans up after itself on failure. */
    private fun openTake(): Take {
        val file = try {
            createTempFile()
        } catch (e: Exception) {
            throw VoiceRecorderException(VoiceRecorderException.Reason.EncodeFailed, e)
        }
        var encoder: VoiceEncoder? = null
        var source: PcmSource? = null
        try {
            encoder = capture.openEncoder(file)
            source = capture.openSource()
            source.start()
            return Take(file, source, encoder)
        } catch (e: Exception) {
            source?.let { runCatching { it.stop() }; runCatching { it.release() } }
            encoder?.abort()
            file.delete()
            throw VoiceRecorderException(VoiceRecorderException.Reason.EncodeFailed, e)
        }
    }

    /** The recording loop (IO): read → encode → meter until finished, cancelled or failed. */
    private fun runLoop(take: Take) {
        val buffer = ShortArray(CHUNK_FRAMES)
        val meter = VoiceLevel.Meter(WINDOW_FRAMES)
        try {
            while (!take.stopRequested && !take.cancelled) {
                val read = take.source.read(buffer, 0, buffer.size)
                if (read < 0) {
                    take.failed = true
                    break
                }
                if (take.cancelled) continue
                if (read == 0) {
                    // Nothing captured (a source that is being stopped): don't spin.
                    Thread.sleep(EMPTY_READ_BACKOFF_MS)
                    continue
                }
                take.encoder.write(buffer, 0, read)
                take.frames += read
                meter.add(buffer, 0, read) { level ->
                    take.envelope.add(level)
                    publishLevel(take, level)
                }
            }
        } catch (_: Exception) {
            take.failed = true
        } finally {
            runCatching { take.source.stop() }
            runCatching { take.source.release() }
            // A failed take is over too: whether finish() (EncodeFailed) or cancel() (slide away,
            // background, focus loss, wipe) ends it, its partial plaintext audio must not stay in
            // cacheDir (plan §1.1 rule 7). complete() never reads the file of a failed take, and
            // abort() is idempotent.
            if (take.cancelled || take.failed) {
                take.encoder.abort()
                take.file.delete()
            }
        }
    }

    private fun publishLevel(take: Take, level: Float) {
        synchronized(lock) {
            if (!take.publishing) return
            val previous = mutableState.value
            val live = if (previous.liveLevels.size >= LIVE_WINDOW) {
                previous.liveLevels.subList(previous.liveLevels.size - LIVE_WINDOW + 1, previous.liveLevels.size) + level
            } else {
                previous.liveLevels + level
            }
            mutableState.value = RecState(
                recording = true,
                elapsedSeconds = take.frames.toDouble() / VoiceFormat.SAMPLE_RATE,
                liveLevels = live,
                levelCount = previous.levelCount + 1,
            )
        }
    }

    /** After the loop ended (IO): duration and waveform first, then the file. */
    private fun complete(take: Take): Recording? {
        try {
            val seconds = take.frames.toDouble() / VoiceFormat.SAMPLE_RATE
            val waveform = VoiceWaveform.downsample(take.envelope, WAVEFORM_BUCKETS)
            if (seconds < MINIMUM_DURATION_S) {
                take.encoder.abort()
                return null
            }
            if (take.failed) {
                take.encoder.abort()
                throw VoiceRecorderException(VoiceRecorderException.Reason.EncodeFailed)
            }
            try {
                take.encoder.finish()
            } catch (e: Exception) {
                throw VoiceRecorderException(VoiceRecorderException.Reason.EncodeFailed, e)
            }
            val data = try {
                take.file.readBytes()
            } catch (e: Exception) {
                throw VoiceRecorderException(VoiceRecorderException.Reason.EncodeFailed, e)
            }
            if (data.isEmpty()) throw VoiceRecorderException(VoiceRecorderException.Reason.EncodeFailed)
            return Recording(data = data, durationMs = durationMs(seconds), waveform = waveform)
        } finally {
            take.file.delete()
        }
    }

    /** One take's resources and what the loop gathered. The loop writes; [complete] reads after joining it. */
    private class Take(val file: File, val source: PcmSource, val encoder: VoiceEncoder) {
        @Volatile var stopRequested = false

        @Volatile var cancelled = false

        /** Guarded by the recorder's lock: false once the take no longer owns [state]. */
        var publishing = false
        var loop: Job? = null
        var failed = false
        var frames = 0L
        val envelope = ArrayList<Float>()

        fun discard() {
            runCatching { source.stop() }
            runCatching { source.release() }
            encoder.abort()
            file.delete()
        }
    }

    companion object {
        /** Anything shorter is an accidental tap, not a message (`VoiceRecorder.swift:43-44`). */
        const val MINIMUM_DURATION_S = 0.6

        /** Buckets sealed into the payload — enough for the widest bubble (`VoiceRecorder.swift:45-46`). */
        const val WAVEFORM_BUCKETS = 44

        /** How many live levels the recording bar shows at once (`VoiceRecorder.swift:61-62`). */
        const val LIVE_WINDOW = 44

        /** 50 ms of audio per envelope level (20 Hz, `VoiceRecorder.swift:63`). */
        const val WINDOW_FRAMES = VoiceFormat.SAMPLE_RATE * VoiceLevel.METER_INTERVAL_MS / 1000

        /** 10 ms per microphone read, so a stop request is seen within one read. */
        const val CHUNK_FRAMES = VoiceFormat.SAMPLE_RATE / 100

        private const val EMPTY_READ_BACKOFF_MS = 5L

        /** iOS `VoiceRecorder.normalize(average:peak:)` (`VoiceRecorder.swift:174-182`). */
        fun normalize(averageDb: Float, peakDb: Float): Float = VoiceLevel.normalize(averageDb, peakDb)

        /** `max(1, round(seconds × 1000))`, Swift `.rounded()` (half away from zero) (`VoiceRecorder.swift:125`). */
        fun durationMs(seconds: Double): Int = max(1L, (seconds * 1000.0).roundToLong()).toInt()
    }
}
