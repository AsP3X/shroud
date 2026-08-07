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
    /// Records whether `wrapAccount` actually carries the presence ACL, so a vault created on a
    /// device without biometry can be spotted and upgraded later.
    private static let wrapProtectionAccount = "history_wrap_protected_v1"

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
            delete(account: wrapProtectionAccount)
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

    /// Unwraps the history key using an already-authenticated context when one is supplied.
    static func unlock(userID: UUID, context: LAContext? = nil) throws -> SymmetricKey {
        guard let storedUser = readData(account: userAccount),
              let storedID = String(data: storedUser, encoding: .utf8),
              storedID.caseInsensitiveCompare(userID.uuidString) == .orderedSame
        else { throw VaultError.notFound }

        guard let blob = readData(account: blobAccount) else {
            throw VaultError.notFound
        }
        let wrapKey = try loadWrapKey(context: context)
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
    ///
    /// Asks for Face ID / Touch ID / passcode explicitly, then hands the **authenticated**
    /// context to the Keychain read. The item's own `.userPresence` ACL is still the real
    /// gate on device — this does not replace it — but relying on the ACL alone means no
    /// prompt ever appears in the Simulator, which does not enforce Keychain ACLs, and none
    /// appears for a vault that predates the device having biometry. Because the same
    /// context is reused for the read, device users still see exactly one prompt.
    static func unlock(userID: UUID) async throws -> SymmetricKey {
        var context = makeContext()
        if requiresUserPresence, canProtectWrapKey {
            context = try await authenticatedContext()
        }
        // The context is only read from here on (handed to the Keychain query), and this
        // continuation is the sole consumer.
        nonisolated(unsafe) let authenticated = context
        return try await withCheckedThrowingContinuation { cont in
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    cont.resume(returning: try unlock(userID: userID, context: authenticated))
                } catch {
                    cont.resume(throwing: error)
                }
            }
        }
    }

    private static let authenticationReason = "Unlock your encrypted chats"

    private static func makeContext() -> LAContext {
        let context = LAContext()
        context.localizedReason = authenticationReason
        return context
    }

    /// Authenticates and returns the context that succeeded, so the Keychain read can reuse it.
    ///
    /// `.deviceOwnerAuthentication` already offers "Enter Passcode" inside the same prompt after
    /// a failed biometric attempt — but only on a device that *has* a passcode; without one, iOS
    /// shows "Try Again / Cancel" and there is no second factor to reach. When biometry itself is
    /// the blocker (locked out after repeated failures, or no longer enrolled), a fresh
    /// evaluation skips straight to passcode entry rather than dead-ending the user on the
    /// 12-word phrase.
    private static func authenticatedContext() async throws -> LAContext {
        let biometric = makeContext()
        do {
            try await evaluate(with: biometric)
            return biometric
        } catch let error as LAError {
            switch error.code {
            case .biometryLockout, .biometryNotAvailable, .biometryNotEnrolled:
                let passcode = makeContext()
                do {
                    try await evaluate(with: passcode)
                    return passcode
                } catch {
                    throw VaultError.userCancelled
                }
            case .passcodeNotSet:
                // Nothing on this device can prove presence (it was removed since the
                // `canProtectWrapKey` check). The Keychain ACL stays the only gate, which is
                // the same position a vault sealed on such a device is already in.
                return makeContext()
            default:
                // Cancelled, or the user gave up on biometry. Locked; the phrase still works.
                throw VaultError.userCancelled
            }
        } catch {
            throw VaultError.userCancelled
        }
    }

    /// Bridges `evaluatePolicy` into async, rethrowing the `LAError` so the caller can decide
    /// whether another factor is still worth trying.
    private static func evaluate(with context: LAContext) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            context.evaluatePolicy(
                .deviceOwnerAuthentication,
                localizedReason: authenticationReason
            ) { success, error in
                if success {
                    cont.resume()
                } else {
                    cont.resume(throwing: error ?? VaultError.userCancelled)
                }
            }
        }
    }

    static func clear() {
        delete(account: wrapAccount)
        delete(account: wrapProtectionAccount)
        delete(account: blobAccount)
        delete(account: userAccount)
    }

    /// True when the stored wrap key is actually gated behind biometry / passcode.
    ///
    /// A vault sealed while the device had neither reads back without any prompt, which is
    /// indistinguishable from a working vault until you notice Face ID never appears.
    static var isWrapKeyProtected: Bool {
        readData(account: wrapProtectionAccount) == Data([1])
    }

    /// True when this device can gate the wrap key at all (biometry enrolled or passcode set).
    static var canProtectWrapKey: Bool {
        var error: NSError?
        return LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: &error)
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
        var isProtected = requiresUserPresence
        do {
            try writeData(
                account: wrapAccount,
                value: data,
                protectWithPresence: requiresUserPresence
            )
        } catch {
            // No passcode and no enrolled biometry: keep the user able to open their own
            // history rather than sealing it behind an ACL the device cannot satisfy.
            // Recorded as unprotected so the next unlock can upgrade it once they enrol —
            // this fallback used to be permanent, and Face ID would never appear again.
            try writeData(account: wrapAccount, value: data, protectWithPresence: false)
            isProtected = false
        }
        try? writeData(
            account: wrapProtectionAccount,
            value: Data([isProtected ? 1 : 0]),
            protectWithPresence: false
        )
        return key
    }

    private static func loadWrapKey(context: LAContext? = nil) throws -> SymmetricKey {
        let context = context ?? {
            let fresh = LAContext()
            fresh.localizedReason = authenticationReason
            return fresh
        }()
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: wrapAccount,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
            // `localizedReason` on the context replaces the deprecated kSecUseOperationPrompt.
            kSecUseAuthenticationContext as String: context,
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
