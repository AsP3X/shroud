package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.transcription.TranscriptionBenchmark
import de.corespace.shroud.core.transcription.WhisperModelStore
import java.io.File

/**
 * On-device transcription (00-plan §1.7.13, P7). Owner: W2-WHISPER (native engine, model
 * store, benchmark), then W3-TRANSCRIPTION — the `VoiceTranscription` behind the seam W2-INT
 * publishes, built on [whisperModels] and `WhisperContext`.
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
        WhisperModelStore(File(container.appContext.noBackupFilesDir, WhisperModelStore.DIRECTORY), container.net.http)
    }

    /** The P7 benchmark (speed and memory of each model on this device), for the instrumented test and diagnostics. */
    val benchmark: TranscriptionBenchmark by lazy { TranscriptionBenchmark(container.appContext, whisperModels) }
}
