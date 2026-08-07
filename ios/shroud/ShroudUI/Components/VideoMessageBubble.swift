import SwiftUI
import UIKit

/// Video bubble: envelope poster + download chip until full video is fetched.
struct VideoMessageBubble: View {
    let message: MessagingController.ChatMessage
    let time: String
    var onDownload: (() -> Void)?
    var isDownloading: Bool = false
    var onRetry: (() -> Void)?
    var onOpen: (() -> Void)?
    var isRowEmbedded: Bool = true
    var frameReportID: UUID? = nil

    @Environment(\.chatRowWidth) private var chatRowWidth
    @State private var poster: UIImage?

    private var isMine: Bool { message.isMine }
    private var isFailed: Bool { message.receipt == .failed }
    private var needsDownload: Bool { message.needsMediaDownload }
    private var canOpen: Bool {
        !message.deleted && !isFailed && message.videoData != nil
    }

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
        let m = total / 60
        let s = total % 60
        return String(format: "%d:%02d", m, s)
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
        return min(240, budget)
    }

    private var displaySize: CGSize {
        let maxW = mediaWidthCap
        let maxH: CGFloat = 320
        let w = CGFloat(message.imageWidth ?? 240)
        let h = CGFloat(message.imageHeight ?? 180)
        guard w > 0, h > 0 else { return CGSize(width: 200, height: 150) }
        let scale = min(maxW / w, maxH / h, 1)
        let size = CGSize(width: max(140, w * scale), height: max(100, h * scale))
        guard hasCaption else { return size }
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
        .task(id: mediaEpoch) {
            await loadPoster()
        }
    }

    private var bubbleCore: some View {
        VStack(alignment: isMine ? .trailing : .leading, spacing: 6) {
            VStack(alignment: .leading, spacing: 0) {
                ZStack {
                    Group {
                        if message.deleted {
                            deletedPlaceholder
                        } else if let ui = displayedPoster {
                            Image(uiImage: ui)
                                .resizable()
                                .scaledToFill()
                                .frame(width: displaySize.width, height: displaySize.height)
                                .clipped()
                                .opacity(isFailed ? 0.55 : 1)
                                .blur(radius: needsDownload ? 0.5 : 0)
                                .transition(.opacity)
                        } else {
                            emptyPlaceholder
                                .transition(.opacity)
                        }
                    }
                    .animation(Motion.fade, value: displayedPoster == nil)

                    if isFailed {
                        failedOverlay
                    } else if needsDownload {
                        MediaDownloadChip(
                            byteCount: message.mediaByteCount,
                            isDownloading: isDownloading,
                            action: { onDownload?() }
                        )
                    } else {
                        playBadge
                        VStack {
                            Spacer()
                            HStack {
                                durationChip
                                Spacer()
                                if !hasCaption { timeChip }
                            }
                            .padding(8)
                        }
                    }

                    // Duration still visible while waiting to download.
                    if needsDownload, !isFailed {
                        VStack {
                            Spacer()
                            HStack {
                                durationChip
                                Spacer()
                                if !hasCaption { timeChip }
                            }
                            .padding(8)
                        }
                    }
                }
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

    private var playBadge: some View {
        Image(systemName: "play.circle.fill")
            .font(.system(size: 52))
            .symbolRenderingMode(.palette)
            .foregroundStyle(Color.white, Color.black.opacity(0.45))
            .shadow(color: .black.opacity(0.25), radius: 4, y: 1)
    }

    private var durationChip: some View {
        Text(durationLabel)
            .font(.system(size: 11, weight: .semibold).monospacedDigit())
            .foregroundStyle(Color.white)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(Color.black.opacity(0.45))
            .clipShape(Capsule())
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
    }

    private var emptyPlaceholder: some View {
        ZStack {
            (isMine ? Theme.accent : Theme.bubbleIncoming)
            Image(systemName: "video")
                .font(.system(size: 28, weight: .medium))
                .foregroundStyle(isMine ? Color.white.opacity(0.7) : Theme.textSecondary)
        }
        .frame(width: displaySize.width, height: displaySize.height)
    }

    private var deletedPlaceholder: some View {
        ZStack {
            Theme.backgroundGrouped
            Text("Video deleted")
                .font(.system(size: 14))
                .foregroundStyle(Theme.textSecondary)
        }
        .frame(width: displaySize.width, height: 120)
    }

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
