import Foundation
import Testing
@testable import shroud

/// The jump-to-latest badge counts what the other side sent after the reader left the bottom.
@MainActor
struct JumpToLatestStateTests {
    private let peer = UUID()
    private let me = UUID()
    private let start = Date(timeIntervalSince1970: 1_800_000_000)

    private func message(
        at seconds: TimeInterval,
        mine: Bool = false,
        deleted: Bool = false
    ) -> MessagingController.ChatMessage {
        MessagingController.ChatMessage(
            id: UUID(),
            peerUserID: peer,
            senderUserID: mine ? me : peer,
            text: "hello",
            createdAt: start.addingTimeInterval(seconds),
            isMine: mine,
            deleted: deleted
        )
    }

    @Test func atTheBottomNothingIsCounted() {
        let state = JumpToLatestState()
        let thread = [message(at: 0), message(at: 1)]
        #expect(!state.isAway)
        #expect(state.unseenCount(in: thread) == 0)
    }

    @Test func messagesArrivingBelowTheReaderAreCounted() {
        let state = JumpToLatestState()
        var thread = [message(at: 0), message(at: 1)]
        state.setAway(true, newest: thread.last)
        #expect(state.unseenCount(in: thread) == 0)

        thread += [message(at: 2), message(at: 3)]
        #expect(state.isAway)
        #expect(state.unseenCount(in: thread) == 2)
    }

    @Test func ourOwnMessagesAndTombstonesDontCount() {
        let state = JumpToLatestState()
        var thread = [message(at: 0)]
        state.setAway(true, newest: thread.last)
        thread += [message(at: 1, mine: true), message(at: 2, deleted: true), message(at: 3)]
        #expect(state.unseenCount(in: thread) == 1)
    }

    @Test func anArrivalDeletedForEveryoneDropsOffTheCount() {
        let state = JumpToLatestState()
        let first = message(at: 0)
        state.setAway(true, newest: first)
        let arrival = message(at: 1)
        #expect(state.unseenCount(in: [first, arrival]) == 1)

        let tombstone = MessagingController.ChatMessage(
            id: arrival.id,
            peerUserID: peer,
            senderUserID: peer,
            text: "",
            createdAt: arrival.createdAt,
            isMine: false,
            deleted: true
        )
        #expect(state.unseenCount(in: [first, tombstone]) == 0)
    }

    @Test func aDeletedAnchorIsFoundAgainByItsDate() {
        let state = JumpToLatestState()
        let older = message(at: 0)
        let newest = message(at: 1)
        state.setAway(true, newest: newest)
        // The message they left on was deleted just for us; one older and two newer remain.
        #expect(state.unseenCount(in: [older, message(at: 2), message(at: 3)]) == 2)
    }

    @Test func gettingBackToTheBottomResetsTheCount() {
        let state = JumpToLatestState()
        var thread = [message(at: 0)]
        state.setAway(true, newest: thread.last)
        thread.append(message(at: 1))
        #expect(state.unseenCount(in: thread) == 1)

        state.setAway(false, newest: thread.last)
        #expect(!state.isAway)
        #expect(state.unseenCount(in: thread) == 0)

        // Leaving again counts from the newest message at that moment.
        state.setAway(true, newest: thread.last)
        #expect(state.unseenCount(in: thread) == 0)
        thread.append(message(at: 2))
        #expect(state.unseenCount(in: thread) == 1)
    }

    @Test func leavingAgainWhileAwayKeepsTheFirstAnchor() {
        let state = JumpToLatestState()
        var thread = [message(at: 0)]
        state.setAway(true, newest: thread.last)
        thread.append(message(at: 1))
        // Callbacks repeat "away" on every scroll frame; the anchor must not move with them.
        state.setAway(true, newest: thread.last)
        #expect(state.unseenCount(in: thread) == 1)
    }

    @Test func leavingAnEmptyThreadCountsEverythingFromThem() {
        let state = JumpToLatestState()
        state.setAway(true, newest: nil)
        #expect(state.unseenCount(in: [message(at: 0), message(at: 1, mine: true)]) == 1)
    }
}
