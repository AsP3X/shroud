import Foundation
import Observation

/// How this iPhone notifies (Settings → Notifications and Sounds).
///
/// Human: Per device, like the server keeps them: a laptop and a phone can want different
/// things. The fields the server acts on while the app is closed (whether to push at all, names,
/// reactions, requests, sound, badge) are mirrored to it (`serverPatch`); the in-app ones only
/// shape what the open app does. Stored in UserDefaults — preferences, not secrets; the logout
/// wipe clears them with the rest.
/// Agent: READS/WRITES UserDefaults `notifications.*`; observed by the settings screen.
@MainActor
@Observable
final class NotificationPreferences {
    static let shared = NotificationPreferences()

    /// Pushes to this iPhone at all (the server's `enabled`).
    var enabled: Bool { didSet { save(enabled, .enabled) } }
    /// Name the sender. Off, a notification only says that something arrived.
    var showSender: Bool { didSet { save(showSender, .showSender) } }
    /// Put the message text in the in-app banner. Notifications from the server never carry
    /// text — it has none.
    var showPreview: Bool { didSet { save(showPreview, .showPreview) } }
    var reactions: Bool { didSet { save(reactions, .reactions) } }
    var contactRequests: Bool { didSet { save(contactRequests, .contactRequests) } }
    var sound: NotificationSound { didSet { save(sound.rawValue, .sound) } }
    /// A banner at the top of the screen when a message arrives in another chat.
    var inAppBanners: Bool { didSet { save(inAppBanners, .inAppBanners) } }
    var inAppSounds: Bool { didSet { save(inAppSounds, .inAppSounds) } }
    var inAppVibrate: Bool { didSet { save(inAppVibrate, .inAppVibrate) } }
    /// Unread count on the app icon.
    var badge: Bool { didSet { save(badge, .badge) } }
    var badgeIncludesMuted: Bool { didSet { save(badgeIncludesMuted, .badgeIncludesMuted) } }

    private enum Key: String {
        case enabled = "notifications.enabled"
        case showSender = "notifications.showSender"
        case showPreview = "notifications.showPreview"
        case reactions = "notifications.reactions"
        case contactRequests = "notifications.contactRequests"
        case sound = "notifications.sound"
        case inAppBanners = "notifications.inAppBanners"
        case inAppSounds = "notifications.inAppSounds"
        case inAppVibrate = "notifications.inAppVibrate"
        case badge = "notifications.badge"
        case badgeIncludesMuted = "notifications.badgeIncludesMuted"
    }

    private let defaults: UserDefaults
    /// Off while `reset()` reloads: a fresh install stores nothing until something is changed.
    @ObservationIgnored private var persists = true

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        func flag(_ key: Key, _ fallback: Bool) -> Bool {
            defaults.object(forKey: key.rawValue) == nil ? fallback : defaults.bool(forKey: key.rawValue)
        }
        enabled = flag(.enabled, true)
        showSender = flag(.showSender, true)
        showPreview = flag(.showPreview, true)
        reactions = flag(.reactions, true)
        contactRequests = flag(.contactRequests, true)
        sound = defaults.string(forKey: Key.sound.rawValue).flatMap(NotificationSound.init(rawValue:)) ?? .standard
        inAppBanners = flag(.inAppBanners, true)
        inAppSounds = flag(.inAppSounds, true)
        inAppVibrate = flag(.inAppVibrate, true)
        badge = flag(.badge, true)
        badgeIncludesMuted = flag(.badgeIncludesMuted, false)
    }

    private func save(_ value: Any, _ key: Key) {
        guard persists else { return }
        defaults.set(value, forKey: key.rawValue)
    }

    /// What the server needs to decide this iPhone's pushes.
    var serverPatch: NotificationSettingsPatch {
        NotificationSettingsPatch(
            enabled: enabled,
            showSender: showSender,
            reactions: reactions,
            contactRequests: contactRequests,
            sound: sound.serverName,
            badge: badge,
            badgeIncludesMuted: badgeIncludesMuted
        )
    }

    /// Back to how a fresh install notifies, which stores nothing (so the logout wipe finds
    /// nothing left after it).
    func reset() {
        for key in [Key.enabled, .showSender, .showPreview, .reactions, .contactRequests, .sound,
                    .inAppBanners, .inAppSounds, .inAppVibrate, .badge, .badgeIncludesMuted] {
            defaults.removeObject(forKey: key.rawValue)
        }
        persists = false
        defer { persists = true }
        let fresh = NotificationPreferences(defaults: defaults)
        enabled = fresh.enabled
        showSender = fresh.showSender
        showPreview = fresh.showPreview
        reactions = fresh.reactions
        contactRequests = fresh.contactRequests
        sound = fresh.sound
        inAppBanners = fresh.inAppBanners
        inAppSounds = fresh.inAppSounds
        inAppVibrate = fresh.inAppVibrate
        badge = fresh.badge
        badgeIncludesMuted = fresh.badgeIncludesMuted
    }
}
