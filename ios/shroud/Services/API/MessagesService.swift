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

    /// History page. Pass both `beforeCreatedAt` and `beforeID` (oldest of previous page) to walk older.
    func listMessages(
        peerUserID: UUID,
        token: String,
        limit: Int = 50,
        beforeCreatedAt: Date? = nil,
        beforeID: UUID? = nil
    ) async throws -> ListMessagesResponse {
        var query: [String: String] = [
            "peer_user_id": peerUserID.uuidString.lowercased(),
            "limit": String(limit),
        ]
        if let beforeCreatedAt, let beforeID {
            query["before_created_at"] = ISO8601DateFormatter.string(fromAPI: beforeCreatedAt)
            query["before_id"] = beforeID.uuidString.lowercased()
        }
        return try await client.get(
            "messages",
            query: query,
            as: ListMessagesResponse.self,
            bearerToken: token
        )
    }

    func send(_ body: SendMessageRequest, token: String) async throws -> MessageDTO {
        try await client.post("messages", body: body, as: MessageDTO.self, bearerToken: token)
    }

    /// Deletes a message. `.me` adds a hide row (history pages skip it from then on);
    /// `.everyone` tombstones it for both sides and is rejected for anyone but the sender.
    func delete(messageID: UUID, scope: MessageDeleteScope, token: String) async throws {
        try await client.deleteNoContent(
            path: "messages/\(messageID.uuidString.lowercased())",
            query: ["scope": scope.rawValue],
            bearerToken: token
        )
    }

    /// Deletes a whole chat. `.me` moves this account's clear watermark; `.everyone` also
    /// unsends our messages for the peer, clears their copy when they allowed it, and drops
    /// the contact link so a later chat starts fresh.
    func deleteConversation(
        peerUserID: UUID,
        scope: ConversationDeleteScope,
        token: String
    ) async throws -> DeleteConversationResponse {
        try await client.delete(
            path: "conversations/\(peerUserID.uuidString.lowercased())",
            query: ["scope": scope.rawValue],
            as: DeleteConversationResponse.self,
            bearerToken: token
        )
    }

    /// Sets or replaces our reaction on a message.
    func putReaction(messageID: UUID, ciphertext: Data, token: String) async throws -> ReactionDTO {
        try await client.put(
            "messages/\(messageID.uuidString.lowercased())/reaction",
            body: PutReactionBody(ciphertext: ciphertext.base64EncodedString()),
            as: ReactionDTO.self,
            bearerToken: token
        )
    }

    /// Removes our reaction. Nil when there was none (`204`).
    func deleteReaction(messageID: UUID, token: String) async throws -> ReactionDTO? {
        let data = try await client.deleteRaw(
            path: "messages/\(messageID.uuidString.lowercased())/reaction",
            bearerToken: token
        )
        guard !data.isEmpty else { return nil }
        return try JSONDecoder.api.decode(ReactionDTO.self, from: data)
    }

    /// Reaction changes in the chat with `peerUserID` after `afterSeq`, oldest first.
    func reactionChanges(
        peerUserID: UUID,
        afterSeq: Int64,
        token: String,
        limit: Int = 200
    ) async throws -> ReactionChangesResponse {
        try await client.get(
            "conversations/\(peerUserID.uuidString.lowercased())/reactions",
            query: ["after_seq": String(afterSeq), "limit": String(limit)],
            as: ReactionChangesResponse.self,
            bearerToken: token
        )
    }

    /// Reactions to our messages in the chat with `peerUserID` are seen up to `upToSeq`.
    @discardableResult
    func markReactionsSeen(peerUserID: UUID, upToSeq: Int64, token: String) async throws -> MarkReactionsSeenResponse {
        try await client.post(
            "conversations/\(peerUserID.uuidString.lowercased())/reactions/seen",
            body: MarkReactionsSeenBody(upToSeq: upToSeq),
            as: MarkReactionsSeenResponse.self,
            bearerToken: token
        )
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

    /// Marks all inbound messages from `peerUserID` up to `upToMessageID` as read.
    func markReadBulk(peerUserID: UUID, upToMessageID: UUID, token: String) async throws -> MarkReadBulkResponse {
        try await client.post(
            "messages/read",
            body: MarkReadBulkBody(peerUserId: peerUserID, upToMessageId: upToMessageID),
            as: MarkReadBulkResponse.self,
            bearerToken: token
        )
    }
}
