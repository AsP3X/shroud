import SwiftUI
import UIKit

/// Where a link preview block is drawn — decides its palette.
enum LinkPreviewStyle {
    /// Inside an incoming (white / dark-grey) bubble: accent stripe, accent site name.
    case incoming
    /// Inside an outgoing (accent) bubble: everything white.
    case outgoing
}

/// The picture a preview block shows.
enum LinkPreviewImage {
    case none
    /// 54 pt square at the top-trailing corner; the text flows around it.
    case thumbnail(UIImage)
    /// Full width under the text. `full` is nil until the blob is decrypted, when the blurred
    /// `placeholder` (or a tint) stands in.
    case large(full: UIImage?, placeholder: UIImage?, aspect: CGFloat)

    var isLarge: Bool {
        if case .large = self { return true }
        return false
    }
}

/// Telegram's link preview block: accent stripe, tinted card, site name, title, description,
/// and a small thumbnail or a large picture. Maps to `Link Preview / In`, `Link Preview / Out`
/// and `Link Preview / In · Thumb` in `iOS-App.pen`.
///
/// Human: Same stripe, radius and tint as the reply quote (`ReplyQuoteView`) — in Telegram the
/// two blocks are the same component, and a message can carry both. Tapping it opens the page;
/// it shrinks a touch while pressed, as Telegram's does.
/// Agent: Pure presentation. `onOpen` is the host's link opener; the block claims its tap
/// (`MessageTapClaim`) so the row's own tap handler stays out of it.
struct LinkPreviewView: View {
    let preview: LinkPreview
    var image: LinkPreviewImage = .none
    var style: LinkPreviewStyle = .incoming
    var onOpen: (() -> Void)?

    static let stripeWidth: CGFloat = 3
    static let cornerRadius: CGFloat = 6
    static let thumbnailSide: CGFloat = 54
    /// Distance of the thumbnail from the block's top and trailing edges (Telegram: 6).
    static let thumbnailInset: CGFloat = 6
    static let mediaCornerRadius: CGFloat = 4
    static let fontSize: CGFloat = 14
    /// A description longer than this is clamped with "…" (Telegram Web cuts at 170).
    static let maxLines = 10

    private var leadingPad: CGFloat { Self.stripeWidth + 7 }
    private var trailingPad: CGFloat { hasThumbnail ? Self.thumbnailInset : 8 }

    private var hasThumbnail: Bool {
        if case .thumbnail = image { return true }
        return false
    }

    private var accentColor: Color {
        style == .outgoing ? Color.white : Theme.accent
    }

    private var tint: Color {
        style == .outgoing ? Color.white.opacity(0.15) : Theme.accent.opacity(0.1)
    }

    var body: some View {
        Button {
            MessageTapClaim.claim()
            onOpen?()
        } label: {
            block
        }
        .buttonStyle(LinkPreviewPressStyle())
        .disabled(onOpen == nil)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
        .accessibilityAddTraits(.isLink)
        .accessibilityHint("Opens the page")
    }

    private var block: some View {
        VStack(alignment: .leading, spacing: 0) {
            LinkPreviewTextView(
                text: LinkPreviewTextView.attributedText(for: preview, style: style),
                cutout: hasThumbnail
                    ? CGSize(
                        width: Self.thumbnailSide + 6,
                        height: Self.thumbnailSide + Self.thumbnailInset - 5
                    )
                    : .zero,
                maximumLines: Self.maxLines
            )
            // The card is never shorter than its thumbnail (+6 pt above and below).
            .frame(minHeight: hasThumbnail ? Self.thumbnailSide + Self.thumbnailInset * 2 - 12 : nil, alignment: .topLeading)

            if case let .large(full, placeholder, aspect) = image {
                largeImage(full: full, placeholder: placeholder, aspect: aspect)
                    .padding(.top, 6)
            }
        }
        .padding(.leading, leadingPad)
        .padding(.trailing, trailingPad)
        .padding(.top, 5)
        .padding(.bottom, 7)
        .overlay(alignment: .topTrailing) {
            if case let .thumbnail(thumbnail) = image {
                Image(uiImage: thumbnail)
                    .resizable()
                    .scaledToFill()
                    .frame(width: Self.thumbnailSide, height: Self.thumbnailSide)
                    .clipShape(RoundedRectangle(cornerRadius: Self.mediaCornerRadius, style: .continuous))
                    .padding(.top, Self.thumbnailInset)
                    .padding(.trailing, Self.thumbnailInset)
                    .accessibilityHidden(true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(tint)
        .overlay(alignment: .leading) {
            Rectangle()
                .fill(accentColor)
                .frame(width: Self.stripeWidth)
        }
        .clipShape(RoundedRectangle(cornerRadius: Self.cornerRadius, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: Self.cornerRadius, style: .continuous))
    }

    /// Full-width picture with Telegram's aspect clamp; a play badge for video pages.
    private func largeImage(full: UIImage?, placeholder: UIImage?, aspect: CGFloat) -> some View {
        // Very tall or very wide images are cropped to a sane band (Telegram does the same).
        let clamped = min(max(aspect, 0.75), 2.4)
        return Color.clear
            .aspectRatio(clamped, contentMode: .fit)
            .frame(maxWidth: .infinity)
            .background {
                if let shown = full ?? placeholder {
                    Image(uiImage: shown)
                        .resizable()
                        .scaledToFill()
                        // The envelope placeholder is a few KB; soften it like a photo's.
                        .blur(radius: full == nil ? 10 : 0)
                } else {
                    tint
                }
            }
            .overlay {
                if preview.isVideo {
                    Circle()
                        .fill(Color.black.opacity(0.45))
                        .frame(width: 44, height: 44)
                        .overlay {
                            Image(systemName: "play.fill")
                                .font(.system(size: 18, weight: .semibold))
                                .foregroundStyle(Color.white)
                                .offset(x: 1.5)
                        }
                        .accessibilityHidden(true)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: Self.mediaCornerRadius, style: .continuous))
            .animation(Motion.fade, value: full != nil)
    }

    private var accessibilityText: String {
        var parts = ["Link preview", preview.displaySiteName]
        if let title = preview.title { parts.append(title) }
        if let summary = preview.summary { parts.append(summary) }
        return parts.joined(separator: ", ")
    }
}

/// Telegram shrinks a pressed preview by a few points (0.3 s in, 0.2 s out).
private struct LinkPreviewPressStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(reduceMotion ? 1 : (configuration.isPressed ? 0.98 : 1))
            .animation(
                configuration.isPressed ? .easeInOut(duration: 0.3) : .easeInOut(duration: 0.2),
                value: configuration.isPressed
            )
    }
}

// MARK: - Text

/// Site name, title and description as one text view, so lines can flow around the thumbnail.
///
/// Human: SwiftUI `Text` cannot wrap around a shape. TextKit can (exclusion paths), which is how
/// Telegram lays its preview text around the 54 pt thumbnail: the first lines are shortened,
/// the rest run the full width underneath.
/// Agent: Measures in `sizeThatFits` (hugging the widest line, like a bubble) and re-applies the
/// exclusion for the final width in `layoutSubviews`. Not interactive — taps go to the block.
struct LinkPreviewTextView: UIViewRepresentable {
    let text: NSAttributedString
    /// Top-trailing area the text keeps clear of (thumbnail + gap); `.zero` for none.
    let cutout: CGSize
    let maximumLines: Int

    func makeUIView(context: Context) -> CutoutTextView {
        CutoutTextView()
    }

    func updateUIView(_ view: CutoutTextView, context: Context) {
        view.configure(text: text, cutout: cutout, maximumLines: maximumLines)
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: CutoutTextView, context: Context) -> CGSize? {
        uiView.configure(text: text, cutout: cutout, maximumLines: maximumLines)
        return uiView.fittingSize(forWidth: proposal.width)
    }

    /// The block's text in Telegram's type: 14 pt semibold site name and title, 14 pt body.
    static func attributedText(for preview: LinkPreview, style: LinkPreviewStyle) -> NSAttributedString {
        let accent = style == .outgoing ? UIColor.white : (UIColor(named: "AccentText") ?? .systemIndigo)
        let primary = style == .outgoing ? UIColor.white : (UIColor(named: "TextPrimary") ?? .label)
        let body = style == .outgoing ? UIColor.white.withAlphaComponent(0.9) : primary
        let semibold = UIFont.systemFont(ofSize: LinkPreviewView.fontSize, weight: .semibold)
        let regular = UIFont.systemFont(ofSize: LinkPreviewView.fontSize, weight: .regular)

        let paragraph = NSMutableParagraphStyle()
        paragraph.lineSpacing = 1
        paragraph.paragraphSpacing = 1
        paragraph.lineBreakMode = .byWordWrapping

        let result = NSMutableAttributedString()
        func append(_ string: String, font: UIFont, color: UIColor) {
            if result.length > 0 {
                result.append(NSAttributedString(string: "\n", attributes: [.font: font, .paragraphStyle: paragraph]))
            }
            result.append(NSAttributedString(string: string, attributes: [
                .font: font,
                .foregroundColor: color,
                .paragraphStyle: paragraph,
            ]))
        }
        append(preview.displaySiteName, font: semibold, color: accent)
        if let title = preview.title { append(title, font: semibold, color: primary) }
        if let summary = preview.summary { append(summary, font: regular, color: body) }
        return result
    }
}

/// TextKit 1 text view with one top-trailing exclusion rectangle.
final class CutoutTextView: UITextView {
    private let storage = NSTextStorage()
    private var cutout: CGSize = .zero
    private var laidOutWidth: CGFloat = -1

    init() {
        let manager = NSLayoutManager()
        let container = NSTextContainer(size: CGSize(width: 0, height: CGFloat.greatestFiniteMagnitude))
        container.widthTracksTextView = false
        container.heightTracksTextView = false
        container.lineFragmentPadding = 0
        container.lineBreakMode = .byTruncatingTail
        manager.addTextContainer(container)
        storage.addLayoutManager(manager)
        super.init(frame: .zero, textContainer: container)
        isEditable = false
        isSelectable = false
        isScrollEnabled = false
        isUserInteractionEnabled = false
        backgroundColor = .clear
        textContainerInset = .zero
        setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    func configure(text: NSAttributedString, cutout: CGSize, maximumLines: Int) {
        if !storage.isEqual(to: text) {
            storage.setAttributedString(text)
            laidOutWidth = -1
        }
        if self.cutout != cutout {
            self.cutout = cutout
            laidOutWidth = -1
        }
        textContainer.maximumNumberOfLines = maximumLines
    }

    /// Height for `width`, and the width the text actually needs (its widest line, plus the
    /// cutout for lines beside the thumbnail). `nil` width means "ideal": one line per paragraph.
    func fittingSize(forWidth proposed: CGFloat?) -> CGSize {
        let width = min(proposed ?? 10_000, 10_000)
        apply(width: width)
        var hug: CGFloat = 0
        var height: CGFloat = 0
        let glyphs = layoutManager.glyphRange(for: textContainer)
        layoutManager.enumerateLineFragments(forGlyphRange: glyphs) { rect, used, _, _, _ in
            let besideCutout = self.cutout != .zero && rect.minY < self.cutout.height
            hug = max(hug, used.maxX + (besideCutout ? self.cutout.width : 0))
            height = max(height, rect.maxY)
        }
        if cutout != .zero {
            hug = max(hug, cutout.width)
        }
        return CGSize(width: min(width, ceil(hug)), height: ceil(height))
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        apply(width: bounds.width)
    }

    private func apply(width: CGFloat) {
        guard width != laidOutWidth else { return }
        laidOutWidth = width
        textContainer.size = CGSize(width: width, height: CGFloat.greatestFiniteMagnitude)
        textContainer.exclusionPaths = cutout == .zero
            ? []
            : [UIBezierPath(rect: CGRect(x: width - cutout.width, y: 0, width: cutout.width, height: cutout.height))]
        layoutManager.ensureLayout(for: textContainer)
    }
}

#Preview("Link previews") {
    let preview = LinkPreview(
        url: "https://www.komoot.com/tour/1398273",
        siteName: "komoot",
        title: "Herzogstand – Heimgarten ridge walk",
        summary: "Intermediate hike · 13.6 km · 5:10 h. Views over Lake Kochel and the Walchensee the whole way along the ridge."
    )
    let square = UIGraphicsImageRenderer(size: CGSize(width: 160, height: 160)).image { context in
        UIColor.systemTeal.setFill()
        context.fill(CGRect(x: 0, y: 0, width: 160, height: 160))
    }
    return ScrollView {
        VStack(alignment: .leading, spacing: 16) {
            LinkPreviewView(preview: preview, style: .incoming, onOpen: {})
                .frame(width: 260)
            LinkPreviewView(preview: preview, image: .thumbnail(square), style: .incoming, onOpen: {})
                .frame(width: 260)
            LinkPreviewView(
                preview: preview,
                image: .large(full: square, placeholder: nil, aspect: 1.91),
                style: .outgoing,
                onOpen: {}
            )
            .frame(width: 260)
            .padding(6)
            .background(Theme.accent)
            .clipShape(RoundedRectangle(cornerRadius: 17, style: .continuous))
        }
        .padding(20)
    }
    .background(Theme.backgroundChat)
}

// MARK: - Decoded image cache

/// Decoded preview pictures, so scrolling never re-decodes a JPEG per frame.
///
/// Agent: Keyed by message id + variant, invalidated by byte count (placeholder → full image).
/// RAM only; `remove(ids:)` drops a deleted message's pictures.
enum LinkPreviewImageCache {
    enum Variant: String {
        case full
        case placeholder
        case thumbnail
    }

    private static var storage: [String: (bytes: Int, image: UIImage)] = [:]
    private static let limit = 120

    static func image(for messageID: UUID, variant: Variant, data: Data?) -> UIImage? {
        guard let data else { return nil }
        let key = messageID.uuidString + variant.rawValue
        if let cached = storage[key], cached.bytes == data.count { return cached.image }
        guard let image = UIImage(data: data) else { return nil }
        if storage.count >= limit { storage.removeAll(keepingCapacity: true) }
        storage[key] = (data.count, image)
        return image
    }

    /// Chats locked: preview pictures leave memory with the messages they belong to.
    static func removeAll() {
        storage.removeAll()
    }

    static func remove(ids: [UUID]) {
        for id in ids {
            for variant in [Variant.full, .placeholder, .thumbnail] {
                storage.removeValue(forKey: id.uuidString + variant.rawValue)
            }
        }
    }
}
