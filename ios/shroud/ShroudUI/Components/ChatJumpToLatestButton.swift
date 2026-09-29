import SwiftUI

/// Telegram's jump-to-latest control: a glass circle over the composer's send / mic slot while
/// the reader is up in the history, with a count of the messages that came in under them.
///
/// Human: It shows only once the reader has scrolled away themselves; a chat following its
/// newest message never shows it. The badge counts what the other side sent since the reader
/// left the bottom and goes when they get back there.
/// Agent: Pure presentation. The caller places it (bottom-trailing, above the composer) and
/// decides `isVisible`; a tap calls `action`. The glass has its own container so it
/// dematerialises on the way out, and the badge sits outside that container, where the glass
/// can't draw over it.
struct ChatJumpToLatestButton: View {
    var isVisible: Bool
    /// Messages that arrived below the reader; 0 shows no badge.
    var count: Int
    let action: () -> Void

    /// The composer's circle, so the control reads as part of that cluster.
    static let size: CGFloat = ChatComposerView.controlSize
    /// The chat list's unread badge height (12 pt text, 3 pt above and below).
    private static let badgeHeight: CGFloat = 20

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GlassEffectContainer {
            if isVisible {
                Button(action: action) {
                    Image(systemName: "chevron.down")
                        .font(GlassBarMetrics.glyphFont)
                        .foregroundStyle(Theme.accent)
                        .frame(width: Self.size, height: Self.size)
                        // 44 pt hit target around the 40 pt glass, like the composer's circles.
                        .contentShape(Circle().inset(by: -2))
                }
                // Interactive glass swells under the finger; the style only adds the haptic tick.
                .pressable(scale: 1, dimming: 0)
                .glassEffect(.regular.interactive(), in: .circle)
                .accessibilityLabel("Jump to latest messages")
                .accessibilityValue(countLabel)
                .transition(reduceMotion ? .opacity : Motion.iconSwap)
            }
        }
        // Holds its place while hidden, so the badge has a fixed edge to sit on.
        .frame(width: Self.size, height: Self.size)
        .overlay(alignment: .top) {
            ZStack {
                if isVisible, count > 0 {
                    badge
                        .transition(reduceMotion ? .opacity : Motion.iconSwap)
                }
            }
            // Centred on the circle's top edge, like Telegram's. An alignment guide on the badge
            // is ignored inside the `if`, and an offset on the badge itself would move the
            // centre its pop scales from.
            .offset(y: -Self.badgeHeight / 2)
        }
        .allowsHitTesting(isVisible)
        .animation(Motion.respecting(reduceMotion, Motion.snappy), value: isVisible)
        .animation(Motion.respecting(reduceMotion, Motion.snappy), value: count)
    }

    /// The chat list's unread badge: accent capsule, white count that rolls as it changes.
    private var badge: some View {
        Text(count > 99 ? "99+" : "\(count)")
            .font(.system(size: 12, weight: .semibold))
            .foregroundStyle(Color.white)
            .contentTransition(.numericText(value: Double(count)))
            .monospacedDigit()
            .padding(.horizontal, 7)
            .frame(minWidth: Self.badgeHeight, minHeight: Self.badgeHeight)
            .background(Theme.accent, in: Capsule())
            .allowsHitTesting(false)
            // The button's value says it.
            .accessibilityHidden(true)
    }

    private var countLabel: String {
        switch count {
        case 0: ""
        case 1: "1 new message"
        default: "\(count) new messages"
        }
    }
}

#Preview {
    @Previewable @State var count = 3

    VStack(spacing: 32) {
        HStack(spacing: 24) {
            ChatJumpToLatestButton(isVisible: true, count: 0) {}
            ChatJumpToLatestButton(isVisible: true, count: count) { count = 0 }
            ChatJumpToLatestButton(isVisible: true, count: 128) {}
        }
        Button("Another message") { count += 1 }
    }
    .padding(40)
    .background(Theme.backgroundChat)
}
