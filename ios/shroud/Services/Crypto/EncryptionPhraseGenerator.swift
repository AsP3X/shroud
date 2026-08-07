import CryptoKit
import Foundation
import Security

/// Generates a 12-word BIP39 encryption phrase using the platform CSPRNG.
nonisolated enum EncryptionPhraseGenerator {
    static let wordCount = 12

    // Human: Creates the account recovery phrase shown during Sign Up.
    // Agent: READS CSPRNG entropy via SecRandomCopyBytes, RETURNS 12 BIP39 words; never logs or transmits phrase.
    static func generate() -> [String] {
        generate(entropy: secureRandomEntropy())
    }

    /// Deterministic generation for tests using a fixed 128-bit entropy payload.
    static func generate(entropy: Data) -> [String] {
        precondition(entropy.count == 16, "12-word phrases require 128 bits of entropy")
        return mnemonic(from: entropy)
    }

    private static func secureRandomEntropy() -> Data {
        var bytes = [UInt8](repeating: 0, count: 16)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        precondition(status == errSecSuccess, "Failed to read secure random bytes")
        return Data(bytes)
    }

    private static func mnemonic(from entropy: Data) -> [String] {
        let entropyBits = bits(from: entropy)
        let checksumBitCount = entropy.count * 8 / 32
        let checksumBits = checksumBits(from: entropy, count: checksumBitCount)
        let combinedBits = entropyBits + checksumBits

        var words: [String] = []
        words.reserveCapacity(wordCount)

        for wordIndex in 0 ..< wordCount {
            let start = wordIndex * 11
            var value = 0
            for bitOffset in 0 ..< 11 where combinedBits[start + bitOffset] {
                value |= 1 << (10 - bitOffset)
            }
            words.append(BIP39EnglishWordlist.words[value])
        }

        return words
    }

    private static func bits(from data: Data) -> [Bool] {
        data.flatMap { byte in
            (0 ..< 8).reversed().map { shift in
                (byte >> shift) & 1 == 1
            }
        }
    }

    private static func checksumBits(from entropy: Data, count: Int) -> [Bool] {
        let hash = Data(SHA256.hash(data: entropy))
        return (0 ..< count).map { index in
            (hash[0] >> (7 - index)) & 1 == 1
        }
    }
}
