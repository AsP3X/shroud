import Foundation

struct UserCardDTO: Codable, Equatable, Sendable, Identifiable {
    let id: UUID
    /// A name this device already has. The server does not send one.
    var username: String
    /// Present on `/users/*` lookups; omitted on contact-request peer cards.
    let shareCode: String?

    enum CodingKeys: String, CodingKey {
        case id
        case username
        case shareCode = "share_code"
    }

    init(id: UUID, username: String = ContactNames.placeholder, shareCode: String? = nil) {
        self.id = id
        self.username = username
        self.shareCode = shareCode
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(UUID.self, forKey: .id)
        username = try container.decodeIfPresent(String.self, forKey: .username) ?? ContactNames.placeholder
        shareCode = try container.decodeIfPresent(String.self, forKey: .shareCode)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(username, forKey: .username)
        try container.encodeIfPresent(shareCode, forKey: .shareCode)
    }
}

struct ContactRequestDTO: Codable, Equatable, Sendable, Identifiable {
    let id: UUID
    let fromUserId: UUID
    let toUserId: UUID
    let status: String
    let createdAt: Date
    let respondedAt: Date?
    let user: UserCardDTO?

    enum CodingKeys: String, CodingKey {
        case id
        case fromUserId = "from_user_id"
        case toUserId = "to_user_id"
        case status
        case createdAt = "created_at"
        case respondedAt = "responded_at"
        case user
    }
}

struct ContactRequestsResponse: Decodable, Equatable, Sendable {
    let requests: [ContactRequestDTO]
}

struct ContactItemDTO: Codable, Equatable, Sendable, Identifiable {
    var id: UUID { userId }
    let userId: UUID
    var username: String
    let createdAt: Date
    /// The contact's username, sealed to this account. Absent until they publish it.
    var sealedName: String?

    enum CodingKeys: String, CodingKey {
        case userId = "user_id"
        case username
        case createdAt = "created_at"
        case sealedName = "sealed_name"
    }

    init(userId: UUID, username: String, createdAt: Date, sealedName: String? = nil) {
        self.userId = userId
        self.username = username
        self.createdAt = createdAt
        self.sealedName = sealedName
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        userId = try container.decode(UUID.self, forKey: .userId)
        username = try container.decodeIfPresent(String.self, forKey: .username) ?? ContactNames.placeholder
        createdAt = try container.decode(Date.self, forKey: .createdAt)
        sealedName = try container.decodeIfPresent(String.self, forKey: .sealedName)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(userId, forKey: .userId)
        try container.encode(username, forKey: .username)
        try container.encode(createdAt, forKey: .createdAt)
        try container.encodeIfPresent(sealedName, forKey: .sealedName)
    }
}

struct ContactsListResponse: Decodable, Equatable, Sendable {
    let contacts: [ContactItemDTO]
}

struct CreateContactRequestBody: Encodable, Equatable, Sendable {
    let userId: UUID

    enum CodingKeys: String, CodingKey {
        case userId = "user_id"
    }
}
