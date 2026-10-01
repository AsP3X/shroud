// Writes the ImageIO fixtures next to this script, built exactly like the iOS tests build theirs
// (ios/shroudTests/MediaMetadataScrubberTests.swift:49-68, :169-222). Run on a Mac from the
// repository root:
//
//     swift android/app/src/test/resources/media/imageio/generate.swift android/app/src/test/resources/media/imageio
//
// The outputs are committed; this script documents (and can reproduce) how they were made. ImageIO
// is the stack an iPhone writes its photos with, so these carry real iOS container layouts
// (APP13 IPTC, ICC profiles, HEIF Exif/XMP items, gain maps) — but no camera maker notes.
import CoreImage
import Foundation
import ImageIO
import UniformTypeIdentifiers

let directory = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : ".")

/// `MediaMetadataScrubberTests.taggedPhoto(_:)`: 64×48, orientation 6, GPS, TIFF, Exif and IPTC secrets.
func taggedPhoto(_ type: UTType) -> Data {
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

/// `animatedPNGKeepsItsFramesAndTiming`: three 32×32 frames, 0.25 s each, GPS on every frame.
func animatedPNG() -> Data {
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
    return output as Data
}

/// The ramp of `hdrPhoto()`: 128×96, black to 4× diffuse white in extended linear Display P3.
func hdrRamp() -> CIImage {
    let extended = CGColorSpace(name: CGColorSpace.extendedLinearDisplayP3)!
    return CIFilter(name: "CILinearGradient", parameters: [
        "inputPoint0": CIVector(x: 0, y: 0),
        "inputPoint1": CIVector(x: 128, y: 0),
        "inputColor0": CIColor(red: 0, green: 0, blue: 0),
        "inputColor1": CIColor(red: 4, green: 4, blue: 4, colorSpace: extended)!,
    ])!.outputImage!.cropped(to: CGRect(x: 0, y: 0, width: 128, height: 96))
}

/// `hdrPhoto()`: a HEIC with an ISO gain map, as Core Image writes HDR photos.
func hdrHEIC() throws -> Data {
    let ramp = hdrRamp()
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("hdr-\(UUID().uuidString).heic")
    defer { try? FileManager.default.removeItem(at: url) }
    try CIContext().writeHEIFRepresentation(
        of: ramp, to: url, format: .RGBA8,
        colorSpace: CGColorSpace(name: CGColorSpace.displayP3)!,
        options: [CIImageRepresentationOption.hdrImage: ramp]
    )
    return try Data(contentsOf: url)
}

/// The same ramp as a gain-map JPEG: a primary image plus an MPF-located gain map image.
func hdrJPEG() throws -> Data {
    let ramp = hdrRamp()
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("hdr-\(UUID().uuidString).jpg")
    defer { try? FileManager.default.removeItem(at: url) }
    try CIContext().writeJPEGRepresentation(
        of: ramp, to: url,
        colorSpace: CGColorSpace(name: CGColorSpace.displayP3)!,
        options: [CIImageRepresentationOption.hdrImage: ramp]
    )
    return try Data(contentsOf: url)
}

try taggedPhoto(.jpeg).write(to: directory.appendingPathComponent("tagged.jpg"))
try taggedPhoto(.heic).write(to: directory.appendingPathComponent("tagged.heic"))
try taggedPhoto(.png).write(to: directory.appendingPathComponent("tagged.png"))
try animatedPNG().write(to: directory.appendingPathComponent("animated.png"))
try hdrHEIC().write(to: directory.appendingPathComponent("hdr-gainmap.heic"))
try hdrJPEG().write(to: directory.appendingPathComponent("hdr-gainmap.jpg"))
