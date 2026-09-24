import CoreGraphics
import SwiftUI
import Testing
@testable import shroud

/// The reaction bar grows in place into the full set: down from its own top edge, kept under
/// the status bar and above the home indicator — or the keyboard, once the search is typing.
@MainActor
struct ReactionPanelLayoutTests {
    /// iPhone 17 Pro: 402 × 874 pt, 62 pt status bar, 34 pt home indicator.
    private let screen = CGSize(width: 402, height: 874)
    private let safeArea = EdgeInsets(top: 62, leading: 0, bottom: 34, trailing: 0)
    private let bar = CGRect(x: 12, y: 300, width: MessageReactionBar.barWidth, height: MessageReactionBar.barHeight)

    private func frame(expanded: Bool, bar: CGRect? = nil, safeArea: EdgeInsets? = nil) -> CGRect {
        MessageReactionPanel.frame(
            expanded: expanded,
            bar: bar ?? self.bar,
            container: screen,
            safeArea: safeArea ?? self.safeArea
        )
    }

    @Test func collapsedIsTheBar() {
        #expect(frame(expanded: false) == bar)
    }

    @Test func expandedGrowsDownFromTheBarsTopEdge() {
        let panel = frame(expanded: true)
        #expect(panel.minX == bar.minX)
        #expect(panel.width == bar.width)
        #expect(panel.minY == bar.minY)
        #expect(panel.height == MessageReactionGrid.height)
        #expect(panel.height > bar.height)
    }

    @Test func thePanelIsAsTallAsTheRowsASearchLeaves() {
        let one = MessageReactionGrid.height(rows: 1)
        let two = MessageReactionGrid.height(rows: 2)
        #expect(two - one == MessageReactionGrid.cell)
        // Nothing matching still shows the "no matches" line, one row tall.
        #expect(MessageReactionGrid.height(rows: 0) == one)
        // Past five and a half rows the grid scrolls instead of growing.
        #expect(MessageReactionGrid.height(rows: 6) == MessageReactionGrid.height)
        #expect(MessageReactionGrid.height(rows: 10) == MessageReactionGrid.height)
        #expect(MessageReactionGrid.rows(for: 0) == 0)
        #expect(MessageReactionGrid.rows(for: 8) == 1)
        #expect(MessageReactionGrid.rows(for: 9) == 2)
        // The whole set is dozens of rows: the panel shows five and a half and scrolls the rest.
        let count = MessageReactionBar.expanded.count
        #expect(MessageReactionGrid.rows(for: count) == (count + 7) / 8)
        #expect(MessageReactionGrid.rows(for: count) > 40)

        let short = frame(expanded: true).size.height
        let oneRow = MessageReactionPanel.frame(expanded: true, bar: bar, container: screen, safeArea: safeArea, height: one)
        #expect(oneRow.height == one)
        #expect(oneRow.height < short)
        #expect(oneRow.minY == bar.minY)
    }

    @Test func theGridFitsEightColumnsInTheBarsWidth() {
        let columns = CGFloat(MessageReactionGrid.columns) * MessageReactionGrid.cell
        #expect(columns + 2 * MessageReactionGrid.sidePadding == MessageReactionBar.barWidth)
        #expect(MessageReactionGrid.sidePadding >= 0)
    }

    @Test func aBarNearTheBottomLiftsThePanelAboveTheHomeIndicator() {
        let low = CGRect(x: 12, y: 700, width: bar.width, height: bar.height)
        let panel = frame(expanded: true, bar: low)
        #expect(panel.maxY == CGFloat(874 - 34 - 10))
        #expect(panel.height == MessageReactionGrid.height)
    }

    @Test func aBarUnderTheStatusBarStaysUnderIt() {
        let high = CGRect(x: 12, y: 40, width: bar.width, height: bar.height)
        let panel = frame(expanded: true, bar: high)
        #expect(panel.minY == CGFloat(62 + 8))
    }

    @Test func theKeyboardLiftsAnOpenPanelAboveItself() {
        // The keyboard adds itself to the bottom inset of the overlay's safe area.
        let typing = EdgeInsets(top: 62, leading: 0, bottom: 34 + 336, trailing: 0)
        let low = CGRect(x: 12, y: 500, width: bar.width, height: bar.height)
        let panel = frame(expanded: true, bar: low, safeArea: typing)
        #expect(panel.maxY == 874 - typing.bottom - 10)
        #expect(panel.height == MessageReactionGrid.height)
    }

    @Test func aScreenTooShortForThePanelShrinksItInstead() {
        let short = MessageReactionPanel.frame(
            expanded: true,
            bar: CGRect(x: 12, y: 60, width: bar.width, height: bar.height),
            container: CGSize(width: 874, height: 300),
            safeArea: EdgeInsets(top: 0, leading: 62, bottom: 21, trailing: 62)
        )
        #expect(short.height == CGFloat(300 - 21 - 16))
        #expect(short.minY == 8)
    }
}
