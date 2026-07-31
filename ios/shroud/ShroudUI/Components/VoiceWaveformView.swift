import SwiftUI

/// Telegram-style amplitude bars for voice messages.
///
/// Human: One renderer for both sides of the feature — the live recording bar and the sent
/// bubble — so a recording always looks like the message it becomes. `progress` tints the played
/// portion; a bar straddling the playhead is tinted proportionally rather than snapping, which is
/// what keeps scrubbing feeling continuous instead of steppy.
struct VoiceWaveformView: View {
    /// Normalised amplitudes, 0…1, oldest/leftmost first.
    let samples: [Float]
    /// 0…1 played fraction. Bars before it use `playedColor`.
    var progress: Double = 0
    var playedColor: Color
    var remainingColor: Color

    var barWidth: CGFloat = 3
    var spacing: CGFloat = 2
    var minHeight: CGFloat = 3

    var body: some View {
        GeometryReader { geo in
            HStack(alignment: .center, spacing: spacing) {
                ForEach(Array(samples.enumerated()), id: \.offset) { index, sample in
                    Capsule(style: .continuous)
                        .fill(color(at: index))
                        .frame(
                            width: barWidth,
                            height: height(for: sample, in: geo.size.height)
                        )
                }
            }
            .frame(width: geo.size.width, height: geo.size.height, alignment: .leading)
        }
        .accessibilityHidden(true)
    }

    private func height(for sample: Float, in available: CGFloat) -> CGFloat {
        max(minHeight, available * CGFloat(min(1, max(0, sample))))
    }

    /// Bars fully behind the playhead are "played"; the one under it blends by how far in it is.
    private func color(at index: Int) -> Color {
        guard !samples.isEmpty else { return remainingColor }
        let position = Double(index) / Double(samples.count)
        let next = Double(index + 1) / Double(samples.count)
        if progress >= next { return playedColor }
        if progress <= position { return remainingColor }
        let fraction = (progress - position) / max(next - position, .ulpOfOne)
        return remainingColor.mix(with: playedColor, by: fraction)
    }
}

// MARK: - Sample plumbing

enum VoiceWaveform {
    /// Payload encoding: raw 0…255 bytes, base64'd into the sealed media payload.
    static func encode(_ buckets: [UInt8]) -> String? {
        guard !buckets.isEmpty else { return nil }
        return Data(buckets).base64EncodedString()
    }

    static func decode(_ base64: String?) -> [UInt8]? {
        guard let base64, let data = Data(base64Encoded: base64), !data.isEmpty else { return nil }
        return [UInt8](data)
    }

    static func normalized(_ buckets: [UInt8]) -> [Float] {
        buckets.map { Float($0) / 255 }
    }

    /// Whether a stored envelope carries real amplitude information.
    ///
    /// Human: An earlier build sealed a *constant* envelope into every voice message — the
    /// recorder cleared its samples before downsampling them, so the payload said "flat". Those
    /// messages are already sent and cannot be rewritten, so a flat array is treated as missing
    /// data and falls back to the placeholder rather than drawing a dead line forever. Speech
    /// varies far more than this threshold, so real quiet recordings are unaffected.
    static func isUsable(_ buckets: [UInt8]) -> Bool {
        guard let low = buckets.min(), let high = buckets.max() else { return false }
        return high - low >= 8
    }

    /// Averages a captured envelope (0…1 per sample) into `buckets` bytes of 0…255.
    ///
    /// Human: Pure on purpose — `VoiceRecorder` used to do this inline against its own mutable
    /// state and computed it *after* tearing that state down, so every recorded message shipped a
    /// dead-flat waveform. As a free function it is testable, and the recorder can only call it
    /// with an envelope it still holds.
    /// Returns `[]` for an empty envelope so callers omit the payload field and the bubble falls
    /// back to its placeholder instead of drawing a flat line.
    static func downsample(_ envelope: [Float], buckets: Int) -> [UInt8] {
        guard buckets > 0, !envelope.isEmpty else { return [] }

        var out: [UInt8] = []
        out.reserveCapacity(buckets)
        let stride = Double(envelope.count) / Double(buckets)
        for bucket in 0 ..< buckets {
            let start = Int(Double(bucket) * stride)
            let end = max(start + 1, Int(Double(bucket + 1) * stride))
            let slice = envelope[min(start, envelope.count - 1) ..< min(end, envelope.count)]
            let mean = slice.isEmpty ? 0 : slice.reduce(0, +) / Float(slice.count)
            // Floor at 8 so a silent stretch reads as a quiet line rather than a gap.
            out.append(UInt8(max(8, min(255, mean * 255))))
        }
        return out
    }

    /// Resamples to exactly `count` bars so a bubble can size itself by duration.
    static func resample(_ samples: [Float], to count: Int) -> [Float] {
        guard count > 0 else { return [] }
        guard samples.count != count else { return samples }
        guard !samples.isEmpty else { return Array(repeating: 0.1, count: count) }

        return (0 ..< count).map { index in
            let start = Int(Double(index) * Double(samples.count) / Double(count))
            let end = max(start + 1, Int(Double(index + 1) * Double(samples.count) / Double(count)))
            let slice = samples[min(start, samples.count - 1) ..< min(end, samples.count)]
            guard !slice.isEmpty else { return samples[min(start, samples.count - 1)] }
            return slice.reduce(0, +) / Float(slice.count)
        }
    }

    /// Stand-in envelope for messages sent before waveforms were part of the payload.
    ///
    /// Human: Rendering those as a flat line would look broken, so we derive a stable pseudo-random
    /// shape from the message id — same message always draws the same bars, and it reads as
    /// "speech" rather than as data we actually have.
    static func placeholder(for id: UUID, count: Int) -> [Float] {
        var generator = SplitMix64(seed: id.uuidString.hashValue)
        return (0 ..< count).map { index in
            // Taper the ends so it reads as an utterance rather than a block.
            let position = Double(index) / Double(max(count - 1, 1))
            let envelope = sin(position * .pi) * 0.45 + 0.55
            let jitter = Double(generator.next() % 1000) / 1000
            return Float(min(1, max(0.12, envelope * (0.45 + jitter * 0.55))))
        }
    }

    /// Small deterministic PRNG — `hashValue` is per-launch seeded, but a bubble only needs
    /// stability for as long as it is on screen.
    private struct SplitMix64 {
        private var state: UInt64

        init(seed: Int) {
            state = UInt64(bitPattern: Int64(seed))
        }

        mutating func next() -> UInt64 {
            state &+= 0x9E37_79B9_7F4A_7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
            z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
            return z ^ (z >> 31)
        }
    }
}

#Preview {
    VStack(spacing: 24) {
        VoiceWaveformView(
            samples: VoiceWaveform.placeholder(for: UUID(), count: 44),
            progress: 0.45,
            playedColor: .white,
            remainingColor: .white.opacity(0.4)
        )
        .frame(height: 26)
        .padding()
        .background(Theme.accent)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))

        VoiceWaveformView(
            samples: VoiceWaveform.placeholder(for: UUID(), count: 44),
            progress: 0.7,
            playedColor: Theme.accent,
            remainingColor: Theme.separator
        )
        .frame(height: 26)
        .padding()
        .background(Theme.bubbleIncoming)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
    .padding()
    .background(Theme.backgroundChat)
}
