import Foundation

/// A user this account has blocked (`GET /blocks`).
nonisolated struct BlockItemDTO: Codable, Equatable, Sendable, Identifiable {
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

nonisolated struct BlocksListResponse: Decodable, Equatable, Sendable {
    let blocks: [BlockItemDTO]
}

struct BlockUserBody: Encodable, Equatable, Sendable {
    let userId: UUID

    enum CodingKeys: String, CodingKey {
        case userId = "user_id"
    }
}
