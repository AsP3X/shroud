package de.corespace.shroud.core.transcription

import java.io.Closeable
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.exp
import kotlin.math.min

/**
 * The JNI surface of whisper.cpp v1.9.4 (media-voice-links §9.9 "JNI surface";
 * `app/src/main/cpp/whisper_jni.cpp`). Android's replacement for WhisperKit
 * (`WhisperKitEngine.swift`), CPU only: ggml's CPU backend in the variant this phone's instruction
 * set runs best (dot product, fp16, i8mm …), chosen when the first model loads.
 *
 * Raw calls; [WhisperContext] is the safe wrapper. A context is used by one thread at a time
 * ("one Whisper instance must never decode two notes at once", `TranscriptionEngineTests.swift:49-62`);
 * only [abort] may be called from another thread. R8 keeps the native method names
 * (`proguard-rules.pro`, W0-A).
 *
 * Nothing on the native side logs audio, text, the detected language or file names.
 */
internal object WhisperNative {
    const val LIBRARY = "shroud_whisper"

    /**
     * True once `libshroud_whisper.so` (with `libggml*.so` and `libc++_shared.so`) is loaded; false
     * on a build without it (JVM unit tests) or when the linker refuses it. Loads on first access.
     */
    val isLoaded: Boolean by lazy {
        try {
            System.loadLibrary(LIBRARY)
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** `whisper_init_from_file_with_params`; a context handle, or 0 when the model can't be loaded. */
    external fun initContext(modelPath: String, useGpu: Boolean): Long

    /** `whisper_free`. Never while a call on the same context runs. */
    external fun freeContext(ctx: Long)

    /** Every language's probability over `pcm[0 .. 30 s]`, indexed by whisper language id, or null. */
    external fun languageProbabilities(ctx: Long, pcm: FloatArray, threads: Int): FloatArray?

    /**
     * `whisper_full` with greedy sampling over 16 kHz mono [pcm]: the segments, or null when whisper
     * failed or [abort] stopped it. [language] null or "" lets whisper detect it. [durationMs] 0 = to
     * the end. Note that whisper.cpp bounds only its seek loop with [offsetMs]/[durationMs] — the
     * encoder still reads whole 30 s windows — so [WhisperContext] cuts the samples and passes 0, 0.
     */
    external fun transcribe(
        ctx: Long, pcm: FloatArray, language: String?, threads: Int,
        noTimestamps: Boolean, offsetMs: Int, durationMs: Int,
        logprobThold: Float, noSpeechThold: Float, entropyThold: Float, temperatureInc: Float,
    ): Array<NativeSegment>?

    /** Sets the context's abort flag; the run in progress stops at its next ggml step. */
    external fun abort(ctx: Long)

    /** Clears the abort flag before a run ([WhisperContext] does this, so an early cancel is never lost). */
    external fun resetAbort(ctx: Long)

    /** `whisper_lang_str`: the two-letter code of a whisper language id, or null. */
    external fun languageCode(langId: Int): String?

    /** Per-call averages of the last runs in ms: `[sample, encode, decode, batchd, prompt]`, or null. */
    external fun timings(ctx: Long): FloatArray?

    external fun resetTimings(ctx: Long)

    /** The CPU backend library in use (`libggml-cpu-android_armv8.2_2.so`), or null when none runs here. */
    external fun cpuBackend(): String?

    /** whisper.cpp's version, commit and feature line (`… | CPU : NEON = 1 | DOTPROD = 1 | …`). */
    external fun systemInfo(): String
}

/**
 * One segment of a whisper.cpp run, built by the JNI bridge (constructor signature `([BJJFII)V`;
 * kept by R8, `proguard-rules.pro`).
 *
 * @property textUtf8 the segment's text as whisper produced it: raw UTF-8, because byte-level tokens
 *   can split a multi-byte character across two segments ([WhisperRun.text] joins before decoding).
 * @property t0Ms start in the audio, ms (whisper's 10 ms steps).
 * @property t1Ms end in the audio, ms (without timestamps whisper.cpp reports its 30 s window end;
 *   [WhisperContext] caps it at the end of the audio).
 * @property avgTokenLogprob mean log probability of the segment's text tokens (ids below EOT), NaN
 *   when it has none — the input of the iOS-style confidence (media §9.9).
 * @property textTokens how many text tokens [avgTokenLogprob] averages.
 * @property langId whisper's language id of the run (`whisper_full_lang_id`).
 */
class NativeSegment(
    val textUtf8: ByteArray,
    val t0Ms: Long,
    val t1Ms: Long,
    val avgTokenLogprob: Float,
    val textTokens: Int,
    val langId: Int,
)

/** whisper.cpp could not load or run; the message names the step, never content. */
class WhisperException(message: String) : Exception(message)

/** [WhisperContext.abort] stopped the run. A [CancellationException], so a cancelled coroutine unwinds normally. */
class WhisperAbortedException : CancellationException("the whisper run was aborted")

/**
 * Decode parameters of one `whisper_full` run, mapped as media §9.9 lists them for a voice note.
 *
 * @property language two-letter code to force; null lets whisper detect it on each window (iOS
 *   detects first and forces, `WhisperKitEngine.swift:81-106` — the engine's job).
 * @property offsetMs where the audio to process starts; [WhisperContext] cuts the samples there.
 * @property durationMs audio to process from [offsetMs]; 0 = to the end. Whisper hears nothing past it.
 * @property entropyThold whisper.cpp's analogue of WhisperKit's compression-ratio threshold.
 */
data class WhisperDecodeOptions(
    val language: String?,
    val threads: Int = WhisperContext.defaultThreads(),
    val noTimestamps: Boolean = false,
    val offsetMs: Int = 0,
    val durationMs: Int = 0,
    val logprobThold: Float = -0.6f,
    val noSpeechThold: Float = 0.5f,
    val entropyThold: Float = 2.2f,
    val temperatureInc: Float = 0.2f,
) {
    companion object {
        /**
         * A whole voice note: timestamps on (whisper.cpp resumes after an early stop by itself, so
         * `VoiceNoteSeek` is not ported, media §9.2), the voice-note profile's thresholds
         * (`TranscriptionProfile.voiceNote`, `TranscriptionTypes.swift:35-41`), temperature 0 with a
         * 0.2 fallback step.
         */
        fun voiceNote(language: String?, threads: Int = WhisperContext.defaultThreads()) =
            WhisperDecodeOptions(language = language, threads = threads)

        /**
         * The language probe: the first 8 s without timestamps (`TranscriptionRequest.detectLanguage`,
         * `TranscriptionTypes.swift:82-84`; `WhisperDecodePlan`, `:127-141`).
         */
        fun languageProbe(threads: Int = WhisperContext.defaultThreads()) =
            WhisperDecodeOptions(language = null, threads = threads, noTimestamps = true, offsetMs = 0, durationMs = 8_000)
    }
}

/** The most likely spoken language over the opening 30 s ([WhisperContext.detectLanguage]). */
data class WhisperLanguage(val code: String, val probability: Double)

/**
 * Per-call averages of whisper's own timers since the last [WhisperContext.resetTimings], in ms:
 * mel/sampling, one encoder pass, one decoder token, one batched decode, one prompt.
 */
data class WhisperTimings(val sampleMs: Float, val encodeMs: Float, val decodeMs: Float, val batchdMs: Float, val promptMs: Float)

/**
 * The result of one run: segments plus what the engine reports (`WhisperKitEngine.swift:206-227`).
 *
 * @property language the run's language code (forced or detected), null without segments.
 */
class WhisperRun(val segments: List<NativeSegment>, val language: String?) {
    /** All segments joined (as bytes, then decoded) and trimmed: `mergeTranscriptionResults(…).text` (`:211-212`). */
    val text: String by lazy { joinedText(segments) }

    /**
     * `exp(mean of the segments' mean token log probability)`, clamped to 0…1; without scored
     * segments 0.7 when there is text, else 0 (`WhisperKitEngine.swift:218-225`; media §9.9).
     */
    val confidence: Double by lazy { confidence(segments, text) }

    companion object {
        fun joinedText(segments: List<NativeSegment>): String {
            val size = segments.sumOf { it.textUtf8.size }
            val joined = ByteArray(size)
            var at = 0
            for (segment in segments) {
                segment.textUtf8.copyInto(joined, at)
                at += segment.textUtf8.size
            }
            return String(joined, Charsets.UTF_8).trim()
        }

        fun confidence(segments: List<NativeSegment>, text: String): Double {
            val scored = segments.filter { it.textTokens > 0 && !it.avgTokenLogprob.isNaN() }
            if (scored.isEmpty()) return if (text.isEmpty()) 0.0 else 0.7
            val mean = scored.sumOf { it.avgTokenLogprob.toDouble() } / scored.size
            return exp(mean).coerceIn(0.0, 1.0)
        }
    }
}

/**
 * Cancels one whisper run before or while it runs, from any thread (the shape of
 * `android.os.CancellationSignal`, in plain Kotlin so JVM tests can use it). Create one per call;
 * the engine cancels it when its coroutine is cancelled (media §9.9 "Cancellation").
 */
class WhisperCancellation {
    @Volatile
    var isCancelled: Boolean = false
        private set

    private var onCancel: (() -> Unit)? = null

    fun cancel() {
        val action = synchronized(this) {
            if (isCancelled) return
            isCancelled = true
            onCancel
        }
        action?.invoke()
    }

    /** Runs [action] on [cancel], at once when already cancelled; null detaches. */
    internal fun setOnCancel(action: (() -> Unit)?) {
        val runNow = synchronized(this) {
            onCancel = action
            isCancelled && action != null
        }
        if (runNow) action?.invoke()
    }
}

/**
 * A loaded Whisper model (`whisper_context`). Blocking calls; run them on one dedicated thread
 * (media §9.9 "Threading") — calls on one context are serialized here as well, so [close] waits for
 * a run in progress after aborting it. Free it when transcription is idle for a while
 * (`onTrimMemory(TRIM_MEMORY_BACKGROUND)`, Log Out): it holds the weights plus whisper's buffers.
 */
class WhisperContext private constructor(private var handle: Long) : Closeable {
    /** Serializes every native call on [handle] and its release. */
    private val lock = Any()

    /** Guards [running] and [abortRequested]: [abort] must never touch a freed context. */
    private val abortLock = Any()
    private var running = 0L
    private var abortRequested = false

    @Volatile
    private var closed = false

    /**
     * Each language's probability over the opening 30 s of 16 kHz mono [pcm16k], keyed by its
     * two-letter code, or null (empty audio, failure). One encoder pass plus one decoder step (as
     * long as a 30 s window's encode); not abortable.
     */
    fun languageProbabilities(pcm16k: FloatArray, threads: Int = defaultThreads()): Map<String, Double>? =
        synchronized(lock) {
            val probabilities = WhisperNative.languageProbabilities(live(), pcm16k, threads) ?: return null
            buildMap {
                probabilities.forEachIndexed { id, probability ->
                    WhisperNative.languageCode(id)?.let { put(it, probability.toDouble()) }
                }
            }
        }

    /** The most likely language over the opening 30 s of [pcm16k], or null ([languageProbabilities]). */
    fun detectLanguage(pcm16k: FloatArray, threads: Int = defaultThreads()): WhisperLanguage? =
        languageProbabilities(pcm16k, threads)?.maxByOrNull { it.value }?.let { WhisperLanguage(it.key, it.value) }

    /**
     * Transcribes 16 kHz mono [pcm16k] with [options].
     *
     * @param cancellation stops this run, also when cancelled before whisper started.
     * @throws WhisperAbortedException when [cancellation] or [abort] stopped the run.
     * @throws WhisperException when whisper failed.
     */
    fun transcribe(
        pcm16k: FloatArray,
        options: WhisperDecodeOptions,
        cancellation: WhisperCancellation? = null,
    ): WhisperRun = synchronized(lock) {
        val ctx = live()
        WhisperNative.resetAbort(ctx)
        synchronized(abortLock) {
            running = ctx
            abortRequested = false
        }
        cancellation?.setOnCancel(::abort)
        try {
            if (cancellation?.isCancelled == true) throw WhisperAbortedException()
            // whisper.cpp's own offset_ms/duration_ms only bound its seek loop: the encoder still
            // reads a whole 30 s window, so an 8 s probe would decode words past 8 s. The clip is
            // cut from the samples instead (whisper pads it with silence), as WhisperKit's
            // clipTimestamps do (`WhisperKitEngine.swift:127-130`), and the times shifted back.
            val clip = clip(pcm16k, options.offsetMs, options.durationMs)
            val segments = WhisperNative.transcribe(
                ctx, clip, options.language, options.threads.coerceAtLeast(1),
                options.noTimestamps, 0, 0,
                options.logprobThold, options.noSpeechThold, options.entropyThold, options.temperatureInc,
            )
            if (synchronized(abortLock) { abortRequested }) throw WhisperAbortedException()
            if (segments == null) throw WhisperException("whisper_full failed")
            val language = segments.firstOrNull()?.langId?.let(WhisperNative::languageCode)
            WhisperRun(placed(segments.toList(), options.offsetMs.coerceAtLeast(0).toLong(), clip.size / SAMPLES_PER_MS), language)
        } finally {
            cancellation?.setOnCancel(null)
            synchronized(abortLock) { running = 0 }
        }
    }

    /** Stops the run in progress, if any (whisper checks before each ggml step). Safe from any thread. */
    fun abort() {
        synchronized(abortLock) {
            if (running == 0L) return
            abortRequested = true
            WhisperNative.abort(running)
        }
    }

    fun timings(): WhisperTimings? = synchronized(lock) {
        val values = WhisperNative.timings(live()) ?: return null
        WhisperTimings(values[0], values[1], values[2], values[3], values[4])
    }

    fun resetTimings() = synchronized(lock) { WhisperNative.resetTimings(live()) }

    /** Aborts a run in progress, waits for it, frees the model. Idempotent. */
    override fun close() {
        abort()
        synchronized(lock) {
            if (closed) return
            closed = true
            val ctx = handle
            handle = 0
            if (ctx != 0L) WhisperNative.freeContext(ctx)
        }
    }

    private fun live(): Long {
        check(!closed && handle != 0L) { "the whisper context is closed" }
        return handle
    }

    companion object {
        /** Samples of `[offsetMs, offsetMs + durationMs)` (`durationMs` 0 = to the end), clamped to [pcm16k]. */
        internal fun clip(pcm16k: FloatArray, offsetMs: Int, durationMs: Int): FloatArray {
            if (offsetMs <= 0 && durationMs <= 0) return pcm16k
            val start = (offsetMs.coerceAtLeast(0).toLong() * SAMPLES_PER_MS).coerceAtMost(pcm16k.size.toLong()).toInt()
            val end = if (durationMs <= 0) pcm16k.size else
                (start + durationMs.toLong() * SAMPLES_PER_MS).coerceAtMost(pcm16k.size.toLong()).toInt()
            return pcm16k.copyOfRange(start, end)
        }

        /**
         * [segments] of a clip [clipMs] long, put back onto the timeline of the whole audio: times
         * capped at the clip's end (without timestamps whisper.cpp ends a segment at its 30 s window,
         * past the audio) and moved by [offsetMs].
         */
        internal fun placed(segments: List<NativeSegment>, offsetMs: Long, clipMs: Long): List<NativeSegment> =
            if (offsetMs <= 0 && segments.all { it.t1Ms <= clipMs }) segments else segments.map {
                NativeSegment(
                    it.textUtf8, it.t0Ms.coerceAtMost(clipMs) + offsetMs, it.t1Ms.coerceAtMost(clipMs) + offsetMs,
                    it.avgTokenLogprob, it.textTokens, it.langId,
                )
            }

        private const val SAMPLES_PER_MS = 16L

        /** `n_threads = min(4, availableProcessors)` (media §9.9). */
        fun defaultThreads(): Int = min(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)

        /** True when the native library loaded and a ggml CPU backend runs on this device. */
        fun isSupported(): Boolean = WhisperNative.isLoaded && WhisperNative.cpuBackend() != null

        /**
         * Loads [model] (a verified file from [WhisperModelStore]). Blocking.
         *
         * @throws WhisperException when the library is missing on this ABI, no CPU backend runs here,
         *   or whisper rejects the file.
         */
        fun load(model: File): WhisperContext {
            if (!WhisperNative.isLoaded) throw WhisperException("the whisper library is not available on this device")
            if (WhisperNative.cpuBackend() == null) throw WhisperException("no ggml CPU backend runs on this device")
            val handle = WhisperNative.initContext(model.absolutePath, false)
            if (handle == 0L) throw WhisperException("whisper could not load the model")
            return WhisperContext(handle)
        }
    }
}
