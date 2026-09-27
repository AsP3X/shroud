import Foundation
import WebRTC

/// The WebRTC side of one call: peer connection, local audio/video tracks, remote video.
/// The controller handles signaling and calls in here with SDP and candidates.
///
/// Human: Every call carries a video section both ways from the start, a voice call too, with no
/// camera on it. Turning video on or off mid-call puts the camera's track on that section's
/// sender or takes it off: no new offer, so the call never drops or stalls for it
/// (docs/calls.md, "Switching between voice and video").
@MainActor
final class CallMediaEngine: NSObject {
    enum EngineError: LocalizedError {
        case notStarted
        case sdp(String)

        var errorDescription: String? {
            switch self {
            case .notStarted: "The call's media is not ready."
            case let .sdp(message): message
            }
        }
    }

    enum Connection: Equatable {
        case new
        case connecting
        case connected
        case disconnected
        case failed
        case closed
    }

    var onLocalCandidate: ((IceCandidatePayload) -> Void)?
    var onConnection: ((Connection) -> Void)?
    var onRemoteVideo: ((RTCVideoTrack?) -> Void)?
    /// The first frame of the other side's video since `awaitRemoteFrame()`.
    var onRemoteFrame: (() -> Void)?
    /// The first frame of our camera since it was switched on.
    var onLocalFrame: (() -> Void)?
    /// The system paused our camera (true) or let it go on (false).
    var onCameraPaused: ((Bool) -> Void)?

    private(set) var localVideoTrack: RTCVideoTrack?
    private(set) var remoteVideoTrack: RTCVideoTrack?

    private var peerConnection: RTCPeerConnection?
    private var iceServers: [RTCIceServer] = []
    private var hasTurn = false
    private var triedRelay = false
    private var audioTrack: RTCAudioTrack?
    /// Kept from `add`: `connection.senders` hops to the signaling thread on every read, and
    /// the speaking indicator asks for this sender many times a second.
    private var audioSender: RTCRtpSender?
    /// Our video section: the caller's from `start`, the callee's from the offer.
    private var videoTransceiver: RTCRtpTransceiver?
    private var cameraOn = false
    private var camera: CallCamera?
    #if DEBUG && targetEnvironment(simulator)
    private var testPattern: TestPatternCapturer?
    #endif
    private let remoteFrames = FrameWatch()
    private let localFrames = FrameWatch()

    private static let factory = RTCPeerConnectionFactory(
        encoderFactory: RTCDefaultVideoEncoderFactory(),
        decoderFactory: RTCDefaultVideoDecoderFactory()
    )

    var usesFrontCamera: Bool { camera?.usesFrontCamera ?? true }
    var canSwitchCamera: Bool { camera != nil && cameraOn }
    /// Our camera is on and on the video section.
    var isCameraOn: Bool { cameraOn }

    /// True when the caller can set a new offer (the previous one has its answer).
    var canOffer: Bool {
        peerConnection?.signalingState == .stable
    }

    /// Our video can go out in this call: its video section goes both ways (every current app
    /// offers one; an older app's voice call brought none, and then video stays off).
    var canSendVideo: Bool {
        guard let video = videoTransceiver, !video.isStopped else { return false }
        var current = RTCRtpTransceiverDirection.inactive
        let direction = video.currentDirection(&current) ? current : video.direction
        return direction == .sendRecv || direction == .sendOnly
    }

    /// - Parameters:
    ///   - video: Start with the camera on (a video call, and the camera may be used).
    ///   - offering: The caller. Its offer brings the video section; the callee takes that one.
    func start(iceServers: [IceServerDTO], video: Bool, offering: Bool) {
        close()
        peerLink = .new
        iceLink = .new

        let built: [RTCIceServer] = iceServers.compactMap { server in
            let urls = server.urls.filter { !$0.isEmpty }
            guard !urls.isEmpty else { return nil }
            return RTCIceServer(urlStrings: urls, username: server.username, credential: server.credential)
        }
        self.iceServers = built
        hasTurn = built.contains { server in
            server.urlStrings.contains { url in
                let lower = url.lowercased()
                return lower.hasPrefix("turn:") || lower.hasPrefix("turns:")
            }
        }

        let peerConstraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let connection = Self.factory.peerConnection(
            with: makeConfig(),
            constraints: peerConstraints,
            delegate: self
        ) else { return }
        peerConnection = connection

        let audioConstraints = RTCMediaConstraints(
            mandatoryConstraints: nil,
            optionalConstraints: [
                "googEchoCancellation": kRTCMediaConstraintsValueTrue,
                "googNoiseSuppression": kRTCMediaConstraintsValueTrue,
                "googAutoGainControl": kRTCMediaConstraintsValueTrue,
                "googHighpassFilter": kRTCMediaConstraintsValueTrue,
            ]
        )
        let audioSource = Self.factory.audioSource(with: audioConstraints)
        let audio = Self.factory.audioTrack(with: audioSource, trackId: "shroud-audio")
        audioSender = connection.add(audio, streamIds: ["shroud"])
        audioTrack = audio

        remoteFrames.setHandler { [weak self] in self?.onRemoteFrame?() }
        localFrames.setHandler { [weak self] in self?.onLocalFrame?() }
        remoteFrames.arm()

        if video, let track = makeVideoTrack() {
            // Added as a track, so a callee's is matched with the offer's video section.
            connection.add(track, streamIds: ["shroud"])
            videoTransceiver = connection.transceivers.first { $0.mediaType == .video }
            startCapture(track)
        } else if offering {
            // No camera yet: the section still goes both ways, for a camera switched on later.
            let parameters = RTCRtpTransceiverInit()
            parameters.direction = .sendRecv
            parameters.streamIds = ["shroud"]
            videoTransceiver = connection.addTransceiver(of: .video, init: parameters)
        }
        tuneSenders()
    }

    /// Video on mid-call: the camera goes on our video section (made now, or the one from
    /// before). False when there is no section to send on, or no camera.
    func startCamera() -> Bool {
        guard peerConnection != nil, let video = videoTransceiver, canSendVideo else { return false }
        guard let track = localVideoTrack ?? makeVideoTrack() else { return false }
        video.sender.track = track
        startCapture(track)
        tuneSenders()
        return true
    }

    /// Video off: nothing more goes out, and the camera stops, its light with it.
    func stopCamera() {
        guard cameraOn else { return }
        cameraOn = false
        videoTransceiver?.sender.track = nil
        localVideoTrack?.isEnabled = false
        localFrames.disarm()
        camera?.stop()
        #if DEBUG && targetEnvironment(simulator)
        testPattern?.stop()
        #endif
    }

    /// The other side switched its camera on: `onRemoteFrame` fires with its first frame.
    func awaitRemoteFrame() {
        remoteFrames.arm()
    }

    /// A camera track and what feeds it: the device camera, or the simulator's test pattern.
    private func makeVideoTrack() -> RTCVideoTrack? {
        guard CallCamera.isAvailable || Self.simulatorPattern else { return nil }
        let source = Self.factory.videoSource()
        source.adaptOutputFormat(toWidth: 1280, height: 720, fps: 30)
        let track = Self.factory.videoTrack(with: source, trackId: "shroud-video")
        if CallCamera.isAvailable {
            let camera = CallCamera(source: source)
            camera.onPaused = { [weak self] paused in self?.onCameraPaused?(paused) }
            self.camera = camera
        } else {
            #if DEBUG && targetEnvironment(simulator)
            testPattern = TestPatternCapturer(delegate: source)
            #endif
        }
        track.add(localFrames)
        localVideoTrack = track
        return track
    }

    private func startCapture(_ track: RTCVideoTrack) {
        track.isEnabled = true
        cameraOn = true
        localFrames.arm()
        camera?.start()
        #if DEBUG && targetEnvironment(simulator)
        testPattern?.start()
        #endif
    }

    /// Direct paths first. After `failed`, the next gathering uses only the TURN relay.
    ///
    /// The live configuration is kept and only the transport policy changes. A freshly built
    /// configuration has no certificate, and WebRTC then refuses the update.
    func preferRelay() {
        guard !triedRelay, hasTurn, let connection = peerConnection else { return }
        let config = connection.configuration
        guard config.iceTransportPolicy != .relay else {
            triedRelay = true
            return
        }
        config.iceTransportPolicy = .relay
        guard connection.setConfiguration(config) else { return }
        triedRelay = true
    }

    private func makeConfig() -> RTCConfiguration {
        let config = RTCConfiguration()
        config.iceServers = iceServers
        config.sdpSemantics = .unifiedPlan
        config.bundlePolicy = .maxBundle
        config.rtcpMuxPolicy = .require
        config.continualGatheringPolicy = .gatherContinually
        config.iceCandidatePoolSize = 1
        config.iceTransportPolicy = .all
        return config
    }

    private static var simulatorPattern: Bool {
        #if DEBUG && targetEnvironment(simulator)
        true
        #else
        false
        #endif
    }

    /// Caller: a new offer, set as the local description. Its sections are the transceivers',
    /// video included; the old receive-video constraint would turn that one to send-only.
    func makeOffer(iceRestart: Bool) async throws -> String {
        guard let connection = peerConnection else { throw EngineError.notStarted }
        let mandatory = iceRestart ? [kRTCMediaConstraintsIceRestart: kRTCMediaConstraintsValueTrue] : nil
        let constraints = RTCMediaConstraints(mandatoryConstraints: mandatory, optionalConstraints: nil)
        let sdp: String = try await withCheckedThrowingContinuation { cont in
            connection.offer(for: constraints) { description, error in
                if let description {
                    cont.resume(returning: description.sdp)
                } else {
                    cont.resume(throwing: EngineError.sdp(error?.localizedDescription ?? "No offer."))
                }
            }
        }
        let tuned = CallSdp.withVoiceResilience(sdp)
        try await setLocal(RTCSessionDescription(type: .offer, sdp: tuned), on: connection)
        tuneSenders()
        return tuned
    }

    /// Callee: applies the caller's offer and returns the answer, set as the local description.
    func answer(offer: String) async throws -> String {
        guard let connection = peerConnection else { throw EngineError.notStarted }
        try await setRemote(RTCSessionDescription(type: .offer, sdp: offer), on: connection)
        adoptOfferedVideo(on: connection)
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        let sdp: String = try await withCheckedThrowingContinuation { cont in
            connection.answer(for: constraints) { description, error in
                if let description {
                    cont.resume(returning: description.sdp)
                } else {
                    cont.resume(throwing: EngineError.sdp(error?.localizedDescription ?? "No answer."))
                }
            }
        }
        let tuned = CallSdp.withVoiceResilience(sdp)
        try await setLocal(RTCSessionDescription(type: .answer, sdp: tuned), on: connection)
        tuneSenders()
        refreshRemoteVideo()
        return tuned
    }

    /// Callee: the offer's video section becomes ours both ways. With no camera on it WebRTC
    /// would answer "receive only", and a camera switched on later would need a new offer.
    /// An older caller's voice call brings none: video stays off in that call.
    private func adoptOfferedVideo(on connection: RTCPeerConnection) {
        guard videoTransceiver == nil else { return }
        guard let video = connection.transceivers.first(where: { $0.mediaType == .video && !$0.isStopped }) else {
            return
        }
        var error: NSError?
        switch video.direction {
        case .recvOnly: video.setDirection(.sendRecv, error: &error)
        case .inactive: video.setDirection(.sendOnly, error: &error)
        default: break
        }
        videoTransceiver = video
    }

    /// Caller: applies the callee's answer. False when no offer is waiting for one.
    func applyAnswer(_ sdp: String) async throws -> Bool {
        guard let connection = peerConnection else { throw EngineError.notStarted }
        guard connection.signalingState == .haveLocalOffer else { return false }
        try await setRemote(RTCSessionDescription(type: .answer, sdp: sdp), on: connection)
        tuneSenders()
        refreshRemoteVideo()
        return true
    }

    var hasRemoteDescription: Bool {
        peerConnection?.remoteDescription != nil
    }

    func addRemoteCandidates(_ candidates: [IceCandidatePayload]) {
        guard let connection = peerConnection else { return }
        for candidate in candidates {
            let ice = RTCIceCandidate(
                sdp: candidate.candidate,
                sdpMLineIndex: candidate.sdpMLineIndex,
                sdpMid: candidate.sdpMid
            )
            connection.add(ice) { _ in }
        }
    }

    func setMicrophoneEnabled(_ enabled: Bool) {
        audioTrack?.isEnabled = enabled
    }

    /// The microphone's level right now, linear 0…1; nil before the call has media.
    ///
    /// Human: The stock WebRTC build has no audio tap on a track, so the level comes from the
    /// audio sender's `media-source` stats. Asking for one sender keeps the report small; the
    /// stats collector answers from the signaling thread, and only the number crosses back.
    func localAudioLevel() async -> Float? {
        guard let connection = peerConnection, let sender = audioSender else { return nil }
        let gate = LevelGate()
        return await withTaskCancellationHandler {
            await withCheckedContinuation { (cont: CheckedContinuation<Float?, Never>) in
                gate.arm(cont)
                connection.statistics(for: sender) { report in
                    gate.resume(Self.audioLevel(in: report))
                }
            }
        } onCancel: {
            // The call screen went away while stats were still queued. Resume once so the
            // poll task can finish; a late report is ignored.
            gate.resume(nil)
        }
    }

    /// The SHA-256 fingerprint of the certificate the handshake actually used, once stats have it.
    func remoteCertificateFingerprint() async -> String? {
        guard let connection = peerConnection else { return nil }
        let report: RTCStatisticsReport = await withCheckedContinuation { continuation in
            connection.statistics { continuation.resume(returning: $0) }
        }
        return Self.remoteFingerprint(in: report)
    }

    /// `transport.remoteCertificateId` names the peer's certificate stat.
    nonisolated private static func remoteFingerprint(in report: RTCStatisticsReport) -> String? {
        let stats = report.statistics
        guard let transport = stats.values.first(where: { $0.type == "transport" }),
              let remoteId = transport.values["remoteCertificateId"] as? String,
              let cert = stats[remoteId]
        else { return nil }
        if let algorithm = cert.values["fingerprintAlgorithm"] as? String, algorithm.lowercased() != "sha-256" {
            return nil
        }
        return cert.values["fingerprint"] as? String
    }

    /// `media-source` is the mic. `audioLevel` arrives as a number, sometimes as a string.
    nonisolated private static func audioLevel(in report: RTCStatisticsReport) -> Float? {
        let stats = report.statistics.values
        let source = stats.first { $0.type == "media-source" } ?? stats.first { $0.type == "track" }
        guard let raw = source?.values["audioLevel"] else { return nil }
        if let number = raw as? NSNumber { return number.floatValue }
        if let text = raw as? String { return Float(text) }
        return nil
    }

    func switchCamera() {
        guard cameraOn else { return }
        camera?.switchCamera()
    }

    func close() {
        camera?.close()
        camera = nil
        #if DEBUG && targetEnvironment(simulator)
        testPattern?.stop()
        testPattern = nil
        #endif
        cameraOn = false
        videoTransceiver = nil
        remoteFrames.disarm()
        localFrames.disarm()
        localVideoTrack?.remove(localFrames)
        remoteVideoTrack?.remove(remoteFrames)
        // Drop the connection before closing it. WebRTC reports "closed" and late candidates
        // after `close()` returns; those must not land on the next call.
        let connection = peerConnection
        peerConnection = nil
        connection?.close()
        iceServers = []
        hasTurn = false
        triedRelay = false
        peerLink = .closed
        iceLink = .closed
        audioTrack = nil
        audioSender = nil
        localVideoTrack = nil
        if remoteVideoTrack != nil {
            remoteVideoTrack = nil
            onRemoteVideo?(nil)
        }
    }

    private func setLocal(_ description: RTCSessionDescription, on connection: RTCPeerConnection) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            connection.setLocalDescription(description) { error in
                if let error {
                    cont.resume(throwing: EngineError.sdp(error.localizedDescription))
                } else {
                    cont.resume()
                }
            }
        }
    }

    private func setRemote(_ description: RTCSessionDescription, on connection: RTCPeerConnection) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            connection.setRemoteDescription(description) { error in
                if let error {
                    cont.resume(throwing: EngineError.sdp(error.localizedDescription))
                } else {
                    cont.resume()
                }
            }
        }
    }

    /// Speech near 32 kbps; video near 1.2 Mbps at 30 fps, shedding rate and detail together.
    private func tuneSenders() {
        guard let connection = peerConnection else { return }
        for sender in connection.senders {
            guard let track = sender.track else { continue }
            let parameters = sender.parameters
            guard let encoding = parameters.encodings.first else { continue }
            if track.kind == kRTCMediaStreamTrackKindAudio {
                encoding.maxBitrateBps = NSNumber(value: 32_000)
                encoding.networkPriority = .high
                encoding.bitratePriority = 4
            } else if track.kind == kRTCMediaStreamTrackKindVideo {
                encoding.maxBitrateBps = NSNumber(value: 1_200_000)
                encoding.maxFramerate = NSNumber(value: 30)
                // Below speech, so a tight link fills the microphone before the camera.
                encoding.networkPriority = .low
                encoding.bitratePriority = 1
                parameters.degradationPreference = NSNumber(value: RTCDegradationPreference.balanced.rawValue)
            } else {
                continue
            }
            sender.parameters = parameters
        }
    }

    /// The remote video track, once a remote description created its receiver.
    ///
    /// Agent: compared by id, not identity: `receiver.track` may wrap the same native track in a
    /// new object on each read, and an ICE restart's answer must keep the view and the frame
    /// watch where they are.
    private func refreshRemoteVideo() {
        let track = peerConnection?.transceivers
            .first { $0.mediaType == .video }?
            .receiver.track as? RTCVideoTrack
        guard track?.trackId != remoteVideoTrack?.trackId else { return }
        remoteVideoTrack?.remove(remoteFrames)
        remoteVideoTrack = track
        track?.add(remoteFrames)
        onRemoteVideo?(track)
    }

    private var peerLink: Connection = .new
    private var iceLink: Connection = .new

    fileprivate func notePeer(_ state: RTCPeerConnectionState) {
        peerLink = switch state {
        case .new: .new
        case .connecting: .connecting
        case .connected: .connected
        case .disconnected: .disconnected
        case .failed: .failed
        case .closed: .closed
        @unknown default: .connecting
        }
        publishLink()
    }

    fileprivate func noteIce(_ state: RTCIceConnectionState) {
        iceLink = switch state {
        case .new, .count: .new
        case .checking: .connecting
        case .connected, .completed: .connected
        case .disconnected: .disconnected
        case .failed: .failed
        case .closed: .closed
        @unknown default: .connecting
        }
        publishLink()
    }

    /// The peer connection's state, and ICE when that has not settled yet.
    private func publishLink() {
        let state: Connection = switch peerLink {
        case .connected, .disconnected, .failed, .closed:
            peerLink
        default:
            switch iceLink {
            case .connected, .disconnected, .failed: iceLink
            default: peerLink == .connecting ? .connecting : iceLink
            }
        }
        onConnection?(state)
    }
}

extension CallMediaEngine: RTCPeerConnectionDelegate {
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}

    nonisolated func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        Task { @MainActor in
            guard self.peerConnection === peerConnection else { return }
            self.noteIce(newState)
        }
    }

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        let payload = IceCandidatePayload(
            candidate: candidate.sdp,
            sdpMid: candidate.sdpMid,
            sdpMLineIndex: candidate.sdpMLineIndex
        )
        Task { @MainActor in
            guard self.peerConnection === peerConnection else { return }
            self.onLocalCandidate?(payload)
        }
    }

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        Task { @MainActor in
            guard self.peerConnection === peerConnection else { return }
            self.notePeer(newState)
        }
    }

    nonisolated func peerConnection(
        _ peerConnection: RTCPeerConnection,
        didAdd rtpReceiver: RTCRtpReceiver,
        streams mediaStreams: [RTCMediaStream]
    ) {
        Task { @MainActor in
            guard self.peerConnection === peerConnection else { return }
            self.refreshRemoteVideo()
        }
    }
}

/// Reports the first frame of a video track after `arm()`, on the main actor.
///
/// Human: A camera switched on shows up only once it has a picture, never as a black box or as
/// the last frame from before it went off.
/// Agent: WebRTC calls `renderFrame` on its own thread for every frame, so each one costs a lock
/// and a flag; only the first after `arm()` hops to the main actor.
nonisolated private final class FrameWatch: NSObject, RTCVideoRenderer, @unchecked Sendable {
    private let lock = NSLock()
    private var armed = false
    private var handler: (@MainActor @Sendable () -> Void)?

    func setHandler(_ handler: @escaping @MainActor @Sendable () -> Void) {
        lock.withLock { self.handler = handler }
    }

    func arm() {
        lock.withLock { armed = true }
    }

    func disarm() {
        lock.withLock { armed = false }
    }

    func setSize(_ size: CGSize) {}

    func renderFrame(_ frame: RTCVideoFrame?) {
        guard frame != nil else { return }
        let fire: (@MainActor @Sendable () -> Void)? = lock.withLock {
            guard armed else { return nil }
            armed = false
            return handler
        }
        guard let fire else { return }
        Task { @MainActor in fire() }
    }
}

/// Resumes one level read, from the stats callback or from cancellation, and never both.
private final class LevelGate: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Float?, Never>?
    private var value: Float?
    private var finished = false

    func arm(_ continuation: CheckedContinuation<Float?, Never>) {
        lock.lock()
        if finished {
            let value = self.value
            lock.unlock()
            continuation.resume(returning: value)
            return
        }
        self.continuation = continuation
        lock.unlock()
    }

    func resume(_ value: Float?) {
        lock.lock()
        if finished {
            lock.unlock()
            return
        }
        finished = true
        self.value = value
        let continuation = self.continuation
        self.continuation = nil
        lock.unlock()
        continuation?.resume(returning: value)
    }
}
