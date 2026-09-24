import CoreGraphics
import Testing
@testable import shroud

/// Where the long-press menu lifts a bubble from. The bubble's own reported frame can be a
/// stale one from the chat's opening transition (half a screen off); the row's frame comes
/// fresh from the press and decides whether to trust it.
@MainActor
struct MessageMenuSourceFrameTests {
    private let row = CGRect(x: 16, y: 731, width: 370, height: 33)

    @Test func aBubbleInsideItsRowIsUsedAsItIs() {
        let bubble = CGRect(x: 16, y: 731, width: 289.3, height: 33)
        #expect(ConversationView.menuSourceFrame(bubble: bubble, row: row, isMine: false) == bubble)
        let mine = CGRect(x: 96.7, y: 731, width: 289.3, height: 33)
        #expect(ConversationView.menuSourceFrame(bubble: mine, row: row, isMine: true) == mine)
    }

    @Test func aHairOfRoundingStillCounts() {
        let bubble = CGRect(x: 15.5, y: 730.5, width: 290, height: 34)
        #expect(ConversationView.menuSourceFrame(bubble: bubble, row: row, isMine: false) == bubble)
    }

    @Test func aStaleFrameIsPlacedAtTheRowsLeadingEdgeForTheirs() {
        // What the transition left behind: the right size, half a screen away.
        let stale = CGRect(x: -185, y: 328, width: 289.3, height: 33)
        let source = ConversationView.menuSourceFrame(bubble: stale, row: row, isMine: false)
        #expect(source == CGRect(x: 16, y: 731, width: 289.3, height: 33))
    }

    @Test func aStaleFrameIsPlacedAtTheRowsTrailingEdgeForOurs() {
        let stale = CGRect(x: -185, y: 328, width: 200, height: 33)
        let source = ConversationView.menuSourceFrame(bubble: stale, row: row, isMine: true)
        #expect(source == CGRect(x: 386 - 200, y: 731, width: 200, height: 33))
    }

    @Test func aStaleFrameNeverOutgrowsTheRow() {
        let stale = CGRect(x: -185, y: 328, width: 500, height: 80)
        let source = ConversationView.menuSourceFrame(bubble: stale, row: row, isMine: false)
        #expect(source == row)
    }

    @Test func noBubbleFrameMeansTheRow() {
        #expect(ConversationView.menuSourceFrame(bubble: nil, row: row, isMine: false) == row)
    }
}
