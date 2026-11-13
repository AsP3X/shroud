import Foundation
import Testing
@testable import shroud

/// Wire format shared with the web client (`parseMediaPayload`). Must survive JSON that
/// `JSONDecoder` on another iOS version rejects (missing zeros, doubles, extra keys).
struct MediaMessagePayloadTests {
    @Test
    func webStyleVoicePayloadParsesWithoutWidthHeight() throws {
        let json = #"{"t":"voice","mime":"audio/mp4","k":"a2V5","d":4200,"wf":"AQI="}"#
        let payload = try #require(MediaMessagePayload.parse(Data(json.utf8)))
        #expect(payload.isVoice)
        #expect(payload.w == 0)
        #expect(payload.h == 0)
        #expect(payload.d == 4200)
        #expect(payload.k == "a2V5")
        #expect(payload.wf == "AQI=")
    }

    @Test
    func webStyleImagePayloadParses() throws {
        let json = #"{"t":"image","mime":"image/jpeg","w":800,"h":600,"k":"a2V5","s":12345,"th":"qqo="}"#
        let payload = try #require(MediaMessagePayload.parse(Data(json.utf8)))
        #expect(payload.isImage)
        #expect(payload.w == 800)
        #expect(payload.h == 600)
        #expect(payload.s == 12345)
        #expect(payload.previewJPEG?.count == 2)
    }

    @Test
    func numericFieldsAcceptJSONDoubles() throws {
        let json = #"{"t":"voice","mime":"audio/mp4","w":0.0,"h":0.0,"k":"a2V5","d":1500.0,"s":99.0}"#
        let payload = try #require(MediaMessagePayload.parse(Data(json.utf8)))
        #expect(payload.isVoice)
        #expect(payload.w == 0)
        #expect(payload.h == 0)
        #expect(payload.d == 1500)
        #expect(payload.s == 99)
    }

    @Test
    func extraKeysAndNullOptionalsAreIgnored() throws {
        let json = #"{"t":"video","mime":"video/mp4","w":1,"h":2,"k":"a2V5","c":null,"wf":null,"extra":true}"#
        let payload = try #require(MediaMessagePayload.parse(Data(json.utf8)))
        #expect(payload.isVideo)
        #expect(payload.c == nil)
        #expect(payload.wf == nil)
    }

    /// A caption, thumbnail or waveform that is not a string is dropped, and the rest of the
    /// payload still parses (the iOS half of November audit W1, fixed on the web in SHRO-16).
    @Test
    func nonStringCaptionThumbnailAndWaveformAreDropped() throws {
        let json = #"{"t":"voice","mime":"audio/mp4","k":"a2V5","d":4200,"c":42,"th":{"x":1},"wf":[1,2]}"#
        let voice = try #require(MediaMessagePayload.parse(Data(json.utf8)))
        #expect(voice.isVoice)
        #expect(voice.d == 4200)
        #expect(voice.c == nil)
        #expect(voice.th == nil)
        #expect(voice.wf == nil)

        let image = #"{"t":"image","mime":"image/jpeg","w":8,"h":6,"k":"a2V5","c":true,"th":7}"#
        let photo = try #require(MediaMessagePayload.parse(Data(image.utf8)))
        #expect(photo.isImage)
        #expect(photo.c == nil)
        #expect(photo.previewJPEG == nil)
    }

    @Test
    func mimeSniffsWhenTypeIsUnknown() throws {
        let json = #"{"t":"note","mime":"audio/mp4","k":"a2V5"}"#
        let payload = try #require(MediaMessagePayload.parse(Data(json.utf8)))
        #expect(payload.isVoice)
        #expect(!payload.isImage)
    }

    @Test
    func utf8BOMDoesNotHideAValidPayload() throws {
        var data = Data([0xEF, 0xBB, 0xBF])
        data.append(contentsOf: #"{"t":"image","mime":"image/png","w":1,"h":1,"k":"a2V5"}"#.utf8)
        let payload = try #require(MediaMessagePayload.parse(data))
        #expect(payload.isImage)
    }

    @Test
    func encodedRoundTripsThroughParse() throws {
        let original = MediaMessagePayload(
            t: MediaMessagePayload.kindVoice,
            mime: "audio/mp4",
            w: 0,
            h: 0,
            k: "a2V5",
            c: "hello",
            d: 900,
            wf: "AQI=",
            th: nil,
            s: 44
        )
        let parsed = try #require(MediaMessagePayload.parse(try original.encoded()))
        #expect(parsed == original)
    }

    @Test
    func jsonEncoderOutputStillParses() throws {
        let original = MediaMessagePayload(
            t: MediaMessagePayload.kindImage,
            mime: "image/jpeg",
            w: 10,
            h: 20,
            k: "a2V5",
            c: nil,
            d: nil,
            wf: nil,
            th: "qqo=",
            s: 8
        )
        let encoded = try JSONEncoder().encode(original)
        let parsed = try #require(MediaMessagePayload.parse(encoded))
        #expect(parsed.t == original.t)
        #expect(parsed.w == 10)
        #expect(parsed.h == 20)
        #expect(parsed.th == "qqo=")
    }
}
