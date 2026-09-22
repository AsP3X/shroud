import CryptoKit
import Foundation

/// Owns on-device identity unlock state for messaging.
///
/// Human: Session token ≠ crypto unlock. Phrase derives keys; local chat history needs the
/// phrase-derived history key, which is vaulted behind Face ID / passcode after first unlock.
///
/// Agent: WRITES IdentityKeyStore + HistoryKeyVault; CALLS KeyBundleService; never logs phrase.
@MainActor
@Observable
final class CryptoController {
    private(set) var material: IdentityKeyMaterial?
    private let store: IdentityKeyStore
    private let keyBundleService: KeyBundleService

    var isUnlocked: Bool { material != nil }

    /// True when Keychain still holds identity material for this account (Face ID / vault path).
    /// False after a full local wipe, incomplete login, or first install — caller should not
    /// show the lock screen in that case.
    func hasLocalIdentity(for userID: UUID) -> Bool {
        store.hasIdentity(for: userID)
    }

    /// True when identity keys exist for the user but history vault has not been opened.
    private(set) var needsHistoryUnlock: Bool = false

    /// After the user **cancels** Face ID / passcode, automatic vault prompts stop until the
    /// next explicit lock (background or "Lock chats now"). Manual button unlock always works.
    private(set) var suppressAutomaticVaultPrompt: Bool = false

    /// Prevents RootView + Welcome from stacking two Face ID sheets at once.
    private var vaultUnlockInFlight = false

    init(
        store: IdentityKeyStore = IdentityKeyStore(),
        keyBundleService: KeyBundleService = KeyBundleService()
    ) {
        self.store = store
        self.keyBundleService = keyBundleService
    }

    /// Last vault unlock failure message for UI toasts (nil after success).
    private(set) var lastUnlockErrorMessage: String?

    /// Re-open history (biometry/passcode). Identity must still match.
    /// Always user-initiated — automatic Face ID prompts were removed (they stacked / stuck).
    /// - Parameter automatic: Kept for call-site compatibility; when true, still respects
    ///   `suppressAutomaticVaultPrompt` if any residual auto path fires.
    /// - Parameter method: Face ID first vs device passcode only.
    @discardableResult
    func unlockHistoryIfPossible(
        for userID: UUID,
        automatic: Bool = false,
        method: HistoryKeyVault.UnlockMethod = .biometryPreferred
    ) async -> Bool {
        lastUnlockErrorMessage = nil
        if automatic, suppressAutomaticVaultPrompt {
            needsHistoryUnlock = store.hasIdentity(for: userID)
            return false
        }
        // Coalesce concurrent prompts so the system sheet cannot stack on itself.
        if vaultUnlockInFlight {
            return material != nil
        }
        vaultUnlockInFlight = true
        defer { vaultUnlockInFlight = false }

        guard let stored = store.load(), stored.userID == userID else {
            material = nil
            needsHistoryUnlock = false
            // No identity on device — Face ID cannot work; session is orphaned after a wipe.
            lastUnlockErrorMessage = Self.userMessage(for: CryptoControllerError.localDataMissing)
            return false
        }

        // Prefer vault; migrate legacy plain history key once.
        if let legacy = store.loadLegacyPlainHistoryKey() {
            try? HistoryKeyVault.store(historyKey: legacy, userID: userID)
            store.clearLegacyPlainHistoryKey()
        }

        do {
            let historyKey = try await HistoryKeyVault.unlock(userID: userID, method: method)
            rewrapHistoryIfNeeded(historyKey, userID: userID)
            let restored = IdentityKeyMaterial(stored: stored, historyKey: historyKey)
            material = restored
            needsHistoryUnlock = false
            suppressAutomaticVaultPrompt = false
            try? store.save(restored)
            return true
        } catch HistoryKeyVault.VaultError.userCancelled {
            material = nil
            needsHistoryUnlock = true
            lastUnlockErrorMessage = Self.userMessage(for: HistoryKeyVault.VaultError.userCancelled)
            if automatic {
                suppressAutomaticVaultPrompt = true
            }
            return false
        } catch HistoryKeyVault.VaultError.timedOut {
            material = nil
            needsHistoryUnlock = true
            lastUnlockErrorMessage = Self.userMessage(for: HistoryKeyVault.VaultError.timedOut)
            if automatic {
                suppressAutomaticVaultPrompt = true
            }
            return false
        } catch {
            material = nil
            needsHistoryUnlock = store.hasIdentity(for: userID)
            lastUnlockErrorMessage = Self.userMessage(for: error)
            if automatic {
                suppressAutomaticVaultPrompt = true
            }
            return false
        }
    }

    /// Sign-up: derive keys from phrase, persist identity, vault history key, upload bundle.
    func establishFromSignup(
        mnemonicWords: [String],
        userID: UUID,
        bearerToken: String
    ) async throws {
        let established = try IdentityKeyMaterial.establish(
            mnemonicWords: mnemonicWords,
            userID: userID
        )
        try persistUnlocked(established)
        let request = try KeyBundleService.makePutRequest(from: established)
        try await keyBundleService.putBundle(request, bearerToken: bearerToken)
        material = established
        needsHistoryUnlock = false
        suppressAutomaticVaultPrompt = false
    }

    /// Login phrase step: validate phrase; reuse Keychain keys if same identity, else re-establish.
    func unlockWithPhrase(
        mnemonicWords: [String],
        userID: UUID,
        bearerToken: String
    ) async throws {
        _ = try BIP39Seed.validateMnemonic(mnemonicWords)

        if let stored = store.load(), stored.userID == userID {
            // Probe history key from phrase; verify identity matches Keychain shell.
            let fromPhrase = try IdentityKeyMaterial.establish(
                mnemonicWords: mnemonicWords,
                userID: userID,
                oneTimePreKeyCount: 0
            )
            let shell = IdentityKeyMaterial(stored: stored, historyKey: fromPhrase.historyKey)
            guard shell.matchesMnemonic(mnemonicWords) else {
                throw CryptoControllerError.phraseDoesNotMatchAccount
            }
            try persistUnlocked(shell)
            material = shell
            needsHistoryUnlock = false
            suppressAutomaticVaultPrompt = false
            try await uploadBundleIfNeeded(bearerToken: bearerToken)
            return
        }

        if let stored = store.load(), stored.userID != userID {
            store.clear()
            HistoryKeyVault.clear()
        }

        let established = try IdentityKeyMaterial.reestablish(
            mnemonicWords: mnemonicWords,
            userID: userID
        )
        // No local keys on this device. Still refuse a phrase that is not the key
        // already published for the account, and do not upload it.
        try await rejectPhraseThatIsNotTheAccountKey(
            established,
            userID: userID,
            bearerToken: bearerToken
        )
        try persistUnlocked(established)
        let request = try KeyBundleService.makePutRequest(from: established)
        try await keyBundleService.putBundle(request, bearerToken: bearerToken)
        material = established
        needsHistoryUnlock = false
        suppressAutomaticVaultPrompt = false
    }

    /// Clears in-memory keys. Keychain identity is kept unless `wipeStore`.
    func lock(wipeStore: Bool = false) {
        material = nil
        needsHistoryUnlock = !wipeStore && (store.load() != nil)
        suppressAutomaticVaultPrompt = false
        if wipeStore {
            store.clear()
            HistoryKeyVault.clear()
            needsHistoryUnlock = false
        }
    }

    /// Drop history material from RAM only (identity Keychain + vault blob remain sealed).
    func lockHistoryInMemory() {
        material = nil
        needsHistoryUnlock = store.load() != nil
        suppressAutomaticVaultPrompt = false
    }

    private func persistUnlocked(_ material: IdentityKeyMaterial) throws {
        try store.save(material)
        try HistoryKeyVault.store(
            historyKey: material.historyKey,
            userID: material.userID,
            rotateWrapKey: SecurityPreferences.vaultNeedsRewrap
        )
        SecurityPreferences.vaultNeedsRewrap = false
        store.clearLegacyPlainHistoryKey()
    }

    /// Re-seal vault after biometry unlock when prefs changed (no phrase available).
    ///
    /// Also upgrades a vault that was sealed while the device had no biometry or passcode. That
    /// fallback is otherwise permanent — the unprotected wrap key keeps satisfying every later
    /// unlock, so enrolling Face ID afterwards never brings the prompt back.
    private func rewrapHistoryIfNeeded(_ historyKey: SymmetricKey, userID: UUID) {
        let canUpgradeProtection = HistoryKeyVault.requiresUserPresence
            && !HistoryKeyVault.isWrapKeyProtected
            && HistoryKeyVault.canProtectWrapKey
        guard SecurityPreferences.vaultNeedsRewrap || canUpgradeProtection else { return }
        do {
            try HistoryKeyVault.store(
                historyKey: historyKey,
                userID: userID,
                rotateWrapKey: true
            )
            SecurityPreferences.vaultNeedsRewrap = false
        } catch {
            // Keep old vault; user can re-lock with phrase later.
        }
    }

    /// First device: the account has no published identity yet. Any later device must
    /// derive that same key or the phrase is wrong.
    private func rejectPhraseThatIsNotTheAccountKey(
        _ established: IdentityKeyMaterial,
        userID: UUID,
        bearerToken: String
    ) async throws {
        do {
            let published = try await keyBundleService.fetchIdentity(
                userID: userID,
                bearerToken: bearerToken
            )
            guard let data = Data(base64Encoded: published.identityKey),
                  data == established.identityPublicKeyData
            else {
                throw CryptoControllerError.phraseDoesNotMatchAccount
            }
        } catch let error as APIError {
            if case let .server(code, _, _) = error, code == "KEYS_REQUIRED" {
                return
            }
            throw error
        }
    }

    private func uploadBundleIfNeeded(bearerToken: String) async throws {
        guard let material else { return }
        do {
            let status = try await keyBundleService.status(bearerToken: bearerToken)
            if status.hasIdentity { return }
        } catch {
            // Fall through to upload.
        }
        let request = try KeyBundleService.makePutRequest(from: material)
        try await keyBundleService.putBundle(request, bearerToken: bearerToken)
    }

    static func userMessage(for error: Error) -> String {
        if let seed = error as? BIP39Seed.SeedError {
            switch seed {
            case .invalidWordCount:
                return "Enter all 12 words of your encryption phrase."
            case .unknownWord:
                return "One or more words are not in the recovery word list."
            case .invalidChecksum:
                return "That phrase isn’t valid. Check the words and order."
            case .derivationFailed:
                return "Could not process your encryption phrase."
            }
        }
        if let crypto = error as? CryptoControllerError {
            switch crypto {
            case .phraseDoesNotMatchAccount:
                return "That phrase doesn’t match this account on this device."
            case .notUnlocked:
                return "Unlock messaging with your encryption phrase first."
            case .historyLocked:
                return "Unlock with Face ID, Touch ID, or your device passcode to open chats."
            case .localDataMissing:
                return "Local encryption data is missing. Sign in or create an account again."
            }
        }
        if let vault = error as? HistoryKeyVault.VaultError {
            switch vault {
            case .userCancelled:
                return "Authentication cancelled."
            case .timedOut:
                return "Unlock timed out. Try again, use device passcode, or your encryption phrase."
            case .notFound:
                return "Enter your encryption phrase to unlock chats on this device."
            default:
                return "Could not unlock encrypted chats. Try your encryption phrase."
            }
        }
        if let api = error as? APIError {
            return SessionController.userMessage(for: api)
        }
        return "Could not unlock encryption. Try again."
    }
}

enum CryptoControllerError: Error, Equatable {
    case phraseDoesNotMatchAccount
    case notUnlocked
    case historyLocked
    /// Session token exists but identity/vault was wiped from the device.
    case localDataMissing
}
