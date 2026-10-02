package de.corespace.shroud.ui.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ApplicationProvider
import de.corespace.shroud.ShroudApplication
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConnectionMode
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.components.OverlayHost
import de.corespace.shroud.ui.shell.AppActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Server on the app's own server store and the shell's Log Out (settings-lock §10.1;
 * W3-SETTINGS-A acceptance "server change goes through `AppActions.logOut(switchingTo)`"): signed in,
 * another endpoint asks first and then signs out **without saving** — the Log Out stores it after the
 * wipe (`ServerSettingsView.swift:506-510, 529-531`); the same endpoint is stored and the page pops.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServerSettingsScreenTest {
    private val app = ApplicationProvider.getApplicationContext<ShroudApplication>()
    private val store get() = app.container.serverConfiguration

    private class RecordingActions : AppActions {
        val switches = ArrayList<ServerConfiguration>()
        var plainLogOuts = 0

        override fun logOut() {
            plainLogOuts++
        }

        override fun logOut(switchingTo: ServerConfiguration) {
            switches += switchingTo
        }

        override fun lockChatsNow() = Unit

        override val isLoggingOut: StateFlow<Boolean> = MutableStateFlow(false)
    }

    private fun SemanticsNode.click() = config[SemanticsActions.OnClick].action!!.invoke()

    private fun ComposeHarness.labelled(prefix: String): SemanticsNode =
        nodes().first { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(prefix) } == true && SemanticsActions.OnClick in node.config
        }

    @Test
    fun anotherEndpointWhileSignedInLogsOutSwitchingToItAndSavesNothing() {
        val start = ServerConfiguration.localDevelopment("10.0.2.2", 8080)
        store.save(start)
        val actions = RecordingActions()
        var popped = 0
        val ui = ComposeHarness {
            OverlayHost { ServerSettingsScreen(store, signedIn = true, actions = actions, onBack = { popped++ }, pause = {}) }
        }
        ui.labelled("Official Shroud server").click()
        ui.idle()
        ui.labelled("Save").click()
        ui.idle()
        assertTrue(ui.describe(), ui.nodesWithText("Change server?").isNotEmpty())
        ui.nodes().single { node -> node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } == listOf("Save and sign out") }.click()
        ui.idle()

        assertEquals("one Log Out, switching to the official server", listOf(ServerConnectionMode.Official), actions.switches.map { it.mode })
        assertEquals(ServerConfiguration.official.resolvedBaseUrl, actions.switches.single().resolvedBaseUrl)
        assertEquals("never the plain Log Out", 0, actions.plainLogOuts)
        assertEquals("nothing saved before the wipe", start, store.configuration.value)
        assertEquals("the page waits for the wipe instead of popping", 0, popped)
    }

    @Test
    fun theSameEndpointIsSavedAndThePagePops() {
        val start = ServerConfiguration.localDevelopment("10.0.2.2", 8080)
        store.save(start)
        val actions = RecordingActions()
        var popped = 0
        val ui = ComposeHarness {
            OverlayHost { ServerSettingsScreen(store, signedIn = true, actions = actions, onBack = { popped++ }, pause = {}) }
        }
        ui.labelled("Save").click()
        ui.idle()
        assertTrue(ui.nodesWithText("Change server?").isEmpty())
        assertEquals(start, store.configuration.value)
        assertEquals(1, popped)
        assertTrue(actions.switches.isEmpty())
    }

    @Test
    fun signedOutAnotherEndpointIsSavedWithoutAsking() {
        // Not reachable from Settings (it is signed in), but the rule is the page's: no session, no sign-out.
        store.save(ServerConfiguration.localDevelopment("10.0.2.2", 8080))
        val actions = RecordingActions()
        val ui = ComposeHarness {
            OverlayHost { ServerSettingsScreen(store, signedIn = false, actions = actions, onBack = {}, pause = {}) }
        }
        ui.labelled("Official Shroud server").click()
        ui.idle()
        ui.labelled("Save").click()
        ui.idle()
        assertEquals(ServerConnectionMode.Official, store.configuration.value.mode)
        assertTrue(actions.switches.isEmpty())
    }
}
