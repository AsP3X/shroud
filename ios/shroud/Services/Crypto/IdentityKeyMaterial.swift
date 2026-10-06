import CryptoKit
import Foundation
import Security

/// On-device identity material derived from the encryption phrase + device pre-keys.
/// Human: Private keys never leave the device; only public bundle fields go to the API.
/// Agent: Deterministic identity from BIP39 seed; random SPK/OTPK for upload.
nonisolated struct IdentityKeyMaterial: Sendable {
    let userID: UUID
    let registrationID: UInt32
    let agreementPrivateKey: Curve25519.KeyAgreement.PrivateKey
    let signingPrivateKey: Curve25519.Signing.PrivateKey
    /// AES-256 key for local history / backup wrapping (not uploaded).
    let historyKey: SymmetricKey
    let signedPreKeyID: UInt32
    let signedPreKeyPrivate: Curve25519.KeyAgreement.PrivateKey
    let oneTimePreKeys: [OneTimePreKey]

    struct OneTimePreKey: Sendable {
        let keyID: UInt32
        let privateKey: Curve25519.KeyAgreement.PrivateKey
    }

    var identityPublicKeyData: Data {
        agreementPrivateKey.publicKey.rawRepresentation
    }

    var signingPublicKeyData: Data {
        signingPrivateKey.publicKey.rawRepresentation
    }

    var signedPreKeyPublicData: Data {
        signedPreKeyPrivate.publicKey.rawRepresentation
    }

    /// Signature over the signed pre-key public bytes (Ed25519).
    func signedPreKeySignature() throws -> Data {
        try signingPrivateKey.signature(for: signedPreKeyPublicData)
    }

    /// Builds identity from a BIP39 phrase for `userID`, generating a fresh SPK/OTPK pool.
    static func establish(
        mnemonicWords: [String],
        userID: UUID,
        oneTimePreKeyCount: Int = 100
    ) throws -> IdentityKeyMaterial {
        let seed = try BIP39Seed.seed(fromMnemonic: mnemonicWords)
        return try derive(seed: seed, userID: userID, oneTimePreKeyCount: oneTimePreKeyCount)
    }

    /// Re-derives identity keys from phrase and attaches newly generated pre-keys (new device).
    static func reestablish(
        mnemonicWords: [String],
        userID: UUID,
        oneTimePreKeyCount: Int = 100
    ) throws -> IdentityKeyMaterial {
        try establish(
            mnemonicWords: mnemonicWords,
            userID: userID,
            oneTimePreKeyCount: oneTimePreKeyCount
        )
    }

    private static func derive(
        seed: Data,
        userID: UUID,
        oneTimePreKeyCount: Int
    ) throws -> IdentityKeyMaterial {
        let agreementSeed = hkdf(seed: seed, info: "shroud-identity-x25519", length: 32)
        let signingSeed = hkdf(seed: seed, info: "shroud-identity-ed25519", length: 32)
        let historyRaw = hkdf(seed: seed, info: "shroud-history-aes", length: 32)
        let regRaw = hkdf(seed: seed, info: "shroud-registration-id", length: 4)

        let agreement = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: agreementSeed)
        let signing = try Curve25519.Signing.PrivateKey(rawRepresentation: signingSeed)
        let historyKey = SymmetricKey(data: historyRaw)

        let regBytes = [UInt8](regRaw)
        let registrationID =
            (UInt32(regBytes[0]) << 24
                | UInt32(regBytes[1]) << 16
                | UInt32(regBytes[2]) << 8
                | UInt32(regBytes[3])) % 16_384

        let spkPrivate = Curve25519.KeyAgreement.PrivateKey()
        // key_id in 1…0xFFFFFF
        let spkID = (secureRandomUInt32() % 0xFF_FFFF) + 1

        var otpks: [OneTimePreKey] = []
        otpks.reserveCapacity(oneTimePreKeyCount)
        for index in 0 ..< oneTimePreKeyCount {
            let keyID = UInt32(index + 1)
            otpks.append(OneTimePreKey(keyID: keyID, privateKey: Curve25519.KeyAgreement.PrivateKey()))
        }

        return IdentityKeyMaterial(
            userID: userID,
            registrationID: registrationID,
            agreementPrivateKey: agreement,
            signingPrivateKey: signing,
            historyKey: historyKey,
            signedPreKeyID: spkID,
            signedPreKeyPrivate: spkPrivate,
            oneTimePreKeys: otpks
        )
    }

    /// Returns true when `words` derive the same identity public key as this material.
    func matchesMnemonic(_ words: [String]) -> Bool {
        (try? Self.identityPublicKeyData(fromMnemonic: words)) == identityPublicKeyData
    }

    /// The X25519 identity public key `words` derive — what the server publishes as the
    /// account's `identity_key`. Throws `BIP39Seed.SeedError` for words that aren't a phrase.
    static func identityPublicKeyData(fromMnemonic words: [String]) throws -> Data {
        let seed = try BIP39Seed.seed(fromMnemonic: words)
        let agreementSeed = hkdf(seed: seed, info: "shroud-identity-x25519", length: 32)
        let agreement = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: agreementSeed)
        return agreement.publicKey.rawRepresentation
    }

    /// The history key `words` derive, which opens the account's sealed device names. For a
    /// login that has no keys on this device yet. Throws `BIP39Seed.SeedError` like the above.
    static func historyKey(fromMnemonic words: [String]) throws -> SymmetricKey {
        let seed = try BIP39Seed.seed(fromMnemonic: words)
        return SymmetricKey(data: hkdf(seed: seed, info: "shroud-history-aes", length: 32))
    }

    static func hkdf(seed: Data, info: String, length: Int) -> Data {
        let input = SymmetricKey(data: seed)
        let derived = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: input,
            salt: Data("shroud-v1".utf8),
            info: Data(info.utf8),
            outputByteCount: length
        )
        return derived.withUnsafeBytes { Data($0) }
    }

    private static func secureRandomUInt32() -> UInt32 {
        var value: UInt32 = 0
        let status = SecRandomCopyBytes(kSecRandomDefault, MemoryLayout<UInt32>.size, &value)
        precondition(status == errSecSuccess)
        return value
    }
}
