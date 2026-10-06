import Foundation

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
    /// No device name goes with it: `DeviceNameSync` seals the name once the phrase is in.
    func register(username: String, password: String) async throws -> SessionStore.Session {
        let name = try UsernameHash.normalize(username)
        let body = RegisterRequest(usernameHash: UsernameHash.digest(name), password: password)
        let response: AuthSessionResponse = try await client.post(
            "auth/register",
            body: body,
            as: AuthSessionResponse.self
        )
        return try persist(response, username: name)
    }

    /// Logs in and stores the session; reuses `device_id` when Keychain still has one.
    ///
    /// Throws `DeviceLimitError` when every device slot is signed in. Once the phrase checked out
    /// against its `identityKey`, calling again with `replacingDeviceID` set to its
    /// `oldestDevice.id` logs that device out to make room; the `device_id` anchor is read the
    /// same way, so the retry sends the same one.
    func login(
        username: String,
        password: String,
        replacingDeviceID: UUID? = nil
    ) async throws -> SessionStore.Session {
        let normalizedUsername = try UsernameHash.normalize(username)
        let existing = sessionStore.load()
        let reusedDeviceID = existing?.deviceID
            ?? sessionStore.loadDeviceID(matchingUsername: normalizedUsername)
        let body = LoginRequest(
            usernameHash: UsernameHash.digest(normalizedUsername),
            password: password,
            deviceId: reusedDeviceID,
            replaceDeviceId: replacingDeviceID
        )
        let (status, data) = try await client.response(
            "POST",
            path: "auth/login",
            jsonBody: try JSONEncoder.api.encode(body)
        )
        let response = try Self.loginAnswer(status: status, data: data)
        return try persist(response, username: normalizedUsername)
    }

    /// A login's answer: the session, or `DeviceLimitError` for a `409 DEVICE_LIMIT` that names
    /// the device to log out and the identity key to check the phrase against. Without either
    /// (an older server, or an account that never published keys) no phrase check is possible,
    /// so it stays a plain `APIError`, shown inline as before.
    static func loginAnswer(status: Int, data: Data) throws -> AuthSessionResponse {
        if status == 409,
           let limit = try? JSONDecoder.api.decode(DeviceLimitResponse.self, from: data),
           limit.error.code == DeviceLimitError.code,
           let oldest = limit.oldestDevice,
           let identityKey = limit.identityKey.flatMap({ Data(base64Encoded: $0) }),
           !identityKey.isEmpty {
            throw DeviceLimitError(oldestDevice: oldest, identityKey: identityKey, message: limit.error.message)
        }
        guard (200 ..< 300).contains(status) else {
            throw APIError.from(data: data, statusCode: status)
        }
        do {
            return try JSONDecoder.api.decode(AuthSessionResponse.self, from: data)
        } catch {
            throw APIError.decoding
        }
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

    private func persist(_ response: AuthSessionResponse, username: String) throws -> SessionStore.Session {
        let session = SessionStore.Session(
            token: response.token,
            userID: response.user.id,
            username: username,
            shareCode: response.user.shareCode,
            deviceID: response.device.id
        )
        try sessionStore.save(session)
        sessionStore.saveDeviceAnchor(username: session.username, deviceID: session.deviceID)
        return session
    }

    /// Session fields as `/auth/me` reports them now (e.g. share code after migration). Does not
    /// write: the caller saves with `saveRefreshedProfile` once it knows the session it asked
    /// about is still the current one.
    func refreshProfile(session: SessionStore.Session) async throws -> SessionStore.Session {
        let me = try await fetchMe(session: session)
        let updated = SessionStore.Session(
            token: session.token,
            userID: me.user.id,
            username: session.username,
            shareCode: me.user.shareCode,
            deviceID: me.device.id
        )
        return updated
    }

    /// Writes a profile `refreshProfile` returned.
    func saveRefreshedProfile(_ session: SessionStore.Session) throws {
        try sessionStore.save(session)
        sessionStore.saveDeviceAnchor(username: session.username, deviceID: session.deviceID)
    }
}
