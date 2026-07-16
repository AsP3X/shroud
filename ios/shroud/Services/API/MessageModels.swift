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

    enum CodingKeys: String, CodingKey {
        case conversationId = "conversation_id"
        case messages
    }
}

struct ConversationPeerDTO: Decodable, Equatable, Sendable, Identifiable {
    let id: UUID
    let username: String
}

struct ConversationItemDTO: Decodable, Equatable, Sendable, Identifiable {
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
