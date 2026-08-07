import CommonCrypto
import CryptoKit
import Foundation

/// BIP39 seed derivation and 12-word mnemonic validation.
/// Human: Phrase → seed stays on-device; never logs or transmits the phrase.
/// Agent: PBKDF2-HMAC-SHA512 (BIP39); validates wordlist + checksum.
nonisolated enum BIP39Seed {
    static let seedLength = 64
    private static let iterations: UInt32 = 2048
    private static let saltPrefix = "mnemonic"

    enum SeedError: Error, Equatable {
        case invalidWordCount
        case unknownWord(String)
        case invalidChecksum
        case derivationFailed
    }

    /// Normalizes and validates a 12-word phrase; returns lowercased words.
    static func validateMnemonic(_ words: [String]) throws -> [String] {
        let normalized = words
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
            .filter { !$0.isEmpty }

        guard normalized.count == EncryptionPhraseGenerator.wordCount else {
            throw SeedError.invalidWordCount
        }

        var indices: [Int] = []
        indices.reserveCapacity(12)
        for word in normalized {
            guard let index = BIP39EnglishWordlist.index(of: word) else {
                throw SeedError.unknownWord(word)
            }
            indices.append(index)
        }

        // 12 words × 11 bits = 132 bits → 128 entropy + 4 checksum.
        var bits: [Bool] = []
        bits.reserveCapacity(132)
        for index in indices {
            for shift in (0 ..< 11).reversed() {
                bits.append((index >> shift) & 1 == 1)
            }
        }

        var entropy = Data(count: 16)
        for byteIndex in 0 ..< 16 {
            var value: UInt8 = 0
            for bit in 0 ..< 8 {
                if bits[byteIndex * 8 + bit] {
                    value |= 1 << (7 - bit)
                }
            }
            entropy[byteIndex] = value
        }

        let checksumBits = Array(bits[128 ..< 132])
        let hash = Data(SHA256.hash(data: entropy))
        let expected: [Bool] = (0 ..< 4).map { bit in
            (hash[0] >> (7 - bit)) & 1 == 1
        }
        guard checksumBits == expected else {
            throw SeedError.invalidChecksum
        }

        return normalized
    }

    /// BIP39 seed from validated mnemonic words (optional empty passphrase).
    static func seed(fromMnemonic words: [String], passphrase: String = "") throws -> Data {
        let validated = try validateMnemonic(words)
        let mnemonic = validated.joined(separator: " ")
        // BIP39: NFKD normalization
        let password = mnemonic.precomposedStringWithCompatibilityMapping
        let salt = (saltPrefix + passphrase).precomposedStringWithCompatibilityMapping
        return try pbkdf2SHA512(
            password: Data(password.utf8),
            salt: Data(salt.utf8),
            iterations: iterations,
            keyLength: seedLength
        )
    }

    private static func pbkdf2SHA512(
        password: Data,
        salt: Data,
        iterations: UInt32,
        keyLength: Int
    ) throws -> Data {
        var derived = Data(count: keyLength)
        let status = derived.withUnsafeMutableBytes { derivedBytes in
            password.withUnsafeBytes { passwordBytes in
                salt.withUnsafeBytes { saltBytes in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        passwordBytes.bindMemory(to: Int8.self).baseAddress,
                        password.count,
                        saltBytes.bindMemory(to: UInt8.self).baseAddress,
                        salt.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA512),
                        iterations,
                        derivedBytes.bindMemory(to: UInt8.self).baseAddress,
                        keyLength
                    )
                }
            }
        }
        guard status == kCCSuccess else {
            throw SeedError.derivationFailed
        }
        return derived
    }
}

nonisolated extension BIP39EnglishWordlist {
    private static let wordToIndex: [String: Int] = {
        var map: [String: Int] = [:]
        map.reserveCapacity(words.count)
        for (index, word) in words.enumerated() {
            map[word] = index
        }
        return map
    }()

    static func index(of word: String) -> Int? {
        wordToIndex[word]
    }

    static func contains(_ word: String) -> Bool {
        wordToIndex[word] != nil
    }
}
