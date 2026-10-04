import Foundation
import Testing
@testable import shroud

/// The "newer version released" prompt: the server's answer, what it shows, dismissals, throttling.
@MainActor
struct ClientVersionTests {
    private let config = ServerConfiguration.default
    private let start = Date(timeIntervalSince1970: 1_000_000)

    private func answer(_ status: ClientVersionStatus, latest: String? = "1.4", url: String? = "https://testflight.apple.com/join/abc") -> ClientVersionResponse {
        ClientVersionResponse(status: status, latestVersion: latest, updateURL: url)
    }

    // MARK: - Decoding

    @Test func decodesTheSnakeCaseAnswer() throws {
        let json = Data(#"{"status":"update_required","latest_version":"1.4","update_url":"https://example.com/get"}"#.utf8)
        let decoded = try JSONDecoder.api.decode(ClientVersionResponse.self, from: json)
        #expect(decoded == ClientVersionResponse(status: .updateRequired, latestVersion: "1.4", updateURL: "https://example.com/get"))
    }

    @Test func decodesNullsAndRejectsAnUnknownStatus() throws {
        let json = Data(#"{"status":"current","latest_version":null,"update_url":null}"#.utf8)
        #expect(try JSONDecoder.api.decode(ClientVersionResponse.self, from: json).status == .current)
        let unknown = Data(#"{"status":"sunset","latest_version":null,"update_url":null}"#.utf8)
        #expect(throws: (any Error).self) { try JSONDecoder.api.decode(ClientVersionResponse.self, from: unknown) }
    }

    @Test func decodesTheServerVersionAndToleratesItsAbsence() throws {
        let json = Data(#"{"status":"current","latest_version":"1.0","update_url":null,"server_version":"0.1.0"}"#.utf8)
        #expect(try JSONDecoder.api.decode(ClientVersionResponse.self, from: json).serverVersion == "0.1.0")
        let older = Data(#"{"status":"current","latest_version":"1.0","update_url":null}"#.utf8)
        #expect(try JSONDecoder.api.decode(ClientVersionResponse.self, from: older).serverVersion == nil)
    }

    // MARK: - What shows

    @Test func statusMapsToThePrompt() {
        let url = URL(string: "https://testflight.apple.com/join/abc")
        #expect(ClientVersionPolicy.prompt(for: nil, dismissed: []) == .none)
        #expect(ClientVersionPolicy.prompt(for: answer(.current), dismissed: []) == .none)
        #expect(ClientVersionPolicy.prompt(for: answer(.updateAvailable), dismissed: []) == .available(latest: "1.4", url: url))
        #expect(ClientVersionPolicy.prompt(for: answer(.updateRequired), dismissed: []) == .required(latest: "1.4", url: url))
    }

    @Test func dismissalHidesOnlyThatVersionAndNeverRequired() {
        #expect(ClientVersionPolicy.prompt(for: answer(.updateAvailable), dismissed: ["1.4"]) == .none)
        #expect(ClientVersionPolicy.prompt(for: answer(.updateAvailable, latest: "1.5"), dismissed: ["1.4"]) != .none)
        #expect(ClientVersionPolicy.prompt(for: answer(.updateRequired), dismissed: ["1.4"]) != .none)
    }

    @Test(arguments: [nil, "", "http://example.com/x", "tel:123", "itms-services://?action=download", "https://", "not a url"])
    func onlyHTTPSLinksAreOffered(_ raw: String?) {
        #expect(ClientVersionPolicy.updateURL(raw) == nil)
    }

    @Test func httpsLinkIsKept() {
        #expect(ClientVersionPolicy.updateURL(" https://apps.apple.com/app/id1 ")?.absoluteString == "https://apps.apple.com/app/id1")
    }

    @Test func copyFollowsWhatTheServerSent() {
        #expect(ClientVersionPolicy.availableMessage(latest: "1.4", current: "1.0") == "Shroud 1.4 is available. You have 1.0.")
        #expect(
            ClientVersionPolicy.requiredMessage(latest: "1.4", hasUpdateURL: true)
                == "This version of Shroud no longer works with this server. Update to 1.4 to keep using it."
        )
        #expect(
            ClientVersionPolicy.requiredMessage(latest: nil, hasUpdateURL: false)
                == "This version of Shroud no longer works with this server. Update to keep using it. Get the new version where you installed Shroud."
        )
    }

    @Test func aboutStatusFollowsTheLastCheck() {
        let url = URL(string: "https://testflight.apple.com/join/abc")
        #expect(ClientVersionPolicy.status(answer: nil, lastCheckFailed: false, isChecking: false) == .unknown)
        #expect(ClientVersionPolicy.status(answer: nil, lastCheckFailed: false, isChecking: true) == .checking)
        #expect(ClientVersionPolicy.status(answer: answer(.current), lastCheckFailed: false, isChecking: false) == .current)
        #expect(
            ClientVersionPolicy.status(answer: answer(.updateAvailable), lastCheckFailed: false, isChecking: false)
                == .available(latest: "1.4", url: url)
        )
        #expect(
            ClientVersionPolicy.status(answer: answer(.updateAvailable, latest: "", url: "tel:1"), lastCheckFailed: false, isChecking: false)
                == .available(latest: nil, url: nil)
        )
        #expect(ClientVersionPolicy.status(answer: answer(.updateRequired), lastCheckFailed: false, isChecking: false) == .required)
        // A failed check outranks an older answer; a running one outranks everything.
        #expect(ClientVersionPolicy.status(answer: answer(.current), lastCheckFailed: true, isChecking: false) == .failed)
        #expect(ClientVersionPolicy.status(answer: answer(.current), lastCheckFailed: true, isChecking: true) == .checking)
    }

    @Test func aboutStatusCopy() {
        #expect(ClientVersionPolicy.statusTitle(.checking) == "Checking for updates…")
        #expect(ClientVersionPolicy.statusTitle(.current) == "Shroud is up to date")
        #expect(ClientVersionPolicy.statusTitle(.available(latest: "1.4", url: nil)) == "Version 1.4 is available")
        #expect(ClientVersionPolicy.statusTitle(.available(latest: nil, url: nil)) == "A new version is available")
        #expect(ClientVersionPolicy.statusTitle(.required) == "Update required")
        #expect(ClientVersionPolicy.statusTitle(.failed) == "Couldn’t check for updates")
        #expect(ClientVersionPolicy.statusTitle(.unknown) == "Updates are checked automatically")
    }

    // MARK: - When to ask

    @Test func foregroundChecksAreThrottledToTenMinutes() {
        #expect(ClientVersionPolicy.shouldCheck(.foreground, lastCheck: nil, now: start))
        #expect(!ClientVersionPolicy.shouldCheck(.foreground, lastCheck: start, now: start.addingTimeInterval(599)))
        #expect(ClientVersionPolicy.shouldCheck(.foreground, lastCheck: start, now: start.addingTimeInterval(600)))
        for trigger in [ClientVersionPolicy.Trigger.launch, .manual, .serverChanged] {
            #expect(ClientVersionPolicy.shouldCheck(trigger, lastCheck: start, now: start))
        }
    }

    // MARK: - Controller

    @Test func controllerThrottlesForegroundButNotManual() async {
        let calls = Counter()
        let controller = ClientVersionController(currentVersion: "1.0") { _, _ in
            await calls.bump()
            return ClientVersionResponse(status: .current, latestVersion: "1.0", updateURL: nil)
        }
        #expect(await controller.check(.launch, configuration: config, now: start) == .answered(.current))
        #expect(await controller.check(.foreground, configuration: config, now: start.addingTimeInterval(60)) == .skipped)
        #expect(await controller.check(.manual, configuration: config, now: start.addingTimeInterval(61)) == .answered(.current))
        // Ten minutes from the last check, the manual one.
        #expect(await controller.check(.foreground, configuration: config, now: start.addingTimeInterval(600)) == .skipped)
        #expect(await controller.check(.foreground, configuration: config, now: start.addingTimeInterval(661)) == .answered(.current))
        #expect(await calls.value == 3)
    }

    @Test func failedCheckDoesNotStartTheTenMinutes() async {
        let results = Script([.success(answer(.current)), .failure, .success(answer(.updateAvailable))])
        let controller = ClientVersionController(currentVersion: "1.0") { _, _ in try await results.next() }
        await controller.check(.launch, configuration: config, now: start)
        // Offline on the way back after ten minutes…
        #expect(await controller.check(.foreground, configuration: config, now: start.addingTimeInterval(600)) == .failed)
        // …so the next return asks again at once, not ten minutes after the failure.
        #expect(
            await controller.check(.foreground, configuration: config, now: start.addingTimeInterval(605))
                == .answered(.updateAvailable)
        )
    }

    @Test func serverChangeDropsTheCheckStillRunningAgainstTheOldServer() async {
        let oldServer = HeldFetch()
        let calls = Counter()
        let controller = ClientVersionController(currentVersion: "1.0") { _, _ in
            await calls.bump()
            // The first call is the old server's and hangs until released; the new one answers.
            if await calls.value == 1 { return await oldServer.wait() }
            return ClientVersionResponse(status: .current, latestVersion: "1.0", updateURL: nil)
        }
        let launch = Task { await controller.check(.launch, configuration: config, now: start) }
        while await !oldServer.isWaiting { await Task.yield() }
        #expect(controller.isChecking)

        #expect(await controller.check(.serverChanged, configuration: config, now: start) == .answered(.current))
        await oldServer.release(answer(.updateRequired))
        #expect(await launch.value == .skipped)
        #expect(controller.prompt == .none, "the old server's late answer is dropped")
        #expect(!controller.isChecking)
    }

    @Test func failedCheckKeepsTheLastAnswer() async {
        let results = Script([.success(answer(.updateRequired)), .failure])
        let controller = ClientVersionController(currentVersion: "1.0") { _, _ in try await results.next() }
        await controller.check(.launch, configuration: config, now: start)
        #expect(controller.isUpdateRequired)
        #expect(await controller.check(.manual, configuration: config, now: start) == .failed)
        #expect(controller.isUpdateRequired)
        #expect(!controller.isChecking)
    }

    @Test func checkAgainClearsRequiredWhenTheServerRelents() async {
        let results = Script([.success(answer(.updateRequired)), .success(answer(.updateAvailable))])
        let controller = ClientVersionController(currentVersion: "1.0") { _, _ in try await results.next() }
        await controller.check(.launch, configuration: config, now: start)
        await controller.check(.manual, configuration: config, now: start)
        #expect(!controller.isUpdateRequired)
        #expect(controller.prompt == .available(latest: "1.4", url: URL(string: "https://testflight.apple.com/join/abc")))
    }

    @Test func dismissalLastsUntilANewerVersion() async {
        let results = Script([
            .success(answer(.updateAvailable, latest: "1.4")),
            .success(answer(.updateAvailable, latest: "1.4")),
            .success(answer(.updateAvailable, latest: "1.5")),
        ])
        let controller = ClientVersionController(currentVersion: "1.0") { _, _ in try await results.next() }
        await controller.check(.launch, configuration: config, now: start)
        controller.dismissAvailable()
        #expect(controller.prompt == .none)
        await controller.check(.manual, configuration: config, now: start)
        #expect(controller.prompt == .none, "the same version stays dismissed")
        await controller.check(.manual, configuration: config, now: start)
        #expect(controller.prompt == .available(latest: "1.5", url: URL(string: "https://testflight.apple.com/join/abc")))
    }

    @Test func serverChangeForgetsTheOldAnswer() async {
        let results = Script([.success(answer(.updateRequired)), .failure])
        let controller = ClientVersionController(currentVersion: "1.0") { _, _ in try await results.next() }
        await controller.check(.launch, configuration: config, now: start)
        #expect(controller.isUpdateRequired)
        #expect(await controller.check(.serverChanged, configuration: config, now: start) == .failed)
        #expect(controller.prompt == .none)
    }

    @Test func controllerKeepsTheLastOutcomeAndServerVersion() async {
        let results = Script([
            .success(ClientVersionResponse(status: .current, latestVersion: "1.0", updateURL: nil, serverVersion: " 0.1.0 ")),
            .failure,
            .success(ClientVersionResponse(status: .updateAvailable, latestVersion: "1.4", updateURL: nil, serverVersion: "")),
        ])
        let controller = ClientVersionController(currentVersion: "1.0") { _, _ in try await results.next() }
        #expect(controller.lastOutcome == nil)
        #expect(controller.updateStatus == .unknown)
        #expect(controller.serverVersion == nil)

        await controller.check(.launch, configuration: config, now: start)
        #expect(controller.lastOutcome == .answered(.current))
        #expect(controller.updateStatus == .current)
        #expect(controller.serverVersion == "0.1.0")
        #expect(!controller.hasUpdate)

        await controller.check(.manual, configuration: config, now: start)
        #expect(controller.lastOutcome == .failed)
        #expect(controller.updateStatus == .failed)
        #expect(controller.serverVersion == "0.1.0", "a failed check keeps the last answer's version")

        await controller.check(.manual, configuration: config, now: start)
        #expect(controller.lastOutcome == .answered(.updateAvailable))
        #expect(controller.updateStatus == .available(latest: "1.4", url: nil))
        #expect(controller.serverVersion == nil, "an empty server_version reads as unknown")
        #expect(controller.hasUpdate)
    }

    @Test func dismissedOfferStillShowsOnAbout() async {
        let controller = ClientVersionController(currentVersion: "1.0") { _, _ in
            ClientVersionResponse(status: .updateAvailable, latestVersion: "1.4", updateURL: nil)
        }
        await controller.check(.launch, configuration: config, now: start)
        controller.dismissAvailable()
        #expect(controller.prompt == .none)
        #expect(controller.updateStatus == .available(latest: "1.4", url: nil))
        #expect(controller.hasUpdate)
    }

    @Test func serverChangeForgetsTheLastOutcome() async {
        let newServer = HeldFetch()
        let calls = Counter()
        let controller = ClientVersionController(currentVersion: "1.0") { _, _ in
            await calls.bump()
            // The old server is unreachable; the new one answers once released.
            if await calls.value == 1 { throw Script.Offline() }
            return await newServer.wait()
        }
        await controller.check(.launch, configuration: config, now: start)
        #expect(controller.lastOutcome == .failed)
        let switched = Task { await controller.check(.serverChanged, configuration: config, now: start) }
        while await !newServer.isWaiting { await Task.yield() }
        #expect(controller.lastOutcome == nil, "the old server's failure says nothing about the new one")
        #expect(controller.updateStatus == .checking)
        await newServer.release(answer(.current))
        #expect(await switched.value == .answered(.current))
        #expect(controller.updateStatus == .current)
    }

    @Test func sendsThisBuildsVersion() async {
        let seen = Counter()
        let controller = ClientVersionController(currentVersion: "2.3.1") { _, version in
            if version == "2.3.1" { await seen.bump() }
            return ClientVersionResponse(status: .current, latestVersion: nil, updateURL: nil)
        }
        await controller.check(.launch, configuration: config, now: start)
        #expect(await seen.value == 1)
    }
}

/// A fetch that hangs until the test hands it an answer.
private actor HeldFetch {
    private var continuation: CheckedContinuation<ClientVersionResponse, Never>?

    var isWaiting: Bool { continuation != nil }

    func wait() async -> ClientVersionResponse {
        await withCheckedContinuation { continuation = $0 }
    }

    func release(_ answer: ClientVersionResponse) {
        continuation?.resume(returning: answer)
        continuation = nil
    }
}

private actor Counter {
    private(set) var value = 0
    func bump() { value += 1 }
}

/// Hands out canned answers in order.
private actor Script {
    enum Step {
        case success(ClientVersionResponse)
        case failure
    }

    struct Offline: Error {}

    private var steps: [Step]

    init(_ steps: [Step]) { self.steps = steps }

    func next() throws -> ClientVersionResponse {
        guard !steps.isEmpty, case let .success(answer) = steps.removeFirst() else { throw Offline() }
        return answer
    }
}
