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

/// `POST /calls` body. Protocol 2: media is negotiated after the answer (docs/calls.md).
struct CreateCallRequest: Encodable, Equatable, Sendable {
    let peerUserId: UUID
    let modality: String
    let callProtocol: Int

    enum CodingKeys: String, CodingKey {
        case peerUserId = "peer_user_id"
        case modality
        case callProtocol = "protocol"
    }

    init(peerUserId: UUID, modality: CallModality) {
        self.peerUserId = peerUserId
        self.modality = modality.rawValue
        callProtocol = 2
    }
}

/// `POST /calls/:id/accept` body: empty.
struct AcceptCallRequest: Encodable, Equatable, Sendable {}

/// `POST /calls/:id/signal` body — a sealed signal (`CallCrypto`).
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
    let callerUsername: String?
    let calleeUserId: UUID
    let calleeDeviceId: UUID?
    let calleeUsername: String?
    let modality: String
    let status: String
    let endedReason: String?
    let callProtocol: Int?
    let createdAt: Date
    let answeredAt: Date?
    let endedAt: Date?

    enum CodingKeys: String, CodingKey {
        case id
        case callerUserId = "caller_user_id"
        case callerDeviceId = "caller_device_id"
        case callerUsername = "caller_username"
        case calleeUserId = "callee_user_id"
        case calleeDeviceId = "callee_device_id"
        case calleeUsername = "callee_username"
        case modality
        case status
        case endedReason = "ended_reason"
        case callProtocol = "protocol"
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

    var isLive: Bool {
        status == "ringing" || status == "active"
    }
}

/// `GET /calls` answer.
struct CallListResponse: Decodable, Sendable {
    let calls: [CallDTO]
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
