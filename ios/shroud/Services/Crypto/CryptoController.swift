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

    /// True when identity keys exist for the user but history vault has not been opened.
    private(set) var needsHistoryUnlock: Bool = false

    init(
        store: IdentityKeyStore = IdentityKeyStore(),
        keyBundleService: KeyBundleService = KeyBundleService()
    ) {
        self.store = store
        self.keyBundleService = keyBundleService
    }

    /// Cold start: restore identity + unwrap history key (biometry/passcode when configured).
    @discardableResult
    func restoreIfPossible(for userID: UUID) async -> Bool {
        guard let stored = store.load(), stored.userID == userID else {
            material = nil
            needsHistoryUnlock = false
            return false
        }

        // Prefer vault; migrate legacy plain history key once.
        if let legacy = store.loadLegacyPlainHistoryKey() {
            try? HistoryKeyVault.store(historyKey: legacy, userID: userID)
            store.clearLegacyPlainHistoryKey()
        }

        do {
            let historyKey = try await HistoryKeyVault.unlock(userID: userID)
            rewrapHistoryIfNeeded(historyKey, userID: userID)
            let restored = IdentityKeyMaterial(stored: stored, historyKey: historyKey)
            material = restored
            needsHistoryUnlock = false
            // Refresh identity keychain attrs without re-storing plain history.
            try? store.save(restored)
            return true
        } catch HistoryKeyVault.VaultError.userCancelled {
            material = nil
            needsHistoryUnlock = true
            return false
        } catch {
            // No vault yet or auth failed — user must enter the encryption phrase.
            material = nil
            needsHistoryUnlock = store.hasIdentity(for: userID)
            return false
        }
    }

    /// Re-open history after background lock (biometry/passcode). Identity must still match.
    @discardableResult
    func unlockHistoryIfPossible(for userID: UUID) async -> Bool {
        guard let stored = store.load(), stored.userID == userID else { return false }
        do {
            let historyKey = try await HistoryKeyVault.unlock(userID: userID)
            rewrapHistoryIfNeeded(historyKey, userID: userID)
            material = IdentityKeyMaterial(stored: stored, historyKey: historyKey)
            needsHistoryUnlock = false
            return true
        } catch {
            needsHistoryUnlock = true
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
        try persistUnlocked(established)
        let request = try KeyBundleService.makePutRequest(from: established)
        try await keyBundleService.putBundle(request, bearerToken: bearerToken)
        material = established
        needsHistoryUnlock = false
    }

    /// Clears in-memory keys. Keychain identity is kept unless `wipeStore`.
    func lock(wipeStore: Bool = false) {
        material = nil
        needsHistoryUnlock = !wipeStore && (store.load() != nil)
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
    private func rewrapHistoryIfNeeded(_ historyKey: SymmetricKey, userID: UUID) {
        guard SecurityPreferences.vaultNeedsRewrap else { return }
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
            }
        }
        if let vault = error as? HistoryKeyVault.VaultError {
            switch vault {
            case .userCancelled:
                return "Authentication cancelled."
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
}
