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

    /// Builds an `APIError` from a failed HTTP response body when possible.
    static func from(data: Data, statusCode: Int) -> APIError {
        if let envelope = try? JSONDecoder().decode(APIErrorResponse.self, from: data) {
            return .server(
                code: envelope.error.code,
                message: envelope.error.message,
                statusCode: statusCode
            )
        }
        return .transport("Request failed with status \(statusCode)")
    }
}
