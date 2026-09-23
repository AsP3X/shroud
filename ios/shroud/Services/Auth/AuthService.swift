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
        let deviceName = await Self.currentDeviceName()
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
        let normalizedUsername = username.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let existing = sessionStore.load()
        let deviceName = await Self.currentDeviceName()
        let reusedDeviceID = existing?.deviceID
            ?? sessionStore.loadDeviceID(matchingUsername: normalizedUsername)
        let body = LoginRequest(
            username: normalizedUsername,
            password: password,
            deviceName: deviceName,
            deviceId: reusedDeviceID
        )
        let response: AuthSessionResponse = try await client.post(
            "auth/login",
            body: body,
            as: AuthSessionResponse.self
        )
        return try persist(response)
    }

    /// Clears the Keychain session immediately, then best-effort server revoke in the background.
    ///
    /// Local clear must not wait on the network: a hung `/auth/logout` used to leave the
    /// token on disk so force-quit mid-logout still restored a signed-in session.
    func logout() async {
        let token = sessionStore.load()?.token
        sessionStore.clear()
        guard let token else { return }

        let client = self.client
        // Fire-and-forget: UI and Keychain must not depend on server reachability.
        Task {
            try? await client.postNoContent(path: "auth/logout", bearerToken: token)
        }
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
            shareCode: response.user.shareCode,
            deviceID: response.device.id,
            deviceName: response.device.name
        )
        try sessionStore.save(session)
        sessionStore.saveDeviceAnchor(username: session.username, deviceID: session.deviceID)
        return session
    }

    /// Updates Keychain session fields from `/auth/me` (e.g. share code after migration).
    ///
    /// Human: The lock screen probes this every few seconds and again when the Face ID sheet
    /// closes, on the main actor. An unconditional save was ~16 Keychain round trips each time,
    /// landing in the unlock animation; an unchanged profile writes nothing.
    func refreshProfile(session: SessionStore.Session) async throws -> SessionStore.Session {
        let me = try await fetchMe(session: session)
        let updated = SessionStore.Session(
            token: session.token,
            userID: me.user.id,
            username: me.user.username,
            shareCode: me.user.shareCode,
            deviceID: me.device.id,
            deviceName: me.device.name ?? session.deviceName
        )
        guard updated != session else { return session }
        try sessionStore.save(updated)
        sessionStore.saveDeviceAnchor(username: updated.username, deviceID: updated.deviceID)
        return updated
    }

    /// UIDevice is main-actor state, so this hops rather than reading it off the caller's thread.
    @MainActor
    private static func currentDeviceName() -> String {
        UIDevice.current.name
    }
}
