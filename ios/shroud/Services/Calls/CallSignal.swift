import Foundation

/// One ICE candidate as the signals carry it (the browser's `RTCIceCandidateInit` shape).
nonisolated struct IceCandidatePayload: Codable, Equatable, Sendable {
    let candidate: String
    let sdpMid: String?
    let sdpMLineIndex: Int32

    enum CodingKeys: String, CodingKey {
        case candidate
        case sdpMid
        case sdpMLineIndex
    }

    init(candidate: String, sdpMid: String?, sdpMLineIndex: Int32) {
        self.candidate = candidate
        self.sdpMid = sdpMid
        self.sdpMLineIndex = sdpMLineIndex
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        candidate = try c.decode(String.self, forKey: .candidate)
        sdpMid = try c.decodeIfPresent(String.self, forKey: .sdpMid)
        sdpMLineIndex = try c.decodeIfPresent(Int32.self, forKey: .sdpMLineIndex) ?? 0
    }
}

/// What one sealed signal says (docs/calls.md, "Plaintext").
nonisolated enum CallSignal: Equatable, Sendable {
    /// `ephemeral` is the sender's fresh X25519 public key, on the first offer only.
    case offer(sdp: String, restart: Bool, ephemeral: Data?)
    /// `ephemeral` is the sender's fresh X25519 public key, on the first answer only.
    case answer(sdp: String, ephemeral: Data?)
    case candidates([IceCandidatePayload])
    /// The callee asks the caller for an ICE restart.
    case restartRequest
    /// What the sender sends now: the other side shows a muted mark or the avatar. `screen` is
    /// whether it shares its screen; nil from an app that cannot share or show one.
    case media(mic: Bool, camera: Bool, screen: Bool? = nil)

    enum ParseError: Error, Equatable {
        case malformed
        case typeMismatch
    }

    /// The `signal_type` the server sees (and the sealed additional data binds).
    var signalType: String {
        switch self {
        case .offer: "sdp_offer"
        case .answer: "sdp_answer"
        case .candidates: "ice_candidate"
        case .restartRequest: "renegotiate"
        case .media: "media_state"
        }
    }

    private var tag: String {
        switch self {
        case .offer: "offer"
        case .answer: "answer"
        case .candidates: "ice"
        case .restartRequest: "restart"
        case .media: "media"
        }
    }

    /// The plaintext JSON, numbered `n` (per sending device, from 1).
    func plaintext(n: Int) throws -> Data {
        var object: [String: Any] = ["t": tag, "n": n]
        switch self {
        case let .offer(sdp, restart, ephemeral):
            object["sdp"] = sdp
            object["restart"] = restart
            if let ephemeral { object["ek"] = ephemeral.base64EncodedString() }
        case let .answer(sdp, ephemeral):
            object["sdp"] = sdp
            if let ephemeral { object["ek"] = ephemeral.base64EncodedString() }
        case let .candidates(list):
            object["cs"] = list.map { candidate -> [String: Any] in
                var entry: [String: Any] = [
                    "candidate": candidate.candidate,
                    "sdpMLineIndex": Int(candidate.sdpMLineIndex),
                ]
                if let mid = candidate.sdpMid { entry["sdpMid"] = mid }
                return entry
            }
        case .restartRequest:
            break
        case let .media(mic, camera, screen):
            object["mic"] = mic
            object["camera"] = camera
            if let screen { object["screen"] = screen }
        }
        return try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    }

    /// Reads a plaintext that arrived under `signalType`; the two must agree.
    static func parse(_ data: Data, signalType: String) throws -> (signal: CallSignal, n: Int) {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let tag = object["t"] as? String,
              let n = Self.int(object["n"]), n > 0
        else { throw ParseError.malformed }

        let signal: CallSignal
        switch tag {
        case "offer":
            guard let sdp = object["sdp"] as? String else { throw ParseError.malformed }
            signal = .offer(
                sdp: sdp,
                restart: Self.bool(object["restart"]) ?? false,
                ephemeral: try Self.ephemeral(object["ek"])
            )
        case "answer":
            guard let sdp = object["sdp"] as? String else { throw ParseError.malformed }
            signal = .answer(sdp: sdp, ephemeral: try Self.ephemeral(object["ek"]))
        case "ice":
            guard let list = object["cs"] as? [[String: Any]] else { throw ParseError.malformed }
            let candidates = list.compactMap { entry -> IceCandidatePayload? in
                guard let candidate = entry["candidate"] as? String, !candidate.isEmpty else { return nil }
                return IceCandidatePayload(
                    candidate: candidate,
                    sdpMid: entry["sdpMid"] as? String,
                    sdpMLineIndex: Int32(Self.int(entry["sdpMLineIndex"]) ?? 0)
                )
            }
            signal = .candidates(candidates)
        case "restart":
            signal = .restartRequest
        case "media":
            // Absent from an app that knows no screens; anything but a boolean is malformed.
            let screen = object["screen"]
            if screen != nil, Self.bool(screen) == nil { throw ParseError.malformed }
            signal = .media(
                mic: Self.bool(object["mic"]) ?? true,
                camera: Self.bool(object["camera"]) ?? false,
                screen: Self.bool(screen)
            )
        default:
            throw ParseError.malformed
        }
        guard signal.signalType == signalType else { throw ParseError.typeMismatch }
        return (signal, n)
    }

    /// JSON numbers arrive as `NSNumber`; a direct `as? Int` cast misses some of them.
    private static func int(_ value: Any?) -> Int? {
        switch value {
        case let number as Int: number
        case let number as NSNumber: number.intValue
        default: nil
        }
    }

    /// Absent is an older peer. Present but not 32 bytes rejects the signal.
    private static func ephemeral(_ value: Any?) throws -> Data? {
        guard let value else { return nil }
        guard let text = value as? String,
              let data = Data(base64Encoded: text),
              data.count == 32
        else { throw ParseError.malformed }
        return data
    }

    private static func bool(_ value: Any?) -> Bool? {
        switch value {
        case let flag as Bool: flag
        case let number as NSNumber: number.boolValue
        default: nil
        }
    }
}

/// Drops signals seen before: with Redis the server may deliver one twice.
nonisolated struct CallSignalSequencer: Sendable {
    private var seen: Set<Int> = []

    /// True the first time `n` arrives.
    mutating func accept(_ n: Int) -> Bool {
        seen.insert(n).inserted
    }
}

/// Media states apply newest first. The server hands back the other device's latest one (after
/// a reconnect, on the heartbeat), and that answer can arrive after a newer one came over the
/// socket: the older one must not undo it.
nonisolated struct CallMediaOrder: Sendable {
    private var latest: [String: Int] = [:]

    /// True when a media state numbered `n` is newer than the last one taken from `device`.
    mutating func isNewer(_ n: Int, from device: String) -> Bool {
        let key = device.lowercased()
        guard n > latest[key, default: 0] else { return false }
        latest[key] = n
        return true
    }
}

/// Why a call ended, as this device tells its user.
nonisolated enum CallEndReason: Equatable, Sendable {
    case ended
    case declined
    case noAnswer
    case missed
    case busy
    case answeredElsewhere
    case declinedElsewhere
    case connectionLost
    case couldNotConnect
    case cancelled
    case failed(String)

    var title: String {
        switch self {
        case .ended, .cancelled: "Call ended"
        case .declined: "Declined"
        case .noAnswer: "No answer"
        case .missed: "Missed call"
        case .busy: "Busy"
        case .answeredElsewhere: "Answered on another device"
        case .declinedElsewhere: "Declined on another device"
        case .connectionLost: "Connection lost"
        case .couldNotConnect: "Couldn't connect"
        case let .failed(message): message
        }
    }

    /// What to say when the call screen closes. Nil closes it with nothing said: the caller
    /// hung up their own ring, or this phone only heard that another of its devices declined.
    var announcement: String? {
        switch self {
        case .cancelled, .declinedElsewhere: nil
        default: title
        }
    }

    /// How the server's `status` / `ended_reason` reads on this side of the call.
    static func from(status: String, reason: String?, isOutgoing: Bool) -> CallEndReason? {
        switch (status, reason) {
        case ("ringing", _), ("active", _):
            return nil
        case ("rejected", _), ("missed", "declined"):
            return isOutgoing ? .declined : .declinedElsewhere
        case ("missed", _):
            return isOutgoing ? .noAnswer : .missed
        case ("cancelled", "connection_lost"):
            return isOutgoing ? .connectionLost : .missed
        case ("cancelled", _):
            return isOutgoing ? .cancelled : .missed
        case ("busy", _):
            return .busy
        case ("ended", "connection_lost"):
            return .connectionLost
        default:
            return .ended
        }
    }
}
