import XCTest

@testable import shroud

/// Wire-format tests for link previews. The preview is sealed inside the plaintext, so the only
/// contract between the clients is how `lp` is written and read — the fixtures below are the
/// same strings `web/src/links.selftest.ts` checks.
@MainActor
final class LinkPreviewPayloadTests: XCTestCase {
    private let thumbnail = Data([0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46])

    private func sample(thumbnail: Data? = nil) -> LinkPreview {
        LinkPreview(
            url: "https://komoot.com/tour/1398273",
            siteName: "komoot",
            title: "Herzogstand – Heimgarten ridge walk",
            summary: "Intermediate hike · 13.6 km",
            thumbnail: thumbnail,
            imageWidth: 1200,
            imageHeight: 630,
            isVideo: true,
            showsAboveText: true
        )
    }

    // MARK: - lp object

    func testWireObjectRoundTrip() {
        let original = sample(thumbnail: thumbnail)
        XCTAssertEqual(LinkPreview.parse(wireObject: original.wireObject), original)
    }

    func testWireObjectUsesTerseKeysAndOmitsFalseFlags() {
        var preview = sample()
        preview.isVideo = false
        preview.showsAboveText = false
        let wire = preview.wireObject
        XCTAssertEqual(wire["u"] as? String, "https://komoot.com/tour/1398273")
        XCTAssertEqual(wire["n"] as? String, "komoot")
        XCTAssertEqual(wire["ti"] as? String, "Herzogstand – Heimgarten ridge walk")
        XCTAssertEqual(wire["d"] as? String, "Intermediate hike · 13.6 km")
        XCTAssertNil(wire["vd"])
        XCTAssertNil(wire["ab"])
        XCTAssertNil(wire["th"])
    }

    func testOnlyWebURLsParse() {
        for url in ["javascript:alert(1)", "ftp://example.com", "file:///etc/passwd", ""] {
            XCTAssertNil(LinkPreview.parse(wireObject: ["u": url]), url)
        }
        XCTAssertNotNil(LinkPreview.parse(wireObject: ["u": "http://example.com"]))
    }

    func testOversizedThumbnailIsDropped() {
        let big = Data(repeating: 1, count: LinkPreview.maxThumbnailBytes + 1)
        let parsed = LinkPreview.parse(wireObject: ["u": "https://example.com", "th": big.base64EncodedString()])
        XCTAssertNotNil(parsed)
        XCTAssertNil(parsed?.thumbnail)
    }

    func testLongFieldsAreClamped() {
        let preview = LinkPreview(url: "https://example.com", title: String(repeating: "a", count: 500))
        XCTAssertEqual(preview.title?.count, LinkPreview.maxTitleCharacters)
        XCTAssertEqual(preview.title?.hasSuffix("…"), true)
    }

    func testDisplaySiteNameFallsBackToHost() {
        let preview = LinkPreview(url: "https://www.example.com/a", title: "A page")
        XCTAssertEqual(preview.displaySiteName, "example.com")
    }

    // MARK: - Text envelope

    func testPlainTextStaysRaw() {
        XCTAssertEqual(MessageTextPayload.wire(body: "hi", replyTo: nil, linkPreview: nil), "hi")
    }

    func testTextEnvelopeRoundTrip() {
        let wire = MessageTextPayload.wire(body: "Route: komoot.com/tour/1398273", replyTo: nil, linkPreview: sample(thumbnail: thumbnail))
        let parsed = MessageTextPayload.parse(wire)
        XCTAssertEqual(parsed.body, "Route: komoot.com/tour/1398273")
        XCTAssertNil(parsed.replyTo)
        XCTAssertEqual(parsed.linkPreview, sample(thumbnail: thumbnail))
    }

    func testReplyAndPreviewTravelTogether() {
        let quote = MessageReplyReference(
            messageID: UUID(uuidString: "3f2504e0-4f89-41d3-9a0c-0305e82c3301")!,
            senderUserID: UUID(uuidString: "6ba7b810-9dad-11d1-80b4-00c04fd430c8")!,
            kind: .text,
            snippet: "Which trail?"
        )
        let wire = MessageTextPayload.wire(body: "This one", replyTo: quote, linkPreview: sample())
        let parsed = MessageTextPayload.parse(wire)
        XCTAssertEqual(parsed.replyTo, quote)
        XCTAssertEqual(parsed.linkPreview, sample())
        XCTAssertTrue(MessageTextPayload.isEnvelope(wire))
    }

    /// What the web client seals (`linkPreviewWire` in `web/src/links.ts`).
    func testParsesWebSealedPreview() {
        let fromWeb = #"{"t":"text","c":"Route: komoot.com/tour/1398273","lp":{"u":"https://komoot.com/tour/1398273","n":"komoot","ti":"Herzogstand","d":"Ridge walk","w":1200,"h":630,"vd":true,"ab":true}}"#
        let parsed = MessageTextPayload.parse(fromWeb)
        XCTAssertEqual(parsed.body, "Route: komoot.com/tour/1398273")
        XCTAssertEqual(parsed.linkPreview?.url, "https://komoot.com/tour/1398273")
        XCTAssertEqual(parsed.linkPreview?.siteName, "komoot")
        XCTAssertEqual(parsed.linkPreview?.title, "Herzogstand")
        XCTAssertEqual(parsed.linkPreview?.summary, "Ridge walk")
        XCTAssertEqual(parsed.linkPreview?.imageWidth, 1200)
        XCTAssertEqual(parsed.linkPreview?.imageHeight, 630)
        XCTAssertEqual(parsed.linkPreview?.isVideo, true)
        XCTAssertEqual(parsed.linkPreview?.showsAboveText, true)
    }

    func testBrokenPreviewKeepsTheMessage() {
        let wire = #"{"t":"text","c":"still readable","lp":{"u":"javascript:alert(1)"}}"#
        let parsed = MessageTextPayload.parse(wire)
        XCTAssertEqual(parsed.body, "still readable")
        XCTAssertNil(parsed.linkPreview)
    }

    // MARK: - Media envelope (large image)

    func testLinkMediaPayloadRoundTrip() throws {
        let payload = MediaMessagePayload(
            t: MediaMessagePayload.kindLink,
            mime: "image/jpeg",
            w: 1200,
            h: 630,
            k: "a2V5",
            c: "Route: komoot.com/tour/1398273",
            s: 48_000,
            lp: sample()
        )
        let parsed = try XCTUnwrap(MediaMessagePayload.parse(try payload.encoded()))
        XCTAssertTrue(parsed.isLink)
        XCTAssertFalse(parsed.isImage)
        XCTAssertFalse(parsed.isVoice)
        XCTAssertFalse(parsed.isVideo)
        XCTAssertEqual(parsed.lp, sample())
        XCTAssertEqual(parsed.c, "Route: komoot.com/tour/1398273")
    }

    func testLinkPayloadWithoutPreviewIsNotALink() throws {
        let raw = #"{"t":"link","mime":"image/jpeg","w":4,"h":3,"k":"a2V5","c":"text"}"#
        let parsed = try XCTUnwrap(MediaMessagePayload.parse(Data(raw.utf8)))
        XCTAssertFalse(parsed.isLink)
    }

    /// Payloads sealed before link previews existed decode exactly as before.
    func testLegacyPhotoPayloadHasNoPreview() throws {
        let raw = #"{"t":"image","mime":"image/jpeg","w":4,"h":3,"k":"a2V5","c":"caption"}"#
        let parsed = try XCTUnwrap(MediaMessagePayload.parse(Data(raw.utf8)))
        XCTAssertTrue(parsed.isImage)
        XCTAssertNil(parsed.lp)
    }

    // MARK: - Budget

    func testTextWireKeepsEverythingWhenSmall() {
        let result = MessagingController.textWire(body: "short", replyTo: nil, linkPreview: sample(thumbnail: thumbnail))
        XCTAssertEqual(result.sealedPreview, sample(thumbnail: thumbnail))
    }

    func testTextWireDropsThumbnailThenPreviewForLongMessages() {
        let bigThumb = Data(repeating: 7, count: 5 * 1024)
        let mid = String(repeating: "a", count: 7 * 1024)
        let trimmed = MessagingController.textWire(body: mid, replyTo: nil, linkPreview: sample(thumbnail: bigThumb))
        XCTAssertNotNil(trimmed.sealedPreview)
        XCTAssertNil(trimmed.sealedPreview?.thumbnail)

        let huge = String(repeating: "a", count: 13 * 1024)
        let dropped = MessagingController.textWire(body: huge, replyTo: nil, linkPreview: sample())
        XCTAssertNil(dropped.sealedPreview)
        XCTAssertEqual(dropped.wire, huge)
    }

    // MARK: - Local store

    func testStoredMessageKeepsPreview() throws {
        let message = MessagingController.ChatMessage(
            id: UUID(),
            peerUserID: UUID(),
            senderUserID: UUID(),
            text: "Route: komoot.com/tour/1398273",
            createdAt: Date(timeIntervalSince1970: 1_700_000_000),
            isMine: true,
            deleted: false,
            linkPreview: sample(thumbnail: thumbnail)
        )
        let stored = LocalMessageStore.StoredMessage.from(message)
        let data = try JSONEncoder().encode(stored)
        let decoded = try JSONDecoder().decode(LocalMessageStore.StoredMessage.self, from: data)
        XCTAssertEqual(decoded.linkPreview, sample(thumbnail: thumbnail))
    }

    func testStoredMessageWithoutPreviewStillDecodes() throws {
        let legacy = #"{"u":"https://example.com"}"#
        let preview = try JSONDecoder().decode(LinkPreview.self, from: Data(legacy.utf8))
        XCTAssertEqual(preview.url, "https://example.com")
        XCTAssertFalse(preview.isVideo)
        XCTAssertFalse(preview.showsAboveText)
    }
}
