import Foundation

/// Bridges nonisolated `APIClient` auth outcomes to the MainActor `SessionController`.
///
/// Bound once from `RootView` so every authenticated HTTP 401 (and success) is counted
/// without threading the session through every service.
@MainActor
enum SessionAuthBridge {
    static weak var controller: SessionController?

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
            self.session = try await authService.refreshProfile(session: session)
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
    /// After `authenticationFailureLogoutThreshold` consecutive failures, logs out and marks
    /// `pendingFullLocalWipe` so RootView clears chats, media, credentials, and crypto keys
    /// the same way as user Log Out, plus identity wipe.
    func recordAuthenticationFailure() async {
        guard session != nil, !isForceLoggingOut else { return }
        consecutiveAuthenticationFailures += 1
        guard consecutiveAuthenticationFailures >= Self.authenticationFailureLogoutThreshold else {
            return
        }
        isForceLoggingOut = true
        pendingFullLocalWipe = true
        consecutiveAuthenticationFailures = 0
        await logout()
        isForceLoggingOut = false
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
