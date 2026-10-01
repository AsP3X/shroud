package de.corespace.shroud.core.contacts

import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.net.ContactItemDto
import java.text.Collator
import java.util.Locale

/** One letter section of the Contacts tab. */
data class ContactSection(val key: String, val contacts: List<ContactItemDto>)

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
     * keys in plain code-point order (Swift `<`: digits, `A`–`Z`, then `_`), reversed when not
     * [ascending]; inside a section [order] (the collator), reversed likewise.
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

    /** Swift `prefix(1)`: the first grapheme cluster. */
    private fun firstCharacter(text: String): String {
        if (text.isEmpty()) return ""
        val iterator = java.text.BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        return text.substring(0, iterator.next())
    }
}
