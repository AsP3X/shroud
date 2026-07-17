import CryptoKit
import Foundation

/// Uploads and fetches public pre-key bundles (private keys stay in IdentityKeyStore).
struct KeyBundleService: Sendable {
    private var client: APIClient {
        .makeConfiguredClient()
    }

    func putBundle(_ request: PutKeyBundleRequest, bearerToken: String) async throws {
        try await client.putNoContent(path: "keys/bundle", body: request, bearerToken: bearerToken)
    }

    func status(bearerToken: String) async throws -> KeysStatusResponse {
        try await client.get("keys/status", as: KeysStatusResponse.self, bearerToken: bearerToken)
    }

    func fetchBundle(userID: UUID, bearerToken: String) async throws -> PeerKeyBundleResponse {
        try await client.get(
            "keys/bundle/\(userID.uuidString.lowercased())",
            as: PeerKeyBundleResponse.self,
            bearerToken: bearerToken
        )
    }

    /// All publishable device bundles for multi-device sealed send (one OTPK per device when available).
    func fetchBundles(userID: UUID, bearerToken: String) async throws -> PeerKeyBundlesResponse {
        try await client.get(
            "keys/bundles/\(userID.uuidString.lowercased())",
            as: PeerKeyBundlesResponse.self,
            bearerToken: bearerToken
        )
    }

    /// Fetches peer identity public key without consuming a one-time pre-key.
    func fetchIdentity(userID: UUID, bearerToken: String) async throws -> PeerIdentityResponse {
        try await client.get(
            "keys/identity/\(userID.uuidString.lowercased())",
            as: PeerIdentityResponse.self,
            bearerToken: bearerToken
        )
    }

    /// Builds the wire request from local identity material.
    static func makePutRequest(from material: IdentityKeyMaterial) throws -> PutKeyBundleRequest {
        let signature = try material.signedPreKeySignature()
        let otpks = material.oneTimePreKeys.map { key in
            OneTimePreKeyDTO(
                keyId: Int(key.keyID),
                publicKey: key.privateKey.publicKey.rawRepresentation.base64EncodedString()
            )
        }
        return PutKeyBundleRequest(
            registrationId: Int(material.registrationID),
            identityKey: material.identityPublicKeyData.base64EncodedString(),
            signedPreKey: SignedPreKeyDTO(
                keyId: Int(material.signedPreKeyID),
                publicKey: material.signedPreKeyPublicData.base64EncodedString(),
                signature: signature.base64EncodedString()
            ),
            oneTimePreKeys: otpks
        )
    }
}
