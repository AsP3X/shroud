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

struct ConversationPeerDTO: Codable, Equatable, Sendable, Identifiable {
    let id: UUID
    let username: String
}

struct ConversationItemDTO: Codable, Equatable, Sendable, Identifiable {
    let id: UUID
    let peer: ConversationPeerDTO
    let createdAt: Date
    let lastMessageAt: Date?

    enum CodingKeys: String, CodingKey {
        case id
        case peer
        case createdAt = "created_at"
        case lastMessageAt = "last_message_at"
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
