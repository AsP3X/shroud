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

    enum CodingKeys: String, CodingKey {
        case usernameHash = "username_hash"
        case password
        case deviceId = "device_id"
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
