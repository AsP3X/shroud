package de.corespace.shroud.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.notifications.NotificationKind
import de.corespace.shroud.core.notifications.NotificationOpenRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * The main shell on real window sizes (shell-chats §4.2, §4.6, §4.9; P12a; design `Chats · 360`
 * zMjzp and `Unfolded / Tablet — Two Pane` KQGfV), with fake screens: the tab bar's frame and items,
 * the clearance the root lists pad by, the two panes from 600 dp, a resize across 600 dp that keeps
 * the tab root, and a tap made on the lock screen opening its chat once the shell appears.
 *
 * Robolectric's window has gesture navigation (no tappable bottom inset): the bar rests 20 dp off
 * the screen bottom.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainShellLayoutTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val alice = UUID.fromString("3f2c8a9e-5b1d-4c7a-9e2f-8d6b4a1c0e7f")

    @After
    fun tearDown() = scope.cancel()

    private fun router() = AppRouter(FakeShellEnvironment(), scope)

    private fun shell(screens: FakeShellScreens = FakeShellScreens()): Pair<ShellUiHarness, FakeShellScreens> =
        ShellUiHarness { MainShell(router(), isRevealed = true, screens = screens) } to screens

    // ---- Compact (shell-chats §4.2, §4.9; zMjzp) ----

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun at360TheBarIs320WideWith60DpTabs() {
        val (ui, screens) = shell()
        val chats = ui.boundsDp(ui.node("Chats"))
        val settings = ui.boundsDp(ui.node("Settings"))
        // 20 dp sides; capsule 320 − 64 − 8 = 248; items (248 − 8) / 4 = 60 × 56, inside the 4 dp rim.
        assertEquals(24f, chats.left, 0.5f)
        assertEquals(60f, chats.width, 0.5f)
        assertEquals(56f, chats.height, 0.5f)
        assertEquals(24f + 3 * 60f, settings.left, 0.5f)
        // The search circle closes the row at the right edge, 20 dp in.
        val search = ui.boundsDp(ui.node("Search"))
        assertEquals(340f, search.right, 0.5f)
        assertEquals(64f, search.width, 0.5f)
        // Gesture navigation: the bar's bottom 20 dp off the screen edge, its top 84 dp up.
        assertEquals(ui.rootHeightDp() - 20f, search.bottom, 0.5f)
        assertEquals(84f, screens.clearance!!.value, 0.5f)
        assertTrue(ui.has("root:Chats"))
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun onThe412DesignFrameTheTabsAre73Wide() {
        val (ui, _) = shell()
        // Design `Tab Chats` 73 × 56 at x 4 inside the capsule (shell-chats §5.2).
        assertEquals(73f, ui.boundsDp(ui.node("Chats")).width, 0.5f)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun aPushedChatCoversTheBarAndBackReturnsToTheListWithIt() {
        val (ui, screens) = shell()
        val navigator = requireNotNull(screens.navigator)
        navigator.push(ChatRoute.Conversation(alice, "alice"))
        ui.idle()
        assertTrue(ui.has("route:chat:alice"))
        assertFalse("the bar sits under the pushed chat", ui.has("Chats"))
        ui.activity.onBackPressedDispatcher.onBackPressed()
        ui.idle()
        assertFalse(ui.has("route:chat:alice"))
        assertTrue(ui.has("Chats"))
    }

    // ---- Two-pane (shell-chats §4.9; KQGfV) ----

    @Test
    @Config(qualifiers = "w840dp-h915dp")
    fun from600DpTheListPaneIs360WithItsBarAndTheDetailWaitsForAChat() {
        val (ui, screens) = shell()
        val root = ui.boundsDp(ui.node("root:Chats"))
        assertEquals(0f, root.left, 0.5f)
        assertEquals(360f, root.width, 0.5f)
        // The bar lives in the list pane: 320 wide, capsule 248, 60 dp items.
        assertEquals(60f, ui.boundsDp(ui.node("Chats")).width, 0.5f)
        assertTrue(ui.boundsDp(ui.node("Search")).right <= 360f)
        // Nothing open: the placeholder copy (web `AppShell.tsx:2436-2441`).
        assertTrue(ui.hasText(SELECT_CONVERSATION_TITLE))
        assertEquals(84f, screens.clearance!!.value, 0.5f)
    }

    @Test
    @Config(qualifiers = "w840dp-h915dp")
    fun aChatOpensInTheDetailPaneAndTheBarStaysUp() {
        val screens = FakeShellScreens()
        screens.known[alice] = "alice"
        screens.pending = NotificationOpenRequest(NotificationKind.Message, alice, null)
        val ui = ShellUiHarness { MainShell(router(), isRevealed = true, screens = screens) }
        val chat = ui.boundsDp(ui.node("route:chat:alice"))
        assertEquals(361f, chat.left, 0.5f)
        assertEquals(ui.rootWidthDp() - 361f, chat.width, 0.5f)
        assertTrue("the list and its bar stay beside the chat", ui.has("Chats"))
        assertTrue(ui.has("root:Chats"))
        // Back clears the selection: the placeholder returns.
        ui.activity.onBackPressedDispatcher.onBackPressed()
        ui.idle()
        assertFalse(ui.has("route:chat:alice"))
        assertTrue(ui.hasText(SELECT_CONVERSATION_TITLE))
    }

    @Test
    @Config(qualifiers = "w840dp-h915dp")
    fun aResizeAcross600KeepsTheTabRootAndTheOpenChat() {
        var width by mutableStateOf(840.dp)
        val screens = FakeShellScreens()
        screens.known[alice] = "alice"
        screens.pending = NotificationOpenRequest(NotificationKind.Message, alice, null)
        val ui = ShellUiHarness {
            Box(Modifier.width(width)) { MainShell(router(), isRevealed = true, screens = screens) }
        }
        assertEquals(360f, ui.boundsDp(ui.node("root:Chats")).width, 0.5f)
        width = 412.dp
        ui.idle()
        // Compact: the chat is pushed over the list, the same stack.
        assertEquals(412f, ui.boundsDp(ui.node("route:chat:alice")).width, 0.5f)
        width = 840.dp
        ui.idle()
        assertEquals(360f, ui.boundsDp(ui.node("root:Chats")).width, 0.5f)
        assertEquals(1, screens.rootBuilds[MainTab.Chats])
    }

    // ---- Opens (shell-chats §4.6; notifications-push §5.7.5) ----

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun aTapMadeOnTheLockScreenOpensItsChatOnceTheShellAppears() {
        var unlocked by mutableStateOf(false)
        val screens = FakeShellScreens()
        screens.known[alice] = "alice"
        // Tapped while locked: the request waits, nothing composes the shell yet.
        screens.pending = NotificationOpenRequest(NotificationKind.Message, alice, null)
        val ui = ShellUiHarness { if (unlocked) MainShell(router(), isRevealed = true, screens = screens) }
        assertFalse(ui.has("route:chat:alice"))
        assertTrue(screens.pending != null)
        unlocked = true
        ui.idle()
        assertTrue(ui.has("route:chat:alice"))
        assertNull("handled once", screens.pending)
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun anUnknownPeerWaitsForTheServersFirstList() {
        val screens = FakeShellScreens()
        screens.hasLoadedServerChats = false
        screens.pending = NotificationOpenRequest(NotificationKind.Message, alice, null)
        val ui = ShellUiHarness { MainShell(router(), isRevealed = true, screens = screens) }
        assertFalse(ui.has("route:chat:alice"))
        assertTrue("still pending", screens.pending != null)
        screens.known[alice] = "alice"
        screens.hasLoadedServerChats = true
        ui.idle()
        assertTrue(ui.has("route:chat:alice"))
        assertNull(screens.pending)
    }
}
