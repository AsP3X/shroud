import Foundation

/// User-facing security toggles (non-secret; stored in UserDefaults).
enum SecurityPreferences {
    private static let lockOnBackgroundKey = "security.lockChatsOnBackground"
    private static let requireUserPresenceKey = "security.requireUserPresence"
    private static let vaultNeedsRewrapKey = "security.vaultNeedsRewrap"

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

    /// Prefer biometry/passcode ACL on the history wrap key (default true).
    static var requireUserPresence: Bool {
        get {
            if UserDefaults.standard.object(forKey: requireUserPresenceKey) == nil {
                return true
            }
            return UserDefaults.standard.bool(forKey: requireUserPresenceKey)
        }
        set {
            UserDefaults.standard.set(newValue, forKey: requireUserPresenceKey)
            HistoryKeyVault.requiresUserPresence = newValue
            // Next successful unlock rotates the wrap key so the new ACL applies.
            vaultNeedsRewrap = true
        }
    }

    /// After changing presence preference, re-seal on next unlock.
    static var vaultNeedsRewrap: Bool {
        get { UserDefaults.standard.bool(forKey: vaultNeedsRewrapKey) }
        set { UserDefaults.standard.set(newValue, forKey: vaultNeedsRewrapKey) }
    }

    /// Apply persisted vault preference at process start.
    static func applyToVault() {
        HistoryKeyVault.requiresUserPresence = requireUserPresence
    }
}
