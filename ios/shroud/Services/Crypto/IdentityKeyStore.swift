import CryptoKit
import Foundation
import Security

/// Persists identity private material in the Keychain (never the raw phrase).
/// Human: Survives restarts so cold start can unlock without re-entering the phrase.
/// Agent: READS/WRITES Keychain service com.shroud.identity; clears on logout only when requested.
nonisolated struct IdentityKeyStore: Sendable {
    private let service: String

    init(service: String = "com.shroud.identity") {
        self.service = service
    }

    struct StoredIdentity: Sendable {
        let userID: UUID
        let registrationID: UInt32
        let agreementPrivateKey: Curve25519.KeyAgreement.PrivateKey
        let signingPrivateKey: Curve25519.Signing.PrivateKey
        let historyKey: SymmetricKey
        let signedPreKeyID: UInt32
        let signedPreKeyPrivate: Curve25519.KeyAgreement.PrivateKey
        /// OTPK private keys keyed by key_id (stringified in storage).
        let oneTimePreKeys: [UInt32: Curve25519.KeyAgreement.PrivateKey]
    }

    func load() -> StoredIdentity? {
        guard
            let userIDString = read(key: Key.userID),
            let userID = UUID(uuidString: userIDString),
            let regString = read(key: Key.registrationID),
            let registrationID = UInt32(regString),
            let agreementData = readData(key: Key.agreementPrivate),
            let signingData = readData(key: Key.signingPrivate),
            let historyData = readData(key: Key.historyKey),
            let spkIDString = read(key: Key.spkID),
            let spkID = UInt32(spkIDString),
            let spkData = readData(key: Key.spkPrivate)
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
        if let mapData = readData(key: Key.otpkMap),
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
            historyKey: SymmetricKey(data: historyData),
            signedPreKeyID: spkID,
            signedPreKeyPrivate: spk,
            oneTimePreKeys: otpks
        )
    }

    func save(_ material: IdentityKeyMaterial) throws {
        try write(key: Key.userID, value: material.userID.uuidString)
        try write(key: Key.registrationID, value: String(material.registrationID))
        try writeData(key: Key.agreementPrivate, value: material.agreementPrivateKey.rawRepresentation)
        try writeData(key: Key.signingPrivate, value: material.signingPrivateKey.rawRepresentation)
        let historyData = material.historyKey.withUnsafeBytes { Data($0) }
        try writeData(key: Key.historyKey, value: historyData)
        try write(key: Key.spkID, value: String(material.signedPreKeyID))
        try writeData(key: Key.spkPrivate, value: material.signedPreKeyPrivate.rawRepresentation)

        var map: [String: Data] = [:]
        for otpk in material.oneTimePreKeys {
            map[String(otpk.keyID)] = otpk.privateKey.rawRepresentation
        }
        let mapData = try JSONEncoder().encode(map)
        try writeData(key: Key.otpkMap, value: mapData)
    }

    func clear() {
        for key in Key.all {
            delete(key: key)
        }
    }

    /// True when Keychain holds identity for this user.
    func hasIdentity(for userID: UUID) -> Bool {
        load()?.userID == userID
    }

    // MARK: - Keychain

    private enum Key {
        static let userID = "identity_user_id"
        static let registrationID = "registration_id"
        static let agreementPrivate = "agreement_private"
        static let signingPrivate = "signing_private"
        static let historyKey = "history_key"
        static let spkID = "spk_id"
        static let spkPrivate = "spk_private"
        static let otpkMap = "otpk_map"

        static let all = [
            userID, registrationID, agreementPrivate, signingPrivate,
            historyKey, spkID, spkPrivate, otpkMap,
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
        // ThisDeviceOnly: never leaves the device via backup restore.
        // WhenUnlocked: unavailable while the device is locked (seized locked device
        // cannot use Keychain-held history/identity keys to open sealed chat files).
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
    init(stored: IdentityKeyStore.StoredIdentity) {
        let otpks = stored.oneTimePreKeys
            .map { OneTimePreKey(keyID: $0.key, privateKey: $0.value) }
            .sorted { $0.keyID < $1.keyID }
        self.init(
            userID: stored.userID,
            registrationID: stored.registrationID,
            agreementPrivateKey: stored.agreementPrivateKey,
            signingPrivateKey: stored.signingPrivateKey,
            historyKey: stored.historyKey,
            signedPreKeyID: stored.signedPreKeyID,
            signedPreKeyPrivate: stored.signedPreKeyPrivate,
            oneTimePreKeys: otpks
        )
    }
}
