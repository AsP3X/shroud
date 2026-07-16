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

    /// Downscales and re-encodes as JPEG for chat (max edge 1920, quality ~0.82).
    static func jpegData(from image: UIImage, maxEdge: CGFloat = 1920, quality: CGFloat = 0.82) throws -> (
        data: Data,
        width: Int,
        height: Int
    ) {
        let scaled = scaledImage(image, maxEdge: maxEdge)
        guard let data = scaled.jpegData(compressionQuality: quality) else {
            throw MediaError.imageEncodeFailed
        }
        let w = Int(scaled.size.width.rounded())
        let h = Int(scaled.size.height.rounded())
        return (data, max(w, 1), max(h, 1))
    }

    private static func scaledImage(_ image: UIImage, maxEdge: CGFloat) -> UIImage {
        let size = image.size
        let longest = max(size.width, size.height)
        guard longest > maxEdge, longest > 0 else { return image }
        let scale = maxEdge / longest
        let newSize = CGSize(width: size.width * scale, height: size.height * scale)
        let renderer = UIGraphicsImageRenderer(size: newSize)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: newSize))
        }
    }
}
