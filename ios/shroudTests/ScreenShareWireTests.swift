import Foundation
import CoreVideo
import ImageIO
import QuartzCore
import Testing
import WebRTC
@testable import shroud

/// The frames the broadcast extension hands the app (ShroudShared/ScreenShareWire.swift).
struct ScreenShareWireTests {
    private func frame(width: UInt32 = 884, height: UInt32 = 1916, orientation: UInt32 = 1, jpeg: Data) -> Data {
        ScreenShareWire.Header(width: width, height: height, orientation: orientation, length: UInt32(jpeg.count)).encoded() + jpeg
    }

    @Test
    func aHeaderRoundTripsInItsFixedLayout() throws {
        let header = ScreenShareWire.Header(width: 884, height: 1916, orientation: 6, length: 123_456)
        let bytes = header.encoded()
        #expect(bytes.count == ScreenShareWire.Header.size)
        // "SHSS", version 1, little-endian throughout.
        #expect(Array(bytes.prefix(8)) == [0x53, 0x53, 0x48, 0x53, 0x01, 0x00, 0x00, 0x00])
        #expect(ScreenShareWire.Header.decode(bytes) == header)
    }

    @Test
    func aHeaderThatIsNotOneIsRefused() {
        var wrongMagic = ScreenShareWire.Header(width: 2, height: 2, orientation: 1, length: 4).encoded()
        wrongMagic[0] = 0
        #expect(ScreenShareWire.Header.decode(wrongMagic) == nil)
        let tooLarge = ScreenShareWire.Header(width: 2, height: 2, orientation: 1, length: UInt32(ScreenShareWire.maxPayload + 1))
        #expect(ScreenShareWire.Header.decode(tooLarge.encoded()) == nil)
        let empty = ScreenShareWire.Header(width: 0, height: 2, orientation: 1, length: 4)
        #expect(ScreenShareWire.Header.decode(empty.encoded()) == nil)
        #expect(ScreenShareWire.Header.decode(Data(count: 10)) == nil)
    }

    @Test
    func framesComeOutWholeHoweverTheBytesArrive() {
        let first = frame(jpeg: Data(repeating: 1, count: 5000))
        let second = frame(width: 1916, height: 884, orientation: 8, jpeg: Data(repeating: 2, count: 7))
        let stream = first + second
        var reader = ScreenShareWire.Reader()
        var got: [(header: ScreenShareWire.Header, jpeg: Data)] = []
        // In awkward pieces: a header split in two, a frame and a half at once.
        var offset = 0
        for size in [10, 30, 4000, 990, 3, 20, 1000] {
            let end = min(stream.count, offset + size)
            got += reader.append(stream.subdata(in: offset..<end))
            offset = end
        }
        got += reader.append(stream.subdata(in: offset..<stream.count))
        #expect(got.count == 2)
        #expect(got[0].jpeg == Data(repeating: 1, count: 5000))
        #expect(got[1].header.orientation == 8 && got[1].header.width == 1916)
        #expect(got[1].jpeg == Data(repeating: 2, count: 7))
        #expect(!reader.isBroken)
    }

    @Test
    func aFrameAndAHalfLeaveTheHalfForLater() {
        let first = frame(jpeg: Data(repeating: 7, count: 3000))
        let second = frame(jpeg: Data(repeating: 8, count: 3000))
        var reader = ScreenShareWire.Reader()
        // One read with a whole frame and the start of the next, then the rest in small reads.
        var got = reader.append(first + second.prefix(1000))
        #expect(got.count == 1 && got[0].jpeg == Data(repeating: 7, count: 3000))
        for offset in stride(from: 1000, to: second.count, by: 700) {
            got = reader.append(second.subdata(in: offset..<min(second.count, offset + 700)))
            if offset + 700 < second.count { #expect(got.isEmpty) }
        }
        #expect(got.count == 1 && got[0].jpeg == Data(repeating: 8, count: 3000))
        // Nothing left over afterwards: the next frame alone comes out whole.
        #expect(reader.append(first).count == 1)
    }

    @Test
    func aStreamThatSaysSomethingElseIsDropped() {
        var reader = ScreenShareWire.Reader()
        #expect(reader.append(Data(repeating: 0xFF, count: 40)).isEmpty)
        #expect(reader.isBroken)
        #expect(reader.append(frame(jpeg: Data([1, 2, 3]))).isEmpty)
    }

    @Test
    func framesAreScaledToTheLongestSideAndKeptEven() {
        // An iPhone 17 Pro screen, portrait: its long side comes down to 1920.
        let phone = ScreenShareWire.wireSize(width: 1206, height: 2622)
        #expect(phone.height <= 1920 && phone.height >= 1918)
        #expect(phone.width % 2 == 0 && phone.height % 2 == 0)
        #expect(abs(Double(phone.width) / Double(phone.height) - 1206.0 / 2622.0) < 0.01)
        // Never enlarged.
        #expect(ScreenShareWire.wireSize(width: 640, height: 480) == (640, 480))
        #expect(ScreenShareWire.wireSize(width: 641, height: 481) == (640, 480))
    }

    @Test
    func theChosenResolutionSetsTheLongestSide() {
        let hd = ScreenShareWire.wireSize(width: 1206, height: 2622, maxSide: ScreenShareQuality.Resolution.hd.maxSide)
        #expect(hd.height <= 1280 && hd.height >= 1278 && hd.width % 2 == 0)
        #expect(ScreenShareWire.wireSize(width: 1206, height: 2622, maxSide: ScreenShareQuality.Resolution.fullHD.maxSide).height >= 1918)
        // Source: the screen's own pixels, only made even.
        #expect(ScreenShareWire.wireSize(width: 1206, height: 2622, maxSide: nil) == (1206, 2622))
        #expect(ScreenShareWire.wireSize(width: 1179, height: 2556, maxSide: nil) == (1178, 2556))
    }

    @Test
    func settingsRoundTripInTheirFixedLayout() throws {
        let settings = ScreenShareWire.Settings(ScreenShareQuality(resolution: .hd, frameRate: .sixty))
        #expect(settings.maxSide == 1280 && settings.framesPerSecond == 60)
        let bytes = settings.encoded()
        #expect(bytes.count == ScreenShareWire.Settings.size)
        // "SHSQ", version 1, little-endian throughout.
        #expect(Array(bytes.prefix(8)) == [0x51, 0x53, 0x48, 0x53, 0x01, 0x00, 0x00, 0x00])
        #expect(ScreenShareWire.Settings.decode(bytes) == settings)
        let source = ScreenShareWire.Settings(ScreenShareQuality(resolution: .source, frameRate: .thirty))
        #expect(source.maxSide == 0 && source.longestSide == nil)
        #expect(ScreenShareWire.Settings.decode(source.encoded()) == source)
        // Until the app says otherwise: 1080p at 15 fps, as before there was a choice.
        #expect(ScreenShareWire.Settings.standard == ScreenShareWire.Settings(maxSide: 1920, framesPerSecond: 15))
    }

    @Test
    func settingsThatAreNotBelievableAreRefused() {
        #expect(ScreenShareWire.Settings.decode(ScreenShareWire.Settings(maxSide: 1280, framesPerSecond: 0).encoded()) == nil)
        #expect(ScreenShareWire.Settings.decode(ScreenShareWire.Settings(maxSide: 1280, framesPerSecond: 120).encoded()) == nil)
        #expect(ScreenShareWire.Settings.decode(ScreenShareWire.Settings(maxSide: 1, framesPerSecond: 30).encoded()) == nil)
        #expect(ScreenShareWire.Settings.decode(ScreenShareWire.Settings(maxSide: 9000, framesPerSecond: 30).encoded()) == nil)
        // A frame header is not settings.
        #expect(ScreenShareWire.Settings.decode(ScreenShareWire.Header(width: 2, height: 2, orientation: 1, length: 4).encoded()) == nil)
    }

    @Test
    func theNewestSettingsComeOutHoweverTheBytesArrive() {
        let first = ScreenShareWire.Settings(maxSide: 1280, framesPerSecond: 15).encoded()
        let second = ScreenShareWire.Settings(maxSide: 0, framesPerSecond: 60).encoded()
        var reader = ScreenShareWire.Settings.Reader()
        #expect(reader.append(first.prefix(5)) == nil)
        #expect(reader.append(first.dropFirst(5)) == ScreenShareWire.Settings(maxSide: 1280, framesPerSecond: 15))
        // Two at once, and the start of a third: the newest whole one.
        #expect(reader.append(first + second + first.prefix(3)) == ScreenShareWire.Settings(maxSide: 0, framesPerSecond: 60))
        #expect(reader.append(first.dropFirst(3)) == ScreenShareWire.Settings(maxSide: 1280, framesPerSecond: 15))
        #expect(!reader.isBroken)
        #expect(reader.append(Data(repeating: 0xFF, count: 16)) == nil)
        #expect(reader.isBroken)
    }

    @Test
    func moreFramesOrMorePixelsGetMoreBits() {
        #expect(ScreenShareQuality.standard == ScreenShareQuality(resolution: .fullHD, frameRate: .fifteen))
        #expect(ScreenShareQuality(resolution: .fullHD, frameRate: .thirty).bitrate == 2_500_000)
        for rate in ScreenShareQuality.FrameRate.allCases {
            #expect(ScreenShareQuality(resolution: .hd, frameRate: rate).bitrate < ScreenShareQuality(resolution: .fullHD, frameRate: rate).bitrate)
            #expect(ScreenShareQuality(resolution: .fullHD, frameRate: rate).bitrate < ScreenShareQuality(resolution: .source, frameRate: rate).bitrate)
        }
        #expect(ScreenShareQuality(resolution: .source, frameRate: .thirty).bitrate < ScreenShareQuality(resolution: .source, frameRate: .sixty).bitrate)
        #expect(ScreenShareQuality(resolution: .fullHD, frameRate: .thirty).keepsResolution)
        #expect(!ScreenShareQuality(resolution: .fullHD, frameRate: .sixty).keepsResolution)
        #expect(ScreenShareQuality(resolution: .source, frameRate: .sixty).label == "Source · 60 fps")
        // The same names as the web client stores.
        #expect(ScreenShareQuality.Resolution.allCases.map(\.rawValue) == ["720p", "1080p", "source"])
    }

    /// The real receiver and uploader over the real socket: the app's settings reach the
    /// broadcast as it connects, and again when they change mid-share.
    @Test
    func theBroadcastSendsAtTheSizeAndRateTheAppChose() async throws {
        let sink = FrameSink()
        let receiver = try #require(ScreenShareReceiver { _ in })
        receiver.setQuality(ScreenShareQuality(resolution: .hd, frameRate: .fifteen))
        receiver.feed = ScreenFrameFeed(delegate: sink)
        receiver.start()
        defer { receiver.stop() }
        try await Task.sleep(for: .milliseconds(100))
        let uploader = ScreenShareUploader()
        try uploader.connect {}
        defer { uploader.close() }
        try await Task.sleep(for: .milliseconds(200))

        // A phone's screen on a 60 Hz beat for a second: 720p at 15 fps comes out. The first
        // scale in a process builds its filter and drops frames meanwhile: warmed up first.
        let screen = try #require(Self.pixelBuffer(width: 1206, height: 2622))
        await Self.beat(screen, into: uploader, seconds: 0.5)
        try await Task.sleep(for: .milliseconds(200))
        _ = sink.take()
        await Self.beat(screen, into: uploader, seconds: 1)
        try await Task.sleep(for: .milliseconds(200))
        let slow = sink.take()
        #expect((8...20).contains(slow.count), "about 15 frames, got \(slow.count)")
        #expect(slow.allSatisfy { $0.height <= 1280 && $0.height >= 1278 && $0.width % 2 == 0 })

        // Changed while sharing: the screen's own pixels, and far more frames.
        receiver.setQuality(ScreenShareQuality(resolution: .source, frameRate: .sixty))
        try await Task.sleep(for: .milliseconds(200))
        _ = sink.take()
        await Self.beat(screen, into: uploader, seconds: 1)
        try await Task.sleep(for: .milliseconds(200))
        let fast = sink.take()
        #expect(fast.count >= 25, "well over 15 frames, got \(fast.count)")
        #expect(fast.allSatisfy { $0.width == 1206 && $0.height == 2622 })
    }

    /// A broadcast that hangs up as it connects (stopped, or its extension gone) is said to have
    /// connected and then gone, in that order, and the next one is taken as usual.
    @Test
    func aBroadcastThatHangsUpAtOnceEndsCleanly() async throws {
        let events = EventLog()
        let receiver = try #require(ScreenShareReceiver { events.append($0) })
        receiver.start()
        defer { receiver.stop() }
        try await Task.sleep(for: .milliseconds(100))
        let path = try #require(ScreenShareWire.socketURL).path
        // Connected and closed before the app gets to accept it: telling it the settings fails.
        let fd = socket(AF_UNIX, SOCK_STREAM, 0)
        var address = try #require(sockaddr_un.unixAddress(path: path))
        let connected = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size))
            }
        }
        #expect(connected == 0)
        close(fd)
        try await Task.sleep(for: .milliseconds(300))
        #expect(events.all == [.connected, .disconnected])

        let sink = FrameSink()
        receiver.feed = ScreenFrameFeed(delegate: sink)
        let uploader = ScreenShareUploader()
        try uploader.connect {}
        defer { uploader.close() }
        try await Task.sleep(for: .milliseconds(200))
        let screen = try #require(Self.pixelBuffer(width: 1206, height: 2622))
        await Self.beat(screen, into: uploader, seconds: 0.5)
        try await Task.sleep(for: .milliseconds(300))
        #expect(events.all == [.connected, .disconnected, .connected, .firstFrame])
        #expect(!sink.take().isEmpty)
    }

    /// Source on a screen with an odd side: one column cut off, never a resample.
    @Test
    func sourceOnAnOddScreenLosesOneColumnAndStaysSharp() async throws {
        let sink = FrameSink()
        let receiver = try #require(ScreenShareReceiver { _ in })
        receiver.setQuality(ScreenShareQuality(resolution: .source, frameRate: .thirty))
        receiver.feed = ScreenFrameFeed(delegate: sink)
        receiver.start()
        defer { receiver.stop() }
        try await Task.sleep(for: .milliseconds(100))
        let uploader = ScreenShareUploader()
        try uploader.connect {}
        defer { uploader.close() }
        try await Task.sleep(for: .milliseconds(200))
        // Sharp vertical stripes a pixel wide: any resample would blur them into grey.
        let screen = try #require(Self.pixelBuffer(width: 1179, height: 2556, stripes: true))
        await Self.beat(screen, into: uploader, seconds: 0.4)
        try await Task.sleep(for: .milliseconds(300))
        let frames = sink.takeFrames()
        let frame = try #require(frames.last)
        #expect(frame.width == 1178 && frame.height == 2556)
        let pixels = try #require((frame.buffer as? RTCCVPixelBuffer)?.pixelBuffer)
        CVPixelBufferLockBaseAddress(pixels, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixels, .readOnly) }
        let row = CVPixelBufferGetBaseAddress(pixels)!.assumingMemoryBound(to: UInt8.self) + 1000 * CVPixelBufferGetBytesPerRow(pixels)
        // Neighbouring columns stay far apart mid-screen, where a 1179-to-1178 resample would land
        // half a pixel off and average them to grey (JPEG only rings a little).
        let contrast = (580..<600).map { abs(Int(row[$0 * 4 + 1]) - Int(row[($0 + 1) * 4 + 1])) }
        #expect(contrast.min()! > 120, "neighbouring columns \(contrast)")
    }

    /// Hands `buffer` to the uploader every 16.7 ms, as the broadcast's display beat does.
    private static func beat(_ buffer: CVPixelBuffer, into uploader: ScreenShareUploader, seconds: Double) async {
        nonisolated(unsafe) let frame = buffer
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            DispatchQueue.global(qos: .userInitiated).async {
                let start = CACurrentMediaTime()
                var tick = 0.0
                while CACurrentMediaTime() - start < seconds {
                    uploader.send(frame, orientation: 1)
                    tick += 1
                    let next = start + tick / 60
                    let wait = next - CACurrentMediaTime()
                    if wait > 0 { usleep(useconds_t(wait * 1_000_000)) }
                }
                done.resume()
            }
        }
    }

    /// Mid-grey, or black and white columns a pixel wide.
    private static func pixelBuffer(width: Int, height: Int, stripes: Bool = false) -> CVPixelBuffer? {
        var buffer: CVPixelBuffer?
        let attributes = [kCVPixelBufferIOSurfacePropertiesKey as String: [:] as [String: Any]]
        CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_32BGRA, attributes as CFDictionary, &buffer)
        guard let buffer else { return nil }
        CVPixelBufferLockBaseAddress(buffer, [])
        let base = CVPixelBufferGetBaseAddress(buffer)!.assumingMemoryBound(to: UInt8.self)
        let bytesPerRow = CVPixelBufferGetBytesPerRow(buffer)
        for y in 0..<height {
            for x in 0..<width {
                let value: UInt8 = stripes ? (x % 2 == 0 ? 0 : 255) : 0x80
                let pixel = base + y * bytesPerRow + x * 4
                pixel[0] = value
                pixel[1] = value
                pixel[2] = value
                pixel[3] = 255
            }
        }
        CVPixelBufferUnlockBaseAddress(buffer, [])
        return buffer
    }

    @Test
    func theBroadcastsOrientationBecomesTheTurnSentWithTheFrame() {
        #expect(ScreenShareReceiver.rotation(CGImagePropertyOrientation.up.rawValue) == ._0)
        #expect(ScreenShareReceiver.rotation(CGImagePropertyOrientation.down.rawValue) == ._180)
        #expect(ScreenShareReceiver.rotation(CGImagePropertyOrientation.left.rawValue) == ._90)
        #expect(ScreenShareReceiver.rotation(CGImagePropertyOrientation.right.rawValue) == ._270)
        #expect(ScreenShareReceiver.rotation(99) == ._0)
    }

    @Test
    func aSocketPathTooLongForAUnixSocketIsRefusedNotCutShort() {
        #expect(sockaddr_un.unixAddress(path: "/private/var/mobile/Containers/Shared/AppGroup/74818113-9D67-4D68-A3DD-DCCCD647FA87/screen.sock") != nil)
        #expect(sockaddr_un.unixAddress(path: "/" + String(repeating: "a", count: 110)) == nil)
        // The simulator's socket stays short whatever its container path.
        #expect(ScreenShareWire.socketURL.map { $0.path.utf8.count < 104 } == true)
    }
}

/// Collects the sizes of the frames that reach the call's screen track.
private nonisolated final class FrameSink: NSObject, RTCVideoCapturerDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var sizes: [(width: Int32, height: Int32)] = []

    func capturer(_ capturer: RTCVideoCapturer, didCapture frame: RTCVideoFrame) {
        lock.withLock {
            sizes.append((frame.width, frame.height))
            frames.append(frame)
        }
    }

    /// The frames since the last call.
    func take() -> [(width: Int32, height: Int32)] {
        lock.withLock {
            defer { sizes.removeAll(); frames.removeAll() }
            return sizes
        }
    }

    /// The frames themselves since the last call.
    func takeFrames() -> [RTCVideoFrame] {
        lock.withLock {
            defer { sizes.removeAll(); frames.removeAll() }
            return frames
        }
    }

    private var frames: [RTCVideoFrame] = []
}

/// The receiver's events, in the order they reached the main queue.
private final class EventLog: @unchecked Sendable {
    private let lock = NSLock()
    private var events: [ScreenShareReceiver.Event] = []

    func append(_ event: ScreenShareReceiver.Event) {
        lock.withLock { events.append(event) }
    }

    var all: [ScreenShareReceiver.Event] { lock.withLock { events } }
}
