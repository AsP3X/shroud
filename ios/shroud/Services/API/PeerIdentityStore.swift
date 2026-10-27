import Foundation
import Security

/// Caches peer identity public keys so we don't re-fetch (and consume OTPKs) every send.
///
/// First-seen keys are TOFU-trusted. Later mismatches are *not* overwritten here — the caller
/// records a `PeerIdentityChange` and waits for an explicit user accept.
///
/// Human: The pins are `WhenUnlockedThisDeviceOnly`, so while the phone is locked the Keychain
/// refuses to read them. That is never "no pin": `pin(for:)` says `.unavailable`, and the caller
/// must then neither trust a key the server sends nor open or seal anything with one (security
/// audit M8; Android `PeerIdentityStore.PinRead.Unavailable`).
struct PeerIdentityStore: Sendable {
    /// What the Keychain says about one peer's pin.
    nonisolated enum PinRead: Equatable, Sendable {
        /// The pinned key.
        case pinned(Data)
        /// Nothing pinned (or a pin that is not Base64): trust on first use applies.
        case notPinned
        /// The pin may exist but cannot be read now (phone locked, Keychain error): decide nothing.
        case unavailable

        var key: Data? {
            if case let .pinned(key) = self { return key }
            return nil
        }
    }

    nonisolated enum KeychainRead: Equatable, Sendable {
        case found(String)
        case notFound
        case unavailable
    }

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

    func pin(for userID: UUID) -> PinRead {
        let account = account(for: userID)
        switch readKeychain(account: account) {
        case let .found(value):
            return Self.pinRead(value)
        case .unavailable:
            return .unavailable
        case .notFound:
            break
        }
        // Migrate legacy UserDefaults cache into Keychain once. The plaintext copy goes only
        // once the Keychain holds it.
        let legacyKey = defaultsPrefix + account
        if let legacy = defaults.string(forKey: legacyKey), !legacy.isEmpty {
            if (try? writeKeychain(account: account, value: legacy)) != nil {
                defaults.removeObject(forKey: legacyKey)
            }
            return Self.pinRead(legacy)
        }
        return .notPinned
    }

    func save(userID: UUID, publicKeyBase64: String) {
        let account = account(for: userID)
        try? writeKeychain(account: account, value: publicKeyBase64)
        defaults.removeObject(forKey: defaultsPrefix + account)
    }

    /// The safety number for this contact has been compared. A new key clears it. False while
    /// the flag cannot be read.
    func isVerified(_ userID: UUID) -> Bool {
        readKeychain(account: verifiedAccount(for: userID)) == .found("1")
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

    private static func pinRead(_ base64: String) -> PinRead {
        guard let key = Data(base64Encoded: base64) else { return .notPinned }
        return .pinned(key)
    }

    private func account(for userID: UUID) -> String {
        userID.uuidString.lowercased()
    }

    private func verifiedAccount(for userID: UUID) -> String {
        "verified." + account(for: userID)
    }

    private func readKeychain(account: String) -> KeychainRead {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        return Self.keychainRead(status: status, data: item as? Data)
    }

    /// `.notFound` only when the Keychain says the item is not there. Every other status,
    /// `errSecInteractionNotAllowed` while the phone is locked among them, is `.unavailable`, and
    /// so is an item that exists but whose data can't be read as text: that is no proof of "no pin".
    static func keychainRead(status: OSStatus, data: Data?) -> KeychainRead {
        switch status {
        case errSecSuccess:
            guard let data, let value = String(data: data, encoding: .utf8) else { return .unavailable }
            return .found(value)
        case errSecItemNotFound:
            return .notFound
        default:
            return .unavailable
        }
    }

    /// Updates the item in place, or adds it when there is none. Never deletes first: a delete
    /// that goes through and an add the locked phone refuses would leave no pin at all.
    private func writeKeychain(account: String, value: String) throws {
        guard let data = value.data(using: .utf8) else { return }
        let match: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let update: [String: Any] = [kSecValueData as String: data]
        var status = SecItemUpdate(match as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            var add = match
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            status = SecItemAdd(add as CFDictionary, nil)
        }
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
