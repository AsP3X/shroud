package de.corespace.shroud.ui.chats

import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.core.model.ListStatus
import de.corespace.shroud.core.model.NOTES_DISPLAY_NAME
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.ui.chats.ChatsFixtures.conversation
import de.corespace.shroud.ui.chats.ChatsFixtures.devon
import de.corespace.shroud.ui.chats.ChatsFixtures.jane
import de.corespace.shroud.ui.chats.ChatsFixtures.mom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * The Chats tab's derived state (`ChatsView.swift:31-54, 82-166`; shell-chats §8.2–§8.4, §14
 * `ChatsUiStateTest`): skeleton only before the first load and only without a query, the error only
 * with an empty list and under Notes, the empty state only when Notes does not match, Notes matched
 * by name or preview, the query trimmed, case-insensitive and diacritic-sensitive.
 */
class ChatsUiStateTest {
    private val nineThirtyEight = Instant.parse("2026-09-24T09:38:00Z")

    private val loaded = ListStatus(hasLoadedChats = true, hasLoadedServerChats = true)

    private fun snapshot(
        vararg conversations: Pair<UUID, String>,
        status: ListStatus = loaded,
        previews: Map<UUID, String> = emptyMap(),
    ) = ChatsSnapshot(
        conversations = conversations.map { (peer, name) -> conversation(peer, name, last = nineThirtyEight) },
        status = status,
        previews = previews,
    )

    private fun derive(snapshot: ChatsSnapshot, query: String = "", selected: UUID? = null, locale: Locale = Locale.US) =
        ChatsUiState.derive(snapshot, query, selected, locale) { at -> if (at == null) "" else "9:38" }

    // ---- skeleton (CV:31-36) ----

    @Test
    fun skeletonOnlyBeforeTheFirstLoadWithNothingListedAndNoQuery() {
        val first = derive(snapshot(status = ListStatus(isLoadingChats = true)))
        assertEquals(ChatsListBlock.Skeleton, first.block)
        assertTrue(first.showsSkeleton)
        // Notes are local and always shown, with the line to the placeholders under them (CV:107-109).
        assertNotNull(first.notesRow)
        assertTrue(first.separatorAfterNotes)

        // Loaded and empty: the 3 s poll must not flip it back to placeholders.
        val loadedEmpty = derive(snapshot(status = ListStatus(hasLoadedChats = true, isLoadingChats = true)))
        assertEquals(ChatsListBlock.OnlyNotes, loadedEmpty.block)
        assertTrue(loadedEmpty.onlyNotes)
        assertFalse(loadedEmpty.separatorAfterNotes)

        // A cached list stands in for the first load.
        val cached = derive(snapshot(jane to "jane", status = ListStatus()))
        assertEquals(ChatsListBlock.None, cached.block)
    }

    @Test
    fun anySearchTextKeepsTheSkeletonAway() {
        assertEquals(ChatsListBlock.Empty(isSearching = true), derive(snapshot(status = ListStatus()), query = "zz").block)
        // iOS tests the raw text: spaces alone hide the placeholders, while the trimmed query still shows Notes.
        val spaces = derive(snapshot(status = ListStatus()), query = "  ")
        assertEquals(ChatsListBlock.OnlyNotes, spaces.block)
        assertNotNull(spaces.notesRow)
    }

    // ---- load error (CV:153-160) ----

    @Test
    fun theErrorShowsUnderNotesOnlyWhileNothingIsListed() {
        val failed = derive(snapshot(status = ListStatus(hasLoadedChats = true, chatsError = "Could not connect to the server.")))
        assertEquals(ChatsListBlock.LoadError("Could not connect to the server."), failed.block)
        assertEquals("Could not connect to the server.", failed.loadError)
        assertNotNull(failed.notesRow)
        assertFalse(failed.separatorAfterNotes)

        val listed = derive(snapshot(jane to "jane", status = ListStatus(hasLoadedChats = true, chatsError = "Could not connect to the server.")))
        assertEquals(ChatsListBlock.None, listed.block)
        assertNull(listed.loadError)
        assertEquals(listOf("jane"), listed.rows.map { it.title })
    }

    @Test
    fun theErrorComesBeforeTheEmptyStateAndTheSkeletonBeforeBoth() {
        val error = ListStatus(chatsError = "Offline")
        assertEquals(ChatsListBlock.Skeleton, derive(snapshot(status = error)).block)
        assertEquals(ChatsListBlock.LoadError("Offline"), derive(snapshot(status = error), query = "zz").block)
    }

    // ---- empty state (CV:161-162, 371-406) ----

    @Test
    fun theEmptyStateShowsOnlyWhenNotesDoesNotMatchEither() {
        val noMatch = derive(snapshot(mom to "bob"), query = "jane")
        assertEquals(ChatsListBlock.Empty(isSearching = true), noMatch.block)
        assertTrue(noMatch.showsEmptyState)
        assertNull(noMatch.notesRow)
        assertTrue(noMatch.rows.isEmpty())

        // Notes matches "note": no empty state, Notes alone, no line under it.
        val notesOnly = derive(snapshot(mom to "bob"), query = "note")
        assertEquals(ChatsListBlock.None, notesOnly.block)
        assertNotNull(notesOnly.notesRow)
        assertFalse(notesOnly.separatorAfterNotes)
    }

    // ---- search (CV:38-54) ----

    @Test
    fun notesMatchByNameOrPreview() {
        val previews = mapOf(NOTES_PEER_ID to "Buy oat milk")
        assertNotNull(derive(snapshot(previews = previews), query = "notes TO").notesRow)
        assertNotNull(derive(snapshot(previews = previews), query = "OAT").notesRow)
        assertNull(derive(snapshot(previews = previews), query = "rice").notesRow)
        // Without a cached preview, the empty-Notes line is what is searched.
        assertNotNull(derive(snapshot(), query = "photos").notesRow)
    }

    @Test
    fun conversationsMatchByUsernameOrPreviewInServerOrder() {
        val snap = snapshot(
            jane to "jane_cooper", mom to "mom", devon to "devon",
            previews = mapOf(jane to "Ok", mom to "Call me when you get home", devon to "The keys are under the mat"),
        )
        assertEquals(listOf("jane_cooper", "mom", "devon"), derive(snap).rows.map { it.title })
        assertEquals(listOf("jane_cooper"), derive(snap, query = "COOPER").rows.map { it.title })
        assertEquals(listOf("mom"), derive(snap, query = "home").rows.map { it.title })
        assertEquals(listOf("devon"), derive(snap, query = "the").rows.map { it.title })
        // Name and preview hits keep the server's order.
        assertEquals(listOf("jane_cooper", "mom", "devon"), derive(snap, query = "e").rows.map { it.title })
    }

    @Test
    fun theQueryIsTrimmed() {
        val snap = snapshot(jane to "jane", mom to "mom")
        assertEquals(listOf("jane"), derive(snap, query = "  jane \n").rows.map { it.title })
        assertEquals(listOf("jane", "mom"), derive(snap, query = " \t ").rows.map { it.title })
    }

    @Test
    fun searchIsCaseInsensitiveButDiacriticSensitive() {
        val snap = snapshot(jane to "José", mom to "strasse")
        assertEquals(listOf("José"), derive(snap, query = "JOSÉ").rows.map { it.title })
        assertEquals(listOf("José"), derive(snap, query = "josé").rows.map { it.title })
        assertEquals(emptyList<String>(), derive(snap, query = "jose").rows.map { it.title })
        // Foundation folds "ß" to "ss", as upper-casing does.
        assertEquals(listOf("strasse"), derive(snap, query = "STRAßE").rows.map { it.title })
    }

    @Test
    fun searchFollowsTheLocalesCaseRules() {
        assertTrue(ChatsSearch.matches("IRMAK", "irmak", Locale.US))
        // Turkish: dotted and dotless i are different letters.
        assertFalse(ChatsSearch.matches("IRMAK", "irmak", Locale.forLanguageTag("tr")))
        assertTrue(ChatsSearch.matches("İRMAK", "irmak", Locale.forLanguageTag("tr")))
        assertTrue(ChatsSearch.matches("anything", "", Locale.US))
    }

    // ---- rows (CV:89-95, 119-131) ----

    @Test
    fun rowsCarryTheEngineFacts() {
        val snap = ChatsSnapshot(
            conversations = listOf(
                conversation(jane, "jane", last = nineThirtyEight),
                conversation(mom, "mom", last = null),
            ),
            status = loaded,
            previews = mapOf(jane to "Ok", NOTES_PEER_ID to "Photo"),
            notesLastActivity = nineThirtyEight,
            unread = mapOf(jane to 2, mom to 0),
            unseenReactions = setOf(mom),
            mutedUntil = mapOf(mom to null),
            activities = mapOf(jane to ChatPeerActivity.Recording),
            activePeer = mom,
        )
        val ui = derive(snap, selected = mom)
        val (janeRow, momRow) = ui.rows
        assertEquals(ChatRowModel(jane, "jane", "Ok", "9:38", unreadCount = 2, activity = ChatPeerActivity.Recording, avatarSeed = "jane"), janeRow)
        // No message yet: no time (iOS "" → nothing to show or say); 0 unread → no badge; the
        // previews fall back to the engine's empty line.
        assertEquals(
            ChatRowModel(mom, "mom", "Encrypted conversation", null, hasUnseenReactions = true, isMuted = true, avatarSeed = "mom", selected = true),
            momRow,
        )
        assertEquals(
            ChatRowModel(NOTES_PEER_ID, NOTES_DISPLAY_NAME, "Photo", "9:38", isNotes = true),
            ui.notesRow,
        )
        assertFalse(ui.hasNoConversations)
        assertTrue(ui.separatorAfterNotes)
    }

    @Test
    fun notesWithoutAnythingSayTheirEmptyLine() {
        val notes = derive(snapshot()).notesRow!!
        assertEquals("Personal notes, photos & todos", notes.subtitle)
        assertNull(notes.time)
        assertTrue(derive(snapshot()).hasNoConversations)
    }

    @Test
    fun theTwoPaneSelectionMarksNotesToo() {
        assertTrue(derive(snapshot(jane to "jane"), selected = NOTES_PEER_ID).notesRow!!.selected)
        assertFalse(derive(snapshot(jane to "jane"), selected = NOTES_PEER_ID).rows.single().selected)
        assertFalse(derive(snapshot(jane to "jane"), selected = null).notesRow!!.selected)
    }

    @Test
    fun theOfflineFlagPassesThrough() {
        assertTrue(derive(snapshot().copy(isOffline = true)).isOffline)
        assertFalse(derive(snapshot()).isOffline)
    }

    @Test
    fun theInitialStateIsTheFirstLoad() {
        assertEquals(ChatsListBlock.Skeleton, ChatsUiState.Initial.block)
        assertEquals(NOTES_DISPLAY_NAME, ChatsUiState.Initial.notesRow?.title)
    }
}
