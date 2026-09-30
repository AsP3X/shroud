import Foundation

/// Fuzzy search over the reaction set: what the expanded reaction panel's search field runs.
///
/// Human: Every emoji answers to its name and the words people actually type for it ("lol",
/// "thanks", "wow"), from `ReactionSet`. A word of the query matches a keyword by prefix, by the
/// start of one of its words, as a substring, as an abbreviation ("hndrd" → hundred) or with a
/// typo or two ("thnaks" → thanks); when the query has several words, all of them have to match.
/// Best match first; ties keep the set's order.
/// Agent: Pure functions, the same rules as `web/src/reactionSearch.ts` over the same generated
/// table; `ReactionSearchTests` and the web selftest check the same vectors, so a query finds the
/// same emoji in the same order on both clients. Keep them in step.
nonisolated enum ReactionSearch {
    /// The words each emoji answers to: its name first, then what people type for it.
    static var keywords: [String: [String]] { ReactionSet.keywords }

    /// The table with each keyword in query form, so "star-struck" and "star struck" both match.
    private static let normalizedKeywords: [String: [String]] = ReactionSet.keywords.mapValues { $0.map(normalize) }

    /// Lower case, hyphens as spaces, one space between words.
    static func normalize(_ query: String) -> String {
        query.lowercased()
            .replacingOccurrences(of: "-", with: " ")
            .replacingOccurrences(of: "_", with: " ")
            .split(whereSeparator: \.isWhitespace)
            .joined(separator: " ")
    }

    /// `word` abbreviates `text`: same first letter, and the rest appear in `text` in order
    /// ("hndrd" → hundred). Anchored at the start, or "fire" would find "face with raised eyebrow".
    private static func abbreviates(_ word: [Character], _ text: String) -> Bool {
        let chars = Array(text)
        guard let first = word.first, chars.first == first else { return false }
        var at = 1
        for char in chars.dropFirst() where at < word.count && char == word[at] {
            at += 1
        }
        return at == word.count
    }

    /// Damerau–Levenshtein (adjacent swaps count one), capped: returns `limit + 1` past `limit`.
    static func editDistance(_ a: String, _ b: String, limit: Int) -> Int {
        let x = Array(a)
        let y = Array(b)
        if abs(x.count - y.count) > limit { return limit + 1 }
        if x.isEmpty { return y.count }
        var previous2: [Int] = []
        var previous = Array(0...y.count)
        for i in 1...x.count {
            var current = [i]
            var rowMin = i
            for j in stride(from: 1, through: y.count, by: 1) {
                let same = x[i - 1] == y[j - 1] ? 0 : 1
                var cost = min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + same)
                if i > 1, j > 1, x[i - 1] == y[j - 2], x[i - 2] == y[j - 1] {
                    cost = min(cost, previous2[j - 2] + 1)
                }
                current.append(cost)
                rowMin = min(rowMin, cost)
            }
            if rowMin > limit { return limit + 1 }
            previous2 = previous
            previous = current
        }
        return previous[y.count]
    }

    /// How many typos a query word may have: none under five letters, two from eight.
    private static func typoBudget(_ length: Int) -> Int {
        if length < 5 { return 0 }
        return length >= 8 ? 2 : 1
    }

    /// How well one word of a query matches one keyword; 0 is no match, 100 the keyword itself.
    static func keywordScore(_ word: String, _ keyword: String) -> Double {
        if keyword == word { return 100 }
        if keyword.hasPrefix(word) { return 90 }
        let parts = keyword.split(separator: " ").map(String.init)
        if parts.contains(where: { $0.hasPrefix(word) }) { return 80 }
        let letters = Array(word)
        if letters.count >= 2, keyword.contains(word) { return 70 }
        // From four letters: "cat" abbreviating "celebrate" and "caution" was noise, "hndrd" is not.
        if letters.count >= 4, parts.contains(where: { abbreviates(letters, $0) }) { return 50 }
        let budget = typoBudget(letters.count)
        if budget == 0 { return 0 }
        var best = Double(budget + 1)
        for candidate in [keyword] + parts {
            best = min(best, Double(editDistance(word, candidate, limit: budget)))
            // A typo in the start of a longer word: "thnaks" against "thanks" from "thank you".
            if candidate.count > letters.count {
                let head = String(candidate.prefix(letters.count))
                best = min(best, Double(editDistance(word, head, limit: budget)) + 0.5)
            }
        }
        return best <= Double(budget) ? 40 - best * 10 : 0
    }

    /// How well the whole query matches `emoji`: every word has to match, the weakest one counts.
    static func score(_ query: String, emoji: String) -> Double {
        let normalized = normalize(query)
        if normalized.isEmpty { return 0 }
        if normalized == emoji { return 100 }
        let keywords = normalizedKeywords[emoji] ?? []
        var weakest = Double.infinity
        for word in normalized.split(separator: " ").map(String.init) {
            let best = keywords.reduce(0.0) { max($0, keywordScore(word, $1)) }
            if best == 0 { return 0 }
            weakest = min(weakest, best)
        }
        return weakest == .infinity ? 0 : weakest
    }

    /// The emoji of `list` that match `query`, best first (ties keep the list's order). An empty
    /// query is the whole list as it is.
    static func matches(_ query: String, in list: [String]) -> [String] {
        if normalize(query).isEmpty { return list }
        var scored: [(emoji: String, index: Int, score: Double)] = []
        for (index, emoji) in list.enumerated() {
            let score = score(query, emoji: emoji)
            if score > 0 { scored.append((emoji, index, score)) }
        }
        scored.sort { a, b in
            if a.score != b.score { return a.score > b.score }
            return a.index < b.index
        }
        return scored.map(\.emoji)
    }
}
