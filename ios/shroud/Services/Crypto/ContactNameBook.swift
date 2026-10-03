import CryptoKit
import Foundation

/// The account's contact-name book, shared by its devices through the server, which can't read
/// it. The same bytes as the web's `crypto/contactBook.ts` and Android's `ContactNameBookSeal`.
///
/// A name reaches a device only when that contact's app seals it to the account. A device that
/// has opened names keeps them in this book, so a browser (which keeps no contact list) or a new
/// phone learns them from the account's other devices at once.
///
///   key       = HKDF-SHA256(historyKey, salt "shroud-v1", info "shroud-contact-names-v1", 32)
///   aad       = "shroud-contact-names-v1:" + lowercase owner user id
///   plaintext = UTF-8 JSON {"v":1,"names":{"<lowercase peer id>":"<username>",…}}, ids sorted,
///               no spaces, then spaces to the next multiple of 1024 bytes
///   sealed    = nonce(12) ‖ AES-256-GCM ciphertext ‖ tag(16), sent as standard Base64
nonisolated enum ContactNameBook {
    private static let label = "shroud-contact-names-v1"
    private static let padTo = 1024
    private static let nameScalars = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789_")

    static func isUsername(_ name: String) -> Bool {
        (3 ... 32).contains(name.unicodeScalars.count) && name.unicodeScalars.allSatisfy { nameScalars.contains($0) }
    }

    /// Only well-formed ids and usernames survive, whichever device wrote the book.
    static func clean(_ names: [String: String]) -> [String: String] {
        var out: [String: String] = [:]
        for (id, name) in names {
            let key = id.lowercased()
            if UUID(uuidString: key) != nil, key.count == 36, isUsername(name) { out[key] = name }
        }
        return out
    }

    static func seal(
        _ names: [String: String],
        owner: UUID,
        historyKey: SymmetricKey,
        nonce: AES.GCM.Nonce = AES.GCM.Nonce()
    ) throws -> String {
        let entries = clean(names).sorted { $0.key < $1.key }.map { "\"\($0.key)\":\"\($0.value)\"" }
        var plain = Data("{\"v\":1,\"names\":{\(entries.joined(separator: ","))}}".utf8)
        let size = (plain.count + 1 + padTo - 1) / padTo * padTo
        plain.append(Data(repeating: 0x20, count: size - plain.count))
        let box = try AES.GCM.seal(plain, using: key(historyKey), nonce: nonce, authenticating: associatedData(owner))
        guard let combined = box.combined else { throw CryptoKitError.incorrectParameterSize }
        return combined.base64EncodedString()
    }

    /// Nil when the book doesn't open: another account's key, or damaged.
    static func open(_ sealedBase64: String?, owner: UUID, historyKey: SymmetricKey) -> [String: String]? {
        guard let sealedBase64,
              let combined = Data(base64Encoded: sealedBase64),
              combined.count >= 12 + 16,
              let box = try? AES.GCM.SealedBox(combined: combined),
              let plain = try? AES.GCM.open(box, using: key(historyKey), authenticating: associatedData(owner)),
              let object = try? JSONSerialization.jsonObject(with: plain) as? [String: Any],
              object["v"] as? Int == 1,
              let names = object["names"] as? [String: Any]
        else { return nil }
        return clean(names.compactMapValues { $0 as? String })
    }

    private static func key(_ historyKey: SymmetricKey) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(
            inputKeyMaterial: historyKey,
            salt: Data("shroud-v1".utf8),
            info: Data(label.utf8),
            outputByteCount: 32
        )
    }

    private static func associatedData(_ owner: UUID) -> Data {
        Data("\(label):\(owner.uuidString.lowercased())".utf8)
    }
}
