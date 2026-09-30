import SwiftUI

/// Ephemeral composer activity from a peer, shown in lists, headers, and the thread bubble.
enum ChatPeerActivity: Equatable {
    case typing
    case recording

    /// The word beside the live glyph in headers and list rows.
    var label: String {
        switch self {
        case .typing: "typing"
        case .recording: "recording"
        }
    }

    /// What VoiceOver says instead: the glyph that makes "recording" mean a voice note is silent.
    var spokenLabel: String {
        switch self {
        case .typing: "typing"
        case .recording: "recording a voice message"
        }
    }

    var accessibilityBubble: String {
        switch self {
        case .typing: "Typing"
        case .recording: "Recording a voice message"
        }
    }
}

/// The peer's message taking shape — maps to `Typing Bubble` in `Conversation — Typing`.
///
/// Human: Three ink dots swell and rise in turn inside an incoming bubble. They are drawn through
/// a blur and a steep alpha curve (a metaball), so a swelling dot pulls a liquid bridge from its
/// neighbour — the same ink as the voice recorder. While the peer records a voice note the same
/// bubble shows the recorder's own look instead: its blinking red dot beside a live level meter.
/// The bubble grows out of its tail corner, the way real messages arrive; a switch between the
/// two only cross-fades what is inside it. The web client draws the same thing (`TypingBubble`).
/// Agent: Pure view; `TimelineView` drives it and stops when it leaves the screen.
struct TypingIndicatorBubble: View {
    var activity: ChatPeerActivity = .typing
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Typing ⇄ recording inside the bubble: the old glyph shrinks away as the new one grows in.
    private static let swap: AnyTransition = .scale(scale: 0.6).combined(with: .opacity)

    var body: some View {
        HStack {
            ZStack {
                switch activity {
                case .typing:
                    TypingInk(isAnimated: !reduceMotion)
                        .transition(reduceMotion ? .opacity : Self.swap)
                case .recording:
                    RecordingInk(isAnimated: !reduceMotion)
                        .transition(reduceMotion ? .opacity : Self.swap)
                }
            }
            .frame(width: TypingInk.size.width, height: TypingInk.size.height)
            .animation(Motion.respecting(reduceMotion, Motion.snappy), value: activity)
            .padding(.horizontal, 13)
            .padding(.top, 8)
            .padding(.bottom, 9)
            .background(Theme.bubbleIncoming)
            .clipShape(
                UnevenRoundedRectangle(
                    topLeadingRadius: 17.5,
                    bottomLeadingRadius: 5,
                    bottomTrailingRadius: 17.5,
                    topTrailingRadius: 17.5,
                    style: .continuous
                )
            )
            .shadow(color: Color.black.opacity(0.04), radius: 3, y: 1)

            Spacer(minLength: 56)
        }
        // Under Reduce Motion it fades in where it will sit instead.
        .transition(
            reduceMotion ? .opacity : .scale(scale: 0.4, anchor: .bottomLeading).combined(with: .opacity)
        )
        .accessibilityElement()
        .accessibilityLabel(activity.accessibilityBubble)
    }
}

/// "typing" with three small dots riding the same wave as the bubble, or "recording" with a small
/// level meter — replaces presence in the thread header and the preview in the chat and contact
/// lists. A change of activity cross-fades the word and the glyph in place.
struct TypingLabel: View {
    var activity: ChatPeerActivity = .typing
    var font: Font = .system(size: 12)
    var color: Color = Theme.accent

    var body: some View {
        HStack(alignment: .center, spacing: 3) {
            Text(activity.label)
                .font(font)
                .contentTransition(.opacity)
            ZStack(alignment: .leading) {
                switch activity {
                case .typing:
                    TypingDots()
                        .offset(y: 1)
                        .transition(.opacity)
                case .recording:
                    RecordingBars()
                        .transition(.opacity)
                }
            }
        }
        .foregroundStyle(color)
        .animation(Motion.fade, value: activity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(activity.spokenLabel)
    }
}

// MARK: - Wave

/// Timing shared by the ink and the small dots, in step with the web client (`typing-ink` and
/// `typing-dot` in index.css): a dot swells over the first quarter of a 1.35s cycle, settles by
/// 56% and rests; the next one follows 0.17s behind.
enum TypingWave {
    static let period = 1.35
    static let stagger = 0.17

    private static let curve = UnitCurve.bezier(
        startControlPoint: UnitPoint(x: 0.45, y: 0),
        endControlPoint: UnitPoint(x: 0.35, y: 1)
    )

    /// 0 at rest, 1 at the crest, for dot `index` at time `t` (seconds).
    static func crest(at t: Double, index: Int) -> Double {
        var phase = (t - Double(index) * stagger).truncatingRemainder(dividingBy: period) / period
        if phase < 0 { phase += 1 }
        if phase < 0.24 { return curve.value(at: phase / 0.24) }
        if phase < 0.56 { return 1 - curve.value(at: (phase - 0.24) / 0.32) }
        return 0
    }
}

// MARK: - Ink

/// The three ink dots, drawn in the web client's 40×20 SVG box so both platforms match.
private struct TypingInk: View {
    let isAnimated: Bool

    static let size = CGSize(width: 40, height: 20)
    private static let centers: [CGFloat] = [9, 20, 31]
    private static let baseline: CGFloat = 11
    private static let radius: CGFloat = 3.6

    var body: some View {
        if isAnimated {
            TimelineView(.animation) { context in
                ink(time: context.date.timeIntervalSinceReferenceDate)
            }
        } else {
            // Holds still under Reduce Motion: three plain dots; the label carries the meaning.
            ink(time: nil)
        }
    }

    private func ink(time: Double?) -> some View {
        // Same mix as the web's `color-mix(in srgb, accent 42%, bubble)`.
        let rest = Theme.accent.mix(with: Theme.bubbleIncoming, by: 0.58, in: .device)
        let dots = Self.centers.indices.map { index in
            dot(index: index, crest: time.map { TypingWave.crest(at: $0, index: index) })
        }
        return Canvas { context, _ in
            // The silhouette: blurred dots through an alpha threshold fuse wherever they come
            // close, so a swelling dot pulls a liquid bridge from its neighbour.
            context.drawLayer { silhouette in
                silhouette.addFilter(.alphaThreshold(min: 0.42, color: time == nil ? Theme.accent : rest))
                silhouette.addFilter(.blur(radius: 2))
                silhouette.drawLayer { layer in
                    for dot in dots {
                        layer.fill(Path(ellipseIn: dot.rect), with: .color(.black))
                    }
                }
            }
            // The ink: each dot darkens toward the accent as it crests. Drawn after the
            // threshold, since blurring colour through it leaves dark fringes.
            for dot in dots where dot.crest > 0 {
                context.fill(
                    Path(ellipseIn: dot.rect.insetBy(dx: -0.3, dy: -0.3)),
                    with: .color(Theme.accent.opacity(dot.crest))
                )
            }
        }
    }

    private func dot(index: Int, crest: Double?) -> (rect: CGRect, crest: Double) {
        let scale = crest.map { 0.72 + 0.54 * $0 } ?? 1
        let r = Self.radius * scale
        let x = Self.centers[index]
        let y = Self.baseline - 4 * (crest ?? 0)
        return (CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2), crest ?? 0)
    }
}

// MARK: - Small dots

/// Three small dots for `TypingLabel`, riding `TypingWave`.
private struct TypingDots: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private let dot: CGFloat = 3.5

    var body: some View {
        if reduceMotion {
            row(time: nil)
        } else {
            TimelineView(.animation) { context in
                row(time: context.date.timeIntervalSinceReferenceDate)
            }
        }
    }

    private func row(time: Double?) -> some View {
        HStack(spacing: 2) {
            ForEach(0 ..< 3, id: \.self) { index in
                let crest = time.map { TypingWave.crest(at: $0, index: index) } ?? 1
                Circle()
                    .frame(width: dot, height: dot)
                    .opacity(0.4 + 0.6 * crest)
                    .offset(y: time == nil ? 0 : -2.5 * crest)
            }
        }
    }
}

// MARK: - Recording

/// Timing of the recording meter, in step with the web client (`rec-bar-*` and `voice-rec-pulse`
/// in index.css). Each bar walks its own loop of heights, eased between stops, so the meter looks
/// like a voice rather than a sine; the red dot breathes like the recorder's.
enum RecordingWave {
    /// Per bar: loop length in seconds, and heights (fraction of the tallest) at 0/25/50/75/100%.
    static let bars: [(period: Double, stops: [Double])] = [
        (1.05, [0.35, 0.9, 0.5, 0.75, 0.35]),
        (0.9, [0.8, 0.4, 1.0, 0.55, 0.8]),
        (1.2, [0.5, 1.0, 0.65, 0.3, 0.5]),
        (0.95, [0.9, 0.55, 0.8, 0.45, 0.9]),
        (1.1, [0.4, 0.7, 0.35, 0.95, 0.4]),
    ]
    static let blinkPeriod = 1.4

    /// Height of bar `index` at time `t` (seconds); its first stop when `t` is nil (Reduce Motion).
    static func level(at t: Double?, bar index: Int) -> Double {
        let (period, stops) = bars[index % bars.count]
        guard let t else { return stops[0] }
        var phase = t.truncatingRemainder(dividingBy: period) / period
        if phase < 0 { phase += 1 }
        let segment = min(Int(phase * 4), 3)
        let eased = UnitCurve.easeInOut.value(at: phase * 4 - Double(segment))
        return stops[segment] + (stops[segment + 1] - stops[segment]) * eased
    }

    /// Red dot opacity: full, down to 30% half-way through the blink, and back.
    static func blink(at t: Double?) -> Double {
        guard let t else { return 1 }
        return 0.65 + 0.35 * cos(2 * .pi * t / blinkPeriod)
    }
}

/// The bubble's recording glyph, in the same 40×20 box as the ink: the voice recorder's red dot
/// beside five bars of a live level meter.
private struct RecordingInk: View {
    let isAnimated: Bool

    private static let dot: CGFloat = 6
    private static let bar: CGFloat = 3
    private static let gap: CGFloat = 2
    private static let tallest: CGFloat = 16

    var body: some View {
        if isAnimated {
            TimelineView(.animation) { context in
                meter(time: context.date.timeIntervalSinceReferenceDate)
            }
        } else {
            meter(time: nil)
        }
    }

    private func meter(time: Double?) -> some View {
        Canvas { context, size in
            let count = RecordingWave.bars.count
            let width = Self.dot + 5 + CGFloat(count) * Self.bar + CGFloat(count - 1) * Self.gap
            var x = (size.width - width) / 2
            let midY = size.height / 2
            context.fill(
                Path(ellipseIn: CGRect(x: x, y: midY - Self.dot / 2, width: Self.dot, height: Self.dot)),
                with: .color(Theme.danger.opacity(RecordingWave.blink(at: time)))
            )
            x += Self.dot + 5
            for index in 0 ..< count {
                let level = RecordingWave.level(at: time, bar: index)
                let height = Self.tallest * level
                let rect = CGRect(x: x, y: midY - height / 2, width: Self.bar, height: height)
                context.fill(Path(roundedRect: rect, cornerRadius: Self.bar / 2), with: .color(Theme.accent))
                x += Self.bar + Self.gap
            }
        }
    }
}

/// Three small meter bars for `TypingLabel` while the peer records.
private struct RecordingBars: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private let bar: CGFloat = 2
    private let tallest: CGFloat = 9

    var body: some View {
        if reduceMotion {
            row(time: nil)
        } else {
            TimelineView(.animation) { context in
                row(time: context.date.timeIntervalSinceReferenceDate)
            }
        }
    }

    private func row(time: Double?) -> some View {
        HStack(alignment: .center, spacing: 1.5) {
            ForEach(0 ..< 3, id: \.self) { index in
                Capsule()
                    .frame(width: bar, height: tallest * RecordingWave.level(at: time, bar: index))
            }
        }
        .frame(height: tallest)
    }
}

#Preview {
    VStack(alignment: .leading, spacing: 16) {
        TypingIndicatorBubble()
        TypingIndicatorBubble(activity: .recording)
        TypingLabel()
        TypingLabel(activity: .recording, font: .system(size: 14))
    }
    .padding()
    .background(Theme.backgroundChat)
}
