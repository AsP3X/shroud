import Foundation

/// Owns on-device identity unlock state for messaging.
/// Human: Session token ≠ crypto unlock. Phrase derives keys; keys never leave the device.
/// Agent: WRITES IdentityKeyStore; CALLS KeyBundleService; never logs phrase or private keys.
@MainActor
@Observable
final class CryptoController {
    private(set) var material: IdentityKeyMaterial?
    private let store: IdentityKeyStore
    private let keyBundleService: KeyBundleService

    var isUnlocked: Bool { material != nil }

    init(
        store: IdentityKeyStore = IdentityKeyStore(),
        keyBundleService: KeyBundleService = KeyBundleService()
    ) {
        self.store = store
        self.keyBundleService = keyBundleService
    }

    /// Cold start: load identity from Keychain when it matches the signed-in user.
    @discardableResult
    func restoreIfPossible(for userID: UUID) -> Bool {
        guard let stored = store.load(), stored.userID == userID else {
            material = nil
            return false
        }
        let restored = IdentityKeyMaterial(stored: stored)
        material = restored
        // Re-write Keychain items under current accessibility (e.g. WhenUnlockedThisDeviceOnly).
        try? store.save(restored)
        return true
    }

    /// Sign-up: derive keys from phrase, persist, upload public bundle.
    func establishFromSignup(
        mnemonicWords: [String],
        userID: UUID,
        bearerToken: String
    ) async throws {
        let established = try IdentityKeyMaterial.establish(
            mnemonicWords: mnemonicWords,
            userID: userID
        )
        try store.save(established)
        let request = try KeyBundleService.makePutRequest(from: established)
        try await keyBundleService.putBundle(request, bearerToken: bearerToken)
        material = established
    }

    /// Login phrase step: validate phrase; reuse Keychain keys if same identity, else re-establish.
    func unlockWithPhrase(
        mnemonicWords: [String],
        userID: UUID,
        bearerToken: String
    ) async throws {
        _ = try BIP39Seed.validateMnemonic(mnemonicWords)

        if let stored = store.load(), stored.userID == userID {
            let existing = IdentityKeyMaterial(stored: stored)
            guard existing.matchesMnemonic(mnemonicWords) else {
                throw CryptoControllerError.phraseDoesNotMatchAccount
            }
            material = existing
            // Ensure server has a bundle (no-op if already uploaded).
            try await uploadBundleIfNeeded(bearerToken: bearerToken)
            return
        }

        // Different user or first unlock on this device — derive + new pre-keys.
        if let stored = store.load(), stored.userID != userID {
            store.clear()
        }

        let established = try IdentityKeyMaterial.reestablish(
            mnemonicWords: mnemonicWords,
            userID: userID
        )
        try store.save(established)
        let request = try KeyBundleService.makePutRequest(from: established)
        try await keyBundleService.putBundle(request, bearerToken: bearerToken)
        material = established
    }

    /// Clears in-memory keys. Keychain identity is kept for same-device re-login unless `wipeStore`.
    func lock(wipeStore: Bool = false) {
        material = nil
        if wipeStore {
            store.clear()
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
}
