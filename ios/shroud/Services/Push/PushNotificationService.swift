import Foundation
import PushKit
import UIKit
import UserNotifications

/// Registers data APNs + VoIP PushKit tokens with `PUT /push/token`.
/// Human: Server only gets opaque tokens; push payloads stay content-free.
/// Agent: sandbox vs production from build config; VoIP uses same register path with voip prefix.
@MainActor
final class PushNotificationService: NSObject {
    static let shared = PushNotificationService()

    private var voipRegistry: PKPushRegistry?
    private weak var sessionController: SessionController?
    private weak var callController: CallController?
    private var lastDataTokenHex: String?
    private var lastVoipTokenHex: String?

    private override init() {
        super.init()
    }

    func bind(session: SessionController, calls: CallController) {
        sessionController = session
        callController = calls
    }

    /// Call once after unlock — requests notification permission and registers for remote notifications.
    func start() {
        #if !targetEnvironment(simulator)
        // PushKit is unreliable / entitlement-gated on Simulator; skip to keep tests stable.
        if voipRegistry == nil {
            let registry = PKPushRegistry(queue: .main)
            registry.delegate = self
            registry.desiredPushTypes = [.voIP]
            voipRegistry = registry
        }
        #endif

        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            guard granted else { return }
            DispatchQueue.main.async {
                UIApplication.shared.registerForRemoteNotifications()
            }
        }
        // Re-register tokens if we already have them (e.g. relaunch while signed in).
        if let hex = lastDataTokenHex {
            Task { await uploadToken(hex: hex, kind: .data) }
        }
        if let hex = lastVoipTokenHex {
            Task { await uploadToken(hex: hex, kind: .voip) }
        }
    }

    func stop() {
        // Keep system registrations; tokens remain valid until logout clears session.
    }

    func didRegisterForRemoteNotifications(deviceToken: Data) {
        let hex = deviceTokenHex(deviceToken)
        lastDataTokenHex = hex
        Task { await uploadToken(hex: hex, kind: .data) }
    }

    func didFailToRegisterForRemoteNotifications(error: Error) {
        // Expected on Simulator without APNs — non-fatal.
        #if DEBUG
        print("APNs data register failed: \(error.localizedDescription)")
        #endif
    }

    private enum TokenKind {
        case data
        case voip
    }

    private func uploadToken(hex: String, kind: TokenKind) async {
        guard let token = sessionController?.bearerToken else { return }
        // Distinguish VoIP tokens server-side via a short prefix (opaque to APNs delivery path).
        let stored = kind == .voip ? "voip:\(hex)" : hex
        let environment = Self.apnsEnvironment
        do {
            try await APIClient.makeConfiguredClient().putNoContent(
                path: "push/token",
                body: PushTokenBody(token: stored, environment: environment),
                bearerToken: token
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

    private static var apnsEnvironment: String {
        #if DEBUG
        "sandbox"
        #else
        "production"
        #endif
    }
}

private struct PushTokenBody: Encodable {
    let token: String
    let environment: String
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
        // VoIP push must report a CallKit call promptly; CallController also handles WS ring.
        Task { @MainActor in
            defer { completion() }
            guard type == .voIP else { return }
            let dict = payload.dictionaryPayload
            // Opaque payload: { "call_id": "...", "from_user_id": "...", "modality": "voice" }
            guard let callIDString = dict["call_id"] as? String,
                  let callID = UUID(uuidString: callIDString)
            else { return }
            let modality = (dict["modality"] as? String).flatMap(CallModality.init(rawValue:)) ?? .voice
            let fromName = dict["from_username"] as? String ?? "Incoming call"
            // CallKit must be reported promptly for VoIP pushes; live ring also arrives on WS.
            if callController?.active == nil {
                callController?.handleVoipPush(
                    callID: callID,
                    peerUsername: fromName,
                    modality: modality
                )
            }
        }
    }

    nonisolated func pushRegistry(
        _ registry: PKPushRegistry,
        didInvalidatePushTokenFor type: PKPushType
    ) {
        // Token will re-register on next credential update.
    }
}
