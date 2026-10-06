import CoreVideo
import Foundation
import Testing
import WebRTC
@testable import shroud

/// Camera frames through `CameraFramer` into a real video source, as a call's track sees them:
/// passed on whole, cropped without a copy, or scaled into a buffer of their own once zoomed.
@MainActor
struct CallCameraFramerTests {
    private static let factory = RTCPeerConnectionFactory(
        encoderFactory: RTCDefaultVideoEncoderFactory(),
        decoderFactory: RTCDefaultVideoDecoderFactory()
    )

    /// One framer feeding a source, and what comes out of the source's track.
    private struct Rig {
        let framer: CameraFramer
        let feed: FrameFeed
        let sink: FrameSink
        let track: RTCVideoTrack
        let outputs: OutputSizes
    }

    private func rig(centerStage: Bool, view: CallFraming.Size?) -> Rig {
        let source = Self.factory.videoSource()
        let track = Self.factory.videoTrack(with: source, trackId: "framer-test-\(UUID().uuidString)")
        let sink = FrameSink()
        track.add(sink)
        let framer = CameraFramer(source: source, centerStage: centerStage)
        framer.setPeerView(view)
        let outputs = OutputSizes()
        framer.setOnOutputSize { size in outputs.sizes.append(size) }
        return Rig(framer: framer, feed: FrameFeed(delegate: framer), sink: sink, track: track, outputs: outputs)
    }

    private func buffer(width: Int, height: Int, format: OSType = kCVPixelFormatType_420YpCbCr8BiPlanarFullRange) throws -> CVPixelBuffer {
        var made: CVPixelBuffer?
        let attributes = [kCVPixelBufferIOSurfacePropertiesKey as String: [:] as [String: Any]]
        CVPixelBufferCreate(kCFAllocatorDefault, width, height, format, attributes as CFDictionary, &made)
        return try #require(made)
    }

    /// Lets the source's frames and the main-actor reports land.
    private func settle() async throws {
        try await Task.sleep(for: .milliseconds(150))
    }

    @Test
    func withoutTheirViewACameraFrameGoesOnWhole() async throws {
        let rig = rig(centerStage: false, view: nil)
        let pixels = try buffer(width: 640, height: 480, format: kCVPixelFormatType_32BGRA)
        rig.feed.push(RTCVideoFrame(buffer: RTCCVPixelBuffer(pixelBuffer: pixels), rotation: ._0, timeStampNs: 1_000_000_000))
        try await settle()
        let frame = try #require(rig.sink.last)
        let out = try #require(frame.buffer as? RTCCVPixelBuffer)
        #expect(out.pixelBuffer === pixels)
        #expect(!out.requiresCropping())
        #expect(frame.width == 640 && frame.height == 480)
        #expect(rig.outputs.sizes == [CallFraming.Size(width: 640, height: 480)])
        rig.track.remove(rig.sink)
    }

    /// A phone's 1080p camera held upright (its buffer lies landscape, turned 90°), seen on a
    /// phone: cut to 886×1920 upright, a crop of the same buffer with nothing scaled. Without
    /// `adaptOutputFormat` the source passes the crop on as it is.
    @Test
    func seenOnAPhoneTheCutIsACropOfTheSameBuffer() async throws {
        let rig = rig(centerStage: false, view: CallFraming.Size(width: 1179, height: 2556))
        let pixels = try buffer(width: 1920, height: 1080)
        rig.feed.push(RTCVideoFrame(buffer: RTCCVPixelBuffer(pixelBuffer: pixels), rotation: ._90, timeStampNs: 1_000_000_000))
        try await settle()
        let frame = try #require(rig.sink.last)
        let out = try #require(frame.buffer as? RTCCVPixelBuffer)
        #expect(out.pixelBuffer === pixels)
        #expect(frame.rotation == ._90)
        #expect(out.width == 1920 && out.height == 886)
        #expect(out.cropX == 0 && out.cropY == 98 && out.cropWidth == 1920 && out.cropHeight == 886)
        #expect(rig.outputs.sizes == [CallFraming.Size(width: 886, height: 1920)])
        rig.track.remove(rig.sink)
    }

    /// With Center Stage on and a face, the cut zooms in: it goes out scaled into a buffer of the
    /// output's size, whole, so WebRTC's own shrinking can never crop it in the wrong place.
    @Test
    func aZoomedCutGoesOutScaledToTheOutput() async throws {
        let rig = rig(centerStage: true, view: nil)
        let pixels = try buffer(width: 1920, height: 1080)
        let capture = CallFraming.Size(width: 1920, height: 1080)
        var time: Int64 = 1_000_000_000
        func push() {
            rig.feed.push(RTCVideoFrame(buffer: RTCCVPixelBuffer(pixelBuffer: pixels), rotation: ._0, timeStampNs: time))
            time += 33_000_000
        }
        // The camera's colour description, which the scaled buffer must carry on.
        CVBufferSetAttachment(pixels, kCVImageBufferYCbCrMatrixKey, kCVImageBufferYCbCrMatrix_ITU_R_709_2, .shouldPropagate)
        CVBufferSetAttachment(pixels, kCVImageBufferColorPrimariesKey, kCVImageBufferColorPrimaries_ITU_R_709_2, .shouldPropagate)
        push()
        rig.framer.takeFaces(
            [CallFraming.Rect(x: 900, y: 400, width: 120, height: 120)],
            capture: capture,
            at: Double(time) / 1_000_000,
            generation: rig.framer.currentGeneration
        )
        for _ in 0..<90 { push() }
        try await settle()
        let frame = try #require(rig.sink.last)
        let out = try #require(frame.buffer as? RTCCVPixelBuffer)
        #expect(out.pixelBuffer !== pixels)
        #expect(!out.requiresCropping())
        #expect(CVPixelBufferGetWidth(out.pixelBuffer) == 1920)
        #expect(CVPixelBufferGetHeight(out.pixelBuffer) == 1080)
        #expect(CVPixelBufferGetPixelFormatType(out.pixelBuffer) == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange)
        let matrix = CVBufferCopyAttachment(out.pixelBuffer, kCVImageBufferYCbCrMatrixKey, nil) as? String
        #expect(matrix == kCVImageBufferYCbCrMatrix_ITU_R_709_2 as String)
        let primaries = CVBufferCopyAttachment(out.pixelBuffer, kCVImageBufferColorPrimariesKey, nil) as? String
        #expect(primaries == kCVImageBufferColorPrimaries_ITU_R_709_2 as String)
        #expect(rig.outputs.sizes == [capture])
        rig.track.remove(rig.sink)
    }

    /// Our own picture is told where the faces are in what goes out: on the face (a little above
    /// the cut's middle) while ours frames, the middle once Apple's camera frames by itself, and
    /// the middle again when the camera starts over.
    @Test
    func theFocusFollowsTheFacesOnlyWhileWeFrame() async throws {
        let focus = CallSelfViewFocus()
        let source = Self.factory.videoSource()
        let framer = CameraFramer(source: source, centerStage: true, focus: focus)
        let feed = FrameFeed(delegate: framer)
        let pixels = try buffer(width: 1920, height: 1080)
        let capture = CallFraming.Size(width: 1920, height: 1080)
        var time: Int64 = 1_000_000_000
        /// `count` frames 33 ms apart; with `face`, the detector sees it every 15 frames, so the
        /// test's blank frames (no faces for Vision) never count as the face being lost.
        func push(_ count: Int, face: CallFraming.Rect? = nil) {
            for index in 0..<count {
                if let face, index % 15 == 0 {
                    framer.takeFaces([face], capture: capture, at: Double(time) / 1_000_000, generation: framer.currentGeneration)
                }
                feed.push(RTCVideoFrame(buffer: RTCCVPixelBuffer(pixelBuffer: pixels), rotation: ._0, timeStampNs: time))
                time += 33_000_000
            }
        }
        push(1)
        #expect(focus.point == .middle, "no faces yet: the middle")
        push(120, face: CallFraming.Rect(x: 900, y: 400, width: 120, height: 120))
        #expect(abs(focus.point.x - 0.5) < 0.01 && abs(focus.point.y - 0.42) < 0.01, "on the face (\(focus.point))")

        framer.setSystemFraming(true)
        push(240)
        #expect(abs(focus.point.x - 0.5) < 0.01 && abs(focus.point.y - 0.5) < 0.01, "Apple framing: the middle (\(focus.point))")

        framer.setSystemFraming(false)
        push(60, face: CallFraming.Rect(x: 1500, y: 300, width: 120, height: 120))
        #expect(focus.point != .middle)
        framer.restart()
        #expect(focus.point == .middle, "a camera switch: the middle at once")
    }

    /// A camera switch (both cameras 1080p) starts the cut over from the whole picture at once,
    /// and faces the detector found in the last camera's frames are dropped when they arrive.
    @Test
    func aCameraSwitchDropsTheZoomAndTheLastCamerasFaces() async throws {
        let rig = rig(centerStage: true, view: nil)
        let pixels = try buffer(width: 1920, height: 1080)
        let capture = CallFraming.Size(width: 1920, height: 1080)
        var time: Int64 = 1_000_000_000
        func push() {
            rig.feed.push(RTCVideoFrame(buffer: RTCCVPixelBuffer(pixelBuffer: pixels), rotation: ._0, timeStampNs: time))
            time += 33_000_000
        }
        push()
        let before = rig.framer.currentGeneration
        let face = [CallFraming.Rect(x: 900, y: 400, width: 120, height: 120)]
        rig.framer.takeFaces(face, capture: capture, at: Double(time) / 1_000_000, generation: before)
        for _ in 0..<60 { push() }
        try await settle()
        let zoomed = try #require(rig.sink.last?.buffer as? RTCCVPixelBuffer)
        #expect(zoomed.pixelBuffer !== pixels, "zoomed in on the face first")

        rig.framer.restart()
        #expect(rig.framer.currentGeneration != before)
        push()
        // The detector's answer for a frame of the last camera, arriving late.
        rig.framer.takeFaces(face, capture: capture, at: Double(time) / 1_000_000, generation: before)
        for _ in 0..<30 { push() }
        try await settle()
        let out = try #require(rig.sink.last?.buffer as? RTCCVPixelBuffer)
        #expect(out.pixelBuffer === pixels)
        #expect(!out.requiresCropping())
        rig.track.remove(rig.sink)
    }

    /// Faces from a picture the camera has since left (switched, turned) are not followed.
    @Test
    func facesFromAnotherPictureAreDropped() async throws {
        let rig = rig(centerStage: true, view: nil)
        let pixels = try buffer(width: 1920, height: 1080)
        var time: Int64 = 1_000_000_000
        rig.feed.push(RTCVideoFrame(buffer: RTCCVPixelBuffer(pixelBuffer: pixels), rotation: ._0, timeStampNs: time))
        // Boxes measured upright in a portrait picture: not this one.
        rig.framer.takeFaces(
            [CallFraming.Rect(x: 400, y: 400, width: 120, height: 120)],
            capture: CallFraming.Size(width: 1080, height: 1920),
            at: Double(time) / 1_000_000,
            generation: rig.framer.currentGeneration
        )
        for _ in 0..<60 {
            time += 33_000_000
            rig.feed.push(RTCVideoFrame(buffer: RTCCVPixelBuffer(pixelBuffer: pixels), rotation: ._0, timeStampNs: time))
        }
        try await settle()
        let out = try #require(rig.sink.last?.buffer as? RTCCVPixelBuffer)
        #expect(out.pixelBuffer === pixels)
        #expect(!out.requiresCropping())
        rig.track.remove(rig.sink)
    }
}

nonisolated private final class FrameSink: NSObject, RTCVideoRenderer, @unchecked Sendable {
    private let lock = NSLock()
    private var frames: [RTCVideoFrame] = []

    var last: RTCVideoFrame? { lock.withLock { frames.last } }

    func setSize(_ size: CGSize) {}

    func renderFrame(_ frame: RTCVideoFrame?) {
        guard let frame else { return }
        lock.withLock { frames.append(frame) }
    }
}

nonisolated private final class FrameFeed: RTCVideoCapturer, @unchecked Sendable {
    func push(_ frame: RTCVideoFrame) {
        delegate?.capturer(self, didCapture: frame)
    }
}

@MainActor
private final class OutputSizes {
    var sizes: [CallFraming.Size] = []
}
