package de.corespace.shroud.di

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.storage.StorageManager
import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.storage.PrefsFiles
import de.corespace.shroud.core.transcription.TranscriptionBenchmark
import de.corespace.shroud.core.transcription.TranscriptionLanguage
import de.corespace.shroud.core.transcription.TranscriptionLanguageMemory
import de.corespace.shroud.core.transcription.TranscriptionSession
import de.corespace.shroud.core.transcription.VoiceTranscriber
import de.corespace.shroud.core.transcription.VoiceTranscription
import de.corespace.shroud.core.transcription.WhisperCppEngine
import de.corespace.shroud.core.transcription.WhisperModelStore
import de.corespace.shroud.core.transcription.WhisperNative
import de.corespace.shroud.core.voice.AudioPcmDecoder
import java.io.File
import java.io.IOException

/**
 * On-device transcription (00-plan §1.7.13, P7). Owner: W2-WHISPER (native engine, model
 * store, benchmark), then W3-TRANSCRIPTION — the [voice] `VoiceTranscription` behind the seam
 * W2-INT published, built on [whisperModels] and `WhisperContext`.
 *
 * Created empty by W0-A; only the owner fills it (00-plan §2.0 rule 3, §2.6). Nobody else
 * constructs this package's classes: other packages reach them through this module.
 */
class TranscriptionModule(container: AppContainer) : AppModule(container) {
    /**
     * Whisper weights in `noBackupFilesDir/whisper` (00-plan §1.5): public files, kept across Log
     * Out. Downloads through a client derived from the base OkHttp client (shared pool, no Shroud
     * token), from the pinned Hugging Face revision.
     */
    val whisperModels: WhisperModelStore by lazy {
        WhisperModelStore(
            directory = File(container.appContext.noBackupFilesDir, WhisperModelStore.DIRECTORY),
            http = container.net.http,
            usableSpace = ::allocatableBytes,
        )
    }

    /**
     * Voice-note transcription for the composer and the bubbles (plan §1.7.13 `VoiceTranscription`).
     * Built on [whisperModels] and [WhisperCppEngine]. [VoiceTranscription.Unavailable] stays the
     * fallback object; this is never that object. The native library missing makes [VoiceTranscription.isAvailable]
     * false and [VoiceTranscriber.transcribe] fail, without downloading a model.
     */
    val voice: VoiceTranscription by lazy { transcriber }

    private val voicePrefs by lazy {
        container.appContext.getSharedPreferences(PrefsFiles.VOICE, Context.MODE_PRIVATE)
    }

    /** Sealed per-chat language stats. A locked history key records nothing and does not throw. */
    private val languageMemory: TranscriptionLanguageMemory by lazy {
        TranscriptionLanguageMemory(
            file = File(
                container.appContext.noBackupFilesDir,
                "${TranscriptionLanguageMemory.DIRECTORY}/${TranscriptionLanguageMemory.FILE_NAME}",
            ),
            state = container.keys.sealedLocalState,
            seal = container.storageSeal,
        )
    }

    private val language: TranscriptionLanguage by lazy {
        TranscriptionLanguage(voicePrefs, container.storageSeal, languageMemory)
    }

    /**
     * The loaded whisper context. Released when chats lock (the engine's own listener) and when
     * the process is backgrounded, so the weights are not held for a phone that is only waiting.
     */
    private val whisperEngine: WhisperCppEngine by lazy {
        val engine = WhisperCppEngine(models = whisperModels, state = container.keys.sealedLocalState)
        container.appContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onLowMemory() = engine.release()

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) engine.release()
            }
        })
        engine
    }

    private val session: TranscriptionSession by lazy {
        TranscriptionSession(whisperEngine, voicePrefs, container.storageSeal)
    }

    private val transcriber: VoiceTranscriber by lazy {
        VoiceTranscriber(
            session = session,
            language = language,
            memory = languageMemory,
            nativeLoaded = WhisperNative.isLoaded,
            decode = { bytes, mime -> AudioPcmDecoder.decodeMono16k(bytes, mime) },
        )
    }

    /** The P7 benchmark (speed and memory of each model on this device), for the instrumented test and diagnostics. */
    val benchmark: TranscriptionBenchmark by lazy { TranscriptionBenchmark(container.appContext, whisperModels) }

    /** Free space for a model download, counting cache files the system would clear for it. */
    @SuppressLint("UsableSpace") // the fallback only, when the volume has no StorageManager UUID
    private fun allocatableBytes(directory: File): Long = try {
        val storage = container.appContext.getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(directory))
    } catch (_: IOException) {
        directory.usableSpace
    }
}
