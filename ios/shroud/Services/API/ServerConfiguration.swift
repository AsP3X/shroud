import Foundation

/// How the app chooses the backend endpoint.
enum ServerConnectionMode: String, Codable, CaseIterable, Sendable {
    case official
    case selfHosted
}

/// User-editable server endpoint configuration (not a secret).
struct ServerConfiguration: Equatable, Codable, Sendable {
    var mode: ServerConnectionMode
    /// Host or IP without scheme (e.g. `127.0.0.1` or `api.example.com`).
    var host: String
    var port: String
    /// Path prefix including leading slash (e.g. `/api/v1`).
    var apiPath: String
    /// When true, self-hosted uses `https://`.
    var useHTTPS: Bool

    /// Built-in production / managed endpoint placeholder.
    /// Human: Replace with the real managed URL when the official service ships.
    static let officialBaseURLString = "https://api.shroud.app/api/v1"

    /// Debug defaults to local Docker Compose; Release defaults to official.
    static var `default`: ServerConfiguration {
        #if DEBUG
        ServerConfiguration(
            mode: .selfHosted,
            host: "127.0.0.1",
            port: "8080",
            apiPath: "/api/v1",
            useHTTPS: false
        )
        #else
        ServerConfiguration(
            mode: .official,
            host: "127.0.0.1",
            port: "8080",
            apiPath: "/api/v1",
            useHTTPS: false
        )
        #endif
    }

    /// Normalized API root used by `APIClient` (no trailing slash).
    var resolvedBaseURLString: String {
        switch mode {
        case .official:
            return Self.officialBaseURLString
        case .selfHosted:
            return Self.composeURL(host: host, port: port, apiPath: apiPath, useHTTPS: useHTTPS)
        }
    }

    var resolvedBaseURL: URL? {
        URL(string: resolvedBaseURLString)
    }

    /// Live preview string for the self-hosted form (may be invalid while typing).
    var selfHostedPreviewString: String {
        Self.composeURL(host: host, port: port, apiPath: apiPath, useHTTPS: useHTTPS)
    }

    static func composeURL(host: String, port: String, apiPath: String, useHTTPS: Bool) -> String {
        let trimmedHost = host.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedPort = port.trimmingCharacters(in: .whitespacesAndNewlines)
        var path = apiPath.trimmingCharacters(in: .whitespacesAndNewlines)
        if path.isEmpty {
            path = "/api/v1"
        }
        if !path.hasPrefix("/") {
            path = "/" + path
        }
        while path.count > 1, path.hasSuffix("/") {
            path.removeLast()
        }

        let scheme = useHTTPS ? "https" : "http"
        if trimmedHost.isEmpty {
            return "\(scheme)://…\(path)"
        }
        if trimmedPort.isEmpty {
            return "\(scheme)://\(trimmedHost)\(path)"
        }
        return "\(scheme)://\(trimmedHost):\(trimmedPort)\(path)"
    }

    /// Returns a user-facing validation error, or `nil` when save is allowed.
    func validationError() -> String? {
        switch mode {
        case .official:
            return nil
        case .selfHosted:
            let trimmedHost = host.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmedHost.isEmpty {
                return "Enter a host or IP address."
            }
            let trimmedPort = port.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmedPort.isEmpty {
                guard let value = Int(trimmedPort), (1 ... 65535).contains(value) else {
                    return "Port must be a number between 1 and 65535."
                }
            }
            guard resolvedBaseURL != nil else {
                return "That server address is not a valid URL."
            }
            return nil
        }
    }
}

/// Persists server configuration in UserDefaults (non-secret).
struct ServerConfigurationStore: Sendable {
    private let defaults: UserDefaults
    private let key = "shroud.server.configuration"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> ServerConfiguration {
        guard
            let data = defaults.data(forKey: key),
            let decoded = try? JSONDecoder().decode(ServerConfiguration.self, from: data)
        else {
            return .default
        }
        return decoded
    }

    func save(_ configuration: ServerConfiguration) {
        guard let data = try? JSONEncoder().encode(configuration) else { return }
        defaults.set(data, forKey: key)
    }
}
