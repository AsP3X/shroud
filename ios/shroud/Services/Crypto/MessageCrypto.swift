import CryptoKit
import Foundation

/// Message sealing for 1:1 chats.
///
/// - **v1** peer-only sealed box (legacy)
/// - **v2** dual-seal peer + self (no session state)
/// - **v3** Double Ratchet for peer ciphertext + dual-seal self box for multi-device history
///
/// Live messaging uses **v3** by default. Self-box always allows the sender (and their
/// other devices) to open history without ratchet state.
///
/// Human: Plaintext never leaves the device unencrypted; server only sees ciphertext bytes.
/// Agent: Seal/open; DR sessions in Keychain; self dual-seal on every v3 message.
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
        var ek: String?
        var ct: String?
        var peer: SealedBox?
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
        case recipient
        case sender
    }

    private static let versionV1 = 1
    private static let versionV2 = 2
    private static let versionV3 = DoubleRatchet.envelopeVersion

    /// v3 wire: Double Ratchet body + self dual-seal for multi-device history.
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

    // MARK: - Seal

    /// Dual-seal v2 (no ratchet). Kept for tests and explicit fallbacks.
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

    /// Seals a 1:1 message for `peerUserID`.
    ///
    /// **v3 Double Ratchet** when a session exists or we are the deterministic initiator
    /// (`ourUserID < peerUserID`). Otherwise the first outbound message is **v2 dual-seal**
    /// so both sides can message first without poisoning ratchet state (dual-initiator).
    ///
    /// Every v3 message also carries a self dual-seal for multi-device history.
    ///
    /// - Parameter ourUserID: Local account id (for initiator election). Defaults to a
    ///   nil-safe path that only uses DR when a session already exists.
    /// - Parameter useRatchet: When false, always dual-seal v2.
    static func seal(
        plaintext: Data,
        peerUserID: UUID,
        toPeerIdentityPublicKey peerPublic: Data,
        ourPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        ourIdentityPublicKey: Data,
        ourUserID: UUID? = nil,
        useRatchet: Bool = true
    ) throws -> Data {
        guard useRatchet else {
            return try seal(
                plaintext: plaintext,
                toPeerIdentityPublicKey: peerPublic,
                ourIdentityPublicKey: ourIdentityPublicKey
            )
        }

        let existing = RatchetSessionStore.load(peerUserID: peerUserID)
        let mayStartRatchet: Bool = {
            if existing != nil { return true }
            // Deterministic initiator: only the lower UUID creates a brand-new DR session.
            // The higher UUID sends v2 until they have received (session established on open).
            guard let ourUserID else { return true }
            return ourUserID.uuidString.lowercased() < peerUserID.uuidString.lowercased()
        }()

        guard mayStartRatchet else {
            return try seal(
                plaintext: plaintext,
                toPeerIdentityPublicKey: peerPublic,
                ourIdentityPublicKey: ourIdentityPublicKey
            )
        }

        var session = try sessionForEncrypt(
            peerUserID: peerUserID,
            ourPrivateKey: ourPrivateKey,
            peerPublic: peerPublic,
            existing: existing
        )
        let drBody = try DoubleRatchet.encrypt(plaintext: plaintext, session: &session)
        RatchetSessionStore.save(session, peerUserID: peerUserID)

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

    // MARK: - Open

    /// Opens v1/v2 (and v3 self-box for sender). Prefer the peerUserID overload for live chats.
    static func open(
        envelopeData: Data,
        with ourPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        ourIdentityPublicKey: Data,
        senderIdentityPublicKey: Data,
        as role: OpenAs = .recipient
    ) throws -> Data {
        if let version = peekEnvelopeVersion(envelopeData), version == versionV3 {
            guard role == .sender else {
                // Recipient needs peerUserID for DR session store.
                throw CryptoError.unsupportedVersion
            }
            let v3 = try JSONDecoder().decode(RatchetEnvelope.self, from: envelopeData)
            guard let box = v3.selfBox else { throw CryptoError.openFailed }
            return try openBox(
                box,
                with: ourPrivateKey,
                senderIdentityPublic: ourIdentityPublicKey,
                recipientIdentityPublic: ourIdentityPublicKey
            )
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

    /// Opens any supported envelope. For v3 recipient, uses Double Ratchet state for `peerUserID`.
    static func open(
        envelopeData: Data,
        peerUserID: UUID,
        with ourPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        ourIdentityPublicKey: Data,
        senderIdentityPublicKey: Data,
        as role: OpenAs = .recipient
    ) throws -> Data {
        guard let version = peekEnvelopeVersion(envelopeData) else {
            throw CryptoError.openFailed
        }

        if version == versionV3 {
            if role == .sender {
                let v3 = try JSONDecoder().decode(RatchetEnvelope.self, from: envelopeData)
                guard let box = v3.selfBox else { throw CryptoError.openFailed }
                return try openBox(
                    box,
                    with: ourPrivateKey,
                    senderIdentityPublic: ourIdentityPublicKey,
                    recipientIdentityPublic: ourIdentityPublicKey
                )
            }

            // Recipient: try session strategies until one decrypts.
            // Order matters for dual-initiator (both sealed before either opened):
            // unused initiator sessions cannot open the peer's initiator message, so we
            // also try a pure receiver bootstrap. Legitimate replies still hit the
            // stored initiator session first when it works.
            return try openRatchetV3Recipient(
                envelopeData: envelopeData,
                peerUserID: peerUserID,
                ourPrivateKey: ourPrivateKey,
                senderIdentityPublicKey: senderIdentityPublicKey
            )
        }

        return try open(
            envelopeData: envelopeData,
            with: ourPrivateKey,
            ourIdentityPublicKey: ourIdentityPublicKey,
            senderIdentityPublicKey: senderIdentityPublicKey,
            as: role
        )
    }

    // MARK: - Session helpers

    private static func sessionForEncrypt(
        peerUserID: UUID,
        ourPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        peerPublic: Data,
        existing: DoubleRatchet.Session?
    ) throws -> DoubleRatchet.Session {
        if var existing {
            if existing.peerIdentityPublic != peerPublic {
                existing.peerIdentityPublic = peerPublic
            }
            // Missing send chain is promoted inside DoubleRatchet.encrypt (keeps recv state).
            return existing
        }
        return try DoubleRatchet.Session.initiateAsSender(
            ourPrivate: ourPrivateKey,
            theirIdentityPublic: peerPublic
        )
    }

    private static func openRatchetV3Recipient(
        envelopeData: Data,
        peerUserID: UUID,
        ourPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        senderIdentityPublicKey: Data
    ) throws -> Data {
        let v3 = try JSONDecoder().decode(RatchetEnvelope.self, from: envelopeData)
        guard v3.v == versionV3 else { throw CryptoError.unsupportedVersion }

        let drData = try JSONEncoder().encode(
            DoubleRatchet.Message(v: v3.v, dh: v3.dh, n: v3.n, pn: v3.pn, ct: v3.ct)
        )

        // Build candidate sessions (copies) — first success wins and is persisted.
        var candidates: [DoubleRatchet.Session] = []

        if let existing = RatchetSessionStore.load(peerUserID: peerUserID) {
            let unusedInitiator =
                existing.sendChainKey != nil
                && existing.recvChainKey == nil
                && existing.dhRecvPublic == existing.peerIdentityPublic

            if unusedInitiator {
                // Prefer pure receiver first: peer's first message is dual-init style.
                // Fall back to stored initiator for a real reply after they received us.
                candidates.append(
                    try DoubleRatchet.Session.prepareAsReceiver(
                        ourPrivate: ourPrivateKey,
                        theirIdentityPublic: senderIdentityPublicKey
                    )
                )
                candidates.append(existing)
            } else {
                candidates.append(existing)
                candidates.append(
                    try DoubleRatchet.Session.prepareAsReceiver(
                        ourPrivate: ourPrivateKey,
                        theirIdentityPublic: senderIdentityPublicKey
                    )
                )
            }
        } else {
            candidates.append(
                try DoubleRatchet.Session.prepareAsReceiver(
                    ourPrivate: ourPrivateKey,
                    theirIdentityPublic: senderIdentityPublicKey
                )
            )
        }

        var lastError: Error = CryptoError.openFailed
        for var session in candidates {
            do {
                let plain = try DoubleRatchet.decrypt(envelopeData: drData, session: &session)
                RatchetSessionStore.save(session, peerUserID: peerUserID)
                return plain
            } catch {
                lastError = error
            }
        }
        throw lastError
    }

    private static func peekEnvelopeVersion(_ data: Data) -> Int? {
        struct VersionPeek: Decodable { let v: Int }
        return try? JSONDecoder().decode(VersionPeek.self, from: data).v
    }

    // MARK: - Sealed box primitives (v1/v2/self)

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
