import AVFoundation
import WebRTC

/// The audio session during calls. CallKit activates it; WebRTC's audio starts only then.
///
/// Human: With CallKit, iOS decides when a call's audio starts (after an answer on the lock
/// screen, for example). WebRTC runs in manual-audio mode and is switched on from CallKit's
/// `didActivate`. The simulator has no CallKit audio, so there WebRTC manages the session.
@MainActor
enum CallAudio {
    private static var usesManualAudio: Bool {
        #if targetEnvironment(simulator)
        false
        #else
        true
        #endif
    }

    /// Once at launch, before any call.
    static func setUp() {
        let session = RTCAudioSession.sharedInstance()
        session.useManualAudio = usesManualAudio
        session.isAudioEnabled = !usesManualAudio
    }

    /// Before the session activates. Voice calls play on the receiver, video calls on the
    /// speaker; Bluetooth headsets either way.
    static func configure(video: Bool) {
        let options: AVAudioSession.CategoryOptions = video
            ? [.allowBluetoothHFP, .defaultToSpeaker]
            : [.allowBluetoothHFP]
        let mode: AVAudioSession.Mode = video ? .videoChat : .voiceChat

        let config = RTCAudioSessionConfiguration.webRTC()
        config.category = AVAudioSession.Category.playAndRecord.rawValue
        config.mode = mode.rawValue
        config.categoryOptions = options
        RTCAudioSessionConfiguration.setWebRTC(config)

        let session = RTCAudioSession.sharedInstance()
        session.lockForConfiguration()
        defer { session.unlockForConfiguration() }
        try? session.setCategory(.playAndRecord, mode: mode, options: options)
    }

    /// CallKit's `didActivate`.
    static func didActivate(_ audioSession: AVAudioSession) {
        let session = RTCAudioSession.sharedInstance()
        session.audioSessionDidActivate(audioSession)
        session.isAudioEnabled = true
    }

    /// CallKit's `didDeactivate`.
    static func didDeactivate(_ audioSession: AVAudioSession) {
        let session = RTCAudioSession.sharedInstance()
        session.isAudioEnabled = false
        session.audioSessionDidDeactivate(audioSession)
    }

    /// The loudspeaker on or off (off: the receiver, or a connected headset).
    ///
    /// Human: A call placed as video plays on the speaker by default, so "off" first turns it
    /// into a voice-chat session; otherwise a video call switched to voice could never reach
    /// the earpiece.
    static func setSpeaker(_ on: Bool) {
        let session = RTCAudioSession.sharedInstance()
        session.lockForConfiguration()
        defer { session.unlockForConfiguration() }
        if !on, session.mode == AVAudioSession.Mode.videoChat.rawValue
            || session.categoryOptions.contains(.defaultToSpeaker)
        {
            let config = RTCAudioSessionConfiguration.webRTC()
            config.mode = AVAudioSession.Mode.voiceChat.rawValue
            config.categoryOptions = [.allowBluetoothHFP]
            RTCAudioSessionConfiguration.setWebRTC(config)
            try? session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetoothHFP])
        }
        try? session.overrideOutputAudioPort(on ? .speaker : .none)
    }

    /// The call plays on the phone's earpiece (not the speaker, not a headset or car).
    static var isOnReceiver: Bool {
        AVAudioSession.sharedInstance().currentRoute.outputs.contains { $0.portType == .builtInReceiver }
    }
}
