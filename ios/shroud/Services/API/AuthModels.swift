import Foundation

// MARK: - Requests

/// `POST /auth/register` body.
struct RegisterRequest: Encodable, Equatable, Sendable {
    let username: String
    let password: String
    let deviceName: String?

    enum CodingKeys: String, CodingKey {
        case username
        case password
        case deviceName = "device_name"
    }
}

/// `POST /auth/login` body.
struct LoginRequest: Encodable, Equatable, Sendable {
    let username: String
    let password: String
    let deviceName: String?
    let deviceId: UUID?

    enum CodingKeys: String, CodingKey {
        case username
        case password
        case deviceName = "device_name"
        case deviceId = "device_id"
    }
}

// MARK: - Responses

/// Register / login success body (token shown once).
struct AuthSessionResponse: Decodable, Equatable, Sendable {
    let token: String
    let user: UserDTO
    let device: DeviceDTO
}

/// `GET /auth/me` body.
struct MeResponse: Decodable, Equatable, Sendable {
    let user: UserDTO
    let device: DeviceDTO
}

struct UserDTO: Decodable, Equatable, Sendable, Identifiable {
    let id: UUID
    let username: String
    /// Short public code for QR / deep links (not a secret).
    let shareCode: String

    enum CodingKeys: String, CodingKey {
        case id
        case username
        case shareCode = "share_code"
    }
}

struct DeviceDTO: Decodable, Equatable, Sendable, Identifiable {
    let id: UUID
    let name: String?
}
