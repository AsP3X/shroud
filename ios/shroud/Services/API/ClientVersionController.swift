import Foundation

/// Keeps the server's last word on this build and decides when to ask again.
///
/// Human: Checks at launch, on the way back from the background (at most every ten minutes),
/// when another server is saved, and from "Check again". A check that fails (offline, an older
/// server without the route, a bad answer) changes nothing. Dismissing "Update available" lasts
/// for this process only, per version: a cold launch or a newer release asks again.
/// Agent: CALLS `GET /client-version` without a session; READS nothing else. `RootView` presents
/// `prompt` (alert or `UpdateRequiredView`) and shares the controller through the environment;
/// About Shroud reads `updateStatus` and `serverVersion` and runs `.manual` checks.
@MainActor
@Observable
final class ClientVersionController {
    typealias Fetch = @Sendable (ServerConfiguration, String) async throws -> ClientVersionResponse

    /// What a check came to, for "Check again" feedback.
    enum Outcome: Equatable {
        case answered(ClientVersionStatus)
        case failed
        /// Throttled, or another check was already running.
        case skipped
    }

    /// The last answer the server gave; nil until one arrives (and after a server change).
    private(set) var answer: ClientVersionResponse?
    private(set) var dismissed: Set<String> = []
    private(set) var isChecking = false
    /// How the last finished check ended: `.answered` or `.failed`, never `.skipped`. Nil until
    /// one finishes (and after a server change).
    private(set) var lastOutcome: Outcome?
    /// This build's `CFBundleShortVersionString`.
    let currentVersion: String

    /// When the last answer arrived (its check started). Failed checks don't count.
    private var lastCheckAt: Date?
    /// Bumped on a server change so a check still running against the old server is dropped.
    private var generation = 0
    private let fetch: Fetch

    init(
        currentVersion: String = ClientVersionService.currentVersion,
        fetch: @escaping Fetch = { try await ClientVersionService().check(configuration: $0, version: $1) }
    ) {
        self.currentVersion = currentVersion
        self.fetch = fetch
    }

    var prompt: UpdatePrompt {
        ClientVersionPolicy.prompt(for: answer, dismissed: dismissed)
    }

    var isUpdateRequired: Bool {
        if case .required = prompt { return true }
        return false
    }

    /// The About page's status row. Unlike `prompt`, a dismissed "available" still shows.
    var updateStatus: UpdateCheckStatus {
        ClientVersionPolicy.status(answer: answer, lastCheckFailed: lastOutcome == .failed, isChecking: isChecking)
    }

    /// The server offers or needs a newer build: the About Shroud row's badge.
    var hasUpdate: Bool {
        guard let status = answer?.status else { return false }
        return status != .current
    }

    /// The server's release from its last answer; nil before one, or from a server too old to say.
    var serverVersion: String? {
        guard let version = answer?.serverVersion?.trimmingCharacters(in: .whitespacesAndNewlines),
              !version.isEmpty
        else { return nil }
        return version
    }

    /// Asks the server at `configuration` unless `trigger` is throttled or a check is running.
    @discardableResult
    func check(
        _ trigger: ClientVersionPolicy.Trigger,
        configuration: ServerConfiguration,
        now: Date = Date()
    ) async -> Outcome {
        if trigger == .serverChanged {
            // The old server's answer says nothing about the new one.
            generation += 1
            answer = nil
            lastOutcome = nil
            isChecking = false
        }
        guard !isChecking,
              ClientVersionPolicy.shouldCheck(trigger, lastCheck: lastCheckAt, now: now)
        else { return .skipped }
        isChecking = true
        let started = generation
        let result: ClientVersionResponse?
        do {
            result = try await fetch(configuration, currentVersion)
        } catch {
            result = nil
        }
        guard started == generation else { return .skipped }
        isChecking = false
        // Only an answer starts the ten minutes: offline on the way back, the next return asks again.
        guard let result else {
            lastOutcome = .failed
            return .failed
        }
        lastCheckAt = now
        answer = result
        lastOutcome = .answered(result.status)
        return .answered(result.status)
    }

    /// Later / OK / Update on the alert: this version stays quiet until the next launch.
    func dismissAvailable() {
        guard case let .available(latest, _) = prompt else { return }
        dismissed.insert(ClientVersionPolicy.dismissalKey(latest: latest))
    }
}
