import Foundation

/// Fuzzy search over the reaction set: what the expanded reaction panel's search field runs.
///
/// Human: Every emoji answers to its Unicode name and the words people actually type for it
/// ("lol", "thanks", "wow"). A word of the query matches a keyword by prefix, by the start of one
/// of its words, as a substring, as an abbreviation ("hndrd" → hundred) or with a typo or two
/// ("thnaks" → thanks); when the query has several words, all of them have to match. Best match
/// first; ties keep the set's order.
/// Agent: Pure functions, the same table and rules as `web/src/reactionSearch.ts`;
/// `ReactionSearchTests` and the web selftest check the same vectors, so a query finds the same
/// emoji in the same order on both clients. Keep them in step.
nonisolated enum ReactionSearch {
    /// The words each emoji answers to: its Unicode name first, then what people type for it.
    static let keywords: [String: [String]] = [
        "❤️": ["red heart", "heart", "love", "like"],
        "🔥": ["fire", "lit", "hot", "flame"],
        "👍": ["thumbs up", "like", "yes", "ok", "approve", "agree", "good"],
        "😢": ["crying face", "sad", "tear", "cry"],
        "🙏": ["folded hands", "please", "thanks", "thank you", "pray", "high five"],
        "😮": ["face with open mouth", "wow", "surprised", "shocked", "omg"],
        "👎": ["thumbs down", "dislike", "no", "disagree", "bad"],
        "🥰": ["smiling face with hearts", "adore", "love", "in love", "crush"],
        "👏": ["clapping hands", "applause", "bravo", "clap", "well done"],
        "😁": ["beaming face with smiling eyes", "grin", "happy", "smile", "teeth"],
        "🤔": ["thinking face", "hmm", "think", "wonder"],
        "🤯": ["exploding head", "mind blown", "wow"],
        "😱": ["face screaming in fear", "scream", "shocked", "horror"],
        "🤬": ["face with symbols on mouth", "cursing", "swearing", "angry", "rage"],
        "🎉": ["party popper", "celebrate", "congratulations", "tada", "party"],
        "🤩": ["star-struck", "starstruck", "excited", "amazing", "wow"],
        "🤮": ["face vomiting", "vomit", "puke", "sick", "gross"],
        "💩": ["pile of poo", "poop", "shit", "crap"],
        "👌": ["ok hand", "okay", "perfect", "nice"],
        "🕊️": ["dove", "peace", "bird"],
        "🤡": ["clown face", "clown", "joke", "fool"],
        "🥱": ["yawning face", "yawn", "bored", "tired", "sleepy"],
        "🥴": ["woozy face", "drunk", "dizzy", "tipsy"],
        "😍": ["smiling face with heart-eyes", "heart eyes", "love", "adore"],
        "🐳": ["spouting whale", "whale", "sea"],
        "❤️‍🔥": ["heart on fire", "burning heart", "passion", "love"],
        "🌚": ["new moon face", "moon", "dark", "creepy"],
        "🌭": ["hot dog", "sausage", "food"],
        "💯": ["hundred points", "100", "perfect score", "keep it 100"],
        "🤣": ["rolling on the floor laughing", "rofl", "lol", "laugh", "haha", "hilarious"],
        "⚡": ["high voltage", "lightning", "zap", "electric", "thunder"],
        "🍌": ["banana", "fruit"],
        "🏆": ["trophy", "winner", "champion", "award", "cup"],
        "💔": ["broken heart", "heartbreak", "sad"],
        "🤨": ["face with raised eyebrow", "suspicious", "skeptical", "doubt", "hmm"],
        "😐": ["neutral face", "meh", "blank", "straight face"],
        "🍓": ["strawberry", "fruit", "berry"],
        "🍾": ["bottle with popping cork", "champagne", "celebrate", "cheers"],
        "💋": ["kiss mark", "kiss", "lips"],
        "🖕": ["middle finger", "fuck you", "flip off", "rude"],
        "😈": ["smiling face with horns", "devil", "evil", "naughty"],
        "😴": ["sleeping face", "sleep", "zzz", "tired", "snore"],
        "😭": ["loudly crying face", "sob", "cry", "bawling", "sad", "tears"],
        "🤓": ["nerd face", "nerd", "geek", "glasses"],
        "👻": ["ghost", "boo", "spooky", "halloween"],
        "👨‍💻": ["man technologist", "coder", "developer", "programmer", "hacker", "computer"],
        "👀": ["eyes", "look", "watching", "see", "side eye"],
        "🎃": ["jack-o-lantern", "pumpkin", "halloween"],
        "🙈": ["see-no-evil monkey", "monkey", "hide", "embarrassed", "cover eyes"],
        "😇": ["smiling face with halo", "angel", "innocent", "holy"],
        "😨": ["fearful face", "scared", "afraid", "fear", "anxious"],
        "🤝": ["handshake", "deal", "agreement", "thanks", "partners"],
        "✍️": ["writing hand", "write", "note", "pen"],
        "🤗": ["smiling face with open hands", "hug", "warm"],
        "🫡": ["saluting face", "salute", "yes sir", "respect", "aye"],
        "🎅": ["santa claus", "christmas", "xmas"],
        "🎄": ["christmas tree", "xmas", "holiday"],
        "☃️": ["snowman", "winter", "snow", "cold"],
        "💅": ["nail polish", "nails", "sassy", "slay", "manicure"],
        "🤪": ["zany face", "crazy", "goofy", "silly", "wild"],
        "🗿": ["moai", "stone face", "statue", "deadpan", "easter island"],
        "🆒": ["cool button", "cool"],
        "💘": ["heart with arrow", "cupid", "love", "crush"],
        "🙉": ["hear-no-evil monkey", "monkey", "ears", "not listening"],
        "🦄": ["unicorn", "magic", "fantasy"],
        "😘": ["face blowing a kiss", "kiss", "love", "xoxo"],
        "💊": ["pill", "medicine", "drug", "capsule"],
        "🙊": ["speak-no-evil monkey", "monkey", "oops", "secret", "quiet"],
        "😎": ["smiling face with sunglasses", "cool", "sunglasses", "chill"],
        "👾": ["alien monster", "space invader", "game", "retro"],
        "🤷": ["person shrugging", "shrug", "dunno", "whatever", "idk"],
        "😡": ["enraged face", "angry", "mad", "furious", "red"],
    ]

    /// The table with each keyword in query form, so "star-struck" and "star struck" both match.
    private static let normalizedKeywords: [String: [String]] = keywords.mapValues { $0.map(normalize) }

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
        if letters.count >= 3, parts.contains(where: { abbreviates(letters, $0) }) { return 50 }
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
