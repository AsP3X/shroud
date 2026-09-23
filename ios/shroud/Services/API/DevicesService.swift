import Foundation

/// Linked device list and removal.
///
/// Human: The server caps an account at `deviceLimit` devices; this list is the only place to
/// get back under it. Removing a device revokes its sessions and deletes its row.
/// Agent: HTTP GET `/devices`, DELETE `/devices/{id}`; no key material involved.
struct DevicesService: Sendable {
    /// Mirrors `MAX_DEVICES_PER_USER` on the server.
    static let deviceLimit = 5

    private var client: APIClient { .makeConfiguredClient() }

    func list(token: String) async throws -> [LinkedDeviceDTO] {
        let response: DevicesListResponse = try await client.get(
            "devices",
            as: DevicesListResponse.self,
            bearerToken: token
        )
        return response.devices
    }

    func revoke(deviceID: UUID, token: String) async throws {
        try await client.deleteNoContent(
            path: "devices/\(deviceID.uuidString.lowercased())",
            bearerToken: token
        )
    }
}
