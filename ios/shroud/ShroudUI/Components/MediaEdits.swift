import CoreImage
import CoreImage.CIFilterBuiltins
import PencilKit
import SwiftUI
import UIKit

/// A colour/tone preset from the compose filter strip.
nonisolated enum MediaFilter: String, CaseIterable, Identifiable, Sendable {
    case none, vivid, warm, cool, fade, dramatic, mono, noir

    var id: String { rawValue }

    var label: String {
        switch self {
        case .none: "Original"
        case .vivid: "Vivid"
        case .warm: "Warm"
        case .cool: "Cool"
        case .fade: "Fade"
        case .dramatic: "Dramatic"
        case .mono: "Mono"
        case .noir: "Noir"
        }
    }

    /// Applies the preset at `intensity` (0…1), blending back toward the original.
    func apply(to input: CIImage, intensity: Double) -> CIImage {
        guard self != .none, intensity > 0.001 else { return input }
        let filtered = transform(input)
        guard intensity < 0.999 else { return filtered }

        // Dissolve back toward the source so the intensity slider is continuous.
        let blend = CIFilter.dissolveTransition()
        blend.inputImage = input
        blend.targetImage = filtered
        blend.time = Float(intensity)
        return blend.outputImage ?? filtered
    }

    private func transform(_ input: CIImage) -> CIImage {
        switch self {
        case .none:
            return input
        case .vivid:
            let f = CIFilter.colorControls()
            f.inputImage = input
            f.saturation = 1.45
            f.contrast = 1.12
            return f.outputImage ?? input
        case .warm:
            let f = CIFilter.temperatureAndTint()
            f.inputImage = input
            f.neutral = CIVector(x: 5200, y: 0)
            f.targetNeutral = CIVector(x: 6800, y: 0)
            return f.outputImage ?? input
        case .cool:
            let f = CIFilter.temperatureAndTint()
            f.inputImage = input
            f.neutral = CIVector(x: 6800, y: 0)
            f.targetNeutral = CIVector(x: 5000, y: 0)
            return f.outputImage ?? input
        case .fade:
            let f = CIFilter.photoEffectFade()
            f.inputImage = input
            return f.outputImage ?? input
        case .dramatic:
            let f = CIFilter.photoEffectTransfer()
            f.inputImage = input
            return f.outputImage ?? input
        case .mono:
            let f = CIFilter.photoEffectMono()
            f.inputImage = input
            return f.outputImage ?? input
        case .noir:
            let f = CIFilter.photoEffectNoir()
            f.inputImage = input
            return f.outputImage ?? input
        }
    }
}

/// A movable caption sticker placed on the photo.
nonisolated struct TextOverlay: Identifiable, Equatable, Sendable {
    enum Style: String, CaseIterable, Sendable {
        /// White text with a soft shadow.
        case plain
        /// Text knocked out of a solid rounded slab.
        case filled
        /// Text with a contrasting stroke.
        case outlined

        var next: Style {
            let all = Style.allCases
            let idx = all.firstIndex(of: self) ?? 0
            return all[(idx + 1) % all.count]
        }

        var systemImage: String {
            switch self {
            case .plain: "textformat"
            case .filled: "textformat.alt"
            case .outlined: "character.textbox"
            }
        }
    }

    let id: UUID
    var string: String
    /// Index into `TextOverlay.palette`.
    var colorIndex: Int
    var style: Style
    /// Centre in normalised image coordinates (0…1).
    var center: CGPoint
    /// Cap height as a fraction of the image's **width**, so it survives any output size.
    var relativeFontSize: CGFloat
    var rotation: Double
    var scale: CGFloat

    init(
        id: UUID = UUID(),
        string: String = "",
        colorIndex: Int = 0,
        style: Style = .plain,
        center: CGPoint = CGPoint(x: 0.5, y: 0.5),
        relativeFontSize: CGFloat = 0.09,
        rotation: Double = 0,
        scale: CGFloat = 1
    ) {
        self.id = id
        self.string = string
        self.colorIndex = colorIndex
        self.style = style
        self.center = center
        self.relativeFontSize = relativeFontSize
        self.rotation = rotation
        self.scale = scale
    }

    static let palette: [Color] = [
        .white,
        .black,
        Color(red: 0.98, green: 0.24, blue: 0.31),
        Color(red: 0.99, green: 0.70, blue: 0.16),
        Color(red: 0.28, green: 0.80, blue: 0.45),
        Color(red: 0.20, green: 0.56, blue: 0.93),
        Color(red: 0.66, green: 0.35, blue: 0.95),
    ]

    static let uiPalette: [UIColor] = [
        .white,
        .black,
        UIColor(red: 0.98, green: 0.24, blue: 0.31, alpha: 1),
        UIColor(red: 0.99, green: 0.70, blue: 0.16, alpha: 1),
        UIColor(red: 0.28, green: 0.80, blue: 0.45, alpha: 1),
        UIColor(red: 0.20, green: 0.56, blue: 0.93, alpha: 1),
        UIColor(red: 0.66, green: 0.35, blue: 0.95, alpha: 1),
    ]

    var color: Color { Self.palette[colorIndex % Self.palette.count] }
    var uiColor: UIColor { Self.uiPalette[colorIndex % Self.uiPalette.count] }

    /// Readable contrast for `filled` slabs and `outlined` strokes.
    var contrastUIColor: UIColor {
        var white: CGFloat = 0
        uiColor.getWhite(&white, alpha: nil)
        return white > 0.6 ? .black : .white
    }
}

/// Every edit the compose screen can apply, held as parameters rather than baked pixels.
///
/// Human: Keeping edits as data is what lets an untouched photo still be sent byte-for-byte —
/// the moment we rasterise, "Original" stops meaning original. Rendering happens once, at send,
/// against the full-resolution source.
nonisolated struct MediaEdits: Equatable, Sendable {
    /// Crop in normalised coordinates of the **rotated** image.
    var cropRect = CGRect(x: 0, y: 0, width: 1, height: 1)
    /// Clockwise 90° steps.
    var rotationQuarters = 0
    var mirrored = false
    var filter: MediaFilter = .none
    var filterIntensity: Double = 1
    /// `PKDrawing.dataRepresentation()`, authored over the cropped image.
    var drawing: Data?
    var texts: [TextOverlay] = []

    /// True when sending can skip rendering entirely and ship the source bytes.
    var isIdentity: Bool {
        cropRect == CGRect(x: 0, y: 0, width: 1, height: 1)
            && rotationQuarters == 0
            && !mirrored
            && filter == .none
            && drawing == nil
            && texts.isEmpty
    }

    /// Which tool chips should read as "active" in the compose toolbar.
    var hasCrop: Bool {
        cropRect != CGRect(x: 0, y: 0, width: 1, height: 1) || rotationQuarters != 0 || mirrored
    }

    var hasDrawing: Bool { drawing != nil }
}

/// Bakes `MediaEdits` into pixels. Off the main actor — this runs at full resolution.
nonisolated enum MediaEditRenderer {
    private static let context = CIContext(options: [.useSoftwareRenderer: false])

    /// Applies rotation, crop, filter, drawing and text, in that order.
    static func render(_ image: UIImage, edits: MediaEdits) -> UIImage {
        guard !edits.isIdentity else { return image }

        let oriented = rotated(upright(image), quarters: edits.rotationQuarters, mirrored: edits.mirrored)
        let cropped = cropping(oriented, to: edits.cropRect)
        let filtered = filtering(cropped, filter: edits.filter, intensity: edits.filterIntensity)
        return annotating(filtered, edits: edits)
    }

    /// Just the rotation half of the pipeline — what the crop editor previews against.
    static func oriented(_ image: UIImage, quarters: Int, mirrored: Bool) -> UIImage {
        rotated(upright(image), quarters: quarters, mirrored: mirrored)
    }

    /// Redraws into an `.up` bitmap so later pixel maths doesn't have to reason about EXIF.
    private static func upright(_ image: UIImage) -> UIImage {
        guard image.imageOrientation != .up else { return image }
        let size = CGSize(
            width: image.size.width * image.scale,
            height: image.size.height * image.scale
        )
        let format = UIGraphicsImageRendererFormat.preferred()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }

    private static func rotated(_ image: UIImage, quarters: Int, mirrored: Bool) -> UIImage {
        let steps = ((quarters % 4) + 4) % 4
        guard steps != 0 || mirrored else { return image }

        let source = CGSize(
            width: image.size.width * image.scale,
            height: image.size.height * image.scale
        )
        let swapped = steps % 2 == 1
        let target = swapped ? CGSize(width: source.height, height: source.width) : source

        let format = UIGraphicsImageRendererFormat.preferred()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: target, format: format).image { ctx in
            let cg = ctx.cgContext
            cg.translateBy(x: target.width / 2, y: target.height / 2)
            cg.rotate(by: CGFloat(steps) * .pi / 2)
            if mirrored { cg.scaleBy(x: -1, y: 1) }
            image.draw(in: CGRect(
                x: -source.width / 2,
                y: -source.height / 2,
                width: source.width,
                height: source.height
            ))
        }
    }

    private static func cropping(_ image: UIImage, to rect: CGRect) -> UIImage {
        guard rect != CGRect(x: 0, y: 0, width: 1, height: 1), let cgImage = image.cgImage else {
            return image
        }
        let width = CGFloat(cgImage.width)
        let height = CGFloat(cgImage.height)
        let pixels = CGRect(
            x: (rect.origin.x * width).rounded(),
            y: (rect.origin.y * height).rounded(),
            width: max(1, (rect.width * width).rounded()),
            height: max(1, (rect.height * height).rounded())
        ).intersection(CGRect(x: 0, y: 0, width: width, height: height))

        guard !pixels.isEmpty, let cropped = cgImage.cropping(to: pixels) else { return image }
        return UIImage(cgImage: cropped, scale: 1, orientation: .up)
    }

    private static func filtering(_ image: UIImage, filter: MediaFilter, intensity: Double) -> UIImage {
        guard filter != .none, let cgImage = image.cgImage else { return image }
        let input = CIImage(cgImage: cgImage)
        let output = filter.apply(to: input, intensity: intensity)
        guard let rendered = context.createCGImage(output, from: input.extent) else { return image }
        return UIImage(cgImage: rendered, scale: 1, orientation: .up)
    }

    /// Draws the PencilKit strokes and text stickers over the finished pixels.
    private static func annotating(_ image: UIImage, edits: MediaEdits) -> UIImage {
        guard edits.drawing != nil || !edits.texts.isEmpty else { return image }

        let size = CGSize(
            width: image.size.width * image.scale,
            height: image.size.height * image.scale
        )
        let format = UIGraphicsImageRendererFormat.preferred()
        format.scale = 1
        format.opaque = true

        return UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            image.draw(in: CGRect(origin: .zero, size: size))

            if let data = edits.drawing, let drawing = try? PKDrawing(data: data) {
                // Strokes were authored over the cropped photo in a unit square, so stretching
                // the drawing's own unit bounds back over the output keeps them aligned.
                let strokes = drawing.image(from: CGRect(x: 0, y: 0, width: 1, height: 1), scale: size.width)
                strokes.draw(in: CGRect(origin: .zero, size: size))
            }

            for text in edits.texts where !text.string.isEmpty {
                draw(text, in: ctx.cgContext, size: size)
            }
        }
    }

    private static func draw(_ overlay: TextOverlay, in context: CGContext, size: CGSize) {
        let pointSize = max(8, overlay.relativeFontSize * size.width * overlay.scale)
        let font = UIFont.systemFont(ofSize: pointSize, weight: .bold)

        var attributes: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: overlay.uiColor,
        ]
        if overlay.style == .outlined {
            attributes[.strokeColor] = overlay.contrastUIColor
            // Negative width strokes *and* fills; positive would hollow the glyphs out.
            attributes[.strokeWidth] = -6.0
        }
        if overlay.style == .plain {
            let shadow = NSShadow()
            shadow.shadowColor = UIColor.black.withAlphaComponent(0.45)
            shadow.shadowBlurRadius = pointSize * 0.12
            shadow.shadowOffset = CGSize(width: 0, height: pointSize * 0.03)
            attributes[.shadow] = shadow
        }

        let string = NSAttributedString(string: overlay.string, attributes: attributes)
        let bounds = string.boundingRect(
            with: CGSize(width: size.width * 0.92, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            context: nil
        )
        let padding = overlay.style == .filled ? pointSize * 0.28 : 0
        let boxSize = CGSize(
            width: bounds.width + padding * 2,
            height: bounds.height + padding * 2
        )

        context.saveGState()
        context.translateBy(x: overlay.center.x * size.width, y: overlay.center.y * size.height)
        context.rotate(by: overlay.rotation)

        if overlay.style == .filled {
            let box = CGRect(
                x: -boxSize.width / 2,
                y: -boxSize.height / 2,
                width: boxSize.width,
                height: boxSize.height
            )
            let path = UIBezierPath(roundedRect: box, cornerRadius: pointSize * 0.26)
            overlay.uiColor.setFill()
            path.fill()
        }

        let textColor = overlay.style == .filled ? overlay.contrastUIColor : overlay.uiColor
        var drawAttributes = attributes
        drawAttributes[.foregroundColor] = textColor
        NSAttributedString(string: overlay.string, attributes: drawAttributes).draw(
            with: CGRect(
                x: -bounds.width / 2,
                y: -bounds.height / 2,
                width: bounds.width,
                height: bounds.height
            ),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            context: nil
        )
        context.restoreGState()
    }
}
