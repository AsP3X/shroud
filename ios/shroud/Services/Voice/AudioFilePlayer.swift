import AVFoundation
import CryptoKit
import Foundation
import Observation
import UIKit

/// Single owner of shared-audio-file playback (`docs/file-sharing.md` §11.5).
///
/// Human: Songs, podcasts and recordings sent as files play in the chat like WhatsApp's and
/// Telegram's music: one at a time, with a scrubber on the bubble and the now-playing bar while
/// the bubble is scrolled away. The player never sees a decrypted file — `AVPlayer` reads the
/// stored SHRF1 blob through `SealedAudioResourceLoader`, a segment at a time. Starting an audio
/// file stops a voice note and the other way round; a call stops both. When a file ends it
/// rewinds and the open chat plays the next audio file below it (`onFinished`).
/// Agent: OWNS AVPlayer, its observers and the resource loader. READS a `Source` built by
/// `MessagingController.audioSource(for:)`, which has already verified the blob. Session state
/// (`verifiedIDs`, `unplayableIDs`, `rate`) is in memory only; `lock()` drops the verified set.
/// Pauses on going to the background and on an audio-session interruption.
@Observable
@MainActor
final class AudioFilePlayer {
    static let shared = AudioFilePlayer()

    /// Speeds the now-playing bar's chip cycles through.
    static let rates: [Float] = [1, 1.5, 2]
    /// The speed chip is offered for files this long or longer (10 minutes).
    static let speedChipMinimumMs = 10 * 60 * 1000

    /// Everything the player needs to open one file, already checked.
    struct Source: Sendable {
        let messageID: UUID
        let peerUserID: UUID
        /// The stored blob (`LocalFileStore`).
        let blob: URL
        let key: SymmetricKey
        let plaintextSize: Int64
        /// Lowercased extension, which tells AVFoundation the format.
        let ext: String
        /// `ti`, else the file name — what the bar shows.
        let title: String
        let artist: String?
        /// `d`, when the sender read one.
        let durationMs: Int?
    }

    /// The loaded file (playing, or paused part-way).
    struct Track: Equatable, Sendable {
        let messageID: UUID
        let peerUserID: UUID
        let title: String
        let artist: String?
        let durationMs: Int?
    }

    enum StartResult: Equatable {
        case started
        /// AVFoundation can't play this file (Ogg Vorbis, say); it shows as a plain file now.
        case unplayable
        /// The blob couldn't be read.
        case failed
        /// Something else was started (or everything stopped) while this one was opening.
        case superseded
    }

    private(set) var track: Track?
    private(set) var isPlaying = false
    /// Seconds into the active file.
    private(set) var currentTime: Double = 0
    /// Seconds; from the file once it is open, else from `d`.
    private(set) var duration: Double = 0
    /// Sticky for the session.
    private(set) var rate: Float = 1
    /// Files this device's player can't open; drawn as plain file bubbles for the session.
    private(set) var unplayableIDs: Set<UUID> = []
    /// Audio bubbles on screen right now; the now-playing bar shows while the active one isn't.
    private(set) var visibleBubbleIDs: Set<UUID> = []
    /// Bumped on every start, so a pending "play when the download finishes" can tell whether
    /// something else started meanwhile.
    private(set) var startCount = 0

    /// Called when a file played to its end, after it was rewound and unloaded. The open chat
    /// starts the next audio file below it from here.
    @ObservationIgnored var onFinished: ((Track) -> Void)?

    /// Blobs whose every tag checked this session (§11.5): checked once, before the first play.
    @ObservationIgnored private var verifiedIDs: Set<UUID> = []
    @ObservationIgnored private var player: AVPlayer?
    @ObservationIgnored private var loader: SealedAudioResourceLoader?
    @ObservationIgnored private var timeObserver: Any?
    @ObservationIgnored private var endObserver: NSObjectProtocol?
    @ObservationIgnored private var failObserver: NSObjectProtocol?
    @ObservationIgnored private var appObservers: [NSObjectProtocol] = []
    /// Bumped by every start and stop, so an opening that lost the race leaves no player behind.
    @ObservationIgnored private var generation = 0
    /// True while a finger owns the scrubber; periodic ticks don't move the playhead then.
    @ObservationIgnored var isScrubbing = false

    private init() {
        let center = NotificationCenter.default
        appObservers.append(center.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { _ in
            MainActor.assumeIsolated { AudioFilePlayer.shared.pause() }
        })
        appObservers.append(center.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil,
            queue: .main
        ) { note in
            let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            guard raw.flatMap(AVAudioSession.InterruptionType.init(rawValue:)) == .began else { return }
            MainActor.assumeIsolated { AudioFilePlayer.shared.pause() }
        })
    }

    // MARK: - Queries

    func isActive(_ id: UUID) -> Bool { track?.messageID == id }

    func isPlaying(_ id: UUID) -> Bool { track?.messageID == id && isPlaying }

    func isUnplayable(_ id: UUID) -> Bool { unplayableIDs.contains(id) }

    func isVerified(_ id: UUID) -> Bool { verifiedIDs.contains(id) }

    /// 0…1 through the active file; 0 for any other.
    func progress(for id: UUID) -> Double {
        guard isActive(id), duration > 0 else { return 0 }
        return min(1, max(0, currentTime / duration))
    }

    /// Whether the speed chip is offered for the active file.
    var offersSpeed: Bool {
        let ms = track?.durationMs ?? Int(duration * 1000)
        return ms >= Self.speedChipMinimumMs
    }

    /// `1×`, `1.5×`, `2×`.
    var rateLabel: String {
        Self.rateLabel(rate)
    }

    static func rateLabel(_ rate: Float) -> String {
        rate == rate.rounded() ? "\(Int(rate))\u{00D7}" : "\(rate)\u{00D7}"
    }

    // MARK: - Session bookkeeping

    func markVerified(_ id: UUID) {
        verifiedIDs.insert(id)
    }

    func markUnplayable(_ id: UUID) {
        unplayableIDs.insert(id)
    }

    func setBubbleVisible(_ id: UUID, _ visible: Bool) {
        if visible {
            if !visibleBubbleIDs.contains(id) { visibleBubbleIDs.insert(id) }
        } else if visibleBubbleIDs.contains(id) {
            visibleBubbleIDs.remove(id)
        }
    }

    /// A message's blob went (deleted, damaged, re-keyed): what this session knew of it goes too.
    func forget(_ ids: [UUID]) {
        for id in ids {
            stopIfActive(id)
            verifiedIDs.remove(id)
            unplayableIDs.remove(id)
            visibleBubbleIDs.remove(id)
        }
    }

    /// The chats locked: playback stops and every blob is checked again after the unlock.
    func lock() {
        stop()
        verifiedIDs.removeAll()
        visibleBubbleIDs.removeAll()
        onFinished = nil
    }

    /// Sign-out or a wipe: nothing of this session is kept.
    func reset() {
        lock()
        unplayableIDs.removeAll()
        rate = 1
    }

    // MARK: - Transport

    /// Opens `source` and plays it from the start, stopping whatever plays now.
    func play(_ source: Source) async -> StartResult {
        teardown()
        generation += 1
        startCount += 1
        let generation = generation
        VoicePlaybackCoordinator.shared.stop()

        let loader: SealedAudioResourceLoader
        do {
            loader = try SealedAudioResourceLoader(
                blob: source.blob,
                key: source.key,
                plaintextSize: source.plaintextSize,
                ext: source.ext
            )
        } catch {
            return .failed
        }
        let asset = AVURLAsset(url: SealedAudioResourceLoader.url(messageID: source.messageID, ext: source.ext))
        asset.resourceLoader.setDelegate(loader, queue: loader.queue)
        self.loader = loader
        track = Track(
            messageID: source.messageID,
            peerUserID: source.peerUserID,
            title: source.title,
            artist: source.artist,
            durationMs: source.durationMs
        )
        currentTime = 0
        duration = Double(source.durationMs ?? 0) / 1000

        try? await ChatAudioSession.shared.activate(.musicPlayback)
        guard generation == self.generation else { return .superseded }

        let playable = (try? await asset.load(.isPlayable)) ?? false
        guard generation == self.generation else { return .superseded }
        guard playable else { return giveUp(source.messageID) }
        if let loaded = try? await asset.load(.duration) {
            let seconds = CMTimeGetSeconds(loaded)
            if seconds.isFinite, seconds > 0 { duration = seconds }
        }
        guard generation == self.generation else { return .superseded }

        let item = AVPlayerItem(asset: asset)
        let av = AVPlayer(playerItem: item)
        av.automaticallyWaitsToMinimizeStalling = false
        av.actionAtItemEnd = .pause
        player = av
        let ready = await ChatVideoPlayer.waitUntilReady(item)
        guard generation == self.generation, player === av else { return .superseded }
        guard ready else { return giveUp(source.messageID) }

        observe(av, item: item)
        av.defaultRate = rate
        av.play()
        isPlaying = true
        return .started
    }

    /// Play/pause the active file.
    func toggle() {
        if isPlaying { pause() } else { resume() }
    }

    func pause() {
        guard player != nil else { return }
        player?.pause()
        isPlaying = false
    }

    func resume() {
        guard let player else { return }
        VoicePlaybackCoordinator.shared.stop()
        let generation = generation
        Task {
            try? await ChatAudioSession.shared.activate(.musicPlayback)
            guard generation == self.generation, self.player === player else { return }
            if duration > 0, currentTime >= duration - 0.05 {
                await player.seek(to: .zero, toleranceBefore: .zero, toleranceAfter: .zero)
            }
            player.defaultRate = rate
            player.play()
            isPlaying = true
        }
    }

    /// Moves the active file's playhead to `fraction` (0…1).
    func seek(to fraction: Double) {
        guard let player, duration > 0 else { return }
        let seconds = duration * min(1, max(0, fraction))
        currentTime = seconds
        player.seek(to: CMTime(seconds: seconds, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
    }

    /// 1× → 1.5× → 2×, live on the playing file; kept for the session.
    func cycleRate() {
        let index = Self.rates.firstIndex(of: rate) ?? 0
        rate = Self.rates[(index + 1) % Self.rates.count]
        player?.defaultRate = rate
        if isPlaying { player?.rate = rate }
    }

    func stop() {
        generation += 1
        teardown()
    }

    func stopIfActive(_ id: UUID) {
        guard isActive(id) else { return }
        stop()
    }

    // MARK: - Internals

    private func giveUp(_ id: UUID) -> StartResult {
        markUnplayable(id)
        teardown()
        return .unplayable
    }

    private func observe(_ av: AVPlayer, item: AVPlayerItem) {
        timeObserver = av.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 1.0 / 30.0, preferredTimescale: 600),
            queue: .main
        ) { time in
            MainActor.assumeIsolated {
                let player = AudioFilePlayer.shared
                guard player.player === av, !player.isScrubbing else { return }
                let seconds = CMTimeGetSeconds(time)
                guard seconds.isFinite else { return }
                player.currentTime = max(0, seconds)
            }
        }
        endObserver = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.didPlayToEndTimeNotification,
            object: item,
            queue: .main
        ) { _ in
            MainActor.assumeIsolated {
                let player = AudioFilePlayer.shared
                guard player.player === av else { return }
                player.reachedEnd()
            }
        }
        failObserver = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.failedToPlayToEndTimeNotification,
            object: item,
            queue: .main
        ) { _ in
            MainActor.assumeIsolated {
                let player = AudioFilePlayer.shared
                guard player.player === av else { return }
                player.stop()
            }
        }
    }

    /// The end rewinds the file and unloads it; the chat may start the next one.
    private func reachedEnd() {
        guard let finished = track else { return }
        player?.seek(to: .zero)
        stop()
        onFinished?(finished)
    }

    private func teardown() {
        if let timeObserver {
            player?.removeTimeObserver(timeObserver)
            self.timeObserver = nil
        }
        for observer in [endObserver, failObserver].compactMap({ $0 }) {
            NotificationCenter.default.removeObserver(observer)
        }
        endObserver = nil
        failObserver = nil
        player?.pause()
        player?.replaceCurrentItem(with: nil)
        player = nil
        loader = nil
        if track != nil { track = nil }
        if isPlaying { isPlaying = false }
        currentTime = 0
        duration = 0
    }
}
