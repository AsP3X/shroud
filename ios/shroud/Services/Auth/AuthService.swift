import Foundation
import UIKit

/// Talks to `/auth/*` and persists sessions.
/// Human: Encryption phrase stays on-device only — never sent here.
/// Agent: CALLS APIClient from ServerConfigurationStore; WRITES SessionStore; no phrase on wire.
nonisolated struct AuthService: Sendable {
    private let sessionStore: SessionStore

    nonisolated init(sessionStore: SessionStore = SessionStore()) {
        self.sessionStore = sessionStore
    }

    /// Always builds from the latest saved server configuration.
    private var client: APIClient {
        .makeConfiguredClient()
    }

    func restoreSession() -> SessionStore.Session? {
        sessionStore.load()
    }

    /// Registers a new account and stores the session in Keychain.
    func register(username: String, password: String) async throws -> SessionStore.Session {
        let deviceName = Self.currentDeviceName()
        let body = RegisterRequest(
            username: username.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
            password: password,
            deviceName: deviceName
        )
        let response: AuthSessionResponse = try await client.post(
            "auth/register",
            body: body,
            as: AuthSessionResponse.self
        )
        return try persist(response)
    }

    /// Logs in and stores the session; reuses `device_id` when Keychain still has one.
    func login(username: String, password: String) async throws -> SessionStore.Session {
        let existing = sessionStore.load()
        let deviceName = Self.currentDeviceName()
        let body = LoginRequest(
            username: username.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
            password: password,
            deviceName: deviceName,
            deviceId: existing?.deviceID
        )
        let response: AuthSessionResponse = try await client.post(
            "auth/login",
            body: body,
            as: AuthSessionResponse.self
        )
        return try persist(response)
    }

    /// Best-effort server logout, then clears Keychain.
    func logout() async {
        if let session = sessionStore.load() {
            try? await client.postNoContent(path: "auth/logout", bearerToken: session.token)
        }
        sessionStore.clear()
    }

    func fetchMe(session: SessionStore.Session) async throws -> MeResponse {
        try await client.get("auth/me", as: MeResponse.self, bearerToken: session.token)
    }

    // MARK: - Private

    private func persist(_ response: AuthSessionResponse) throws -> SessionStore.Session {
        let session = SessionStore.Session(
            token: response.token,
            userID: response.user.id,
            username: response.user.username,
            deviceID: response.device.id,
            deviceName: response.device.name
        )
        try sessionStore.save(session)
        return session
    }

    private static func currentDeviceName() -> String {
        UIDevice.current.name
    }
}
