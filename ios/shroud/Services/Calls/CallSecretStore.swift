import CryptoKit
import Foundation
import Security

nonisolated enum CallSecretError: LocalizedError, Equatable {
    /// Deriving a secret needs the identity keys, which need the chats unlocked.
    case chatsLocked

    var errorDescription: String? {
        switch self {
        case .chatsLocked: "Open Shroud and unlock your chats to connect this call."
        }
    }
}

/// Each contact's call secret (`CallCrypto.callSecret`), kept where a locked phone can read it.
///
/// Human: The identity keys need Face ID, and a call rings on the lock screen: answering it
/// must open the caller's sealed offer without the chats being unlocked. So while the chats
/// are unlocked the app derives each contact's call secret and keeps it here, readable after
/// the first unlock (like the notification payload key). It opens call signals and nothing
/// else. The sign-out wipe deletes it with every other Keychain item.
/// Agent: Keychain service `com.shroud.call-secrets`, account = lowercased peer user id,
/// AfterFirstUnlockThisDeviceOnly. Tests use `useInMemoryStorageForTesting()`.
nonisolated enum CallSecretStore {
    static let service = "com.shroud.call-secrets"

    private static let memoryLock = NSLock()
    private nonisolated(unsafe) static var memory: [String: Data]?

    /// Keeps secrets in memory: simulator test hosts may lack the Keychain entitlement.
    static func useInMemoryStorageForTesting() {
        memoryLock.withLock { if memory == nil { memory = [:] } }
    }

    static func secret(for peerUserID: UUID) -> SymmetricKey? {
        let account = account(for: peerUserID)
        if let stored = memoryLock.withLock({ memory.map { $0[account] } }) {
            return stored.map { SymmetricKey(data: $0) }
        }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              data.count == 32
        else { return nil }
        return SymmetricKey(data: data)
    }

    /// Stores `secret` for the contact unless the same one is already there.
    static func save(_ secret: SymmetricKey, for peerUserID: UUID) {
        let account = account(for: peerUserID)
        let data = secret.withUnsafeBytes { Data($0) }
        let handled = memoryLock.withLock { () -> Bool in
            guard memory != nil else { return false }
            memory?[account] = data
            return true
        }
        if handled { return }
        if let existing = Self.secret(for: peerUserID),
           existing.withUnsafeBytes({ Data($0) }) == data
        {
            return
        }
        delete(for: peerUserID)
        let attributes: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        SecItemAdd(attributes as CFDictionary, nil)
    }

    static func delete(for peerUserID: UUID) {
        let account = account(for: peerUserID)
        let handled = memoryLock.withLock { () -> Bool in
            guard memory != nil else { return false }
            memory?[account] = nil
            return true
        }
        if handled { return }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }

    private static func account(for peerUserID: UUID) -> String {
        peerUserID.uuidString.lowercased()
    }
}
