import Foundation

/// Canonical API error envelope from `/api/v1` — mirrors server `AppError` JSON.
nonisolated struct APIErrorResponse: Decodable, Equatable, Sendable {
    struct Detail: Decodable, Equatable, Sendable {
        let code: String
        let message: String
    }

    let error: Detail
}

/// Typed client-side API failure mapped from HTTP status + error envelope.
nonisolated enum APIError: Error, Equatable, Sendable {
    case transport(String)
    case server(code: String, message: String, statusCode: Int)
    case decoding

    /// True for HTTP 401 — the session token was rejected (not offline / unreachable).
    ///
    /// Transport failures and other 4xx/5xx must not be treated as auth failure so we never
    /// force-logout a user who is merely offline or hitting a bad server.
    var isAuthenticationFailure: Bool {
        if case let .server(_, _, statusCode) = self, statusCode == 401 {
            return true
        }
        return false
    }

    /// Builds an `APIError` from a failed HTTP response body when possible.
    static func from(data: Data, statusCode: Int) -> APIError {
        if let envelope = try? JSONDecoder().decode(APIErrorResponse.self, from: data) {
            return .server(
                code: envelope.error.code,
                message: envelope.error.message,
                statusCode: statusCode
            )
        }
        // Preserve 401 even without a JSON envelope so session policy can still revoke.
        if statusCode == 401 {
            return .server(
                code: "unauthorized",
                message: "Unauthorized",
                statusCode: 401
            )
        }
        return .transport("Request failed with status \(statusCode)")
    }
}
