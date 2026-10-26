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
    func copyStaysOnThisDeviceAndExpires() {
        // Human: Universal Clipboard would otherwise hand the phrase to the user's Mac and iPad.
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let options = EncryptionPhrasePasteboard.copyOptions(now: now)
        #expect(options[.localOnly] as? Bool == true)
        #expect(options[.expirationDate] as? Date == now.addingTimeInterval(60))
    }

    @Test
    @MainActor
    func copyRejectsEmptyPhrase() {
        #expect(EncryptionPhrasePasteboard.copy("") == false)
        #expect(EncryptionPhrasePasteboard.copy("   ") == false)
    }
}
