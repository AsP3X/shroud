import SwiftUI
import UIKit

/// What a reply header actually draws — resolved once by the host, never by the bubble.
///
/// Human: The quote prefers the *live* message when it is still in the thread, so quoting
/// something that is later deleted for everyone reads "Message deleted" rather than keeping a
/// copy of text the sender withdrew. Only when the original is not on this device (older than
/// the local window, or deleted for me) does it fall back to the snippet sealed with the reply.
struct ReplyQuoteContent: Equatable {
    /// "You" or the peer's display name.
    var author: String
    /// One line of the quoted message.
    var text: String
    /// True when `text` is a stand-in ("Photo", "Message deleted") rather than the words
    /// someone typed — drawn in the muted colour.
    var isStandIn: Bool
    /// Thumbnail of a quoted photo/video, when the original is on this device.
    var thumbnail: UIImage?
    /// Glyph shown instead of a thumbnail (voice notes, media that isn't downloaded).
    var symbolName: String?

    static func == (lhs: ReplyQuoteContent, rhs: ReplyQuoteContent) -> Bool {
        lhs.author == rhs.author
            && lhs.text == rhs.text
            && lhs.isStandIn == rhs.isStandIn
            && lhs.symbolName == rhs.symbolName
            && lhs.thumbnail === rhs.thumbnail
    }
}

extension ReplyQuoteContent {
    /// Resolves a sealed `MessageReplyReference` against the thread for display.
    ///
    /// Agent: READS `original` (the quoted bubble, if still loaded) and `DecodedImageCache`;
    /// performs no I/O — a thumbnail appears only when the bubble already decoded one.
    static func make(
        reference: MessageReplyReference,
        original: MessagingController.ChatMessage?,
        peerName: String,
        myUserID: UUID?
    ) -> ReplyQuoteContent {
        if let original {
            return make(original: original, peerName: peerName)
        }
        // Not on this device any more: show what the sender sealed with the reply.
        let isMine = myUserID != nil && reference.senderUserID == myUserID
        let snippet = reference.snippet.trimmingCharacters(in: .whitespacesAndNewlines)
        let label = reference.kind.mediaLabel ?? "Message"
        return ReplyQuoteContent(
            author: isMine ? "You" : peerName,
            text: snippet.isEmpty ? label : snippet,
            isStandIn: snippet.isEmpty,
            thumbnail: nil,
            symbolName: symbol(for: reference.kind)
        )
    }

    /// The header for a message that *is* on this device — used by bubbles and by the
    /// composer bar, so both always read the message's current state.
    static func make(
        original: MessagingController.ChatMessage,
        peerName: String
    ) -> ReplyQuoteContent {
        let author = original.isMine ? "You" : peerName

        if original.deleted {
            return ReplyQuoteContent(
                author: author,
                text: "Message deleted",
                isStandIn: true,
                thumbnail: nil,
                symbolName: nil
            )
        }

        let caption = original.text.trimmingCharacters(in: .whitespacesAndNewlines)
        let thumbnail = (original.kind == .image || original.kind == .video)
            ? DecodedImageCache.image(forMessage: original.id, data: original.displayPreviewData)
            : nil

        switch original.kind {
        case .image:
            let hasCaption = !caption.isEmpty && caption != "Photo" && caption != "Media"
            return ReplyQuoteContent(
                author: author,
                text: hasCaption ? caption : "Photo",
                isStandIn: !hasCaption,
                thumbnail: thumbnail,
                symbolName: thumbnail == nil ? "photo" : nil
            )
        case .video:
            let hasCaption = !caption.isEmpty && caption != "Video" && caption != "Media"
            return ReplyQuoteContent(
                author: author,
                text: hasCaption ? caption : "Video",
                isStandIn: !hasCaption,
                thumbnail: thumbnail,
                symbolName: thumbnail == nil ? "video.fill" : nil
            )
        case .voice:
            // Telegram quotes a voice note by name, not by its transcript.
            return ReplyQuoteContent(
                author: author,
                text: "Voice message",
                isStandIn: true,
                thumbnail: nil,
                symbolName: "waveform"
            )
        case .file:
            // A file is quoted by its name, as the sealed snippet (`x`) carries it.
            return ReplyQuoteContent(
                author: author,
                text: original.fileName ?? "File",
                isStandIn: original.fileName == nil,
                thumbnail: nil,
                symbolName: "doc.fill"
            )
        case .text, .todo:
            return ReplyQuoteContent(
                author: author,
                text: MessageBubbleMetrics.normalizedForDisplay(caption),
                isStandIn: caption.isEmpty,
                thumbnail: nil,
                symbolName: nil
            )
        }
    }

    private static func symbol(for kind: MessageReplyReference.Kind) -> String? {
        switch kind {
        case .text: nil
        case .image: "photo"
        case .video: "video.fill"
        case .voice: "waveform"
        case .file: "doc.fill"
        }
    }
}

/// Where a quote is being drawn — decides its palette.
enum ReplyQuoteStyle {
    /// Inside an incoming (white) bubble.
    case incoming
    /// Inside an outgoing (accent) bubble.
    case outgoing
    /// Above the composer, on the app background.
    case composer
}

/// Telegram's reply header: accent stripe, author, one line of the quoted message.
///
/// Human: The block is deliberately the same shape in the bubble and in the composer bar, so the
/// thing you are answering looks identical before and after you send it.
/// Agent: Pure presentation. `onTap` is what jumps the thread to the quoted message.
struct ReplyQuoteView: View {
    let content: ReplyQuoteContent
    var style: ReplyQuoteStyle = .incoming
    /// Jump-to-original. Nil where the quote is not tappable.
    var onTap: (() -> Void)?
    /// Body size; the composer bar runs one point larger, as Telegram's does.
    var fontSize: CGFloat = ReplyQuoteView.defaultFontSize

    /// Matches Telegram's 3pt stripe clipped by the block's rounded corners.
    private static let stripeWidth: CGFloat = 3
    private static let cornerRadius: CGFloat = 6
    private static let thumbnailSide: CGFloat = 32
    static let defaultFontSize: CGFloat = 14

    /// Author, stripe and glyph. On the incoming bubble it is `accentText`, which keeps the
    /// author readable in dark mode; the composer bar sits on the chat background.
    private var accentColor: Color {
        switch style {
        case .outgoing: Color.white
        case .incoming: Theme.accentText
        case .composer: Theme.accent
        }
    }

    private var bodyColor: Color {
        switch style {
        case .outgoing: content.isStandIn ? Color.white.opacity(0.7) : Color.white.opacity(0.92)
        case .incoming, .composer: content.isStandIn ? Theme.textSecondary : Theme.textPrimary
        }
    }

    private var tint: Color {
        switch style {
        // A darkening tint: white text on a lightened accent falls below 4.5:1.
        case .outgoing: Color.black.opacity(0.12)
        case .incoming: Theme.accent.opacity(0.1)
        // The composer bar draws the line only — a filled block there would read as a message.
        case .composer: Color.clear
        }
    }

    var body: some View {
        HStack(spacing: 7) {
            leading

            VStack(alignment: .leading, spacing: 1) {
                Text(content.author)
                    .font(.system(size: fontSize, weight: .semibold))
                    .foregroundStyle(accentColor)
                    .lineLimit(1)
                Text(content.text)
                    .font(.system(size: fontSize))
                    .foregroundStyle(bodyColor)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
        }
        .padding(.leading, Self.stripeWidth + 6)
        .padding(.trailing, 8)
        .padding(.vertical, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(tint)
        .overlay(alignment: .leading) {
            Rectangle()
                .fill(accentColor)
                .frame(width: Self.stripeWidth)
        }
        .clipShape(RoundedRectangle(cornerRadius: Self.cornerRadius, style: .continuous))
        .contentShape(Rectangle())
        .onTapGesture {
            guard let onTap else { return }
            // The row's own tap gesture sees this touch too (it is installed as a
            // `simultaneousGesture`); claiming it keeps a photo reply from also opening the
            // photo when the header was what the finger landed on.
            MessageTapClaim.claim()
            Haptics.impact(.light)
            onTap()
        }
        .allowsHitTesting(onTap != nil)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Reply to \(content.author): \(content.text)")
        .accessibilityAddTraits(onTap != nil ? .isButton : [])
    }

    @ViewBuilder
    private var leading: some View {
        if let thumbnail = content.thumbnail {
            Image(uiImage: thumbnail)
                .resizable()
                .scaledToFill()
                .frame(width: Self.thumbnailSide, height: Self.thumbnailSide)
                .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
                .accessibilityHidden(true)
        } else if let symbolName = content.symbolName {
            Image(systemName: symbolName)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(accentColor)
                .frame(width: 15)
                .accessibilityHidden(true)
        }
    }
}

#Preview("Reply quotes") {
    VStack(alignment: .leading, spacing: 12) {
        ReplyQuoteView(
            content: ReplyQuoteContent(
                author: "Jane Cooper",
                text: "Hey! Are we still on for tomorrow?",
                isStandIn: false,
                thumbnail: nil,
                symbolName: nil
            ),
            style: .incoming
        )
        .frame(width: 250)

        ReplyQuoteView(
            content: ReplyQuoteContent(
                author: "You",
                text: "Photo",
                isStandIn: true,
                thumbnail: nil,
                symbolName: "photo"
            ),
            style: .outgoing
        )
        .frame(width: 250)
        .padding(6)
        .background(Theme.bubbleOutgoing)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))

        ReplyQuoteView(
            content: ReplyQuoteContent(
                author: "Jane Cooper",
                text: "Voice message",
                isStandIn: true,
                thumbnail: nil,
                symbolName: "waveform"
            ),
            style: .composer
        )
        .frame(width: 280)
    }
    .padding(20)
    .background(Theme.backgroundChat)
}
