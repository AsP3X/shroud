import Foundation

/// Minimal HTTP client for the Shroud REST API (`/api/v1`).
/// Human: Views never call this directly — feature services wrap it.
/// Agent: HTTP GET/POST only; never sends key material or message plaintext.
final class APIClient: Sendable {
    private let baseURL: URL
    private let session: URLSession

    init(baseURL: URL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    /// Debug factory — simulator reaches host loopback.
    static func makeDebugClient() -> APIClient? {
        guard let url = URL(string: "http://127.0.0.1:8080/api/v1") else {
            return nil
        }
        return APIClient(baseURL: url)
    }

    /// Performs a GET and decodes JSON on success.
    func get<T: Decodable>(_ path: String, as type: T.Type) async throws -> T {
        let url = baseURL.appendingPathComponent(path.trimmingCharacters(in: CharacterSet(charactersIn: "/")))
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw APIError.transport("Invalid response")
        }

        guard (200 ..< 300).contains(http.statusCode) else {
            throw APIError.from(data: data, statusCode: http.statusCode)
        }

        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw APIError.decoding
        }
    }
}

/// Health probe payload matching the server route.
struct HealthResponse: Decodable, Equatable, Sendable {
    let status: String
    let database: String
}
