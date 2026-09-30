import Foundation

/// Account-level privacy flags (`/privacy/settings`).
///
/// These live on the server rather than in `UserDefaults` because they gate what a *peer*
/// is allowed to do to or learn about this account — the server is the only place that can
/// enforce them when the request comes from the other side. They carry no secret material.
///
/// Human: The three visibility switches work both ways, and the server enforces both: hiding
/// your read receipts also hides your contacts' from you, and the same goes for typing and
/// online / last seen.
nonisolated struct PrivacySettingsDTO: Codable, Equatable, Sendable {
    /// When true, a contact deleting a chat "for both" also clears this account's copy.
    /// When false, their messages are replaced with "Message deleted" here and this
    /// account's own messages survive.
    let allowPeerChatDelete: Bool
    /// Contacts see when this account read their messages, and it sees theirs.
    let sendReadReceipts: Bool
    /// Typing and voice-recording indicators go out and come in.
    let sendTyping: Bool
    /// Contacts see "online" / "last seen", and this account sees theirs.
    let sharePresence: Bool
    /// Someone who only knows the username can find this account. Off, strangers need the QR
    /// code or share code; contacts and pending requests still find it by name.
    let discoverableByUsername: Bool

    enum CodingKeys: String, CodingKey {
        case allowPeerChatDelete = "allow_peer_chat_delete"
        case sendReadReceipts = "send_read_receipts"
        case sendTyping = "send_typing"
        case sharePresence = "share_presence"
        case discoverableByUsername = "discoverable_by_username"
    }

    init(
        allowPeerChatDelete: Bool,
        sendReadReceipts: Bool = true,
        sendTyping: Bool = true,
        sharePresence: Bool = true,
        discoverableByUsername: Bool = true
    ) {
        self.allowPeerChatDelete = allowPeerChatDelete
        self.sendReadReceipts = sendReadReceipts
        self.sendTyping = sendTyping
        self.sharePresence = sharePresence
        self.discoverableByUsername = discoverableByUsername
    }

    /// A server from before these switches sends only the chat-delete flag; it behaves as if
    /// all the others were on, so that is what they read as.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        allowPeerChatDelete = try container.decode(Bool.self, forKey: .allowPeerChatDelete)
        sendReadReceipts = try container.decodeIfPresent(Bool.self, forKey: .sendReadReceipts) ?? true
        sendTyping = try container.decodeIfPresent(Bool.self, forKey: .sendTyping) ?? true
        sharePresence = try container.decodeIfPresent(Bool.self, forKey: .sharePresence) ?? true
        discoverableByUsername = try container.decodeIfPresent(Bool.self, forKey: .discoverableByUsername) ?? true
    }
}

/// Partial update — absent (nil) fields are left unchanged server-side.
nonisolated struct UpdatePrivacySettingsBody: Encodable, Equatable, Sendable {
    var allowPeerChatDelete: Bool?
    var sendReadReceipts: Bool?
    var sendTyping: Bool?
    var sharePresence: Bool?
    var discoverableByUsername: Bool?

    enum CodingKeys: String, CodingKey {
        case allowPeerChatDelete = "allow_peer_chat_delete"
        case sendReadReceipts = "send_read_receipts"
        case sendTyping = "send_typing"
        case sharePresence = "share_presence"
        case discoverableByUsername = "discoverable_by_username"
    }
}

/// `POST /users/me/share-code` — the account's new share code.
nonisolated struct ShareCodeDTO: Decodable, Equatable, Sendable {
    let shareCode: String

    enum CodingKeys: String, CodingKey {
        case shareCode = "share_code"
    }
}
