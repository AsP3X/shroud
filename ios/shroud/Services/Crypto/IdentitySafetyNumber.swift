import CryptoKit
import Foundation

/// Stable, order-independent fingerprint of two X25519 identity public keys.
///
/// Human: Compare this number in person (or via a second channel) with a contact.
/// Agent: SHA-256 of the sorted raw public keys; never logs key bytes.
enum IdentitySafetyNumber {
    /// 12 groups of 5 digits, identical regardless of which key is "ours".
    static func displayString(localIdentity: Data, peerIdentity: Data) -> String {
        let first: Data
        let second: Data
        if localIdentity.lexicographicallyPrecedes(peerIdentity) {
            first = localIdentity
            second = peerIdentity
        } else {
            first = peerIdentity
            second = localIdentity
        }
        var material = Data()
        material.append(first)
        material.append(second)
        let digest = SHA256.hash(data: material)
        let bytes = Array(digest)
        var groups: [String] = []
        groups.reserveCapacity(12)
        // 30 bytes → 10 groups; wrap with the remaining 2 bytes so the full hash is used.
        for i in 0..<12 {
            let a = UInt32(bytes[(i * 2) % bytes.count])
            let b = UInt32(bytes[(i * 2 + 1) % bytes.count])
            let c = UInt32(bytes[(i * 3) % bytes.count])
            let value = (a << 16) | (b << 8) | c
            groups.append(String(format: "%05d", value % 100_000))
        }
        return groups.joined(separator: " ")
    }
}

/// A contact's identity public key no longer matches the first-seen (TOFU) key.
enum PeerIdentityError: Error, Equatable {
    case changed
}

/// Recorded when a later fetch disagrees with the cached identity key.
struct PeerIdentityChange: Equatable, Sendable {
    let previousKey: Data
    let currentKey: Data
}
