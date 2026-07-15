import Testing
@testable import shroud

struct EncryptionPhraseParserTests {
    @Test
    func parseAcceptsTwelveWhitespaceSeparatedWords() {
        let phrase = "ember copper lyric marble frost anchor velvet orbit prism delta canyon harbor"
        let words = EncryptionPhraseParser.parse(phrase)
        #expect(words?.count == 12)
        #expect(words?.first == "ember")
        #expect(words?.last == "harbor")
    }

    @Test
    func parseNormalizesCaseAndExtraWhitespace() {
        let phrase = "  Ember   COPPER  lyric marble frost anchor velvet orbit prism delta canyon harbor  "
        let words = EncryptionPhraseParser.parse(phrase)
        #expect(words == [
            "ember", "copper", "lyric", "marble", "frost", "anchor",
            "velvet", "orbit", "prism", "delta", "canyon", "harbor",
        ])
    }

    @Test
    func parseRejectsFewerThanTwelveWords() {
        #expect(EncryptionPhraseParser.parse("one two three") == nil)
    }

    @Test
    func parseUsesFirstTwelveWhenExtraWordsPresent() {
        let words = EncryptionPhraseParser.parse(
            "one two three four five six seven eight nine ten eleven twelve thirteen"
        )
        #expect(words?.count == 12)
        #expect(words?.last == "twelve")
    }
}
