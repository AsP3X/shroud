import AVFoundation
import CoreImage
import CoreVideo
import Foundation
import ImageIO
import Testing
import UniformTypeIdentifiers
@testable import shroud

/// Photos and videos leave the device without location, capture time or device details, and
/// "Original" still means the original pixels.
struct MediaMetadataScrubberTests {
    // MARK: - Photos

    @Test(arguments: [UTType.jpeg, .heic, .png])
    func removesLocationCameraAndDates(_ type: UTType) throws {
        let original = Self.taggedPhoto(type)
        #expect(!MediaMetadataScrubber.leftoverMetadata(in: original).isEmpty)

        let clean = try #require(MediaMetadataScrubber.scrubImage(original))

        #expect(MediaMetadataScrubber.leftoverMetadata(in: clean).isEmpty)
        let raw = String(decoding: clean, as: UTF8.self)
        for secret in ["SERIAL-0042", "Test Phone", "2026:09:01", "2026-09-01", "Reykjavik", "Iceland", "Test Photographer"] {
            #expect(!raw.contains(secret), "\(secret) survived in \(type)")
        }
    }

    @Test(arguments: [UTType.jpeg, .heic, .png])
    func keepsPixelsAndOrientation(_ type: UTType) throws {
        let original = Self.taggedPhoto(type)
        let clean = try #require(MediaMetadataScrubber.scrubImage(original))

        #expect(Self.orientation(of: clean) == 6)
        #expect(Self.pixels(of: clean) == Self.pixels(of: original))
        #expect(CGImageSourceGetType(CGImageSourceCreateWithData(clean as CFData, nil)!) as String? == type.identifier)
    }

    @Test func keepsTheHDRGainMap() throws {
        let original = try Self.hdrPhoto()
        #expect(Self.hasGainMap(original))

        let clean = try #require(MediaMetadataScrubber.scrubImage(original))

        #expect(Self.hasGainMap(clean))
        #expect(Self.pixels(of: clean) == Self.pixels(of: original))
    }

    @Test func animatedPNGKeepsItsFramesAndTiming() throws {
        let output = NSMutableData()
        let destination = CGImageDestinationCreateWithData(output, UTType.png.identifier as CFString, 3, nil)!
        CGImageDestinationSetProperties(destination, [
            kCGImagePropertyPNGDictionary: [kCGImagePropertyAPNGLoopCount: 0],
        ] as CFDictionary)
        for frame in 0 ..< 3 {
            let context = CGContext(
                data: nil, width: 32, height: 32, bitsPerComponent: 8, bytesPerRow: 0,
                space: CGColorSpace(name: CGColorSpace.sRGB)!,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            )!
            context.setFillColor(CGColor(srgbRed: CGFloat(frame) / 3, green: 0.4, blue: 0.8, alpha: 1))
            context.fill(CGRect(x: 0, y: 0, width: 32, height: 32))
            CGImageDestinationAddImage(destination, context.makeImage()!, [
                kCGImagePropertyPNGDictionary: [kCGImagePropertyAPNGDelayTime: 0.25],
                kCGImagePropertyGPSDictionary: [kCGImagePropertyGPSLatitude: 52.52],
            ] as CFDictionary)
        }
        CGImageDestinationFinalize(destination)

        let clean = try #require(MediaMetadataScrubber.scrubImage(output as Data))

        #expect(MediaMetadataScrubber.leftoverMetadata(in: clean).isEmpty)
        let source = try #require(CGImageSourceCreateWithData(clean as CFData, nil))
        #expect(CGImageSourceGetCount(source) == 3)
        let frame = CGImageSourceCopyPropertiesAtIndex(source, 2, nil) as? [CFString: Any]
        let png = frame?[kCGImagePropertyPNGDictionary] as? [CFString: Any]
        #expect((png?[kCGImagePropertyAPNGDelayTime] as? Double).map { abs($0 - 0.25) < 0.01 } == true)
    }

    @Test func originalSendCarriesNoMetadata() throws {
        let encoded = try MediaCrypto.encode(
            .fileData(Self.taggedPhoto(.heic)),
            maxEdge: 16_384,
            compression: 1,
            allowsPassthrough: true
        )
        #expect(encoded.mime == "image/heic")
        #expect(MediaMetadataScrubber.leftoverMetadata(in: encoded.data).isEmpty)
        // Orientation 6 swaps the axes for the recipient.
        #expect(encoded.width == 48 && encoded.height == 64)
    }

    @Test func reencodedSendCarriesNoMetadata() throws {
        let encoded = try MediaCrypto.encode(
            .fileData(Self.taggedPhoto(.jpeg)),
            maxEdge: 32,
            compression: 0.8,
            allowsPassthrough: false
        )
        #expect(MediaMetadataScrubber.leftoverMetadata(in: encoded.data).isEmpty)
    }

    // MARK: - XMP

    @Test func blanksDisallowedXMPInPlace() throws {
        let packet = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="XMP Core 6.0.0"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">\
        <rdf:Description rdf:about="" xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/" photoshop:City="Berlin" hdrgm:Version="1.0">\
        <photoshop:DateCreated>2026-09-01T12:00:00</photoshop:DateCreated>\
        <tiff:Orientation>6</tiff:Orientation>\
        <hdrgm:GainMapMax><rdf:Seq><rdf:li>2.3</rdf:li></rdf:Seq></hdrgm:GainMapMax>\
        <mwg-rs:Regions rdf:parseType="Resource"><mwg-rs:Name>Alice</mwg-rs:Name><mwg-rs:Regions/></mwg-rs:Regions>\
        <exif:Flash exif:Fired="False"/>\
        </rdf:Description></rdf:RDF></x:xmpmeta>
        """
        var data = Data("HEADER".utf8) + Data(packet.utf8) + Data("TRAILER".utf8)
        let length = data.count

        MediaMetadataScrubber.blankXMP(in: &data)

        #expect(data.count == length)
        let text = String(decoding: data, as: UTF8.self)
        for gone in ["Berlin", "DateCreated", "Alice", "Regions", "exif:Flash"] {
            #expect(!text.contains(gone), "\(gone) survived")
        }
        for kept in ["hdrgm:Version=\"1.0\"", "<tiff:Orientation>6</tiff:Orientation>", "<rdf:li>2.3</rdf:li>", "HEADER", "TRAILER"] {
            #expect(text.contains(kept), "\(kept) was removed")
        }
        let xml = String(text.dropFirst("HEADER".count).dropLast("TRAILER".count))
        #expect(XMLParser(data: Data(xml.utf8)).parse())
    }

    @Test func allowListKeepsOnlyRenderingTags() {
        #expect(MediaMetadataScrubber.isAllowedTagPath("tiff:Orientation"))
        #expect(MediaMetadataScrubber.isAllowedTagPath("hdrgm:GainMapMax[0]"))
        #expect(MediaMetadataScrubber.isAllowedTagPath("iio:hasXMP"))
        #expect(!MediaMetadataScrubber.isAllowedTagPath("tiff:Model"))
        #expect(!MediaMetadataScrubber.isAllowedTagPath("exif:GPSLatitude"))
        #expect(!MediaMetadataScrubber.isAllowedTagPath("photoshop:DateCreated"))
        #expect(!MediaMetadataScrubber.isAllowedTagPath("unprefixed"))
    }

    // MARK: - Video

    @Test func compressedVideoCarriesNoLocation() async throws {
        let movie = try await Self.taggedMovie(type: .mov, extension: "mov")
        defer { try? FileManager.default.removeItem(at: movie) }
        #expect(try await !Self.metadataIdentifiers(of: movie).isEmpty)

        let encoded = try await VideoMedia.encode(sourceURL: movie, quality: .high)

        #expect(try await Self.identifyingMetadata(in: encoded.data).isEmpty)
    }

    @Test func passthroughMp4IsRewrittenWithoutLocation() async throws {
        let movie = try await Self.taggedMovie(type: .mp4, extension: "mp4")
        defer { try? FileManager.default.removeItem(at: movie) }
        #expect(try await !Self.metadataIdentifiers(of: movie).isEmpty)

        let encoded = try await VideoMedia.encode(sourceURL: movie, quality: .original)

        #expect(encoded.data != (try Data(contentsOf: movie)), "the source file went out as it is")
        #expect(try await Self.identifyingMetadata(in: encoded.data).isEmpty)
        #expect(encoded.width == 320 && encoded.height == 240)
    }

    // MARK: - Fixtures

    private static func taggedPhoto(_ type: UTType) -> Data {
        let context = CGContext(
            data: nil, width: 64, height: 48, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpace(name: CGColorSpace.displayP3)!,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )!
        context.setFillColor(CGColor(srgbRed: 0.1, green: 0.5, blue: 0.9, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: 40, height: 48))
        let output = NSMutableData()
        let destination = CGImageDestinationCreateWithData(output, type.identifier as CFString, 1, nil)!
        CGImageDestinationAddImage(destination, context.makeImage()!, [
            kCGImagePropertyOrientation: 6,
            kCGImagePropertyGPSDictionary: [
                kCGImagePropertyGPSLatitude: 52.52, kCGImagePropertyGPSLatitudeRef: "N",
                kCGImagePropertyGPSLongitude: 13.40, kCGImagePropertyGPSLongitudeRef: "E",
            ],
            kCGImagePropertyTIFFDictionary: [
                kCGImagePropertyTIFFMake: "Apple", kCGImagePropertyTIFFModel: "Test Phone",
                kCGImagePropertyTIFFSoftware: "26.5",
            ],
            kCGImagePropertyExifDictionary: [
                kCGImagePropertyExifDateTimeOriginal: "2026:09:01 12:00:00",
                kCGImagePropertyExifBodySerialNumber: "SERIAL-0042",
                kCGImagePropertyExifLensModel: "Test Phone back camera",
            ],
            // Camera and editor JPEGs carry this as APP13, which a metadata copy keeps.
            kCGImagePropertyIPTCDictionary: [
                kCGImagePropertyIPTCCity: "Reykjavik",
                kCGImagePropertyIPTCCountryPrimaryLocationName: "Iceland",
                kCGImagePropertyIPTCByline: ["Test Photographer"],
            ],
        ] as CFDictionary)
        CGImageDestinationFinalize(destination)
        return output as Data
    }

    /// A HEIC with an ISO gain map, as Core Image writes HDR photos.
    private static func hdrPhoto() throws -> Data {
        let extended = CGColorSpace(name: CGColorSpace.extendedLinearDisplayP3)!
        let ramp = CIFilter(name: "CILinearGradient", parameters: [
            "inputPoint0": CIVector(x: 0, y: 0),
            "inputPoint1": CIVector(x: 128, y: 0),
            "inputColor0": CIColor(red: 0, green: 0, blue: 0),
            "inputColor1": CIColor(red: 4, green: 4, blue: 4, colorSpace: extended)!,
        ])!.outputImage!.cropped(to: CGRect(x: 0, y: 0, width: 128, height: 96))
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("hdr-\(UUID().uuidString).heic")
        defer { try? FileManager.default.removeItem(at: url) }
        try CIContext().writeHEIFRepresentation(
            of: ramp, to: url, format: .RGBA8,
            colorSpace: CGColorSpace(name: CGColorSpace.displayP3)!,
            options: [CIImageRepresentationOption.hdrImage: ramp]
        )
        return try Data(contentsOf: url)
    }

    private static func orientation(of data: Data) -> Int? {
        let source = CGImageSourceCreateWithData(data as CFData, nil)!
        let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        return properties?[kCGImagePropertyOrientation] as? Int
    }

    private static func pixels(of data: Data) -> Data? {
        let source = CGImageSourceCreateWithData(data as CFData, nil)!
        return CGImageSourceCreateImageAtIndex(source, 0, nil)?.dataProvider?.data as Data?
    }

    private static func hasGainMap(_ data: Data) -> Bool {
        let source = CGImageSourceCreateWithData(data as CFData, nil)!
        return CGImageSourceCopyAuxiliaryDataInfoAtIndex(source, 0, kCGImageAuxiliaryDataTypeISOGainMap) != nil
    }

    private static func textItem(_ identifier: AVMetadataIdentifier, _ value: String) -> AVMetadataItem {
        let item = AVMutableMetadataItem()
        item.identifier = identifier
        item.value = value as NSString
        item.dataType = kCMMetadataBaseDataType_UTF8 as String
        return item
    }

    /// A one-second 320×240 H.264 clip tagged with a location, device and capture date.
    private static func taggedMovie(type: AVFileType, extension ext: String) async throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("scrub-test-\(UUID().uuidString).\(ext)")
        let writer = try AVAssetWriter(outputURL: url, fileType: type)
        writer.metadata = [
            textItem(.quickTimeMetadataLocationISO6709, "+52.5200+013.4050+034.000/"),
            textItem(.quickTimeMetadataMake, "Apple"),
            textItem(.quickTimeMetadataModel, "Test Phone"),
            textItem(.quickTimeMetadataCreationDate, "2026-09-01T12:00:00+0200"),
        ]
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: 320, AVVideoHeightKey: 240,
        ])
        input.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: 320,
            kCVPixelBufferHeightKey as String: 240,
        ])
        writer.add(input)
        writer.startWriting()
        writer.startSession(atSourceTime: .zero)
        for frame in 0 ..< 30 {
            while !input.isReadyForMoreMediaData { try await Task.sleep(nanoseconds: 1_000_000) }
            var buffer: CVPixelBuffer?
            CVPixelBufferPoolCreatePixelBuffer(nil, try #require(adaptor.pixelBufferPool), &buffer)
            let pixels = try #require(buffer)
            CVPixelBufferLockBaseAddress(pixels, [])
            memset(CVPixelBufferGetBaseAddress(pixels), Int32(frame * 8 % 255), CVPixelBufferGetDataSize(pixels))
            CVPixelBufferUnlockBaseAddress(pixels, [])
            adaptor.append(pixels, withPresentationTime: CMTime(value: CMTimeValue(frame), timescale: 30))
        }
        input.markAsFinished()
        await writer.finishWriting()
        try #require(writer.status == .completed)
        return url
    }

    private static func metadataIdentifiers(of url: URL) async throws -> [String] {
        let asset = AVURLAsset(url: url)
        var identifiers: [String] = []
        for format in try await asset.load(.availableMetadataFormats) {
            identifiers += try await asset.loadMetadata(for: format).compactMap { $0.identifier?.rawValue }
        }
        for track in try await asset.load(.tracks) {
            identifiers += try await track.load(.metadata).compactMap { $0.identifier?.rawValue }
        }
        return identifiers
    }

    /// Metadata items that say where, when or on what a clip was recorded.
    private static func identifyingMetadata(in data: Data) async throws -> [String] {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("scrub-out-\(UUID().uuidString).mp4")
        try data.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let raw = String(decoding: data, as: UTF8.self)
        let leaks = ["+52.5200", "Test Phone", "2026-09-01"].filter { raw.contains($0) }
        let items = try await metadataIdentifiers(of: url).filter { identifier in
            ["loci", "location", "date", "make", "model"].contains { identifier.lowercased().contains($0) }
        }
        return leaks + items
    }
}
