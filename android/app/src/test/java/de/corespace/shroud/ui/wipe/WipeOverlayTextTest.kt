package de.corespace.shroud.ui.wipe

import de.corespace.shroud.core.auth.DeviceDataWipe
import de.corespace.shroud.core.auth.WipePhase
import de.corespace.shroud.core.auth.WipeReason
import de.corespace.shroud.core.auth.WipeStep
import de.corespace.shroud.ui.wipe.WipeOverlayText.RowState
import org.junit.Assert.assertEquals
import org.junit.Test

/** The interim wipe overlay says what iOS says (`DeviceWipeOverlay.swift:101-117, 141-180`; review W2). */
class WipeOverlayTextTest {
    private val none = emptyList<DeviceDataWipe.Leftover>()

    @Test
    fun theRunningSubtitleSaysWhyAndForWhom() {
        assertEquals(
            "Removing everything Shroud stored for @alice.",
            WipeOverlayText.subtitle(WipePhase.Running, WipeReason.Logout, "@alice", none, "phone"),
        )
        assertEquals(
            "Your session ended. Removing everything Shroud stored for @alice.",
            WipeOverlayText.subtitle(WipePhase.Running, WipeReason.SessionEnded, "@alice", none, "phone"),
        )
        assertEquals(
            "This tablet was removed from your account. Removing everything Shroud stored on this tablet.",
            WipeOverlayText.subtitle(WipePhase.Running, WipeReason.Removed, "", none, "tablet"),
        )
    }

    @Test
    fun theDoneAndFailedSubtitles() {
        assertEquals("Nothing from @alice is left on this device.", WipeOverlayText.subtitle(WipePhase.Done, WipeReason.Removed, "@alice", none, "phone"))
        assertEquals("Nothing from your account is left on this phone.", WipeOverlayText.subtitle(WipePhase.Done, WipeReason.Logout, "", none, "phone"))
        val left = listOf(DeviceDataWipe.Leftover(WipeStep.Media, "media files"))
        assertEquals(
            "Still here: media files. Try again — if it keeps failing, restart your phone and open Shroud; it finishes on its own.",
            WipeOverlayText.subtitle(WipePhase.Failed, WipeReason.Logout, "@alice", left, "phone"),
        )
    }

    @Test
    fun rowsFollowTheIosStates() {
        val details = mapOf(WipeStep.Session to "Ended", WipeStep.Media to "3 files · 3 KB")
        val left = listOf(DeviceDataWipe.Leftover(WipeStep.Media, "media files"))
        fun state(step: WipeStep, phase: WipePhase, active: WipeStep? = null, retrying: Set<WipeStep> = emptySet()) =
            WipeOverlayText.rowState(step, phase, active, retrying, details, if (phase == WipePhase.Failed) left else none)

        // Failed: Verify and the steps with leftovers, even with an old detail.
        assertEquals(RowState.Failed, state(WipeStep.Verify, WipePhase.Failed))
        assertEquals(RowState.Failed, state(WipeStep.Media, WipePhase.Failed))
        assertEquals(RowState.Done, state(WipeStep.Session, WipePhase.Failed))
        assertEquals("Failed", WipeOverlayText.rowStatus(WipeStep.Verify, RowState.Failed, details))
        assertEquals("Still here", WipeOverlayText.rowStatus(WipeStep.Media, RowState.Failed, details))
        // Try Again: a retried row is in progress, not its old detail.
        assertEquals(RowState.Active, state(WipeStep.Media, WipePhase.Running, retrying = setOf(WipeStep.Media)))
        assertEquals("In progress", WipeOverlayText.rowStatus(WipeStep.Media, RowState.Active, details))
        assertEquals(RowState.Active, state(WipeStep.Keys, WipePhase.Running, active = WipeStep.Keys))
        assertEquals("3 files · 3 KB", WipeOverlayText.rowStatus(WipeStep.Media, state(WipeStep.Media, WipePhase.Running), details))
        assertEquals(RowState.Pending, state(WipeStep.Settings, WipePhase.Running))
        assertEquals("Waiting", WipeOverlayText.rowStatus(WipeStep.Settings, RowState.Pending, details))
    }
}
