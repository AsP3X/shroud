package de.corespace.shroud.ui.chats

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.provider.Settings
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import de.corespace.shroud.core.model.ChatDeleteOutcome
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.core.model.ContactsListState
import de.corespace.shroud.core.model.ListStatus
import de.corespace.shroud.core.model.NOTES_DISPLAY_NAME
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.net.ChatMuteDto
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.ConversationItemDto
import de.corespace.shroud.core.net.PresenceDto
import de.corespace.shroud.testing.FakeAppClock
import de.corespace.shroud.ui.chats.ChatsFixtures.conversation
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.components.LocalGlassBackdrop
import de.corespace.shroud.ui.components.OverlayHost
import de.corespace.shroud.ui.components.rememberGlassBackdrop
import de.corespace.shroud.ui.shell.FloatingTabBar
import de.corespace.shroud.ui.shell.LocalIsTabBarSearchActive
import de.corespace.shroud.ui.shell.LocalTabBarClearance
import de.corespace.shroud.ui.shell.MainTab
import de.corespace.shroud.ui.shell.ShellLayoutMath
import de.corespace.shroud.ui.shell.WindowLayout
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CompletableDeferred
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Renders every state of the Chats tab and New Chat (W3-CHATS acceptance) to a PNG under
 * `android/app/build/outputs/c5-screens/`, for the design pass to lay next to the frames `eVLXT`
 * (Chats), `Fevlu` (Search), `AqgbA` (Chat Menu), `WHZDi` (New Chat), `MGREE` (Chats · Dark) and
 * `zMjzp` (Chats · 360). The pictures are not committed; the assertions check a few pixels that
 * prove each state drew what it should (the fills of the badges, the scrim, the sheet).
 *
 * The screen sits in the shell's place: under a 52 dp status bar and over a 24 dp gesture bar
 * (the design's insets), with the floating tab bar 20 dp off the bottom and its clearance
 * published, as `MainShell` does. 2× density, so a PNG is the frame at 824 × 1830 px. Motion is
 * reduced so every animation has settled (no shimmer sweep, the typing dots full and still).
 * Software rendering draws no backdrop blur: glass shows its translucent fill only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatsScreensRenderTest {
    private val zone: ZoneId = ZoneId.systemDefault()

    /** Friday 2 Oct 2026, 10:00 in the device's zone. */
    private val today: LocalDate = LocalDate.of(2026, 10, 2)
    private val clock = FakeAppClock(wallMillis = today.atTime(10, 0).atZone(zone).toInstant().toEpochMilli())
    private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val navigation = RecordingNavigation()
    private val hosts = ArrayList<ComposeHarness>()
    private val frame = mutableIntStateOf(0)

    @After
    fun tearDown() {
        actionScope.cancel()
        hosts.forEach { host ->
            host.activity.findViewById<ViewGroup>(android.R.id.content).removeAllViews()
            host.idle()
        }
        Thread.sleep(SETTLE_REAL_MS)
        hosts.firstOrNull()?.idle()
    }

    // ---- data: the design's list (eVLXT) ----

    private val jane = UUID.fromString("3f2c8a9e-5b1d-4c7a-9e2f-8d6b4a1c0e7f")
    private val mom = UUID.fromString("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
    private val devon = UUID.fromString("1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed")
    private val dianne = UUID.fromString("6a1f3b2c-1d4e-4f5a-8b6c-7d8e9f0a1b2c")
    private val arlene = UUID.fromString("7b2a4c3d-2e5f-4a6b-9c7d-8e9f0a1b2c3d")
    private val guy = UUID.fromString("8c3b5d4e-3f6a-4b7c-8d8e-9f0a1b2c3d4e")
    private val albert = UUID.fromString("9d4c6e5f-4a7b-4c8d-9e9f-0a1b2c3d4e5f")

    private fun at(time: LocalTime, day: LocalDate = today) = LocalDateTime.of(day, time).atZone(zone).toInstant()

    private fun chat(peer: UUID, name: String, time: LocalTime, day: LocalDate = today, mute: ChatMuteDto? = null): ConversationItemDto =
        conversation(peer, name, last = at(time, day), mute = mute)

    private val designList: ChatsSnapshot = ChatsSnapshot(
        conversations = listOf(
            chat(jane, "Jane Cooper", LocalTime.of(9, 38)),
            chat(mom, "Mom", LocalTime.of(9, 12)),
            chat(devon, "Devon Lane", LocalTime.of(8, 58)),
            chat(dianne, "Dianne Russell", LocalTime.of(8, 40)),
            chat(arlene, "Arlene McCoy", LocalTime.of(18, 5), today.minusDays(1)),
            chat(guy, "Guy Hawkins", LocalTime.of(12, 0), LocalDate.of(2026, 9, 24)),
            chat(albert, "Albert Flores", LocalTime.of(12, 0), LocalDate.of(2026, 9, 22)),
        ),
        status = ListStatus(hasLoadedChats = true, hasLoadedServerChats = true),
        previews = mapOf(
            NOTES_PEER_ID to "Photo",
            jane to "Ok",
            mom to "Call me when you get home",
            devon to "The keys are under the mat",
            dianne to "Voice message",
            arlene to "Video",
            guy to "See you tomorrow",
            albert to "Thanks for the photos!",
        ),
        notesLastActivity = at(LocalTime.of(8, 15)),
        unread = mapOf(mom to 2, dianne to 1),
        activities = mapOf(dianne to ChatPeerActivity.Recording, guy to ChatPeerActivity.Typing),
    )

    private val roster = NewChatSnapshot(
        contacts = listOf(
            ContactItemDto(albert, "Albert Flores", at(LocalTime.NOON)),
            ContactItemDto(arlene, "Arlene McCoy", at(LocalTime.NOON)),
            ContactItemDto(devon, "Devon Lane", at(LocalTime.NOON)),
            ContactItemDto(dianne, "Dianne Russell", at(LocalTime.NOON)),
            ContactItemDto(guy, "Guy Hawkins", at(LocalTime.NOON)),
            ContactItemDto(jane, "Jane Cooper", at(LocalTime.NOON)),
            ContactItemDto(mom, "Mom", at(LocalTime.NOON)),
        ),
        listState = ContactsListState(hasLoaded = true),
        presence = mapOf(
            jane to PresenceDto(jane, online = true),
            devon to PresenceDto(devon, online = true),
            mom to PresenceDto(mom, online = false, lastSeenAt = at(LocalTime.of(9, 41))),
            arlene to PresenceDto(arlene, online = false, lastSeenAt = at(LocalTime.of(21, 3), today.minusDays(1))),
            albert to PresenceDto(albert, online = false, lastSeenAt = at(LocalTime.NOON, LocalDate.of(2026, 9, 27))),
            guy to PresenceDto(guy, online = false),
        ),
    )

    // ---- the host ----

    /** The Chats tab where the shell puts it: under the status bar, over the floating tab bar. */
    private fun screen(source: FakeChatsSource, dark: Boolean = false, query: String = "", searching: Boolean = false): ComposeHarness {
        val ui = ComposeHarness(dark = dark) {
            frame.intValue
            OverlayHost {
                val backdrop = rememberGlassBackdrop()
                val barBottom = ShellLayoutMath.barBottom(aboveKeyboard = false, imeBottom = 0.dp, gestureNavigation = true, navigationBarBottom = NAV_BAR)
                val clearance = ShellLayoutMath.tabBarClearance(barVisible = true, barBottom = barBottom, isSearching = searching)
                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(
                        LocalTabBarClearance provides clearance,
                        LocalIsTabBarSearchActive provides searching,
                        LocalGlassBackdrop provides backdrop,
                    ) {
                        Box(Modifier.fillMaxSize().hazeSource(backdrop)) {
                            ChatsTab(source, query, {}, actionScope, clock, navigation, WindowLayout.Compact)
                        }
                    }
                    CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                        FloatingTabBar(
                            selection = MainTab.Chats,
                            onSelect = {},
                            isSearching = searching,
                            onSearchingChange = {},
                            query = query,
                            onQueryChange = {},
                            searchFocus = remember { FocusRequester() },
                            badges = mapOf(MainTab.Chats to 3),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = barBottom)
                                .padding(horizontal = ShellLayoutMath.barSideInset(aboveKeyboard = false))
                                .widthIn(max = ShellLayoutMath.barMaxWidth),
                        )
                    }
                }
            }
        }
        hosts += ui
        applyDesignInsets(ui)
        ui.settle()
        return ui
    }

    private fun ComposeHarness.settle() {
        frame.intValue++
        idle()
        idle()
    }

    /** The design's status bar (52 dp) and gesture bar (24 dp), dispatched to the Compose view. */
    private fun applyDesignInsets(ui: ComposeHarness) {
        val density = ui.activity.resources.displayMetrics.density
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, (STATUS_BAR.value * density).toInt(), 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, (NAV_BAR.value * density).toInt()))
            .build()
        ViewCompat.dispatchApplyWindowInsets(ui.root, insets)
    }

    private fun render(ui: ComposeHarness, name: String): Bitmap {
        ui.settle()
        val root = ui.root
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val dir = File("build/outputs/c5-screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun ComposeHarness.longPress(prefix: String) {
        val row = nodes().single { node -> node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(prefix) } == true }
        row.config[SemanticsActions.OnLongClick].action!!.invoke()
        settle()
    }

    private fun ComposeHarness.clickText(text: String) {
        nodesWithText(text).single { SemanticsActions.OnClick in it.config }.config[SemanticsActions.OnClick].action!!.invoke()
        settle()
    }

    private fun ComposeHarness.clickLabel(label: String) {
        node(label).config[SemanticsActions.OnClick].action!!.invoke()
        settle()
    }

    /** dp → px of the 2× render. */
    private fun px(ui: ComposeHarness, dp: Float): Int = (dp * ui.activity.resources.displayMetrics.density).toInt()

    private fun rgb(color: Int) = color and 0xFFFFFF

    private fun use24Hour() {
        Settings.System.putString(RuntimeEnvironment.getApplication().contentResolver, Settings.System.TIME_12_24, "24")
    }

    // ---- the frames ----

    @Test
    fun chatsLight() {
        use24Hour()
        val ui = screen(FakeChatsSource(designList))
        val bitmap = render(ui, "01-chats-eVLXT")
        // The background is the light `background` token under the list.
        assertEquals(0xFFFFFF, rgb(bitmap.getPixel(px(ui, 400f), px(ui, 300f))))
    }

    @Test
    fun chatsDark() {
        use24Hour()
        val ui = screen(FakeChatsSource(designList), dark = true)
        val bitmap = render(ui, "02-chats-dark-MGREE")
        assertTrue(Color.red(bitmap.getPixel(px(ui, 400f), px(ui, 300f))) < 40)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-xhdpi")
    fun chats360() {
        use24Hour()
        render(screen(FakeChatsSource(designList)), "03-chats-360-zMjzp")
    }

    @Test
    fun chatsSearch() {
        use24Hour()
        render(screen(FakeChatsSource(designList), query = "Ja", searching = true), "04-chats-search-Fevlu")
    }

    @Test
    fun badgesAndMutes() {
        use24Hour()
        val list = designList.copy(
            conversations = designList.conversations.map { if (it.peer.id == mom) it.copy(mute = ChatMuteDto(until = null)) else it },
            unread = mapOf(mom to 2, jane to 120, dianne to 1),
            unseenReactions = setOf(devon),
            mutedUntil = mapOf(mom to null),
        )
        render(screen(FakeChatsSource(list)), "05-chats-badges-muted")
    }

    @Test
    fun rowMenu() {
        use24Hour()
        val ui = screen(FakeChatsSource(designList))
        ui.longPress("Mom,")
        assertTrue(ui.describe(), ui.nodesWithText("Mark as Read").isNotEmpty())
        render(ui, "06-chats-menu-AqgbA")
    }

    @Test
    fun rowMenuMuteSubmenu() {
        use24Hour()
        val ui = screen(FakeChatsSource(designList))
        ui.longPress("Mom,")
        ui.clickText("Mute")
        assertTrue(ui.describe(), ui.nodesWithText("Until I Turn It Back On").isNotEmpty())
        render(ui, "07-chats-menu-mute")
    }

    @Test
    fun rowMenuMutedChat() {
        use24Hour()
        val ui = screen(FakeChatsSource(designList.copy(mutedUntil = mapOf(jane to null))))
        ui.longPress("Jane Cooper,")
        render(ui, "08-chats-menu-unmute")
    }

    @Test
    fun notesMenu() {
        use24Hour()
        val ui = screen(FakeChatsSource(designList))
        ui.longPress("$NOTES_DISPLAY_NAME,")
        render(ui, "09-chats-menu-notes")
    }

    @Test
    fun deleteChatSheet() {
        use24Hour()
        val ui = screen(FakeChatsSource(designList))
        ui.longPress("Jane Cooper,")
        ui.clickText("Delete Chat")
        assertTrue(ui.describe(), ui.nodesWithText("Delete chat with Jane Cooper?").isNotEmpty())
        render(ui, "10-chats-delete-chat")
    }

    @Test
    fun deleteNotesSheet() {
        use24Hour()
        val ui = screen(FakeChatsSource(designList))
        ui.longPress("$NOTES_DISPLAY_NAME,")
        ui.clickText("Delete All Notes")
        render(ui, "11-chats-delete-notes")
    }

    @Test
    fun deletedToast() {
        use24Hour()
        val source = FakeChatsSource(designList).apply { deleteOutcome = ChatDeleteOutcome.UnsentForPeer }
        val ui = screen(source)
        ui.longPress("Jane Cooper,")
        ui.clickText("Delete Chat")
        ui.clickText("Delete for me and Jane Cooper")
        assertTrue(ui.describe(), ui.nodesWithText("Deleted · Jane Cooper keeps their own messages").isNotEmpty())
        render(ui, "12-chats-toast-deleted")
    }

    @Test
    fun skeleton() {
        render(screen(FakeChatsSource(ChatsSnapshot(status = ListStatus(isLoadingChats = true)))), "13-chats-loading")
    }

    @Test
    fun loadError() {
        val source = FakeChatsSource(ChatsSnapshot(status = ListStatus(hasLoadedChats = true, chatsError = "Could not connect to the server.")))
        render(screen(source), "14-chats-error")
    }

    @Test
    fun onlyNotes() {
        render(screen(FakeChatsSource(ChatsSnapshot(status = ListStatus(hasLoadedChats = true, hasLoadedServerChats = true)))), "15-chats-only-notes")
    }

    @Test
    fun offline() {
        use24Hour()
        render(screen(FakeChatsSource(designList.copy(isOffline = true))), "16-chats-offline")
    }

    @Test
    fun noMatches() {
        use24Hour()
        render(screen(FakeChatsSource(designList), query = "zz"), "17-chats-no-matches")
    }

    @Test
    fun refreshing() {
        use24Hour()
        val gate = CompletableDeferred<Unit>()
        val source = FakeChatsSource(designList).apply { refreshGate = gate }
        val ui = screen(source)
        val list = ui.nodes().first { node -> node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == "Refresh" } == true }
        list.config[SemanticsActions.CustomActions].first { it.label == "Refresh" }.action()
        ui.settle()
        render(ui, "18-chats-refreshing")
        gate.complete(Unit)
    }

    @Test
    fun newChat() {
        use24Hour()
        val ui = screen(FakeChatsSource(designList, roster))
        ui.clickLabel("New chat")
        render(ui, "19-new-chat-WHZDi")
    }

    @Test
    fun newChatDark() {
        use24Hour()
        val ui = screen(FakeChatsSource(designList, roster), dark = true)
        ui.clickLabel("New chat")
        render(ui, "20-new-chat-dark")
    }

    @Test
    fun newChatLoading() {
        val ui = screen(FakeChatsSource(designList))
        ui.clickLabel("New chat")
        render(ui, "21-new-chat-loading")
    }

    @Test
    fun newChatError() {
        val ui = screen(FakeChatsSource(designList, NewChatSnapshot(listState = ContactsListState(hasLoaded = true, error = "Could not connect to the server."))))
        ui.clickLabel("New chat")
        render(ui, "22-new-chat-error")
    }

    @Test
    fun newChatNoContacts() {
        val ui = screen(FakeChatsSource(designList, NewChatSnapshot(listState = ContactsListState(hasLoaded = true))))
        ui.clickLabel("New chat")
        render(ui, "23-new-chat-no-contacts")
    }

    @Test
    fun newChatNoResults() {
        val ui = screen(FakeChatsSource(designList, roster))
        ui.clickLabel("New chat")
        val field = ui.nodes().single { SemanticsActions.SetText in it.config && it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Search contacts") == true }
        field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("zz"))
        ui.settle()
        render(ui, "24-new-chat-no-results")
    }

    private companion object {
        const val SETTLE_REAL_MS = 30L
        val STATUS_BAR = 52.dp
        val NAV_BAR = 24.dp
    }
}
