import CryptoKit
import Foundation
import ImageIO
import UIKit
import UniformTypeIdentifiers

/// Where a photo about to be sent came from.
///
/// The distinction matters for quality: `fileData` still holds the library's original
/// encoded bytes, so an "Original" send can ship them untouched. A `UIImage` has already
/// been decoded (camera capture, legacy call sites) and must be re-encoded.
nonisolated enum MediaImageSource: Sendable {
    /// Untouched encoded bytes from the photo library / picker.
    case fileData(Data)
    /// An in-memory image with no file representation (camera capture).
    case image(UIImage)
}

/// The bytes that actually go on the wire, plus what the recipient needs to render them.
nonisolated struct EncodedImage: Sendable {
    let data: Data
    let width: Int
    let height: Int
    /// Real payload type — `image/heic` when an original iPhone photo passed through untouched.
    let mime: String
}

/// Encrypts/decrypts media blobs (AES-GCM) and prepares image payloads for chat.
///
/// Nothing here touches UI state, and encoding a 48 MP photo has no business on the main
/// actor — so the whole type stays off it.
nonisolated enum MediaCrypto {
    enum MediaError: Error {
        case imageEncodeFailed
        case invalidKey
        case decryptFailed
    }

    /// Random 32-byte AES key + AES-GCM sealed combined blob (nonce||ciphertext||tag).
    static func sealFile(_ plaintext: Data) throws -> (key: Data, sealed: Data) {
        let key = SymmetricKey(size: .bits256)
        let sealed = try AES.GCM.seal(plaintext, using: key)
        guard let combined = sealed.combined else { throw MediaError.decryptFailed }
        let keyData = key.withUnsafeBytes { Data($0) }
        return (keyData, combined)
    }

    static func openFile(sealed: Data, keyData: Data) throws -> Data {
        guard keyData.count == 32 else { throw MediaError.invalidKey }
        let key = SymmetricKey(data: keyData)
        let box = try AES.GCM.SealedBox(combined: sealed)
        return try AES.GCM.open(box, using: key)
    }

    // MARK: - Outbound images

    /// Largest payload we will pass through untouched.
    ///
    /// The API rejects sealed blobs over 25 MiB and AES-GCM adds 28 bytes, so leave headroom.
    /// Anything bigger (ProRAW, panoramas) falls back to the re-encode path.
    private static let passthroughByteLimit = 24 * 1024 * 1024

    /// Container formats every client decodes natively, so the original file can ship as-is.
    private static let passthroughTypes: [UTType] = [.jpeg, .png, .heic, .heif]

    /// Prepares an image for sending.
    ///
    /// Human: "Original" means *original* — when the source is a library file in a format we can
    /// ship as-is, the exact bytes go on the wire. Decoding and re-encoding, even at JPEG
    /// quality 1.0, is a generation loss: it resamples through a bitmap, clips Display P3 to
    /// whatever the render format is, drops the gain map, and usually *inflates* the file.
    ///
    /// - Parameters:
    ///   - maxEdge: Longest pixel edge cap applied only on the re-encode path.
    ///   - compression: JPEG quality 0…1 on the re-encode path.
    ///   - allowsPassthrough: When true, library originals within the size limit are sent verbatim.
    static func encode(
        _ source: MediaImageSource,
        maxEdge: CGFloat,
        compression: CGFloat,
        allowsPassthrough: Bool
    ) throws -> EncodedImage {
        if allowsPassthrough,
           case let .fileData(data) = source,
           let untouched = passthrough(data)
        {
            return untouched
        }

        let decoded = try decodedImage(from: source, maxEdge: maxEdge)
        let scaled = scaledImage(decoded, maxEdgePixels: maxEdge)
        let quality = min(1, max(0.05, compression))
        guard let data = scaled.jpegData(compressionQuality: quality) else {
            throw MediaError.imageEncodeFailed
        }
        let width = Int((scaled.size.width * scaled.scale).rounded())
        let height = Int((scaled.size.height * scaled.scale).rounded())
        return EncodedImage(
            data: data,
            width: max(width, 1),
            height: max(height, 1),
            mime: UTType.jpeg.preferredMIMEType ?? "image/jpeg"
        )
    }

    /// Container type of already-encoded bytes, for re-sending without touching them.
    static func mimeType(for data: Data) -> String {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let identifier = CGImageSourceGetType(source) as String?,
              let type = UTType(identifier),
              let mime = type.preferredMIMEType
        else { return "image/jpeg" }
        return mime
    }

    /// Pixel dimensions of encoded bytes, read from metadata without decoding the image.
    static func pixelSize(for data: Data) -> (width: Int, height: Int)? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        return pixelSize(of: source)
    }

    /// The source decoded to a full-resolution `UIImage`, for baking edits into before sending.
    static func fullResolutionImage(from source: MediaImageSource, maxEdge: CGFloat) -> UIImage? {
        try? decodedImage(from: source, maxEdge: maxEdge)
    }

    /// A screen-sized preview, decoded straight out of ImageIO.
    ///
    /// Human: Compose and the viewer only ever show a few million pixels. Decoding a 48 MP
    /// original into a `UIImage` just to draw it at 1290 pt wide costs ~200 MB of backing store.
    static func previewImage(from data: Data, maxEdge: CGFloat) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else {
            return UIImage(data: data)
        }
        return downsampled(source, maxEdge: maxEdge) ?? UIImage(data: data)
    }

    /// Hard cap for `MediaMessagePayload.th` (JPEG bytes, not Base64).
    ///
    /// Server rejects message envelopes over 64 KiB. v2 dual-seal and v3 (DR + self box)
    /// each encrypt the full payload twice, so a large preview makes ciphertext fail with
    /// "ciphertext must decode to 1–65536 bytes". Keep the thumb tiny.
    static let maxEnvelopePreviewBytes = 6 * 1024

    /// Tiny JPEG for the chat bubble / payload (`MediaMessagePayload.th`).
    ///
    /// Recipients show this until they explicitly download the full media blob.
    static func chatPreviewJPEG(
        from data: Data,
        maxEdge: CGFloat = 160,
        quality: CGFloat = 0.42
    ) -> Data? {
        var edge = maxEdge
        var q = quality
        for _ in 0 ..< 5 {
            guard let image = previewImage(from: data, maxEdge: edge),
                  let jpeg = image.jpegData(compressionQuality: min(0.85, max(0.15, q)))
            else { return nil }
            if jpeg.count <= maxEnvelopePreviewBytes {
                return jpeg
            }
            edge = max(80, edge * 0.7)
            q = max(0.15, q - 0.08)
        }
        // Last resort: return the smallest attempt even if slightly over — caller may drop `th`.
        return previewImage(from: data, maxEdge: 80)?
            .jpegData(compressionQuality: 0.15)
    }

    /// Human-readable size for the Telegram-style download chip.
    static func byteCountLabel(_ bytes: Int) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useKB, .useMB, .useGB]
        formatter.countStyle = .file
        formatter.includesUnit = true
        formatter.isAdaptive = true
        return formatter.string(fromByteCount: Int64(max(0, bytes)))
    }

    // MARK: - Encoding internals

    /// Returns the source bytes verbatim when they are already a shippable original.
    private static func passthrough(_ data: Data) -> EncodedImage? {
        guard data.count <= passthroughByteLimit,
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let identifier = CGImageSourceGetType(source) as String?,
              let type = UTType(identifier),
              passthroughTypes.contains(where: { type.conforms(to: $0) }),
              let size = pixelSize(of: source),
              let mime = type.preferredMIMEType
        else { return nil }

        return EncodedImage(data: data, width: size.width, height: size.height, mime: mime)
    }

    private static func pixelSize(of source: CGImageSource) -> (width: Int, height: Int)? {
        guard let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int
        else { return nil }

        // EXIF orientations 5…8 swap the axes — report what the recipient will actually draw.
        let orientation = properties[kCGImagePropertyOrientation] as? Int ?? 1
        return (5 ... 8).contains(orientation) ? (height, width) : (width, height)
    }

    private static func decodedImage(from source: MediaImageSource, maxEdge: CGFloat) throws -> UIImage {
        switch source {
        case let .image(image):
            return image
        case let .fileData(data):
            if let imageSource = CGImageSourceCreateWithData(data as CFData, nil),
               let downsampled = downsampled(imageSource, maxEdge: maxEdge)
            {
                return downsampled
            }
            guard let image = UIImage(data: data) else { throw MediaError.imageEncodeFailed }
            return image
        }
    }

    /// ImageIO-side downsample — never materialises the full-resolution bitmap.
    /// `kCGImageSourceCreateThumbnailWithTransform` bakes EXIF orientation in, so the result is `.up`.
    private static func downsampled(_ source: CGImageSource, maxEdge: CGFloat) -> UIImage? {
        let cap = maxEdge.isFinite ? max(1, Int(maxEdge)) : 16_384
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: cap,
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            return nil
        }
        return UIImage(cgImage: cgImage)
    }

    /// Scales so the longest **pixel** edge is ≤ `maxEdgePixels`, fixing orientation.
    ///
    /// An upright image already inside the cap is returned untouched — redrawing it would only
    /// cost a resample and, on a plain render format, a trip through sRGB.
    private static func scaledImage(_ image: UIImage, maxEdgePixels: CGFloat) -> UIImage {
        let pixelW = image.size.width * image.scale
        let pixelH = image.size.height * image.scale
        let longest = max(pixelW, pixelH)
        let needsScaling = maxEdgePixels.isFinite && longest > maxEdgePixels && longest > 0

        if !needsScaling, image.imageOrientation == .up {
            return image
        }

        let targetW: CGFloat
        let targetH: CGFloat
        if needsScaling {
            let factor = maxEdgePixels / longest
            targetW = (pixelW * factor).rounded(.down)
            targetH = (pixelH * factor).rounded(.down)
        } else {
            targetW = pixelW.rounded(.down)
            targetH = pixelH.rounded(.down)
        }

        // `.preferred()` keeps the device's wide-gamut range; the plain initialiser clamps to sRGB.
        let format = UIGraphicsImageRendererFormat.preferred()
        format.scale = 1 // work in absolute pixels
        format.opaque = true
        let size = CGSize(width: max(targetW, 1), height: max(targetH, 1))
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        return renderer.image { _ in
            // Drawing through UIImage applies orientation correctly.
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }
}
