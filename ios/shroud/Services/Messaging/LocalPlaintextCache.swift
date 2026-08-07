import CryptoKit
import Foundation

/// On-device plaintext after first successful decrypt (or send) — **AES-256-GCM sealed at rest**.
///
/// Human: Ratchet keys are one-shot — never re-open the same ciphertext as recipient.
/// Cached payload bytes live only as sealed files; UserDefaults is never used for content.
///
/// Agent: Application Support `shroud/plaintext/{id}.sealed`. Requires `historyKey`.
/// Migrates and wipes any legacy UserDefaults `msg_plain_v2.*` entries.
struct LocalPlaintextCache: Sendable {
    private let directory: URL
    private let defaults: UserDefaults
    private let legacyPrefix = "msg_plain_v2."

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        directory = base
            .appendingPathComponent("shroud", isDirectory: true)
            .appendingPathComponent("plaintext", isDirectory: true)
        LocalDataProtection.prepareDirectory(directory)
    }

    func data(for messageID: UUID, historyKey: SymmetricKey) -> Data? {
        let url = fileURL(messageID)
        if let blob = try? Data(contentsOf: url) {
            return try? LocalHistoryCrypto.open(
                blob,
                masterKey: historyKey,
                context: .plaintextPayload
            )
        }

        // Legacy UserDefaults (plaintext) — migrate into sealed store, then wipe defaults.
        let legacyKey = legacyDefaultsKey(messageID)
        if let plain = defaults.data(forKey: legacyKey) {
            save(messageID: messageID, data: plain, historyKey: historyKey)
            defaults.removeObject(forKey: legacyKey)
            return plain
        }
        return nil
    }

    func text(for messageID: UUID, historyKey: SymmetricKey) -> String? {
        guard let data = data(for: messageID, historyKey: historyKey) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func save(messageID: UUID, data: Data, historyKey: SymmetricKey) {
        guard let sealed = try? LocalHistoryCrypto.seal(
            data,
            masterKey: historyKey,
            context: .plaintextPayload
        ) else { return }
        let url = fileURL(messageID)
        try? sealed.write(to: url, options: .atomic)
        LocalDataProtection.lockDown(url: url)
        // Never leave a plaintext UserDefaults sibling.
        defaults.removeObject(forKey: legacyDefaultsKey(messageID))
    }

    func save(messageID: UUID, text: String, historyKey: SymmetricKey) {
        save(messageID: messageID, data: Data(text.utf8), historyKey: historyKey)
    }

    /// Drops every cached plaintext entry (logout / local wipe).
    func clearAll() {
        try? FileManager.default.removeItem(at: directory)
        LocalDataProtection.prepareDirectory(directory)
        wipeLegacyUserDefaults()
    }

    /// Removes plaintext for pruned / deleted message ids.
    func remove(messageIDs: [UUID]) {
        for id in messageIDs {
            try? FileManager.default.removeItem(at: fileURL(id))
            defaults.removeObject(forKey: legacyDefaultsKey(id))
        }
    }

    /// Scrubs any remaining unencrypted UserDefaults plaintext keys (upgrade path).
    func wipeLegacyUserDefaults() {
        let keys = defaults.dictionaryRepresentation().keys.filter { $0.hasPrefix(legacyPrefix) }
        for key in keys {
            defaults.removeObject(forKey: key)
        }
    }

    private func fileURL(_ messageID: UUID) -> URL {
        directory.appendingPathComponent(messageID.uuidString.lowercased() + ".sealed")
    }

    private func legacyDefaultsKey(_ messageID: UUID) -> String {
        legacyPrefix + messageID.uuidString.lowercased()
    }
}
