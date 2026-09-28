import AVFoundation
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Removes location, capture time and device details from media before it is sent.
///
/// Human: An "Original" photo is the library file itself, and an iPhone photo carries where it
/// was taken (GPS), when, and which phone, lens and software took it. None of that belongs in a
/// chat. What stays is only what a viewer needs to draw the picture right: orientation, the
/// colour profile and HDR gain maps. Pixels are never re-compressed on this path — ImageIO copies
/// JPEG and HEIC losslessly with replaced metadata, and PNG is lossless anyway.
///
/// Agent: pure functions, no UI or network. `scrubImage` returns nil when the result could not be
/// proven clean; callers must then re-encode (which carries no metadata) instead of shipping the
/// original. Measured on ImageIO: HEIC keeps its XMP packet through the copy, so `blankXMP`
/// removes disallowed XMP properties in place (same byte length, so HEIF item offsets stay valid).
nonisolated enum MediaMetadataScrubber {
    // MARK: - Photos

    /// The image with only rendering metadata left, or nil if that could not be achieved.
    static func scrubImage(_ data: Data) -> Data? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let identifier = CGImageSourceGetType(source) as String?,
              let type = UTType(identifier),
              CGImageSourceGetCount(source) > 0
        else { return nil }

        let copied: Data?
        if type.conforms(to: .png) {
            // `CGImageDestinationCopyImageSource` returns PNGs unchanged, metadata and all.
            copied = reencodePNG(source)
        } else {
            copied = losslessCopy(source, type: identifier as CFString)
        }
        guard var result = copied else { return nil }

        if !leftoverMetadata(in: result).isEmpty {
            if type.conforms(to: .jpeg) { blankJPEGIPTC(in: &result) }
            blankXMP(in: &result)
        }
        return leftoverMetadata(in: result).isEmpty ? result : nil
    }

    /// Everything in `data` that isn't on the allow-list, as readable key paths. Empty = clean.
    ///
    /// Checks both views ImageIO offers, because neither is complete on its own: the properties
    /// dictionary shows maker notes and IPTC, which never appear as XMP-style tags, and the tag
    /// list shows XMP-only properties, which the dictionary folds into other keys or drops.
    static func leftoverMetadata(in data: Data) -> [String] {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return ["unreadable"] }
        var found: [String] = []
        for index in 0 ..< CGImageSourceGetCount(source) {
            if let properties = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any] {
                found += disallowedProperties(properties)
            }
            if let metadata = CGImageSourceCopyMetadataAtIndex(source, index, nil) {
                CGImageMetadataEnumerateTagsUsingBlock(
                    metadata, nil, [kCGImageMetadataEnumerateRecursively: true] as CFDictionary
                ) { path, _ in
                    let path = path as String
                    if !isAllowedTagPath(path) { found.append(path) }
                    return true
                }
            }
        }
        return found
    }

    private static func losslessCopy(_ source: CGImageSource, type: CFString) -> Data? {
        let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] ?? [:]
        // Orientation has to travel inside the replacement metadata: ImageIO refuses
        // `kCGImageDestinationOrientation` together with `kCGImageDestinationMetadata`.
        let metadata = CGImageMetadataCreateMutable()
        if let orientation = properties[kCGImagePropertyOrientation] as? Int, orientation != 1 {
            CGImageMetadataSetValueMatchingImageProperty(
                metadata, kCGImagePropertyTIFFDictionary, kCGImagePropertyTIFFOrientation, orientation as CFNumber
            )
        }
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output, type, CGImageSourceGetCount(source), nil) else {
            return nil
        }
        let options: [CFString: Any] = [
            kCGImageDestinationMetadata: metadata,
            kCGImageDestinationMergeMetadata: false,
            kCGImageMetadataShouldExcludeGPS: true,
            kCGImageMetadataShouldExcludeXMP: true,
        ]
        guard CGImageDestinationCopyImageSource(destination, source, options as CFDictionary, nil) else { return nil }
        return output as Data
    }

    /// Every frame again, so an animated PNG stays animated; only orientation and frame timing
    /// carry over.
    private static func reencodePNG(_ source: CGImageSource) -> Data? {
        let count = CGImageSourceGetCount(source)
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output, UTType.png.identifier as CFString, count, nil) else {
            return nil
        }
        if count > 1,
           let container = CGImageSourceCopyProperties(source, nil) as? [CFString: Any],
           let png = container[kCGImagePropertyPNGDictionary] as? [CFString: Any],
           let loops = png[kCGImagePropertyAPNGLoopCount]
        {
            CGImageDestinationSetProperties(destination, [
                kCGImagePropertyPNGDictionary: [kCGImagePropertyAPNGLoopCount: loops],
            ] as CFDictionary)
        }
        for index in 0 ..< count {
            guard let image = CGImageSourceCreateImageAtIndex(source, index, nil) else { return nil }
            let properties = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any] ?? [:]
            var kept: [CFString: Any] = [:]
            if let orientation = properties[kCGImagePropertyOrientation] as? Int, orientation != 1 {
                kept[kCGImagePropertyOrientation] = orientation
            }
            if count > 1, let png = properties[kCGImagePropertyPNGDictionary] as? [CFString: Any] {
                var timing: [CFString: Any] = [:]
                for key in [kCGImagePropertyAPNGDelayTime, kCGImagePropertyAPNGUnclampedDelayTime] {
                    if let value = png[key] { timing[key] = value }
                }
                if !timing.isEmpty { kept[kCGImagePropertyPNGDictionary] = timing }
            }
            CGImageDestinationAddImage(destination, image, kept as CFDictionary)
        }
        guard CGImageDestinationFinalize(destination) else { return nil }
        return output as Data
    }

    // MARK: - Allow-list

    /// Image-structure keys that say nothing about who, where or when.
    private static let allowedTIFF: Set<CFString> = [
        kCGImagePropertyTIFFOrientation, kCGImagePropertyTIFFTileWidth, kCGImagePropertyTIFFTileLength,
        kCGImagePropertyTIFFXResolution, kCGImagePropertyTIFFYResolution, kCGImagePropertyTIFFResolutionUnit,
    ]
    private static let allowedExif: Set<CFString> = [
        kCGImagePropertyExifPixelXDimension, kCGImagePropertyExifPixelYDimension, kCGImagePropertyExifColorSpace,
        kCGImagePropertyExifComponentsConfiguration, kCGImagePropertyExifVersion, kCGImagePropertyExifFlashPixVersion,
    ]
    /// Dictionaries that only ever carry identifying data.
    private static let forbiddenDictionaries: [CFString] = [
        kCGImagePropertyGPSDictionary, kCGImagePropertyIPTCDictionary, kCGImagePropertyExifAuxDictionary,
        kCGImagePropertyMakerAppleDictionary, kCGImagePropertyMakerCanonDictionary,
        kCGImagePropertyMakerNikonDictionary, kCGImagePropertyMakerFujiDictionary,
        kCGImagePropertyMakerOlympusDictionary, kCGImagePropertyMakerPentaxDictionary,
        kCGImagePropertyMakerMinoltaDictionary, kCGImagePropertyCIFFDictionary, kCGImagePropertyDNGDictionary,
    ]

    private static func disallowedProperties(_ properties: [CFString: Any]) -> [String] {
        var found: [String] = forbiddenDictionaries.filter { properties[$0] != nil }.map { $0 as String }
        if let tiff = properties[kCGImagePropertyTIFFDictionary] as? [CFString: Any] {
            found += tiff.keys.filter { !allowedTIFF.contains($0) }.map { "TIFF.\($0)" }
        }
        if let exif = properties[kCGImagePropertyExifDictionary] as? [CFString: Any] {
            found += exif.keys.filter { !allowedExif.contains($0) }.map { "Exif.\($0)" }
        }
        if let png = properties[kCGImagePropertyPNGDictionary] as? [CFString: Any] {
            let identifying: [CFString] = [
                kCGImagePropertyPNGAuthor, kCGImagePropertyPNGComment, kCGImagePropertyPNGCopyright,
                kCGImagePropertyPNGCreationTime, kCGImagePropertyPNGDescription, kCGImagePropertyPNGModificationTime,
                kCGImagePropertyPNGSoftware, kCGImagePropertyPNGTitle,
            ]
            found += identifying.filter { png[$0] != nil }.map { "PNG.\($0)" }
        }
        return found
    }

    /// `prefix:Name[0].field` → allowed when it describes the picture itself.
    static func isAllowedTagPath(_ path: String) -> Bool {
        let qualified = String(path.prefix { $0 != "[" && $0 != "." && $0 != "/" })
        guard let colon = qualified.firstIndex(of: ":") else { return false }
        let prefix = qualified[..<colon]
        if keptXMPPrefixes.contains(String(prefix)) || prefix == "iio" { return true }
        let local = String(qualified[qualified.index(after: colon)...])
        switch prefix {
        case "tiff": return allowedTIFF.contains(local as CFString)
        case "exif": return allowedExif.contains(local as CFString)
        default: return false
        }
    }

    // MARK: - XMP

    /// XMP namespaces whose properties survive: HDR gain-map parameters (ISO 21496-1 / Adobe
    /// `hdrgm`, Apple `HDRGainMap`). An unknown prefix is removed — a lost HDR hint beats a leak.
    private static let keptXMPPrefixes: Set<String> = ["hdrgm", "HDRGainMap"]
    /// Qualified property names kept outside those namespaces.
    private static let keptXMPProperties: Set<String> = ["tiff:Orientation"]

    /// Overwrites every disallowed property in every XMP packet with spaces.
    ///
    /// Byte length never changes, so container offsets (HEIF `iloc`, JPEG MPF) stay valid, and the
    /// result is still well-formed XML: whitespace is legal between elements and attributes.
    static func blankXMP(in data: inout Data) {
        var bytes = [UInt8](data)
        var searchFrom = 0
        while let open = find(Array("<x:xmpmeta".utf8), in: bytes, from: searchFrom)
            ?? find(Array("<x:xapmeta".utf8), in: bytes, from: searchFrom)
        {
            let closeTag = bytes[open + 4] == UInt8(ascii: "m") ? "</x:xmpmeta>" : "</x:xapmeta>"
            guard let close = find(Array(closeTag.utf8), in: bytes, from: open) else { break }
            let end = close + closeTag.utf8.count
            var blanker = XMPBlanker(storage: bytes, range: open ..< end)
            bytes = blanker.run()
            searchFrom = end
        }
        data = Data(bytes)
    }

    /// Turns the primary image's APP13 segments (Photoshop resources: IPTC city, country, creator,
    /// dates) into comments of spaces, in place.
    ///
    /// Measured on camera JPEGs: `CGImageDestinationCopyImageSource` carries APP13 through even with
    /// replaced metadata. Removing the segment would shift everything after it and break the MPF
    /// offsets that locate a gain map, so it keeps its length and becomes a COM segment instead.
    static func blankJPEGIPTC(in data: inout Data) {
        var bytes = [UInt8](data)
        guard bytes.count > 4, bytes[0] == 0xFF, bytes[1] == 0xD8 else { return }
        var index = 2
        while index + 4 <= bytes.count, bytes[index] == 0xFF {
            let marker = bytes[index + 1]
            // Start of scan or end of image: the primary image's header is over.
            if marker == 0xDA || marker == 0xD9 { break }
            // Fill bytes and markers without a length.
            if marker == 0xFF { index += 1; continue }
            if (0xD0 ... 0xD7).contains(marker) || marker == 0x01 { index += 2; continue }
            let length = Int(bytes[index + 2]) << 8 | Int(bytes[index + 3])
            guard length >= 2, index + 2 + length <= bytes.count else { break }
            if marker == 0xED {
                bytes[index + 1] = 0xFE
                for offset in (index + 4) ..< (index + 2 + length) { bytes[offset] = UInt8(ascii: " ") }
            }
            index += 2 + length
        }
        data = Data(bytes)
    }

    fileprivate static func isKeptXMPName(_ qualified: String) -> Bool {
        if keptXMPProperties.contains(qualified) { return true }
        guard let colon = qualified.firstIndex(of: ":") else { return false }
        return keptXMPPrefixes.contains(String(qualified[..<colon]))
    }

    private static func find(_ needle: [UInt8], in haystack: [UInt8], from start: Int) -> Int? {
        guard !needle.isEmpty, haystack.count >= needle.count, start <= haystack.count - needle.count else { return nil }
        var index = start
        while index <= haystack.count - needle.count {
            if haystack[index] == needle[0], Array(haystack[index ..< index + needle.count]) == needle {
                return index
            }
            index += 1
        }
        return nil
    }

    // MARK: - Video

    /// Metadata to hand `AVAssetExportSession` so none of the source's is written.
    ///
    /// Measured: `metadata = nil` or `[]` makes the session translate the source's items (location,
    /// make, model, dates) into the output; any non-empty array replaces them. `.forSharing()` then
    /// also drops location from anything the session still derives from the source.
    static var videoExportMetadata: [AVMetadataItem] {
        let item = AVMutableMetadataItem()
        item.identifier = .quickTimeMetadataSoftware
        item.value = "Shroud" as NSString
        item.dataType = kCMMetadataBaseDataType_UTF8 as String
        return [item]
    }

    static func stripMetadata(from session: AVAssetExportSession) {
        session.metadata = videoExportMetadata
        session.metadataItemFilter = .forSharing()
    }
}

/// A minimal scanner over one XMP packet. It only needs to find element and attribute
/// boundaries — never to build a tree — because disallowed properties are blanked, not moved.
private nonisolated struct XMPBlanker {
    var storage: [UInt8]
    let range: Range<Int>

    /// Blanks disallowed properties inside `range` and returns the whole buffer.
    mutating func run() -> [UInt8] {
        var index = range.lowerBound
        while index < range.upperBound {
            guard storage[index] == UInt8(ascii: "<") else { index += 1; continue }
            if starts(with: "<?", at: index) { index = after("?>", from: index); continue }
            if starts(with: "<!--", at: index) { index = after("-->", from: index); continue }
            if starts(with: "<![CDATA[", at: index) { index = after("]]>", from: index); continue }
            if starts(with: "</", at: index) { index = after(">", from: index); continue }

            let tag = parseStartTag(at: index)
            if isStructural(tag.name) {
                for attribute in tag.attributes where !keepsAttribute(attribute.name) {
                    blank(attribute.span)
                }
                index = tag.end
            } else if MediaMetadataScrubber.isKeptXMPName(tag.name) {
                index = tag.selfClosing ? tag.end : endOfElement(named: tag.name, openedAt: tag.end)
            } else {
                let end = tag.selfClosing ? tag.end : endOfElement(named: tag.name, openedAt: tag.end)
                blank(index ..< end)
                index = end
            }
        }
        return storage
    }

    private struct Attribute { let name: String; let span: Range<Int> }
    private struct StartTag { let name: String; let attributes: [Attribute]; let end: Int; let selfClosing: Bool }

    private func isStructural(_ name: String) -> Bool {
        name == "x:xmpmeta" || name == "x:xapmeta" || name.hasPrefix("rdf:")
    }

    private func keepsAttribute(_ name: String) -> Bool {
        name == "xmlns" || name.hasPrefix("xmlns:") || name.hasPrefix("rdf:") || name.hasPrefix("x:")
            || name.hasPrefix("xml:") || MediaMetadataScrubber.isKeptXMPName(name)
    }

    private func isNameByte(_ byte: UInt8) -> Bool {
        !(byte == UInt8(ascii: " ") || byte == UInt8(ascii: "\t") || byte == UInt8(ascii: "\n")
            || byte == UInt8(ascii: "\r") || byte == UInt8(ascii: ">") || byte == UInt8(ascii: "/")
            || byte == UInt8(ascii: "="))
    }

    private func isSpace(_ byte: UInt8) -> Bool {
        byte == UInt8(ascii: " ") || byte == UInt8(ascii: "\t") || byte == UInt8(ascii: "\n") || byte == UInt8(ascii: "\r")
    }

    private func parseStartTag(at start: Int) -> StartTag {
        var index = start + 1
        let nameStart = index
        while index < range.upperBound, isNameByte(storage[index]) { index += 1 }
        let name = String(decoding: storage[nameStart ..< index], as: UTF8.self)
        var attributes: [Attribute] = []
        while index < range.upperBound {
            let spanStart = index
            while index < range.upperBound, isSpace(storage[index]) { index += 1 }
            guard index < range.upperBound else { break }
            if storage[index] == UInt8(ascii: ">") {
                return StartTag(name: name, attributes: attributes, end: index + 1, selfClosing: false)
            }
            if storage[index] == UInt8(ascii: "/") {
                return StartTag(name: name, attributes: attributes, end: min(index + 2, range.upperBound), selfClosing: true)
            }
            let attributeNameStart = index
            while index < range.upperBound, isNameByte(storage[index]) { index += 1 }
            let attributeName = String(decoding: storage[attributeNameStart ..< index], as: UTF8.self)
            while index < range.upperBound, isSpace(storage[index]) || storage[index] == UInt8(ascii: "=") { index += 1 }
            guard index < range.upperBound else { break }
            let quote = storage[index]
            if quote == UInt8(ascii: "\"") || quote == UInt8(ascii: "'") {
                index += 1
                while index < range.upperBound, storage[index] != quote { index += 1 }
                index = min(index + 1, range.upperBound)
            }
            attributes.append(Attribute(name: attributeName, span: spanStart ..< index))
        }
        return StartTag(name: name, attributes: attributes, end: range.upperBound, selfClosing: false)
    }

    /// Index just past the end tag matching an element opened before `start`, counting nested
    /// elements of the same name.
    private func endOfElement(named name: String, openedAt start: Int) -> Int {
        let open = Array("<\(name)".utf8)
        let close = Array("</\(name)".utf8)
        var depth = 1
        var index = start
        while index < range.upperBound {
            if matches(close, at: index) {
                depth -= 1
                let end = after(">", from: index)
                if depth == 0 { return end }
                index = end
            } else if matches(open, at: index), index + open.count < range.upperBound,
                      !isNameByte(storage[index + open.count]) || storage[index + open.count] == UInt8(ascii: "/")
            {
                let tag = parseStartTag(at: index)
                if !tag.selfClosing { depth += 1 }
                index = tag.end
            } else {
                index += 1
            }
        }
        return range.upperBound
    }

    private func matches(_ needle: [UInt8], at index: Int) -> Bool {
        guard index + needle.count <= range.upperBound else { return false }
        for offset in 0 ..< needle.count where storage[index + offset] != needle[offset] { return false }
        return true
    }

    private func starts(with text: String, at index: Int) -> Bool { matches(Array(text.utf8), at: index) }

    private func after(_ text: String, from index: Int) -> Int {
        let needle = Array(text.utf8)
        var cursor = index
        while cursor < range.upperBound {
            if matches(needle, at: cursor) { return cursor + needle.count }
            cursor += 1
        }
        return range.upperBound
    }

    private mutating func blank(_ span: Range<Int>) {
        for index in span where index < range.upperBound { storage[index] = UInt8(ascii: " ") }
    }
}
