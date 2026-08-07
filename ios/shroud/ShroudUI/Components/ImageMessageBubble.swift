import SwiftUI
import UIKit

/// Telegram-style image bubble with bottom-trailing time / receipts overlay.
struct ImageMessageBubble: View {
    let message: MessagingController.ChatMessage
    let time: String
    var onAppearLoad: (() -> Void)?
    var onRetry: (() -> Void)?
    /// Tap the photo (when loaded) — host presents the media overlay.
    var onOpen: (() -> Void)?
    /// When false, renders only the bubble (no leading/trailing row spacers) for menu hero.
    var isRowEmbedded: Bool = true
    /// When set, reports this bubble’s global frame via `MessageBubbleFrameKey`.
    var frameReportID: UUID? = nil

    @Environment(\.chatRowWidth) private var chatRowWidth

    private var isMine: Bool { message.isMine }
    private var isFailed: Bool { message.receipt == .failed }
    private var canOpen: Bool {
        !message.deleted && !isFailed && message.imageData != nil
    }

    /// Caption when `text` is real user text (not the default "Photo" label).
    private var hasCaption: Bool {
        let t = message.text.trimmingCharacters(in: .whitespacesAndNewlines)
        return !t.isEmpty && t != "Photo"
    }

    private var caption: String {
        message.text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var corners: UnevenRoundedRectangle {
        if isMine {
            UnevenRoundedRectangle(
                topLeadingRadius: 17.5,
                bottomLeadingRadius: 17.5,
                bottomTrailingRadius: 5,
                topTrailingRadius: 17.5,
                style: .continuous
            )
        } else {
            UnevenRoundedRectangle(
                topLeadingRadius: 17.5,
                bottomLeadingRadius: 5,
                bottomTrailingRadius: 17.5,
                topTrailingRadius: 17.5,
                style: .continuous
            )
        }
    }

    /// Widest the media may draw — its own cap, but never wider than the row allows.
    private var mediaWidthCap: CGFloat {
        let row = chatRowWidth > 0 ? chatRowWidth : MessageBubbleMetrics.fallbackRowWidth
        let budget = min(row, max(MessageBubbleMetrics.minBubbleWidth, row - MessageBubbleMetrics.oppositeGutter))
        return min(240, budget)
    }

    private var displaySize: CGSize {
        let maxW = mediaWidthCap
        let maxH: CGFloat = 320
        let w = CGFloat(message.imageWidth ?? 240)
        let h = CGFloat(message.imageHeight ?? 240)
        guard w > 0, h > 0 else { return CGSize(width: 180, height: 180) }
        let scale = min(maxW / w, maxH / h, 1)
        let size = CGSize(width: max(120, w * scale), height: max(120, h * scale))

        // A caption is laid out in the media's width, and a tall photo is height-capped long
        // before it reaches the width cap — a 9:19.5 screenshot lands at ~150pt, which leaves
        // the text about 70pt after padding and meta, so it breaks mid-word. Captioned media
        // takes the full width instead and center-crops into it via `scaledToFill` + `clipped`
        // (Telegram crops very tall media in-thread too; the viewer still opens the original).
        guard hasCaption else { return size }
        return CGSize(width: maxW, height: size.height)
    }

    var body: some View {
        Group {
            if isRowEmbedded {
                HStack(alignment: .bottom, spacing: 0) {
                    if isMine { Spacer(minLength: 56) }
                    bubbleCore
                    if !isMine { Spacer(minLength: 56) }
                }
                .frame(maxWidth: .infinity, alignment: isMine ? .trailing : .leading)
            } else {
                bubbleCore
            }
        }
    }

    private var bubbleCore: some View {
        VStack(alignment: isMine ? .trailing : .leading, spacing: 6) {
            VStack(alignment: .leading, spacing: 0) {
                ZStack(alignment: .bottomTrailing) {
                    Group {
                        if message.deleted {
                            deletedPlaceholder
                        } else if let data = message.imageData,
                                  let ui = DecodedImageCache.image(forMessage: message.id, data: data)
                        {
                            Image(uiImage: ui)
                                .resizable()
                                .scaledToFill()
                                .frame(width: displaySize.width, height: displaySize.height)
                                .clipped()
                                .opacity(isFailed ? 0.55 : 1)
                                // Decrypt + decode finishes off the main thread; dissolve the
                                // photo in over the placeholder instead of snapping it.
                                .transition(.opacity)
                        } else {
                            loadingPlaceholder
                                .transition(.opacity)
                        }
                    }
                    .animation(Motion.fade, value: message.imageData == nil)

                    if isFailed {
                        failedOverlay
                            .transition(.opacity)
                    } else if !hasCaption {
                        timeChip
                    }
                }
                .animation(Motion.snappy, value: isFailed)
                .frame(width: displaySize.width, height: displaySize.height)
                .clipShape(
                    hasCaption
                        ? UnevenRoundedRectangle(
                            topLeadingRadius: 17.5,
                            bottomLeadingRadius: 0,
                            bottomTrailingRadius: 0,
                            topTrailingRadius: 17.5,
                            style: .continuous
                        )
                        : corners
                )
                .contentShape(Rectangle())
                // Tap / long-press are handled on the row via UIKit
                // (`messageContextLongPress`) so ScrollView doesn’t delay the menu ~1s.
                // Keep a SwiftUI tap as fallback when the bubble is used outside chat rows.
                .onTapGesture {
                    guard canOpen else { return }
                    Haptics.impact(.light)
                    onOpen?()
                }

                if hasCaption, !message.deleted {
                    captionFooter
                }
            }
            .overlay {
                if isFailed {
                    RoundedRectangle(cornerRadius: 17.5, style: .continuous)
                        .stroke(Theme.danger.opacity(0.7), lineWidth: 1.5)
                }
            }
            .shadow(color: Color.black.opacity(0.08), radius: 3, y: 1)
            .onAppear { onAppearLoad?() }

            if isFailed {
                failedFooter
            }
        }
        .background {
            if let frameReportID {
                GeometryReader { geo in
                    Color.clear.preference(
                        key: MessageBubbleFrameKey.self,
                        value: [frameReportID: geo.frame(in: .global)]
                    )
                }
            }
        }
    }

    /// Match text bubbles: muted meta for time/sent/delivered; brighter ticks when read.
    private var metaColor: Color { Color.white.opacity(0.75) }
    private var readTickColor: Color { Color.white.opacity(0.95) }

    private var timeChip: some View {
        HStack(spacing: 3) {
            Text(time)
                .font(.system(size: 11, weight: .regular))
                .foregroundStyle(metaColor)
                .monospacedDigit()
                .fixedSize()
            if isMine, !message.deleted {
                MessageReceiptIcon(
                    receipt: message.receipt,
                    metaColor: metaColor,
                    readColor: readTickColor,
                    failedColor: Color.white
                )
            }
        }
        .padding(.horizontal, 7)
        .padding(.vertical, 3)
        .background(Color.black.opacity(0.35))
        .clipShape(Capsule())
        .padding(8)
    }

    private var failedOverlay: some View {
        VStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 28))
                .foregroundStyle(Color.white)
            Text("Not sent")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Color.white)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black.opacity(0.35))
    }

    private var failedFooter: some View {
        VStack(alignment: isMine ? .trailing : .leading, spacing: 4) {
            if let error = message.sendError, !error.isEmpty {
                Text(error)
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.danger)
                    .multilineTextAlignment(isMine ? .trailing : .leading)
                    .frame(maxWidth: displaySize.width, alignment: isMine ? .trailing : .leading)
            }
            Button {
                onRetry?()
            } label: {
                Label("Retry", systemImage: "arrow.clockwise")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                    .padding(.vertical, 4)
                    .contentShape(Rectangle())
            }
            .pressable(scale: 0.92, haptic: .medium)
        }
        .padding(.horizontal, 2)
        .transition(.opacity.combined(with: .move(edge: .top)))
    }

    /// Human: The bubble already knows the photo's dimensions from the message metadata, so the
    /// placeholder occupies the exact final frame — the thread never reflows when the image lands.
    private var loadingPlaceholder: some View {
        ZStack {
            (isMine ? Theme.accent : Theme.bubbleIncoming)
                .shimmering()
            ProgressView()
                .tint(isMine ? Color.white.opacity(0.9) : Theme.accent)
        }
        .frame(width: displaySize.width, height: displaySize.height)
    }

    private var deletedPlaceholder: some View {
        ZStack {
            Theme.backgroundGrouped
            Text("Photo deleted")
                .font(.system(size: 14))
                .foregroundStyle(Theme.textSecondary)
        }
        .frame(width: displaySize.width, height: 120)
    }

    /// Caption strip under the photo (Telegram: text lives under media in the bubble).
    ///
    /// Meta sits on the **last line** via the same reservation the text bubble uses. The
    /// previous `HStack` put the time beside the whole text block, so every line — not just
    /// the last — lost the meta's width, which is what squeezed captions into a ragged column.
    private var captionFooter: some View {
        ZStack(alignment: .bottomTrailing) {
            Text("\(captionBodyText)\(captionMetaSpacerText)")
                .multilineTextAlignment(.leading)
                .lineSpacing(2.5)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.leading, MessageBubbleMetrics.textLeadingPad)
                .padding(.trailing, MessageBubbleMetrics.textTrailingPad)
                .padding(.top, 7)
                .padding(.bottom, 6)

            captionMetaRow
                .padding(.trailing, MessageBubbleMetrics.metaTrailingPad)
                .padding(.bottom, 5)
        }
        .frame(width: displaySize.width, alignment: .leading)
        .background(isMine ? Theme.accent : Theme.bubbleIncoming)
        .clipShape(
            UnevenRoundedRectangle(
                topLeadingRadius: 0,
                bottomLeadingRadius: isMine ? 17.5 : 5,
                bottomTrailingRadius: isMine ? 5 : 17.5,
                topTrailingRadius: 0,
                style: .continuous
            )
        )
    }

    private var showsCaptionReceipt: Bool { isMine && !message.deleted }

    private var captionMetaColor: Color {
        isMine ? Color.white.opacity(0.65) : Theme.textSecondary.opacity(0.95)
    }

    /// Must stay in step with `MessageBubbleMetrics`' measuring font, or the last-line
    /// reservation drifts away from the meta it is reserving for.
    private var captionMetaFont: Font {
        .system(size: MessageBubbleMetrics.metaFontSize, weight: .regular).monospacedDigit()
    }

    private var captionBodyText: Text {
        Text(MessageBubbleMetrics.normalizedForDisplay(caption))
            .font(.system(size: MessageBubbleMetrics.bodyFontSize))
            .foregroundStyle(isMine ? Color.white : Theme.textPrimary)
    }

    /// Invisible trailing reservation so the last caption line leaves room for time + ticks.
    private var captionMetaSpacerText: Text {
        Text(
            verbatim: MessageBubbleMetrics.metaReservation(
                time: time,
                showsReceipt: showsCaptionReceipt
            )
        )
        .font(captionMetaFont)
        .foregroundStyle(Color.clear)
    }

    private var captionMetaRow: some View {
        HStack(spacing: MessageBubbleMetrics.metaSpacing) {
            Text(time)
                .font(captionMetaFont)
                .foregroundStyle(captionMetaColor)
                .fixedSize()

            if showsCaptionReceipt {
                MessageReceiptIcon(
                    receipt: message.receipt,
                    metaColor: captionMetaColor,
                    readColor: Color.white.opacity(0.95)
                )
            }
        }
    }
}
