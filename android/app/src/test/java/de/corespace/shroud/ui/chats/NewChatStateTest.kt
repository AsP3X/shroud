package de.corespace.shroud.ui.chats

import de.corespace.shroud.core.messaging.ChatListFormatting
import de.corespace.shroud.core.model.ContactsListState
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.PresenceDto
import de.corespace.shroud.ui.chats.ChatsFixtures.devon
import de.corespace.shroud.ui.chats.ChatsFixtures.jane
import de.corespace.shroud.ui.chats.ChatsFixtures.mom
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/**
 * New Chat's body (`NewChatSheet.swift:12-45, 107-111`; shell-chats §9.2–§9.3): never "No contacts"
 * before the first load answered or when it failed, the filter, the status line.
 */
class NewChatStateTest {
    private val now = Instant.ofEpochSecond(1_790_000_000)
    private val created = Instant.parse("2026-09-01T10:00:00Z")
    private val roster = listOf(
        ContactItemDto(jane, "jane_cooper", created),
        ContactItemDto(mom, "mom", created),
        ContactItemDto(devon, "devon", created),
    )
    private val loaded = ContactsListState(hasLoaded = true)

    private fun derive(snapshot: NewChatSnapshot, query: String = "") =
        NewChatState.derive(snapshot, query, Locale.US) { presence ->
            ChatListFormatting.presenceLabel(presence, now, ZoneOffset.UTC, Locale.US, is24h = true)
        }

    @Test
    fun placeholdersUntilTheFirstLoadAnswers() {
        assertEquals(NewChatContent.Loading, derive(NewChatSnapshot(listState = ContactsListState(isLoading = true))))
        // A cached roster shows at once.
        assertEquals(3, (derive(NewChatSnapshot(contacts = roster)) as NewChatContent.Rows).rows.size)
    }

    @Test
    fun aFailedLoadSaysSoInsteadOfNoContacts() {
        assertEquals(
            NewChatContent.LoadError("Could not connect to the server."),
            derive(NewChatSnapshot(listState = ContactsListState(hasLoaded = true, error = "Could not connect to the server."))),
        )
        // Before the first answer the placeholders win, as on iOS.
        assertEquals(NewChatContent.Loading, derive(NewChatSnapshot(listState = ContactsListState(error = "Offline"))))
        // With contacts listed, the error is not shown.
        assertEquals(3, (derive(NewChatSnapshot(roster, ContactsListState(hasLoaded = true, error = "Offline"))) as NewChatContent.Rows).rows.size)
    }

    @Test
    fun anEmptyRosterSaysNoContacts() {
        assertEquals(NewChatContent.NoContacts, derive(NewChatSnapshot(listState = loaded)))
        assertEquals(NewChatContent.NoContacts, derive(NewChatSnapshot(listState = loaded), query = "jane"))
        assertEquals("No contacts", NewChatState.NO_CONTACTS_TITLE)
        assertEquals("Add a contact first, then start a chat.", NewChatState.NO_CONTACTS_MESSAGE)
    }

    @Test
    fun theSearchFiltersByUsernameTrimmedAndCaseInsensitive() {
        val snapshot = NewChatSnapshot(roster, loaded)
        assertEquals(listOf("jane_cooper", "mom", "devon"), (derive(snapshot) as NewChatContent.Rows).rows.map { it.username })
        assertEquals(listOf("jane_cooper"), (derive(snapshot, "  COOP ") as NewChatContent.Rows).rows.map { it.username })
        assertEquals(listOf("jane_cooper", "devon"), (derive(snapshot, "E") as NewChatContent.Rows).rows.map { it.username })
        assertEquals(NewChatContent.NoResults("zed"), derive(snapshot, " zed "))
    }

    @Test
    fun noResultsUsesTheSystemWordsWithCurlyQuotes() {
        assertEquals("No Results for “zed”", NewChatState.noResultsTitle("zed"))
        assertEquals("Check the spelling or try a new search.", NewChatState.NO_RESULTS_MESSAGE)
        assertEquals("Can't load contacts", NewChatState.LOAD_ERROR_TITLE)
    }

    @Test
    fun statusLinesSayPresenceOrContact() {
        val presence = mapOf(
            jane to PresenceDto(jane, online = true),
            mom to PresenceDto(mom, online = false, lastSeenAt = now.minusSeconds(3_600)),
        )
        val rows = (derive(NewChatSnapshot(roster, loaded, presence)) as NewChatContent.Rows).rows
        assertEquals(
            listOf(
                NewChatRow(jane, "jane_cooper", "online", online = true, avatarSeed = "jane_cooper"),
                NewChatRow(mom, "mom", "last seen 13:13", online = false, avatarSeed = "mom"),
                NewChatRow(devon, "devon", "contact", online = false, avatarSeed = "devon"),
            ),
            rows,
        )
        val offline = mapOf(devon to PresenceDto(devon, online = false))
        assertEquals("offline", (derive(NewChatSnapshot(roster, loaded, offline)) as NewChatContent.Rows).rows[2].status)
    }
}
