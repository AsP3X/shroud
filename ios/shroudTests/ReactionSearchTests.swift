import Testing
@testable import shroud

/// The reaction panel's search: the same vectors as `web/src/reactionSearch.selftest.ts`, so a
/// query finds the same emoji in the same order on both clients.
@MainActor
struct ReactionSearchTests {
    private let all = MessageReactionBar.expanded

    private func search(_ query: String) -> [String] {
        ReactionSearch.matches(query, in: all)
    }

    @Test func everyReactionHasKeywordsAndNoStrayOnes() {
        for emoji in all {
            #expect(!(ReactionSearch.keywords[emoji] ?? []).isEmpty, "keywords for \(emoji)")
        }
        for emoji in ReactionSearch.keywords.keys {
            #expect(all.contains(emoji), "\(emoji) is not in the set")
        }
    }

    @Test func normalisingLowersCaseAndSpacesHyphens() {
        #expect(ReactionSearch.normalize("  Star-Struck ") == "star struck")
        #expect(ReactionSearch.normalize("thank\t you") == "thank you")
    }

    @Test func editDistanceCountsSwapsOnceAndCaps() {
        #expect(ReactionSearch.editDistance("thnaks", "thanks", limit: 2) == 1)
        #expect(ReactionSearch.editDistance("kitten", "sitting", limit: 3) == 3)
        #expect(ReactionSearch.editDistance("abc", "abcd", limit: 1) == 1)
        #expect(ReactionSearch.editDistance("abc", "xyz", limit: 1) == 2)
        #expect(ReactionSearch.editDistance("👍", "👍", limit: 1) == 0)
        #expect(ReactionSearch.editDistance("", "ab", limit: 2) == 2)
    }

    @Test func oneKeywordScoresByHowItMatches() {
        #expect(ReactionSearch.keywordScore("fire", "fire") == 100)
        #expect(ReactionSearch.keywordScore("fir", "fire") == 90)
        #expect(ReactionSearch.keywordScore("heart", "broken heart") == 80)
        #expect(ReactionSearch.keywordScore("ok", "look") == 70)
        #expect(ReactionSearch.keywordScore("hndrd", "hundred points") == 50)
        #expect(ReactionSearch.keywordScore("thnaks", "thanks") == 30)
        #expect(ReactionSearch.keywordScore("xx", "look") == 0)
        #expect(ReactionSearch.keywordScore("zzzz", "zany face") == 0)
    }

    @Test func emptyQueryIsTheWholeSetInOrder() {
        #expect(search("") == all)
        #expect(search("   ") == all)
    }

    @Test func queriesFindWhatTheWebFinds() {
        #expect(search("fire") == ["🔥", "❤️‍🔥"])
        #expect(search("lol").first == "🤣")
        #expect(search("LOL").first == "🤣")
        #expect(Array(search("thanks").prefix(2)) == ["🙏", "🤝"])
        #expect(search("thnaks").first == "🙏")
        #expect(search("hndrd").first == "💯")
        #expect(search("100").first == "💯")
        #expect(search("heart").first == "❤️")
        #expect(search("heart").contains("💔"))
        #expect(search("red heart") == ["❤️"])
        #expect(search("monkey") == ["🙈", "🙉", "🙊"])
        #expect(search("star struck").first == "🤩")
        #expect(search("star-struck").first == "🤩")
        #expect(search("wow") == ["😮", "🤯", "🤩"])
        #expect(Array(search("ok").prefix(2)) == ["👍", "👌"])
        #expect(search("no").first == "👎")
        #expect(search("🔥") == ["🔥"])
        #expect(search("xyzzy").isEmpty)
        #expect(ReactionSearch.matches("fire", in: ["👍", "🔥"]) == ["🔥"])
    }
}
