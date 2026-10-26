import XCTest
@testable import shroud

/// The recipient sealed inside a message (`to`, security audit M7): what iOS seals, that the
/// parsers every current build runs still read it, and which plaintexts
/// `SealedRecipient.accepts` lets through. The fixtures are the strings
/// `web/src/sealedRecipient.selftest.ts` and Android `SealedRecipientTest` check.
@MainActor
final class SealedRecipientTests: XCTestCase {
    private let me = UUID(uuidString: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")!
    private let bob = UUID(uuidString: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")!
    private let alice = UUID(uuidString: "a11ce000-0000-4000-8000-000000000001")!
    private let voiceID = UUID(uuidString: "3f2504e0-4f89-41d3-9a0c-0305e82c3301")!

    private func accepts(_ plaintext: String, _ contentType: String, _ expected: UUID) -> Bool {
        SealedRecipient.accepts(Data(plaintext.utf8), contentType: contentType, expected: expected)
    }

    // MARK: - Text

    func testAPlainMessageIsSealedAsTheEnvelopeNamingItsRecipient() {
        XCTAssertEqual(
            MessageTextPayload.wire(body: "See you at 10", replyTo: nil, to: bob),
            #"{"c":"See you at 10","t":"text","to":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}"#
        )
        // Without a recipient the body stays raw, as every build sent it before.
        XCTAssertEqual(MessageTextPayload.wire(body: "See you at 10", replyTo: nil), "See you at 10")
    }

    func testTheParserEveryBuildRunsShowsTheBody() {
        let parsed = MessageTextPayload.parse(MessageTextPayload.wire(body: "See you at 10", replyTo: nil, to: bob))
        XCTAssertEqual(parsed.body, "See you at 10")
        XCTAssertNil(parsed.replyTo)
        XCTAssertNil(parsed.linkPreview)
        // The web seals the keys in another order.
        let fromWeb = #"{"t":"text","c":"See you at 10","to":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}"#
        XCTAssertEqual(MessageTextPayload.parse(fromWeb).body, "See you at 10")
        XCTAssertTrue(accepts(fromWeb, "text", bob))
    }

    func testBoundAddsTheRecipientToAReadyWireAndWrapsAnythingElse() {
        let quote = MessageReplyReference(messageID: voiceID, senderUserID: me, kind: .text, snippet: "10?")
        let reply = MessageTextPayload.wire(body: "Yes", replyTo: quote)
        XCTAssertEqual(
            MessageTextPayload.bound(reply, to: bob),
            MessageTextPayload.wire(body: "Yes", replyTo: quote, to: bob)
        )
        XCTAssertEqual(MessageTextPayload.bound("hi", to: bob), MessageTextPayload.wire(body: "hi", replyTo: nil, to: bob))
        // A Notes todo keeps its marker as the body.
        let todo = MessageTextPayload.bound("[todo:0]Buy milk", to: me)
        XCTAssertEqual(MessageTextPayload.parse(todo).body, "[todo:0]Buy milk")
        XCTAssertEqual(NotesLocal.parseSyncedTodo(MessageTextPayload.parse(todo).body)?.text, "Buy milk")
        XCTAssertTrue(accepts(todo, "text", me))
        // An envelope that already names someone is re-addressed, not doubled.
        XCTAssertEqual(
            MessageTextPayload.bound(MessageTextPayload.wire(body: "x", replyTo: nil, to: alice), to: bob),
            MessageTextPayload.wire(body: "x", replyTo: nil, to: bob)
        )
    }

    func testTheRecipientIsCheckedOnText() {
        let wire = MessageTextPayload.wire(body: "See you at 10", replyTo: nil, to: bob)
        XCTAssertTrue(accepts(wire, "text", bob))
        XCTAssertFalse(accepts(wire, "text", alice))
        XCTAssertFalse(accepts(wire, "text", me))
        XCTAssertEqual(SealedRecipient.named(in: Data(wire.utf8), contentType: "text"), .user(bob))
    }

    func testLegacyAndTypedTextNamesNobody() {
        XCTAssertTrue(accepts("See you at 10", "text", alice))
        let quote = MessageReplyReference(messageID: voiceID, senderUserID: me, kind: .text, snippet: "10?")
        XCTAssertTrue(accepts(MessageTextPayload.wire(body: "Yes", replyTo: quote), "text", alice))
        // JSON someone typed is not the envelope, whatever keys it has.
        XCTAssertTrue(accepts(#"{"to":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}"#, "text", alice))
        XCTAssertTrue(accepts(#"{"t":"text","c":"x","to":null}"#, "text", alice))
        XCTAssertEqual(SealedRecipient.named(in: Data("plain".utf8), contentType: "text"), .nobody)
    }

    func testAToThatIsNotAnIdFailsClosed() {
        XCTAssertFalse(accepts(#"{"t":"text","c":"x","to":5}"#, "text", alice))
        XCTAssertFalse(accepts(#"{"t":"text","c":"x","to":""}"#, "text", alice))
        XCTAssertFalse(accepts(#"{"t":"text","c":"x","to":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb "}"#, "text", bob))
        XCTAssertEqual(SealedRecipient.named(in: Data(#"{"t":"text","c":"x","to":"nope"}"#.utf8), contentType: "text"), .invalid)
        // Any case of the canonical form is the same id.
        XCTAssertTrue(accepts(#"{"t":"text","c":"x","to":"BBBBBBBB-BBBB-4BBB-8BBB-BBBBBBBBBBBB"}"#, "text", bob))
    }

    // MARK: - Media and annotations

    func testMediaPayloadsNameTheirRecipientAndStillParse() throws {
        let fixture = #"{"h":768,"k":"a2V5","mime":"image/jpeg","t":"image","to":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","w":1024}"#
        let payload = try XCTUnwrap(MediaMessagePayload.parse(Data(fixture.utf8)))
        XCTAssertEqual(payload.t, "image")
        XCTAssertEqual(payload.w, 1024)
        XCTAssertNil(payload.to) // never read back: a payload sealed again carries no old recipient
        XCTAssertTrue(accepts(fixture, "media", bob))
        XCTAssertFalse(accepts(fixture, "media", alice))

        let sealed = try MediaMessagePayload(t: "voice", mime: "audio/mp4", w: 0, h: 0, k: "a2V5", d: 1200, to: bob).encoded()
        XCTAssertEqual(SealedRecipient.named(in: sealed, contentType: "media"), .user(bob))
        XCTAssertEqual(MediaMessagePayload.parse(sealed)?.t, "voice")

        XCTAssertTrue(accepts(#"{"t":"voice","mime":"audio/mp4","w":0,"h":0,"k":"a2V5","d":1200}"#, "media", alice))
        XCTAssertTrue(accepts("not json", "media", alice))
    }

    func testAnnotationsNameTheirRecipientAndStillParse() throws {
        let fixture = #"{"c":"Hello there","r":"3f2504e0-4f89-41d3-9a0c-0305e82c3301","t":"transcript","to":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}"#
        let shared = try XCTUnwrap(MessageAnnotation.parseTranscript(fixture))
        XCTAssertEqual(shared.messageID, voiceID)
        XCTAssertEqual(shared.text, "Hello there")
        XCTAssertTrue(accepts(fixture, MessageAnnotation.contentType, bob))
        XCTAssertFalse(accepts(fixture, MessageAnnotation.contentType, me))

        let sealed = try JSONEncoder().encode(MessageAnnotation.transcript("Hello there", for: voiceID, to: bob))
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: sealed) as? [String: Any])
        XCTAssertEqual(object["to"] as? String, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        XCTAssertEqual(MessageAnnotation.parseTranscript(sealed)?.text, "Hello there")
        // Without a recipient nothing new is written.
        let legacy = try JSONEncoder().encode(MessageAnnotation.transcript("Hello there", for: voiceID))
        let legacyObject = try XCTUnwrap(JSONSerialization.jsonObject(with: legacy) as? [String: Any])
        XCTAssertNil(legacyObject["to"])
    }
}
