import SwiftUI
import UIKit

/// Runs the logout wipe one step at a time behind `DeviceWipeOverlay`.
///
/// Human: Every row the overlay ticks off is real work in `DeviceDataWipe`, and the last row
/// proves the result. Only then does the app forget the session and return to Welcome. A step
/// never finishes faster than it can be read — the deletions take milliseconds, and a list that
/// flashes by shows nothing. If something survives, the overlay says what, and offers a retry.
///
/// Agent: CALLS MessagingController.haltForDeviceWipe / stop, CallController, CryptoController,
/// VoicePlaybackCoordinator, `POST auth/logout`, DeviceDataWipe; WRITES SessionController
/// (logout) and AppRouter (unlock state, path) when done. One run at a time.
@MainActor
@Observable
final class DeviceWipeController {
    typealias Step = DeviceDataWipe.Step

    enum Reason: Equatable {
        /// The user confirmed Log Out (or changed server).
        case logout
        /// The server stopped accepting the session. The token is still here; the session step
        /// revokes it (a 401 means it was already over).
        case sessionEnded
    }

    enum Phase: Equatable {
        case idle, running, done, failed
    }

    private(set) var phase: Phase = .idle
    private(set) var reason: Reason = .logout
    /// The step whose row is spinning.
    private(set) var active: Step?
    /// Finished steps and what they report ("214 removed").
    private(set) var details: [Step: String] = [:]
    private(set) var leftovers: [DeviceDataWipe.Leftover] = []
    /// Rows that failed and are running again under "Try Again".
    private(set) var retrying: Set<Step> = []
    /// "@name", captured before the session goes; empty when it was already gone.
    private(set) var handle = ""

    var isPresented: Bool { phase != .idle }

    weak var session: SessionController?
    weak var messaging: MessagingController?
    weak var crypto: CryptoController?
    weak var calls: CallController?
    weak var router: AppRouter?

    private let wipe: DeviceDataWipe
    private var inventory = DeviceDataWipe.Inventory()

    private static let stepPace: Duration = .milliseconds(420)
    private static let verifyPace: Duration = .milliseconds(560)
    private static let reducedPace: Duration = .milliseconds(120)
    private static let doneHold: Duration = .milliseconds(1400)
    private static let reducedDoneHold: Duration = .milliseconds(900)
    private static let serverTimeout: Duration = .seconds(4)

    init(wipe: DeviceDataWipe? = nil) {
        self.wipe = wipe ?? .app
    }

    // MARK: - Running

    func start(reason: Reason) {
        guard phase == .idle else { return }
        self.reason = reason
        handle = session?.username.map { "@\($0)" } ?? ""
        details = [:]
        leftovers = []
        retrying = []
        // Stop writers before the first await. A poll that lands between here and `run` would
        // refill a store the wipe is about to delete.
        messaging?.haltForDeviceWipe()
        calls?.clearLocalState()
        VoicePlaybackCoordinator.shared.stop()
        withAnimation(Motion.standard) { phase = .running }
        Task { await run() }
    }

    func retry() {
        guard phase == .failed else { return }
        retrying = Set(leftovers.map(\.step))
        leftovers = []
        phase = .running
        Task {
            let reduce = UIAccessibility.isReduceMotionEnabled
            if await perform(.verify, reduce: reduce) {
                await complete(reduce: reduce)
            }
        }
    }

    /// "Continue" after a failed check: the session is gone either way, and the next launch
    /// tries the wipe again (the pending marker is still set).
    func continueAfterFailure() {
        guard phase == .failed else { return }
        Task { await finish(reduce: true) }
    }

    private func run() async {
        let reduce = UIAccessibility.isReduceMotionEnabled
        // Both reasons still hold the token: Log Out hasn't cleared it, and a forced sign-out
        // leaves it in place so this step can revoke it and report an offline server.
        let token = session?.bearerToken
        // Nothing may write while the stores are emptied: no poll, socket, playback or call.
        messaging?.haltForDeviceWipe()
        calls?.clearLocalState()
        VoicePlaybackCoordinator.shared.stop()
        inventory = wipe.inventory()
        wipe.markPending()
        for step in Step.allCases {
            guard await perform(step, reduce: reduce, token: token) else { return }
        }
        await complete(reduce: reduce)
    }

    /// Runs one step and fills in its row. False when `verify` found something it cannot remove.
    private func perform(_ step: Step, reduce: Bool, token: String? = nil) async -> Bool {
        withAnimation(Motion.snappy) { active = step }
        let started = ContinuousClock.now
        let detail: String
        switch step {
        case .session:
            detail = await endServerSession(token: token) ? "Session ended" : "Ended here · server offline"
        case .messages:
            wipe.wipeMessages()
            detail = Self.removed(inventory.messages)
        case .media:
            wipe.wipeMedia()
            detail = inventory.mediaFiles == 0 ? "None stored" : inventory.mediaSummary
        case .keys:
            wipe.wipeKeys()
            detail = Self.removed(inventory.keys)
        case .settings:
            await wipe.wipeSettings()
            detail = "Cleared"
        case .verify:
            var found = await wipe.leftovers()
            if !found.isEmpty {
                await wipe.wipeEverything()
                found = await wipe.leftovers()
            }
            await pace(since: started, step: step, reduce: reduce)
            guard found.isEmpty else {
                fail(found)
                return false
            }
            wipe.clearPending()
            detail = "Nothing left"
        }
        if step != .verify { await pace(since: started, step: step, reduce: reduce) }
        withAnimation(Motion.snappy) {
            details[step] = detail
            retrying.remove(step)
            if step == .verify { retrying = [] }
        }
        AccessibilityNotification.Announcement("\(Self.title(for: step)): \(detail)").post()
        return true
    }

    private func fail(_ found: [DeviceDataWipe.Leftover]) {
        withAnimation(Motion.standard) {
            active = nil
            retrying = []
            leftovers = found
            phase = .failed
        }
        Haptics.notification(.error)
        AccessibilityNotification.Announcement(
            "Some data could not be removed: \(Self.labels(of: found))."
        ).post()
    }

    /// "media and cached files, settings" — each kind once, in the order found.
    static func labels(of found: [DeviceDataWipe.Leftover]) -> String {
        var seen = Set<String>()
        return found.map(\.label).filter { seen.insert($0).inserted }.joined(separator: ", ")
    }

    private func complete(reduce: Bool) async {
        withAnimation(Motion.standard) {
            active = nil
            phase = .done
        }
        Haptics.notification(.success)
        AccessibilityNotification.Announcement("This iPhone is clear. Nothing from your account is left on it.").post()
        await endLocalSession()
        try? await Task.sleep(for: reduce ? Self.reducedDoneHold : Self.doneHold)
        await finish(reduce: reduce)
    }

    /// The app forgets the session only now, behind the overlay, so Welcome is what it reveals.
    private func endLocalSession() async {
        await session?.logout()
        crypto?.lock(wipeStore: true)
        messaging?.stop(wipeDisk: true)
        calls?.clearLocalState()
        PushNotificationService.shared.stop()
        router?.postAuthToast = nil
        router?.hasUnlockedMessaging = false
        router?.path = []
    }

    private func finish(reduce: Bool) async {
        if phase == .failed { await endLocalSession() }
        withAnimation(reduce ? Motion.reduced : Motion.gentle) { phase = .idle }
        active = nil
    }

    private func pace(since started: ContinuousClock.Instant, step: Step, reduce: Bool) async {
        let floor = reduce ? Self.reducedPace : step == .verify ? Self.verifyPace : Self.stepPace
        let remaining = floor - (ContinuousClock.now - started)
        if remaining > .zero { try? await Task.sleep(for: remaining) }
    }

    /// Revokes the token on the server. False only when the server could not be reached (or
    /// did not answer in time): then only this iPhone forgot the session.
    private func endServerSession(token: String?) async -> Bool {
        guard let token else { return true }
        let timeout = Self.serverTimeout
        return await withTaskGroup(of: Bool?.self) { group in
            group.addTask {
                do {
                    try await APIClient.makeConfiguredClient().postNoContent(path: "auth/logout", bearerToken: token)
                    return true
                } catch let error as APIError {
                    // 401: already over. Anything but "could not connect" means the server heard us.
                    if case .transport = error { return false }
                    return true
                } catch {
                    return false
                }
            }
            group.addTask {
                try? await Task.sleep(for: timeout)
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first ?? false
        }
    }

    // MARK: - Launch

    /// Called once at launch, before the session is used. Finishes a wipe the app was killed in
    /// the middle of, and clears what an older version's logout left behind (it kept the
    /// identity keys and caches). A signed-in iPhone is only touched when a wipe was pending —
    /// the user asked to log out — and an unreadable Keychain (before first unlock) never counts
    /// as "signed out".
    /// - Returns: true when a pending wipe was finished, so the caller can say so.
    @discardableResult
    func finishInterruptedWipeIfNeeded() async -> Bool {
        // The unit-test host launches the app too, and its tests keep fixtures in `tmp`.
        guard !Self.isUnitTestHost else { return false }
        let pending = wipe.isPending
        let signedOut = session?.isSignedIn != true
            && UIApplication.shared.isProtectedDataAvailable
            && SessionStore().hasNoSession()
        guard pending || signedOut else { return false }
        // Killed before the server heard about it: the token is still in memory, so try again.
        if pending, let token = session?.bearerToken {
            _ = await endServerSession(token: token)
        }
        await wipe.wipeEverything()
        if await wipe.leftovers().isEmpty { wipe.clearPending() }
        guard pending else { return false }
        if session?.isSignedIn == true { await session?.logout() }
        crypto?.lock(wipeStore: true)
        return true
    }

    private static var isUnitTestHost: Bool {
        let environment = ProcessInfo.processInfo.environment
        return environment["XCTestConfigurationFilePath"] != nil
            || environment["XCTestBundlePath"] != nil
            || environment["XCTestSessionIdentifier"] != nil
    }

    // MARK: - Copy

    static func title(for step: Step) -> String {
        switch step {
        case .session: "Signing out"
        case .messages: "Messages"
        case .media: "Photos, videos & voice"
        case .keys: "Encryption keys"
        case .settings: "Settings & caches"
        case .verify: "Checking nothing is left"
        }
    }

    private static func removed(_ count: Int) -> String {
        count == 0 ? "None stored" : "\(count) removed"
    }
}
