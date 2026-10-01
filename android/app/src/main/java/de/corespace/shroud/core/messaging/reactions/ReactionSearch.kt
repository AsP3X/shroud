package de.corespace.shroud.core.messaging.reactions

import de.corespace.shroud.core.net.wire.TextUnits
import de.corespace.shroud.core.net.wire.WireText
import kotlin.math.abs
import kotlin.math.min

/**
 * Fuzzy search over the reaction set: what the expanded reaction panel's search field runs — iOS
 * `ReactionSearch` (`ios/shroud/Services/Messaging/ReactionSearch.swift:13-128`), web
 * `reactionSearch.ts`. The same rules over the same generated table ([ReactionSet]), checked with the
 * same vectors, so a query finds the same emoji in the same order on every client.
 *
 * Every emoji answers to its name and the words people type for it. A word of the query matches a
 * keyword by prefix, by the start of one of its words, as a substring, as an abbreviation ("hndrd" →
 * hundred) or with a typo or two ("thnaks" → thanks); with several words all of them must match. Best
 * match first; ties keep the set's order.
 *
 * Strings are compared as grapheme arrays, like Swift's `Array(String)`. The keyword table is ASCII,
 * so this only matters for what a person types (an emoji pasted into the field).
 */
object ReactionSearch {
    /** The words each emoji answers to: its name first, then what people type for it. */
    val keywords: Map<String, List<String>> get() = ReactionSet.keywords

    /** The table with each keyword in query form, so "star-struck" and "star struck" both match (`:17-18`). */
    private val normalizedKeywords: Map<String, List<String>> by lazy {
        ReactionSet.keywords.mapValues { (_, words) -> words.map(::normalize) }
    }

    /**
     * Lower case, `-` and `_` as spaces, one space between words (`:20-27`). Words split at whitespace
     * graphemes, like Swift's `split(whereSeparator: \.isWhitespace)`.
     */
    fun normalize(query: String): String {
        val spaced = query.lowercase().replace('-', ' ').replace('_', ' ')
        return splitWords(spaced).joinToString(" ")
    }

    /**
     * Damerau–Levenshtein distance (adjacent swaps count one), capped: `limit + 1` once it is past
     * [limit] (`:41-66`).
     */
    fun editDistance(a: String, b: String, limit: Int): Int = editDistance(characters(a), characters(b), limit)

    /**
     * How well one word of a query matches one keyword; 0 is no match, 100 the keyword itself
     * (`:74-96`): equal 100, prefix 90, a word of the keyword starts with it 80, a substring of at
     * least two letters 70, an abbreviation of at least four letters 50, else a typo within budget
     * (none under five letters, one up to seven, two from eight) `40 − 10 × distance`, where a typo in
     * the start of a longer word costs half a point more.
     */
    fun keywordScore(word: String, keyword: String): Double {
        if (keyword == word) return 100.0
        if (keyword.startsWith(word)) return 90.0
        val parts = keyword.split(' ').filter { it.isNotEmpty() }
        if (parts.any { it.startsWith(word) }) return 80.0
        val letters = characters(word)
        if (letters.size >= 2 && keyword.contains(word)) return 70.0
        // From four letters: "cat" abbreviating "celebrate" and "caution" was noise, "hndrd" is not.
        if (letters.size >= 4 && parts.any { abbreviates(letters, it) }) return 50.0
        val budget = typoBudget(letters.size)
        if (budget == 0) return 0.0
        var best = (budget + 1).toDouble()
        for (candidate in listOf(keyword) + parts) {
            val candidateCharacters = characters(candidate)
            best = min(best, editDistance(letters, candidateCharacters, budget).toDouble())
            // A typo in the start of a longer word: "thnaks" against "thanks" from "thank you".
            if (candidateCharacters.size > letters.size) {
                val head = candidateCharacters.subList(0, letters.size)
                best = min(best, editDistance(letters, head, budget) + 0.5)
            }
        }
        return if (best <= budget) 40.0 - best * 10.0 else 0.0
    }

    /**
     * How well the whole [query] matches [emoji] (`:98-111`): 100 for the emoji itself, else every word
     * has to match one of its keywords and the weakest word counts; 0 for an empty query.
     */
    fun score(query: String, emoji: String): Double {
        val normalized = normalize(query)
        if (normalized.isEmpty()) return 0.0
        if (normalized == emoji) return 100.0
        val words = normalizedKeywords[emoji] ?: emptyList()
        var weakest = Double.POSITIVE_INFINITY
        for (word in normalized.split(' ').filter { it.isNotEmpty() }) {
            val best = words.fold(0.0) { acc, keyword -> maxOf(acc, keywordScore(word, keyword)) }
            if (best == 0.0) return 0.0
            weakest = min(weakest, best)
        }
        return if (weakest == Double.POSITIVE_INFINITY) 0.0 else weakest
    }

    /**
     * The emoji of [list] that match [query], best first, ties in [list]'s order (`:113-127`). An empty
     * query is the whole list as it is.
     */
    fun matches(query: String, list: List<String> = ReactionSet.all): List<String> {
        if (normalize(query).isEmpty()) return list
        return list.mapIndexedNotNull { index, emoji ->
            val score = score(query, emoji)
            if (score > 0) Triple(emoji, index, score) else null
        }
            .sortedWith(compareByDescending<Triple<String, Int, Double>> { it.third }.thenBy { it.second })
            .map { it.first }
    }

    // ---------------------------------------------------------------------------------------------

    /** Swift `Array(text)`: the graphemes. ASCII needs no segmenter. */
    private fun characters(text: String): List<String> =
        if (text.all { it.code < 0x80 && it != '\r' }) text.map(Char::toString) else TextUnits.current.graphemes(text)

    /** `split(whereSeparator: \.isWhitespace)`: graphemes whose first scalar is whitespace separate words. */
    private fun splitWords(text: String): List<String> {
        val words = ArrayList<String>()
        val current = StringBuilder()
        for (character in characters(text)) {
            if (WireText.isWhitespaceOrNewline(character.codePointAt(0))) {
                if (current.isNotEmpty()) {
                    words += current.toString()
                    current.setLength(0)
                }
            } else {
                current.append(character)
            }
        }
        if (current.isNotEmpty()) words += current.toString()
        return words
    }

    /**
     * [word] abbreviates [text]: same first letter, and the rest appear in [text] in order ("hndrd" →
     * hundred), anchored at the start (`:29-39`).
     */
    private fun abbreviates(word: List<String>, text: String): Boolean {
        val chars = characters(text)
        if (word.isEmpty() || chars.isEmpty() || chars[0] != word[0]) return false
        var at = 1
        for (char in chars.drop(1)) {
            if (at < word.size && char == word[at]) at++
        }
        return at == word.size
    }

    /** How many typos a query word may have: none under five letters, two from eight (`:68-72`). */
    private fun typoBudget(length: Int): Int = when {
        length < 5 -> 0
        length >= 8 -> 2
        else -> 1
    }

    private fun editDistance(x: List<String>, y: List<String>, limit: Int): Int {
        if (abs(x.size - y.size) > limit) return limit + 1
        if (x.isEmpty()) return y.size
        var previous2 = IntArray(0)
        var previous = IntArray(y.size + 1) { it }
        for (i in 1..x.size) {
            val current = IntArray(y.size + 1)
            current[0] = i
            var rowMin = i
            for (j in 1..y.size) {
                val same = if (x[i - 1] == y[j - 1]) 0 else 1
                var cost = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + same)
                if (i > 1 && j > 1 && x[i - 1] == y[j - 2] && x[i - 2] == y[j - 1]) {
                    cost = min(cost, previous2[j - 2] + 1)
                }
                current[j] = cost
                rowMin = min(rowMin, cost)
            }
            if (rowMin > limit) return limit + 1
            previous2 = previous
            previous = current
        }
        return previous[y.size]
    }
}
