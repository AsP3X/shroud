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
struct MediaMessagePayload: Codable, Equatable, Sendable {
    /// `"image"` for photos.
    var t: String
    var mime: String
    var w: Int
    var h: Int
    /// Base64 AES-256 key for the uploaded blob.
    var k: String

    static let kindImage = "image"
}
