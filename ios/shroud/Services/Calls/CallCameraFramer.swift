import CoreVideo
import Foundation
import ImageIO
import Vision
import WebRTC

/// Cuts each camera frame to what goes out, between the capturer and the video source
/// (docs/calls.md, "Framing and Center Stage").
///
/// Human: Our camera goes out in the shape the other side shows it in (their `view`), and with
/// Center Stage on the cut follows the faces in it. Faces are found on this device, about five
/// times a second, and nothing about them leaves it. While the camera frames people by itself
/// (Apple's Center Stage active, on iPads and newer front cameras), it does that instead, and this
/// only cuts the shape. Our own picture shows what goes out: it comes from the same source.
///
/// Agent: `RTCCameraVideoCapturer` (or the simulator's test pattern) delivers here instead of to
/// the source; `CallFramer` (the web's `Framer`) says where the cut is for each frame, in upright
/// pixels, and it is mapped back onto the buffer as it lies. A cut at the output's own size is a
/// crop of the camera's buffer, nothing copied. A zoomed cut (Center Stage on faces) is scaled
/// into a buffer of the output's size: the video source's own adapter re-crops a buffer that is
/// both cropped and scaled in the wrong coordinates (seen: a 960×540 crop of a 1920×1080 buffer
/// adapted to 1920×1080 came out as a 1920×1080 crop at 480,270, past the buffer's edge) whenever
/// WebRTC shrinks frames for a busy encoder or a tight link.
///
/// Frames arrive on the capturer's queue; the state they share with the main actor and with face
/// detection sits behind `lock`.
nonisolated final class CameraFramer: NSObject, RTCVideoCapturerDelegate, @unchecked Sendable {
    typealias Size = CallFraming.Size
    typealias Rect = CallFraming.Rect

    /// How often faces are looked for, at most (ms): about five times a second.
    static let detectEvery = 200.0

    private let source: RTCVideoSource
    private let lock = NSLock()
    /// Behind `lock`.
    private var framer = CallFramer()
    private var peerView: Size?
    private var centerStage: Bool
    private var systemFraming = false
    private var upright: Size?
    private var reportedOutput: Size?
    private var detecting = false
    private var lastDetect = -Double.infinity
    /// Bumped whenever the camera (re)starts or switches: faces found in frames from before carry
    /// an older one and are dropped.
    private var generation = 0
    private var onOutputSize: (@MainActor @Sendable (Size) -> Void)?

    /// Only on the capturer's queue (one frame at a time); `scaleLock` covers it anyway.
    private let scaleLock = NSLock()
    private var pool: CVPixelBufferPool?
    private var poolShape: (width: Int, height: Int, format: OSType)?
    private var scratch: [UInt8] = []

    private let faceQueue = DispatchQueue(label: "shroud.call-faces", qos: .userInitiated)

    init(source: RTCVideoSource, centerStage: Bool) {
        self.source = source
        self.centerStage = centerStage
    }

    /// The size of the area that shows our camera on the other side (device pixels), or nil:
    /// their picture keeps our camera's own shape.
    func setPeerView(_ view: Size?) {
        lock.withLock { peerView = view }
    }

    /// Center Stage on or off: the cut follows the faces, or glides back to the whole picture.
    func setCenterStage(_ on: Bool) {
        lock.withLock { centerStage = on }
    }

    /// The camera frames people by itself right now (Apple's Center Stage is active on it): only
    /// the shape is cut. False whenever it does not (another camera, Center Stage off in Control
    /// Center, a format without it), and then faces are looked for here.
    func setSystemFraming(_ on: Bool) {
        lock.withLock { systemFraming = on }
    }

    /// The camera started or switched: the cut starts over from the whole picture, and faces found
    /// in frames from before (still on their way from the detector) are dropped. Both cameras may
    /// give the same size, so the size alone cannot tell their frames apart.
    func restart() {
        lock.withLock {
            generation += 1
            framer = CallFramer()
            upright = nil
            lastDetect = -Double.infinity
        }
    }

    /// The camera's current run (`restart`): face results are tagged with it.
    var currentGeneration: Int {
        lock.withLock { generation }
    }

    /// Told on the main actor whenever the output's (upright) size changes: what reaches the
    /// encoder, so the camera-quality ladder sizes its rungs from it.
    func setOnOutputSize(_ handler: (@MainActor @Sendable (Size) -> Void)?) {
        lock.withLock { onOutputSize = handler }
    }

    // MARK: - Frames

    func capturer(_ capturer: RTCVideoCapturer, didCapture frame: RTCVideoFrame) {
        // Only whole camera buffers are cut; anything else goes on as it came.
        guard let buffer = frame.buffer as? RTCCVPixelBuffer, !buffer.requiresCropping() else {
            source.capturer(capturer, didCapture: frame)
            return
        }
        let pixels = buffer.pixelBuffer
        let bufferWidth = CVPixelBufferGetWidth(pixels)
        let bufferHeight = CVPixelBufferGetHeight(pixels)
        let rotation = frame.rotation.rawValue
        let quarter = rotation == 90 || rotation == 270
        let capture = Size(
            width: Double(quarter ? bufferHeight : bufferWidth),
            height: Double(quarter ? bufferWidth : bufferHeight)
        )
        let now = Double(frame.timeStampNs) / 1_000_000

        let step = lock.withLock {
            () -> (output: Size, cut: Rect, detect: Bool, generation: Int, report: (@MainActor @Sendable (Size) -> Void)?) in
            let output = CallFraming.outputSize(capture, view: peerView)
            let follow = centerStage && !systemFraming
            framer.configure(capture: capture, output: output, follow: follow)
            upright = capture
            let cut = framer.next(now)
            let detect = follow && !detecting && now - lastDetect >= Self.detectEvery
            if detect {
                detecting = true
                lastDetect = now
            }
            var report: (@MainActor @Sendable (Size) -> Void)?
            if output != reportedOutput {
                reportedOutput = output
                report = onOutputSize
            }
            return (output, cut, detect, generation, report)
        }
        if let report = step.report {
            let output = step.output
            Task { @MainActor in report(output) }
        }
        if step.detect {
            findFaces(in: pixels, rotation: rotation, capture: capture, at: now, generation: step.generation)
        }

        let outWidth = Int(quarter ? step.output.height : step.output.width)
        let outHeight = Int(quarter ? step.output.width : step.output.height)
        let crop = CallFraming.pixelCrop(
            CallFraming.bufferRect(step.cut, rotation: rotation, bufferWidth: bufferWidth, bufferHeight: bufferHeight),
            bufferWidth: bufferWidth,
            bufferHeight: bufferHeight,
            outputWidth: outWidth,
            outputHeight: outHeight
        )
        if crop.x == 0, crop.y == 0, crop.width == bufferWidth, crop.height == bufferHeight,
           outWidth == bufferWidth, outHeight == bufferHeight {
            source.capturer(capturer, didCapture: frame)
            return
        }
        let cropped = RTCCVPixelBuffer(
            pixelBuffer: pixels,
            adaptedWidth: Int32(crop.width),
            adaptedHeight: Int32(crop.height),
            cropWidth: Int32(crop.width),
            cropHeight: Int32(crop.height),
            cropX: Int32(crop.x),
            cropY: Int32(crop.y)
        )
        var out: RTCCVPixelBuffer = cropped
        if crop.width != outWidth || crop.height != outHeight,
           let scaled = scale(cropped, width: outWidth, height: outHeight) {
            out = RTCCVPixelBuffer(pixelBuffer: scaled)
        }
        source.capturer(capturer, didCapture: RTCVideoFrame(buffer: out, rotation: frame.rotation, timeStampNs: frame.timeStampNs))
    }

    /// The cut, enlarged into a pooled buffer of the output's size. Nil when that fails: the
    /// frame then goes out at the cut's own size.
    private func scale(_ cropped: RTCCVPixelBuffer, width: Int, height: Int) -> CVPixelBuffer? {
        let format = CVPixelBufferGetPixelFormatType(cropped.pixelBuffer)
        return scaleLock.withLock {
            if poolShape.map({ $0.width != width || $0.height != height || $0.format != format }) ?? true {
                pool = Self.makePool(width: width, height: height, format: format)
                poolShape = (width, height, format)
            }
            guard let pool else { return nil }
            var made: CVPixelBuffer?
            guard CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &made) == kCVReturnSuccess,
                  let made
            else { return nil }
            let need = Int(cropped.bufferSizeForCroppingAndScaling(toWidth: Int32(width), height: Int32(height)))
            if scratch.count < need { scratch = [UInt8](repeating: 0, count: need) }
            let done = scratch.withUnsafeMutableBufferPointer { temp in
                cropped.cropAndScale(to: made, withTempBuffer: temp.baseAddress)
            }
            guard done else { return nil }
            // The camera buffer's colour description (matrix, primaries, transfer function) and
            // the rest it hands on, so the encoder reads the scaled picture the same way.
            CVBufferPropagateAttachments(cropped.pixelBuffer, made)
            return made
        }
    }

    private static func makePool(width: Int, height: Int, format: OSType) -> CVPixelBufferPool? {
        let attributes: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: format,
            kCVPixelBufferWidthKey as String: width,
            kCVPixelBufferHeightKey as String: height,
            // The hardware encoder takes IOSurface-backed buffers without a copy.
            kCVPixelBufferIOSurfacePropertiesKey as String: [:] as [String: Any],
        ]
        var pool: CVPixelBufferPool?
        CVPixelBufferPoolCreate(kCFAllocatorDefault, nil, attributes as CFDictionary, &pool)
        return pool
    }

    // MARK: - Faces

    /// Looks for faces in one frame, off the capture queue; at most one look at a time. The
    /// buffer is kept alive by the closure until Vision is done with it.
    private func findFaces(in pixels: CVPixelBuffer, rotation: Int, capture: Size, at now: Double, generation: Int) {
        let frame = FacesInput(pixels: pixels)
        faceQueue.async { [weak self] in
            let faces = Self.detectFaces(in: frame.pixels, orientation: Self.orientation(rotation), upright: capture)
            guard let self else { return }
            self.lock.withLock { self.detecting = false }
            if let faces { self.takeFaces(faces, capture: capture, at: now, generation: generation) }
        }
    }

    /// Faces found in a frame of `capture` (upright pixels) taken at `now` (ms) during the camera's
    /// run `generation`. Dropped when the camera has switched, restarted or turned since: those
    /// boxes are in another picture.
    func takeFaces(_ faces: [Rect], capture: Size, at now: Double, generation: Int) {
        lock.withLock {
            guard generation == self.generation, upright == capture else { return }
            framer.faces(faces, now: now)
        }
    }

    /// Vision's face boxes in upright pixels (Vision's are 0…1 from the bottom left of the upright
    /// picture); nil when the request failed.
    private static func detectFaces(in pixels: CVPixelBuffer, orientation: CGImagePropertyOrientation, upright: Size) -> [Rect]? {
        let request = VNDetectFaceRectanglesRequest()
        let handler = VNImageRequestHandler(cvPixelBuffer: pixels, orientation: orientation, options: [:])
        do {
            try handler.perform([request])
        } catch {
            return nil
        }
        return (request.results ?? []).map { face in
            let box = face.boundingBox
            return Rect(
                x: box.minX * upright.width,
                y: (1 - box.maxY) * upright.height,
                width: box.width * upright.width,
                height: box.height * upright.height
            )
        }
    }

    /// How Vision is to read a buffer that WebRTC turns clockwise by `rotation` degrees to show it.
    static func orientation(_ rotation: Int) -> CGImagePropertyOrientation {
        switch rotation {
        case 90: .right
        case 180: .down
        case 270: .left
        default: .up
        }
    }
}

/// A camera buffer on its way to the face detector. Agent: only read there, after the capture
/// queue has let it go (WebRTC never writes a delivered buffer).
nonisolated private struct FacesInput: @unchecked Sendable {
    let pixels: CVPixelBuffer
}

nonisolated extension CallFraming {
    /// A buffer's crop in whole pixels.
    struct PixelCrop: Equatable, Sendable {
        var x: Int
        var y: Int
        var width: Int
        var height: Int
    }

    /// An upright rectangle on the buffer as it lies: WebRTC turns a `bufferWidth` × `bufferHeight`
    /// buffer clockwise by `rotation` degrees to show it upright.
    static func bufferRect(_ upright: Rect, rotation: Int, bufferWidth: Int, bufferHeight: Int) -> Rect {
        let bw = Double(bufferWidth)
        let bh = Double(bufferHeight)
        let u = upright
        switch rotation {
        case 90:
            return Rect(x: u.y, y: bh - (u.x + u.width), width: u.height, height: u.width)
        case 180:
            return Rect(x: bw - (u.x + u.width), y: bh - (u.y + u.height), width: u.width, height: u.height)
        case 270:
            return Rect(x: bw - (u.y + u.height), y: u.x, width: u.height, height: u.width)
        default:
            return u
        }
    }

    /// `rect` (buffer pixels) as a crop the buffer can take: even sides and offsets (the colour
    /// planes are half size), inside the buffer. Within two pixels of the output's size it takes
    /// that size exactly, so the cut goes out without being scaled.
    static func pixelCrop(_ rect: Rect, bufferWidth: Int, bufferHeight: Int, outputWidth: Int, outputHeight: Int) -> PixelCrop {
        func evenInt(_ value: Double) -> Int { Int((value / 2).rounded()) * 2 }
        var width = min(bufferWidth & ~1, max(2, evenInt(rect.width)))
        var height = min(bufferHeight & ~1, max(2, evenInt(rect.height)))
        if abs(width - outputWidth) <= 2, abs(height - outputHeight) <= 2,
           outputWidth <= bufferWidth, outputHeight <= bufferHeight {
            width = outputWidth
            height = outputHeight
        }
        let x = min((bufferWidth - width) & ~1, max(0, evenInt(rect.x)))
        let y = min((bufferHeight - height) & ~1, max(0, evenInt(rect.y)))
        return PixelCrop(x: x, y: y, width: width, height: height)
    }
}
