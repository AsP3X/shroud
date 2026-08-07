import CryptoKit
import Foundation
import LocalAuthentication
import Security

/// Stores the phrase-derived **history key** wrapped so disk/Keychain dumps alone cannot open chats.
///
/// Human: Opening local history needs Face ID / Touch ID / device passcode (or the 12-word phrase).
/// Agent: Random wrap key in Keychain with `.userPresence` when possible; AES-GCM sealed history
/// blob. Never stores raw `historyKey` bytes. Tests set `requiresUserPresence = false`.
enum HistoryKeyVault {
    /// When false (unit tests / degraded envs), wrap key is stored without biometry ACL.
    nonisolated(unsafe) static var requiresUserPresence: Bool = true

    private static let service = "com.shroud.history-vault"
    private static let wrapAccount = "history_wrap_key_v1"
    private static let blobAccount = "history_key_blob_v1"
    private static let userAccount = "history_vault_user"

    enum VaultError: Error, Equatable {
        case sealFailed
        case openFailed
        case keychain(OSStatus)
        case userCancelled
        case notFound
    }

    /// Seals `historyKey` under a device wrap key (biometry/passcode gated when available).
    /// - Parameter rotateWrapKey: When true, discards the old wrap key so ACL prefs apply.
    static func store(
        historyKey: SymmetricKey,
        userID: UUID,
        rotateWrapKey: Bool = false
    ) throws {
        if rotateWrapKey {
            delete(account: wrapAccount)
        }
        let wrapKey = try loadOrCreateWrapKey()
        let plain = historyKey.withUnsafeBytes { Data($0) }
        let sealed = try AES.GCM.seal(plain, using: wrapKey)
        guard let combined = sealed.combined else { throw VaultError.sealFailed }
        try writeData(account: blobAccount, value: combined, protectWithPresence: false)
        try writeData(
            account: userAccount,
            value: Data(userID.uuidString.utf8),
            protectWithPresence: false
        )
    }

    /// Unwraps the history key (may prompt Face ID / Touch ID / passcode).
    static func unlock(userID: UUID) throws -> SymmetricKey {
        guard let storedUser = readData(account: userAccount),
              let storedID = String(data: storedUser, encoding: .utf8),
              storedID.caseInsensitiveCompare(userID.uuidString) == .orderedSame
        else { throw VaultError.notFound }

        guard let blob = readData(account: blobAccount) else {
            throw VaultError.notFound
        }
        let wrapKey = try loadWrapKey()
        do {
            let box = try AES.GCM.SealedBox(combined: blob)
            let plain = try AES.GCM.open(box, using: wrapKey)
            guard plain.count == 32 else { throw VaultError.openFailed }
            return SymmetricKey(data: plain)
        } catch let err as VaultError {
            throw err
        } catch {
            throw VaultError.openFailed
        }
    }

    /// Background-friendly unlock (Keychain may present system UI off the main actor).
    static func unlock(userID: UUID) async throws -> SymmetricKey {
        try await withCheckedThrowingContinuation { cont in
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    cont.resume(returning: try unlock(userID: userID))
                } catch {
                    cont.resume(throwing: error)
                }
            }
        }
    }

    static func clear() {
        delete(account: wrapAccount)
        delete(account: blobAccount)
        delete(account: userAccount)
    }

    static func hasBlob(for userID: UUID) -> Bool {
        guard let storedUser = readData(account: userAccount),
              let storedID = String(data: storedUser, encoding: .utf8),
              storedID.caseInsensitiveCompare(userID.uuidString) == .orderedSame,
              readData(account: blobAccount) != nil
        else { return false }
        return true
    }

    // MARK: - Wrap key

    private static func loadOrCreateWrapKey() throws -> SymmetricKey {
        if let existing = try? loadWrapKey() {
            return existing
        }
        let key = SymmetricKey(size: .bits256)
        let data = key.withUnsafeBytes { Data($0) }
        do {
            try writeData(
                account: wrapAccount,
                value: data,
                protectWithPresence: requiresUserPresence
            )
        } catch {
            // Simulator / no passcode: fall back to device-only accessibility.
            try writeData(account: wrapAccount, value: data, protectWithPresence: false)
        }
        return key
    }

    private static func loadWrapKey() throws -> SymmetricKey {
        let context = LAContext()
        context.localizedReason = "Unlock your encrypted chats"
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: wrapAccount,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecUseAuthenticationContext as String: context,
            kSecUseOperationPrompt as String: "Unlock your encrypted chats",
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecUserCanceled || status == errSecAuthFailed {
            throw VaultError.userCancelled
        }
        guard status == errSecSuccess, let data = item as? Data, data.count == 32 else {
            throw VaultError.notFound
        }
        return SymmetricKey(data: data)
    }

    // MARK: - Keychain I/O

    private static func writeData(
        account: String,
        value: Data,
        protectWithPresence: Bool
    ) throws {
        delete(account: account)
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: value,
        ]
        if protectWithPresence {
            var error: Unmanaged<CFError>?
            guard let access = SecAccessControlCreateWithFlags(
                nil,
                kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
                .userPresence,
                &error
            ) else {
                throw VaultError.keychain(errSecParam)
            }
            query[kSecAttrAccessControl as String] = access
        } else {
            query[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        }
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw VaultError.keychain(status)
        }
    }

    private static func readData(account: String) -> Data? {
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

    private static func delete(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
