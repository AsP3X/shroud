package de.corespace.shroud.ui.calls

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import de.corespace.shroud.core.calls.CallHistoryState
import de.corespace.shroud.ui.components.ComposeHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Calls tab on a fake history: its states, rows, sections, call buttons and older pages (calls §9). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CallsScreenTest {
    private fun tab(ports: FakeCallPorts): ComposeHarness = ComposeHarness {
        CompositionLocalProvider(LocalCallPorts provides ports) { CallsScreen() }
    }

    private fun ComposeHarness.labels(): List<String> =
        nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() }

    private fun ComposeHarness.click(description: String) {
        node(description).config[SemanticsActions.OnClick].action!!.invoke()
        idle()
    }

    @Test
    fun theFirstLoadShowsTheSkeletonAndRefreshes() {
        val ports = FakeCallPorts()
        val ui = tab(ports)
        assertTrue(ui.describe(), "Loading" in ui.labels())
        assertEquals(1, ui.nodesWithText("Calls").size)
        assertTrue(ports.calls.toString(), "refreshHistory" in ports.calls)
    }

    @Test
    fun aFailedLoadSaysWhyAndTriesAgain() {
        val ports = FakeCallPorts(history = CallHistoryState(hasLoaded = true, error = "Couldn’t reach Shroud. Check your connection."))
        val ui = tab(ports)
        assertEquals(ui.describe(), 1, ui.nodesWithText("Can't load calls").size)
        assertEquals(1, ui.nodesWithText("Couldn’t reach Shroud. Check your connection.").size)
        ports.calls.clear()
        ui.click("Try again")
        assertTrue(ports.calls.toString(), "refreshHistory" in ports.calls)
    }

    @Test
    fun noCallsYet() {
        val ui = tab(FakeCallPorts(history = CallHistoryState(hasLoaded = true)))
        assertEquals(ui.describe(), 1, ui.nodesWithText("No calls yet").size)
        assertEquals(1, ui.nodesWithText("Your recent calls show up here.").size)
    }

    @Test
    fun aSingleCallIsOneStopAndTwoCallButtons() {
        val ports = FakeCallPorts(history = CallHistoryState(recent = listOf(CallFixtures.recent(1)), hasLoaded = true))
        val ui = tab(ports)
        val labels = ui.labels()
        assertTrue(ui.describe(), labels.any { it.startsWith("anna, outgoing voice call, 4 minutes, 12 seconds, ") })
        assertTrue(ui.describe(), "Call anna" in labels && "Video call anna" in labels)
        // The row's texts are folded into its label.
        assertTrue(ui.describe(), ui.nodesWithText("Outgoing voice").isEmpty())
        // The last row on screen asks for the next older page.
        assertTrue(ports.calls.toString(), "loadOlderHistory" in ports.calls)
        ui.click("Video call anna")
        assertTrue(ports.calls.toString(), "startCall:anna:video" in ports.calls)
    }

    @Test
    fun aCallThatCannotStartSaysWhy() {
        val ports = FakeCallPorts(history = CallHistoryState(recent = listOf(CallFixtures.recent(1)), hasLoaded = true))
        ports.lastError.value = "Allow microphone access for Shroud in Settings to call."
        val ui = tab(ports)
        ui.click("Call anna")
        assertEquals(ui.describe(), 1, ui.nodesWithText("Allow microphone access for Shroud in Settings to call.").size)
    }

    @Test
    fun aDeletedAccountCannotBeCalledBack() {
        val gone = CallFixtures.recent(1, name = "Deleted account", deleted = true)
        val ui = tab(FakeCallPorts(history = CallHistoryState(recent = listOf(gone), hasLoaded = true)))
        assertFalse(ui.describe(), ui.labels().any { it.startsWith("Call ") || it.startsWith("Video call ") })
    }

    @Test
    fun callsInARowShareASectionThatOpens() {
        val calls = listOf(
            CallFixtures.recent(3, minutesAgo = 0),
            CallFixtures.recent(2, minutesAgo = 10, status = "missed", outgoing = false, connected = false, seconds = null),
            CallFixtures.recent(1, minutesAgo = 20),
        )
        val ui = tab(FakeCallPorts(history = CallHistoryState(recent = calls, hasLoaded = true)))
        val header = ui.node("anna, 3 calls, 1 missed")
        assertEquals("Collapsed", header.config[SemanticsProperties.StateDescription])
        assertEquals("show the calls", header.config[SemanticsActions.OnClick].label)
        assertFalse(ui.describe(), ui.labels().any { it.startsWith("Incoming voice call, missed") })
        ui.click("anna, 3 calls, 1 missed")
        assertEquals("Expanded", ui.node("anna, 3 calls, 1 missed").config[SemanticsProperties.StateDescription])
        assertTrue(ui.describe(), ui.labels().any { it.startsWith("Incoming voice call, Missed") || it.startsWith("Incoming voice call, missed") })
        assertEquals(ui.describe(), 3, ui.labels().count { it == "Call anna" })
    }

    @Test
    fun aFailedOlderPageCanBeTriedAgain() {
        val ports = FakeCallPorts(history = CallHistoryState(recent = listOf(CallFixtures.recent(1)), hasLoaded = true, olderFailed = true))
        val ui = tab(ports)
        ports.calls.clear()
        ui.nodesWithText("Load older calls").single().config[SemanticsActions.OnClick].action!!.invoke()
        ui.idle()
        assertTrue(ports.calls.toString(), "loadOlderHistory" in ports.calls)
    }

    @Test
    fun anOlderPageLoadingShowsASpinner() {
        val ports = FakeCallPorts(history = CallHistoryState(recent = listOf(CallFixtures.recent(1)), hasLoaded = true, loadingOlder = true))
        val ui = tab(ports)
        assertTrue(ui.describe(), "Loading older calls" in ui.labels())
    }
}
