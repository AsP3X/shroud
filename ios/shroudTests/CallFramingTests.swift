import Foundation
import ImageIO
import Testing
@testable import shroud

/// The cut our camera goes out in: its shape, where it goes for faces, and how it moves. The
/// web's `framing.selftest.ts` and Android's `CallFramingTest` check the same cases.
struct CallFramingTests {
    typealias Size = CallFraming.Size
    typealias Rect = CallFraming.Rect

    private static let uhd = Size(width: 3840, height: 2160)
    private static let fhd = Size(width: 1920, height: 1080)
    private static let phone = Size(width: 1179, height: 2556)
    private static let face = Rect(x: 1770, y: 900, width: 300, height: 300)

    private func near(_ a: Double, _ b: Double, _ tolerance: Double = 0.5) -> Bool {
        abs(a - b) <= tolerance
    }

    // MARK: - The output's shape

    @Test
    func aFourKCameraSeenOnAPhone() {
        #expect(CallFraming.outputSize(Self.uhd, view: Self.phone) == Size(width: 886, height: 1920))
    }

    @Test
    func a1080pCameraCanOnlyGiveItsHeight() {
        #expect(CallFraming.outputSize(Self.fhd, view: Self.phone) == Size(width: 498, height: 1080))
    }

    @Test
    func withoutTheirViewTheCamerasOwnShapeAt1080pAtMost() {
        #expect(CallFraming.outputSize(Self.uhd, view: nil) == Self.fhd)
    }

    @Test
    func neverEnlarged() {
        let hd = Size(width: 1280, height: 720)
        #expect(CallFraming.outputSize(hd, view: nil) == hd)
    }

    @Test
    func aPhoneHeldUprightSeenOnAPhone() {
        let portrait = CallFraming.outputSize(Size(width: 1080, height: 1920), view: Self.phone)
        #expect(portrait.height == Double(1920))
        #expect(portrait.width == Double(886))
    }

    @Test
    func noNarrowerThanPointFourNorWiderThanTwoAndAHalf() {
        let sliver = CallFraming.outputSize(Self.uhd, view: Size(width: 100, height: 1000))
        #expect(near(sliver.width / sliver.height, 0.4, 0.01))
        let banner = CallFraming.outputSize(Self.uhd, view: Size(width: 1000, height: 100))
        #expect(near(banner.width / banner.height, 2.5, 0.01))
    }

    @Test
    func anEmptyViewCountsAsNone() {
        #expect(CallFraming.outputSize(Self.uhd, view: Size(width: 0, height: 0)).width == Double(1920))
    }

    @Test
    func evenSides() {
        let odd = CallFraming.outputSize(Size(width: 1001, height: 999), view: nil)
        #expect(Int(odd.width) % 2 == 0)
        #expect(Int(odd.height) % 2 == 0)
    }

    // MARK: - When a new view counts

    @Test
    func aFewPixelsAreNoNewShape() {
        #expect(!CallFraming.shapeChanged(Size(width: 1179, height: 2556), Size(width: 1180, height: 2540)))
    }

    @Test
    func aRotationIs() {
        #expect(CallFraming.shapeChanged(Size(width: 1179, height: 2556), Size(width: 2556, height: 1179)))
    }

    @Test
    func appearingAndGoingAwayAre() {
        #expect(CallFraming.shapeChanged(nil, Self.phone))
        #expect(CallFraming.shapeChanged(Self.phone, nil))
        #expect(!CallFraming.shapeChanged(nil, nil))
    }

    // MARK: - Where the cut goes

    @Test
    func noFacesTheWholePicture() {
        let output = CallFraming.outputSize(Self.uhd, view: nil)
        let base = CallFraming.targetCrop(Self.uhd, output: output, faces: nil)
        #expect(base == Rect(x: 0, y: 0, width: 3840, height: 2160))
    }

    @Test
    func headAndShouldersWithTheFaceInTheMiddleALittleAboveIt() {
        let output = CallFraming.outputSize(Self.uhd, view: nil)
        let cut = CallFraming.targetCrop(Self.uhd, output: output, faces: Self.face)
        #expect(near(cut.height, 1000))
        #expect(near(cut.width, 1000 * (16.0 / 9.0)))
        #expect(near(cut.x + cut.width / 2, 1920))
        #expect(near(cut.y, 1050 - 420))
    }

    @Test
    func neverTighterThanTheOutputAllows() {
        let output = CallFraming.outputSize(Self.uhd, view: nil)
        let tiny = CallFraming.targetCrop(Self.uhd, output: output, faces: Rect(x: 1900, y: 1000, width: 40, height: 40))
        #expect(near(tiny.height, 864))
    }

    @Test
    func alwaysInsideThePicture() {
        let output = CallFraming.outputSize(Self.uhd, view: nil)
        let edge = CallFraming.targetCrop(Self.uhd, output: output, faces: Rect(x: 3700, y: 2000, width: 140, height: 140))
        #expect(edge.x + edge.width <= 3840 + 1e-6)
        #expect(edge.y + edge.height <= 2160 + 1e-6)
    }

    @Test
    func severalPeopleSideBySideAllFit() {
        let output = CallFraming.outputSize(Self.uhd, view: nil)
        let union = CallFraming.faceUnion([
            Rect(x: 600, y: 900, width: 250, height: 250),
            Rect(x: 2900, y: 950, width: 250, height: 250),
        ])
        let group = CallFraming.targetCrop(Self.uhd, output: output, faces: union)
        #expect(near(group.width, 3840) || group.width >= 2550 / 0.6 - 1)
    }

    @Test
    func noFacesNoUnion() {
        #expect(CallFraming.faceUnion([]) == nil)
    }

    @Test
    func theCutKeepsTheOutputsShape() {
        let tall = CallFraming.outputSize(Self.uhd, view: Self.phone)
        let cut = CallFraming.targetCrop(Self.uhd, output: tall, faces: Self.face)
        #expect(near(cut.width / cut.height, tall.width / tall.height, 0.002))
    }

    @Test
    func theLargestSquareInTheMiddle() {
        let inside = CallFraming.largestInside(Self.uhd, aspect: 1)
        #expect(inside.width == Double(2160))
        #expect(inside.x == Double(840))
    }

    // MARK: - How it moves

    /// Runs `next` every 33 ms from `from` to `to` (ms), both included where they land; the last cut.
    @discardableResult
    private func run(_ framer: inout CallFramer, from: Double, to: Double) -> Rect {
        var cut = framer.next(from)
        var t = from + 33
        while t <= to {
            cut = framer.next(t)
            t += 33
        }
        return cut
    }

    @Test
    func howItMoves() {
        let output = CallFraming.outputSize(Self.uhd, view: nil)
        var framer = CallFramer()
        framer.configure(capture: Self.uhd, output: output, follow: true)
        #expect(framer.next(0).width == Double(3840), "it starts on the whole picture")

        framer.faces([Self.face], now: 0)
        var cut = framer.next(33)
        #expect(cut.height < 2160 && cut.height > 1500, "it glides rather than jumps (\(cut.height))")
        cut = run(&framer, from: 66, to: 5_000)
        #expect(near(cut.height, 1000, 2) && near(cut.x + cut.width / 2, 1920, 2), "and settles on the face")

        var moved = Self.face
        moved.x += 20
        framer.faces([moved], now: 5_000)
        cut = run(&framer, from: 5_033, to: 8_000)
        #expect(near(cut.x + cut.width / 2, 1920, 2), "a small move is not followed")

        moved = Self.face
        moved.x += 600
        framer.faces([moved], now: 8_000)
        cut = run(&framer, from: 8_033, to: 12_000)
        #expect(near(cut.x + cut.width / 2, 2520, 2), "a real move is")

        framer.faces([moved], now: 11_800)
        framer.faces([], now: 12_000)
        cut = run(&framer, from: 12_033, to: 13_000)
        #expect(near(cut.height, 1000, 2), "a face lost for a moment holds the cut")

        framer.faces([], now: 14_000)
        cut = run(&framer, from: 14_033, to: 20_000)
        #expect(near(cut.height, 2160, 2), "gone for longer: back to the whole picture")

        framer.faces([Self.face], now: 20_000)
        framer.configure(capture: Self.uhd, output: output, follow: false)
        cut = run(&framer, from: 20_033, to: 26_000)
        #expect(near(cut.height, 2160, 2), "Center Stage off: the whole picture")

        framer.configure(capture: Self.uhd, output: CallFraming.outputSize(Self.uhd, view: Self.phone), follow: true)
        cut = framer.next(26_033)
        #expect(near(cut.width / cut.height, 886.0 / 1920.0, 0.002) && near(cut.height, 2160), "a new shape starts again from the whole picture")
        let late = framer.next(26_033 + 10_000)
        #expect(late.height <= 2160 && late.y >= 0, "a long pause between frames is not a jump past the target")
    }

    // MARK: - Our own small picture

    @Test
    func ourOwnSmallPictureCentresOnTheFaces() {
        let output = CallFraming.outputSize(Self.uhd, view: nil)
        var framer = CallFramer()
        framer.configure(capture: Self.uhd, output: output, follow: true)
        _ = framer.next(0)
        #expect(framer.focus() == CallFraming.Point(x: 0.5, y: 0.5), "no faces: the middle")
        framer.faces([Self.face], now: 0)
        run(&framer, from: 33, to: 6_000)
        let settled = framer.focus()
        #expect(near(settled.x, 0.5, 0.01) && near(settled.y, 0.42, 0.01), "on the face, a little above the middle (\(settled))")
        framer.faces([Rect(x: 3600, y: 900, width: 200, height: 200)], now: 6_000)
        run(&framer, from: 6_033, to: 12_000)
        #expect(framer.focus().x > 0.6, "a face the cut cannot centre (at the picture's edge) is off its middle (\(framer.focus().x))")
        framer.configure(capture: Self.uhd, output: output, follow: false)
        run(&framer, from: 12_033, to: 20_000)
        let off = framer.focus()
        #expect(near(off.x, 0.5, 0.01) && near(off.y, 0.5, 0.01), "Center Stage off: back to the middle")
        let fresh = CallFramer()
        #expect(fresh.focus().x == Double(0.5) && fresh.focus().y == Double(0.5), "before any frame: the middle")
    }

    @Test
    func coverOffsetPutsTheFocusInTheBoxsMiddle() {
        let tile = Size(width: 200, height: 125)
        let tall = Size(width: 886, height: 1920)
        let at = CallFraming.coverOffset(tile, picture: tall, focus: .init(x: 0.5, y: 0.35))
        #expect(near(at.x, 0) && near(at.y, 62.5 - 0.35 * 1920 * (200.0 / 886.0), 0.01), "the face's point in the tile's middle (\(at))")
        #expect(near(CallFraming.coverOffset(tile, picture: tall, focus: .init(x: 0.5, y: 0)).y, 0), "never past the top")
        #expect(
            near(CallFraming.coverOffset(tile, picture: tall, focus: .init(x: 0.5, y: 1)).y, 125 - 1920 * (200.0 / 886.0), 0.01),
            "nor past the bottom"
        )
        let same = CallFraming.coverOffset(Size(width: 108, height: 234), picture: tall, focus: .init(x: 0.9, y: 0.1))
        #expect(near(same.x, 0, 0.6) && near(same.y, 0, 0.6), "one shape: nothing to move")
        let wide = CallFraming.coverOffset(Size(width: 108, height: 164), picture: Self.fhd, focus: .init(x: 0.8, y: 0.5))
        #expect(wide.x < 0 && near(wide.y, 0) && near(wide.x, 54 - 0.8 * 1920 * (164.0 / 1080.0), 0.01), "a wide picture in a tall tile moves sideways")
        #expect(CallFraming.coverOffset(Size(width: 0, height: 0), picture: tall, focus: .middle).x == Double(0), "an empty box: nothing")
    }

    // MARK: - On the buffer (iPhone only)

    /// A 1920×1080 buffer turned by each rotation; the upright cut maps back onto it.
    @Test(arguments: [0, 90, 180, 270])
    func anUprightCutLandsOnTheBufferAsItLies(rotation: Int) {
        let upright = Rect(x: 100, y: 200, width: 300, height: 400)
        let mapped = CallFraming.bufferRect(upright, rotation: rotation, bufferWidth: 1920, bufferHeight: 1080)
        switch rotation {
        case 90:
            // Shown turned clockwise: the buffer's left column is the picture's top row.
            #expect(mapped == Rect(x: 200, y: 1080 - 400, width: 400, height: 300))
        case 180:
            #expect(mapped == Rect(x: 1920 - 400, y: 1080 - 600, width: 300, height: 400))
        case 270:
            #expect(mapped == Rect(x: 1920 - 600, y: 100, width: 400, height: 300))
        default:
            #expect(mapped == upright)
        }
    }

    /// Each rotation, checked point by point: a buffer pixel turned clockwise by the rotation lands
    /// inside the upright cut exactly when it lies inside the mapped one.
    @Test(arguments: [0, 90, 180, 270])
    func theMappingAgreesWithTurningThePicture(rotation: Int) {
        let bw = 64.0
        let bh = 36.0
        let quarter = rotation == 90 || rotation == 270
        let uw = quarter ? bh : bw
        let uh = quarter ? bw : bh
        let upright = Rect(x: 5, y: 7, width: 11, height: 13)
        let mapped = CallFraming.bufferRect(upright, rotation: rotation, bufferWidth: Int(bw), bufferHeight: Int(bh))
        for by in stride(from: 0.5, to: bh, by: 1) {
            for bx in stride(from: 0.5, to: bw, by: 1) {
                // The pixel's centre, turned clockwise by `rotation` into the upright picture.
                let (ux, uy): (Double, Double) = switch rotation {
                case 90: (bh - by, bx)
                case 180: (bw - bx, bh - by)
                case 270: (by, bw - bx)
                default: (bx, by)
                }
                #expect(ux >= 0 && ux <= uw && uy >= 0 && uy <= uh)
                let inUpright = ux > upright.x && ux < upright.x + upright.width && uy > upright.y && uy < upright.y + upright.height
                let inMapped = bx > mapped.x && bx < mapped.x + mapped.width && by > mapped.y && by < mapped.y + mapped.height
                #expect(inUpright == inMapped, "rotation \(rotation) at \(bx),\(by)")
            }
        }
    }

    @Test
    func aCropIsEvenAndInsideTheBuffer() {
        let crop = CallFraming.pixelCrop(
            Rect(x: 1801.3, y: -3, width: 301.4, height: 1201),
            bufferWidth: 1920,
            bufferHeight: 1080,
            outputWidth: 498,
            outputHeight: 1080
        )
        #expect(crop.width == 302)
        #expect(crop.height == 1080)
        #expect(crop.x == 1618)
        #expect(crop.y == 0)
        #expect(crop.x % 2 == 0 && crop.y % 2 == 0)
    }

    @Test
    func aCropWithinTwoPixelsOfTheOutputTakesItsSize() {
        let crop = CallFraming.pixelCrop(
            Rect(x: 710.85, y: 0, width: 498.3, height: 1080),
            bufferWidth: 1920,
            bufferHeight: 1080,
            outputWidth: 498,
            outputHeight: 1080
        )
        #expect(crop == CallFraming.PixelCrop(x: 710, y: 0, width: 498, height: 1080))
        let zoomed = CallFraming.pixelCrop(
            Rect(x: 600, y: 200, width: 400, height: 868),
            bufferWidth: 1920,
            bufferHeight: 1080,
            outputWidth: 498,
            outputHeight: 1080
        )
        #expect(zoomed == CallFraming.PixelCrop(x: 600, y: 200, width: 400, height: 868), "a zoomed cut keeps its own size")
    }

    @Test
    func visionReadsTheBufferTheWayWebRTCTurnsIt() {
        #expect(CameraFramer.orientation(0) == .up)
        #expect(CameraFramer.orientation(90) == .right)
        #expect(CameraFramer.orientation(180) == .down)
        #expect(CameraFramer.orientation(270) == .left)
    }

    @Test
    func aViewOnTheWireIsAShapeHere() {
        #expect(CallViewSize(w: 1179, h: 2556)?.framingSize == Self.phone)
    }
}
