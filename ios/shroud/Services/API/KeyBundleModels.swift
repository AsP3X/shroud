import Foundation

/// Request body for `PUT /keys/bundle`.
nonisolated struct PutKeyBundleRequest: Encodable, Equatable, Sendable {
    let registrationId: Int
    let identityKey: String
    let signedPreKey: SignedPreKeyDTO
    let oneTimePreKeys: [OneTimePreKeyDTO]

    enum CodingKeys: String, CodingKey {
        case registrationId = "registration_id"
        case identityKey = "identity_key"
        case signedPreKey = "signed_pre_key"
        case oneTimePreKeys = "one_time_pre_keys"
    }
}

nonisolated struct SignedPreKeyDTO: Codable, Equatable, Sendable {
    let keyId: Int
    let publicKey: String
    let signature: String

    enum CodingKeys: String, CodingKey {
        case keyId = "key_id"
        case publicKey = "public_key"
        case signature
    }
}

nonisolated struct OneTimePreKeyDTO: Codable, Equatable, Sendable {
    let keyId: Int
    let publicKey: String

    enum CodingKeys: String, CodingKey {
        case keyId = "key_id"
        case publicKey = "public_key"
    }
}

nonisolated struct KeysStatusResponse: Decodable, Equatable, Sendable {
    let deviceId: UUID
    let hasIdentity: Bool
    let signedPreKeyId: Int?
    let otpkCount: Int

    enum CodingKeys: String, CodingKey {
        case deviceId = "device_id"
        case hasIdentity = "has_identity"
        case signedPreKeyId = "signed_pre_key_id"
        case otpkCount = "otpk_count"
    }
}

nonisolated struct PeerKeyBundleResponse: Decodable, Equatable, Sendable {
    let userId: UUID
    let deviceId: UUID
    let registrationId: Int
    let identityKey: String
    let signedPreKey: SignedPreKeyDTO
    let oneTimePreKey: OneTimePreKeyDTO?

    enum CodingKeys: String, CodingKey {
        case userId = "user_id"
        case deviceId = "device_id"
        case registrationId = "registration_id"
        case identityKey = "identity_key"
        case signedPreKey = "signed_pre_key"
        case oneTimePreKey = "one_time_pre_key"
    }
}

/// One device entry from `GET /keys/bundles/:user_id`.
nonisolated struct PeerDeviceBundle: Decodable, Equatable, Sendable {
    let deviceId: UUID
    let registrationId: Int
    let identityKey: String
    let signedPreKey: SignedPreKeyDTO
    let oneTimePreKey: OneTimePreKeyDTO?

    enum CodingKeys: String, CodingKey {
        case deviceId = "device_id"
        case registrationId = "registration_id"
        case identityKey = "identity_key"
        case signedPreKey = "signed_pre_key"
        case oneTimePreKey = "one_time_pre_key"
    }
}

/// Multi-device fan-out response from `GET /keys/bundles/:user_id`.
nonisolated struct PeerKeyBundlesResponse: Decodable, Equatable, Sendable {
    let userId: UUID
    let bundles: [PeerDeviceBundle]

    enum CodingKeys: String, CodingKey {
        case userId = "user_id"
        case bundles
    }
}

/// Identity-only response from `GET /keys/identity/:user_id` (no OTPK consume).
nonisolated struct PeerIdentityResponse: Decodable, Equatable, Sendable {
    let userId: UUID
    let deviceId: UUID
    let registrationId: Int
    let identityKey: String

    enum CodingKeys: String, CodingKey {
        case userId = "user_id"
        case deviceId = "device_id"
        case registrationId = "registration_id"
        case identityKey = "identity_key"
    }
}
