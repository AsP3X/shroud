import SwiftUI
import UIKit

/// Post-auth shell — Chats / Contacts / Calls / Settings.
///
/// Critical: do **not** change the root view tree (or apply parent `.animation`) when
/// `chatsPath` / `contactsPath` changes. That used to recreate `NavigationStack` mid-push
/// (tab bar hide + keyboard safe-area branch) and kill the slide transition — often looking
/// like “first tap only hides the bar, second tap opens the chat”.
struct MainTabView: View {
    let router: AppRouter

    @Environment(MessagingController.self) private var messaging
    @Environment(NotificationsController.self) private var notifications
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var selection: MainTab = .chats
    @State private var chatsPath: [ChatRoute] = []
    @State private var contactsPath: [ChatRoute] = []
    @State private var settingsPath: [SettingsRoute] = []
    @State private var movesForward = true
    /// The tab bar's own search field is open (its magnifier was tapped).
    @State private var isSearching = false
    /// What Chats / Contacts filter by — shared by the header field and the bar's field.
    @State private var searchQuery = ""
    @FocusState private var searchFocused: Bool
    /// How much of the screen's bottom the keyboard covers (0 when it's down).
    @State private var keyboardHeight: CGFloat = 0
    /// The keyboard came up for the bar's own field; stays set until it is fully down, so
    /// closing search rides the keyboard down instead of blinking the bar out and back.
    @State private var keyboardServesSearch = false
    /// Home-indicator inset, measured with the keyboard ignored. Seeded from the window: the
    /// geometry reading lands after the first layout, and starting at 0 dropped the bar 12 pt
    /// on the shell's first frame (visible as a jump at the end of the unlock reveal).
    @State private var homeIndicatorInset: CGFloat = Self.windowBottomInset()

    // Same curve as before, now expressed as a design-system token (`Motion.standard`).
    private let tabAnimation = Motion.standard

    // Bar placement, after Telegram: 20pt off the screen's bottom and sides on phones with a
    // home indicator (8pt without one), 8pt above the keyboard with 12pt sides while searching.
    private let barEdgeGap: CGFloat = 20
    private let barCompactGap: CGFloat = 8
    private let barKeyboardSideInset: CGFloat = 12
    private let barMaxWidth: CGFloat = 500

    private var showsTabBar: Bool {
        switch selection {
        case .chats: return chatsPath.isEmpty
        case .contacts: return contactsPath.isEmpty
        case .calls: return true
        case .settings: return settingsPath.isEmpty
        }
    }

    /// Search rides the keyboard; otherwise the bar stays down and hides under it
    /// (a focused header search field shouldn't have the tabs floating over its results).
    private var barFollowsKeyboard: Bool { isSearching || keyboardServesSearch }

    private var keyboardVisible: Bool { keyboardHeight > 0 }

    private var barVisible: Bool {
        showsTabBar && (barFollowsKeyboard || !keyboardVisible)
    }

    private var barAboveKeyboard: Bool { barFollowsKeyboard && keyboardVisible }

    private var barBottomGap: CGFloat {
        if barAboveKeyboard { return barCompactGap }
        return homeIndicatorInset > 0 ? barEdgeGap : barCompactGap
    }

    private var barSideInset: CGFloat {
        barAboveKeyboard ? barKeyboardSideInset : barEdgeGap
    }

    /// Padding on top of the home-indicator inset that ends list content at the bar's top edge.
    /// Zero when the bar is hidden (pushed chat, or header search — the keyboard inset
    /// takes over). Tab roots ignore the keyboard only while this is non-zero, so an open
    /// tab-bar search still clears the keyboard under the field.
    private var tabBarClearance: CGFloat {
        guard barVisible else { return 0 }
        let barHeight = isSearching ? FloatingTabBar.searchHeight : FloatingTabBar.height
        let barBase = barAboveKeyboard ? keyboardHeight : 0
        return max(0, barBase + barBottomGap + barHeight - homeIndicatorInset)
    }

    /// Muted chats count only when the badge setting says so, as on the app icon.
    private var chatsUnreadCount: Int {
        messaging.unreadTotal(includeMuted: notifications.preferences.badgeIncludesMuted)
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            tabRoot(for: selection)
                .id(selection)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                // Reduce Motion: a plain cross-fade, no slide or scale.
                .transition(reduceMotion ? AnyTransition.opacity : tabContentTransition)
                // Always the same modifier shape; only the edges flag changes.
                // Ignore the keyboard while the bar is showing (clearance covers it).
                // Header search hides the bar — let the list sit above the keyboard.
                .ignoresSafeArea(.keyboard, edges: barVisible ? .all : [])

            FloatingTabBar(
                selection: selectionBinding,
                isSearching: $isSearching,
                query: $searchQuery,
                searchFocus: $searchFocused,
                badges: [.chats: chatsUnreadCount]
            )
            .frame(maxWidth: barMaxWidth)
            .padding(.horizontal, barSideInset)
            .padding(.bottom, barBottomGap)
            // Measured from the screen edge, not the home-indicator inset.
            .ignoresSafeArea(barFollowsKeyboard ? .container : .all, edges: .bottom)
            .opacity(barVisible ? 1 : 0)
            .offset(y: barVisible ? 0 : 24)
            .allowsHitTesting(barVisible)
            .accessibilityHidden(!barVisible)
            // Animate **only** the bar, not the NavigationStack parent.
            .animation(Motion.scrim, value: barVisible)
            .zIndex(barVisible ? 1 : 0)
        }
        .background {
            Color.clear
                .ignoresSafeArea(.keyboard)
                .onGeometryChange(for: CGFloat.self) { proxy in
                    proxy.safeAreaInsets.bottom
                } action: { inset in
                    homeIndicatorInset = inset
                }
        }
        // Tab switches only — never animate off `showsTabBar` here (that cancelled pushes).
        // This replaces the bar's own animation for the whole subtree, so it has to respect
        // Reduce Motion itself, or the lens and the content would still spring.
        .animation(Motion.respecting(reduceMotion, tabAnimation), value: selection)
        .navigationBarHidden(true)
        .environment(\.hideFloatingTabBar, hideBinding)
        .environment(\.isTabBarSearchActive, isSearching)
        // Never tied to path, so push layout stays put.
        .environment(\.tabBarClearance, tabBarClearance)
        .onChange(of: selection) { _, newValue in
            if newValue != .settings {
                settingsPath = []
            }
            if newValue != .chats { chatsPath = [] }
            if newValue != .contacts { contactsPath = [] }
            // Each tab opens unfiltered, as when every list owned its own query.
            searchQuery = ""
            // Search only filters Chats / Contacts; don't leave the field up on Calls.
            if !newValue.isSearchable {
                isSearching = false
            }
        }
        .onChange(of: isSearching) { _, searching in
            if searching {
                if !selection.isSearchable { select(.chats) }
                searchFocused = true
            } else {
                // The field keeps focus through its exit transition unless released here.
                searchFocused = false
                searchQuery = ""
            }
        }
        .onChange(of: showsTabBar) { _, shows in
            // Opening a result keeps the search, but its field must not hold the keyboard.
            if !shows { searchFocused = false }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { note in
            updateKeyboardHeight(from: note)
        }
        // A notification (or in-app banner) tap: open what it is about — also one tapped
        // before the unlock.
        .onAppear { openPendingNotification() }
        .onChange(of: notifications.pendingOpen) { _, _ in openPendingNotification() }
        .onChange(of: messaging.hasLoadedServerChats) { _, _ in openPendingNotification() }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { note in
            updateKeyboardHeight(from: note, hiding: true)
        }
    }

    private func openPendingNotification() {
        guard let open = notifications.pendingOpen else { return }
        switch open.kind {
        case .contactRequest:
            notifications.pendingOpen = nil
            contactsPath = []
            select(.contacts)
        case .test:
            notifications.pendingOpen = nil
        default:
            guard let peerID = open.peerUserID else {
                notifications.pendingOpen = nil
                select(.chats)
                return
            }
            let known = messaging.conversations.first(where: { $0.peer.id == peerID })?.peer.username
                ?? messaging.contacts.first(where: { $0.userId == peerID })?.username
            // The chat's header shows the name: wait for the server's first list (the cached one
            // may predate the chat) rather than guess it.
            if known == nil, !messaging.hasLoadedServerChats { return }
            notifications.pendingOpen = nil
            guard let username = known ?? open.username else {
                // Not a chat of ours (any more): the list it would be in is open anyway.
                select(.chats)
                return
            }
            let route = ChatRoute.conversation(peerID: peerID, username: username)
            if chatsPath.last != route { chatsPath = [route] }
            select(.chats)
        }
    }

    private static func windowBottomInset() -> CGFloat {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)?
            .safeAreaInsets.bottom ?? 0
    }

    private func updateKeyboardHeight(from note: Notification, hiding: Bool = false) {
        var overlap: CGFloat = 0
        if !hiding, let frame = note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect {
            // Prefer the active window scene's screen (UIScreen.main is deprecated in iOS 26).
            let screenHeight = UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .first(where: { $0.activationState == .foregroundActive })?
                .screen.bounds.height
                ?? frame.maxY
            overlap = max(0, screenHeight - frame.minY)
        }
        guard abs(overlap - keyboardHeight) > 0.5 else { return }
        let duration = (note.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double) ?? 0.25
        withAnimation(.easeOut(duration: duration)) {
            keyboardHeight = overlap
            keyboardServesSearch = overlap > 0 && (isSearching || keyboardServesSearch)
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
            set: { select($0) }
        )
    }

    private func select(_ tab: MainTab) {
        guard tab != selection else { return }
        movesForward = tab.rawValue > selection.rawValue
        selection = tab
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
            ChatsView(path: $chatsPath, searchText: $searchQuery)
        case .contacts:
            ContactsView(path: $contactsPath, searchText: $searchQuery)
        case .calls:
            CallsView()
        case .settings:
            SettingsView(router: router, navigationPath: $settingsPath, onOpenCalls: { select(.calls) })
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
        .environment(NotificationsController.shared)
}
