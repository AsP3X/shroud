import Foundation

/// REST client for 1:1 call signaling (`/calls/*`, docs/calls.md).
/// Human: The server rings devices and relays sealed signals — never media or keys.
/// Agent: Nonisolated so a PushKit wake can use it before any UI exists.
nonisolated struct CallsService: Sendable {
    private var client: APIClient { .makeConfiguredClient() }

    private func path(_ id: UUID, _ suffix: String = "") -> String {
        "calls/\(id.uuidString.lowercased())\(suffix)"
    }

    func iceServers(token: String) async throws -> [IceServerDTO] {
        let response: IceServersResponse = try await client.get(
            "calls/ice-servers",
            as: IceServersResponse.self,
            bearerToken: token
        )
        return response.iceServers
    }

    func createCall(peerUserID: UUID, modality: CallModality, token: String) async throws -> CallDTO {
        try await client.post(
            "calls",
            body: CreateCallRequest(peerUserId: peerUserID, modality: modality),
            as: CallDTO.self,
            bearerToken: token
        )
    }

    func getCall(id: UUID, token: String) async throws -> CallDTO {
        try await client.get(path(id), as: CallDTO.self, bearerToken: token)
    }

    /// The account's calls, newest first; `before` pages back from a call's `created_at`.
    func history(limit: Int = 50, before: Date? = nil, token: String) async throws -> [CallDTO] {
        var query = ["limit": String(limit)]
        if let before {
            query["before"] = ISO8601DateFormatter.string(fromAPI: before)
        }
        let response: CallListResponse = try await client.get(
            "calls",
            query: query,
            as: CallListResponse.self,
            bearerToken: token
        )
        return response.calls
    }

    func acceptCall(id: UUID, token: String) async throws -> CallDTO {
        try await client.post(path(id, "/accept"), body: AcceptCallRequest(), as: CallDTO.self, bearerToken: token)
    }

    func rejectCall(id: UUID, token: String) async throws -> CallDTO {
        try await client.postEmpty(path(id, "/reject"), as: CallDTO.self, bearerToken: token)
    }

    func hangupCall(id: UUID, token: String) async throws -> CallDTO {
        try await client.postEmpty(path(id, "/hangup"), as: CallDTO.self, bearerToken: token)
    }

    func heartbeat(id: UUID, token: String) async throws -> CallDTO {
        try await client.postEmpty(path(id, "/heartbeat"), as: CallDTO.self, bearerToken: token)
    }

    func signal(callID: UUID, signalType: String, payload: String, token: String) async throws {
        try await client.postNoContent(
            path: path(callID, "/signal"),
            body: CallSignalRequest(signalType: signalType, payload: payload),
            bearerToken: token
        )
    }
}
