import Foundation
import ImageIO
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
