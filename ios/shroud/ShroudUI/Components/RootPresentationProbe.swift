import SwiftUI
import UIKit

/// What is presented over the app's root view, read from the window's root view controller.
///
/// Human: SwiftUI can't tell a root view that a child opened a sheet, a full-screen cover, an
/// alert or Safari. The root's own `.alert` must wait for those: presenting it dismisses them
/// (a photo editor's cover lost its edits). `RootPresentationProbe` puts an invisible view in the
/// window so this can ask UIKit instead.
/// Agent: READS the root view controller's `presentedViewController`; `clearForBlockingScreen`
/// dismisses it and ends editing. Nothing else.
@MainActor
final class RootPresentation {
    fileprivate weak var anchor: UIView?

    /// The window's scene is in front and taking input.
    var isInForeground: Bool {
        anchor?.window?.windowScene?.activationState == .foregroundActive
    }

    /// A sheet, cover, alert or dialog is up over the root view (or one is still leaving).
    var isPresentingOverRoot: Bool {
        anchor?.window?.rootViewController?.presentedViewController != nil
    }

    /// For a screen that blocks the whole app: drops the keyboard and dismisses everything
    /// presented over the root, so nothing sits on top of it or takes typing behind it.
    func clearForBlockingScreen() {
        guard let window = anchor?.window else { return }
        window.endEditing(true)
        // Dismissing the root's presented controller takes the whole chain above it along.
        guard let root = window.rootViewController,
              let presented = root.presentedViewController,
              !presented.isBeingDismissed
        else { return }
        root.dismiss(animated: true)
    }
}

/// Places `presentation`'s anchor in the window. Invisible; takes no touches.
struct RootPresentationProbe: UIViewRepresentable {
    let presentation: RootPresentation

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.isUserInteractionEnabled = false
        presentation.anchor = view
        return view
    }

    func updateUIView(_ view: UIView, context: Context) {
        presentation.anchor = view
    }
}
