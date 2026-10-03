import Foundation

/// Usernames this device has opened from a mutual contact's seal. The server never has them.
enum ContactNames {
    static let placeholder = "Contact"
    private static let nameScalars = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789_")

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

    private static func key(_ owner: UUID) -> String {
        "shroud.contact-names.\(owner.uuidString.lowercased())"
    }

    private static func publishedKey(_ owner: UUID) -> String {
        "shroud.contact-names.published.\(owner.uuidString.lowercased())"
    }

    private static func map(_ key: String) -> [String: String] {
        UserDefaults.standard.dictionary(forKey: key) as? [String: String] ?? [:]
    }

    private static func write(_ map: [String: String], key: String) {
        if map.isEmpty {
            UserDefaults.standard.removeObject(forKey: key)
        } else {
            UserDefaults.standard.set(map, forKey: key)
        }
    }
}
