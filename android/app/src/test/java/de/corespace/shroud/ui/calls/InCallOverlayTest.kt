package de.corespace.shroud.ui.calls

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import de.corespace.shroud.core.calls.CallPhase
import de.corespace.shroud.core.net.CallModality
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.components.OverlayHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

/**
 * The call screen as the shell shows it, on a fake controller (as before G9 attaches the engine):
 * nothing without a call, the iOS controls per phase, the minimised pill, TalkBack on a new call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InCallOverlayTest {
    private var harness: ComposeHarness? = null

    @Before
    fun reset() {
        InCallPresentation.restore()
    }

    @After
    fun close() {
        harness?.close()
        InCallPresentation.restore()
    }

    private fun overlay(ports: FakeCallPorts): ComposeHarness = ComposeHarness {
        CompositionLocalProvider(LocalCallPorts provides ports) {
            OverlayHost { InCallOverlay() }
        }
    }.also { harness = it }

    private fun ComposeHarness.click(description: String) {
        node(description).config[SemanticsActions.OnClick].action!!.invoke()
        idle()
    }

    private fun ComposeHarness.labels(): List<String> =
        nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() }

    @Test
    fun drawsNothingWithoutACall() {
        val ports = FakeCallPorts()
        val ui = overlay(ports)
        assertTrue(ui.describe(), ui.labels().isEmpty())
        assertTrue(ui.describe(), ui.nodesWithText("anna").isEmpty())
        // The prompt is there for a call started from a chat.
        assertNotNull(ports.permissionPrompt)
        assertFalse("no engine is reached without a picture", "eglContext" in ports.calls)
    }

    @Test
    fun aRingInOffersDeclineAndAccept() {
        val ports = FakeCallPorts(CallFixtures.call(CallPhase.IncomingRinging, CallModality.Video))
        val ui = overlay(ports)
        val labels = ui.labels()
        assertTrue(ui.describe(), "Decline" in labels && "Accept" in labels)
        assertFalse(ui.describe(), "Mute" in labels)
        assertEquals(1, ui.nodesWithText("Incoming video call").size)
        // No Share while it rings in.
        assertFalse(ui.describe(), "Share your screen" in labels)
        ui.click("Accept")
        assertTrue(ports.calls.toString(), "acceptIncoming" in ports.calls)
        ui.click("Decline")
        assertTrue(ports.calls.toString(), "rejectIncoming" in ports.calls)
    }

    @Test
    fun aRunningCallOffersMuteVideoSpeakerAndEnd() {
        val ports = FakeCallPorts(CallFixtures.call(CallPhase.Active))
        val ui = overlay(ports)
        val labels = ui.labels()
        for (label in listOf("Mute", "Turn video on", "Turn speaker on", "End", "Share your screen")) {
            assertTrue("$label missing: ${ui.describe()}", label in labels)
        }
        assertEquals(1, ui.nodesWithText("Connected").size)
        ui.click("Mute")
        ui.click("Turn video on")
        ui.click("Turn speaker on")
        ui.click("End")
        assertEquals(listOf("toggleMute", "toggleVideo", "toggleSpeaker", "hangup"), ports.calls.filter { !it.startsWith("eglContext") })
    }

    @Test
    fun theControlsFollowTheCall() {
        val muted = CallFixtures.call(CallPhase.Active).copy(isMuted = true, isVideoEnabled = true, speakerOn = true)
        val ports = FakeCallPorts(muted)
        ports.isOnEarpiece.value = false
        val ui = overlay(ports)
        val labels = ui.labels()
        assertTrue(ui.describe(), "Unmute" in labels)
        assertTrue(ui.describe(), "Turn video off" in labels)
        assertTrue(ui.describe(), "Turn speaker off" in labels)
        // A refused speaker route does not show as on.
        ports.isOnEarpiece.value = true
        ui.idle()
        assertTrue(ui.describe(), "Turn speaker on" in ui.labels())
    }

    @Test
    fun videoIsDimmedWhenTheirAppCannotCarryIt() {
        val ports = FakeCallPorts(CallFixtures.call(CallPhase.Active).copy(canVideo = false))
        val ui = overlay(ports)
        assertTrue(ui.describe(), SemanticsProperties.Disabled in ui.node("Turn video on").config)
    }

    @Test
    fun theEndingScreenSaysHowItEnded() {
        val ports = FakeCallPorts(CallFixtures.call(CallPhase.Ending).copy(endedText = "No answer"))
        val ui = overlay(ports)
        assertEquals(ui.describe(), 1, ui.nodesWithText("No answer").size)
        // The row keeps its room but is gone for TalkBack.
        assertFalse(ui.describe(), "End" in ui.labels())
    }

    @Test
    fun anUnverifiedContactGetsTheBadge() {
        val ports = FakeCallPorts(CallFixtures.call(CallPhase.Active).copy(safetyVerified = false))
        ports.safetyNumber = (1..12).joinToString(" ") { "%05d".format(it) }
        val ui = overlay(ports)
        val badge = ui.node("Not verified")
        assertEquals("show the safety number to compare with anna", badge.config[SemanticsActions.OnClick].label)
        ui.click("Not verified")
        assertEquals(ui.describe(), 1, ui.nodesWithText("Mark as Verified").size)
        ui.nodesWithText("Mark as Verified").single().clickSelfOrParent()
        ui.idle()
        assertTrue(ports.calls.toString(), "confirmSafety" in ports.calls)
    }

    @Test
    fun aComparedNumberShowsNoBadge() {
        val ports = FakeCallPorts(CallFixtures.call(CallPhase.Active))
        ports.safetyNumber = "12345 67890"
        val ui = overlay(ports)
        assertFalse(ui.describe(), "Not verified" in ui.labels())
    }

    @Test
    fun talkBackMovesOntoANewCall() {
        val ports = FakeCallPorts(CallFixtures.call(CallPhase.IncomingRinging))
        val ui = overlay(ports)
        val focused = ui.nodes().filter { it.config.getOrNull(SemanticsProperties.Focused) == true }
        assertEquals(ui.describe(), 1, focused.size)
        assertTrue(ui.describe(), focused.single().text().contains("anna"))
    }

    @Test
    fun backMinimisesToThePillAndThePillBringsTheCallBack() {
        val ports = FakeCallPorts(CallFixtures.call(CallPhase.Active))
        val ui = overlay(ports)
        ui.activity.onBackPressedDispatcher.onBackPressed()
        ui.idle()
        assertEquals(CallFixtures.callId, InCallPresentation.minimizedCall.value)
        val labels = ui.labels()
        assertTrue(ui.describe(), "Return to call with anna" in labels)
        assertFalse(ui.describe(), "Mute" in labels)
        ui.click("Return to call with anna")
        assertNull(InCallPresentation.minimizedCall.value)
        assertTrue(ui.describe(), "Mute" in ui.labels())
    }

    @Test
    fun aNewCallShowsInFullEvenAfterTheLastWasMinimised() {
        val ports = FakeCallPorts(CallFixtures.call(CallPhase.Active))
        val ui = overlay(ports)
        ui.activity.onBackPressedDispatcher.onBackPressed()
        ui.idle()
        ports.ui.value = ports.ui.value.copy(active = CallFixtures.call(CallPhase.IncomingRinging, id = UUID.randomUUID(), name = "ben"))
        ui.idle()
        assertNull(InCallPresentation.minimizedCall.value)
        assertTrue(ui.describe(), "Accept" in ui.labels())
    }

    @Test
    fun theCallGoingAwayClearsTheScreen() {
        val ports = FakeCallPorts(CallFixtures.call(CallPhase.OutgoingRinging))
        val ui = overlay(ports)
        assertEquals(1, ui.nodesWithText("Calling…").size)
        ports.ui.value = ports.ui.value.copy(active = null)
        ui.idle()
        assertTrue(ui.describe(), ui.labels().isEmpty())
    }

    private fun SemanticsNode.text(): String =
        config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()

    private fun SemanticsNode.clickSelfOrParent() {
        var current: SemanticsNode? = this
        while (current != null) {
            val action = current.config.getOrNull(SemanticsActions.OnClick)?.action
            if (action != null) {
                action.invoke()
                return
            }
            current = current.parent
        }
        error("nothing clickable")
    }
}
