package de.corespace.shroud.core.contacts

import de.corespace.shroud.core.model.ContactsListState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The Contacts tab's search, letter sections and order (iOS `ContactsView.swift:24-57, 95-150,
 * 277-299`; contacts §5.1, §9 *ContactsSortingTest*). Collation is ICU at secondary strength, as
 * `java.text.Collator` is on Android (iOS `localizedCaseInsensitiveCompare`).
 */
class ContactsSortingTest {
    private val order = icuOrder()
    private val me = UUID.fromString("8f14e45f-ceea-467a-9575-3a6b7a1e6c0e")

    private val roster = listOf(
        contact("bob"),
        contact("_root"),
        contact("alice"),
        contact("9lives"),
        contact("Anna"),
        contact("zoe"),
        contact("0zero"),
        contact("ab_c"),
        contact("abc"),
        contact("Alice2"),
    )

    @Test
    fun sectionKeysAreOrdinalDigitsLettersThenUnderscore() {
        val keys = ContactsSorting.sections(roster, "", ascending = true, order = order).map { it.key }
        assertEquals(listOf("0", "9", "A", "B", "Z", "_"), keys)
        val reversed = ContactsSorting.sections(roster, "", ascending = false, order = order).map { it.key }
        assertEquals(listOf("_", "Z", "B", "A", "9", "0"), reversed)
    }

    @Test
    fun withinASectionTheCollatorOrdersBothWays() {
        val a = ContactsSorting.sections(roster, "", ascending = true, order = order).first { it.key == "A" }
        // ICU: `_` sorts before letters, case is ignored at secondary strength, digits before letters.
        assertEquals(listOf("ab_c", "abc", "alice", "Alice2", "Anna"), a.contacts.map { it.username })
        val down = ContactsSorting.sections(roster, "", ascending = false, order = order).first { it.key == "A" }
        assertEquals(listOf("Anna", "Alice2", "alice", "abc", "ab_c"), down.contacts.map { it.username })
    }

    @Test
    fun searchMatchesUsernameOrIdIgnoringCase() {
        val alice = roster.first { it.username == "alice" }
        assertEquals(listOf("alice", "Alice2"), ContactsSorting.filter(roster, "  ALI ").map { it.username })
        val idPart = alice.userId.toString().substring(9, 18).uppercase()
        assertEquals(listOf(alice), ContactsSorting.filter(roster, idPart))
        assertEquals(roster, ContactsSorting.filter(roster, " \n"))
        assertTrue(ContactsSorting.filter(roster, "nobody").isEmpty())
    }

    @Test
    fun requestsAreNeverFilteredBySearch() {
        val request = pendingRequest(from = UUID.randomUUID(), to = me, name = "carol")
        val content = ContactsSorting.listContent(roster, listOf(request), ContactsListState(hasLoaded = true), "zzz", ascending = true, order = order)
        assertEquals(listOf(request), content.pending)
        assertTrue(content.sections.isEmpty())
        assertEquals(ContactsListBlock.None, content.block)
    }

    @Test
    fun theStateBlockFollowsIos() {
        fun block(contacts: Int, requests: Int, state: ContactsListState, search: String) = ContactsSorting.listContent(
            roster.take(contacts),
            List(requests) { pendingRequest(from = UUID.randomUUID(), to = me, name = "r$it") },
            state,
            search,
            ascending = true,
            order = order,
        ).block

        // First load, nothing cached, no search text: skeleton (iOS checks the raw text).
        assertEquals(ContactsListBlock.Skeleton, block(0, 0, ContactsListState(isLoading = true), ""))
        assertEquals(
            ContactsListBlock.Empty("No matches", "Try a different name."),
            block(0, 0, ContactsListState(isLoading = true), "x"),
        )
        // Failed with nothing known: the error, never "No contacts yet".
        val failed = block(0, 0, ContactsListState(hasLoaded = true, error = "The request timed out."), "")
        assertEquals(ContactsListBlock.LoadError("The request timed out."), failed)
        assertEquals("Can't load contacts", (failed as ContactsListBlock.LoadError).title)
        // Requests are something known.
        assertEquals(ContactsListBlock.None, block(0, 1, ContactsListState(hasLoaded = true, error = "offline"), ""))
        assertEquals(
            ContactsListBlock.Empty("No contacts yet", "Scan a QR code or enter a share code to add someone."),
            block(0, 0, ContactsListState(hasLoaded = true), ""),
        )
        assertEquals(ContactsListBlock.Empty("No matches", "Try a different name."), block(3, 0, ContactsListState(hasLoaded = true), "nobody"))
        assertEquals(ContactsListBlock.None, block(3, 0, ContactsListState(hasLoaded = true), ""))
    }
}
