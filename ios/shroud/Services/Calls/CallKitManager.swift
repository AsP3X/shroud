import AVFoundation
import CallKit
import Foundation
import UIKit

/// Bridges Shroud call state to the system CallKit UI (lock screen, Bluetooth, CarPlay).
/// Human: Users answer/end from the native call UI; media stays in WebRTC.
/// Agent: CXProvider reports outgoing/incoming; actions call back into CallController.
@MainActor
final class CallKitManager: NSObject {
    private let provider: CXProvider
    private let controller = CXCallController()
    private var callUUIDByCallID: [UUID: UUID] = [:]
    private var callIDByUUID: [UUID: UUID] = [:]

    weak var delegate: CallKitManagerDelegate?

    override init() {
        let config = CXProviderConfiguration()
        config.supportsVideo = true
        config.maximumCallsPerCallGroup = 1
        config.maximumCallGroups = 1
        config.supportedHandleTypes = [.generic]
        config.includesCallsInRecents = true
        if let icon = UIImage(systemName: "lock.shield.fill") {
            config.iconTemplateImageData = icon.pngData()
        }
        provider = CXProvider(configuration: config)
        super.init()
        provider.setDelegate(self, queue: nil)
    }

    func startOutgoing(
        callID: UUID,
        peerUsername: String,
        hasVideo: Bool
    ) {
        let uuid = mappedUUID(for: callID)
        let handle = CXHandle(type: .generic, value: peerUsername)
        let start = CXStartCallAction(call: uuid, handle: handle)
        start.isVideo = hasVideo
        let tx = CXTransaction(action: start)
        controller.request(tx) { [weak self] error in
            Task { @MainActor in
                if let error {
                    self?.delegate?.callKit(didFail: error.localizedDescription)
                    return
                }
                self?.provider.reportOutgoingCall(with: uuid, startedConnectingAt: Date())
            }
        }
    }

    func reportOutgoingConnected(callID: UUID) {
        guard let uuid = callUUIDByCallID[callID] else { return }
        provider.reportOutgoingCall(with: uuid, connectedAt: Date())
    }

    func reportIncoming(
        callID: UUID,
        peerUsername: String,
        hasVideo: Bool
    ) {
        let uuid = mappedUUID(for: callID)
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: peerUsername)
        update.hasVideo = hasVideo
        update.localizedCallerName = peerUsername
        provider.reportNewIncomingCall(with: uuid, update: update) { [weak self] error in
            Task { @MainActor in
                if let error {
                    self?.delegate?.callKit(didFail: error.localizedDescription)
                }
            }
        }
    }

    func end(callID: UUID, reason: CXCallEndedReason = .remoteEnded) {
        guard let uuid = callUUIDByCallID[callID] else { return }
        provider.reportCall(with: uuid, endedAt: Date(), reason: reason)
        callUUIDByCallID.removeValue(forKey: callID)
        callIDByUUID.removeValue(forKey: uuid)
    }

    private func mappedUUID(for callID: UUID) -> UUID {
        if let existing = callUUIDByCallID[callID] { return existing }
        let uuid = UUID()
        callUUIDByCallID[callID] = uuid
        callIDByUUID[uuid] = callID
        return uuid
    }
}

@MainActor
protocol CallKitManagerDelegate: AnyObject {
    func callKit(answer callID: UUID)
    func callKit(end callID: UUID)
    func callKit(mute callID: UUID, muted: Bool)
    func callKit(didFail message: String)
}

extension CallKitManager: CXProviderDelegate {
    nonisolated func providerDidReset(_ provider: CXProvider) {
        Task { @MainActor in
            callUUIDByCallID.removeAll()
            callIDByUUID.removeAll()
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        Task { @MainActor in
            if let callID = callIDByUUID[action.callUUID] {
                delegate?.callKit(answer: callID)
            }
            action.fulfill()
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        Task { @MainActor in
            if let callID = callIDByUUID[action.callUUID] {
                delegate?.callKit(end: callID)
            }
            action.fulfill()
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        Task { @MainActor in
            if let callID = callIDByUUID[action.callUUID] {
                delegate?.callKit(mute: callID, muted: action.isMuted)
            }
            action.fulfill()
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        action.fulfill()
    }

    nonisolated func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        try? audioSession.setCategory(
            .playAndRecord,
            mode: .voiceChat,
            options: [.allowBluetooth, .allowBluetoothA2DP, .defaultToSpeaker]
        )
        try? audioSession.setActive(true)
    }
}
