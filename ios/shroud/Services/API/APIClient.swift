import Foundation

/// Minimal HTTP client for the Shroud REST API (`/api/v1`).
/// Human: Views never call this directly — feature services wrap it. Every request names the
/// build (`ClientIdentity`); a `426 UPDATE_REQUIRED` answer starts a version check.
/// Agent: HTTP JSON only; never sends key material or message plaintext. Talks only to
/// `baseURL`, the configured server.
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
    /// `timeoutIntervalForResource` bounds the whole transfer. Media can be up to 2 GiB,
    /// so a slow but moving upload is allowed an hour; a stall still fails on the idle timer.
    ///
    /// `waitsForConnectivity` stays off on purpose: it suppresses "cannot connect" and parks
    /// the request for up to `timeoutIntervalForResource`, which read as an app that loads
    /// forever instead of one that says the server is down.
    private static func makeSession() -> URLSession {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 3600
        config.waitsForConnectivity = false
        // Human: No HTTP disk cache. The default wrote every response — contact lists, message
        // envelopes, share-code lookups — into a plain SQLite file in Library/Caches, outside
        // the sealed stores; media and history have their own encrypted caches.
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
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

    /// Performs a PUT with a JSON body and decodes the response.
    func put<Body: Encodable, T: Decodable>(
        _ path: String,
        body: Body,
        as type: T.Type,
        bearerToken: String? = nil
    ) async throws -> T {
        let bodyData = try JSONEncoder.api.encode(body)
        let (data, http) = try await perform(
            path,
            method: "PUT",
            bodyData: bodyData,
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

    /// Performs a DELETE and returns the body as is — empty for `204` (e.g. removing a reaction
    /// that was already gone).
    func deleteRaw(path: String, bearerToken: String? = nil) async throws -> Data {
        let (data, http) = try await perform(
            path,
            method: "DELETE",
            bodyData: nil,
            bearerToken: bearerToken
        )
        try Self.throwIfNeeded(data: data, status: http.statusCode)
        return http.statusCode == 204 ? Data() : data
    }

    /// Performs a request and hands back the status and body as they are, for routes whose
    /// error answers carry data (a reaction's `409` holds the current record). Throws only when
    /// no answer came.
    func response(
        _ method: String,
        path: String,
        jsonBody: Data? = nil,
        query: [String: String]? = nil,
        bearerToken: String? = nil
    ) async throws -> (status: Int, data: Data) {
        let (data, http) = try await perform(
            path,
            method: method,
            bodyData: jsonBody,
            bearerToken: bearerToken,
            query: query
        )
        return (http.statusCode, data)
    }

    /// Performs a DELETE and decodes a JSON result body (e.g. chat delete outcome).
    func delete<T: Decodable>(
        path: String,
        query: [String: String]? = nil,
        as type: T.Type,
        bearerToken: String? = nil
    ) async throws -> T {
        let (data, http) = try await perform(
            path,
            method: "DELETE",
            bodyData: nil,
            bearerToken: bearerToken,
            query: query
        )
        try Self.throwIfNeeded(data: data, status: http.statusCode)
        return try Self.decode(T.self, from: data)
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

    // MARK: - Progress-reporting raw transfers
    //
    // These duplicate a few lines of `perform` on purpose: the progress variants need the
    // per-task delegate, which only the `delegate:` overloads of URLSession accept.

    /// PUT raw bytes, reporting how much of the body has left the device (0…1).
    func putRaw(
        path: String,
        body: Data,
        contentType: String,
        bearerToken: String? = nil,
        onProgress: @escaping @Sendable (Double) -> Void
    ) async throws {
        var request = makeRequest(path: path, method: "PUT", bearerToken: bearerToken)
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")

        let observer = TransferProgressObserver(direction: .upload, onProgress: onProgress)
        defer { observer.finish() }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.upload(for: request, from: body, delegate: observer)
        } catch {
            throw APIError.transport(error.localizedDescription)
        }
        guard let http = response as? HTTPURLResponse else {
            throw APIError.transport("Invalid response")
        }
        noteOutcome(path: path, status: http.statusCode, data: data, bearerToken: bearerToken)
        try Self.throwIfNeeded(data: data, status: http.statusCode)
    }

    /// GET raw bytes, reporting how much of the body has arrived (0…1).
    func getRaw(
        path: String,
        bearerToken: String? = nil,
        onProgress: @escaping @Sendable (Double) -> Void
    ) async throws -> Data {
        let request = makeRequest(path: path, method: "GET", bearerToken: bearerToken)

        let observer = TransferProgressObserver(direction: .download, onProgress: onProgress)
        defer { observer.finish() }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request, delegate: observer)
        } catch {
            throw APIError.transport(error.localizedDescription)
        }
        guard let http = response as? HTTPURLResponse else {
            throw APIError.transport("Invalid response")
        }
        noteOutcome(path: path, status: http.statusCode, data: data, bearerToken: bearerToken)
        try Self.throwIfNeeded(data: data, status: http.statusCode)
        return data
    }

    // MARK: - File-backed raw transfers
    //
    // Shared files can be 2 GB: the body streams from a file and the response lands in one, so
    // neither side of the transfer ever sits in memory.

    /// PUT the file at `fileURL` as the body, reporting how much has left the device (0…1).
    func putFile(
        path: String,
        fileURL: URL,
        contentType: String,
        bearerToken: String? = nil,
        onProgress: (@Sendable (Double) -> Void)? = nil
    ) async throws {
        var request = makeRequest(path: path, method: "PUT", bearerToken: bearerToken)
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")

        let observer = onProgress.map { TransferProgressObserver(direction: .upload, onProgress: $0) }
        defer { observer?.finish() }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.upload(for: request, fromFile: fileURL, delegate: observer)
        } catch {
            if error is CancellationError || (error as? URLError)?.code == .cancelled { throw CancellationError() }
            throw APIError.transport(error.localizedDescription)
        }
        guard let http = response as? HTTPURLResponse else {
            throw APIError.transport("Invalid response")
        }
        noteOutcome(path: path, status: http.statusCode, data: data, bearerToken: bearerToken)
        try Self.throwIfNeeded(data: data, status: http.statusCode)
    }

    /// GET raw bytes into a temporary file, reporting how much has arrived (0…1).
    ///
    /// Agent: RETURNS a file the caller owns and must move or delete.
    func downloadFile(
        path: String,
        bearerToken: String? = nil,
        onProgress: (@Sendable (Double) -> Void)? = nil
    ) async throws -> URL {
        let request = makeRequest(path: path, method: "GET", bearerToken: bearerToken)

        let observer = onProgress.map { TransferProgressObserver(direction: .download, onProgress: $0) }
        defer { observer?.finish() }

        let location: URL
        let response: URLResponse
        do {
            (location, response) = try await session.download(for: request, delegate: observer)
        } catch {
            if error is CancellationError || (error as? URLError)?.code == .cancelled { throw CancellationError() }
            throw APIError.transport(error.localizedDescription)
        }
        guard let http = response as? HTTPURLResponse else {
            try? FileManager.default.removeItem(at: location)
            throw APIError.transport("Invalid response")
        }
        guard (200 ..< 300).contains(http.statusCode) else {
            // An error body is a small JSON document, not the blob.
            let data = (try? Data(contentsOf: location)) ?? Data()
            try? FileManager.default.removeItem(at: location)
            noteOutcome(path: path, status: http.statusCode, data: data, bearerToken: bearerToken)
            try Self.throwIfNeeded(data: data, status: http.statusCode)
            throw APIError.transport("Unexpected status \(http.statusCode)")
        }
        noteOutcome(path: path, status: http.statusCode, data: Data(), bearerToken: bearerToken)
        return location
    }

    // MARK: - Internals

    /// Every request to the server starts here, so each one carries `ClientIdentity`.
    private func makeRequest(
        path: String,
        method: String,
        bearerToken: String?,
        query: [String: String]? = nil
    ) -> URLRequest {
        var request = URLRequest(url: resolveURL(path, query: query))
        request.httpMethod = method
        request.setValue("application/json, application/octet-stream, */*", forHTTPHeaderField: "Accept")
        ClientIdentity.apply(to: &request)
        if let bearerToken, !bearerToken.isEmpty {
            request.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
        }
        return request
    }

    /// Version policy: `426 UPDATE_REQUIRED` (with or without a Bearer) asks the server about this
    /// build at once, so "Update required" comes up. It never counts toward the 401 streak.
    ///
    /// Session policy: only authenticated requests contribute to the 401 streak.
    /// Login/register (no Bearer) must not force-logout an existing local session.
    /// `DEVICE_REMOVED` is final on the first answer: the account removed this iPhone.
    private func noteOutcome(path: String, status: Int, data: Data, bearerToken: String?) {
        if status == 426 {
            if Self.startsVersionCheck(path: path, status: status, data: data) {
                ClientVersionBridge.noteUpdateRequired()
            }
            return
        }
        guard bearerToken.map({ !$0.isEmpty }) == true else { return }
        if (200 ..< 300).contains(status) {
            SessionAuthBridge.noteAuthenticationSuccess()
        } else if status == 401 {
            if let bearerToken, APIError.from(data: data, statusCode: status).isDeviceRemoval {
                SessionAuthBridge.noteDeviceRemoved(token: bearerToken)
            } else {
                SessionAuthBridge.noteAuthenticationFailure()
            }
        }
    }

    /// A refusal of this build that should ask `/client-version` about it. Not when the refused
    /// request was that check itself: the server never gates it, and if a proxy did, asking
    /// again would only be refused again.
    static func startsVersionCheck(path: String, status: Int, data: Data) -> Bool {
        guard status == 426,
              path.trimmingCharacters(in: CharacterSet(charactersIn: "/")) != ClientVersionService.path
        else { return false }
        return APIError.from(data: data, statusCode: status).isUpdateRequired
    }

    private func perform(
        _ path: String,
        method: String,
        bodyData: Data?,
        bearerToken: String?,
        query: [String: String]? = nil,
        contentType: String? = "application/json"
    ) async throws -> (Data, HTTPURLResponse) {
        var request = makeRequest(path: path, method: method, bearerToken: bearerToken, query: query)
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

        noteOutcome(path: path, status: http.statusCode, data: data, bearerToken: bearerToken)

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
    /// Fresh decoder per call - `JSONDecoder` is not safe to share across concurrent tasks.
    nonisolated static var api: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let string = try container.decode(String.self)
            if let date = ISO8601DateFormatter.date(fromAPI: string) {
                return date
            }
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "Invalid ISO-8601 date: \(string)"
            )
        }
        return decoder
    }
}

extension JSONEncoder {
    nonisolated static var api: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }
}

extension ISO8601DateFormatter {
    nonisolated private static let lock = NSLock()

    nonisolated(unsafe) private static let api: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    nonisolated(unsafe) private static let apiFractional: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    /// `ISO8601DateFormatter` is not thread-safe; all API date parsing goes through this lock.
    nonisolated static func date(fromAPI string: String) -> Date? {
        lock.lock()
        defer { lock.unlock() }
        return apiFractional.date(from: string) ?? api.date(from: string)
    }

    nonisolated static func string(fromAPI date: Date) -> String {
        lock.lock()
        defer { lock.unlock() }
        return apiFractional.string(from: date)
    }
}

/// Health probe payload matching the server route.
nonisolated struct HealthResponse: Decodable, Equatable, Sendable {
    let status: String
    let database: String
}
