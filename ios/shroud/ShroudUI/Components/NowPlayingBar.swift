import SwiftUI

/// The now-playing bar: a Liquid Glass capsule under the chat's header while a shared audio
/// file is active and its bubble is scrolled away (`docs/file-sharing.md` §11.6).
///
/// Human: Telegram and WhatsApp keep the song you started in reach after you scroll on: play or
/// pause, the title over `{artist} · {elapsed} / {duration}`, a speed chip for long files
/// (podcasts), and ✕ to stop. A thin accent line along the bottom edge fills with the file. A
/// tap on the text scrolls back to the bubble. Voice notes don't get the bar.
/// Agent: Pure presentation over `AudioFilePlayer`; the host places it (top, 8 under the header,
/// 16 from the sides) and decides `isVisible`. `onShowBubble` scrolls to the message. The glass
/// has its own container so it dematerialises on the way out, like the jump-to-latest control.
struct NowPlayingBar: View {
    var isVisible: Bool
    var player: AudioFilePlayer = .shared
    var onShowBubble: (UUID) -> Void

    static let height: CGFloat = 48
    static let playSide: CGFloat = 32
    static let progressHeight: CGFloat = 2

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GlassEffectContainer {
            if isVisible, let track = player.track {
                bar(track)
                    .transition(reduceMotion ? .opacity : .move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(Motion.respecting(reduceMotion, Motion.snappy), value: isVisible && player.track != nil)
    }

    private func bar(_ track: AudioFilePlayer.Track) -> some View {
        HStack(spacing: 10) {
            playButton

            Button {
                onShowBubble(track.messageID)
            } label: {
                VStack(alignment: .leading, spacing: 1) {
                    Text(track.title)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(Theme.textPrimary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Text(subtitle(track))
                        .font(.system(size: 12).monospacedDigit())
                        .foregroundStyle(Theme.textSecondary)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityHint("Shows the message")

            if player.offersSpeed {
                speedChip
            }

            Button {
                Haptics.impact(.light)
                player.stop()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Theme.textSecondary)
                    .frame(width: 32, height: 32)
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Stop")
        }
        .padding(.leading, 8)
        .padding(.trailing, 6)
        .frame(height: Self.height)
        .frame(maxWidth: .infinity)
        .overlay(alignment: .bottomLeading) {
            GeometryReader { geo in
                Rectangle()
                    .fill(Theme.accent)
                    .frame(width: geo.size.width * player.progress(for: track.messageID), height: Self.progressHeight)
                    .frame(maxHeight: .infinity, alignment: .bottom)
            }
            .allowsHitTesting(false)
            .accessibilityHidden(true)
        }
        .clipShape(Capsule())
        .glassEffect(.regular, in: .capsule)
    }

    /// `{artist} · {elapsed} / {duration}`, without the missing parts.
    private func subtitle(_ track: AudioFilePlayer.Track) -> String {
        let elapsed = AudioFileText.elapsedLabel(seconds: player.currentTime)
        let total: String? = if let ms = track.durationMs, ms > 0 {
            AudioFileText.totalLabel(ms: ms)
        } else if player.duration > 0 {
            AudioFileText.totalLabel(ms: Int(player.duration * 1000))
        } else {
            nil
        }
        let time = total.map { "\(elapsed) / \($0)" } ?? elapsed
        return [track.artist, time].compactMap { $0 }.joined(separator: " · ")
    }

    private var playButton: some View {
        Button {
            player.toggle()
        } label: {
            ZStack {
                Circle().fill(Theme.accent)
                Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(Color.white)
                    .contentTransition(.symbolEffect(.replace.offUp))
                    .offset(x: player.isPlaying ? 0 : 1)
            }
            .frame(width: Self.playSide, height: Self.playSide)
            .contentShape(Circle().inset(by: -6))
        }
        .pressable(scale: 0.88, dimming: 0)
        .accessibilityLabel(player.isPlaying ? "Pause" : "Play")
    }

    private var speedChip: some View {
        Button {
            player.cycleRate()
        } label: {
            Text(player.rateLabel)
                .font(.system(size: 12, weight: .semibold).monospacedDigit())
                .foregroundStyle(Theme.accent)
                .padding(.horizontal, 8)
                .frame(minWidth: 36, minHeight: 24)
                .background(Theme.accent.opacity(0.14), in: Capsule())
                .contentShape(Capsule().inset(by: -8))
        }
        .pressable(scale: 0.92, dimming: 0)
        .accessibilityLabel("Playback speed")
        .accessibilityValue(player.rateLabel)
    }
}
