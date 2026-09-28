import Foundation
import Testing
@testable import shroud

/// Auto-lock after leaving the app, and the move from the old on/off switch.
@MainActor
@Suite(.serialized)
struct AutoLockDelayTests {
    private let oldKey = "security.lockChatsOnBackground"
    private let newKey = "security.autoLockDelay"

    @Test func dueOnceTheDelayHasPassed() {
        let left = Date(timeIntervalSince1970: 1_000_000)
        #expect(AutoLockDelay.immediately.isDue(leftAt: left, now: left))
        #expect(!AutoLockDelay.oneMinute.isDue(leftAt: left, now: left.addingTimeInterval(59)))
        #expect(AutoLockDelay.oneMinute.isDue(leftAt: left, now: left.addingTimeInterval(60)))
        #expect(!AutoLockDelay.fifteenMinutes.isDue(leftAt: left, now: left.addingTimeInterval(14 * 60)))
        #expect(!AutoLockDelay.never.isDue(leftAt: left, now: left.addingTimeInterval(86_400)))
    }

    @Test(arguments: [(true, AutoLockDelay.immediately), (false, AutoLockDelay.never)])
    func theOldSwitchCarriesOver(_ wasOn: Bool, _ expected: AutoLockDelay) {
        withDefaults {
            UserDefaults.standard.set(wasOn, forKey: oldKey)
            #expect(SecurityPreferences.autoLockDelay == expected)
            #expect(UserDefaults.standard.object(forKey: oldKey) == nil, "the old key is removed")
            #expect(UserDefaults.standard.integer(forKey: newKey) == expected.rawValue)
        }
    }

    @Test func defaultsToImmediately() {
        withDefaults {
            #expect(SecurityPreferences.autoLockDelay == .immediately)
            SecurityPreferences.autoLockDelay = .fiveMinutes
            #expect(SecurityPreferences.autoLockDelay == .fiveMinutes)
        }
    }

    /// Runs `body` with neither key set, then puts back what was there.
    private func withDefaults(_ body: () -> Void) {
        let defaults = UserDefaults.standard
        let saved = (defaults.object(forKey: oldKey), defaults.object(forKey: newKey))
        defaults.removeObject(forKey: oldKey)
        defaults.removeObject(forKey: newKey)
        body()
        defaults.removeObject(forKey: oldKey)
        defaults.removeObject(forKey: newKey)
        if let old = saved.0 { defaults.set(old, forKey: oldKey) }
        if let new = saved.1 { defaults.set(new, forKey: newKey) }
    }
}
