import CryptoKit
import Foundation
import UIKit

/// Encrypts/decrypts media blobs (AES-GCM) and prepares JPEG payloads for chat.
enum MediaCrypto {
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

    /// Downscales (in **pixels**) and re-encodes as JPEG for chat.
    /// - Parameters:
    ///   - maxEdge: Longest pixel edge cap. Pass a very large value (e.g. 16384) for original size.
    ///   - quality: JPEG compression 0…1. Use `1.0` for maximum quality (default for Original send).
    static func jpegData(from image: UIImage, maxEdge: CGFloat = 16_384, quality: CGFloat = 1.0) throws -> (
        data: Data,
        width: Int,
        height: Int
    ) {
        let scaled = scaledImage(image, maxEdgePixels: maxEdge)
        // Clamp quality into a valid JPEG range; 1.0 is full fidelity for Original.
        let q = min(1, max(0, quality))
        guard let data = scaled.jpegData(compressionQuality: q) else {
            throw MediaError.imageEncodeFailed
        }
        // Report pixel dimensions (scale may be 1 after pixel-space render).
        let w = Int((scaled.size.width * scaled.scale).rounded())
        let h = Int((scaled.size.height * scaled.scale).rounded())
        return (data, max(w, 1), max(h, 1))
    }

    /// Scales so the longest **pixel** edge is ≤ `maxEdgePixels`, fixing orientation.
    /// When the image is already within the cap, dimensions are preserved (no quality loss from resize).
    private static func scaledImage(_ image: UIImage, maxEdgePixels: CGFloat) -> UIImage {
        let pixelW = image.size.width * image.scale
        let pixelH = image.size.height * image.scale
        let longest = max(pixelW, pixelH)
        let targetW: CGFloat
        let targetH: CGFloat
        if maxEdgePixels.isFinite, longest > maxEdgePixels, longest > 0 {
            let factor = maxEdgePixels / longest
            targetW = (pixelW * factor).rounded(.down)
            targetH = (pixelH * factor).rounded(.down)
        } else {
            targetW = pixelW.rounded(.down)
            targetH = pixelH.rounded(.down)
        }
        let format = UIGraphicsImageRendererFormat()
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
