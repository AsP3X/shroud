package de.corespace.shroud.core.calls

import de.corespace.shroud.core.calls.signal.CallSdp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SDP munging — iOS `CallSdpTests` (`ios/shroudTests/CallSdpTests.swift`, all 4) and the web's
 * `voiceSdp` / `screenSoundSdp` / `screenVideoSdp` / `cameraVideoSdp` / `h264Level` rows of
 * `logic.selftest.ts`.
 */
class CallSdpTest {
    @Test
    fun opusGainsErrorCorrectionWithoutTouchingAnotherCodec() {
        // The Swift multi-line literal: every line ends "\r\n" but the last, which keeps its "\r".
        val sdp = "v=0\r\n" +
            "m=audio 9 UDP/TLS/RTP/SAVPF 111 0\r\n" +
            "a=rtpmap:111 opus/48000/2\r\n" +
            "a=fmtp:111 minptime=10;sprop-stereo=1;useinbandfec=0\r\n" +
            "a=rtpmap:0 PCMU/8000\r\n" +
            "a=fmtp:0 comfort=1\r"
        val tuned = CallSdp.withVoiceResilience(sdp)
        assertTrue(tuned.contains("a=fmtp:111 minptime=10;sprop-stereo=0;useinbandfec=1;usedtx=1;stereo=0;maxaveragebitrate=32000"))
        assertFalse(tuned.contains("useinbandfec=0"))
        assertFalse(tuned.contains("sprop-stereo=1"))
        assertTrue(tuned.contains("a=fmtp:0 comfort=1"))
        assertTrue(tuned.startsWith("v=0\r\n"))
        assertEquals(tuned, CallSdp.withVoiceResilience(tuned))
        // web: payload 111 does not rewrite 1110, and opus still gets its own fmtp line.
        val neighbor = CallSdp.withVoiceResilience("v=0\r\na=rtpmap:111 opus/48000/2\r\na=fmtp:1110 useinbandfec=0\r\n")
        assertTrue(neighbor.contains("a=fmtp:1110 useinbandfec=0"))
        assertTrue(neighbor.contains("a=fmtp:111 useinbandfec=1"))
    }

    @Test
    fun missingFmtpIsInsertedAndALineWithoutOpusIsKept() {
        val sdp = "v=0\nm=audio 9 UDP/TLS/RTP/SAVPF 111\na=rtpmap:111 opus/48000/2\n"
        val tuned = CallSdp.withVoiceResilience(sdp)
        assertEquals(
            "v=0\nm=audio 9 UDP/TLS/RTP/SAVPF 111\na=rtpmap:111 opus/48000/2\na=fmtp:111 useinbandfec=1;usedtx=1;stereo=0;sprop-stereo=0;maxaveragebitrate=32000\n",
            tuned,
        )
        assertEquals("v=0\r\n", CallSdp.withVoiceResilience("v=0\r\n"))
    }

    @Test
    fun theScreensSoundGetsMusicSettingsAndTheMicrophoneKeepsSpeech() {
        val sdp = listOf(
            "v=0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=rtpmap:111 opus/48000/2",
            "a=fmtp:111 minptime=10;useinbandfec=1",
            "m=video 9 UDP/TLS/RTP/SAVPF 96",
            "a=rtpmap:96 VP8/90000",
            "m=video 9 UDP/TLS/RTP/SAVPF 96",
            "a=rtpmap:96 VP8/90000",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=rtpmap:111 opus/48000/2",
            "a=fmtp:111 minptime=10;useinbandfec=1",
            "",
        ).joinToString("\r\n")
        val tuned = CallSdp.withScreenSound(CallSdp.withVoiceResilience(sdp))
        val fmtps = tuned.split("\r\n").filter { it.startsWith("a=fmtp:111") }
        assertEquals(2, fmtps.size)
        assertEquals("a=fmtp:111 minptime=10;useinbandfec=1;usedtx=1;stereo=0;sprop-stereo=0;maxaveragebitrate=32000", fmtps[0])
        assertEquals("a=fmtp:111 minptime=10;useinbandfec=1;usedtx=0;stereo=1;sprop-stereo=1;maxaveragebitrate=128000", fmtps[1])
        assertEquals(tuned, CallSdp.withScreenSound(tuned))
        // An older app's description has one audio section: nothing to do.
        val older = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2\r\n"
        assertEquals(older, CallSdp.withScreenSound(older))
        // No fmtp in the screen's section: one is added after its rtpmap.
        val bare = "v=0\nm=audio 9 X 111\na=rtpmap:111 opus/48000/2\nm=audio 9 X 111\na=rtpmap:111 opus/48000/2\n"
        assertTrue(
            CallSdp.withScreenSound(bare)
                .endsWith("a=rtpmap:111 opus/48000/2\na=fmtp:111 useinbandfec=1;usedtx=0;stereo=1;sprop-stereo=1;maxaveragebitrate=128000\n"),
        )
    }

    @Test
    fun vp8LeadsTheScreensPictureAndTheCameraKeepsItsOrder() {
        val sdp = listOf(
            "v=0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "m=video 9 UDP/TLS/RTP/SAVPF 102 103 96 97",
            "a=rtpmap:102 H264/90000",
            "a=rtpmap:103 rtx/90000",
            "a=rtpmap:96 VP8/90000",
            "a=rtpmap:97 rtx/90000",
            "m=video 9 UDP/TLS/RTP/SAVPF 102 103 96 97",
            "a=rtpmap:102 H264/90000",
            "a=rtpmap:103 rtx/90000",
            "a=rtpmap:96 VP8/90000",
            "a=rtpmap:97 rtx/90000",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "",
        ).joinToString("\r\n")
        val tuned = CallSdp.withScreenVideo(sdp)
        val mlines = tuned.split("\r\n").filter { it.startsWith("m=video") }
        assertEquals("m=video 9 UDP/TLS/RTP/SAVPF 102 103 96 97", mlines[0])
        assertEquals("m=video 9 UDP/TLS/RTP/SAVPF 96 102 103 97", mlines[1])
        assertEquals(tuned, CallSdp.withScreenVideo(tuned))
        val older = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 102 96\r\na=rtpmap:96 VP8/90000\r\n"
        assertEquals(older, CallSdp.withScreenVideo(older))
        // The full tuning both sides apply before setLocalDescription (ME:392, 413).
        assertEquals(
            CallSdp.withCameraVideo(CallSdp.withScreenVideo(CallSdp.withScreenSound(CallSdp.withVoiceResilience(sdp)))),
            CallSdp.tuned(sdp),
        )
    }

    @Test
    fun h264LeadsTheCamerasPictureAndTheScreenKeepsVp8First() {
        // A browser's order: VP8 first, H.264 later in two profiles.
        val sdp = listOf(
            "v=0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=rtpmap:111 opus/48000/2",
            "m=video 9 UDP/TLS/RTP/SAVPF 96 97 102 103 127",
            "a=rtpmap:96 VP8/90000",
            "a=rtpmap:97 rtx/90000",
            "a=rtpmap:102 H264/90000",
            "a=rtpmap:103 rtx/90000",
            "a=rtpmap:127 H264/90000",
            "m=video 9 UDP/TLS/RTP/SAVPF 96 97 102 103 127",
            "a=rtpmap:96 VP8/90000",
            "a=rtpmap:97 rtx/90000",
            "a=rtpmap:102 H264/90000",
            "a=rtpmap:103 rtx/90000",
            "a=rtpmap:127 H264/90000",
            "",
        ).joinToString("\r\n")
        val tuned = CallSdp.withCameraVideo(CallSdp.withScreenVideo(sdp))
        val mlines = tuned.split("\r\n").filter { it.startsWith("m=video") }
        assertEquals("H.264 leads the camera's section", "m=video 9 UDP/TLS/RTP/SAVPF 102 127 96 97 103", mlines[0])
        assertEquals("the screen keeps VP8 first", "m=video 9 UDP/TLS/RTP/SAVPF 96 97 102 103 127", mlines[1])
        assertEquals("a second pass changes nothing", tuned, CallSdp.withCameraVideo(tuned))
        val noH264 = sdp.replace("H264", "VP9")
        assertEquals("without H.264 the camera's section is unchanged", noH264, CallSdp.withCameraVideo(noH264))
        val voiceOnly = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
        assertEquals("an sdp without video is unchanged", voiceOnly, CallSdp.withCameraVideo(voiceOnly))
        // Offer and answer both go through tuned(): the camera's section leads with H.264 there too.
        assertEquals("m=video 9 UDP/TLS/RTP/SAVPF 102 127 96 97 103", CallSdp.tuned(sdp).split("\r\n").first { it.startsWith("m=video") })
    }

    @Test
    fun theCamerasH264DeclaresAtLeastLevel40() {
        // The H.264 level each side declares for the camera: at least 4.0 (1080p30).
        assertEquals("3.1 is raised to 4.0", "42e028", CallSdp.h264Level("42e01f"))
        assertEquals("the profile part keeps its case", "640C28", CallSdp.h264Level("640C1F"))
        assertEquals("a higher level stays", "640c34", CallSdp.h264Level("640c34"))
        assertEquals("4.0 stays", "42e028", CallSdp.h264Level("42e028"))
        assertEquals("a malformed value is left alone", "42e0", CallSdp.h264Level("42e0"))
        assertEquals("a malformed value is left alone", "zzzzzz", CallSdp.h264Level("zzzzzz"))
        val sdp = listOf(
            "v=0",
            "m=video 9 UDP/TLS/RTP/SAVPF 96 102 127 103",
            "a=rtpmap:96 VP8/90000",
            "a=rtpmap:102 H264/90000",
            "a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f",
            "a=rtpmap:127 H264/90000",
            "a=fmtp:127 profile-level-id=640c34;packetization-mode=1",
            "a=rtpmap:103 rtx/90000",
            "a=fmtp:103 apt=102",
            "m=video 9 UDP/TLS/RTP/SAVPF 96 102",
            "a=rtpmap:96 VP8/90000",
            "a=rtpmap:102 H264/90000",
            "a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f",
            "",
        ).joinToString("\r\n")
        val tuned = CallSdp.withCameraVideo(sdp)
        val lines = tuned.split("\r\n")
        assertEquals("H.264 first", "m=video 9 UDP/TLS/RTP/SAVPF 102 127 96 103", lines[1])
        assertEquals(
            "the camera's 3.1 becomes 4.0",
            "a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e028",
            lines[4],
        )
        assertEquals("a higher level stays", "a=fmtp:127 profile-level-id=640c34;packetization-mode=1", lines[6])
        assertEquals("rtx is untouched", "a=fmtp:103 apt=102", lines[8])
        assertTrue("the screen's section keeps its level", lines[12].endsWith("profile-level-id=42e01f"))
        assertEquals("a second pass changes nothing", tuned, CallSdp.withCameraVideo(tuned))
        val lf = sdp.replace("\r\n", "\n")
        assertEquals("LF line endings are kept", tuned.replace("\r\n", "\n"), CallSdp.withCameraVideo(lf))
    }
}
