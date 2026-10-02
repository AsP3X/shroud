package de.corespace.shroud.ui.calls

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import de.corespace.shroud.core.calls.CallPhase
import de.corespace.shroud.core.calls.CallUiState
import de.corespace.shroud.core.calls.ScreenShareQuality
import de.corespace.shroud.core.calls.Standard
import de.corespace.shroud.core.net.CallModality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The call screen's derived state, copy and pure helpers (iOS `InCallOverlay.swift`; calls §8). */
class CallScreenRulesTest {
    private val call = CallFixtures.call()

    @Test
    fun statusLinesFollowThePhaseAsIosWritesThem() {
        assertEquals("Calling…", CallScreenRules.statusLine(call.copy(phase = CallPhase.OutgoingRinging)))
        assertEquals("Incoming call", CallScreenRules.statusLine(call.copy(phase = CallPhase.IncomingRinging)))
        assertEquals(
            "Incoming video call",
            CallScreenRules.statusLine(call.copy(phase = CallPhase.IncomingRinging, modality = CallModality.Video)),
        )
        assertEquals("Connecting…", CallScreenRules.statusLine(call.copy(phase = CallPhase.Connecting)))
        assertEquals("Connected", CallScreenRules.statusLine(call.copy(phase = CallPhase.Active)))
        assertEquals("Call ended", CallScreenRules.statusLine(call.copy(phase = CallPhase.Ending)))
        assertEquals("Declined", CallScreenRules.statusLine(call.copy(phase = CallPhase.Ending, endedText = "Declined")))
        assertEquals("", CallScreenRules.statusLine(call.copy(phase = CallPhase.Idle)))
    }

    @Test
    fun theTimerReadsLikeIosAndTheWeb() {
        assertEquals("0:00", CallScreenRules.elapsed(0))
        assertEquals("0:42", CallScreenRules.elapsed(42))
        assertEquals("12:05", CallScreenRules.elapsed(725))
        assertEquals("1:02:03", CallScreenRules.elapsed(3723))
        assertEquals("0:00", CallScreenRules.elapsed(-5))
    }

    @Test
    fun anActiveCallShowsItsRunningTimeUnlessItReconnects() {
        val start = CallFixtures.now
        val running = call.copy(startedAt = start)
        val status = CallScreenRules.status(running, start.plusSeconds(42))
        assertEquals("0:42", status.text)
        assertTrue(status.isTimer)
        val reconnecting = CallScreenRules.status(running.copy(reconnecting = true), start.plusSeconds(42))
        assertEquals(CallStatusText("Reconnecting…", isTimer = false), reconnecting)
        assertEquals(CallStatusText("Connected", isTimer = false), CallScreenRules.status(call, start))
    }

    @Test
    fun picturesShowOnlyOnceTheirFramesArrive() {
        // No tracks (as before G9 attaches the engine): nothing but the face.
        val flags = CallScreenRules.flags(CallUiState(active = call), call, hasSafetyNumber = false, chromeHidden = false)
        assertFalse(flags.showsRemoteVideo)
        assertFalse(flags.showsRemoteScreen)
        assertFalse(flags.showsLocalVideo)
        assertFalse(flags.picture)
        assertFalse(flags.chromeAway)
    }

    @Test
    fun sharingCountsFromTheStartAndStopsWhenTheCallEnds() {
        val starting = call.copy(screenShareStarting = true)
        assertTrue(CallScreenRules.flags(CallUiState(), starting, false, false).sharing)
        assertTrue(CallScreenRules.flags(CallUiState(), call.copy(isSharingScreen = true), false, false).sharing)
        assertFalse(CallScreenRules.flags(CallUiState(), starting.copy(phase = CallPhase.Ending), false, false).sharing)
    }

    @Test
    fun theBadgeNeedsAnUncomparedNumberAndGoesWhenTheCallEnds() {
        val unverified = call.copy(safetyVerified = false)
        val flags = CallScreenRules.flags(CallUiState(), unverified, hasSafetyNumber = true, chromeHidden = false)
        assertTrue(flags.badgeRoom)
        assertTrue(flags.unverified)
        val ending = CallScreenRules.flags(CallUiState(), unverified.copy(phase = CallPhase.Ending), true, false)
        // Its room stays for that last moment; the badge itself goes.
        assertTrue(ending.badgeRoom)
        assertFalse(ending.unverified)
        assertFalse(CallScreenRules.flags(CallUiState(), unverified, hasSafetyNumber = false, chromeHidden = false).badgeRoom)
        assertFalse(CallScreenRules.flags(CallUiState(), call, hasSafetyNumber = true, chromeHidden = false).badgeRoom)
    }

    @Test
    fun theMutedLineAndTheMeterFollowIos() {
        val flags = CallScreenRules.flags(CallUiState(), call, false, false)
        // Without their picture the face's badge says it.
        assertFalse(CallScreenRules.showsMutedLine(call.copy(remoteMicMuted = true), flags))
        assertTrue(CallScreenRules.showsMutedLine(call.copy(remoteMicMuted = true), flags.copy(showsRemoteVideo = true)))
        assertTrue(CallScreenRules.showsSpeaking(call))
        assertFalse(CallScreenRules.showsSpeaking(call.copy(isMuted = true)))
        assertFalse(CallScreenRules.showsSpeaking(call.copy(phase = CallPhase.Connecting)))
        assertEquals("anna is muted", CallScreenRules.mutedText("anna"))
    }

    @Test
    fun theSpeakerShowsOnOnlyWhileAudioLeavesTheEarpiece() {
        assertTrue(CallScreenRules.speakerShownOn(speakerOn = true, onEarpiece = false))
        // The route refused the speaker: it does not pretend.
        assertFalse(CallScreenRules.speakerShownOn(speakerOn = true, onEarpiece = true))
        // A headset: off.
        assertFalse(CallScreenRules.speakerShownOn(speakerOn = false, onEarpiece = false))
    }

    @Test
    fun videoAndShareAvailability() {
        assertTrue(CallScreenRules.videoAvailable(call))
        assertFalse(CallScreenRules.videoAvailable(call.copy(canVideo = false)))
        assertTrue(CallScreenRules.videoAvailable(call.copy(canVideo = false, isVideoEnabled = true)))
        assertFalse(CallScreenRules.shareAvailable(call))
        assertTrue(CallScreenRules.shareAvailable(call.copy(canShareScreen = true)))
        assertTrue(CallScreenRules.shareAvailable(call.copy(screenShareStarting = true)))
    }

    @Test
    fun shareCopy() {
        val quality = ScreenShareQuality.Standard
        assertEquals("Share your screen", CallScreenRules.shareLabel(false))
        assertEquals("Screen sharing", CallScreenRules.shareLabel(true))
        assertEquals("1080p · 15 fps", CallScreenRules.shareValue(false, quality))
        assertEquals("On, 1080p · 15 fps", CallScreenRules.shareValue(true, quality))
        assertEquals("Starting…", CallScreenRules.sharingPillText(true))
        assertEquals("Sharing screen", CallScreenRules.sharingPillText(false))
        assertEquals("Starting to share your screen", CallScreenRules.sharingPillLabel(true))
        assertEquals("You’re sharing your screen", CallScreenRules.sharingPillLabel(false))
    }

    @Test
    fun theSafetyNumberReadsFourGroupsToARow() {
        val number = (1..12).joinToString(" ") { "%05d".format(it) }
        val rows = CallScreenRules.safetyRows(number)
        assertEquals(3, rows.size)
        assertEquals(listOf("00001", "00002", "00003", "00004"), rows[0])
        assertEquals(listOf("00009", "00010", "00011", "00012"), rows[2])
        assertEquals(
            "Compare it with anna: read it out on this call, or check it in person. If it matches, nobody else can listen in.",
            CallScreenRules.safetyCompareText("anna"),
        )
    }

    @Test
    fun theControlsLingerOnlyOverTheirScreenAndNeverWithTalkBackOrTheNumberOpen() {
        assertTrue(CallScreenRules.chromeShouldLinger(showsRemoteScreen = true, chromeHidden = false, safetyOpen = false, touchExploration = false))
        assertFalse(CallScreenRules.chromeShouldLinger(showsRemoteScreen = false, chromeHidden = false, safetyOpen = false, touchExploration = false))
        assertFalse(CallScreenRules.chromeShouldLinger(showsRemoteScreen = true, chromeHidden = true, safetyOpen = false, touchExploration = false))
        assertFalse(CallScreenRules.chromeShouldLinger(showsRemoteScreen = true, chromeHidden = false, safetyOpen = true, touchExploration = false))
        assertFalse(CallScreenRules.chromeShouldLinger(showsRemoteScreen = true, chromeHidden = false, safetyOpen = false, touchExploration = true))
    }

    /** At iOS's 59 pt top and no drop the stops are the measured 0.42 and 0.70 (calls §8.8). */
    @Test
    fun theShadeStopsMatchIosAtItsInset() {
        val stops = CallScreenRules.shadeStops(topInset = 59f, drop = 0f)
        assertEquals(0f, stops[0].first, 0f)
        assertEquals(0.42f, stops[1].first, 0.005f)
        assertEquals(0.70f, stops[2].first, 0.005f)
        assertEquals(1f, stops[3].first, 0f)
        assertEquals(0.62f, stops[0].second.alpha, 0.01f)
        assertEquals(0f, stops[3].second.alpha, 0f)
        assertEquals(279f, CallScreenRules.shadeHeight(59f, 0f), 0f)
        assertEquals(90f, CallScreenRules.shadeDrop(badgeRoom = true, sharing = true), 0f)
    }

    @Test
    fun theRevealCoversTheFarthestCornerAndClosesInsideTheFace() {
        assertEquals(49.92f, CallStageGeometry.faceRadius(104f), 0.001f)
        assertEquals(1f, CallStageGeometry.faceRadius(0f), 0f)
        // From the middle of a 400 × 300 view: the corner is 250 away.
        assertEquals(252f, CallStageGeometry.fullRadius(Offset(200f, 150f), Size(400f, 300f)), 0.001f)
        // From near a corner, the opposite one counts.
        val far = CallStageGeometry.fullRadius(Offset(10f, 10f), Size(400f, 300f))
        assertTrue(abs(far - (Math.hypot(390.0, 290.0).toFloat() + 2f)) < 0.01f)
    }

    @Test
    fun theRemoteScreenMountsAsSoonAsTheySayTheyShare() {
        assertFalse(CallScreenRules.mountsRemoteScreen(CallUiState(), call.copy(remoteSharingScreen = true)))
        assertNull(CallUiState().remoteScreenTrack)
    }
}
