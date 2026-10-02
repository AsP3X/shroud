package de.corespace.shroud.core.calls.media

import de.corespace.shroud.core.calls.CallController
import de.corespace.shroud.core.calls.ScreenShareQuality
import de.corespace.shroud.core.calls.Standard
import de.corespace.shroud.core.net.IceServerDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section order, send direction, relay choice, capture size and sender bitrates. No peer connection:
 * the engine applies these to WebRTC (calls §5, §7).
 */
class CallMediaPlanTest {
    private val turn = IceServerDto(urls = listOf("turn:turn.example:3478"), username = "u", credential = "c")
    private val turns = IceServerDto(urls = listOf("TURNS:turn.example:5349"))
    private val stun = IceServerDto(urls = listOf("stun:stun.example:3478"))

    @Test
    fun relayNeedsATurnUrlAndIsForcedOnlyWhenAsked() {
        assertTrue(offersRelay(listOf(stun, turn)))
        assertTrue(offersRelay(listOf(turns)))
        assertFalse(offersRelay(listOf(stun)))
        assertFalse(offersRelay(emptyList()))
        assertEquals(CallController.offersRelay(listOf(turn)), offersRelay(listOf(turn)))
        assertEquals(CallController.offersRelay(listOf(stun)), offersRelay(listOf(stun)))
        assertEquals(IceTransportChoice.ALL, iceTransportChoice(relayOnly = false, hasTurn = true))
        assertEquals(IceTransportChoice.ALL, iceTransportChoice(relayOnly = true, hasTurn = false))
        assertEquals(IceTransportChoice.RELAY, iceTransportChoice(relayOnly = true, hasTurn = true))
    }

    @Test
    fun sectionsFollowKindThenPlace() {
        val sdp = """
            v=0
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            a=sendrecv
            m=video 9 UDP/TLS/RTP/SAVPF 96
            a=recvonly
            m=video 9 UDP/TLS/RTP/SAVPF 97
            a=inactive
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            a=sendonly
        """.trimIndent()
        val lines = sdpMLines(sdp)
        assertEquals(
            listOf(MediaSection.MIC, MediaSection.CAMERA, MediaSection.SCREEN, MediaSection.SCREEN_SOUND),
            lines.map { it.section },
        )
        assertEquals(listOf("audio", "video", "video", "audio"), lines.map { it.kind })
        assertEquals(listOf("sendrecv", "recvonly", "inactive", "sendonly"), lines.map { it.direction })
        assertEquals(listOf(true, false, false, true), lines.map { it.canSend })
        // An older offer has no second video: the screen section is absent.
        assertNull(sectionAt(2, video = true))
        assertEquals(MediaSection.CAMERA, sectionAt(0, video = true))
    }

    @Test
    fun sendDirectionUsesTheOfferUntilARemoteDescriptionExists() {
        assertTrue(canSend("sendrecv", negotiated = "inactive", hasRemoteDescription = false, stopped = false))
        assertTrue(canSend("sendrecv", negotiated = null, hasRemoteDescription = true, stopped = false))
        assertFalse(canSend("sendrecv", negotiated = "recvonly", hasRemoteDescription = true, stopped = false))
        assertFalse(canSend("sendrecv", negotiated = "inactive", hasRemoteDescription = true, stopped = false))
        assertTrue(canSend("sendonly", negotiated = "sendonly", hasRemoteDescription = true, stopped = false))
        assertFalse(canSend("sendrecv", negotiated = "sendrecv", hasRemoteDescription = true, stopped = true))
        assertEquals("sendrecv", bothWays("recvonly"))
        assertEquals("sendonly", bothWays("inactive"))
        assertEquals("sendrecv", bothWays("sendrecv"))
    }

    @Test
    fun capturePicksTheFormatNearest720pAndCapsAt30() {
        val picked = nearestCapture(
            listOf(
                CaptureChoice(640, 480, 30_000),
                CaptureChoice(1920, 1080, 30_000),
                CaptureChoice(1280, 720, 60_000),
            ),
        )
        assertEquals(CaptureChoice(1280, 720, 60_000), picked)
        assertEquals(30, captureFps(60_000))
        assertEquals(30, captureFps(30_000))
        assertEquals(15, captureFps(15_000))
        assertEquals(15, captureFps(15))
        assertEquals(30, captureFps(60))
    }

    @Test
    fun sendersKeepSpeechAheadOfTheScreenAndTheCamera() {
        val screen = senderTune("shroud-screen", "video", screenOn = true, ScreenShareQuality.Standard)!!
        assertEquals(1_800_000, screen.maxBitrateBps)
        assertEquals(15, screen.maxFramerate)
        assertEquals(NETWORK_PRIORITY_MEDIUM, screen.networkPriority)
        assertEquals(2.0, screen.bitratePriority, 0.0)
        assertEquals(DEGRADE_MAINTAIN_RESOLUTION, screen.degradation)

        val motion = senderTune(
            "shroud-screen",
            "video",
            screenOn = true,
            ScreenShareQuality(ScreenShareQuality.Resolution.Source, 60),
        )!!
        assertEquals(6_500_000, motion.maxBitrateBps)
        assertEquals(60, motion.maxFramerate)
        assertEquals(DEGRADE_BALANCED, motion.degradation)

        val camera = senderTune("shroud-video", "video", screenOn = false, ScreenShareQuality.Standard)!!
        assertEquals(1_200_000, camera.maxBitrateBps)
        assertEquals(30, camera.maxFramerate)
        assertEquals(1.0, camera.scaleResolutionDownBy)
        assertEquals(NETWORK_PRIORITY_LOW, camera.networkPriority)
        assertEquals(DEGRADE_BALANCED, camera.degradation)

        val tile = senderTune("shroud-video", "video", screenOn = true, ScreenShareQuality.Standard)!!
        assertEquals(350_000, tile.maxBitrateBps)
        assertEquals(15, tile.maxFramerate)
        assertEquals(2.0, tile.scaleResolutionDownBy)

        val mic = senderTune("shroud-audio", "audio", screenOn = true, ScreenShareQuality.Standard)!!
        assertEquals(32_000, mic.maxBitrateBps)
        assertEquals(NETWORK_PRIORITY_HIGH, mic.networkPriority)
        assertEquals(4.0, mic.bitratePriority, 0.0)
        assertNull(mic.degradation)
        assertNull(senderTune("other", "application", screenOn = false, ScreenShareQuality.Standard))
    }

    @Test
    fun theScreenGateDropsFramesInsideTheSlackAndRepeatsAStillOne() {
        assertTrue(screenFrameDue(nowNs = 1_000, lastSentNs = 0L, fps = 15))
        val gap = 60_000_000L
        assertFalse(screenFrameDue(nowNs = 1_000 + gap - 1, lastSentNs = 1_000, fps = 15))
        assertTrue(screenFrameDue(nowNs = 1_000 + gap, lastSentNs = 1_000, fps = 15))
        assertFalse(screenFrameDue(nowNs = 5_000, lastSentNs = 0L, fps = 0))
        assertFalse(screenRepeatDue(nowNs = 450_000_000L, lastSentNs = 0L))
        assertFalse(screenRepeatDue(nowNs = 1_000 + 449_000_000L, lastSentNs = 1_000))
        assertTrue(screenRepeatDue(nowNs = 1_000 + 450_000_000L, lastSentNs = 1_000))
    }
}
