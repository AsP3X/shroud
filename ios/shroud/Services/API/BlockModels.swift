import Foundation

/// A user this account has blocked (`GET /blocks`).
nonisolated struct BlockItemDTO: Codable, Equatable, Sendable, Identifiable {
    var id: UUID { userId }
    let userId: UUID
    var username: String
    let createdAt: Date

    enum CodingKeys: String, CodingKey {
        case userId = "user_id"
        case username
        case createdAt = "created_at"
    }

    init(userId: UUID, username: String = ContactNames.placeholder, createdAt: Date) {
        self.userId = userId
        self.username = username
        self.createdAt = createdAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        userId = try container.decode(UUID.self, forKey: .userId)
        username = try container.decodeIfPresent(String.self, forKey: .username) ?? ContactNames.placeholder
        createdAt = try container.decode(Date.self, forKey: .createdAt)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(userId, forKey: .userId)
        try container.encode(username, forKey: .username)
        try container.encode(createdAt, forKey: .createdAt)
    }
}

nonisolated struct BlocksListResponse: Decodable, Equatable, Sendable {
    let blocks: [BlockItemDTO]
}

struct BlockUserBody: Encodable, Equatable, Sendable {
    let userId: UUID

    enum CodingKeys: String, CodingKey {
        case userId = "user_id"
    }
}
