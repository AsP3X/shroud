import Foundation

/// User-facing security toggles (non-secret; stored in UserDefaults).
enum SecurityPreferences {
    private static let lockOnBackgroundKey = "security.lockChatsOnBackground"
    /// Retired: user presence can no longer be turned off, and a vault that needs re-wrapping
    /// is spotted from the wrap key's own protection marker (`HistoryKeyVault`).
    private static let retiredKeys = ["security.requireUserPresence", "security.vaultNeedsRewrap"]
    private static let generatesLinkPreviewsKey = "privacy.generateLinkPreviews"
    private static let alwaysRelayCallsKey = "privacy.alwaysRelayCalls"

    /// When true, every call goes through the server's TURN relay from the start, so the other
    /// person only ever sees the relay's address. Off (default): direct paths first, the relay
    /// only after one fails.
    ///
    /// Human: Costs relay bandwidth and a little latency. Without a relay on the server a call
    /// is refused rather than sent direct — the switch exists to keep this phone's IP hidden.
    static var alwaysRelayCalls: Bool {
        get { UserDefaults.standard.bool(forKey: alwaysRelayCallsKey) }
        set { UserDefaults.standard.set(newValue, forKey: alwaysRelayCallsKey) }
    }

    /// When true (default), typing a link fetches its preview **from this device** and seals it
    /// into the message. Off: links are sent bare and no website is contacted while typing.
    ///
    /// Human: The website sees this phone's IP address when the preview is built (the same as
    /// opening the link). Recipients never contact it either way — the preview is sealed.
    static var generatesLinkPreviews: Bool {
        get {
            if UserDefaults.standard.object(forKey: generatesLinkPreviewsKey) == nil {
                return true
            }
            return UserDefaults.standard.bool(forKey: generatesLinkPreviewsKey)
        }
        set {
            UserDefaults.standard.set(newValue, forKey: generatesLinkPreviewsKey)
        }
    }

    private static let autoLockDelayKey = "security.autoLockDelay"
    private static let hidesDuringScreenCaptureKey = "privacy.hideDuringScreenCapture"
    private static let blocksThirdPartyKeyboardsKey = "privacy.blockThirdPartyKeyboards"

    /// How long after leaving the app the chats lock: the history key and decrypted threads
    /// leave RAM, and coming back needs Face ID, the passcode or the phrase. Default immediately.
    ///
    /// Human: Replaces the on/off "Lock chats in background" (on → immediately, off → never),
    /// which is read once and then removed.
    static var autoLockDelay: AutoLockDelay {
        get {
            let defaults = UserDefaults.standard
            if let raw = defaults.object(forKey: autoLockDelayKey) as? Int,
               let delay = AutoLockDelay(rawValue: raw)
            {
                return delay
            }
            if defaults.object(forKey: lockOnBackgroundKey) != nil {
                let migrated: AutoLockDelay = defaults.bool(forKey: lockOnBackgroundKey) ? .immediately : .never
                defaults.set(migrated.rawValue, forKey: autoLockDelayKey)
                defaults.removeObject(forKey: lockOnBackgroundKey)
                return migrated
            }
            return .immediately
        }
        set {
            UserDefaults.standard.set(newValue.rawValue, forKey: autoLockDelayKey)
            UserDefaults.standard.removeObject(forKey: lockOnBackgroundKey)
        }
    }

    /// When true (default), the chats are covered while the screen is recorded, mirrored or
    /// shared (AirPlay, QuickTime, Control Center's recorder).
    static var hidesDuringScreenCapture: Bool {
        get {
            if UserDefaults.standard.object(forKey: hidesDuringScreenCaptureKey) == nil { return true }
            return UserDefaults.standard.bool(forKey: hidesDuringScreenCaptureKey)
        }
        set { UserDefaults.standard.set(newValue, forKey: hidesDuringScreenCaptureKey) }
    }

    /// When true, only Apple's keyboards open in Shroud. Off (default): whatever the user
    /// installed, as in every other app.
    ///
    /// Human: A keyboard extension with Full Access can send everything typed to its developer.
    /// iOS may keep `AppDelegate`'s answer for the app's lifetime, so a change can need a relaunch.
    static var blocksThirdPartyKeyboards: Bool {
        get { UserDefaults.standard.bool(forKey: blocksThirdPartyKeyboardsKey) }
        set { UserDefaults.standard.set(newValue, forKey: blocksThirdPartyKeyboardsKey) }
    }

    /// Drops preferences older builds wrote. Turning user presence off made the lock UI-only.
    static func removeRetiredKeys() {
        for key in retiredKeys {
            UserDefaults.standard.removeObject(forKey: key)
        }
    }
}

/// Settings → Privacy and Security → Auto-lock.
enum AutoLockDelay: Int, CaseIterable, Identifiable, Sendable {
    case immediately = 0
    case oneMinute = 60
    case fiveMinutes = 300
    case fifteenMinutes = 900
    case never = -1

    var id: Int { rawValue }

    var label: String {
        switch self {
        case .immediately: "Immediately"
        case .oneMinute: "After 1 minute"
        case .fiveMinutes: "After 5 minutes"
        case .fifteenMinutes: "After 15 minutes"
        case .never: "Never"
        }
    }

    /// Whether chats left at `leftAt` must be locked by `now`.
    func isDue(leftAt: Date, now: Date) -> Bool {
        switch self {
        case .never: false
        case .immediately: true
        default: now.timeIntervalSince(leftAt) >= TimeInterval(rawValue)
        }
    }
}
