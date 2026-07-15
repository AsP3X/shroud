import Foundation

/// App-wide session state (token never exposed to views as a free string for display).
@MainActor
@Observable
final class SessionController {
    private(set) var session: SessionStore.Session?
    private let authService: AuthService

    var isSignedIn: Bool { session != nil }
    var username: String? { session?.username }

    init(authService: AuthService = AuthService()) {
        self.authService = authService
        self.session = authService.restoreSession()
    }

    func register(username: String, password: String) async throws {
        session = try await authService.register(username: username, password: password)
    }

    func login(username: String, password: String) async throws {
        session = try await authService.login(username: username, password: password)
    }

    func logout() async {
        await authService.logout()
        session = nil
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
