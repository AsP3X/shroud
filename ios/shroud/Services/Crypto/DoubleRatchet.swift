import CryptoKit
import Foundation

/// Signal-style Double Ratchet (X25519 + HKDF-SHA256 + AES-GCM) for 1:1 sessions.
///
/// ## Session bootstrap (identity X3DH-lite)
/// Both sides derive the same root seed from static identity ECDH:
/// `RK0 = HKDF(ECDH(IKa, IKb))`.
///
/// - **Sender (first message, no session):** generate ratchet key pair `DHs`,
///   `(RK, CKs) = KDF_RK(RK0, DH(DHs, IKb))`, encrypt with `CKs`. Header carries `DHs.public`.
/// - **Receiver (first message, no session):** use identity as temporary `DHs` private,
///   on header `DHr` run a DH ratchet: `DH(IKb, DHr)` matches the sender’s `DH(DHs, IKb)`.
///
/// ## Dual-initiator recovery
/// If both peers send before either decrypts, each has an initiator session and the
/// first decrypt fails. Caller should wipe the session and retry as a fresh responder
/// (see `MessageCrypto.open`). Self dual-seal on the wire still opens own history.
///
/// Human: Forward secrecy for ongoing chats; identity keys only leave as public material.
/// Agent: Envelope v3; Keychain session per peer; never logs keys or plaintext.
enum DoubleRatchet {
    static let envelopeVersion = 3
    /// Max skipped message keys retained per session (out-of-order delivery).
    static let maxSkip = 64

    enum RatchetError: Error, Equatable {
        case invalidKey
        case decryptFailed
        case skippedTooFar
        case noSession
    }

    /// Wire header + body for v3 envelopes (peer-directed ratchet ciphertext).
    struct Message: Codable, Equatable, Sendable {
        var v: Int
        /// Sender's current ratchet public key (standard Base64).
        var dh: String
        /// Message number in the sending chain.
        var n: UInt32
        /// Length of previous sending chain.
        var pn: UInt32
        /// AES-GCM combined nonce||ciphertext||tag (Base64).
        var ct: String
    }

    /// Persistent session state for one peer.
    struct Session: Codable, Equatable, Sendable {
        var rootKey: Data
        var sendChainKey: Data?
        var recvChainKey: Data?
        var sendN: UInt32
        var recvN: UInt32
        var prevChainLength: UInt32
        var dhSendPrivate: Data?
        var dhSendPublic: Data?
        var dhRecvPublic: Data?
        /// Peer identity public (X25519) used for RK0 / first-message remote.
        var peerIdentityPublic: Data
        /// True after at least one successful encrypt or decrypt with this peer.
        var touched: Bool
        /// Skipped message keys: "dhB64:n" → key bytes.
        var skipped: [String: Data]

        // MARK: Bootstrap

        /// Shared root seed — identical for both peers given the same identity pair.
        static func rootSeed(
            ourPrivate: Curve25519.KeyAgreement.PrivateKey,
            theirIdentityPublic: Data
        ) throws -> Data {
            let theirPub = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: theirIdentityPublic)
            let shared = try ourPrivate.sharedSecretFromKeyAgreement(with: theirPub)
            // Domain-separated HKDF; salt binds both public keys in sorted order for symmetry.
            let ourPub = ourPrivate.publicKey.rawRepresentation
            let salt = sortedConcat(ourPub, theirIdentityPublic)
            let key = shared.hkdfDerivedSymmetricKey(
                using: SHA256.self,
                salt: salt,
                sharedInfo: Data("shroud-dr-root-v3".utf8),
                outputByteCount: 32
            )
            return key.withUnsafeBytes { Data($0) }
        }

        /// Local user is about to send the first message (or re-init after wipe).
        static func initiateAsSender(
            ourPrivate: Curve25519.KeyAgreement.PrivateKey,
            theirIdentityPublic: Data
        ) throws -> Session {
            let rk0 = try rootSeed(ourPrivate: ourPrivate, theirIdentityPublic: theirIdentityPublic)
            let dh = Curve25519.KeyAgreement.PrivateKey()
            let theirPub = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: theirIdentityPublic)
            let dhOut = try dh.sharedSecretFromKeyAgreement(with: theirPub)
            let (root, sendChain) = kdfRK(root: rk0, dhOut: dhOut)
            return Session(
                rootKey: root,
                sendChainKey: sendChain,
                recvChainKey: nil,
                sendN: 0,
                recvN: 0,
                prevChainLength: 0,
                dhSendPrivate: dh.rawRepresentation,
                dhSendPublic: dh.publicKey.rawRepresentation,
                // Until the peer ratchets, treat their identity as the remote DH public.
                dhRecvPublic: theirIdentityPublic,
                peerIdentityPublic: theirIdentityPublic,
                touched: true,
                skipped: [:]
            )
        }

        /// Local user is about to decrypt the first message from a peer (no prior session).
        /// Receiving DH private starts as our identity; first remote header triggers DH ratchet.
        static func prepareAsReceiver(
            ourPrivate: Curve25519.KeyAgreement.PrivateKey,
            theirIdentityPublic: Data
        ) throws -> Session {
            let rk0 = try rootSeed(ourPrivate: ourPrivate, theirIdentityPublic: theirIdentityPublic)
            return Session(
                rootKey: rk0,
                sendChainKey: nil,
                recvChainKey: nil,
                sendN: 0,
                recvN: 0,
                prevChainLength: 0,
                // Identity is the private that matches sender's DH(eph, our_identity).
                dhSendPrivate: ourPrivate.rawRepresentation,
                dhSendPublic: ourPrivate.publicKey.rawRepresentation,
                dhRecvPublic: nil,
                peerIdentityPublic: theirIdentityPublic,
                touched: false,
                skipped: [:]
            )
        }
    }

    // MARK: - Encrypt / decrypt

    static func encrypt(plaintext: Data, session: inout Session) throws -> Data {
        // If we only ever received, DH ratchet already installed a send chain.
        // If somehow send chain is missing, re-initiate against peer identity.
        if session.sendChainKey == nil {
            // Promote: create a fresh sending ratchet from current root + new DH.
            let peer = session.peerIdentityPublic
            let dh = Curve25519.KeyAgreement.PrivateKey()
            let remote = try Curve25519.KeyAgreement.PublicKey(
                rawRepresentation: session.dhRecvPublic ?? peer
            )
            let dhOut = try dh.sharedSecretFromKeyAgreement(with: remote)
            let (root, sendChain) = kdfRK(root: session.rootKey, dhOut: dhOut)
            session.rootKey = root
            session.sendChainKey = sendChain
            session.dhSendPrivate = dh.rawRepresentation
            session.dhSendPublic = dh.publicKey.rawRepresentation
            session.prevChainLength = session.sendN
            session.sendN = 0
        }

        guard var chain = session.sendChainKey,
              let dhPub = session.dhSendPublic
        else { throw RatchetError.noSession }

        let (nextChain, msgKey) = kdfCK(chainKey: chain)
        chain = nextChain
        session.sendChainKey = chain

        let sealed = try AES.GCM.seal(plaintext, using: SymmetricKey(data: msgKey))
        guard let combined = sealed.combined else { throw RatchetError.decryptFailed }

        let message = Message(
            v: envelopeVersion,
            dh: dhPub.base64EncodedString(),
            n: session.sendN,
            pn: session.prevChainLength,
            ct: combined.base64EncodedString()
        )
        session.sendN += 1
        session.touched = true
        return try JSONEncoder().encode(message)
    }

    static func decrypt(envelopeData: Data, session: inout Session) throws -> Data {
        let message = try JSONDecoder().decode(Message.self, from: envelopeData)
        guard message.v == envelopeVersion else { throw RatchetError.decryptFailed }
        guard let dhData = Data(base64Encoded: message.dh),
              let ctData = Data(base64Encoded: message.ct)
        else { throw RatchetError.invalidKey }

        let skipKey = "\(message.dh):\(message.n)"
        if let skipped = session.skipped.removeValue(forKey: skipKey) {
            return try openAES(ctData, key: skipped)
        }

        // New remote ratchet public → DH ratchet (and skip remaining keys of old chain).
        if session.dhRecvPublic != dhData {
            try skipMessageKeys(until: message.pn, session: &session)
            try dhRatchet(remotePublic: dhData, session: &session)
        }
        try skipMessageKeys(until: message.n, session: &session)

        guard var chain = session.recvChainKey else { throw RatchetError.noSession }
        let (nextChain, msgKey) = kdfCK(chainKey: chain)
        chain = nextChain
        session.recvChainKey = chain
        session.recvN += 1
        session.touched = true

        return try openAES(ctData, key: msgKey)
    }

    // MARK: - DH ratchet

    private static func dhRatchet(remotePublic: Data, session: inout Session) throws {
        session.prevChainLength = session.sendN
        session.sendN = 0
        session.recvN = 0
        session.dhRecvPublic = remotePublic

        let remote = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: remotePublic)
        guard let sendPrivData = session.dhSendPrivate else { throw RatchetError.noSession }
        let sendPriv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: sendPrivData)

        // Receiving chain from DH(our_current_send, their_new_public).
        let dh1 = try sendPriv.sharedSecretFromKeyAgreement(with: remote)
        let (root1, recvChain) = kdfRK(root: session.rootKey, dhOut: dh1)
        session.rootKey = root1
        session.recvChainKey = recvChain

        // New sending key pair for replies.
        let newDH = Curve25519.KeyAgreement.PrivateKey()
        session.dhSendPrivate = newDH.rawRepresentation
        session.dhSendPublic = newDH.publicKey.rawRepresentation
        let dh2 = try newDH.sharedSecretFromKeyAgreement(with: remote)
        let (root2, sendChain) = kdfRK(root: session.rootKey, dhOut: dh2)
        session.rootKey = root2
        session.sendChainKey = sendChain
    }

    private static func skipMessageKeys(until target: UInt32, session: inout Session) throws {
        guard var chain = session.recvChainKey else { return }
        if target < session.recvN { return }
        let distance = Int(target &- session.recvN)
        guard distance <= maxSkip else { throw RatchetError.skippedTooFar }

        while session.recvN < target {
            let (next, msgKey) = kdfCK(chainKey: chain)
            chain = next
            if let dh = session.dhRecvPublic {
                let key = "\(dh.base64EncodedString()):\(session.recvN)"
                session.skipped[key] = msgKey
            }
            session.recvN += 1
        }
        session.recvChainKey = chain

        if session.skipped.count > maxSkip {
            let drop = session.skipped.count - maxSkip
            for key in session.skipped.keys.prefix(drop) {
                session.skipped.removeValue(forKey: key)
            }
        }
    }

    // MARK: - KDF

    private static func kdfRK(root: Data, dhOut: SharedSecret) -> (Data, Data) {
        let ikm = dhOut.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: root,
            sharedInfo: Data("shroud-dr-rk-v3".utf8),
            outputByteCount: 64
        )
        let bytes = ikm.withUnsafeBytes { Data($0) }
        return (Data(bytes.prefix(32)), Data(bytes.suffix(32)))
    }

    private static func kdfCK(chainKey: Data) -> (Data, Data) {
        let next = HMAC<SHA256>.authenticationCode(for: Data([0x01]), using: SymmetricKey(data: chainKey))
        let msg = HMAC<SHA256>.authenticationCode(for: Data([0x02]), using: SymmetricKey(data: chainKey))
        return (Data(next), Data(msg))
    }

    private static func openAES(_ combined: Data, key: Data) throws -> Data {
        let box = try AES.GCM.SealedBox(combined: combined)
        return try AES.GCM.open(box, using: SymmetricKey(data: key))
    }

    private static func sortedConcat(_ a: Data, _ b: Data) -> Data {
        if a.lexicographicallyPrecedes(b) {
            return a + b
        }
        if b.lexicographicallyPrecedes(a) {
            return b + a
        }
        return a + b
    }
}
