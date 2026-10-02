package de.corespace.shroud.ui.chats

import androidx.compose.runtime.Immutable
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.core.model.NOTES_DISPLAY_NAME
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.net.ConversationItemDto
import de.corespace.shroud.ui.components.AvatarPalette
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * One row of the chat list (`ChatsView.swift:89-95, 119-131`; shell-chats §8.2).
 *
 * @property time the list time label; null when the chat has no message yet (iOS passes "" and
 *   shows an empty text; null keeps the empty part out of the TalkBack label too).
 * @property unreadCount null when nothing is unread (`n > 0 ? n : nil`, CV:123-126).
 * @property avatarSeed the gradient seed: the username (P15, `AvatarView.gradient(for: username)`).
 * @property selected the chat open in the two-pane detail (design KQGfV: `accentSoft` row).
 */
@Immutable
data class ChatRowModel(
    val peerId: UUID,
    val title: String,
    val subtitle: String,
    val time: String?,
    val unreadCount: Int? = null,
    val hasUnseenReactions: Boolean = false,
    val isMuted: Boolean = false,
    val activity: ChatPeerActivity? = null,
    val avatarSeed: String = title,
    val isNotes: Boolean = false,
    val selected: Boolean = false,
)

/**
 * What stands under the rows — evaluated in this order (`ChatsView.swift:151-166`; shell-chats §8.4).
 */
@Immutable
sealed interface ChatsListBlock {
    /** Rows only. */
    data object None : ChatsListBlock

    /** First load still out, nothing cached, no search: placeholders (CV:31-36). */
    data object Skeleton : ChatsListBlock

    /** The load failed and nothing is listed: "Can't load chats" under the Notes row (CV:153-160). */
    data class LoadError(val message: String) : ChatsListBlock

    /**
     * Nothing matches and Notes does not either (CV:161-162, 371-406). [isSearching] picks the copy:
     * "No matches" / "Try a different name.", or — unreachable today, since Notes always shows
     * without a query — "No chats yet" with the "New Chat" button (ported for parity).
     */
    data class Empty(val isSearching: Boolean) : ChatsListBlock

    /** The account has no chats yet and only Notes shows: an 8 dp spacer (CV:163-165). */
    data object OnlyNotes : ChatsListBlock
}

/**
 * The Chats tab's derived state (`ChatsView.swift:31-54`; shell-chats §8.2): the Notes row, the
 * filtered conversation rows in server order, the block under them and the offline banner.
 */
@Immutable
data class ChatsUiState(
    val notesRow: ChatRowModel?,
    val rows: List<ChatRowModel>,
    val block: ChatsListBlock,
    val isOffline: Boolean,
    /** The account lists no chat: the staggered entrance re-arms when the first page lands (CV:182-183). */
    val hasNoConversations: Boolean,
) {
    val showsSkeleton: Boolean get() = block == ChatsListBlock.Skeleton
    val loadError: String? get() = (block as? ChatsListBlock.LoadError)?.message
    val showsEmptyState: Boolean get() = block is ChatsListBlock.Empty
    val onlyNotes: Boolean get() = block == ChatsListBlock.OnlyNotes

    /** The 1 dp line under Notes: only when rows or placeholders follow (CV:107-109). */
    val separatorAfterNotes: Boolean get() = notesRow != null && (rows.isNotEmpty() || showsSkeleton)

    companion object {
        /** Before anything was read. */
        val Initial = derive(ChatsSnapshot(), query = "", selectedPeer = null, locale = Locale.ROOT, timeLabel = { "" })

        /**
         * The list for [snapshot] and the search text [query] (the shell's shared search, untrimmed).
         *
         * - The query is trimmed (CV:39, 48); conversations match by username **or** preview, Notes by
         *   "Notes to me" or its preview — case-insensitive and locale-aware, diacritics count
         *   (Swift `localizedCaseInsensitiveContains`, [ChatsSearch]).
         * - Skeleton only before the first load, with nothing listed and **no search text at all**
         *   (CV:34-36 tests the raw text) — keyed on `hasLoadedChats`, so the poll cannot flip an
         *   empty list back to placeholders.
         * - The error only while nothing is listed, under the Notes row (CV:153-155).
         * - [timeLabel] formats `lastMessageAt` / Notes' last activity (`ChatListFormatting.timeLabel`).
         * - [selectedPeer]: the chat the two-pane detail shows, drawn selected; null in compact.
         */
        fun derive(
            snapshot: ChatsSnapshot,
            query: String,
            selectedPeer: UUID?,
            locale: Locale,
            timeLabel: (Instant?) -> String,
        ): ChatsUiState {
            val trimmed = query.trim()
            val conversations = snapshot.conversations
            val filtered = if (trimmed.isEmpty()) {
                conversations
            } else {
                conversations.filter { item ->
                    ChatsSearch.matches(item.peer.username, trimmed, locale) ||
                        ChatsSearch.matches(preview(snapshot, item.peer.id), trimmed, locale)
                }
            }
            val notesPreview = preview(snapshot, NOTES_PEER_ID)
            val showsNotes = trimmed.isEmpty() ||
                ChatsSearch.matches(NOTES_DISPLAY_NAME, trimmed, locale) ||
                ChatsSearch.matches(notesPreview, trimmed, locale)
            val showsSkeleton = !snapshot.status.hasLoadedChats && conversations.isEmpty() && query.isEmpty()
            val chatsError = snapshot.status.chatsError
            val block = when {
                showsSkeleton -> ChatsListBlock.Skeleton
                chatsError != null && conversations.isEmpty() -> ChatsListBlock.LoadError(chatsError)
                filtered.isEmpty() && !showsNotes -> ChatsListBlock.Empty(isSearching = query.isNotEmpty())
                filtered.isEmpty() && showsNotes && conversations.isEmpty() -> ChatsListBlock.OnlyNotes
                else -> ChatsListBlock.None
            }
            val notesRow = if (showsNotes) {
                ChatRowModel(
                    peerId = NOTES_PEER_ID,
                    title = NOTES_DISPLAY_NAME,
                    subtitle = notesPreview,
                    time = timeLabel(snapshot.notesLastActivity).ifEmpty { null },
                    isNotes = true,
                    selected = selectedPeer == NOTES_PEER_ID,
                )
            } else {
                null
            }
            return ChatsUiState(
                notesRow = notesRow,
                rows = filtered.map { row(it, snapshot, selectedPeer, timeLabel) },
                block = block,
                isOffline = snapshot.isOffline,
                hasNoConversations = conversations.isEmpty(),
            )
        }

        private fun row(
            item: ConversationItemDto,
            snapshot: ChatsSnapshot,
            selectedPeer: UUID?,
            timeLabel: (Instant?) -> String,
        ): ChatRowModel {
            val peer = item.peer.id
            return ChatRowModel(
                peerId = peer,
                title = item.peer.username,
                subtitle = preview(snapshot, peer),
                time = timeLabel(item.lastMessageAt).ifEmpty { null },
                unreadCount = snapshot.unread[peer]?.takeIf { it > 0 },
                hasUnseenReactions = peer in snapshot.unseenReactions,
                isMuted = snapshot.mutedUntil.containsKey(peer),
                activity = snapshot.activities[peer],
                avatarSeed = AvatarPalette.seed(item.peer.username, peer),
                selected = selectedPeer == peer,
            )
        }

        /** `ChatListFormatting.preview`'s fallbacks when the engine said nothing for [peer] (CLF:27-28). */
        private fun preview(snapshot: ChatsSnapshot, peer: UUID): String =
            snapshot.previews[peer] ?: if (peer == NOTES_PEER_ID) EMPTY_NOTES else EMPTY_CHAT

        private const val EMPTY_NOTES = "Personal notes, photos & todos"
        private const val EMPTY_CHAT = "Encrypted conversation"
    }
}

/**
 * Swift's `localizedCaseInsensitiveContains` for the list searches (CV:42-43, 52-53;
 * `NewChatSheet.swift:16`): case-insensitive with [locale]'s case rules, **not** diacritic-insensitive
 * ("jose" does not find "José"). Both sides are case-folded through upper then lower case, so "ß"
 * meets "SS" and "ς" meets "Σ" as Foundation's folding does.
 */
object ChatsSearch {
    fun matches(text: String, query: String, locale: Locale): Boolean {
        if (query.isEmpty()) return true
        return fold(text, locale).contains(fold(query, locale))
    }

    private fun fold(text: String, locale: Locale): String = text.uppercase(locale).lowercase(locale)
}
