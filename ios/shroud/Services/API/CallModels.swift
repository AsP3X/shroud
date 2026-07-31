import Foundation

/// Call modality for 1:1 WebRTC sessions.
enum CallModality: String, Codable, Sendable, CaseIterable {
    case voice
    case video
}

/// Server call status values.
enum CallStatus: String, Codable, Sendable {
    case ringing
    case active
    case ended
    case rejected
    case busy
    case missed
    case cancelled
}

/// `POST /calls` body.
struct CreateCallRequest: Encodable, Equatable, Sendable {
    let peerUserId: UUID
    let modality: String
    let sdpOffer: String?

    enum CodingKeys: String, CodingKey {
        case peerUserId = "peer_user_id"
        case modality
        case sdpOffer = "sdp_offer"
    }

    init(peerUserId: UUID, modality: CallModality, sdpOffer: String? = nil) {
        self.peerUserId = peerUserId
        self.modality = modality.rawValue
        self.sdpOffer = sdpOffer
    }
}

/// `POST /calls/:id/accept` body.
struct AcceptCallRequest: Encodable, Equatable, Sendable {
    let sdpAnswer: String?

    enum CodingKeys: String, CodingKey {
        case sdpAnswer = "sdp_answer"
    }
}

/// `POST /calls/:id/signal` body — opaque WebRTC SDP/ICE relay.
struct CallSignalRequest: Encodable, Equatable, Sendable {
    let signalType: String
    let payload: String

    enum CodingKeys: String, CodingKey {
        case signalType = "signal_type"
        case payload
    }
}

/// Call metadata from the server (no media, no keys).
struct CallDTO: Decodable, Equatable, Sendable, Identifiable {
    let id: UUID
    let callerUserId: UUID
    let callerDeviceId: UUID
    let calleeUserId: UUID
    let calleeDeviceId: UUID?
    let modality: String
    let status: String
    let endedReason: String?
    let createdAt: Date
    let answeredAt: Date?
    let endedAt: Date?

    enum CodingKeys: String, CodingKey {
        case id
        case callerUserId = "caller_user_id"
        case callerDeviceId = "caller_device_id"
        case calleeUserId = "callee_user_id"
        case calleeDeviceId = "callee_device_id"
        case modality
        case status
        case endedReason = "ended_reason"
        case createdAt = "created_at"
        case answeredAt = "answered_at"
        case endedAt = "ended_at"
    }

    var callModality: CallModality {
        CallModality(rawValue: modality) ?? .voice
    }

    var callStatus: CallStatus {
        CallStatus(rawValue: status) ?? .ended
    }
}

/// ICE server advertised by `GET /calls/ice-servers`.
struct IceServerDTO: Decodable, Equatable, Sendable {
    let urls: [String]
    let username: String?
    let credential: String?

    enum CodingKeys: String, CodingKey {
        case urls
        case username
        case credential
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        // Server may send `urls` as string or array.
        if let list = try? c.decode([String].self, forKey: .urls) {
            urls = list
        } else if let single = try? c.decode(String.self, forKey: .urls) {
            urls = [single]
        } else {
            urls = []
        }
        username = try c.decodeIfPresent(String.self, forKey: .username)
        credential = try c.decodeIfPresent(String.self, forKey: .credential)
    }
}

struct IceServersResponse: Decodable, Equatable, Sendable {
    let iceServers: [IceServerDTO]

    enum CodingKeys: String, CodingKey {
        case iceServers = "ice_servers"
    }
}
