import SwiftUI

/// Post-auth shell — Chats / Contacts / Calls / Settings.
///
/// Critical: do **not** change the root view tree (or apply parent `.animation`) when
/// `chatsPath` / `contactsPath` changes. That used to recreate `NavigationStack` mid-push
/// (tab bar hide + keyboard safe-area branch) and kill the slide transition — often looking
/// like “first tap only hides the bar, second tap opens the chat”.
struct MainTabView: View {
    let router: AppRouter

    @State private var selection: MainTab = .chats
    @State private var chatsPath: [ChatRoute] = []
    @State private var contactsPath: [ChatRoute] = []
    @State private var settingsPath: [SettingsRoute] = []
    @State private var movesForward = true

    // Same curve as before, now expressed as a design-system token (`Motion.standard`).
    private let tabAnimation = Motion.standard
    private let tabBarClearance: CGFloat = 88

    private var showsTabBar: Bool {
        switch selection {
        case .chats: return chatsPath.isEmpty
        case .contacts: return contactsPath.isEmpty
        case .calls: return true
        case .settings: return settingsPath.isEmpty
        }
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            tabRoot(for: selection)
                .id(selection)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .transition(tabContentTransition)
                // Stable — never tied to path, so push layout stays put.
                .safeAreaPadding(.bottom, tabBarClearance)
                // Always the same modifier shape; only the edges flag changes.
                .ignoresSafeArea(.keyboard, edges: showsTabBar ? .all : [])

            FloatingTabBar(selection: selectionBinding)
                .opacity(showsTabBar ? 1 : 0)
                .offset(y: showsTabBar ? 0 : 24)
                .allowsHitTesting(showsTabBar)
                .accessibilityHidden(!showsTabBar)
                // Animate **only** the bar, not the NavigationStack parent.
                .animation(Motion.scrim, value: showsTabBar)
                .zIndex(showsTabBar ? 1 : 0)
        }
        // Tab switches only — never animate off `showsTabBar` here (that cancelled pushes).
        .animation(tabAnimation, value: selection)
        .navigationBarHidden(true)
        .environment(\.hideFloatingTabBar, hideBinding)
        .onChange(of: selection) { _, newValue in
            if newValue != .settings {
                settingsPath = []
            }
            if newValue != .chats { chatsPath = [] }
            if newValue != .contacts { contactsPath = [] }
        }
    }

    private var hideBinding: Binding<Bool> {
        Binding(
            get: { !showsTabBar },
            set: { hidden in
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

#Preview {
    MainTabView(router: AppRouter())
        .environment(SessionController())
        .environment(ServerConfigurationController())
        .environment(MessagingController())
        .environment(CryptoController())
        .environment(CallController())
}
