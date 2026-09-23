import Foundation

/// User-facing security toggles (non-secret; stored in UserDefaults).
enum SecurityPreferences {
    private static let lockOnBackgroundKey = "security.lockChatsOnBackground"
    /// Retired: user presence can no longer be turned off, and a vault that needs re-wrapping
    /// is spotted from the wrap key's own protection marker (`HistoryKeyVault`).
    private static let retiredKeys = ["security.requireUserPresence", "security.vaultNeedsRewrap"]
    private static let generatesLinkPreviewsKey = "privacy.generateLinkPreviews"

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

    /// When true (default), backgrounding wipes history key + decrypted threads from RAM.
    static var lockChatsOnBackground: Bool {
        get {
            if UserDefaults.standard.object(forKey: lockOnBackgroundKey) == nil {
                return true
            }
            return UserDefaults.standard.bool(forKey: lockOnBackgroundKey)
        }
        set {
            UserDefaults.standard.set(newValue, forKey: lockOnBackgroundKey)
        }
    }

    /// Drops preferences older builds wrote. Turning user presence off made the lock UI-only.
    static func removeRetiredKeys() {
        for key in retiredKeys {
            UserDefaults.standard.removeObject(forKey: key)
        }
    }
}
