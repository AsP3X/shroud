import SwiftUI
import UIKit

/// Telegram-style image bubble: envelope preview first, full download only on demand.
struct ImageMessageBubble: View {
    let message: MessagingController.ChatMessage
    let time: String
    /// Manual full-media download (not auto on appear).
    var onDownload: (() -> Void)?
    /// Live transfer for this message, when one is running.
    var transfer: MessagingController.MediaTransfer?
    var onCancelDownload: (() -> Void)?
    var onRetry: (() -> Void)?
    /// Tap the photo when fully loaded — host presents the media overlay.
    var onOpen: (() -> Void)?
    var isRowEmbedded: Bool = true
    var frameReportID: UUID? = nil
    /// Quote header for a reply; nil for an ordinary photo.
    var reply: ReplyQuoteContent? = nil
    /// Jump to the quoted message.
    var onReplyTap: (() -> Void)? = nil
    /// Reaction chips, drawn in a foot strip under the media (with the caption, if any).
    var reactions: [ReactionChipContent] = []
    var onReactionTap: ((String) -> Void)? = nil

    @Environment(\.chatRowWidth) private var chatRowWidth

    private var isMine: Bool { message.isMine }
    private var isFailed: Bool { message.receipt == .failed }
    private var needsDownload: Bool { message.needsMediaDownload }
    private var canOpen: Bool {
        !message.deleted && !isFailed && message.imageData != nil
    }

    private var hasReactions: Bool { !reactions.isEmpty && !message.deleted }

    /// The bubble continues under the media: a caption, reaction chips, or both.
    private var hasFooter: Bool { hasCaption || hasReactions }

    private var hasCaption: Bool {
        let t = message.text.trimmingCharacters(in: .whitespacesAndNewlines)
        return !t.isEmpty && t != "Photo" && t != "Media"
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

    private var mediaWidthCap: CGFloat {
        let row = chatRowWidth > 0 ? chatRowWidth : MessageBubbleMetrics.fallbackRowWidth
        let budget = min(row, max(MessageBubbleMetrics.minBubbleWidth, row - MessageBubbleMetrics.oppositeGutter))
        return min(MessageBubbleMetrics.mediaWidthCap, budget)
    }

    private var displaySize: CGSize {
        let maxW = mediaWidthCap
        let maxH: CGFloat = 320
        let w = CGFloat(message.imageWidth ?? 240)
        let h = CGFloat(message.imageHeight ?? 240)
        guard w > 0, h > 0 else { return CGSize(width: 180, height: 180) }
        let scale = min(maxW / w, maxH / h, 1)
        let size = CGSize(width: max(120, w * scale), height: max(120, h * scale))
        // A caption or a reply header needs a line's worth of width to read; a narrow
        // portrait photo would truncate both into nothing.
        guard hasCaption || reply != nil else { return size }
        return CGSize(width: maxW, height: size.height)
    }

    private var displayImage: UIImage? {
        if let data = message.imageData {
            return DecodedImageCache.image(forMessage: message.id, data: data)
        }
        if let data = message.previewData {
            return DecodedImageCache.image(forMessage: message.id, data: data)
        }
        return nil
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
        // One element, like the text and voice bubbles. Otherwise VoiceOver reads an unlabelled
        // image, each tick by its symbol name, and the clear reservation after the caption.
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityAddTraits(hasDefaultAction ? [.isImage, .isButton] : [.isImage])
        .accessibilityAction {
            // A failed photo can't open, so a double tap retries, as the footer's button did
            // when it was its own element.
            if isFailed {
                onRetry?()
            } else if needsDownload {
                transfer == nil ? onDownload?() : onCancelDownload?()
            } else if canOpen {
                onOpen?()
            }
        }
        .accessibilityActions {
            if isFailed, let onRetry {
                Button("Retry", action: onRetry)
            }
            // The default action above replaces the quote's own tap.
            if reply != nil, !message.deleted, let onReplyTap {
                Button("Show replied message", action: onReplyTap)
            }
        }
        .reactionAccessibilityActions(hasReactions ? reactions : [], onTap: onReactionTap)
    }

    private var bubbleCore: some View {
        VStack(alignment: isMine ? .trailing : .leading, spacing: 6) {
            VStack(alignment: .leading, spacing: 0) {
                if let reply, !message.deleted {
                    replyHeader(reply)
                }

                ZStack {
                    Group {
                        if let ui = displayImage {
                            Image(uiImage: ui)
                                .resizable()
                                .scaledToFill()
                                .frame(width: displaySize.width, height: displaySize.height)
                                .clipped()
                                .opacity(isFailed ? 0.55 : (needsDownload ? 0.92 : 1))
                                // Soften un-downloaded previews so the download chip reads clearly.
                                .blur(radius: needsDownload && message.imageData == nil ? 0.6 : 0)
                                .transition(.opacity)
                        } else {
                            emptyPlaceholder
                                .transition(.opacity)
                        }
                    }
                    .animation(Motion.fade, value: message.imageData == nil)
                    .animation(Motion.fade, value: message.previewData == nil)

                    if isFailed {
                        failedOverlay
                            .transition(.opacity)
                    } else if needsDownload {
                        MediaTransferControl(
                            mode: transfer.map { .busy($0) } ?? .idle(byteCount: message.mediaByteCount),
                            onTap: {
                                // The row's own tap would start the download a cancel just stopped.
                                MessageTapClaim.claim()
                                transfer == nil ? onDownload?() : onCancelDownload?()
                            }
                        )
                        // The bubble's label already speaks the state, size and progress and
                        // its default action is this tap; merged in, the disc's value would
                        // repeat the size after the label.
                        .accessibilityHidden(true)
                        .transition(.scale(scale: 0.8).combined(with: .opacity))
                    } else if !hasFooter {
                        VStack {
                            Spacer()
                            HStack {
                                Spacer()
                                timeChip
                            }
                        }
                    }
                }
                .animation(Motion.snappy, value: isFailed)
                .animation(Motion.snappy, value: needsDownload)
                .frame(width: displaySize.width, height: displaySize.height)
                .clipShape(mediaShape)
                .contentShape(Rectangle())
                .onTapGesture(perform: handleTap)

                if hasFooter, !message.deleted {
                    captionFooter
                }
            }
            .overlay {
                if isFailed {
                    // The bubble's own outline, tail corner included.
                    corners
                        .stroke(Theme.danger.opacity(0.7), lineWidth: 1.5)
                }
            }
            .shadow(color: Color.black.opacity(0.08), radius: 3, y: 1)

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

    /// Corners of the photo itself: squared off wherever the bubble continues (a reply
    /// header above it, a caption below it).
    private var mediaShape: UnevenRoundedRectangle {
        let hasHeader = reply != nil && !message.deleted
        return UnevenRoundedRectangle(
            topLeadingRadius: hasHeader ? 0 : 17.5,
            bottomLeadingRadius: hasFooter ? 0 : (isMine ? 17.5 : 5),
            bottomTrailingRadius: hasFooter ? 0 : (isMine ? 5 : 17.5),
            topTrailingRadius: hasHeader ? 0 : 17.5,
            style: .continuous
        )
    }

    /// Reply quote drawn on the bubble fill above the photo (Telegram's layout).
    private func replyHeader(_ reply: ReplyQuoteContent) -> some View {
        ReplyQuoteView(
            content: reply,
            style: isMine ? .outgoing : .incoming,
            onTap: onReplyTap
        )
        .padding(6)
        .frame(width: displaySize.width, alignment: .leading)
        .background(isMine ? Theme.bubbleOutgoing : Theme.bubbleIncoming)
        .clipShape(
            UnevenRoundedRectangle(
                topLeadingRadius: 17.5,
                bottomLeadingRadius: 0,
                bottomTrailingRadius: 0,
                topTrailingRadius: 17.5,
                style: .continuous
            )
        )
    }

    private var metaColor: Color { Color.white.opacity(0.75) }
    private var readTickColor: Color { Color.white.opacity(0.95) }

    /// The time is brighter than `metaColor`, which stays dimmer for the ticks so delivered and
    /// read still differ. A photo has no scrim under the chip, so its fill is darker than the
    /// video chip's; on a white photo both land on the same shade and keep the time at 4.5:1.
    private var timeChip: some View {
        HStack(spacing: 3) {
            Text(time)
                .font(.system(size: 11, weight: .regular))
                .foregroundStyle(Color.white.opacity(0.9))
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
        .background(Color.black.opacity(0.6))
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
        // The dimmed photo lets the light chat background through; this much black keeps
        // "Not sent" at 4.5:1 even over a white photo.
        .background(Color.black.opacity(0.55))
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
                // The row's own tap sees this touch too; unclaimed, it would open the viewer.
                MessageTapClaim.claim()
                onRetry?()
            } label: {
                Label("Retry", systemImage: "arrow.clockwise")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                    // 44 pt of target; the negative padding below keeps the footer's height.
                    .padding(.vertical, 14)
                    .contentShape(Rectangle())
            }
            .pressable(scale: 0.92, haptic: .medium)
            .padding(.vertical, -10)
        }
        .padding(.horizontal, 2)
        .transition(.opacity.combined(with: .move(edge: .top)))
    }

    private var emptyPlaceholder: some View {
        ZStack {
            (isMine ? Theme.bubbleOutgoing : Theme.bubbleIncoming)
            Image(systemName: "photo")
                .font(.system(size: 28, weight: .medium))
                .foregroundStyle(isMine ? Color.white.opacity(0.7) : Theme.textSecondary)
        }
        .frame(width: displaySize.width, height: displaySize.height)
    }

    /// Caption and/or reaction chips under the media. With chips the time leaves the caption's
    /// last line (and the media) for the end of the chip row, as in Telegram.
    private var captionFooter: some View {
        VStack(alignment: .leading, spacing: 0) {
            if hasReactions {
                if hasCaption {
                    captionBodyText
                        .multilineTextAlignment(.leading)
                        .lineSpacing(2.5)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.leading, MessageBubbleMetrics.textLeadingPad)
                        .padding(.trailing, MessageBubbleMetrics.textTrailingPad)
                        .padding(.top, 7)
                }
                ReactionFooter(
                    chips: reactions,
                    onOutgoingBubble: isMine,
                    onTap: onReactionTap,
                    chipsAccessible: false
                ) {
                    captionMetaRow
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.leading, 8)
                .padding(.trailing, MessageBubbleMetrics.metaTrailingPad)
                .padding(.top, hasCaption ? 5 : 6)
                .padding(.bottom, 6)
            } else {
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
            }
        }
        .frame(width: displaySize.width, alignment: .leading)
        .background(isMine ? Theme.bubbleOutgoing : Theme.bubbleIncoming)
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

    private var captionMetaFont: Font {
        .system(size: MessageBubbleMetrics.metaFontSize, weight: .regular).monospacedDigit()
    }

    private var captionBodyText: Text {
        Text(MessageBubbleMetrics.normalizedForDisplay(caption))
            .font(.system(size: MessageBubbleMetrics.bodyFontSize))
            .foregroundStyle(isMine ? Color.white : Theme.textPrimary)
    }

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
                // Only outgoing bubbles show ticks, so this is always on the accent strip,
                // where the default red failed glyph all but disappears.
                MessageReceiptIcon(
                    receipt: message.receipt,
                    metaColor: captionMetaColor,
                    readColor: Color.white.opacity(0.95),
                    failedColor: Color.white
                )
            }
        }
    }

    // MARK: - Actions

    /// The photo owns every tap on it, including the ones it declines: the row's own tap would
    /// otherwise open a failed photo, or open this one a second time.
    private func handleTap() {
        MessageTapClaim.claim()
        if needsDownload {
            Haptics.impact(.light)
            onDownload?()
            return
        }
        guard canOpen else { return }
        Haptics.impact(.light)
        onOpen?()
    }

    // MARK: - Accessibility

    /// A double tap does something: retry, download or cancel, or open.
    private var hasDefaultAction: Bool {
        (isFailed && onRetry != nil) || needsDownload || canOpen
    }

    /// The whole bubble as VoiceOver reads it, in the text bubble's order.
    private var accessibilityLabel: String {
        var parts = [isMine ? "You" : "Them"]
        // The quote's own label is replaced by this one, so it is spoken here.
        if let reply, !message.deleted {
            parts.append("Reply to \(reply.author): \(reply.text)")
        }
        parts.append("Photo")
        if hasCaption, !message.deleted { parts.append(caption) }
        if isFailed {
            parts.append("Not sent")
            if let error = message.sendError, !error.isEmpty { parts.append(error) }
        } else if needsDownload {
            if let transfer {
                parts.append("Downloading")
                // The ring's fill; the disc itself is hidden from VoiceOver.
                if !transfer.isIndeterminate {
                    parts.append("\(Int(transfer.ringFraction * 100)) percent")
                }
            } else {
                parts.append("Not downloaded")
                // The size under the download arrow, before committing to it on mobile data.
                if let bytes = message.mediaByteCount, bytes > 0 {
                    parts.append(MediaCrypto.byteCountLabel(bytes))
                }
            }
        }
        if hasReactions, let summary = reactions.spokenSummary { parts.append(summary) }
        parts.append(time)
        // A failed send already said "Not sent".
        if isMine, !message.deleted, !isFailed {
            parts.append(message.receipt.spokenLabel)
        }
        return parts.joined(separator: ", ")
    }
}
