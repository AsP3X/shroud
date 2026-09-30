import Foundation

/// Plaintext sealed inside a `content_type = annotation` message: data one participant attaches
/// to an earlier message instead of sending a new one.
///
/// Human: The first (and so far only) use is a voice transcript made on the recipient's device
/// and shared back, so the sender's devices — the web client included — show it too. The server
/// relays annotations like any message but never reorders the chat or pushes for them; clients
/// fold them into the message they point at and never show them as bubbles.
/// Agent: Wire format is shared with the web client (`web/src/messaging.ts`, `Annotation`).
nonisolated struct MessageAnnotation: Codable, Equatable, Sendable {
    /// Annotation type — `"transcript"`.
    var t: String
    /// Server id of the annotated message, lowercased.
    var r: String
    /// Annotation body — the transcript text.
    var c: String

    static let contentType = "annotation"
    static let kindTranscript = "transcript"
    /// Largest transcript we seal or accept, in UTF-8 bytes (~18 minutes of English speech).
    ///
    /// Human: Every message is sealed twice (ratchet + our own copy) and base64-expanded, so
    /// 16 KB of text is ~44 KB on the wire — safely inside the server's 64 KB message limit in
    /// any script. A character cap is not enough: 8,000 CJK characters already brush the limit,
    /// and an over-long transcript would make the whole voice message fail to send.
    static let maxTranscriptBytes = 16 * 1024

    /// Trims `text` to `maxTranscriptBytes` at a character boundary, marking the cut with "…".
    static func clampTranscript(_ text: String) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.utf8.count > maxTranscriptBytes else { return trimmed }
        let ellipsis = "…"
        let budget = maxTranscriptBytes - ellipsis.utf8.count
        var used = 0
        var kept = ""
        for character in trimmed {
            let size = character.utf8.count
            if used + size > budget { break }
            used += size
            kept.append(character)
        }
        return kept.trimmingCharacters(in: .whitespacesAndNewlines) + ellipsis
    }

    static func transcript(_ text: String, for messageID: UUID) -> MessageAnnotation {
        MessageAnnotation(t: kindTranscript, r: messageID.uuidString.lowercased(), c: clampTranscript(text))
    }

    /// The shared transcript and the voice message it belongs to, or nil if `plaintext` isn't one.
    static func parseTranscript(_ plaintext: Data) -> (messageID: UUID, text: String)? {
        guard let annotation = try? JSONDecoder().decode(MessageAnnotation.self, from: plaintext),
              annotation.t == kindTranscript,
              let messageID = UUID(uuidString: annotation.r)
        else { return nil }
        let text = clampTranscript(annotation.c)
        return text.isEmpty ? nil : (messageID, text)
    }

    static func parseTranscript(_ plaintext: String) -> (messageID: UUID, text: String)? {
        parseTranscript(Data(plaintext.utf8))
    }
}
