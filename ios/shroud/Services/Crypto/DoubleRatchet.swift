import CryptoKit
import Foundation

/// Signal-style Double Ratchet (X25519 + HKDF-SHA256 + AES-GCM).
///
/// Human: Forward secrecy for ongoing chats after a sealed-send bootstrap.
/// Agent: Envelope v3 carries DH public + counters + ciphertext; sessions stored per peer.
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

    /// Wire header + body for v3 envelopes.
    struct Message: Codable, Equatable, Sendable {
        var v: Int
        /// Sender's current ratchet public key (Base64).
        var dh: String
        /// Message number in the sending chain.
        var n: UInt32
        /// Length of previous sending chain.
        var pn: UInt32
        /// AES-GCM combined nonce||ciphertext||tag (Base64).
        var ct: String
    }

    /// Persistent session state for one peer direction pair.
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
        /// Skipped message keys: "dhB64:n" → key bytes.
        var skipped: [String: Data]

        static func bootstrapInitiator(
            sharedSecret: SharedSecret,
            theirRatchetPublic: Data
        ) throws -> Session {
            let root = rootFromShared(sharedSecret)
            let dh = Curve25519.KeyAgreement.PrivateKey()
            let theirPub = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: theirRatchetPublic)
            let dhOut = try dh.sharedSecretFromKeyAgreement(with: theirPub)
            let (newRoot, sendChain) = kdfRK(root: root, dhOut: dhOut)
            return Session(
                rootKey: newRoot,
                sendChainKey: sendChain,
                recvChainKey: nil,
                sendN: 0,
                recvN: 0,
                prevChainLength: 0,
                dhSendPrivate: dh.rawRepresentation,
                dhSendPublic: dh.publicKey.rawRepresentation,
                dhRecvPublic: theirRatchetPublic,
                skipped: [:]
            )
        }

        static func bootstrapResponder(
            sharedSecret: SharedSecret,
            ourRatchetPrivate: Curve25519.KeyAgreement.PrivateKey
        ) -> Session {
            let root = rootFromShared(sharedSecret)
            return Session(
                rootKey: root,
                sendChainKey: nil,
                recvChainKey: nil,
                sendN: 0,
                recvN: 0,
                prevChainLength: 0,
                dhSendPrivate: ourRatchetPrivate.rawRepresentation,
                dhSendPublic: ourRatchetPrivate.publicKey.rawRepresentation,
                dhRecvPublic: nil,
                skipped: [:]
            )
        }
    }

    // MARK: - Seal / open

    static func encrypt(plaintext: Data, session: inout Session) throws -> Data {
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

        return try openAES(ctData, key: msgKey)
    }

    // MARK: - Ratchet steps

    private static func dhRatchet(remotePublic: Data, session: inout Session) throws {
        session.prevChainLength = session.sendN
        session.sendN = 0
        session.recvN = 0
        session.dhRecvPublic = remotePublic

        let remote = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: remotePublic)
        guard let sendPrivData = session.dhSendPrivate else { throw RatchetError.noSession }
        let sendPriv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: sendPrivData)

        // Receiving chain from remote DH.
        let dh1 = try sendPriv.sharedSecretFromKeyAgreement(with: remote)
        let (root1, recvChain) = kdfRK(root: session.rootKey, dhOut: dh1)
        session.rootKey = root1
        session.recvChainKey = recvChain

        // New sending DH key pair.
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
        let distance = Int(target - session.recvN)
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

        // Bound skipped map size.
        if session.skipped.count > maxSkip {
            let drop = session.skipped.count - maxSkip
            for key in session.skipped.keys.prefix(drop) {
                session.skipped.removeValue(forKey: key)
            }
        }
    }

    // MARK: - KDF

    private static func rootFromShared(_ secret: SharedSecret) -> Data {
        let key = secret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data("shroud-dr-root-v1".utf8),
            sharedInfo: Data("shroud-double-ratchet".utf8),
            outputByteCount: 32
        )
        return key.withUnsafeBytes { Data($0) }
    }

    private static func kdfRK(root: Data, dhOut: SharedSecret) -> (Data, Data) {
        let ikm = dhOut.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: root,
            sharedInfo: Data("shroud-dr-rk".utf8),
            outputByteCount: 64
        )
        let bytes = ikm.withUnsafeBytes { Data($0) }
        return (bytes.prefix(32), bytes.suffix(32))
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

    /// Shared secret from identity ECDH (bootstrap for first DR session).
    static func identitySharedSecret(
        ourPrivate: Curve25519.KeyAgreement.PrivateKey,
        theirPublic: Data
    ) throws -> SharedSecret {
        let pub = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: theirPublic)
        return try ourPrivate.sharedSecretFromKeyAgreement(with: pub)
    }
}

// Data prefix/suffix returning Data
private extension Data {
    func prefix(_ count: Int) -> Data { Data(self[..<Swift.min(count, self.count)]) }
    func suffix(_ count: Int) -> Data {
        let start = Swift.max(0, self.count - count)
        return Data(self[start...])
    }
}
