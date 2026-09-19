import SwiftUI

/// The peer's message taking shape — maps to `Typing Bubble` in `Conversation — Typing`.
///
/// Human: Three ink dots swell and rise in turn inside an incoming bubble. They are drawn through
/// a blur and a steep alpha curve (a metaball), so a swelling dot pulls a liquid bridge from its
/// neighbour — the same ink as the voice recorder. The bubble grows out of its tail corner, the
/// way real messages arrive. The web client draws the same thing (`TypingBubble`).
/// Agent: Pure view; `TimelineView` drives it and stops when it leaves the screen.
struct TypingIndicatorBubble: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack {
            TypingInk(isAnimated: !reduceMotion)
                .frame(width: TypingInk.size.width, height: TypingInk.size.height)
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
        .transition(
            .scale(scale: 0.4, anchor: .bottomLeading).combined(with: .opacity)
        )
        .accessibilityElement()
        .accessibilityLabel("Typing")
    }
}

/// "typing" and three small dots riding the same wave as the bubble — replaces presence in the
/// thread header and the preview in the chat and contact lists.
struct TypingLabel: View {
    var font: Font = .system(size: 12)
    var color: Color = Theme.accent

    var body: some View {
        HStack(alignment: .center, spacing: 3) {
            Text("typing")
                .font(font)
            TypingDots()
                .offset(y: 1)
        }
        .foregroundStyle(color)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("typing")
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

#Preview {
    VStack(alignment: .leading, spacing: 16) {
        TypingIndicatorBubble()
        TypingLabel()
        TypingLabel(font: .system(size: 14))
    }
    .padding()
    .background(Theme.backgroundChat)
}
