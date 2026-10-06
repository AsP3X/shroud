import Foundation

/// The message a reply quotes, sealed **inside** the reply's own plaintext.
///
/// Human: Telegram-style replies. The quote never becomes server metadata: it rides inside the
/// ciphertext, so the relay still cannot tell which message answers which. A short snippet of the
/// quoted text travels with it so the header still reads when the original has aged out of the
/// 90-day window or has not been paged in yet — when the original *is* on the device the bubble
/// prefers it, so a later "delete for everyone" empties the quote too.
/// Agent: Wire format is shared with the web client (`web/src/reply.ts`, `ReplyRef`). Keys are
/// terse because every byte is sealed twice (peer + self) and base64-expanded: `id` = quoted
/// message id, `u` = its author, `k` = its kind, `x` = snippet.
nonisolated struct MessageReplyReference: Codable, Equatable, Hashable, Sendable {
    /// What kind of bubble is being quoted — drives the stand-in label ("Photo", "Voice message").
    enum Kind: String, Codable, Equatable, Hashable, Sendable {
        case text
        case image
        case video
        case voice
        /// A shared file; `x` carries its name, which old builds show as text.
        case file
        /// A shared audio file (`docs/file-sharing.md` §11); `x` carries its display title.
        case audio

        /// Label shown in the quote when there is no text of its own.
        var mediaLabel: String? {
            switch self {
            case .text: nil
            case .image: "Photo"
            case .video: "Video"
            case .voice: "Voice message"
            case .file: "File"
            case .audio: "Audio"
            }
        }
    }

    /// Server id of the quoted message, as `UUID` (lowercased on the wire).
    var messageID: UUID
    /// Author of the quoted message, so the header can say "You" or the peer's name.
    var senderUserID: UUID
    var kind: Kind
    /// Caption / text of the quoted message, clamped to one readable line.
    var snippet: String

    enum CodingKeys: String, CodingKey {
        case messageID = "id"
        case senderUserID = "u"
        case kind = "k"
        case snippet = "x"
    }

    /// One line of quoted text is all the header ever draws; anything longer is dead weight
    /// inside an envelope that is already sealed twice.
    static let maxSnippetCharacters = 120

    init(messageID: UUID, senderUserID: UUID, kind: Kind, snippet: String) {
        self.messageID = messageID
        self.senderUserID = senderUserID
        self.kind = kind
        self.snippet = Self.clampSnippet(snippet)
    }

    /// Collapses whitespace and cuts at a character boundary, marking the cut with "…".
    ///
    /// Human: A quote is one line. Newlines and runs of spaces in the original would otherwise
    /// blow the header up or leave it looking empty.
    static func clampSnippet(_ raw: String) -> String {
        var collapsed = ""
        collapsed.reserveCapacity(raw.count)
        var pendingSpace = false
        for character in raw {
            if character.isWhitespace || character.isNewline {
                pendingSpace = !collapsed.isEmpty
                continue
            }
            if pendingSpace {
                collapsed.append(" ")
                pendingSpace = false
            }
            collapsed.append(character)
        }
        guard collapsed.count > maxSnippetCharacters else { return collapsed }
        let kept = collapsed.prefix(maxSnippetCharacters - 1)
        return kept.trimmingCharacters(in: .whitespaces) + "…"
    }

    // MARK: - Wire format

    /// Lenient parse of the `re` object.
    ///
    /// Human: Built on `JSONSerialization` for the same reason `MediaMessagePayload` is —
    /// `JSONDecoder` has not behaved identically across iOS releases, and a payload one phone
    /// sealed must never fail to open on another.
    /// Agent: RETURNS nil unless `id` and `u` are UUIDs; unknown kinds fall back to `.text`.
    static func parse(wireObject object: [String: Any]) -> MessageReplyReference? {
        guard let rawID = string(object["id"]), let messageID = UUID(uuidString: rawID),
              let rawSender = string(object["u"]), let senderUserID = UUID(uuidString: rawSender)
        else { return nil }
        let kind = Kind(rawValue: string(object["k"]) ?? "") ?? .text
        return MessageReplyReference(
            messageID: messageID,
            senderUserID: senderUserID,
            kind: kind,
            snippet: string(object["x"]) ?? ""
        )
    }

    /// JSON object for sealing, matching the web client's `replyRefWire`.
    var wireObject: [String: Any] {
        var object: [String: Any] = [
            "id": messageID.uuidString.lowercased(),
            "u": senderUserID.uuidString.lowercased(),
            "k": kind.rawValue,
        ]
        if !snippet.isEmpty { object["x"] = snippet }
        return object
    }

    private static func string(_ value: Any?) -> String? {
        guard let string = value as? String else { return nil }
        let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

/// Plaintext of a `content_type = text` message.
///
/// Human: An ordinary message is still sealed as raw UTF-8 — byte for byte what every build has
/// sent since v1 — so nothing changes for the common case, and a build without reply support
/// still shows it correctly. A *reply* has to carry its quote, and a message with a link preview
/// its preview, so those are sealed as a small JSON envelope instead. Anything that does not
/// parse as that envelope is read back as raw text, which is what keeps the shapes
/// interoperable in both directions — a build that predates previews simply ignores `lp`.
/// Agent: READS/WRITES sealed plaintext only. Shared with `web/src/reply.ts`
/// (`parseTextPayload` / `textPayload`); change both sides together.
nonisolated enum MessageTextPayload {
    /// Discriminator of the JSON envelope (`{"t":"text", …}`).
    static let kind = "text"

    /// Plaintext to seal: the body itself when there is nothing to attach, else the envelope.
    static func wire(
        body: String,
        replyTo: MessageReplyReference?,
        linkPreview: LinkPreview? = nil
    ) -> String {
        guard replyTo != nil || linkPreview != nil else { return body }
        var object: [String: Any] = [
            "t": kind,
            "c": body,
        ]
        if let replyTo { object["re"] = replyTo.wireObject }
        if let linkPreview { object["lp"] = linkPreview.wireObject }
        // Sorted, so the same message always yields the same bytes: the local cache skips a
        // rewrite only when they match, and dictionary order differs from call to call.
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let json = String(data: data, encoding: .utf8)
        else { return body }
        return json
    }

    /// Splits sealed plaintext into the body, the quote it replies to and its link preview.
    ///
    /// Agent: Never throws — anything unparsable is returned verbatim as the body.
    static func parse(
        _ plaintext: String
    ) -> (body: String, replyTo: MessageReplyReference?, linkPreview: LinkPreview?) {
        // Cheap reject first: the overwhelming majority of messages are plain text.
        let trimmed = plaintext.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("{"), trimmed.hasSuffix("}"),
              let data = trimmed.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              object["t"] as? String == kind,
              let body = object["c"] as? String
        else { return (plaintext, nil, nil) }
        let replyTo = (object["re"] as? [String: Any]).flatMap(MessageReplyReference.parse(wireObject:))
        let linkPreview = (object["lp"] as? [String: Any]).flatMap(LinkPreview.parse(wireObject:))
        return (body, replyTo, linkPreview)
    }

    static func parse(
        _ data: Data
    ) -> (body: String, replyTo: MessageReplyReference?, linkPreview: LinkPreview?) {
        guard let text = String(data: data, encoding: .utf8) else { return ("", nil, nil) }
        return parse(text)
    }

    /// True when `plaintext` is a reply / link-preview envelope rather than raw text.
    ///
    /// Human: Used to re-read bubbles that a build without reply support stored as raw JSON.
    static func isEnvelope(_ plaintext: String) -> Bool {
        let parsed = parse(plaintext)
        return parsed.replyTo != nil || parsed.linkPreview != nil
    }
}
