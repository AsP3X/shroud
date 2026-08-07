import SwiftUI

/// In-settings destinations pushed over the Settings tab.
enum SettingsRoute: Hashable {
    case server
    case transcription
    case privacySecurity
}

/// Settings tab — Telegram-style profile hero for the **title-only** sticky bar
/// (no leading/trailing nav actions). Avatar exits; name settles as the compact bar title.
struct SettingsView: View {
    let router: AppRouter
    /// When non-empty, the floating tab bar should hide (detail is covering Settings).
    @Binding var navigationPath: [SettingsRoute]

    @Environment(SessionController.self) private var sessionController
    @Environment(ServerConfigurationController.self) private var serverConfig

    @State private var scrollOffsetY: CGFloat = 0
    @State private var showLogOutConfirm = false

    // MARK: - Layout metrics (title-only compact bar)

    /// Collapsed sticky bar height (centered title only — no side buttons).
    private let compactBarHeight: CGFloat = 44
    private let avatarExpandedSize: CGFloat = 88
    private let heroTopPadding: CGFloat = 12
    private let avatarToNameGap: CGFloat = 12
    private let nameExpandedLine: CGFloat = 30
    private let handleLine: CGFloat = 20
    private let handleGap: CGFloat = 4
    private let heroBottomPadding: CGFloat = 12

    private let nameExpandedSize: CGFloat = 26
    private let nameCollapsedSize: CGFloat = 17

    /// Full expanded hero height (avatar + name + handle). No empty action row.
    private var expandedHeroHeight: CGFloat {
        heroTopPadding
            + avatarExpandedSize
            + avatarToNameGap
            + nameExpandedLine
            + handleGap
            + handleLine
            + heroBottomPadding
    }

    /// Scroll distance that maps progress 0 → 1.
    private var collapseDistance: CGFloat {
        max(1, expandedHeroHeight - compactBarHeight)
    }

    /// 0 at rest → 1 when fully collapsed into the compact title bar.
    private var collapseProgress: CGFloat {
        min(1, max(0, scrollOffsetY / collapseDistance))
    }

    /// Sticky chrome height shrinks from expanded hero → compact title bar.
    private var stickyChromeHeight: CGFloat {
        max(compactBarHeight, expandedHeroHeight - scrollOffsetY)
    }

    // MARK: - Avatar motion

    private var avatarScale: CGFloat {
        1 - 0.78 * collapseProgress
    }

    private var avatarBlur: CGFloat {
        20 * collapseProgress
    }

    private var avatarOpacity: CGFloat {
        max(0, 1 - pow(collapseProgress, 1.25) * 1.08)
    }

    private var avatarCenterY: CGFloat {
        let rest = heroTopPadding + avatarExpandedSize / 2
        // Exit above the compact bar so it clears the title.
        let gone = -avatarExpandedSize * 0.55
        return rest + (gone - rest) * collapseProgress
    }

    // MARK: - Name motion (hero → centered bar title)

    private var nameCenterY: CGFloat {
        let rest = heroTopPadding
            + avatarExpandedSize
            + avatarToNameGap
            + nameExpandedLine / 2
        let bar = compactBarHeight / 2
        // Ease so the name reaches the bar a bit before progress hits 1.
        let t = min(1, collapseProgress * 1.05)
        return rest + (bar - rest) * t
    }

    private var nameFontSize: CGFloat {
        // Keep large early; finish at compact bar title size.
        let t = collapseProgress * collapseProgress
        return nameExpandedSize - (nameExpandedSize - nameCollapsedSize) * t
    }

    private var nameWeight: Font.Weight {
        collapseProgress > 0.5 ? .semibold : .bold
    }

    private var handleOpacity: CGFloat {
        max(0, 1 - collapseProgress * 2.0)
    }

    private var handleCenterY: CGFloat {
        nameCenterY + nameExpandedLine / 2 + handleGap + handleLine / 2
    }

    private var badgeOpacity: CGFloat {
        max(0, 1 - collapseProgress * 2.4)
    }

    private var materialProgress: CGFloat {
        min(1, max(0, collapseProgress * 1.1))
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
                    case .transcription:
                        TranscriptionLanguageView()
                    case .privacySecurity:
                        PrivacySecurityView(router: router)
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
                        // Match expanded sticky height so content sits below the hero at rest.
                        Color.clear
                            .frame(height: expandedHeroHeight)
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
                .onScrollGeometryChange(for: CGFloat.self) { geometry in
                    // Include top content inset so progress is 0 when pinned at rest.
                    max(0, geometry.contentOffset.y + geometry.contentInsets.top)
                } action: { _, newOffset in
                    guard abs(newOffset - scrollOffsetY) > 0.2 else { return }
                    var transaction = Transaction()
                    transaction.disablesAnimations = true
                    withTransaction(transaction) {
                        scrollOffsetY = newOffset
                    }
                }

                stickyChrome(midX: midX)
                    .allowsHitTesting(false)
            }
        }
        .background(Theme.backgroundGrouped)
        .confirmationDialog(
            "Log out of Shroud?",
            isPresented: $showLogOutConfirm,
            titleVisibility: .visible
        ) {
            Button("Log Out", role: .destructive) {
                router.logOut()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(
                "This ends your session on this device and clears cached messages, media, and chat keys from local storage. You’ll need your password and encryption phrase to sign in again."
            )
        }
    }

    // MARK: - Sticky chrome

    private func stickyChrome(midX: CGFloat) -> some View {
        ZStack(alignment: .top) {
            stickyGradientBackground
                .frame(height: stickyChromeHeight + 28)
                .frame(maxWidth: .infinity, alignment: .top)

            // Avatar — exits upward as the bar collapses.
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

            // Name — continuous path into the compact centered title.
            HStack(spacing: 6) {
                Text(displayName)
                    .font(.system(size: nameFontSize, weight: nameWeight))
                    .foregroundStyle(Theme.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
                Image(systemName: "checkmark.seal.fill")
                    .font(.system(size: nameFontSize * 0.78, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                    .opacity(Double(badgeOpacity))
            }
            .frame(maxWidth: midX * 1.4)
            .position(x: midX, y: nameCenterY)
            .accessibilityAddTraits(.isHeader)
            .zIndex(6)

            Text(handle)
                .font(.system(size: 15))
                .foregroundStyle(Theme.textSecondary)
                .opacity(Double(handleOpacity))
                .position(x: midX, y: handleCenterY)
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
        .padding(.bottom, 20)
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
            ) {
                navigationPath.append(.privacySecurity)
            }
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
            SettingsRowView(
                title: "Transcription",
                systemImage: "waveform",
                iconBackground: Color(red: 46 / 255, green: 143 / 255, blue: 224 / 255)
            ) {
                navigationPath.append(.transcription)
            }
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
            .buttonStyle(HighlightRowButtonStyle())
            .accessibilityLabel("Server, \(serverSubtitle)")
        }
    }

    private var logOutGroup: some View {
        Button {
            showLogOutConfirm = true
        } label: {
            HStack(spacing: 8) {
                if router.isLoggingOut {
                    ProgressView()
                        .controlSize(.small)
                }
                Text(router.isLoggingOut ? "Signing out…" : "Log Out")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Theme.danger)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(Theme.background)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .disabled(router.isLoggingOut)
        .pressable(scale: 0.98, dimming: 0.1, haptic: .medium)
        .accessibilityLabel(router.isLoggingOut ? "Signing out" : "Log Out")
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
