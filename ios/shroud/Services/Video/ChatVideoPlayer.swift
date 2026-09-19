import AVFoundation
import Foundation

/// Playback for one decrypted chat video, driving a custom transport.
///
/// Human: `VideoPlayer` from AVKit brings Apple's own control bar, which is nothing like
/// Telegram's. This owns a bare `AVPlayer` instead and publishes exactly what the overlay
/// draws: a playhead, a duration and a play/pause state.
/// Agent: OWNS AVPlayer + one temp .mp4 on disk. `teardown()` is mandatory — it removes the
/// time observer, the end notification and the file.
@MainActor
@Observable
final class ChatVideoPlayer {
    private(set) var player: AVPlayer?
    private(set) var duration: Double = 0
    private(set) var currentTime: Double = 0
    private(set) var isPlaying = false
    private(set) var isReady = false
    private(set) var failed = false
    /// True while a finger owns the playhead; observer ticks are ignored until it lifts.
    var isScrubbing = false
    /// When set, playback wraps inside this window — how the compose screen previews a trim.
    var loopRange: ClosedRange<Double>?

    /// Temp file we created and therefore own; a caller-supplied URL is left alone.
    private var ownedURL: URL?
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    /// Bumped by `teardown` so an in-flight `start` cannot attach a player after the overlay left.
    private var startID = 0

    /// Playhead as 0…1 for the scrubber.
    var progress: Double {
        guard duration > 0 else { return 0 }
        return min(1, max(0, currentTime / duration))
    }

    /// Writes the bytes to a temp file and starts playing. Safe to call more than once.
    func start(data: Data) async {
        guard player == nil, !failed else { return }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("shroud-play-\(UUID().uuidString).mp4")
        do {
            try data.write(to: url, options: .atomic)
        } catch {
            failed = true
            return
        }
        ownedURL = url
        await start(url: url)
    }

    /// Plays a file that already exists on disk (compose preview). The file is not deleted.
    func start(url: URL) async {
        guard player == nil, !failed else { return }
        startID += 1
        let id = startID

        // A clip the user deliberately tapped play on should be audible even with the ring
        // switch flipped — same as Telegram. Must not `setActive` on the main thread (iOS 27).
        try? await ChatAudioSession.shared.activate(.moviePlayback)
        guard id == startID, player == nil, !failed else { return }

        let asset = AVURLAsset(url: url)
        let item = AVPlayerItem(asset: asset)
        let av = AVPlayer(playerItem: item)
        av.actionAtItemEnd = .pause
        player = av

        if let loaded = try? await asset.load(.duration) {
            let seconds = CMTimeGetSeconds(loaded)
            duration = seconds.isFinite ? max(0, seconds) : 0
        }

        // Adding a periodic observer before the item has a timebase logs
        // "cannot add handler to 0 from 0" and drops the callback.
        let ready = await Self.waitUntilReady(item)
        guard id == startID, player === av else { return }
        guard ready else {
            failed = true
            return
        }

        timeObserver = av.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 1.0 / 30.0, preferredTimescale: 600),
            queue: .main
        ) { [weak self] time in
            MainActor.assumeIsolated {
                guard let self, !self.isScrubbing else { return }
                let seconds = CMTimeGetSeconds(time)
                guard seconds.isFinite else { return }
                if let loop = self.loopRange, seconds >= loop.upperBound - 0.03 {
                    self.seek(to: loop.lowerBound)
                    return
                }
                self.currentTime = max(0, seconds)
            }
        }

        endObserver = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.didPlayToEndTimeNotification,
            object: item,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.reachedEnd()
            }
        }

        isReady = true
        play()
    }

    /// Waits until `AVPlayerItem` has a timebase so periodic observers can attach.
    private static func waitUntilReady(_ item: AVPlayerItem) async -> Bool {
        if item.status == .readyToPlay { return true }
        if item.status == .failed { return false }
        return await withCheckedContinuation { continuation in
            final class Wait: @unchecked Sendable {
                let lock = NSLock()
                var resumed = false
                var observation: NSKeyValueObservation?
                var timeout: Task<Void, Never>?
                let continuation: CheckedContinuation<Bool, Never>
                init(_ continuation: CheckedContinuation<Bool, Never>) {
                    self.continuation = continuation
                }

                func finish(_ ok: Bool) {
                    lock.lock()
                    defer { lock.unlock() }
                    guard !resumed else { return }
                    resumed = true
                    observation?.invalidate()
                    observation = nil
                    timeout?.cancel()
                    continuation.resume(returning: ok)
                }
            }
            let wait = Wait(continuation)
            wait.observation = item.observe(\.status, options: [.initial, .new]) { item, _ in
                switch item.status {
                case .readyToPlay: wait.finish(true)
                case .failed: wait.finish(false)
                default: break
                }
            }
            wait.timeout = Task {
                try? await Task.sleep(for: .seconds(8))
                wait.finish(item.status == .readyToPlay)
            }
        }
    }

    func play() {
        guard let player else { return }
        // Replaying after the end has to rewind first, or play() is a no-op.
        if let loop = loopRange, currentTime < loop.lowerBound || currentTime >= loop.upperBound - 0.05 {
            seek(to: loop.lowerBound, precise: true)
        } else if duration > 0, currentTime >= duration - 0.05 {
            seek(to: 0, precise: true)
        }
        player.play()
        isPlaying = true
    }

    func pause() {
        player?.pause()
        isPlaying = false
    }

    func toggle() {
        isPlaying ? pause() : play()
    }

    /// Moves the playhead. Dragging uses a tolerant seek (cheap); the release is exact.
    func seek(to seconds: Double, precise: Bool = false) {
        guard let player, duration > 0 else { return }
        let clamped = min(max(0, seconds), duration)
        currentTime = clamped
        let time = CMTime(seconds: clamped, preferredTimescale: 600)
        if precise {
            player.seek(to: time, toleranceBefore: .zero, toleranceAfter: .zero)
        } else {
            player.seek(to: time)
        }
    }

    func teardown() {
        startID += 1
        if let timeObserver {
            player?.removeTimeObserver(timeObserver)
            self.timeObserver = nil
        }
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
            self.endObserver = nil
        }
        player?.pause()
        player = nil
        isPlaying = false
        isReady = false
        // Reset `failed` too: the compose screen reuses one player across clips, and one bad
        // file must not poison every clip after it.
        failed = false
        currentTime = 0
        duration = 0
        loopRange = nil
        if let ownedURL {
            try? FileManager.default.removeItem(at: ownedURL)
            self.ownedURL = nil
        }
    }

    private func reachedEnd() {
        isPlaying = false
        if duration > 0 { currentTime = duration }
    }

    /// `m:ss` for the transport labels.
    static func timeLabel(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "0:00" }
        let total = Int(seconds.rounded(.down))
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}
