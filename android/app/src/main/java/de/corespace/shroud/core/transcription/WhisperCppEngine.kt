package de.corespace.shroud.core.transcription

import de.corespace.shroud.core.keys.SealedLocalState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

/**
 * A loaded whisper context. Tests pass a fake; [WhisperContext] is the device one.
 * One context is used by one thread at a time — [abort][WhisperContext.abort] may run elsewhere.
 */
internal interface WhisperRunner : Closeable {
    fun languageProbabilities(pcm16k: FloatArray, threads: Int): Map<String, Double>?
    fun transcribe(pcm16k: FloatArray, options: WhisperDecodeOptions, cancellation: WhisperCancellation?): WhisperRun
}

/**
 * whisper.cpp behind [TranscriptionEngine] (iOS `WhisperKitEngine.swift`; media §9.4, §9.9).
 * Thin on purpose: download and verify stay in [WhisperModelStore], the JNI stays in
 * [WhisperContext]. This class picks the model, detects the language once (weighed with the
 * request's candidate languages and chat history, [SpokenLanguagePick]) and forces it for the
 * decode (later windows must not detect again), and frees the weights when memory is tight or
 * chats lock.
 *
 * [TranscriptionRequest.hints] are not an initial prompt. whisper.cpp leaves that null
 * (`whisper_jni.cpp`); language bias is [SpokenLanguagePick].
 * Nothing here contacts the network except [WhisperModelStore.ensure].
 */
class WhisperCppEngine internal constructor(
    private val models: WhisperModelStore,
    private val specFor: (TranscriptionModelId) -> WhisperModelSpec = { id ->
        WhisperModelFile.forId(id.raw) ?: WhisperModelFile.DEFAULT
    },
    private val load: (File) -> WhisperRunner = { file -> LoadedContext(WhisperContext.load(file)) },
    private val dispatcher: CoroutineDispatcher = whisperDispatcher(),
    state: SealedLocalState? = null,
) : TranscriptionEngine {
    override val id: String = ENGINE_ID

    private val gate = Any()
    private var runner: WhisperRunner? = null
    private var loaded: TranscriptionModelId? = null

    init {
        state?.addListener(object : SealedLocalState.Listener {
            override fun onUnlock(historyKey: ByteArray) = Unit
            override fun onLock() = release()
        })
    }

    /**
     * Downloads [model] when it is not already verified, then loads it. A library or CPU-backend
     * miss is [TranscriptionEngineError.Unavailable]; anything else about the file is
     * [TranscriptionEngineError.ModelUnavailable].
     */
    override suspend fun prepare(model: TranscriptionModelId, progress: ((Double) -> Unit)?) {
        val file = try {
            models.ensure(specFor(model), progress)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw TranscriptionEngineError.ModelUnavailable()
        }
        withContext(dispatcher) {
            val created = try {
                load(file)
            } catch (e: WhisperException) {
                throw mapLoad(e)
            }
            val previous = synchronized(gate) {
                loaded = model
                runner.also { runner = created }
            }
            if (previous != null && previous !== created) previous.close()
        }
    }

    override suspend fun transcribe(pcm16k: FloatArray, request: TranscriptionRequest): TranscriptionOutput =
        withContext(dispatcher) {
            val cancellation = WhisperCancellation()
            coroutineContext.job.invokeOnCompletion { cause ->
                if (cause is CancellationException) cancellation.cancel()
            }
            try {
                coroutineContext.ensureActive()
                val live = runner()
                val requested = WhisperReportedLanguage.code(request.language)
                var heard: Double? = null
                val detected = if (requested == null) {
                    live.languageProbabilities(pcm16k, WhisperContext.defaultThreads())?.let { probabilities ->
                        SpokenLanguagePick.pick(probabilities, request.candidateLanguages, request.languageHistory)
                            ?.also { heard = probabilities[it] }
                    }?.let(WhisperReportedLanguage::code)
                } else {
                    null
                }
                val forced = requested ?: detected
                val run = live.transcribe(pcm16k, decodeOptions(request, forced), cancellation)
                val language = WhisperReportedLanguage.choose(forced, openingToken = null, reported = run.language)
                TranscriptionOutput(run.text, language, run.confidence, heard.takeIf { detected != null })
            } catch (aborted: WhisperAbortedException) {
                // A subclass of CancellationException, so it has to be caught first. A cancelled
                // caller already asked for the abort; a trim while the call is still active did not.
                if (!coroutineContext.isActive) throw aborted
                throw TranscriptionEngineError.Failed(TranscriptionEngineError.FAILED)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: TranscriptionEngineError) {
                throw e
            } catch (_: Exception) {
                throw TranscriptionEngineError.Failed(TranscriptionEngineError.FAILED)
            }
        }

    /**
     * Drops the loaded weights. The verified file stays, so the next [transcribe] loads it again
     * without a download. Safe while a run is in progress: [WhisperContext.close] aborts it first.
     * Called on trim (`TRIM_MEMORY_BACKGROUND`) and when chats lock.
     */
    fun release() {
        val old = synchronized(gate) { runner.also { runner = null } }
        old?.close()
    }

    private fun runner(): WhisperRunner {
        synchronized(gate) {
            runner?.let { return it }
            val model = loaded ?: throw TranscriptionEngineError.ModelUnavailable()
            val spec = specFor(model)
            if (!models.isInstalled(spec)) throw TranscriptionEngineError.ModelUnavailable()
            val created = try {
                load(models.file(spec))
            } catch (e: WhisperException) {
                throw mapLoad(e)
            }
            runner = created
            return created
        }
    }

    private class LoadedContext(private val context: WhisperContext) : WhisperRunner {
        override fun languageProbabilities(pcm16k: FloatArray, threads: Int): Map<String, Double>? =
            context.languageProbabilities(pcm16k, threads)

        override fun transcribe(pcm16k: FloatArray, options: WhisperDecodeOptions, cancellation: WhisperCancellation?): WhisperRun =
            context.transcribe(pcm16k, options, cancellation)

        override fun close() = context.close()
    }

    companion object {
        const val ENGINE_ID = "whispercpp"

        fun whisperDispatcher(): CoroutineDispatcher =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "shroud-whisper").apply { isDaemon = true }
            }.asCoroutineDispatcher()

        /** Timestamps and the clip, mapped onto [WhisperDecodeOptions] (§9.9). The tail clip is the plan's; whisper.cpp has no separate knob. */
        internal fun decodeOptions(request: TranscriptionRequest, language: String?): WhisperDecodeOptions {
            val plan = WhisperDecodePlan.make(request)
            val clip = request.clipSeconds
            return WhisperDecodeOptions(
                language = language,
                noTimestamps = !plan.keepTimestamps,
                offsetMs = clip?.let { (it.start * 1000.0).toInt().coerceAtLeast(0) } ?: 0,
                durationMs = clip?.let { ((it.endInclusive - it.start) * 1000.0).toInt().coerceAtLeast(0) } ?: 0,
                logprobThold = request.profile.logProbThreshold,
                noSpeechThold = request.profile.noSpeechThreshold,
                entropyThold = request.profile.compressionRatioThreshold,
                temperatureInc = 0.2f,
            )
        }

        private fun mapLoad(e: WhisperException): TranscriptionEngineError {
            val message = e.message.orEmpty()
            return if ("not available" in message || "no ggml" in message) {
                TranscriptionEngineError.Unavailable()
            } else {
                TranscriptionEngineError.ModelUnavailable()
            }
        }
    }
}
