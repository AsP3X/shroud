import CryptoKit
import Foundation
import Security

/// Keychain-backed Double Ratchet sessions keyed by peer user id.
/// Human: Ratchet state never leaves the device, and is sealed under the history key, so it
/// is as locked as the chats — reading the Keychain of an unlocked phone does not give it up.
/// Agent: Service = bundle id + ".dr-sessions"; one item per peer. Values are session JSON
/// AES-GCM sealed with `LocalHistoryCrypto` context `.ratchetKeychain`, keyed from
/// `SealedLocalState.historyKey`. While locked, `load` returns nil and `save` drops the write.
/// Plaintext items from older builds are re-sealed on unlock (`sealPlaintextSessions`).
nonisolated enum RatchetSessionStore {
    private static var service: String {
        (Bundle.main.bundleIdentifier ?? "de.corespace.shroud") + ".dr-sessions"
    }

    static func load(peerUserID: UUID) -> DoubleRatchet.Session? {
        guard let historyKey = SealedLocalState.historyKey,
              let stored = readItem(account: account(for: peerUserID)),
              let opened = openStored(stored, historyKey: historyKey)
        else { return nil }
        if opened.wasPlaintext {
            save(opened.session, peerUserID: peerUserID)
        }
        return opened.session
    }

    /// Drops the write while locked. Never stores the session in the clear: a lost ratchet
    /// step costs at most a skipped message key, a plaintext one leaks the chain.
    static func save(_ session: DoubleRatchet.Session, peerUserID: UUID) {
        guard let historyKey = SealedLocalState.historyKey,
              let sealed = try? seal(session, historyKey: historyKey)
        else { return }
        writeItem(account: account(for: peerUserID), value: sealed)
    }

    static func delete(peerUserID: UUID) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account(for: peerUserID),
        ]
        SecItemDelete(query as CFDictionary)
    }

    static func deleteAll() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ]
        SecItemDelete(query as CFDictionary)
    }

    /// Re-seals every session an older build stored as plaintext JSON. Runs on each unlock, so
    /// a peer who never messages again does not keep a readable ratchet in the Keychain.
    static func sealPlaintextSessions(historyKey: SymmetricKey) {
        for account in allAccounts() {
            guard let stored = readItem(account: account),
                  !LocalHistoryCrypto.isSealedBlob(stored),
                  let opened = openStored(stored, historyKey: historyKey),
                  let sealed = try? seal(opened.session, historyKey: historyKey)
            else { continue }
            writeItem(account: account, value: sealed)
        }
    }

    // MARK: - Sealing

    static func seal(_ session: DoubleRatchet.Session, historyKey: SymmetricKey) throws -> Data {
        let json = try JSONEncoder().encode(session)
        return try LocalHistoryCrypto.seal(json, masterKey: historyKey, context: .ratchetKeychain)
    }

    /// Opens a stored value. Unsealed JSON is an older build's item (`wasPlaintext`) and is
    /// accepted only so it can be re-sealed; a sealed value that does not open is nil.
    static func openStored(
        _ stored: Data,
        historyKey: SymmetricKey
    ) -> (session: DoubleRatchet.Session, wasPlaintext: Bool)? {
        let isSealed = LocalHistoryCrypto.isSealedBlob(stored)
        let json: Data
        if isSealed {
            guard let opened = try? LocalHistoryCrypto.open(
                stored,
                masterKey: historyKey,
                context: .ratchetKeychain
            ) else { return nil }
            json = opened
        } else {
            json = stored
        }
        guard let session = try? JSONDecoder().decode(DoubleRatchet.Session.self, from: json) else {
            return nil
        }
        return (session, !isSealed)
    }

    // MARK: - Keychain

    private static func account(for peerUserID: UUID) -> String {
        peerUserID.uuidString.lowercased()
    }

    private static func readItem(account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess else { return nil }
        return item as? Data
    }

    private static func writeItem(account: String, value: Data) {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData as String] = value
        add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    /// Accounts only — data is read one item at a time, which every Keychain accepts.
    private static func allAccounts() -> [String] {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return [] }
        return ((result as? [[String: Any]]) ?? []).compactMap { $0[kSecAttrAccount as String] as? String }
    }
}
