import Foundation

/// This device's push settings as the server stores them (`/notifications/settings`).
struct NotificationSettingsDTO: Codable, Equatable, Sendable {
    let enabled: Bool
    let showSender: Bool
    let reactions: Bool
    let contactRequests: Bool
    let sound: String
    let badge: Bool
    let badgeIncludesMuted: Bool

    enum CodingKeys: String, CodingKey {
        case enabled
        case showSender = "show_sender"
        case reactions
        case contactRequests = "contact_requests"
        case sound
        case badge
        case badgeIncludesMuted = "badge_includes_muted"
    }
}

/// Partial update: nil fields stay as the server has them.
struct NotificationSettingsPatch: Encodable, Equatable, Sendable {
    var enabled: Bool?
    var showSender: Bool?
    var reactions: Bool?
    var contactRequests: Bool?
    var sound: String?
    var badge: Bool?
    var badgeIncludesMuted: Bool?

    enum CodingKeys: String, CodingKey {
        case enabled
        case showSender = "show_sender"
        case reactions
        case contactRequests = "contact_requests"
        case sound
        case badge
        case badgeIncludesMuted = "badge_includes_muted"
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(enabled, forKey: .enabled)
        try container.encodeIfPresent(showSender, forKey: .showSender)
        try container.encodeIfPresent(reactions, forKey: .reactions)
        try container.encodeIfPresent(contactRequests, forKey: .contactRequests)
        try container.encodeIfPresent(sound, forKey: .sound)
        try container.encodeIfPresent(badge, forKey: .badge)
        try container.encodeIfPresent(badgeIncludesMuted, forKey: .badgeIncludesMuted)
    }
}

/// A chat's mute: present while muted; `until` nil = until turned back on.
struct ChatMuteDTO: Codable, Equatable, Sendable {
    let until: Date?

    /// A mute whose time has passed is over, before the next chat list says so.
    func isActive(now: Date = Date()) -> Bool {
        guard let until else { return true }
        return until > now
    }
}

struct MuteChatBody: Encodable, Equatable, Sendable {
    /// Nil: until unmuted. Encoded as `null` so the server reads "no end".
    let seconds: Int?

    enum CodingKeys: String, CodingKey { case seconds }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(seconds, forKey: .seconds)
    }
}

struct MuteChatResponse: Decodable, Equatable, Sendable {
    let peerUserId: UUID
    let mute: ChatMuteDTO

    enum CodingKeys: String, CodingKey {
        case peerUserId = "peer_user_id"
        case mute
    }
}

struct MarkChatReadResponse: Decodable, Equatable, Sendable {
    let readAt: Date?
    let unreadCount: Int
    let receipts: Int

    enum CodingKeys: String, CodingKey {
        case readAt = "read_at"
        case unreadCount = "unread_count"
        case receipts
    }
}

/// `POST /push/test`: what happened to the test notification.
struct TestPushOutcomeDTO: Decodable, Equatable, Sendable {
    let channel: String?
    let status: String
    let detail: String?
}

struct PushTokenBody: Encodable, Sendable {
    let token: String
    let environment: String
    /// `alert` or `voip`.
    let kind: String
    /// Base64 of the 32-byte key the server seals names with (alert tokens only).
    let payloadKey: String?

    enum CodingKeys: String, CodingKey {
        case token
        case environment
        case kind
        case payloadKey = "payload_key"
    }
}

/// How long a chat stays muted, as Telegram offers it.
enum MuteDuration: CaseIterable, Identifiable, Sendable {
    case hour
    case eightHours
    case day
    case week
    case forever

    var id: Self { self }

    /// Seconds for the server; nil = until unmuted.
    var seconds: Int? {
        switch self {
        case .hour: 3600
        case .eightHours: 8 * 3600
        case .day: 24 * 3600
        case .week: 7 * 24 * 3600
        case .forever: nil
        }
    }

    var title: String {
        switch self {
        case .hour: "For 1 Hour"
        case .eightHours: "For 8 Hours"
        case .day: "For 1 Day"
        case .week: "For 7 Days"
        case .forever: "Until I Turn It Back On"
        }
    }

    /// "Muted", "Muted until 14:30", "Muted until Fri 14:30", "Muted until 3 Oct" — or nil when
    /// the chat is not muted.
    static func label(for mute: ChatMuteDTO?, now: Date = Date(), calendar: Calendar = .current) -> String? {
        guard let mute, mute.isActive(now: now) else { return nil }
        guard let until = mute.until else { return "Muted" }
        let time = until.formatted(date: .omitted, time: .shortened)
        if calendar.isDate(until, inSameDayAs: now) { return "Muted until \(time)" }
        if until.timeIntervalSince(now) < 6 * 24 * 3600 {
            return "Muted until \(until.formatted(.dateTime.weekday(.abbreviated))) \(time)"
        }
        return "Muted until \(until.formatted(.dateTime.day().month(.abbreviated)))"
    }
}
