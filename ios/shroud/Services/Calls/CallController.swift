import AVFoundation
import CallKit
import Foundation

/// Orchestrates 1:1 voice/video calls: REST signaling + WebRTC + CallKit.
/// Human: Place/receive calls with contacts; media is peer-to-peer (or TURN).
/// Agent: Observes WS call.* events; never logs SDP bodies at info level.
@MainActor
@Observable
final class CallController {
    enum Phase: Equatable {
        case idle
        case outgoingRinging
        case incomingRinging
        case connecting
        case active
        case ending
    }

    struct ActiveCall: Equatable, Identifiable {
        let id: UUID
        let peerUserID: UUID
        var peerUsername: String
        let modality: CallModality
        let isOutgoing: Bool
        var phase: Phase
        var isMuted: Bool
        var isVideoEnabled: Bool
        var connectionState: String
        var startedAt: Date?
    }

    /// Current call overlay state (nil when idle).
    private(set) var active: ActiveCall?
    private(set) var lastError: String?
    /// Recent ended calls for the Calls tab (local session memory).
    private(set) var recent: [RecentCall] = []

    struct RecentCall: Identifiable, Equatable {
        let id: UUID
        let peerUserID: UUID
        let peerUsername: String
        let modality: CallModality
        let isOutgoing: Bool
        let status: String
        let at: Date
    }

    private let service = CallsService()
    private let engine = WebRTCEngine()
    /// Lazy — CXProvider creation is deferred so unit tests do not trap at import time.
    private var callKit: CallKitManager?
    private weak var sessionController: SessionController?
    private weak var messagingController: MessagingController?
    private var pendingRemoteOffer: String?
    private var iceBuffer: [String] = []
    private var enginePrepared = false

    init() {
        engine.onLocalSignal = { [weak self] type, payload in
            Task { @MainActor in
                await self?.relayLocalSignal(type: type, payload: payload)
            }
        }
        engine.onConnectionState = { [weak self] state in
            Task { @MainActor in
                self?.active?.connectionState = state
                if state == "connected" || state == "completed" {
                    if var a = self?.active {
                        a.phase = .active
                        if a.startedAt == nil { a.startedAt = Date() }
                        self?.active = a
                        self?.ensureCallKit().reportOutgoingConnected(callID: a.id)
                    }
                }
            }
        }
        engine.onError = { [weak self] message in
            Task { @MainActor in
                self?.lastError = message
            }
        }
    }

    @discardableResult
    private func ensureCallKit() -> CallKitManager {
        if let callKit { return callKit }
        let manager = CallKitManager()
        manager.delegate = self
        callKit = manager
        return manager
    }

    func bind(session: SessionController, messaging: MessagingController) {
        sessionController = session
        messagingController = messaging
    }

    /// Handle raw realtime call events from `RealtimeClient`.
    func handleRealtime(type: String, json: [String: Any]) {
        switch type {
        case "call.ring":
            handleRing(json)
        case "call.accepted":
            handleAccepted(json)
        case "call.ended":
            handleEnded(json)
        case "call.signal":
            handleSignal(json)
        default:
            break
        }
    }

    /// Seed an incoming call from a VoIP push when WS ring has not arrived yet.
    func handleVoipPush(callID: UUID, peerUsername: String, modality: CallModality) {
        guard active == nil else { return }
        active = ActiveCall(
            id: callID,
            peerUserID: UUID(), // filled when WS ring arrives
            peerUsername: peerUsername,
            modality: modality,
            isOutgoing: false,
            phase: .incomingRinging,
            isMuted: false,
            isVideoEnabled: modality == .video,
            connectionState: "new",
            startedAt: nil
        )
        ensureCallKit().reportIncoming(
            callID: callID,
            peerUsername: peerUsername,
            hasVideo: modality == .video
        )
    }

    // MARK: - Outgoing

    func startCall(peerUserID: UUID, peerUsername: String, modality: CallModality) async {
        guard active == nil else {
            lastError = "Already in a call."
            return
        }
        guard let token = sessionController?.bearerToken else {
            lastError = "Not signed in."
            return
        }

        lastError = nil
        do {
            try await configureAudioSession()
            let ice = try await service.iceServers(token: token)
            try await engine.prepare(iceServers: ice, video: modality == .video)
            enginePrepared = true

            let offer = try await engine.createOffer()
            let call = try await service.createCall(
                peerUserID: peerUserID,
                modality: modality,
                sdpOffer: offer,
                token: token
            )

            active = ActiveCall(
                id: call.id,
                peerUserID: peerUserID,
                peerUsername: peerUsername,
                modality: modality,
                isOutgoing: true,
                phase: .outgoingRinging,
                isMuted: false,
                isVideoEnabled: modality == .video,
                connectionState: "new",
                startedAt: nil
            )
            ensureCallKit().startOutgoing(
                callID: call.id,
                peerUsername: peerUsername,
                hasVideo: modality == .video
            )
            // Also push offer via signal path for peers that ignore sdp_offer on ring.
            try await service.signal(
                callID: call.id,
                signalType: "sdp_offer",
                payload: offer,
                token: token
            )
        } catch {
            engine.teardown()
            enginePrepared = false
            active = nil
            lastError = SessionController.userMessage(for: error)
        }
    }

    // MARK: - Incoming / answer / reject / hangup

    func acceptIncoming() async {
        guard var current = active, current.phase == .incomingRinging else { return }
        guard let token = sessionController?.bearerToken else { return }

        current.phase = .connecting
        active = current
        do {
            try await configureAudioSession()
            if !enginePrepared {
                let ice = try await service.iceServers(token: token)
                try await engine.prepare(iceServers: ice, video: current.modality == .video)
                enginePrepared = true
            }
            guard let offer = pendingRemoteOffer else {
                throw WebRTCEngine.EngineError.scriptFailed("Missing remote offer.")
            }
            let answer = try await engine.createAnswer(remoteOfferSDP: offer)
            _ = try await service.acceptCall(id: current.id, sdpAnswer: answer, token: token)
            try await service.signal(
                callID: current.id,
                signalType: "sdp_answer",
                payload: answer,
                token: token
            )
            for candidate in iceBuffer {
                try? await engine.addIceCandidate(candidate)
            }
            iceBuffer.removeAll()
            current.phase = .active
            current.startedAt = Date()
            active = current
        } catch {
            lastError = SessionController.userMessage(for: error)
            await hangup()
        }
    }

    func rejectIncoming() async {
        guard let current = active, current.phase == .incomingRinging,
              let token = sessionController?.bearerToken
        else {
            await resetLocal(status: "rejected")
            return
        }
        _ = try? await service.rejectCall(id: current.id, token: token)
        ensureCallKit().end(callID: current.id, reason: .declinedElsewhere)
        await resetLocal(status: "rejected")
    }

    func hangup() async {
        guard let current = active else { return }
        if let token = sessionController?.bearerToken {
            _ = try? await service.hangupCall(id: current.id, token: token)
        }
        ensureCallKit().end(callID: current.id, reason: .remoteEnded)
        await resetLocal(status: "ended")
    }

    func toggleMute() async {
        guard var current = active else { return }
        current.isMuted.toggle()
        active = current
        await engine.setMuted(current.isMuted)
    }

    func toggleVideo() async {
        guard var current = active, current.modality == .video else { return }
        current.isVideoEnabled.toggle()
        active = current
        await engine.setVideoEnabled(current.isVideoEnabled)
    }

    // MARK: - Realtime handlers

    private func handleRing(_ json: [String: Any]) {
        guard active == nil,
              let me = sessionController?.userID,
              let call = parseCall(json["call"]),
              call.calleeUserId == me
        else { return }

        pendingRemoteOffer = json["sdp_offer"] as? String
        let username = messagingController?.contacts.first(where: { $0.userId == call.callerUserId })?.username
            ?? "Shroud user"

        active = ActiveCall(
            id: call.id,
            peerUserID: call.callerUserId,
            peerUsername: username,
            modality: call.callModality,
            isOutgoing: false,
            phase: .incomingRinging,
            isMuted: false,
            isVideoEnabled: call.callModality == .video,
            connectionState: "new",
            startedAt: nil
        )
        ensureCallKit().reportIncoming(
            callID: call.id,
            peerUsername: username,
            hasVideo: call.callModality == .video
        )
    }

    private func handleAccepted(_ json: [String: Any]) {
        guard var current = active,
              let call = parseCall(json["call"]),
              call.id == current.id
        else { return }

        if let answer = json["sdp_answer"] as? String {
            Task {
                try? await engine.setRemoteAnswer(answer)
                for candidate in iceBuffer {
                    try? await engine.addIceCandidate(candidate)
                }
                iceBuffer.removeAll()
            }
        }
        current.phase = .connecting
        active = current
    }

    private func handleEnded(_ json: [String: Any]) {
        guard let call = parseCall(json["call"]),
              active?.id == call.id
        else { return }
        ensureCallKit().end(callID: call.id, reason: .remoteEnded)
        Task { await resetLocal(status: call.status) }
    }

    private func handleSignal(_ json: [String: Any]) {
        guard let callIDString = json["call_id"] as? String,
              let callID = UUID(uuidString: callIDString),
              active?.id == callID,
              let signalType = json["signal_type"] as? String,
              let payload = json["payload"] as? String
        else { return }

        Task {
            switch signalType {
            case "sdp_offer":
                pendingRemoteOffer = payload
            case "sdp_answer":
                try? await engine.setRemoteAnswer(payload)
            case "ice_candidate":
                if enginePrepared {
                    try? await engine.addIceCandidate(payload)
                } else {
                    iceBuffer.append(payload)
                }
            case "renegotiate":
                break
            default:
                break
            }
        }
    }

    private func relayLocalSignal(type: String, payload: String) async {
        guard let current = active,
              let token = sessionController?.bearerToken
        else { return }
        try? await service.signal(
            callID: current.id,
            signalType: type,
            payload: payload,
            token: token
        )
    }

    private func resetLocal(status: String) async {
        if let current = active {
            recent.insert(
                RecentCall(
                    id: current.id,
                    peerUserID: current.peerUserID,
                    peerUsername: current.peerUsername,
                    modality: current.modality,
                    isOutgoing: current.isOutgoing,
                    status: status,
                    at: Date()
                ),
                at: 0
            )
            if recent.count > 40 {
                recent = Array(recent.prefix(40))
            }
        }
        engine.hangup()
        enginePrepared = false
        pendingRemoteOffer = nil
        iceBuffer.removeAll()
        active = nil
    }

    private func parseCall(_ value: Any?) -> CallDTO? {
        guard let dict = value as? [String: Any],
              let data = try? JSONSerialization.data(withJSONObject: dict)
        else { return nil }
        return try? JSONDecoder.api.decode(CallDTO.self, from: data)
    }

    private func configureAudioSession() async throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(
            .playAndRecord,
            mode: .voiceChat,
            options: [.allowBluetooth, .allowBluetoothA2DP, .defaultToSpeaker]
        )
        try session.setActive(true)
    }
}

extension CallController: CallKitManagerDelegate {
    func callKit(answer callID: UUID) {
        guard active?.id == callID else { return }
        Task { await acceptIncoming() }
    }

    func callKit(end callID: UUID) {
        guard active?.id == callID else { return }
        Task { await hangup() }
    }

    func callKit(mute callID: UUID, muted: Bool) {
        guard var current = active, current.id == callID else { return }
        current.isMuted = muted
        active = current
        Task { await engine.setMuted(muted) }
    }

    func callKit(didFail message: String) {
        lastError = message
    }
}
