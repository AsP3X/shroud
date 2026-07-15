import Testing
import UIKit
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
    func copyIsReadableAsGeneralPasteboardString() {
        // Human: Users paste into Notes / password managers via the system plain-text payload.
        let phrase = "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima"
        #expect(EncryptionPhrasePasteboard.copy(phrase))
        #expect(UIPasteboard.general.string == phrase)
    }

    @Test
    @MainActor
    func copyRejectsEmptyPhrase() {
        #expect(EncryptionPhrasePasteboard.copy("") == false)
        #expect(EncryptionPhrasePasteboard.copy("   ") == false)
    }
}
