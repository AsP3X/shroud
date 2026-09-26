import CoreGraphics
import SwiftUI
import Testing
@testable import shroud

/// The call screen's face stays in the middle of the stage. On a voice call, and while only our
/// camera is on, the name block hangs under it. Their picture puts it in the top-leading corner.
/// On the way it rises and slides together, one straight glide.
@MainActor
struct CallStageLayoutTests {
    /// iPhone 17 Pro in portrait: the safe area above the controls (874 pt less the 62 pt status
    /// bar, the 34 pt home indicator, the 86 pt control row with its 48 pt padding, and 28 pt).
    private let stage = CGRect(x: 0, y: 0, width: 402, height: 616)
    private let face = CGSize(width: 104, height: 104)
    /// Name, clock and speaking meter.
    private let block = CGSize(width: 150, height: 93)

    private func frames(
        _ progress: CGFloat,
        stage: CGRect? = nil,
        block: CGSize? = nil
    ) -> (face: CGRect, block: CGRect) {
        CallStageLayout.frames(stage: stage ?? self.stage, face: face, block: block ?? self.block, progress: progress)
    }

    @Test func theNameMovesOnlyForTheirCamera() {
        #expect(!InCallOverlay.nameBelongsInCorner(remotePicture: false))
        #expect(InCallOverlay.nameBelongsInCorner(remotePicture: true))
    }

    @Test func theFaceSitsInTheMiddleAndTheBlockHangsUnderIt() {
        let frames = frames(0)
        #expect(frames.face.midX == stage.midX)
        #expect(frames.face.midY == stage.midY)
        #expect(frames.block.midX == stage.midX)
        #expect(frames.block.minY - frames.face.maxY == CallStageLayout.gap)
    }

    @Test func dockedTheBlockSitsInTheCornerAndTheFaceStaysInTheMiddle() {
        let frames = frames(1)
        #expect(frames.block.origin == CGPoint(x: 20, y: 12))
        #expect(frames.face.midX == stage.midX)
        #expect(frames.face.midY == stage.midY)
    }

    @Test func onlyTheBlockMoves() {
        let face = frames(0).face
        for progress: CGFloat in [0.1, 0.5, 0.9, 1] {
            #expect(frames(progress).face == face)
        }
        // Nor does the face follow the block's height (the meter coming and going).
        #expect(frames(0, block: CGSize(width: 150, height: 55)).face == face)
    }

    @Test func nothingChangesSizeOnTheWay() {
        for progress: CGFloat in [0, 0.25, 0.5, 0.75, 1] {
            let frames = frames(progress)
            #expect(frames.face.size == face)
            #expect(frames.block.size == block)
        }
    }

    @Test func theBlockRisesAndSlidesTogether() {
        let start = frames(0).block.origin
        let end = frames(1).block.origin
        for progress: CGFloat in [0.2, 0.4, 0.5, 0.6, 0.8] {
            let at = frames(progress).block.origin
            let across = (at.x - start.x) / (end.x - start.x)
            let up = (at.y - start.y) / (end.y - start.y)
            #expect(abs(across - progress) < 0.001)
            #expect(abs(up - progress) < 0.001)
        }
    }

    @Test func aShortStageLiftsTheFaceJustEnoughForTheBlockToFitUnderIt() {
        // iPhone 17 Pro in landscape: 219 pt above the controls.
        let short = CGRect(x: 0, y: 0, width: 750, height: 219)
        let small = CGSize(width: 150, height: 55)
        let frames = frames(0, stage: short, block: small)
        #expect(frames.block.maxY == short.maxY)
        #expect(frames.face.minY < short.midY - face.height / 2)
        #expect(frames.face.minY >= short.minY)
    }

    @Test func theBlockHasOneWidthInBothPlacesAndStopsShortOfOurPicture() {
        let width = CallStageLayout.blockWidth(stage: stage.width)
        let picture = InCallOverlay.selfViewSize
        let inset = InCallOverlay.selfViewInsets
        #expect(CallStageLayout.corner.y == inset.top)
        #expect(CallStageLayout.corner.x + width + 12 + picture.width + inset.trailing == stage.width)
        // Room for a name of about fourteen characters at 26 pt on this phone; a longer one is
        // fitted to this width under the face already, so nothing changes when the block moves.
        #expect(width == CGFloat(246))
        #expect(width <= stage.width - CallStageLayout.margin * 2)
    }

    @Test func aWideStageCapsTheBlock() {
        // Landscape or iPad: the block never runs across the screen.
        #expect(CallStageLayout.blockWidth(stage: 750) == CallStageLayout.cornerMaxWidth)
        #expect(CallStageLayout.blockWidth(stage: 100) == 0)
    }

    @Test func aShortStageMovesTheFaceBesideAWideDockedBlock() {
        // iPhone 17 Pro in landscape: 750 × 219 pt above the controls, and a long name.
        let short = CGRect(x: 0, y: 0, width: 750, height: 219)
        let wide = CGSize(width: 320, height: 130)
        let frames = frames(1, stage: short, block: wide)
        #expect(!frames.face.intersects(frames.block))
        #expect(frames.face.minX == frames.block.maxX + CallStageLayout.gap)
        #expect(frames.face.midY == short.midY.rounded())
    }

    @Test func withNoRoomBesideItTheFaceGoesBelowTheDockedBlock() {
        let narrow = CGRect(x: 0, y: 0, width: 402, height: 219)
        // Too wide to leave the face room beside it.
        let tall = CGSize(width: 300, height: 150)
        let frames = frames(1, stage: narrow, block: tall)
        #expect(!frames.face.intersects(frames.block))
        #expect(frames.face.minY == frames.block.maxY + CallStageLayout.gap)
    }

    @Test func aPairTallerThanTheStageStartsAtItsTop() {
        let short = CGRect(x: 0, y: 40, width: 750, height: 180)
        #expect(frames(0, stage: short).face.minY == short.minY)
    }

    @Test func aSpringPastTheEndHoldsTheCorner() {
        let start = frames(0)
        let end = frames(1)
        let past = frames(1.2)
        let before = frames(-0.2)
        #expect(past.block.origin == end.block.origin)
        #expect(past.face == end.face)
        #expect(before.block.origin == start.block.origin)
        #expect(before.face == start.face)
    }

    @Test func bothEndsAreOnWholePoints() {
        let odd = CGSize(width: 151.3, height: 92.7)
        let stage = CGRect(x: 0.5, y: 0, width: 401, height: 615)
        for progress: CGFloat in [0, 1] {
            let frames = frames(progress, stage: stage, block: odd)
            for origin in [frames.face.origin, frames.block.origin] {
                #expect(origin.x == origin.x.rounded())
                #expect(origin.y == origin.y.rounded())
            }
        }
    }
}
