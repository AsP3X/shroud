import Foundation
import Testing
@testable import shroud

/// Guards the voice-message amplitude envelope end to end: capture → downsample →
/// payload encode/decode → render-time resample.
struct VoiceWaveformTests {
    // MARK: - Downsample

    /// The regression this suite exists for.
    ///
    /// `VoiceRecorder.finish()` used to tear down its state (clearing `envelope`) *before*
    /// computing the waveform, so every recorded message shipped a flat placeholder and the
    /// bubble drew a dead line. A varied envelope must never flatten.
    @Test
    func downsampleKeepsVariationFromAVariedEnvelope() {
        // Ramp 0…1 across 200 samples — the loudest bucket must clearly beat the quietest.
        let envelope = (0 ..< 200).map { Float($0) / 199 }
        let buckets = VoiceWaveform.downsample(envelope, buckets: 44)

        #expect(buckets.count == 44)
        guard let low = buckets.first, let high = buckets.last else {
            Issue.record("expected non-empty waveform")
            return
        }
        #expect(high > low)
        #expect(high - low > 100)
        #expect(Set(buckets).count > 10, "a ramp must not collapse to a handful of values")
    }

    @Test
    func downsampleReturnsEmptyForAnEmptyEnvelope() {
        // Empty means "we have no data", not "the audio was silent" — callers omit the
        // payload field so the bubble falls back to its per-message placeholder.
        #expect(VoiceWaveform.downsample([], buckets: 44).isEmpty)
        #expect(VoiceWaveform.downsample([0.5, 0.5], buckets: 0).isEmpty)
    }

    @Test
    func downsampleAveragesWithinEachBucket() {
        // Two buckets: first half 0, second half 1.
        let envelope: [Float] = Array(repeating: 0, count: 50) + Array(repeating: 1, count: 50)
        let buckets = VoiceWaveform.downsample(envelope, buckets: 2)

        #expect(buckets.count == 2)
        // Silence is floored at 8 so the bar stays visible.
        #expect(buckets[0] == 8)
        #expect(buckets[1] == 255)
    }

    @Test
    func downsampleFloorsSilenceInsteadOfProducingGaps() {
        let buckets = VoiceWaveform.downsample(Array(repeating: 0, count: 100), buckets: 12)
        #expect(buckets.count == 12)
        #expect(buckets.allSatisfy { $0 == 8 })
    }

    @Test
    func downsampleHandlesFewerSamplesThanBuckets() {
        let buckets = VoiceWaveform.downsample([0.2, 0.9], buckets: 16)
        #expect(buckets.count == 16)
    }

    // MARK: - Usability of stored envelopes

    /// Messages sent by the build that sealed a constant envelope must not render a dead line.
    @Test
    func flatStoredWaveformIsRejectedSoTheBubbleFallsBack() {
        #expect(!VoiceWaveform.isUsable(Array(repeating: 24, count: 44)))
        #expect(!VoiceWaveform.isUsable([]))
        #expect(!VoiceWaveform.isUsable([100, 103, 99, 102]))
    }

    @Test
    func variedStoredWaveformIsUsed() {
        #expect(VoiceWaveform.isUsable([8, 90, 200, 40]))
        // A real recording round-tripped through downsample must survive this check.
        let envelope = (0 ..< 120).map { Float($0 % 40) / 39 }
        #expect(VoiceWaveform.isUsable(VoiceWaveform.downsample(envelope, buckets: 44)))
    }

    // MARK: - Payload round trip

    @Test
    func encodeDecodeRoundTripsExactly() {
        let original: [UInt8] = [8, 40, 120, 200, 255, 17]
        guard let encoded = VoiceWaveform.encode(original) else {
            Issue.record("expected base64 for a non-empty waveform")
            return
        }
        #expect(VoiceWaveform.decode(encoded) == original)
    }

    @Test
    func encodeReturnsNilForEmptySoThePayloadOmitsTheField() {
        #expect(VoiceWaveform.encode([]) == nil)
    }

    @Test
    func decodeRejectsMissingOrGarbageValues() {
        // Messages predating the waveform field decode as nil rather than throwing.
        #expect(VoiceWaveform.decode(nil) == nil)
        #expect(VoiceWaveform.decode("") == nil)
        #expect(VoiceWaveform.decode("not base64!!") == nil)
    }

    @Test
    func normalizedMapsBytesToUnitRange() {
        let values = VoiceWaveform.normalized([0, 128, 255])
        #expect(values[0] == 0)
        #expect(abs(values[1] - 0.502) < 0.01)
        #expect(values[2] == 1)
    }

    // MARK: - Render-time resample

    @Test
    func resampleHitsTheRequestedBarCount() {
        let samples = (0 ..< 44).map { Float($0) / 43 }
        #expect(VoiceWaveform.resample(samples, to: 18).count == 18)
        #expect(VoiceWaveform.resample(samples, to: 38).count == 38)
        #expect(VoiceWaveform.resample(samples, to: 44) == samples)
    }

    @Test
    func resamplePreservesTheOverallShape() {
        let samples = (0 ..< 44).map { Float($0) / 43 }
        let reduced = VoiceWaveform.resample(samples, to: 11)
        guard let first = reduced.first, let last = reduced.last else {
            Issue.record("expected non-empty resample")
            return
        }
        #expect(last > first)
    }

    @Test
    func resampleOfEmptySamplesStillFillsTheBars() {
        // A bubble must never render zero bars, even with no data at all.
        #expect(VoiceWaveform.resample([], to: 20).count == 20)
    }

    // MARK: - Placeholder (pre-waveform messages)

    @Test
    func placeholderIsStableForTheSameMessage() {
        let id = UUID()
        #expect(VoiceWaveform.placeholder(for: id, count: 30) == VoiceWaveform.placeholder(for: id, count: 30))
    }

    @Test
    func placeholderIsVariedAndInRange() {
        let samples = VoiceWaveform.placeholder(for: UUID(), count: 40)
        #expect(samples.count == 40)
        #expect(samples.allSatisfy { $0 >= 0 && $0 <= 1 })
        #expect(Set(samples).count > 5, "placeholder must read as speech, not a flat line")
    }

    // MARK: - Sealed payload compatibility

    @Test
    func voicePayloadCarriesTheWaveformThroughJSON() throws {
        let waveform: [UInt8] = [8, 64, 200, 255, 31]
        let payload = MediaMessagePayload(
            t: MediaMessagePayload.kindVoice,
            mime: "audio/mp4",
            w: 0,
            h: 0,
            k: "a2V5",
            c: nil,
            d: 4200,
            wf: VoiceWaveform.encode(waveform)
        )

        let data = try JSONEncoder().encode(payload)
        let decoded = try JSONDecoder().decode(MediaMessagePayload.self, from: data)

        #expect(decoded.isVoice)
        #expect(decoded.d == 4200)
        #expect(VoiceWaveform.decode(decoded.wf) == waveform)
    }

    /// Old-client backtest: voice payloads sealed before `wf` existed must still decode.
    @Test
    func legacyVoicePayloadWithoutWaveformStillDecodes() throws {
        let legacy = #"{"t":"voice","mime":"audio/mp4","w":0,"h":0,"k":"a2V5","d":1}"#
        let decoded = try JSONDecoder().decode(
            MediaMessagePayload.self,
            from: Data(legacy.utf8)
        )

        #expect(decoded.isVoice)
        #expect(decoded.wf == nil)
        #expect(VoiceWaveform.decode(decoded.wf) == nil)
    }

    // MARK: - Level normalisation

    @Test
    func normalizeMapsTheDecibelWindowOntoUnitRange() {
        // -50 dB is the noise floor, 0 dB is full scale.
        #expect(VoiceRecorder.normalize(average: -50, peak: -50) == 0)
        #expect(VoiceRecorder.normalize(average: 0, peak: 0) == 1)

        let mid = VoiceRecorder.normalize(average: -25, peak: -25)
        #expect(mid > 0 && mid < 1)
    }

    @Test
    func normalizeClampsBelowTheFloorAndHandlesInfinity() {
        #expect(VoiceRecorder.normalize(average: -160, peak: -160) == 0)
        #expect(VoiceRecorder.normalize(average: -.infinity, peak: -.infinity) == 0)
    }

    @Test
    func normalizeLetsPeaksLiftAQuietAverage() {
        let flat = VoiceRecorder.normalize(average: -40, peak: -40)
        let transient = VoiceRecorder.normalize(average: -40, peak: -5)
        #expect(transient > flat)
    }
}
