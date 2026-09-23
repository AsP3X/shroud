import Foundation

/// One user's reaction on a message, as the thread holds it.
///
/// Human: A removal stays as an entry with no emoji, so a late or replayed older change (a
/// delayed WebSocket event, a stale page) cannot bring the reaction back.
/// Agent: `seq` is the server's change cursor; the highest one per user wins. `pending` marks our
/// own change before the server confirmed it — it keeps the previous `seq` and is not persisted,
/// so after a relaunch it is judged like any other old entry and the next page corrects it.
nonisolated struct MessageReaction: Codable, Equatable, Hashable, Sendable {
    let userID: UUID
    /// Nil when the user removed their reaction.
    let emoji: String?
    let seq: Int64
    var pending: Bool = false

    enum CodingKeys: String, CodingKey {
        case userID, emoji, seq
    }

    var isLive: Bool { emoji != nil }
}

/// A chip under a bubble: one emoji and who picked it.
nonisolated struct ReactionChip: Equatable, Hashable, Sendable, Identifiable {
    let emoji: String
    /// In the order they reacted.
    let userIDs: [UUID]
    let includesMe: Bool

    var id: String { emoji }
}

/// Pure merge rules for reactions (server `seq` is last-write-wins per user).
nonisolated enum ReactionMerge {
    /// Applies one change (WebSocket event, catch-up row, server ack). Returns nil when it
    /// changes nothing, so callers can skip republishing the thread.
    static func apply(_ change: MessageReaction, to reactions: [MessageReaction]) -> [MessageReaction]? {
        guard let index = reactions.firstIndex(where: { $0.userID == change.userID }) else {
            return sorted(reactions + [change])
        }
        let current = reactions[index]
        // Our unconfirmed change is newer than anything the server has sent so far.
        if current.pending, !change.pending { return nil }
        if !change.pending, change.seq <= current.seq { return nil }
        var result = reactions
        result[index] = change
        return sorted(result)
    }

    /// Reconciles what the thread holds with a history page's live set for the same message.
    ///
    /// The page lists every live reaction as of `snapshot` (the conversation's highest `seq` when
    /// the server read it). An entry we hold that is newer than the snapshot — or still pending —
    /// stays; an older one the page no longer lists was removed or replaced, and goes.
    static func reconcile(
        held: [MessageReaction],
        page: [MessageReaction],
        snapshot: Int64
    ) -> [MessageReaction] {
        var byUser: [UUID: MessageReaction] = [:]
        for entry in page {
            byUser[entry.userID] = entry
        }
        for entry in held {
            // Page entries are never newer than the snapshot, so these win outright.
            if entry.pending || entry.seq > snapshot {
                byUser[entry.userID] = entry
            }
        }
        return sorted(Array(byUser.values))
    }

    /// `userID`'s entry swapped for `entry` (or dropped when nil), whatever its `seq` — for our
    /// own optimistic change, its confirmation, or putting the confirmed one back.
    static func replacing(
        _ userID: UUID,
        with entry: MessageReaction?,
        in reactions: [MessageReaction]
    ) -> [MessageReaction] {
        var result = reactions.filter { $0.userID != userID }
        if let entry { result.append(entry) }
        return sorted(result)
    }

    /// The emoji `userID` currently shows on the message, pending changes included.
    static func emoji(of userID: UUID, in reactions: [MessageReaction]) -> String? {
        reactions.first(where: { $0.userID == userID })?.emoji
    }

    /// Chips in the order each emoji first appeared; an unconfirmed reaction of ours goes last,
    /// where it will land once the server gives it the newest `seq`.
    static func chips(_ reactions: [MessageReaction], me: UUID?) -> [ReactionChip] {
        var order: [String] = []
        var users: [String: [UUID]] = [:]
        for entry in reactions {
            guard let emoji = entry.emoji else { continue }
            if users[emoji] == nil { order.append(emoji) }
            users[emoji, default: []].append(entry.userID)
        }
        return order.map { emoji in
            let ids = users[emoji] ?? []
            return ReactionChip(emoji: emoji, userIDs: ids, includesMe: me.map(ids.contains) ?? false)
        }
    }

    private static func sorted(_ reactions: [MessageReaction]) -> [MessageReaction] {
        reactions.sorted { lhs, rhs in
            let left = lhs.pending ? Int64.max : lhs.seq
            let right = rhs.pending ? Int64.max : rhs.seq
            return left == right ? lhs.userID.uuidString < rhs.userID.uuidString : left < right
        }
    }
}
