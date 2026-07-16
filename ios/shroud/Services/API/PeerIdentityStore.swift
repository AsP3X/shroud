import Foundation

/// Caches peer identity public keys (Base64) so we don't re-fetch (and consume OTPKs) every send.
struct PeerIdentityStore: Sendable {
    private let defaults: UserDefaults
    private let prefix = "peer_identity_pub."

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func publicKeyBase64(for userID: UUID) -> String? {
        defaults.string(forKey: prefix + userID.uuidString.lowercased())
    }

    func publicKeyData(for userID: UUID) -> Data? {
        guard let b64 = publicKeyBase64(for: userID) else { return nil }
        return Data(base64Encoded: b64)
    }

    func save(userID: UUID, publicKeyBase64: String) {
        defaults.set(publicKeyBase64, forKey: prefix + userID.uuidString.lowercased())
    }

    func clear() {
        let keys = defaults.dictionaryRepresentation().keys.filter { $0.hasPrefix(prefix) }
        for key in keys {
            defaults.removeObject(forKey: key)
        }
    }
}
