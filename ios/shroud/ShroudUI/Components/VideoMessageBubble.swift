import SwiftUI
import UIKit

/// Telegram's video bubble: poster, a play-glyph duration badge top-left, a blurred play disc
/// in the middle, and a transfer ring in its place while bytes move.
///
/// Human: The badge is the piece that makes a still read as *video* rather than a photo —
/// it carries the duration always, and the payload size until the clip is on the device
/// ("▶ 0:12 · 4.2 MB", then "▶ 1.1 MB / 4.2 MB" while downloading).
/// Agent: READS message + transfer state only. `onDownload`/`onCancel`/`onOpen` are the host's.
struct VideoMessageBubble: View {
    let message: MessagingController.ChatMessage
    let time: String
    var onDownload: (() -> Void)?
    /// Live transfer for this message (download or send), when one is running.
    var transfer: MessagingController.MediaTransfer?
    var onCancelDownload: (() -> Void)?
    var onRetry: (() -> Void)?
    var onOpen: (() -> Void)?
    var isRowEmbedded: Bool = true
    var frameReportID: UUID? = nil
    /// Quote header for a reply; nil for an ordinary clip.
    var reply: ReplyQuoteContent? = nil
    /// Jump to the quoted message.
    var onReplyTap: (() -> Void)? = nil
    /// Reaction chips, drawn in a foot strip under the media (with the caption, if any).
    var reactions: [ReactionChipContent] = []
    var onReactionTap: ((String) -> Void)? = nil

    @Environment(\.chatRowWidth) private var chatRowWidth
    @State private var poster: UIImage?

    private var isMine: Bool { message.isMine }
    private var isFailed: Bool { message.receipt == .failed }
    private var needsDownload: Bool { message.needsMediaDownload }
    /// Outbound clip still compressing or uploading — same ring, opposite direction.
    private var isSending: Bool { transfer?.isUpload == true }
    private var showsTransferControl: Bool { !isFailed && (needsDownload || isSending) }
    private var canOpen: Bool {
        !message.deleted && !isFailed && !isSending && message.videoData != nil
    }

    private var hasReactions: Bool { !reactions.isEmpty && !message.deleted }

    /// The bubble continues under the media: a caption, reaction chips, or both.
    private var hasFooter: Bool { hasCaption || hasReactions }

    private var hasCaption: Bool {
        let t = message.text.trimmingCharacters(in: .whitespacesAndNewlines)
        return !t.isEmpty && t != "Video" && t != "Media"
    }

    private var caption: String {
        message.text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var durationLabel: String {
        let ms = message.voiceDurationMs ?? 0
        let total = max(0, ms / 1000)
        return String(format: "%d:%02d", total / 60, total % 60)
    }

    /// Right half of the badge: bytes moved while transferring, total size until downloaded.
    private var sizeLabel: String? {
        if let transfer {
            switch transfer.phase {
            case .preparing:
                return "Compressing"
            case .transferring:
                guard let total = transfer.totalBytes, total > 0 else { return nil }
                guard let moved = transfer.movedBytes else {
                    return MediaCrypto.byteCountLabel(total)
                }
                return "\(MediaCrypto.byteCountLabel(moved)) / \(MediaCrypto.byteCountLabel(total))"
            case .finishing:
                return isSending ? "Sending" : "Decrypting"
            }
        }
        guard needsDownload, let bytes = message.mediaByteCount, bytes > 0 else { return nil }
        return MediaCrypto.byteCountLabel(bytes)
    }

    private var mediaEpoch: String {
        "\(message.id.uuidString)-\(message.videoData?.count ?? 0)-\(message.previewData?.count ?? 0)-\(message.imageData?.count ?? 0)"
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
        let maxH: CGFloat = 340
        let w = CGFloat(message.imageWidth ?? 240)
        let h = CGFloat(message.imageHeight ?? 180)
        guard w > 0, h > 0 else { return CGSize(width: maxW, height: maxW * 9 / 16) }
        // Landscape clips fill the bubble's width; portrait ones are bounded by height.
        let scale = min(maxW / w, maxH / h)
        let size = CGSize(width: max(150, w * scale), height: max(110, h * scale))
        // A caption or a reply header needs a line's worth of width to read.
        guard hasCaption || reply != nil else { return size }
        return CGSize(width: maxW, height: size.height)
    }

    private var displayedPoster: UIImage? {
        if let poster { return poster }
        if let data = message.displayPreviewData {
            return DecodedImageCache.image(forMessage: message.id, data: data)
        }
        return DecodedImageCache.image(for: message.id)
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
        // One element, like the text and voice bubbles, instead of a poster, a badge, the time,
        // each tick and the disc read one by one.
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityAddTraits(hasDefaultAction ? .isButton : [])
        .accessibilityAction {
            // A failed clip can't play, so a double tap retries, as the footer's button did
            // when it was its own element.
            if isFailed {
                onRetry?()
            } else {
                handleTap()
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
        .task(id: mediaEpoch) {
            await loadPoster()
        }
    }

    private var bubbleCore: some View {
        VStack(alignment: isMine ? .trailing : .leading, spacing: 6) {
            VStack(alignment: .leading, spacing: 0) {
                if let reply, !message.deleted {
                    replyHeader(reply)
                }

                posterStack
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

    /// Corners of the poster: squared off wherever the bubble continues (reply header above,
    /// caption below).
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

    /// Reply quote drawn on the bubble fill above the poster.
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

    // MARK: - Poster + chrome

    private var posterStack: some View {
        ZStack {
            Group {
                if let ui = displayedPoster {
                    Image(uiImage: ui)
                        .resizable()
                        .scaledToFill()
                        .frame(width: displaySize.width, height: displaySize.height)
                        .clipped()
                        .opacity(isFailed ? 0.55 : 1)
                        // A soft blur behind the ring is what tells you the clip isn't here yet.
                        .blur(radius: needsDownload && !isSending ? 1.5 : 0)
                        .scaleEffect(needsDownload && !isSending ? 1.04 : 1)
                        .transition(.opacity)
                } else {
                    emptyPlaceholder
                        .transition(.opacity)
                }
            }
            .animation(Motion.fade, value: displayedPoster == nil)
            .animation(Motion.standard, value: needsDownload)

            if !message.deleted {
                scrims
            }

            if isFailed {
                failedOverlay
            } else if showsTransferControl {
                MediaTransferControl(
                    mode: transfer.map { .busy($0) } ?? .idle(byteCount: message.mediaByteCount),
                    onTap: transferAction,
                    diameter: 54
                )
                // The bubble's label already speaks the state and the badge's size or phase, and
                // its default action is this tap; merged in, the disc's value would repeat them.
                .accessibilityHidden(true)
                .transition(.scale(scale: 0.8).combined(with: .opacity))
            } else if !message.deleted {
                playDisc
                    .transition(.scale(scale: 0.8).combined(with: .opacity))
            }

            if !message.deleted, !isFailed {
                chrome
            }
        }
        .animation(Motion.snappy, value: showsTransferControl)
        .animation(Motion.snappy, value: isFailed)
    }

    /// Top and bottom darkening so white badges survive a bright poster.
    private var scrims: some View {
        VStack(spacing: 0) {
            LinearGradient(
                colors: [Color.black.opacity(0.34), .clear],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: 52)
            Spacer(minLength: 0)
            LinearGradient(
                colors: [.clear, Color.black.opacity(0.30)],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: 46)
        }
        .allowsHitTesting(false)
    }

    private var chrome: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                durationBadge
                Spacer(minLength: 0)
            }
            Spacer(minLength: 0)
            if !hasFooter {
                HStack(spacing: 0) {
                    Spacer(minLength: 0)
                    timeChip
                }
            }
        }
        .padding(8)
        .allowsHitTesting(false)
    }

    /// "▶ 0:12" — plus the size while the clip is still on the server.
    private var durationBadge: some View {
        HStack(spacing: 4) {
            Image(systemName: "play.fill")
                .font(.system(size: 8, weight: .black))
                .foregroundStyle(Color.white)
            Text(durationLabel)
                .font(.system(size: 11, weight: .semibold).monospacedDigit())
                .foregroundStyle(Color.white)
            if let sizeLabel {
                Text("·")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(Color.white.opacity(0.55))
                Text(sizeLabel)
                    .font(.system(size: 11, weight: .medium).monospacedDigit())
                    .foregroundStyle(Color.white.opacity(0.9))
                    .lineLimit(1)
                    // Not numericText: its per-frame blur would run back to back for the whole
                    // transfer. Monospaced digits keep the width steady as the bytes tick.
                    .contentTransition(.opacity)
            }
        }
        .padding(.horizontal, 7)
        .padding(.vertical, 3.5)
        .background(Color.black.opacity(0.45), in: Capsule())
        // Springs when a transfer starts, changes phase or ends, and when the size comes or
        // goes; the byte ticks in between update in place.
        .animation(Motion.snappy, value: transfer?.phase)
        .animation(Motion.snappy, value: sizeLabel == nil)
    }

    /// The centre play control — Telegram's is a blurred disc, not a filled SF symbol.
    private var playDisc: some View {
        ZStack {
            Circle()
                .fill(Color.black.opacity(0.35))
                .background(.ultraThinMaterial.opacity(0.5), in: Circle())
                .frame(width: 54, height: 54)
            Image(systemName: "play.fill")
                .font(.system(size: 21, weight: .semibold))
                .foregroundStyle(Color.white)
                // Optical centring: a triangle's visual mass sits left of its bounding box.
                .offset(x: 1.5)
        }
        .shadow(color: .black.opacity(0.2), radius: 6, y: 2)
        .allowsHitTesting(false)
    }

    private var metaColor: Color { Color.white.opacity(0.8) }

    /// The time is brighter than `metaColor`, which stays dimmer for the ticks so delivered and
    /// read still differ; with the fill and the scrim under it, it keeps 4.5:1 on a white poster.
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
                    readColor: Color.white.opacity(0.95),
                    failedColor: Color.white
                )
            }
        }
        .padding(.horizontal, 7)
        .padding(.vertical, 3)
        .background(Color.black.opacity(0.5), in: Capsule())
    }

    // MARK: - States

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
        // The dimmed poster lets the light chat background through; this much black keeps
        // "Not sent" at 4.5:1 even over a white poster.
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
                // The row's own tap sees this touch too; unclaimed, it would open the player.
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

    /// No poster yet — a dark plate that shimmers while the encode/decrypt runs.
    private var emptyPlaceholder: some View {
        ZStack {
            LinearGradient(
                colors: [Color.black.opacity(0.65), Color.black.opacity(0.45)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            Image(systemName: "video.fill")
                .font(.system(size: 26, weight: .medium))
                .foregroundStyle(Color.white.opacity(0.35))
        }
        .frame(width: displaySize.width, height: displaySize.height)
        // The plate is dark in both appearances, so it keeps the bright sweep in dark mode too.
        .shimmering(transfer != nil, adaptsToAppearance: false)
    }

    // MARK: - Caption

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

    /// The poster owns every tap on it, including the ones it declines: the row's own tap would
    /// otherwise open a failed or still-uploading clip, or open this one a second time.
    private func handleTap() {
        MessageTapClaim.claim()
        if isSending { return }
        if needsDownload {
            Haptics.impact(.light)
            transfer == nil ? onDownload?() : onCancelDownload?()
            return
        }
        guard canOpen else { return }
        Haptics.impact(.light)
        onOpen?()
    }

    /// The disc's own tap: start a download, or cancel the one in flight. Nil while sending —
    /// an upload is already committed to the wire.
    private var transferAction: (() -> Void)? {
        guard !isSending, let action = transfer == nil ? onDownload : onCancelDownload else { return nil }
        return {
            // Claimed like the poster's tap, or the row would restart a download just cancelled.
            MessageTapClaim.claim()
            action()
        }
    }

    /// A double tap does something: retry, download or cancel, or play.
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
        parts.append("Video")
        parts.append(durationLabel)
        if hasCaption, !message.deleted { parts.append(caption) }
        if isFailed {
            parts.append("Not sent")
            if let error = message.sendError, !error.isEmpty { parts.append(error) }
        } else {
            if needsDownload { parts.append(transfer == nil ? "Not downloaded" : "Downloading") }
            // The badge's size or progress; "1.1 MB of 4.2 MB" reads better than its slash.
            if let sizeLabel { parts.append(sizeLabel.replacingOccurrences(of: " / ", with: " of ")) }
        }
        if hasReactions, let summary = reactions.spokenSummary { parts.append(summary) }
        parts.append(time)
        // A failed send already said "Not sent".
        if isMine, !message.deleted, !isFailed {
            parts.append(message.receipt.spokenLabel)
        }
        return parts.joined(separator: ", ")
    }

    private func loadPoster() async {
        if let data = message.displayPreviewData, let ui = UIImage(data: data) {
            DecodedImageCache.store(message.id, image: ui)
            poster = ui
            return
        }
        // Only generate from full video after the user downloaded it.
        guard let video = message.videoData else {
            poster = nil
            return
        }
        if let jpeg = await VideoMedia.thumbnailJPEG(from: video),
           let ui = UIImage(data: jpeg)
        {
            DecodedImageCache.store(message.id, image: ui)
            poster = ui
        }
    }
}
