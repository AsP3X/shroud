import Foundation

/// Notification settings, chat mutes, read markers and the test push.
///
/// Agent: HTTP `/notifications/settings`, `/conversations/{peer}/mute|read`, `/push/token`,
/// `/push/test`; no key material except the push payload key (see `NotificationPayload`).
struct NotificationsService: Sendable {
    private var client: APIClient { .makeConfiguredClient() }

    func settings(token: String) async throws -> NotificationSettingsDTO {
        try await client.get("notifications/settings", as: NotificationSettingsDTO.self, bearerToken: token)
    }

    /// Returns the settings as stored after the write.
    func update(_ patch: NotificationSettingsPatch, token: String) async throws -> NotificationSettingsDTO {
        try await client.put("notifications/settings", body: patch, as: NotificationSettingsDTO.self, bearerToken: token)
    }

    /// Silences a chat on every device of the account; `seconds` nil = until unmuted.
    func mute(peerUserID: UUID, seconds: Int?, token: String) async throws -> MuteChatResponse {
        try await client.put(
            "conversations/\(peerUserID.uuidString.lowercased())/mute",
            body: MuteChatBody(seconds: seconds),
            as: MuteChatResponse.self,
            bearerToken: token
        )
    }

    func unmute(peerUserID: UUID, token: String) async throws {
        try await client.deleteNoContent(
            path: "conversations/\(peerUserID.uuidString.lowercased())/mute",
            bearerToken: token
        )
    }

    /// The chat was read on this device: its unread count clears everywhere, and the peer gets
    /// read receipts for what it covers.
    func markChatRead(peerUserID: UUID, token: String) async throws -> MarkChatReadResponse {
        try await client.postEmpty(
            "conversations/\(peerUserID.uuidString.lowercased())/read",
            as: MarkChatReadResponse.self,
            bearerToken: token
        )
    }

    func registerToken(_ body: PushTokenBody, token: String) async throws {
        try await client.putNoContent(path: "push/token", body: body, bearerToken: token)
    }

    func sendTest(token: String) async throws -> TestPushOutcomeDTO {
        try await client.postEmpty("push/test", as: TestPushOutcomeDTO.self, bearerToken: token)
    }
}
