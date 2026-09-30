import Foundation
import Testing
@testable import shroud

struct BIP39SeedTests {
    @Test
    func abandonAboutMnemonicValidatesAndSeeds() throws {
        // BIP39 test vector: 128 zero bits → 11× abandon + about
        let words = Array(repeating: "abandon", count: 11) + ["about"]
        let validated = try BIP39Seed.validateMnemonic(words)
        #expect(validated == words)

        let seed = try BIP39Seed.seed(fromMnemonic: words)
        #expect(seed.count == 64)
        // Deterministic: second call matches.
        #expect(try BIP39Seed.seed(fromMnemonic: words) == seed)
    }

    @Test
    func invalidChecksumRejected() {
        let words = Array(repeating: "abandon", count: 12)
        #expect(throws: BIP39Seed.SeedError.invalidChecksum) {
            try BIP39Seed.validateMnemonic(words)
        }
    }

    @Test
    func unknownWordRejected() {
        let words = Array(repeating: "abandon", count: 11) + ["notaword"]
        #expect(throws: BIP39Seed.SeedError.unknownWord("notaword")) {
            try BIP39Seed.validateMnemonic(words)
        }
    }

    @Test
    func generatedPhraseValidates() throws {
        let words = EncryptionPhraseGenerator.generate()
        _ = try BIP39Seed.validateMnemonic(words)
        let seed = try BIP39Seed.seed(fromMnemonic: words)
        #expect(seed.count == 64)
    }
}
