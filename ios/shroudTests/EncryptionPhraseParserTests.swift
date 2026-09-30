import Testing
@testable import shroud

struct EncryptionPhraseParserTests {
    private let validPhrase = ([String](repeating: "abandon", count: 11) + ["about"]).joined(separator: " ")

    @Test
    func parseAcceptsValidBIP39TwelveWords() {
        let words = EncryptionPhraseParser.parse(validPhrase)
        #expect(words?.count == 12)
        #expect(words?.first == "abandon")
        #expect(words?.last == "about")
    }

    @Test
    func parseNormalizesCaseAndExtraWhitespace() {
        let phrase = "  Abandon   ABANDON  abandon abandon abandon abandon abandon abandon abandon abandon abandon about  "
        let words = EncryptionPhraseParser.parse(phrase)
        #expect(words == Array(repeating: "abandon", count: 11) + ["about"])
    }

    @Test
    func parseRejectsFewerThanTwelveWords() {
        #expect(EncryptionPhraseParser.parse("one two three") == nil)
    }

    @Test
    func parseRejectsInvalidChecksum() {
        let invalid = Array(repeating: "abandon", count: 12).joined(separator: " ")
        #expect(EncryptionPhraseParser.parse(invalid) == nil)
    }

    @Test
    func parseLenientDoesNotRequireChecksum() {
        let words = EncryptionPhraseParser.parseLenient(
            "one two three four five six seven eight nine ten eleven twelve thirteen"
        )
        #expect(words?.count == 12)
        #expect(words?.last == "twelve")
    }
}
