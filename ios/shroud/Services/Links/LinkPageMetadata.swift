import Foundation

/// What a web page says about itself in its `<head>` — the raw material of a link preview.
nonisolated struct LinkPageMetadata: Equatable, Sendable {
    var siteName: String?
    var title: String?
    var summary: String?
    /// Absolute `https` URL of the page's preview image.
    var imageURL: URL?
    /// Size the page declares for that image (`og:image:width/height`); a hint only.
    var imageWidth: Int?
    var imageHeight: Int?
    /// `og:type` is a video, or the page declares a player.
    var isVideo: Bool = false

    /// Nothing a preview could show.
    var isEmpty: Bool {
        title == nil && summary == nil && imageURL == nil
    }
}

/// Reads OpenGraph / Twitter-card / `<title>` metadata out of an HTML head.
///
/// Human: This is deliberately not an HTML parser. Previews only need a dozen `<meta>` tags that
/// sit near the top of the page, and a tag scanner copes with broken markup that a strict
/// parser would reject. It runs on bytes fetched by the *sender's* phone only.
/// Agent: Pure; no I/O. Callers hand in at most the first few hundred KB of the page.
nonisolated enum LinkPageMetadataParser {
    /// Parses `data` (the start of an HTML document) fetched from `pageURL`.
    ///
    /// Agent: `contentType` is the response's `Content-Type` header, used for its charset.
    static func parse(_ data: Data, pageURL: URL, contentType: String?) -> LinkPageMetadata {
        let html = decode(data, contentType: contentType)
        let head = headSection(of: html)

        var meta: [String: String] = [:]
        for tag in tags(named: "meta", in: head) {
            let attributes = self.attributes(of: tag)
            guard let content = attributes["content"].map(decodeEntities),
                  !content.isEmpty
            else { continue }
            for key in ["property", "name", "itemprop"] {
                if let name = attributes[key]?.lowercased(), meta[name] == nil {
                    meta[name] = content
                }
            }
        }

        let documentTitle = title(in: head)
        var result = LinkPageMetadata()
        result.siteName = firstNonEmpty(meta["og:site_name"], meta["application-name"])
        result.title = firstNonEmpty(meta["og:title"], meta["twitter:title"], documentTitle)
        result.summary = firstNonEmpty(meta["og:description"], meta["twitter:description"], meta["description"])

        let rawImage = firstNonEmpty(
            meta["og:image:secure_url"],
            meta["og:image:url"],
            meta["og:image"],
            meta["twitter:image"],
            meta["twitter:image:src"]
        )
        result.imageURL = rawImage.flatMap { secureURL($0, relativeTo: pageURL) }
        result.imageWidth = meta["og:image:width"].flatMap { Int($0) }
        result.imageHeight = meta["og:image:height"].flatMap { Int($0) }

        let type = meta["og:type"]?.lowercased() ?? ""
        result.isVideo = type.hasPrefix("video")
            || meta["og:video"] != nil
            || meta["og:video:url"] != nil
            || meta["og:video:secure_url"] != nil
            || meta["twitter:player"] != nil
        return result
    }

    // MARK: - Text decoding

    /// Decodes with the declared charset, then a `<meta charset>` sniff, then UTF-8 (lossy as a
    /// last resort).
    static func decode(_ data: Data, contentType: String?) -> String {
        if let charset = charset(inContentType: contentType), let encoding = encoding(named: charset),
           let text = string(from: data, encoding: encoding)
        {
            return text
        }
        // The charset declaration has to sit in the first 1 KB of the document; ASCII is enough
        // to read it whatever the real encoding is.
        let prefix = String(decoding: data.prefix(1024), as: UTF8.self).lowercased()
        if let range = prefix.range(of: "charset=") {
            let value = prefix[range.upperBound...].drop { $0 == "\"" || $0 == "'" }
            if let encoding = encoding(named: String(value.prefix { !"\"'>; /".contains($0) })),
               let text = string(from: data, encoding: encoding)
            {
                return text
            }
        }
        if let text = string(from: data, encoding: .utf8) { return text }
        // A legacy page with no usable declaration: decode leniently rather than losing the
        // preview.
        return String(decoding: data, as: UTF8.self)
    }

    /// `data` decoded as `encoding`, forgiving a multi-byte character cut off at the end.
    ///
    /// Human: Only the head of a page is read, so the last character is often incomplete — and
    /// one broken character must not throw the whole page onto the lossy fallback, which turns
    /// every non-ASCII letter of a Shift_JIS or GBK title into garbage.
    private static func string(from data: Data, encoding: String.Encoding) -> String? {
        for cut in 0 ... min(3, max(data.count - 1, 0)) {
            if let text = String(data: data.dropLast(cut), encoding: encoding) { return text }
        }
        return nil
    }

    private static func charset(inContentType contentType: String?) -> String? {
        guard let contentType = contentType?.lowercased(),
              let range = contentType.range(of: "charset=")
        else { return nil }
        return String(contentType[range.upperBound...].prefix { !"; \"".contains($0) })
    }

    /// The encoding behind a charset label: the common ones by name, anything else through the
    /// IANA registry (`windows-1251`, `gbk`, `big5`, `euc-kr`, `koi8-r`, …).
    private static func encoding(named name: String) -> String.Encoding? {
        let label = name.trimmingCharacters(in: CharacterSet(charactersIn: "\"' ")).lowercased()
        switch label {
        case "": return nil
        case "utf-8", "utf8": return .utf8
        case "iso-8859-1", "latin1", "iso8859-1": return .isoLatin1
        case "windows-1252", "cp1252": return .windowsCP1252
        case "iso-8859-2": return .isoLatin2
        case "shift_jis", "shift-jis", "sjis", "x-sjis": return .shiftJIS
        case "euc-jp": return .japaneseEUC
        default: break
        }
        let registered = CFStringConvertIANACharSetNameToEncoding(label as CFString)
        guard registered != kCFStringEncodingInvalidId else { return nil }
        return String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(registered))
    }

    // MARK: - Tag scanning

    /// Everything before `</head>` (or `<body`), so a page that quotes markup in its body
    /// cannot inject a second set of tags.
    private static func headSection(of html: String) -> Substring {
        if let end = html.range(of: "</head", options: .caseInsensitive)
            ?? html.range(of: "<body", options: .caseInsensitive)
        {
            return html[..<end.lowerBound]
        }
        return Substring(html)
    }

    /// The raw source of every `<name …>` tag.
    private static func tags(named name: String, in html: Substring) -> [Substring] {
        var result: [Substring] = []
        var cursor = html.startIndex
        let opener = "<" + name
        while let start = html.range(of: opener, options: .caseInsensitive, range: cursor ..< html.endIndex) {
            // `<meta` must be followed by whitespace or `>`; `<metadata>` is not a meta tag.
            let after = start.upperBound
            guard after < html.endIndex else { break }
            let next = html[after]
            guard next.isWhitespace || next == ">" || next == "/" else {
                cursor = after
                continue
            }
            guard let end = tagEnd(in: html, from: after) else { break }
            result.append(html[after ..< end])
            cursor = html.index(after: end)
        }
        return result
    }

    /// The `>` that closes a tag whose attributes start at `start`.
    ///
    /// Human: HTML allows a bare `>` inside a quoted attribute value, and titles such as
    /// "Rust > Go?" or "Home > Shop" do show up in `og:title` — stopping at the first `>` would
    /// cut the preview's title and description there.
    /// Agent: RETURNS nil for a tag left open (an unterminated quote runs to the end, as in a
    /// browser).
    private static func tagEnd(in html: Substring, from start: Substring.Index) -> Substring.Index? {
        var index = start
        var afterEquals = false
        while index < html.endIndex {
            let character = html[index]
            if character == ">" { return index }
            if character == "=" {
                afterEquals = true
            } else if afterEquals, character == "\"" || character == "'" {
                // A quoted value runs to its closing quote, whatever it contains.
                guard let close = html[html.index(after: index)...].firstIndex(of: character) else {
                    return nil
                }
                index = close
                afterEquals = false
            } else if !character.isWhitespace {
                // An unquoted value: it ends at whitespace or `>`, so quotes inside it are text.
                afterEquals = false
            }
            index = html.index(after: index)
        }
        return nil
    }

    /// Attribute map of one tag's source, keys lowercased. Handles `"…"`, `'…'` and bare values.
    static func attributes(of tag: Substring) -> [String: String] {
        var attributes: [String: String] = [:]
        var index = tag.startIndex

        func skipWhitespace() {
            while index < tag.endIndex, tag[index].isWhitespace || tag[index] == "/" {
                index = tag.index(after: index)
            }
        }

        while true {
            skipWhitespace()
            guard index < tag.endIndex else { break }
            let nameStart = index
            while index < tag.endIndex, !tag[index].isWhitespace, tag[index] != "=", tag[index] != "/" {
                index = tag.index(after: index)
            }
            let name = tag[nameStart ..< index].lowercased()
            skipWhitespace()
            guard index < tag.endIndex, tag[index] == "=" else {
                if !name.isEmpty { attributes[name] = attributes[name] ?? "" }
                continue
            }
            index = tag.index(after: index)
            while index < tag.endIndex, tag[index].isWhitespace {
                index = tag.index(after: index)
            }
            guard index < tag.endIndex else { break }
            let value: Substring
            if tag[index] == "\"" || tag[index] == "'" {
                let quote = tag[index]
                let valueStart = tag.index(after: index)
                let valueEnd = tag[valueStart...].firstIndex(of: quote) ?? tag.endIndex
                value = tag[valueStart ..< valueEnd]
                index = valueEnd < tag.endIndex ? tag.index(after: valueEnd) : valueEnd
            } else {
                let valueStart = index
                while index < tag.endIndex, !tag[index].isWhitespace {
                    index = tag.index(after: index)
                }
                value = tag[valueStart ..< index]
            }
            if !name.isEmpty, attributes[name] == nil {
                attributes[name] = String(value)
            }
        }
        return attributes
    }

    private static func title(in head: Substring) -> String? {
        guard let open = head.range(of: "<title", options: .caseInsensitive),
              let openEnd = head.range(of: ">", range: open.upperBound ..< head.endIndex),
              let close = head.range(of: "</title", options: .caseInsensitive, range: openEnd.upperBound ..< head.endIndex)
        else { return nil }
        return decodeEntities(String(head[openEnd.upperBound ..< close.lowerBound]))
    }

    private static func firstNonEmpty(_ values: String?...) -> String? {
        for value in values {
            if let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty {
                return trimmed
            }
        }
        return nil
    }

    /// Resolves a (possibly relative or protocol-relative) image reference to an `https` URL.
    ///
    /// Human: `http` images are upgraded rather than fetched in the clear; a page that only
    /// serves its image over plain HTTP simply gets a preview without one.
    static func secureURL(_ raw: String, relativeTo page: URL) -> URL? {
        guard let resolved = URL(string: raw, relativeTo: page)?.absoluteURL,
              var components = URLComponents(url: resolved, resolvingAgainstBaseURL: true)
        else { return nil }
        switch components.scheme?.lowercased() {
        case "https": break
        case "http": components.scheme = "https"
        default: return nil
        }
        return components.url
    }

    // MARK: - Entities

    private static let namedEntities: [String: String] = [
        "amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'", "nbsp": " ",
        "ndash": "–", "mdash": "—", "hellip": "…", "laquo": "«", "raquo": "»",
        "lsquo": "‘", "rsquo": "’", "ldquo": "“", "rdquo": "”", "sbquo": "‚", "bdquo": "„",
        "bull": "•", "middot": "·", "copy": "©", "reg": "®", "trade": "™", "euro": "€",
        "pound": "£", "yen": "¥", "deg": "°", "times": "×", "shy": "",
        "auml": "ä", "ouml": "ö", "uuml": "ü", "Auml": "Ä", "Ouml": "Ö", "Uuml": "Ü", "szlig": "ß",
        "eacute": "é", "egrave": "è", "ecirc": "ê", "aacute": "á", "agrave": "à", "acirc": "â",
        "oacute": "ó", "ograve": "ò", "ocirc": "ô", "uacute": "ú", "iacute": "í", "ccedil": "ç",
        "ntilde": "ñ", "Eacute": "É", "oslash": "ø", "aring": "å", "aelig": "æ",
    ]

    /// Decodes `&amp;`, `&#39;`, `&#x27;` and the common named entities.
    static func decodeEntities(_ text: String) -> String {
        guard text.contains("&") else { return text }
        var output = ""
        output.reserveCapacity(text.count)
        var index = text.startIndex
        while index < text.endIndex {
            let character = text[index]
            guard character == "&",
                  let semicolon = text[index...].prefix(12).firstIndex(of: ";")
            else {
                output.append(character)
                index = text.index(after: index)
                continue
            }
            let entity = text[text.index(after: index) ..< semicolon]
            if let replacement = entityValue(entity) {
                output += replacement
                index = text.index(after: semicolon)
            } else {
                output.append(character)
                index = text.index(after: index)
            }
        }
        return output
    }

    private static func entityValue(_ entity: Substring) -> String? {
        if entity.hasPrefix("#x") || entity.hasPrefix("#X") {
            return UInt32(entity.dropFirst(2), radix: 16).flatMap(Unicode.Scalar.init).map { String($0) }
        }
        if entity.hasPrefix("#") {
            return UInt32(entity.dropFirst()).flatMap(Unicode.Scalar.init).map { String($0) }
        }
        return namedEntities[String(entity)]
    }
}
