import AVFoundation
import CallKit
import CryptoKit
import Foundation
import WebRTC

extension Notification.Name {
    /// A call is about to take the microphone, so a voice note stops.
    static let shroudCallMediaStarting = Notification.Name("shroud.call.mediaStarting")
}

/// Orchestrates 1:1 voice and video calls: sealed signaling, native WebRTC, and CallKit.
///
/// Human: Call a contact, or answer one, including from the lock screen. Audio and video go
/// between the two phones (or through the relay). The server only rings devices and forwards
/// signals it cannot read.
/// Agent: Protocol 2 in docs/calls.md. The caller offers, ICE restarts included. Signals are
/// sealed with `CallCrypto`. CallKit ids are the server call id.
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
        var id: UUID
        var peerUserID: UUID
        var peerUsername: String
        var modality: CallModality
        let isOutgoing: Bool
        var phase: Phase
        var isMuted: Bool
        var isVideoEnabled: Bool
        var connectionState: String
        var startedAt: Date?
        var endedText: String?
        var reconnecting: Bool
        var remoteMicMuted: Bool
        var remoteCameraOff: Bool
        var notice: String?
        var speakerOn: Bool
    }

    /// Current call screen (nil when idle).
    private(set) var active: ActiveCall?
    private(set) var lastError: String?
    /// Recent ended calls for the Calls tab (this launch only).
    private(set) var recent: [RecentCall] = []
    private(set) var localVideoTrack: RTCVideoTrack?
    private(set) var remoteVideoTrack: RTCVideoTrack?
    private(set) var usesFrontCamera = true
    private(set) var canSwitchCamera = false

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
    private let engine = CallMediaEngine()
    private var callKit: CallKitManager?
    private weak var sessionController: SessionController?
    private weak var messagingController: MessagingController?
    private var machine: Machine?
    private var generation = 0
    private var finished: [String] = []
    private var ignoreKitEnd = false
    private var dismissTask: Task<Void, Never>?

    /// One live call's signaling. Replaced wholesale when the call ends.
    private final class Machine {
        let generation: Int
        let role: CallCrypto.Role
        var serverID: UUID?
        var peerDeviceID: UUID?
        var keys: CallSignalKeys?
        var negotiated = false
        var sent = 0
        var seen: [String: Set<Int>] = [:]
        var gathered: [IceCandidatePayload] = []
        var pendingRemote: [IceCandidatePayload] = []
        var earlySignals: [(from: String, type: String, payload: String)] = []
        var earlyEvents: [(type: String, json: [String: Any])] = []
        var offerTask: Task<String, Error>?
        var outbox: Task<Void, Never>?
        var inbox: Task<Void, Never>?
        var heartbeat: Task<Void, Never>?
        var ringLimit: Task<Void, Never>?
        var ringCheck: Task<Void, Never>?
        var connectTimer: Task<Void, Never>?
        var graceTimer: Task<Void, Never>?
        var reconnectTimer: Task<Void, Never>?
        var restartTimer: Task<Void, Never>?
        var batchTimer: Task<Void, Never>?
        var lastRestart = Date.distantPast
        var accepting = false
        var dialing = false
        var ended = false
        var linkBroken = false
        /// Set when the link has failed. A restart delayed by the 10 s gate still uses the relay.
        var wantRelay = false
        var reportedConnected = false

        init(generation: Int, role: CallCrypto.Role) {
            self.generation = generation
            self.role = role
        }
    }

    private enum ServerNotify {
        case hangup
        case reject
    }

    private enum KitClose {
        case none
        case requestEnd
        case report(CXCallEndedReason)
    }

    init() {
        CallAudio.setUp()
        ensureCallKit()
        // The call keeps the socket when the chats lock or the app backgrounds, and opens it
        // for a ring that woke a locked phone. Messaging forwards nothing: both would run.
        RealtimeClient.shared.setListener(.call) { [weak self] event in
            guard let self, case let .raw(type, json) = event else { return }
            self.handleRealtime(type: type, json: json)
        }
        engine.onLocalCandidate = { [weak self] candidate in
            self?.gathered(candidate)
        }
        engine.onConnection = { [weak self] state in
            guard let self, let machine = self.machine, self.current(machine) else { return }
            self.linkChanged(state, machine)
        }
        engine.onRemoteVideo = { [weak self] track in
            self?.remoteVideoTrack = track
        }
    }

    /// Drops the call screen and history (sign-out). A call this phone had joined is hung up.
    func clearLocalState() {
        if let machine, !machine.ended {
            let joined = machine.role == .caller || machine.accepting
                || active?.phase == .connecting || active?.phase == .active
            if joined, let id = machine.serverID, let token = sessionController?.bearerToken {
                Task { try? await service.hangupCall(id: id, token: token) }
            }
            if let id = machine.serverID {
                ensureCallKit().reportEnded(id, reason: .remoteEnded)
            }
        }
        releaseSocket()
        teardownMedia()
        machine?.ended = true
        machine = nil
        generation += 1
        dismissTask?.cancel()
        active = nil
        lastError = nil
        recent = []
    }

    func bind(session: SessionController, messaging: MessagingController) {
        sessionController = session
        messagingController = messaging
        // A push can ring before this bind; the socket needs the token it now has.
        if machine != nil { holdSocket() }
    }

    /// Handle raw realtime call events from `RealtimeClient`.
    func handleRealtime(type: String, json: [String: Any]) {
        if type == "auth.ok" {
            guard let machine, machine.serverID != nil else { return }
            Task { await self.reconcile(machine) }
            return
        }
        // The outgoing call's id is not known yet. Rings for other people are not this call.
        if let machine, machine.dialing, machine.serverID == nil, type != "call.ring" {
            machine.earlyEvents.append((type, json))
            return
        }
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

    /// True when a VoIP push for this call has already ended it. A later ring push must not
    /// start the call UI again.
    func callWasFinished(_ callID: UUID) -> Bool {
        finished.contains(callID.uuidString.lowercased())
    }

    /// Seed an incoming call from a VoIP push when the socket ring has not arrived yet.
    /// - Parameter alreadyReported: True when CallKit was told in the PushKit callback.
    func handleVoipPush(
        callID: UUID,
        peerUserID: UUID? = nil,
        peerUsername: String,
        modality: CallModality,
        alreadyReported: Bool = false
    ) {
        ensureCallKit()
        if callWasFinished(callID) {
            if alreadyReported {
                ensureCallKit().reportEnded(callID, reason: .remoteEnded)
            } else {
                ensureCallKit().reportIncoming(callID, callerName: peerUsername, video: modality == .video) { _ in
                    Task { @MainActor in
                        CallKitManager.shared.reportEnded(callID, reason: .remoteEnded)
                    }
                }
            }
            return
        }
        if let machine, machine.serverID != callID {
            ensureCallKit().reportEnded(callID, reason: .unanswered)
            ensureCallKit().flushPending()
            return
        }
        if machine?.serverID == callID {
            applyCallerName(peerUsername, callID: callID, video: modality == .video)
            ensureCallKit().flushPending()
            return
        }
        guard beginIncoming(
            id: callID,
            peerUserID: peerUserID ?? UUID(),
            peerUsername: peerUsername,
            modality: modality,
            peerDeviceID: nil,
            reportKit: !alreadyReported
        ) else {
            ensureCallKit().flushPending()
            return
        }
        ensureCallKit().flushPending()
        Task { await self.confirmStillRinging(callID) }
    }

    /// True while this phone is ringing, connecting, or in a call. The lock screen must not
    /// treat that moment as a signed-out launch.
    var isInCall: Bool {
        if let machine, !machine.ended { return true }
        switch active?.phase {
        case .outgoingRinging, .incomingRinging, .connecting, .active:
            return true
        case .idle, .ending, nil:
            return false
        }
    }

    /// A PushKit `call_ended`: CallKit must stop, including when this phone never saw the socket.
    func endFromVoipPush(_ callID: UUID) {
        // This phone is the one that answered, or is already talking. Ending CallKit here
        // drops a live call. The server check below hangs up only if the call is actually over.
        if machine?.serverID == callID,
           machine?.accepting == true || active?.phase == .connecting || active?.phase == .active
        {
            ensureCallKit().update(callID, callerName: active?.peerUsername ?? "Shroud", video: active?.modality == .video)
            Task { await self.confirmStillRinging(callID) }
            return
        }
        rememberFinished(callID)
        let kit = ensureCallKit()
        if machine?.serverID == callID || kit.isTracking(callID) {
            kit.reportEnded(callID, reason: .remoteEnded)
            if machine?.serverID == callID {
                Task { await self.confirmStillRinging(callID) }
            }
            return
        }
        // Already over, or this push beat the ring. PushKit still requires a report.
        kit.reportIncoming(callID, callerName: "Shroud", video: false) { _ in
            Task { @MainActor in
                CallKitManager.shared.reportEnded(callID, reason: .remoteEnded)
            }
        }
    }

    // MARK: - Actions

    func startCall(peerUserID: UUID, peerUsername: String, modality: CallModality) async {
        // A call this phone still has open, including one whose screen already closed, is
        // ended first. Otherwise every later call is refused.
        if machine != nil || (active != nil && active?.phase != .ending) {
            await userEnd(fromKit: false)
        }
        if active?.phase == .ending { active = nil }
        guard machine == nil, active == nil else {
            lastError = "Already in a call."
            return
        }
        guard sessionController?.bearerToken != nil else {
            lastError = "Not signed in."
            return
        }
        lastError = nil
        holdSocket()
        generation += 1
        let machine = Machine(generation: generation, role: .caller)
        machine.dialing = true
        self.machine = machine
        active = makeCall(
            id: UUID(),
            peerUserID: peerUserID,
            peerUsername: peerUsername,
            modality: modality,
            outgoing: true,
            phase: .outgoingRinging
        )
        takeMicrophone()
        CallAudio.configure(video: modality == .video)
        if modality == .video { CallAudio.setSpeaker(true) }

        do {
            let secret = try await callSecret(for: peerUserID)
            guard current(machine) else { return }
            let token = try requireToken()
            let ice = try await service.iceServers(token: token)
            guard current(machine) else { return }
            engine.start(iceServers: ice, video: modality == .video)
            publishLocalPreview(modality: modality, machine: machine)
            let receiveVideo = modality == .video
            let media = engine
            machine.offerTask = Task { @MainActor in
                try await media.makeOffer(iceRestart: false, receiveVideo: receiveVideo)
            }
            let created = try await service.createCall(peerUserID: peerUserID, modality: modality, token: token)
            guard current(machine) else {
                try? await service.hangupCall(id: created.id, token: token)
                return
            }
            machine.serverID = created.id
            machine.keys = CallSignalKeys(secret: secret, callID: created.id, role: .caller)
            machine.dialing = false
            if var call = active {
                call.id = created.id
                active = call
            }
            startHeartbeat(machine)
            arm(machine, after: 75, text: "No answer", notify: .hangup, status: "missed", close: .report(.unanswered))
            await startKit(machine, id: created.id, name: peerUsername, video: modality == .video)
            replayEarly(machine)
            watchOffer(machine)
        } catch {
            guard current(machine) else { return }
            let message = callErrorText(error, peer: peerUsername)
            lastError = message
            let notify: ServerNotify? = machine.serverID == nil ? nil : .hangup
            finish(machine, text: message, notify: notify, status: "ended", close: .report(.failed), visible: .seconds(4))
        }
    }

    func acceptIncoming() async {
        await answer(fromKit: false)
    }

    func rejectIncoming() async {
        await userEnd(fromKit: false)
    }

    func hangup() async {
        await userEnd(fromKit: false)
    }

    func toggleMute() async {
        guard let call = active else { return }
        await setMuted(!call.isMuted, fromKit: false)
    }

    func toggleVideo() async {
        guard var call = active, call.modality == .video, call.phase != .incomingRinging, call.phase != .ending else { return }
        guard localVideoTrack != nil else { return }
        call.isVideoEnabled.toggle()
        active = call
        engine.setCameraEnabled(call.isVideoEnabled)
        if let machine { sendMedia(machine) }
    }

    func toggleSpeaker() {
        guard var call = active, call.phase != .ending else { return }
        call.speakerOn.toggle()
        active = call
        CallAudio.setSpeaker(call.speakerOn)
    }

    func switchCamera() {
        guard canSwitchCamera else { return }
        engine.switchCamera()
        usesFrontCamera = engine.usesFrontCamera
    }

    // MARK: - CallKit

    private func ensureCallKit() -> CallKitManager {
        let manager = CallKitManager.shared
        manager.delegate = self
        callKit = manager
        return manager
    }

    private func startKit(_ machine: Machine, id: UUID, name: String, video: Bool) async {
        do {
            try await ensureCallKit().requestStart(id, handle: name, video: video)
        } catch {
            // No CallKit (the simulator). The in-app call still runs.
        }
    }

    private func setMuted(_ muted: Bool, fromKit: Bool) async {
        guard var call = active, call.phase != .incomingRinging, call.phase != .ending else { return }
        call.isMuted = muted
        active = call
        engine.setMicrophoneEnabled(!muted)
        if let machine { sendMedia(machine) }
        guard !fromKit, let id = machine?.serverID else { return }
        try? await ensureCallKit().requestMute(id, muted: muted)
    }

    // MARK: - Incoming

    @discardableResult
    private func beginIncoming(
        id: UUID,
        peerUserID: UUID,
        peerUsername: String,
        modality: CallModality,
        peerDeviceID: UUID?,
        reportKit: Bool
    ) -> Bool {
        if active?.phase == .ending { active = nil }
        guard machine == nil, active == nil else { return false }
        generation += 1
        let machine = Machine(generation: generation, role: .callee)
        machine.serverID = id
        machine.peerDeviceID = peerDeviceID
        self.machine = machine
        active = makeCall(
            id: id,
            peerUserID: peerUserID,
            peerUsername: peerUsername,
            modality: modality,
            outgoing: false,
            phase: .incomingRinging
        )
        if reportKit {
            ensureCallKit().reportIncoming(id, callerName: peerUsername, video: modality == .video)
        }
        arm(machine, after: 70, text: "Missed call", notify: nil, status: "missed", close: .report(.unanswered))
        holdSocket()
        machine.ringCheck = Task { [weak self] in
            while let self, self.current(machine), !Task.isCancelled {
                guard await self.sleep(.seconds(10)) else { return }
                guard self.current(machine) else { return }
                await self.reconcile(machine)
            }
        }
        return true
    }

    private func handleRing(_ json: [String: Any]) {
        guard let me = sessionController?.userID,
              let call = parseCall(json["call"]),
              call.callProtocol == 2,
              call.callStatus == .ringing,
              same(call.calleeUserId, me),
              !same(call.callerUserId, me)
        else { return }
        let key = call.id.uuidString.lowercased()
        if finished.contains(key) { return }
        let name = callerName(call)
        if machine?.serverID == call.id {
            machine?.peerDeviceID = machine?.peerDeviceID ?? call.callerDeviceId
            applyCallerName(name, callID: call.id, video: call.callModality == .video)
            if var shown = active {
                shown.peerUserID = call.callerUserId
                shown.modality = call.callModality
                active = shown
            }
            return
        }
        _ = beginIncoming(
            id: call.id,
            peerUserID: call.callerUserId,
            peerUsername: name,
            modality: call.callModality,
            peerDeviceID: call.callerDeviceId,
            reportKit: true
        )
    }

    private func confirmStillRinging(_ id: UUID) async {
        guard let machine, machine.serverID == id, let token = sessionController?.bearerToken else { return }
        guard let call = try? await service.getCall(id: id, token: token) else { return }
        guard current(machine) else { return }
        if call.callProtocol == 2, call.callStatus == .ringing {
            machine.peerDeviceID = call.callerDeviceId
            applyCallerName(callerName(call), callID: call.id, video: call.callModality == .video)
            if var shown = active {
                shown.peerUserID = call.callerUserId
                shown.modality = call.callModality
                active = shown
            }
            return
        }
        // Still ringing here: another of our devices answered. A call this phone already
        // accepted is `active` on the server too, and must keep going.
        if call.callStatus == .active, active?.phase == .incomingRinging {
            finish(machine, text: "Answered on another device", notify: nil, status: "ended", close: .report(.answeredElsewhere))
            return
        }
        if call.callStatus == .active { return }
        let text = CallEndReason.from(status: call.status, reason: call.endedReason, isOutgoing: false)?.announcement
        finish(
            machine,
            text: text,
            notify: nil,
            status: call.status,
            close: .report(kitReason(status: call.status, reason: call.endedReason))
        )
    }

    private func answer(fromKit: Bool) async {
        guard let machine, machine.role == .callee, active?.phase == .incomingRinging,
              let id = machine.serverID
        else { return }
        if !fromKit {
            do {
                try await ensureCallKit().requestAnswer(id)
                return
            } catch {
                // No CallKit: answer from the in-app button.
            }
        }
        guard current(machine), !machine.accepting else { return }
        machine.accepting = true
        if var call = active {
            call.phase = .connecting
            call.notice = nil
            active = call
        }
        takeMicrophone()
        let video = active?.modality == .video
        CallAudio.configure(video: video)
        if active?.speakerOn == true { CallAudio.setSpeaker(true) }
        do {
            guard let peer = active?.peerUserID else { return }
            let secret = try await callSecret(for: peer)
            guard current(machine), let token = sessionController?.bearerToken else { return }
            machine.keys = CallSignalKeys(secret: secret, callID: id, role: .callee)
            let ice = (try? await service.iceServers(token: token)) ?? []
            guard current(machine) else { return }
            engine.start(iceServers: ice, video: video)
            publishLocalPreview(modality: active?.modality ?? .voice, machine: machine)
            do {
                _ = try await service.acceptCall(id: id, token: token)
            } catch {
                guard current(machine) else { return }
                if let info = try? await service.getCall(id: id, token: token) {
                    if info.callStatus == .active, let mine = sessionController?.session?.deviceID,
                       !same(info.calleeDeviceId, mine) {
                        finish(machine, text: "Answered on another device", notify: nil, status: "ended", close: .report(.answeredElsewhere))
                        return
                    }
                    if !info.isLive {
                        let text = CallEndReason.from(status: info.status, reason: info.endedReason, isOutgoing: false)?.announcement
                        finish(machine, text: text, notify: nil, status: info.status, close: .report(.remoteEnded))
                        return
                    }
                }
                throw error
            }
            guard current(machine) else {
                try? await service.hangupCall(id: id, token: token)
                return
            }
            machine.accepting = false
            machine.ringLimit?.cancel()
            machine.ringCheck?.cancel()
            startHeartbeat(machine)
            arm(machine, after: 30, text: "Couldn't connect", notify: .hangup, status: "ended", close: .report(.failed))
            drainSignals(machine)
        } catch let error as CallSecretError {
            guard current(machine) else { return }
            machine.accepting = false
            machine.keys = nil
            if var call = active {
                call.phase = .incomingRinging
                call.notice = error.localizedDescription
                active = call
            }
            lastError = error.localizedDescription
        } catch {
            guard current(machine) else { return }
            let message = callErrorText(error, peer: active?.peerUsername ?? "them")
            lastError = message
            finish(machine, text: message, notify: .hangup, status: "ended", close: .report(.failed), visible: .seconds(4))
        }
    }

    // MARK: - Socket events

    private func handleAccepted(_ json: [String: Any]) {
        guard let call = parseCall(json["call"]) else { return }
        guard let machine else { return }
        guard machine.serverID == call.id else { return }
        if machine.role == .caller {
            callerAnswered(machine, call)
        } else if let mine = sessionController?.session?.deviceID, !same(call.calleeDeviceId, mine) {
            finish(machine, text: "Answered on another device", notify: nil, status: "ended", close: .report(.answeredElsewhere))
        }
    }

    private func handleEnded(_ json: [String: Any]) {
        guard let machine, let call = parseCall(json["call"]), machine.serverID == call.id else { return }
        let outgoing = active?.isOutgoing ?? (machine.role == .caller)
        let text = CallEndReason.from(status: call.status, reason: call.endedReason, isOutgoing: outgoing)?.announcement
        finish(machine, text: text, notify: nil, status: call.status, close: .report(kitReason(status: call.status, reason: call.endedReason)))
    }

    private func handleSignal(_ json: [String: Any]) {
        guard let machine,
              let callID = (json["call_id"] as? String).flatMap(UUID.init(uuidString:)),
              machine.serverID == callID,
              let signalType = json["signal_type"] as? String,
              let payload = json["payload"] as? String,
              !payload.isEmpty
        else { return }
        let fromUser = (json["from_user_id"] as? String).flatMap(UUID.init(uuidString:))
        guard same(fromUser, active?.peerUserID) else { return }
        let fromDevice = (json["from_device_id"] as? String)?.lowercased() ?? ""
        if let peerDevice = machine.peerDeviceID, !fromDevice.isEmpty,
           fromDevice != peerDevice.uuidString.lowercased() {
            return
        }
        guard machine.keys != nil else {
            machine.earlySignals.append((fromDevice, signalType, payload))
            return
        }
        let previous = machine.inbox
        machine.inbox = Task { [weak self] in
            await previous?.value
            guard let self, self.current(machine) else { return }
            await self.receive(from: fromDevice, type: signalType, payload: payload, machine: machine)
        }
    }

    private func callerAnswered(_ machine: Machine, _ call: CallDTO) {
        guard active?.phase == .outgoingRinging else { return }
        machine.peerDeviceID = call.calleeDeviceId
        machine.ringLimit?.cancel()
        if var shown = active {
            shown.phase = .connecting
            active = shown
        }
        arm(machine, after: 30, text: "Couldn't connect", notify: .hangup, status: "ended", close: .report(.failed))
        Task { await self.sendOffer(machine) }
    }

    private func replayEarly(_ machine: Machine) {
        let events = machine.earlyEvents
        machine.earlyEvents.removeAll()
        for event in events where current(machine) {
            handleRealtime(type: event.type, json: event.json)
        }
    }

    // MARK: - Media and signals

    private func watchOffer(_ machine: Machine) {
        Task { [weak self] in
            do {
                _ = try await machine.offerTask?.value
            } catch {
                guard let self, self.current(machine), machine.serverID != nil else { return }
                guard self.active?.phase == .outgoingRinging || self.active?.phase == .connecting else { return }
                self.lastError = "Couldn't start the call."
                self.finish(machine, text: "Couldn't start the call.", notify: .hangup, status: "ended", close: .report(.failed), visible: .seconds(4))
            }
        }
    }

    private func sendOffer(_ machine: Machine) async {
        do {
            guard let sdp = try await machine.offerTask?.value, !sdp.isEmpty else {
                throw CallMediaEngine.EngineError.sdp("No offer.")
            }
            guard current(machine) else { return }
            send(.offer(sdp: sdp, restart: false), machine)
            markNegotiated(machine)
        } catch {
            guard current(machine) else { return }
            finish(machine, text: "Couldn't connect", notify: .hangup, status: "ended", close: .report(.failed))
        }
    }

    private func receive(from: String, type: String, payload: String, machine: Machine) async {
        guard current(machine), let keys = machine.keys, let id = machine.serverID else { return }
        let opened: Data
        do {
            opened = try CallCrypto.open(payload, key: keys.receive, callID: id, signalType: type)
        } catch {
            return
        }
        guard let parsed = try? CallSignal.parse(opened, signalType: type) else { return }
        var seen = machine.seen[from, default: []]
        guard seen.insert(parsed.n).inserted else { return }
        machine.seen[from] = seen
        switch parsed.signal {
        case let .offer(sdp, _):
            guard machine.role == .callee else { return }
            await answerOffer(sdp, machine)
        case let .answer(sdp):
            guard machine.role == .caller else { return }
            await takeAnswer(sdp, machine)
        case let .candidates(list):
            addRemote(list, machine)
        case .restartRequest:
            guard machine.role == .caller else { return }
            restartIce(machine)
        case let .media(mic, camera):
            guard var call = active else { return }
            call.remoteMicMuted = !mic
            call.remoteCameraOff = !camera
            active = call
        }
    }

    private func answerOffer(_ sdp: String, _ machine: Machine) async {
        do {
            let answer = try await engine.answer(offer: sdp)
            guard current(machine) else { return }
            flushRemote(machine)
            send(.answer(sdp: answer), machine)
            if !machine.negotiated { markNegotiated(machine) }
        } catch {
            guard current(machine), !machine.negotiated else { return }
            finish(machine, text: "Couldn't connect", notify: .hangup, status: "ended", close: .report(.failed))
        }
    }

    private func takeAnswer(_ sdp: String, _ machine: Machine) async {
        guard current(machine) else { return }
        guard let applied = try? await engine.applyAnswer(sdp), applied else { return }
        flushRemote(machine)
    }

    private func addRemote(_ candidates: [IceCandidatePayload], _ machine: Machine) {
        guard current(machine) else { return }
        if !engine.hasRemoteDescription {
            machine.pendingRemote.append(contentsOf: candidates)
            return
        }
        engine.addRemoteCandidates(candidates)
    }

    private func flushRemote(_ machine: Machine) {
        let waiting = machine.pendingRemote
        machine.pendingRemote.removeAll()
        guard !waiting.isEmpty else { return }
        engine.addRemoteCandidates(waiting)
    }

    private func gathered(_ candidate: IceCandidatePayload) {
        guard let machine, current(machine), !candidate.candidate.isEmpty else { return }
        machine.gathered.append(candidate)
        guard machine.negotiated, machine.batchTimer == nil else { return }
        machine.batchTimer = Task { [weak self] in
            guard let self, await self.sleep(.milliseconds(100)), self.current(machine) else { return }
            machine.batchTimer = nil
            self.flushGathered(machine)
        }
    }

    private func flushGathered(_ machine: Machine) {
        machine.batchTimer?.cancel()
        machine.batchTimer = nil
        guard machine.negotiated, current(machine) else { return }
        while !machine.gathered.isEmpty {
            let count = min(20, machine.gathered.count)
            let batch = Array(machine.gathered.prefix(count))
            machine.gathered.removeFirst(count)
            send(.candidates(batch), machine)
        }
    }

    private func markNegotiated(_ machine: Machine) {
        machine.negotiated = true
        flushGathered(machine)
        sendMedia(machine)
    }

    private func sendMedia(_ machine: Machine) {
        guard machine.negotiated, let call = active else { return }
        let camera = call.isVideoEnabled && localVideoTrack != nil
        send(.media(mic: !call.isMuted, camera: camera), machine)
    }

    private func send(_ signal: CallSignal, _ machine: Machine) {
        guard machine.keys != nil, machine.serverID != nil else { return }
        machine.sent += 1
        let n = machine.sent
        let previous = machine.outbox
        machine.outbox = Task { [weak self] in
            await previous?.value
            guard let self, self.current(machine) else { return }
            await self.deliver(signal, n: n, machine: machine)
        }
    }

    private func deliver(_ signal: CallSignal, n: Int, machine: Machine) async {
        guard let id = machine.serverID, let keys = machine.keys, let token = sessionController?.bearerToken else { return }
        guard let plaintext = try? signal.plaintext(n: n) else { return }
        guard let payload = try? CallCrypto.seal(plaintext, key: keys.send, callID: id, signalType: signal.signalType) else { return }
        for attempt in 0..<3 {
            guard current(machine) else { return }
            do {
                try await service.signal(callID: id, signalType: signal.signalType, payload: payload, token: token)
                return
            } catch let error as APIError {
                if case let .server(code, _, status) = error {
                    if code == "CALL_ENDED" {
                        await reconcile(machine, fallback: "Call ended")
                        return
                    }
                    let retry = attempt < 2 && (code == "CALL_NOT_ANSWERED" || status >= 500 || status == 429)
                    if !retry { return }
                } else if case .transport = error, attempt < 2 {
                    // one more try
                } else {
                    return
                }
            } catch {
                return
            }
            guard await sleep(.seconds(attempt == 0 ? 0.5 : 1.5)) else { return }
        }
    }

    private func drainSignals(_ machine: Machine) {
        let queued = machine.earlySignals
        machine.earlySignals.removeAll()
        for item in queued where current(machine) {
            let previous = machine.inbox
            machine.inbox = Task { [weak self] in
                await previous?.value
                guard let self, self.current(machine) else { return }
                await self.receive(from: item.from, type: item.type, payload: item.payload, machine: machine)
            }
        }
    }

    // MARK: - Connection

    private func linkChanged(_ state: CallMediaEngine.Connection, _ machine: Machine) {
        switch state {
        case .connected:
            machine.linkBroken = false
            machine.graceTimer?.cancel()
            machine.graceTimer = nil
            machine.reconnectTimer?.cancel()
            machine.reconnectTimer = nil
            machine.restartTimer?.cancel()
            machine.restartTimer = nil
            machine.connectTimer?.cancel()
            if active?.phase == .connecting {
                if var call = active {
                    call.phase = .active
                    call.startedAt = call.startedAt ?? Date()
                    call.reconnecting = false
                    call.connectionState = "connected"
                    active = call
                }
                if !machine.reportedConnected, let id = machine.serverID {
                    machine.reportedConnected = true
                    ensureCallKit().reportConnected(id)
                }
            } else if var call = active, call.reconnecting {
                call.reconnecting = false
                call.connectionState = "connected"
                active = call
            }
        case .disconnected:
            machine.linkBroken = true
            troubled(machine)
            guard machine.graceTimer == nil else { return }
            machine.graceTimer = Task { [weak self] in
                guard let self, await self.sleep(.seconds(4)), self.current(machine) else { return }
                machine.graceTimer = nil
                if machine.linkBroken { self.restartIce(machine) }
            }
        case .failed:
            machine.linkBroken = true
            machine.wantRelay = true
            troubled(machine)
            restartIce(machine)
        case .new, .connecting:
            if var call = active, call.phase != .ending {
                call.connectionState = String(describing: state)
                active = call
            }
        case .closed:
            // `engine.close()` runs after the call is marked ended, so this is a peer
            // connection that shut itself while the call was still up.
            guard active?.phase == .connecting || active?.phase == .active else { return }
            finish(machine, text: "Connection lost", notify: .hangup, status: "ended", close: .report(.remoteEnded))
        }
    }

    private func troubled(_ machine: Machine) {
        guard active?.phase == .active else { return }
        if var call = active, !call.reconnecting {
            call.reconnecting = true
            call.connectionState = "disconnected"
            active = call
        }
        guard machine.reconnectTimer == nil else { return }
        machine.reconnectTimer = Task { [weak self] in
            guard let self, await self.sleep(.seconds(30)), self.current(machine), machine.linkBroken else { return }
            self.finish(machine, text: "Connection lost", notify: .hangup, status: "ended", close: .report(.remoteEnded))
        }
    }

    private func restartIce(_ machine: Machine) {
        guard current(machine), machine.negotiated else { return }
        guard active?.phase == .connecting || active?.phase == .active else { return }
        // Before the 10 s gate, so the offer that follows the wait gathers only relay candidates.
        if machine.wantRelay { engine.preferRelay() }
        let wait = machine.lastRestart.addingTimeInterval(10).timeIntervalSinceNow
        if wait > 0 {
            guard machine.restartTimer == nil else { return }
            machine.restartTimer = Task { [weak self] in
                guard let self, await self.sleep(.seconds(wait)), self.current(machine) else { return }
                machine.restartTimer = nil
                if machine.linkBroken { self.restartIce(machine) }
            }
            return
        }
        machine.lastRestart = Date()
        if machine.role == .callee {
            send(.restartRequest, machine)
            return
        }
        guard engine.canOffer else {
            guard machine.restartTimer == nil else { return }
            machine.restartTimer = Task { [weak self] in
                guard let self, await self.sleep(.seconds(1)), self.current(machine) else { return }
                machine.restartTimer = nil
                if machine.linkBroken { self.restartIce(machine) }
            }
            return
        }
        let receiveVideo = active?.modality == .video
        Task { [weak self] in
            guard let self, self.current(machine) else { return }
            do {
                let sdp = try await self.engine.makeOffer(iceRestart: true, receiveVideo: receiveVideo)
                guard self.current(machine) else { return }
                self.send(.offer(sdp: sdp, restart: true), machine)
            } catch {
                // The next failed or disconnected report tries again.
            }
        }
    }

    // MARK: - Server liveness

    private func startHeartbeat(_ machine: Machine) {
        guard machine.heartbeat == nil else { return }
        machine.heartbeat = Task { [weak self] in
            while let self, self.current(machine), !Task.isCancelled {
                guard await self.sleep(.seconds(10)) else { return }
                guard self.current(machine) else { return }
                await self.beat(machine)
            }
        }
    }

    private func beat(_ machine: Machine) async {
        guard let id = machine.serverID, let token = sessionController?.bearerToken else { return }
        do {
            let info = try await service.heartbeat(id: id, token: token)
            guard current(machine) else { return }
            applyServerStatus(info, machine)
        } catch let error as APIError {
            if case let .server(_, _, status) = error, status == 404 {
                finish(machine, text: "Call ended", notify: nil, status: "ended", close: .report(.remoteEnded))
            }
        } catch {
            // A missed heartbeat is not an ended call. The 45 s server limit still applies.
        }
    }

    private func reconcile(_ machine: Machine, fallback: String? = nil) async {
        guard let id = machine.serverID, let token = sessionController?.bearerToken else { return }
        do {
            let info = try await service.getCall(id: id, token: token)
            guard current(machine) else { return }
            applyServerStatus(info, machine)
        } catch let error as APIError {
            if case let .server(_, _, status) = error, status == 404 {
                finish(machine, text: fallback ?? "Call ended", notify: nil, status: "ended", close: .report(.remoteEnded))
            }
        } catch {
            if let fallback {
                finish(machine, text: fallback, notify: nil, status: "ended", close: .report(.remoteEnded))
            }
        }
    }

    private func applyServerStatus(_ info: CallDTO, _ machine: Machine) {
        guard current(machine), same(machine.serverID, info.id) else { return }
        if info.callStatus == .ringing { return }
        if info.callStatus == .active {
            if machine.role == .caller { callerAnswered(machine, info) }
            else if active?.phase == .incomingRinging {
                finish(machine, text: "Answered on another device", notify: nil, status: "ended", close: .report(.answeredElsewhere))
            }
            return
        }
        let outgoing = active?.isOutgoing ?? (machine.role == .caller)
        let text = CallEndReason.from(status: info.status, reason: info.endedReason, isOutgoing: outgoing)?.announcement
        finish(machine, text: text, notify: nil, status: info.status, close: .report(kitReason(status: info.status, reason: info.endedReason)))
    }

    // MARK: - Ending

    private func userEnd(fromKit: Bool) async {
        guard let machine, let call = active, call.phase != .ending else { return }
        if call.phase == .incomingRinging {
            finish(
                machine,
                text: nil,
                notify: .reject,
                status: "rejected",
                close: fromKit ? .none : .requestEnd
            )
            return
        }
        let ringingOut = call.phase == .outgoingRinging
        finish(
            machine,
            text: ringingOut ? nil : "Call ended",
            notify: .hangup,
            status: ringingOut ? "cancelled" : "ended",
            close: fromKit ? .none : .requestEnd
        )
    }

    private func finish(
        _ machine: Machine,
        text: String?,
        notify: ServerNotify?,
        status: String,
        close: KitClose,
        visible: Duration = .seconds(2)
    ) {
        guard current(machine) else { return }
        machine.ended = true
        releaseSocket()
        let generation = machine.generation
        let serverID = machine.serverID
        if let serverID {
            rememberFinished(serverID)
        }
        cancelWork(machine)
        teardownMedia()
        if let serverID, let notify, let token = sessionController?.bearerToken {
            Task {
                switch notify {
                case .hangup:
                    try? await service.hangupCall(id: serverID, token: token)
                case .reject:
                    try? await service.rejectCall(id: serverID, token: token)
                }
            }
        }
        if let serverID {
            closeKit(serverID, close)
        }
        if let call = active, let serverID {
            recent.insert(
                RecentCall(
                    id: serverID,
                    peerUserID: call.peerUserID,
                    peerUsername: call.peerUsername,
                    modality: call.modality,
                    isOutgoing: call.isOutgoing,
                    status: status,
                    at: Date()
                ),
                at: 0
            )
            if recent.count > 40 { recent = Array(recent.prefix(40)) }
        }
        self.machine = nil
        guard let text else {
            active = nil
            return
        }
        if var call = active {
            call.phase = .ending
            call.endedText = text
            call.reconnecting = false
            call.notice = nil
            active = call
        }
        dismissTask?.cancel()
        dismissTask = Task { [weak self] in
            guard let self, await self.sleep(visible) else { return }
            guard self.generation == generation, self.active?.phase == .ending else { return }
            self.active = nil
        }
    }

    /// False when the wait was cancelled, so a timer that was called off does not still run.
    private func sleep(_ duration: Duration) async -> Bool {
        do {
            try await Task.sleep(for: duration)
        } catch {
            return false
        }
        return !Task.isCancelled
    }

    /// Keeps `/ws` open for this call. A second hold while one is already up is the same socket.
    private func holdSocket() {
        guard let token = sessionController?.bearerToken else { return }
        RealtimeClient.shared.hold(.call, token: token)
    }

    private func releaseSocket() {
        RealtimeClient.shared.release(.call)
    }

    private func closeKit(_ id: UUID, _ close: KitClose) {
        switch close {
        case .none:
            break
        case .requestEnd:
            ignoreKitEnd = true
            Task {
                try? await ensureCallKit().requestEnd(id)
                ignoreKitEnd = false
            }
        case let .report(reason):
            ensureCallKit().reportEnded(id, reason: reason)
        }
    }

    private func cancelWork(_ machine: Machine) {
        machine.offerTask?.cancel()
        machine.heartbeat?.cancel()
        machine.ringLimit?.cancel()
        machine.ringCheck?.cancel()
        machine.connectTimer?.cancel()
        machine.graceTimer?.cancel()
        machine.reconnectTimer?.cancel()
        machine.restartTimer?.cancel()
        machine.batchTimer?.cancel()
    }

    private func teardownMedia() {
        engine.close()
        localVideoTrack = nil
        remoteVideoTrack = nil
        canSwitchCamera = false
    }

    private func arm(
        _ machine: Machine,
        after seconds: Double,
        text: String,
        notify: ServerNotify?,
        status: String,
        close: KitClose
    ) {
        let task = Task { [weak self] in
            do {
                try await Task.sleep(for: .seconds(seconds))
            } catch {
                return
            }
            guard let self, !Task.isCancelled, self.current(machine) else { return }
            self.finish(machine, text: text, notify: notify, status: status, close: close)
        }
        if seconds >= 60 {
            machine.ringLimit?.cancel()
            machine.ringLimit = task
        } else {
            machine.connectTimer?.cancel()
            machine.connectTimer = task
        }
    }

    // MARK: - Helpers

    private func current(_ machine: Machine) -> Bool {
        self.machine === machine && !machine.ended
    }

    private func requireToken() throws -> String {
        guard let token = sessionController?.bearerToken else {
            throw CallSecretError.chatsLocked
        }
        return token
    }

    private func callSecret(for peer: UUID) async throws -> SymmetricKey {
        if let stored = CallSecretStore.secret(for: peer) { return stored }
        guard let messagingController else { throw CallSecretError.chatsLocked }
        return try await messagingController.callSecret(for: peer)
    }

    private func takeMicrophone() {
        NotificationCenter.default.post(name: .shroudCallMediaStarting, object: nil)
        VoicePlaybackCoordinator.shared.stop()
    }

    private func publishLocalPreview(modality: CallModality, machine: Machine) {
        localVideoTrack = engine.localVideoTrack
        canSwitchCamera = engine.canSwitchCamera
        usesFrontCamera = engine.usesFrontCamera
        guard var call = active else { return }
        if modality == .video, localVideoTrack == nil {
            call.isVideoEnabled = false
            call.notice = "Your camera isn’t available, so this call is audio only."
        }
        active = call
    }

    private func makeCall(
        id: UUID,
        peerUserID: UUID,
        peerUsername: String,
        modality: CallModality,
        outgoing: Bool,
        phase: Phase
    ) -> ActiveCall {
        ActiveCall(
            id: id,
            peerUserID: peerUserID,
            peerUsername: peerUsername,
            modality: modality,
            isOutgoing: outgoing,
            phase: phase,
            isMuted: false,
            isVideoEnabled: modality == .video,
            connectionState: "new",
            startedAt: nil,
            endedText: nil,
            reconnecting: false,
            remoteMicMuted: false,
            remoteCameraOff: false,
            notice: nil,
            speakerOn: modality == .video
        )
    }

    private func applyCallerName(_ name: String, callID: UUID, video: Bool) {
        guard var call = active, !name.isEmpty, name != "Incoming call" else { return }
        call.peerUsername = name
        active = call
        ensureCallKit().update(callID, callerName: name, video: video)
    }

    private func callerName(_ call: CallDTO) -> String {
        if let name = call.callerUsername, !name.isEmpty { return name }
        if let contact = messagingController?.contacts.first(where: { same($0.userId, call.callerUserId) }) {
            return contact.username
        }
        return "Unknown"
    }

    private func rememberFinished(_ id: UUID) {
        let key = id.uuidString.lowercased()
        guard !finished.contains(key) else { return }
        finished.append(key)
        if finished.count > 20 { finished.removeFirst() }
    }

    private func parseCall(_ value: Any?) -> CallDTO? {
        guard let dict = value as? [String: Any],
              let data = try? JSONSerialization.data(withJSONObject: dict)
        else { return nil }
        return try? JSONDecoder.api.decode(CallDTO.self, from: data)
    }

    private func same(_ lhs: UUID?, _ rhs: UUID?) -> Bool {
        guard let lhs, let rhs else { return false }
        return lhs.uuidString.lowercased() == rhs.uuidString.lowercased()
    }

    private func callErrorText(_ error: Error, peer: String) -> String {
        if let secret = error as? CallSecretError {
            return secret.localizedDescription ?? "Open Shroud and unlock your chats to connect this call."
        }
        if error as? PeerIdentityError == .changed {
            return "This contact's encryption key changed. Verify their safety number before calling."
        }
        if let api = error as? APIError {
            switch api {
            case let .server(code, _, _):
                switch code {
                case "CALL_BUSY": return "\(peer) is on another call."
                case "CALL_IN_PROGRESS": return "You’re already in a call."
                case "FORBIDDEN": return "You can’t call \(peer)."
                case "RATE_LIMITED": return "Too many calls. Try again in a moment."
                default: break
                }
            case .transport:
                return "Couldn’t reach Shroud. Check your connection."
            case .decoding:
                break
            }
        }
        return "Couldn’t start the call."
    }

    private func kitReason(status: String, reason: String?) -> CXCallEndedReason {
        if status == "missed", reason != "declined" { return .unanswered }
        if status == "rejected" || reason == "declined" { return .declinedElsewhere }
        if status == "cancelled" { return .unanswered }
        return .remoteEnded
    }
}

extension CallController: CallKitManagerDelegate {
    func callKitStartCall(_ id: UUID) {
        guard machine?.serverID == id else { return }
        let video = active?.modality == .video
        CallAudio.configure(video: video)
        if active?.speakerOn == true { CallAudio.setSpeaker(true) }
        ensureCallKit().reportConnecting(id)
    }

    func callKitAnswerCall(_ id: UUID) {
        guard machine?.serverID == id else { return }
        Task { await answer(fromKit: true) }
    }

    func callKitEndCall(_ id: UUID) {
        guard !ignoreKitEnd else { return }
        guard machine?.serverID == id else { return }
        Task { await userEnd(fromKit: true) }
    }

    func callKitSetMuted(_ id: UUID, muted: Bool) {
        guard machine?.serverID == id else { return }
        Task { await setMuted(muted, fromKit: true) }
    }

    func callKitAudioActivated(_ session: AVAudioSession) {
        CallAudio.didActivate(session)
        if active?.speakerOn == true { CallAudio.setSpeaker(true) }
    }

    func callKitAudioDeactivated(_ session: AVAudioSession) {
        CallAudio.didDeactivate(session)
    }

    func callKitReset() {
        guard let machine else { return }
        finish(machine, text: nil, notify: .hangup, status: "ended", close: .none)
    }
}

