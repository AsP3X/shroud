import SwiftUI

/// In-settings destinations pushed over the Settings tab.
enum SettingsRoute: Hashable {
    case server
}

/// Settings tab — maps to `Settings` in `iOS-App.pen`.
/// Telegram-style profile hero: avatar shrinks/blurs on scroll; name slides into the sticky bar title.
struct SettingsView: View {
    let router: AppRouter
    /// When non-empty, the floating tab bar should hide (detail is covering Settings).
    @Binding var navigationPath: [SettingsRoute]

    @Environment(SessionController.self) private var sessionController
    @Environment(ServerConfigurationController.self) private var serverConfig

    @State private var scrollOffsetY: CGFloat = 0

    // MARK: - Collapse metrics

    private let navRowHeight: CGFloat = 44
    private let avatarExpandedSize: CGFloat = 88
    private let heroTopPadding: CGFloat = 10
    private let avatarToNameGap: CGFloat = 14
    private let nameBlockHeight: CGFloat = 52
    private let heroBottomPadding: CGFloat = 10

    /// Layout height of the expanded hero under the nav (also collapse travel).
    private var heroExpandedHeight: CGFloat {
        heroTopPadding + avatarExpandedSize + avatarToNameGap + nameBlockHeight + heroBottomPadding
    }

    /// 0 at rest → 1 when the hero has fully collapsed.
    private var collapseProgress: CGFloat {
        min(1, max(0, scrollOffsetY / max(heroExpandedHeight, 1)))
    }

    /// Sticky band under the nav shrinks so list content meets the bar (name stays in overlay).
    private var heroBandHeight: CGFloat {
        max(0, heroExpandedHeight - scrollOffsetY)
    }

    // Avatar: shrink + blur + slide upward out of the top of the screen.
    private var avatarScale: CGFloat {
        1 - 0.72 * collapseProgress
    }

    private var avatarBlur: CGFloat {
        22 * collapseProgress
    }

    private var avatarOpacity: CGFloat {
        max(0, 1 - pow(collapseProgress, 1.35) * 1.05)
    }

    /// Center Y of the avatar relative to the top of the sticky chrome (including nav).
    private var avatarCenterY: CGFloat {
        let rest = navRowHeight + heroTopPadding + avatarExpandedSize / 2
        let gone = -avatarExpandedSize * 0.85
        return rest + (gone - rest) * collapseProgress
    }

    // Single name layer: interpolates from hero position → nav-bar title slot.
    private var nameCenterY: CGFloat {
        let rest = navRowHeight + heroTopPadding + avatarExpandedSize + avatarToNameGap + 16
        let bar = navRowHeight / 2
        return rest + (bar - rest) * collapseProgress
    }

    private var nameFontSize: CGFloat {
        let expanded: CGFloat = 26
        let collapsed: CGFloat = 17
        let t = collapseProgress * collapseProgress
        return expanded - (expanded - collapsed) * t
    }

    private var nameWeight: Font.Weight {
        collapseProgress > 0.55 ? .semibold : .bold
    }

    private var handleOpacity: CGFloat {
        max(0, 1 - collapseProgress * 1.8)
    }

    private var badgeOpacity: CGFloat {
        max(0, 1 - collapseProgress * 2.2)
    }

    private var materialProgress: CGFloat {
        min(1, max(0, collapseProgress * 1.05))
    }

    init(router: AppRouter, navigationPath: Binding<[SettingsRoute]> = .constant([])) {
        self.router = router
        _navigationPath = navigationPath
    }

    private var displayName: String {
        if let username = sessionController.username, !username.isEmpty {
            return username
                .replacingOccurrences(of: "_", with: " ")
                .split(separator: " ")
                .map { $0.prefix(1).uppercased() + $0.dropFirst().lowercased() }
                .joined(separator: " ")
        }
        return "Shroud User"
    }

    private var handle: String {
        if let username = sessionController.username, !username.isEmpty {
            return "@\(username)"
        }
        return "@user"
    }

    private var initials: String {
        AvatarView.initials(for: displayName)
    }

    private var serverSubtitle: String {
        switch serverConfig.configuration.mode {
        case .official:
            return "Official · api.shroud.app"
        case .selfHosted:
            return serverConfig.configuration.selfHostedPreviewString
        }
    }

    var body: some View {
        NavigationStack(path: $navigationPath) {
            settingsRoot
                .navigationBarTitleDisplayMode(.inline)
                .toolbar(.hidden, for: .navigationBar)
                .navigationDestination(for: SettingsRoute.self) { route in
                    switch route {
                    case .server:
                        ServerSettingsView(router: router)
                    }
                }
        }
    }

    // MARK: - Root

    private var settingsRoot: some View {
        GeometryReader { geo in
            let midX = geo.size.width / 2

            ZStack(alignment: .top) {
                ScrollView {
                    VStack(spacing: 0) {
                        // Spacer under sticky chrome so list starts below the expanded hero.
                        Color.clear
                            .frame(height: navRowHeight + heroExpandedHeight)
                            .accessibilityHidden(true)

                        VStack(spacing: 14) {
                            profileActionsCard
                            myProfileCard
                            primaryGroup
                            secondaryGroup
                            logOutGroup
                            Color.clear.frame(height: 16)
                        }
                        .padding(.horizontal, 16)
                        .padding(.bottom, 8)
                    }
                }
                .scrollIndicators(.hidden)
                // iOS 18+: reliable content offset (preference/coordinateSpace broke under NavigationStack).
                .onScrollGeometryChange(for: CGFloat.self) { geometry in
                    // contentOffset.y grows as the user scrolls down the list.
                    max(0, geometry.contentOffset.y)
                } action: { _, newOffset in
                    // Drive collapse every frame without inheriting tab-switch animations.
                    guard abs(newOffset - scrollOffsetY) > 0.25 else { return }
                    var transaction = Transaction()
                    transaction.disablesAnimations = true
                    withTransaction(transaction) {
                        scrollOffsetY = newOffset
                    }
                }

                stickyChrome(midX: midX)
                    // Keep hero drawn above list; allow avatar to slide past the top edge.
                    .allowsHitTesting(false)
            }
        }
        .background(Theme.backgroundGrouped)
    }

    // MARK: - Sticky chrome + collapsing hero

    private var stickyChromeHeight: CGFloat {
        navRowHeight + heroBandHeight
    }

    private func stickyChrome(midX: CGFloat) -> some View {
        ZStack(alignment: .top) {
            stickyGradientBackground
                .frame(height: stickyChromeHeight + 32)
                .frame(maxWidth: .infinity, alignment: .top)

            // Invisible nav-height band keeps layout metrics stable.
            Color.clear
                .frame(height: navRowHeight)
                .frame(maxWidth: .infinity)
                .zIndex(5)

            AvatarView(
                initials: initials,
                size: avatarExpandedSize,
                gradient: Theme.brandGradient,
                fontSize: 32
            )
            .scaleEffect(avatarScale)
            .blur(radius: avatarBlur)
            .opacity(Double(avatarOpacity))
            .frame(width: avatarExpandedSize, height: avatarExpandedSize)
            .position(x: midX, y: avatarCenterY)
            .zIndex(2)

            HStack(spacing: 6) {
                Text(displayName)
                    .font(.system(size: nameFontSize, weight: nameWeight))
                    .foregroundStyle(Theme.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Image(systemName: "checkmark.seal.fill")
                    .font(.system(size: nameFontSize * 0.78, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                    .opacity(Double(badgeOpacity))
            }
            .frame(maxWidth: midX * 1.35)
            .position(x: midX, y: nameCenterY)
            .accessibilityAddTraits(.isHeader)
            .zIndex(6)

            Text(handle)
                .font(.system(size: 15))
                .foregroundStyle(Theme.textSecondary)
                .opacity(Double(handleOpacity))
                .position(
                    x: midX,
                    y: nameCenterY + 22 * (1 - collapseProgress)
                )
                .zIndex(3)
        }
        .frame(height: stickyChromeHeight, alignment: .top)
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(displayName), \(handle)")
    }

    private var stickyGradientBackground: some View {
        ZStack(alignment: .top) {
            Rectangle()
                .fill(.ultraThinMaterial)
                .mask(
                    LinearGradient(
                        stops: [
                            .init(color: .white.opacity(0.95), location: 0),
                            .init(color: .white.opacity(0.55), location: 0.5),
                            .init(color: .white.opacity(0.1), location: 0.85),
                            .init(color: .clear, location: 1),
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .opacity(0.7 + 0.25 * Double(materialProgress))

            LinearGradient(
                stops: [
                    .init(
                        color: Theme.backgroundGrouped.opacity(0.92 + 0.06 * Double(materialProgress)),
                        location: 0
                    ),
                    .init(
                        color: Theme.backgroundGrouped.opacity(0.55 + 0.2 * Double(materialProgress)),
                        location: 0.55
                    ),
                    .init(color: Theme.backgroundGrouped.opacity(0), location: 1),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
        }
        .padding(.bottom, 24)
        .ignoresSafeArea(edges: .top)
    }

    // MARK: - Cards

    private var profileActionsCard: some View {
        settingsCard {
            HStack(spacing: 12) {
                Image(systemName: "person.text.rectangle")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                    .frame(width: 22)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Profile personalization")
                        .font(.system(size: 16))
                        .foregroundStyle(Theme.textPrimary)
                    Text("Emoji status, colors, and photos come later.")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textSecondary)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
        }
    }

    private var myProfileCard: some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(Color(red: 1, green: 107 / 255, blue: 107 / 255))
                    .frame(width: 30, height: 30)
                Image(systemName: "person.fill")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Color.white)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text("Signed in as \(handle)")
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.textPrimary)
                if let userID = sessionController.userID {
                    Text(userID.uuidString.lowercased())
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(Theme.textSecondary)
                        .textSelection(.enabled)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private var primaryGroup: some View {
        settingsCard {
            SettingsRowView(
                title: "Saved Messages",
                systemImage: "bookmark.fill",
                iconBackground: Color(red: 46 / 255, green: 143 / 255, blue: 224 / 255)
            )
            groupDivider()
            SettingsRowView(
                title: "Recent Calls",
                systemImage: "phone.fill",
                iconBackground: Color(red: 47 / 255, green: 168 / 255, blue: 91 / 255)
            )
            groupDivider()
            SettingsRowView(
                title: "Devices",
                systemImage: "laptopcomputer.and.iphone",
                iconBackground: Color(red: 247 / 255, green: 107 / 255, blue: 28 / 255)
            )
            groupDivider()
            SettingsRowView(
                title: "Chat Folders",
                systemImage: "folder.fill",
                iconBackground: Color(red: 74 / 255, green: 199 / 255, blue: 250 / 255)
            )
        }
    }

    private var secondaryGroup: some View {
        settingsCard {
            SettingsRowView(
                title: "Notifications and Sounds",
                systemImage: "bell.fill",
                iconBackground: Color(red: 230 / 255, green: 74 / 255, blue: 114 / 255)
            )
            groupDivider()
            SettingsRowView(
                title: "Privacy and Security",
                systemImage: "lock.fill",
                iconBackground: Theme.textSecondary
            )
            groupDivider()
            SettingsRowView(
                title: "Data and Storage",
                systemImage: "externaldrive.fill",
                iconBackground: Color(red: 47 / 255, green: 168 / 255, blue: 91 / 255)
            )
            groupDivider()
            SettingsRowView(
                title: "Appearance",
                systemImage: "paintpalette.fill",
                iconBackground: Theme.accent
            )
            groupDivider()
            SettingsRowView(
                title: "Language",
                systemImage: "globe",
                iconBackground: Color(red: 155 / 255, green: 74 / 255, blue: 230 / 255)
            )
            groupDivider()
            Button {
                Haptics.impact(.light)
                navigationPath.append(.server)
            } label: {
                HStack(spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 8, style: .continuous)
                            .fill(Theme.accent)
                            .frame(width: 30, height: 30)
                        Image(systemName: "server.rack")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(Color.white)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Server")
                            .font(.system(size: 16))
                            .foregroundStyle(Theme.textPrimary)
                        Text(serverSubtitle)
                            .font(.system(size: 13))
                            .foregroundStyle(Theme.textSecondary)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Color(red: 199 / 255, green: 199 / 255, blue: 204 / 255))
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Server, \(serverSubtitle)")
        }
    }

    private var logOutGroup: some View {
        Button {
            router.logOut()
        } label: {
            Text("Log Out")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Theme.danger)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(Theme.background)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    private func groupDivider(leading: CGFloat = 54) -> some View {
        Rectangle()
            .fill(Theme.separator)
            .frame(height: 1)
            .padding(.leading, leading)
    }

    private func settingsCard<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        VStack(spacing: 0) {
            content()
        }
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

#Preview {
    SettingsView(router: AppRouter())
        .environment(SessionController())
        .environment(ServerConfigurationController())
}
