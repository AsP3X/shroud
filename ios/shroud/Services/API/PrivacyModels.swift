import Foundation

/// Account-level privacy consent flags (`/privacy/settings`).
///
/// These live on the server rather than in `UserDefaults` because they gate what a *peer*
/// is allowed to do to this account's data — the server is the only place that can enforce
/// them when the request comes from the other side. They carry no secret material.
struct PrivacySettingsDTO: Codable, Equatable, Sendable {
    /// When true, a contact deleting a chat "for both" also clears this account's copy.
    /// When false, their messages are replaced with "Message deleted" here and this
    /// account's own messages survive.
    let allowPeerChatDelete: Bool

    enum CodingKeys: String, CodingKey {
        case allowPeerChatDelete = "allow_peer_chat_delete"
    }
}

/// Partial update — absent fields are left unchanged server-side.
struct UpdatePrivacySettingsBody: Encodable, Equatable, Sendable {
    let allowPeerChatDelete: Bool

    enum CodingKeys: String, CodingKey {
        case allowPeerChatDelete = "allow_peer_chat_delete"
    }
}
