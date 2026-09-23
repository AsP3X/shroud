import SwiftUI
import UIKit

/// Makes the enclosing `UIScrollView` deliver touches immediately.
///
/// Human: This is the one piece of the old UIKit long-press that still earns its keep. Without
/// `delaysContentTouches = false`, UIScrollView holds touches for ~150ms deciding whether they
/// are a scroll, which is what made SwiftUI's `LongPressGesture` feel like it needed a full
/// second inside a chat thread.
/// Agent: WRITES delaysContentTouches/canCancelContentTouches on the nearest ancestor
/// UIScrollView. Never participates in hit-testing itself.
private struct ScrollTouchDelayDisabler: UIViewRepresentable {
    /// Returns nil from `hitTest` so it can never intercept a touch meant for the row.
    final class PassthroughView: UIView {
        override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? { nil }
    }

    func makeUIView(context: Context) -> UIView {
        let view = PassthroughView()
        view.backgroundColor = .clear
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        DispatchQueue.main.async {
            var node: UIView? = uiView
            while let current = node {
                if let scroll = current as? UIScrollView {
                    scroll.delaysContentTouches = false
                    scroll.canCancelContentTouches = true
                    break
                }
                node = current.superview
            }
        }
    }
}

/// Lets a control *inside* a bubble claim a tap the row-level gesture also sees.
///
/// Human: The row installs its tap as a `simultaneousGesture`, which is what lets a tap reach a
/// button inside the bubble. The flip side is that both handlers fire — so tapping the reply
/// header of a photo would jump to the quoted message *and* open the photo. The header claims
/// the tap; the row checks the claim before acting on it.
/// Agent: WRITES a timestamp only; no view state. Main-actor isolated, so no locking.
@MainActor
enum MessageTapClaim {
    private static var claimedAt: Date?
    /// How long a claim suppresses the row's own tap — one event loop's worth, generously.
    private static let window: TimeInterval = 0.4

    static func claim() {
        claimedAt = Date()
    }

    /// True when something inside the bubble just handled this tap.
    static func isClaimed() -> Bool {
        guard let claimedAt else { return false }
        return Date().timeIntervalSince(claimedAt) < window
    }
}

/// Chat-row context press: opens the message menu on a short hold, without stealing taps from
/// controls inside the bubble.
///
/// Human: This used to install a UIKit recognizer in an `.overlay` stretched across the row.
/// That view became the frontmost hit-test result for every touch, so **no control inside any
/// bubble was reachable** — the voice message play button, its speed chip and the image retry
/// button all silently did nothing. Moving it behind the row fixed the buttons but broke the
/// long-press, because opaque bubble content then swallowed the touch first.
///
/// `simultaneousGesture` is the composition that satisfies both: the long press recognises
/// alongside whatever the bubble's own controls are doing, so a hold opens the menu and a tap
/// still reaches the button under the finger.
/// Agent: READS the row's global frame via GeometryReader (used for the menu's hero animation);
/// CALLS perform(frame) once the hold threshold is met, and onTap for a short tap.
private struct MessageContextLongPress: ViewModifier {
    let minimumDuration: TimeInterval
    let onTap: (() -> Void)?
    /// Quick reaction (Telegram's double tap). Simultaneous like the tap, so only bubbles
    /// without controls of their own should set it.
    let onDoubleTap: (() -> Void)?
    let perform: (CGRect) -> Void

    /// Live global frame of the row — the menu hero flies from and back to it.
    @State private var rowFrame: CGRect = .zero
    /// Suppresses the trailing tap so a hold that opened the menu doesn't also fire `onTap`.
    @State private var didLongPress = false

    func body(content: Content) -> some View {
        content
            .background {
                GeometryReader { geo in
                    Color.clear
                        .onAppear { rowFrame = geo.frame(in: .global) }
                        .onChange(of: geo.frame(in: .global)) { _, new in rowFrame = new }
                }
                .allowsHitTesting(false)
            }
            .background { ScrollTouchDelayDisabler() }
            .simultaneousGesture(
                LongPressGesture(minimumDuration: minimumDuration)
                    .onEnded { _ in
                        didLongPress = true
                        perform(rowFrame)
                    }
            )
            .simultaneousGesture(
                TapGesture().onEnded {
                    // A hold that already opened the menu must not also open the image viewer.
                    guard !didLongPress else {
                        didLongPress = false
                        return
                    }
                    // A control inside the bubble (the reply header) may have handled it.
                    guard !MessageTapClaim.isClaimed() else { return }
                    onTap?()
                },
                isEnabled: onTap != nil
            )
            .simultaneousGesture(
                TapGesture(count: 2).onEnded {
                    guard !MessageTapClaim.isClaimed() else { return }
                    onDoubleTap?()
                },
                isEnabled: onDoubleTap != nil
            )
    }
}

extension View {
    /// ScrollView-safe context-menu long-press (optional tap for image open).
    /// `perform` receives the row's **global** frame for the menu's hero open/close.
    func messageContextLongPress(
        minimumDuration: TimeInterval = 0.25,
        onTap: (() -> Void)? = nil,
        onDoubleTap: (() -> Void)? = nil,
        perform: @escaping (_ globalFrame: CGRect) -> Void
    ) -> some View {
        modifier(
            MessageContextLongPress(
                minimumDuration: minimumDuration,
                onTap: onTap,
                onDoubleTap: onDoubleTap,
                perform: perform
            )
        )
    }
}
