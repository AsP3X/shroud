import AVFoundation
import CallKit
import CryptoKit
import Foundation
import UIKit
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
        /// How the call was placed (the ring says so). Whether it is voice or video now is up to
        /// the two cameras: `isVideoEnabled` and `remoteCameraOff`.
        var modality: CallModality
        let isOutgoing: Bool
        var phase: Phase
        var isMuted: Bool
        /// Our camera is on and sent. Either side can switch its own at any time mid-call.
        var isVideoEnabled: Bool
        /// This call can carry our video. False only with an older app on the other side.
        var canVideo: Bool
        var connectionState: String
        var startedAt: Date?
        var endedText: String?
        var reconnecting: Bool
        var remoteMicMuted: Bool
        var remoteCameraOff: Bool
        /// Our screen goes out (a broadcast runs and its frames arrive), next to the camera.
        var isSharingScreen: Bool
        /// A broadcast has connected and its first frame is not here yet: Share already stops it.
        var screenShareStarting: Bool
        /// This call can carry our screen and their app can show it. False with an older app on
        /// either side.
        var canShareScreen: Bool
        /// They say they share their screen (`media_state`).
        var remoteSharingScreen: Bool
        var notice: String?
        var speakerOn: Bool
        /// The safety number has been compared on this phone. The call still connects either way.
        var safetyVerified: Bool
    }

    /// Current call screen (nil when idle).
    private(set) var active: ActiveCall?
    private(set) var lastError: String?
    /// Recent ended calls for the Calls tab (this launch only).
    private(set) var recent: [RecentCall] = []
    private(set) var localVideoTrack: RTCVideoTrack?
    private(set) var remoteVideoTrack: RTCVideoTrack?
    /// Their camera's frames are arriving since it was last switched on: their picture shows.
    private(set) var remoteVideoLive = false
    /// Our camera's frames are arriving since it was switched on: our own picture shows.
    private(set) var localVideoLive = false
    private(set) var usesFrontCamera = true
    private(set) var canSwitchCamera = false
    private(set) var remoteScreenTrack: RTCVideoTrack?
    /// Their screen's frames are arriving since they started sharing: their screen shows.
    private(set) var remoteScreenLive = false
    /// The resolution and frame rate our screen goes out at, in every call from this phone.
    private(set) var screenShareQuality = ScreenShareQuality.saved

    struct RecentCall: Identifiable, Equatable {
        let id: UUID
        let peerUserID: UUID
        let peerUsername: String
        let modality: CallModality
        let isOutgoing: Bool
        /// How it ended, in the server's words ("ended", "missed", "rejected", "cancelled", "busy";
        /// `recentStatus`), or "answered_elsewhere" when another of our devices took the call.
        let status: String
        /// Media connected at some point. An "ended" call that never did (couldn't connect, lost
        /// while connecting, hung up before it connected) is not a completed one.
        let connected: Bool
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
    /// The app's end of a screen broadcast, listening while a call runs.
    private var screenReceiver: ScreenShareReceiver?
    #if DEBUG && targetEnvironment(simulator)
    /// The simulator cannot broadcast its screen: a pattern goes through the same socket instead.
    private var simulatedBroadcast: SimulatedBroadcast?
    #endif

    /// One live call's signaling. Replaced wholesale when the call ends.
    private final class Machine {
        let generation: Int
        let role: CallCrypto.Role
        var serverID: UUID?
        var peerDeviceID: UUID?
        var keys: CallSignalKeys?
        /// The identity call secret, kept only until the per-call key is derived.
        var identitySecret: SymmetricKey?
        var ephPrivate: Curve25519.KeyAgreement.PrivateKey?
        var ephPublic: Data?
        /// Post-setup signals. The identity keys, when the peer sent no ephemeral key.
        var forward: CallSignalKeys?
        /// The first offer or answer has been taken, so later ones use `forward`.
        var gotSetup = false
        /// Our first answer has been sealed with the identity key.
        var sealedAnswer = false
        var expectedFingerprint: String?
        var fingerprintChecked = false
        var pendingSend: [CallSignal] = []
        var pendingSecure: [(from: String, type: String, payload: String)] = []
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
        var mediaOrder = CallMediaOrder()
        /// The system paused our camera: they are told it is off until it runs again.
        var cameraPaused = false
        /// Waiting for camera access after Video was pressed.
        var cameraBusy = false
        /// Video moved the sound to the speaker; it goes back to the earpiece with the video.
        var speakerForVideo = false
        /// Their app can show a screen: its `media_state` carries `screen`.
        var peerShowsScreens = false
        /// A broadcast is connected and our screen is on its section (its first frame may not be
        /// here yet).
        var broadcasting = false
        /// What CallKit was last told: a video call or not.
        var reportedVideo: Bool?
        var noticeTask: Task<Void, Never>?

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
        engine.screenQuality = screenShareQuality
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
        engine.onRemoteFrame = { [weak self] in
            self?.remoteVideoLive = true
        }
        engine.onLocalFrame = { [weak self] in
            self?.localVideoLive = true
        }
        engine.onRemoteScreen = { [weak self] track in
            self?.remoteScreenTrack = track
        }
        engine.onRemoteScreenFrame = { [weak self] in
            self?.remoteScreenLive = true
        }
        engine.onCameraPaused = { [weak self] paused in
            guard let self, let machine = self.machine, self.current(machine) else { return }
            machine.cameraPaused = paused
            // They see our face rather than the last frame, frozen, while the camera is away.
            if self.active?.isVideoEnabled == true { self.sendMedia(machine) }
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
            let relayOnly = try Self.relayPolicy(for: ice)
            let camera = modality == .video ? await cameraAccess() : false
            guard current(machine) else { return }
            engine.start(iceServers: ice, video: camera, offering: true, relayOnly: relayOnly)
            publishLocalPreview(modality: modality, machine: machine)
            let media = engine
            machine.offerTask = Task { @MainActor in
                try await media.makeOffer(iceRestart: false)
            }
            let created = try await service.createCall(peerUserID: peerUserID, modality: modality, token: token)
            guard current(machine) else {
                try? await service.hangupCall(id: created.id, token: token)
                return
            }
            machine.serverID = created.id
            machine.identitySecret = secret
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

    /// The microphone's level for the speaking indicator; nil with no media.
    func localAudioLevel() async -> Float? {
        await engine.localAudioLevel()
    }

    func toggleMute() async {
        guard let call = active else { return }
        await setMuted(!call.isMuted, fromKit: false)
    }

    /// Video on or off: a voice call becomes a video call and back, from either side, without a
    /// new offer (docs/calls.md, "Switching between voice and video"). Only our own camera;
    /// theirs is theirs to switch.
    func toggleVideo() async {
        guard let machine, current(machine), !machine.cameraBusy, let call = active else { return }
        guard call.phase == .outgoingRinging || call.phase == .connecting || call.phase == .active else { return }
        if call.isVideoEnabled {
            engine.stopCamera()
            localVideoLive = false
            canSwitchCamera = false
            setVideoEnabled(false, machine)
            return
        }
        guard engine.canSendVideo else {
            note("Video isn’t available in this call. Their app needs an update.", machine)
            return
        }
        machine.cameraBusy = true
        let allowed = await cameraAccess()
        machine.cameraBusy = false
        guard current(machine), active?.isVideoEnabled == false, active?.phase != .ending else { return }
        guard allowed else {
            note("Allow camera access for Shroud in Settings to turn on video.", machine)
            return
        }
        guard engine.startCamera() else {
            note("Your camera isn’t available right now.", machine)
            return
        }
        localVideoTrack = engine.localVideoTrack
        canSwitchCamera = engine.canSwitchCamera
        usesFrontCamera = engine.usesFrontCamera
        machine.cameraPaused = false
        setVideoEnabled(true, machine)
    }

    func toggleSpeaker() {
        guard var call = active, call.phase != .ending else { return }
        call.speakerOn.toggle()
        active = call
        // Chosen by hand: video no longer moves it.
        machine?.speakerForVideo = false
        CallAudio.setSpeaker(call.speakerOn)
    }

    func switchCamera() {
        guard canSwitchCamera, active?.isVideoEnabled == true else { return }
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
            finish(machine, text: "Answered on another device", notify: nil, status: "answered_elsewhere", close: .report(.answeredElsewhere))
            return
        }
        if call.callStatus == .active { return }
        let text = CallEndReason.from(status: call.status, reason: call.endedReason, isOutgoing: false)?.announcement
        finish(
            machine,
            text: text,
            notify: nil,
            status: Self.recentStatus(call.status, reason: call.endedReason),
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
            machine.identitySecret = secret
            machine.keys = CallSignalKeys(secret: secret, callID: id, role: .callee)
            let ice = (try? await service.iceServers(token: token)) ?? []
            guard current(machine) else { return }
            let relayOnly = try Self.relayPolicy(for: ice)
            let camera = video ? await cameraAccess() : false
            guard current(machine) else { return }
            engine.start(iceServers: ice, video: camera, offering: false, relayOnly: relayOnly)
            publishLocalPreview(modality: active?.modality ?? .voice, machine: machine)
            do {
                _ = try await service.acceptCall(id: id, token: token)
            } catch {
                guard current(machine) else { return }
                if let info = try? await service.getCall(id: id, token: token) {
                    if info.callStatus == .active, let mine = sessionController?.session?.deviceID,
                       !same(info.calleeDeviceId, mine) {
                        finish(machine, text: "Answered on another device", notify: nil, status: "answered_elsewhere", close: .report(.answeredElsewhere))
                        return
                    }
                    if !info.isLive {
                        let text = CallEndReason.from(status: info.status, reason: info.endedReason, isOutgoing: false)?.announcement
                        finish(machine, text: text, notify: nil, status: Self.recentStatus(info.status, reason: info.endedReason), close: .report(.remoteEnded))
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
            machine.identitySecret = nil
            if var call = active {
                call.phase = .incomingRinging
                call.notice = error.localizedDescription
                active = call
            }
            lastError = error.localizedDescription
        } catch is CallRelayUnavailable {
            // Nothing was accepted: end it here only, so the ring goes on on the other devices.
            guard current(machine) else { return }
            let message = callErrorText(CallRelayUnavailable(), peer: active?.peerUsername ?? "them")
            lastError = message
            finish(machine, text: message, notify: nil, status: "ended", close: .report(.failed), visible: .seconds(4))
        } catch is PeerIdentityError {
            // CallKit is already on the answered call. End it here without telling the server,
            // so the ring continues on their other devices while this one asks for the safety number.
            guard current(machine) else { return }
            let message = callErrorText(PeerIdentityError.changed, peer: active?.peerUsername ?? "them")
            lastError = message
            finish(machine, text: message, notify: nil, status: "ended", close: .report(.failed), visible: .seconds(4))
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
            finish(machine, text: "Answered on another device", notify: nil, status: "answered_elsewhere", close: .report(.answeredElsewhere))
        }
    }

    private func handleEnded(_ json: [String: Any]) {
        guard let machine, let call = parseCall(json["call"]), machine.serverID == call.id else { return }
        let outgoing = active?.isOutgoing ?? (machine.role == .caller)
        let text = CallEndReason.from(status: call.status, reason: call.endedReason, isOutgoing: outgoing)?.announcement
        finish(
            machine,
            text: text,
            notify: nil,
            status: Self.recentStatus(call.status, reason: call.endedReason),
            close: .report(kitReason(status: call.status, reason: call.endedReason))
        )
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
            if machine.ephPrivate == nil {
                let eph = Curve25519.KeyAgreement.PrivateKey()
                machine.ephPrivate = eph
                machine.ephPublic = eph.publicKey.rawRepresentation
            }
            send(
                .offer(sdp: CallSdp.withoutCandidates(sdp), restart: false, ephemeral: machine.ephPublic),
                machine
            )
            markNegotiated(machine)
        } catch {
            guard current(machine) else { return }
            finish(machine, text: "Couldn't connect", notify: .hangup, status: "ended", close: .report(.failed))
        }
    }

    private func receive(from: String, type: String, payload: String, machine: Machine) async {
        guard current(machine), machine.keys != nil, let id = machine.serverID else { return }
        guard let key = openKey(type: type, machine: machine) else {
            machine.pendingSecure.append((from, type, payload))
            return
        }
        let opened: Data
        do {
            opened = try CallCrypto.open(payload, key: key, callID: id, signalType: type)
        } catch {
            return
        }
        guard let parsed = try? CallSignal.parse(opened, signalType: type) else { return }
        var seen = machine.seen[from, default: []]
        guard seen.insert(parsed.n).inserted else { return }
        machine.seen[from] = seen
        switch parsed.signal {
        case let .offer(sdp, restart, ephemeral):
            guard machine.role == .callee else { return }
            await answerOffer(sdp, ephemeral: restart ? nil : ephemeral, restart: restart, machine)
        case let .answer(sdp, ephemeral):
            guard machine.role == .caller else { return }
            await takeAnswer(sdp, ephemeral: ephemeral, machine)
        case let .candidates(list):
            addRemote(list, machine)
        case .restartRequest:
            guard machine.role == .caller else { return }
            restartIce(machine)
        case let .media(mic, camera, screen):
            // The latest wins: the server's kept copy can arrive after a newer one.
            guard machine.mediaOrder.isNewer(parsed.n, from: from), var call = active else { return }
            let cameraWasOn = !call.remoteCameraOff
            let screenWasOn = call.remoteSharingScreen
            // An app that knows screens always says whether it shares one; an older one never does.
            if screen != nil { machine.peerShowsScreens = true }
            call.remoteMicMuted = !mic
            call.remoteCameraOff = !camera
            call.remoteSharingScreen = screen == true
            active = call
            if camera != cameraWasOn {
                // Their picture shows again from its first new frame, never a stale one.
                remoteVideoLive = false
                if camera { engine.awaitRemoteFrame() }
            }
            if call.remoteSharingScreen != screenWasOn {
                remoteScreenLive = false
                if call.remoteSharingScreen {
                    engine.awaitRemoteScreenFrame()
                    // Their screen is to be looked at, not listened to at the ear.
                    preferSpeaker(machine)
                }
            }
            refreshCanVideo()
            videoChanged(machine)
        }
    }

    private func answerOffer(_ sdp: String, ephemeral: Data?, restart: Bool, _ machine: Machine) async {
        do {
            if !restart {
                if machine.ephPrivate == nil {
                    let eph = Curve25519.KeyAgreement.PrivateKey()
                    machine.ephPrivate = eph
                    machine.ephPublic = eph.publicKey.rawRepresentation
                }
                engageForward(ephemeral, machine)
                machine.gotSetup = true
            }
            noteFingerprint(sdp, machine)
            let answer = try await engine.answer(offer: sdp)
            guard current(machine) else { return }
            flushRemote(machine)
            let ours = machine.sealedAnswer ? nil : machine.ephPublic
            send(.answer(sdp: CallSdp.withoutCandidates(answer), ephemeral: ours), machine)
            if !machine.negotiated {
                markNegotiated(machine)
            } else {
                releaseHeld(machine)
            }
            refreshCanVideo()
        } catch {
            guard current(machine), !machine.negotiated else { return }
            finish(machine, text: "Couldn't connect", notify: .hangup, status: "ended", close: .report(.failed))
        }
    }

    private func takeAnswer(_ sdp: String, ephemeral: Data?, _ machine: Machine) async {
        guard current(machine) else { return }
        if !machine.gotSetup {
            engageForward(ephemeral, machine)
            machine.gotSetup = true
        }
        noteFingerprint(sdp, machine)
        guard let applied = try? await engine.applyAnswer(sdp), applied else { return }
        flushRemote(machine)
        releaseHeld(machine)
        refreshCanVideo()
    }

    private func verifyFingerprint(_ machine: Machine) {
        guard !machine.fingerprintChecked, let expected = machine.expectedFingerprint else { return }
        Task { [weak self] in
            guard let self else { return }
            var got = await self.engine.remoteCertificateFingerprint()
            if got == nil {
                guard await self.sleep(.milliseconds(400)), self.current(machine) else { return }
                got = await self.engine.remoteCertificateFingerprint()
            }
            guard self.current(machine), let got else { return }
            machine.fingerprintChecked = true
            guard CallSdp.matches(expected, got) else {
                self.finish(
                    machine,
                    text: "This call couldn't be verified.",
                    notify: .hangup,
                    status: "ended",
                    close: .report(.failed),
                    visible: .seconds(4)
                )
                return
            }
        }
    }

    /// Identity key for the first offer and answer; the per-call key after that.
    private func openKey(type: String, machine: Machine) -> SymmetricKey? {
        let setup = type == "sdp_offer" || type == "sdp_answer"
        if setup && !machine.gotSetup { return machine.keys?.receive }
        return machine.forward?.receive
    }

    /// Derives the post-setup keys. An older peer sends no ephemeral key, and the identity keys continue.
    private func engageForward(_ theirKey: Data?, _ machine: Machine) {
        guard machine.forward == nil, let id = machine.serverID, let identity = machine.keys else { return }
        if let theirKey {
            // They sent a key, so they will open later signals with it. The long-term key
            // must not carry the addresses if that derivation does not succeed.
            guard let ours = machine.ephPrivate, let pub = machine.ephPublic, let secret = machine.identitySecret,
                  let forward = try? CallCrypto.forwardSecret(
                    identitySecret: secret,
                    ourEphemeralPrivate: ours,
                    ourEphemeralPublic: pub,
                    peerEphemeralPublic: theirKey,
                    callID: id
                  )
            else { return }
            machine.forward = CallSignalKeys(forwardSecret: forward, callID: id, role: machine.role)
        } else {
            machine.forward = identity
        }
        machine.ephPrivate = nil
        machine.identitySecret = nil
    }

    private func noteFingerprint(_ sdp: String, _ machine: Machine) {
        guard let fingerprint = CallSdp.fingerprint(sdp) else { return }
        if fingerprint != machine.expectedFingerprint { machine.fingerprintChecked = false }
        machine.expectedFingerprint = fingerprint
    }

    private func releaseHeld(_ machine: Machine) {
        flushGathered(machine)
        let waiting = machine.pendingSend
        machine.pendingSend.removeAll()
        for signal in waiting where current(machine) { send(signal, machine) }
        let inbound = machine.pendingSecure
        machine.pendingSecure.removeAll()
        for item in inbound where current(machine) {
            let previous = machine.inbox
            machine.inbox = Task { [weak self] in
                await previous?.value
                guard let self, self.current(machine) else { return }
                await self.receive(from: item.from, type: item.type, payload: item.payload, machine: machine)
            }
        }
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
        guard machine.negotiated, machine.forward != nil, current(machine) else { return }
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
        listenForBroadcasts(machine)
    }

    /// What we send now. A camera the system paused counts as off: they see our face, not a still.
    /// `screen` always goes along: it also tells them this app can show theirs.
    private func sendMedia(_ machine: Machine) {
        guard machine.negotiated, let call = active else { return }
        let camera = call.isVideoEnabled && engine.isCameraOn && !machine.cameraPaused
        send(.media(mic: !call.isMuted, camera: camera, screen: call.isSharingScreen), machine)
    }

    private func send(_ signal: CallSignal, _ machine: Machine) {
        guard machine.serverID != nil else { return }
        let needsForward: Bool
        switch signal {
        case .offer(_, let restart, _): needsForward = restart
        case .answer: needsForward = machine.sealedAnswer
        default: needsForward = true
        }
        if needsForward && machine.forward == nil {
            machine.pendingSend.append(signal)
            return
        }
        guard let key = sealingKey(signal, machine) else { return }
        if case .answer = signal { machine.sealedAnswer = true }
        machine.sent += 1
        let n = machine.sent
        let previous = machine.outbox
        machine.outbox = Task { [weak self] in
            await previous?.value
            guard let self, self.current(machine) else { return }
            await self.deliver(signal, n: n, key: key, machine: machine)
        }
    }

    /// The first offer and the first answer stay under the identity keys. Later signals use the per-call key.
    private func sealingKey(_ signal: CallSignal, _ machine: Machine) -> SymmetricKey? {
        switch signal {
        case .offer(_, let restart, _):
            return restart ? machine.forward?.send : machine.keys?.send
        case .answer:
            return machine.sealedAnswer ? machine.forward?.send : machine.keys?.send
        default:
            return machine.forward?.send
        }
    }

    private func deliver(_ signal: CallSignal, n: Int, key: SymmetricKey, machine: Machine) async {
        guard let id = machine.serverID, let token = sessionController?.bearerToken else { return }
        guard let plaintext = try? signal.plaintext(n: n) else { return }
        guard let payload = try? CallCrypto.seal(plaintext, key: key, callID: id, signalType: signal.signalType) else { return }
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
            verifyFingerprint(machine)
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
        Task { [weak self] in
            guard let self, self.current(machine) else { return }
            do {
                let sdp = try await self.engine.makeOffer(iceRestart: true)
                guard self.current(machine) else { return }
                self.send(.offer(sdp: CallSdp.withoutCandidates(sdp), restart: true, ephemeral: nil), machine)
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
                finish(machine, text: "Answered on another device", notify: nil, status: "answered_elsewhere", close: .report(.answeredElsewhere))
                return
            }
            catchUpMedia(info, machine)
            return
        }
        let outgoing = active?.isOutgoing ?? (machine.role == .caller)
        let text = CallEndReason.from(status: info.status, reason: info.endedReason, isOutgoing: outgoing)?.announcement
        finish(
            machine,
            text: text,
            notify: nil,
            status: Self.recentStatus(info.status, reason: info.endedReason),
            close: .report(kitReason(status: info.status, reason: info.endedReason))
        )
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
                    connected: call.startedAt != nil,
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
        machine.noticeTask?.cancel()
    }

    private func teardownMedia() {
        stopBroadcasts()
        engine.close()
        localVideoTrack = nil
        remoteVideoTrack = nil
        remoteVideoLive = false
        localVideoLive = false
        canSwitchCamera = false
        remoteScreenTrack = nil
        remoteScreenLive = false
    }

    // MARK: - Switching between voice and video

    /// Our camera went on or off: they are told, and the call's sound and CallKit follow.
    private func setVideoEnabled(_ on: Bool, _ machine: Machine) {
        guard var call = active else { return }
        call.isVideoEnabled = on
        call.notice = nil
        active = call
        sendMedia(machine)
        // The phone is away from the ear now: the sound leaves the earpiece for the speaker.
        if on { preferSpeaker(machine) }
        videoChanged(machine)
    }

    /// The phone is held away from the ear (a camera, a screen): the sound leaves the earpiece
    /// for the speaker, and comes back once nothing is left to look at.
    private func preferSpeaker(_ machine: Machine) {
        guard let call = active, !call.speakerOn, CallAudio.isOnReceiver else { return }
        machine.speakerForVideo = true
        setSpeaker(true)
    }

    /// A camera or a screen changed. CallKit shows a video call while any picture is on, and once
    /// no video is left the sound goes back to the earpiece, if it was video that moved it.
    private func videoChanged(_ machine: Machine) {
        guard let call = active, call.phase != .ending else { return }
        let hasVideo = call.isVideoEnabled || !call.remoteCameraOff || call.isSharingScreen || call.remoteSharingScreen
        if let id = machine.serverID, machine.reportedVideo != hasVideo {
            machine.reportedVideo = hasVideo
            ensureCallKit().update(id, callerName: call.peerUsername, video: hasVideo)
        }
        if !hasVideo, machine.speakerForVideo, call.speakerOn {
            machine.speakerForVideo = false
            setSpeaker(false)
        }
    }

    private func setSpeaker(_ on: Bool) {
        guard var call = active else { return }
        call.speakerOn = on
        active = call
        CallAudio.setSpeaker(on)
    }

    /// The answer settled whether our video can go out in this call; their `media_state`,
    /// whether they can show our screen.
    private func refreshCanVideo() {
        guard var call = active else { return }
        let canShare = engine.canSendScreen && machine?.peerShowsScreens == true
        guard call.canVideo != engine.canSendVideo || call.canShareScreen != canShare else { return }
        call.canVideo = engine.canSendVideo
        call.canShareScreen = canShare
        active = call
    }

    // MARK: - Sharing the screen

    /// What Share does. True when the system's broadcast picker should open: Shroud cannot start
    /// a broadcast itself, the person starts it there. Stopping needs no picker: closing the
    /// broadcast's connection ends it (docs/calls.md, "Screen sharing").
    func toggleScreenShare() -> Bool {
        guard let machine, current(machine), let call = active else { return false }
        guard call.phase == .active || call.phase == .connecting else {
            // Share shows, dimmed, while our call rings out: the tap says why it does nothing.
            if call.phase == .outgoingRinging { note(shareUnavailableText(call), machine) }
            return false
        }
        if call.isSharingScreen || machine.broadcasting {
            screenReceiver?.dropBroadcast()
            #if DEBUG && targetEnvironment(simulator)
            simulatedBroadcast?.stop()
            simulatedBroadcast = nil
            #endif
            broadcastEnded(machine)
            return false
        }
        guard call.canShareScreen, screenReceiver != nil else {
            note(shareUnavailableText(call), machine)
            return false
        }
        #if DEBUG && targetEnvironment(simulator)
        let broadcast = SimulatedBroadcast()
        simulatedBroadcast = broadcast
        broadcast.start()
        return false
        #else
        return true
        #endif
    }

    /// The resolution and frame rate for sharing our screen, kept for the next share too. While
    /// we share, the broadcast and the encoder take it at once, with no new offer.
    func setScreenShareQuality(_ quality: ScreenShareQuality) {
        guard quality != screenShareQuality else { return }
        screenShareQuality = quality
        ScreenShareQuality.saved = quality
        engine.screenQuality = quality
        screenReceiver?.setQuality(quality)
    }

    /// From the answer on, a broadcast started now (from Share, or from Control Center) goes
    /// on this call.
    private func listenForBroadcasts(_ machine: Machine) {
        guard screenReceiver == nil else { return }
        let receiver = ScreenShareReceiver { [weak self] event in
            guard let self, let machine = self.machine, self.current(machine) else { return }
            self.broadcastEvent(event, machine)
        }
        receiver?.setQuality(screenShareQuality)
        receiver?.start()
        screenReceiver = receiver
    }

    private func broadcastEvent(_ event: ScreenShareReceiver.Event, _ machine: Machine) {
        switch event {
        case .connected:
            guard var call = active, call.phase != .ending else {
                screenReceiver?.dropBroadcast()
                return
            }
            guard call.canShareScreen, engine.startScreen() else {
                // Nowhere to send it (yet): the broadcast ends, and the screen says why.
                screenReceiver?.dropBroadcast()
                note(call.canShareScreen ? "Couldn’t share your screen." : shareUnavailableText(call), machine)
                return
            }
            machine.broadcasting = true
            screenReceiver?.feed = engine.screenFeed
            call.screenShareStarting = true
            active = call
        case .firstFrame:
            guard machine.broadcasting, var call = active, !call.isSharingScreen else { return }
            call.isSharingScreen = true
            call.screenShareStarting = false
            call.notice = nil
            active = call
            sendMedia(machine)
            preferSpeaker(machine)
            videoChanged(machine)
        case .disconnected:
            broadcastEnded(machine)
        }
    }

    /// The broadcast is over: nothing more goes out, and they are told.
    private func broadcastEnded(_ machine: Machine) {
        machine.broadcasting = false
        screenReceiver?.feed = nil
        engine.stopScreen()
        guard var call = active, call.isSharingScreen || call.screenShareStarting else { return }
        let wasShared = call.isSharingScreen
        call.isSharingScreen = false
        call.screenShareStarting = false
        active = call
        guard wasShared else { return }
        sendMedia(machine)
        videoChanged(machine)
    }

    /// Why Share cannot be used in this call right now.
    private func shareUnavailableText(_ call: ActiveCall) -> String {
        if screenReceiver == nil, call.phase == .active, call.canShareScreen {
            return "Screen sharing isn’t available on this \(UIDevice.current.model)."
        }
        // Their app says whether it shows screens as the call connects.
        guard call.phase == .active else { return "You can share your screen once the call has connected." }
        return "Screen sharing isn’t available in this call. Their app needs an update."
    }

    private func stopBroadcasts() {
        #if DEBUG && targetEnvironment(simulator)
        simulatedBroadcast?.stop()
        simulatedBroadcast = nil
        #endif
        screenReceiver?.stop()
        screenReceiver = nil
    }

    /// The other device's latest media state as the server kept it: a camera switch whose
    /// signal was lost in a socket gap still arrives, at the latest with the next heartbeat.
    /// It is opened and checked like any signal; one taken already, or older, changes nothing.
    private func catchUpMedia(_ info: CallDTO, _ machine: Machine) {
        guard let kept = info.peerMediaState, same(kept.fromDeviceId, machine.peerDeviceID) else { return }
        let from = kept.fromDeviceId.uuidString.lowercased()
        let previous = machine.inbox
        machine.inbox = Task { [weak self] in
            await previous?.value
            guard let self, self.current(machine) else { return }
            await self.receive(from: from, type: "media_state", payload: kept.payload, machine: machine)
        }
    }

    /// Whether the camera may be used, asking the first time. The simulator's test pattern
    /// needs no permission.
    private func cameraAccess() async -> Bool {
        guard CallCamera.isAvailable else { return true }
        return await CallCamera.requestAccess()
    }

    /// A passing line under the name (why the camera did not come on).
    private func note(_ text: String, _ machine: Machine) {
        guard var call = active else { return }
        call.notice = text
        active = call
        machine.noticeTask?.cancel()
        machine.noticeTask = Task { [weak self] in
            guard let self, await self.sleep(.seconds(6)), self.current(machine) else { return }
            guard var call = self.active, call.notice == text else { return }
            call.notice = nil
            self.active = call
        }
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
        call.isVideoEnabled = engine.isCameraOn
        call.canVideo = engine.canSendVideo
        active = call
        // A video call whose camera is refused or missing goes on with sound; Video can try again.
        if modality == .video, !engine.isCameraOn {
            note("Your camera isn’t available, so your video is off.", machine)
        }
        // Video put the sound on the speaker; it goes back to the earpiece with the video.
        if modality == .video, call.speakerOn { machine.speakerForVideo = true }
        machine.reportedVideo = modality == .video
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
            canVideo: false,
            connectionState: "new",
            startedAt: nil,
            endedText: nil,
            reconnecting: false,
            remoteMicMuted: false,
            // Until they say otherwise: a video call's other side sends video, a voice call's not.
            remoteCameraOff: modality != .video,
            isSharingScreen: false,
            screenShareStarting: false,
            canShareScreen: false,
            remoteSharingScreen: false,
            notice: nil,
            speakerOn: modality == .video,
            safetyVerified: messagingController?.peerSafetyVerified(peerUserID) ?? false
        )
    }

    /// The safety number for the call on screen, when this phone has the contact's key.
    func safetyNumberForActiveCall() -> String? {
        guard let peer = active?.peerUserID else { return nil }
        return messagingController?.safetyNumber(for: peer)
    }

    /// The number on the call screen was compared.
    func confirmSafety() {
        guard var call = active, call.phase != .ending else { return }
        messagingController?.confirmPeerSafety(call.peerUserID)
        call.safetyVerified = true
        active = call
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

    /// Whether this call must stay on the relay ("Always relay calls"); throws when it must but
    /// the server offers none, since the only other way to connect would show this phone's address.
    static func relayPolicy(for servers: [IceServerDTO]) throws -> Bool {
        try relayPolicy(for: servers, alwaysRelay: SecurityPreferences.alwaysRelayCalls)
    }

    static func relayPolicy(for servers: [IceServerDTO], alwaysRelay: Bool) throws -> Bool {
        guard alwaysRelay else { return false }
        guard CallMediaEngine.offersRelay(servers) else { throw CallRelayUnavailable() }
        return true
    }

    private func callErrorText(_ error: Error, peer: String) -> String {
        if error is CallRelayUnavailable {
            return "“Always relay calls” is on, but this server has no relay. Turn it off in Privacy and Security to call directly."
        }
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

    /// How Recents files a call the server ended: as the server has it, except a decline it files
    /// as missed, which reads "Declined" there as on the call screen.
    private static func recentStatus(_ status: String, reason: String?) -> String {
        reason == "declined" ? "rejected" : status
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

/// "Always relay calls" is on, and the server handed out no TURN relay to go through.
struct CallRelayUnavailable: Error, Equatable {}

extension ScreenShareQuality {
    private static let resolutionKey = "calls.screenShareResolution"
    private static let frameRateKey = "calls.screenShareFrameRate"

    /// The choice kept on this phone (UserDefaults: a preference, not a secret); `standard`
    /// until one is made.
    static var saved: ScreenShareQuality {
        get {
            let defaults = UserDefaults.standard
            return ScreenShareQuality(
                resolution: defaults.string(forKey: resolutionKey).flatMap(Resolution.init(rawValue:)) ?? standard.resolution,
                frameRate: FrameRate(rawValue: defaults.integer(forKey: frameRateKey)) ?? standard.frameRate
            )
        }
        set {
            UserDefaults.standard.set(newValue.resolution.rawValue, forKey: resolutionKey)
            UserDefaults.standard.set(newValue.frameRate.rawValue, forKey: frameRateKey)
        }
    }
}
