import SwiftUI
import UIKit

/// Motion tokens — the timing half of the design system.
///
/// Feature views must never invent their own spring numbers; pick the token that
/// matches the *intent* so every surface in the app decelerates identically.
///
/// | Token | Feel | Use for |
/// | --- | --- | --- |
/// | `press` / `release` | instant down, soft back | button + row press states |
/// | `snappy` | quick, no overshoot | icon swaps, toggles, badges, receipts |
/// | `standard` | the default | list/layout changes, tab content |
/// | `gentle` | slow settle | large surfaces, sheets, hero chrome |
/// | `bouncy` | visible overshoot | "it happened" moments (send, new message) |
/// | `fade` | linear-ish opacity | scrims, cross-dissolves |
/// | `menuLift` / `menuDrop` | Telegram's context menu | a message lifting into its long-press menu and back |
enum Motion {
    /// Press-down: near-instant so the UI answers the finger before it lifts.
    static let press = Animation.easeOut(duration: 0.09)
    /// Press-release: springs back with a touch of life.
    static let release = Animation.spring(response: 0.34, dampingFraction: 0.62)

    static let snappy = Animation.spring(response: 0.26, dampingFraction: 0.86)
    static let standard = Animation.spring(response: 0.38, dampingFraction: 0.9)
    static let gentle = Animation.spring(response: 0.5, dampingFraction: 0.92)
    static let bouncy = Animation.spring(response: 0.32, dampingFraction: 0.66)
    /// A reaction flying from the bar (or a double tap) into its chip.
    static let reactionFlight = Animation.spring(response: 0.46, dampingFraction: 0.82)

    static let fade = Animation.easeOut(duration: 0.18)
    static let scrim = Animation.easeOut(duration: 0.22)

    /// A message lifting out of the thread into its long-press menu: Telegram's context-menu
    /// spring (mass 5, stiffness 900, damping 104 — a hair of overshoot, settled in ~0.4 s).
    static let menuLift = Animation.interpolatingSpring(mass: 5, stiffness: 900, damping: 104)
    /// Putting it back: Telegram's 0.2 s ease-in-out.
    static let menuDropDuration: Double = 0.2
    static let menuDrop = Animation.easeInOut(duration: menuDropDuration)

    /// Reduce Motion substitute — position/scale changes collapse into a plain cross-fade.
    static let reducedDuration: Double = 0.15
    static let reduced = Animation.easeOut(duration: reducedDuration)

    /// Picks `animation` normally, or a flat fade when the user asked for less motion.
    static func respecting(_ reduceMotion: Bool, _ animation: Animation) -> Animation {
        reduceMotion ? reduced : animation
    }

    // MARK: - Shared transitions

    /// New chat bubble: grows out of the corner it was "spoken" from.
    static func bubbleIn(isMine: Bool) -> AnyTransition {
        .asymmetric(
            insertion: .scale(scale: 0.82, anchor: isMine ? .bottomTrailing : .bottomLeading)
                .combined(with: .opacity),
            removal: .scale(scale: 0.9).combined(with: .opacity)
        )
    }

    /// Control that swaps in place (send ⇄ mic, clear button, receipts).
    static let iconSwap: AnyTransition = .scale(scale: 0.45).combined(with: .opacity)

    /// Transient surface rising from the bottom edge (toasts).
    static let riseFromBottom: AnyTransition = .move(edge: .bottom)
        .combined(with: .scale(scale: 0.9, anchor: .bottom))
        .combined(with: .opacity)
}

// MARK: - Press feedback

/// Scales + haptically confirms a tap the moment the finger lands.
///
/// Human: The haptic fires on press-*down*, not on the action, so the control feels
/// instantaneous even when the work behind it is async.
/// Agent: CALLS Haptics.impact on press-down only; no state outside the button.
struct PressableButtonStyle: ButtonStyle {
    var scale: CGFloat = 0.96
    /// How much the label dims while held (0 = no dimming).
    var dimming: Double = 0.08
    /// Press-down haptic; `nil` when the caller already fires its own.
    var haptic: UIImpactFeedbackGenerator.FeedbackStyle? = .light

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        let pressed = configuration.isPressed && isEnabled
        return configuration.label
            .scaleEffect(reduceMotion ? 1 : (pressed ? scale : 1))
            .opacity(pressed ? 1 - dimming : 1)
            .animation(pressed ? Motion.press : Motion.release, value: pressed)
            .onChange(of: pressed) { _, isDown in
                guard isDown, let haptic else { return }
                Haptics.impact(haptic)
            }
    }
}

/// Full-width list row press state — UIKit-style highlight instead of a scale
/// (scaling a row that spans the screen reads as a glitch, not as feedback).
struct HighlightRowButtonStyle: ButtonStyle {
    var fill: Color = Theme.backgroundGrouped
    var haptic: UIImpactFeedbackGenerator.FeedbackStyle? = .light

    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        let pressed = configuration.isPressed && isEnabled
        return configuration.label
            .background(pressed ? fill : Color.clear)
            .contentShape(Rectangle())
            // Highlight lands instantly and fades out — matches UITableViewCell.
            .animation(pressed ? nil : Motion.fade, value: pressed)
            .onChange(of: pressed) { _, isDown in
                guard isDown, let haptic else { return }
                Haptics.impact(haptic)
            }
    }
}

extension View {
    /// Standard tap feedback for compact controls (icons, capsules, chips).
    func pressable(
        scale: CGFloat = 0.96,
        dimming: Double = 0.08,
        haptic: UIImpactFeedbackGenerator.FeedbackStyle? = .light
    ) -> some View {
        buttonStyle(PressableButtonStyle(scale: scale, dimming: dimming, haptic: haptic))
    }
}

// MARK: - Staggered list entrance

/// Timestamp of the host list's last "fresh content" moment.
private struct ListEntranceStartKey: EnvironmentKey {
    static let defaultValue: Date? = nil
}

extension EnvironmentValues {
    /// Set by `listEntranceHost`; read by `entranceRow` to decide whether to animate.
    var listEntranceStart: Date? {
        get { self[ListEntranceStartKey.self] }
        set { self[ListEntranceStartKey.self] = newValue }
    }
}

/// Marks a scroll container as the owner of a staggered row entrance.
///
/// Human: Rows only animate in during a short window after the list appears (or after
/// `resetOn` flips, i.e. when the first page of data lands). Outside that window rows
/// snap in, so scrolling a long list never drags a fade behind the finger.
/// Agent: WRITES environment(listEntranceStart); RETURNS view unchanged visually.
private struct ListEntranceHost<Token: Equatable>: ViewModifier {
    let token: Token

    @State private var start = Date()

    func body(content: Content) -> some View {
        content
            .environment(\.listEntranceStart, start)
            .onChange(of: token) { _, _ in
                start = Date()
            }
    }
}

/// Fades + lifts a row into place, staggered by its position in the list.
private struct EntranceRow: ViewModifier {
    let index: Int

    /// Rows past this index share the last delay — a long list must not queue seconds of motion.
    private let staggerLimit = 8
    private let step = 0.03
    /// How long after the list's start rows are still allowed to animate in.
    private let window: TimeInterval = 0.6

    @Environment(\.listEntranceStart) private var start
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var shown = false

    func body(content: Content) -> some View {
        content
            .opacity(shown ? 1 : 0)
            .offset(y: shown || reduceMotion ? 0 : 10)
            .onAppear(perform: reveal)
    }

    private func reveal() {
        guard !shown else { return }
        guard let start, Date().timeIntervalSince(start) < window else {
            // Scrolled into view later (or no host) — appear immediately.
            shown = true
            return
        }
        let delay = Double(min(index, staggerLimit)) * step
        withAnimation(Motion.respecting(reduceMotion, Motion.standard).delay(delay)) {
            shown = true
        }
    }
}

extension View {
    /// Hosts a staggered entrance; `resetOn` re-arms it (e.g. when loaded data replaces an empty list).
    func listEntranceHost(resetOn token: some Equatable) -> some View {
        modifier(ListEntranceHost(token: token))
    }

    /// Row-level entrance; pair with `listEntranceHost` on the enclosing scroll view.
    func entranceRow(index: Int) -> some View {
        modifier(EntranceRow(index: index))
    }
}

// MARK: - Shimmer

/// Sweeping highlight for skeleton content.
///
/// Human: One shared implementation so every placeholder in the app pulses in step.
/// Agent: Uses TimelineView so the sweep keeps time with the display link and pauses off-screen.
private struct Shimmer: ViewModifier {
    var active: Bool = true
    /// Off for placeholders that are dark in both appearances (a video plate, the forced-dark
    /// editors), which keep the full-strength sweep.
    var adaptsToAppearance: Bool = true

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme

    /// Peak of the sweep. `plusLighter` adds it to the fill: the light bars just clip to white,
    /// while the dark bars would flash near-white at full strength.
    private var peakOpacity: Double {
        adaptsToAppearance && colorScheme == .dark ? 0.12 : 0.65
    }

    private let period: TimeInterval = 1.4

    func body(content: Content) -> some View {
        if active, !reduceMotion {
            content.overlay {
                TimelineView(.animation) { context in
                    let t = context.date.timeIntervalSinceReferenceDate
                        .truncatingRemainder(dividingBy: period) / period
                    GeometryReader { geo in
                        LinearGradient(
                            stops: [
                                .init(color: .white.opacity(0), location: 0),
                                .init(color: .white.opacity(peakOpacity), location: 0.5),
                                .init(color: .white.opacity(0), location: 1),
                            ],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                        .frame(width: geo.size.width * 0.6)
                        // Sweep from fully off the leading edge to fully off the trailing edge.
                        .offset(x: -geo.size.width * 0.6 + (geo.size.width * 1.6) * t)
                    }
                }
                .blendMode(.plusLighter)
                .allowsHitTesting(false)
            }
            .mask(content)
        } else {
            content
        }
    }
}

extension View {
    /// Adds the shared skeleton shimmer sweep (no-op under Reduce Motion). Pass
    /// `adaptsToAppearance: false` on a surface that is dark in light mode too.
    func shimmering(_ active: Bool = true, adaptsToAppearance: Bool = true) -> some View {
        modifier(Shimmer(active: active, adaptsToAppearance: adaptsToAppearance))
    }
}
