import CryptoKit
import Foundation
@preconcurrency import LocalAuthentication
import Security

/// Stores the phrase-derived **history key** wrapped so disk/Keychain dumps alone cannot open chats.
///
/// Human: Opening local history needs Face ID / Touch ID / device passcode (or the 12-word phrase).
/// Agent: Random wrap key in Keychain, always `.userPresence` +
/// `WhenPasscodeSetThisDeviceOnly`; AES-GCM sealed history blob. Never stores raw `historyKey`
/// bytes. No passcode on the device means no vault: store and unlock throw `.passcodeNotSet`.
enum HistoryKeyVault {
    private static let service = "com.shroud.history-vault"
    private static let wrapAccount = "history_wrap_key_v1"
    private static let blobAccount = "history_key_blob_v1"
    private static let userAccount = "history_vault_user"
    /// Records which protection `wrapAccount` was written with, so a vault from an older build
    /// can be spotted and re-wrapped at its next unlock.
    private static let wrapProtectionAccount = "history_wrap_protected_v1"
    /// Marker for `.userPresence` + `WhenPasscodeSetThisDeviceOnly`. Older builds wrote 0 (no
    /// ACL: no passcode, or presence switched off) or 1 (`.userPresence` + `WhenUnlocked`, which
    /// survives the passcode being removed).
    private static let currentProtectionMarker = Data([2])

    enum VaultError: Error, Equatable {
        case sealFailed
        case openFailed
        case keychain(OSStatus)
        case userCancelled
        case notFound
        /// App-side auth timeout — Face ID / passcode sheet did not finish in time.
        case timedOut
        /// The device has no passcode, so the wrap key cannot be protected and chats stay shut.
        case passcodeNotSet
    }

    /// How the user chooses to satisfy device-owner presence before unwrapping the vault.
    enum UnlockMethod: Sendable, Equatable {
        /// Face ID / Touch ID first (system may still offer passcode after failure).
        case biometryPreferred
        /// Device passcode only — skips the biometry sheet.
        case passcodeOnly
    }

    /// Biometry unlock may fall through to passcode entry, so allow time for both.
    static let biometryAuthenticationTimeout: Duration = .seconds(90)
    /// Device passcode entry gets longer — typing can take more time than a glance.
    static let passcodeAuthenticationTimeout: Duration = .seconds(90)

    /// Active `LAContext` for the in-flight system auth sheet (invalidated on timeout).
    /// Module defaults to MainActor isolation; these are shared with timeout tasks off the actor.
    private nonisolated static let activeAuthLock = NSLock()
    private nonisolated(unsafe) static var activeAuthContext: LAContext?
    /// Set when the app timeout fires so the LA callback maps to `.timedOut` not cancel.
    private nonisolated(unsafe) static var activeAuthTimedOut = false
    /// Abort flag for the current unlock attempt (checked before presenting a new sheet).
    private nonisolated(unsafe) static var activeAuthAborted = false

    /// Seals `historyKey` under a fresh device wrap key gated by biometry or passcode.
    ///
    /// Always rotates: the blob is re-sealed anyway, and reading the old wrap key first would
    /// raise a second Face ID sheet. Replacing it also retires an unprotected wrap key an older
    /// build left behind.
    static func store(historyKey: SymmetricKey, userID: UUID) throws {
        let wrapKey = try createProtectedWrapKey()
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
    /// - Parameter allowKeychainUI: When false (passcode-only path), never let Keychain show its
    ///   own auth sheet — that UI prefers Face ID for `.userPresence` items.
    static func unlock(
        userID: UUID,
        context: LAContext? = nil,
        allowKeychainUI: Bool = true
    ) throws -> SymmetricKey {
        // A wrap key from an older build may carry no ACL at all. Without a passcode nothing
        // could have been proven, so never read it.
        guard canProtectWrapKey else { throw VaultError.passcodeNotSet }
        guard let storedUser = readData(account: userAccount),
              let storedID = String(data: storedUser, encoding: .utf8),
              storedID.caseInsensitiveCompare(userID.uuidString) == .orderedSame
        else { throw VaultError.notFound }

        guard let blob = readData(account: blobAccount) else {
            throw VaultError.notFound
        }
        let wrapKey = try loadWrapKey(context: context, allowKeychainUI: allowKeychainUI)
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

    /// Unlocks after optional device-owner auth (Face ID or passcode, per `method`).
    ///
    /// Pre-authenticates an `LAContext`, then reuses it for the Keychain read so the user
    /// sees a single prompt. Auth UI is presented on the main queue; the Keychain read also
    /// runs on the main actor because `LAContext` is not thread-safe.
    ///
    /// Races auth against an app timeout so a stuck Face ID sheet cannot leave unlock busy forever.
    static func unlock(
        userID: UUID,
        method: UnlockMethod = .biometryPreferred
    ) async throws -> SymmetricKey {
        let timeout: Duration = switch method {
        case .biometryPreferred: biometryAuthenticationTimeout
        case .passcodeOnly: passcodeAuthenticationTimeout
        }

        beginAuthAttempt()
        return try await withThrowingTaskGroup(of: SymmetricKey.self) { group in
            group.addTask {
                try await unlockPerformingAuth(userID: userID, method: method)
            }
            group.addTask {
                try await Task.sleep(for: timeout)
                cancelActiveAuthentication(timedOut: true)
                throw VaultError.timedOut
            }
            do {
                guard let key = try await group.next() else {
                    throw VaultError.userCancelled
                }
                group.cancelAll()
                endAuthAttempt()
                return key
            } catch {
                group.cancelAll()
                // Ensure any lingering Face ID sheet is dismissed.
                let isTimeout = (error as? VaultError) == .timedOut
                cancelActiveAuthentication(timedOut: isTimeout)
                endAuthAttempt()
                throw error
            }
        }
    }

    private static func unlockPerformingAuth(
        userID: UUID,
        method: UnlockMethod
    ) async throws -> SymmetricKey {
        if isAuthAborted() { throw VaultError.timedOut }
        guard canProtectWrapKey else { throw VaultError.passcodeNotSet }
        // Always authenticate first, even when the wrap key itself carries no ACL (an older
        // build's vault): the unlock is what proves presence, and it re-wraps that key after.
        let context: LAContext = switch method {
        case .biometryPreferred: try await authenticatedContext()
        case .passcodeOnly: try await authenticatedPasscodeContext()
        }
        try Task.checkCancellation()
        if isAuthAborted() { throw VaultError.timedOut }
        // Passcode path must not re-prompt via Keychain (that sheet prefers Face ID).
        let allowKeychainUI = method == .biometryPreferred
        // Keep the authenticated context on the same actor as the Keychain query.
        return try await MainActor.run {
            try unlock(
                userID: userID,
                context: context,
                allowKeychainUI: allowKeychainUI
            )
        }
    }

    private static let authenticationReason = "Unlock your encrypted chats"

    private static func makeContext() -> LAContext {
        let context = LAContext()
        context.localizedReason = authenticationReason
        return context
    }

    private nonisolated static func beginAuthAttempt() {
        activeAuthLock.lock()
        activeAuthTimedOut = false
        activeAuthAborted = false
        activeAuthContext = nil
        activeAuthLock.unlock()
    }

    private nonisolated static func endAuthAttempt() {
        activeAuthLock.lock()
        activeAuthContext = nil
        activeAuthAborted = false
        activeAuthTimedOut = false
        activeAuthLock.unlock()
    }

    private nonisolated static func isAuthAborted() -> Bool {
        activeAuthLock.lock()
        let aborted = activeAuthAborted
        activeAuthLock.unlock()
        return aborted
    }

    private nonisolated static func registerActiveAuth(_ context: LAContext) -> Bool {
        activeAuthLock.lock()
        defer { activeAuthLock.unlock() }
        if activeAuthAborted {
            return false
        }
        activeAuthContext = context
        return true
    }

    private nonisolated static func clearActiveAuth(matching context: LAContext) {
        activeAuthLock.lock()
        if activeAuthContext === context {
            activeAuthContext = nil
        }
        activeAuthLock.unlock()
    }

    /// Invalidates the in-flight system auth sheet (timeout or teardown).
    private nonisolated static func cancelActiveAuthentication(timedOut: Bool) {
        activeAuthLock.lock()
        if timedOut {
            activeAuthTimedOut = true
            activeAuthAborted = true
        }
        let context = activeAuthContext
        activeAuthContext = nil
        activeAuthLock.unlock()
        // invalidate() dismisses Face ID / passcode; callback may arrive on a background queue.
        guard let context else { return }
        DispatchQueue.main.async {
            context.invalidate()
        }
    }

    private nonisolated static func mapAuthFailure(_ error: Error?) -> Error {
        activeAuthLock.lock()
        let timedOut = activeAuthTimedOut || activeAuthAborted
        if timedOut {
            activeAuthTimedOut = false
        }
        activeAuthLock.unlock()
        if timedOut {
            return VaultError.timedOut
        }
        // Preserve LAError codes so callers can fall back to passcode after Face ID fails.
        if let laError = error as? LAError {
            return laError
        }
        return error ?? VaultError.userCancelled
    }

    /// Face ID / Touch ID first; on failure, fallback button, or lockout → device passcode sheet.
    ///
    /// Uses biometrics policy with an explicit "Enter Passcode" fallback title, then a dedicated
    /// passcode evaluation. Requires `NSFaceIDUsageDescription` in Info.plist or Face ID fails
    /// immediately on device.
    private static func authenticatedContext() async throws -> LAContext {
        if isAuthAborted() { throw VaultError.timedOut }

        let biometric = makeContext()
        // Shown on the Face ID sheet so the user can jump to passcode without canceling out.
        biometric.localizedFallbackTitle = "Enter Passcode"

        var biometryError: NSError?
        let canUseBiometrics = biometric.canEvaluatePolicy(
            .deviceOwnerAuthenticationWithBiometrics,
            error: &biometryError
        )

        if canUseBiometrics {
            do {
                try await evaluate(
                    with: biometric,
                    policy: .deviceOwnerAuthenticationWithBiometrics
                )
                return biometric
            } catch let error as VaultError {
                // Timeout / cancel from our layer — do not open a second sheet.
                throw error
            } catch let error as LAError {
                switch error.code {
                case .userCancel, .appCancel, .systemCancel:
                    throw VaultError.userCancelled
                case .userFallback,
                     .authenticationFailed,
                     .biometryLockout,
                     .biometryNotAvailable,
                     .biometryNotEnrolled,
                     .biometryDisconnected,
                     .biometryNotPaired:
                    // Face ID failed, user chose passcode, or biometry unavailable → passcode.
                    break
                case .passcodeNotSet:
                    throw VaultError.passcodeNotSet
                default:
                    // Any other biometry failure: still offer passcode rather than dead-ending.
                    break
                }
            } catch {
                // Unknown error from biometry — try passcode before giving up.
            }
        }

        if isAuthAborted() { throw VaultError.timedOut }
        return try await authenticatedPasscodeContext()
    }

    /// Forces the device passcode sheet only — never Face ID / Touch ID.
    ///
    /// Never uses `.deviceOwnerAuthentication` (that policy always prefers Face ID). Instead we
    /// read a throwaway Keychain item protected with `.devicePasscode` only — iOS shows the
    /// passcode pad for that ACL, not biometry.
    private static func authenticatedPasscodeContext() async throws -> LAContext {
        if isAuthAborted() { throw VaultError.timedOut }

        let context = makeContext()
        context.localizedFallbackTitle = ""
        context.localizedCancelTitle = "Cancel"
        context.localizedReason = authenticationReason

        var error: Unmanaged<CFError>?
        guard let access = SecAccessControlCreateWithFlags(
            nil,
            kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
            .devicePasscode,
            &error
        ) else {
            throw VaultError.userCancelled
        }

        return try await authenticatePasscodeViaKeychainProbe(context: context, access: access)
    }

    /// Reads a temporary Keychain secret gated with `.devicePasscode` so iOS shows the passcode
    /// pad (not Face ID). The same authenticated `LAContext` is reused for the wrap-key read.
    private static func authenticatePasscodeViaKeychainProbe(
        context: LAContext,
        access: SecAccessControl
    ) async throws -> LAContext {
        let probeAccount = "history_passcode_probe_v1"
        // Always clean up the probe item.
        defer { delete(account: probeAccount) }
        delete(account: probeAccount)

        var add: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: probeAccount,
            kSecValueData as String: Data([0x50]), // "P"
            kSecAttrAccessControl as String: access,
        ]
        let addStatus = SecItemAdd(add as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw VaultError.keychain(addStatus)
        }

        // Synchronous Keychain auth UI — run off the main actor, then hop back.
        return try await withCheckedThrowingContinuation { cont in
            DispatchQueue.global(qos: .userInitiated).async {
                if isAuthAborted() {
                    cont.resume(throwing: VaultError.timedOut)
                    return
                }
                registerActiveAuth(context)
                context.interactionNotAllowed = false
                context.localizedReason = authenticationReason

                let query: [String: Any] = [
                    kSecClass as String: kSecClassGenericPassword,
                    kSecAttrService as String: service,
                    kSecAttrAccount as String: probeAccount,
                    kSecReturnData as String: true,
                    kSecMatchLimit as String: kSecMatchLimitOne,
                    kSecUseAuthenticationContext as String: context,
                ]
                var item: CFTypeRef?
                let status = SecItemCopyMatching(query as CFDictionary, &item)
                clearActiveAuth(matching: context)

                if status == errSecSuccess {
                    cont.resume(returning: context)
                } else if status == errSecUserCanceled || status == errSecAuthFailed {
                    cont.resume(throwing: VaultError.userCancelled)
                } else if status == errSecInteractionNotAllowed {
                    cont.resume(throwing: VaultError.userCancelled)
                } else {
                    cont.resume(throwing: VaultError.keychain(status))
                }
            }
        }
    }

    /// Bridges `evaluatePolicy` into async, rethrowing `LAError` / `VaultError` for fallback logic.
    private static func evaluate(
        with context: LAContext,
        policy: LAPolicy
    ) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            DispatchQueue.main.async {
                guard registerActiveAuth(context) else {
                    cont.resume(throwing: VaultError.timedOut)
                    return
                }
                context.evaluatePolicy(
                    policy,
                    localizedReason: authenticationReason
                ) { success, error in
                    clearActiveAuth(matching: context)
                    if success {
                        cont.resume()
                    } else {
                        cont.resume(throwing: mapAuthFailure(error))
                    }
                }
            }
        }
    }

    private static func evaluateAccessControl(
        _ access: SecAccessControl,
        with context: LAContext
    ) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            DispatchQueue.main.async {
                guard registerActiveAuth(context) else {
                    cont.resume(throwing: VaultError.timedOut)
                    return
                }
                context.evaluateAccessControl(
                    access,
                    operation: .useItem,
                    localizedReason: authenticationReason
                ) { success, error in
                    clearActiveAuth(matching: context)
                    if success {
                        cont.resume()
                    } else {
                        cont.resume(throwing: mapAuthFailure(error))
                    }
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

    /// True when the stored wrap key carries the current protection.
    ///
    /// A vault sealed without the ACL reads back without any prompt, which is indistinguishable
    /// from a working vault until you notice Face ID never appears. One sealed with `WhenUnlocked`
    /// survives the passcode being removed. Both are re-wrapped at the next unlock.
    static var isWrapKeyProtected: Bool {
        readData(account: wrapProtectionAccount) == currentProtectionMarker
    }

    /// True when this device has a passcode (biometry cannot be enrolled without one), so the
    /// wrap key can be gated. False means Shroud must not open chats at all.
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

    private static func createProtectedWrapKey() throws -> SymmetricKey {
        guard canProtectWrapKey else { throw VaultError.passcodeNotSet }
        let key = SymmetricKey(size: .bits256)
        let data = key.withUnsafeBytes { Data($0) }
        delete(account: wrapProtectionAccount)
        do {
            try writeData(account: wrapAccount, value: data, protectWithPresence: true)
        } catch {
            // No unprotected fallback: a wrap key readable without presence makes the lock
            // screen decoration. The passcode can vanish between the check and the write.
            delete(account: wrapAccount)
            throw canProtectWrapKey ? error : VaultError.passcodeNotSet
        }
        try writeData(
            account: wrapProtectionAccount,
            value: currentProtectionMarker,
            protectWithPresence: false
        )
        return key
    }

    private static func loadWrapKey(
        context: LAContext? = nil,
        allowKeychainUI: Bool = true
    ) throws -> SymmetricKey {
        let context = context ?? {
            let fresh = LAContext()
            fresh.localizedReason = authenticationReason
            return fresh
        }()

        // Prefer no second UI when presence was already proven on this context.
        context.interactionNotAllowed = true
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
        var status = SecItemCopyMatching(query as CFDictionary, &item)

        // Biometry path only: if the pre-auth context is not accepted, allow Keychain UI once.
        // Passcode path must not — Keychain's sheet for `.userPresence` opens Face ID first.
        if status != errSecSuccess, allowKeychainUI {
            context.interactionNotAllowed = false
            status = SecItemCopyMatching(query as CFDictionary, &item)
        }

        if status == errSecUserCanceled || status == errSecAuthFailed {
            throw VaultError.userCancelled
        }
        if status == errSecInteractionNotAllowed {
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
            // `WhenPasscodeSet`: iOS deletes the item when the passcode is removed, so the
            // vault cannot outlive the protection it relies on. Recovery is the phrase.
            var error: Unmanaged<CFError>?
            guard let access = SecAccessControlCreateWithFlags(
                nil,
                kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
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
