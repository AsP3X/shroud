import AVFoundation

/// One place that talks to `AVAudioSession`, so video, voice notes and calls never
/// block the main thread.
///
/// Human: iOS 27 logs (and can hitch the UI) if `setActive` / `setCategory` run on the
/// main thread, especially while the session is already live. Category changes while
/// active are the SessionCore warning; `setActive` is the AVAudioSession one. The
/// async activate/deactivate APIs return immediately and finish off-thread.
/// Agent: An actor so overlapping video / voice / call activations cannot interleave
/// `setCategory` and `setActive`. Not MainActor — that was the hitch.
actor ChatAudioSession {
    static let shared = ChatAudioSession()

    struct Config: Equatable, Sendable {
        var category: AVAudioSession.Category
        var mode: AVAudioSession.Mode
        var options: AVAudioSession.CategoryOptions

        static let moviePlayback = Config(category: .playback, mode: .moviePlayback, options: [])
        static let spokenPlayback = Config(category: .playback, mode: .spokenAudio, options: [])
        /// Shared audio files: songs as well as podcasts, so the default mode.
        static let musicPlayback = Config(category: .playback, mode: .default, options: [])
        static let mixedPlayback = Config(category: .playback, mode: .default, options: [.mixWithOthers])
        static let voiceRecord = Config(category: .playAndRecord, mode: .default, options: [.defaultToSpeaker])
        static let voiceCall = Config(
            category: .playAndRecord,
            mode: .voiceChat,
            options: [.allowBluetoothHFP, .allowBluetoothA2DP, .defaultToSpeaker]
        )
    }

    func activate(_ config: Config) async throws {
        try await apply(config, wantActive: true)
    }

    /// CallKit has already activated the session; only the category/mode may still be wrong.
    func applyCategory(_ config: Config) {
        let session = AVAudioSession.sharedInstance()
        guard session.category != config.category
            || session.mode != config.mode
            || session.categoryOptions != config.options
        else { return }
        try? session.setCategory(config.category, mode: config.mode, options: config.options)
    }

    func deactivate() async {
        try? await apply(nil, wantActive: false)
    }

    private func apply(_ config: Config?, wantActive: Bool) async throws {
        let session = AVAudioSession.sharedInstance()
        let needsCategory: Bool = {
            guard let config else { return false }
            return session.category != config.category
                || session.mode != config.mode
                || session.categoryOptions != config.options
        }()

        if #available(iOS 27.0, *) {
            if needsCategory {
                await asyncDeactivate(session)
                if let config {
                    try session.setCategory(config.category, mode: config.mode, options: config.options)
                }
            }
            if wantActive {
                try await asyncActivate(session)
            } else if !needsCategory {
                await asyncDeactivate(session)
            }
        } else {
            if needsCategory {
                try? session.setActive(false, options: [.notifyOthersOnDeactivation])
                if let config {
                    try session.setCategory(config.category, mode: config.mode, options: config.options)
                }
            }
            if wantActive {
                try session.setActive(true)
            } else {
                try session.setActive(false, options: [.notifyOthersOnDeactivation])
            }
        }
    }

    @available(iOS 27.0, *)
    private func asyncActivate(_ session: AVAudioSession) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            session.activate(options: []) { activated, error in
                if let error {
                    cont.resume(throwing: error)
                } else if !activated {
                    cont.resume(throwing: ActivationError.notActivated)
                } else {
                    cont.resume()
                }
            }
        }
    }

    @available(iOS 27.0, *)
    private func asyncDeactivate(_ session: AVAudioSession) async {
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            session.deactivate(options: []) { _, _ in
                cont.resume()
            }
        }
    }

    private enum ActivationError: Error {
        case notActivated
    }
}
