import Foundation

/// Parses free-form invite text (share code, username, UUID, or `/u/{code}` link).
enum ContactInviteParser {
    nonisolated enum Invite: Equatable, Sendable {
        case userID(UUID)
        case shareCode(String)
        case username(String)
    }

    /// Official public share host for deep links / QR payloads.
    static let officialShareHost = "shroud.corespace.de"

    /// Builds a share URL for a user's short code using the current server config when possible.
    static func shareURL(
        code: String,
        configuration: ServerConfiguration = ServerConfigurationStore().load()
    ) -> URL {
        let normalized = normalizeShareCode(code)
        switch configuration.mode {
        case .official:
            return URL(string: "https://\(officialShareHost)/u/\(normalized)")!
        case .selfHosted:
            let host = configuration.host.trimmingCharacters(in: .whitespacesAndNewlines)
            if host.isEmpty || host == "127.0.0.1" || host == "localhost" {
                // Local API has no public web front; still encode a stable path for in-app scan.
                return URL(string: "https://\(officialShareHost)/u/\(normalized)")!
            }
            let scheme = configuration.useHTTPS ? "https" : "http"
            let port = configuration.port.trimmingCharacters(in: .whitespacesAndNewlines)
            if port.isEmpty || port == "80" || port == "443" {
                return URL(string: "\(scheme)://\(host)/u/\(normalized)")!
            }
            return URL(string: "\(scheme)://\(host):\(port)/u/\(normalized)")!
        }
    }

    static func normalizeShareCode(_ raw: String) -> String {
        raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "@"))
            .replacingOccurrences(of: "-", with: "")
            .replacingOccurrences(of: " ", with: "")
            .uppercased()
    }

    /// Interprets user-entered or scanned invite material.
    static func parse(_ raw: String) -> Invite? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        if let uuid = UUID(uuidString: trimmed) {
            return .userID(uuid)
        }

        if let fromURL = parseURL(trimmed) {
            return fromURL
        }

        // Bare share code (8–16 alnum after normalize).
        let code = normalizeShareCode(trimmed)
        if isShareCodeFormat(code) {
            return .shareCode(code)
        }

        // Username: 3–32 letters/digits/underscore (case folded later by API).
        let username = trimmed
            .trimmingCharacters(in: CharacterSet(charactersIn: "@"))
            .lowercased()
        if isUsernameFormat(username) {
            return .username(username)
        }

        return nil
    }

    private static func parseURL(_ raw: String) -> Invite? {
        // Accept full URLs and scheme-less hosts.
        let candidates: [String] = {
            if raw.contains("://") { return [raw] }
            if raw.lowercased().hasPrefix("shroud.") || raw.contains("/u/") {
                return ["https://\(raw)", "http://\(raw)"]
            }
            return []
        }()

        for candidate in candidates {
            guard let url = URL(string: candidate) else { continue }
            let path = url.path
            // /u/{code} or trailing slash variants
            let parts = path.split(separator: "/").map(String.init)
            if parts.count >= 2, parts[parts.count - 2].lowercased() == "u" {
                let code = normalizeShareCode(parts[parts.count - 1])
                if isShareCodeFormat(code) {
                    return .shareCode(code)
                }
            }
            // /api/v1/users/{uuid}
            if let last = parts.last, let uuid = UUID(uuidString: last) {
                return .userID(uuid)
            }
        }
        return nil
    }

    static func isShareCodeFormat(_ code: String) -> Bool {
        let len = code.count
        guard (8 ... 16).contains(len) else { return false }
        return code.unicodeScalars.allSatisfy { CharacterSet.alphanumerics.contains($0) }
            && code == code.uppercased()
    }

    private static func isUsernameFormat(_ username: String) -> Bool {
        let len = username.count
        guard (3 ... 32).contains(len) else { return false }
        return username.unicodeScalars.allSatisfy { scalar in
            CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789_").contains(scalar)
        }
    }
}
