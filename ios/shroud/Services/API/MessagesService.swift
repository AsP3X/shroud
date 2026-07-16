import Foundation

/// Message send/history and conversations list.
struct MessagesService: Sendable {
    private var client: APIClient { .makeConfiguredClient() }

    func listConversations(token: String) async throws -> [ConversationItemDTO] {
        let response: ConversationsResponse = try await client.get(
            "conversations",
            as: ConversationsResponse.self,
            bearerToken: token
        )
        return response.conversations
    }

    func listMessages(peerUserID: UUID, token: String, limit: Int = 50) async throws -> ListMessagesResponse {
        try await client.get(
            "messages",
            query: [
                "peer_user_id": peerUserID.uuidString.lowercased(),
                "limit": String(limit),
            ],
            as: ListMessagesResponse.self,
            bearerToken: token
        )
    }

    func send(_ body: SendMessageRequest, token: String) async throws -> MessageDTO {
        try await client.post("messages", body: body, as: MessageDTO.self, bearerToken: token)
    }

    func markDelivered(messageID: UUID, token: String) async throws {
        try await client.postNoContent(
            path: "messages/\(messageID.uuidString.lowercased())/delivered",
            bearerToken: token
        )
    }

    func markRead(messageID: UUID, token: String) async throws {
        try await client.postNoContent(
            path: "messages/\(messageID.uuidString.lowercased())/read",
            bearerToken: token
        )
    }
}
