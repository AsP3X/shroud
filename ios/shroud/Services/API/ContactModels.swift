import Foundation

struct UserCardDTO: Decodable, Equatable, Sendable, Identifiable {
    let id: UUID
    let username: String
}

struct ContactRequestDTO: Decodable, Equatable, Sendable, Identifiable {
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

struct ContactItemDTO: Decodable, Equatable, Sendable, Identifiable {
    var id: UUID { userId }
    let userId: UUID
    let username: String
    let createdAt: Date

    enum CodingKeys: String, CodingKey {
        case userId = "user_id"
        case username
        case createdAt = "created_at"
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
