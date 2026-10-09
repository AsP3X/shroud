import CallKit
import CryptoKit
import Foundation
import PushKit
import UIKit
import UserNotifications

/// APNs registration, the notification-centre delegate, and PushKit.
///
/// Human: The server only gets opaque tokens, plus a random key it seals each push's ids (and a
/// sender's name) with so Apple cannot read them (`NotificationPayload`). Pushes never carry
/// message content.
/// Agent: `PUT /push/token` with kind `alert` (+ payload key) and `voip`; sandbox vs production
/// from the embedded provisioning profile. Taps and foreground pushes go to NotificationsController.
@MainActor
final class PushNotificationService: NSObject {
    static let shared = PushNotificationService()

    private var voipRegistry: PKPushRegistry?
    private weak var sessionController: SessionController?
    private weak var callController: CallController?
    private var lastAlertTokenHex: String?
    private var lastVoipTokenHex: String?
    /// A VoIP push that woke the app before the call controller existed.
    private var pendingVoip: (UUID, UUID?, String, CallModality)?

    private override init() {
        super.init()
    }

    func bind(session: SessionController, calls: CallController) {
        sessionController = session
        callController = calls
        if let pending = pendingVoip {
            pendingVoip = nil
            calls.handleVoipPush(
                callID: pending.0,
                peerUserID: pending.1,
                peerUsername: pending.2,
                modality: pending.3,
                alreadyReported: true
            )
        }
    }

    /// Becomes the notification centre's delegate and starts PushKit. Called at launch, before
    /// a tap or a VoIP push that launched the app is delivered.
    ///
    /// Human: A call has to be able to wake a signed-in iPhone that is not open. PushKit only
    /// delivers that if the registry exists from launch, not after the chats are unlocked.
    func install() {
        UNUserNotificationCenter.current().delegate = self
        startVoip()
    }

    /// After sign-in: asks for permission the first time, registers with APNs, and tells the
    /// server this iPhone's settings. Also starts PushKit if launch did not (tests, or a
    /// logout that tore it down and a later sign-in in the same process).
    func start() {
        startVoip()

        Task {
            let notifications = NotificationsController.shared
            if notifications.preferences.enabled {
                await notifications.requestAuthorizationIfNeeded()
            } else {
                await notifications.refreshAuthorization()
            }
            // Registering is harmless without permission (no alerts show), and the token lets
            // "Send a test notification" report what is wrong.
            UIApplication.shared.registerForRemoteNotifications()
            if let token = sessionController?.bearerToken {
                try? await notifications.pushSettings(token: token)
            }
        }
        // Re-register tokens if we already have them (e.g. relaunch while signed in).
        if let hex = lastAlertTokenHex {
            Task { await uploadToken(hex: hex, kind: .alert) }
        }
        if let hex = lastVoipTokenHex {
            Task { await uploadToken(hex: hex, kind: .voip) }
        }
    }

    func stop() {
        // Keep system registrations; tokens remain valid until logout clears session.
    }

    /// PushKit, from launch. Simulator builds skip it: the entitlement is not there, and a
    /// registry in unit tests has nothing to register with.
    private func startVoip() {
        #if !targetEnvironment(simulator)
        if voipRegistry == nil {
            let registry = PKPushRegistry(queue: .main)
            registry.delegate = self
            registry.desiredPushTypes = [.voIP]
            voipRegistry = registry
        }
        #endif
    }

    /// Logout: this iPhone stops being reachable for the account at Apple too. The server drops
    /// its tokens on logout, but it may not have heard it (offline); a push to an unregistered
    /// app never arrives. `start()` registers again after the next sign-in.
    func forgetRegistration() {
        UIApplication.shared.unregisterForRemoteNotifications()
        lastAlertTokenHex = nil
        lastVoipTokenHex = nil
        voipRegistry?.desiredPushTypes = []
        voipRegistry = nil
    }

    func didRegisterForRemoteNotifications(deviceToken: Data) {
        let hex = deviceTokenHex(deviceToken)
        lastAlertTokenHex = hex
        Task { await uploadToken(hex: hex, kind: .alert) }
    }

    func didFailToRegisterForRemoteNotifications(error: Error) {
        // Expected on Simulator without APNs — non-fatal.
        #if DEBUG
        print("APNs data register failed: \(error.localizedDescription)")
        #endif
    }

    private enum TokenKind: String {
        case alert
        case voip
    }

    private func uploadToken(hex: String, kind: TokenKind) async {
        guard let token = sessionController?.bearerToken else { return }
        // The key the server seals each push's ids and names with; without one (Keychain
        // unavailable) the server sends the ids readable and leaves names out.
        let payloadKey: String? = kind == .alert
            ? NotificationPayload.makeKey().map { key in key.withUnsafeBytes { Data($0) }.base64EncodedString() }
            : nil
        do {
            try await NotificationsService().registerToken(
                PushTokenBody(token: hex, environment: Self.apnsEnvironment, kind: kind.rawValue, payloadKey: payloadKey),
                token: token
            )
        } catch {
            #if DEBUG
            print("push token upload failed: \(error)")
            #endif
        }
    }

    private func deviceTokenHex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    /// `sandbox` for development-signed builds, `production` otherwise. Read from the embedded
    /// provisioning profile: a Release build run from Xcode still has development tokens.
    private static let apnsEnvironment: String = {
        if let environment = embeddedProfileAPNsEnvironment() {
            return environment == "production" ? "production" : "sandbox"
        }
        #if DEBUG
        return "sandbox"
        #else
        // App Store and TestFlight builds carry no profile, and always use production.
        return "production"
        #endif
    }()

    private static func embeddedProfileAPNsEnvironment() -> String? {
        guard let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
              let data = try? Data(contentsOf: url),
              let start = data.range(of: Data("<?xml".utf8)),
              let end = data.range(of: Data("</plist>".utf8), in: start.lowerBound..<data.endIndex),
              let plist = try? PropertyListSerialization.propertyList(
                  from: data.subdata(in: start.lowerBound..<end.upperBound),
                  format: nil
              ) as? [String: Any],
              let entitlements = plist["Entitlements"] as? [String: Any]
        else { return nil }
        return entitlements["aps-environment"] as? String
    }
}

/// Human: The completion-handler forms, finished on the main thread. The `async` forms finish on
/// a background thread, and so does UIKit's completion block behind them: for a tap it updates
/// the app snapshot, asserts it is on the main thread, and aborts (the simulator crashed; the
/// iPhone came up on a lock screen that took no touches).
/// Agent: UIKit calls these on the main thread; the `Thread.isMainThread` branch is a guard.
extension PushNotificationService: UNUserNotificationCenterDelegate {
    /// A push (or the app's own notification) while the app is in front.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        let present = {
            MainActor.assumeIsolated {
                completionHandler(NotificationsController.shared.presentation(for: notification))
            }
        }
        if Thread.isMainThread {
            present()
        } else {
            DispatchQueue.main.sync(execute: present)
        }
    }

    /// A tap on a notification: open its chat.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let open = {
            MainActor.assumeIsolated {
                let content = response.notification.request.content
                NotificationsController.shared.handleTap(
                    NotificationPayload.parse(content.userInfo),
                    title: content.title
                )
                completionHandler()
            }
        }
        if Thread.isMainThread {
            open()
        } else {
            DispatchQueue.main.sync(execute: open)
        }
    }
}

extension PushNotificationService: PKPushRegistryDelegate {
    nonisolated func pushRegistry(
        _ registry: PKPushRegistry,
        didUpdate pushCredentials: PKPushCredentials,
        for type: PKPushType
    ) {
        guard type == .voIP else { return }
        let hex = pushCredentials.token.map { String(format: "%02x", $0) }.joined()
        Task { @MainActor in
            lastVoipTokenHex = hex
            await uploadToken(hex: hex, kind: .voip)
        }
    }

    nonisolated func pushRegistry(
        _ registry: PKPushRegistry,
        didReceiveIncomingPushWith payload: PKPushPayload,
        for type: PKPushType,
        completion: @escaping () -> Void
    ) {
        // CallKit must be reported before this method returns. The registry is created on
        // the main queue, but hop synchronously if we are ever invoked off-main.
        let deliver = {
            MainActor.assumeIsolated {
                self.deliverIncomingVoipPush(payload: payload, type: type, completion: completion)
            }
        }
        if Thread.isMainThread {
            deliver()
        } else {
            DispatchQueue.main.sync(execute: deliver)
        }
    }

    @MainActor
    private func deliverIncomingVoipPush(
        payload: PKPushPayload,
        type: PKPushType,
        completion: @escaping () -> Void
    ) {
        guard type == .voIP else {
            completion()
            return
        }
        let dict = payload.dictionaryPayload
        let contents = NotificationPayload.parse(dict)
        let isCall = contents?.kind == .call || contents?.kind == .videoCall || contents?.kind == .callEnded
        guard isCall, let contents, let callID = contents.callID else {
            // PushKit still requires a CallKit report, even when this is not a call or the call
            // id did not open. Before the first unlock the payload key is unreadable, so a
            // sealed ring is reported as a failed call: the id stays inside the seal (Apple
            // must not see it), and the session is in the same Keychain class, so the call
            // could not be answered yet anyway.
            let unused = UUID()
            CallKitManager.shared.reportIncoming(unused, callerName: "Shroud", video: false)
            CallKitManager.shared.reportEnded(unused, reason: .failed)
            completion()
            return
        }
        if contents.kind == .callEnded {
            if pendingVoip?.0 == callID { pendingVoip = nil }
            if let calls = callController {
                calls.endFromVoipPush(callID)
            } else if CallKitManager.shared.isTracking(callID) {
                CallKitManager.shared.reportEnded(callID, reason: .remoteEnded)
            } else {
                // PushKit requires a CallKit report even when the call is already over.
                CallKitManager.shared.reportIncoming(callID, callerName: "Shroud", video: false) { _ in
                    Task { @MainActor in
                        CallKitManager.shared.reportEnded(callID, reason: .remoteEnded)
                    }
                }
            }
            completion()
            return
        }
        let modality: CallModality = contents.kind == .videoCall ? .video : .voice
        let fromName = contents.senderName ?? "Incoming call"

        // A hang-up that already arrived must not leave a new ring on screen. PushKit still
        // requires a report, so the call is reported and ended together.
        if callController?.callWasFinished(callID) == true {
            CallKitManager.shared.reportIncoming(callID, callerName: fromName, video: modality == .video) { _ in
                Task { @MainActor in
                    CallKitManager.shared.reportEnded(callID, reason: .remoteEnded)
                }
            }
            completion()
            return
        }
        // reportNewIncomingCall runs before we return; that satisfies PushKit. Waiting on
        // CallKit's completion delayed the report.
        CallKitManager.shared.reportIncoming(callID, callerName: fromName, video: modality == .video)
        if let calls = callController {
            calls.handleVoipPush(
                callID: callID,
                peerUserID: contents.peerUserID,
                peerUsername: fromName,
                modality: modality,
                alreadyReported: true
            )
        } else {
            pendingVoip = (callID, contents.peerUserID, fromName, modality)
        }
        completion()
    }

    nonisolated func pushRegistry(
        _ registry: PKPushRegistry,
        didInvalidatePushTokenFor type: PKPushType
    ) {
        // Token will re-register on next credential update.
    }
}
