import Foundation

/// Reads and writes the signed-in account's privacy flags (consent and visibility).
///
/// Agent: HTTP GET/PUT `/privacy/settings`; RETURNS PrivacySettingsDTO; no key material.
struct PrivacyService: Sendable {
    private var client: APIClient { .makeConfiguredClient() }

    func settings(token: String) async throws -> PrivacySettingsDTO {
        try await client.get(
            "privacy/settings",
            as: PrivacySettingsDTO.self,
            bearerToken: token
        )
    }

    /// Returns the settings as stored after the write, so callers can trust the toggle state.
    /// Fields left nil in `change` stay as they are.
    func update(_ change: UpdatePrivacySettingsBody, token: String) async throws -> PrivacySettingsDTO {
        try await client.put(
            "privacy/settings",
            body: change,
            as: PrivacySettingsDTO.self,
            bearerToken: token
        )
    }

    /// A new share code for this account; QR codes and links with the old one stop working.
    func rotateShareCode(token: String) async throws -> String {
        try await client.postEmpty(
            "users/me/share-code",
            as: ShareCodeDTO.self,
            bearerToken: token
        ).shareCode
    }
}
