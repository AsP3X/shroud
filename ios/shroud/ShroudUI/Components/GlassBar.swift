import SwiftUI
import UIKit

/// Liquid Glass bar primitives — the top bars and floating controls of `iOS-App.pen`.
///
/// Human: On iOS 26 a bar is not an opaque strip. Content scrolls under the status bar and
/// the scroll edge effect fades it; only the *controls* carry glass — circles for glyphs,
/// capsules for words — and the title stays plain text. Every custom bar in the app (root
/// tabs, chat header, composer, onboarding) is built from these so they share one set of
/// metrics and one press feel with the system bars on the sheets.
/// Agent: Pure presentation. `GlassBarRow` lays out leading / centre / trailing inside one
/// `GlassEffectContainer`; `GlassBarButton` is one glass control. No state beyond press.
enum GlassBarMetrics {
    /// Control diameter — the system toolbar's, so custom bars sit flush with system ones.
    static let controlSize: CGFloat = 44
    /// Horizontal bar inset, matching the system navigation bar's item margin.
    static let horizontalInset: CGFloat = 16
    /// Gap between neighbouring controls. Two controls this close only blend into one
    /// capsule when they are joined with `glassEffectUnion` (see `GlassBarGroup`).
    static let spacing: CGFloat = 8
    /// Room under the control row before content starts.
    static let bottomPadding: CGFloat = 6
    /// Width a centred title keeps free on each side for a cluster of `controls` glass
    /// controls (fused or not) plus the gap to the title.
    static func sideReserve(controls: Int) -> CGFloat {
        controlSize * CGFloat(max(1, controls)) + spacing
    }

    static let glyphFont = Font.system(size: 17, weight: .semibold)
    static let labelFont = Font.system(size: 16, weight: .medium)
    static let titleFont = Font.system(size: 17, weight: .semibold)
}

// MARK: - Button

/// One glass control: a 44 pt circle around a glyph, or a capsule around a word (both are
/// glass capsules; a square frame makes the circle).
///
/// Human: Regular glass with the accent glyph is the default (Back, New chat…). `prominent`
/// tints the glass with the accent and turns the glyph white — for the one action a bar
/// wants pressed (Save, Send). Interactive glass already swells under the finger, so the
/// press style only adds the haptic tick.
/// Agent: CALLS Haptics.impact on press-down (via PressableButtonStyle); READS
/// `isEnabled` to dim. RETURNS a Button; `shape` picks the label metrics, the glass is always a capsule.
/// Outline of a bar control: a circle around a glyph or a capsule around a word. Both are
/// drawn as glass capsules (a square frame makes the circle); the shape only picks the
/// label's font, padding and hit area.
enum GlassBarShape {
    case circle
    case capsule
}

/// How loudly a bar control asks to be pressed.
enum GlassBarEmphasis {
    case regular
    case prominent
}

struct GlassBarButton<Label: View>: View {
    var shape: GlassBarShape = .circle
    var emphasis: GlassBarEmphasis = .regular
    var haptic: UIImpactFeedbackGenerator.FeedbackStyle? = .light
    let action: () -> Void
    @ViewBuilder let label: () -> Label

    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.glassBarUnion) private var union

    var body: some View {
        Button(action: action) {
            label()
                .font(shape == .circle ? GlassBarMetrics.glyphFont : GlassBarMetrics.labelFont)
                .padding(.horizontal, shape == .capsule ? 16 : 0)
                .foregroundStyle(emphasis == .prominent ? Color.white : Theme.accent)
                .frame(minWidth: GlassBarMetrics.controlSize, minHeight: GlassBarMetrics.controlSize)
                .contentShape(Capsule())
        }
        .buttonStyle(PressableButtonStyle(scale: 1, dimming: 0, haptic: haptic))
        .modifier(GlassShapeModifier(glass: glass, union: union))
        // Disabled controls fade rather than vanish, so the bar keeps its shape.
        .opacity(isEnabled ? 1 : 0.4)
        .animation(Motion.fade, value: isEnabled)
    }

    private var glass: Glass {
        switch emphasis {
        case .regular: .regular.interactive()
        case .prominent: .regular.tint(Theme.accent).interactive()
        }
    }
}

extension GlassBarButton where Label == Image {
    /// A glyph in a glass circle — the common case (Back, Call, New chat…).
    init(
        systemImage: String,
        emphasis: GlassBarEmphasis = .regular,
        haptic: UIImpactFeedbackGenerator.FeedbackStyle? = .light,
        action: @escaping () -> Void
    ) {
        self.shape = .circle
        self.emphasis = emphasis
        self.haptic = haptic
        self.action = action
        self.label = { Image(systemName: systemImage) }
    }
}

extension GlassBarButton where Label == Text {
    /// A word in a glass capsule (Edit, Save, A–Z…).
    init(
        _ title: String,
        emphasis: GlassBarEmphasis = .regular,
        haptic: UIImpactFeedbackGenerator.FeedbackStyle? = .light,
        action: @escaping () -> Void
    ) {
        self.shape = .capsule
        self.emphasis = emphasis
        self.haptic = haptic
        self.action = action
        self.label = { Text(title) }
    }
}

/// Applies the glass and, inside a `GlassBarGroup`, the union.
///
/// Human: Always a capsule — on a square glyph frame it *is* a circle, and a union of
/// capsules grows into one long capsule. An explicit `.circle` here would stay a circle when
/// fused, leaving the second glyph outside the glass.
/// Agent: `glassEffectUnion` only fuses views that carry a glass effect themselves, so the
/// union (from an enclosing `GlassBarGroup`) is applied here, right after the glass.
private struct GlassShapeModifier: ViewModifier {
    let glass: Glass
    let union: GlassBarUnion?

    func body(content: Content) -> some View {
        content
            .glassEffect(glass, in: .capsule)
            .modifier(GlassUnionModifier(union: union))
    }
}

private struct GlassUnionModifier: ViewModifier {
    let union: GlassBarUnion?

    func body(content: Content) -> some View {
        if let union {
            content.glassEffectUnion(id: union.id, namespace: union.namespace)
        } else {
            content
        }
    }
}

// MARK: - Group

/// The union a `GlassBarGroup` hands its controls through the environment.
struct GlassBarUnion {
    let id: String
    let namespace: Namespace.ID
}

private struct GlassBarUnionKey: EnvironmentKey {
    static let defaultValue: GlassBarUnion? = nil
}

extension EnvironmentValues {
    /// Set by `GlassBarGroup`; a `GlassBarButton` fuses its glass into the group when present.
    var glassBarUnion: GlassBarUnion? {
        get { self[GlassBarUnionKey.self] }
        set { self[GlassBarUnionKey.self] = newValue }
    }
}

/// Neighbouring controls fused into one capsule, as the system toolbar groups its items.
///
/// Human: Contacts' QR + Add, the chat's Video + Call: related actions read as one control
/// with two glyphs. Each stays its own button.
/// Agent: WRITES `glassBarUnion` for its children; each `GlassBarButton` inside applies the
/// union to its own glass (the modifier must sit on the glassed view, not on a container).
/// Needs an enclosing `GlassEffectContainer` (every `GlassBarRow` provides one).
struct GlassBarGroup<Content: View>: View {
    @ViewBuilder let content: () -> Content

    @Namespace private var namespace

    var body: some View {
        HStack(spacing: 0) {
            content()
        }
        .environment(\.glassBarUnion, GlassBarUnion(id: "group", namespace: namespace))
    }
}

// MARK: - Row

/// The control row of a bar: leading and trailing clusters, and either a centred title or
/// leading-aligned centre content (the chat header's avatar and name).
///
/// Human: The title is centred on the *screen*, not between the clusters, so it stays put
/// when a side gains or loses a button. Side clusters win the space fight: the centre is
/// inset by `sideReserve` on both sides (two fused controls plus the gap by default), so a
/// long title truncates before it can overlap them, on any phone width.
/// Agent: RETURNS a fixed-height row; the caller pins it with `glassTopBar`. No scroll
/// tracking: legibility over passing content comes from the scroll edge effect (plus
/// `glassTopBar(scrim:)` where it is not enough), not from a backdrop here.
struct GlassBarRow<Leading: View, Center: View, Trailing: View>: View {
    var centersTitle = true
    /// Width kept free of the centre on each side, so it never runs under a cluster.
    var sideReserve: CGFloat = GlassBarMetrics.sideReserve(controls: 2)
    @ViewBuilder var leading: () -> Leading
    @ViewBuilder var center: () -> Center
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        GlassEffectContainer(spacing: GlassBarMetrics.spacing) {
            if centersTitle {
                ZStack {
                    center()
                        .padding(.horizontal, sideReserve)
                    HStack(spacing: GlassBarMetrics.spacing) {
                        leading()
                        Spacer(minLength: 0)
                        trailing()
                    }
                }
            } else {
                HStack(spacing: GlassBarMetrics.spacing) {
                    leading()
                    center()
                        .frame(maxWidth: .infinity, alignment: .leading)
                    trailing()
                }
            }
        }
        .frame(minHeight: GlassBarMetrics.controlSize)
        .padding(.horizontal, GlassBarMetrics.horizontalInset)
        .padding(.bottom, GlassBarMetrics.bottomPadding)
    }
}

/// Plain centred bar title — the system's inline title, in the design system's face.
struct GlassBarTitle: View {
    let title: String

    var body: some View {
        Text(title)
            .font(GlassBarMetrics.titleFont)
            .foregroundStyle(Theme.textPrimary)
            .lineLimit(1)
            .minimumScaleFactor(0.8)
            .multilineTextAlignment(.center)
            .accessibilityAddTraits(.isHeader)
    }
}

// MARK: - Pinning

extension View {
    /// Pins a bar above scrolling content and lets the scroll edge effect fade what passes
    /// under it — the same treatment a system navigation bar gets.
    ///
    /// Human: Use on the scroll view (or its wrapper). The bar itself carries no backdrop,
    /// unless `scrim` gives it one: the colour of the content's background, laid over the
    /// edge effect's blur, for a bar whose plain text must read over busy content (the chat
    /// header over bubbles). See `TopBarScrim`.
    /// Agent: `safeAreaBar` insets the scroll content; `scrollEdgeEffectStyle` applies to
    /// every scroll view inside.
    func glassTopBar<Bar: View>(
        scrim: Color? = nil,
        @ViewBuilder _ bar: @escaping () -> Bar
    ) -> some View {
        safeAreaBar(edge: .top, spacing: 0) {
            bar()
                .background {
                    if let scrim {
                        TopBarScrim(color: scrim)
                    }
                }
        }
        .scrollEdgeEffectStyle(.soft, for: .top)
    }

    /// Bottom counterpart, for the composer.
    func glassBottomBar<Bar: View>(@ViewBuilder _ bar: @escaping () -> Bar) -> some View {
        safeAreaBar(edge: .bottom, spacing: 0) { bar() }
            .scrollEdgeEffectStyle(.soft, for: .bottom)
    }
}

// MARK: - Scrim

/// The backdrop `glassTopBar(scrim:)` puts behind a bar.
///
/// Human: The soft edge effect blurs what passes under a bar but fades it across the bar's
/// own height, so a subtitle near the bar's bottom sat on barely dimmed bubbles. The scrim
/// holds `color` at `peak` from the screen's top edge through the whole bar and eases out
/// over `tail` below it, with no visible seam. Drawn in the content's own background colour,
/// it vanishes wherever nothing scrolls under the bar. Android draws the same profile
/// (`ChatHeaderBackdrop`).
/// Agent: A background of the bar row; reaches into the top safe area and `tail` past the
/// row's bottom. Draws only; never takes touches or reaches VoiceOver.
struct TopBarScrim: View {
    let color: Color

    /// Opacity through the bar: enough for 12 pt secondary text over any bubble, while the
    /// blurred thread still shows through.
    static let peak = 0.8
    /// How far below the bar the scrim eases out.
    static let tail: CGFloat = 28
    /// Stops along the eased tail; a linear ramp would show a band where it meets the hold.
    private static let falloffSteps = 8

    var body: some View {
        GeometryReader { proxy in
            let height = proxy.size.height + Self.tail
            LinearGradient(
                stops: Self.stops(color: color, hold: proxy.size.height / max(height, 1)),
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(width: proxy.size.width, height: height)
        }
        .ignoresSafeArea(edges: .top)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    /// `color` at `peak` down to `hold` (the bar's bottom, as a fraction of the scrim's
    /// height), then a smoothstep to clear at the end.
    static func stops(color: Color, hold: CGFloat) -> [Gradient.Stop] {
        let start = min(max(hold, 0), 1)
        let tail = (1 ... falloffSteps).map { step in
            let t = CGFloat(step) / CGFloat(falloffSteps)
            let eased = t * t * (3 - 2 * t)
            return Gradient.Stop(
                color: color.opacity(peak * Double(1 - eased)),
                location: start + (1 - start) * t
            )
        }
        return [
            Gradient.Stop(color: color.opacity(peak), location: 0),
            Gradient.Stop(color: color.opacity(peak), location: start),
        ] + tail
    }
}

// MARK: - Preview

#Preview("Bars over content") {
    ScrollView {
        LazyVStack(spacing: 0) {
            ForEach(0 ..< 30, id: \.self) { index in
                ChatRowView(
                    title: "Contact \(index)",
                    subtitle: "Scrolls under the bar and fades out",
                    time: "9:41",
                    avatarGradient: AvatarView.gradient(for: "c\(index)")
                )
            }
        }
    }
    .background(Theme.background)
    .glassTopBar {
        GlassBarRow {
            GlassBarButton("Edit") {}
        } center: {
            GlassBarTitle(title: "Chats")
        } trailing: {
            GlassBarGroup {
                GlassBarButton(systemImage: "qrcode") {}
                GlassBarButton(systemImage: "person.badge.plus") {}
            }
            GlassBarButton(systemImage: "square.and.pencil", emphasis: .prominent) {}
        }
    }
    .glassBottomBar {
        GlassBarRow(centersTitle: false) {
            GlassBarButton(systemImage: "plus") {}
        } center: {
            Text("Message")
                .font(.system(size: 15))
                .foregroundStyle(Theme.textSecondary)
                .padding(.horizontal, 14)
                .frame(maxWidth: .infinity, minHeight: 40, alignment: .leading)
                .glassEffect(.regular, in: .capsule)
        } trailing: {
            GlassBarButton(systemImage: "mic.fill") {}
        }
    }
}
