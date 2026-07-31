import Foundation

/// REST client for 1:1 call signaling (`/calls/*`).
/// Human: Server relays opaque SDP/ICE only — never media or E2E call keys.
/// Agent: Contacts-only ring/accept/reject/hangup/signal; ICE from env.
struct CallsService: Sendable {
    private var client: APIClient { .makeConfiguredClient() }

    func iceServers(token: String) async throws -> [IceServerDTO] {
        let response: IceServersResponse = try await client.get(
            "calls/ice-servers",
            as: IceServersResponse.self,
            bearerToken: token
        )
        return response.iceServers
    }

    func createCall(
        peerUserID: UUID,
        modality: CallModality,
        sdpOffer: String? = nil,
        token: String
    ) async throws -> CallDTO {
        try await client.post(
            "calls",
            body: CreateCallRequest(
                peerUserId: peerUserID,
                modality: modality,
                sdpOffer: sdpOffer
            ),
            as: CallDTO.self,
            bearerToken: token
        )
    }

    func getCall(id: UUID, token: String) async throws -> CallDTO {
        try await client.get(
            "calls/\(id.uuidString.lowercased())",
            as: CallDTO.self,
            bearerToken: token
        )
    }

    func acceptCall(id: UUID, sdpAnswer: String? = nil, token: String) async throws -> CallDTO {
        try await client.post(
            "calls/\(id.uuidString.lowercased())/accept",
            body: AcceptCallRequest(sdpAnswer: sdpAnswer),
            as: CallDTO.self,
            bearerToken: token
        )
    }

    func rejectCall(id: UUID, token: String) async throws -> CallDTO {
        try await client.postEmpty(
            "calls/\(id.uuidString.lowercased())/reject",
            as: CallDTO.self,
            bearerToken: token
        )
    }

    func hangupCall(id: UUID, token: String) async throws -> CallDTO {
        try await client.postEmpty(
            "calls/\(id.uuidString.lowercased())/hangup",
            as: CallDTO.self,
            bearerToken: token
        )
    }

    func signal(
        callID: UUID,
        signalType: String,
        payload: String,
        token: String
    ) async throws {
        try await client.postNoContent(
            path: "calls/\(callID.uuidString.lowercased())/signal",
            body: CallSignalRequest(signalType: signalType, payload: payload),
            bearerToken: token
        )
    }
}
