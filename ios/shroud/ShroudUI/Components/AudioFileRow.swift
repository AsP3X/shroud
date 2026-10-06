import SwiftUI
import UIKit

/// The audio bubble's row: round cover, title, detail line and — on the active file — the
/// scrubber (`docs/file-sharing.md` §11.4).
///
/// Human: Takes the file bubble's tile slot, so an audio file keeps the §7 shell (width, corners,
/// caption, footer, failed look) and only this row differs. The cover says what a tap does:
/// download arrow, a ring with a stop glyph while it moves, play or pause once the file is here,
/// a retry arrow on a failed send. The detail line turns into `{elapsed} / {duration}` while the
/// file is the active one, and the scrubber appears under it.
/// Agent: Reads `AudioFilePlayer` itself, so the 30 Hz playhead redraws this row only, not the
/// bubble around it. A drag or tap on the scrubber seeks and claims the tap (`MessageTapClaim`),
/// so the bubble's and the row's own taps don't also play or pause.
struct AudioFileRow: View {
    enum Phase: Equatable {
        case transferring, failed, notOnDevice, onDevice
    }

    let message: MessagingController.ChatMessage
    let phase: Phase
    var transfer: MessagingController.MediaTransfer?
    /// The cover art (`th`), when the payload carried one.
    var cover: UIImage?
    /// The live player; injected so previews and the long-press hero can show a state.
    var player: AudioFilePlayer = .shared

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Non-nil while a finger is on the scrubber; the playhead and elapsed time follow it.
    @State private var scrubProgress: Double?

    static let coverSide: CGFloat = 44
    static let glyphSize: CGFloat = 18
    static let trackHeight: CGFloat = 3
    static let thumbSide: CGFloat = 10
    /// The scrubber's touch area, taller than the 3 pt track.
    static let scrubberHeight: CGFloat = 18

    private var isMine: Bool { message.isMine }
    private var isActive: Bool { player.isActive(message.id) }
    private var isPlaying: Bool { player.isPlaying(message.id) }
    private var title: String { message.fileTitle ?? message.fileName ?? "Audio" }

    private var primaryText: Color { isMine ? Color.white : Theme.textPrimary }
    private var detailColor: Color { isMine ? Color.white.opacity(0.65) : Theme.textSecondary }
    private var coverFill: Color { isMine ? Color.white.opacity(0.22) : Theme.accent }
    private var playedColor: Color { isMine ? Color.white : Theme.accent }
    private var restColor: Color { isMine ? Color.white.opacity(0.3) : Theme.accent.opacity(0.25) }

    var body: some View {
        HStack(alignment: .center, spacing: FileMessageBubble.tileSpacing) {
            coverView
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(primaryText)
                    .lineLimit(1)
                    .truncationMode(.middle)
                PacedRollingText(detailLine, pacing: transfer != nil)
                    .font(.system(size: 13).monospacedDigit())
                    .foregroundStyle(detailColor)
                    .lineLimit(1)
                if isActive {
                    scrubber
                        .padding(.top, 2)
                        .transition(.opacity)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .animation(Motion.respecting(reduceMotion, Motion.snappy), value: isActive)
    }

    // MARK: - Detail line

    /// `d` as a total, else what the player measured once the file is open.
    private var totalLabel: String? {
        if let ms = message.voiceDurationMs, ms > 0 { return AudioFileText.totalLabel(ms: ms) }
        if isActive, player.duration > 0 { return AudioFileText.totalLabel(ms: Int(player.duration * 1000)) }
        return nil
    }

    private var sizeLabel: String? {
        guard let bytes = message.mediaByteCount, bytes > 0 else { return nil }
        return MediaCrypto.byteCountLabel(bytes)
    }

    /// The §11.4 rules, in order; a missing duration drops its part.
    private var detailLine: String {
        if let transfer, let moved = transfer.movedBytes, let total = transfer.totalBytes {
            return "\(MediaCrypto.byteCountLabel(moved)) of \(MediaCrypto.byteCountLabel(total))"
        }
        if phase == .failed { return "Not sent" }
        if isActive {
            let elapsedSeconds = scrubProgress.map { $0 * player.duration } ?? player.currentTime
            let elapsed = AudioFileText.elapsedLabel(seconds: elapsedSeconds)
            return totalLabel.map { "\(elapsed) / \($0)" } ?? elapsed
        }
        var parts: [String] = []
        if let artist = message.fileArtist {
            parts.append(artist)
            if let totalLabel { parts.append(totalLabel) }
            if phase == .notOnDevice, let sizeLabel { parts.append(sizeLabel) }
        } else {
            if let totalLabel { parts.append(totalLabel) }
            if let sizeLabel { parts.append(sizeLabel) }
            if let type = message.fileType { parts.append(type.label) }
        }
        return parts.joined(separator: " · ")
    }

    // MARK: - Cover

    private var coverView: some View {
        ZStack {
            Circle().fill(coverFill)
            if let cover {
                Image(uiImage: cover)
                    .resizable()
                    .scaledToFill()
                    .frame(width: Self.coverSide, height: Self.coverSide)
                    .clipped()
                Color.black.opacity(0.35)
            }
            glyph
                .id(glyphIdentity)
                .transition(Motion.iconSwap)
        }
        .frame(width: Self.coverSide, height: Self.coverSide)
        .clipShape(Circle())
        .accessibilityHidden(true)
    }

    /// Play and pause swap in place; the other glyphs pop like the file tile's.
    private var glyphIdentity: Int {
        switch phase {
        case .transferring: 0
        case .failed: 1
        case .notOnDevice: 2
        case .onDevice: 3
        }
    }

    @ViewBuilder
    private var glyph: some View {
        switch phase {
        case .failed:
            symbol("arrow.clockwise")
        case .notOnDevice:
            symbol("arrow.down")
        case .onDevice:
            symbol(isPlaying ? "pause.fill" : "play.fill")
                .contentTransition(.symbolEffect(.replace.offUp))
                // `play.fill` is visually left-heavy; nudge it onto the optical centre.
                .offset(x: isPlaying ? 0 : 1)
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

    private func symbol(_ name: String) -> some View {
        Image(systemName: name)
            .font(.system(size: Self.glyphSize, weight: .semibold))
            .foregroundStyle(Color.white)
    }

    // MARK: - Scrubber

    private var progress: Double {
        scrubProgress ?? player.progress(for: message.id)
    }

    private var scrubber: some View {
        GeometryReader { geo in
            let width = max(1, geo.size.width - Self.thumbSide)
            let x = width * progress
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(restColor)
                    .frame(height: Self.trackHeight)
                Capsule()
                    .fill(playedColor)
                    .frame(width: x + Self.thumbSide / 2, height: Self.trackHeight)
                Circle()
                    .fill(playedColor)
                    .frame(width: Self.thumbSide, height: Self.thumbSide)
                    .offset(x: x)
            }
            .frame(maxHeight: .infinity)
            // The whole strip takes the finger, not just the 3 pt track.
            .contentShape(Rectangle())
            // Before the bubble's own tap: a touch here seeks and never plays or pauses.
            .highPriorityGesture(scrubGesture(width: width))
        }
        .frame(height: Self.scrubberHeight)
        .accessibilityHidden(true)
    }

    private func scrubGesture(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                MessageTapClaim.claim()
                player.isScrubbing = true
                scrubProgress = fraction(at: value.location.x, width: width)
            }
            .onEnded { value in
                MessageTapClaim.claim()
                let target = fraction(at: value.location.x, width: width)
                Haptics.impact(.light)
                player.seek(to: target)
                player.isScrubbing = false
                scrubProgress = nil
            }
    }

    private func fraction(at x: CGFloat, width: CGFloat) -> Double {
        Double(min(1, max(0, (x - Self.thumbSide / 2) / width)))
    }
}
