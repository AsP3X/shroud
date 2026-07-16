import SwiftUI

/// When true, `MainTabView` hides the floating tab bar (e.g. open conversation).
struct HidesTabBarPreferenceKey: PreferenceKey {
    static var defaultValue: Bool = false

    static func reduce(value: inout Bool, nextValue: () -> Bool) {
        value = value || nextValue()
    }
}

extension View {
    /// Marks this hierarchy as full-screen content that should cover the tab bar.
    func hidesFloatingTabBar(_ hidden: Bool = true) -> some View {
        preference(key: HidesTabBarPreferenceKey.self, value: hidden)
    }
}
