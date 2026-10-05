package de.corespace.shroud.core.transcription

import kotlin.math.min

/**
 * Whether a transcript is speech, and how much a note teaches the language memory (iOS
 * `VoiceTranscript`, `TranscriptionLanguage.swift`). Pure: no audio, no network.
 */
object VoiceTranscript {
    fun containsSpeech(text: String): Boolean = letterCount(text) >= 2

    /** Unicode letters (`\p{L}`), matching `CharacterSet.letters`. */
    fun letterCount(text: String): Int = text.count { it.isLetter() }

    /**
     * Trim, drop punctuation-only output, collapse whitespace to one space
     * (`VoiceTranscript.cleaned`). `", , , ,"` becomes "".
     */
    fun cleaned(text: String): String {
        val trimmed = text.trim()
        if (!containsSpeech(trimmed)) return ""
        return trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Below this probability the audio did not settle the language, so the note teaches nothing. */
    const val DECISIVE_PROBABILITY = 0.5

    /** At this probability the audio settled it fully. */
    const val CERTAIN_PROBABILITY = 0.9

    /**
     * How much a finished note teaches the memory (`learningWeight`). Only what the audio itself
     * settled counts: [languageProbability] is Whisper's probability for the language the note was
     * decoded in, before any weighting. A note that history carried teaches nothing, so a wrong
     * entry can't feed itself. Longer notes count more, up to 8 s.
     */
    fun learningWeight(audioSeconds: Double, languageProbability: Double): Double {
        if (!languageProbability.isFinite() || audioSeconds <= 0.0) return 0.0
        val settled = (languageProbability - DECISIVE_PROBABILITY) / (CERTAIN_PROBABILITY - DECISIVE_PROBABILITY)
        return min(1.0, audioSeconds / 8.0) * settled.coerceIn(0.0, 1.0)
    }
}
