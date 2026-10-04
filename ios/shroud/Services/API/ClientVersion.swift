import Foundation

/// What the server thinks of this build (`GET /client-version`).
nonisolated enum ClientVersionStatus: String, Decodable, Equatable, Sendable {
    case current
    case updateAvailable = "update_available"
    case updateRequired = "update_required"
}

/// `GET /client-version?platform=ios&version=…` answer. An unknown `status` fails to decode and is
/// ignored like any other failed check.
nonisolated struct ClientVersionResponse: Decodable, Equatable, Sendable {
    let status: ClientVersionStatus
    let latestVersion: String?
    /// Operator-set link to the new build (TestFlight, App Store, a website); may be missing.
    let updateURL: String?

    enum CodingKeys: String, CodingKey {
        case status
        case latestVersion = "latest_version"
        case updateURL = "update_url"
    }
}

/// What the app shows for the server's last answer.
nonisolated enum UpdatePrompt: Equatable, Sendable {
    case none
    /// The "Update available" alert; Later hides it for this process.
    case available(latest: String?, url: URL?)
    /// The blocking "Update required" screen over everything.
    case required(latest: String?, url: URL?)
}

/// Pure rules behind the update prompt: what to show, when to ask again, and the copy.
///
/// Human: The server decides; the app never compares versions itself. A failed check changes
/// nothing, so an offline phone keeps whatever the last answer was (usually: nothing shown).
nonisolated enum ClientVersionPolicy {
    /// Why a check runs.
    enum Trigger: Equatable, Sendable {
        case launch
        /// Back from the background. Throttled by `foregroundInterval`.
        case foreground
        /// "Check again" on the blocking screen.
        case manual
        /// Another server was saved in Server Settings.
        case serverChanged
    }

    /// Foreground re-checks run at most this often.
    static let foregroundInterval: TimeInterval = 10 * 60

    static func shouldCheck(_ trigger: Trigger, lastCheck: Date?, now: Date) -> Bool {
        guard trigger == .foreground, let lastCheck else { return true }
        return now.timeIntervalSince(lastCheck) >= foregroundInterval
    }

    /// The prompt for `answer`. An "available" whose version was dismissed in this process shows
    /// nothing; "required" ignores dismissals.
    static func prompt(for answer: ClientVersionResponse?, dismissed: Set<String>) -> UpdatePrompt {
        guard let answer else { return .none }
        let latest = answer.latestVersion.flatMap { $0.isEmpty ? nil : $0 }
        let url = updateURL(answer.updateURL)
        switch answer.status {
        case .current:
            return .none
        case .updateAvailable:
            return dismissed.contains(dismissalKey(latest: latest)) ? .none : .available(latest: latest, url: url)
        case .updateRequired:
            return .required(latest: latest, url: url)
        }
    }

    /// Dismissals are per latest version: a newer release prompts again.
    static func dismissalKey(latest: String?) -> String {
        latest ?? ""
    }

    /// The server's link, when it is an `https` URL with a host.
    ///
    /// Human: The link comes from the server, so anything else (a `tel:`, a custom scheme, plain
    /// http) is dropped, and the prompt falls back to "where you installed Shroud".
    static func updateURL(_ raw: String?) -> URL? {
        guard let raw = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
              let url = URL(string: raw),
              url.scheme?.lowercased() == "https",
              let host = url.host(), !host.isEmpty
        else { return nil }
        return url
    }

    // MARK: - Copy

    static func availableMessage(latest: String?, current: String) -> String {
        if let latest {
            return "Shroud \(latest) is available. You have \(current)."
        }
        return "A new version of Shroud is available. You have \(current)."
    }

    static func requiredMessage(latest: String?, hasUpdateURL: Bool) -> String {
        var message = "This version of Shroud no longer works with this server."
        if let latest {
            message += " Update to \(latest) to keep using it."
        } else {
            message += " Update to keep using it."
        }
        if !hasUpdateURL {
            message += " Get the new version where you installed Shroud."
        }
        return message
    }
}

/// Asks the configured server about this build. No session needed: it runs on Welcome and on the
/// lock screen too.
nonisolated struct ClientVersionService: Sendable {
    /// `CFBundleShortVersionString`, as the server compares it.
    static var currentVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    }

    func check(configuration: ServerConfiguration, version: String) async throws -> ClientVersionResponse {
        try await APIClient.makeConfiguredClient(configuration: configuration).get(
            "client-version",
            query: ["platform": "ios", "version": version],
            as: ClientVersionResponse.self
        )
    }
}
