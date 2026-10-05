import CoreGraphics
import Foundation
import Synchronization
import UIKit

/// The top of a PDF's first page, as the bubble's preview card shows it
/// (`docs/file-sharing.md` §10.1).
///
/// Human: The card is 2:1 and pinned to the page's top edge, so a page shows its heading the
/// way WhatsApp and Telegram show one. The sender squeezes that strip into the 6 KB `th`; once
/// the file is on this device the card is drawn again from the file itself, sharp at the
/// screen's scale.
/// Agent: Pure CoreGraphics, safe off the main actor. A locked (password) PDF, one without pages
/// or one CoreGraphics can't parse yields nil — callers then go without a preview.
nonisolated enum PDFPagePreview {
    /// Card width ÷ height.
    static let cardAspect: CGFloat = 2
    /// The sender's `th`: drawn at this width, shrunk until it fits 6 KB.
    static let senderWidth = 480
    static let senderMinWidth = 160
    /// A sender's preview that takes longer than this is skipped, so the send isn't held up.
    static let senderBudget: Duration = .seconds(2)
    /// The largest file the bubble decrypts into memory to draw its card.
    static let localRenderMaxBytes = 64 * 1024 * 1024

    struct SenderPreview: Sendable {
        let thumbnail: FilePreview.Thumbnail
        let pageCount: Int
    }

    /// An open, readable document: nil when it is locked by a password or has no pages.
    static func document(_ document: CGPDFDocument?) -> CGPDFDocument? {
        guard let document else { return nil }
        if !document.isUnlocked, !document.unlockWithPassword("") { return nil }
        guard document.numberOfPages > 0 else { return nil }
        return document
    }

    static func document(at url: URL) -> CGPDFDocument? {
        document(CGPDFDocument(url as CFURL))
    }

    static func document(data: Data) -> CGPDFDocument? {
        guard let provider = CGDataProvider(data: data as CFData) else { return nil }
        return document(CGPDFDocument(provider))
    }

    /// The page's size as it is shown: the crop box, turned by the page's rotation.
    static func displaySize(of page: CGPDFPage) -> CGSize {
        let box = page.getBoxRect(.cropBox).standardized
        let turned = (page.rotationAngle % 180 + 180) % 180 == 90
        return turned ? CGSize(width: box.height, height: box.width) : box.size
    }

    /// Pixel height of the card image for a page shown `width` px wide: the page's own height
    /// when it is wider than 2:1, else half the width.
    static func cropHeight(pageSize: CGSize, width: Int) -> Int {
        guard pageSize.width > 0, pageSize.height > 0, width > 0 else { return 0 }
        let fullHeight = CGFloat(width) * pageSize.height / pageSize.width
        let cardHeight = CGFloat(width) / cardAspect
        return max(1, Int(min(fullHeight, cardHeight).rounded()))
    }

    /// Page 1 on white, `width` px wide, cut to the card from the top.
    static func renderTop(of document: CGPDFDocument, width: Int) -> CGImage? {
        guard let page = document.page(at: 1) else { return nil }
        return render(page, width: width, height: cropHeight(pageSize: displaySize(of: page), width: width))
    }

    /// A whole page on white, `width` px wide (the viewer's page thumbnails).
    static func renderPage(_ number: Int, of document: CGPDFDocument, width: Int) -> CGImage? {
        guard let page = document.page(at: number) else { return nil }
        let size = displaySize(of: page)
        guard size.width > 0 else { return nil }
        return render(page, width: width, height: max(1, Int((CGFloat(width) * size.height / size.width).rounded())))
    }

    /// `page` scaled to `width` px, its top edge at the image's top, cut off below `height`.
    private static func render(_ page: CGPDFPage, width: Int, height: Int) -> CGImage? {
        let size = displaySize(of: page)
        guard width > 0, height > 0, size.width > 0,
              let context = CGContext(
                  data: nil,
                  width: width,
                  height: height,
                  bitsPerComponent: 8,
                  bytesPerRow: 0,
                  space: CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB(),
                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
              )
        else { return nil }
        context.setFillColor(UIColor.white.cgColor)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        context.interpolationQuality = .high
        let scale = CGFloat(width) / size.width
        // CoreGraphics counts from the bottom: lift the page so its top meets the image's top.
        context.translateBy(x: 0, y: CGFloat(height) - size.height * scale)
        context.scaleBy(x: scale, y: scale)
        // At 1:1 the transform only turns and places the box; it never scales up.
        context.concatenate(page.getDrawingTransform(.cropBox, rect: CGRect(origin: .zero, size: size), rotate: 0, preserveAspectRatio: true))
        context.clip(to: page.getBoxRect(.cropBox))
        context.drawPDFPage(page)
        return context.makeImage()
    }

    /// The sender's `th` and page count, or nil past `senderBudget`.
    ///
    /// Agent: The render can't be stopped half way; one that runs late finishes in the
    /// background and its result is dropped.
    static func senderPreview(for url: URL) async -> SenderPreview? {
        let work = Task.detached(priority: .userInitiated) { makeSenderPreview(for: url) }
        let once = Once()
        return await withCheckedContinuation { continuation in
            Task.detached {
                let result = await work.value
                if once.claim() { continuation.resume(returning: result) }
            }
            Task.detached {
                try? await Task.sleep(for: senderBudget)
                if once.claim() { continuation.resume(returning: nil) }
            }
        }
    }

    static func makeSenderPreview(for url: URL) -> SenderPreview? {
        guard let document = document(at: url),
              let strip = renderTop(of: document, width: senderWidth),
              let thumbnail = fitEnvelope(strip)
        else { return nil }
        return SenderPreview(thumbnail: thumbnail, pageCount: document.numberOfPages)
    }

    /// The strip as a JPEG within `MediaCrypto.maxEnvelopePreviewBytes`: lower quality first,
    /// then 0.8 of the width, never under `senderMinWidth`.
    static func fitEnvelope(_ strip: CGImage) -> FilePreview.Thumbnail? {
        var width = strip.width
        while width >= senderMinWidth {
            let height = max(1, Int((CGFloat(strip.height) * CGFloat(width) / CGFloat(strip.width)).rounded()))
            guard let scaled = width == strip.width ? strip : resize(strip, width: width, height: height) else { return nil }
            let image = UIImage(cgImage: scaled)
            for quality in [0.6, 0.45, 0.32] as [CGFloat] {
                if let jpeg = image.jpegData(compressionQuality: quality), jpeg.count <= MediaCrypto.maxEnvelopePreviewBytes {
                    return FilePreview.Thumbnail(jpeg: jpeg, width: scaled.width, height: scaled.height)
                }
            }
            width = Int(CGFloat(width) * 0.8)
        }
        return nil
    }

    private static func resize(_ image: CGImage, width: Int, height: Int) -> CGImage? {
        guard let context = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ) else { return nil }
        context.interpolationQuality = .high
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        return context.makeImage()
    }

    /// The bubble's card drawn from the file itself, and its page count.
    static func card(from data: Data, pixelWidth: Int) -> (image: UIImage, pageCount: Int)? {
        // §4's check first: a file that isn't a PDF never reaches the parser.
        guard let type = SharedFile.type(forName: "file.pdf"),
              SharedFile.contentMatches(type, header: data.prefix(SharedFile.contentCheckBytes)),
              let document = document(data: data),
              let image = renderTop(of: document, width: pixelWidth)
        else { return nil }
        return (UIImage(cgImage: image), document.numberOfPages)
    }

    /// Resumes a continuation exactly once across two racing tasks.
    private final class Once: Sendable {
        private let taken = Mutex(false)

        func claim() -> Bool {
            taken.withLock { taken in
                if taken { return false }
                taken = true
                return true
            }
        }
    }
}
