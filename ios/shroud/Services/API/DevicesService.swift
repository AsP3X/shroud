import Foundation

/// Linked device list and removal.
///
/// Human: The server caps an account at `deviceLimit` devices; this list is the only place to
/// get back under it. Removing a device revokes its sessions and deletes its row.
/// Agent: HTTP GET `/devices`, DELETE `/devices/{id}` (with the account password),
/// PUT `/devices/{id}/name` (sealed only).
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

    /// Stores a name sealed with `DeviceNameSeal`; the server never sees it in the clear.
    func putName(deviceID: UUID, sealedName: String, token: String) async throws {
        try await client.putNoContent(
            path: "devices/\(deviceID.uuidString.lowercased())/name",
            body: PutDeviceNameRequest(sealedName: sealedName),
            bearerToken: token
        )
    }

    /// Removes another device of the account. The server asks for the account password
    /// (security audit L4): `204` removed, `401 INVALID_CREDENTIALS` wrong password (not counted
    /// toward the 401 streak, see `APIClient.sessionAuthNote`), `429` the budget it shares with
    /// password change and account deletion, `404` not a live device of the account.
    /// The password is sent once and not stored here.
    func revoke(deviceID: UUID, password: String, token: String) async throws {
        let (status, data) = try await client.response(
            "DELETE",
            path: Self.revokePath(deviceID),
            jsonBody: try JSONEncoder.api.encode(RemoveDeviceRequest(password: password)),
            bearerToken: token
        )
        guard (200 ..< 300).contains(status) else {
            throw APIError.from(data: data, statusCode: status)
        }
    }

    static func revokePath(_ deviceID: UUID) -> String {
        "devices/\(deviceID.uuidString.lowercased())"
    }
}
