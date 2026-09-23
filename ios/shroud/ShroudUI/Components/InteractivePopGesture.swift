import SwiftUI
import UIKit

extension View {
    /// Brings back the left-edge swipe that pops the current `NavigationStack` screen.
    ///
    /// Human: UIKit switches the back swipe off when a screen hides its back button, and every
    /// pushed screen here draws its own chrome instead. Since iOS 26 the swipe is driven by
    /// `interactiveContentPopGestureRecognizer` (the classic edge recogniser never starts on its
    /// own), so both get a delegate that only asks "is there something to pop, and is the screen
    /// willing to let go right now?". The content recogniser is kept to the leading edge: a
    /// rightward drag in the middle of a chat is not a request to leave it.
    /// Agent: Pass `enabled: false` while a gesture-heavy state is up (menus, recording) so the
    /// swipe cannot tear the screen away mid-interaction.
    func interactivePopGesture(enabled: Bool = true) -> some View {
        background(InteractivePopGestureInstaller(enabled: enabled).frame(width: 0, height: 0))
    }
}

private struct InteractivePopGestureInstaller: UIViewControllerRepresentable {
    let enabled: Bool

    func makeUIViewController(context: Context) -> InstallerController {
        InstallerController(enabled: enabled)
    }

    func updateUIViewController(_ controller: InstallerController, context: Context) {
        controller.enabled = enabled
    }

    final class InstallerController: UIViewController {
        var enabled: Bool {
            // Only while on screen: a screen pushed on top owns the swipe until this one is back.
            didSet { if viewIfLoaded?.window != nil { install() } }
        }

        init(enabled: Bool) {
            self.enabled = enabled
            super.init(nibName: nil, bundle: nil)
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

        override func loadView() {
            view = UIView()
            view.isUserInteractionEnabled = false
        }

        override func viewWillAppear(_ animated: Bool) {
            super.viewWillAppear(animated)
            install()
        }

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            install()
        }

        /// A screen that leaves while it holds the swipe off must not leave it off for the next one.
        override func viewDidDisappear(_ animated: Bool) {
            super.viewDidDisappear(animated)
            // A popped screen has already lost its `navigationController` by now.
            guard let lastNavigationController else { return }
            InteractivePopGestureDelegate.attached(to: lastNavigationController).isEnabled = true
        }

        private weak var lastNavigationController: UINavigationController?

        private func install() {
            guard let navigationController else { return }
            lastNavigationController = navigationController
            let delegate = InteractivePopGestureDelegate.attached(to: navigationController)
            delegate.isEnabled = enabled
            for recognizer in [
                navigationController.interactivePopGestureRecognizer,
                navigationController.interactiveContentPopGestureRecognizer,
            ].compactMap({ $0 }) {
                recognizer.delegate = delegate
                recognizer.isEnabled = true
            }
        }
    }
}

/// One per navigation controller, kept alive by the controller itself.
private final class InteractivePopGestureDelegate: NSObject, UIGestureRecognizerDelegate {
    private static var key: UInt8 = 0
    /// How far in from the leading edge a back swipe may start (the system edge region is ~20 pt).
    static let edgeWidth: CGFloat = 30

    weak var navigationController: UINavigationController?
    var isEnabled = true

    static func attached(to navigationController: UINavigationController) -> InteractivePopGestureDelegate {
        if let existing = objc_getAssociatedObject(navigationController, &key) as? InteractivePopGestureDelegate {
            return existing
        }
        let delegate = InteractivePopGestureDelegate()
        delegate.navigationController = navigationController
        objc_setAssociatedObject(navigationController, &key, delegate, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        return delegate
    }

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard isEnabled, let navigationController else { return false }
        // A second pop while the first is still animating leaves the stack out of sync with the path.
        guard navigationController.transitionCoordinator == nil else { return false }
        return navigationController.viewControllers.count > 1
    }

    /// The full-width content recogniser only takes touches that start on the leading edge.
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        guard let navigationController, let view = navigationController.view,
              gestureRecognizer === navigationController.interactiveContentPopGestureRecognizer
        else { return true }
        let x = touch.location(in: view).x
        return view.effectiveUserInterfaceLayoutDirection == .rightToLeft
            ? x >= view.bounds.width - Self.edgeWidth
            : x <= Self.edgeWidth
    }

    /// The thread's scroll view must not steal a swipe that started on the edge.
    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldBeRequiredToFailBy otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        otherGestureRecognizer is UIPanGestureRecognizer && otherGestureRecognizer.view is UIScrollView
    }
}
