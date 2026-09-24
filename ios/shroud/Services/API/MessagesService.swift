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

    /// History page. Pass both `beforeCreatedAt` (the server's `created_at` text, not a
    /// re-formatted `Date`) and `beforeID` from the oldest item of the previous page.
    func listMessages(
        peerUserID: UUID,
        token: String,
        limit: Int = 50,
        beforeCreatedAt: String? = nil,
        beforeID: UUID? = nil
    ) async throws -> ListMessagesResponse {
        var query: [String: String] = [
            "peer_user_id": peerUserID.uuidString.lowercased(),
            "limit": String(limit),
        ]
        if let beforeCreatedAt, let beforeID {
            query["before_created_at"] = beforeCreatedAt
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

    /// Server settings for clients (the reaction limit).
    func clientConfig(token: String) async throws -> ClientConfigDTO {
        try await client.get("config", as: ClientConfigDTO.self, bearerToken: token)
    }

    /// What a reaction write did.
    enum ReactionWriteResult: Equatable, Sendable {
        /// Written. Nil for a removal that found nothing to remove (`204`).
        case saved(ReactionDTO?)
        /// Our record moved past `baseSeq` (another device of ours): nothing was written; this
        /// is the record now, a removal included.
        case changedElsewhere(ReactionDTO)
    }

    /// Sets or replaces our reactions on a message (the whole set), built on our record at
    /// `baseSeq` (0: we had none).
    func putReaction(
        messageID: UUID,
        ciphertext: Data,
        baseSeq: Int64,
        added: Bool,
        token: String
    ) async throws -> ReactionWriteResult {
        let body = try JSONEncoder.api.encode(PutReactionBody(
            ciphertext: ciphertext.base64EncodedString(),
            baseSeq: baseSeq,
            added: added
        ))
        let (status, data) = try await client.response(
            "PUT",
            path: "messages/\(messageID.uuidString.lowercased())/reaction",
            jsonBody: body,
            bearerToken: token
        )
        return try Self.reactionWrite(status: status, data: data)
    }

    /// Removes our reaction, the one at `baseSeq`.
    func deleteReaction(messageID: UUID, baseSeq: Int64, token: String) async throws -> ReactionWriteResult {
        let (status, data) = try await client.response(
            "DELETE",
            path: "messages/\(messageID.uuidString.lowercased())/reaction",
            query: ["base_seq": String(baseSeq)],
            bearerToken: token
        )
        return try Self.reactionWrite(status: status, data: data)
    }

    /// A reaction write's answer: `409 REACTION_CHANGED` carries our record as it is now.
    static func reactionWrite(status: Int, data: Data) throws -> ReactionWriteResult {
        if status == 409, let conflict = try? JSONDecoder.api.decode(ReactionConflictDTO.self, from: data) {
            return .changedElsewhere(conflict.current)
        }
        guard (200 ..< 300).contains(status) else {
            throw APIError.from(data: data, statusCode: status)
        }
        guard status != 204, !data.isEmpty else { return .saved(nil) }
        return .saved(try JSONDecoder.api.decode(ReactionDTO.self, from: data))
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
