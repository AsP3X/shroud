import Foundation

/// Which part of our camera goes out (docs/calls.md, "Framing and Center Stage").
///
/// Human: The picture is cut to the shape of the area the other side shows it in (their
/// `media_state` `view`), from the camera's full resolution, so a phone that fills its screen
/// with our picture gets every pixel it shows instead of enlarging the middle of a wide frame.
/// With Center Stage on, the cut also follows the faces in it: it pans to keep them in the middle
/// and zooms in on them, as far as the camera has pixels to spare.
///
/// Agent: Pure geometry, in upright picture pixels (as the picture is seen, rotation applied). A
/// line-for-line port of the web's `framing.ts`, with the same numbers; Android's
/// `CallFraming.kt` runs them too. `CameraFramer` applies it to the camera's frames.
nonisolated enum CallFraming {
    struct Size: Equatable, Sendable {
        var width: Double
        var height: Double
    }

    struct Rect: Equatable, Sendable {
        var x: Double
        var y: Double
        var width: Double
        var height: Double
    }

    /// The most pixels the output's longer side gets: the camera ladder's top rung (1080p).
    static let maxLong = 1920.0
    /// The narrowest and the widest shape we cut to (width / height): a tall phone, a wide window.
    static let minAspect = 0.4
    static let maxAspect = 2.5
    /// A new view shape is taken only when it differs this much: a window being dragged is not.
    static let shapeChange = 0.03
    /// How much the faces fill the cut: their height a third of it (head and shoulders).
    static let faceHeightShare = 0.3
    /// Their width at most this much of it, for several people side by side.
    static let faceWidthShare = 0.6
    /// Where the faces' middle sits from the top of the cut.
    static let faceFromTop = 0.42
    /// The cut is never enlarged more than this into the output (so it never turns soft).
    static let maxUpscale = 1.25
    /// Nor zoomed in more than this on the whole picture.
    static let maxZoom = 2.5
    /// A new target closer than this to the last one (share of the cut's size) is not followed.
    static let deadband = 0.06
    /// How fast the cut follows (seconds to cover about two thirds of the way): pan, then zoom.
    static let panSeconds = 0.35
    static let zoomSeconds = 0.6
    /// No face for this long (ms): the cut goes back to the whole picture.
    static let lostMs = 1_500.0

    static func even(_ value: Double) -> Double {
        max(2, (value / 2).rounded() * 2)
    }

    private static func clampAspect(_ aspect: Double) -> Double {
        min(maxAspect, max(minAspect, aspect))
    }

    /// The largest rectangle of `aspect` inside `size`, in its middle.
    static func largestInside(_ size: Size, aspect: Double) -> Rect {
        var width = size.width
        var height = width / aspect
        if height > size.height {
            height = size.height
            width = height * aspect
        }
        return Rect(x: (size.width - width) / 2, y: (size.height - height) / 2, width: width, height: height)
    }

    /// The output picture: the shape of their view (or the camera's own, when they did not say),
    /// as large as the camera can fill without enlarging, at most 1080p on its longer side. Even
    /// sides.
    static func outputSize(_ capture: Size, view: Size?) -> Size {
        let shape: Double
        if let view, view.width > 0, view.height > 0 {
            shape = view.width / view.height
        } else {
            shape = capture.width / capture.height
        }
        let aspect = clampAspect(shape)
        let base = largestInside(capture, aspect: aspect)
        let long = min(maxLong, max(base.width, base.height))
        return aspect >= 1
            ? Size(width: even(long), height: even(long / aspect))
            : Size(width: even(long * aspect), height: even(long))
    }

    /// Whether a newly reported view shape is far enough from the one in use to cut to it.
    static func shapeChanged(_ current: Size?, _ next: Size?) -> Bool {
        guard let current, let next else { return (current == nil) != (next == nil) }
        let a = current.width / current.height
        let b = next.width / next.height
        return abs(a - b) / a > shapeChange
    }

    /// The smallest box around every face (each in upright pixels), or nil for none.
    static func faceUnion(_ faces: [Rect]) -> Rect? {
        guard !faces.isEmpty else { return nil }
        var left = Double.infinity
        var top = Double.infinity
        var right = -Double.infinity
        var bottom = -Double.infinity
        for face in faces {
            left = min(left, face.x)
            top = min(top, face.y)
            right = max(right, face.x + face.width)
            bottom = max(bottom, face.y + face.height)
        }
        return Rect(x: left, y: top, width: right - left, height: bottom - top)
    }

    /// Where the cut should be: the whole picture in the output's shape, or, with faces, a cut
    /// around them (head and shoulders, the faces a little above the middle), no tighter than the
    /// output's pixels allow, and always inside the picture.
    static func targetCrop(_ capture: Size, output: Size, faces: Rect?) -> Rect {
        let aspect = output.width / output.height
        let base = largestInside(capture, aspect: aspect)
        guard let faces else { return base }
        let fromHeight = faces.height / faceHeightShare
        let fromWidth = faces.width / faceWidthShare / aspect
        let least = max(output.height / maxUpscale, base.height / maxZoom)
        let height = min(base.height, max(fromHeight, fromWidth, least))
        let width = height * aspect
        let centerX = faces.x + faces.width / 2
        let centerY = faces.y + faces.height / 2
        let x = min(capture.width - width, max(0, centerX - width / 2))
        let y = min(capture.height - height, max(0, centerY - height * faceFromTop))
        return Rect(x: x, y: y, width: width, height: height)
    }

    fileprivate static func far(_ a: Rect, _ b: Rect) -> Bool {
        let size = max(a.width, a.height)
        let moved = hypot(a.x + a.width / 2 - (b.x + b.width / 2), a.y + a.height / 2 - (b.y + b.height / 2))
        return moved > size * deadband || abs(a.height - b.height) > a.height * deadband
    }

    /// One step of `from` toward `to` after `seconds`, covering about two thirds of it every `tau`.
    fileprivate static func ease(_ from: Double, _ to: Double, seconds: Double, tau: Double) -> Double {
        from + (to - from) * (1 - exp(-seconds / tau))
    }
}

/// The cut over time, for one camera: `faces` gives it what the detector saw, `next` where the
/// cut is for a frame. It glides (pans quicker than it zooms), ignores small jitter, holds on a
/// face that is briefly lost, and goes back to the whole picture once none has been seen for a
/// while.
///
/// Agent: the web's `Framer`, as a value type; `CameraFramer` keeps one behind its lock. Times are
/// milliseconds on any steady clock.
nonisolated struct CallFramer: Sendable {
    typealias Size = CallFraming.Size
    typealias Rect = CallFraming.Rect

    private var capture = Size(width: 0, height: 0)
    private var output = Size(width: 0, height: 0)
    private var current: Rect?
    private var target: Rect?
    private var lastFace = -Double.infinity
    private var lastStep: Double?
    private var follow = true

    init() {}

    /// The picture coming in and the output going out; a change starts again from the whole
    /// picture. `follow` false: the cut stays on the whole picture whatever the faces do.
    mutating func configure(capture: Size, output: Size, follow: Bool) {
        let same = capture == self.capture && output == self.output
        self.follow = follow
        if same, current != nil {
            if !follow { target = CallFraming.targetCrop(capture, output: output, faces: nil) }
            return
        }
        self.capture = capture
        self.output = output
        let whole = CallFraming.targetCrop(capture, output: output, faces: nil)
        current = whole
        target = whole
        lastStep = nil
    }

    /// What the detector saw in the picture at `now` (ms): faces in upright pixels, maybe none.
    mutating func faces(_ faces: [Rect], now: Double) {
        guard current != nil else { return }
        let union = follow ? CallFraming.faceUnion(faces) : nil
        if union != nil {
            lastFace = now
        } else if now - lastFace < CallFraming.lostMs {
            return
        }
        let next = CallFraming.targetCrop(capture, output: output, faces: union)
        if let target, !CallFraming.far(target, next) { return }
        target = next
    }

    /// Where the cut is for a frame at `now` (ms).
    mutating func next(_ now: Double) -> Rect {
        guard let c = current, let t = target else {
            return Rect(x: 0, y: 0, width: capture.width, height: capture.height)
        }
        let seconds = lastStep.map { max(0, min(0.25, (now - $0) / 1000)) } ?? 0
        lastStep = now
        let height = CallFraming.ease(c.height, t.height, seconds: seconds, tau: CallFraming.zoomSeconds)
        let width = height * (output.width / output.height)
        let centerX = CallFraming.ease(c.x + c.width / 2, t.x + t.width / 2, seconds: seconds, tau: CallFraming.panSeconds)
        let centerY = CallFraming.ease(c.y + c.height / 2, t.y + t.height / 2, seconds: seconds, tau: CallFraming.panSeconds)
        let x = min(capture.width - width, max(0, centerX - width / 2))
        let y = min(capture.height - height, max(0, centerY - height / 2))
        let rect = Rect(x: x, y: y, width: width, height: height)
        current = rect
        return rect
    }
}

nonisolated extension CallViewSize {
    /// The view as the framing geometry takes it.
    var framingSize: CallFraming.Size {
        CallFraming.Size(width: Double(w), height: Double(h))
    }
}
