import SwiftUI
import XCTest

@testable import shroud

/// Link detection must underline the same characters on the phone and in the browser. These
/// vectors are the same strings `web/src/links.selftest.ts` checks; offsets are UTF-16 units.
@MainActor
final class LinkDetectorTests: XCTestCase {
    private struct Vector {
        let text: String
        /// (UTF-16 location, UTF-16 length, URL) per expected link.
        let links: [(Int, Int, String)]
    }

    private let vectors: [Vector] = [
        Vector(text: "Plain text, no links.", links: []),
        Vector(text: "see https://example.com/path?q=1.", links: [(4, 28, "https://example.com/path?q=1")]),
        Vector(text: "www.example.org", links: [(0, 15, "https://www.example.org")]),
        Vector(text: "Route: komoot.com/tour/1398273", links: [(7, 23, "https://komoot.com/tour/1398273")]),
        Vector(
            text: "(see en.wikipedia.org/wiki/Foo_(bar))",
            links: [(5, 31, "https://en.wikipedia.org/wiki/Foo_(bar)")]
        ),
        Vector(text: "mail bob@example.com please", links: [(5, 15, "mailto:bob@example.com")]),
        Vector(text: "Ende.Da geht es weiter", links: []),
        Vector(text: "file.txt and v1.2.3", links: []),
        Vector(text: "HTTPS://Example.COM", links: [(0, 19, "HTTPS://Example.COM")]),
        Vector(text: "two links: a.com and https://b.org/x", links: [(11, 5, "https://a.com"), (21, 15, "https://b.org/x")]),
        Vector(text: "user@host (no tld)", links: []),
        Vector(text: "https://", links: []),
        Vector(text: "end of sentence www.test.de!", links: [(16, 11, "https://www.test.de")]),
        Vector(text: "\"https://quoted.com/a\"", links: [(1, 20, "https://quoted.com/a")]),
        Vector(text: "emoji 👍https://x.io", links: [(8, 12, "https://x.io")]),
        Vector(text: "a.b.c and example.com.", links: [(10, 11, "https://example.com")]),
        Vector(
            text: "localhost:3000 and http://localhost:3000/x",
            links: [(19, 23, "http://localhost:3000/x")]
        ),
        Vector(text: "Link:https://colon.com", links: [(5, 17, "https://colon.com")]),
    ]

    func testSharedVectors() {
        for vector in vectors {
            let found = LinkDetector.links(in: vector.text)
            XCTAssertEqual(found.count, vector.links.count, "count for \(vector.text)")
            for (link, expected) in zip(found, vector.links) {
                XCTAssertEqual(link.range.location, expected.0, "location in \(vector.text)")
                XCTAssertEqual(link.range.length, expected.1, "length in \(vector.text)")
                XCTAssertEqual(link.url.absoluteString, expected.2, "url in \(vector.text)")
            }
        }
    }

    func testEmailIsTappableButNeverPreviewed() {
        let text = "write bob@example.com or see example.com"
        XCTAssertEqual(LinkDetector.links(in: text).first?.isEmail, true)
        XCTAssertEqual(LinkDetector.firstPreviewableURL(in: text)?.absoluteString, "https://example.com")
    }

    func testOnlyEmailMeansNothingToPreview() {
        XCTAssertNil(LinkDetector.firstPreviewableURL(in: "bob@example.com"))
    }

    func testInternationalDomainIsFound() {
        let links = LinkDetector.links(in: "Visit straße.de today")
        XCTAssertEqual(links.count, 1)
        XCTAssertEqual(links.first?.range, NSRange(location: 6, length: 9))
    }

    func testMessageLinkTextMarksLinks() {
        let attributed = MessageLinkText.attributed("see example.com now", isMine: false)
        let linkRuns = attributed.runs.filter { $0.link != nil }
        XCTAssertEqual(linkRuns.count, 1)
        XCTAssertEqual(linkRuns.first?.link?.absoluteString, "https://example.com")
        XCTAssertEqual(String(attributed[linkRuns[0].range].characters), "example.com")
    }

    func testOutgoingLinksAreUnderlined() {
        let attributed = MessageLinkText.attributed("example.com", isMine: true)
        XCTAssertEqual(attributed.runs.first?.swiftUI.underlineStyle, .single)
        let incoming = MessageLinkText.attributed("example.com", isMine: false)
        XCTAssertNil(incoming.runs.first?.swiftUI.underlineStyle)
    }

    /// Differentiate Without Color: incoming links get the underline too.
    func testIncomingLinksAreUnderlinedWhenAsked() {
        let incoming = MessageLinkText.attributed("example.com", isMine: false, underlined: true)
        XCTAssertEqual(incoming.runs.first?.swiftUI.underlineStyle, .single)
    }
}
