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
    /// Longest transcript accepted from a peer; a two-minute note is far below this.
    static let maxTranscriptLength = 8000

    static func transcript(_ text: String, for messageID: UUID) -> MessageAnnotation {
        MessageAnnotation(t: kindTranscript, r: messageID.uuidString.lowercased(), c: text)
    }

    /// The shared transcript and the voice message it belongs to, or nil if `plaintext` isn't one.
    static func parseTranscript(_ plaintext: Data) -> (messageID: UUID, text: String)? {
        guard let annotation = try? JSONDecoder().decode(MessageAnnotation.self, from: plaintext),
              annotation.t == kindTranscript,
              let messageID = UUID(uuidString: annotation.r)
        else { return nil }
        let text = String(
            annotation.c.trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxTranscriptLength)
        )
        return text.isEmpty ? nil : (messageID, text)
    }

    static func parseTranscript(_ plaintext: String) -> (messageID: UUID, text: String)? {
        parseTranscript(Data(plaintext.utf8))
    }
}
