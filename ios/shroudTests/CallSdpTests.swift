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
}
