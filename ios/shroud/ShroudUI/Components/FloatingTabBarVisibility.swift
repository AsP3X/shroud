import SwiftUI

// MARK: - Environment binding (reliable hide across NavigationStack)

private struct HideFloatingTabBarKey: EnvironmentKey {
    static let defaultValue: Binding<Bool> = .constant(false)
}

private struct TabBarSearchActiveKey: EnvironmentKey {
    static let defaultValue = false
}

private struct TabBarClearanceKey: EnvironmentKey {
    static let defaultValue: CGFloat = 0
}

extension EnvironmentValues {
    /// When `true`, `MainTabView` hides the floating tab bar (conversation, profile, …).
    var hideFloatingTabBar: Binding<Bool> {
        get { self[HideFloatingTabBarKey.self] }
        set { self[HideFloatingTabBarKey.self] = newValue }
    }

    /// True while the tab bar's own search field is open — lists hide their header search
    /// so the query is only typed (and shown) in one place.
    var isTabBarSearchActive: Bool {
        get { self[TabBarSearchActiveKey.self] }
        set { self[TabBarSearchActiveKey.self] = newValue }
    }

    /// Bottom padding a root tab screen's scroll view needs so its content ends at the floating
    /// bar's top edge. Travels by environment: `MainTabView`'s own safe-area padding never
    /// reaches the content inside a tab's `NavigationStack`.
    var tabBarClearance: CGFloat {
        get { self[TabBarClearanceKey.self] }
        set { self[TabBarClearanceKey.self] = newValue }
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
