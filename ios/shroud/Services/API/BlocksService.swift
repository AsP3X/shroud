import Foundation

/// Block list management.
///
/// Human: Blocking is the stronger sibling of "delete chat for both" — that only removes the
/// contact link (either side can send a new request), while a block also stops the peer from
/// reaching this account at all.
/// Agent: HTTP GET/POST `/blocks`, DELETE `/blocks/{user_id}`; the server drops contact rows
/// and cancels pending requests as part of the block.
struct BlocksService: Sendable {
    private var client: APIClient { .makeConfiguredClient() }

    func list(token: String) async throws -> [BlockItemDTO] {
        let response: BlocksListResponse = try await client.get(
            "blocks",
            as: BlocksListResponse.self,
            bearerToken: token
        )
        return response.blocks
    }

    func block(userID: UUID, token: String) async throws {
        try await client.postNoContent(
            path: "blocks",
            body: BlockUserBody(userId: userID),
            bearerToken: token
        )
    }

    func unblock(userID: UUID, token: String) async throws {
        try await client.deleteNoContent(
            path: "blocks/\(userID.uuidString.lowercased())",
            bearerToken: token
        )
    }
}
