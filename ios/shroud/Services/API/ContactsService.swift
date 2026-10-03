import Foundation

/// Contacts, requests, and user cards.
struct ContactsService: Sendable {
    private var client: APIClient { .makeConfiguredClient() }

    func listContacts(token: String) async throws -> [ContactItemDTO] {
        let response: ContactsListResponse = try await client.get(
            "contacts",
            as: ContactsListResponse.self,
            bearerToken: token
        )
        return response.contacts
    }

    func listIncomingRequests(token: String) async throws -> [ContactRequestDTO] {
        let response: ContactRequestsResponse = try await client.get(
            "contacts/requests",
            query: ["box": "incoming", "status": "pending"],
            as: ContactRequestsResponse.self,
            bearerToken: token
        )
        return response.requests
    }

    func createRequest(userID: UUID, token: String) async throws -> ContactRequestDTO {
        try await client.post(
            "contacts/requests",
            body: CreateContactRequestBody(userId: userID),
            as: ContactRequestDTO.self,
            bearerToken: token
        )
    }

    func acceptRequest(id: UUID, token: String) async throws {
        // Server returns a JSON body; ignore payload and accept any 2xx.
        struct Empty: Encodable {}
        let _: ContactRequestDTO = try await client.post(
            "contacts/requests/\(id.uuidString.lowercased())/accept",
            body: Empty(),
            as: ContactRequestDTO.self,
            bearerToken: token
        )
    }

    func rejectRequest(id: UUID, token: String) async throws {
        struct Empty: Encodable {}
        let _: ContactRequestDTO = try await client.post(
            "contacts/requests/\(id.uuidString.lowercased())/reject",
            body: Empty(),
            as: ContactRequestDTO.self,
            bearerToken: token
        )
    }

    func deleteContact(userID: UUID, token: String) async throws {
        try await client.deleteNoContent(
            path: "contacts/\(userID.uuidString.lowercased())",
            bearerToken: token
        )
    }

    func getUser(userID: UUID, token: String) async throws -> UserCardDTO {
        try await client.get(
            "users/\(userID.uuidString.lowercased())",
            as: UserCardDTO.self,
            bearerToken: token
        )
    }

    /// This account's username, sealed to a mutual contact. The server cannot read it.
    func putSealedName(userID: UUID, sealed: String, token: String) async throws {
        struct Body: Encodable { let sealed: String }
        try await client.putNoContent(
            path: "contacts/\(userID.uuidString.lowercased())/sealed-name",
            body: Body(sealed: sealed),
            bearerToken: token
        )
    }

    func getUserByShareCode(_ code: String, token: String) async throws -> UserCardDTO {
        let normalized = ContactInviteParser.normalizeShareCode(code)
        let encoded = normalized.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? normalized
        return try await client.get(
            "users/by-code/\(encoded)",
            as: UserCardDTO.self,
            bearerToken: token
        )
    }

    func presence(userID: UUID, token: String) async throws -> PresenceDTO {
        try await client.get(
            "presence/\(userID.uuidString.lowercased())",
            as: PresenceDTO.self,
            bearerToken: token
        )
    }
}
