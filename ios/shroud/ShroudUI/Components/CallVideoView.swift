import SwiftUI
import UIKit
import WebRTC

/// One WebRTC video track, cropped to fill. A front-camera self view is mirrored.
///
/// Human: With `focus` (our own small picture), the crop centres on the faces Center Stage follows
/// instead of on the picture's middle: our picture goes out in the other side's shape, so a tile
/// of another shape would otherwise cut the faces off. With `reveal`, the other side's picture
/// opens out of their face: it shows only inside a circle that grows from the face to the whole
/// screen when their camera comes on, and shrinks back behind the face when it goes off.
struct CallVideoView: UIViewRepresentable {
    let track: RTCVideoTrack?
    var mirror = false
    var reveal: FaceReveal?
    /// Where the faces are in the picture, for our own small picture; nil: the middle (their
    /// picture).
    var focus: CallSelfViewFocus?

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
        view.setMirror(mirror)
        view.follow(focus)
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
///
/// Our own picture follows the faces (`follow`): the video view is sized to the picture filled
/// into this box (aspect fill, so nothing is letterboxed) and moved inside it, clipped by the clip
/// view, so the point `CallSelfViewFocus` names sits as near the middle as the picture allows
/// (`CallFraming.coverOffset`). A display link at up to 30 Hz reads the focus and moves only the
/// video view's `center`: no layout pass, nothing in SwiftUI. The focus already glides with the
/// cut; at rest (no faces, Center Stage off, Apple framing) it is the middle, which is the plain
/// centred fill.
final class CallVideoContainer: UIView, RTCVideoViewDelegate {
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
    /// Our own picture: where its faces are (nil for theirs, centred).
    private var focusSource: CallSelfViewFocus?
    /// The focus the video view is placed for now.
    private var shownFocus = CallFraming.Point.middle
    /// The picture's size as the renderer last reported it (upright); zero before its first frame.
    private var pictureSize: CGSize = .zero
    private var mirrored = false
    private var focusLink: CADisplayLink?
    /// Fast enough for a glide that takes a third of a second; the screen's own rate is not needed.
    private static let focusRate = CAFrameRateRange(minimum: 15, maximum: 30, preferred: 30)

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
        video.delegate = self
        clip.addSubview(video)
        addSubview(clip)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        placeVideo()
        if !animating { settle() }
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        updateFocusLink()
    }

    /// A front-camera self view shows the picture mirrored, as in a mirror.
    func setMirror(_ on: Bool) {
        guard on != mirrored else { return }
        mirrored = on
        video.transform = on ? CGAffineTransform(scaleX: -1, y: 1) : .identity
        placeVideo()
    }

    /// Our own picture centres on where `focus` says the faces are; nil: on its middle.
    func follow(_ focus: CallSelfViewFocus?) {
        guard focus !== focusSource else { return }
        focusSource = focus
        shownFocus = focus?.point ?? .middle
        placeVideo()
        updateFocusLink()
    }

    /// The video view in the clip's coordinates (pinned where it is on screen while the circle
    /// moves); `bounds`/`center`, as a self view carries a mirroring transform. Without a focus,
    /// or before the first frame, it covers this box and fills it round the middle; with one, it
    /// is the filled picture's size, moved to put the focus in the middle. The renderer mirrors
    /// inside the video view, about its own middle, so a point at `x` in the picture shows at
    /// `1 - x` of it: the offset is worked out for what the person sees.
    private func placeVideo() {
        let box = bounds.size
        guard focusSource != nil, pictureSize.width > 0, pictureSize.height > 0, box.width > 0, box.height > 0 else {
            setVideo(size: box, center: CGPoint(x: bounds.midX, y: bounds.midY))
            return
        }
        let seen = CallFraming.Point(x: mirrored ? 1 - shownFocus.x : shownFocus.x, y: shownFocus.y)
        let cover = CallFraming.coverOffset(
            CallFraming.Size(width: box.width, height: box.height),
            picture: CallFraming.Size(width: pictureSize.width, height: pictureSize.height),
            focus: seen
        )
        let size = CGSize(width: pictureSize.width * cover.scale, height: pictureSize.height * cover.scale)
        setVideo(size: size, center: CGPoint(x: cover.x + size.width / 2, y: cover.y + size.height / 2))
    }

    /// Sets only what changed: a new size re-lays the renderer out, a new centre only moves it.
    private func setVideo(size: CGSize, center: CGPoint) {
        if video.bounds.size != size {
            video.bounds = CGRect(origin: .zero, size: size)
        }
        if video.center != center {
            video.center = center
        }
    }

    /// Runs while there is a focus to follow and the picture is on screen.
    private func updateFocusLink() {
        let wanted = focusSource != nil && window != nil
        if wanted, focusLink == nil {
            let link = CADisplayLink(target: FocusTicker(self), selector: #selector(FocusTicker.tick(_:)))
            link.preferredFrameRateRange = Self.focusRate
            link.add(to: .main, forMode: .common)
            focusLink = link
        } else if !wanted, let link = focusLink {
            link.invalidate()
            focusLink = nil
        }
    }

    /// One display-link tick: the latest focus, applied when it moved by more than a hair.
    fileprivate func followFocus() {
        guard let focusSource else { return }
        let next = focusSource.point
        guard abs(next.x - shownFocus.x) > 0.0005 || abs(next.y - shownFocus.y) > 0.0005 else { return }
        shownFocus = next
        placeVideo()
    }

    // MARK: - RTCVideoViewDelegate

    /// The picture's (upright) size: on the first frame, and whenever it changes shape or the
    /// encoder's ladder resizes it. Delivered on the main queue.
    nonisolated func videoView(_ videoView: any RTCVideoRenderer, didChangeVideoSize size: CGSize) {
        Task { @MainActor in
            guard size != self.pictureSize else { return }
            self.pictureSize = size
            self.placeVideo()
        }
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

/// The focus display link's target: CADisplayLink keeps its target, so this holds the picture
/// weakly and stops the link once the picture is gone.
private final class FocusTicker: NSObject {
    private weak var owner: CallVideoContainer?

    init(_ owner: CallVideoContainer) {
        self.owner = owner
    }

    @objc func tick(_ link: CADisplayLink) {
        guard let owner else {
            link.invalidate()
            return
        }
        owner.followFocus()
    }
}
