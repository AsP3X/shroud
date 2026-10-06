import Foundation
import WebRTC

/// The WebRTC side of one call: peer connection, local audio/video tracks, remote video.
/// The controller handles signaling and calls in here with SDP and candidates.
///
/// Human: Every call carries a video section both ways from the start, a voice call too, with no
/// camera on it. Turning video on or off mid-call puts the camera's track on that section's
/// sender or takes it off: no new offer, so the call never drops or stalls for it
/// (docs/calls.md, "Switching between voice and video"). A shared screen works the same way on two
/// sections of its own after the camera's, its picture and its sound (docs/calls.md, "Screen
/// sharing"); the sections are told apart by their place among those of their kind.
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
    var onRemoteScreen: ((RTCVideoTrack?) -> Void)?
    /// The first frame of their shared screen since `awaitRemoteScreenFrame()`.
    var onRemoteScreenFrame: (() -> Void)?

    private(set) var localVideoTrack: RTCVideoTrack?
    private(set) var remoteVideoTrack: RTCVideoTrack?
    private(set) var remoteScreenTrack: RTCVideoTrack?
    /// Where our shared screen's frames go in, from any thread: the broadcast's frames, while
    /// the screen is on its section. Nil until the screen is first shared in this call.
    private(set) var screenFeed: ScreenFrameFeed?

    private var peerConnection: RTCPeerConnection?
    private var iceServers: [RTCIceServer] = []
    private var hasTurn = false
    private var triedRelay = false
    private var relayOnly = false
    private var audioTrack: RTCAudioTrack?
    /// Kept from `add`: `connection.senders` hops to the signaling thread on every read, and
    /// the speaking indicator asks for this sender many times a second.
    private var audioSender: RTCRtpSender?
    /// Our video section: the caller's from `start`, the callee's from the offer.
    private var videoTransceiver: RTCRtpTransceiver? {
        didSet { videoSender = videoTransceiver?.sender }
    }
    /// The camera section's sender, kept like `audioSender`: the quality readings ask for its
    /// stats every two seconds.
    private var videoSender: RTCRtpSender?
    /// How sharp our camera goes out: a rung of the ladder, moved by the link
    /// (`CallVideoQuality`). New for each call.
    private var cameraQuality = CameraQuality()
    /// Reads the camera's stats every two seconds while the link is connected.
    private var qualityWatch: Task<Void, Never>?
    /// A stats reading is on its way; the next one waits for it.
    private var samplingQuality = false
    /// Our screen's picture and sound sections, found the same way. Nil in a call with an older
    /// app. The sound's stays empty: this app shares the picture only (for now).
    private var screenTransceiver: RTCRtpTransceiver?
    private var screenSoundTransceiver: RTCRtpTransceiver?
    private var screenTrack: RTCVideoTrack?
    private var screenOn = false
    private var cameraOn = false
    /// The system paused our camera (Shroud left the screen, another app took the camera): no
    /// frames go out, so its stats say nothing about the link. Cleared when the camera goes on,
    /// off, or the call ends.
    private var cameraPaused = false
    private var camera: CallCamera?
    #if DEBUG && targetEnvironment(simulator)
    private var testPattern: TestPatternCapturer?
    #endif
    private let remoteFrames = FrameWatch()
    private let localFrames = FrameWatch()
    private let remoteScreenFrames = FrameWatch()

    private static let factory = RTCPeerConnectionFactory(
        encoderFactory: RTCDefaultVideoEncoderFactory(),
        decoderFactory: RTCDefaultVideoDecoderFactory()
    )

    var usesFrontCamera: Bool { camera?.usesFrontCamera ?? true }
    var canSwitchCamera: Bool { camera != nil && cameraOn }
    /// Our camera is on and on the video section.
    var isCameraOn: Bool { cameraOn }
    /// Our screen is on its section.
    var isScreenOn: Bool { screenOn }

    /// The resolution and frame rate our screen goes out at; the encoder takes a change at once.
    var screenQuality = ScreenShareQuality.standard {
        didSet {
            guard screenQuality != oldValue else { return }
            tuneSenders()
        }
    }

    /// True when the caller can set a new offer (the previous one has its answer).
    var canOffer: Bool {
        peerConnection?.signalingState == .stable
    }

    /// Our video can go out in this call: its video section goes both ways (every current app
    /// offers one; an older app's voice call brought none, and then video stays off).
    var canSendVideo: Bool {
        Self.sends(videoTransceiver)
    }

    /// Our screen can go out in this call: its section goes both ways (an older app's offer has
    /// none). Whether they can show it is theirs to say (`media_state`).
    var canSendScreen: Bool {
        Self.sends(screenTransceiver)
    }

    private static func sends(_ transceiver: RTCRtpTransceiver?) -> Bool {
        guard let transceiver, !transceiver.isStopped else { return false }
        var current = RTCRtpTransceiverDirection.inactive
        let direction = transceiver.currentDirection(&current) ? current : transceiver.direction
        return direction == .sendRecv || direction == .sendOnly
    }

    /// What a section carries: by kind, then by place (docs/calls.md, "Screen sharing").
    enum Section: Equatable {
        case mic
        case camera
        case screen
        case screenSound
    }

    /// Each transceiver with its section, in the offer's order: the first audio section is the
    /// microphone and the first video the camera, the second of each the screen's sound and
    /// picture. Both ends see them in that order, so both agree without naming them.
    /// Agent: one pass over `transceivers`; the wrappers may be new objects on each read, so they
    /// are never compared by identity.
    private static func sections(of connection: RTCPeerConnection) -> [(section: Section, transceiver: RTCRtpTransceiver)] {
        var audio = 0
        var video = 0
        var out: [(section: Section, transceiver: RTCRtpTransceiver)] = []
        for transceiver in connection.transceivers {
            switch transceiver.mediaType {
            case .audio:
                if let section = [Section.mic, .screenSound][safe: audio] { out.append((section, transceiver)) }
                audio += 1
            case .video:
                if let section = [Section.camera, .screen][safe: video] { out.append((section, transceiver)) }
                video += 1
            default:
                break
            }
        }
        return out
    }

    /// Whether `servers` include a TURN relay (a `turn:` or `turns:` URL).
    static func offersRelay(_ servers: [IceServerDTO]) -> Bool {
        servers.contains { server in
            server.urls.contains { url in
                let lower = url.lowercased()
                return lower.hasPrefix("turn:") || lower.hasPrefix("turns:")
            }
        }
    }

    /// - Parameters:
    ///   - video: Start with the camera on (a video call, and the camera may be used).
    ///   - offering: The caller. Its offer brings the video section; the callee takes that one.
    ///   - relayOnly: Gather relay candidates only ("Always relay calls"). The caller checks
    ///     `offersRelay` first; without a relay this connection could never connect.
    func start(iceServers: [IceServerDTO], video: Bool, offering: Bool, relayOnly: Bool = false) {
        close()
        peerLink = .new
        iceLink = .new

        let built: [RTCIceServer] = iceServers.compactMap { server in
            let urls = server.urls.filter { !$0.isEmpty }
            guard !urls.isEmpty else { return nil }
            return RTCIceServer(urlStrings: urls, username: server.username, credential: server.credential)
        }
        self.iceServers = built
        hasTurn = Self.offersRelay(iceServers)
        // Already relayed, so the fallback after a failed link has nothing left to switch to.
        self.relayOnly = relayOnly && hasTurn
        triedRelay = self.relayOnly

        let peerConstraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let connection = Self.factory.peerConnection(
            with: makeConfig(),
            constraints: peerConstraints,
            delegate: self
        ) else { return }
        peerConnection = connection
        // The bandwidth estimate starts at 1 Mbps instead of WebRTC's 300 kbps, so the camera is
        // sharp from the call's first seconds (docs/calls.md, "Camera quality"). Only here: set
        // mid-call it would throw away what the estimator has learned.
        _ = connection.setBweMinBitrateBps(nil, currentBitrateBps: NSNumber(value: 1_000_000), maxBitrateBps: nil)

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
        remoteScreenFrames.setHandler { [weak self] in self?.onRemoteScreenFrame?() }
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
        if offering {
            // Then the screen's picture and sound, both ways and empty until someone shares:
            // after the camera's, so each end tells the sections apart by their place.
            let picture = RTCRtpTransceiverInit()
            picture.direction = .sendRecv
            picture.streamIds = ["shroud-screen"]
            screenTransceiver = connection.addTransceiver(of: .video, init: picture)
            let sound = RTCRtpTransceiverInit()
            sound.direction = .sendRecv
            sound.streamIds = ["shroud-screen"]
            screenSoundTransceiver = connection.addTransceiver(of: .audio, init: sound)
        }
        tuneSenders()
    }

    /// Our screen goes on its section; its frames then come through `screenFeed`. False when
    /// this call has no screen section to send on.
    func startScreen() -> Bool {
        guard peerConnection != nil, let screen = screenTransceiver, canSendScreen else { return false }
        let track = screenTrack ?? makeScreenTrack()
        track.isEnabled = true
        screen.sender.track = track
        screenOn = true
        tuneSenders()
        return true
    }

    /// Stop sharing: nothing more goes out on the screen's section.
    func stopScreen() {
        guard screenOn else { return }
        screenOn = false
        screenTransceiver?.sender.track = nil
        screenTrack?.isEnabled = false
        tuneSenders()
    }

    /// Their screen came on: `onRemoteScreenFrame` fires with its first frame.
    func awaitRemoteScreenFrame() {
        remoteScreenFrames.arm()
    }

    /// A screencast source: the encoder keeps text sharp rather than smoothing motion. Frames
    /// arrive already scaled by the broadcast, so the source does not adapt them (its adapter
    /// would crop a tall phone screen to 9:16).
    private func makeScreenTrack() -> RTCVideoTrack {
        let source = Self.factory.videoSource(forScreenCast: true)
        let track = Self.factory.videoTrack(with: source, trackId: Self.screenTrackID)
        screenFeed = ScreenFrameFeed(delegate: source)
        screenTrack = track
        return track
    }

    private static let screenTrackID = "shroud-screen"

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
        cameraPaused = false
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
    /// Up to 1080p30 goes in; the encoder sends a rung of the ladder below that
    /// (`CallVideoQuality`), and our own picture stays full size.
    private func makeVideoTrack() -> RTCVideoTrack? {
        guard CallCamera.isAvailable || Self.simulatorPattern else { return nil }
        let source = Self.factory.videoSource()
        source.adaptOutputFormat(toWidth: 1920, height: 1080, fps: 30)
        let track = Self.factory.videoTrack(with: source, trackId: "shroud-video")
        if CallCamera.isAvailable {
            let camera = CallCamera(source: source)
            camera.onPaused = { [weak self] paused in
                guard let self else { return }
                cameraPaused = paused
                onCameraPaused?(paused)
            }
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
        cameraPaused = false
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
        config.iceTransportPolicy = relayOnly ? .relay : .all
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
        let tuned = Self.withCodecs(sdp)
        try await setLocal(RTCSessionDescription(type: .offer, sdp: tuned), on: connection)
        tuneSenders()
        return tuned
    }

    /// Callee: applies the caller's offer and returns the answer, set as the local description.
    func answer(offer: String) async throws -> String {
        guard let connection = peerConnection else { throw EngineError.notStarted }
        try await setRemote(RTCSessionDescription(type: .offer, sdp: offer), on: connection)
        adoptOfferedSections(on: connection)
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
        let tuned = Self.withCodecs(sdp)
        try await setLocal(RTCSessionDescription(type: .answer, sdp: tuned), on: connection)
        tuneSenders()
        refreshRemoteVideo()
        refreshRemoteScreen()
        return tuned
    }

    /// Our own description, offer or answer: Opus as each audio section needs it (speech on the
    /// microphone's, music on the screen's), H.264 first for the camera's picture and VP8 first
    /// for the screen's. The web does the same (`withVoice`).
    private static func withCodecs(_ sdp: String) -> String {
        CallSdp.withCameraVideo(CallSdp.withScreenVideo(CallSdp.withScreenSound(CallSdp.withVoiceResilience(sdp))))
    }

    /// Callee: the offer's camera and screen sections become ours both ways. With no track on
    /// one WebRTC would answer "receive only", and a camera or a screen switched on later would
    /// need a new offer. An older caller's offer lacks the screen's (and, for its voice calls,
    /// the camera's): those stay off in that call.
    private func adoptOfferedSections(on connection: RTCPeerConnection) {
        for (section, transceiver) in Self.sections(of: connection) where !transceiver.isStopped {
            switch section {
            case .camera where videoTransceiver == nil:
                videoTransceiver = Self.bothWays(transceiver)
            case .screen where screenTransceiver == nil:
                screenTransceiver = Self.bothWays(transceiver)
            case .screenSound where screenSoundTransceiver == nil:
                screenSoundTransceiver = Self.bothWays(transceiver)
            default:
                break
            }
        }
    }

    private static func bothWays(_ transceiver: RTCRtpTransceiver) -> RTCRtpTransceiver {
        var error: NSError?
        switch transceiver.direction {
        case .recvOnly: transceiver.setDirection(.sendRecv, error: &error)
        case .inactive: transceiver.setDirection(.sendOnly, error: &error)
        default: break
        }
        return transceiver
    }

    /// Caller: applies the callee's answer. False when no offer is waiting for one.
    func applyAnswer(_ sdp: String) async throws -> Bool {
        guard let connection = peerConnection else { throw EngineError.notStarted }
        guard connection.signalingState == .haveLocalOffer else { return false }
        try await setRemote(RTCSessionDescription(type: .answer, sdp: sdp), on: connection)
        tuneSenders()
        refreshRemoteVideo()
        refreshRemoteScreen()
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
        let gate = StatsGate<Float?>()
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
        // The other camera may open at another size: the ladder's ceiling and the shrink follow.
        tuneSenders()
    }

    func close() {
        stopWatchingQuality()
        cameraQuality = CameraQuality()
        samplingQuality = false
        camera?.close()
        camera = nil
        #if DEBUG && targetEnvironment(simulator)
        testPattern?.stop()
        testPattern = nil
        #endif
        cameraOn = false
        cameraPaused = false
        videoTransceiver = nil
        screenOn = false
        screenTransceiver = nil
        screenSoundTransceiver = nil
        screenTrack = nil
        screenFeed = nil
        remoteFrames.disarm()
        localFrames.disarm()
        remoteScreenFrames.disarm()
        localVideoTrack?.remove(localFrames)
        remoteVideoTrack?.remove(remoteFrames)
        remoteScreenTrack?.remove(remoteScreenFrames)
        // Drop the connection before closing it. WebRTC reports "closed" and late candidates
        // after `close()` returns; those must not land on the next call.
        let connection = peerConnection
        peerConnection = nil
        connection?.close()
        iceServers = []
        hasTurn = false
        triedRelay = false
        relayOnly = false
        peerLink = .closed
        iceLink = .closed
        link = .closed
        audioTrack = nil
        audioSender = nil
        localVideoTrack = nil
        if remoteVideoTrack != nil {
            remoteVideoTrack = nil
            onRemoteVideo?(nil)
        }
        if remoteScreenTrack != nil {
            remoteScreenTrack = nil
            onRemoteScreen?(nil)
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

    /// Speech near 32 kbps, first in line. The camera at its rung of the ladder
    /// (`CallVideoQuality`), shedding rate and detail together within it; while our screen is
    /// shared, a thumbnail's worth and never more than its rung (they show it as a tile). The
    /// screen at the chosen frame rate and a bitrate to match (`ScreenShareQuality`), ahead of the
    /// camera and behind speech: up to 30 fps it keeps its sharpness and gives up frames when the
    /// link is tight, at 60 it gives up some of each.
    private func tuneSenders() {
        guard let connection = peerConnection else { return }
        let capture = captureSize
        cameraQuality.setCapture(capture.map { max($0.width, $0.height) })
        let cameraShape = CallVideoQuality.cameraEncoding(
            screenOn ? CallVideoQuality.tileOf(cameraQuality.rung) : cameraQuality.rung,
            width: capture?.width,
            height: capture?.height
        )
        for sender in connection.senders {
            guard let track = sender.track else { continue }
            let parameters = sender.parameters
            guard let encoding = parameters.encodings.first else { continue }
            if track.trackId == Self.screenTrackID {
                encoding.maxBitrateBps = NSNumber(value: screenQuality.bitrate)
                encoding.maxFramerate = NSNumber(value: screenQuality.frameRate.rawValue)
                encoding.networkPriority = .medium
                encoding.bitratePriority = 2
                let degradation: RTCDegradationPreference = screenQuality.keepsResolution ? .maintainResolution : .balanced
                parameters.degradationPreference = NSNumber(value: degradation.rawValue)
            } else if track.kind == kRTCMediaStreamTrackKindAudio {
                encoding.maxBitrateBps = NSNumber(value: 32_000)
                encoding.networkPriority = .high
                encoding.bitratePriority = 4
            } else if track.kind == kRTCMediaStreamTrackKindVideo {
                encoding.maxBitrateBps = NSNumber(value: cameraShape.maxBitrate)
                encoding.maxFramerate = NSNumber(value: cameraShape.maxFramerate)
                encoding.scaleResolutionDownBy = NSNumber(value: cameraShape.scaleResolutionDownBy)
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

    /// What our camera captures at: the device camera's chosen format, or the simulator's test
    /// pattern. Nil while unknown (the encoder then sends it as it is).
    private var captureSize: (width: Int, height: Int)? {
        if let size = camera?.captureSize {
            return (Int(size.width), Int(size.height))
        }
        #if DEBUG && targetEnvironment(simulator)
        if testPattern != nil {
            return (TestPatternCapturer.width, TestPatternCapturer.height)
        }
        #endif
        return nil
    }

    /// Every two seconds while the link is connected, the camera's stats move it along the
    /// ladder, and the encoder takes a new rung at once (docs/calls.md, "Camera quality").
    private func watchQuality() {
        guard qualityWatch == nil else { return }
        qualityWatch = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: CallVideoQuality.sampleInterval)
                guard !Task.isCancelled, let self else { return }
                await self.sampleQuality()
            }
        }
    }

    /// The link dropped, or the call is over: no readings until it is connected again, and those
    /// start counting afresh.
    /// Agent: pauses only a running watch, so the states before the first connect leave the
    /// ladder's opening settle as it is.
    private func stopWatchingQuality() {
        guard let watch = qualityWatch else { return }
        watch.cancel()
        qualityWatch = nil
        cameraQuality.pause()
    }

    /// One reading. Not while the camera is off, paused by the system or goes out as a tile:
    /// those only restart the count.
    private func sampleQuality() async {
        guard let connection = peerConnection, let sender = videoSender, !samplingQuality else { return }
        guard cameraOn, !cameraPaused, !screenOn, link == .connected else {
            cameraQuality.pause()
            return
        }
        samplingQuality = true
        let sample = await cameraSample(of: sender, on: connection)
        samplingQuality = false
        guard let sample, peerConnection === connection, cameraOn, !cameraPaused, !screenOn else { return }
        if cameraQuality.sample(sample) {
            tuneSenders()
        }
    }

    /// The camera's reading from its sender's stats; nil when the read was cancelled, or while
    /// nothing goes out yet.
    ///
    /// Agent: the same pattern as `localAudioLevel`: the stats collector answers on the signaling
    /// thread, the report is read there, and only the `Sendable` sample crosses back.
    private func cameraSample(of sender: RTCRtpSender, on connection: RTCPeerConnection) async -> CameraSample? {
        let gate = StatsGate<CameraSample?>()
        return await withTaskCancellationHandler {
            await withCheckedContinuation { (cont: CheckedContinuation<CameraSample?, Never>) in
                gate.arm(cont)
                connection.statistics(for: sender) { report in
                    gate.resume(Self.cameraSample(in: report))
                }
            }
        } onCancel: {
            gate.resume(nil)
        }
    }

    /// The report as `CallVideoQuality.readCameraSample` reads it: each stat's values and type by id.
    nonisolated private static func cameraSample(in report: RTCStatisticsReport) -> CameraSample? {
        var stats: [String: [String: Any]] = [:]
        for (id, stat) in report.statistics {
            var values: [String: Any] = stat.values
            values["type"] = stat.type
            stats[id] = values
        }
        return CallVideoQuality.readCameraSample(stats)
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

    /// Their screen's track, on the second video section, once a remote description made it.
    /// Its sound needs nothing here: WebRTC plays every remote audio track.
    private func refreshRemoteScreen() {
        guard let connection = peerConnection else { return }
        let track = Self.sections(of: connection)
            .first { $0.section == .screen }?
            .transceiver.receiver.track as? RTCVideoTrack
        guard track?.trackId != remoteScreenTrack?.trackId else { return }
        remoteScreenTrack?.remove(remoteScreenFrames)
        remoteScreenTrack = track
        track?.add(remoteScreenFrames)
        onRemoteScreen?(track)
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

    /// What `publishLink` last reported.
    private var link: Connection = .new

    /// The peer connection's state, and ICE when that has not settled yet. The camera's quality
    /// is read only while it is connected.
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
        link = state
        if state == .connected {
            watchQuality()
        } else {
            stopWatchingQuality()
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
            self.refreshRemoteScreen()
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

/// Resumes one stats read (the microphone's level, the camera's quality reading), from the stats
/// callback or from cancellation, and never both.
nonisolated private final class StatsGate<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Value, Never>?
    /// Set once, by the first `resume`.
    private var result: Value?

    func arm(_ continuation: CheckedContinuation<Value, Never>) {
        lock.lock()
        if let result {
            lock.unlock()
            continuation.resume(returning: result)
            return
        }
        self.continuation = continuation
        lock.unlock()
    }

    func resume(_ value: Value) {
        lock.lock()
        if result != nil {
            lock.unlock()
            return
        }
        result = value
        let continuation = self.continuation
        self.continuation = nil
        lock.unlock()
        continuation?.resume(returning: value)
    }
}

/// Our shared screen's frames into its video source, from whichever thread they arrive on (the
/// broadcast's socket queue). WebRTC's sources take frames from any thread.
nonisolated final class ScreenFrameFeed: RTCVideoCapturer, @unchecked Sendable {
    func push(_ frame: RTCVideoFrame) {
        delegate?.capturer(self, didCapture: frame)
    }
}
