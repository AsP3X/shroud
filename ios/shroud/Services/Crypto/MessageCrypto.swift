import CryptoKit
import Foundation

/// Message sealing for 1:1 chats.
///
/// - **v1** peer-only sealed box (legacy)
/// - **v2** dual-seal peer + self (no session state)
/// - **v3** Double Ratchet for peer ciphertext + identity boxes for multi-device
///
/// Live messaging uses **v3** by default. The self-box lets the sender's other devices
/// open history without ratchet state. The peer-box lets the recipient's other devices
/// (same phrase, different DR session) open a message after a sibling device has
/// ratcheted — otherwise only the device that last sent can decrypt the reply.
///
/// Every identity box carries `t`, a sender tag keyed by the static ECDH of the two identity
/// keys. The box key itself is ECDH(ephemeral, recipient) only, so without the tag anyone
/// holding both public keys — the server included — could seal a box that opens as the peer.
/// Untagged boxes (v1, and builds before the tag) are read under the `SenderTagStore` policy.
///
/// Human: Plaintext never leaves the device unencrypted; server only sees ciphertext bytes.
/// Agent: Seal/open; DR sessions in Keychain; self dual-seal on every v3 message.
enum MessageCrypto {
    /// One sealed box (ephemeral ECDH → AES-GCM) and its sender tag.
    nonisolated struct SealedBox: Codable, Equatable, Sendable {
        /// Ephemeral X25519 public key (standard Base64).
        var ek: String
        /// AES-GCM combined nonce+ciphertext (Base64).
        var ct: String
        /// HMAC-SHA256 over `ek ‖ ct` under the sender↔recipient identity key (Base64).
        /// Nil on boxes from builds before the tag; those builds ignore it when reading.
        var t: String?
    }

    /// Untagged boxes from everyone are refused at or after this server time. Nil until the
    /// builds that cannot tag are gone; see `docs/architecture.md`.
    static let legacyBoxCutoff: Date? = nil

    /// Wire envelope JSON (stored as server ciphertext Base64 outer layer).
    nonisolated struct SealedEnvelope: Codable, Equatable, Sendable {
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
        /// A tag that does not verify, or an untagged box the policy refuses.
        case unauthenticatedSender
    }

    /// Who is opening the envelope.
    enum OpenAs: Sendable {
        case recipient
        case sender
    }

    private static let versionV1 = 1
    private static let versionV2 = 2
    private static let versionV3 = DoubleRatchet.envelopeVersion

    /// v3 wire: Double Ratchet body + identity boxes for each side's other devices.
    nonisolated struct RatchetEnvelope: Codable, Equatable, Sendable {
        var v: Int
        var dh: String
        var n: UInt32
        var pn: UInt32
        var ct: String
        /// Recipient identity box — sibling devices of the peer can open without DR state.
        var peer: SealedBox?
        var selfBox: SealedBox?

        enum CodingKeys: String, CodingKey {
            case v, dh, n, pn, ct, peer
            case selfBox = "self"
        }
    }

    // MARK: - Seal

    /// Dual-seal v2 (no ratchet). Kept for tests and explicit fallbacks.
    static func seal(
        plaintext: Data,
        toPeerIdentityPublicKey peerPublic: Data,
        ourPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        ourIdentityPublicKey: Data
    ) throws -> Data {
        let peerBox = try sealBox(
            plaintext: plaintext,
            senderPrivate: ourPrivateKey,
            senderIdentityPublic: ourIdentityPublicKey,
            recipientIdentityPublic: peerPublic
        )
        let selfBox = try sealBox(
            plaintext: plaintext,
            senderPrivate: ourPrivateKey,
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
                ourPrivateKey: ourPrivateKey,
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
                ourPrivateKey: ourPrivateKey,
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
            senderPrivate: ourPrivateKey,
            senderIdentityPublic: ourIdentityPublicKey,
            recipientIdentityPublic: ourIdentityPublicKey
        )
        let peerBox = try sealBox(
            plaintext: plaintext,
            senderPrivate: ourPrivateKey,
            senderIdentityPublic: ourIdentityPublicKey,
            recipientIdentityPublic: peerPublic
        )
        let drMessage = try JSONDecoder().decode(DoubleRatchet.Message.self, from: drBody)
        let envelope = RatchetEnvelope(
            v: versionV3,
            dh: drMessage.dh,
            n: drMessage.n,
            pn: drMessage.pn,
            ct: drMessage.ct,
            peer: peerBox,
            selfBox: selfBox
        )
        return try JSONEncoder().encode(envelope)
    }

    // MARK: - Open

    /// Opens v1/v2 (and v3 self-box for sender). Prefer the peerUserID overload for live chats.
    ///
    /// - Parameter sentAt: Server time of the message; untagged boxes are judged against the
    ///   sender's `SenderTagStore` watermark by it.
    static func open(
        envelopeData: Data,
        with ourPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        ourIdentityPublicKey: Data,
        senderIdentityPublicKey: Data,
        as role: OpenAs = .recipient,
        sentAt: Date
    ) throws -> Data {
        // Our own boxes are sealed from and to our identity, and tagged under it.
        let boxSender = role == .sender ? ourIdentityPublicKey : senderIdentityPublicKey
        func openIdentity(_ box: SealedBox) throws -> Data {
            try openIdentityBox(
                box,
                with: ourPrivateKey,
                senderIdentityPublic: boxSender,
                recipientIdentityPublic: ourIdentityPublicKey,
                sentAt: sentAt
            )
        }

        if let version = peekEnvelopeVersion(envelopeData), version == versionV3 {
            guard role == .sender else {
                // Recipient needs peerUserID for DR session store.
                throw CryptoError.unsupportedVersion
            }
            let v3 = try JSONDecoder().decode(RatchetEnvelope.self, from: envelopeData)
            guard let box = v3.selfBox else { throw CryptoError.openFailed }
            return try openIdentity(box)
        }

        let envelope = try JSONDecoder().decode(SealedEnvelope.self, from: envelopeData)
        switch envelope.v {
        case versionV1:
            // v1 never carried a tag and only ever went peer-ward.
            guard role == .recipient, let ek = envelope.ek, let ct = envelope.ct else {
                throw CryptoError.openFailed
            }
            return try openIdentity(SealedBox(ek: ek, ct: ct))
        case versionV2:
            let box = role == .recipient ? envelope.peer : envelope.selfBox
            guard let box else { throw CryptoError.openFailed }
            return try openIdentity(box)
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
        as role: OpenAs = .recipient,
        sentAt: Date
    ) throws -> Data {
        guard let version = peekEnvelopeVersion(envelopeData) else {
            throw CryptoError.openFailed
        }

        guard version == versionV3, role == .recipient else {
            return try open(
                envelopeData: envelopeData,
                with: ourPrivateKey,
                ourIdentityPublicKey: ourIdentityPublicKey,
                senderIdentityPublicKey: senderIdentityPublicKey,
                as: role,
                sentAt: sentAt
            )
        }

        let v3 = try JSONDecoder().decode(RatchetEnvelope.self, from: envelopeData)
        // Recipient: DR first (forward secrecy). If this device's session is stale —
        // typically because a sibling device sent and the peer ratcheted to that DH —
        // open the identity peer-box instead of wiping the local session.
        let plain: Data
        do {
            plain = try openRatchetV3Recipient(
                v3,
                peerUserID: peerUserID,
                ourPrivateKey: ourPrivateKey,
                senderIdentityPublicKey: senderIdentityPublicKey
            )
        } catch {
            guard let box = v3.peer else { throw error }
            return try openIdentityBox(
                box,
                with: ourPrivateKey,
                senderIdentityPublic: senderIdentityPublicKey,
                recipientIdentityPublic: ourIdentityPublicKey,
                sentAt: sentAt
            )
        }
        // The ratchet body is authentic on its own. A tag on its peer box still marks the
        // sender as tagging, or the device that reads by ratchet would never learn it.
        // A bad tag next to a good ratchet body proves nothing either way.
        if let box = v3.peer,
           (try? verifyBoxTag(
               box,
               with: ourPrivateKey,
               senderIdentityPublic: senderIdentityPublicKey,
               recipientIdentityPublic: ourIdentityPublicKey
           )) == true
        {
            SenderTagStore.noteTagged(senderIdentityPublic: senderIdentityPublicKey, sentAt: sentAt)
        }
        return plain
    }

    /// Opens a v2 envelope whose box must carry a sender tag that verifies.
    ///
    /// Human: For records that never existed untagged — reactions. Accepting an untagged or v1
    /// box (as history must, for messages from before the tag) would let the server build one
    /// from public keys alone and date it before the sender's watermark.
    /// Agent: No ratchet and no `SenderTagStore` (no Keychain write per record).
    static func openTagged(
        envelopeData: Data,
        with ourPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        ourIdentityPublicKey: Data,
        senderIdentityPublicKey: Data,
        as role: OpenAs
    ) throws -> Data {
        let envelope = try JSONDecoder().decode(SealedEnvelope.self, from: envelopeData)
        guard envelope.v == versionV2 else { throw CryptoError.unsupportedVersion }
        // Our own boxes are sealed from and to our identity.
        let sender = role == .sender ? ourIdentityPublicKey : senderIdentityPublicKey
        guard let box = role == .recipient ? envelope.peer : envelope.selfBox else {
            throw CryptoError.openFailed
        }
        guard try verifyBoxTag(
            box,
            with: ourPrivateKey,
            senderIdentityPublic: sender,
            recipientIdentityPublic: ourIdentityPublicKey
        ) else {
            throw CryptoError.unauthenticatedSender
        }
        return try openBox(
            box,
            with: ourPrivateKey,
            senderIdentityPublic: sender,
            recipientIdentityPublic: ourIdentityPublicKey
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
        _ v3: RatchetEnvelope,
        peerUserID: UUID,
        ourPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        senderIdentityPublicKey: Data
    ) throws -> Data {
        guard v3.v == versionV3 else { throw CryptoError.unsupportedVersion }

        let drData = try JSONEncoder().encode(
            DoubleRatchet.Message(v: v3.v, dh: v3.dh, n: v3.n, pn: v3.pn, ct: v3.ct)
        )

        // Critical: once we have an established receive chain, NEVER fall back to a fresh
        // prepareAsReceiver on failure. That wipe/rebuild desyncs the ratchet after the
        // first successful message and makes the next text/image fail with auth errors.
        if var existing = RatchetSessionStore.load(peerUserID: peerUserID),
           existing.recvChainKey != nil || existing.touched
        {
            let plain = try DoubleRatchet.decrypt(envelopeData: drData, session: &existing)
            RatchetSessionStore.save(existing, peerUserID: peerUserID)
            return plain
        }

        // No established session yet: try unused-initiator recovery, then pure receiver.
        var candidates: [DoubleRatchet.Session] = []
        if let existing = RatchetSessionStore.load(peerUserID: peerUserID) {
            let unusedInitiator =
                existing.sendChainKey != nil
                && existing.recvChainKey == nil
                && existing.dhRecvPublic == existing.peerIdentityPublic
            if unusedInitiator {
                candidates.append(
                    try DoubleRatchet.Session.prepareAsReceiver(
                        ourPrivate: ourPrivateKey,
                        theirIdentityPublic: senderIdentityPublicKey
                    )
                )
                candidates.append(existing)
            } else {
                candidates.append(existing)
            }
        }
        if candidates.isEmpty {
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

    // MARK: - Sender tag policy

    /// Opens an identity box, then applies the untagged-box policy:
    ///
    /// - A tag that verifies moves the sender's watermark back to this message's time.
    /// - An untagged box opens only if the message predates the sender's watermark (history
    ///   from before they upgraded) and `legacyBoxCutoff`. Fails closed while locked.
    private static func openIdentityBox(
        _ box: SealedBox,
        with ourPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        senderIdentityPublic: Data,
        recipientIdentityPublic: Data,
        sentAt: Date
    ) throws -> Data {
        let tagged = try verifyBoxTag(
            box,
            with: ourPrivateKey,
            senderIdentityPublic: senderIdentityPublic,
            recipientIdentityPublic: recipientIdentityPublic
        )
        if !tagged {
            if let legacyBoxCutoff, sentAt >= legacyBoxCutoff {
                throw CryptoError.unauthenticatedSender
            }
            switch SenderTagStore.taggedSince(senderIdentityPublic: senderIdentityPublic) {
            case .untagged:
                break
            case .locked:
                throw CryptoError.unauthenticatedSender
            case let .since(watermark):
                guard sentAt < watermark else { throw CryptoError.unauthenticatedSender }
            }
        }
        let plain = try openBox(
            box,
            with: ourPrivateKey,
            senderIdentityPublic: senderIdentityPublic,
            recipientIdentityPublic: recipientIdentityPublic
        )
        if tagged {
            SenderTagStore.noteTagged(senderIdentityPublic: senderIdentityPublic, sentAt: sentAt)
        }
        return plain
    }

    // MARK: - Sealed box primitives (v1/v2/self)

    private static func sealBox(
        plaintext: Data,
        senderPrivate: Curve25519.KeyAgreement.PrivateKey,
        senderIdentityPublic: Data,
        recipientIdentityPublic: Data
    ) throws -> SealedBox {
        guard let recipientKey = try? Curve25519.KeyAgreement.PublicKey(
            rawRepresentation: recipientIdentityPublic
        ) else {
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
        let ek = ephemeral.publicKey.rawRepresentation
        let tag = try boxTag(
            ourPrivateKey: senderPrivate,
            theirIdentityPublic: recipientIdentityPublic,
            senderIdentityPublic: senderIdentityPublic,
            recipientIdentityPublic: recipientIdentityPublic,
            ek: ek,
            ct: combined
        )

        return SealedBox(
            ek: ek.base64EncodedString(),
            ct: combined.base64EncodedString(),
            t: tag.base64EncodedString()
        )
    }

    /// `true` when the tag proves the sender, `false` for an untagged box. Throws on a tag
    /// that does not verify: a box that carries one is never read without it.
    private static func verifyBoxTag(
        _ box: SealedBox,
        with ourPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        senderIdentityPublic: Data,
        recipientIdentityPublic: Data
    ) throws -> Bool {
        guard let t = box.t else { return false }
        guard let tag = Data(base64Encoded: t),
              let ek = Data(base64Encoded: box.ek),
              let ct = Data(base64Encoded: box.ct)
        else { throw CryptoError.unauthenticatedSender }
        let key = try boxTagKey(
            ourPrivateKey: ourPrivateKey,
            theirIdentityPublic: senderIdentityPublic,
            senderIdentityPublic: senderIdentityPublic,
            recipientIdentityPublic: recipientIdentityPublic
        )
        var message = Data("shroud-box-tag-v1".utf8)
        message.append(ek)
        message.append(ct)
        // Constant-time compare.
        guard HMAC<SHA256>.isValidAuthenticationCode(tag, authenticating: message, using: key) else {
            throw CryptoError.unauthenticatedSender
        }
        return true
    }

    private static func boxTag(
        ourPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        theirIdentityPublic: Data,
        senderIdentityPublic: Data,
        recipientIdentityPublic: Data,
        ek: Data,
        ct: Data
    ) throws -> Data {
        let key = try boxTagKey(
            ourPrivateKey: ourPrivateKey,
            theirIdentityPublic: theirIdentityPublic,
            senderIdentityPublic: senderIdentityPublic,
            recipientIdentityPublic: recipientIdentityPublic
        )
        var message = Data("shroud-box-tag-v1".utf8)
        message.append(ek)
        message.append(ct)
        return Data(HMAC<SHA256>.authenticationCode(for: message, using: key))
    }

    /// ECDH(sender identity, recipient identity) is the same from either end. The ordered
    /// public keys in the info make the A→B key differ from B→A, so a box cannot be reflected.
    private static func boxTagKey(
        ourPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        theirIdentityPublic: Data,
        senderIdentityPublic: Data,
        recipientIdentityPublic: Data
    ) throws -> SymmetricKey {
        guard let theirKey = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: theirIdentityPublic)
        else { throw CryptoError.invalidPeerKey }
        let staticShared = try ourPrivateKey.sharedSecretFromKeyAgreement(with: theirKey)
        var info = Data("shroud-box-auth-v1".utf8)
        info.append(senderIdentityPublic)
        info.append(recipientIdentityPublic)
        return staticShared.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data("shroud-box-auth-v1".utf8),
            sharedInfo: info,
            outputByteCount: 32
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
