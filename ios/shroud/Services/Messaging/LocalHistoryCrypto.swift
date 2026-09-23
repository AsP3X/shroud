import CryptoKit
import Foundation

/// AES-256-GCM at-rest encryption for local chat history, media, and plaintext caches.
///
/// Human: Files on disk are ciphertext. Without the phrase-derived `historyKey` (only in memory
/// while messaging is unlocked, and never uploaded), the blobs are unreadable — even from a
/// full filesystem dump.
///
/// Agent: Master key = `IdentityKeyMaterial.historyKey` (HKDF from BIP39 seed,
/// info `shroud-history-aes`). Domain-separated subkeys per store via HKDF. Combined
/// AES-GCM layout (12-byte nonce ‖ ciphertext ‖ 16-byte tag). Never log keys or plaintext.
nonisolated enum LocalHistoryCrypto {
    enum Error: Swift.Error, Equatable {
        case sealFailed
        case openFailed
        case missingKey
    }

    /// Domain separation for HKDF subkeys — one store cannot open another’s blobs by mistake.
    enum Context: String, Sendable {
        case messagesSnapshot = "shroud-local-messages-v1"
        case mediaFile = "shroud-local-media-v1"
        case plaintextPayload = "shroud-local-plaintext-v1"
        /// Identity, signed-prekey and one-time-prekey privates (Keychain values).
        case identityKeychain = "shroud-keychain-identity-v1"
        /// Double Ratchet session JSON, one Keychain item per peer.
        case ratchetKeychain = "shroud-keychain-ratchet-v1"
        /// Per-conversation voice transcription language statistics.
        case languageStats = "shroud-local-language-stats-v1"
    }

    /// Magic prefix so we can tell sealed blobs from legacy plaintext leftovers.
    private static let magic = Data("SHRD1".utf8) // 5 bytes

    /// Seals `plaintext` under a context-specific subkey of `masterKey`.
    static func seal(
        _ plaintext: Data,
        masterKey: SymmetricKey,
        context: Context
    ) throws -> Data {
        let key = subkey(masterKey: masterKey, context: context)
        let sealed = try AES.GCM.seal(plaintext, using: key)
        guard let combined = sealed.combined else { throw Error.sealFailed }
        // magic ‖ combined
        var out = Data()
        out.reserveCapacity(magic.count + combined.count)
        out.append(magic)
        out.append(combined)
        return out
    }

    /// Opens a blob produced by `seal`. Fails closed on any mismatch / tamper.
    static func open(
        _ blob: Data,
        masterKey: SymmetricKey,
        context: Context
    ) throws -> Data {
        guard blob.count > magic.count + 12 + 16,
              blob.prefix(magic.count) == magic
        else { throw Error.openFailed }
        let combined = blob.dropFirst(magic.count)
        let key = subkey(masterKey: masterKey, context: context)
        do {
            let box = try AES.GCM.SealedBox(combined: Data(combined))
            return try AES.GCM.open(box, using: key)
        } catch {
            throw Error.openFailed
        }
    }

    /// True when `blob` carries our sealed-file magic (not legacy plaintext).
    static func isSealedBlob(_ blob: Data) -> Bool {
        blob.count > magic.count && blob.prefix(magic.count) == magic
    }

    // MARK: - Subkeys

    private static func subkey(masterKey: SymmetricKey, context: Context) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(
            inputKeyMaterial: masterKey,
            salt: Data("shroud-local-at-rest-v1".utf8),
            info: Data(context.rawValue.utf8),
            outputByteCount: 32
        )
    }
}

// MARK: - File protection helpers

nonisolated enum LocalDataProtection {
    /// Marks a file excluded from iCloud / device backups.
    ///
    /// File-protection class is left at the default for Application Support. At-rest secrecy
    /// comes from AES-GCM with the phrase-derived history key — over-aggressive NSFileProtection
    /// can make sealed blobs unreadable across launches even though the AES key is available.
    static func lockDown(url: URL) {
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutable = url
        try? mutable.setResourceValues(values)
    }

    static func prepareDirectory(_ url: URL) {
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        lockDown(url: url)
    }
}
