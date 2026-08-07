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
enum LocalHistoryCrypto {
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

enum LocalDataProtection {
    /// Marks a file complete-protected and excluded from iCloud / device backups.
    static func lockDown(url: URL) {
        try? (url as NSURL).setResourceValue(
            true,
            forKey: .isExcludedFromBackupKey
        )
        try? FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
            ofItemAtPath: url.path
        )
    }

    static func prepareDirectory(_ url: URL) {
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        lockDown(url: url)
    }
}
