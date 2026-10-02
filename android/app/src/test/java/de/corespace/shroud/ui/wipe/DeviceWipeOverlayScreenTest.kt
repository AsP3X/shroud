package de.corespace.shroud.ui.wipe

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import de.corespace.shroud.core.auth.DeviceDataWipe
import de.corespace.shroud.core.auth.WipePhase
import de.corespace.shroud.core.auth.WipeReason
import de.corespace.shroud.core.auth.WipeStep
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.onboarding.HarnessHosts
import de.corespace.shroud.ui.onboarding.button
import de.corespace.shroud.ui.onboarding.click
import de.corespace.shroud.ui.onboarding.hasButton
import de.corespace.shroud.ui.onboarding.hasTag
import de.corespace.shroud.ui.onboarding.shows
import de.corespace.shroud.ui.onboarding.tagged
import de.corespace.shroud.ui.onboarding.unmergedNodes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The wipe overlay as TalkBack meets it (`DeviceWipeOverlay.swift`; settings-lock §14.5; design
 * `qaRQA`, `ZFJ1m`): the modal pane, each row's state, the failure footer, the polite live region
 * that speaks `DeviceWipeController.feedback`, and the leaving overlay that is gone for TalkBack.
 * The touch release itself is the device test `DeviceWipeOverlayTest` (androidTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DeviceWipeOverlayScreenTest {
    private val hosts = HarnessHosts()
    private var state by mutableStateOf(WipeOverlayState(WipePhase.Running, handle = "@alice"))
    private var presented by mutableStateOf(true)
    private var announcement by mutableStateOf<String?>(null)
    private var retries = 0
    private var continues = 0

    @After
    fun tearDown() = hosts.disposeAll()

    private fun overlay() = hosts.host {
        DeviceWipeOverlayContent(state, presented, "phone", onRetry = { retries++ }, onContinue = { continues++ }, announcement = announcement)
    }

    private fun ComposeHarness.row(step: WipeStep): SemanticsNode =
        nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(step.title) }

    private val SemanticsNode.state: String? get() = config.getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun whileRunningEachRowSaysWhereItIs() {
        // Design `qaRQA`: three done, one running, two waiting.
        state = state.copy(
            active = WipeStep.Keys,
            details = mapOf(WipeStep.Session to "Session ended", WipeStep.Messages to "214 removed", WipeStep.Media to "37 files · 18.2 MB"),
        )
        val ui = overlay()
        assertTrue(ui.shows("Clearing this phone"))
        assertTrue(ui.shows("Removing everything Shroud stored for @alice."))
        assertTrue(ui.shows("Your account and chats on other devices stay as they are."))
        assertEquals("Session ended", ui.row(WipeStep.Session).state)
        assertEquals("214 removed", ui.row(WipeStep.Messages).state)
        assertEquals("37 files · 18.2 MB", ui.row(WipeStep.Media).state)
        assertEquals("In progress", ui.row(WipeStep.Keys).state)
        assertEquals("Waiting", ui.row(WipeStep.Settings).state)
        assertEquals("Waiting", ui.row(WipeStep.Verify).state)
        assertFalse(ui.hasButton("Try Again"))
    }

    @Test
    fun theOverlayIsOneModalPaneWithAStableName() {
        val ui = overlay()
        val pane = ui.unmergedNodes().single { it.config.getOrNull(SemanticsProperties.PaneTitle) != null }
        assertEquals("Clearing this phone", pane.config[SemanticsProperties.PaneTitle])
        assertTrue(pane.config.getOrNull(SemanticsProperties.IsTraversalGroup) == true)
        // Done keeps the pane's name: the end is spoken once, by the controller's announcement.
        state = state.copy(phase = WipePhase.Done, details = WipeStep.entries.associateWith { "Done" })
        ui.idle()
        val done = ui.unmergedNodes().single { it.config.getOrNull(SemanticsProperties.PaneTitle) != null }
        assertEquals("Clearing this phone", done.config[SemanticsProperties.PaneTitle])
        assertTrue(ui.shows("This phone is clear"))
    }

    @Test
    fun theControllersFeedbackIsSpokenThroughAPoliteLiveRegion() {
        // iOS `AccessibilityNotification.Announcement` (`DeviceWipeController.swift:150-160`); Android:
        // a live region's change is the AccessibilityEvent TalkBack reads (announceForAccessibility is deprecated).
        val ui = overlay()
        val voice = ui.tagged(ANNOUNCER_TAG)
        assertEquals(LiveRegionMode.Polite, voice.config[SemanticsProperties.LiveRegion])
        assertEquals(listOf("Clearing this phone"), voice.config[SemanticsProperties.ContentDescription])
        announcement = "Signing out: Session ended"
        ui.idle()
        assertEquals(listOf("Signing out: Session ended"), ui.tagged(ANNOUNCER_TAG).config[SemanticsProperties.ContentDescription])
        announcement = "This phone is clear. Nothing from your account is left on it."
        ui.idle()
        assertEquals(
            listOf("This phone is clear. Nothing from your account is left on it."),
            ui.tagged(ANNOUNCER_TAG).config[SemanticsProperties.ContentDescription],
        )
    }

    @Test
    fun doneTakesTheUserToWelcome() {
        // Design `ZFJ1m`.
        state = WipeOverlayState(WipePhase.Done, details = WipeStep.entries.associateWith { "Nothing left" }, handle = "@niklas_v")
        val ui = overlay()
        assertTrue(ui.shows("This phone is clear"))
        assertTrue(ui.shows("Nothing from @niklas_v is left on this device."))
        assertTrue(ui.shows("Taking you to the welcome screen…"))
        WipeStep.entries.forEach { assertEquals("Nothing left", ui.row(it).state) }
    }

    @Test
    fun aFailedCheckSaysWhatIsLeftAndOffersTryAgainAndContinue() {
        state = WipeOverlayState(
            phase = WipePhase.Failed,
            details = mapOf(WipeStep.Session to "Session ended", WipeStep.Messages to "4 removed", WipeStep.Keys to "3 removed"),
            leftovers = listOf(DeviceDataWipe.Leftover(WipeStep.Media, "media and cached files"), DeviceDataWipe.Leftover(WipeStep.Settings, "settings")),
            handle = "@alice",
        )
        val ui = overlay()
        assertTrue(ui.shows("Couldn’t clear everything"))
        assertTrue(
            ui.shows("Still here: media and cached files, settings. Try again — if it keeps failing, restart your phone and open Shroud; it finishes on its own."),
        )
        assertEquals("Still here", ui.row(WipeStep.Media).state)
        assertEquals("Still here", ui.row(WipeStep.Settings).state)
        assertEquals("Failed", ui.row(WipeStep.Verify).state)
        assertEquals("4 removed", ui.row(WipeStep.Messages).state)
        assertFalse(ui.shows("Your account and chats on other devices stay as they are."))
        ui.click(ui.button("Try Again"))
        ui.click(ui.button("Continue"))
        assertEquals(1, retries)
        assertEquals(1, continues)
    }

    @Test
    fun aRetryShowsTheRetriedRowsRunningAgain() {
        // `retry()` (DWC:84-95): the leftovers' steps spin while Verify runs again.
        state = WipeOverlayState(
            phase = WipePhase.Running,
            active = WipeStep.Verify,
            retrying = setOf(WipeStep.Media),
            details = mapOf(WipeStep.Session to "Session ended", WipeStep.Messages to "4 removed", WipeStep.Media to "None stored"),
        )
        val ui = overlay()
        assertEquals("In progress", ui.row(WipeStep.Media).state)
        assertEquals("In progress", ui.row(WipeStep.Verify).state)
    }

    @Test
    fun aSessionThatEndedOrARemovedPhoneSaysWhy() {
        state = WipeOverlayState(WipePhase.Running, reason = WipeReason.SessionEnded, handle = "@alice")
        val ui = overlay()
        assertTrue(ui.shows("Your session ended. Removing everything Shroud stored for @alice."))
        state = WipeOverlayState(WipePhase.Running, reason = WipeReason.Removed)
        ui.idle()
        assertTrue(ui.shows("This phone was removed from your account. Removing everything Shroud stored on this phone."))
    }

    @Test
    fun aLeavingOverlayIsGoneForTalkBackAndKeepsItsLastPhase() {
        // `:17-30, 64-68`: the controller is Idle before the fade ends; the overlay draws Done, hidden.
        state = WipeOverlayState(WipePhase.Done, details = WipeStep.entries.associateWith { "Done" })
        val ui = overlay()
        presented = false
        state = state.copy(phase = WipePhase.Idle)
        ui.idle()
        assertFalse(ui.shows("Clearing this phone"))
        assertFalse(ui.shows("This phone is clear"))
        assertFalse(ui.hasTag(ANNOUNCER_TAG))
        assertTrue(ui.unmergedNodes().none { it.config.getOrNull(SemanticsProperties.PaneTitle) != null })
    }
}
