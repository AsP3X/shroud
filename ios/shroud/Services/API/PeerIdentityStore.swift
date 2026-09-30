import Foundation
import Security

/// Caches peer identity public keys so we don't re-fetch (and consume OTPKs) every send.
///
/// First-seen keys are TOFU-trusted. Later mismatches are *not* overwritten here — the caller
/// records a `PeerIdentityChange` and waits for an explicit user accept.
struct PeerIdentityStore: Sendable {
    private let defaults: UserDefaults
    private let service: String
    private let defaultsPrefix = "peer_identity_pub."

    init(
        service: String = "com.shroud.peer-identity",
        defaults: UserDefaults = .standard
    ) {
        self.service = service
        self.defaults = defaults
    }

    func publicKeyBase64(for userID: UUID) -> String? {
        let account = account(for: userID)
        if let fromKeychain = readKeychain(account: account) {
            return fromKeychain
        }
        // Migrate legacy UserDefaults cache into Keychain once.
        let legacyKey = defaultsPrefix + account
        if let legacy = defaults.string(forKey: legacyKey), !legacy.isEmpty {
            try? writeKeychain(account: account, value: legacy)
            defaults.removeObject(forKey: legacyKey)
            return legacy
        }
        return nil
    }

    func publicKeyData(for userID: UUID) -> Data? {
        guard let b64 = publicKeyBase64(for: userID) else { return nil }
        return Data(base64Encoded: b64)
    }

    func save(userID: UUID, publicKeyBase64: String) {
        let account = account(for: userID)
        try? writeKeychain(account: account, value: publicKeyBase64)
        defaults.removeObject(forKey: defaultsPrefix + account)
    }

    /// The safety number for this contact has been compared. A new key clears it.
    func isVerified(_ userID: UUID) -> Bool {
        readKeychain(account: verifiedAccount(for: userID)) == "1"
    }

    func setVerified(_ userID: UUID, _ verified: Bool) {
        let account = verifiedAccount(for: userID)
        if verified {
            try? writeKeychain(account: account, value: "1")
        } else {
            deleteKeychain(account: account)
        }
    }

    func clear() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ]
        SecItemDelete(query as CFDictionary)
        let keys = defaults.dictionaryRepresentation().keys.filter { $0.hasPrefix(defaultsPrefix) }
        for key in keys {
            defaults.removeObject(forKey: key)
        }
    }

    private func account(for userID: UUID) -> String {
        userID.uuidString.lowercased()
    }

    private func verifiedAccount(for userID: UUID) -> String {
        "verified." + account(for: userID)
    }

    private func readKeychain(account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func writeKeychain(account: String, value: String) throws {
        deleteKeychain(account: account)
        guard let data = value.data(using: .utf8) else { return }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        ]
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw IdentityKeyStoreError.keychain(status)
        }
    }

    private func deleteKeychain(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
