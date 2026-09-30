import Foundation

/// One linked device on the account (`GET /devices`).
///
/// Human: A row outlives a logout — the server keeps it so the next sign-in can reuse it —
/// so an old entry here is not proof that someone is still signed in there.
nonisolated struct LinkedDeviceDTO: Decodable, Equatable, Sendable, Identifiable {
    let id: UUID
    /// Base64, sealed by the account's devices; open with `DeviceNameSeal.open`.
    let sealedName: String?
    let createdAt: Date
    let lastSeenAt: Date?
    let isCurrent: Bool

    enum CodingKeys: String, CodingKey {
        case id
        case sealedName = "sealed_name"
        case createdAt = "created_at"
        case lastSeenAt = "last_seen_at"
        case isCurrent = "is_current"
    }
}

nonisolated struct DevicesListResponse: Decodable, Equatable, Sendable {
    let devices: [LinkedDeviceDTO]
}

/// `PUT /devices/{id}/name` body.
nonisolated struct PutDeviceNameRequest: Encodable, Equatable, Sendable {
    let sealedName: String

    enum CodingKeys: String, CodingKey {
        case sealedName = "sealed_name"
    }
}
