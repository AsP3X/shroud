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
    }

    private var bubbleCore: some View {
        VStack(alignment: isMine ? .trailing : .leading, spacing: 6) {
            VStack(alignment: .leading, spacing: 0) {
                if let reply, !message.deleted {
                    replyHeader(reply)
                }

                ZStack {
                    Group {
                        if message.deleted {
                            deletedPlaceholder
                        } else if let ui = displayImage {
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
                            onTap: { transfer == nil ? onDownload?() : onCancelDownload?() }
                        )
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
                .onTapGesture {
                    if needsDownload {
                        Haptics.impact(.light)
                        onDownload?()
                        return
                    }
                    guard canOpen else { return }
                    Haptics.impact(.light)
                    onOpen?()
                }

                if hasFooter, !message.deleted {
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
        .background(isMine ? Theme.accent : Theme.bubbleIncoming)
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

    private var emptyPlaceholder: some View {
        ZStack {
            (isMine ? Theme.accent : Theme.bubbleIncoming)
            Image(systemName: "photo")
                .font(.system(size: 28, weight: .medium))
                .foregroundStyle(isMine ? Color.white.opacity(0.7) : Theme.textSecondary)
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
                ReactionFooter(chips: reactions, onOutgoingBubble: isMine, onTap: onReactionTap) {
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
                MessageReceiptIcon(
                    receipt: message.receipt,
                    metaColor: captionMetaColor,
                    readColor: Color.white.opacity(0.95)
                )
            }
        }
    }
}
