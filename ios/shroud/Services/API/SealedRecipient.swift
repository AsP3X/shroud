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
/// Agent: Shared with `web/src/sealedRecipient.ts` and Android `SealedRecipient.kt`; change all
/// three together.
nonisolated enum SealedRecipient {
    /// Key of the recipient's user id in the sealed payload.
    static let key = "to"

    /// What a plaintext says about its recipient.
    enum Named: Equatable, Sendable {
        /// No `to` (or `null`): a payload from before it, or not a payload of this content type.
        case nobody
        /// `to` is this user.
        case user(UUID)
        /// `to` is there but not a user id: refused like a mismatch.
        case invalid
    }

    /// A plaintext that names someone else than the chat it was listed in.
    struct Misaddressed: Error {}

    /// `to` as sealed: the lowercase wire form.
    static func wire(_ userID: UUID) -> String {
        userID.uuidString.lowercased()
    }

    /// What `plaintext` (of a message of `contentType`) names.
    ///
    /// Agent: A text message names someone only as the envelope `MessageTextPayload.parse` reads
    /// (`"t":"text"` and a string `c`), never as JSON someone typed. Media and annotations are
    /// read as JSON objects the way `MediaMessagePayload.parse` reads them (BOM skipped, trimmed).
    static func named(in plaintext: Data, contentType: String) -> Named {
        let isPayload = contentType == "media" || contentType == MessageAnnotation.contentType
        guard let object = isPayload ? payloadObject(plaintext) : textEnvelope(plaintext),
              let value = object[key]
        else { return .nobody }
        if value is NSNull { return .nobody }
        guard let string = value as? String else { return .invalid }
        guard let userID = UUID(uuidString: string) else { return .invalid }
        return .user(userID)
    }

    /// Whether `plaintext` may be shown where it is for `expected`: the chat's peer for a message
    /// I sent, me for the peer's (me in Notes). True when it names nobody (legacy); false when it
    /// names someone else or its `to` is not a user id.
    static func accepts(_ plaintext: Data, contentType: String, expected: UUID) -> Bool {
        switch named(in: plaintext, contentType: contentType) {
        case .nobody: true
        case .invalid: false
        case let .user(userID): userID == expected
        }
    }

    /// The text envelope's object, under exactly `MessageTextPayload.parse`'s conditions.
    private static func textEnvelope(_ plaintext: Data) -> [String: Any]? {
        guard let text = String(data: plaintext, encoding: .utf8) else { return nil }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("{"), trimmed.hasSuffix("}"),
              let data = trimmed.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              object["t"] as? String == MessageTextPayload.kind,
              object["c"] as? String != nil
        else { return nil }
        return object
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
