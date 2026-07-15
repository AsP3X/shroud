import Foundation
import Testing
@testable import shroud

struct EncryptionPhraseGeneratorTests {
    @Test
    func knownEntropyProducesBIP39TestVector() {
        let entropy = Data(repeating: 0, count: 16)
        let words = EncryptionPhraseGenerator.generate(entropy: entropy)

        #expect(words.count == 12)
        #expect(words == Array(repeating: "abandon", count: 11) + ["about"])
    }

    @Test
    func generatedPhraseUsesWordlistEntries() {
        let words = EncryptionPhraseGenerator.generate(
            entropy: Data([
                0x7F, 0xA5, 0x42, 0x10, 0x9C, 0x33, 0x88, 0x6D,
                0x14, 0xC2, 0x5E, 0x91, 0x03, 0xB8, 0x47, 0x6A,
            ])
        )

        #expect(words.count == 12)
        #expect(words.allSatisfy { BIP39EnglishWordlist.words.contains($0) })
    }

    @Test
    func liveGenerationProducesTwelveUniqueOrValidWords() {
        let words = EncryptionPhraseGenerator.generate()

        #expect(words.count == 12)
        #expect(words.allSatisfy { !$0.isEmpty })
        #expect(Set(words).count >= 8)
    }
}
