package de.corespace.shroud.ui.calls

import de.corespace.shroud.core.calls.CallHistory
import de.corespace.shroud.core.calls.CallHistoryState
import de.corespace.shroud.core.net.CallModality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** The Calls tab's states, footer, summaries and labels (iOS `CallsView.swift`; calls §9). */
class CallsTabStateTest {
    private val anna = CallFixtures.recent(1)

    @Test
    fun theSkeletonShowsOnlyBeforeTheFirstLoad() {
        assertEquals(CallsTabContent.Skeleton, CallsTabRules.content(CallHistoryState()))
        // A reload over rows keeps the list.
        assertEquals(CallsTabContent.List, CallsTabRules.content(CallHistoryState(recent = listOf(anna))))
    }

    @Test
    fun aFailedFirstLoadSaysSoInsteadOfClaimingNoCalls() {
        val failed = CallHistoryState(hasLoaded = true, error = "Couldn’t reach Shroud. Check your connection.")
        assertEquals(CallsTabContent.Error, CallsTabRules.content(failed))
        // With calls on screen the error does not replace them.
        assertEquals(CallsTabContent.List, CallsTabRules.content(failed.copy(recent = listOf(anna))))
    }

    @Test
    fun loadedWithoutCallsIsTheEmptyState() {
        assertEquals(CallsTabContent.Empty, CallsTabRules.content(CallHistoryState(hasLoaded = true)))
        assertEquals("No calls yet", CallsTabRules.EMPTY_TITLE)
        assertEquals(
            "Your recent calls show up here. To call someone, open their chat or profile and tap Call or Video.",
            CallsTabRules.EMPTY_MESSAGE,
        )
        assertEquals("Can't load calls", CallsTabRules.ERROR_TITLE)
    }

    @Test
    fun theListEndsWithASpinnerOrLoadOlder() {
        val loaded = CallHistoryState(recent = listOf(anna), hasLoaded = true)
        assertEquals(CallsTabFooter.None, CallsTabRules.footer(loaded))
        assertEquals(CallsTabFooter.Loading, CallsTabRules.footer(loaded.copy(loadingOlder = true)))
        assertEquals(CallsTabFooter.LoadOlder, CallsTabRules.footer(loaded.copy(olderFailed = true)))
        assertEquals("Load older calls", CallsTabRules.LOAD_OLDER)
        assertEquals("Loading older calls", CallsTabRules.LOADING_OLDER)
    }

    @Test
    fun aRunSummarisesItsCallsAndItsMissedOnes() {
        val calls = listOf(
            CallFixtures.recent(3, minutesAgo = 0),
            CallFixtures.recent(2, minutesAgo = 10, status = "missed", outgoing = false, connected = false, seconds = null),
            CallFixtures.recent(1, minutesAgo = 20),
        )
        val run = CallHistory.runs(calls).single()
        assertEquals("3 calls · " to "1 missed", CallsTabRules.runSummary(run))
        assertEquals("3 calls, 1 missed", CallsTabRules.runSummaryLabel(run))
        assertEquals("anna, 3 calls, 1 missed", CallsTabRules.runHeaderLabel(run))
        val quiet = CallHistory.runs(listOf(calls[0], calls[2])).single()
        assertEquals("2 calls" to null, CallsTabRules.runSummary(quiet))
        assertEquals("Expanded", CallsTabRules.expandedValue(true))
        assertEquals("Collapsed", CallsTabRules.expandedValue(false))
    }

    @Test
    fun statusLinesSplitBeforeTheOutcome() {
        assertEquals("Outgoing voice · ", CallsTabRules.statusPrefix(anna))
        val missedVideo = CallFixtures.recent(2, outgoing = false, status = "missed", modality = CallModality.Video, connected = false, seconds = null)
        assertEquals("Incoming video · ", CallsTabRules.statusPrefix(missedVideo))
        assertEquals(CallHistory.statusLine(missedVideo), CallsTabRules.statusPrefix(missedVideo) + CallHistory.outcome(missedVideo))
    }

    @Test
    fun runTimesNameTheDayAndTheTime() {
        val zone = ZoneOffset.UTC
        val now = Instant.parse("2026-09-30T18:00:00Z")
        fun label(at: String) = CallsTabRules.runTimeLabel(Instant.parse(at), now, zone, Locale.ENGLISH, is24h = true, dayMonthPattern = "d MMM")
        assertEquals("14:02", label("2026-09-30T14:02:00Z"))
        assertEquals("Yesterday, 14:02", label("2026-09-29T14:02:00Z"))
        assertEquals("28 Sep, 14:02", label("2026-09-28T14:02:00Z"))
        val twelve = CallsTabRules.runTimeLabel(Instant.parse("2026-09-30T14:02:00Z"), now, zone, Locale.US, is24h = false, dayMonthPattern = "MMM d")
        assertEquals("2:02 PM", twelve)
    }

    @Test
    fun talkBackHearsTheWholeCallInWords() {
        val zone = ZoneOffset.UTC
        val call = CallFixtures.recent(1).copy(at = Instant.parse("2026-09-30T09:41:00Z"))
        assertEquals(
            "anna, outgoing voice call, 4 minutes, 12 seconds, 30 September 2026 at 09:41",
            CallsTabRules.accessibilityLabel(call, named = true, zone = zone, locale = Locale.UK, is24h = true),
        )
        val failed = call.copy(connected = false, duration = null)
        assertEquals(
            "Outgoing voice call, not connected, 30 September 2026 at 09:41",
            CallsTabRules.accessibilityLabel(failed, named = false, zone = zone, locale = Locale.UK, is24h = true),
        )
        val elsewhere = call.copy(isOutgoing = false, status = "answered_elsewhere", modality = CallModality.Video)
        assertEquals(
            "Incoming video call, answered on another device, 30 September 2026 at 09:41",
            CallsTabRules.accessibilityLabel(elsewhere, named = false, zone = zone, locale = Locale.UK, is24h = true),
        )
    }

    @Test
    fun callButtonsNameWhoTheyCall() {
        assertEquals("Call anna", CallsTabRules.callLabel("anna"))
        assertEquals("Video call anna", CallsTabRules.videoCallLabel("anna"))
        assertEquals("show the calls", CallsTabRules.expandAction(false))
        assertEquals("hide the calls", CallsTabRules.expandAction(true))
        assertNull(CallsTabRules.runSummary(CallHistory.runs(listOf(anna)).single()).second)
    }
}
