import CryptoKit
import Foundation
import Security

/// Persists identity private material in the Keychain (never the raw phrase).
///
/// Human: Identity keys can restore for messaging; the **history key** that opens local chats
/// is **not** stored here in the clear — see `HistoryKeyVault`. The private keys are sealed
/// under that history key, so they are as locked as the chats: the Keychain of an unlocked
/// phone alone does not give them up.
///
/// Agent: Service com.shroud.identity. Private keys (identity, signed prekey, one-time
/// prekeys) are AES-GCM sealed with `LocalHistoryCrypto` context `.identityKeychain`. User id,
/// registration id and signed-prekey id stay plain so the lock screen can tell an identity is
/// here. Items from older builds are plaintext; `load` still reads them and the next `save`
/// (every unlock does one) seals them. Plain `history_key` is deleted on save (migration).
nonisolated struct IdentityKeyStore: Sendable {
    private let service: String

    init(service: String = "com.shroud.identity") {
        self.service = service
    }

    /// Keychain shell without history key (must be supplied from phrase or vault).
    struct StoredIdentity: Sendable {
        let userID: UUID
        let registrationID: UInt32
        let agreementPrivateKey: Curve25519.KeyAgreement.PrivateKey
        let signingPrivateKey: Curve25519.Signing.PrivateKey
        let signedPreKeyID: UInt32
        let signedPreKeyPrivate: Curve25519.KeyAgreement.PrivateKey
        let oneTimePreKeys: [UInt32: Curve25519.KeyAgreement.PrivateKey]
    }

    /// Account the stored identity belongs to. Readable while chats are locked.
    func storedUserID() -> UUID? {
        read(key: Key.userID).flatMap(UUID.init(uuidString:))
    }

    /// Opens the private keys with the history key. Nil while locked, with the wrong key, or
    /// when an item is missing or tampered with.
    func load(historyKey: SymmetricKey) -> StoredIdentity? {
        func opened(_ key: String) -> Data? {
            readData(key: key).flatMap { Self.openPrivate($0, historyKey: historyKey) }
        }
        guard
            let userID = storedUserID(),
            let regString = read(key: Key.registrationID),
            let registrationID = UInt32(regString),
            let agreementData = opened(Key.agreementPrivate),
            let signingData = opened(Key.signingPrivate),
            let spkIDString = read(key: Key.spkID),
            let spkID = UInt32(spkIDString),
            let spkData = opened(Key.spkPrivate)
        else {
            return nil
        }

        guard
            let agreement = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: agreementData),
            let signing = try? Curve25519.Signing.PrivateKey(rawRepresentation: signingData),
            let spk = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: spkData)
        else {
            return nil
        }

        var otpks: [UInt32: Curve25519.KeyAgreement.PrivateKey] = [:]
        if let mapData = opened(Key.otpkMap),
           let dict = try? JSONDecoder().decode([String: Data].self, from: mapData)
        {
            for (idString, raw) in dict {
                guard let id = UInt32(idString),
                      let key = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: raw)
                else { continue }
                otpks[id] = key
            }
        }

        return StoredIdentity(
            userID: userID,
            registrationID: registrationID,
            agreementPrivateKey: agreement,
            signingPrivateKey: signing,
            signedPreKeyID: spkID,
            signedPreKeyPrivate: spk,
            oneTimePreKeys: otpks
        )
    }

    /// Legacy plain history key (pre-vault). Returned once for migration, then should be wiped.
    func loadLegacyPlainHistoryKey() -> SymmetricKey? {
        guard let historyData = readData(key: Key.legacyHistoryKey), historyData.count == 32 else {
            return nil
        }
        return SymmetricKey(data: historyData)
    }

    func clearLegacyPlainHistoryKey() {
        delete(key: Key.legacyHistoryKey)
    }

    /// Writes the identity with every private value sealed under `material.historyKey`.
    /// Rewrites plaintext items an older build left, which is how they are migrated.
    func save(_ material: IdentityKeyMaterial) throws {
        let historyKey = material.historyKey
        func writeSealed(_ key: String, _ value: Data) throws {
            try writeData(key: key, value: Self.sealPrivate(value, historyKey: historyKey))
        }
        try write(key: Key.userID, value: material.userID.uuidString)
        try write(key: Key.registrationID, value: String(material.registrationID))
        try writeSealed(Key.agreementPrivate, material.agreementPrivateKey.rawRepresentation)
        try writeSealed(Key.signingPrivate, material.signingPrivateKey.rawRepresentation)
        try write(key: Key.spkID, value: String(material.signedPreKeyID))
        try writeSealed(Key.spkPrivate, material.signedPreKeyPrivate.rawRepresentation)

        var map: [String: Data] = [:]
        for otpk in material.oneTimePreKeys {
            map[String(otpk.keyID)] = otpk.privateKey.rawRepresentation
        }
        let mapData = try JSONEncoder().encode(map)
        try writeSealed(Key.otpkMap, mapData)

        // Never leave plaintext history key in the identity keychain.
        clearLegacyPlainHistoryKey()
    }

    func clear() {
        for key in Key.all {
            delete(key: key)
        }
        clearLegacyPlainHistoryKey()
    }

    /// True when Keychain holds identity for this user. Does not open the sealed keys, so it
    /// answers while chats are locked.
    func hasIdentity(for userID: UUID) -> Bool {
        storedUserID() == userID && readData(key: Key.agreementPrivate) != nil
    }

    // MARK: - Sealing

    /// Seals one private value for the Keychain.
    static func sealPrivate(_ value: Data, historyKey: SymmetricKey) throws -> Data {
        try LocalHistoryCrypto.seal(value, masterKey: historyKey, context: .identityKeychain)
    }

    /// Opens a Keychain value written by `sealPrivate`. A value without the sealed magic is an
    /// older build's plaintext and is returned as-is so the unlock can re-save it sealed. A
    /// sealed value that does not open is nil — never read as plaintext.
    static func openPrivate(_ stored: Data, historyKey: SymmetricKey) -> Data? {
        guard LocalHistoryCrypto.isSealedBlob(stored) else { return stored }
        return try? LocalHistoryCrypto.open(stored, masterKey: historyKey, context: .identityKeychain)
    }

    // MARK: - Keychain

    private enum Key {
        static let userID = "identity_user_id"
        static let registrationID = "registration_id"
        static let agreementPrivate = "agreement_private"
        static let signingPrivate = "signing_private"
        /// Legacy plaintext history key — migrated into HistoryKeyVault then deleted.
        static let legacyHistoryKey = "history_key"
        static let spkID = "spk_id"
        static let spkPrivate = "spk_private"
        static let otpkMap = "otpk_map"

        static let all = [
            userID, registrationID, agreementPrivate, signingPrivate,
            spkID, spkPrivate, otpkMap,
        ]
    }

    private func read(key: String) -> String? {
        guard let data = readData(key: key) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func readData(key: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess else { return nil }
        return item as? Data
    }

    private func write(key: String, value: String) throws {
        guard let data = value.data(using: .utf8) else {
            throw IdentityKeyStoreError.encodingFailed
        }
        try writeData(key: key, value: data)
    }

    private func writeData(key: String, value: Data) throws {
        delete(key: key)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecValueData as String: value,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        ]
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw IdentityKeyStoreError.keychain(status)
        }
    }

    private func delete(key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
    }
}

enum IdentityKeyStoreError: Error, Equatable {
    case encodingFailed
    case keychain(OSStatus)
}

extension IdentityKeyMaterial {
    init(stored: IdentityKeyStore.StoredIdentity, historyKey: SymmetricKey) {
        let otpks = stored.oneTimePreKeys
            .map { OneTimePreKey(keyID: $0.key, privateKey: $0.value) }
            .sorted { $0.keyID < $1.keyID }
        self.init(
            userID: stored.userID,
            registrationID: stored.registrationID,
            agreementPrivateKey: stored.agreementPrivateKey,
            signingPrivateKey: stored.signingPrivateKey,
            historyKey: historyKey,
            signedPreKeyID: stored.signedPreKeyID,
            signedPreKeyPrivate: stored.signedPreKeyPrivate,
            oneTimePreKeys: otpks
        )
    }
}
