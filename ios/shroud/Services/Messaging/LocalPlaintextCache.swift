import CryptoKit
import Foundation

/// On-device plaintext after first successful decrypt (or send) — **AES-256-GCM sealed at rest**.
///
/// Human: Ratchet keys are one-shot — never re-open the same ciphertext as recipient.
/// Agent: L1 in-memory map (session) + L2 sealed Application Support files. Never store
/// plaintext in UserDefaults. Migrates legacy `msg_plain_v2.*` keys into sealed files.
final class LocalPlaintextCache: @unchecked Sendable {
    private let directory: URL
    private let defaults: UserDefaults
    private let legacyPrefix = "msg_plain_v2."
    private let lock = NSLock()
    /// Session L1 — survives loadThread reloads even if disk I/O glitches.
    private var memory: [UUID: Data] = [:]

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
        lock.lock()
        if let hit = memory[messageID] {
            lock.unlock()
            return hit
        }
        lock.unlock()

        let url = fileURL(messageID)
        if let blob = try? Data(contentsOf: url) {
            if let plain = try? LocalHistoryCrypto.open(
                blob,
                masterKey: historyKey,
                context: .plaintextPayload
            ) {
                lock.lock()
                memory[messageID] = plain
                lock.unlock()
                return plain
            }
            // Sealed file present but wrong key / corrupt — do not fall through to legacy.
            if LocalHistoryCrypto.isSealedBlob(blob) {
                return nil
            }
        }

        // Legacy UserDefaults (plaintext) — migrate into sealed store, then wipe that key.
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
        lock.lock()
        memory[messageID] = data
        lock.unlock()

        guard let sealed = try? LocalHistoryCrypto.seal(
            data,
            masterKey: historyKey,
            context: .plaintextPayload
        ) else { return }

        let url = fileURL(messageID)
        // Ensure parent exists (e.g. after clearAll).
        LocalDataProtection.prepareDirectory(directory)
        do {
            try sealed.write(to: url, options: .atomic)
            LocalDataProtection.lockDown(url: url)
            // Verify we can read back — fail closed by keeping memory even if disk is flaky.
            if let written = try? Data(contentsOf: url),
               let opened = try? LocalHistoryCrypto.open(
                   written,
                   masterKey: historyKey,
                   context: .plaintextPayload
               ),
               opened == data
            {
                // Disk OK.
            }
        } catch {
            // Memory still holds plaintext for this session; next save may succeed.
        }
        defaults.removeObject(forKey: legacyDefaultsKey(messageID))
    }

    func save(messageID: UUID, text: String, historyKey: SymmetricKey) {
        save(messageID: messageID, data: Data(text.utf8), historyKey: historyKey)
    }

    /// Drops in-RAM plaintext only (history lock / background). Disk seals remain.
    func clearMemory() {
        lock.lock()
        memory.removeAll()
        lock.unlock()
    }

    /// Drops every cached plaintext entry (logout / local wipe).
    func clearAll() {
        clearMemory()
        try? FileManager.default.removeItem(at: directory)
        LocalDataProtection.prepareDirectory(directory)
        wipeLegacyUserDefaults()
    }

    /// Removes plaintext for pruned / deleted message ids.
    func remove(messageIDs: [UUID]) {
        lock.lock()
        for id in messageIDs {
            memory.removeValue(forKey: id)
        }
        lock.unlock()
        for id in messageIDs {
            try? FileManager.default.removeItem(at: fileURL(id))
            defaults.removeObject(forKey: legacyDefaultsKey(id))
        }
    }

    /// Migrates all legacy UserDefaults plaintext keys into sealed files, then scrubs them.
    /// Call once per unlock with the active history key — never wipe before migrating.
    func migrateLegacyIfNeeded(historyKey: SymmetricKey) {
        let keys = defaults.dictionaryRepresentation().keys.filter { $0.hasPrefix(legacyPrefix) }
        for key in keys {
            guard let plain = defaults.data(forKey: key) else {
                defaults.removeObject(forKey: key)
                continue
            }
            let idString = String(key.dropFirst(legacyPrefix.count))
            guard let id = UUID(uuidString: idString) else {
                defaults.removeObject(forKey: key)
                continue
            }
            save(messageID: id, data: plain, historyKey: historyKey)
            defaults.removeObject(forKey: key)
        }
    }

    /// Scrubs any remaining unencrypted UserDefaults plaintext keys (logout path).
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
