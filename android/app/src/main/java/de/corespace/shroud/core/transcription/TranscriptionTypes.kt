package de.corespace.shroud.core.transcription

/**
 * Whisper model ids, decode profiles and the language-token rules
 * (iOS `TranscriptionTypes.swift`; media-voice-links §9.2). Android offers `base` and `small`
 * only, default **base** (D9) — not iOS's `medium` / default `small`.
 *
 * `VoiceNoteSeek` is not ported: whisper.cpp resumes after an early stop when timestamps are on
 * (§9.2). Contextual [TranscriptionRequest.hints] are carried for the caller; whisper.cpp is not
 * given an initial prompt (iOS ignores them, `WhisperKitEngine.swift:123-152`).
 */
enum class TranscriptionModelId(val raw: String, val displayName: String) {
    Base("base", "Base"),
    Small("small", "Small"),
    ;

    companion object {
        val DEFAULT = Base

        fun fromRaw(raw: String?): TranscriptionModelId? = entries.firstOrNull { it.raw == raw }
    }
}

/** Decode knobs that differ between a finished voice note and a live-call chunk (`TranscriptionTypes.swift:27-51`). */
data class TranscriptionProfile(
    val compressionRatioThreshold: Float,
    val logProbThreshold: Float,
    val firstTokenLogProbThreshold: Float,
    val noSpeechThreshold: Float,
    val windowClipTime: Float,
) {
    companion object {
        val voiceNote = TranscriptionProfile(2.2f, -0.6f, -1.2f, 0.5f, 1.0f)
        val liveCall = TranscriptionProfile(2.2f, -0.6f, -1.2f, 0.75f, 1.0f)
    }
}

/** Inclusive clip of the note, in seconds. Null means the whole input (`TranscriptionTypes.swift:61-62`). */
data class ClipSeconds(val start: Double, val endInclusive: Double)

/**
 * One decode (`TranscriptionRequest`, `TranscriptionTypes.swift`). [language] null means detect.
 * [hints] are chat words; the whisper.cpp engine does not prompt with them. [candidateLanguages]
 * are the device's languages and [languageHistory] the chat's; detection weighs Whisper's
 * probabilities with both ([SpokenLanguagePick]).
 */
data class TranscriptionRequest(
    val language: String?,
    val hints: List<String>,
    val profile: TranscriptionProfile,
    val clipSeconds: ClipSeconds?,
    val candidateLanguages: List<String> = emptyList(),
    val languageHistory: Map<String, Double> = emptyMap(),
) {
    companion object {
        fun voiceNote(
            language: String? = null,
            hints: List<String> = emptyList(),
            candidateLanguages: List<String> = emptyList(),
            languageHistory: Map<String, Double> = emptyMap(),
        ) = TranscriptionRequest(language, hints, TranscriptionProfile.voiceNote, null, candidateLanguages, languageHistory)

        fun liveCall(language: String? = null) =
            TranscriptionRequest(language, emptyList(), TranscriptionProfile.liveCall, null)

        /** The opening [clipSeconds] seconds, timestamps off (`TranscriptionTypes.swift:82-84`). */
        fun detectLanguage(clipSeconds: Double = 8.0) =
            TranscriptionRequest(null, emptyList(), TranscriptionProfile.voiceNote, ClipSeconds(0.0, clipSeconds))
    }
}

/**
 * How a request is handed to Whisper (`WhisperDecodePlan`, `TranscriptionTypes.swift:121-142`).
 * A finished voice note keeps timestamps and does not clip the tail, so a pause does not end it.
 */
data class WhisperDecodePlan(
    val keepTimestamps: Boolean,
    val tailClipSeconds: Float,
    val useVoiceActivityChunking: Boolean,
) {
    companion object {
        fun make(request: TranscriptionRequest): WhisperDecodePlan {
            val wholeVoiceNote = request.profile == TranscriptionProfile.voiceNote && request.clipSeconds == null
            return if (wholeVoiceNote) {
                WhisperDecodePlan(keepTimestamps = true, tailClipSeconds = 0f, useVoiceActivityChunking = false)
            } else {
                WhisperDecodePlan(
                    keepTimestamps = false,
                    tailClipSeconds = request.profile.windowClipTime,
                    useVoiceActivityChunking = request.clipSeconds == null,
                )
            }
        }
    }
}

/** The language token from the opening of a note (`WhisperLanguageToken`, `TranscriptionTypes.swift:208-227`). */
object WhisperLanguageToken {
    fun code(tokenText: String): String? {
        val trimmed = tokenText.trim()
        val inner = if (trimmed.startsWith("<|") && trimmed.endsWith("|>") && trimmed.length > 4) {
            trimmed.substring(2, trimmed.length - 2)
        } else {
            trimmed
        }
        if (inner.length != 2 || inner.any { !it.isLetter() }) return null
        return inner.lowercase()
    }

    fun firstCode(tokenTexts: List<String>): String? {
        for (text in tokenTexts) code(text)?.let { return it }
        return null
    }
}

/**
 * The spoken language from Whisper's language probabilities (`SpokenLanguagePick`,
 * `TranscriptionTypes.swift`; web `pickSpokenLanguage`).
 *
 * The audio decides; what is known about this person only weighs it. The candidates are the
 * device's languages and English: a language outside them needs [OUTSIDE_CANDIDATE_ODDS] times
 * the probability. A chat's history makes the language it is spoken in up to
 * 1 + [HISTORY_ODDS] times likelier, at full strength once [HISTORY_SATURATION] notes' worth has
 * been heard. That settles a short note Whisper is unsure about, and a clear note in another
 * language still wins. With no candidates and no history the most likely language wins.
 */
object SpokenLanguagePick {
    const val OUTSIDE_CANDIDATE_ODDS = 5.0
    const val HISTORY_ODDS = 2.0
    const val HISTORY_SATURATION = 3.0

    /** Whisper's code where it differs from the ISO code the hints use. */
    private val WHISPER_CODE = mapOf("nb" to "no")

    fun pick(
        probabilities: Map<String, Double>,
        candidates: List<String>,
        history: Map<String, Double> = emptyMap(),
    ): String? {
        val allowed = candidates.map { WHISPER_CODE[it] ?: it }.toSet()
        val heard = history.entries.groupBy({ WHISPER_CODE[it.key] ?: it.key }, { it.value }).mapValues { it.value.sum() }
        val total = heard.values.filter { it > 0.0 }.sum()
        val strength = HISTORY_ODDS * minOf(1.0, total / HISTORY_SATURATION)
        fun score(code: String, probability: Double): Double {
            val known = if (allowed.isEmpty() || code in allowed) 1.0 else 1.0 / OUTSIDE_CANDIDATE_ODDS
            val share = if (total > 0.0) (heard[code] ?: 0.0).coerceAtLeast(0.0) / total else 0.0
            return probability * known * (1.0 + strength * share)
        }
        // Ties go to the smaller code, so the pick never depends on map order.
        return probabilities.entries
            .filter { it.value.isFinite() }
            .sortedBy { it.key }
            .maxByOrNull { score(it.key, it.value) }
            ?.key
    }
}

/**
 * Which language a finished note reports (`WhisperReportedLanguage`, `TranscriptionTypes.swift:234-248`).
 * A language the caller asked for wins, then the opening token, then the engine's label.
 */
object WhisperReportedLanguage {
    fun code(raw: String?): String? {
        if (raw == null) return null
        val normalized = TranscriptionLanguage.normalize(raw)
        if (normalized.length != 2 || normalized.any { !it.isLetter() }) return null
        return normalized
    }

    fun choose(forced: String?, openingToken: String?, reported: String?): String? =
        code(forced) ?: code(openingToken) ?: code(reported)
}

/**
 * One transcription (`TranscriptionOutput`, `TranscriptionTypes.swift`). [languageProbability] is
 * what the audio alone gave [language] when the engine detected it, null when it was asked for one.
 * [toString] omits [text]: a log must not record a transcript.
 */
class TranscriptionOutput(
    val text: String,
    val language: String?,
    val confidence: Double,
    val languageProbability: Double? = null,
) {
    override fun toString(): String =
        "TranscriptionOutput(language=$language, confidence=$confidence, chars=${text.length})"
}

/** The engine could not run. [message] is the sentence shown to the user (`TranscriptionTypes.swift:256-271`). */
sealed class TranscriptionEngineError(message: String) : Exception(message) {
    class Unavailable : TranscriptionEngineError(UNAVAILABLE)
    class ModelUnavailable : TranscriptionEngineError(MODEL_UNAVAILABLE)
    class Failed(message: String) : TranscriptionEngineError(message.ifBlank { FAILED })

    companion object {
        const val UNAVAILABLE = "On-device transcription is not available on this device."
        const val MODEL_UNAVAILABLE = "Couldn't download the transcription model. Check your connection and try again."
        const val FAILED = "This voice message couldn't be transcribed."
    }
}
