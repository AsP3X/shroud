import Foundation

// MARK: - Requests

/// `POST /auth/register` body. The name is a SHA-256 digest. No device name: it is sealed later.
nonisolated struct RegisterRequest: Encodable, Equatable, Sendable {
    let usernameHash: String
    let password: String

    enum CodingKeys: String, CodingKey {
        case usernameHash = "username_hash"
        case password
    }
}

/// `POST /auth/login` body. The name is a SHA-256 digest.
nonisolated struct LoginRequest: Encodable, Equatable, Sendable {
    let usernameHash: String
    let password: String
    let deviceId: UUID?
    /// The `oldest_device` of a `DEVICE_LIMIT` answer, sent once the user agreed to log it out.
    /// The server ignores it while a slot is free. Omitted from the JSON when nil.
    var replaceDeviceId: UUID? = nil

    enum CodingKeys: String, CodingKey {
        case usernameHash = "username_hash"
        case password
        case deviceId = "device_id"
        case replaceDeviceId = "replace_device_id"
    }
}

// MARK: - Responses

/// Register / login success body (token shown once).
nonisolated struct AuthSessionResponse: Decodable, Equatable, Sendable {
    let token: String
    let user: UserDTO
    let device: DeviceDTO
}

/// `GET /auth/me` body.
nonisolated struct MeResponse: Decodable, Equatable, Sendable {
    let user: UserDTO
    let device: DeviceDTO
}

nonisolated struct UserDTO: Decodable, Equatable, Sendable, Identifiable {
    let id: UUID
    /// Absent. The name stays on the device that typed it.
    let username: String?
    /// Short public code for QR / deep links (not a secret).
    let shareCode: String

    enum CodingKeys: String, CodingKey {
        case id
        case username
        case shareCode = "share_code"
    }
}

nonisolated struct DeviceDTO: Decodable, Equatable, Sendable, Identifiable {
    let id: UUID
    /// Base64, sealed by the account's devices (`DeviceNameSeal`); nil until one names it.
    let sealedName: String?

    enum CodingKeys: String, CodingKey {
        case id
        case sealedName = "sealed_name"
    }
}

/// One device of the account, from a login's `409 DEVICE_LIMIT`.
///
/// Human: The name is sealed with the 12-word phrase, so the login screen opens it only once the
/// phrase checked out (`DeviceNameSeal` with the phrase's history key), as Settings › Devices does.
nonisolated struct DeviceLimitDeviceDTO: Decodable, Equatable, Sendable, Identifiable {
    let id: UUID
    /// Base64, sealed by the account's devices; nil until one names it.
    var sealedName: String? = nil
    let createdAt: Date
    /// Absent for a device that never made an authenticated request.
    let lastSeenAt: Date?

    enum CodingKeys: String, CodingKey {
        case id
        case sealedName = "sealed_name"
        case createdAt = "created_at"
        case lastSeenAt = "last_seen_at"
    }
}

/// A login's `409 DEVICE_LIMIT` body: the usual error envelope, every device of the account
/// (least recently active first; a retry with `replace_device_id` may name any of them), the
/// oldest one again, and the account's published identity key (standard Base64), which the
/// phrase must derive before the app offers that retry. A server before the device list sends
/// only `oldest_device`; an older one neither. An account whose devices never published keys has
/// no `identity_key`.
nonisolated struct DeviceLimitResponse: Decodable, Equatable, Sendable {
    let error: APIErrorResponse.Detail
    let oldestDevice: DeviceLimitDeviceDTO?
    let devices: [DeviceLimitDeviceDTO]?
    let identityKey: String?

    enum CodingKeys: String, CodingKey {
        case error
        case oldestDevice = "oldest_device"
        case devices
        case identityKey = "identity_key"
    }
}

/// Every device slot of the account is signed in. Once the encryption phrase derives
/// `identityKey`, logging in again with `replace_device_id` set to one of `devices` logs that
/// device out to make room.
nonisolated struct DeviceLimitError: Error, Equatable, Sendable {
    static let code = "DEVICE_LIMIT"

    /// Least recently active first, never empty: a server without the list gives just the oldest.
    let devices: [DeviceLimitDeviceDTO]
    /// The account's X25519 identity public key (raw bytes).
    let identityKey: Data
    /// The server's message, for a caller that only shows text.
    let message: String

    var oldestDevice: DeviceLimitDeviceDTO { devices[0] }
}
