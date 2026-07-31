import CryptoKit
import Foundation

/// Real CryptoKit message sealing for 1:1 envelopes (ECDH + AES-GCM).
/// Human: Plaintext never leaves the device unencrypted; server only sees ciphertext bytes.
/// Agent: v2 dual-seals to peer + self so sender devices can decrypt history; v1 peer-only still openable.
enum MessageCrypto {
    /// One sealed box (ephemeral ECDH → AES-GCM).
    struct SealedBox: Codable, Equatable, Sendable {
        /// Ephemeral X25519 public key (standard Base64).
        var ek: String
        /// AES-GCM combined nonce+ciphertext (Base64).
        var ct: String
    }

    /// Wire envelope JSON (stored as server ciphertext Base64 outer layer).
    struct SealedEnvelope: Codable, Equatable, Sendable {
        /// 1 = peer-only (legacy); 2 = peer + self dual seal.
        var v: Int
        /// v1 fields (also used when decoding legacy).
        var ek: String?
        var ct: String?
        /// v2: sealed to recipient identity.
        var peer: SealedBox?
        /// v2: sealed to sender identity (multi-device / own history).
        var selfBox: SealedBox?

        enum CodingKeys: String, CodingKey {
            case v, ek, ct, peer
            case selfBox = "self"
        }
    }

    enum CryptoError: Error, Equatable {
        case invalidPeerKey
        case sealingFailed
        case openFailed
        case unsupportedVersion
    }

    /// Who is opening the envelope.
    enum OpenAs: Sendable {
        /// Recipient decrypts the peer-directed box.
        case recipient
        /// Sender (or another of sender's devices) decrypts the self box.
        case sender
    }

    private static let versionV1 = 1
    private static let versionV2 = 2
    private static let versionV3 = DoubleRatchet.envelopeVersion

    /// v3 wire: Double Ratchet body + optional self dual-seal for multi-device history.
    struct RatchetEnvelope: Codable, Equatable, Sendable {
        var v: Int
        var dh: String
        var n: UInt32
        var pn: UInt32
        var ct: String
        var selfBox: SealedBox?

        enum CodingKeys: String, CodingKey {
            case v, dh, n, pn, ct
            case selfBox = "self"
        }
    }

    /// Encrypts plaintext for the peer **and** a copy for the sender (v2 dual-seal).
    /// Prefer ``seal(plaintext:peerUserID:...)`` for Double Ratchet (v3) when a session exists or can be bootstrapped.
    static func seal(
        plaintext: Data,
        toPeerIdentityPublicKey peerPublic: Data,
        ourIdentityPublicKey: Data
    ) throws -> Data {
        let peerBox = try sealBox(
            plaintext: plaintext,
            recipientPublic: peerPublic,
            senderIdentityPublic: ourIdentityPublicKey,
            recipientIdentityPublic: peerPublic
        )
        let selfBox = try sealBox(
            plaintext: plaintext,
            recipientPublic: ourIdentityPublicKey,
            senderIdentityPublic: ourIdentityPublicKey,
            recipientIdentityPublic: ourIdentityPublicKey
        )
        let envelope = SealedEnvelope(
            v: versionV2,
            ek: nil,
            ct: nil,
            peer: peerBox,
            selfBox: selfBox
        )
        return try JSONEncoder().encode(envelope)
    }

    /// Seals with Double Ratchet (v3) when possible; falls back to v2 dual-seal.
    ///
    /// - Parameters:
    ///   - peerUserID: Session store key (ratchet state is per peer).
    ///   - useRatchet: When false, always use classic sealed send (v2).
    static func seal(
        plaintext: Data,
        peerUserID: UUID,
        toPeerIdentityPublicKey peerPublic: Data,
        ourPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        ourIdentityPublicKey: Data,
        useRatchet: Bool = true
    ) throws -> Data {
        guard useRatchet else {
            return try seal(
                plaintext: plaintext,
                toPeerIdentityPublicKey: peerPublic,
                ourIdentityPublicKey: ourIdentityPublicKey
            )
        }

        var session = try loadOrCreateInitiatorSession(
            peerUserID: peerUserID,
            ourPrivateKey: ourPrivateKey,
            peerPublic: peerPublic
        )
        let drBody = try DoubleRatchet.encrypt(plaintext: plaintext, session: &session)
        RatchetSessionStore.save(session, peerUserID: peerUserID)

        // Attach self dual-seal so other own devices can open history without the ratchet state.
        let selfBox = try sealBox(
            plaintext: plaintext,
            recipientPublic: ourIdentityPublicKey,
            senderIdentityPublic: ourIdentityPublicKey,
            recipientIdentityPublic: ourIdentityPublicKey
        )
        let drMessage = try JSONDecoder().decode(DoubleRatchet.Message.self, from: drBody)
        let envelope = RatchetEnvelope(
            v: versionV3,
            dh: drMessage.dh,
            n: drMessage.n,
            pn: drMessage.pn,
            ct: drMessage.ct,
            selfBox: selfBox
        )
        return try JSONEncoder().encode(envelope)
    }

    /// Decrypts an envelope. Use `.recipient` for incoming, `.sender` for own messages.
    static func open(
        envelopeData: Data,
        with ourPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        ourIdentityPublicKey: Data,
        senderIdentityPublicKey: Data,
        as role: OpenAs = .recipient
    ) throws -> Data {
        // Peek version without failing on unknown shapes.
        if let v3 = try? JSONDecoder().decode(RatchetEnvelope.self, from: envelopeData),
           v3.v == versionV3
        {
            switch role {
            case .sender:
                guard let box = v3.selfBox else { throw CryptoError.openFailed }
                return try openBox(
                    box,
                    with: ourPrivateKey,
                    senderIdentityPublic: ourIdentityPublicKey,
                    recipientIdentityPublic: ourIdentityPublicKey
                )
            case .recipient:
                // Requires peerUserID path — use openRatchet for full DR.
                throw CryptoError.unsupportedVersion
            }
        }

        let envelope = try JSONDecoder().decode(SealedEnvelope.self, from: envelopeData)

        switch envelope.v {
        case versionV1:
            guard let ek = envelope.ek, let ct = envelope.ct else {
                throw CryptoError.openFailed
            }
            return try openBox(
                SealedBox(ek: ek, ct: ct),
                with: ourPrivateKey,
                senderIdentityPublic: senderIdentityPublicKey,
                recipientIdentityPublic: ourIdentityPublicKey
            )

        case versionV2:
            switch role {
            case .recipient:
                guard let box = envelope.peer else { throw CryptoError.openFailed }
                return try openBox(
                    box,
                    with: ourPrivateKey,
                    senderIdentityPublic: senderIdentityPublicKey,
                    recipientIdentityPublic: ourIdentityPublicKey
                )
            case .sender:
                guard let box = envelope.selfBox else { throw CryptoError.openFailed }
                return try openBox(
                    box,
                    with: ourPrivateKey,
                    senderIdentityPublic: ourIdentityPublicKey,
                    recipientIdentityPublic: ourIdentityPublicKey
                )
            }

        default:
            throw CryptoError.unsupportedVersion
        }
    }

    /// Opens any supported envelope; for v3 uses Double Ratchet state for `peerUserID`.
    static func open(
        envelopeData: Data,
        peerUserID: UUID,
        with ourPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        ourIdentityPublicKey: Data,
        senderIdentityPublicKey: Data,
        as role: OpenAs = .recipient
    ) throws -> Data {
        if let v3 = try? JSONDecoder().decode(RatchetEnvelope.self, from: envelopeData),
           v3.v == versionV3
        {
            if role == .sender, let box = v3.selfBox {
                return try openBox(
                    box,
                    with: ourPrivateKey,
                    senderIdentityPublic: ourIdentityPublicKey,
                    recipientIdentityPublic: ourIdentityPublicKey
                )
            }

            var session = try loadOrCreateResponderSession(
                peerUserID: peerUserID,
                ourPrivateKey: ourPrivateKey,
                senderPublic: senderIdentityPublicKey
            )
            let drData = try JSONEncoder().encode(
                DoubleRatchet.Message(v: v3.v, dh: v3.dh, n: v3.n, pn: v3.pn, ct: v3.ct)
            )
            let plain = try DoubleRatchet.decrypt(envelopeData: drData, session: &session)
            RatchetSessionStore.save(session, peerUserID: peerUserID)
            return plain
        }

        return try open(
            envelopeData: envelopeData,
            with: ourPrivateKey,
            ourIdentityPublicKey: ourIdentityPublicKey,
            senderIdentityPublicKey: senderIdentityPublicKey,
            as: role
        )
    }

    private static func loadOrCreateInitiatorSession(
        peerUserID: UUID,
        ourPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        peerPublic: Data
    ) throws -> DoubleRatchet.Session {
        if let existing = RatchetSessionStore.load(peerUserID: peerUserID) {
            return existing
        }
        let shared = try DoubleRatchet.identitySharedSecret(
            ourPrivate: ourPrivateKey,
            theirPublic: peerPublic
        )
        let session = try DoubleRatchet.Session.bootstrapInitiator(
            sharedSecret: shared,
            theirRatchetPublic: peerPublic
        )
        RatchetSessionStore.save(session, peerUserID: peerUserID)
        return session
    }

    private static func loadOrCreateResponderSession(
        peerUserID: UUID,
        ourPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        senderPublic: Data
    ) throws -> DoubleRatchet.Session {
        if let existing = RatchetSessionStore.load(peerUserID: peerUserID) {
            return existing
        }
        let shared = try DoubleRatchet.identitySharedSecret(
            ourPrivate: ourPrivateKey,
            theirPublic: senderPublic
        )
        let session = DoubleRatchet.Session.bootstrapResponder(
            sharedSecret: shared,
            ourRatchetPrivate: ourPrivateKey
        )
        RatchetSessionStore.save(session, peerUserID: peerUserID)
        return session
    }

    // MARK: - Internals

    private static func sealBox(
        plaintext: Data,
        recipientPublic: Data,
        senderIdentityPublic: Data,
        recipientIdentityPublic: Data
    ) throws -> SealedBox {
        guard let recipientKey = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: recipientPublic)
        else {
            throw CryptoError.invalidPeerKey
        }

        let ephemeral = Curve25519.KeyAgreement.PrivateKey()
        let shared = try ephemeral.sharedSecretFromKeyAgreement(with: recipientKey)
        let symmetric = deriveMessageKey(
            sharedSecret: shared,
            ephemeralPublic: ephemeral.publicKey.rawRepresentation,
            senderIdentityPublic: senderIdentityPublic,
            recipientIdentityPublic: recipientIdentityPublic
        )

        let sealed = try AES.GCM.seal(plaintext, using: symmetric)
        guard let combined = sealed.combined else {
            throw CryptoError.sealingFailed
        }

        return SealedBox(
            ek: ephemeral.publicKey.rawRepresentation.base64EncodedString(),
            ct: combined.base64EncodedString()
        )
    }

    private static func openBox(
        _ box: SealedBox,
        with ourPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        senderIdentityPublic: Data,
        recipientIdentityPublic: Data
    ) throws -> Data {
        guard let ekData = Data(base64Encoded: box.ek),
              let ctData = Data(base64Encoded: box.ct),
              let ephemeralPublic = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: ekData)
        else {
            throw CryptoError.openFailed
        }

        let shared = try ourPrivateKey.sharedSecretFromKeyAgreement(with: ephemeralPublic)
        let symmetric = deriveMessageKey(
            sharedSecret: shared,
            ephemeralPublic: ekData,
            senderIdentityPublic: senderIdentityPublic,
            recipientIdentityPublic: recipientIdentityPublic
        )

        let sealed = try AES.GCM.SealedBox(combined: ctData)
        return try AES.GCM.open(sealed, using: symmetric)
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
