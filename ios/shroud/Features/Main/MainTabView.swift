import SwiftUI

/// Post-auth shell — Chats / Contacts / Calls / Settings from `iOS-App.pen`.
///
/// Liquid-glass floating tab bar plus a soft directional crossfade when switching tabs.
/// Settings can push full-screen destinations (e.g. Server); the tab bar hides while those are open.
struct MainTabView: View {
    let router: AppRouter

    @State private var selection: MainTab = .chats
    @State private var settingsPath: [SettingsRoute] = []
    /// Drives insertion offset: higher index → enter from the right, lower → from the left.
    @State private var movesForward = true

    private let tabAnimation = Animation.spring(response: 0.38, dampingFraction: 0.9)

    private var showsTabBar: Bool {
        if selection == .settings, !settingsPath.isEmpty {
            return false
        }
        return true
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            tabRoot(for: selection)
                .id(selection)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .transition(tabContentTransition)
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    Color.clear.frame(height: showsTabBar ? 72 : 0)
                }

            if showsTabBar {
                FloatingTabBar(selection: selectionBinding)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(tabAnimation, value: selection)
        .animation(.spring(response: 0.32, dampingFraction: 0.9), value: showsTabBar)
        .ignoresSafeArea(.keyboard)
        .navigationBarHidden(true)
        .onChange(of: selection) { _, newValue in
            // Leaving Settings pops any pushed server screen so state stays clean.
            if newValue != .settings {
                settingsPath = []
            }
        }
    }

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
            SettingsView(router: router, navigationPath: $settingsPath)
        }
    }
}

#Preview {
    MainTabView(router: AppRouter())
        .environment(SessionController())
        .environment(ServerConfigurationController())
        .environment(MessagingController())
        .environment(CryptoController())
}
