import Foundation

/// App-wide session state (token never exposed to views as a free string for display).
@MainActor
@Observable
final class SessionController {
    private(set) var session: SessionStore.Session?
    /// True after a successful `/auth/me` probe (or fresh login/register) this process.
    private(set) var sessionValidated = false
    private let authService: AuthService

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
    }

    func login(username: String, password: String) async throws {
        session = try await authService.login(username: username, password: password)
        sessionValidated = true
    }

    func logout() async {
        await authService.logout()
        session = nil
        sessionValidated = false
    }

    /// Probes `/auth/me`; clears Keychain session on 401/unauthorized.
    /// Also refreshes profile fields (username, share code) into Keychain.
    func validateSessionIfNeeded() async {
        guard let session else {
            sessionValidated = false
            return
        }
        do {
            self.session = try await authService.refreshProfile(session: session)
            sessionValidated = true
        } catch let api as APIError {
            if case let .server(_, _, status) = api, status == 401 {
                await logout()
            } else {
                // Offline / server blip — keep session; treat as not yet validated.
                sessionValidated = false
            }
        } catch {
            sessionValidated = false
        }
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
        return "Something went wrong. Try again."
    }
}
