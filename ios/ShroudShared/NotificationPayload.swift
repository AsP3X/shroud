import CryptoKit
import Foundation
import Security
import UserNotifications

/// What Shroud's APNs alerts carry, and how a notification is worded from them.
///
/// Human: Apple relays these pushes, so all Apple reads is the kind ("message", "call"). The
/// ids — chat, sender, message, call — and, when there is one, the sender's name are sealed
/// under a key this app shares with the server alone, and a chat's thread and a call's
/// collapse id are keyed per iPhone, so Apple can't match two people's pushes to one chat. No
/// push holds message text: the server has none to put in. The notification extension opens
/// the seal while the phone is locked (the key is readable after the first unlock) and puts
/// the ids back on the notification, so taps and grouping work as before.
/// Agent: compiled into the app and ShroudNotificationService. The wire format must stay in step
/// with `server/crates/shroud-server/src/push/payload.rs` (shared test vectors in shroudTests).
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
        case missedCall = "missed_call"
        /// PushKit: the ring is over. Ends the CallKit call; not shown as an alert.
        case callEnded = "call_ended"
        case test
    }

    /// The app's part of a push (`userInfo["shroud"]`), opened.
    nonisolated struct Contents: Equatable, Sendable {
        var kind: Kind
        var conversationID: UUID?
        var peerUserID: UUID?
        var messageID: UUID?
        var callID: UUID?
        /// Who it is from; nil when the push names nobody or its seal did not open.
        var senderName: String?
    }

    /// Reads a push's app part. A sealed one (`"v": 2`, `e`) is opened with `key`, read only
    /// then; when there is no key or the seal does not open, only the kind is known. An older
    /// server's push (`"v": 1`, ids in the clear, and an `e` that sealed only the sender's
    /// name) keeps those ids; the name is lost when that older seal does not open. One the
    /// extension already opened carries the ids as they are.
    static func parse(
        _ userInfo: [AnyHashable: Any],
        key: () -> SymmetricKey? = { storedKey() }
    ) -> Contents? {
        guard let app = userInfo["shroud"] as? [String: Any],
              let rawKind = app["k"] as? String,
              let kind = Kind(rawValue: rawKind)
        else { return nil }
        var fields = app
        if isVersionTwo(app), let sealed = app["e"] as? String {
            fields = key().flatMap { open(sealed, key: $0, kind: kind) } ?? [:]
        }
        func uuid(_ name: String) -> UUID? { (fields[name] as? String).flatMap(UUID.init(uuidString:)) }
        return Contents(
            kind: kind,
            conversationID: uuid("c"),
            peerUserID: uuid("p"),
            messageID: uuid("m"),
            callID: uuid("call"),
            senderName: (fields["n"] as? String).flatMap(cleanName)
        )
    }

    /// `v` is 2. APNs boxes JSON numbers as `NSNumber`; a dictionary built in process is an `Int`.
    /// An older push is `v` 1 even when it still carries the sealed name in `e`.
    private static func isVersionTwo(_ app: [String: Any]) -> Bool {
        switch app["v"] {
        case let version as Int: return version == 2
        case let version as NSNumber: return version.intValue == 2
        default: return false
        }
    }

    /// Opens a push's seal: AES-256-GCM, base64(nonce ‖ ciphertext ‖ tag) of a JSON object,
    /// bound to the kind Apple saw, so a relay can't pass one kind's ids off as another's.
    static func open(_ sealed: String, key: SymmetricKey, kind: Kind) -> [String: Any]? {
        guard let combined = Data(base64Encoded: sealed),
              let box = try? AES.GCM.SealedBox(combined: combined),
              let plain = try? AES.GCM.open(box, using: key, authenticating: aad(kind: kind))
        else { return nil }
        return try? JSONSerialization.jsonObject(with: plain) as? [String: Any]
    }

    static func aad(kind: Kind) -> Data {
        Data("shroud-push-v2|\(kind.rawValue)".utf8)
    }

    /// A chat's `thread-id` as the server sends it to this iPhone: HMAC-SHA256 under the
    /// payload key, the first 16 bytes in lowercase hex. A notification the extension could not
    /// open keeps it, so the app looks for both this and the chat's own id.
    static func threadID(for conversationID: UUID, key: SymmetricKey) -> String {
        keyedID("shroud-push-thread-v1", conversationID, key: key)
    }

    private static func keyedID(_ label: String, _ id: UUID, key: SymmetricKey) -> String {
        let tag = HMAC<SHA256>.authenticationCode(
            for: Data("\(label)|\(id.uuidString.lowercased())".utf8),
            using: key
        )
        return Data(tag).prefix(16).map { String(format: "%02x", $0) }.joined()
    }

    private static func cleanName(_ name: String) -> String? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : String(trimmed.prefix(64))
    }

    /// What a notification says under the name. Never message text — a push has none.
    static func body(for kind: Kind) -> String {
        switch kind {
        case .message: "New message"
        case .reaction: "Reacted to your message"
        case .contactRequest: "Wants to add you as a contact"
        case .call: "Incoming call"
        case .videoCall: "Incoming video call"
        case .missedCall: "Missed call"
        case .callEnded: "Call ended"
        case .test: "Notifications are working"
        }
    }

    /// Words a delivered push and opens it for the app: the sender's name as the title (none
    /// when the push names nobody or cannot be opened), the kind's line as the body. A chat's
    /// notifications group under the chat's own id, like the app's own notifications, and the
    /// opened ids replace the seal, so a tap needs no key. A seal that does not open (no key
    /// yet) stays as it came, for the app to try again.
    static func dress(_ content: UNMutableNotificationContent, key: SymmetricKey?) {
        guard let contents = parse(content.userInfo, key: { key }) else { return }
        content.body = body(for: contents.kind)
        if let app = content.userInfo["shroud"] as? [String: Any],
           isVersionTwo(app),
           let sealed = app["e"] as? String,
           key.flatMap({ open(sealed, key: $0, kind: contents.kind) }) == nil
        {
            return
        }
        if let name = contents.senderName {
            content.title = name
        }
        if let conversationID = contents.conversationID,
           contents.kind == .message || contents.kind == .reaction
        {
            content.threadIdentifier = conversationID.uuidString.lowercased()
        }
        var userInfo = content.userInfo
        userInfo["shroud"] = appObject(contents)
        content.userInfo = userInfo
    }

    /// `contents` as an opened app part (`"v": 1`, ids in the clear), for this device only.
    static func appObject(_ contents: Contents) -> [String: Any] {
        var app: [String: Any] = ["v": 1, "k": contents.kind.rawValue]
        if let id = contents.conversationID { app["c"] = id.uuidString.lowercased() }
        if let id = contents.peerUserID { app["p"] = id.uuidString.lowercased() }
        if let id = contents.messageID { app["m"] = id.uuidString.lowercased() }
        if let id = contents.callID { app["call"] = id.uuidString.lowercased() }
        return app
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
