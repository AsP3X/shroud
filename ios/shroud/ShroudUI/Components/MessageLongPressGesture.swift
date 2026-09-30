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
    /// Quick reaction (Telegram's double tap), with where the finger was in global coordinates.
    /// Simultaneous like the tap, so only bubbles without controls of their own should set it.
    let onDoubleTap: ((CGPoint) -> Void)?
    let perform: (CGRect) -> Void

    /// Live global frame of the row — the menu hero flies from and back to it.
    @State private var rowFrame: CGRect = .zero
    /// Suppresses the trailing tap so a hold that opened the menu doesn't also fire `onTap`.
    /// Cleared on every new touch-down: a hold held past the tap's cut-off never produces that
    /// trailing tap, and the next real tap on the bubble must not be the one swallowed. Not view
    /// state: nothing draws from it, and it changes on every touch.
    @State private var press = PressMemory()

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
            // Only the tap reads the flag, so only rows with a tap listen for touch-downs.
            .gesture(TouchDownGesture(isEnabled: onTap != nil) { press.didLongPress = false })
            .simultaneousGesture(
                // No `onChanged` here: it made the press claim the touch at touch-down, so a drag
                // that started on a bubble never scrolled the thread.
                LongPressGesture(minimumDuration: minimumDuration)
                    .onEnded { _ in
                        press.didLongPress = true
                        perform(rowFrame)
                    }
            )
            .simultaneousGesture(
                TapGesture().onEnded {
                    // A hold that already opened the menu must not also open the image viewer.
                    guard !press.didLongPress else {
                        press.didLongPress = false
                        return
                    }
                    afterInnerControls {
                        // A control inside the bubble (the reply header, a reaction chip) may
                        // have handled it.
                        guard !MessageTapClaim.isClaimed() else { return }
                        onTap?()
                    }
                },
                isEnabled: onTap != nil
            )
            .simultaneousGesture(
                SpatialTapGesture(count: 2).onEnded { value in
                    let point = CGPoint(x: rowFrame.minX + value.location.x, y: rowFrame.minY + value.location.y)
                    afterInnerControls {
                        guard !MessageTapClaim.isClaimed() else { return }
                        onDoubleTap?(point)
                    }
                },
                isEnabled: onDoubleTap != nil
            )
            // VoiceOver's double-tap-and-hold is hard to find; the menu (reply, copy, delete,
            // every reaction) is a named action on the bubble too.
            .accessibilityAction(named: "Message options") { perform(rowFrame) }
    }
}

/// Whether the current press on a row opened its menu. A reference type so that writing it
/// redraws nothing.
@MainActor
private final class PressMemory {
    var didLongPress = false
}

/// Reports every touch that lands on a row, then steps aside.
///
/// Human: The row needs to know when a new press starts (see `PressMemory`), but anything
/// SwiftUI offers for that — an `onChanged` on the long press, a zero-distance drag — takes the
/// touch, and the thread no longer scrolls when the drag starts on a bubble. This recogniser
/// fails in `touchesBegan`: it hears the touch-down and competes with nothing.
/// Agent: CALLS `onTouchDown` on the main thread at each touch-down; never begins.
private final class TouchDownRecognizer: UIGestureRecognizer {
    var onTouchDown: () -> Void = {}

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        onTouchDown()
        state = .failed
    }
}

private struct TouchDownGesture: UIGestureRecognizerRepresentable {
    let isEnabled: Bool
    let onTouchDown: () -> Void

    func makeUIGestureRecognizer(context: Context) -> TouchDownRecognizer {
        let recognizer = TouchDownRecognizer()
        recognizer.cancelsTouchesInView = false
        recognizer.delaysTouchesEnded = false
        recognizer.isEnabled = isEnabled
        recognizer.onTouchDown = onTouchDown
        return recognizer
    }

    func updateUIGestureRecognizer(_ recognizer: TouchDownRecognizer, context: Context) {
        recognizer.isEnabled = isEnabled
        recognizer.onTouchDown = onTouchDown
    }
}

/// Runs `action` after the controls inside the bubble have had this touch.
///
/// Human: The row's tap ends before a button, or a tap gesture, inside the bubble gets the same
/// touch (measured in the harness for a Button, `onTapGesture` and a high-priority gesture
/// alike), so a claim made there always came too late and a tap on a photo's reaction chip also
/// opened the photo. One main-queue turn later the claim is in.
@MainActor
private func afterInnerControls(_ action: @escaping @MainActor () -> Void) {
    DispatchQueue.main.async { action() }
}

extension View {
    /// ScrollView-safe context-menu long-press (optional tap for image open).
    /// `perform` receives the row's **global** frame for the menu's hero open/close.
    func messageContextLongPress(
        minimumDuration: TimeInterval = 0.25,
        onTap: (() -> Void)? = nil,
        onDoubleTap: ((CGPoint) -> Void)? = nil,
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
