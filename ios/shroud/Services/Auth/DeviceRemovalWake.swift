import UIKit

/// Wipes this iPhone when the account removes it while the app is not open.
///
/// Human: Removing a device from Settings → Devices has to take the account's data off it right
/// away, and a phone in a pocket has no socket to hear it on. The server sends it one last
/// silent push (`device_removed`) before it forgets the push token. The push alone never deletes
/// anything: the app asks `/auth/me`, and only the server's `DEVICE_REMOVED` answer starts the
/// wipe — the same wipe Log Out runs. With the app's UI alive (suspended in the background) the
/// overlay wipe runs, so returning to the app shows it finishing; after a background launch
/// there is no UI, so the stores are emptied directly and the pending marker makes the next
/// launch verify it and say so. A locked iPhone deletes every file at once; Keychain items that
/// open only while unlocked go at unlock.
///
/// Agent: READS SessionStore; CALLS GET /auth/me (APIClient reports DEVICE_REMOVED to
/// SessionAuthBridge); RUNS DeviceWipeController or DeviceDataWipe.app. iOS gives a silent
/// push about 30 s, so everything, the server check included, fits in `budget`.
@MainActor
enum DeviceRemovalWake {
    nonisolated static let payloadType = "device_removed"
    /// iOS gives a silent push about 30 s in all; this is everything, the server check included.
    private static let budget: Duration = .seconds(25)
    private static let confirmTimeout: Duration = .seconds(8)

    nonisolated static func isRemoval(_ userInfo: [AnyHashable: Any]) -> Bool {
        userInfo["type"] as? String == payloadType
    }

    /// Confirms the removal with the server, then wipes. Returns once the wipe is done, or out
    /// of time: a locked iPhone finishes the Keychain at unlock, and the pending marker finishes
    /// anything else on the next launch.
    static func handle() async -> UIBackgroundFetchResult {
        let deadline = ContinuousClock.now + budget
        guard let session = SessionStore().load() else { return .noData }
        switch await confirmRemoved(session) {
        case false?:
            // Still part of the account: a stale or misdirected push.
            return .noData
        case nil:
            // No answer in time. The socket, the next request or the next launch will tell.
            return .failed
        case true?:
            break
        }

        if let controller = SessionAuthBridge.controller, let wipe = SessionAuthBridge.deviceWipe {
            // The app holds another login now (the removed session was replaced): leave it.
            guard controller.bearerToken == session.token else { return .noData }
            controller.recordDeviceRemoved(token: session.token)
            if !wipe.isPresented {
                _ = controller.consumePendingFullLocalWipe()
                wipe.start(reason: .sessionEnded)
            }
            while wipe.isPresented, wipe.phase != .failed, ContinuousClock.now < deadline {
                try? await Task.sleep(for: .milliseconds(200))
            }
            return .newData
        }

        // Launched in the background with no UI. The marker stays set, so the next launch
        // verifies what is left (Keychain items a locked iPhone keeps until unlock) and tells
        // the user this iPhone was cleared.
        let data = DeviceDataWipe.app
        data.markPending()
        await data.wipeEverything()
        NotificationsController.shared.forgetAccount()
        return .newData
    }

    /// True when the server says `DEVICE_REMOVED` for this session, false when it still takes
    /// it (or refuses it for another reason), nil without an answer in time.
    private static func confirmRemoved(_ session: SessionStore.Session) async -> Bool? {
        let timeout = confirmTimeout
        return await withTaskGroup(of: Bool??.self) { group in
            group.addTask {
                do {
                    _ = try await AuthService().fetchMe(session: session)
                    return .some(false)
                } catch let error as APIError where error.isDeviceRemoval {
                    return .some(true)
                } catch let error as APIError {
                    if case .transport = error { return .some(nil) }
                    return .some(false)
                } catch {
                    return .some(nil)
                }
            }
            group.addTask {
                try? await Task.sleep(for: timeout)
                return .some(nil)
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first ?? nil
        }
    }
}
