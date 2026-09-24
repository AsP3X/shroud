import SwiftUI

/// An emoji on its way from where it was picked (the menu's bar, a double tap) to the chip it
/// becomes (Telegram).
///
/// Human: The chip is laid out first with its emoji hidden (`reactionFlightTarget`), reports
/// where that emoji sits (`ReactionFlightFrameKey`), and the flying copy arcs there and hands
/// over without a seam: same place, same size, then the chip's own emoji shows.
/// Agent: The host owns the state: sets `to` from the preference, animates `landed`, clears the
/// flight on completion (or after a timeout when the chip never appears, e.g. scrolled away).
struct ReactionFlight: Equatable, Identifiable {
    let id = UUID()
    let emoji: String
    let messageID: UUID
    /// Global frame the emoji leaves from.
    let from: CGRect
    /// Scale at the start, relative to `ReactionFlightLayer.fontSize` (a double tap starts big).
    var fromScale: CGFloat = 1
    /// Global frame of the chip's emoji, once it is laid out.
    var to: CGRect?
    var landed = false

    var target: ReactionFlightTarget { ReactionFlightTarget(messageID: messageID, emoji: emoji) }
}

/// Which chip a flight lands on. That chip hides its emoji until the flight is over.
struct ReactionFlightTarget: Equatable {
    let messageID: UUID
    let emoji: String
}

extension EnvironmentValues {
    @Entry var reactionFlightTarget: ReactionFlightTarget? = nil
}

/// The landing chip's emoji frame, in global coordinates.
struct ReactionFlightFrameKey: PreferenceKey {
    static let defaultValue: CGRect? = nil

    static func reduce(value: inout CGRect?, nextValue: () -> CGRect?) {
        value = value ?? nextValue()
    }
}

/// Draws the flying emoji above everything; never takes touches.
struct ReactionFlightLayer: View {
    let flight: ReactionFlight?

    /// Emoji size in flight at scale 1 — the bar's size, so a pick lifts off unchanged.
    static let fontSize: CGFloat = 26
    /// The chip's emoji size (see `ReactionChipView`), where the flight ends.
    static let landedScale: CGFloat = ReactionChipView.emojiFontSize / fontSize

    var body: some View {
        GeometryReader { geo in
            if let flight {
                let origin = geo.frame(in: .global).origin
                Text(flight.emoji)
                    .font(.system(size: Self.fontSize))
                    .fixedSize()
                    .modifier(
                        ReactionFlightPath(
                            progress: flight.landed ? 1 : 0,
                            from: CGPoint(x: flight.from.midX - origin.x, y: flight.from.midY - origin.y),
                            to: flight.to.map { CGPoint(x: $0.midX - origin.x, y: $0.midY - origin.y) },
                            fromScale: flight.fromScale,
                            toScale: Self.landedScale
                        )
                    )
                    .transition(.opacity)
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// Moves along a gentle arc (a quadratic curve bowed upward) while shrinking to the chip's size.
private struct ReactionFlightPath: ViewModifier, Animatable {
    var progress: CGFloat
    let from: CGPoint
    let to: CGPoint?
    let fromScale: CGFloat
    let toScale: CGFloat

    var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }

    func body(content: Content) -> some View {
        let end = to ?? from
        let t = min(max(progress, 0), 1.15) // a spring may overshoot a little
        // The bow grows with the distance, so a short hop stays short.
        let lift = min(90, hypot(end.x - from.x, end.y - from.y) * 0.35)
        let control = CGPoint(x: (from.x + end.x) / 2, y: min(from.y, end.y) - lift)
        let u = 1 - t
        let point = CGPoint(
            x: u * u * from.x + 2 * u * t * control.x + t * t * end.x,
            y: u * u * from.y + 2 * u * t * control.y + t * t * end.y
        )
        let scale = fromScale + (toScale - fromScale) * min(t, 1)
        return content
            .scaleEffect(scale)
            .position(point)
    }
}
