import SwiftUI
import UIKit
import WebRTC

/// One WebRTC video track, cropped to fill. A front-camera self view is mirrored.
///
/// Human: With `reveal`, the other side's picture opens out of their face: it shows only inside
/// a circle that grows from the face to the whole screen when their camera comes on, and shrinks
/// back behind the face when it goes off.
struct CallVideoView: UIViewRepresentable {
    let track: RTCVideoTrack?
    var mirror = false
    var reveal: FaceReveal?

    /// Where the picture opens from and closes into, and whether it is open.
    struct FaceReveal: Equatable {
        /// The whole picture shows; false: none of it (its circle waits behind the face).
        var open: Bool
        /// Their camera is on, so the first frame is on its way: the renderer starts ahead of the
        /// opening, and the circle never opens onto a frame left from before.
        var warm: Bool
        /// Where the face is on screen.
        let face: FaceSpot

        static func == (lhs: Self, rhs: Self) -> Bool {
            lhs.open == rhs.open && lhs.warm == rhs.warm && lhs.face === rhs.face
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> CallVideoContainer {
        let view = CallVideoContainer()
        if let reveal {
            view.reveal(reveal, animated: false)
        }
        return view
    }

    func updateUIView(_ view: CallVideoContainer, context: Context) {
        if context.coordinator.track !== track {
            context.coordinator.track?.remove(view.video)
            track?.add(view.video)
            context.coordinator.track = track
        }
        view.video.transform = mirror ? CGAffineTransform(scaleX: -1, y: 1) : .identity
        if let reveal {
            view.reveal(reveal, animated: true)
        }
    }

    static func dismantleUIView(_ view: CallVideoContainer, coordinator: Coordinator) {
        coordinator.track?.remove(view.video)
        coordinator.track = nil
    }

    final class Coordinator {
        var track: RTCVideoTrack?
    }
}

/// Where the face sits on screen, in window coordinates, as layout last measured it.
///
/// Agent: A reference, so the overlay can keep it current without re-rendering itself; the
/// video reads it only when its circle starts to move.
final class FaceSpot {
    var frame: CGRect = .zero
}

/// The video, and while it opens or closes, a circular clip centred on the face.
///
/// Human: The circle is a clip view whose `bounds` and `cornerRadius` animate together, with the
/// video pinned where it is on screen. Both are properties the render server interpolates itself:
/// the circle keeps the display's pace (up to 120 Hz on ProMotion iPhones) whatever the main
/// thread does, with no mask layer (an extra offscreen pass of the whole picture every frame) and
/// no path redrawn per frame. At rest an open picture is not clipped at all, and a closed one is
/// hidden with its renderer paused, so a voice call costs nothing here.
final class CallVideoContainer: UIView {
    let video = RTCMTLVideoView(frame: .zero)

    /// Both directions move from the first frame and slow evenly: into the screen's corners when
    /// opening, onto the face when closing. The web uses the same (`useFaceReveal`).
    static let openDuration: CFTimeInterval = 0.42
    static let closeDuration: CFTimeInterval = 0.38
    private static let timing = CAMediaTimingFunction(controlPoints: 0.33, 1, 0.68, 1)
    private static let frameRate = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)

    private let clip = UIView()
    private var open = true
    private var warm = false
    private var face: FaceSpot?
    private var animating = false
    private var generation = 0

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        clip.clipsToBounds = true
        clip.autoresizesSubviews = false
        clip.layer.cornerCurve = .circular
        video.videoContentMode = .scaleAspectFill
        video.clipsToBounds = true
        video.backgroundColor = .clear
        video.autoresizingMask = []
        clip.addSubview(video)
        addSubview(clip)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        // Pinned to the screen in the clip's coordinates; `bounds`/`center`, as a self view
        // carries a mirroring transform.
        video.bounds = CGRect(origin: .zero, size: bounds.size)
        video.center = CGPoint(x: bounds.midX, y: bounds.midY)
        if !animating { settle() }
    }

    /// Opens or closes the picture round the face.
    func reveal(_ reveal: CallVideoView.FaceReveal, animated: Bool) {
        face = reveal.face
        warm = reveal.warm
        guard reveal.open != open else {
            if !animating { settle() }
            return
        }
        open = reveal.open
        guard animated, window != nil, !UIAccessibility.isReduceMotionEnabled else {
            generation += 1
            animating = false
            clip.layer.removeAnimation(forKey: "revealBounds")
            clip.layer.removeAnimation(forKey: "revealCorner")
            settle()
            return
        }
        run(opening: open)
    }

    /// At rest: open is the whole picture, unclipped; closed is hidden, its circle behind the face.
    private func settle() {
        if open {
            clip.layer.cornerRadius = 0
            clip.bounds = CGRect(origin: .zero, size: bounds.size)
            clip.center = CGPoint(x: bounds.midX, y: bounds.midY)
            isHidden = false
        } else {
            place(radius: faceRadius)
            isHidden = true
        }
        video.isEnabled = open || warm
    }

    private func run(opening: Bool) {
        generation += 1
        let run = generation
        // Turned around halfway, the circle goes on from where it is.
        let from = animating
            ? (clip.layer.presentation()?.bounds.width ?? clip.bounds.width) / 2
            : (opening ? faceRadius : fullRadius)
        let to = opening ? fullRadius : faceRadius
        let center = faceCenter
        animating = true
        video.isEnabled = true
        isHidden = false

        CATransaction.begin()
        CATransaction.setDisableActions(true)
        CATransaction.setCompletionBlock { [weak self] in
            guard let self, self.generation == run else { return }
            self.animating = false
            self.settle()
        }
        place(radius: to)
        let duration = opening ? Self.openDuration : Self.closeDuration
        let bounds = CABasicAnimation(keyPath: "bounds")
        bounds.fromValue = NSValue(cgRect: square(radius: from, center: center))
        let corner = CABasicAnimation(keyPath: "cornerRadius")
        corner.fromValue = from
        // Same start, length and curve: at every frame the corners are half the side, a circle.
        for animation in [bounds, corner] {
            animation.duration = duration
            animation.timingFunction = Self.timing
            animation.preferredFrameRateRange = Self.frameRate
        }
        clip.layer.add(bounds, forKey: "revealBounds")
        clip.layer.add(corner, forKey: "revealCorner")
        CATransaction.commit()
    }

    /// The clip as a circle of `radius` round the face. Its bounds' origin moves with it, so the
    /// video inside stays where it is on screen.
    private func place(radius: CGFloat) {
        let center = faceCenter
        clip.center = center
        clip.bounds = square(radius: radius, center: center)
        clip.layer.cornerRadius = radius
    }

    private func square(radius: CGFloat, center: CGPoint) -> CGRect {
        CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2)
    }

    /// The face's centre here; the middle of the screen before the face has been measured.
    private var faceCenter: CGPoint {
        guard let frame = face?.frame, frame.width > 0, window != nil else {
            return CGPoint(x: bounds.midX, y: bounds.midY)
        }
        let local = convert(frame, from: nil)
        return CGPoint(x: local.midX, y: local.midY)
    }

    /// A hair inside the face's edge, so no picture shows around it once it is back.
    private var faceRadius: CGFloat {
        max(1, (face?.frame.width ?? 0) / 2 * 0.96)
    }

    /// Far enough to cover the screen's farthest corner.
    private var fullRadius: CGFloat {
        let center = faceCenter
        let dx = max(center.x - bounds.minX, bounds.maxX - center.x)
        let dy = max(center.y - bounds.minY, bounds.maxY - center.y)
        return (dx * dx + dy * dy).squareRoot() + 2
    }
}
