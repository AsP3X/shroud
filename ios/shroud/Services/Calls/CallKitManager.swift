import AVFoundation
import CallKit
import Foundation
import UIKit

/// What CallKit asks the app to do (the user acted on the system call UI, or iOS did).
@MainActor
protocol CallKitManagerDelegate: AnyObject {
    func callKitStartCall(_ id: UUID)
    func callKitAnswerCall(_ id: UUID)
    func callKitEndCall(_ id: UUID)
    func callKitSetMuted(_ id: UUID, muted: Bool)
    func callKitAudioActivated(_ session: AVAudioSession)
    func callKitAudioDeactivated(_ session: AVAudioSession)
    func callKitReset()
}

/// The system call UI: lock screen, Dynamic Island, Bluetooth and CarPlay buttons.
///
/// Human: Calls are not added to the iPhone's Recents (they would sync names to iCloud) and
/// cannot be held. Ids are the call's CallKit UUID: the server's call id for incoming calls, a
/// local one for outgoing calls (the server id comes later).
/// Agent: provider delegate queue = main; every action is forwarded, then fulfilled.
@MainActor
final class CallKitManager: NSObject {
    static let shared = CallKitManager()

    weak var delegate: CallKitManagerDelegate?

    private let provider: CXProvider
    private let callController = CXCallController()

    private override init() {
        let config = CXProviderConfiguration()
        config.supportsVideo = true
        config.maximumCallsPerCallGroup = 1
        config.maximumCallGroups = 1
        config.supportedHandleTypes = [.generic]
        config.includesCallsInRecents = false
        if let mark = UIImage(named: "BrandMark") {
            // The vector asset's natural size is 680 pt; CallKit wants a ~40 pt template.
            let size = CGSize(width: 40, height: 40)
            config.iconTemplateImageData = UIGraphicsImageRenderer(size: size).image { _ in
                mark.draw(in: CGRect(origin: .zero, size: size))
            }.pngData()
        }
        provider = CXProvider(configuration: config)
        super.init()
        provider.setDelegate(self, queue: nil)
    }

    /// An incoming call. Must run before a PushKit handler returns.
    func reportIncoming(
        _ id: UUID,
        callerName: String,
        video: Bool,
        completion: @escaping @Sendable (Error?) -> Void = { _ in }
    ) {
        provider.reportNewIncomingCall(with: id, update: update(name: callerName, video: video)) { error in
            completion(error)
        }
    }

    /// New details for a call CallKit shows (the caller's name arrived).
    func update(_ id: UUID, callerName: String, video: Bool) {
        provider.reportCall(with: id, updated: update(name: callerName, video: video))
    }

    func requestStart(_ id: UUID, handle: String, video: Bool) async throws {
        let action = CXStartCallAction(call: id, handle: CXHandle(type: .generic, value: handle))
        action.isVideo = video
        action.contactIdentifier = handle
        try await callController.request(CXTransaction(action: action))
    }

    func requestAnswer(_ id: UUID) async throws {
        try await callController.request(CXTransaction(action: CXAnswerCallAction(call: id)))
    }

    func requestEnd(_ id: UUID) async throws {
        try await callController.request(CXTransaction(action: CXEndCallAction(call: id)))
    }

    func requestMute(_ id: UUID, muted: Bool) async throws {
        try await callController.request(CXTransaction(action: CXSetMutedCallAction(call: id, muted: muted)))
    }

    func reportConnecting(_ id: UUID) {
        provider.reportOutgoingCall(with: id, startedConnectingAt: Date())
    }

    func reportConnected(_ id: UUID) {
        provider.reportOutgoingCall(with: id, connectedAt: Date())
    }

    /// The call ended without the user ending it here.
    func reportEnded(_ id: UUID, reason: CXCallEndedReason) {
        provider.reportCall(with: id, endedAt: Date(), reason: reason)
    }

    /// True when CallKit shows a call with this id.
    func isTracking(_ id: UUID) -> Bool {
        callController.callObserver.calls.contains { $0.uuid == id && !$0.hasEnded }
    }

    private func update(name: String, video: Bool) -> CXCallUpdate {
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: name)
        update.localizedCallerName = name
        update.hasVideo = video
        update.supportsHolding = false
        update.supportsGrouping = false
        update.supportsUngrouping = false
        update.supportsDTMF = false
        return update
    }
}

extension CallKitManager: CXProviderDelegate {
    nonisolated func providerDidReset(_ provider: CXProvider) {
        MainActor.assumeIsolated {
            delegate?.callKitReset()
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        MainActor.assumeIsolated {
            delegate?.callKitStartCall(action.callUUID)
            action.fulfill()
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        MainActor.assumeIsolated {
            delegate?.callKitAnswerCall(action.callUUID)
            action.fulfill()
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        MainActor.assumeIsolated {
            delegate?.callKitEndCall(action.callUUID)
            action.fulfill()
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        MainActor.assumeIsolated {
            delegate?.callKitSetMuted(action.callUUID, muted: action.isMuted)
            action.fulfill()
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
        action.fail()
    }

    nonisolated func provider(_ provider: CXProvider, timedOutPerforming action: CXAction) {
        action.fail()
    }

    nonisolated func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        MainActor.assumeIsolated {
            delegate?.callKitAudioActivated(audioSession)
        }
    }

    nonisolated func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        MainActor.assumeIsolated {
            delegate?.callKitAudioDeactivated(audioSession)
        }
    }
}
