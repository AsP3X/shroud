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
nonisolated struct MediaMessagePayload: Codable, Equatable, Sendable {
    /// `"image"` for photos, `"voice"` for voice messages.
    var t: String
    var mime: String
    /// Image width, or `0` for voice.
    var w: Int
    /// Image height, or `0` for voice.
    var h: Int
    /// Base64 AES-256 key for the uploaded blob.
    var k: String
    /// Optional caption (images) or on-device transcript (voice).
    var c: String?
    /// Voice duration in milliseconds (voice only).
    var d: Int?
    /// Base64 amplitude envelope, one byte (0…255) per bar (voice only).
    /// Optional so payloads written before waveforms existed still decode.
    var wf: String?

    static let kindImage = "image"
    static let kindVoice = "voice"

    var isVoice: Bool { t == Self.kindVoice }
    var isImage: Bool { t == Self.kindImage }
}
