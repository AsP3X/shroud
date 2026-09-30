import SwiftUI
import UIKit

/// Shared rules for how a photo is presented full-screen, so the zoom view and the chrome agree.
enum MediaViewerLayout {
    /// How much overflow still counts as "this photo was made for a screen this shape".
    ///
    /// Human: A screenshot (≈1.00) and a 9:16 photo (≈1.22) open edge-to-edge the way they do in
    /// Photos — showing them fitted would waste the screen on slivers of black. A 2:3 (≈1.45) or
    /// 3:4 (≈1.63) photo is a different shape entirely and is shown whole.
    static let fillThreshold: CGFloat = 1.25

    /// The box an image of this aspect occupies when fitted into `container`.
    static func fittedSize(aspect: CGFloat, in container: CGSize) -> CGSize {
        guard aspect > 0, container.width > 0, container.height > 0 else { return container }
        let height = min(container.height, container.width / aspect)
        return CGSize(width: height * aspect, height: height)
    }

    /// Scale from the fitted box to one that covers the container (always ≥ 1).
    static func fillScale(aspect: CGFloat, in container: CGSize) -> CGFloat {
        let fitted = fittedSize(aspect: aspect, in: container)
        guard fitted.width > 0, fitted.height > 0 else { return 1 }
        return max(container.width / fitted.width, container.height / fitted.height)
    }

    /// The scale a photo opens at: edge-to-edge for screen-shaped verticals, fitted otherwise.
    static func presentationScale(aspect: CGFloat, in container: CGSize) -> CGFloat {
        guard aspect > 0, aspect < 1 else { return 1 } // landscape and square always fit
        let fill = fillScale(aspect: aspect, in: container)
        return fill <= fillThreshold ? fill : 1
    }

    /// True when the photo opens covering the whole container.
    static func opensFullBleed(aspect: CGFloat, in container: CGSize) -> Bool {
        presentationScale(aspect: aspect, in: container) > 1.001
    }
}

/// A pinch/pan/double-tap zoomable photo, backed by `UIScrollView`.
///
/// Human: Zoom is the one thing SwiftUI gestures can't fake convincingly — anchoring a pinch,
/// rubber-banding at the edges, and carrying pan momentum are all things `UIScrollView` already
/// does exactly the way iOS users expect. This wraps it and reports back only what the overlay
/// chrome needs to react to.
///
/// Gesture split:
/// - horizontal drag at 1× does nothing here, so the parent pager gets it
/// - vertical drag at 1× drives interactive dismiss (reported, not applied)
/// - once zoomed, every drag pans the photo and dismiss is off
struct ZoomableImageView: UIViewRepresentable {
    let image: UIImage
    /// False for off-screen pages — they reset to 1× so returning to them starts fresh.
    var isActive: Bool = true
    var onSingleTap: () -> Void = {}
    var onZoomChange: (Bool) -> Void = { _ in }
    var onDismissDragChange: (CGSize) -> Void = { _ in }
    /// `(translation, velocity)` at the end of a dismiss drag.
    var onDismissDragEnd: (CGSize, CGSize) -> Void = { _, _ in }

    func makeUIView(context: Context) -> ZoomImageScrollView {
        let view = ZoomImageScrollView()
        view.delegate = context.coordinator
        view.backgroundColor = .clear

        let doubleTap = UITapGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleDoubleTap(_:))
        )
        doubleTap.numberOfTapsRequired = 2
        view.addGestureRecognizer(doubleTap)

        let singleTap = UITapGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleSingleTap(_:))
        )
        singleTap.numberOfTapsRequired = 1
        singleTap.require(toFail: doubleTap)
        view.addGestureRecognizer(singleTap)

        let dismissPan = UIPanGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleDismissPan(_:))
        )
        dismissPan.delegate = context.coordinator
        view.addGestureRecognizer(dismissPan)

        view.setImage(image)
        return view
    }

    func updateUIView(_ view: ZoomImageScrollView, context: Context) {
        context.coordinator.parent = self

        if view.imageView.image !== image {
            view.setImage(image)
        }
        // Swiping a page away throws its zoom away too, so it re-enters as first presented.
        if !isActive, abs(view.zoomScale - view.baseScale) > 0.01 {
            view.setZoomScale(view.baseScale, animated: false)
            view.syncScrollEnabled()
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(parent: self)
    }

    final class Coordinator: NSObject, UIScrollViewDelegate, UIGestureRecognizerDelegate {
        var parent: ZoomableImageView
        private var wasZoomed = false

        init(parent: ZoomableImageView) {
            self.parent = parent
        }

        func viewForZooming(in scrollView: UIScrollView) -> UIView? {
            (scrollView as? ZoomImageScrollView)?.imageView
        }

        func scrollViewDidZoom(_ scrollView: UIScrollView) {
            guard let view = scrollView as? ZoomImageScrollView else { return }
            view.centerContent()
            view.syncScrollEnabled()
            reportZoom(view)
        }

        func scrollViewDidEndZooming(_ scrollView: UIScrollView, with view: UIView?, atScale scale: CGFloat) {
            guard let zoomView = scrollView as? ZoomImageScrollView else { return }
            zoomView.syncScrollEnabled()
            reportZoom(zoomView)
        }

        private func reportZoom(_ scrollView: ZoomImageScrollView) {
            let zoomed = scrollView.isZoomedIn
            guard zoomed != wasZoomed else { return }
            wasZoomed = zoomed
            parent.onZoomChange(zoomed)
        }

        // MARK: - Taps

        @objc func handleSingleTap(_ gesture: UITapGestureRecognizer) {
            parent.onSingleTap()
        }

        @objc func handleDoubleTap(_ gesture: UITapGestureRecognizer) {
            guard let scrollView = gesture.view as? ZoomImageScrollView else { return }

            // Any magnification (or a pinch-out below the opening scale) snaps back to how the
            // photo was presented, so double tap is always a reliable "put it back".
            if scrollView.isZoomedIn || scrollView.zoomScale < scrollView.baseScale - 0.01 {
                scrollView.setZoomScale(scrollView.baseScale, animated: true)
                return
            }

            // Zoom toward the tapped point rather than the centre, like Photos.
            let target = scrollView.doubleTapZoomScale
            let point = gesture.location(in: scrollView.imageView)
            let size = CGSize(
                width: scrollView.bounds.width / target,
                height: scrollView.bounds.height / target
            )
            let rect = CGRect(
                x: point.x - size.width / 2,
                y: point.y - size.height / 2,
                width: size.width,
                height: size.height
            )
            scrollView.zoom(to: rect, animated: true)
        }

        // MARK: - Dismiss drag

        @objc func handleDismissPan(_ gesture: UIPanGestureRecognizer) {
            guard let scrollView = gesture.view as? UIScrollView else { return }
            let translation = gesture.translation(in: scrollView)
            let size = CGSize(width: translation.x, height: translation.y)

            switch gesture.state {
            case .changed:
                parent.onDismissDragChange(size)
            case .ended, .cancelled, .failed:
                let velocity = gesture.velocity(in: scrollView)
                parent.onDismissDragEnd(size, CGSize(width: velocity.x, height: velocity.y))
            default:
                break
            }
        }

        /// Dismiss only from the scale the photo opened at, and only for a drag that is clearly
        /// vertical — anything else belongs to the pager or to panning a zoomed photo.
        func gestureRecognizerShouldBegin(_ gesture: UIGestureRecognizer) -> Bool {
            guard let pan = gesture as? UIPanGestureRecognizer,
                  let scrollView = pan.view as? ZoomImageScrollView
            else { return true }

            guard !scrollView.isZoomedIn else { return false }
            let velocity = pan.velocity(in: scrollView)
            return abs(velocity.y) > abs(velocity.x)
        }

        func gestureRecognizer(
            _ gesture: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer
        ) -> Bool {
            true
        }
    }
}

/// `UIScrollView` that keeps a photo centred at every zoom level and opens it at the right scale.
final class ZoomImageScrollView: UIScrollView {
    let imageView = UIImageView()

    /// Size of the image at fit scale — the basis for every other scale here.
    private var fittedSize: CGSize = .zero
    private var lastLaidOutBounds: CGSize = .zero

    /// The scale the photo opens at: > 1 when a screen-shaped vertical goes edge-to-edge.
    /// Panning and "is zoomed" are measured against this, not against fit.
    private(set) var baseScale: CGFloat = 1

    override init(frame: CGRect) {
        super.init(frame: frame)
        showsVerticalScrollIndicator = false
        showsHorizontalScrollIndicator = false
        contentInsetAdjustmentBehavior = .never
        bouncesZoom = true
        decelerationRate = .fast
        imageView.contentMode = .scaleAspectFit
        imageView.isUserInteractionEnabled = true
        addSubview(imageView)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /// True while the photo is magnified past the scale it opened at.
    var isZoomedIn: Bool { zoomScale > baseScale + 0.01 }

    /// Fills the screen on the first double tap, then a fixed step past that.
    var doubleTapZoomScale: CGFloat {
        guard fittedSize.width > 0, fittedSize.height > 0 else { return 2 }
        let fill = max(bounds.width / fittedSize.width, bounds.height / fittedSize.height)
        return min(max(baseScale * 2, fill * 1.4), maximumZoomScale)
    }

    func setImage(_ image: UIImage) {
        imageView.image = image
        lastLaidOutBounds = .zero
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if bounds.size != lastLaidOutBounds {
            lastLaidOutBounds = bounds.size
            resetLayout()
        }
        centerContent()
    }

    private func resetLayout() {
        guard let image = imageView.image,
              bounds.width > 0, bounds.height > 0,
              image.size.width > 0, image.size.height > 0
        else { return }

        zoomScale = 1
        let fit = min(bounds.width / image.size.width, bounds.height / image.size.height)
        fittedSize = CGSize(
            width: (image.size.width * fit).rounded(),
            height: (image.size.height * fit).rounded()
        )
        imageView.frame = CGRect(origin: .zero, size: fittedSize)
        contentSize = fittedSize

        baseScale = MediaViewerLayout.presentationScale(
            aspect: image.size.width / image.size.height,
            in: bounds.size
        )
        // Floor stays at fit even when we open filled, so a pinch out can always reveal the
        // whole photo rather than locking the user out of the cropped edges.
        minimumZoomScale = 1
        // Let the user reach native pixels, but always allow a useful zoom on small images.
        let nativeScale = (image.size.width * image.scale) / max(fittedSize.width, 1)
        maximumZoomScale = min(max(3 * baseScale, nativeScale), 8)
        zoomScale = baseScale
        syncScrollEnabled()
    }

    /// At the opening scale a drag means "dismiss", so the scroll view must not eat it.
    /// Zooming past that hands panning back over. Pinch keeps working either way.
    func syncScrollEnabled() {
        isScrollEnabled = isZoomedIn
    }

    /// Keeps the photo centred while it is smaller than the viewport (Apple's zoom pattern —
    /// adjusting the frame rather than `contentInset` avoids a layout feedback loop).
    func centerContent() {
        var frame = imageView.frame
        frame.origin.x = frame.width < bounds.width ? (bounds.width - frame.width) / 2 : 0
        frame.origin.y = frame.height < bounds.height ? (bounds.height - frame.height) / 2 : 0
        imageView.frame = frame
    }
}
