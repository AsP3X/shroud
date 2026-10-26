import Foundation
import XCTest

@testable import shroud

/// The HTTP/1.1 the link-preview fetcher speaks over its own connection (security audit L17):
/// the request it sends, and how it reads status lines, headers, body framing, chunked bodies
/// and redirects. All pure; no network.
final class LinkPreviewHTTPTests: XCTestCase {
    /// Parses `text` the way the client does: up to the blank line, then the head.
    private func head(_ text: String) -> LinkPreviewHTTP.ResponseHead? {
        let bytes = Array(text.utf8)
        guard let end = LinkPreviewHTTP.headEnd(in: bytes) else { return nil }
        return LinkPreviewHTTP.parseHead(Array(bytes[..<end.headLength]))
    }

    private func decodeChunked(_ text: String) throws -> (body: String, finished: Bool) {
        var decoder = LinkPreviewHTTP.ChunkedDecoder()
        let body = try decoder.decode(Data(text.utf8))
        return (String(decoding: body, as: UTF8.self), decoder.isFinished)
    }

    // MARK: - Request

    func testRequestIsOneMinimalGET() throws {
        let url = URL(string: "https://Example.com./a/b%20c?q=1&r=%C3%A4#section")!
        let request = try XCTUnwrap(LinkPreviewHTTP.request(
            for: url,
            headers: [(name: "User-Agent", value: "UA/1"), (name: "Accept", value: "text/html")]
        ))
        XCTAssertEqual(
            String(decoding: request, as: UTF8.self),
            "GET /a/b%20c?q=1&r=%C3%A4 HTTP/1.1\r\n"
                + "Host: example.com\r\n"
                + "User-Agent: UA/1\r\n"
                + "Accept: text/html\r\n"
                + "Accept-Encoding: identity\r\n"
                + "Connection: close\r\n"
                + "\r\n"
        )
    }

    func testRequestTargetAndHost() {
        XCTAssertEqual(LinkPreviewHTTP.requestTarget(of: URL(string: "https://example.com")!), "/")
        XCTAssertEqual(LinkPreviewHTTP.requestTarget(of: URL(string: "https://example.com?x=1")!), "/?x=1")
        XCTAssertEqual(LinkPreviewHTTP.hostName(of: URL(string: "https://WWW.Example.com:443/")!), "www.example.com")
    }

    func testRequestRefusesHeaderInjection() {
        let url = URL(string: "https://example.com/")!
        XCTAssertNil(LinkPreviewHTTP.request(for: url, headers: [(name: "Accept", value: "a\r\nX-Evil: 1")]))
        XCTAssertNil(LinkPreviewHTTP.request(for: url, headers: [(name: "Accept", value: "a\nb")]))
        XCTAssertNil(LinkPreviewHTTP.request(for: url, headers: [(name: "Bad Name", value: "x")]))
        XCTAssertNil(LinkPreviewHTTP.request(for: url, headers: [(name: "", value: "x")]))
    }

    // MARK: - Status line and headers

    func testStatusLines() {
        XCTAssertEqual(LinkPreviewHTTP.statusCode(ofStatusLine: "HTTP/1.1 200 OK"), 200)
        XCTAssertEqual(LinkPreviewHTTP.statusCode(ofStatusLine: "HTTP/1.0 301 Moved Permanently"), 301)
        XCTAssertEqual(LinkPreviewHTTP.statusCode(ofStatusLine: "HTTP/1.1 204"), 204)
        XCTAssertEqual(LinkPreviewHTTP.statusCode(ofStatusLine: "HTTP/1.1 404 "), 404)
        for bad in [
            "",
            "HTTP/2 200",
            "HTTP/1.1 20 OK",
            "HTTP/1.1 2000 OK",
            "HTTP/1.1 099 Low",
            "HTTP/1.1 600 High",
            "HTTP/1.1  200 OK",
            "ICY 200 OK",
            "http/1.1 200 OK",
        ] {
            XCTAssertNil(LinkPreviewHTTP.statusCode(ofStatusLine: bad), bad)
        }
    }

    func testHeadEndsAtTheBlankLine() throws {
        let bytes = Array("HTTP/1.1 200 OK\r\nA: b\r\n\r\nbody".utf8)
        let end = try XCTUnwrap(LinkPreviewHTTP.headEnd(in: bytes))
        XCTAssertEqual(String(decoding: bytes[end.bodyStart...], as: UTF8.self), "body")
        XCTAssertEqual(String(decoding: bytes[..<end.headLength], as: UTF8.self), "HTTP/1.1 200 OK\r\nA: b\r")

        let bare = Array("HTTP/1.1 200 OK\nA: b\n\nbody".utf8)
        let bareEnd = try XCTUnwrap(LinkPreviewHTTP.headEnd(in: bare))
        XCTAssertEqual(String(decoding: bare[bareEnd.bodyStart...], as: UTF8.self), "body")

        XCTAssertNil(LinkPreviewHTTP.headEnd(in: Array("HTTP/1.1 200 OK\r\nA: b\r\n".utf8)))
    }

    func testHeadersAreParsed() throws {
        let parsed = try XCTUnwrap(head(
            "HTTP/1.1 200 OK\r\n"
                + "Content-Type:  text/html; charset=utf-8 \r\n"
                + "Set-Cookie: a=1\r\n"
                + "set-cookie: b=2\r\n"
                + "X-Empty:\r\n"
                + "\r\n"
        ))
        XCTAssertEqual(parsed.status, 200)
        XCTAssertEqual(parsed.value(named: "content-type"), "text/html; charset=utf-8")
        XCTAssertEqual(parsed.value(named: "Content-Type"), "text/html; charset=utf-8")
        XCTAssertEqual(parsed.values(named: "set-cookie"), ["a=1", "b=2"])
        XCTAssertEqual(parsed.value(named: "x-empty"), "")
        XCTAssertNil(parsed.value(named: "location"))

        let bare = try XCTUnwrap(head("HTTP/1.1 301 Moved\nLocation: /x\n\n"))
        XCTAssertEqual(bare.status, 301)
        XCTAssertEqual(bare.value(named: "location"), "/x")
    }

    func testMalformedHeadsAreRefused() {
        for bad in [
            "HTTP/1.1 200 OK\r\nA: b\r\n folded\r\n\r\n",
            "HTTP/1.1 200 OK\r\nNo colon here\r\n\r\n",
            "HTTP/1.1 200 OK\r\nBad Name: x\r\n\r\n",
            "HTTP/1.1 200 OK\r\nName : x\r\n\r\n",
            "HTTP/1.1 200 OK\r\n: no name\r\n\r\n",
            "HTTP/1.1 200 OK\r\nA: b\rc\r\n\r\n",
            "HTTP/2 200\r\n\r\n",
            "garbage\r\n\r\n",
        ] {
            XCTAssertNil(head(bad), bad.debugDescription)
        }
        let tooMany = "HTTP/1.1 200 OK\r\n"
            + String(repeating: "A: b\r\n", count: LinkPreviewHTTP.maxHeaderCount + 1) + "\r\n"
        XCTAssertNil(head(tooMany))
    }

    // MARK: - Body framing

    func testBodyFraming() {
        func framing(_ headers: String, status: String = "200 OK") -> LinkPreviewHTTP.BodyFraming? {
            guard let parsed = head("HTTP/1.1 \(status)\r\n\(headers)\r\n") else {
                XCTFail("head did not parse: \(headers)")
                return nil
            }
            return LinkPreviewHTTP.bodyFraming(of: parsed)
        }
        XCTAssertEqual(framing("Content-Length: 42\r\n"), .length(42))
        XCTAssertEqual(framing("Content-Length: 0\r\n"), .length(0))
        XCTAssertEqual(framing("Content-Length: 42, 42\r\n"), .length(42))
        XCTAssertEqual(framing("Content-Length: 42\r\nContent-Length: 42\r\n"), .length(42))
        XCTAssertEqual(framing("Transfer-Encoding: chunked\r\n"), .chunked)
        XCTAssertEqual(framing("Transfer-Encoding: Chunked\r\n"), .chunked)
        // Transfer-Encoding wins over Content-Length.
        XCTAssertEqual(framing("Transfer-Encoding: chunked\r\nContent-Length: 10\r\n"), .chunked)
        XCTAssertEqual(framing("Content-Type: text/html\r\n"), .untilClose)
        XCTAssertEqual(framing("Content-Encoding: identity\r\nContent-Length: 3\r\n"), .length(3))
        XCTAssertEqual(framing("Content-Length: 42\r\n", status: "204 No Content"), .empty)
        XCTAssertEqual(framing("", status: "304 Not Modified"), .empty)

        for refused in [
            "Content-Length: 42, 43\r\n",
            "Content-Length: 42\r\nContent-Length: 43\r\n",
            "Content-Length: -1\r\n",
            "Content-Length: +5\r\n",
            "Content-Length: 0x10\r\n",
            "Content-Length:\r\n",
            "Content-Length: 1234567890123456789\r\n",
            "Transfer-Encoding: gzip, chunked\r\n",
            "Transfer-Encoding: identity\r\n",
            "Content-Encoding: gzip\r\nContent-Length: 3\r\n",
            "Content-Encoding: br\r\n",
        ] {
            XCTAssertNil(framing(refused), refused.debugDescription)
        }
    }

    // MARK: - Chunked bodies

    private let wikipedia = "4\r\nWiki\r\n5;ext=1\r\npedia\r\nE\r\n in\r\n\r\nchunks.\r\n0\r\nTrailer: x\r\n\r\n"

    func testChunkedBodyIsDecoded() throws {
        let result = try decodeChunked(wikipedia)
        XCTAssertEqual(result.body, "Wikipedia in\r\n\r\nchunks.")
        XCTAssertTrue(result.finished)
    }

    func testChunkedBodyDecodesInAnyPieces() throws {
        var decoder = LinkPreviewHTTP.ChunkedDecoder()
        var body = Data()
        for byte in Array(wikipedia.utf8) {
            let piece = try decoder.decode(Data([byte]))
            body.append(piece)
        }
        XCTAssertEqual(String(decoding: body, as: UTF8.self), "Wikipedia in\r\n\r\nchunks.")
        XCTAssertTrue(decoder.isFinished)
        // Bytes after the last chunk are ignored.
        let extra = try decoder.decode(Data("5\r\nextra\r\n".utf8))
        XCTAssertEqual(extra, Data())
    }

    func testChunkedBodyWithBareLineEndsAndNoTrailers() throws {
        let result = try decodeChunked("3\nabc\n0\n\n")
        XCTAssertEqual(result.body, "abc")
        XCTAssertTrue(result.finished)
    }

    func testTruncatedChunkedBodyIsNotFinished() throws {
        let result = try decodeChunked("4\r\nWiki\r\n5\r\nped")
        XCTAssertEqual(result.body, "Wikiped")
        XCTAssertFalse(result.finished)
    }

    func testMalformedChunkedBodiesAreRefused() {
        for bad in [
            "zz\r\n",
            "-1\r\n",
            "+5\r\nhello\r\n",
            " 5\r\nhello\r\n",
            "\r\n",
            "4\r\nWikiXX\r\n",
            "1234567890abcdef0\r\n",
            String(repeating: "a", count: LinkPreviewHTTP.ChunkedDecoder.maxLineBytes + 2),
        ] {
            XCTAssertThrowsError(try decodeChunked(bad), bad.debugDescription) { error in
                XCTAssertEqual(error as? LinkPreviewHTTPError, .badResponse)
            }
        }
    }

    // MARK: - Redirects

    func testRedirectTargetsResolveAgainstTheAnsweringURL() throws {
        let page = URL(string: "https://example.com/a/b")!
        func target(_ text: String) -> String? {
            guard let parsed = head(text) else { return nil }
            return LinkPreviewHTTP.redirectTarget(of: parsed, from: page)?.absoluteString
        }
        XCTAssertEqual(target("HTTP/1.1 301 Moved\r\nLocation: /next?x=1\r\n\r\n"), "https://example.com/next?x=1")
        XCTAssertEqual(target("HTTP/1.1 302 Found\r\nLocation: other\r\n\r\n"), "https://example.com/a/other")
        XCTAssertEqual(target("HTTP/1.1 303 See Other\r\nLocation:  https://cdn.example.org/i.png \r\n\r\n"), "https://cdn.example.org/i.png")
        XCTAssertEqual(target("HTTP/1.1 307 Temporary\r\nLocation: //example.net/x\r\n\r\n"), "https://example.net/x")
        XCTAssertEqual(target("HTTP/1.1 308 Permanent\r\nLocation: http://example.org/\r\n\r\n"), "http://example.org/")

        XCTAssertNil(target("HTTP/1.1 200 OK\r\nLocation: /x\r\n\r\n"))
        XCTAssertNil(target("HTTP/1.1 300 Multiple\r\nLocation: /x\r\n\r\n"))
        XCTAssertNil(target("HTTP/1.1 304 Not Modified\r\nLocation: /x\r\n\r\n"))
        XCTAssertNil(target("HTTP/1.1 301 Moved\r\n\r\n"))
        XCTAssertNil(target("HTTP/1.1 301 Moved\r\nLocation:\r\n\r\n"))
        XCTAssertNil(target("HTTP/1.1 301 Moved\r\nLocation: /a\r\nLocation: /b\r\n\r\n"))
    }

    func testRedirectsAreHeldToTheFetchRules() throws {
        // The client runs every hop through `allowedTarget` and the address check again.
        let page = URL(string: "https://example.com/")!
        let upgraded = try XCTUnwrap(head("HTTP/1.1 301 Moved\r\nLocation: http://example.org/x\r\n\r\n"))
        let next = try XCTUnwrap(LinkPreviewHTTP.redirectTarget(of: upgraded, from: page))
        XCTAssertEqual(LinkPreviewFetcher.allowedTarget(next)?.absoluteString, "https://example.org/x")

        for blocked in ["https://192.168.1.1/", "https://localhost/", "https://example.com:8443/", "ftp://example.com/"] {
            let redirect = try XCTUnwrap(head("HTTP/1.1 302 Found\r\nLocation: \(blocked)\r\n\r\n"))
            let next = try XCTUnwrap(LinkPreviewHTTP.redirectTarget(of: redirect, from: page))
            XCTAssertNil(LinkPreviewFetcher.allowedTarget(next), blocked)
        }
    }
}
