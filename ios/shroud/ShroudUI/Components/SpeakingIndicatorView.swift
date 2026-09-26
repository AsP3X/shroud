import SwiftUI

/// "You're speaking": a glass capsule with a mic glyph and five bars that follow the
/// microphone's level, under the status line of the call screen.
///
/// Human: The level is polled from WebRTC's sender stats — the only tap the stock build
/// offers — and would look steppy drawn as-is, so a 30 fps timeline eases each bar towards
/// its target with a fast attack and a slower release. Nothing observable changes per frame:
/// the timeline redraws one small Canvas and pauses once the mic has been quiet for a moment,
/// and the stats poll slows with it. Only the "speaking / quiet" flip touches SwiftUI state.
struct SpeakingIndicatorView: View {
    /// The microphone's linear level, 0…1; nil while there is no media.
    let level: () async -> Float?

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var meter = Meter()
    @State private var speaking = false

    private static let barCount = 5
    private static let barWidth: CGFloat = 3
    private static let barSpacing: CGFloat = 2
    private static let barHeight: CGFloat = 16
    private static let pollInterval: Duration = .milliseconds(66)
    /// Quiet mics are only checked often enough to notice the next word. Stats are a
    /// signaling-thread hop, so a silent call does not stay at the speaking rate.
    private static let idlePoll: Duration = .milliseconds(250)
    /// How long the bars keep animating after the last voice; they have settled well before.
    private static let holdSeconds: TimeInterval = 1

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "mic.fill")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(.white)
            bars
                .frame(
                    width: CGFloat(Self.barCount) * Self.barWidth + CGFloat(Self.barCount - 1) * Self.barSpacing,
                    height: Self.barHeight
                )
        }
        .padding(.vertical, 5)
        .padding(.horizontal, 10)
        .glassEffect(.regular, in: .capsule)
        .accessibilityHidden(true)
        .task(id: reduceMotion) { await poll() }
    }

    private var bars: some View {
        TimelineView(.animation(minimumInterval: 1 / 30, paused: !speaking)) { context in
            Canvas { graphics, size in
                let heights = meter.step(now: context.date.timeIntervalSinceReferenceDate, wobble: !reduceMotion)
                for (index, fraction) in heights.enumerated() {
                    let height = max(Self.barWidth, size.height * CGFloat(fraction))
                    let x = CGFloat(index) * (Self.barWidth + Self.barSpacing)
                    let rect = CGRect(x: x, y: (size.height - height) / 2, width: Self.barWidth, height: height)
                    graphics.fill(Capsule(style: .continuous).path(in: rect), with: .color(.white))
                }
            }
        }
    }

    /// Polls the level and flips `speaking` only when the answer changes.
    private func poll() async {
        var lastVoice: TimeInterval = 0
        while !Task.isCancelled {
            let raw = await level() ?? 0
            let now = Date.timeIntervalSinceReferenceDate
            let target = Meter.display(raw)
            meter.target = target
            if target > Meter.gate { lastVoice = now }
            let active = now - lastVoice < Self.holdSeconds
            if active != speaking { speaking = active }
            try? await Task.sleep(for: active ? Self.pollInterval : Self.idlePoll)
        }
    }

    /// The per-bar easing. A plain class: the Canvas mutates it every frame, and nothing
    /// should re-render because of that.
    private final class Meter {
        /// The display level the bars head towards, 0…1.
        var target: Float = 0
        /// Below this, the mic counts as quiet (about −45 dBFS after the mapping).
        static let gate: Float = 0.12

        private var heights: [Float] = Array(repeating: 0, count: SpeakingIndicatorView.barCount)
        private var lastTick: TimeInterval = 0
        /// The outer bars follow a little less than the middle, so it reads as a waveform.
        private let weights: [Float] = [0.55, 0.8, 1, 0.8, 0.55]
        private let wobbleRates: [Double] = [9.1, 12.7, 7.3, 11.2, 8.6]
        private let wobblePhases: [Double] = [0, 1.9, 3.1, 4.4, 5.6]

        /// Linear 0…1 to a display fraction: −50 dBFS is the floor, −10 dBFS fills the bar.
        static func display(_ linear: Float) -> Float {
            guard linear > 0 else { return 0 }
            let decibels = 20 * log10(linear)
            return min(1, max(0, (decibels + 50) / 40))
        }

        func step(now: TimeInterval, wobble: Bool) -> [Float] {
            let delta = lastTick == 0 ? 1.0 / 30 : min(0.1, now - lastTick)
            lastTick = now
            let attack = 1 - exp(-delta * 40)
            let release = 1 - exp(-delta * 12)
            for index in heights.indices {
                var goal = target * weights[index]
                if wobble, goal > 0 {
                    goal *= Float(0.75 + 0.25 * sin(now * wobbleRates[index] + wobblePhases[index]))
                }
                let rate = goal > heights[index] ? attack : release
                heights[index] += (goal - heights[index]) * Float(rate)
            }
            return heights
        }
    }
}

#Preview {
    ZStack {
        Color(red: 0.08, green: 0.10, blue: 0.16).ignoresSafeArea()
        SpeakingIndicatorView {
            // A synthetic voice: bursts of speech with pauses.
            let t = Date.timeIntervalSinceReferenceDate
            let talking = sin(t * 0.7) > -0.2
            return talking ? Float(0.05 + 0.2 * abs(sin(t * 5.3) * sin(t * 1.7))) : 0.001
        }
    }
}
