import CoreGraphics
import Foundation
import Testing
@testable import shroud

/// The area we tell them shows their camera (`media_state` `view`), when a new one goes out, and
/// how Apple's Center Stage switch and ours meet (docs/calls.md, "Framing and Center Stage").
@MainActor
struct CallViewAreaTests {
    private static let phone = CGSize(width: 393, height: 852)

    // MARK: - The area that shows their camera

    @Test
    func theWholeCallScreenInDevicePixels() {
        let area = InCallOverlay.theirCameraArea(callScreen: Self.phone, theirScreen: false, scale: 3)
        #expect(area.width == 1179 && area.height == 2556)
    }

    @Test
    func overTheirScreenTheirCamerasTile() {
        let area = InCallOverlay.theirCameraArea(callScreen: Self.phone, theirScreen: true, scale: 3)
        #expect(area.width == Int(InCallOverlay.tileSize.width * 3))
        #expect(area.height == Int(InCallOverlay.tileSize.height * 3))
        // A shape far enough from the screen's to go out.
        let screen = CallViewSize(w: 1179, h: 2556)
        let tile = CallViewSize(w: area.width, h: area.height)
        #expect(CallFraming.shapeChanged(screen?.framingSize, tile?.framingSize))
    }

    @Test
    func anUnmeasuredScreenIsNoArea() {
        let area = InCallOverlay.theirCameraArea(callScreen: .zero, theirScreen: false, scale: 3)
        // `setVideoArea` keeps the last area for it: no `media_state` without a view.
        #expect(CallViewSize(w: area.width, h: area.height) == nil)
    }

    // MARK: - When a new view goes out

    private static let upright = CallViewSize(w: 1179, h: 2556)
    private static let sideways = CallViewSize(w: 2556, h: 1179)

    @Test
    func aNewShapeGoesOutAtOnceWhenNothingWasSentLately() {
        #expect(CallController.viewSendWait(sent: Self.upright, area: Self.sideways, sinceLastMedia: nil) == .zero)
        #expect(CallController.viewSendWait(sent: Self.upright, area: Self.sideways, sinceLastMedia: .milliseconds(800)) == .zero)
    }

    @Test
    func aNewShapeWaitsUntil500msAfterTheLastMediaState() {
        let wait = CallController.viewSendWait(sent: Self.upright, area: Self.sideways, sinceLastMedia: .milliseconds(200))
        #expect(wait == .milliseconds(300))
    }

    @Test
    func aShapeWithinThreePercentDoesNotGoOut() {
        #expect(CallController.viewSendWait(sent: Self.upright, area: CallViewSize(w: 1180, h: 2550), sinceLastMedia: nil) == nil)
        #expect(CallController.viewSendWait(sent: Self.upright, area: Self.upright, sinceLastMedia: nil) == nil)
    }

    @Test
    func theFirstMeasuredViewIsANewShape() {
        // None sent yet (an area measured after the first `media_state`): it goes out.
        #expect(CallController.viewSendWait(sent: nil, area: Self.upright, sinceLastMedia: .seconds(2)) == .zero)
    }

    // MARK: - Apple's Center Stage switch and ours

    @Test
    func beforeAnyCallCameraLetGoOursIsTaken() {
        #expect(CallCenterStage.agreed(ours: true, system: false, systemLeft: nil))
        #expect(!CallCenterStage.agreed(ours: false, system: true, systemLeft: nil))
    }

    @Test
    func aControlCenterChangeBetweenCallsWins() {
        #expect(!CallCenterStage.agreed(ours: true, system: false, systemLeft: true))
        #expect(CallCenterStage.agreed(ours: false, system: true, systemLeft: false))
    }

    @Test
    func appleLeftWhereACallHadItOursIsTaken() {
        #expect(CallCenterStage.agreed(ours: true, system: false, systemLeft: false))
        #expect(!CallCenterStage.agreed(ours: false, system: true, systemLeft: true))
    }
}
