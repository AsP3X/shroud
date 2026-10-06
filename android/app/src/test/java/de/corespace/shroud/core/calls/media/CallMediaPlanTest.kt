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
    fun capturePicksTheFormatNearest1080pAndCapsAt30() {
        val picked = nearestCapture(
            listOf(
                CaptureChoice(640, 480, 30_000),
                CaptureChoice(1920, 1080, 30_000),
                CaptureChoice(1280, 720, 60_000),
                CaptureChoice(3840, 2160, 30_000),
            ),
        )
        assertEquals(CaptureChoice(1920, 1080, 30_000), picked)
        // A front camera without 1080p: the nearest it has.
        assertEquals(
            CaptureChoice(1280, 960, 30_000),
            nearestCapture(listOf(CaptureChoice(640, 480, 30_000), CaptureChoice(1280, 960, 30_000))),
        )
        assertEquals(1920, sentLong(CaptureChoice(1920, 1080, 30)))
        assertEquals(1920, sentLong(CaptureChoice(1920, 1440, 30)))
        assertEquals(1920, sentLong(CaptureChoice(3840, 2160, 30)))
        assertEquals(1280, sentLong(CaptureChoice(1280, 960, 30)))
        // The video source crops to 16:9 and shrinks a larger capture in 3/4 steps until it fits.
        assertEquals(1536, sentLong(CaptureChoice(2048, 1536, 30)))
        assertEquals(1920, sentLong(CaptureChoice(2560, 1440, 30)))
        assertEquals(0, sentLong(CaptureChoice(0, 0, 30)))
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

        // The camera starts at 720p; from a 1080p capture the encoder shrinks it by 1.5.
        val camera = senderTune("shroud-video", "video", screenOn = false, ScreenShareQuality.Standard, captureLong = 1920)!!
        assertEquals(2_200_000, camera.maxBitrateBps)
        assertEquals(30, camera.maxFramerate)
        assertEquals(1.5, camera.scaleResolutionDownBy)
        assertEquals(NETWORK_PRIORITY_LOW, camera.networkPriority)
        assertEquals(1.0, camera.bitratePriority, 0.0)
        assertEquals(DEGRADE_BALANCED, camera.degradation)

        // Each rung of the ladder.
        val top = senderTune("shroud-video", "video", screenOn = false, ScreenShareQuality.Standard, CAMERA_LADDER[5], 1920)!!
        assertEquals(3_800_000, top.maxBitrateBps)
        assertEquals(1.0, top.scaleResolutionDownBy)
        val bottom = senderTune("shroud-video", "video", screenOn = false, ScreenShareQuality.Standard, CAMERA_LADDER[0], 1920)!!
        assertEquals(150_000, bottom.maxBitrateBps)
        assertEquals(15, bottom.maxFramerate)
        assertEquals(6.0, bottom.scaleResolutionDownBy)

        // While the screen is shared, a tile's worth whatever the rung.
        val tile = senderTune("shroud-video", "video", screenOn = true, ScreenShareQuality.Standard, CAMERA_LADDER[5], 1920)!!
        assertEquals(350_000, tile.maxBitrateBps)
        assertEquals(15, tile.maxFramerate)
        assertEquals(3.0, tile.scaleResolutionDownBy)
        assertEquals(2.0, senderTune("shroud-video", "video", screenOn = true, ScreenShareQuality.Standard, captureLong = 1280)!!.scaleResolutionDownBy)
        // A camera the link had pushed below the tile stays there while the screen is shared.
        val lowTile = senderTune("shroud-video", "video", screenOn = true, ScreenShareQuality.Standard, CAMERA_LADDER[1], 1920)!!
        assertEquals(300_000, lowTile.maxBitrateBps)
        assertEquals(15, lowTile.maxFramerate)
        assertEquals(4.0, lowTile.scaleResolutionDownBy)

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
