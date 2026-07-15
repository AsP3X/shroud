import SwiftUI

/// Post-auth shell — Chats / Contacts / Calls / Settings from `iOS-App.pen`.
///
/// Liquid-glass floating tab bar (`glassEffect`) plus a soft **directional crossfade**
/// when switching tabs (parallel destinations, not a navigation stack).
struct MainTabView: View {
    let router: AppRouter

    @State private var selection: MainTab = .chats
    /// Drives insertion offset: higher index → enter from the right, lower → from the left.
    @State private var movesForward = true

    /// Soft spring — short enough to feel snappy, damped enough to avoid bounce noise.
    private let tabAnimation = Animation.spring(response: 0.38, dampingFraction: 0.9)

    var body: some View {
        ZStack(alignment: .bottom) {
            tabRoot(for: selection)
                .id(selection)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .transition(tabContentTransition)
                // Clearance for the floating glass tab bar.
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    Color.clear.frame(height: 72)
                }

            FloatingTabBar(selection: selectionBinding)
        }
        .animation(tabAnimation, value: selection)
        .ignoresSafeArea(.keyboard)
        .navigationBarHidden(true)
    }

    /// Binding that records direction before applying the selection change.
    private var selectionBinding: Binding<MainTab> {
        Binding(
            get: { selection },
            set: { newValue in
                guard newValue != selection else { return }
                movesForward = newValue.rawValue > selection.rawValue
                selection = newValue
            }
        )
    }

    /// Opacity + micro scale + slight horizontal drift following tab order.
    /// Avoid full-width slides — those read as push/pop, not peer tabs.
    private var tabContentTransition: AnyTransition {
        let insertX: CGFloat = movesForward ? 14 : -14
        let removeX: CGFloat = movesForward ? -10 : 10
        return .asymmetric(
            insertion: .opacity
                .combined(with: .scale(scale: 0.985, anchor: .center))
                .combined(with: .offset(x: insertX)),
            removal: .opacity
                .combined(with: .scale(scale: 1.01, anchor: .center))
                .combined(with: .offset(x: removeX))
        )
    }

    @ViewBuilder
    private func tabRoot(for tab: MainTab) -> some View {
        switch tab {
        case .chats:
            ChatsView()
        case .contacts:
            ContactsView()
        case .calls:
            CallsView()
        case .settings:
            SettingsView(router: router)
        }
    }
}

#Preview {
    MainTabView(router: AppRouter())
        .environment(SessionController())
        .environment(ServerConfigurationController())
}
