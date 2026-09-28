import SwiftUI
import UIKit

/// Which veil the brand mark draws: the app icon's, with folds and shading, or one flat shape.
enum BrandLogoStyle: String, CaseIterable, Identifiable {
    case detailed
    case simple

    var id: String { rawValue }

    var title: String {
        switch self {
        case .detailed: "Detailed"
        case .simple: "Simple"
        }
    }

    /// The asset catalog icon set for this style; `nil` is the primary `AppIcon`.
    var alternateIconName: String? {
        switch self {
        case .detailed: nil
        case .simple: "AppIconSimple"
        }
    }

    init(alternateIconName: String?) {
        self = alternateIconName == BrandLogoStyle.simple.alternateIconName ? .simple : .detailed
    }
}

/// The chosen logo, shared by every in-app mark and the home screen icon.
///
/// Human: The home screen icon is the record. A logout wipes UserDefaults but not the icon, so a
/// stored flag would let the in-app mark drift from what the home screen shows.
/// Agent: READS `UIApplication.alternateIconName` once; WRITES it through `choose(_:)`.
@MainActor
@Observable
final class BrandLogoPreference {
    static let shared = BrandLogoPreference()

    private(set) var style: BrandLogoStyle
    /// True while iOS changes the icon (its alert is up); a second change would fail.
    private(set) var isChanging = false

    private init() {
        style = BrandLogoStyle(alternateIconName: UIApplication.shared.alternateIconName)
    }

    /// Switches the home screen icon, then the in-app marks. iOS confirms with its own alert.
    func choose(_ newStyle: BrandLogoStyle) async throws {
        guard !isChanging else { return }
        isChanging = true
        defer { isChanging = false }
        let app = UIApplication.shared
        if app.supportsAlternateIcons, app.alternateIconName != newStyle.alternateIconName {
            try await app.setAlternateIconName(newStyle.alternateIconName)
        }
        style = newStyle
    }
}

/// Brand logo mark used on Welcome, auth, lock and the app switcher cover: the app icon itself.
///
/// Human: Drawn, not rasterized, from the same geometry as design/icon/shroud-icon.svg (and
/// shroud-icon-simple.svg), so it stays sharp from a 40 pt banner to the 100 pt lock hero.
/// Agent: READS `BrandLogoPreference.shared.style` unless `style` pins one (the picker preview).
struct BrandLogoMark: View {
    var size: CGFloat = 72
    var style: BrandLogoStyle?

    var body: some View {
        let style = style ?? BrandLogoPreference.shared.style
        Canvas { context, canvasSize in
            BrandIconArt.draw(style, in: &context, side: canvasSize.width)
        }
        .frame(width: size, height: size)
        .clipShape(Self.tileShape(size: size))
        // Decoration: the screens around it say what it is.
        .accessibilityHidden(true)
    }

    /// The home screen icon's mask, so the mark reads as the icon.
    static func tileShape(size: CGFloat) -> RoundedRectangle {
        RoundedRectangle(cornerRadius: size * 0.2237, style: .continuous)
    }
}

/// The icon's backdrop alone, for tiles that stand in for the mark (the login key).
struct BrandTileBackground: View {
    var body: some View {
        Canvas { context, canvasSize in
            BrandIconArt.drawBackdrop(in: &context, side: canvasSize.width, glow: true)
        }
    }
}

/// The app icon's artwork in its 1024-unit space. Keep in sync with design/icon/*.svg.
private enum BrandIconArt {
    static let veil = SVGPath.parse(
        "M512 204C686 204 788 330 792 500C795 612 806 690 818 752C826 792 806 818 776 818C742 818 720 752 684 752C646 752 628 824 590 824C552 824 532 758 494 758C446 758 400 790 340 818C300 836 250 850 206 852C236 820 242 776 236 720C230 650 230 580 232 500C236 330 338 204 512 204Z"
    )
    static let folds = [
        (SVGPath.parse("M596 430C640 530 676 640 684 752C704 790 730 812 760 830C748 690 690 530 596 430Z"), 430.0, 830.0),
        (SVGPath.parse("M424 440C468 540 490 650 494 758C512 790 540 816 572 836C562 690 510 540 424 440Z"), 440.0, 836.0),
    ]

    static let backdrop = Gradient(stops: [
        .init(color: rgb(0x7D7BFA), location: 0),
        .init(color: rgb(0x5E5CE6), location: 0.55),
        .init(color: rgb(0x3432B8), location: 1),
    ])
    static let fold = rgb(0x4B49D8)

    static func draw(_ style: BrandLogoStyle, in context: inout GraphicsContext, side: CGFloat) {
        drawBackdrop(in: &context, side: side, glow: style == .detailed)
        let unit = side / 1024
        switch style {
        case .detailed:
            // Soft drop shadow: blurred in its own unscaled layer so the radius is in points.
            context.drawLayer { layer in
                layer.addFilter(.blur(radius: 22 * 0.9 * unit))
                layer.opacity = 0.35
                placeVeil(&layer, unit: unit)
                layer.translateBy(x: 0, y: 26)
                layer.fill(veil, with: .color(rgb(0x0A0930)))
            }
            var cloth = context
            placeVeil(&cloth, unit: unit)
            cloth.fill(veil, with: .linearGradient(
                Gradient(colors: [.white, rgb(0xE4E3FF)]),
                startPoint: CGPoint(x: 512, y: 204), endPoint: CGPoint(x: 512, y: 852)
            ))
            cloth.clip(to: veil)
            cloth.fill(Path(CGRect(x: 200, y: 200, width: 620, height: 660)), with: .linearGradient(
                Gradient(stops: [
                    .init(color: fold.opacity(0), location: 0.7),
                    .init(color: fold.opacity(0.12), location: 1),
                ]),
                startPoint: CGPoint(x: 200, y: 0), endPoint: CGPoint(x: 820, y: 0)
            ))
            for (path, top, bottom) in folds {
                cloth.fill(path, with: .linearGradient(
                    Gradient(colors: [fold.opacity(0), fold.opacity(0.24)]),
                    startPoint: CGPoint(x: 0, y: top), endPoint: CGPoint(x: 0, y: bottom)
                ))
            }
        case .simple:
            var glyph = context
            placeVeil(&glyph, unit: unit)
            glyph.fill(veil, with: .color(.white))
        }
    }

    static func drawBackdrop(in context: inout GraphicsContext, side: CGFloat, glow: Bool) {
        var art = context
        art.scaleBy(x: side / 1024, y: side / 1024)
        let square = Path(CGRect(x: 0, y: 0, width: 1024, height: 1024))
        art.fill(square, with: .linearGradient(
            backdrop, startPoint: CGPoint(x: 153.6, y: 0), endPoint: CGPoint(x: 870.4, y: 1024)
        ))
        if glow {
            art.fill(square, with: .radialGradient(
                Gradient(colors: [.white.opacity(0.18), .white.opacity(0)]),
                center: CGPoint(x: 512, y: 307.2), startRadius: 0, endRadius: 614.4
            ))
        }
    }

    /// `translate(512 518) scale(0.9) translate(-512 -528)` from the SVG, in points.
    private static func placeVeil(_ context: inout GraphicsContext, unit: CGFloat) {
        context.scaleBy(x: unit, y: unit)
        context.translateBy(x: 512, y: 518)
        context.scaleBy(x: 0.9, y: 0.9)
        context.translateBy(x: -512, y: -528)
    }

    private static func rgb(_ hex: UInt32) -> Color {
        Color(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

/// Reads the `M`/`C`/`Z` subset of SVG path data the icon masters use, so paths paste verbatim.
private enum SVGPath {
    static func parse(_ data: String) -> Path {
        var tokens: [String] = []
        var number = ""
        for character in data {
            if character.isLetter {
                if !number.isEmpty { tokens.append(number); number = "" }
                tokens.append(String(character))
            } else if character == " " || character == "," {
                if !number.isEmpty { tokens.append(number); number = "" }
            } else {
                number.append(character)
            }
        }
        if !number.isEmpty { tokens.append(number) }

        var path = Path()
        var command = ""
        var values: [CGFloat] = []
        func flush() {
            switch command {
            case "M" where values.count == 2:
                path.move(to: CGPoint(x: values[0], y: values[1]))
            case "C":
                for i in stride(from: 0, to: values.count - 5, by: 6) {
                    path.addCurve(
                        to: CGPoint(x: values[i + 4], y: values[i + 5]),
                        control1: CGPoint(x: values[i], y: values[i + 1]),
                        control2: CGPoint(x: values[i + 2], y: values[i + 3])
                    )
                }
            case "Z":
                path.closeSubpath()
            default:
                break
            }
            values = []
        }
        for token in tokens {
            if let value = Double(token) {
                values.append(CGFloat(value))
            } else {
                flush()
                command = token
            }
        }
        flush()
        return path
    }
}

#Preview {
    HStack(spacing: 20) {
        BrandLogoMark(size: 100, style: .detailed)
        BrandLogoMark(size: 100, style: .simple)
        BrandLogoMark(size: 40, style: .detailed)
    }
    .padding()
}
