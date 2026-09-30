import CryptoKit
import Foundation

/// Seals a call's signals (SDP, ICE candidates) between the two people in it.
///
/// Human: The server relays signals it cannot read or change. Were the SDP readable and
/// writable, the server could swap in its own DTLS fingerprint and sit in the middle of the
/// call. The key comes from both identity keys, so only the two people can seal or open.
/// Agent: Wire format and test vector in docs/calls.md; must match web/src/calls/crypto.ts.
nonisolated enum CallCrypto {
    enum Role: String, Sendable {
        case caller
        case callee

        var other: Role { self == .caller ? .callee : .caller }
    }

    enum CryptoError: Error, Equatable {
        case badPayload
        case openFailed
    }

    /// The pair's call secret: the same from either side and on each of their devices.
    ///
    /// `HKDF-SHA256(X25519(ours, theirs), salt "shroud-call-v1",
    /// info "shroud-call-secret-v1" ‖ lower key ‖ higher key)`.
    static func callSecret(
        ourPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        ourPublicKey: Data,
        peerPublicKey: Data
    ) throws -> SymmetricKey {
        let peer = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerPublicKey)
        let shared = try ourPrivateKey.sharedSecretFromKeyAgreement(with: peer)
        let (low, high) = ourPublicKey.lexicographicallyPrecedes(peerPublicKey)
            ? (ourPublicKey, peerPublicKey)
            : (peerPublicKey, ourPublicKey)
        return shared.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data("shroud-call-v1".utf8),
            sharedInfo: Data("shroud-call-secret-v1".utf8) + low + high,
            outputByteCount: 32
        )
    }

    /// The key `role` seals its signals with in call `callID`.
    static func signalKey(secret: SymmetricKey, callID: UUID, role: Role) -> SymmetricKey {
        directionKey(secret: secret, callID: callID, role: role, info: "shroud-call-signal-v1")
    }

    /// The per-call secret for signals after the offer and answer.
    ///
    /// `HKDF-SHA256(X25519(our ephemeral, theirs), salt: the identity call secret,
    /// info: "shroud-call-fs-v1" ‖ call id ‖ lower ephemeral public ‖ higher)`.
    /// The identity secret salts it, so a swapped ephemeral key does not mix in.
    static func forwardSecret(
        identitySecret: SymmetricKey,
        ourEphemeralPrivate: Curve25519.KeyAgreement.PrivateKey,
        ourEphemeralPublic: Data,
        peerEphemeralPublic: Data,
        callID: UUID
    ) throws -> SymmetricKey {
        let peer = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerEphemeralPublic)
        let shared = try ourEphemeralPrivate.sharedSecretFromKeyAgreement(with: peer)
        let (low, high) = ourEphemeralPublic.lexicographicallyPrecedes(peerEphemeralPublic)
            ? (ourEphemeralPublic, peerEphemeralPublic)
            : (peerEphemeralPublic, ourEphemeralPublic)
        let salt = identitySecret.withUnsafeBytes { Data($0) }
        return shared.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: salt,
            sharedInfo: Data("shroud-call-fs-v1".utf8) + uuidBytes(callID) + low + high,
            outputByteCount: 32
        )
    }

    /// The key `role` seals its post-setup signals with.
    static func forwardSignalKey(secret: SymmetricKey, callID: UUID, role: Role) -> SymmetricKey {
        directionKey(secret: secret, callID: callID, role: role, info: "shroud-call-fs-signal-v1")
    }

    private static func directionKey(
        secret: SymmetricKey,
        callID: UUID,
        role: Role,
        info: String
    ) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(
            inputKeyMaterial: secret,
            salt: uuidBytes(callID),
            info: Data("\(info)|\(role.rawValue)".utf8),
            outputByteCount: 32
        )
    }

    /// `"c1." + base64(nonce ‖ ciphertext ‖ tag)`, bound to the call and the signal type.
    static func seal(
        _ plaintext: Data,
        key: SymmetricKey,
        callID: UUID,
        signalType: String,
        nonce: AES.GCM.Nonce = AES.GCM.Nonce()
    ) throws -> String {
        let box = try AES.GCM.seal(
            plaintext,
            using: key,
            nonce: nonce,
            authenticating: aad(callID: callID, signalType: signalType)
        )
        guard let combined = box.combined else { throw CryptoError.badPayload }
        return "c1." + combined.base64EncodedString()
    }

    static func open(
        _ payload: String,
        key: SymmetricKey,
        callID: UUID,
        signalType: String
    ) throws -> Data {
        guard payload.hasPrefix("c1."),
              let combined = Data(base64Encoded: String(payload.dropFirst(3))),
              combined.count >= 12 + 16
        else { throw CryptoError.badPayload }
        do {
            let box = try AES.GCM.SealedBox(combined: combined)
            return try AES.GCM.open(box, using: key, authenticating: aad(callID: callID, signalType: signalType))
        } catch {
            throw CryptoError.openFailed
        }
    }

    static func aad(callID: UUID, signalType: String) -> Data {
        Data("shroud-call-v1|\(callID.uuidString.lowercased())|\(signalType)".utf8)
    }

    /// RFC 4122 byte order: the hex digits of the string form, in order.
    static func uuidBytes(_ id: UUID) -> Data {
        withUnsafeBytes(of: id.uuid) { Data($0) }
    }
}

/// Both keys of one call: what this device seals with, and what it opens the other side's with.
nonisolated struct CallSignalKeys: Sendable {
    let send: SymmetricKey
    let receive: SymmetricKey

    init(secret: SymmetricKey, callID: UUID, role: CallCrypto.Role) {
        send = CallCrypto.signalKey(secret: secret, callID: callID, role: role)
        receive = CallCrypto.signalKey(secret: secret, callID: callID, role: role.other)
    }

    /// Post-setup directions. `secret` is the forward secret, not the identity call secret.
    init(forwardSecret secret: SymmetricKey, callID: UUID, role: CallCrypto.Role) {
        send = CallCrypto.forwardSignalKey(secret: secret, callID: callID, role: role)
        receive = CallCrypto.forwardSignalKey(secret: secret, callID: callID, role: role.other)
    }
}
