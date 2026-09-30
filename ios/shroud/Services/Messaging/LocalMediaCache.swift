import CryptoKit
import Foundation

/// On-device cache of media bytes keyed by message id — **AES-256-GCM sealed at rest**.
///
/// Human: Photo and voice files on disk are ciphertext. Only the unlocked app (with the
/// phrase-derived history key) can open them.
///
/// Agent: Application Support `shroud/media/{id}.sealed`. Requires `historyKey` for all
/// read/write. Migrates legacy plaintext `.bin` once, then deletes it.
struct LocalMediaCache: Sendable {
    private let directory: URL

    init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        directory = base
            .appendingPathComponent("shroud", isDirectory: true)
            .appendingPathComponent("media", isDirectory: true)
        LocalDataProtection.prepareDirectory(directory)
        migrateFromCachesIfNeeded()
    }

    func data(for messageID: UUID, historyKey: SymmetricKey) -> Data? {
        let sealedURL = fileURL(messageID)
        if let blob = try? Data(contentsOf: sealedURL) {
            if LocalHistoryCrypto.isSealedBlob(blob),
               let plain = try? LocalHistoryCrypto.open(
                   blob,
                   masterKey: historyKey,
                   context: .mediaFile
               )
            {
                return plain
            }
            // Corrupt / wrong key — fail closed.
            if LocalHistoryCrypto.isSealedBlob(blob) {
                return nil
            }
        }

        // Legacy plaintext `.bin` — open once, re-seal, delete plain.
        let legacy = legacyFileURL(messageID)
        if let plain = try? Data(contentsOf: legacy) {
            save(messageID: messageID, data: plain, historyKey: historyKey)
            try? FileManager.default.removeItem(at: legacy)
            return plain
        }
        return nil
    }

    func save(messageID: UUID, data: Data, historyKey: SymmetricKey) {
        guard let sealed = try? LocalHistoryCrypto.seal(
            data,
            masterKey: historyKey,
            context: .mediaFile
        ) else { return }
        let url = fileURL(messageID)
        // Ensure parent exists: a logout wipe removes the folder, and this cache outlives it.
        LocalDataProtection.prepareDirectory(directory)
        try? sealed.write(to: url, options: .atomic)
        LocalDataProtection.lockDown(url: url)
        try? FileManager.default.removeItem(at: legacyFileURL(messageID))
    }

    func remove(messageIDs: [UUID]) {
        for id in messageIDs {
            try? FileManager.default.removeItem(at: fileURL(id))
            try? FileManager.default.removeItem(at: legacyFileURL(id))
        }
    }

    /// Removes the on-disk media cache directory (logout / local wipe).
    func clearAll() {
        try? FileManager.default.removeItem(at: directory)
        LocalDataProtection.prepareDirectory(directory)
    }

    private func fileURL(_ messageID: UUID) -> URL {
        directory.appendingPathComponent(messageID.uuidString.lowercased() + ".sealed")
    }

    private func legacyFileURL(_ messageID: UUID) -> URL {
        directory.appendingPathComponent(messageID.uuidString.lowercased() + ".bin")
    }

    private func migrateFromCachesIfNeeded() {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let old = caches.appendingPathComponent("shroud-media", isDirectory: true)
        guard FileManager.default.fileExists(atPath: old.path) else { return }
        // Do not copy plaintext into Application Support unencrypted — leave legacy in place
        // for one-shot open-on-demand via path? Actually old path is only under caches.
        // Best effort: wipe caches dir so plaintext media does not linger.
        try? FileManager.default.removeItem(at: old)
    }
}
