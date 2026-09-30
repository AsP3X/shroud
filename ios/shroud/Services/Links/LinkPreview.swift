import Foundation

/// A link preview sealed **inside** a message's plaintext (`lp`).
///
/// Human: Telegram builds previews on its servers. Shroud's server must never see what anyone
/// sends, so the *sender's* phone fetches the page and seals what it found into the message —
/// the recipient never contacts the website at all (no IP leak, no tracking pixel, works
/// offline). The metadata rides in `lp`; a small thumbnail rides inline as `th`. A large image
/// is too big for the envelope, so it travels as the media blob of a `t: "link"` media message
/// (see `MediaMessagePayload.kindLink`) and is *not* part of this object.
/// Agent: Wire keys are shared with `web/src/links.ts` (`parseLinkPreview` / `linkPreviewWire`):
/// `u` url, `n` site name, `ti` title, `d` description, `th` base64 JPEG, `w`/`h` image size,
/// `vd` video page, `ab` drawn above the text. Everything but `u` is optional.
nonisolated struct LinkPreview: Codable, Equatable, Hashable, Sendable {
    /// The page the preview describes (`http`/`https` only).
    var url: String
    /// "komoot", "YouTube" — the accent-coloured first line.
    var siteName: String?
    var title: String?
    var summary: String?
    /// Inline JPEG for the small layout (≤ `maxThumbnailBytes`).
    var thumbnail: Data?
    /// Pixel size of the page image (large blob or thumbnail), for the aspect ratio.
    var imageWidth: Int?
    var imageHeight: Int?
    /// The page is a video: the large image gets a play badge.
    var isVideo: Bool = false
    /// Telegram's "Show above message": the block sits over the text instead of under it.
    var showsAboveText: Bool = false

    enum CodingKeys: String, CodingKey {
        case url = "u"
        case siteName = "n"
        case title = "ti"
        case summary = "d"
        case thumbnail = "th"
        case imageWidth = "w"
        case imageHeight = "h"
        case isVideo = "vd"
        case showsAboveText = "ab"
    }

    // MARK: - Limits

    /// Every byte is sealed three times (ratchet + peer box + self box) and base64-expanded,
    /// and the server caps an envelope at 64 KiB — keep the text parts short.
    static let maxSiteNameCharacters = 64
    static let maxTitleCharacters = 200
    static let maxSummaryCharacters = 300
    static let maxURLCharacters = 2048
    /// Same budget as a photo's envelope thumbnail (`MediaCrypto.maxEnvelopePreviewBytes`).
    static let maxThumbnailBytes = 6 * 1024

    /// Local persistence (sealed thread files). Lenient like the wire parse: a flag written by a
    /// later build, or missing from an earlier one, must never fail the whole thread file.
    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        url = try container.decode(String.self, forKey: .url)
        siteName = try container.decodeIfPresent(String.self, forKey: .siteName)
        title = try container.decodeIfPresent(String.self, forKey: .title)
        summary = try container.decodeIfPresent(String.self, forKey: .summary)
        thumbnail = try container.decodeIfPresent(Data.self, forKey: .thumbnail)
        imageWidth = try container.decodeIfPresent(Int.self, forKey: .imageWidth)
        imageHeight = try container.decodeIfPresent(Int.self, forKey: .imageHeight)
        isVideo = try container.decodeIfPresent(Bool.self, forKey: .isVideo) ?? false
        showsAboveText = try container.decodeIfPresent(Bool.self, forKey: .showsAboveText) ?? false
    }

    init(
        url: String,
        siteName: String? = nil,
        title: String? = nil,
        summary: String? = nil,
        thumbnail: Data? = nil,
        imageWidth: Int? = nil,
        imageHeight: Int? = nil,
        isVideo: Bool = false,
        showsAboveText: Bool = false
    ) {
        self.url = url
        self.siteName = Self.clean(siteName, max: Self.maxSiteNameCharacters)
        self.title = Self.clean(title, max: Self.maxTitleCharacters)
        self.summary = Self.clean(summary, max: Self.maxSummaryCharacters)
        self.thumbnail = thumbnail
        self.imageWidth = imageWidth
        self.imageHeight = imageHeight
        self.isVideo = isVideo
        self.showsAboveText = showsAboveText
    }

    /// The URL to open, if it is still a web URL.
    var openURL: URL? {
        guard let url = URL(string: url), let scheme = url.scheme?.lowercased(),
              scheme == "https" || scheme == "http"
        else { return nil }
        return url
    }

    /// Host without `www.`, the fallback site name ("komoot.com").
    var displayHost: String {
        guard let host = URL(string: url)?.host?.lowercased() else { return url }
        return host.hasPrefix("www.") ? String(host.dropFirst(4)) : host
    }

    /// First line of the block: the site's own name, else its host (Telegram Web does the same).
    var displaySiteName: String {
        siteName ?? displayHost
    }

    /// Image aspect (width / height) when the sender knew it.
    var imageAspect: CGFloat? {
        guard let imageWidth, let imageHeight, imageWidth > 0, imageHeight > 0 else { return nil }
        return CGFloat(imageWidth) / CGFloat(imageHeight)
    }

    /// Nothing worth drawing: no words and no picture.
    var isEmpty: Bool {
        title == nil && summary == nil && thumbnail == nil
    }

    /// Copy without the inline thumbnail — the large layout carries its image as a blob.
    func withoutThumbnail() -> LinkPreview {
        var copy = self
        copy.thumbnail = nil
        return copy
    }

    // MARK: - Wire format

    /// JSON object for sealing, matching the web client's `linkPreviewWire`.
    ///
    /// Human: Built with `JSONSerialization` like every other sealed shape — `JSONDecoder`
    /// has not decoded identically across iOS releases, and a payload one phone sealed must
    /// open on the next. Falsy flags are left out to keep the envelope small.
    var wireObject: [String: Any] {
        var object: [String: Any] = ["u": url]
        if let siteName { object["n"] = siteName }
        if let title { object["ti"] = title }
        if let summary { object["d"] = summary }
        if let thumbnail { object["th"] = thumbnail.base64EncodedString() }
        if let imageWidth, imageWidth > 0 { object["w"] = imageWidth }
        if let imageHeight, imageHeight > 0 { object["h"] = imageHeight }
        if isVideo { object["vd"] = true }
        if showsAboveText { object["ab"] = true }
        return object
    }

    /// Lenient parse of the `lp` object.
    ///
    /// Agent: RETURNS nil unless `u` is an http(s) URL. Oversized fields are clamped, a thumb
    /// over budget is dropped, and anything unknown is ignored — a newer sender never breaks
    /// an older reader.
    static func parse(wireObject object: [String: Any]) -> LinkPreview? {
        guard let rawURL = string(object["u"]),
              rawURL.count <= maxURLCharacters,
              let url = URL(string: rawURL),
              let scheme = url.scheme?.lowercased(),
              scheme == "https" || scheme == "http"
        else { return nil }
        var thumbnail: Data?
        if let base64 = string(object["th"]), let data = Data(base64Encoded: base64),
           data.count <= maxThumbnailBytes
        {
            thumbnail = data
        }
        return LinkPreview(
            url: rawURL,
            siteName: string(object["n"]),
            title: string(object["ti"]),
            summary: string(object["d"]),
            thumbnail: thumbnail,
            imageWidth: int(object["w"]),
            imageHeight: int(object["h"]),
            isVideo: object["vd"] as? Bool == true,
            showsAboveText: object["ab"] as? Bool == true
        )
    }

    /// Collapses runs of whitespace and cuts at a character boundary, marking the cut with "…".
    static func clean(_ raw: String?, max: Int) -> String? {
        guard let raw else { return nil }
        var collapsed = ""
        collapsed.reserveCapacity(raw.count)
        var pendingSpace = false
        for character in raw {
            if character.isWhitespace || character.isNewline {
                pendingSpace = !collapsed.isEmpty
                continue
            }
            if pendingSpace {
                collapsed.append(" ")
                pendingSpace = false
            }
            collapsed.append(character)
        }
        guard !collapsed.isEmpty else { return nil }
        guard collapsed.count > max else { return collapsed }
        return collapsed.prefix(max - 1).trimmingCharacters(in: .whitespaces) + "…"
    }

    private static func string(_ value: Any?) -> String? {
        guard let string = value as? String else { return nil }
        let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func int(_ value: Any?) -> Int? {
        (value as? NSNumber)?.intValue
    }
}
