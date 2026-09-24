import Foundation

/// One user's reactions on a message, as the thread holds them.
///
/// Human: A person may leave several emoji on one message — up to the server's limit
/// (`GET /config`) — and they travel as one sealed record. A removal stays as an entry with no
/// emoji, so a late or replayed older change (a delayed WebSocket event, a stale page) cannot
/// bring the reactions back.
/// Agent: `seq` is the server's change cursor; the highest one per user wins. `pending` marks our
/// own change before the server confirmed it — it keeps the previous `seq`, and the thread is saved
/// with our last confirmed entry in its place, so nothing unconfirmed outlives a relaunch.
nonisolated struct MessageReaction: Codable, Equatable, Hashable, Sendable {
    let userID: UUID
    /// Oldest first; empty when the user took their reactions back.
    let emojis: [String]
    let seq: Int64
    var pending: Bool = false

    enum CodingKeys: String, CodingKey {
        case userID, emojis, seq
    }

    var isLive: Bool { !emojis.isEmpty }
}

/// A chip under a bubble: one person's emoji and their face — or both people's faces when they
/// picked exactly the same emoji. Several reactions by one person share one chip.
nonisolated struct ReactionChip: Equatable, Hashable, Sendable, Identifiable {
    /// Oldest first.
    let emojis: [String]
    /// In the order they reacted.
    let userIDs: [UUID]
    let includesMe: Bool

    var id: String { userIDs.map(\.uuidString).joined(separator: "+") }
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
    /// The page lists every live reaction as of `snapshot` (the conversation's latest `seq` when
    /// the server read it — before the page, so a page entry may be newer still). An entry we
    /// hold that is newer than the snapshot and than the page's entry — or still pending —
    /// stays; an older one the page no longer lists was removed or replaced, and goes.
    static func reconcile(
        held: [MessageReaction],
        page: [MessageReaction],
        snapshot: Int64
    ) -> [MessageReaction] {
        var byUser: [UUID: MessageReaction] = [:]
        for entry in page where (byUser[entry.userID]?.seq ?? .min) < entry.seq {
            byUser[entry.userID] = entry
        }
        for entry in held where entry.pending || entry.seq > snapshot {
            if !entry.pending, let fromPage = byUser[entry.userID], fromPage.seq >= entry.seq {
                continue
            }
            byUser[entry.userID] = entry
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
    static func emojis(of userID: UUID, in reactions: [MessageReaction]) -> [String] {
        reactions.first(where: { $0.userID == userID })?.emojis ?? []
    }

    /// Our set after picking `emoji`: taken back when it is there, otherwise added — and when
    /// that makes more than `limit` (the server's `max_per_user`), our oldest goes, so a pick
    /// always shows.
    static func toggled(_ emoji: String, in current: [String], limit: Int) -> [String] {
        if current.contains(emoji) { return current.filter { $0 != emoji } }
        var next = current + [emoji]
        let cap = max(1, limit)
        if next.count > cap { next.removeFirst(next.count - cap) }
        return next
    }

    /// What we changed (`base` → `mine`) re-applied onto the set the server holds now
    /// (`theirs`, written by our other device meanwhile): emoji we added are added, emoji we took
    /// back go, the rest stays theirs. Past `limit` the oldest go, as with `toggled`.
    static func rebased(_ mine: [String], from base: [String], onto theirs: [String], limit: Int) -> [String] {
        let takenBack = Set(base).subtracting(mine)
        var result = theirs.filter { !takenBack.contains($0) }
        for emoji in mine where !base.contains(emoji) && !result.contains(emoji) {
            result.append(emoji)
        }
        let cap = max(1, limit)
        if result.count > cap { result.removeFirst(result.count - cap) }
        return result
    }

    /// One chip per person (both people share one when they picked exactly the same emoji), the
    /// other side's first and ours after — Telegram's place for them, whoever reacted first.
    static func chips(_ reactions: [MessageReaction], me: UUID?) -> [ReactionChip] {
        var chips: [ReactionChip] = []
        for entry in reactions where entry.isLive {
            if let index = chips.firstIndex(where: { Set($0.emojis) == Set(entry.emojis) }) {
                let users = chips[index].userIDs + [entry.userID]
                chips[index] = ReactionChip(
                    emojis: chips[index].emojis,
                    userIDs: users,
                    includesMe: me.map(users.contains) ?? false
                )
            } else {
                chips.append(ReactionChip(
                    emojis: entry.emojis,
                    userIDs: [entry.userID],
                    includesMe: entry.userID == me
                ))
            }
        }
        return chips.enumerated().sorted { lhs, rhs in
            if lhs.element.includesMe != rhs.element.includesMe { return !lhs.element.includesMe }
            return lhs.offset < rhs.offset
        }.map(\.element)
    }

    private static func sorted(_ reactions: [MessageReaction]) -> [MessageReaction] {
        reactions.sorted { lhs, rhs in
            let left = lhs.pending ? Int64.max : lhs.seq
            let right = rhs.pending ? Int64.max : rhs.seq
            return left == right ? lhs.userID.uuidString < rhs.userID.uuidString : left < right
        }
    }
}
