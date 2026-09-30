import CoreGraphics
import SwiftUI
import Testing
@testable import shroud

/// The long-press menu follows Telegram: reaction bar on the bubble, action card under it, and
/// the bubble itself moves when that stack would not fit where it is. The previous layout kept
/// the bubble still and opened the card *above* it, pushing the reactions far away from the
/// message near the composer — the "menu opens in the wrong place" report.
@MainActor
struct MessageMenuLayoutTests {
    /// iPhone 17 Pro: 402 × 874 pt, 62 pt status bar, 34 pt home indicator.
    private let screen = CGSize(width: 402, height: 874)
    private let safeArea = EdgeInsets(top: 62, leading: 0, bottom: 34, trailing: 0)
    private let reaction = CGSize(width: 334, height: 50)
    private let peerCard = CGSize(width: 250, height: 6 * 44 + 5)
    private let ownCard = CGSize(width: 250, height: 7 * 44 + 6)

    /// Highest a reaction bar may sit: 8 pt under the status bar.
    private var reactionTop: CGFloat { 62 + 8 }
    /// Lowest a card may reach: 10 pt above the home indicator.
    private var cardBottom: CGFloat { 874 - 34 - 10 }

    private func metrics(card: CGSize) -> MessageMenuLayout.Metrics {
        MessageMenuLayout.Metrics(reactionSize: reaction, cardSize: card)
    }

    private func layout(
        _ source: CGRect,
        isMine: Bool = false,
        card: CGSize? = nil,
        container: CGSize? = nil,
        safeArea: EdgeInsets? = nil
    ) -> (plan: MessageMenuLayout.Plan, reactions: CGRect, card: CGRect) {
        let metrics = metrics(card: card ?? (isMine ? ownCard : peerCard))
        let plan = MessageMenuLayout.plan(
            source: source,
            container: container ?? screen,
            safeArea: safeArea ?? self.safeArea,
            metrics: metrics
        )
        // Where it is drawn when the menu opens (a scrolling stack opens at its bottom).
        let heroOnScreen = plan.hero.offsetBy(dx: 0, dy: -plan.initialOffset)
        let chrome = MessageMenuLayout.chrome(hero: heroOnScreen, isMine: isMine, plan: plan, metrics: metrics)
        return (plan, chrome.reactions, chrome.card)
    }

    @Test func messageWithRoomStaysWhereItIs() {
        let source = CGRect(x: 16, y: 300, width: 200, height: 40)
        let result = layout(source)
        #expect(result.plan.hero == source)
        #expect(!result.plan.scrolls)
        #expect(result.reactions.maxY == source.minY - 10)
        #expect(result.card.minY == source.maxY + 10)
        #expect(result.reactions.minX == 16)
        #expect(result.card.minX == 16)
    }

    @Test func messageAboveTheComposerLiftsUntilItsCardFitsBelow() {
        let source = CGRect(x: 16, y: 760, width: 180, height: 40)
        let result = layout(source)
        #expect(result.plan.hero.minX == 16)
        #expect(result.plan.hero.size == source.size)
        #expect(result.card.minY == result.plan.hero.maxY + 10)
        #expect(result.card.maxY == cardBottom)
        #expect(result.reactions.maxY == result.plan.hero.minY - 10, "the reactions ride on the bubble")
        #expect(!result.plan.scrolls)
    }

    @Test func ownMessageLiftsAndLinesItsChromeUpOnTheRight() {
        let source = CGRect(x: 266, y: 780, width: 120, height: 40)
        let result = layout(source, isMine: true)
        #expect(result.plan.hero.minY == cardBottom - ownCard.height - 10 - 40)
        #expect(result.card.maxX == source.maxX)
        #expect(result.reactions.maxX == source.maxX)
        #expect(result.card.maxY == cardBottom)
    }

    @Test func messageUnderTheHeaderDropsJustEnoughForTheReactionBar() {
        let source = CGRect(x: 16, y: 90, width: 200, height: 40)
        let result = layout(source)
        #expect(result.plan.hero.minY == reactionTop + reaction.height + 10)
        #expect(result.reactions.minY == reactionTop)
        #expect(result.card.minY == result.plan.hero.maxY + 10)
    }

    @Test func theKeyboardIsTheBottomWhileItIsUp() {
        var keyboardUp = safeArea
        keyboardUp.bottom = 336
        let source = CGRect(x: 16, y: 480, width: 200, height: 40)
        let result = layout(source, safeArea: keyboardUp)
        #expect(result.card.maxY == CGFloat(874 - 336 - 10))
        #expect(result.reactions.maxY == result.plan.hero.minY - 10)
        #expect(result.reactions.minY >= reactionTop)
    }

    @Test func messageTooTallForItsCardScrollsAndOpensAtTheCard() {
        let source = CGRect(x: 16, y: 100, width: 300, height: 700)
        let result = layout(source)
        #expect(result.plan.scrolls)
        // At the top of the stack the bubble sits right under the reaction bar …
        #expect(result.plan.hero.minY == reactionTop + reaction.height + 10)
        // … and the stack opens scrolled to its bottom, with the card fully on screen.
        #expect(result.card.maxY == cardBottom)
        #expect(result.card.minY == result.plan.hero.maxY - result.plan.initialOffset + 10)
        // The bubble's top is scrolled away; the reaction bar stays pinned over it.
        #expect(result.reactions.minY == reactionTop)
    }

    @Test func chromeNeverLeavesTheScreen() {
        // Lined up with an incoming bubble that starts mid-screen, the bar would run off the
        // right edge; it stops 12 pt short instead, while the card keeps the bubble's edge.
        let mid = layout(CGRect(x: 100, y: 400, width: 120, height: 40))
        #expect(mid.reactions.maxX == CGFloat(402 - 12))
        #expect(mid.card.minX == 100)
        // A bubble flush with the left edge still leaves the chrome 12 pt of margin.
        let flush = layout(CGRect(x: 0, y: 400, width: 120, height: 40))
        #expect(flush.card.minX == 12)
        #expect(flush.reactions.minX == 12)
    }

    @Test func landscapeScrollsRatherThanOverlapping() {
        let landscape = CGSize(width: 874, height: 402)
        let insets = EdgeInsets(top: 0, leading: 62, bottom: 21, trailing: 62)
        let result = layout(
            CGRect(x: 80, y: 200, width: 200, height: 40),
            container: landscape,
            safeArea: insets
        )
        #expect(result.plan.scrolls)
        #expect(result.card.maxY == CGFloat(402 - 21 - 10))
        #expect(result.card.minY >= result.plan.hero.maxY - result.plan.initialOffset + 10)
    }

    @Test func cardHeightCountsItsRows() {
        let all = MessageMenuAction.primary()
        let withLink = MessageMenuAction.primary(hasLink: true)
        #expect(MessageContextMenuCard.height(receipt: nil, actions: all) == CGFloat(6 * 44 + 5))
        #expect(MessageContextMenuCard.height(receipt: .read, actions: all) == CGFloat(7 * 44 + 6))
        #expect(MessageContextMenuCard.height(receipt: .delivered, actions: withLink) == CGFloat(8 * 44 + 7))
        // A photo still sending: no receipt row, no Reply, no Copy of its "Photo" stand-in.
        let sendingPhoto = MessageMenuAction.primary(canReply: false, canCopy: false)
        #expect(MessageContextMenuCard.height(receipt: .sending, actions: sendingPhoto) == CGFloat(4 * 44 + 3))
    }

    /// The muted row says what the ticks say, and nothing while the server hasn't confirmed the
    /// message — "read" on every own message was a false read receipt.
    @Test func receiptRowFollowsTheTicks() {
        #expect(MessageContextMenuCard.receiptTitle(for: nil) == nil)
        #expect(MessageContextMenuCard.receiptTitle(for: .failed) == nil)
        #expect(MessageContextMenuCard.receiptTitle(for: .sending) == nil)
        #expect(MessageContextMenuCard.receiptTitle(for: .sent) == "sent")
        #expect(MessageContextMenuCard.receiptTitle(for: .delivered) == "delivered")
        #expect(MessageContextMenuCard.receiptTitle(for: .read) == "read")
    }

    /// Only what the message can do, in the design's order.
    @Test func cardOffersOnlyWhatTheMessageCanDo() {
        #expect(MessageMenuAction.primary() == [.reply, .copy, .pin, .forward, .delete])
        #expect(MessageMenuAction.primary(hasLink: true) == [.reply, .copy, .copyLink, .pin, .forward, .delete])
        #expect(MessageMenuAction.primary(canReply: false, canCopy: false, hasLink: true) == [.copyLink, .pin, .forward, .delete])
    }
}
