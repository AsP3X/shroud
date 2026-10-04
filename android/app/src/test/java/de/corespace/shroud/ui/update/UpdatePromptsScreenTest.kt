package de.corespace.shroud.ui.update

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import de.corespace.shroud.core.update.ClientUpdateStatus
import de.corespace.shroud.core.update.UpdateCheckOutcome
import de.corespace.shroud.core.update.UpdatePrompt
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.components.OverlayHost
import de.corespace.shroud.ui.components.OverlayLayer
import de.corespace.shroud.ui.components.ShroudSheet
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.focusBlocked
import de.corespace.shroud.ui.onboarding.HarnessHosts
import de.corespace.shroud.ui.onboarding.button
import de.corespace.shroud.ui.onboarding.click
import de.corespace.shroud.ui.onboarding.hasButton
import de.corespace.shroud.ui.onboarding.hasTag
import de.corespace.shroud.ui.onboarding.isEnabled
import de.corespace.shroud.ui.onboarding.shows
import de.corespace.shroud.ui.onboarding.tagged
import de.corespace.shroud.ui.onboarding.unmergedNodes
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The root's update prompts as TalkBack meets them: the "Update available" dialog with and without
 * a link, the blocking "Update required" screen (its pane, copy, buttons, "Checking…" and the
 * check's feedback), the overlays and focus it takes over, and the toast when nothing can open the
 * link.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdatePromptsScreenTest {
    private val hosts = HarnessHosts()
    private var prompt by mutableStateOf<UpdatePrompt>(UpdatePrompt.None)
    private var checking by mutableStateOf(false)
    private var offersDialog by mutableStateOf(true)
    private var sheetOpen by mutableStateOf(false)
    private var stickyLayerOpen by mutableStateOf(false)
    private val opens = ArrayList<String>()
    private var opensSucceed = true
    private val laters = ArrayList<UpdatePrompt.Available>()
    private var checks = 0
    private val outcomes = ArrayDeque<UpdateCheckOutcome>()
    private var focusManager: FocusManager? = null

    @After
    fun tearDown() = hosts.disposeAll()

    private fun layer(dark: Boolean = false, below: @Composable () -> Unit = {}) = hosts.host(dark = dark) {
        OverlayHost {
            Box(Modifier.fillMaxSize()) {
                below()
                UpdatePromptLayer(
                    prompt = prompt,
                    checking = checking,
                    offersDialog = offersDialog,
                    onUpdate = { opens += it; opensSucceed },
                    onLater = { laters += it },
                    onCheckAgain = {
                        checks++
                        outcomes.removeFirstOrNull() ?: UpdateCheckOutcome.Answered(ClientUpdateStatus.UpdateRequired)
                    },
                )
            }
        }
    }

    private fun ComposeHarness.paneTitles(): List<String> =
        unmergedNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.PaneTitle) }

    /** Lets the "Checking…" floor (a real-time delay) pass, then runs what it resumed. */
    private fun ComposeHarness.passCheckingFloor() {
        Thread.sleep(MIN_CHECKING_MS + 150)
        idle()
    }

    private val offer = UpdatePrompt.Available("0.1.0", "0.2.0", "https://example.org/shroud")
    private val block = UpdatePrompt.Required("0.1.0", "0.3.0", "https://example.org/shroud")

    @Test
    fun noPromptDrawsNothing() {
        val ui = layer()
        assertFalse(ui.hasTag(REQUIRED_SCREEN_TAG))
        assertFalse(ui.shows(UpdateCopy.AVAILABLE_TITLE))
        assertFalse(ui.shows(UpdateCopy.REQUIRED_TITLE))
    }

    // ---- Update available ----

    @Test
    fun theOfferNamesBothVersionsAndOpensTheLink() {
        prompt = offer
        val ui = layer()
        assertTrue(ui.shows("Update available"))
        assertTrue(ui.shows("Shroud 0.2.0 is available. You have 0.1.0."))
        assertTrue(ui.hasButton("Later"))
        ui.click(ui.button("Update"))
        assertEquals(listOf("https://example.org/shroud"), opens)
        // Opened: the offer shown is put off.
        assertEquals(listOf(offer), laters)
    }

    @Test
    fun laterPutsTheShownOfferOff() {
        prompt = offer
        val ui = layer()
        ui.click(ui.button("Later"))
        assertEquals(listOf(offer), laters)
        assertTrue(opens.isEmpty())
    }

    @Test
    fun aLinkThatCannotOpenKeepsTheOffer() {
        opensSucceed = false
        prompt = offer
        val ui = layer()
        ui.click(ui.button("Update"))
        assertEquals(1, opens.size)
        assertTrue(laters.isEmpty())
        assertTrue(ui.shows("No app on this phone can open the update link."))
        // The dialog is still up: "Later" or another try.
        assertTrue(ui.hasButton("Later"))
    }

    @Test
    fun anOfferWithoutALinkIsANoticeWithOneOk() {
        prompt = UpdatePrompt.Available("0.1.0", null, null)
        val ui = layer()
        assertTrue(ui.shows("A new version of Shroud is available. You have 0.1.0."))
        assertFalse(ui.hasButton("Update"))
        assertFalse(ui.hasButton("Later"))
        ui.click(ui.button("OK"))
        assertEquals(listOf(UpdatePrompt.Available("0.1.0", null, null)), laters)
        assertTrue(opens.isEmpty())
    }

    @Test
    fun theOfferWaitsWhileTheRootHoldsItBack() {
        prompt = offer
        offersDialog = false
        val ui = layer()
        assertFalse(ui.shows("Update available"))
        offersDialog = true
        ui.idle()
        assertTrue(ui.shows("Update available"))
    }

    // ---- Update required ----

    @Test
    fun theRequiredScreenIsOnePaneWithTheUpdateAndTheCheck() {
        prompt = block
        val ui = layer()
        assertEquals(listOf("Update required"), ui.paneTitles())
        assertTrue(ui.shows("Update required"))
        assertTrue(ui.shows("This version of Shroud no longer works with this server. Update to 0.3.0 to keep using it."))
        ui.click(ui.button("Update"))
        assertEquals(listOf("https://example.org/shroud"), opens)
        ui.click(ui.button("Check again"))
        assertEquals(1, checks)
        // The screen stays: only the server's next answer lifts it.
        assertTrue(ui.hasTag(REQUIRED_SCREEN_TAG))
        assertTrue(laters.isEmpty())
    }

    @Test
    fun whileCheckingTheButtonSaysSoAndWaits() {
        prompt = block
        checking = true
        val ui = layer()
        assertFalse(ui.hasButton("Check again"))
        assertFalse(ui.button("Checking…").isEnabled)
        checking = false
        ui.idle()
        assertTrue(ui.button("Check again").isEnabled)
    }

    @Test
    fun checkingStaysUpAWhileThenSaysTheServerStillAsks() {
        prompt = block
        val ui = layer()
        ui.click(ui.button("Check again"))
        // The fake answers at once; "Checking…" stays for the floor.
        assertTrue(ui.hasButton("Checking…"))
        assertFalse(ui.shows(UpdateCopy.STILL_REQUIRED))
        ui.passCheckingFloor()
        assertTrue(ui.hasButton("Check again"))
        assertTrue(ui.shows("This server still needs a newer version"))
        assertEquals(1, checks)
    }

    @Test
    fun aCheckWithoutAnAnswerSaysSo() {
        prompt = block
        outcomes += UpdateCheckOutcome.Failed
        val ui = layer()
        ui.click(ui.button("Check again"))
        ui.passCheckingFloor()
        assertTrue(ui.shows("Couldn’t reach the server"))
    }

    @Test
    fun anAnswerThatLiftsTheScreenNeedsNoToast() {
        prompt = block
        outcomes += UpdateCheckOutcome.Answered(ClientUpdateStatus.Current)
        val ui = layer()
        ui.click(ui.button("Check again"))
        prompt = UpdatePrompt.None
        ui.passCheckingFloor()
        assertFalse(ui.hasTag(REQUIRED_SCREEN_TAG))
        assertFalse(ui.shows(UpdateCopy.STILL_REQUIRED))
        assertFalse(ui.shows(UpdateCopy.CHECK_FAILED))
    }

    @Test
    fun withoutALinkItSaysWhereTheUpdateComesFrom() {
        prompt = UpdatePrompt.Required("0.1.0", null, null)
        val ui = layer(dark = true)
        assertTrue(
            ui.shows(
                "This version of Shroud no longer works with this server. Update to keep using it. " +
                    "Get the new version where you installed Shroud.",
            ),
        )
        assertFalse(ui.hasButton("Update"))
        ui.click(ui.button("Check again"))
        assertEquals(1, checks)
    }

    @Test
    fun anAnswerOfCurrentLiftsTheScreen() {
        prompt = UpdatePrompt.Required("0.1.0", "0.3.0", null)
        val ui = layer()
        assertTrue(ui.hasTag(REQUIRED_SCREEN_TAG))
        prompt = UpdatePrompt.None
        ui.idle()
        assertFalse(ui.hasTag(REQUIRED_SCREEN_TAG))
    }

    @Test
    fun aLinkNothingCanOpenSaysSo() {
        opensSucceed = false
        prompt = block
        val ui = layer()
        ui.click(ui.button("Update"))
        assertTrue(ui.shows("No app on this phone can open the update link."))
    }

    // ---- What the block takes over ----

    @Test
    fun theBlockClosesOpenSheetsAndHidesOverlaysThatCannotClose() {
        sheetOpen = true
        stickyLayerOpen = true
        val ui = layer {
            ShroudSheet(visible = sheetOpen, onDismiss = { sheetOpen = false }, paneTitle = "Device details") {
                ShroudText("Sheet body", inter(15f), ShroudTheme.colors.textPrimary)
            }
            // Not modal, so TalkBack reaches the sheet below it too.
            OverlayLayer(active = stickyLayerOpen, modal = false) {
                ShroudText("Sticky layer", inter(15f), ShroudTheme.colors.textPrimary)
            }
        }
        assertTrue(ui.describe(), ui.shows("Sheet body"))
        assertTrue(ui.describe(), ui.shows("Sticky layer"))
        prompt = block
        ui.idle()
        assertFalse("the sheet was asked to close", sheetOpen)
        assertFalse(ui.shows("Sheet body"))
        assertFalse(ui.shows("Sticky layer"))
        // TalkBack meets the block, not a layer above it.
        assertEquals(listOf("Update required"), ui.paneTitles())
        ui.click(ui.button("Check again"))
        assertEquals(1, checks)
        // The layer that could not close keeps its state and comes back with the block gone.
        prompt = UpdatePrompt.None
        ui.idle()
        assertTrue(ui.describe(), ui.shows("Sticky layer"))
    }

    @Test
    fun theBlockTakesFocusAndKeepsItFromTheAppBelow() {
        var draft by mutableStateOf("half a phrase")
        val ui = layer {
            val field = remember { FocusRequester() }
            focusManager = LocalFocusManager.current
            Column(Modifier.focusBlocked(prompt is UpdatePrompt.Required)) {
                BasicTextField(draft, { draft = it }, Modifier.focusRequester(field).testTag(FIELD_TAG))
            }
            LaunchedEffect(Unit) { field.requestFocus() }
        }
        assertTrue(ui.fieldFocused())
        prompt = block
        ui.idle()
        assertFalse("the field under the block lost focus", ui.fieldFocused())
        // Tab and D-pad moves stay out of the hidden app.
        for (direction in listOf(FocusDirection.Next, FocusDirection.Down, FocusDirection.Up, FocusDirection.Previous)) {
            focusManager!!.moveFocus(direction)
            ui.idle()
            assertFalse("focus moved into the hidden app ($direction)", ui.fieldFocused())
        }
    }

    private fun ComposeHarness.fieldFocused(): Boolean = tagged(FIELD_TAG).config.getOrNull(SemanticsProperties.Focused) == true

    @Test
    fun copyDropsTheMissingVersion() {
        assertEquals("Shroud 2.0 is available. You have 1.9.", UpdateCopy.availableMessage("2.0", "1.9"))
        assertEquals(
            "This version of Shroud no longer works with this server. Update to 2.0 to keep using it.",
            UpdateCopy.requiredMessage("2.0", hasLink = true),
        )
        assertEquals(
            "This version of Shroud no longer works with this server. Update to 2.0 to keep using it. " +
                "Get the new version where you installed Shroud.",
            UpdateCopy.requiredMessage("2.0", hasLink = false),
        )
    }

    private companion object {
        const val FIELD_TAG = "below.field"
    }
}
