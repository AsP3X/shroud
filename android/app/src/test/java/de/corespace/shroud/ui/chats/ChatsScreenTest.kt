package de.corespace.shroud.ui.chats

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import de.corespace.shroud.core.model.ChatDeleteOutcome
import de.corespace.shroud.core.model.ContactsListState
import de.corespace.shroud.core.model.ListStatus
import de.corespace.shroud.core.model.NOTES_DISPLAY_NAME
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.net.ChatMuteDto
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.PresenceDto
import de.corespace.shroud.testing.FakeAppClock
import de.corespace.shroud.ui.chats.ChatsFixtures.conversation
import de.corespace.shroud.ui.chats.ChatsFixtures.jane
import de.corespace.shroud.ui.chats.ChatsFixtures.mom
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.components.OverlayHost
import de.corespace.shroud.ui.shell.ChatRoute
import de.corespace.shroud.ui.shell.LocalIsTabBarSearchActive
import de.corespace.shroud.ui.shell.WindowLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * The Chats tab on a fake engine, read the way TalkBack reads it (shell-chats §8, §9; W3-CHATS
 * acceptance in JVM form): skeleton, the error under Notes, no matches, the offline banner, rows,
 * the row menu with its mute submenu, the delete sheet, pull-to-refresh's TalkBack action and New Chat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatsScreenTest {
    private val clock = FakeAppClock()
    private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val navigation = RecordingNavigation()
    private val loaded = ListStatus(hasLoadedChats = true, hasLoadedServerChats = true)

    @After
    fun tearDown() = actionScope.cancel()

    /**
     * Read by the hosted content: bumping it forces a frame. Compose's main-thread dispatcher
     * outlives a Robolectric test, and a trampolined dispatch the previous test's looper reset
     * dropped leaves effects waiting for the next frame — so every step here ends with one.
     */
    private val frame = mutableIntStateOf(0)

    private fun screen(
        source: FakeChatsSource,
        query: String = "",
        layout: WindowLayout = WindowLayout.Compact,
        searchActive: Boolean = false,
    ) = ComposeHarness {
        frame.intValue
        OverlayHost {
            CompositionLocalProvider(LocalIsTabBarSearchActive provides searchActive) {
                ChatsTab(source, query, {}, actionScope, clock, navigation, layout)
            }
        }
    }.also { it.settle() }

    private fun ComposeHarness.settle() {
        frame.intValue++
        idle()
        idle()
    }

    /** The one node whose spoken label is exactly [label]. */
    private fun ComposeHarness.labelled(label: String): SemanticsNode {
        val matches = nodes().filter { node -> node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == label } == true }
        check(matches.size == 1) { "expected one node labelled \"$label\", found ${matches.size}: ${describe()}" }
        return matches.single()
    }

    /** The one node whose spoken label starts with [prefix]. */
    private fun ComposeHarness.labelledStarting(prefix: String): SemanticsNode {
        val matches = nodes().filter { node -> node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(prefix) } == true }
        check(matches.size == 1) { "expected one node labelled \"$prefix…\", found ${matches.size}: ${describe()}" }
        return matches.single()
    }

    private fun ComposeHarness.click(node: SemanticsNode) {
        node.config[SemanticsActions.OnClick].action!!.invoke()
        settle()
    }

    private fun ComposeHarness.clickText(text: String) = click(nodesWithText(text).single { SemanticsActions.OnClick in it.config })

    private fun ComposeHarness.longPress(description: String) {
        labelled(description).config[SemanticsActions.OnLongClick].action!!.invoke()
        settle()
    }

    private fun ComposeHarness.texts(): List<String> =
        nodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun listed(vararg conversations: Pair<java.util.UUID, String>, unread: Map<java.util.UUID, Int> = emptyMap()) = FakeChatsSource(
        ChatsSnapshot(
            conversations = conversations.map { (peer, name) -> conversation(peer, name) },
            status = loaded,
            previews = conversations.associate { (peer, _) -> peer to "Ok" },
            unread = unread,
        ),
    )

    @Test
    fun theFirstLoadShowsNotesOverPlaceholdersAndAsksForTheList() {
        val source = FakeChatsSource()
        val ui = screen(source)
        ui.node("$NOTES_DISPLAY_NAME, Personal notes, photos & todos")
        ui.node("Loading")
        ui.node("New chat")
        assertTrue(ui.describe(), ui.nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Chats") == true })
        assertEquals(listOf("refreshConversations:false"), source.log)
    }

    @Test
    fun aFailedLoadShowsTheErrorUnderNotesAndRetries() {
        val source = FakeChatsSource(ChatsSnapshot(status = ListStatus(hasLoadedChats = true, chatsError = "Could not connect to the server.")))
        val ui = screen(source)
        ui.labelledStarting(NOTES_DISPLAY_NAME)
        val title = ui.nodesWithText("Can't load chats").single()
        assertTrue(SemanticsProperties.Heading in title.config)
        ui.nodesWithText("Could not connect to the server.").single()
        ui.click(ui.node("Try again"))
        assertEquals("refreshConversations:true", source.log.last())
    }

    @Test
    fun aSearchWithoutHitsSaysNoMatches() {
        val ui = screen(listed(jane to "jane"), query = "zz")
        ui.nodesWithText("No matches").single()
        ui.nodesWithText("Try a different name.").single()
        assertTrue(ui.describe(), ui.nodesWithText("New Chat").isEmpty())
        assertTrue(ui.describe(), ui.nodes().none { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith(NOTES_DISPLAY_NAME) } == true })
    }

    @Test
    fun offlineShowsTheBannerAsOneNode() {
        val source = listed(jane to "jane")
        val ui = screen(source)
        assertTrue(ui.describe(), ui.nodes().none { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Offline. Showing cached messages.") == true })
        source.chats.value = source.chats.value.copy(isOffline = true)
        ui.settle()
        ui.node("Offline. Showing cached messages.")
        assertTrue(ui.describe(), ui.nodesWithText("Offline — showing last 90 days").isEmpty())
    }

    @Test
    fun theTabBarSearchHidesTheHeaderField() {
        val withField = screen(listed(jane to "jane"))
        assertTrue(withField.describe(), withField.nodes().any { SemanticsActions.SetText in it.config })
        val withoutField = screen(listed(jane to "jane"), searchActive = true)
        assertTrue(withoutField.describe(), withoutField.nodes().none { SemanticsActions.SetText in it.config })
    }

    @Test
    fun rowsSpeakTheirFactsAndOpenTheirChat() {
        val source = listed(jane to "jane", mom to "mom", unread = mapOf(jane to 2))
        source.chats.value = source.chats.value.copy(mutedUntil = mapOf(mom to null))
        val ui = screen(source)
        val janeRow = ui.node("jane, Ok, 2 unread")
        ui.node("mom, Ok, muted")
        assertEquals("Chat options", janeRow.config[SemanticsActions.OnLongClick].label)
        ui.click(janeRow)
        ui.click(ui.labelledStarting(NOTES_DISPLAY_NAME))
        assertEquals(
            listOf("push:${ChatRoute.Conversation(jane, "jane")}", "push:${ChatRoute.Conversation(NOTES_PEER_ID, NOTES_DISPLAY_NAME)}"),
            navigation.log,
        )
    }

    @Test
    fun twoPaneOpensChatsInTheDetailAndMarksTheOpenOne() {
        val source = listed(jane to "jane")
        source.chats.value = source.chats.value.copy(activePeer = jane)
        val ui = screen(source, layout = WindowLayout.TwoPane)
        val row = ui.node("jane, Ok")
        assertEquals(true, row.config[SemanticsProperties.Selected])
        ui.click(row)
        assertEquals(listOf("openChat:$jane:jane"), navigation.log)
    }

    @Test
    fun theRowMenuOffersReadMuteAndDeleteAndMutesThroughTheSubmenu() {
        val source = listed(jane to "jane", unread = mapOf(jane to 1))
        source.mutes[jane] = ChatMuteDto(until = null)
        val ui = screen(source)
        ui.longPress("jane, Ok, 1 unread")
        val items = ui.texts()
        assertTrue(ui.describe(), items.containsAll(listOf("Mark as Read", "Mute", "Delete Chat")))
        assertTrue(ui.describe(), "Unmute" !in items)

        ui.clickText("Mute")
        assertTrue(ui.describe(), ui.texts().containsAll(listOf("For 1 Hour", "For 8 Hours", "For 1 Day", "For 7 Days", "Until I Turn It Back On")))
        ui.clickText("Until I Turn It Back On")
        ui.settle()
        assertEquals("muteChat:$jane:Forever", source.log.last())
        // The saved mute has no end: "Muted" (MuteDuration.label).
        assertTrue(ui.describe(), ui.nodesWithText("Muted").isNotEmpty())
    }

    @Test
    fun aMutedChatOffersUnmute() {
        val source = listed(jane to "jane")
        source.chats.value = source.chats.value.copy(mutedUntil = mapOf(jane to null))
        val ui = screen(source)
        ui.longPress("jane, Ok, muted")
        assertTrue(ui.describe(), "Unmute" in ui.texts())
        assertTrue(ui.describe(), "Mark as Read" !in ui.texts())
        ui.clickText("Unmute")
        ui.settle()
        assertEquals("unmuteChat:$jane", source.log.last())
        ui.nodesWithText("Notifications on").single()
    }

    @Test
    fun deleteChatAsksForTheScopeThenDeletes() {
        val source = listed(jane to "jane")
        source.deleteOutcome = ChatDeleteOutcome.ClearedForBoth
        val ui = screen(source)
        ui.longPress("jane, Ok")
        ui.clickText("Delete Chat")
        ui.nodesWithText("Delete chat with jane?").single()
        ui.nodesWithText("Deleting for both unsends your messages in jane's chat.").single()
        ui.nodesWithText("Cancel").single()
        ui.clickText("Delete for me and jane")
        ui.settle()
        assertEquals("deleteConversation:$jane:everyone", source.log.last())
        ui.nodesWithText("Chat deleted for both").single()
    }

    @Test
    fun notesOnlyOfferDeleteAllNotesForMe() {
        val source = listed(jane to "jane")
        val ui = screen(source)
        ui.longPress("$NOTES_DISPLAY_NAME, Personal notes, photos & todos")
        val items = ui.texts()
        assertTrue(ui.describe(), "Delete All Notes" in items)
        assertTrue(ui.describe(), "Mute" !in items && "Delete Chat" !in items)
        ui.clickText("Delete All Notes")
        ui.nodesWithText("Delete all notes?").single()
        ui.nodesWithText("Removes every note from this device and your account.").single()
        ui.clickText("Delete")
        ui.settle()
        assertEquals("deleteConversation:$NOTES_PEER_ID:me", source.log.last())
        ui.nodesWithText("Chat deleted").single()
    }

    @Test
    fun talkBackGetsTheMenuAsActions() {
        val source = listed(jane to "jane", unread = mapOf(jane to 3))
        val ui = screen(source)
        val row = ui.node("jane, Ok, 3 unread")
        val actions = row.config[SemanticsActions.CustomActions]
        assertEquals(listOf("Mark as Read", "Mute", "Delete Chat"), actions.map { it.label })
        actions.first { it.label == "Mark as Read" }.action()
        ui.settle()
        assertEquals("markChatRead:$jane", source.log.last())
        actions.first { it.label == "Delete Chat" }.action()
        ui.settle()
        ui.nodesWithText("Delete chat with jane?").single()
    }

    @Test
    fun pullToRefreshForcesAFetch() {
        val source = listed(jane to "jane")
        val ui = screen(source)
        val list = ui.nodes().first { node -> node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == "Refresh" } == true }
        list.config[SemanticsActions.CustomActions].first { it.label == "Refresh" }.action()
        ui.settle()
        assertEquals("refreshConversations:true", source.log.last())
    }

    @Test
    fun newChatListsContactsAndOpensThePickedChat() {
        val created = Instant.parse("2026-09-01T10:00:00Z")
        val source = listed(jane to "jane")
        source.roster.value = NewChatSnapshot(
            contacts = listOf(ContactItemDto(jane, "jane_cooper", created), ContactItemDto(mom, "mom", created)),
            listState = ContactsListState(hasLoaded = true),
            presence = mapOf(jane to PresenceDto(jane, online = true)),
        )
        val ui = screen(source)
        ui.click(ui.node("New chat"))
        ui.node("New Chat")
        ui.node("Search contacts")
        ui.node("mom, contact")
        ui.click(ui.node("jane_cooper, online"))
        assertEquals(listOf("push:${ChatRoute.Conversation(jane, "jane_cooper")}"), navigation.log)
        // The sheet closed: its rows are gone.
        assertTrue(ui.describe(), ui.nodes().none { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("mom, contact") == true })
        // A roster was there already: nothing was fetched.
        assertTrue(source.log.none { it.startsWith("refreshContacts") })
    }

    @Test
    fun newChatSaysWhatIsMissing() {
        val source = listed(jane to "jane")
        val ui = screen(source)
        ui.click(ui.node("New chat"))
        // Nothing cached: the roster is asked for, placeholders meanwhile.
        assertEquals("refreshContacts:false", source.log.last())
        ui.node("Loading")

        source.roster.value = NewChatSnapshot(listState = ContactsListState(hasLoaded = true, error = "Could not connect to the server."))
        ui.settle()
        ui.nodesWithText("Can't load contacts").single()

        source.roster.value = NewChatSnapshot(listState = ContactsListState(hasLoaded = true))
        ui.settle()
        ui.nodesWithText("No contacts").single()
        ui.nodesWithText("Add a contact first, then start a chat.").single()
    }

    @Test
    fun newChatCancelCloses() {
        val source = listed(jane to "jane")
        source.roster.value = NewChatSnapshot(listOf(ContactItemDto(jane, "jane_cooper", Instant.EPOCH)), ContactsListState(hasLoaded = true))
        val ui = screen(source)
        ui.click(ui.node("New chat"))
        ui.node("jane_cooper, contact")
        ui.clickText("Cancel")
        assertTrue(ui.describe(), ui.nodes().none { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("jane_cooper, contact") == true })
        assertTrue(navigation.log.isEmpty())
    }

    @Test
    fun theEmptyStatesNewChatButtonOpensNewChat() {
        // Unreachable in the app (Notes shows without a query) but ported for parity: render it alone.
        var opened by mutableStateOf(false)
        val ui = ComposeHarness { ChatsEmptyState(isSearching = false, onNewChat = { opened = true }) }
        ui.nodesWithText("No chats yet").single()
        ui.nodesWithText("Message a contact to start a conversation.").single()
        ui.clickText("New Chat")
        assertTrue(opened)
    }
}
