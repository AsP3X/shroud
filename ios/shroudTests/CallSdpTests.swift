import Foundation
import Testing
@testable import shroud

struct CallSdpTests {
    @Test
    func opusGainsErrorCorrectionWithoutTouchingAnotherCodec() {
        let sdp = """
        v=0\r
        m=audio 9 UDP/TLS/RTP/SAVPF 111 0\r
        a=rtpmap:111 opus/48000/2\r
        a=fmtp:111 minptime=10;sprop-stereo=1;useinbandfec=0\r
        a=rtpmap:0 PCMU/8000\r
        a=fmtp:0 comfort=1\r
        """
        let tuned = CallSdp.withVoiceResilience(sdp)
        #expect(tuned.contains("a=fmtp:111 minptime=10;sprop-stereo=0;useinbandfec=1;usedtx=1;stereo=0;maxaveragebitrate=32000"))
        #expect(!tuned.contains("useinbandfec=0"))
        #expect(!tuned.contains("sprop-stereo=1"))
        #expect(tuned.contains("a=fmtp:0 comfort=1"))
        #expect(tuned.hasPrefix("v=0\r\n"))
        #expect(tuned == CallSdp.withVoiceResilience(tuned))
    }

    @Test
    func missingFmtpIsInsertedAndALineWithoutOpusIsKept() {
        let sdp = "v=0\nm=audio 9 UDP/TLS/RTP/SAVPF 111\na=rtpmap:111 opus/48000/2\n"
        let tuned = CallSdp.withVoiceResilience(sdp)
        #expect(tuned == "v=0\nm=audio 9 UDP/TLS/RTP/SAVPF 111\na=rtpmap:111 opus/48000/2\na=fmtp:111 useinbandfec=1;usedtx=1;stereo=0;sprop-stereo=0;maxaveragebitrate=32000\n")
        #expect(CallSdp.withVoiceResilience("v=0\r\n") == "v=0\r\n")
    }

    @Test
    func theScreensSoundGetsMusicSettingsAndTheMicrophoneKeepsSpeech() {
        let sdp = [
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
        ].joined(separator: "\r\n")
        let tuned = CallSdp.withScreenSound(CallSdp.withVoiceResilience(sdp))
        let fmtps = tuned.components(separatedBy: "\r\n").filter { $0.hasPrefix("a=fmtp:111") }
        #expect(fmtps.count == 2)
        #expect(fmtps[0] == "a=fmtp:111 minptime=10;useinbandfec=1;usedtx=1;stereo=0;sprop-stereo=0;maxaveragebitrate=32000")
        #expect(fmtps[1] == "a=fmtp:111 minptime=10;useinbandfec=1;usedtx=0;stereo=1;sprop-stereo=1;maxaveragebitrate=128000")
        #expect(CallSdp.withScreenSound(tuned) == tuned)
        // An older app's description has one audio section: nothing to do.
        let older = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2\r\n"
        #expect(CallSdp.withScreenSound(older) == older)
        // No fmtp in the screen's section: one is added after its rtpmap.
        let bare = "v=0\nm=audio 9 X 111\na=rtpmap:111 opus/48000/2\nm=audio 9 X 111\na=rtpmap:111 opus/48000/2\n"
        #expect(CallSdp.withScreenSound(bare).hasSuffix("a=rtpmap:111 opus/48000/2\na=fmtp:111 useinbandfec=1;usedtx=0;stereo=1;sprop-stereo=1;maxaveragebitrate=128000\n"))
    }

    @Test
    func vp8LeadsTheScreensPictureAndTheCameraKeepsItsOrder() {
        let sdp = [
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
        ].joined(separator: "\r\n")
        let tuned = CallSdp.withScreenVideo(sdp)
        let mlines = tuned.components(separatedBy: "\r\n").filter { $0.hasPrefix("m=video") }
        #expect(mlines[0] == "m=video 9 UDP/TLS/RTP/SAVPF 102 103 96 97")
        #expect(mlines[1] == "m=video 9 UDP/TLS/RTP/SAVPF 96 102 103 97")
        #expect(CallSdp.withScreenVideo(tuned) == tuned)
        let older = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 102 96\r\na=rtpmap:96 VP8/90000\r\n"
        #expect(CallSdp.withScreenVideo(older) == older)
    }

    @Test
    func h264LeadsTheCamerasPictureAndTheScreenKeepsVp8First() {
        // A browser's order: VP8 first, H.264 later in two profiles.
        let sdp = [
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
        ].joined(separator: "\r\n")
        let tuned = CallSdp.withCameraVideo(CallSdp.withScreenVideo(sdp))
        let mlines = tuned.components(separatedBy: "\r\n").filter { $0.hasPrefix("m=video") }
        #expect(mlines[0] == "m=video 9 UDP/TLS/RTP/SAVPF 102 127 96 97 103")
        #expect(mlines[1] == "m=video 9 UDP/TLS/RTP/SAVPF 96 97 102 103 127")
        #expect(CallSdp.withCameraVideo(tuned) == tuned)
        let noH264 = sdp.replacingOccurrences(of: "H264", with: "VP9")
        #expect(CallSdp.withCameraVideo(noH264) == noH264)
        let voiceOnly = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
        #expect(CallSdp.withCameraVideo(voiceOnly) == voiceOnly)
    }

    /// The H.264 level each side declares for the camera: at least 4.0 (1080p30).
    @Test
    func theCamerasH264LevelIsAtLeast4() {
        #expect(CallSdp.h264Level("42e01f") == "42e028", "3.1 is raised to 4.0")
        #expect(CallSdp.h264Level("640C1F") == "640C28", "the profile part keeps its case")
        #expect(CallSdp.h264Level("640c34") == "640c34", "a higher level stays")
        #expect(CallSdp.h264Level("42e028") == "42e028", "4.0 stays")
        #expect(CallSdp.h264Level("42e0") == "42e0" && CallSdp.h264Level("zzzzzz") == "zzzzzz", "a malformed value is left alone")
        let sdp = [
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
        ].joined(separator: "\r\n")
        let tuned = CallSdp.withCameraVideo(sdp)
        let lines = tuned.components(separatedBy: "\r\n")
        #expect(lines[1] == "m=video 9 UDP/TLS/RTP/SAVPF 102 127 96 103", "H.264 first")
        #expect(lines[4] == "a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e028", "the camera's 3.1 becomes 4.0")
        #expect(lines[6] == "a=fmtp:127 profile-level-id=640c34;packetization-mode=1", "a higher level stays")
        #expect(lines[8] == "a=fmtp:103 apt=102", "rtx is untouched")
        #expect(lines[12].hasSuffix("profile-level-id=42e01f"), "the screen's section keeps its level")
        #expect(CallSdp.withCameraVideo(tuned) == tuned, "a second pass changes nothing")
        let lf = sdp.replacingOccurrences(of: "\r\n", with: "\n")
        #expect(CallSdp.withCameraVideo(lf) == tuned.replacingOccurrences(of: "\r\n", with: "\n"), "LF line endings are kept")
    }
}
