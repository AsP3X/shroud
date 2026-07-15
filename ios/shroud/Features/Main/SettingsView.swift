import SwiftUI

/// Settings tab — maps to `Settings` in `iOS-App.pen` (profile + grouped rows).
struct SettingsView: View {
    let router: AppRouter

    @Environment(SessionController.self) private var sessionController
    @Environment(ServerConfigurationController.self) private var serverConfig
    @State private var showServerSettings = false

    private var displayName: String {
        if let username = sessionController.username, !username.isEmpty {
            // Design shows a full name; until profile fields exist, title-case the username.
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

    var body: some View {
        // Settings: large title scrolls away (not sticky / collapsing).
        MainScrollScreen(title: "Settings") {
            Color.clear.frame(width: 1, height: 1)
                .accessibilityHidden(true)
        } navTrailing: {
            Button("Edit") {}
                .font(.system(size: 16))
                .foregroundStyle(Theme.accent)
        } content: {
            VStack(spacing: 20) {
                profileCard
                primaryGroup
                secondaryGroup
                serverGroup
                logOutGroup
                Color.clear.frame(height: 16)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 8)
        }
        .background(Theme.backgroundGrouped)
        .sheet(isPresented: $showServerSettings) {
            ServerSettingsSheet()
                .environment(serverConfig)
        }
    }

    private var profileCard: some View {
        Button {} label: {
            HStack(spacing: 14) {
                AvatarView(initials: initials, size: 64, fontSize: 23)
                VStack(alignment: .leading, spacing: 2) {
                    Text(displayName)
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundStyle(Theme.textPrimary)
                    Text(handle)
                        .font(.system(size: 14))
                        .foregroundStyle(Theme.textSecondary)
                    Text(serverLabel)
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textSecondary)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.textSecondary)
            }
            .padding(14)
            .background(Theme.background)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Profile \(displayName)")
    }

    private var serverLabel: String {
        switch serverConfig.configuration.mode {
        case .official:
            return "Official Shroud server"
        case .selfHosted:
            return serverConfig.configuration.selfHostedPreviewString
        }
    }

    private var primaryGroup: some View {
        settingsCard {
            SettingsRowView(
                title: "Saved Messages",
                systemImage: "bookmark.fill",
                iconBackground: Color(red: 46 / 255, green: 143 / 255, blue: 224 / 255)
            )
            groupDivider
            SettingsRowView(
                title: "Recent Calls",
                systemImage: "phone.fill",
                iconBackground: Color(red: 47 / 255, green: 168 / 255, blue: 91 / 255)
            )
            groupDivider
            SettingsRowView(
                title: "Devices",
                systemImage: "laptopcomputer.and.iphone",
                iconBackground: Color(red: 247 / 255, green: 107 / 255, blue: 28 / 255)
            )
            groupDivider
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
            groupDivider
            SettingsRowView(
                title: "Privacy and Security",
                systemImage: "lock.fill",
                iconBackground: Theme.textSecondary
            )
            groupDivider
            SettingsRowView(
                title: "Data and Storage",
                systemImage: "externaldrive.fill",
                iconBackground: Color(red: 47 / 255, green: 168 / 255, blue: 91 / 255)
            )
            groupDivider
            SettingsRowView(
                title: "Appearance",
                systemImage: "paintpalette.fill",
                iconBackground: Theme.accent
            )
            groupDivider
            SettingsRowView(
                title: "Language",
                systemImage: "globe",
                iconBackground: Color(red: 155 / 255, green: 74 / 255, blue: 230 / 255)
            )
        }
    }

    private var serverGroup: some View {
        settingsCard {
            SettingsRowView(
                title: "Server",
                systemImage: "server.rack",
                iconBackground: Theme.accent
            ) {
                showServerSettings = true
            }
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

    private var groupDivider: some View {
        Rectangle()
            .fill(Theme.separator)
            .frame(height: 1)
            .padding(.leading, 54)
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
