package de.corespace.shroud.core.messaging.reactions

import de.corespace.shroud.core.net.wire.Icu4jTextUnitsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The reaction panel's search: the vectors of `ios/shroudTests/ReactionSearchTests.swift` (all) and
 * `web/src/reactionSearch.selftest.ts`, so a query finds the same emoji in the same order on every
 * client. iOS searches `MessageReactionBar.expanded`, which is `ReactionSet.all`
 * (`MessageActionMenu.swift:51`).
 */
class ReactionSearchTest {
    @get:Rule
    val textUnits = Icu4jTextUnitsRule()

    private val all = ReactionSet.all

    private fun search(query: String): List<String> = ReactionSearch.matches(query, all)

    @Test
    fun everyReactionHasKeywordsAndNoStrayOnes() { // :14-21
        for (emoji in all) {
            assertTrue("keywords for $emoji", ReactionSearch.keywords[emoji].orEmpty().isNotEmpty())
        }
        for (emoji in ReactionSearch.keywords.keys) {
            assertTrue("$emoji is not in the set", emoji in all)
        }
    }

    @Test
    fun normalisingLowersCaseAndSpacesHyphens() { // :23-26
        assertEquals("star struck", ReactionSearch.normalize("  Star-Struck "))
        assertEquals("thank you", ReactionSearch.normalize("thank\t you"))
        assertEquals("snake case words", ReactionSearch.normalize("snake_case\n\nWORDS"))
        assertEquals("", ReactionSearch.normalize(" 　 "))
    }

    @Test
    fun editDistanceCountsSwapsOnceAndCaps() { // :28-35
        assertEquals(1, ReactionSearch.editDistance("thnaks", "thanks", 2))
        assertEquals(3, ReactionSearch.editDistance("kitten", "sitting", 3))
        assertEquals(1, ReactionSearch.editDistance("abc", "abcd", 1))
        assertEquals(2, ReactionSearch.editDistance("abc", "xyz", 1))
        assertEquals(0, ReactionSearch.editDistance("👍", "👍", 1))
        assertEquals(2, ReactionSearch.editDistance("", "ab", 2))
        // Characters, not UTF-16 units: a family is one character away from another.
        assertEquals(1, ReactionSearch.editDistance("👨‍👩‍👧", "👨‍👩‍👦", 1))
    }

    @Test
    fun oneKeywordScoresByHowItMatches() { // :37-46
        assertEquals(100.0, ReactionSearch.keywordScore("fire", "fire"), 0.0)
        assertEquals(90.0, ReactionSearch.keywordScore("fir", "fire"), 0.0)
        assertEquals(80.0, ReactionSearch.keywordScore("heart", "broken heart"), 0.0)
        assertEquals(70.0, ReactionSearch.keywordScore("ok", "look"), 0.0)
        assertEquals(50.0, ReactionSearch.keywordScore("hndrd", "hundred points"), 0.0)
        assertEquals(30.0, ReactionSearch.keywordScore("thnaks", "thanks"), 0.0)
        assertEquals(0.0, ReactionSearch.keywordScore("xx", "look"), 0.0)
        assertEquals(0.0, ReactionSearch.keywordScore("zzzz", "zany face"), 0.0)
    }

    @Test
    fun aTypoInTheStartOfALongerWordCostsHalfAPointMore() { // ReactionSearch.swift:89-93
        // Eight letters may have two typos. The whole keyword is three letters longer (out of reach),
        // but its first eight letters are one swap away: 1 + 0.5 → 40 − 15.
        assertEquals(25.0, ReactionSearch.keywordScore("bacdefgh", "abcdefghijk"), 0.0)
        // The same swap against a keyword of the word's own length costs the full point.
        assertEquals(30.0, ReactionSearch.keywordScore("bacdefgh", "abcdefgh"), 0.0)
        // A typo against one word of the keyword is a typo too ("thnak" → "thank" of "thank you").
        assertEquals(30.0, ReactionSearch.keywordScore("thnak", "thank you"), 0.0)
        // Past the budget nothing matches.
        assertEquals(0.0, ReactionSearch.keywordScore("qwxyz", "thank you"), 0.0)
    }

    @Test
    fun emptyQueryIsTheWholeSetInOrder() { // :48-51
        assertEquals(all, search(""))
        assertEquals(all, search("   "))
    }

    @Test
    fun queriesFindWhatTheWebFinds() { // :53-80
        assertEquals(listOf("🔥", "❤️‍🔥"), search("fire"))
        assertEquals("🤣", search("lol").first())
        assertEquals("🤣", search("LOL").first())
        assertEquals(listOf("🙏", "🤝"), search("thanks").take(2))
        assertEquals("🙏", search("thnaks").first())
        assertEquals("💯", search("hndrd").first())
        assertEquals("💯", search("100").first())
        assertEquals("❤️", search("heart").first())
        assertTrue(search("heart").contains("💔"))
        assertEquals(listOf("❤️"), search("red heart"))
        assertEquals(listOf("🙈", "🙉", "🙊"), search("monkey").take(3))
        assertEquals("😺", search("cat").first())
        assertTrue(search("cat").contains("🐱"))
        assertTrue(search("check").contains("✅"))
        assertTrue(search("grinning").contains("😀"))
        assertTrue(all.size > 300)
        assertEquals(all.size, all.toSet().size)
        assertEquals("🤩", search("star struck").first())
        assertEquals("🤩", search("star-struck").first())
        assertEquals(listOf("😮", "🤯", "🤩"), search("wow"))
        assertEquals(listOf("👍", "👌"), search("ok").take(2))
        assertEquals("👎", search("no").first())
        assertEquals(listOf("🔥"), search("🔥"))
        assertTrue(search("xyzzy").isEmpty())
        assertEquals(listOf("🔥"), ReactionSearch.matches("fire", listOf("👍", "🔥")))
    }

    @Test
    fun theDefaultListIsTheWholeSet() {
        assertEquals(search("fire"), ReactionSearch.matches("fire"))
    }

    @Test
    fun scoreOfAWholeQuery() { // ReactionSearch.swift:98-111
        assertEquals(0.0, ReactionSearch.score("  ", "🔥"), 0.0)
        assertEquals(100.0, ReactionSearch.score("🔥", "🔥"), 0.0)
        // Every word must match and the weakest counts: "heart" is a keyword (100), "red" only the
        // start of "red heart" (90).
        assertEquals(90.0, ReactionSearch.score("red heart", "❤️"), 0.0)
        assertEquals(0.0, ReactionSearch.score("red xyzzy", "❤️"), 0.0)
        assertEquals(0.0, ReactionSearch.score("fire", "not in the table"), 0.0)
    }
}
