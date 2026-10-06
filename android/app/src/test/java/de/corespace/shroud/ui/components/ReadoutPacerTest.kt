package de.corespace.shroud.ui.components

import de.corespace.shroud.ui.theme.Motion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The clock behind [rememberPaced] (iOS `PacedRollingText`): a fast readout changes at most once per
 * [Motion.READOUT_PACE_MS] and always ends on the latest value. Each new value cancels the one still
 * waiting, as `LaunchedEffect(value)` does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadoutPacerTest {
    private val pace = Motion.READOUT_PACE_MS

    private class Feed(val scope: TestScope) {
        val pacer = ReadoutPacer("0 KB", scope.testScheduler.timeSource)
        private var waiting: Job? = null

        fun send(value: String, pacing: Boolean = true) {
            waiting?.cancel()
            waiting = scope.launch { pacer.show(value, pacing) }
            scope.runCurrent()
        }
    }

    @Test
    fun theFirstChangeShowsAtOnce() = runTest {
        val feed = Feed(this)
        feed.send("1 KB")
        assertEquals("1 KB", feed.pacer.shown)
    }

    @Test
    fun aChangeInsideThePaceWaitsForIt() = runTest {
        val feed = Feed(this)
        feed.send("1 KB")
        advanceTimeBy(200)
        feed.send("2 KB")
        assertEquals("1 KB", feed.pacer.shown)
        advanceTimeBy(pace - 200 - 1)
        runCurrent()
        assertEquals("1 KB", feed.pacer.shown)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("2 KB", feed.pacer.shown)
    }

    @Test
    fun aSteadyStreamLandsOncePerPaceOnTheLatestValue() = runTest {
        val feed = Feed(this)
        feed.send("0.1 MB")
        // A tick every 50 ms for a second: the deadline hangs off the last change shown, so the
        // restarts never push it back.
        val shown = mutableListOf(feed.pacer.shown)
        for (tick in 1..20) {
            advanceTimeBy(50)
            feed.send("${tick + 1} ticks")
            if (feed.pacer.shown != shown.last()) shown += feed.pacer.shown
        }
        assertEquals(listOf("0.1 MB", "11 ticks", "21 ticks"), shown)
    }

    @Test
    fun theLastValueAlwaysLands() = runTest {
        val feed = Feed(this)
        feed.send("1 KB")
        feed.send("2 KB")
        feed.send("3 KB")
        advanceTimeBy(pace)
        runCurrent()
        assertEquals("3 KB", feed.pacer.shown)
    }

    @Test
    fun withoutPacingAChangeShowsAtOnce() = runTest {
        val feed = Feed(this)
        feed.send("4.7 MB of 4.8 MB")
        feed.send("4.8 MB · PDF", pacing = false)
        assertEquals("4.8 MB · PDF", feed.pacer.shown)
    }
}
