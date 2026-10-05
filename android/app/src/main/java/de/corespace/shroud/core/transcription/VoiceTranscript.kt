package de.corespace.shroud.core.transcription

import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Whether a transcript is speech, and how much to trust it (iOS `VoiceTranscript`,
 * `TranscriptionLanguage.swift:393-535`). Pure: no audio, no network. [toString] of a
 * [Candidate] does not include the words.
 */
object VoiceTranscript {
    /** Below this the detection is not worth teaching (`minimumTrustedScore`). */
    const val MINIMUM_TRUSTED_SCORE = 0.12

    /** Auto-detect is English-biased; a non-English challenger only has to be close (`englishChallengeMargin`). */
    const val ENGLISH_CHALLENGE_MARGIN = 1.2

    /** One decode pass. [toString] omits [text]. */
    class Candidate(val text: String, val language: String?, val confidence: Double) {
        override fun toString(): String = "Candidate(language=$language, confidence=$confidence, chars=${text.length})"
    }

    fun containsSpeech(text: String): Boolean = letterCount(text) >= 2

    /** Unicode letters (`\p{L}`), matching `CharacterSet.letters`. */
    fun letterCount(text: String): Int = text.count { it.isLetter() }

    /**
     * Trim, drop punctuation-only output, collapse whitespace to one space
     * (`TranscriptionLanguage.swift:407-414`). `", , , ,"` becomes "".
     */
    fun cleaned(text: String): String {
        val trimmed = text.trim()
        if (!containsSpeech(trimmed)) return ""
        return trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /**
     * Higher is better; 0 means not speech (`TranscriptionLanguage.swift:426-447`).
     * [languageProbability] and [prior] are centred on 0.5 so "no opinion" is plain
     * confidence × substance. [prior] weighs more as the audio gets shorter.
     */
    fun score(
        text: String,
        modelConfidence: Double,
        languageProbability: Double = 0.5,
        prior: Double = 0.5,
        audioSeconds: Double = Double.POSITIVE_INFINITY,
    ): Double {
        if (!containsSpeech(text)) return 0.0
        val letters = letterCount(text).toDouble()
        val substance = min(1.0, letters / 12.0)
        val textTrust = min(1.0, letters / 40.0)
        val languageTerm = 0.5 + (languageProbability - 0.5) * textTrust
        val priorTrust = 1.0 - min(1.0, max(0.0, audioSeconds - 2.0) / 6.0)
        val priorTerm = 0.5 + (prior - 0.5) * priorTrust
        return modelConfidence * substance * (0.5 + languageTerm) * (0.5 + priorTerm)
    }

    /** How much a finished note should teach the memory. Short or shaky results teach nothing. */
    fun learningWeight(audioSeconds: Double, score: Double): Double {
        if (score < MINIMUM_TRUSTED_SCORE) return 0.0
        return min(1.0, audioSeconds / 8.0) * min(1.0, score / 0.4)
    }

    fun languageProbability(code: String, text: String): Double = TextLanguageHeuristic.probability(code, text)

    fun score(
        candidate: Candidate,
        memory: TranscriptionLanguageMemory,
        conversationId: UUID?,
        audioSeconds: Double,
    ): Double {
        val language = candidate.language ?: ""
        return score(
            text = candidate.text,
            modelConfidence = candidate.confidence,
            languageProbability = languageProbability(language, candidate.text),
            prior = if (language.isEmpty()) 0.5 else memory.prior(language, conversationId),
            audioSeconds = audioSeconds,
        )
    }

    /**
     * Auto-detect versus a forced-language challenger (`TranscriptionLanguage.swift:488-519`).
     * English (or a missing language) is discounted; the challenger wins only when strictly better.
     */
    fun choose(
        auto: Candidate,
        challenge: Candidate?,
        memory: TranscriptionLanguageMemory,
        conversationId: UUID?,
        audioSeconds: Double,
    ): Candidate {
        val autoLang = auto.language?.let(TranscriptionLanguage::normalize)
        val autoClean = Candidate(cleaned(auto.text), autoLang, auto.confidence)
        val autoScore = score(autoClean, memory, conversationId, audioSeconds)
        if (challenge == null) return autoClean
        val alt = Candidate(
            cleaned(challenge.text),
            challenge.language?.let(TranscriptionLanguage::normalize),
            challenge.confidence,
        )
        val altScore = score(alt, memory, conversationId, audioSeconds)
        val autoLooksEnglish = autoLang == null || autoLang == "en"
        val autoEffective = if (autoLooksEnglish && alt.language != "en") autoScore / ENGLISH_CHALLENGE_MARGIN else autoScore
        return if (altScore > autoEffective) alt else autoClean
    }
}
