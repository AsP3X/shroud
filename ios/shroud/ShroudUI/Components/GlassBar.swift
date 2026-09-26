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
    static let glyphFont = Font.system(size: 17, weight: .semibold)
    static let labelFont = Font.system(size: 16, weight: .medium)
    static let titleFont = Font.system(size: 17, weight: .semibold)
}

// MARK: - Button

/// One glass control: a 44 pt circle around a glyph, or a capsule around a word.
///
/// Human: Regular glass with the accent glyph is the default (Back, New chat…). `prominent`
/// tints the glass with the accent and turns the glyph white — for the one action a bar
/// wants pressed (Save, Send). Interactive glass already swells under the finger, so the
/// press style only adds the haptic tick.
/// Agent: CALLS Haptics.impact on press-down (via PressableButtonStyle); READS
/// `isEnabled` to dim. RETURNS a Button; the glass shape is picked by `shape`.
/// Glass outline of a bar control.
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

    var body: some View {
        Button(action: action) {
            label()
                .font(shape == .circle ? GlassBarMetrics.glyphFont : GlassBarMetrics.labelFont)
                .padding(.horizontal, shape == .capsule ? 16 : 0)
                .foregroundStyle(emphasis == .prominent ? Color.white : Theme.accent)
                .frame(minWidth: GlassBarMetrics.controlSize, minHeight: GlassBarMetrics.controlSize)
                .contentShape(shape == .circle ? AnyShape(Circle()) : AnyShape(Capsule()))
        }
        .buttonStyle(PressableButtonStyle(scale: 1, dimming: 0, haptic: haptic))
        .modifier(GlassShapeModifier(shape: shape, glass: glass))
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

/// Applies one glass shape; kept as a modifier so the two shapes share one code path.
private struct GlassShapeModifier: ViewModifier {
    let shape: GlassBarShape
    let glass: Glass

    func body(content: Content) -> some View {
        switch shape {
        case .circle:
            content.glassEffect(glass, in: .circle)
        case .capsule:
            content.glassEffect(glass, in: .capsule)
        }
    }
}

// MARK: - Group

/// Neighbouring controls fused into one capsule, as the system toolbar groups its items.
///
/// Human: Contacts' QR + Add, the chat's Video + Call: related actions read as one control
/// with two glyphs. Each stays its own button.
/// Agent: Wraps each child in `glassEffectUnion` under one id; needs an enclosing
/// `GlassEffectContainer` (every `GlassBarRow` provides one).
struct GlassBarGroup<Content: View>: View {
    @ViewBuilder let content: () -> Content

    @Namespace private var union

    var body: some View {
        HStack(spacing: 0) {
            content()
        }
        .glassEffectUnion(id: "group", namespace: union)
    }
}

// MARK: - Row

/// The control row of a bar: leading and trailing clusters, and either a centred title or
/// leading-aligned centre content (the chat header's avatar and name).
///
/// Human: The title is centred on the *screen*, not between the clusters, so it stays put
/// when a side gains or loses a button. Side clusters win the space fight — a long title
/// truncates before it can overlap them.
/// Agent: RETURNS a fixed-height row; the caller pins it with `glassTopBar`. No scroll
/// tracking: legibility over passing content comes from the scroll edge effect, not from a
/// backdrop here.
struct GlassBarRow<Leading: View, Center: View, Trailing: View>: View {
    var centersTitle = true
    @ViewBuilder var leading: () -> Leading
    @ViewBuilder var center: () -> Center
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        GlassEffectContainer(spacing: GlassBarMetrics.spacing) {
            if centersTitle {
                ZStack {
                    center()
                        // Roughly the width the system leaves a title beside two buttons.
                        .frame(maxWidth: 220)
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
    /// Human: Use on the scroll view (or its wrapper). The bar itself carries no backdrop.
    /// Agent: `safeAreaBar` insets the scroll content; `scrollEdgeEffectStyle` applies to
    /// every scroll view inside.
    func glassTopBar<Bar: View>(@ViewBuilder _ bar: @escaping () -> Bar) -> some View {
        safeAreaBar(edge: .top, spacing: 0) { bar() }
            .scrollEdgeEffectStyle(.soft, for: .top)
    }

    /// Bottom counterpart, for the composer.
    func glassBottomBar<Bar: View>(@ViewBuilder _ bar: @escaping () -> Bar) -> some View {
        safeAreaBar(edge: .bottom, spacing: 0) { bar() }
            .scrollEdgeEffectStyle(.soft, for: .bottom)
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
