import Foundation

/// Plaintext sealed inside a reaction record (`PUT /messages/{id}/reaction`).
///
/// Human: A reaction is not a message: the server keeps one sealed record per (message, user)
/// and learns who reacted to which message, never the emoji. `r` ties the record to its message,
/// so the server cannot move a genuine reaction onto another message; `e` is the person's whole
/// set, oldest first, up to the server's `max_per_user`.
/// Agent: Wire format shared with the web client (`web/src/reactions.ts`); sealed as a v2
/// envelope (identity boxes, sender tag), never through the ratchet. See docs/architecture.md.
nonisolated struct MessageReactionPayload: Codable, Equatable, Sendable {
    /// Always `"reaction"`.
    var t: String
    /// Server id of the message reacted to, lowercased.
    var r: String
    /// The person's emoji, oldest first.
    var e: [String]

    static let kind = "reaction"
    /// One emoji is at most a few scalars; anything longer is not an emoji we show.
    static let maxEmojiBytes = 32
    /// Most emoji a reader shows from one record, whatever the server's limit is today — a
    /// lowered limit never hides what was already there, and a modified client can't flood a
    /// bubble.
    static let readerCap = 20

    static func make(_ emojis: [String], for messageID: UUID) -> MessageReactionPayload {
        MessageReactionPayload(t: kind, r: messageID.uuidString.lowercased(), e: emojis)
    }

    /// The emoji in `plaintext` when it is a reaction to `messageID` — single emoji only, each
    /// once, oldest first, at most `readerCap` — else nil: a record whose `r` names another
    /// message is dropped, not moved.
    static func parse(_ plaintext: Data, for messageID: UUID) -> [String]? {
        guard let payload = try? JSONDecoder().decode(MessageReactionPayload.self, from: plaintext),
              payload.t == kind,
              UUID(uuidString: payload.r) == messageID
        else { return nil }
        var seen = Set<String>()
        var emojis: [String] = []
        for emoji in payload.e where isSingleEmoji(emoji) && seen.insert(emoji).inserted {
            emojis.append(emoji)
            if emojis.count == readerCap { break }
        }
        return emojis
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

/// `GET /config`: behaviour the server operator sets.
struct ClientConfigDTO: Decodable, Equatable, Sendable {
    struct Reactions: Decodable, Equatable, Sendable {
        /// Most emoji one person may leave on one message.
        let maxPerUser: Int

        enum CodingKeys: String, CodingKey {
            case maxPerUser = "max_per_user"
        }
    }

    let reactions: Reactions
}

struct PutReactionBody: Encodable, Equatable, Sendable {
    let ciphertext: String
    /// `seq` of our record the set was built on; 0 when we had none.
    let baseSeq: Int64
    /// The set has an emoji the base lacked (news for the message's author).
    let added: Bool

    enum CodingKeys: String, CodingKey {
        case ciphertext
        case baseSeq = "base_seq"
        case added
    }
}

/// `409 REACTION_CHANGED`: our other device wrote the record first; `current` is it now.
nonisolated struct ReactionConflictDTO: Decodable, Sendable {
    let current: ReactionDTO
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
