import SwiftUI

// MARK: - Environment binding (reliable hide across NavigationStack)

private struct HideFloatingTabBarKey: EnvironmentKey {
    static let defaultValue: Binding<Bool> = .constant(false)
}

extension EnvironmentValues {
    /// When `true`, `MainTabView` hides the floating tab bar (conversation, profile, …).
    var hideFloatingTabBar: Binding<Bool> {
        get { self[HideFloatingTabBarKey.self] }
        set { self[HideFloatingTabBarKey.self] = newValue }
    }
}

// MARK: - Legacy observable (kept for any remaining call sites)

/// Coordinates floating tab bar visibility. Prefer `\.hideFloatingTabBar` binding.
@MainActor
@Observable
final class FloatingTabBarVisibility {
    var isHidden: Bool = false

    func setAbsoluteHidden(_ hidden: Bool) {
        isHidden = hidden
    }
}
