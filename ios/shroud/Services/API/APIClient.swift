import Foundation

/// Minimal HTTP client for the Shroud REST API (`/api/v1`).
/// Human: Views never call this directly — feature services wrap it.
/// Agent: HTTP JSON only; never sends key material or message plaintext.
nonisolated final class APIClient: Sendable {
    private let baseURL: URL
    private let session: URLSession

    init(baseURL: URL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    /// Builds a client from the user's saved server configuration.
    static func makeConfiguredClient(
        configuration: ServerConfiguration = ServerConfigurationStore().load()
    ) -> APIClient {
        let fallback = URL(string: "http://127.0.0.1:8080/api/v1")!
        let url = configuration.resolvedBaseURL ?? fallback
        return APIClient(baseURL: url, session: Self.makeSession())
    }

    /// Fails fast on an unreachable server, but stays patient once bytes are moving.
    ///
    /// `timeoutIntervalForRequest` is an *idle* timer (it resets on every chunk), so 20s is
    /// generous for a multi-MB encrypted upload while still surfacing a dead server quickly;
    /// `timeoutIntervalForResource` is what actually bounds a long upload.
    ///
    /// `waitsForConnectivity` stays off on purpose: it suppresses "cannot connect" and parks
    /// the request for up to `timeoutIntervalForResource`, which read as an app that loads
    /// forever instead of one that says the server is down.
    private static func makeSession() -> URLSession {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 300
        config.waitsForConnectivity = false
        return URLSession(configuration: config)
    }

    /// Debug factory — local Docker Compose default.
    static func makeDebugClient() -> APIClient {
        makeConfiguredClient(configuration: .default)
    }

    /// Performs a GET and decodes JSON on success.
    func get<T: Decodable>(
        _ path: String,
        query: [String: String]? = nil,
        as type: T.Type,
        bearerToken: String? = nil
    ) async throws -> T {
        let (data, http) = try await perform(
            path,
            method: "GET",
            bodyData: nil,
            bearerToken: bearerToken,
            query: query
        )
        try Self.throwIfNeeded(data: data, status: http.statusCode)
        return try Self.decode(T.self, from: data)
    }

    /// Performs a POST with a JSON body and decodes the response.
    func post<Body: Encodable, T: Decodable>(
        _ path: String,
        body: Body,
        as type: T.Type,
        bearerToken: String? = nil
    ) async throws -> T {
        let bodyData = try JSONEncoder.api.encode(body)
        let (data, http) = try await perform(
            path,
            method: "POST",
            bodyData: bodyData,
            bearerToken: bearerToken
        )
        try Self.throwIfNeeded(data: data, status: http.statusCode)
        return try Self.decode(T.self, from: data)
    }

    /// Performs a POST that expects 2xx with no meaningful body (e.g. 204).
    func postNoContent(path: String, bearerToken: String? = nil) async throws {
        let (data, http) = try await perform(path, method: "POST", bodyData: nil, bearerToken: bearerToken)
        try Self.throwIfNeeded(data: data, status: http.statusCode)
    }

    /// Performs a POST with a JSON body and expects 2xx with no meaningful body (e.g. 204).
    func postNoContent<Body: Encodable>(
        path: String,
        body: Body,
        bearerToken: String? = nil
    ) async throws {
        let bodyData = try JSONEncoder.api.encode(body)
        let (data, http) = try await perform(
            path,
            method: "POST",
            bodyData: bodyData,
            bearerToken: bearerToken
        )
        try Self.throwIfNeeded(data: data, status: http.statusCode)
    }

    /// Performs a POST with no body and decodes a JSON response (e.g. hangup/reject).
    func postEmpty<T: Decodable>(
        _ path: String,
        as type: T.Type,
        bearerToken: String? = nil
    ) async throws -> T {
        let (data, http) = try await perform(
            path,
            method: "POST",
            bodyData: nil,
            bearerToken: bearerToken
        )
        try Self.throwIfNeeded(data: data, status: http.statusCode)
        return try Self.decode(T.self, from: data)
    }

    /// Performs a PUT with a JSON body and expects 2xx with no meaningful body (e.g. 204).
    func putNoContent<Body: Encodable>(
        path: String,
        body: Body,
        bearerToken: String? = nil
    ) async throws {
        let bodyData = try JSONEncoder.api.encode(body)
        let (data, http) = try await perform(
            path,
            method: "PUT",
            bodyData: bodyData,
            bearerToken: bearerToken
        )
        try Self.throwIfNeeded(data: data, status: http.statusCode)
    }

    /// Performs a DELETE that expects 2xx with no meaningful body.
    func deleteNoContent(
        path: String,
        query: [String: String]? = nil,
        bearerToken: String? = nil
    ) async throws {
        let (data, http) = try await perform(
            path,
            method: "DELETE",
            bodyData: nil,
            bearerToken: bearerToken,
            query: query
        )
        try Self.throwIfNeeded(data: data, status: http.statusCode)
    }

    /// PUT raw bytes (e.g. encrypted media) with an explicit Content-Type.
    func putRaw(
        path: String,
        body: Data,
        contentType: String,
        bearerToken: String? = nil
    ) async throws {
        let (data, http) = try await perform(
            path,
            method: "PUT",
            bodyData: body,
            bearerToken: bearerToken,
            contentType: contentType
        )
        try Self.throwIfNeeded(data: data, status: http.statusCode)
    }

    /// GET raw bytes (e.g. encrypted media).
    func getRaw(path: String, bearerToken: String? = nil) async throws -> Data {
        let (data, http) = try await perform(
            path,
            method: "GET",
            bodyData: nil,
            bearerToken: bearerToken,
            contentType: nil
        )
        try Self.throwIfNeeded(data: data, status: http.statusCode)
        return data
    }

    // MARK: - Internals

    private func perform(
        _ path: String,
        method: String,
        bodyData: Data?,
        bearerToken: String?,
        query: [String: String]? = nil,
        contentType: String? = "application/json"
    ) async throws -> (Data, HTTPURLResponse) {
        var request = URLRequest(url: resolveURL(path, query: query))
        request.httpMethod = method
        request.setValue("application/json, application/octet-stream, */*", forHTTPHeaderField: "Accept")
        if let bearerToken, !bearerToken.isEmpty {
            request.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
        }
        if let bodyData {
            if let contentType {
                request.setValue(contentType, forHTTPHeaderField: "Content-Type")
            }
            request.httpBody = bodyData
        }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            // Unreachable / offline — transport only. Never counts as auth failure.
            throw APIError.transport(error.localizedDescription)
        }

        guard let http = response as? HTTPURLResponse else {
            throw APIError.transport("Invalid response")
        }

        // Session policy: only authenticated requests contribute to the 401 streak.
        // Login/register (no Bearer) must not force-logout an existing local session.
        let hadBearer = bearerToken.map { !$0.isEmpty } ?? false
        if hadBearer {
            if (200 ..< 300).contains(http.statusCode) {
                SessionAuthBridge.noteAuthenticationSuccess()
            } else if http.statusCode == 401 {
                SessionAuthBridge.noteAuthenticationFailure()
            }
        }

        return (data, http)
    }

    private func resolveURL(_ path: String, query: [String: String]? = nil) -> URL {
        let trimmed = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        var url = trimmed.isEmpty ? baseURL : baseURL.appending(path: trimmed)
        if let query, !query.isEmpty {
            var components = URLComponents(url: url, resolvingAgainstBaseURL: false)
            components?.queryItems = query
                .map { URLQueryItem(name: $0.key, value: $0.value) }
                .sorted { $0.name < $1.name }
            if let withQuery = components?.url {
                url = withQuery
            }
        }
        return url
    }

    private static func throwIfNeeded(data: Data, status: Int) throws {
        guard (200 ..< 300).contains(status) else {
            throw APIError.from(data: data, statusCode: status)
        }
    }

    private static func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        do {
            return try JSONDecoder.api.decode(T.self, from: data)
        } catch {
            throw APIError.decoding
        }
    }
}

// MARK: - Coders

extension JSONDecoder {
    /// Shared API decoder (ISO-8601 dates with fractional seconds when present).
    nonisolated static let api: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let string = try container.decode(String.self)
            if let date = ISO8601DateFormatter.apiFractional.date(from: string)
                ?? ISO8601DateFormatter.api.date(from: string)
            {
                return date
            }
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "Invalid ISO-8601 date: \(string)"
            )
        }
        return decoder
    }()
}

extension JSONEncoder {
    nonisolated static let api: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }()
}

extension ISO8601DateFormatter {
    nonisolated(unsafe) static let api: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    nonisolated(unsafe) static let apiFractional: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()
}

/// Health probe payload matching the server route.
nonisolated struct HealthResponse: Decodable, Equatable, Sendable {
    let status: String
    let database: String
}
