import Foundation

/// Plaintext sealed inside a reaction record (`PUT /messages/{id}/reaction`).
///
/// Human: A reaction is not a message: the server keeps one sealed record per (message, user)
/// and learns who reacted to which message, never the emoji. `r` ties the record to its message,
/// so the server cannot move a genuine reaction onto another message; `e` is a list so several
/// reactions per user need no new format (this build sends one and reads the first).
/// Agent: Wire format shared with the web client (`web/src/reactions.ts`); sealed as a v2
/// envelope (identity boxes, sender tag), never through the ratchet. See docs/architecture.md.
nonisolated struct MessageReactionPayload: Codable, Equatable, Sendable {
    /// Always `"reaction"`.
    var t: String
    /// Server id of the message reacted to, lowercased.
    var r: String
    /// The emoji; exactly one from this build.
    var e: [String]

    static let kind = "reaction"
    /// One emoji is at most a few scalars; anything longer is not an emoji we show.
    static let maxEmojiBytes = 32

    static func make(_ emoji: String, for messageID: UUID) -> MessageReactionPayload {
        MessageReactionPayload(t: kind, r: messageID.uuidString.lowercased(), e: [emoji])
    }

    /// The emoji in `plaintext` when it is a reaction to `messageID`, else nil — a record whose
    /// `r` names another message is dropped, not moved.
    static func parse(_ plaintext: Data, for messageID: UUID) -> String? {
        guard let payload = try? JSONDecoder().decode(MessageReactionPayload.self, from: plaintext),
              payload.t == kind,
              UUID(uuidString: payload.r) == messageID,
              let emoji = payload.e.first,
              isSingleEmoji(emoji)
        else { return nil }
        return emoji
    }

    /// One grapheme that is drawn as an emoji: rejects text, digits, and several emoji at once.
    static func isSingleEmoji(_ text: String) -> Bool {
        guard text.count == 1,
              text.utf8.count <= maxEmojiBytes,
              let first = text.unicodeScalars.first
        else { return false }
        let scalars = text.unicodeScalars
        if first.properties.isEmojiPresentation { return true }
        // "❤" + VS16, keycaps, flags: emoji-capable base made an emoji by what follows.
        return first.properties.isEmoji && scalars.count > 1
    }
}

/// One reaction record as the server returns it (history, WS event, catch-up).
nonisolated struct ReactionDTO: Decodable, Equatable, Sendable {
    let messageId: UUID
    let userId: UUID
    /// Null for a removed reaction.
    let ciphertext: String?
    let seq: Int64
    let updatedAt: Date

    enum CodingKeys: String, CodingKey {
        case messageId = "message_id"
        case userId = "user_id"
        case ciphertext
        case seq
        case updatedAt = "updated_at"
    }
}

struct PutReactionBody: Encodable, Equatable, Sendable {
    let ciphertext: String
}

struct ReactionChangesResponse: Decodable, Equatable, Sendable {
    let reactions: [ReactionDTO]
    let nextSeq: Int64
    let hasMore: Bool

    enum CodingKeys: String, CodingKey {
        case reactions
        case nextSeq = "next_seq"
        case hasMore = "has_more"
    }
}
