import SwiftUI

/// Live presence indicator — a solid dot that emits a slow halo while the peer is online.
///
/// Human: The halo is the only always-running animation in chat chrome; it is deliberately
/// slow and low-contrast so it reads as "connected", not as a distraction. While the peer types
/// or records a voice note, `TypingLabel` takes its place.
/// Agent: Pure view state (`phaseAnimator`); no controller reads. Halo is dropped under
/// Reduce Motion.
struct PresenceDot: View {
    var size: CGFloat = 7

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var color: Color {
        Theme.online
    }

    private var pulses: Bool {
        !reduceMotion
    }

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: size, height: size)
            .background {
                if pulses {
                    halo
                }
            }
            .accessibilityHidden(true)
    }

    private var halo: some View {
        // Two-phase loop: expand + fade out, then reset invisibly.
        Circle()
            .fill(color)
            .frame(width: size, height: size)
            .phaseAnimator([false, true]) { view, expanded in
                view
                    .scaleEffect(expanded ? 2.6 : 1)
                    .opacity(expanded ? 0 : 0.45)
            } animation: { expanded in
                expanded
                    ? .easeOut(duration: 1.6)
                    : .linear(duration: 0.01).delay(0.5)
            }
    }
}

#Preview {
    HStack(spacing: 20) {
        PresenceDot()
        PresenceDot(size: 12)
    }
    .padding(40)
}
