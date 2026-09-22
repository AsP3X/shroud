import CoreGraphics
import Darwin
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// A preview the composer is holding for the link in the draft.
nonisolated struct LinkPreviewDraft: Equatable, Sendable {
    /// Metadata. `thumbnail` is the square inline thumb when the page has a usable image.
    var preview: LinkPreview
    /// JPEG for the large layout; nil when the page has no image worth showing big.
    var largeImage: Data?
    var largeImageWidth: Int?
    var largeImageHeight: Int?
    /// The layout Telegram would pick: a big picture for videos and wide card images, a small
    /// square next to the text for everything else.
    var prefersLargeImage: Bool

    var hasImage: Bool { preview.thumbnail != nil || largeImage != nil }
}

/// What is sealed with an outgoing message.
nonisolated struct LinkPreviewAttachment: Equatable, Sendable {
    /// Metadata plus the square thumbnail used by the small layout (and as the fallback when
    /// the large image cannot be uploaded).
    var preview: LinkPreview
    /// Present when the sender chose the large layout: uploaded as the media blob.
    var largeImage: Data?
    var largeImageWidth: Int?
    var largeImageHeight: Int?
}

/// Fetches link previews. Protocol-backed so the composer can be tested without a network.
nonisolated protocol LinkPreviewFetching: Sendable {
    func fetchPreview(for url: URL) async throws -> LinkPreviewDraft
}

/// Builds a link preview on the sender's device.
///
/// Human: Only the sender ever talks to the website, and only after they typed or pasted the
/// link themselves — the same trade Signal makes. The recipient gets the result sealed inside
/// the message. To keep the fetch from being turned against the sender:
/// - HTTPS only, default port, and never an IP literal, a local name (`.local`, `.internal`,
///   `localhost`), or a name that resolves to a private, loopback, or link-local address, so a
///   pasted link cannot probe the home network. Redirects are held to the same rules.
/// - An ephemeral session: no cookies, no cache, no credentials, nothing kept afterwards.
/// - Only the page head is read (≤ 512 KB) and images are capped at 5 MB and decoded
///   downsampled, so a hostile page cannot run the phone out of memory.
/// Agent: HTTP GET to the link's host only; never to the Shroud server. No logging of URLs.
/// Runs off the main actor (`Task.detached`); cancellation of the caller cancels the fetch.
nonisolated struct LinkPreviewFetcher: LinkPreviewFetching {
    /// Sites hand compact OpenGraph-only pages to known preview fetchers and a consent wall or
    /// a JavaScript shell to anything else. This is the user agent Signal uses; tested against
    /// YouTube, Amazon, Reddit, Wikipedia and GitHub. The trailing token says who we are.
    static let userAgent = "WhatsApp/2 (compatible; ShroudBot/1.0)"
    static let maxHeadBytes = 512 * 1024
    static let maxImageBytes = 5 * 1024 * 1024
    /// Longest edge of the large-layout JPEG (≈ 3× a phone bubble's width).
    static let largeImageMaxEdge: CGFloat = 1024
    /// Edge of the square thumbnail (54 pt at 3×).
    static let thumbnailEdge = 160
    /// Images smaller than this are icons or tracking pixels, not previews.
    static let minimumImageEdge = 80

    enum FetchError: Error, Equatable {
        case notAllowed
        case badResponse
        case notHTML
        case empty
        case imageTooLarge
    }

    func fetchPreview(for url: URL) async throws -> LinkPreviewDraft {
        let task = Task.detached(priority: .utility) {
            try await Self.fetch(url)
        }
        return try await withTaskCancellationHandler {
            try await task.value
        } onCancel: {
            task.cancel()
        }
    }

    // MARK: - Pipeline

    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieAcceptPolicy = .never
        configuration.httpShouldSetCookies = false
        configuration.urlCredentialStorage = nil
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 8
        configuration.timeoutIntervalForResource = 15
        configuration.waitsForConnectivity = false
        configuration.httpAdditionalHeaders = [
            "User-Agent": userAgent,
            "Accept-Language": acceptLanguage,
        ]
        return URLSession(configuration: configuration)
    }()

    /// `de-DE,de;q=0.9,en;q=0.8` — the page in the sender's language when it has one.
    private static var acceptLanguage: String {
        let languages = Locale.preferredLanguages.prefix(3)
        guard !languages.isEmpty else { return "en" }
        return languages.enumerated().map { index, language in
            index == 0 ? language : "\(language);q=\(String(format: "%.1f", 1 - Double(index) * 0.1))"
        }.joined(separator: ",")
    }

    private static func fetch(_ url: URL) async throws -> LinkPreviewDraft {
        guard let target = allowedTarget(url),
              let host = target.host,
              resolvesOnlyToPublicAddresses(host)
        else { throw FetchError.notAllowed }
        let (head, finalURL, contentType) = try await fetchHead(target)
        try Task.checkCancellation()

        var metadata: LinkPageMetadata
        if contentType.hasPrefix("image/") {
            // A direct link to a picture: the picture is the preview.
            metadata = LinkPageMetadata(imageURL: finalURL)
        } else {
            metadata = LinkPageMetadataParser.parse(head, pageURL: finalURL, contentType: contentType)
        }
        if let imageURL = metadata.imageURL {
            // Fetch the upgraded https URL, not the page's original http one.
            if let allowed = allowedTarget(imageURL),
               let host = allowed.host,
               resolvesOnlyToPublicAddresses(host)
            {
                metadata.imageURL = allowed
            } else {
                metadata.imageURL = nil
            }
        }
        guard !metadata.isEmpty else { throw FetchError.empty }

        var images: PreparedImages?
        if let imageURL = metadata.imageURL {
            // A broken image must not cost the whole preview — Telegram shows the text alone.
            if let data = try? await fetchImage(imageURL) {
                try Task.checkCancellation()
                images = prepareImages(from: data)
            }
        }
        guard metadata.title != nil || metadata.summary != nil || images != nil else {
            throw FetchError.empty
        }

        let preview = LinkPreview(
            url: url.absoluteString,
            siteName: metadata.siteName,
            title: metadata.title,
            summary: metadata.summary,
            thumbnail: images?.thumbnail,
            imageWidth: images?.width,
            imageHeight: images?.height,
            isVideo: metadata.isVideo
        )
        let prefersLarge: Bool = {
            guard let images else { return false }
            if metadata.isVideo { return true }
            return images.width >= 400 && Double(images.width) / Double(max(images.height, 1)) >= 1.2
        }()
        return LinkPreviewDraft(
            preview: preview,
            largeImage: images?.large,
            largeImageWidth: images?.width,
            largeImageHeight: images?.height,
            prefersLargeImage: prefersLarge
        )
    }

    /// Reads the page until `</head>` or `maxHeadBytes`, whichever comes first.
    private static func fetchHead(_ url: URL) async throws -> (Data, URL, String) {
        var request = URLRequest(url: url)
        request.setValue("text/html,application/xhtml+xml;q=0.9,*/*;q=0.5", forHTTPHeaderField: "Accept")
        let (bytes, response) = try await session.bytes(for: request, delegate: RedirectGuard.shared)
        defer { bytes.task.cancel() }
        guard let http = response as? HTTPURLResponse, (200 ..< 300).contains(http.statusCode) else {
            throw FetchError.badResponse
        }
        let finalURL = http.url ?? url
        guard allowedTarget(finalURL) != nil else { throw FetchError.notAllowed }
        let contentType = http.value(forHTTPHeaderField: "Content-Type")?.lowercased() ?? ""
        if contentType.hasPrefix("image/") {
            return (Data(), finalURL, contentType)
        }
        guard contentType.isEmpty || contentType.contains("html") || contentType.contains("xml") else {
            throw FetchError.notHTML
        }

        var buffer = Data()
        buffer.reserveCapacity(64 * 1024)
        let headEnd = Data("</head".utf8)
        let headEndUpper = Data("</HEAD".utf8)
        for try await byte in bytes {
            buffer.append(byte)
            if buffer.count >= maxHeadBytes { break }
            // Checking every 4 KB keeps the scan cheap; the window overlaps the last check.
            if buffer.count % 4096 == 0 {
                let window = buffer.suffix(4096 + headEnd.count)
                if window.range(of: headEnd) != nil || window.range(of: headEndUpper) != nil { break }
            }
        }
        return (buffer, finalURL, contentType)
    }

    private static func fetchImage(_ url: URL) async throws -> Data {
        var request = URLRequest(url: url)
        request.setValue("image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8", forHTTPHeaderField: "Accept")
        let (bytes, response) = try await session.bytes(for: request, delegate: RedirectGuard.shared)
        defer { bytes.task.cancel() }
        guard let http = response as? HTTPURLResponse, (200 ..< 300).contains(http.statusCode) else {
            throw FetchError.badResponse
        }
        if response.expectedContentLength > Int64(maxImageBytes) { throw FetchError.imageTooLarge }
        var data = Data()
        data.reserveCapacity(Int(max(0, min(response.expectedContentLength, Int64(maxImageBytes)))))
        for try await byte in bytes {
            data.append(byte)
            if data.count > maxImageBytes { throw FetchError.imageTooLarge }
        }
        return data
    }

    // MARK: - Target rules

    /// The URL to fetch, or nil when it must not be fetched.
    ///
    /// Human: A typed `http://` link is upgraded to `https://` rather than fetched in the clear.
    /// Agent: RETURNS nil for IP literals, local names, non-default ports and other schemes.
    static func allowedTarget(_ url: URL) -> URL? {
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: true) else { return nil }
        switch components.scheme?.lowercased() {
        case "https": break
        case "http": components.scheme = "https"
        default: return nil
        }
        if let port = components.port, port != 443 { return nil }
        guard let host = components.host?.lowercased(), isPublicHostName(host) else { return nil }
        components.user = nil
        components.password = nil
        return components.url
    }

    /// True when every address `host` resolves to is a public unicast address.
    ///
    /// Human: A name like `home.example.com` can still point at `192.168.1.1`. Checking the
    /// name is not enough — the addresses are what the phone would actually connect to.
    /// Agent: Fail closed (false) when lookup fails or any address is private, loopback,
    /// link-local, multicast, or otherwise not globally routable. `getaddrinfo` of an IP
    /// literal does not use the network.
    static func resolvesOnlyToPublicAddresses(_ host: String) -> Bool {
        var hints = addrinfo()
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_STREAM
        var info: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, nil, &hints, &info) == 0, let info else { return false }
        defer { freeaddrinfo(info) }
        var sawAddress = false
        var cursor: UnsafeMutablePointer<addrinfo>? = info
        while let current = cursor {
            if let address = current.pointee.ai_addr {
                sawAddress = true
                if !isGloballyRoutable(address) { return false }
            }
            cursor = current.pointee.ai_next
        }
        return sawAddress
    }

    /// `ip` is in host byte order (`1.1.1.1` is `0x01010101`).
    static func isGloballyRoutableIPv4(_ ip: UInt32) -> Bool {
        let a = (ip >> 24) & 0xFF
        let b = (ip >> 16) & 0xFF
        let c = (ip >> 8) & 0xFF
        if a == 0 || a == 10 || a == 127 { return false }
        if a == 100 && (b & 0xC0) == 64 { return false }
        if a == 169 && b == 254 { return false }
        if a == 172 && (b & 0xF0) == 16 { return false }
        if a == 192 && b == 0 && c == 0 { return false }
        if a == 192 && b == 0 && c == 2 { return false }
        if a == 192 && b == 168 { return false }
        if a == 198 && (b & 0xFE) == 18 { return false }
        if a == 198 && b == 51 && c == 100 { return false }
        if a == 203 && b == 0 && c == 113 { return false }
        if a >= 224 { return false }
        return true
    }

    private static func isGloballyRoutable(_ address: UnsafePointer<sockaddr>) -> Bool {
        switch Int32(address.pointee.sa_family) {
        case AF_INET:
            return address.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { pointer in
                isGloballyRoutableIPv4(UInt32(bigEndian: pointer.pointee.sin_addr.s_addr))
            }
        case AF_INET6:
            return address.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) { pointer in
                isGloballyRoutableIPv6(pointer.pointee.sin6_addr)
            }
        default:
            return false
        }
    }

    private static func isGloballyRoutableIPv6(_ address: in6_addr) -> Bool {
        let bytes = withUnsafeBytes(of: address) { Array($0) }
        guard bytes.count == 16 else { return false }
        if bytes.allSatisfy({ $0 == 0 }) { return false }
        if bytes.dropLast().allSatisfy({ $0 == 0 }) && bytes[15] == 1 { return false }
        // IPv4-mapped (::ffff:a.b.c.d) is only as public as the v4 address inside it.
        if bytes[..<10].allSatisfy({ $0 == 0 }) && bytes[10] == 0xFF && bytes[11] == 0xFF {
            let v4 = (UInt32(bytes[12]) << 24) | (UInt32(bytes[13]) << 16)
                | (UInt32(bytes[14]) << 8) | UInt32(bytes[15])
            return isGloballyRoutableIPv4(v4)
        }
        if bytes[0] == 0xFE && (bytes[1] & 0xC0) == 0x80 { return false }
        if (bytes[0] & 0xFE) == 0xFC { return false }
        if bytes[0] == 0xFF { return false }
        if bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0D && bytes[3] == 0xB8 { return false }
        return true
    }

    /// True for a dotted DNS name that is not local and not an address literal.
    static func isPublicHostName(_ host: String) -> Bool {
        let host = host.hasSuffix(".") ? String(host.dropLast()) : host
        guard host.contains("."), !host.contains(":"), !host.contains("%") else { return false }
        let localSuffixes = [".local", ".localhost", ".internal", ".lan", ".home", ".arpa", ".intranet", ".corp"]
        if localSuffixes.contains(where: { host.hasSuffix($0) }) { return false }
        // Dotted-quad IPv4 (and the all-digits shorthands some resolvers accept).
        let labels = host.split(separator: ".")
        if labels.allSatisfy({ $0.allSatisfy(\.isNumber) }) { return false }
        return true
    }

    // MARK: - Images

    private struct PreparedImages {
        let large: Data?
        let thumbnail: Data?
        let width: Int
        let height: Int
    }

    /// Downsampled large JPEG + square thumbnail, both flattened onto white (JPEG has no alpha,
    /// and a transparent logo would otherwise come out black).
    private static func prepareImages(from data: Data) -> PreparedImages? {
        let options = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, options) else { return nil }
        let thumbnailOptions = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: largeImageMaxEdge,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
        ] as CFDictionary
        guard let decoded = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnailOptions),
              min(decoded.width, decoded.height) >= minimumImageEdge
        else { return nil }

        let large = flattened(decoded, size: CGSize(width: decoded.width, height: decoded.height))
            .flatMap { jpeg($0, quality: 0.72) }

        // Center square crop, then down to the thumbnail edge.
        let side = min(decoded.width, decoded.height)
        let crop = CGRect(
            x: (decoded.width - side) / 2,
            y: (decoded.height - side) / 2,
            width: side,
            height: side
        )
        var thumbnail: Data?
        if let square = decoded.cropping(to: crop) {
            var edge = thumbnailEdge
            var quality: CGFloat = 0.7
            for _ in 0 ..< 5 {
                if let image = flattened(square, size: CGSize(width: edge, height: edge)),
                   let data = jpeg(image, quality: quality),
                   data.count <= LinkPreview.maxThumbnailBytes
                {
                    thumbnail = data
                    break
                }
                edge = max(96, edge - 24)
                quality = max(0.35, quality - 0.1)
            }
        }
        guard large != nil || thumbnail != nil else { return nil }
        return PreparedImages(large: large, thumbnail: thumbnail, width: decoded.width, height: decoded.height)
    }

    private static func flattened(_ image: CGImage, size: CGSize) -> CGImage? {
        let width = max(1, Int(size.width.rounded()))
        let height = max(1, Int(size.height.rounded()))
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(
                  data: nil,
                  width: width,
                  height: height,
                  bitsPerComponent: 8,
                  bytesPerRow: 0,
                  space: space,
                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
              )
        else { return nil }
        context.interpolationQuality = .high
        context.setFillColor(CGColor(gray: 1, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        return context.makeImage()
    }

    private static func jpeg(_ image: CGImage, quality: CGFloat) -> Data? {
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            output,
            UTType.jpeg.identifier as CFString,
            1,
            nil
        ) else { return nil }
        CGImageDestinationAddImage(
            destination,
            image,
            [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary
        )
        guard CGImageDestinationFinalize(destination) else { return nil }
        return output as Data
    }
}

/// Holds redirects to the same rules as the first request.
///
/// Human: The completion-handler form on purpose — the `async` form of this delegate method
/// crashes the Swift 6.2 compiler (SILGen thunk) under this target's concurrency settings.
/// Agent: Hands back nil (stop, surface the 3xx) for any redirect `allowedTarget` rejects.
private nonisolated final class RedirectGuard: NSObject, URLSessionTaskDelegate, Sendable {
    static let shared = RedirectGuard()

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping @Sendable (URLRequest?) -> Void
    ) {
        guard let url = request.url,
              let allowed = LinkPreviewFetcher.allowedTarget(url),
              let host = allowed.host,
              LinkPreviewFetcher.resolvesOnlyToPublicAddresses(host)
        else {
            completionHandler(nil)
            return
        }
        var redirected = request
        redirected.url = allowed
        completionHandler(redirected)
    }
}
