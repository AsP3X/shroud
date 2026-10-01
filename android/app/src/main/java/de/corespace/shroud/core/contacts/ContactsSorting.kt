package de.corespace.shroud.core.contacts

import de.corespace.shroud.core.model.ContactsListState
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.ContactRequestDto
import java.text.Collator
import java.util.Locale

/** One letter section of the Contacts tab. */
data class ContactSection(val key: String, val contacts: List<ContactItemDto>)

/** What the Contacts tab shows below its rows (`ContactsView.swift:132-146`): exactly one of these. */
sealed interface ContactsListBlock {
    /** The first load is still out and nothing is cached (`showsSkeleton`, `:28-30`). */
    data object Skeleton : ContactsListBlock

    /** Nothing is known and the load failed: the roster must not read as empty. */
    data class LoadError(val message: String) : ContactsListBlock {
        val title: String get() = "Can't load contacts"
    }

    /** No rows at all (`emptyState`, `:277-299`); the copy depends on whether a search is on. */
    data class Empty(val title: String, val message: String) : ContactsListBlock

    data object None : ContactsListBlock
}

/** The Contacts tab's content (`ContactsView.swift:95-150`). */
data class ContactsListContent(
    /** Incoming requests, server order, never filtered by the search. */
    val pending: List<ContactRequestDto>,
    val sections: List<ContactSection>,
    val block: ContactsListBlock,
)

/**
 * The Contacts tab's search, sections and order (iOS `ContactsView.swift:24-57`; contacts §4.3,
 * §5.1), kept out of the composable so it is tested on the JVM. W3-CONTACTS-UI draws what this
 * returns; the Pending section (incoming requests) is never filtered by the search.
 */
object ContactsSorting {
    /**
     * iOS `localizedCaseInsensitiveCompare`: the user's locale's collation at secondary strength
     * (case ignored, accents not). On Android `java.text.Collator` is ICU's; one instance per call
     * site, as a collator is not thread-safe.
     */
    fun collator(locale: Locale = Locale.getDefault()): Comparator<String> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
        return Comparator { a, b -> collator.compare(a, b) }
    }

    /** The trimmed query (Swift whitespace and newlines); empty means "not searching" (`:40-42`). */
    fun searchQuery(searchText: String): String = ContactInviteParser.trimSwiftWhitespace(searchText)

    /**
     * Contacts whose username or user id contains the query, ignoring case (`filtered`, `:32-38`);
     * all of them without a query. iOS compares the upper-case `uuidString` case-insensitively, the
     * same as the lower-case wire form here.
     */
    fun filter(contacts: List<ContactItemDto>, searchText: String): List<ContactItemDto> {
        val query = searchQuery(searchText)
        if (query.isEmpty()) return contacts
        return contacts.filter { it.username.contains(query, ignoreCase = true) || Ids.wire(it.userId).contains(query, ignoreCase = true) }
    }

    /**
     * Letter sections (`sections`, `:44-57`): grouped by the username's first character upper-cased;
     * keys in ordinal order (Swift `<` on the server's `[a-z0-9_]` usernames: digits, `A`–`Z`,
     * then `_`), reversed when not [ascending]; inside a section [order] (the collator), reversed
     * likewise.
     */
    fun sections(
        contacts: List<ContactItemDto>,
        searchText: String,
        ascending: Boolean,
        order: Comparator<String> = collator(),
    ): List<ContactSection> {
        val grouped = filter(contacts, searchText).groupBy { firstCharacter(it.username).uppercase() }
        val keys = grouped.keys.sorted().let { if (ascending) it else it.reversed() }
        val within = compareBy(order) { contact: ContactItemDto -> contact.username }.let { if (ascending) it else it.reversed() }
        return keys.map { key -> ContactSection(key, grouped.getValue(key).sortedWith(within)) }
    }

    /**
     * The whole list (`ContactsView.swift:95-150`): the Pending section as is, the letter sections,
     * and the one state block — skeleton only for a first load with nothing cached and no search text
     * (iOS checks the untrimmed text, `:28-30`); the load error only when nothing at all is known;
     * the empty state when there are no rows, worded for a search when one is on.
     */
    fun listContent(
        contacts: List<ContactItemDto>,
        requests: List<ContactRequestDto>,
        state: ContactsListState,
        searchText: String,
        ascending: Boolean,
        order: Comparator<String> = collator(),
    ): ContactsListContent {
        val sections = sections(contacts, searchText, ascending, order)
        val searching = searchQuery(searchText).isNotEmpty()
        val block = when {
            !state.hasLoaded && contacts.isEmpty() && searchText.isEmpty() -> ContactsListBlock.Skeleton
            state.error != null && contacts.isEmpty() && requests.isEmpty() -> ContactsListBlock.LoadError(state.error)
            sections.isEmpty() && requests.isEmpty() -> if (searching) {
                ContactsListBlock.Empty("No matches", "Try a different name.")
            } else {
                ContactsListBlock.Empty("No contacts yet", "Scan a QR code or enter a share code to add someone.")
            }
            else -> ContactsListBlock.None
        }
        return ContactsListContent(pending = requests, sections = sections, block = block)
    }

    /** Swift `prefix(1)`: the first grapheme cluster. */
    private fun firstCharacter(text: String): String {
        if (text.isEmpty()) return ""
        val iterator = java.text.BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        return text.substring(0, iterator.next())
    }
}
