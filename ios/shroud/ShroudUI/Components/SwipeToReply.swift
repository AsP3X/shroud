import SwiftUI
import UIKit

/// Geometry and thresholds of the swipe-to-reply gesture, matching Telegram iOS.
enum SwipeToReplyMetrics {
    /// How far an **incoming** bubble travels before the gesture arms.
    static let incomingThreshold: CGFloat = 45
    /// Outgoing bubbles start further from the right edge, so they ask for a longer pull.
    static let outgoingThreshold: CGFloat = 60
    /// Past the threshold the row keeps moving, but with progressively more resistance.
    static let bandRange: CGFloat = 100
    static let bandCoefficient: CGFloat = 0.4
    /// Hard stop, so a long drag can never push the bubble off screen.
    static let maxTravel: CGFloat = 180
    static let iconSide: CGFloat = 33
    /// Distance from the row's trailing edge to the icon's centre at rest (incoming rows).
    static let incomingIconInset: CGFloat = 8.5
    /// Outgoing rows keep the icon further out, where the bubble was before it moved.
    static let outgoingIconInset: CGFloat = 42.5

    static func threshold(isMine: Bool) -> CGFloat {
        isMine ? outgoingThreshold : incomingThreshold
    }

    static func iconInset(isMine: Bool) -> CGFloat {
        isMine ? outgoingIconInset : incomingIconInset
    }

    /// Rubber banding past the threshold — 1:1 until then, asymptotic after.
    static func banded(_ distance: CGFloat, threshold: CGFloat) -> CGFloat {
        guard distance > threshold else { return max(0, distance) }
        let beyond = distance - threshold
        let eased = (1 - (1 / ((beyond * bandCoefficient / bandRange) + 1))) * bandRange
        return min(maxTravel, threshold + eased)
    }
}

/// Pan that only takes over once the finger is clearly moving **left**.
///
/// Human: Without this test the recogniser would fight the thread's own scrolling and the
/// interactive back-swipe. Telegram makes the same call in `ChatSwipeToReplyRecognizer`: fail
/// as soon as the gesture looks vertical (or rightward), and only start tracking once the
/// horizontal component clearly dominates.
/// Agent: Never calls `super.touchesMoved` before validating, so `translation(in:)` cannot
/// report a drag the recogniser decided not to own.
final class SwipeToReplyGestureRecognizer: UIPanGestureRecognizer {
    private var validated = false
    private var origin: CGPoint = .zero

    override init(target: Any?, action: Selector?) {
        super.init(target: target, action: action)
        maximumNumberOfTouches = 1
    }

    override func reset() {
        super.reset()
        validated = false
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        super.touchesBegan(touches, with: event)
        guard let touch = touches.first else {
            state = .failed
            return
        }
        origin = touch.location(in: view)
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let touch = touches.first else { return }
        if !validated {
            let location = touch.location(in: view)
            let dx = location.x - origin.x
            let dy = location.y - origin.y
            // Rightward: that belongs to the navigation back-swipe, never to a reply.
            if dx > 2 {
                state = .failed
                return
            }
            if abs(dy) > 2, abs(dy) > abs(dx) * 2 {
                state = .failed
                return
            }
            guard abs(dx) > 2, abs(dy) * 2 < abs(dx) else { return }
            validated = true
        }
        super.touchesMoved(touches, with: event)
    }
}

/// Bridges the UIKit recogniser into SwiftUI's gesture system.
///
/// Agent: CALLS `onChanged` with the raw (unbanded) horizontal translation, and `onEnded` with
/// the final translation plus whether the gesture was cancelled.
private struct SwipeToReplyGesture: UIGestureRecognizerRepresentable {
    let isEnabled: Bool
    let onChanged: (CGFloat) -> Void
    let onEnded: (CGFloat, Bool) -> Void

    func makeUIGestureRecognizer(context: Context) -> SwipeToReplyGestureRecognizer {
        let recognizer = SwipeToReplyGestureRecognizer()
        recognizer.isEnabled = isEnabled
        return recognizer
    }

    /// Switching the recogniser off mid-drag makes UIKit cancel it, which settles the row.
    func updateUIGestureRecognizer(
        _ recognizer: SwipeToReplyGestureRecognizer,
        context: Context
    ) {
        recognizer.isEnabled = isEnabled
    }

    func handleUIGestureRecognizerAction(
        _ recognizer: SwipeToReplyGestureRecognizer,
        context: Context
    ) {
        let translation = recognizer.translation(in: recognizer.view).x
        switch recognizer.state {
        case .began, .changed:
            onChanged(translation)
        case .ended:
            onEnded(translation, false)
        case .cancelled, .failed:
            onEnded(translation, true)
        default:
            break
        }
    }
}

/// The circle that slides in from the trailing edge while a row is being swiped.
///
/// Human: Progress draws as a ring around the glyph; crossing the threshold fills the circle
/// and pops it, which is the visual half of the "you can let go now" haptic.
struct SwipeReplyIcon: View {
    /// 0…1 toward the threshold.
    var progress: CGFloat
    /// True once the threshold is crossed — releasing now starts the reply.
    var isArmed: Bool

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let clamped = min(1, max(0, progress))
        let reveal = min(1, clamped * 1.2)
        let restingScale = 0.65 + reveal * 0.35

        ZStack {
            Circle()
                .fill(isArmed ? Theme.accent : Theme.accentSoft)

            Circle()
                .trim(from: 0, to: clamped)
                .stroke(
                    Theme.accent.opacity(isArmed ? 0 : 0.85),
                    style: StrokeStyle(lineWidth: 2, lineCap: .round)
                )
                .rotationEffect(.degrees(-90))
                .padding(1)

            Image(systemName: "arrowshape.turn.up.left.fill")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(isArmed ? Color.white : Theme.accent)
                .opacity(reveal)
        }
        .frame(width: SwipeToReplyMetrics.iconSide, height: SwipeToReplyMetrics.iconSide)
        .scaleEffect(isArmed ? 1 : restingScale)
        .opacity(min(1, clamped * 2))
        .animation(Motion.respecting(reduceMotion, Motion.bouncy), value: isArmed)
        .accessibilityHidden(true)
    }
}

/// Telegram-style swipe-to-reply for one chat row.
///
/// Human: The row follows the finger 1:1 to the threshold and then rubber-bands, a ring fills
/// around the reply glyph, and a haptic marks the point where letting go actually replies.
/// Releasing springs the row home whether or not it armed.
/// Agent: READS nothing; CALLS `onReply` once, on release past the threshold. Owns its own
/// offset state so a drag never re-renders the thread.
struct SwipeToReplyModifier: ViewModifier {
    let isEnabled: Bool
    let isMine: Bool
    let onReply: () -> Void

    /// Banded translation actually applied to the row (≤ 0).
    @State private var offset: CGFloat = 0
    /// 0…1 toward the threshold; drives the icon.
    @State private var progress: CGFloat = 0
    @State private var isArmed = false
    /// True between the first tracked movement and the spring back — keeps the icon mounted.
    @State private var isSwiping = false

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var threshold: CGFloat { SwipeToReplyMetrics.threshold(isMine: isMine) }

    func body(content: Content) -> some View {
        content
            .offset(x: offset)
            .overlay(alignment: .trailing) {
                if isSwiping {
                    SwipeReplyIcon(progress: progress, isArmed: isArmed)
                        .offset(
                            x: SwipeToReplyMetrics.iconInset(isMine: isMine)
                                + SwipeToReplyMetrics.iconSide / 2
                                + offset
                        )
                        .transition(.opacity.combined(with: .scale(scale: 0.2)))
                        .allowsHitTesting(false)
                }
            }
            .gesture(
                SwipeToReplyGesture(
                    isEnabled: isEnabled,
                    onChanged: handleChange,
                    onEnded: handleEnd
                )
            )
            // A row that stops being swipeable mid-gesture (menu opened, message deleted)
            // must not stay parked off to the left.
            .onChange(of: isEnabled) { _, enabled in
                if !enabled, isSwiping { settle(triggerReply: false) }
            }
    }

    private func handleChange(_ translation: CGFloat) {
        guard isEnabled else { return }
        let distance = SwipeToReplyMetrics.banded(-translation, threshold: threshold)
        if !isSwiping {
            withAnimation(Motion.respecting(reduceMotion, Motion.snappy)) { isSwiping = true }
        }
        offset = -distance
        progress = min(1, distance / threshold)

        let armed = -translation >= threshold
        if armed != isArmed {
            isArmed = armed
            // Telegram fires once, on the way in — crossing back and forth stays quiet.
            if armed { Haptics.impact(.heavy) }
        }
    }

    private func handleEnd(_ translation: CGFloat, cancelled: Bool) {
        // The raw translation decides, not the banded one: past the threshold is past it.
        settle(triggerReply: !cancelled && -translation >= threshold)
    }

    private func settle(triggerReply: Bool) {
        let animation = Motion.respecting(reduceMotion, Motion.snappy)
        withAnimation(animation) {
            offset = 0
            progress = 0
            isArmed = false
            isSwiping = false
        }
        if triggerReply { onReply() }
    }
}

extension View {
    /// Adds swipe-left-to-reply to a chat row.
    /// - Parameters:
    ///   - isEnabled: False for bubbles that cannot be quoted yet (sending, failed, deleted).
    ///   - isMine: Outgoing rows use the longer threshold, as in Telegram.
    func swipeToReply(
        isEnabled: Bool = true,
        isMine: Bool,
        onReply: @escaping () -> Void
    ) -> some View {
        modifier(SwipeToReplyModifier(isEnabled: isEnabled, isMine: isMine, onReply: onReply))
    }
}

#Preview("Swipe icon states") {
    HStack(spacing: 20) {
        SwipeReplyIcon(progress: 0.3, isArmed: false)
        SwipeReplyIcon(progress: 0.7, isArmed: false)
        SwipeReplyIcon(progress: 1, isArmed: true)
    }
    .padding(30)
    .background(Theme.backgroundChat)
}
