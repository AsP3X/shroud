import SwiftUI
import UIKit

/// Reliable short long-press for chat rows.
///
/// SwiftUI’s `onLongPressGesture` inside a `ScrollView` is often delayed ~0.5–1s by
/// scroll touch arbitration and by child `onTapGesture`s. This installs a real
/// `UILongPressGestureRecognizer` with `delaysTouchesBegan = false` and disables
/// `delaysContentTouches` on the enclosing scroll view so `minimumPressDuration`
/// is actually honored.
///
/// Optional `onTap` is wired with `require(toFail: longPress)` so short taps still
/// open the image viewer without waiting on the long-press timeout.
///
/// Long-press reports the press target’s **global** frame so the context menu can
/// hero-animate from / back to the bubble’s list position.
struct MessageLongPressGesture: UIViewRepresentable {
    var minimumDuration: TimeInterval = 0.25
    var allowableMovement: CGFloat = 16
    var onTap: (() -> Void)?
    var onLongPress: (CGRect) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onTap: onTap, onLongPress: onLongPress)
    }

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .clear
        view.isUserInteractionEnabled = true

        let longPress = UILongPressGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleLongPress(_:))
        )
        longPress.minimumPressDuration = minimumDuration
        longPress.allowableMovement = allowableMovement
        longPress.cancelsTouchesInView = true
        longPress.delaysTouchesBegan = false
        longPress.delaysTouchesEnded = false
        view.addGestureRecognizer(longPress)

        let tap = UITapGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleTap(_:))
        )
        tap.cancelsTouchesInView = false
        tap.delaysTouchesBegan = false
        // Short tap only if long-press never began.
        tap.require(toFail: longPress)
        view.addGestureRecognizer(tap)

        context.coordinator.longPress = longPress
        context.coordinator.tap = tap
        context.coordinator.onTap = onTap
        context.coordinator.onLongPress = onLongPress
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.onTap = onTap
        context.coordinator.onLongPress = onLongPress
        context.coordinator.longPress?.minimumPressDuration = minimumDuration
        context.coordinator.longPress?.allowableMovement = allowableMovement
        // Tap is only useful when provided (e.g. open image).
        context.coordinator.tap?.isEnabled = onTap != nil

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

    final class Coordinator: NSObject {
        var onTap: (() -> Void)?
        var onLongPress: (CGRect) -> Void
        weak var longPress: UILongPressGestureRecognizer?
        weak var tap: UITapGestureRecognizer?

        init(onTap: (() -> Void)?, onLongPress: @escaping (CGRect) -> Void) {
            self.onTap = onTap
            self.onLongPress = onLongPress
        }

        @objc func handleLongPress(_ gesture: UILongPressGestureRecognizer) {
            // Fire at the moment duration is met — don’t wait for finger lift.
            guard gesture.state == .began, let view = gesture.view else { return }
            let globalFrame = view.convert(view.bounds, to: nil)
            onLongPress(globalFrame)
        }

        @objc func handleTap(_ gesture: UITapGestureRecognizer) {
            guard gesture.state == .ended else { return }
            onTap?()
        }
    }
}

extension View {
    /// ScrollView-safe context-menu long-press (optional tap for image open).
    /// `perform` receives the press target’s global frame for hero open/close.
    func messageContextLongPress(
        minimumDuration: TimeInterval = 0.25,
        onTap: (() -> Void)? = nil,
        perform: @escaping (_ globalFrame: CGRect) -> Void
    ) -> some View {
        overlay {
            MessageLongPressGesture(
                minimumDuration: minimumDuration,
                onTap: onTap,
                onLongPress: perform
            )
            // Cover the row so UIKit receives the same hit target as the bubble stack.
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(Rectangle())
        }
    }
}
