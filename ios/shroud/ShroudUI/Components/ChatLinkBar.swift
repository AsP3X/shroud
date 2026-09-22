import SwiftUI

/// What the composer's link bar shows.
enum ChatLinkBarState: Equatable {
    /// Fetching the page — Telegram's "Loading…" with the link underneath.
    case loading(url: String)
    /// Page title (or site name) over its description (or the link).
    case ready(title: String, snippet: String, snippetIsLink: Bool)
}

/// The link preview strip above the composer (`Link Bar` / `Link Bar / Loading` in
/// `iOS-App.pen`).
///
/// Human: The sibling of `ChatReplyBar` — same glyph column, same stripe, same ✕ — so a quote
/// and a preview read as the same kind of attachment. Tapping the strip opens Telegram's link
/// options: show the preview above or below the text, a larger or smaller picture, or no
/// preview at all. ✕ drops the preview for this link; the text is left alone.
/// Agent: Pure presentation; `ConversationView` owns the `LinkPreviewComposer` behind it.
struct ChatLinkBar: View {
    let state: ChatLinkBarState
    var showsAboveText: Bool = false
    var canToggleImageSize: Bool = false
    var usesLargeImage: Bool = false
    var onToggleAboveText: () -> Void = {}
    var onToggleImageSize: () -> Void = {}
    var onRemove: () -> Void

    private static let stripeWidth: CGFloat = 3
    private static let fontSize: CGFloat = 15

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "link")
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(Theme.accent)
                .frame(width: 26, height: 34)
                .accessibilityHidden(true)

            Menu {
                if case .ready = state {
                    Button(action: onToggleAboveText) {
                        Label(
                            showsAboveText ? "Show Below Text" : "Show Above Text",
                            systemImage: showsAboveText ? "arrow.down.to.line" : "arrow.up.to.line"
                        )
                    }
                    if canToggleImageSize {
                        Button(action: onToggleImageSize) {
                            Label(
                                usesLargeImage ? "Smaller Image" : "Larger Image",
                                systemImage: usesLargeImage
                                    ? "arrow.down.right.and.arrow.up.left"
                                    : "arrow.up.left.and.arrow.down.right"
                            )
                        }
                    }
                }
                Button(role: .destructive, action: onRemove) {
                    Label("Remove Preview", systemImage: "trash")
                }
            } label: {
                preview
            }
            .menuIndicator(.hidden)
            .accessibilityLabel(accessibilityText)
            .accessibilityHint("Shows link preview options")

            Button(action: onRemove) {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Theme.textSecondary)
                    .frame(width: 34, height: 34)
                    .contentShape(Rectangle())
            }
            .pressable(scale: 0.86)
            .accessibilityLabel("Remove link preview")
        }
        .padding(.leading, 10)
        .padding(.trailing, 6)
        .padding(.top, 6)
        .padding(.bottom, 2)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Title over snippet with the accent stripe, drawn like the reply bar's quote.
    private var preview: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(title)
                .font(.system(size: Self.fontSize, weight: .semibold))
                .foregroundStyle(Theme.accent)
                .lineLimit(1)
            Text(snippet)
                .font(.system(size: Self.fontSize))
                .foregroundStyle(snippetIsMuted ? Theme.textSecondary : Theme.textPrimary)
                .lineLimit(1)
                .truncationMode(snippetIsMuted ? .middle : .tail)
        }
        .padding(.leading, Self.stripeWidth + 6)
        .padding(.trailing, 8)
        .padding(.vertical, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .overlay(alignment: .leading) {
            Rectangle()
                .fill(Theme.accent)
                .frame(width: Self.stripeWidth)
        }
        .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
        .contentShape(Rectangle())
        .animation(Motion.fade, value: state)
    }

    private var title: String {
        switch state {
        case .loading: "Loading preview…"
        case let .ready(title, _, _): title
        }
    }

    private var snippet: String {
        switch state {
        case let .loading(url): url
        case let .ready(_, snippet, _): snippet
        }
    }

    /// The link itself (loading, or a page without a description) reads as a stand-in.
    private var snippetIsMuted: Bool {
        switch state {
        case .loading: true
        case let .ready(_, _, isLink): isLink
        }
    }

    private var accessibilityText: String {
        switch state {
        case let .loading(url): "Loading link preview for \(url)"
        case let .ready(title, snippet, _): "Link preview: \(title), \(snippet)"
        }
    }
}

extension ChatLinkBarState {
    /// Bar content for a loaded preview: title → site name → host, over description → link.
    init(preview: LinkPreview) {
        let title = preview.title ?? preview.siteName ?? preview.displayHost
        if let summary = preview.summary {
            self = .ready(title: title, snippet: summary, snippetIsLink: false)
        } else {
            self = .ready(title: title, snippet: preview.url, snippetIsLink: true)
        }
    }
}

#Preview("Link bar") {
    VStack(spacing: 0) {
        Spacer()
        ChatLinkBar(state: .loading(url: "komoot.com/tour/1398273"), onRemove: {})
        ChatLinkBar(
            state: .ready(
                title: "Herzogstand – Heimgarten ridge walk",
                snippet: "Intermediate hike · 13.6 km · 5:10 h. Views over Lake Kochel.",
                snippetIsLink: false
            ),
            canToggleImageSize: true,
            usesLargeImage: true,
            onRemove: {}
        )
    }
    .background(Theme.background)
}
