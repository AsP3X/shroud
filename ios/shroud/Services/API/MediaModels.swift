import Foundation

/// `POST /media/uploads` body.
struct CreateMediaUploadRequest: Encodable, Equatable, Sendable {
    let sizeBytes: Int64
    let contentType: String?

    enum CodingKeys: String, CodingKey {
        case sizeBytes = "size_bytes"
        case contentType = "content_type"
    }
}

/// `POST /media/uploads` response.
struct CreateMediaUploadResponse: Decodable, Equatable, Sendable {
    let mediaObjectId: UUID
    let uploadUrl: String
    let objectKey: String
    let expiresAt: Date

    enum CodingKeys: String, CodingKey {
        case mediaObjectId = "media_object_id"
        case uploadUrl = "upload_url"
        case objectKey = "object_key"
        case expiresAt = "expires_at"
    }
}

/// `POST /media/:id/download` response.
struct MediaDownloadResponse: Decodable, Equatable, Sendable {
    let downloadUrl: String
    let expiresAt: Date

    enum CodingKeys: String, CodingKey {
        case downloadUrl = "download_url"
        case expiresAt = "expires_at"
    }
}

/// Plaintext sealed inside the message ciphertext for `content_type = media`.
///
/// Human: The full blob stays on the server until the user taps download. A small JPEG
/// preview (`th`) rides in this payload so the bubble can show something Telegram-style
/// without fetching megabytes.
nonisolated struct MediaMessagePayload: Codable, Equatable, Sendable {
    /// `"image"` / `"voice"` / `"video"`.
    var t: String
    var mime: String
    /// Image/video width, or `0` for voice.
    var w: Int
    /// Image/video height, or `0` for voice.
    var h: Int
    /// Base64 AES-256 key for the uploaded blob.
    var k: String
    /// Optional caption (images/videos) or on-device transcript (voice).
    var c: String?
    /// Duration in milliseconds (voice / video).
    var d: Int?
    /// Base64 amplitude envelope, one byte (0…255) per bar (voice only).
    /// Optional so payloads written before waveforms existed still decode.
    var wf: String?
    /// Base64 JPEG preview for the bubble (images/videos). Small enough to live in the envelope.
    var th: String?
    /// Full media plaintext size in bytes (for the download chip label).
    var s: Int?

    static let kindImage = "image"
    static let kindVoice = "voice"
    static let kindVideo = "video"

    var isVoice: Bool { t == Self.kindVoice }
    var isImage: Bool { t == Self.kindImage }
    var isVideo: Bool { t == Self.kindVideo }

    /// Decoded preview JPEG, if present.
    var previewJPEG: Data? {
        guard let th, !th.isEmpty else { return nil }
        return Data(base64Encoded: th)
    }
}
