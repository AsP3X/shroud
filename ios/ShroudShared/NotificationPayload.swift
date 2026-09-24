import CryptoKit
import Foundation
import Security
import UserNotifications

/// What Shroud's APNs alerts carry, and how a notification is worded from them.
///
/// Human: Apple relays these pushes, so they hold ids, a kind and — only when this iPhone asked
/// for names — the sender's name, sealed under a key this app shares with the server alone.
/// Apple never reads the name, and no push holds message text: the server has none to put in.
/// The notification extension opens the name while the phone is locked (the key is readable
/// after the first unlock), so the lock screen says who wrote.
/// Agent: compiled into the app and ShroudNotificationService. The wire format must stay in step
/// with `server/crates/shroud-server/src/push/payload.rs` (shared test vector in shroudTests).
nonisolated enum NotificationPayload {
    /// App group of the app and its notification extension; also the Keychain access group
    /// the payload key lives in.
    static let appGroup = "group.de.corespace.shroud"
    static let keychainService = "de.corespace.shroud.notifications"
    static let keychainAccount = "payload-key"

    nonisolated enum Kind: String, Sendable {
        case message
        case reaction
        case contactRequest = "contact_request"
        case call
        case videoCall = "video_call"
        case test
    }

    /// The app's part of a push (`userInfo["shroud"]`).
    nonisolated struct Contents: Equatable, Sendable {
        var kind: Kind
        var conversationID: UUID?
        var peerUserID: UUID?
        var messageID: UUID?
        var callID: UUID?
        /// `p` exactly as sent (a lowercase UUID): the sealed name is bound to it.
        var rawPeer: String?
        /// The sender's name, sealed for this iPhone; nil when names are off.
        var sealedName: String?
    }

    static func parse(_ userInfo: [AnyHashable: Any]) -> Contents? {
        guard let app = userInfo["shroud"] as? [String: Any],
              let rawKind = app["k"] as? String,
              let kind = Kind(rawValue: rawKind)
        else { return nil }
        func uuid(_ key: String) -> UUID? { (app[key] as? String).flatMap(UUID.init(uuidString:)) }
        return Contents(
            kind: kind,
            conversationID: uuid("c"),
            peerUserID: uuid("p"),
            messageID: uuid("m"),
            callID: uuid("call"),
            rawPeer: app["p"] as? String,
            sealedName: app["e"] as? String
        )
    }

    /// Opens a sealed name: AES-256-GCM, base64(nonce ‖ ciphertext ‖ tag), bound to the kind,
    /// thread and person it came with, so a relay cannot move a name onto another push — not
    /// another chat's, and not another requester's or caller's (those share one thread).
    static func openName(
        _ sealed: String,
        key: SymmetricKey,
        kind: Kind,
        thread: String,
        peer: String
    ) -> String? {
        guard let combined = Data(base64Encoded: sealed),
              let box = try? AES.GCM.SealedBox(combined: combined),
              let plain = try? AES.GCM.open(
                  box,
                  using: key,
                  authenticating: aad(kind: kind, thread: thread, peer: peer)
              ),
              let object = try? JSONSerialization.jsonObject(with: plain) as? [String: Any],
              let name = object["n"] as? String
        else { return nil }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : String(trimmed.prefix(64))
    }

    static func aad(kind: Kind, thread: String, peer: String) -> Data {
        Data("shroud-push-v1|\(kind.rawValue)|\(thread)|\(peer)".utf8)
    }

    /// What a notification says under the name. Never message text — a push has none.
    static func body(for kind: Kind) -> String {
        switch kind {
        case .message: "New message"
        case .reaction: "Reacted to your message"
        case .contactRequest: "Wants to add you as a contact"
        case .call: "Incoming call"
        case .videoCall: "Incoming video call"
        case .test: "Notifications are working"
        }
    }

    /// Words a delivered push: the opened name as the title (none when it is off or cannot be
    /// opened), the kind's line as the body. The name is bound to the push's `thread-id` and `p`.
    static func dress(_ content: UNMutableNotificationContent, key: SymmetricKey?) {
        guard let contents = parse(content.userInfo) else { return }
        content.body = body(for: contents.kind)
        if let sealed = contents.sealedName,
           let key,
           let name = openName(
               sealed,
               key: key,
               kind: contents.kind,
               thread: content.threadIdentifier,
               peer: contents.rawPeer ?? ""
           )
        {
            content.title = name
        }
    }

    // MARK: - Payload key (shared Keychain)

    private static func keyQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
            kSecAttrAccessGroup as String: appGroup,
        ]
    }

    /// The key the server seals names with for this iPhone; nil before the app made one.
    static func storedKey() -> SymmetricKey? {
        var query = keyQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              data.count == 32
        else { return nil }
        return SymmetricKey(data: data)
    }

    /// The stored key, or a new one. Readable after the first unlock (the extension runs on a
    /// locked phone) and never backed up or moved to another device.
    static func makeKey() -> SymmetricKey? {
        if let key = storedKey() { return key }
        let key = SymmetricKey(size: .bits256)
        var attributes = keyQuery()
        attributes[kSecValueData as String] = key.withUnsafeBytes { Data($0) }
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        switch SecItemAdd(attributes as CFDictionary, nil) {
        case errSecSuccess: return key
        case errSecDuplicateItem: return storedKey()
        default: return nil
        }
    }
}
