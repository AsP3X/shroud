import CryptoKit
import Foundation

/// Usernames this device has opened from a mutual contact's seal. The server never has them.
///
/// Human: Who your contacts are is the one thing the server deliberately does not know, so the
/// book is sealed on disk and only open while the chats are: locked, every name reads as unknown
/// and writes drop (security audit M9). It is not in iCloud or Finder backups.
/// Agent: READS/WRITES a sealed file (`shroud/contacts/names.sealed`, `LocalHistoryCrypto`
/// context `.contactNames`) holding the same `[storage key: [peer id: value]]` maps older builds
/// kept in UserDefaults (`shroud.contact-names.<owner>` and `.published.<owner>`). OPENED and
/// LOCKED by `SealedLocalState`; unlocking migrates and deletes those UserDefaults keys. Log Out
/// deletes the file (`DeviceDataWipe.wipeSettings`).
nonisolated enum ContactNames {
    static let placeholder = "Contact"
    private static let nameScalars = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789_")
    /// Prefix of the UserDefaults keys older builds kept the books under, in the clear.
    static let legacyDefaultsPrefix = "shroud.contact-names."

    /// `Application Support/shroud/contacts/names.sealed`, next to the other sealed stores.
    static var defaultFileURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base
            .appendingPathComponent("shroud", isDirectory: true)
            .appendingPathComponent("contacts", isDirectory: true)
            .appendingPathComponent("names.sealed")
    }

    /// Present only while unlocked. `books` is the decrypted copy; the file is rewritten on
    /// every change.
    private struct Unlocked {
        let historyKey: SymmetricKey
        let fileURL: URL
        var books: [String: [String: String]]
    }

    private static let stateLock = NSLock()
    private nonisolated(unsafe) static var unlocked: Unlocked?

    /// False while the chats are locked: the book cannot be read, which says nothing about a name.
    static var isOpen: Bool {
        stateLock.withLock { unlocked != nil }
    }

    static func isUsername(_ name: String) -> Bool {
        (3 ... 32).contains(name.unicodeScalars.count)
            && name.unicodeScalars.allSatisfy { nameScalars.contains($0) }
    }

    static func name(peer: UUID, owner: UUID) -> String? {
        let stored = map(key(owner))[peer.uuidString.lowercased()]
        guard let stored, isUsername(stored) else { return nil }
        return stored
    }

    static func display(peer: UUID, owner: UUID?) -> String {
        guard let owner, let known = name(peer: peer, owner: owner) else { return placeholder }
        return known
    }

    /// Every name this device has, by lowercase peer id (for the shared `ContactNameBook`).
    static func all(owner: UUID) -> [String: String] {
        map(key(owner)).filter { isUsername($0.value) }
    }

    static func remember(_ name: String, peer: UUID, owner: UUID) {
        guard isUsername(name) else { return }
        var book = map(key(owner))
        book[peer.uuidString.lowercased()] = name
        write(book, key: key(owner))
    }

    static func forget(peer: UUID, owner: UUID) {
        let id = peer.uuidString.lowercased()
        for storageKey in [key(owner), publishedKey(owner)] {
            var book = map(storageKey)
            guard book.removeValue(forKey: id) != nil else { continue }
            write(book, key: storageKey)
        }
    }

    /// Drops names of people who are no longer contacts.
    static func retain(peers: [UUID], owner: UUID) {
        let keep = Set(peers.map { $0.uuidString.lowercased() })
        for storageKey in [key(owner), publishedKey(owner)] {
            var book = map(storageKey)
            let before = book.count
            book = book.filter { keep.contains($0.key) }
            if book.count != before { write(book, key: storageKey) }
        }
    }

    static func publishedFingerprint(peer: UUID, owner: UUID) -> String? {
        map(publishedKey(owner))[peer.uuidString.lowercased()]
    }

    static func rememberPublished(_ fingerprint: String, peer: UUID, owner: UUID) {
        var book = map(publishedKey(owner))
        book[peer.uuidString.lowercased()] = fingerprint
        write(book, key: publishedKey(owner))
    }

    // MARK: - Lock

    /// Opens the sealed books and folds in (then deletes) the plaintext UserDefaults copies an
    /// older build left. Called whenever chats unlock.
    static func unlock(
        historyKey: SymmetricKey,
        fileURL: URL = defaultFileURL,
        defaults: UserDefaults = .standard
    ) {
        stateLock.withLock {
            var books = (try? Data(contentsOf: fileURL))
                .flatMap { open($0, historyKey: historyKey) } ?? [:]
            let legacyKeys = defaults.dictionaryRepresentation().keys.filter { $0.hasPrefix(legacyDefaultsPrefix) }
            var migrated: [String] = []
            for legacyKey in legacyKeys {
                if let legacy = defaults.dictionary(forKey: legacyKey) as? [String: String] {
                    // The sealed copy is newer wherever both know a contact.
                    books[legacyKey, default: [:]].merge(legacy) { sealed, _ in sealed }
                    migrated.append(legacyKey)
                } else {
                    // Unreadable leftover: nothing to keep, and it must not stay in the clear.
                    defaults.removeObject(forKey: legacyKey)
                }
            }
            if !migrated.isEmpty, write(books, historyKey: historyKey, to: fileURL) {
                for legacyKey in migrated { defaults.removeObject(forKey: legacyKey) }
            }
            unlocked = Unlocked(historyKey: historyKey, fileURL: fileURL, books: books)
        }
    }

    /// Forgets the decrypted books and the key. The sealed file stays.
    static func lock() {
        stateLock.withLock { unlocked = nil }
    }

    // MARK: - Sealing

    static func seal(_ books: [String: [String: String]], historyKey: SymmetricKey) throws -> Data {
        let json = try JSONEncoder().encode(books)
        return try LocalHistoryCrypto.seal(json, masterKey: historyKey, context: .contactNames)
    }

    /// Nil for a file that does not open under `historyKey` (wrong account, tampered).
    static func open(_ blob: Data, historyKey: SymmetricKey) -> [String: [String: String]]? {
        guard let json = try? LocalHistoryCrypto.open(blob, masterKey: historyKey, context: .contactNames)
        else { return nil }
        return try? JSONDecoder().decode([String: [String: String]].self, from: json)
    }

    @discardableResult
    private static func write(
        _ books: [String: [String: String]],
        historyKey: SymmetricKey,
        to url: URL
    ) -> Bool {
        guard let sealed = try? seal(books, historyKey: historyKey) else { return false }
        LocalDataProtection.prepareDirectory(url.deletingLastPathComponent())
        do {
            try sealed.write(to: url, options: .atomic)
            LocalDataProtection.lockDown(url: url)
            return true
        } catch {
            return false
        }
    }

    // MARK: - Storage

    private static func key(_ owner: UUID) -> String {
        "shroud.contact-names.\(owner.uuidString.lowercased())"
    }

    private static func publishedKey(_ owner: UUID) -> String {
        "shroud.contact-names.published.\(owner.uuidString.lowercased())"
    }

    /// Empty while locked.
    private static func map(_ key: String) -> [String: String] {
        stateLock.withLock { unlocked?.books[key] ?? [:] }
    }

    /// Dropped while locked.
    private static func write(_ map: [String: String], key: String) {
        stateLock.withLock {
            guard var state = unlocked else { return }
            if map.isEmpty {
                state.books.removeValue(forKey: key)
            } else {
                state.books[key] = map
            }
            unlocked = state
            write(state.books, historyKey: state.historyKey, to: state.fileURL)
        }
    }
}
