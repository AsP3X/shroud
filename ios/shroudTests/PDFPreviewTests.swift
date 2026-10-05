import CoreGraphics
import CryptoKit
import Foundation
import Testing
import UIKit
@testable import shroud

/// The PDF preview card and its payload: `pg`, the page-count meta line, the 2:1 crop, the
/// sender's 6 KB `th`, and opening a blob into memory for the card (`docs/file-sharing.md` §10).
struct PDFPreviewTests {
    // MARK: - Payload

    @Test
    func thePageCountTravelsAsPg() throws {
        let payload = MediaMessagePayload(
            t: MediaMessagePayload.kindFile,
            mime: "application/pdf",
            w: 480,
            h: 240,
            k: "a2V5",
            s: 12,
            n: "report.pdf",
            pg: 12
        )
        let object = try #require(try JSONSerialization.jsonObject(with: payload.encoded()) as? [String: Any])
        #expect(object["pg"] as? Int == 12)
        #expect(MediaMessagePayload.parse(try payload.encoded())?.pg == 12)
    }

    @Test(arguments: [#""pg":0"#, #""pg":-3"#, #""pg":null"#, #""pg":"x""#])
    func aPageCountBelowOneIsDropped(field: String) throws {
        let json = #"{"t":"file","n":"a.pdf","mime":"application/pdf","k":"a2V5","s":1,\#(field)}"#
        let payload = try #require(MediaMessagePayload.parse(Data(json.utf8)))
        #expect(payload.pg == nil)
    }

    @Test
    func payloadsWithoutAPageCountLeaveItOut() throws {
        let json = #"{"t":"file","n":"a.pdf","mime":"application/pdf","k":"a2V5","s":1}"#
        let payload = try #require(MediaMessagePayload.parse(Data(json.utf8)))
        #expect(payload.pg == nil)
        let object = try #require(try JSONSerialization.jsonObject(with: payload.encoded()) as? [String: Any])
        #expect(object["pg"] == nil)
    }

    // MARK: - Meta line

    @Test
    func theMetaLineLeadsWithThePageCount() throws {
        let pdf = try #require(SharedFile.type(forName: "a.pdf"))
        #expect(SharedFile.metaLine(byteCount: 2_400_000, type: pdf, pageCount: 12).hasPrefix("12 pages · "))
        #expect(SharedFile.metaLine(byteCount: 2_400_000, type: pdf, pageCount: 1).hasPrefix("1 page · "))
        #expect(SharedFile.metaLine(byteCount: 2_400_000, type: pdf, pageCount: 12).hasSuffix(" · PDF"))
        #expect(!SharedFile.metaLine(byteCount: 2_400_000, type: pdf).contains("page"))
        #expect(SharedFile.metaLine(byteCount: nil, type: pdf, pageCount: 3) == "3 pages · PDF")
        #expect(SharedFile.metaLine(byteCount: nil, type: pdf) == "PDF")
    }

    // MARK: - Crop

    @Test
    func aPortraitPageIsCutToTwoToOne() {
        #expect(PDFPagePreview.cropHeight(pageSize: CGSize(width: 595, height: 842), width: 480) == 240)
    }

    @Test
    func aPageWiderThanTwoToOneKeepsItsOwnHeight() {
        #expect(PDFPagePreview.cropHeight(pageSize: CGSize(width: 1000, height: 300), width: 480) == 144)
    }

    @Test
    func aDegeneratePageHasNoCrop() {
        #expect(PDFPagePreview.cropHeight(pageSize: .zero, width: 480) == 0)
    }

    // MARK: - Rendering

    @Test
    func theSenderPreviewFitsTheEnvelopeAndCountsPages() throws {
        let url = try Self.makePDF(pages: 5, size: CGSize(width: 595, height: 842))
        defer { try? FileManager.default.removeItem(at: url) }
        let preview = try #require(PDFPagePreview.makeSenderPreview(for: url))
        #expect(preview.pageCount == 5)
        #expect(preview.thumbnail.jpeg.count <= MediaCrypto.maxEnvelopePreviewBytes)
        #expect(preview.thumbnail.width >= PDFPagePreview.senderMinWidth)
        #expect(preview.thumbnail.width <= PDFPagePreview.senderWidth)
        // 2:1, give or take rounding after a shrink.
        #expect(abs(preview.thumbnail.width - preview.thumbnail.height * 2) <= 2)
    }

    @Test
    func aLockedPDFHasNoPreview() throws {
        let url = try Self.makePDF(pages: 2, size: CGSize(width: 595, height: 842), password: "secret")
        defer { try? FileManager.default.removeItem(at: url) }
        #expect(PDFPagePreview.makeSenderPreview(for: url) == nil)
    }

    @Test
    func theLocalCardIsDrawnFromTheFile() throws {
        let url = try Self.makePDF(pages: 3, size: CGSize(width: 960, height: 540))
        defer { try? FileManager.default.removeItem(at: url) }
        let card = try #require(PDFPagePreview.card(from: Data(contentsOf: url), pixelWidth: 876))
        #expect(card.pageCount == 3)
        #expect(card.image.cgImage?.width == 876)
        #expect(card.image.cgImage?.height == 438)
    }

    @Test
    func aCardIsNeverDrawnFromSomethingThatIsntAPDF() {
        #expect(PDFPagePreview.card(from: Data("hello, not a pdf".utf8), pixelWidth: 300) == nil)
    }

    // MARK: - Opening into memory

    @Test
    func aBlobOpensIntoMemoryWithEveryCheck() throws {
        let plaintext = Data((0 ..< 200_000).map { UInt8($0 % 251) })
        let key = FileBlob.makeKey()
        let sealed = try FileBlob.seal(plaintext, key: key, noncePrefix: FileBlob.makeNoncePrefix())
        let blob = FileManager.default.temporaryDirectory.appendingPathComponent("pdfpreview-\(UUID().uuidString).shrf")
        try sealed.write(to: blob)
        defer { try? FileManager.default.removeItem(at: blob) }

        #expect(try FileBlob.openIntoMemory(from: blob, key: key, plaintextSize: 200_000) == plaintext)
        #expect(throws: FileBlob.BlobError.wrongLength) {
            try FileBlob.openIntoMemory(from: blob, key: key, plaintextSize: 199_999)
        }
        #expect(throws: FileBlob.BlobError.tampered) {
            try FileBlob.openIntoMemory(from: blob, key: FileBlob.makeKey(), plaintextSize: 200_000)
        }
    }

    // MARK: - Helpers

    static func makePDF(pages: Int, size: CGSize, password: String? = nil) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("pdfpreview-\(UUID().uuidString).pdf")
        var box = CGRect(origin: .zero, size: size)
        var info: [CFString: Any] = [:]
        if let password {
            info[kCGPDFContextUserPassword] = password
            info[kCGPDFContextOwnerPassword] = password
        }
        let context = try #require(CGContext(url as CFURL, mediaBox: &box, info as CFDictionary))
        for page in 0 ..< pages {
            context.beginPDFPage(nil)
            context.setFillColor(UIColor(white: CGFloat(page) / CGFloat(max(1, pages)), alpha: 1).cgColor)
            context.fill(CGRect(x: 40, y: size.height - 120, width: size.width - 80, height: 60))
            for line in 0 ..< 30 {
                context.setFillColor(UIColor.darkGray.cgColor)
                context.fill(CGRect(x: 40, y: size.height - 160 - CGFloat(line) * 14, width: size.width - 80 - CGFloat(line % 7) * 20, height: 6))
            }
            context.endPDFPage()
        }
        context.closePDF()
        return url
    }
}
