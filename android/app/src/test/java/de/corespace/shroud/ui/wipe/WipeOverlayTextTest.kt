package de.corespace.shroud.ui.wipe

import de.corespace.shroud.core.auth.DeviceDataWipe
import de.corespace.shroud.core.auth.WipePhase
import de.corespace.shroud.core.auth.WipeReason
import de.corespace.shroud.core.auth.WipeStep
import de.corespace.shroud.ui.wipe.WipeOverlayText.RowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The wipe overlay says and draws what iOS does (`DeviceWipeOverlay.swift:17-30, 94-193, 287-325`;
 * settings-lock §14.5; the interim overlay's tests from review W2 kept).
 */
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

    @Test
    fun aLeavingOverlayKeepsDrawingItsLastPhase() {
        // The controller is back at Idle before the fade ends; Idle drawn as Running restarted the
        // emblem's loops inside the exit transition, which then never finished (`:17-30`).
        assertEquals(WipePhase.Done, WipeOverlayText.shownPhase(WipePhase.Idle, WipePhase.Done))
        assertEquals(WipePhase.Failed, WipeOverlayText.shownPhase(WipePhase.Idle, WipePhase.Failed))
        assertEquals(WipePhase.Running, WipeOverlayText.shownPhase(WipePhase.Running, WipePhase.Done))
        assertEquals(WipePhase.Done, WipeOverlayText.shownPhase(WipePhase.Done, WipePhase.Running))
    }

    @Test
    fun titlesPerPhase() {
        // `:94-100` with "phone" / "tablet" for "iPhone" / "iPad".
        assertEquals("Clearing this phone", WipeOverlayText.title(WipePhase.Running, "phone"))
        assertEquals("Clearing this tablet", WipeOverlayText.title(WipePhase.Idle, "tablet"))
        assertEquals("This phone is clear", WipeOverlayText.title(WipePhase.Done, "phone"))
        assertEquals("Couldn’t clear everything", WipeOverlayText.title(WipePhase.Failed, "phone"))
    }

    @Test
    fun theDetailARowShows() {
        // `:140`: a finished row's detail, "Still here" on a failed data row, nothing otherwise.
        val details = mapOf(WipeStep.Messages to "12 removed", WipeStep.Verify to "Nothing left")
        assertEquals("12 removed", WipeOverlayText.rowDetail(WipeStep.Messages, RowState.Done, details))
        assertEquals("Still here", WipeOverlayText.rowDetail(WipeStep.Messages, RowState.Failed, details))
        assertNull(WipeOverlayText.rowDetail(WipeStep.Verify, RowState.Failed, details))
        assertNull(WipeOverlayText.rowDetail(WipeStep.Keys, RowState.Active, details))
        assertNull(WipeOverlayText.rowDetail(WipeStep.Keys, RowState.Pending, details))
        assertEquals("Done", WipeOverlayText.rowStatus(WipeStep.Keys, RowState.Done, details))
    }

    @Test
    fun theRingFillsWithFinishedRowsAndIsFullOnceDone() {
        // `progress` (`:181-185`).
        val rows = listOf(RowState.Done, RowState.Done, RowState.Active, RowState.Pending, RowState.Pending, RowState.Pending)
        assertEquals(2f / 6f, WipeOverlayText.progress(WipePhase.Running, rows), 1e-6f)
        assertEquals(0f, WipeOverlayText.progress(WipePhase.Running, List(6) { RowState.Pending }), 0f)
        assertEquals(1f, WipeOverlayText.progress(WipePhase.Done, List(6) { RowState.Pending }), 0f)
        assertEquals(5f / 6f, WipeOverlayText.progress(WipePhase.Failed, List(5) { RowState.Done } + RowState.Failed), 1e-6f)
    }

    @Test
    fun theFooterLine() {
        // `:215-219`.
        assertEquals("Taking you to the welcome screen…", WipeOverlayText.footer(done = true))
        assertEquals("Your account and chats on other devices stay as they are.", WipeOverlayText.footer(done = false))
    }

    @Test
    fun theSixParticlesAndTheirCurve() {
        // `WipeParticles.particles` and the curve (`:296-319`): period 1.8 s, phase from the delay,
        // eased 1 − (1 − p)², scale 1 − 0.65·eased, opacity up to 0.9 over the first fifth, then down.
        val p = WipeOverlayText.PARTICLES
        assertEquals(6, p.size)
        assertEquals(listOf(62f, 72f, -64f, -70f, 40f, -18f), p.map { it.dx })
        assertEquals(listOf(-40f, 12f, -30f, 24f, 60f, -70f), p.map { it.dy })
        assertEquals(listOf(8f, 5f, 6f, 5f, 6f, 4f), p.map { it.size })
        assertEquals(listOf(0.0, 0.5, 0.25, 0.9, 1.2, 0.7), p.map { it.delay })
        assertEquals(1.8, WipeOverlayText.PARTICLE_PERIOD_S, 0.0)

        val start = WipeOverlayText.particle(p[0], 0.0)
        assertEquals(0.0, start.phase, 1e-9)
        assertEquals(0f, start.eased, 0f)
        assertEquals(1f, start.scale, 0f)
        assertEquals(0f, start.alpha, 0f)

        val peak = WipeOverlayText.particle(p[0], 0.36)
        assertEquals(0.2, peak.phase, 1e-9)
        assertEquals(0.9f, peak.alpha, 1e-6f)

        val half = WipeOverlayText.particle(p[0], 0.9)
        assertEquals(0.75f, half.eased, 1e-6f)
        assertEquals(0.5125f, half.scale, 1e-6f)
        assertEquals(0.5625f, half.alpha, 1e-6f)

        // A delayed dot starts the loop part-way through (`(t + period − delay) mod period`).
        assertEquals((1.8 - 0.5) / 1.8, WipeOverlayText.particle(p[1], 0.0).phase, 1e-9)
        assertEquals(0.5, WipeOverlayText.particle(p[1], 1.4).phase, 1e-9)
    }
}
