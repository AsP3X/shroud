import XCTest

@testable import shroud

/// The sender builds previews from a page's `<head>`; these pin down what is read and what is
/// ignored, and the fetch rules that keep a pasted link from probing the local network.
final class LinkPageMetadataParserTests: XCTestCase {
    private let page = URL(string: "https://www.example.com/articles/42")!

    private func parse(_ html: String, contentType: String? = "text/html; charset=utf-8") -> LinkPageMetadata {
        LinkPageMetadataParser.parse(Data(html.utf8), pageURL: page, contentType: contentType)
    }

    func testOpenGraphWins() {
        let html = """
        <html><head>
        <title>Fallback title</title>
        <meta property="og:site_name" content="Example">
        <meta property="og:title" content="The real title">
        <meta name="description" content="Plain description">
        <meta property="og:description" content="OG description">
        <meta property="og:image" content="https://cdn.example.com/card.jpg">
        <meta property="og:image:width" content="1200"><meta property="og:image:height" content="630">
        </head><body></body></html>
        """
        let metadata = parse(html)
        XCTAssertEqual(metadata.siteName, "Example")
        XCTAssertEqual(metadata.title, "The real title")
        XCTAssertEqual(metadata.summary, "OG description")
        XCTAssertEqual(metadata.imageURL?.absoluteString, "https://cdn.example.com/card.jpg")
        XCTAssertEqual(metadata.imageWidth, 1200)
        XCTAssertEqual(metadata.imageHeight, 630)
        XCTAssertFalse(metadata.isVideo)
    }

    func testFallsBackToTwitterAndTitle() {
        let html = """
        <head><TITLE>Only a &amp; title</TITLE>
        <meta name="twitter:description" content='Single &#39;quoted&#39; &#x2014; ok'>
        <meta name=twitter:image content=/img/card.png></head>
        """
        let metadata = parse(html)
        XCTAssertEqual(metadata.title, "Only a & title")
        XCTAssertEqual(metadata.summary, "Single 'quoted' — ok")
        XCTAssertEqual(metadata.imageURL?.absoluteString, "https://www.example.com/img/card.png")
        XCTAssertNil(metadata.siteName)
    }

    func testVideoPagesAreFlagged() {
        XCTAssertTrue(parse(#"<head><meta property="og:type" content="video.other"><meta property="og:title" content="A"></head>"#).isVideo)
        XCTAssertTrue(parse(#"<head><meta property="og:video:url" content="https://x.com/v"><meta property="og:title" content="A"></head>"#).isVideo)
    }

    func testInsecureAndRelativeImagesAreResolvedToHTTPS() {
        let insecure = parse(#"<head><meta property="og:image" content="http://cdn.example.com/a.jpg"></head>"#)
        XCTAssertEqual(insecure.imageURL?.scheme, "https")
        let protocolRelative = parse(#"<head><meta property="og:image" content="//cdn.example.com/b.jpg"></head>"#)
        XCTAssertEqual(protocolRelative.imageURL?.absoluteString, "https://cdn.example.com/b.jpg")
        let dataURL = parse(#"<head><meta property="og:image" content="data:image/png;base64,AAAA"></head>"#)
        XCTAssertNil(dataURL.imageURL)
    }

    func testTagsAfterTheHeadAreIgnored() {
        let html = #"<head><meta property="og:title" content="Head"></head><body><meta property="og:description" content="Injected"></body>"#
        let metadata = parse(html)
        XCTAssertEqual(metadata.title, "Head")
        XCTAssertNil(metadata.summary)
    }

    func testMetadataTagIsNotMeta() {
        let metadata = parse(#"<head><metadata content="x"></metadata><meta property="og:title" content="Real"></head>"#)
        XCTAssertEqual(metadata.title, "Real")
    }

    func testLatin1PagesDecode() {
        let html = "<head><meta charset=\"iso-8859-1\"><meta property=\"og:title\" content=\"Gr\u{FC}\u{DF}e\"></head>"
        let data = html.data(using: .isoLatin1)!
        let metadata = LinkPageMetadataParser.parse(data, pageURL: page, contentType: "text/html")
        XCTAssertEqual(metadata.title, "Grüße")
    }

    func testEmptyPageHasNothing() {
        XCTAssertTrue(parse("<html><head></head><body>Hello</body></html>").isEmpty)
    }

    // MARK: - Fetch rules

    func testOnlyPublicHTTPSTargetsAreFetched() {
        XCTAssertEqual(
            LinkPreviewFetcher.allowedTarget(URL(string: "http://example.com/a")!)?.absoluteString,
            "https://example.com/a"
        )
        for blocked in [
            "https://localhost/a",
            "https://192.168.1.1/",
            "https://10.0.0.8:443/",
            "https://[::1]/",
            "https://printer.local/",
            "https://nas.home/",
            "https://intranet/",
            "https://example.com:8443/",
            "ftp://example.com/",
        ] {
            XCTAssertNil(LinkPreviewFetcher.allowedTarget(URL(string: blocked)!), blocked)
        }
    }

    func testResolvedPrivateAddressesAreNotFetched() {
        XCTAssertTrue(LinkPreviewFetcher.isGloballyRoutableIPv4(0x01010101))
        XCTAssertTrue(LinkPreviewFetcher.isGloballyRoutableIPv4(0x08080808))
        for blocked in [0x00000000, 0x0A000001, 0x7F000001, 0x64400001, 0xA9FE0001, 0xAC100001, 0xC0A80001, 0xE0000001, 0xFFFFFFFF] as [UInt32] {
            XCTAssertFalse(LinkPreviewFetcher.isGloballyRoutableIPv4(blocked), String(blocked, radix: 16))
        }
        for blocked in ["127.0.0.1", "10.1.2.3", "192.168.0.8", "172.16.5.5", "169.254.1.1", "0.0.0.0", "::1", "not-a-real-host.invalid"] {
            XCTAssertFalse(LinkPreviewFetcher.resolvesOnlyToPublicAddresses(blocked), blocked)
        }
        XCTAssertTrue(LinkPreviewFetcher.resolvesOnlyToPublicAddresses("1.1.1.1"))
        XCTAssertTrue(LinkPreviewFetcher.resolvesOnlyToPublicAddresses("8.8.8.8"))
    }
}
