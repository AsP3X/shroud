import Foundation

/// Parses a pasted encryption phrase into exactly 12 normalized words.
enum EncryptionPhraseParser {
    // Human: Accepts whitespace-separated phrases copied from Shroud or any plain-text source.
    // Agent: RETURNS 12 lowercased words or nil when fewer than 12 tokens are present; never logs input.
    static func parse(_ text: String) -> [String]? {
        let words = text
            .components(separatedBy: .whitespacesAndNewlines)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
            .filter { !$0.isEmpty }

        guard words.count >= EncryptionPhraseGenerator.wordCount else { return nil }
        return Array(words.prefix(EncryptionPhraseGenerator.wordCount))
    }
}
