import Foundation

struct SendMessageRequest: Encodable, Equatable, Sendable {
    let peerUserId: UUID
    let clientMessageId: UUID
    let contentType: String
    let ciphertext: String
    let mediaObjectId: UUID?

    enum CodingKeys: String, CodingKey {
        case peerUserId = "peer_user_id"
        case clientMessageId = "client_message_id"
        case contentType = "content_type"
        case ciphertext
        case mediaObjectId = "media_object_id"
    }

    init(
        peerUserId: UUID,
        clientMessageId: UUID = UUID(),
        contentType: String = "text",
        ciphertext: String,
        mediaObjectId: UUID? = nil
    ) {
        self.peerUserId = peerUserId
        self.clientMessageId = clientMessageId
        self.contentType = contentType
        self.ciphertext = ciphertext
        self.mediaObjectId = mediaObjectId
    }
}

struct MessageDTO: Decodable, Equatable, Sendable, Identifiable {
    let id: UUID
    let conversationId: UUID
    let senderUserId: UUID
    let senderDeviceId: UUID
    let clientMessageId: UUID
    let contentType: String
    let ciphertext: String?
    let mediaObjectId: UUID?
    let deletedForEveryone: Bool
    let createdAt: Date
    /// `created_at` exactly as the server sent it. `createdAt` only keeps milliseconds
    /// (`ISO8601DateFormatter`), and a history cursor built from it skips every message in
    /// the rest of that millisecond.
    let createdAtWire: String
    /// Outbound only — peer device(s) delivered.
    let delivered: Bool?
    /// Outbound only — peer user read.
    let read: Bool?
    /// History only — live sealed reactions (omitted when there are none).
    let reactions: [ReactionDTO]?

    enum CodingKeys: String, CodingKey {
        case id
        case conversationId = "conversation_id"
        case senderUserId = "sender_user_id"
        case senderDeviceId = "sender_device_id"
        case clientMessageId = "client_message_id"
        case contentType = "content_type"
        case ciphertext
        case mediaObjectId = "media_object_id"
        case deletedForEveryone = "deleted_for_everyone"
        case createdAt = "created_at"
        case delivered
        case read
        case reactions
    }
}

/// `scope` on `DELETE /messages/:id`.
enum MessageDeleteScope: String, Equatable, Sendable {
    /// Hides the message for this account only; the peer keeps their copy.
    case me
    /// Tombstones it for both sides. Server rejects this from anyone but the sender.
    case everyone
}

/// `scope` on `DELETE /conversations/:peer_user_id` — the whole chat, not one message.
enum ConversationDeleteScope: String, Equatable, Sendable {
    /// Clears the chat for this account only; the peer keeps everything.
    case me
    /// Clears it here, unsends our messages on the peer's side, and drops the contact link.
    /// Whether the peer's *own* messages go too depends on their `allowPeerChatDelete`.
    case everyone
}

struct DeleteConversationResponse: Decodable, Equatable, Sendable {
    /// False only when there was no server-side conversation to clear.
    let clearedForMe: Bool
    /// `everyone` scope: the peer consented, so their copy of the chat is gone as well.
    let clearedForPeer: Bool
    /// `everyone` scope: how many of our messages became "Message deleted" for the peer.
    let tombstoned: UInt64
    /// `everyone` scope: the contact link existed and was dropped in both directions.
    let contactRemoved: Bool

    enum CodingKeys: String, CodingKey {
        case clearedForMe = "cleared_for_me"
        case clearedForPeer = "cleared_for_peer"
        case tombstoned
        case contactRemoved = "contact_removed"
    }
}

struct MarkReadBulkBody: Encodable, Equatable, Sendable {
    let peerUserId: UUID
    let upToMessageId: UUID

    enum CodingKeys: String, CodingKey {
        case peerUserId = "peer_user_id"
        case upToMessageId = "up_to_message_id"
    }
}

struct MarkReadBulkResponse: Decodable, Equatable, Sendable {
    let marked: UInt64
    let readAt: Date

    enum CodingKeys: String, CodingKey {
        case marked
        case readAt = "read_at"
    }
}

struct ListMessagesResponse: Decodable, Equatable, Sendable {
    let conversationId: UUID?
    let messages: [MessageDTO]
    /// When false, no older page exists (or server omitted the field — treat as false).
    let hasMore: Bool?
    /// The conversation's highest reaction `seq` as the page was read: the page's `reactions`
    /// are the whole live set as of this value. Nil from servers without reactions.
    let reactionSeq: Int64?

    enum CodingKeys: String, CodingKey {
        case conversationId = "conversation_id"
        case messages
        case hasMore = "has_more"
        case reactionSeq = "reaction_seq"
    }
}

extension MessageDTO {
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(UUID.self, forKey: .id)
        conversationId = try container.decode(UUID.self, forKey: .conversationId)
        senderUserId = try container.decode(UUID.self, forKey: .senderUserId)
        senderDeviceId = try container.decode(UUID.self, forKey: .senderDeviceId)
        clientMessageId = try container.decode(UUID.self, forKey: .clientMessageId)
        contentType = try container.decode(String.self, forKey: .contentType)
        ciphertext = try container.decodeIfPresent(String.self, forKey: .ciphertext)
        mediaObjectId = try container.decodeIfPresent(UUID.self, forKey: .mediaObjectId)
        deletedForEveryone = try container.decode(Bool.self, forKey: .deletedForEveryone)
        let wire = try container.decode(String.self, forKey: .createdAt)
        guard let date = ISO8601DateFormatter.date(fromAPI: wire) else {
            throw DecodingError.dataCorruptedError(
                forKey: .createdAt,
                in: container,
                debugDescription: "Invalid ISO-8601 date: \(wire)"
            )
        }
        createdAtWire = wire
        createdAt = date
        delivered = try container.decodeIfPresent(Bool.self, forKey: .delivered)
        read = try container.decodeIfPresent(Bool.self, forKey: .read)
        reactions = try container.decodeIfPresent([ReactionDTO].self, forKey: .reactions)
    }
}

struct ConversationPeerDTO: Codable, Equatable, Sendable, Identifiable {
    let id: UUID
    let username: String
}

struct ConversationItemDTO: Codable, Equatable, Sendable, Identifiable {
    let id: UUID
    let peer: ConversationPeerDTO
    let createdAt: Date
    let lastMessageAt: Date?
    /// The chat's latest reaction change; nil from servers without reactions.
    var reactionSeq: Int64?
    /// The other side's reactions to our messages we have not marked seen (the heart badge).
    var unseenReactions: Int?
    /// Their messages after our read marker, the same on every device; nil from older servers.
    var unreadCount: Int?
    /// Set while we have the chat muted (`until` nil = until unmuted).
    var mute: ChatMuteDTO?

    enum CodingKeys: String, CodingKey {
        case id
        case peer
        case createdAt = "created_at"
        case lastMessageAt = "last_message_at"
        case reactionSeq = "reaction_seq"
        case unseenReactions = "unseen_reactions"
        case unreadCount = "unread_count"
        case mute
    }
}

struct MarkReactionsSeenBody: Encodable, Equatable, Sendable {
    let upToSeq: Int64

    enum CodingKeys: String, CodingKey {
        case upToSeq = "up_to_seq"
    }
}

struct MarkReactionsSeenResponse: Decodable, Equatable, Sendable {
    let seenSeq: Int64

    enum CodingKeys: String, CodingKey {
        case seenSeq = "seen_seq"
    }
}

struct ConversationsResponse: Decodable, Equatable, Sendable {
    let conversations: [ConversationItemDTO]
}

struct PresenceDTO: Decodable, Equatable, Sendable {
    let userId: UUID
    let online: Bool
    let lastSeenAt: Date?

    enum CodingKeys: String, CodingKey {
        case userId = "user_id"
        case online
        case lastSeenAt = "last_seen_at"
    }
}
