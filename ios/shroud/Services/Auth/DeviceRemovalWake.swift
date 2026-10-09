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

    /// What `GET /auth/me` said about the session the wake is for.
    enum Confirmation: Equatable, Sendable {
        /// The server still accepts the session, or refused it for some other reason.
        case present
        /// `401 DEVICE_REMOVED` with no account-deletion reason.
        case removed
        /// `401 DEVICE_REMOVED` with `reason` `account_deleted`.
        case accountDeleted
        /// No answer in time, or the network failed.
        case unanswered
    }

    /// The confirmed `/auth/me` error picks the wipe. `pushReason` is the wake payload's
    /// `reason` key: it never skips this confirm and never starts a wipe by itself.
    nonisolated static func wipeReason(confirmed: Confirmation, pushReason: String?) -> DeviceWipeController.Reason? {
        switch confirmed {
        case .accountDeleted:
            return .accountDeleted
        case .removed:
            return .removed
        case .present, .unanswered:
            _ = pushReason
            return nil
        }
    }

    /// Confirms the removal with the server, then wipes. Returns once the wipe is done, or out
    /// of time: a locked iPhone finishes the Keychain at unlock, and the pending marker finishes
    /// anything else on the next launch.
    ///
    /// `pushReason` is read from the push (`userInfo["reason"]`). The wipe still waits on
    /// `GET /auth/me`; only that answer's `reason` chooses account-deleted wording.
    static func handle(pushReason: String? = nil) async -> UIBackgroundFetchResult {
        let deadline = ContinuousClock.now + budget
        guard let session = SessionStore().load() else { return .noData }
        let confirmation = await confirm(session)
        guard let reason = wipeReason(confirmed: confirmation, pushReason: pushReason) else {
            return confirmation == .unanswered ? .failed : .noData
        }

        if let controller = SessionAuthBridge.controller, let wipe = SessionAuthBridge.deviceWipe {
            // The app holds another login now (the removed session was replaced): leave it.
            guard controller.bearerToken == session.token else { return .noData }
            if reason == .accountDeleted {
                controller.recordAccountDeleted(token: session.token)
            } else {
                controller.recordDeviceRemoved(token: session.token)
            }
            if !wipe.isPresented {
                _ = controller.consumePendingFullLocalWipe()
                wipe.start(reason: reason)
            } else if reason == .accountDeleted {
                wipe.start(reason: .accountDeleted)
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

    /// `GET /auth/me` for this session. Account deletion is a `DEVICE_REMOVED` whose body
    /// carries `reason` `account_deleted`. Anything but a removal leaves the phone alone.
    private static func confirm(_ session: SessionStore.Session) async -> Confirmation {
        let timeout = confirmTimeout
        return await withTaskGroup(of: Confirmation?.self) { group in
            group.addTask {
                do {
                    _ = try await AuthService().fetchMe(session: session)
                    return .present
                } catch let error as APIError where error.isAccountDeletion {
                    return .accountDeleted
                } catch let error as APIError where error.isDeviceRemoval {
                    return .removed
                } catch let error as APIError {
                    if case .transport = error { return .unanswered }
                    return .present
                } catch {
                    return .unanswered
                }
            }
            group.addTask {
                try? await Task.sleep(for: timeout)
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first ?? .unanswered
        }
    }
}
