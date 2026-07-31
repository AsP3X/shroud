import SwiftUI

/// Three-dot typing bubble — maps to `Typing Bubble` in `Conversation — Typing`.
///
/// Human: Dots ride one continuous sine wave (offset per dot) instead of stepping between
/// discrete phases, so the bounce reads as smooth at any frame rate. The bubble itself
/// scales in from the incoming-tail corner, matching how real messages arrive.
/// Agent: Pure view; driven by TimelineView so it stops when the row scrolls off-screen.
struct TypingIndicatorBubble: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// One full bounce cycle per dot.
    private let period: Double = 1.0
    /// Phase offset between neighbouring dots (in cycles).
    private let dotStagger: Double = 0.16

    var body: some View {
        HStack {
            dots
                .padding(.horizontal, 14)
                .padding(.vertical, 14)
                .background(Theme.bubbleIncoming)
                .clipShape(
                    UnevenRoundedRectangle(
                        topLeadingRadius: 18,
                        bottomLeadingRadius: 4,
                        bottomTrailingRadius: 18,
                        topTrailingRadius: 18,
                        style: .continuous
                    )
                )
                .shadow(color: Color.black.opacity(0.04), radius: 4, y: 1)

            Spacer(minLength: 56)
        }
        .transition(
            .scale(scale: 0.7, anchor: .bottomLeading).combined(with: .opacity)
        )
        .accessibilityLabel("Typing")
    }

    @ViewBuilder
    private var dots: some View {
        if reduceMotion {
            // Still, evenly weighted dots — the label carries the meaning instead.
            dotRow(time: nil)
        } else {
            TimelineView(.animation) { context in
                dotRow(time: context.date.timeIntervalSinceReferenceDate / period)
            }
        }
    }

    /// `time` nil renders the resting state; otherwise each dot samples the shared wave.
    private func dotRow(time: Double?) -> some View {
        HStack(spacing: 5) {
            ForEach(0 ..< 3, id: \.self) { index in
                let w = time.map { wave(t: $0, index: index) } ?? 0.5
                Circle()
                    .fill(dotColor(emphasis: w))
                    .frame(width: 7, height: 7)
                    .offset(y: time == nil ? 0 : -3.5 * CGFloat(w))
            }
        }
    }

    /// Only the top half of the sine lifts a dot; the rest of the cycle it rests flat.
    private func wave(t: Double, index: Int) -> Double {
        let phase = (t - Double(index) * dotStagger).truncatingRemainder(dividingBy: 1)
        let normalized = phase < 0 ? phase + 1 : phase
        return max(0, sin(normalized * 2 * .pi))
    }

    /// Design: rests at #B4B4BA and darkens toward `text-secondary` at the top of the bounce.
    private func dotColor(emphasis: Double) -> Color {
        let rest = Color(red: 0.769, green: 0.769, blue: 0.792)
        return rest.mix(with: Theme.textSecondary, by: emphasis)
    }
}

#Preview {
    TypingIndicatorBubble()
        .padding()
        .background(Theme.backgroundChat)
}
