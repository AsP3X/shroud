import SwiftUI

/// A voice note's transcript, streamed in the way AI-written text appears: word after word fades
/// up out of a blur.
///
/// Human: Only text revealed in front of the reader streams — unfolded by a tap, by a new note
/// landing, or taking over from "Transcribing…". A thread opening with transcripts already
/// showing draws them in place. The web client streams the same way (`StreamedWords`).
/// Agent: Pure view. Decides once, at creation, whether to stream; give it a new identity to
/// stream again.
struct VoiceTranscriptText: View {
    let text: String
    let color: Color
    /// Invisible glyphs appended after the last word so the last line stays clear of the
    /// time and ticks drawn over its end (`MessageBubbleMetrics.metaReservation`). They are
    /// set in the meta's own font, so the room kept is exactly the meta's width.
    let trailingReservation: String?

    /// Seconds into the reveal; starts past the end when the text is drawn in place.
    @State private var elapsed: Double

    init(text: String, color: Color, streams: Bool, trailingReservation: String? = nil) {
        self.text = text
        self.color = color
        self.trailingReservation = trailingReservation
        _elapsed = State(initialValue: streams ? 0 : .infinity)
    }

    var body: some View {
        if elapsed.isInfinite {
            styled(Text(text))
        } else {
            let pieces = Self.pieces(of: text)
            let timing = TranscriptRevealTiming(pieces: pieces.count)
            styled(pieces.text)
                .textRenderer(TranscriptRevealRenderer(elapsed: elapsed, stagger: timing.stagger))
                .onAppear {
                    withAnimation(.linear(duration: timing.total)) { elapsed = timing.total }
                }
        }
    }

    private func styled(_ text: Text) -> some View {
        var styled = text
            .font(.system(size: 15))
            .foregroundStyle(color)
        if let trailingReservation {
            // Clear, and untagged: the reveal renderer draws it at once, and nothing shows.
            styled = styled + Text(verbatim: trailingReservation)
                .font(.system(size: MessageBubbleMetrics.metaFontSize).monospacedDigit())
                .foregroundStyle(Color.clear)
        }
        return styled
            .lineSpacing(2)
            .fixedSize(horizontal: false, vertical: true)
    }

    /// At most this many pieces stream in; a long transcript goes in chunks of several words.
    private static let maxPieces = 160

    /// The transcript as tagged runs: a word each (with its trailing space, so line breaking is
    /// unchanged), or a few words each once there are more than `maxPieces`.
    private static func pieces(of text: String) -> (text: Text, count: Int) {
        let words = text.matches(of: #/\S+\s*/#).map { String($0.output) }
        guard !words.isEmpty else { return (Text(text), 0) }
        let size = (words.count + maxPieces - 1) / maxPieces
        var tagged = Text(verbatim: "")
        var count = 0
        for start in stride(from: 0, to: words.count, by: size) {
            let piece = Text(verbatim: words[start..<min(start + size, words.count)].joined())
                .customAttribute(TranscriptPiece(index: count))
            tagged = Text("\(tagged)\(piece)")
            count += 1
        }
        return (tagged, count)
    }
}

/// Which piece of a streaming transcript a run belongs to.
private struct TranscriptPiece: TextAttribute {
    let index: Int
}

/// Pieces start `stagger` apart and each takes `pieceDuration` to settle; long transcripts close
/// the gaps so the whole reveal stays around a second.
private struct TranscriptRevealTiming {
    static let pieceDuration = 0.5
    let stagger: Double
    let total: Double

    init(pieces: Int) {
        stagger = min(0.045, 0.9 / Double(max(pieces, 1)))
        total = Double(max(pieces - 1, 0)) * stagger + Self.pieceDuration
    }
}

/// Draws each piece of the transcript fading up out of a blur, one after another.
private struct TranscriptRevealRenderer: TextRenderer, Animatable {
    var elapsed: Double
    let stagger: Double

    var animatableData: Double {
        get { elapsed }
        set { elapsed = newValue }
    }

    func draw(layout: Text.Layout, in context: inout GraphicsContext) {
        for line in layout {
            for run in line {
                let index = Double(run[TranscriptPiece.self]?.index ?? 0)
                let progress = (elapsed - index * stagger) / TranscriptRevealTiming.pieceDuration
                if progress >= 1 {
                    context.draw(run)
                } else if progress > 0 {
                    // Ease out: most of the blur lifts early, the last of it settles gently.
                    let eased = 1 - pow(1 - progress, 3)
                    var piece = context
                    piece.opacity = eased
                    piece.addFilter(.blur(radius: 6 * (1 - eased)))
                    piece.translateBy(x: 0, y: 3 * (1 - eased))
                    piece.draw(run)
                }
            }
        }
    }
}
