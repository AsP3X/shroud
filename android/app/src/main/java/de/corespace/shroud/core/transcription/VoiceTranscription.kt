package de.corespace.shroud.core.transcription

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.UUID

/**
 * Where a model download or a transcription stands, for the composer's and the bubble's progress
 * (iOS `VoiceTranscriptionService.installState`; media-voice-links §9; plan §1.7.13).
 *
 * @property messageId the voice note being transcribed ([Phase.Transcribing]), else null.
 */
data class TranscriptionInstallState(
    val phase: Phase,
    val fractionCompleted: Double,
    val isDeterminate: Boolean,
    val languageName: String?,
    val messageId: UUID?,
) {
    enum class Phase { Idle, Downloading, Transcribing }

    companion object {
        val Idle = TranscriptionInstallState(Phase.Idle, 0.0, isDeterminate = false, languageName = null, messageId = null)
    }
}

/** A transcription that could not run; [message] is user-facing. */
class TranscribeException(message: String) : Exception(message)

/**
 * On-device transcription of voice notes (P7 decided: whisper.cpp; plan §1.7.13). Nothing leaves the
 * phone: the model weights are public files (`WhisperModelStore`, kept across Log Out) and the audio
 * is decoded and run in-process (`WhisperContext`, W2-WHISPER).
 *
 * **Seam (W2-INT), owner W3-TRANSCRIPTION**, which implements it on `WhisperModelStore` (download,
 * verify, [prepareModel]) and `WhisperContext` (load, run, language detection) with
 * `AudioPcmDecoder` (W2-VOICE) for the 16 kHz mono input; `TranscriptionModule.voice` exposes it.
 * Until then [Unavailable].
 */
interface VoiceTranscription {
    /** The device can transcribe (the native library loads; a model can be installed). */
    val isAvailable: StateFlow<Boolean>
    val install: StateFlow<TranscriptionInstallState>

    /** Downloads and verifies the chosen model if needed; false when it could not. */
    suspend fun prepareModel(): Boolean
    suspend fun modelIsInstalled(): Boolean

    /**
     * Transcribes [audio] ([mime], e.g. `audio/mp4`). [hints] are names and words of the chat that
     * bias the decoder; [conversationId] keys the per-chat language statistics; [tracking] is the
     * voice note shown in [install] meanwhile. Throws [TranscribeException].
     */
    suspend fun transcribe(audio: ByteArray, mime: String, hints: List<String> = emptyList(), conversationId: UUID? = null, tracking: UUID? = null): String
    fun availableLocales(): List<Locale>

    /** Settings › Transcription's language; null = detect. */
    var languageOverride: Locale?

    /**
     * Settings › Transcription › Transcribe automatically (iOS `TranscriptionPreferences`). Off by
     * default: a note is then transcribed only when its transcript button is tapped, and recording
     * does not start the model download.
     */
    var transcribesAutomatically: Boolean
        get() = false
        set(_) = Unit

    /** A note was re-keyed to its server id while being transcribed (`ThreadState.rekey`): progress follows it. */
    fun handOff(from: UUID, to: UUID)

    /** No transcription on this build yet: unavailable, never installs, every transcribe fails. */
    object Unavailable : VoiceTranscription {
        override val isAvailable: StateFlow<Boolean> = MutableStateFlow(false)
        override val install: StateFlow<TranscriptionInstallState> = MutableStateFlow(TranscriptionInstallState.Idle)
        override suspend fun prepareModel(): Boolean = false
        override suspend fun modelIsInstalled(): Boolean = false
        override suspend fun transcribe(audio: ByteArray, mime: String, hints: List<String>, conversationId: UUID?, tracking: UUID?): String =
            throw TranscribeException("Transcription isn’t available on this phone.")
        override fun availableLocales(): List<Locale> = emptyList()
        override var languageOverride: Locale?
            get() = null
            set(_) = Unit
        override fun handOff(from: UUID, to: UUID) = Unit
    }
}
