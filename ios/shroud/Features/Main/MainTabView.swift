import SwiftUI

/// Post-auth shell — Chats / Contacts / Calls / Settings from `iOS-App.pen`.
///
/// Floating tab bar lives in a bottom `safeAreaInset` so list content clears it.
/// Pushed destinations (chat, settings detail) clear the path-driven hide flag so the
/// bar is fully removed — no residual overlay over the composer.
struct MainTabView: View {
    let router: AppRouter

    @State private var selection: MainTab = .chats
    @State private var chatsPath: [ChatRoute] = []
    @State private var contactsPath: [ChatRoute] = []
    @State private var settingsPath: [SettingsRoute] = []
    /// Drives insertion offset: higher index → enter from the right, lower → from the left.
    @State private var movesForward = true

    private let tabAnimation = Animation.spring(response: 0.38, dampingFraction: 0.9)

    /// Tab bar only on root list screens — never on conversation / profile / settings detail.
    private var showsTabBar: Bool {
        switch selection {
        case .chats:
            return chatsPath.isEmpty
        case .contacts:
            return contactsPath.isEmpty
        case .calls:
            return true
        case .settings:
            return settingsPath.isEmpty
        }
    }

    var body: some View {
        tabRoot(for: selection)
            .id(selection)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .transition(tabContentTransition)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if showsTabBar {
                    FloatingTabBar(selection: selectionBinding)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(tabAnimation, value: selection)
            .animation(.spring(response: 0.32, dampingFraction: 0.9), value: showsTabBar)
            // List tabs: ignore keyboard so the bar doesn't jump. Conversation needs avoidance.
            .modifier(KeyboardSafeAreaModifier(ignoreKeyboard: showsTabBar))
            .navigationBarHidden(true)
            // Still expose binding for conversation/profile backups (same source of truth paths).
            .environment(\.hideFloatingTabBar, hideBinding)
            .onChange(of: selection) { _, newValue in
                if newValue != .settings {
                    settingsPath = []
                }
            }
    }

    /// Back-compat for screens that still write the hide flag; maps to path state.
    private var hideBinding: Binding<Bool> {
        Binding(
            get: { !showsTabBar },
            set: { hidden in
                // Only allow "show" by clearing paths when explicitly requested.
                if !hidden {
                    switch selection {
                    case .chats: chatsPath = []
                    case .contacts: contactsPath = []
                    case .settings: settingsPath = []
                    case .calls: break
                    }
                }
            }
        )
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
            ChatsView(path: $chatsPath)
        case .contacts:
            ContactsView(path: $contactsPath)
        case .calls:
            CallsView()
        case .settings:
            SettingsView(router: router, navigationPath: $settingsPath)
        }
    }
}

/// Applies `.ignoresSafeArea(.keyboard)` only while the floating tab bar is visible.
private struct KeyboardSafeAreaModifier: ViewModifier {
    let ignoreKeyboard: Bool

    func body(content: Content) -> some View {
        if ignoreKeyboard {
            content.ignoresSafeArea(.keyboard)
        } else {
            content
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
