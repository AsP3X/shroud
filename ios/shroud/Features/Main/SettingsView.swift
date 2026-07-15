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
        // Stay visible while shrinking/sliding, then vanish near the end.
        max(0, 1 - pow(collapseProgress, 1.35) * 1.05)
    }

    /// Center Y of the avatar relative to the top of the sticky chrome (including nav).
    private var avatarCenterY: CGFloat {
        let rest = navRowHeight + heroTopPadding + avatarExpandedSize / 2
        // Exit well above the top edge so it leaves the view entirely.
        let gone = -avatarExpandedSize * 0.85
        return rest + (gone - rest) * collapseProgress
    }

    // Single name layer: interpolates from hero position → nav-bar title slot.
    private var nameCenterY: CGFloat {
        // Rest sits under the avatar; finish dead-center in the nav row (bar title slot).
        let rest = navRowHeight + heroTopPadding + avatarExpandedSize + avatarToNameGap + 16
        let bar = navRowHeight / 2
        return rest + (bar - rest) * collapseProgress
    }

    /// Large under the avatar, then eases down to compact bar-title size while sliding up.
    private var nameFontSize: CGFloat {
        let expanded: CGFloat = 26
        let collapsed: CGFloat = 17
        // Slight ease-in so it stays big early, then shrinks more as it enters the bar.
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
                        // Fixed spacer matching max sticky chrome so list meets the bar cleanly.
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
                    .background {
                        GeometryReader { proxy in
                            Color.clear.preference(
                                key: SettingsScrollOffsetKey.self,
                                value: -proxy.frame(in: .named("settingsScroll")).minY
                            )
                        }
                    }
                }
                .coordinateSpace(name: "settingsScroll")
                .onPreferenceChange(SettingsScrollOffsetKey.self) { value in
                    if abs(value - scrollOffsetY) > 0.4 {
                        scrollOffsetY = value
                    }
                }
                .scrollDismissesKeyboard(.interactively)

                stickyChrome(midX: midX)
            }
        }
        .background(Theme.backgroundGrouped)
        .navigationBarHidden(true)
    }

    // MARK: - Sticky chrome + collapsing hero

    /// Sticky stack height = nav + remaining hero band (list scrolls under this).
    private var stickyChromeHeight: CGFloat {
        navRowHeight + heroBandHeight
    }

    private func stickyChrome(midX: CGFloat) -> some View {
        ZStack(alignment: .top) {
            // Gradient only within the sticky band (list peeks through below).
            stickyGradientBackground
                .frame(height: stickyChromeHeight + 32)
                .frame(maxWidth: .infinity, alignment: .top)

            // Nav controls (QR / Edit). Name is a separate floating layer.
            stickyNavRow
                .zIndex(5)

            // Avatar: slides upward out of the view, shrinks, blurs, then vanishes.
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
            .allowsHitTesting(false)
            .zIndex(2)

            // One continuous name: from under the avatar → dead center of the nav bar.
            // Drawn above the scroll view so list content never covers it.
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
            // Keep layout width stable so the title doesn’t jump while the font shrinks.
            .frame(maxWidth: midX * 1.35)
            .position(x: midX, y: nameCenterY)
            .allowsHitTesting(false)
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
                .allowsHitTesting(false)
                .zIndex(3)
        }
        // Layout height shrinks with scroll; drawing may extend above (avatar slides out).
        .frame(height: stickyChromeHeight, alignment: .top)
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(displayName), \(handle)")
    }

    private var stickyNavRow: some View {
        HStack(spacing: 0) {
            HStack {
                Button {} label: {
                    Image(systemName: "qrcode")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Theme.textPrimary)
                        .frame(width: 36, height: 36)
                        .background(Theme.background.opacity(0.92))
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("QR code")
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Color.clear
                .frame(maxWidth: .infinity)
                .frame(height: 1)

            HStack {
                Spacer(minLength: 0)
                Button("Edit") {}
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(Theme.accent)
                    .padding(.horizontal, 16)
                    .frame(height: 36)
                    .background(Theme.background.opacity(0.92))
                    .clipShape(Capsule())
                    .buttonStyle(.plain)
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
        }
        .padding(.horizontal, 16)
        .frame(height: navRowHeight)
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
            profileActionRow(systemImage: "face.smiling.inverse", title: "Set Emoji Status")
            groupDivider(leading: 48)
            profileActionRow(systemImage: "paintpalette.fill", title: "Change Profile Color")
            groupDivider(leading: 48)
            profileActionRow(systemImage: "camera.fill", title: "Change Profile Photo")
        }
    }

    private func profileActionRow(systemImage: String, title: String) -> some View {
        Button {} label: {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                    .frame(width: 22)
                Text(title)
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.accent)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var myProfileCard: some View {
        Button {} label: {
            HStack(spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(Color(red: 1, green: 107 / 255, blue: 107 / 255))
                        .frame(width: 30, height: 30)
                    Image(systemName: "person.fill")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Color.white)
                }
                Text("My Profile")
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.textPrimary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Color(red: 199 / 255, green: 199 / 255, blue: 204 / 255))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
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

// MARK: - Scroll tracking

private struct SettingsScrollOffsetKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

#Preview {
    SettingsView(router: AppRouter())
        .environment(SessionController())
        .environment(ServerConfigurationController())
}
