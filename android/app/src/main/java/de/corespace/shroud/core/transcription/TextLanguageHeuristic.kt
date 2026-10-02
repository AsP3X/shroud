package de.corespace.shroud.core.transcription

/**
 * Text-based language id for scoring a transcript (web `language.ts:278-291`, Android D10).
 * Neutral 0.5 below 8 letters, with no code, or when this language has no stopword list — iOS's
 * "no opinion". Web returns 0.15 without a list, which would penalise Japanese; 0.5 does not.
 *
 * Stopword lists are copied from `language.ts:52-59`. Never leaves the device.
 */
internal object TextLanguageHeuristic {
    private val stopwords: Map<String, List<String>> = mapOf(
        "de" to listOf("und", "ich", "nicht", "das", "die", "der", "ist", "ein", "zu", "den", "mit", "auf", "für", "es", "auch", "wie", "dass", "sich", "von", "dem"),
        "en" to listOf("the", "and", "you", "that", "was", "for", "are", "with", "this", "have", "not", "but", "they", "from", "what", "your"),
        "fr" to listOf("je", "les", "une", "des", "que", "est", "pas", "le", "la", "et", "dans", "pour"),
        "es" to listOf("que", "los", "las", "una", "por", "con", "para", "está", "como"),
        "it" to listOf("che", "non", "una", "per", "con", "come", "sono"),
        "nl" to listOf("het", "van", "een", "dat", "niet", "voor", "met"),
    )

    private val nonLetters = Regex("[^\\p{L}]+")
    private const val UMLAUTS = "äöüßÄÖÜ"

    fun probability(code: String, text: String): Double {
        val language = TranscriptionLanguage.normalize(code)
        if (language.isEmpty() || VoiceTranscript.letterCount(text) < 8) return 0.5
        val stops = stopwords[language] ?: return 0.5
        val tokens = text.lowercase().split(nonLetters).filter { it.isNotEmpty() }
        val hits = tokens.count { it in stops }
        val stopScore = if (tokens.isEmpty()) 0.0 else hits.toDouble() / minOf(tokens.size, 20)
        if (language == "en" && text.any { it in UMLAUTS }) return minOf(0.25, stopScore)
        val script = if (language == "de" && text.any { it in UMLAUTS }) 0.3 else 0.0
        return (0.15 + stopScore * 1.6 + script).coerceIn(0.05, 1.0)
    }
}
