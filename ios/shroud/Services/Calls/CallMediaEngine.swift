import Foundation
import WebRTC

/// The WebRTC side of one call: peer connection, local audio/video tracks, remote video.
/// The controller handles signaling and calls in here with SDP and candidates.
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

    private(set) var localVideoTrack: RTCVideoTrack?
    private(set) var remoteVideoTrack: RTCVideoTrack?

    private var peerConnection: RTCPeerConnection?
    private var audioTrack: RTCAudioTrack?
    private var camera: CallCamera?
    #if DEBUG && targetEnvironment(simulator)
    private var testPattern: TestPatternCapturer?
    #endif

    private static let factory = RTCPeerConnectionFactory(
        encoderFactory: RTCDefaultVideoEncoderFactory(),
        decoderFactory: RTCDefaultVideoDecoderFactory()
    )

    var usesFrontCamera: Bool { camera?.usesFrontCamera ?? true }
    var canSwitchCamera: Bool { camera != nil }

    /// True when the caller can set a new offer (the previous one has its answer).
    var canOffer: Bool {
        peerConnection?.signalingState == .stable
    }

    func start(iceServers: [IceServerDTO], video: Bool) {
        close()
        peerLink = .new
        iceLink = .new

        let config = RTCConfiguration()
        config.iceServers = iceServers.compactMap { server in
            let urls = server.urls.filter { !$0.isEmpty }
            guard !urls.isEmpty else { return nil }
            return RTCIceServer(urlStrings: urls, username: server.username, credential: server.credential)
        }
        config.sdpSemantics = .unifiedPlan
        config.bundlePolicy = .maxBundle
        config.rtcpMuxPolicy = .require
        config.continualGatheringPolicy = .gatherContinually

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let connection = Self.factory.peerConnection(
            with: config,
            constraints: constraints,
            delegate: self
        ) else { return }
        peerConnection = connection

        let audioSource = Self.factory.audioSource(with: constraints)
        let audio = Self.factory.audioTrack(with: audioSource, trackId: "shroud-audio")
        connection.add(audio, streamIds: ["shroud"])
        audioTrack = audio

        guard video else { return }
        if CallCamera.isAvailable || Self.simulatorPattern {
            let source = Self.factory.videoSource()
            source.adaptOutputFormat(toWidth: 1280, height: 720, fps: 30)
            let track = Self.factory.videoTrack(with: source, trackId: "shroud-video")
            connection.add(track, streamIds: ["shroud"])
            localVideoTrack = track
            if CallCamera.isAvailable {
                let camera = CallCamera(source: source)
                camera.start()
                self.camera = camera
            } else {
                #if DEBUG && targetEnvironment(simulator)
                let pattern = TestPatternCapturer(delegate: source)
                pattern.start()
                testPattern = pattern
                #endif
            }
        } else {
            // No camera: still receive the other side's video.
            let parameters = RTCRtpTransceiverInit()
            parameters.direction = .recvOnly
            connection.addTransceiver(of: .video, init: parameters)
        }
    }

    private static var simulatorPattern: Bool {
        #if DEBUG && targetEnvironment(simulator)
        true
        #else
        false
        #endif
    }

    /// Caller: a new offer, set as the local description.
    func makeOffer(iceRestart: Bool, receiveVideo: Bool) async throws -> String {
        guard let connection = peerConnection else { throw EngineError.notStarted }
        var mandatory = [
            kRTCMediaConstraintsOfferToReceiveAudio: kRTCMediaConstraintsValueTrue,
            kRTCMediaConstraintsOfferToReceiveVideo: receiveVideo
                ? kRTCMediaConstraintsValueTrue : kRTCMediaConstraintsValueFalse,
        ]
        if iceRestart {
            mandatory[kRTCMediaConstraintsIceRestart] = kRTCMediaConstraintsValueTrue
        }
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
        try await setLocal(RTCSessionDescription(type: .offer, sdp: sdp), on: connection)
        return sdp
    }

    /// Callee: applies the caller's offer and returns the answer, set as the local description.
    func answer(offer: String) async throws -> String {
        guard let connection = peerConnection else { throw EngineError.notStarted }
        try await setRemote(RTCSessionDescription(type: .offer, sdp: offer), on: connection)
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
        try await setLocal(RTCSessionDescription(type: .answer, sdp: sdp), on: connection)
        refreshRemoteVideo()
        return sdp
    }

    /// Caller: applies the callee's answer. False when no offer is waiting for one.
    func applyAnswer(_ sdp: String) async throws -> Bool {
        guard let connection = peerConnection else { throw EngineError.notStarted }
        guard connection.signalingState == .haveLocalOffer else { return false }
        try await setRemote(RTCSessionDescription(type: .answer, sdp: sdp), on: connection)
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

    func setCameraEnabled(_ enabled: Bool) {
        localVideoTrack?.isEnabled = enabled
        if enabled {
            camera?.start()
        } else {
            camera?.stop()
        }
    }

    func switchCamera() {
        camera?.switchCamera()
    }

    func close() {
        camera?.stop()
        camera = nil
        #if DEBUG && targetEnvironment(simulator)
        testPattern?.stop()
        testPattern = nil
        #endif
        peerConnection?.close()
        peerConnection = nil
        peerLink = .closed
        iceLink = .closed
        audioTrack = nil
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

    /// The remote video track, once a remote description created its receiver.
    private func refreshRemoteVideo() {
        let track = peerConnection?.transceivers
            .first { $0.mediaType == .video }?
            .receiver.track as? RTCVideoTrack
        guard track !== remoteVideoTrack else { return }
        remoteVideoTrack = track
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
            self.onLocalCandidate?(payload)
        }
    }

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        Task { @MainActor in
            self.notePeer(newState)
        }
    }

    nonisolated func peerConnection(
        _ peerConnection: RTCPeerConnection,
        didAdd rtpReceiver: RTCRtpReceiver,
        streams mediaStreams: [RTCMediaStream]
    ) {
        Task { @MainActor in
            self.refreshRemoteVideo()
        }
    }
}
