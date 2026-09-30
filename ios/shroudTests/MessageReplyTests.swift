import CryptoKit
import XCTest

@testable import shroud

/// Wire-format tests for replies. The quote travels inside the sealed plaintext, so these
/// cover the only thing both clients must agree on: how a reply is written and read back.
final class MessageReplyReferenceTests: XCTestCase {
    private let messageID = UUID(uuidString: "3f2504e0-4f89-41d3-9a0c-0305e82c3301")!
    private let senderID = UUID(uuidString: "6ba7b810-9dad-11d1-80b4-00c04fd430c8")!

    private func reference(
        kind: MessageReplyReference.Kind = .text,
        snippet: String = "Hey! Are we still on for tomorrow?"
    ) -> MessageReplyReference {
        MessageReplyReference(
            messageID: messageID,
            senderUserID: senderID,
            kind: kind,
            snippet: snippet
        )
    }

    // MARK: - Snippet

    func testSnippetCollapsesWhitespace() {
        let reference = reference(snippet: "  two   lines\nof   text \n")
        XCTAssertEqual(reference.snippet, "two lines of text")
    }

    func testSnippetIsClampedWithEllipsis() {
        let long = String(repeating: "a", count: 400)
        let clamped = MessageReplyReference.clampSnippet(long)
        XCTAssertEqual(clamped.count, MessageReplyReference.maxSnippetCharacters)
        XCTAssertTrue(clamped.hasSuffix("…"))
    }

    func testSnippetKeepsGraphemeClusters() {
        let emoji = String(repeating: "👩‍👩‍👧‍👦", count: 60)
        let clamped = MessageReplyReference.clampSnippet(emoji)
        XCTAssertLessThanOrEqual(clamped.count, MessageReplyReference.maxSnippetCharacters)
    }

    // MARK: - Wire object

    func testWireObjectRoundTrip() {
        let original = reference(kind: .image, snippet: "At the trailhead")
        guard let parsed = MessageReplyReference.parse(wireObject: original.wireObject) else {
            return XCTFail("wire object must parse")
        }
        XCTAssertEqual(parsed, original)
    }

    func testWireObjectUsesLowercasedIDs() {
        let wire = reference().wireObject
        XCTAssertEqual(wire["id"] as? String, messageID.uuidString.lowercased())
        XCTAssertEqual(wire["u"] as? String, senderID.uuidString.lowercased())
        XCTAssertEqual(wire["k"] as? String, "text")
    }

    func testWireObjectOmitsEmptySnippet() {
        XCTAssertNil(reference(kind: .voice, snippet: "").wireObject["x"])
    }

    func testParseRejectsMissingIDs() {
        XCTAssertNil(MessageReplyReference.parse(wireObject: ["u": senderID.uuidString, "k": "text"]))
        XCTAssertNil(MessageReplyReference.parse(wireObject: ["id": "not-a-uuid", "u": senderID.uuidString]))
    }

    func testParseFallsBackToTextKind() {
        let parsed = MessageReplyReference.parse(wireObject: [
            "id": messageID.uuidString,
            "u": senderID.uuidString,
            "k": "sticker",
        ])
        XCTAssertEqual(parsed?.kind, .text)
    }

    // MARK: - Text payload

    func testPlainTextIsSealedVerbatim() {
        XCTAssertEqual(MessageTextPayload.wire(body: "Just a message", replyTo: nil), "Just a message")
    }

    func testPlainTextParsesAsItself() {
        let parsed = MessageTextPayload.parse("Just a message")
        XCTAssertEqual(parsed.body, "Just a message")
        XCTAssertNil(parsed.replyTo)
    }

    func testReplyEnvelopeRoundTrip() {
        let quote = reference()
        let wire = MessageTextPayload.wire(body: "Yes! 10am at the trailhead", replyTo: quote)
        let parsed = MessageTextPayload.parse(wire)
        XCTAssertEqual(parsed.body, "Yes! 10am at the trailhead")
        XCTAssertEqual(parsed.replyTo, quote)
    }

    /// A message someone typed that merely looks like JSON must survive untouched.
    func testJSONLookingTextIsNotMistakenForAReply() {
        let typed = #"{"t":"text","c":"nice try"}"#
        let parsed = MessageTextPayload.parse(typed)
        XCTAssertEqual(parsed.body, "nice try")
        XCTAssertNil(parsed.replyTo)

        let other = #"{"hello":"world"}"#
        XCTAssertEqual(MessageTextPayload.parse(other).body, other)
    }

    func testTruncatedEnvelopeFallsBackToRawText() {
        let broken = #"{"t":"text","c":"half"#
        XCTAssertEqual(MessageTextPayload.parse(broken).body, broken)
    }

    /// Golden fixture: exactly what the web client seals (`web/src/reply.ts`). iOS must open it.
    func testOpensWebClientEnvelope() {
        let fromWeb = """
        {"t":"text","c":"Perfect, see you there",\
        "re":{"id":"3f2504e0-4f89-41d3-9a0c-0305e82c3301",\
        "u":"6ba7b810-9dad-11d1-80b4-00c04fd430c8","k":"voice","x":"Voice message"}}
        """
        let parsed = MessageTextPayload.parse(fromWeb)
        XCTAssertEqual(parsed.body, "Perfect, see you there")
        XCTAssertEqual(parsed.replyTo?.messageID, messageID)
        XCTAssertEqual(parsed.replyTo?.senderUserID, senderID)
        XCTAssertEqual(parsed.replyTo?.kind, .voice)
        XCTAssertEqual(parsed.replyTo?.snippet, "Voice message")
    }

    // MARK: - Media payload

    func testMediaPayloadCarriesTheQuote() throws {
        let quote = reference(kind: .image, snippet: "Nice shot")
        let payload = MediaMessagePayload(
            t: MediaMessagePayload.kindImage,
            mime: "image/jpeg",
            w: 1024,
            h: 768,
            k: "a2V5",
            s: 2048,
            re: quote
        )
        let encoded = try payload.encoded()
        let parsed = try XCTUnwrap(MediaMessagePayload.parse(encoded))
        XCTAssertEqual(parsed.re, quote)
        XCTAssertEqual(parsed.k, "a2V5")
    }

    func testMediaPayloadWithoutQuoteStillParses() throws {
        let payload = MediaMessagePayload(
            t: MediaMessagePayload.kindVoice,
            mime: "audio/mp4",
            w: 0,
            h: 0,
            k: "a2V5",
            d: 1200
        )
        let parsed = try XCTUnwrap(MediaMessagePayload.parse(payload.encoded()))
        XCTAssertNil(parsed.re)
    }

    /// Old payloads (sealed before replies existed) must decode exactly as before.
    func testLegacyMediaPayloadIsUnaffected() throws {
        let legacy = #"{"t":"image","mime":"image/jpeg","w":4,"h":3,"k":"a2V5","c":"caption"}"#
        let parsed = try XCTUnwrap(MediaMessagePayload.parse(Data(legacy.utf8)))
        XCTAssertNil(parsed.re)
        XCTAssertEqual(parsed.c, "caption")
    }
}

/// How a bubble turns into the quote a reply carries.
@MainActor
final class ChatMessageReplyReferenceTests: XCTestCase {
    private func message(
        text: String = "Hey!",
        kind: MessagingController.ChatMessageKind = .text,
        deleted: Bool = false,
        receipt: MessageReceiptStatus = .sent,
        pendingSync: Bool = false
    ) -> MessagingController.ChatMessage {
        MessagingController.ChatMessage(
            id: UUID(),
            peerUserID: UUID(),
            senderUserID: UUID(),
            text: text,
            createdAt: Date(),
            isMine: true,
            deleted: deleted,
            receipt: receipt,
            kind: kind,
            pendingSync: pendingSync
        )
    }

    func testQuotesTextMessage() {
        let source = message(text: "Hey!")
        let quote = source.replyReference
        XCTAssertEqual(quote?.messageID, source.id)
        XCTAssertEqual(quote?.kind, .text)
        XCTAssertEqual(quote?.snippet, "Hey!")
    }

    /// Media bubbles keep a stand-in label in `text`; only a real caption is worth sealing.
    func testMediaStandInLabelsAreNotSealedAsSnippets() {
        XCTAssertEqual(message(text: "Photo", kind: .image).replyReference?.snippet, "")
        XCTAssertEqual(message(text: "Video", kind: .video).replyReference?.snippet, "")
        XCTAssertEqual(message(text: "A caption", kind: .image).replyReference?.snippet, "A caption")
        XCTAssertEqual(message(text: "A transcript", kind: .voice).replyReference?.snippet, "")
    }

    func testUnsendableMessagesCannotBeQuoted() {
        XCTAssertNil(message(deleted: true).replyReference)
        XCTAssertNil(message(receipt: .failed).replyReference)
        XCTAssertNil(message(receipt: .sending).replyReference)
        XCTAssertNil(message(pendingSync: true).replyReference)
    }

    /// The composer keeps the snapshot from when the reply started, so a later
    /// delete-for-everyone must not strip the quote off the message about to be sent.
    func testSnapshotStillQuotesAfterTheLiveCopyIsDeleted() {
        let source = message(text: "Hey!")
        let snapshot = source.replyReference
        let deleted = message(text: "Hey!", deleted: true)
        XCTAssertNil(deleted.replyReference)
        XCTAssertEqual(snapshot?.snippet, "Hey!")
        XCTAssertEqual(snapshot?.messageID, source.id)
    }
}

/// Replies have to survive a cold start: the quote is part of the sealed thread file.
@MainActor
final class StoredReplyTests: XCTestCase {
    func testStoredMessageKeepsTheQuote() throws {
        let quote = MessageReplyReference(
            messageID: UUID(),
            senderUserID: UUID(),
            kind: .video,
            snippet: "On the ridge"
        )
        let message = MessagingController.ChatMessage(
            id: UUID(),
            peerUserID: UUID(),
            senderUserID: UUID(),
            text: "Look at this",
            createdAt: Date(),
            isMine: true,
            deleted: false,
            replyTo: quote
        )

        let stored = LocalMessageStore.StoredMessage.from(message)
        let data = try JSONEncoder.localStore.encode(stored)
        let decoded = try JSONDecoder.localStore.decode(LocalMessageStore.StoredMessage.self, from: data)
        let restored = decoded.toChatMessage(media: LocalMediaCache(), historyKey: SymmetricKey(size: .bits256))

        XCTAssertEqual(restored.replyTo, quote)
        XCTAssertEqual(restored.text, "Look at this")
    }

    /// Thread files written before replies existed have no `replyTo` key at all.
    func testLegacyStoredMessageDecodesWithoutQuote() throws {
        let legacy = """
        {"createdAt":"2026-09-19T10:00:00.000Z","deleted":false,"id":"\(UUID().uuidString)",\
        "isMine":true,"kind":"text","peerUserID":"\(UUID().uuidString)","receipt":"sent",\
        "senderUserID":"\(UUID().uuidString)","text":"before replies"}
        """
        let decoded = try JSONDecoder.localStore.decode(
            LocalMessageStore.StoredMessage.self,
            from: Data(legacy.utf8)
        )
        XCTAssertNil(decoded.replyTo)
        XCTAssertEqual(decoded.text, "before replies")
    }
}
