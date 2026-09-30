import AVFoundation
import Foundation
import Observation

/// Single owner of voice-message playback.
///
/// Human: Telegram only ever plays one voice note at a time, and starting a second one stops the
/// first. Keeping the player here (instead of one per bubble) gives us that for free, keeps a
/// single 30 Hz progress timer for the whole thread, and lets playback survive a bubble being
/// scrolled out of view and recycled.
/// Agent: OWNS AVAudioPlayer + AVAudioSession(.playback). READS decrypted audio bytes handed in by
/// the bubble; never fetches or decrypts anything itself. `playedIDs` is in-memory only.
@Observable
@MainActor
final class VoicePlaybackCoordinator {
    static let shared = VoicePlaybackCoordinator()

    /// Playback speeds cycled by the bubble's rate chip.
    static let rates: [Float] = [1, 1.5, 2]

    /// Message currently loaded into the player (playing *or* paused mid-way).
    private(set) var activeID: UUID?
    private(set) var isPlaying = false
    private(set) var currentTime: TimeInterval = 0
    private(set) var duration: TimeInterval = 0
    /// Sticky across messages, like Telegram's per-chat speed setting.
    private(set) var rate: Float = 1
    /// Incoming notes the user has already listened to (drives the unplayed dot).
    private(set) var playedIDs: Set<UUID> = []

    private var player: AVAudioPlayer?
    private var ticker: Task<Void, Never>?
    /// Bumped so an in-flight session activate cannot start a note the user already left.
    private var sessionGeneration = 0

    private init() {}

    // MARK: - Queries

    /// 0…1 position for `id`, or 0 when that message is not the active one.
    func progress(for id: UUID) -> Double {
        guard activeID == id, duration > 0 else { return 0 }
        return min(1, max(0, currentTime / duration))
    }

    func isActive(_ id: UUID) -> Bool { activeID == id }

    func isPlaying(_ id: UUID) -> Bool { activeID == id && isPlaying }

    /// Elapsed while active, otherwise the message's own length.
    func displayTime(for id: UUID, fallbackMs: Int) -> TimeInterval {
        guard activeID == id else { return Double(fallbackMs) / 1000 }
        return currentTime
    }

    func hasPlayed(_ id: UUID) -> Bool { playedIDs.contains(id) }

    // MARK: - Transport

    /// Play/pause `id`, loading `data` if it is not the active message.
    func toggle(id: UUID, data: Data) {
        if activeID == id {
            if isPlaying {
                pause()
            } else {
                Task { await resume() }
            }
            return
        }
        Task { await start(id: id, data: data, at: 0) }
    }

    func pause() {
        sessionGeneration += 1
        player?.pause()
        isPlaying = false
        stopTicker()
    }

    func resume() async {
        sessionGeneration += 1
        let generation = sessionGeneration
        try? await ChatAudioSession.shared.activate(.spokenPlayback)
        guard generation == sessionGeneration, let player else { return }
        player.enableRate = true
        player.rate = rate
        guard player.play() else { return }
        isPlaying = true
        startTicker()
    }

    /// Scrub the active message. `fraction` is 0…1.
    func seek(id: UUID, data: Data, to fraction: Double) {
        let clamped = min(1, max(0, fraction))
        if activeID != id {
            // Scrubbing a message that is not loaded yet: load it paused at that point.
            Task { await start(id: id, data: data, at: clamped, autoplay: false) }
            return
        }
        guard let player else { return }
        player.currentTime = player.duration * clamped
        currentTime = player.currentTime
    }

    /// Advances to the next speed in `rates`, applying it live if something is playing.
    func cycleRate() {
        let index = Self.rates.firstIndex(of: rate) ?? 0
        rate = Self.rates[(index + 1) % Self.rates.count]
        guard let player, isPlaying else { return }
        player.enableRate = true
        player.rate = rate
    }

    func stop() {
        sessionGeneration += 1
        stopTicker()
        player?.stop()
        player = nil
        activeID = nil
        isPlaying = false
        currentTime = 0
        duration = 0
    }

    /// Stops playback if `id` is the active message (bubble disappearing, thread closing).
    func stopIfActive(_ id: UUID) {
        guard activeID == id else { return }
        stop()
    }

    // MARK: - Internals

    private func start(id: UUID, data: Data, at fraction: Double, autoplay: Bool = true) async {
        sessionGeneration += 1
        let generation = sessionGeneration
        stopTicker()
        player?.stop()

        try? await ChatAudioSession.shared.activate(.spokenPlayback)
        guard generation == sessionGeneration else { return }
        guard let newPlayer = try? AVAudioPlayer(data: data) else {
            // Corrupt or still-encrypted bytes — leave the previous state cleared.
            stop()
            return
        }
        newPlayer.enableRate = true
        newPlayer.prepareToPlay()
        newPlayer.currentTime = newPlayer.duration * min(1, max(0, fraction))
        newPlayer.rate = rate

        player = newPlayer
        activeID = id
        duration = newPlayer.duration
        currentTime = newPlayer.currentTime
        playedIDs.insert(id)

        guard autoplay else {
            isPlaying = false
            return
        }
        guard newPlayer.play() else {
            stop()
            return
        }
        isPlaying = true
        startTicker()
    }

    /// 30 Hz is enough for a smooth playhead and also detects end-of-file without a delegate.
    private func startTicker() {
        ticker?.cancel()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(33))
                guard let self, let player = self.player else { return }
                if player.isPlaying {
                    self.currentTime = player.currentTime
                } else if self.isPlaying {
                    // Reached the end: rewind so the next tap replays from the start.
                    self.finishPlayback()
                    return
                }
            }
        }
    }

    private func stopTicker() {
        ticker?.cancel()
        ticker = nil
    }

    private func finishPlayback() {
        stopTicker()
        isPlaying = false
        currentTime = 0
        player?.currentTime = 0
    }
}
