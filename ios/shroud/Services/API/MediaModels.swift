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
    /// The message this one replies to, when it was sent from the reply composer.
    /// Optional so payloads sealed before replies existed still decode (see `MessageReplyReference`).
    var re: MessageReplyReference?

    static let kindImage = "image"
    static let kindVoice = "voice"
    static let kindVideo = "video"

    var isVoice: Bool {
        if t == Self.kindVoice { return true }
        if t == Self.kindImage || t == Self.kindVideo { return false }
        return mime.hasPrefix("audio/")
    }

    var isImage: Bool {
        if t == Self.kindImage { return true }
        if t == Self.kindVoice || t == Self.kindVideo { return false }
        return mime.hasPrefix("image/")
    }

    var isVideo: Bool {
        if t == Self.kindVideo { return true }
        if t == Self.kindImage || t == Self.kindVoice { return false }
        return mime.hasPrefix("video/")
    }

    /// Decoded preview JPEG, if present.
    var previewJPEG: Data? {
        guard let th, !th.isEmpty else { return nil }
        return Data(base64Encoded: th)
    }

    /// Wire JSON, matching the web client's `parseMediaPayload`.
    ///
    /// Human: `JSONDecoder` is not the same binary on iOS 26 and iOS 27. A payload one
    /// phone sealed can fail to decode on the other, and the chat then draws every media
    /// note as a photo. The web client only requires `t` and `k` strings and defaults the
    /// rest — we do the same with `JSONSerialization`, which has been stable.
    /// Agent: Production decode/encode of this type must go through `parse` / `encoded`.
    static func parse(_ data: Data) -> MediaMessagePayload? {
        var json = data
        if json.starts(with: [0xEF, 0xBB, 0xBF] as [UInt8]) {
            json = Data(json.dropFirst(3))
        }
        guard let object = try? JSONSerialization.jsonObject(with: json) as? [String: Any] else {
            guard let raw = String(data: json, encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines),
                raw.first == "{",
                let trimmed = raw.data(using: .utf8),
                let object = try? JSONSerialization.jsonObject(with: trimmed) as? [String: Any]
            else { return nil }
            return parse(object)
        }
        return parse(object)
    }

    static func parse(_ object: [String: Any]) -> MediaMessagePayload? {
        guard let t = string(object["t"]), !t.isEmpty,
              let k = string(object["k"]), !k.isEmpty
        else { return nil }
        return MediaMessagePayload(
            t: t,
            mime: string(object["mime"]) ?? "application/octet-stream",
            w: int(object["w"]) ?? 0,
            h: int(object["h"]) ?? 0,
            k: k,
            c: string(object["c"]),
            d: int(object["d"]),
            wf: string(object["wf"]),
            th: string(object["th"]),
            s: int(object["s"]),
            re: (object["re"] as? [String: Any]).flatMap(MessageReplyReference.parse(wireObject:))
        )
    }

    /// Stable object JSON (no pretty-print, omit nils) so iOS 26 and 27 seal the same bytes.
    func encoded() throws -> Data {
        var object: [String: Any] = [
            "t": t,
            "mime": mime,
            "w": w,
            "h": h,
            "k": k,
        ]
        if let c { object["c"] = c }
        if let d { object["d"] = d }
        if let wf { object["wf"] = wf }
        if let th { object["th"] = th }
        if let s { object["s"] = s }
        if let re { object["re"] = re.wireObject }
        return try JSONSerialization.data(withJSONObject: object)
    }

    private static func string(_ value: Any?) -> String? {
        if value is NSNull { return nil }
        if let string = value as? String {
            let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }
        return nil
    }

    private static func int(_ value: Any?) -> Int? {
        if value is NSNull { return nil }
        if let number = value as? NSNumber { return number.intValue }
        if let int = value as? Int { return int }
        if let double = value as? Double { return Int(double) }
        if let string = value as? String { return Int(string) }
        return nil
    }
}
