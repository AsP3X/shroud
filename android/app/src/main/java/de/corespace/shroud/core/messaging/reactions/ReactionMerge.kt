package de.corespace.shroud.core.messaging.reactions

import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.net.ReactionDto
import java.util.UUID

/**
 * Pure merge rules for reactions — iOS `ReactionMerge`
 * (`ios/shroud/Services/Messaging/MessageReactions.swift:38-154`), web `reactions.ts`
 * (`applyReaction`, `replacingReaction`, `emojisOf`, `toggledReactions`, `rebasedReactions`,
 * `reactionChips`, `pageReactionsFor`). messaging-core §19.2.
 *
 * The server's `seq` is last-write-wins per user. A removal stays as an entry with no emoji, so a late
 * or replayed older change cannot bring the reactions back; `pending` marks our own unconfirmed change
 * (it keeps the previous `seq` and sorts last).
 */
object ReactionMerge {
    /**
     * Applies one change — a socket event, a catch-up row, a server ack (`:40-53`). Null when it
     * changes nothing, so callers can skip republishing the thread: our pending entry outlives any
     * confirmed change, and a confirmed change must be newer than what is held.
     */
    fun apply(change: MessageReaction, to: List<MessageReaction>): List<MessageReaction>? {
        val index = to.indexOfFirst { it.userId == change.userId }
        if (index < 0) return sorted(to + change)
        val current = to[index]
        // Our unconfirmed change is newer than anything the server has sent so far.
        if (current.pending && !change.pending) return null
        if (!change.pending && change.seq <= current.seq) return null
        return sorted(to.toMutableList().also { it[index] = change })
    }

    /**
     * Reconciles what the thread holds with a history page's live set for the same message (`:55-77`).
     * The page lists every live reaction as of [snapshot] (the chat's latest `seq` when the server read
     * it — before the page, so a page entry may be newer still). A held entry that is pending, or newer
     * than the snapshot and than the page's entry, stays; an older one the page no longer lists was
     * removed or replaced, and goes.
     */
    fun reconcile(held: List<MessageReaction>, page: List<MessageReaction>, snapshot: Long): List<MessageReaction> {
        val byUser = LinkedHashMap<UUID, MessageReaction>()
        for (entry in page) {
            val known = byUser[entry.userId]
            if (known == null || known.seq < entry.seq) byUser[entry.userId] = entry
        }
        for (entry in held) {
            if (!entry.pending && entry.seq <= snapshot) continue
            val fromPage = byUser[entry.userId]
            if (!entry.pending && fromPage != null && fromPage.seq >= entry.seq) continue
            byUser[entry.userId] = entry
        }
        return sorted(byUser.values.toList())
    }

    /**
     * [userId]'s entry swapped for [entry] (or dropped when null), whatever its `seq` (`:79-89`) — for
     * our own optimistic change, its confirmation, or putting the confirmed one back.
     */
    fun replacing(userId: UUID, entry: MessageReaction?, reactions: List<MessageReaction>): List<MessageReaction> {
        val result = reactions.filter { it.userId != userId }.toMutableList()
        if (entry != null) result += entry
        return sorted(result)
    }

    /** The emoji [userId] currently shows on the message, pending changes included (`:91-94`). */
    fun emojis(userId: UUID, reactions: List<MessageReaction>): List<String> =
        reactions.firstOrNull { it.userId == userId }?.emojis ?: emptyList()

    /**
     * Our set after picking [emoji] (`:96-105`): taken back when it is there, otherwise added — and
     * past [limit] (the server's `max_per_user`, never below 1) our oldest goes, so a pick always shows.
     */
    fun toggled(emoji: String, current: List<String>, limit: Int): List<String> {
        if (emoji in current) return current.filter { it != emoji }
        return capped(current + emoji, limit)
    }

    /**
     * What we changed (`base` → [mine]) re-applied onto the set the server holds now ([theirs], written
     * by our other device meanwhile) (`:107-119`): emoji we added are added, emoji we took back go, the
     * rest stays theirs. Past [limit] the oldest go, as with [toggled].
     */
    fun rebased(mine: List<String>, base: List<String>, theirs: List<String>, limit: Int): List<String> {
        val takenBack = base.toSet() - mine.toSet()
        val result = theirs.filter { it !in takenBack }.toMutableList()
        for (emoji in mine) {
            if (emoji !in base && emoji !in result) result += emoji
        }
        return capped(result, limit)
    }

    /**
     * One chip per person — both people share one when they picked exactly the same emoji set — the
     * other side's first and ours after, whoever reacted first (Telegram's place for them) (`:121-145`).
     * Removals (no emoji) draw nothing.
     */
    fun chips(reactions: List<MessageReaction>, me: UUID?): List<ReactionChip> {
        val chips = ArrayList<ReactionChip>()
        for (entry in reactions) {
            if (!entry.isLive) continue
            val set = entry.emojis.toSet()
            val index = chips.indexOfFirst { it.emojis.toSet() == set }
            if (index >= 0) {
                val users = chips[index].userIds + entry.userId
                chips[index] = ReactionChip(emojis = chips[index].emojis, userIds = users, includesMe = me != null && me in users)
            } else {
                chips += ReactionChip(emojis = entry.emojis, userIds = listOf(entry.userId), includesMe = entry.userId == me)
            }
        }
        // Stable: within each side the reaction order stays.
        return chips.sortedBy { it.includesMe }
    }

    /**
     * The records of a history page that may be trusted for [messageId] (MC:1180-1187, web
     * `pageReactionsFor`): only records for that very message, from the people in the chat
     * ([reactors]: us and the peer), the newest per person. The server could otherwise list a genuine
     * reaction under another message, or a stranger's. In first-seen order of the people.
     */
    fun pageRecords(messageId: UUID, records: List<ReactionDto>, reactors: Set<UUID>): List<ReactionDto> {
        val latest = LinkedHashMap<UUID, ReactionDto>()
        for (record in records) {
            if (record.messageId != messageId || record.userId !in reactors) continue
            val held = latest[record.userId]
            if (held == null || held.seq < record.seq) latest[record.userId] = record
        }
        return latest.values.toList()
    }

    private fun capped(emojis: List<String>, limit: Int): List<String> {
        val cap = maxOf(1, limit)
        return if (emojis.size > cap) emojis.drop(emojis.size - cap) else emojis
    }

    /**
     * By `seq`, pending last; ties by the user id string (`:147-153`). iOS compares upper-case
     * `uuidString`s and Android the lower-case wire form: hex digits sort before letters in both
     * cases, so the order is the same.
     */
    private fun sorted(reactions: List<MessageReaction>): List<MessageReaction> =
        reactions.sortedWith(compareBy<MessageReaction> { if (it.pending) Long.MAX_VALUE else it.seq }.thenBy { Ids.wire(it.userId) })
}

// -------------------------------------------------------------------------------------------------
// PROVISIONAL HOME. Plan §1.7.5 puts these two in `core/model/Reactions.kt`, a W2 seam that W1-INT
// publishes; ReactionMerge needs them in W1. W1-INT moves both declarations there unchanged and adds
// `import de.corespace.shroud.core.model.{MessageReaction, ReactionChip}` here and in the tests
// (contract change request in the W1-WIRE report).
// -------------------------------------------------------------------------------------------------

/**
 * One user's reactions on a message, as a thread holds them (`MessageReactions.swift:3-24`; plan
 * §1.7.5). [seq] is the server's change cursor (the highest per user wins); [pending] marks our own
 * change before the server confirmed it — it keeps the previous [seq], and the thread is saved with our
 * last confirmed entry in its place, so nothing unconfirmed outlives a relaunch.
 */
data class MessageReaction(
    val userId: UUID,
    /** Oldest first; empty when the user took their reactions back. */
    val emojis: List<String>,
    val seq: Long,
    val pending: Boolean = false,
) {
    val isLive: Boolean get() = emojis.isNotEmpty()

    /** Never prints the emoji. */
    override fun toString(): String = "MessageReaction(emoji=${emojis.size}, seq=$seq, pending=$pending)"
}

/**
 * A chip under a bubble: one person's emoji and face — or both people's faces when they picked exactly
 * the same emoji (`MessageReactions.swift:26-36`; plan §1.7.5).
 */
data class ReactionChip(
    /** Oldest first. */
    val emojis: List<String>,
    /** In the order they reacted. */
    val userIds: List<UUID>,
    val includesMe: Boolean,
) {
    /** Stable identity for lists: the user ids joined with `+` (iOS uses upper-case `uuidString`s; only identity matters). */
    val id: String get() = userIds.joinToString("+") { Ids.wire(it) }

    /** Never prints the emoji. */
    override fun toString(): String = "ReactionChip(emoji=${emojis.size}, users=${userIds.size}, includesMe=$includesMe)"
}
