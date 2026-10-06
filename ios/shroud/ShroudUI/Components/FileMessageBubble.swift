import SwiftUI
import UIKit

/// A shared file in the thread: tile, name, `{size} · {TYPE}`, the warning line, the caption.
///
/// Human: The tile says what a tap does — download arrow when the file isn't on this device, a
/// ring with a stop glyph while it moves (a tap stops a download), the category glyph once it
/// is here, a retry arrow on a failed send, a question mark for a type Shroud doesn't open. The
/// name stays on one line, truncated in the middle, so the extension is always visible
/// (`docs/file-sharing.md` §7). A PDF with a preview gets the card of §10.1 above the row: the
/// top of its first page, sharp once the file is on this device.
/// An audio file (§11.4) keeps this shell and swaps the tile row for `AudioFileRow` — a round
/// cover with play/pause, title, detail line and the active file's scrubber. One AVFoundation
/// can't play falls back to this plain row with a music glyph and "Can't play on this iPhone".
/// Agent: Presentation, plus asking `MessagingController` (when it is in the environment) to draw
/// a stored PDF's card. The host decides what a tap does (`onTap` = download if needed, then
/// open — or play/pause for an audio file). Every tap claims `MessageTapClaim`, like the photo
/// bubble. Audio bubbles in the thread report whether they are on screen to `AudioFilePlayer`,
/// which the now-playing bar reads.
struct FileMessageBubble: View {
    let message: MessagingController.ChatMessage
    let time: String
    /// Live transfer for this message, when one is running.
    var transfer: MessagingController.MediaTransfer?
    /// Download when needed, then open.
    var onTap: (() -> Void)?
    var onCancelDownload: (() -> Void)?
    var onRetry: (() -> Void)?
    var isRowEmbedded: Bool = true
    var frameReportID: UUID? = nil
    var reply: ReplyQuoteContent? = nil
    var onReplyTap: (() -> Void)? = nil
    var reactions: [ReactionChipContent] = []
    var onReactionTap: ((String) -> Void)? = nil

    @Environment(\.chatRowWidth) private var chatRowWidth
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.displayScale) private var displayScale
    @Environment(MessagingController.self) private var messaging: MessagingController?
    /// Read only for audio files: whether this one plays here, and whether it is playing.
    @State private var audioPlayer = AudioFilePlayer.shared

    static let tileSide: CGFloat = 44
    static let tileRadius: CGFloat = 12
    static let tileSpacing: CGFloat = 10
    static let minWidth: CGFloat = 240
    static let maxWidth: CGFloat = 300
    /// The PDF card's inset from the bubble's top and sides, and its corner radius (§10.1).
    static let cardInset: CGFloat = 4
    static let cardRadius: CGFloat = 12

    private var isMine: Bool { message.isMine }
    private var isFailed: Bool { isMine && message.receipt == .failed }
    private var type: SharedFile.FileType? { message.fileType }
    private var name: String { message.fileName ?? "File" }
    private var caption: String { message.text.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var hasCaption: Bool { !caption.isEmpty }
    private var hasReactions: Bool { !reactions.isEmpty && !message.deleted }
    private var isPDF: Bool { type?.category == .pdf }
    private var isAudio: Bool { type?.category == .audio }
    /// An audio file this device's player couldn't open (§11.4 "Can't play here").
    private var cantPlayHere: Bool { isAudio && audioPlayer.isUnplayable(message.id) }
    /// The audio row instead of the tile row.
    private var showsAudioRow: Bool { isAudio && !cantPlayHere }
    /// The sharp card drawn from the file, once it is on this device.
    private var localCard: PDFCardPreviewStore.Card? {
        isPDF ? PDFCardPreviewStore.shared.card(for: message.id) : nil
    }
    /// The envelope's `th` (soft; a full page from older senders).
    private var envelopePreview: UIImage? {
        guard state != .unsupported else { return nil }
        return DecodedImageCache.image(forMessage: message.id, data: message.previewData)
    }
    private var showsCard: Bool {
        isPDF && state != .unsupported && (localCard != nil || envelopePreview != nil)
    }
    private var pageCount: Int? { message.filePageCount ?? localCard?.pageCount }
    /// The card's width in pixels: the bubble's width minus the insets, at the screen's scale.
    private var cardPixelWidth: Int {
        Int(((bubbleWidthCap - Self.cardInset * 2) * displayScale).rounded(.up))
    }

    /// What the tile shows, in the spec's order of precedence.
    private enum TileState: Equatable {
        case unsupported, failed, transferring, notOnDevice, onDevice
    }

    private var state: TileState {
        if type == nil { return .unsupported }
        if transfer != nil { return .transferring }
        if isFailed { return .failed }
        if message.needsMediaDownload { return .notOnDevice }
        return .onDevice
    }

    private var bubbleWidthCap: CGFloat {
        let row = chatRowWidth > 0 ? chatRowWidth : MessageBubbleMetrics.fallbackRowWidth
        let budget = min(row, max(MessageBubbleMetrics.minBubbleWidth, row - MessageBubbleMetrics.oppositeGutter))
        return min(Self.maxWidth, budget)
    }

    private var bubbleFill: Color { isMine ? Theme.bubbleOutgoing : Theme.bubbleIncoming }
    private var primaryText: Color { isMine ? Color.white : Theme.textPrimary }
    private var secondaryText: Color { isMine ? Color.white.opacity(0.7) : Theme.textSecondary }
    /// The warning colour is dark amber in light mode: on the accent bubble it is white instead.
    private var warningText: Color { isMine ? Color.white : Theme.warningText }
    private var warningIcon: Color { isMine ? Color.white : Theme.warningIcon }
    private var metaColor: Color { isMine ? Color.white.opacity(0.65) : Theme.textSecondary.opacity(0.95) }

    private var corners: UnevenRoundedRectangle {
        UnevenRoundedRectangle(
            topLeadingRadius: 17.5,
            bottomLeadingRadius: isMine ? 17.5 : 5,
            bottomTrailingRadius: isMine ? 5 : 17.5,
            topTrailingRadius: 17.5,
            style: .continuous
        )
    }

    var body: some View {
        Group {
            if isRowEmbedded {
                HStack(alignment: .bottom, spacing: 0) {
                    if isMine { Spacer(minLength: MessageBubbleMetrics.oppositeGutter) }
                    bubbleCore
                    if !isMine { Spacer(minLength: MessageBubbleMetrics.oppositeGutter) }
                }
                .frame(maxWidth: .infinity, alignment: isMine ? .trailing : .leading)
            } else {
                bubbleCore
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityValue(accessibilityValue)
        .accessibilityAddTraits(hasDefaultAction ? .isButton : [])
        .accessibilityAction { handleTap() }
        .accessibilityActions {
            if isFailed, let onRetry {
                Button("Retry", action: onRetry)
            }
            if reply != nil, !message.deleted, let onReplyTap {
                Button("Show replied message", action: onReplyTap)
            }
        }
        .reactionAccessibilityActions(hasReactions ? reactions : [], onTap: onReactionTap)
    }

    private var bubbleCore: some View {
        VStack(alignment: isMine ? .trailing : .leading, spacing: 6) {
            // Hugs the widest row between 240 and 300 pt (`LinkBubbleLayout`, as link bubbles do):
            // a short name keeps the minimum, a long one truncates at the cap.
            LinkBubbleLayout(maxWidth: bubbleWidthCap, fillsWidth: showsCard) {
                Color.clear
                    .frame(width: Self.minWidth, height: 0)
                    .layoutValue(key: LinkBubbleRole.self, value: .ideal)

                if let reply, !message.deleted {
                    ReplyQuoteView(
                        content: reply,
                        style: isMine ? .outgoing : .incoming,
                        onTap: onReplyTap
                    )
                    .padding(.horizontal, 6)
                    .padding(.top, 6)
                    .layoutValue(key: LinkBubbleRole.self, value: .ideal)
                }

                if showsCard {
                    PDFPreviewCard(envelope: envelopePreview, sharp: localCard?.image, reduceMotion: reduceMotion)
                        .padding(.horizontal, Self.cardInset)
                        .padding(.top, reply == nil ? Self.cardInset : 6)
                        .layoutValue(key: LinkBubbleRole.self, value: .ideal)
                }

                Group {
                    if showsAudioRow {
                        AudioFileRow(
                            message: message,
                            phase: audioPhase,
                            transfer: transfer,
                            cover: envelopePreview
                        )
                    } else {
                        fileRow
                    }
                }
                .padding(.leading, 10)
                .padding(.trailing, 12)
                .padding(.top, showsCard ? 8 : (reply == nil ? 10 : 8))
                .layoutValue(key: LinkBubbleRole.self, value: .ideal)

                if hasCaption {
                    Text(MessageBubbleMetrics.normalizedForDisplay(caption))
                        .font(.system(size: MessageBubbleMetrics.bodyFontSize))
                        .foregroundStyle(primaryText)
                        .multilineTextAlignment(.leading)
                        .lineSpacing(2.5)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.leading, MessageBubbleMetrics.textLeadingPad)
                        .padding(.trailing, MessageBubbleMetrics.textTrailingPad)
                        .padding(.top, 8)
                }

                footer
            }
            .background(bubbleFill)
            .clipShape(corners)
            .overlay {
                if isFailed {
                    corners.stroke(Theme.danger.opacity(0.7), lineWidth: 1.5)
                }
            }
            .contentShape(corners)
            .onTapGesture(perform: handleTap)
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

            if isFailed, let error = message.sendError, !error.isEmpty {
                Text(error)
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.danger)
                    .multilineTextAlignment(.trailing)
                    .frame(maxWidth: bubbleWidthCap, alignment: .trailing)
                    .padding(.horizontal, 2)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .animation(Motion.snappy, value: state)
        .animation(Motion.snappy, value: cantPlayHere)
        // The now-playing bar shows while the active file's bubble is off screen (§11.6).
        .onScrollVisibilityChange(threshold: 0.2) { visible in
            guard isAudio, isRowEmbedded else { return }
            audioPlayer.setBubbleVisible(message.id, visible)
        }
        .onDisappear {
            guard isAudio, isRowEmbedded else { return }
            audioPlayer.setBubbleVisible(message.id, false)
        }
        .task(id: cardRequestKey) {
            guard isPDF, message.fileStored, !message.deleted else { return }
            messaging?.requestPDFCard(for: message, pixelWidth: cardPixelWidth)
        }
    }

    /// Asks again when the file lands, or the bubble gets wider (rotation, iPad split).
    private var cardRequestKey: String {
        "\(message.id)-\(message.fileStored)-\(cardPixelWidth)"
    }

    // MARK: - Tile + text

    private var fileRow: some View {
        HStack(alignment: .center, spacing: Self.tileSpacing) {
            tile
            VStack(alignment: .leading, spacing: 2) {
                Text(name)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(primaryText)
                    .lineLimit(1)
                    .truncationMode(.middle)
                // "1.2 MB of 4.8 MB" rolls as a transfer runs, paced to twice a second; the
                // size after it lands at once.
                PacedRollingText(metaLine, pacing: transfer != nil)
                    .font(.system(size: 13).monospacedDigit())
                    .foregroundStyle(secondaryText)
                    .lineLimit(1)
                if cantPlayHere {
                    // In the warning's place, in the secondary colour: a fact, not a caution.
                    HStack(spacing: 4) {
                        Image(systemName: "info.circle.fill")
                            .font(.system(size: 10, weight: .semibold))
                        Text(Self.cantPlayLine)
                            .font(.system(size: 12, weight: .medium))
                            .lineLimit(1)
                    }
                    .foregroundStyle(secondaryText)
                } else if let warning = type?.warning {
                    HStack(spacing: 4) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(warningIcon)
                        Text(warning.bubbleLine)
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(warningText)
                            .lineLimit(1)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// The audio row's state, from the tile's.
    private var audioPhase: AudioFileRow.Phase {
        switch state {
        case .transferring: .transferring
        case .failed: .failed
        case .notOnDevice: .notOnDevice
        case .onDevice, .unsupported: .onDevice
        }
    }

    /// "Can't play on this iPhone" / "… iPad" (§11.4).
    static var cantPlayLine: String {
        UIDevice.current.userInterfaceIdiom == .pad ? "Can't play on this iPad" : "Can't play on this iPhone"
    }

    private var metaLine: String {
        guard let type else { return SharedFile.unsupportedLabel }
        if let transfer, let moved = transfer.movedBytes, let total = transfer.totalBytes {
            return "\(MediaCrypto.byteCountLabel(moved)) of \(MediaCrypto.byteCountLabel(total))"
        }
        if isFailed { return "Not sent" }
        guard let bytes = message.mediaByteCount, bytes > 0 else {
            return SharedFile.metaLine(byteCount: nil, type: type, pageCount: pageCount)
        }
        return SharedFile.metaLine(byteCount: Int64(bytes), type: type, pageCount: pageCount)
    }

    private var tileFill: Color {
        if state == .unsupported { return Theme.textSecondary.opacity(0.35) }
        return isMine ? Color.white.opacity(0.22) : Theme.accent
    }

    /// The tile's own `th` — not when the card above already shows it.
    private var previewImage: UIImage? {
        showsCard ? nil : envelopePreview
    }

    private var tile: some View {
        ZStack {
            RoundedRectangle(cornerRadius: Self.tileRadius, style: .continuous)
                .fill(tileFill)
            if let previewImage {
                Image(uiImage: previewImage)
                    .resizable()
                    .scaledToFill()
                    .frame(width: Self.tileSide, height: Self.tileSide)
                    .clipped()
                Color.black.opacity(0.35)
            }
            tileGlyph
                .id(state)
                .transition(Motion.iconSwap)
        }
        .frame(width: Self.tileSide, height: Self.tileSide)
        .clipShape(RoundedRectangle(cornerRadius: Self.tileRadius, style: .continuous))
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private var tileGlyph: some View {
        switch state {
        case .unsupported:
            glyph("questionmark")
        case .failed:
            glyph("arrow.clockwise")
        case .notOnDevice:
            glyph("arrow.down")
        case .onDevice:
            glyph(Self.symbol(for: type?.category))
        case .transferring:
            ZStack {
                if let transfer {
                    FileTransferRing(transfer: transfer, reduceMotion: reduceMotion)
                }
                Image(systemName: "stop.fill")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(Color.white)
            }
            .frame(width: 30, height: 30)
        }
    }

    private func glyph(_ systemName: String) -> some View {
        Image(systemName: systemName)
            .font(.system(size: 19, weight: .semibold))
            .foregroundStyle(Color.white)
    }

    /// The category glyph of a file on this device.
    static func symbol(for category: SharedFile.Category?) -> String {
        switch category {
        case .text: "text.document.fill"
        case .pdf: "richtext.page.fill"
        case .word: "doc.text.fill"
        case .excel: "tablecells.fill"
        case .powerPoint: "play.rectangle.on.rectangle.fill"
        case .image: "photo.fill"
        case .video: "video.fill"
        case .audio: "music.note"
        case .app: "shippingbox.fill"
        case nil: "doc.fill"
        }
    }

    // MARK: - Footer (time, ticks, reactions)

    @ViewBuilder
    private var footer: some View {
        if hasReactions {
            ReactionFooter(
                chips: reactions,
                onOutgoingBubble: isMine,
                onTap: onReactionTap,
                chipsAccessible: false
            ) {
                metaRow
            }
            .padding(.leading, 8)
            .padding(.trailing, MessageBubbleMetrics.metaTrailingPad)
            .padding(.top, 6)
            .padding(.bottom, 6)
            .layoutValue(key: LinkBubbleRole.self, value: .footer)
        } else {
            metaRow
                .padding(.trailing, MessageBubbleMetrics.metaTrailingPad)
                .padding(.top, hasCaption ? 4 : 2)
                .padding(.bottom, 5)
                .layoutValue(key: LinkBubbleRole.self, value: .trailing)
        }
    }

    private var metaRow: some View {
        HStack(spacing: MessageBubbleMetrics.metaSpacing) {
            Text(time)
                .font(.system(size: MessageBubbleMetrics.metaFontSize, weight: .regular).monospacedDigit())
                .foregroundStyle(metaColor)
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
    }

    // MARK: - Actions

    /// The bubble owns every tap on it, including the ones it declines, so the row's own tap
    /// never acts a second time.
    private func handleTap() {
        MessageTapClaim.claim()
        switch state {
        case .unsupported:
            return
        case .transferring:
            // An upload is already committed to the wire; only a download stops.
            guard transfer?.isUpload == false else { return }
            Haptics.impact(.light)
            onCancelDownload?()
        case .failed:
            Haptics.impact(.light)
            onRetry?()
        case .notOnDevice, .onDevice:
            Haptics.impact(.light)
            onTap?()
        }
    }

    // MARK: - Accessibility

    private var hasDefaultAction: Bool {
        switch state {
        case .unsupported: false
        case .transferring: transfer?.isUpload == false
        case .failed: onRetry != nil
        case .notOnDevice, .onDevice: onTap != nil
        }
    }

    /// `File, {name}, {size}` plus the warning (§7); `Audio, {title}, {artist}, {duration}` for
    /// an audio file that plays here (§11.4).
    private var accessibilityLabel: String {
        if showsAudioRow {
            var parts = ["Audio", message.fileTitle ?? name]
            if let artist = message.fileArtist { parts.append(artist) }
            if let ms = message.voiceDurationMs, ms > 0 { parts.append(AudioFileText.totalLabel(ms: ms)) }
            return parts.joined(separator: ", ")
        }
        var parts = ["File", name]
        if let pageCount {
            parts.append(SharedFile.pageCountLabel(pageCount))
        }
        if let bytes = message.mediaByteCount, bytes > 0 {
            parts.append(MediaCrypto.byteCountLabel(bytes))
        }
        if cantPlayHere {
            parts.append(Self.cantPlayLine)
        } else if let warning = type?.warning {
            parts.append(warning.spokenSuffix)
        }
        return parts.joined(separator: ", ")
    }

    /// Who, the quote, the caption, the state, reactions, time and ticks — after the label.
    private var accessibilityValue: String {
        var parts = [isMine ? "You" : "Them"]
        if let reply, !message.deleted {
            parts.append("Reply to \(reply.author): \(reply.text)")
        }
        if hasCaption { parts.append(caption) }
        switch state {
        case .unsupported:
            parts.append(SharedFile.unsupportedLabel)
        case .failed:
            parts.append("Not sent")
            if let error = message.sendError, !error.isEmpty { parts.append(error) }
        case .transferring:
            if let transfer {
                parts.append(transfer.isUpload ? "Sending" : "Downloading")
                if !transfer.isIndeterminate { parts.append("\(Int(transfer.ringFraction * 100)) percent") }
            }
        case .notOnDevice:
            parts.append("Not downloaded")
        case .onDevice:
            if showsAudioRow, audioPlayer.isActive(message.id) {
                parts.append(audioPlayer.isPlaying ? "Playing" : "Paused")
            }
        }
        if hasReactions, let summary = reactions.spokenSummary { parts.append(summary) }
        parts.append(time)
        if isMine, !message.deleted, !isFailed {
            parts.append(message.receipt.spokenLabel)
        }
        return parts.joined(separator: ", ")
    }
}

/// The top of a PDF's first page, 2:1, pinned to the page's top edge (§10.1).
///
/// Human: White under the image, so a page with a transparent background or a strip shorter
/// than the card still reads as paper; a hairline keeps the white page off a white bubble.
private struct PDFPreviewCard: View {
    let envelope: UIImage?
    let sharp: UIImage?
    let reduceMotion: Bool

    var body: some View {
        Color.white
            .aspectRatio(PDFPagePreview.cardAspect, contentMode: .fit)
            .overlay(alignment: .top) {
                ZStack(alignment: .top) {
                    if let envelope {
                        page(envelope)
                    }
                    if let sharp {
                        page(sharp)
                            .transition(.opacity)
                    }
                }
                .animation(reduceMotion ? nil : .easeOut(duration: 0.15), value: sharp != nil)
            }
            .clipShape(RoundedRectangle(cornerRadius: FileMessageBubble.cardRadius, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: FileMessageBubble.cardRadius, style: .continuous)
                    .strokeBorder(Color.black.opacity(0.1), lineWidth: 0.5)
            }
            .accessibilityHidden(true)
    }

    private func page(_ image: UIImage) -> some View {
        Image(uiImage: image)
            .resizable()
            .interpolation(.high)
            .scaledToFill()
    }
}

/// The tile's (and the audio cover's) progress ring: fills with the transfer, sweeps while there
/// is no number.
struct FileTransferRing: View {
    let transfer: MessagingController.MediaTransfer
    let reduceMotion: Bool

    private static let lineWidth: CGFloat = 2.5
    private static let sweepPeriod: TimeInterval = 1.1
    private static let sweepLength: CGFloat = 0.22

    var body: some View {
        ZStack {
            Circle()
                .stroke(Color.white.opacity(0.3), lineWidth: Self.lineWidth)
            if transfer.isIndeterminate {
                sweep
            } else {
                Circle()
                    .trim(from: 0, to: max(0.03, transfer.ringFraction))
                    .stroke(Color.white, style: StrokeStyle(lineWidth: Self.lineWidth, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .animation(.easeOut(duration: 0.3), value: transfer.ringFraction)
            }
        }
    }

    @ViewBuilder
    private var sweep: some View {
        let arc = Circle()
            .trim(from: 0, to: Self.sweepLength)
            .stroke(Color.white, style: StrokeStyle(lineWidth: Self.lineWidth, lineCap: .round))
        if reduceMotion {
            arc.rotationEffect(.degrees(-90))
        } else {
            TimelineView(.animation) { context in
                let t = context.date.timeIntervalSinceReferenceDate
                    .truncatingRemainder(dividingBy: Self.sweepPeriod) / Self.sweepPeriod
                arc.rotationEffect(.degrees(-90 + t * 360))
            }
        }
    }
}

#Preview("File bubbles") {
    let me = UUID()
    @MainActor func file(_ name: String, mine: Bool, stored: Bool, caption: String = "", receipt: MessageReceiptStatus = .read) -> MessagingController.ChatMessage {
        MessagingController.ChatMessage(
            id: UUID(),
            peerUserID: UUID(),
            senderUserID: me,
            text: caption,
            createdAt: Date(),
            isMine: mine,
            deleted: false,
            receipt: receipt,
            kind: .file,
            mediaObjectId: UUID(),
            mediaByteCount: 2_400_000,
            fileName: name,
            fileStored: stored
        )
    }
    return ScrollView {
        VStack(spacing: 10) {
            FileMessageBubble(message: file("Quarterly report 2026.pdf", mine: false, stored: false), time: "12:04")
            FileMessageBubble(message: file("Budget.xlsm", mine: false, stored: true, caption: "Numbers for Q3"), time: "12:05")
            FileMessageBubble(message: file("installer.apk", mine: true, stored: true), time: "12:06")
            FileMessageBubble(message: file("notes.txt", mine: true, stored: true, receipt: .failed), time: "12:07")
            FileMessageBubble(message: file("page.html", mine: false, stored: false), time: "12:08")
            FileMessageBubble(
                message: file("Movie.mov", mine: false, stored: false),
                time: "12:09",
                transfer: .init(phase: .transferring, isUpload: false, fraction: 0.4, totalBytes: 2_400_000)
            )
        }
        .padding(16)
    }
    .background(Theme.backgroundChat)
}
