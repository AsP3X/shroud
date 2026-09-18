import SwiftUI

/// Which voice notes have their transcript unfolded. Transcripts start folded, as in Telegram.
///
/// Human: The fold lives outside the bubble's own `@State` so the long-press hero draws the
/// same fold as the list bubble it lifts off, and an open note stays open while the thread
/// re-renders around it.
/// Agent: In-memory, session-only view state; nothing is persisted.
@Observable
@MainActor
final class VoiceTranscriptDisclosure {
    static let shared = VoiceTranscriptDisclosure()

    private var openIDs: Set<UUID> = []

    func isOpen(_ id: UUID) -> Bool {
        openIDs.contains(id)
    }

    func setOpen(_ open: Bool, for id: UUID) {
        if open {
            openIDs.insert(id)
        } else {
            openIDs.remove(id)
        }
    }
}

/// Telegram's "→A" transcript toggle, sat to the right of a voice note's waveform.
///
/// Human: Folded it reads "→A" (voice to text); unfolded, the arrow slides off and the A's legs
/// swing into a chevron pointing back up — one set of strokes morphing, not two icons swapping.
/// A stroke laps the button while this device is still transcribing. The geometry matches the
/// web client's SVG so the two platforms draw the same glyph.
/// Agent: Pure view; the bubble owns the fold and the transcription state.
struct VoiceTranscriptButton: View {
    let isOpen: Bool
    let isWorking: Bool
    /// Glyph and ring colour.
    let ink: Color
    let fill: Color
    var isEnabled = true
    let action: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    static let size: CGFloat = 28

    var body: some View {
        Button(action: action) {
            ZStack {
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .fill(fill)
                glyph
                if isWorking {
                    TranscriptLapRing(color: ink, isAnimated: !reduceMotion)
                        .transition(.opacity)
                }
            }
            .frame(width: Self.size, height: Self.size)
            .opacity(isEnabled ? 1 : 0.5)
        }
        .pressable(scale: 0.88, dimming: 0)
        .disabled(!isEnabled)
        .animation(Motion.snappy, value: isWorking)
        .animation(Motion.snappy, value: isEnabled)
        .accessibilityHidden(true)
    }

    private var glyph: some View {
        let progress: CGFloat = isOpen ? 1 : 0
        let unit = TranscriptGlyph.unit(in: TranscriptGlyph.size)
        let style = StrokeStyle(lineWidth: 2 * unit, lineCap: .round, lineJoin: .round)
        return ZStack {
            TranscriptGlyph.Arrow()
                .stroke(style: style)
                .opacity(1 - progress)
                .offset(x: -4 * unit * progress)
            TranscriptGlyph.Legs(progress: progress)
                .stroke(style: style)
            TranscriptGlyph.Crossbar()
                .stroke(style: style)
                .scaleEffect(x: 1 - 0.7 * progress, y: 1, anchor: TranscriptGlyph.Crossbar.anchor)
                .opacity(1 - progress)
        }
        .foregroundStyle(ink)
        .frame(width: TranscriptGlyph.size, height: TranscriptGlyph.size)
        .animation(Motion.respecting(reduceMotion, Motion.standard), value: isOpen)
    }
}

// MARK: - Glyph

/// Strokes of the "→A" glyph, drawn in a 24-unit box (the web client's SVG viewBox).
private enum TranscriptGlyph {
    static let size: CGFloat = 20

    static func unit(in side: CGFloat) -> CGFloat {
        side / 24
    }

    /// Maps a point in the 24-unit box into `rect`, centred.
    static func point(_ x: CGFloat, _ y: CGFloat, in rect: CGRect) -> CGPoint {
        let u = unit(in: min(rect.width, rect.height))
        return CGPoint(x: rect.midX + (x - 12) * u, y: rect.midY + (y - 12) * u)
    }

    struct Arrow: Shape {
        func path(in rect: CGRect) -> Path {
            var path = Path()
            path.move(to: point(3, 12, in: rect))
            path.addLine(to: point(9.4, 12, in: rect))
            path.move(to: point(6.9, 9.3, in: rect))
            path.addLine(to: point(9.6, 12, in: rect))
            path.addLine(to: point(6.9, 14.7, in: rect))
            return path
        }
    }

    struct Crossbar: Shape {
        /// The bar's own centre, so it shrinks in place.
        static let anchor = UnitPoint(x: 17 / 24, y: 14 / 24)

        func path(in rect: CGRect) -> Path {
            var path = Path()
            path.move(to: point(14.3, 14, in: rect))
            path.addLine(to: point(19.7, 14, in: rect))
            return path
        }
    }

    /// The A's legs at `progress` 0, the chevron's arms at 1: the apex slides left and down while
    /// each leg turns outward from 20.6° to 45° off vertical and shortens from 12.8 to 8.5 units.
    struct Legs: Shape {
        var progress: CGFloat

        var animatableData: CGFloat {
            get { progress }
            set { progress = newValue }
        }

        func path(in rect: CGRect) -> Path {
            let t = progress
            let apexX = 17 + (12 - 17) * t
            let apexY = 6 + (9 - 6) * t
            let angle = (20.556 + (45 - 20.556) * t) * .pi / 180
            let length = 12.816 + (8.5 - 12.816) * t
            let dx = sin(angle) * length
            let dy = cos(angle) * length
            var path = Path()
            path.move(to: point(apexX - dx, apexY + dy, in: rect))
            path.addLine(to: point(apexX, apexY, in: rect))
            path.addLine(to: point(apexX + dx, apexY + dy, in: rect))
            return path
        }
    }
}

// MARK: - Progress ring

/// A comet that laps the button's outline while a transcript is being made.
///
/// Human: Same timing as the web ring — over 1.3s it stretches to a third of the lap, then its
/// tail catches up. Under Reduce Motion it holds still as a third of the outline.
private struct TranscriptLapRing: View {
    let color: Color
    let isAnimated: Bool

    private let period: TimeInterval = 1.3

    private var outline: some Shape {
        RoundedRectangle(cornerRadius: 8.25, style: .continuous).inset(by: 0.75)
    }

    var body: some View {
        if isAnimated {
            TimelineView(.animation) { context in
                let elapsed = context.date.timeIntervalSinceReferenceDate
                comet(phase: elapsed.truncatingRemainder(dividingBy: period) / period)
            }
        } else {
            arc(from: 0, to: 0.33)
        }
    }

    private func comet(phase: Double) -> some View {
        let start: Double
        let length: Double
        if phase < 0.5 {
            let u = phase / 0.5
            start = 0.25 * u
            length = 0.005 + 0.365 * u
        } else {
            let u = (phase - 0.5) / 0.5
            start = 0.25 + 0.75 * u
            length = 0.37 - 0.365 * u
        }
        let end = start + length
        // `trim` can't wrap past the path's end, so a lap crossing it is drawn in two pieces.
        return ZStack {
            arc(from: start, to: min(end, 1))
            if end > 1 {
                arc(from: 0, to: end - 1)
            }
        }
    }

    private func arc(from: Double, to: Double) -> some View {
        outline
            .trim(from: from, to: to)
            .stroke(color, style: StrokeStyle(lineWidth: 1.5, lineCap: .round))
    }
}
