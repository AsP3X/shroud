package de.corespace.shroud.core.transcription

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.UUID

/**
 * 16 kHz mono PCM from a voice-note container. Throws when [bytes] cannot be decoded.
 * Production passes [de.corespace.shroud.core.voice.AudioPcmDecoder.decodeMono16k].
 */
fun interface Pcm16kSource {
    fun decodeMono16k(bytes: ByteArray, mime: String): FloatArray
}

/**
 * On-device transcription of voice notes (iOS `VoiceTranscriber`, `VoiceTranscriber.swift:81-316`;
 * media-voice-links §9.5). [VoiceTranscription] for the composer and the bubbles.
 *
 * Language: a Settings pin wins; otherwise Whisper detects it once, weighed with the device's
 * languages and this chat's history ([SpokenLanguagePick]), and the note is decoded in it. [transcribe]'s [hints][VoiceTranscription.transcribe]
 * are chat words carried on the request; whisper.cpp does not prompt with them (§9.4).
 * A locked history key does not throw: the note is still decoded, and the memory writes nothing.
 * Audio, text and language stats stay on the device. The only network is the model download.
 */
class VoiceTranscriber(
    private val session: TranscriptionSession,
    private val language: TranscriptionLanguage,
    private val memory: TranscriptionLanguageMemory,
    private val nativeLoaded: Boolean,
    private val decode: Pcm16kSource,
    private val installs: TranscriptionModelInstall = TranscriptionModelInstall(),
) : VoiceTranscription {
    override val isAvailable: StateFlow<Boolean> = MutableStateFlow(nativeLoaded)
    override val install: StateFlow<TranscriptionInstallState> = installs.state

    /**
     * One download shared by every caller. False when the library or the download failed.
     * Only the call that opened the install session paints it: a joiner waits on the same
     * [TranscriptionSession.prepare] and must not set [install] back to transcribing after the
     * owner has already finished.
     */
    override suspend fun prepareModel(): Boolean {
        if (!nativeLoaded) return false
        val opened = installs.openSessionIfIdle()
        return try {
            session.prepare { fraction ->
                if (opened) installs.downloading(MODEL_NAME, fraction, fraction > 0.0)
            }
            if (opened) installs.transcribing()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            if (opened) installs.finish()
        }
    }

    override suspend fun modelIsInstalled(): Boolean = session.isPrepared

    /**
     * Decodes [audio], biases the language with the pin or the sealed per-chat stats, and returns
     * the cleaned transcript ("" when there was no speech). Failures throw [TranscribeException]
     * whose message is a sentence for the user — never a path, and never the audio.
     */
    override suspend fun transcribe(
        audio: ByteArray,
        mime: String,
        hints: List<String>,
        conversationId: UUID?,
        tracking: UUID?,
    ): String {
        if (!nativeLoaded) throw TranscribeException(TranscriptionEngineError.UNAVAILABLE)
        installs.begin(tracking)
        try {
            try {
                session.prepare { fraction -> installs.downloading(MODEL_NAME, fraction, fraction > 0.0) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: TranscriptionEngineError) {
                throw TranscribeException(sentence(e))
            } catch (_: Exception) {
                throw TranscribeException(TranscriptionEngineError.MODEL_UNAVAILABLE)
            }
            installs.transcribing()
            val pcm = try {
                decode.decodeMono16k(audio, mime)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw TranscribeException(TranscriptionEngineError.FAILED)
            }
            val duration = pcm.size.toDouble() / SAMPLE_RATE
            val output = try {
                decodeVoiceNote(pcm, hints, conversationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: TranscriptionEngineError) {
                throw TranscribeException(sentence(e))
            } catch (_: Exception) {
                throw TranscribeException(TranscriptionEngineError.FAILED)
            }
            val text = VoiceTranscript.cleaned(output.text)
            val code = output.language
            val heard = output.languageProbability
            if (code != null && heard != null && text.isNotEmpty()) {
                val weight = VoiceTranscript.learningWeight(duration, heard)
                if (weight > 0.0) memory.record(code, conversationId, weight)
            }
            return text
        } finally {
            installs.finish()
        }
    }

    override fun availableLocales(): List<Locale> = language.whisperLocales()

    override var languageOverride: Locale?
        get() = language.override
        set(value) {
            language.override = value
        }

    override fun handOff(from: UUID, to: UUID) = installs.handOff(from, to)

    /**
     * The pin, else one decode in the language Whisper detects, weighed with the device's
     * languages and this chat's history (`decodeVoiceNote`, `VoiceTranscriber.swift`). A note is
     * never decoded again in a language Whisper did not hear: forced, it translates.
     */
    private suspend fun decodeVoiceNote(pcm: FloatArray, contextual: List<String>, conversationId: UUID?): TranscriptionOutput {
        val request = TranscriptionRequest.voiceNote(
            language = language.pinnedLanguage(),
            hints = contextual,
            candidateLanguages = TranscriptionLanguage.detectionCandidates(language.deviceLanguages()),
            languageHistory = memory.history(conversationId),
        )
        return session.transcribe(pcm, request)
    }

    private fun sentence(error: TranscriptionEngineError): String =
        error.message?.takeIf { it.isNotBlank() } ?: TranscriptionEngineError.FAILED

    private companion object {
        const val MODEL_NAME = "Whisper"
        const val SAMPLE_RATE = 16_000
    }
}
