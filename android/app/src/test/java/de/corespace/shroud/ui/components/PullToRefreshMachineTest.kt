package de.corespace.shroud.ui.components

import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.components.PullToRefreshMachine.Phase
import de.corespace.shroud.ui.theme.Haptics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pull-to-refresh state machine of shell-chats §10.16 (threshold 80, max 120, hold 56,
 * resistance 0.5), in px at density 1.
 */
class PullToRefreshMachineTest {
    private fun machine() = PullToRefreshMachine(thresholdPx = 80f, maxPx = 120f, holdPx = 56f)

    @Test
    fun pullingMovesTheListHalfTheFingerAndConsumesAllOfIt() {
        val m = machine()
        val pull = m.pull(60f)
        assertEquals(60f, pull.consumed, 0.001f)
        assertEquals(30f, m.offset, 0.001f)
        assertEquals(Phase.Pulling, m.phase)
        assertTrue(m.isPulling)
        assertEquals(30f / 80f, m.progress, 0.0001f)
    }

    @Test
    fun crossingTheThresholdArmsAndTicksOnce() {
        val m = machine()
        assertFalse(m.pull(150f).crossedThreshold) // 75
        val crossing = m.pull(10f) // 80
        assertTrue(crossing.crossedThreshold)
        assertEquals(Phase.Armed, m.phase)
        assertFalse(m.pull(10f).crossedThreshold) // 85, still armed: no second tick
        assertEquals(1f, m.progress, 0.0001f)
    }

    @Test
    fun theListNeverTravelsPastTheMaximum() {
        val m = machine()
        m.pull(1_000f)
        assertEquals(120f, m.offset, 0.001f)
        assertEquals(Phase.Armed, m.phase)
    }

    @Test
    fun pushingBackUpRetractsFirstAndDisarmsBelowTheThreshold() {
        val m = machine()
        m.pull(180f) // 90, armed
        val used = m.retract(-40f) // 70
        assertEquals(-40f, used, 0.001f)
        assertEquals(70f, m.offset, 0.001f)
        assertEquals(Phase.Pulling, m.phase)
        // Crossing again ticks again.
        assertTrue(m.pull(20f).crossedThreshold)
    }

    @Test
    fun retractingPastRestHandsTheRestToTheList() {
        val m = machine()
        m.pull(40f) // 20
        val used = m.retract(-100f) // the list takes the remaining 60
        assertEquals(-40f, used, 0.001f)
        assertEquals(0f, m.offset, 0.001f)
        assertEquals(Phase.Idle, m.phase)
        assertEquals(0f, m.retract(-10f), 0.001f)
    }

    @Test
    fun releasingWhileArmedRefreshesAndHoldsTheList() {
        val m = machine()
        m.pull(200f)
        assertTrue(m.release())
        assertEquals(Phase.Refreshing, m.phase)
        assertFalse(m.isPulling)
        // The composable animates to the hold position.
        m.animateOffset(m.holdPx)
        assertEquals(56f, m.offset, 0.001f)
        // A second pull while refreshing does nothing.
        assertFalse(m.canPull)
        assertEquals(0f, m.pull(100f).consumed, 0.001f)
        assertEquals(56f, m.offset, 0.001f)
        m.refreshFinished()
        assertEquals(Phase.Settling, m.phase)
        m.animateOffset(0f)
        m.settled()
        assertEquals(Phase.Idle, m.phase)
        assertEquals(0f, m.offset, 0.001f)
    }

    @Test
    fun releasingBeforeTheThresholdJustSpringsBack() {
        val m = machine()
        m.pull(100f) // 50
        assertFalse(m.release())
        assertEquals(Phase.Settling, m.phase)
        m.settled()
        assertEquals(Phase.Idle, m.phase)
        assertEquals(0f, m.offset, 0.001f)
    }

    @Test
    fun aPullWhileSpringingBackPicksTheListUpAgain() {
        val m = machine()
        m.pull(100f)
        m.release()
        m.animateOffset(30f)
        assertTrue(m.canPull)
        m.pull(120f) // 30 + 60 = 90
        assertEquals(Phase.Armed, m.phase)
        assertEquals(90f, m.offset, 0.001f)
    }

    @Test
    fun releaseWithoutAPullDoesNothing() {
        val m = machine()
        assertFalse(m.release())
        assertEquals(Phase.Idle, m.phase)
        assertEquals(0f, m.pull(-5f).consumed, 0.001f)
    }

    @Test
    fun talkBacksRefreshActionStartsFromRest() {
        val m = machine()
        m.beginProgrammaticRefresh()
        assertEquals(Phase.Refreshing, m.phase)
        m.refreshFinished()
        m.settled()
        assertEquals(Phase.Idle, m.phase)
        // Not while a pull is in progress.
        m.pull(10f)
        m.beginProgrammaticRefresh()
        assertEquals(Phase.Pulling, m.phase)
    }

    @Test
    fun thresholdTickIsSegmentTickOnAndroid14AndAClockTickBefore() {
        // 00-plan §1.7.12: SegmentTick → SEGMENT_TICK (34+) else CLOCK_TICK.
        // The pull now plays it through the theme's View.perform(Haptic.SegmentTick).
        assertEquals(android.view.HapticFeedbackConstants.SEGMENT_TICK, Haptics.feedbackConstant(Haptic.SegmentTick, sdk = 34))
        assertEquals(android.view.HapticFeedbackConstants.SEGMENT_TICK, Haptics.feedbackConstant(Haptic.SegmentTick, sdk = 37))
        assertEquals(android.view.HapticFeedbackConstants.CLOCK_TICK, Haptics.feedbackConstant(Haptic.SegmentTick, sdk = 33))
        assertEquals(android.view.HapticFeedbackConstants.CLOCK_TICK, Haptics.feedbackConstant(Haptic.SegmentTick, sdk = 30))
    }
}
