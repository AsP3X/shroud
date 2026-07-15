import Testing
@testable import shroud

struct EncryptionPhrasePasteboardTests {
    @Test
    @MainActor
    func copyWritesPlainTextToPasteboard() {
        let phrase = "ember copper lyric marble frost anchor velvet orbit prism delta canyon harbor"
        let copied = EncryptionPhrasePasteboard.copy(phrase)

        #expect(copied)
        #expect(EncryptionPhrasePasteboard.read() == phrase)
    }

    @Test
    @MainActor
    func copyRejectsEmptyPhrase() {
        #expect(EncryptionPhrasePasteboard.copy("") == false)
        #expect(EncryptionPhrasePasteboard.copy("   ") == false)
    }
}
