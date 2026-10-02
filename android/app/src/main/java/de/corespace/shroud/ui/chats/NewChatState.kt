package de.corespace.shroud.ui.chats

import androidx.compose.runtime.Immutable
import de.corespace.shroud.core.net.PresenceDto
import de.corespace.shroud.ui.components.AvatarPalette
import java.util.Locale
import java.util.UUID

/**
 * One contact in New Chat (`NewChatSheet.swift:46-84`; shell-chats §9.3).
 *
 * @property status "online", "last seen 9:41", "last seen yesterday", "last seen Sep 27, 2026",
 *   "offline" — or "contact" while the server has said nothing (NCS:107-111).
 * @property online the status is drawn in `accent` (NCS:64-68).
 */
@Immutable
data class NewChatRow(
    val userId: UUID,
    val username: String,
    val status: String,
    val online: Boolean,
    val avatarSeed: String,
)

/** What New Chat's body shows, in iOS's order (`NewChatSheet.swift:22-45`; shell-chats §9.2). */
@Immutable
sealed interface NewChatContent {
    /** The roster's first load has not answered and nothing is cached: 6 placeholder rows. */
    data object Loading : NewChatContent

    /** The load failed and nothing is listed: "Can't load contacts" with Try Again. */
    data class LoadError(val message: String) : NewChatContent

    /** The account has no contacts: "No contacts" / "Add a contact first, then start a chat." */
    data object NoContacts : NewChatContent

    /** Nothing matches [query] (trimmed): "No Results for “query”" (iOS `ContentUnavailableView.search`). */
    data class NoResults(val query: String) : NewChatContent

    data class Rows(val rows: List<NewChatRow>) : NewChatContent
}

/** The pure rules of New Chat. */
object NewChatState {
    /** `ContentUnavailableView.search(text:)`'s words, with the curly quotes iOS shows. */
    fun noResultsTitle(query: String): String = "No Results for “$query”"

    const val NO_RESULTS_MESSAGE = "Check the spelling or try a new search."
    const val NO_CONTACTS_TITLE = "No contacts"
    const val NO_CONTACTS_MESSAGE = "Add a contact first, then start a chat."
    const val LOAD_ERROR_TITLE = "Can't load contacts"

    /** "contact" until the server has told us anything (NCS:107-111). */
    const val NO_PRESENCE = "contact"

    /**
     * The body for [snapshot] and the sheet's own search text [query] (`NewChatSheet.swift:12-45`):
     * never "No contacts" before the first load answered or when it failed; contacts filtered by
     * username containing the trimmed query (case-insensitive, [ChatsSearch]) in the engine's
     * order. [presenceLabel] is `ChatListFormatting.presenceLabel` in the user's clock settings.
     */
    fun derive(
        snapshot: NewChatSnapshot,
        query: String,
        locale: Locale,
        presenceLabel: (PresenceDto?) -> String?,
    ): NewChatContent {
        val contacts = snapshot.contacts
        val trimmed = query.trim()
        val error = snapshot.listState.error
        return when {
            !snapshot.listState.hasLoaded && contacts.isEmpty() -> NewChatContent.Loading
            error != null && contacts.isEmpty() -> NewChatContent.LoadError(error)
            contacts.isEmpty() -> NewChatContent.NoContacts
            else -> {
                val filtered = if (trimmed.isEmpty()) contacts else contacts.filter { ChatsSearch.matches(it.username, trimmed, locale) }
                if (filtered.isEmpty()) {
                    NewChatContent.NoResults(trimmed)
                } else {
                    NewChatContent.Rows(
                        filtered.map { contact ->
                            val presence = snapshot.presence[contact.userId]
                            NewChatRow(
                                userId = contact.userId,
                                username = contact.username,
                                status = presenceLabel(presence) ?: NO_PRESENCE,
                                online = presence?.online == true,
                                avatarSeed = AvatarPalette.seed(contact.username, contact.userId),
                            )
                        },
                    )
                }
            }
        }
    }
}
