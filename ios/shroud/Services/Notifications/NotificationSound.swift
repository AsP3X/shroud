import AudioToolbox
import Foundation

/// The notification sounds: iOS's own, the tones `scripts/gen_notification_sounds.py` makes
/// (bundled as `Resources/Sounds/<id>.wav`, the same files the web client plays), or none.
///
/// Agent: `serverName` is what `PUT /notifications/settings` stores; the server sends APNs
/// `default`, `<id>.wav` or no sound.
enum NotificationSound: String, CaseIterable, Identifiable, Sendable {
    /// iOS's notification tone.
    case standard = "default"
    case note
    case chime
    case glass
    case pop
    case pulse
    case none

    var id: String { rawValue }

    var title: String {
        switch self {
        case .standard: "Default"
        case .note: "Note"
        case .chime: "Chime"
        case .glass: "Glass"
        case .pop: "Pop"
        case .pulse: "Pulse"
        case .none: "None"
        }
    }

    var serverName: String { rawValue }

    /// Bundled file for the Shroud tones; nil for iOS's own tone and for none.
    var fileURL: URL? {
        switch self {
        case .standard, .none: nil
        default: Bundle.main.url(forResource: rawValue, withExtension: "wav")
        }
    }

    /// Plays the sound once, the way a notification would (quiet in silent mode).
    func play() {
        switch self {
        case .none:
            return
        case .standard:
            // "Tri-tone", iOS's notification sound.
            AudioServicesPlaySystemSound(1007)
        default:
            guard let id = Self.systemSoundID(for: self) else { return }
            AudioServicesPlaySystemSound(id)
        }
    }

    /// System sounds are registered once per file and kept for the process.
    private static var registered: [NotificationSound: SystemSoundID] = [:]

    private static func systemSoundID(for sound: NotificationSound) -> SystemSoundID? {
        if let id = registered[sound] { return id }
        guard let url = sound.fileURL else { return nil }
        var id: SystemSoundID = 0
        guard AudioServicesCreateSystemSoundID(url as CFURL, &id) == kAudioServicesNoError else { return nil }
        registered[sound] = id
        return id
    }
}
