import CryptoKit
import Foundation

/// Real CryptoKit message sealing for 1:1 envelopes (ECDH + AES-GCM).
/// Human: Plaintext never leaves the device unencrypted; server only sees ciphertext bytes.
/// Agent: ECDH(ephemeral, peer identity) → HKDF → AES-GCM; no Double Ratchet yet.
enum MessageCrypto {
    /// Wire envelope (JSON inside the server's base64 ciphertext field, or raw binary packing).
    struct SealedEnvelope: Codable, Equatable, Sendable {
        /// Version tag for future ratchet upgrades.
        var v: Int
        /// Ephemeral X25519 public key (32 bytes, standard Base64).
        var ek: String
        /// AES-GCM combined nonce+ciphertext (CryptoKit sealed box combined, Base64).
        var ct: String
    }

    enum CryptoError: Error, Equatable {
        case invalidPeerKey
        case sealingFailed
        case openFailed
        case unsupportedVersion
    }

    private static let version = 1

    /// Encrypts UTF-8 plaintext to a peer's identity public key (X25519 raw 32 bytes).
    static func seal(
        plaintext: Data,
        toPeerIdentityPublicKey peerPublic: Data,
        ourIdentityPublicKey: Data
    ) throws -> Data {
        guard let peerKey = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerPublic) else {
            throw CryptoError.invalidPeerKey
        }

        let ephemeral = Curve25519.KeyAgreement.PrivateKey()
        let shared = try ephemeral.sharedSecretFromKeyAgreement(with: peerKey)
        let symmetric = deriveMessageKey(
            sharedSecret: shared,
            ephemeralPublic: ephemeral.publicKey.rawRepresentation,
            senderIdentityPublic: ourIdentityPublicKey,
            recipientIdentityPublic: peerPublic
        )

        let sealed = try AES.GCM.seal(plaintext, using: symmetric)
        guard let combined = sealed.combined else {
            throw CryptoError.sealingFailed
        }

        let envelope = SealedEnvelope(
            v: version,
            ek: ephemeral.publicKey.rawRepresentation.base64EncodedString(),
            ct: combined.base64EncodedString()
        )
        return try JSONEncoder().encode(envelope)
    }

    /// Decrypts an envelope produced by `seal` using our identity private key.
    static func open(
        envelopeData: Data,
        with ourPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        ourIdentityPublicKey: Data,
        senderIdentityPublicKey: Data
    ) throws -> Data {
        let envelope = try JSONDecoder().decode(SealedEnvelope.self, from: envelopeData)
        guard envelope.v == version else {
            throw CryptoError.unsupportedVersion
        }
        guard let ekData = Data(base64Encoded: envelope.ek),
              let ctData = Data(base64Encoded: envelope.ct),
              let ephemeralPublic = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: ekData)
        else {
            throw CryptoError.openFailed
        }

        let shared = try ourPrivateKey.sharedSecretFromKeyAgreement(with: ephemeralPublic)
        let symmetric = deriveMessageKey(
            sharedSecret: shared,
            ephemeralPublic: ekData,
            senderIdentityPublic: senderIdentityPublicKey,
            recipientIdentityPublic: ourIdentityPublicKey
        )

        let box = try AES.GCM.SealedBox(combined: ctData)
        return try AES.GCM.open(box, using: symmetric)
    }

    private static func deriveMessageKey(
        sharedSecret: SharedSecret,
        ephemeralPublic: Data,
        senderIdentityPublic: Data,
        recipientIdentityPublic: Data
    ) -> SymmetricKey {
        var info = Data("shroud-msg-v1".utf8)
        info.append(ephemeralPublic)
        info.append(senderIdentityPublic)
        info.append(recipientIdentityPublic)

        return sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data("shroud-v1".utf8),
            sharedInfo: info,
            outputByteCount: 32
        )
    }
}
