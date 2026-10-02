package de.corespace.shroud.ui.wipe

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.corespace.shroud.core.auth.DeviceDataWipe
import de.corespace.shroud.core.auth.WipePhase
import de.corespace.shroud.core.auth.WipeReason
import de.corespace.shroud.core.auth.WipeStep
import de.corespace.shroud.ui.components.OverlayHost
import de.corespace.shroud.ui.theme.LocalReduceMotion
import de.corespace.shroud.ui.theme.ShroudTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The wipe overlay on a device (settings-lock §14.5, §18.3): it takes every touch and Back while
 * presented and **none once it hides** — the regression of the iOS stuck removal transition that
 * left an invisible overlay over Welcome (memory `stuck-removal-transition-eats-touches`;
 * `DeviceWipeOverlay.swift:17-30, 64-68`) — and its failure footer runs Try Again / Continue.
 */
@RunWith(AndroidJUnit4::class)
class DeviceWipeOverlayTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var behindTaps = 0
    private var behindBacks = 0
    private var retries = 0
    private var continues = 0
    private var state by mutableStateOf(WipeOverlayState(WipePhase.Running, handle = "@alice"))
    private var presented by mutableStateOf(true)
    private var shown by mutableStateOf(true)
    private var announcement by mutableStateOf<String?>(null)

    private fun show(reduceMotion: Boolean = true) {
        rule.setContent {
            ShroudTheme(dark = false) {
                CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
                    OverlayHost {
                        Box(Modifier.fillMaxSize()) {
                            // What the overlay uncovers: Welcome's button and its Back.
                            BackHandler { behindBacks++ }
                            Box(Modifier.fillMaxSize().testTag("behind").clickable { behindTaps++ })
                            // The shell keeps the overlay composed through its exit fade (presented false).
                            if (shown) {
                                DeviceWipeOverlayContent(
                                    state,
                                    presented,
                                    "phone",
                                    onRetry = { retries++ },
                                    onContinue = { continues++ },
                                    announcement = announcement,
                                )
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun pressBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun whilePresentedItTakesEveryTouchAndBack() {
        show()
        rule.onNodeWithTag("behind").performClick()
        pressBack()
        assertEquals(0, behindTaps)
        assertEquals(0, behindBacks)
        rule.onNodeWithText("Clearing this phone").assertExists()
        rule.onNodeWithText("Removing everything Shroud stored for @alice.").assertExists()
    }

    @Test
    fun aLeavingOverlayTakesNoTouchAndNoBack() {
        state = state.copy(phase = WipePhase.Done, details = WipeStep.entries.associateWith { "Done" })
        show()
        rule.onNodeWithText("This phone is clear").assertExists()
        // The controller lets go: phase back to Idle, the overlay still on screen for its fade.
        rule.runOnUiThread {
            presented = false
            state = state.copy(phase = WipePhase.Idle)
        }
        rule.waitForIdle()
        rule.onNodeWithTag("behind").performClick()
        pressBack()
        assertEquals(1, behindTaps)
        assertEquals(1, behindBacks)
        // Hidden from TalkBack while it leaves, and nothing in it is announced as running again.
        rule.onNodeWithText("Clearing this phone").assertDoesNotExist()
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).assertDoesNotExist()
    }

    @Test
    fun aLeavingOverlayStartsNothingRepeatingWithMotionOn() {
        // Motion on: the emblem breathes and the dots fly while it runs. Once it leaves, the last
        // phase (Done) is drawn, so nothing restarts inside the exit fade and the UI goes idle.
        state = state.copy(phase = WipePhase.Done, details = WipeStep.entries.associateWith { "Done" })
        show(reduceMotion = false)
        rule.runOnUiThread {
            presented = false
            state = state.copy(phase = WipePhase.Idle)
        }
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        rule.onNodeWithTag("behind").performClick()
        assertEquals(1, behindTaps)
        rule.runOnUiThread { shown = false }
        rule.waitForIdle()
        rule.onNodeWithTag("behind").performClick()
        assertEquals(2, behindTaps)
    }

    @Test
    fun theControllersFeedbackIsAPoliteLiveRegion() {
        // `DeviceWipeController.feedback` is spoken through a live region: its changes are the
        // AccessibilityEvent TalkBack reads (announceForAccessibility is deprecated).
        show()
        rule.onNodeWithTag(ANNOUNCER_TAG).assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule.runOnUiThread { announcement = "Signing out: Session ended" }
        rule.waitForIdle()
        rule.onNodeWithTag(ANNOUNCER_TAG).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Signing out: Session ended")))
        // Gone with the overlay's presentation: a leaving overlay says nothing more.
        rule.runOnUiThread { presented = false }
        rule.waitForIdle()
        rule.onNodeWithTag(ANNOUNCER_TAG).assertDoesNotExist()
    }

    @Test
    fun theOverlayIsAModalPaneWhilePresented() {
        show()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Clearing this phone")).assertExists()
    }

    @Test
    fun aFailedCheckOffersTryAgainAndContinue() {
        state = WipeOverlayState(
            phase = WipePhase.Failed,
            details = mapOf(WipeStep.Session to "Ended", WipeStep.Messages to "4 removed"),
            leftovers = listOf(DeviceDataWipe.Leftover(WipeStep.Media, "media and cached files"), DeviceDataWipe.Leftover(WipeStep.Settings, "settings")),
            handle = "@alice",
        )
        show()
        rule.onNodeWithText("Couldn’t clear everything").assertExists()
        rule.onNodeWithText(
            "Still here: media and cached files, settings. Try again — if it keeps failing, restart your phone and open Shroud; it finishes on its own.",
        ).assertExists()
        rule.onNodeWithText("Try Again").performClick()
        rule.onNodeWithText("Continue").performClick()
        assertEquals(1, retries)
        assertEquals(1, continues)
    }

    @Test
    fun aRemovedPhoneSaysSo() {
        state = WipeOverlayState(WipePhase.Running, reason = WipeReason.Removed)
        show()
        rule.onNodeWithText("This phone was removed from your account. Removing everything Shroud stored on this phone.").assertExists()
        rule.onNodeWithText("Your account and chats on other devices stay as they are.").assertExists()
    }
}
