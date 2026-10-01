package de.corespace.shroud.core.model

import java.util.UUID

// Reactions as a thread holds them (plan §1.7.5, a W2 seam). The merge rules over them are
// `core/messaging/reactions/ReactionMerge` (W1-WIRE).

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

/**
 * A reaction change the server refused (`MessagingController.swift:4988-4992`): the thread shows
 * [message] once as a toast. [id] is random, so two equal failures are still two toasts.
 */
data class ReactionFailure(val id: UUID, val messageId: UUID, val message: String)
