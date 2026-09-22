import CoreGraphics
import Testing
@testable import shroud

/// The long-press menu used to centre the bubble on the screen. A message near the composer
/// then travelled a few hundred points, which read as the whole thread scrolling.
struct MessageMenuLayoutTests {
    private let screen = CGSize(width: 390, height: 844)
    private let reaction: CGFloat = 50
    /// Mine: 7 rows and the hairline under each of the first six.
    private let menu: CGFloat = 7 * 44 + 6
    private let peerMenu: CGFloat = 6 * 44 + 5

    @Test func messageInTheMiddleStaysAndOpensDownward() {
        let source = CGRect(x: 200, y: 300, width: 160, height: 44)
        let decision = MessageMenuLayout.decide(
            source: source,
            container: screen,
            menuHeight: peerMenu,
            reactionHeight: reaction
        )
        #expect(decision.placement == .below)
        #expect(decision.hero == source)

        let chrome = MessageMenuLayout.chrome(
            hero: decision.hero,
            placement: decision.placement,
            containerHeight: screen.height,
            menuHeight: peerMenu,
            reactionHeight: reaction
        )
        #expect(chrome.menuY == source.maxY + 10)
        #expect(chrome.reactionY == source.minY - 10 - reaction)
    }

    @Test func messageNearTheComposerOpensUpwardWithoutMoving() {
        let source = CGRect(x: 40, y: 700, width: 200, height: 52)
        let decision = MessageMenuLayout.decide(
            source: source,
            container: screen,
            menuHeight: menu,
            reactionHeight: reaction
        )
        #expect(decision.placement == .above)
        #expect(decision.hero.minY == 700)

        let chrome = MessageMenuLayout.chrome(
            hero: decision.hero,
            placement: decision.placement,
            containerHeight: screen.height,
            menuHeight: menu,
            reactionHeight: reaction
        )
        #expect(chrome.menuY == 700 - 10 - menu)
        #expect(chrome.reactionY == chrome.menuY - 10 - reaction)
        #expect(chrome.reactionY >= 56)
    }

    @Test func upwardMenuDoesNotDragTheBubbleToMakeRoomForReactions() {
        // The card fits above the bubble; the reaction bar does not. Dragging the
        // bubble up so the card can open below was the "thread scrolled" bug.
        let source = CGRect(x: 30, y: 400, width: 200, height: 280)
        let decision = MessageMenuLayout.decide(
            source: source,
            container: screen,
            menuHeight: menu,
            reactionHeight: reaction
        )
        #expect(decision.placement == .above)
        #expect(decision.hero == source)

        let chrome = MessageMenuLayout.chrome(
            hero: decision.hero,
            placement: decision.placement,
            containerHeight: screen.height,
            menuHeight: menu,
            reactionHeight: reaction
        )
        #expect(chrome.menuY >= 56)
        #expect(chrome.menuY + menu <= 844 - 48)
        let reactionAboveMenu = chrome.reactionY + reaction <= chrome.menuY
        let reactionBelowBubble = chrome.reactionY >= source.maxY
        #expect(reactionAboveMenu || reactionBelowBubble)
    }

    @Test func bubbleExtendingPastTheBottomPadIsNotYankedUpward() {
        let source = CGRect(x: 40, y: 760, width: 180, height: 50)
        let decision = MessageMenuLayout.decide(
            source: source,
            container: screen,
            menuHeight: menu,
            reactionHeight: reaction
        )
        #expect(decision.placement == .above)
        #expect(decision.hero.minY == 760)
    }

    @Test func messageUnderTheHeaderMovesDownOnlyEnoughForTheReactionBar() {
        let source = CGRect(x: 20, y: 60, width: 180, height: 40)
        let decision = MessageMenuLayout.decide(
            source: source,
            container: screen,
            menuHeight: peerMenu,
            reactionHeight: reaction
        )
        #expect(decision.placement == .below)
        #expect(decision.hero.minY == 56 + reaction + 10)

        let chrome = MessageMenuLayout.chrome(
            hero: decision.hero,
            placement: decision.placement,
            containerHeight: screen.height,
            menuHeight: peerMenu,
            reactionHeight: reaction
        )
        #expect(chrome.reactionY >= 56)
        #expect(chrome.menuY + peerMenu <= 844 - 48)
    }

    @Test func tallMessageStaysPutAndKeepsTheCardOnScreen() {
        let source = CGRect(x: 16, y: 80, width: 250, height: 640)
        let decision = MessageMenuLayout.decide(
            source: source,
            container: screen,
            menuHeight: menu,
            reactionHeight: reaction
        )
        #expect(decision.hero.minY == 80)

        let chrome = MessageMenuLayout.chrome(
            hero: decision.hero,
            placement: decision.placement,
            containerHeight: screen.height,
            menuHeight: menu,
            reactionHeight: reaction
        )
        #expect(chrome.menuY >= 56)
        #expect(chrome.menuY + menu <= 844 - 48)
        #expect(chrome.reactionY >= 56)
        #expect(chrome.reactionY + reaction <= 844 - 48)
    }
}
