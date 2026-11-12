import Foundation

/// The recipient a message names inside its own plaintext (security audit M7).
///
/// Human: Every message is sealed twice with the same plaintext — for the peer, and for the
/// sender's other devices (the `self` box). The envelope does not say whom the `self` box was
/// for, so a server could list my message to Alice in my chat with Bob, and my other devices
/// would show it as sent to Bob. New messages therefore carry `to`, the recipient's user id
/// (lowercase UUID; my own id in Notes), inside the sealed payload, where the server can neither
/// read nor strip it:
/// - text: the envelope `{"c":<body>,"t":"text","to":<id>,…}` (`MessageTextPayload`); every build
///   since replies reads `c` of that envelope and ignores keys it does not know;
/// - media: `to` in `MediaMessagePayload`, whose unknown keys every build ignores;
/// - annotation: `to` in `MessageAnnotation`, likewise.
///
/// Opening checks it: a message I sent must name the chat's peer, one from the peer must name
/// me. A payload without `to` (every message from builds before it) is accepted as before.
///
/// Human: `content_type` is set by the server outside the seal, so it never decides whether `to`
/// is read (security audit C1): any plaintext that is a JSON object with `to` is checked. It only
/// marks a text message whose plaintext is a media payload or an annotation as misfiled. The rule
/// is written down in `docs/architecture.md` (Sealed recipient).
/// Agent: Shared with `web/src/sealedRecipient.ts` and Android `SealedRecipient.kt`; change all
/// three together.
nonisolated enum SealedRecipient {
    /// Key of the recipient's user id in the sealed payload.
    static let key = "to"

    /// What a plaintext says about its recipient.
    nonisolated enum Named: Equatable, Sendable {
        /// No `to` (or `null`): a payload from before it, raw text, or not a JSON object.
        case nobody
        /// `to` is this user.
        case user(UUID)
        /// `to` is there but not a user id: refused like a mismatch.
        case invalid
    }

    /// A plaintext that names someone else than the chat it was listed in.
    nonisolated struct Misaddressed: Error {}

    /// `to` as sealed: the lowercase wire form.
    static func wire(_ userID: UUID) -> String {
        userID.uuidString.lowercased()
    }

    /// What `plaintext` names: the `to` of the JSON object it is, read the way
    /// `MediaMessagePayload.parse` reads a payload (BOM skipped, trimmed), whatever the message is
    /// labelled. Raw text and anything else that is not an object names nobody.
    static func named(in plaintext: Data) -> Named {
        payloadObject(plaintext).map(named(in:)) ?? .nobody
    }

    private static func named(in object: [String: Any]) -> Named {
        guard let value = object[key], !(value is NSNull) else { return .nobody }
        guard let string = value as? String, let userID = UUID(uuidString: string) else { return .invalid }
        return .user(userID)
    }

    /// Whether `plaintext` (of a message labelled `contentType`) may be shown where it is for
    /// `expected`: the chat's peer for a message I sent, me for the peer's (me in Notes). True when
    /// it names nobody (legacy); false when it names someone else, its `to` is not a user id, or a
    /// message not labelled media or annotation holds a media payload or an annotation (relabelled
    /// by the server, its raw JSON would be the bubble).
    static func accepts(_ plaintext: Data, contentType: String, expected: UUID) -> Bool {
        guard let object = payloadObject(plaintext) else { return true }
        let isPayloadType = contentType == "media" || contentType == MessageAnnotation.contentType
        if !isPayloadType, isPayloadShaped(object) { return false }
        switch named(in: object) {
        case .nobody: return true
        case .invalid: return false
        case let .user(userID): return userID == expected
        }
    }

    /// The shape of a media payload (`t` and `k` strings, `MediaMessagePayload.parse`) or of an
    /// annotation (`t` is `transcript` and `r` a string, `MessageAnnotation.parseTranscript`).
    private static func isPayloadShaped(_ object: [String: Any]) -> Bool {
        guard let t = object["t"] as? String else { return false }
        return object["k"] is String || (t == MessageAnnotation.kindTranscript && object["r"] is String)
    }

    /// The payload's object, as `MediaMessagePayload.parse` finds it.
    private static func payloadObject(_ plaintext: Data) -> [String: Any]? {
        var json = plaintext
        if json.starts(with: [0xEF, 0xBB, 0xBF] as [UInt8]) {
            json = Data(json.dropFirst(3))
        }
        if let object = try? JSONSerialization.jsonObject(with: json) as? [String: Any] {
            return object
        }
        guard let raw = String(data: json, encoding: .utf8)?
            .trimmingCharacters(in: .whitespacesAndNewlines),
            raw.first == "{",
            let trimmed = raw.data(using: .utf8)
        else { return nil }
        return try? JSONSerialization.jsonObject(with: trimmed) as? [String: Any]
    }
}
