package de.corespace.shroud.core.transcription

import android.content.SharedPreferences
import androidx.core.content.edit
import de.corespace.shroud.core.storage.StorageSeal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/**
 * App-wide owner of the active engine and the loaded model (iOS `TranscriptionSession`,
 * `TranscriptionSession.swift:11-123`). [prepare] is single-flight per model: a second caller
 * waits on the first, and a failure leaves the flight clear so the next call retries.
 * Transcriptions run one at a time, in the order they arrived (the queue mutex is fair).
 *
 * Prefs `transcription.model` and `transcription.<engine id>.ready` live in `shroud.voice` and
 * are kept across Log Out. A test engine's id is not `whispercpp`, so it cannot mark the real
 * model ready. [StorageSeal] drops that write during a wipe. Model ids are not secrets.
 */
class TranscriptionSession(
    engine: TranscriptionEngine,
    private val prefs: SharedPreferences,
    private val seal: StorageSeal,
) {
    private val gate = Mutex()
    private val queue = Mutex()

    @Volatile
    private var engine: TranscriptionEngine = engine

    @Volatile
    private var loaded: TranscriptionModelId? = null

    private var generation = 0
    private var preparing: InFlight? = null

    private class InFlight(
        val model: TranscriptionModelId,
        val generation: Int,
        val deferred: CompletableDeferred<Unit>,
    )

    /** `transcription.model`, or [TranscriptionModelId.DEFAULT] (`selectedModel`, `:34-41`). */
    val selectedModel: TranscriptionModelId
        get() = TranscriptionModelId.fromRaw(prefs.getString(MODEL_KEY, null)) ?: TranscriptionModelId.DEFAULT

    fun selectModel(model: TranscriptionModelId) {
        if (seal.isSealed) return
        prefs.edit(commit = true) { putString(MODEL_KEY, model.raw) }
    }

    /**
     * True when this process has loaded the selected model, or a previous launch finished
     * downloading it (`isPrepared`, `:51-53`).
     */
    val isPrepared: Boolean
        get() = loaded != null || prefs.getString(readyKey(engine.id), null) == selectedModel.raw

    /** Swaps the backend. The next [prepare] loads its model (`use`, `:27-32`). */
    suspend fun use(engine: TranscriptionEngine) {
        val flight = gate.withLock {
            generation += 1
            loaded = null
            this.engine = engine
            preparing.also { preparing = null }
        }
        flight?.deferred?.cancel()
    }

    /**
     * Loads [model] (or [selectedModel]). Callers for the model already in flight wait for it.
     * Failure clears the flight. Success records the ready marker unless the engine was swapped.
     */
    suspend fun prepare(model: TranscriptionModelId? = null, progress: ((Double) -> Unit)? = null) {
        val target = model ?: selectedModel
        val flight: InFlight
        val mine: TranscriptionEngine?
        val generationAtStart: Int
        gate.withLock {
            if (loaded == target) return
            val existing = preparing
            if (existing != null && existing.model == target) {
                flight = existing
                mine = null
                generationAtStart = existing.generation
            } else {
                generation += 1
                val created = InFlight(target, generation, CompletableDeferred())
                preparing = created
                flight = created
                mine = engine
                generationAtStart = created.generation
            }
        }
        if (mine == null) {
            flight.deferred.await()
            return
        }
        try {
            mine.prepare(target, progress)
            gate.withLock {
                if (preparing === flight) preparing = null
                if (generation == generationAtStart && engine.id == mine.id) {
                    loaded = target
                    if (!seal.isSealed) prefs.edit(commit = true) { putString(readyKey(mine.id), target.raw) }
                }
            }
            flight.deferred.complete(Unit)
        } catch (t: Throwable) {
            gate.withLock { if (preparing === flight) preparing = null }
            flight.deferred.completeExceptionally(t)
            throw t
        }
    }

    /** [prepare], then one decode at a time. Cancelling the caller cancels only its wait. */
    suspend fun transcribe(pcm16k: FloatArray, request: TranscriptionRequest): TranscriptionOutput {
        prepare()
        val current = gate.withLock { engine }
        return queue.withLock {
            coroutineContext.ensureActive()
            current.transcribe(pcm16k, request)
        }
    }

    companion object {
        const val MODEL_KEY = "transcription.model"

        fun readyKey(engineId: String) = "transcription.$engineId.ready"
    }
}
