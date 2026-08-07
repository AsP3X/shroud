import Foundation

/// Reads and writes the signed-in account's privacy consent flags.
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
    func update(allowPeerChatDelete: Bool, token: String) async throws -> PrivacySettingsDTO {
        try await client.put(
            "privacy/settings",
            body: UpdatePrivacySettingsBody(allowPeerChatDelete: allowPeerChatDelete),
            as: PrivacySettingsDTO.self,
            bearerToken: token
        )
    }
}
