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
    /// The server's own release, for About Shroud. Servers before it was added don't send it.
    let serverVersion: String?

    init(status: ClientVersionStatus, latestVersion: String?, updateURL: String?, serverVersion: String? = nil) {
        self.status = status
        self.latestVersion = latestVersion
        self.updateURL = updateURL
        self.serverVersion = serverVersion
    }

    enum CodingKeys: String, CodingKey {
        case status
        case latestVersion = "latest_version"
        case updateURL = "update_url"
        case serverVersion = "server_version"
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

/// The update status row on About Shroud.
nonisolated enum UpdateCheckStatus: Equatable, Sendable {
    case checking
    case current
    /// The server offers a newer build; shown even after the alert was dismissed.
    case available(latest: String?, url: URL?)
    /// The blocking screen is up anyway; the row just names it.
    case required
    /// The last check got no answer (offline, an older server, a bad reply).
    case failed
    /// No check has finished yet.
    case unknown
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
        /// The server refused a request with `426 UPDATE_REQUIRED`: this build is below its
        /// minimum. Asks at once, so the blocking screen comes up, whatever other checks
        /// answered just before; throttled only against the previous `.refused` check.
        case refused
    }

    /// Foreground re-checks run at most this often.
    static let foregroundInterval: TimeInterval = 10 * 60

    /// `.refused` checks start at most this often, counted from the last one that started,
    /// answered or not. Every request after the first is refused too; this keeps a server whose
    /// `/client-version` disagrees with its gate (or fails) from being asked once per refused
    /// request.
    static let refusedInterval: TimeInterval = 30

    /// `lastCheck` is when the last answer arrived for every trigger but `.refused`, and when the
    /// last `.refused` check started for `.refused`.
    static func shouldCheck(_ trigger: Trigger, lastCheck: Date?, now: Date) -> Bool {
        guard let lastCheck else { return true }
        switch trigger {
        case .foreground:
            return now.timeIntervalSince(lastCheck) >= foregroundInterval
        case .refused:
            return now.timeIntervalSince(lastCheck) >= refusedInterval
        case .launch, .manual, .serverChanged:
            return true
        }
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

    /// The About row for the last answer. A running check wins; then a failed last check, even
    /// over an older answer, so "Check for Updates" never looks as if it had worked.
    static func status(answer: ClientVersionResponse?, lastCheckFailed: Bool, isChecking: Bool) -> UpdateCheckStatus {
        if isChecking { return .checking }
        if lastCheckFailed { return .failed }
        guard let answer else { return .unknown }
        switch answer.status {
        case .current:
            return .current
        case .updateAvailable:
            return .available(latest: answer.latestVersion.flatMap { $0.isEmpty ? nil : $0 }, url: updateURL(answer.updateURL))
        case .updateRequired:
            return .required
        }
    }

    // MARK: - Copy

    static func statusTitle(_ status: UpdateCheckStatus) -> String {
        switch status {
        case .checking:
            return "Checking for updates…"
        case .current:
            return "Shroud is up to date"
        case let .available(latest, _):
            if let latest { return "Version \(latest) is available" }
            return "A new version is available"
        case .required:
            return "Update required"
        case .failed:
            return "Couldn’t check for updates"
        case .unknown:
            return "Updates are checked automatically"
        }
    }

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

/// Names this build on every request to the Shroud API: `X-Shroud-Client: ios/<version>`.
///
/// Human: A server with `IOS_MIN_VERSION` set refuses requests from older builds (or with no
/// header) with `426 UPDATE_REQUIRED`; `ClientVersionBridge` turns that into a version check,
/// which brings up the blocking "Update required" screen.
/// Agent: Set by `APIClient` (every request it builds) and `RealtimeClient` (the `/ws`
/// upgrade), the only code that talks to the configured server. Never on requests to other
/// hosts: link previews, TURN, anything third-party.
nonisolated enum ClientIdentity {
    static let headerField = "X-Shroud-Client"
    /// `ios/` + `CFBundleShortVersionString`, the version `/client-version` compares too.
    static let headerValue = "ios/\(ClientVersionService.currentVersion)"

    static func apply(to request: inout URLRequest) {
        request.setValue(headerValue, forHTTPHeaderField: headerField)
    }
}

/// Asks the configured server about this build. No session needed: it runs on Welcome and on the
/// lock screen too.
nonisolated struct ClientVersionService: Sendable {
    /// `CFBundleShortVersionString`, as the server compares it.
    static var currentVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    }

    /// `CFBundleVersion`, the build number shown next to the version on About Shroud.
    static var buildNumber: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0"
    }

    /// The route, relative to the API base. `APIClient` never turns a 426 on it into another check.
    static let path = "client-version"

    func check(configuration: ServerConfiguration, version: String) async throws -> ClientVersionResponse {
        try await APIClient.makeConfiguredClient(configuration: configuration).get(
            Self.path,
            query: ["platform": "ios", "version": version],
            as: ClientVersionResponse.self
        )
    }
}
