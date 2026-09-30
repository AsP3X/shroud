import Foundation

/// Bridges nonisolated `APIClient` auth outcomes to the MainActor `SessionController`.
///
/// Bound once from `RootView` so every authenticated HTTP 401 (and success) is counted
/// without threading the session through every service.
@MainActor
enum SessionAuthBridge {
    static weak var controller: SessionController?
    /// The overlay wipe, so a silent push can run it while the app is in the background.
    static weak var deviceWipe: DeviceWipeController?

    /// A request that carried a Bearer token completed successfully (2xx).
    nonisolated static func noteAuthenticationSuccess() {
        Task { @MainActor in
            controller?.resetAuthenticationFailures()
        }
    }

    /// A request that carried a Bearer token was rejected with HTTP 401.
    /// Offline / unreachable servers must not call this (those are transport errors).
    nonisolated static func noteAuthenticationFailure() {
        Task { @MainActor in
            await controller?.recordAuthenticationFailure()
        }
    }

    /// The server said the account removed this iPhone (`DEVICE_REMOVED`, over HTTP or the
    /// socket) when it was shown `token`: wipe now, not after the 401 streak.
    nonisolated static func noteDeviceRemoved(token: String) {
        Task { @MainActor in
            controller?.recordDeviceRemoved(token: token)
        }
    }
}

/// App-wide session state (token never exposed to views as a free string for display).
@MainActor
@Observable
final class SessionController {
    /// Consecutive HTTP 401s on authenticated requests before we force-logout and wipe local data.
    /// Offline / transport failures never increment this counter.
    static let authenticationFailureLogoutThreshold = 3

    private(set) var session: SessionStore.Session?
    /// True after a successful `/auth/me` probe (or fresh login/register) this process.
    private(set) var sessionValidated = false
    /// Consecutive authentication failures (401 only). Reset on any successful authed request.
    private(set) var consecutiveAuthenticationFailures = 0
    /// When true, the next sign-out side effects must wipe identity keys + all local data.
    /// Set only by the repeated-auth-failure path (not user-initiated Log Out).
    private(set) var pendingFullLocalWipe = false

    private let authService: AuthService
    private var isForceLoggingOut = false
    /// When true, logout skips Keychain so unit tests never wipe a real session.
    private var usesEphemeralSession = false

    var isSignedIn: Bool { session != nil }
    var username: String? { session?.username }
    var userID: UUID? { session?.userID }
    var shareCode: String? { session?.shareCode }
    var bearerToken: String? { session?.token }

    init(authService: AuthService = AuthService()) {
        self.authService = authService
        self.session = authService.restoreSession()
    }

    func register(username: String, password: String) async throws {
        session = try await authService.register(username: username, password: password)
        sessionValidated = true
        resetAuthenticationFailures()
    }

    func login(username: String, password: String) async throws {
        session = try await authService.login(username: username, password: password)
        sessionValidated = true
        resetAuthenticationFailures()
    }

    func logout() async {
        // Keychain first (sync), then in-memory — so a force-quit mid-logout cannot restore a token.
        // Server revoke is fire-and-forget inside AuthService and never blocks this path.
        if !usesEphemeralSession {
            await authService.logout()
        }
        session = nil
        sessionValidated = false
        consecutiveAuthenticationFailures = 0
        isForceLoggingOut = false
    }

    /// Testing seam: inject a session without going through Keychain.
    func applySessionForTests(_ session: SessionStore.Session?) {
        usesEphemeralSession = true
        self.session = session
        sessionValidated = session != nil
        consecutiveAuthenticationFailures = 0
        pendingFullLocalWipe = false
        isForceLoggingOut = false
    }

    /// Probes `/auth/me`; does **not** logout on a single 401 — repeated 401s are handled by
    /// `recordAuthenticationFailure` (via `SessionAuthBridge` / `APIClient`).
    /// Offline / server blips keep the session. Also refreshes profile fields into Keychain.
    func validateSessionIfNeeded() async {
        guard let session else {
            sessionValidated = false
            return
        }
        do {
            let refreshed = try await authService.refreshProfile(session: session)
            // A logout or another login during the request owns the session now. Writing this
            // one back would sign a logged-out app in again, or swap accounts.
            guard self.session == session else { return }
            // Human: The lock screen probes this every few seconds and again when the Face ID
            // sheet closes, on the main actor. Saving every time was ~16 Keychain round trips in
            // the unlock animation, and a same-value write still invalidates every observer.
            if refreshed != session {
                if !usesEphemeralSession { try authService.saveRefreshedProfile(refreshed) }
                self.session = refreshed
            }
            sessionValidated = true
            // Success is also recorded by APIClient; reset here so unit paths without the bridge work.
            resetAuthenticationFailures()
        } catch let api as APIError where api.isAuthenticationFailure {
            // Bridge already counted this 401 from APIClient; keep session until threshold.
            sessionValidated = false
        } catch {
            // Offline / server blip / decoding — keep session; treat as not yet validated.
            sessionValidated = false
        }
    }

    /// Resets the consecutive 401 counter (call after any successful authenticated request).
    func resetAuthenticationFailures() {
        consecutiveAuthenticationFailures = 0
    }

    /// Records one real authentication rejection (HTTP 401 with a Bearer token).
    ///
    /// After `authenticationFailureLogoutThreshold` consecutive failures, marks
    /// `pendingFullLocalWipe` so RootView runs the same device wipe as Log Out.
    ///
    /// The session stays in memory and in the Keychain until that wipe's session step revokes
    /// it. Logging out here used to drop the token first, so the wipe could not tell an offline
    /// server from one that accepted the logout, and a kill before the overlay left the push
    /// token in place. The pending marker is written now, so that kill still finishes on the
    /// next launch. Unit tests use an ephemeral session and must not set the real marker.
    func recordAuthenticationFailure() async {
        guard session != nil, !isForceLoggingOut else { return }
        consecutiveAuthenticationFailures += 1
        guard consecutiveAuthenticationFailures >= Self.authenticationFailureLogoutThreshold else {
            return
        }
        markSessionEnded()
    }

    /// The account removed this iPhone. Its messages, keys and media must go now.
    ///
    /// Human: A plain 401 waits for `authenticationFailureLogoutThreshold` in a row so a server
    /// hiccup never costs anyone their history. `DEVICE_REMOVED` is the server saying on purpose
    /// that this device no longer belongs to the account, so one answer is enough; the wipe is
    /// the same one Log Out runs, and the marker finishes it on the next launch if iOS kills
    /// the app mid-way.
    ///
    /// Only for the session that got the answer: the server keeps saying `DEVICE_REMOVED` about an
    /// old token, and a late reply to a request made before a new login must not wipe it. While a
    /// wipe is already running (Log Out on a removed iPhone), nothing is queued behind it.
    func recordDeviceRemoved(token: String) {
        guard let session, session.token == token, !isForceLoggingOut else { return }
        if SessionAuthBridge.deviceWipe?.isPresented == true {
            isForceLoggingOut = true
            return
        }
        markSessionEnded()
    }

    /// Launch: a wipe the app was killed in is being finished. Its server call answers
    /// `DEVICE_REMOVED` for a removed iPhone, which must not start a second wipe over it.
    func beginInterruptedWipe() {
        isForceLoggingOut = true
    }

    private func markSessionEnded() {
        isForceLoggingOut = true
        pendingFullLocalWipe = true
        consecutiveAuthenticationFailures = 0
        if !usesEphemeralSession {
            UserDefaults.standard.set(true, forKey: DeviceDataWipe.pendingKey)
        }
    }

    /// Consumes the full-wipe flag for RootView sign-out side effects (one-shot).
    func consumePendingFullLocalWipe() -> Bool {
        let value = pendingFullLocalWipe
        pendingFullLocalWipe = false
        return value
    }

    /// Maps API errors to a short user-facing string.
    static func userMessage(for error: Error) -> String {
        if let api = error as? APIError {
            switch api {
            case let .server(_, message, _):
                return message
            case let .transport(message):
                return message
            case .decoding:
                return "Could not read the server response."
            }
        }
        if error as? PeerIdentityError == .changed {
            return "This contact's encryption key changed. Verify their safety number before sending."
        }
        return "Something went wrong. Try again."
    }
}
