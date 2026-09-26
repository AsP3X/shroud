import SwiftUI
import UIKit
import UserNotifications

/// Settings → Notifications and Sounds, after Telegram's.
///
/// Human: Two kinds of notification, set apart on purpose. Pushes (the app closed or locked)
/// come through Apple and never hold message text — the server has none; they can name the
/// sender, sealed so Apple cannot read it. In-app banners (the app open) may show the text.
/// Mutes live on the chat (its row's menu, or its profile) and follow the account everywhere.
/// Agent: READS/WRITES NotificationPreferences; PUTs the server's part (debounced);
/// READS MessagingController for muted chats.
struct NotificationsSettingsView: View {
    @Binding var navigationPath: [SettingsRoute]

    @Environment(NotificationsController.self) private var notifications
    @Environment(MessagingController.self) private var messaging
    @Environment(SessionController.self) private var session
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase

    @State private var toast: String?
    @State private var testResult: String?
    @State private var isTesting = false
    @State private var showResetConfirm = false
    @State private var unmuting: Set<UUID> = []
    @State private var saveTask: Task<Void, Never>?

    private var preferences: NotificationPreferences { notifications.preferences }

    var body: some View {
        GroupedScreen {
            ScrollView {
                VStack(spacing: 14) {
                    permissionCard
                    messageCard
                    sectionFooter(
                        "Notifications that arrive while Shroud is closed or locked never contain message text — the server can't read it. With Show Sender on, the sender's name travels sealed, so Apple can't read it either."
                    )
                    alsoCard
                    inAppCard
                    sectionFooter("While Shroud is open, a banner, sound or vibration tells you about messages in other chats. Banners can show the text: they never reach the notification centre.")
                    badgeCard
                    mutedCard
                    testCard
                    resetCard
                    Color.clear.frame(height: 24)
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
        }
        // System navigation bar: Liquid Glass back button, inline title, scroll edge fade.
        .navigationTitle("Notifications and Sounds")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        .toast($toast)
        .task { await notifications.refreshAuthorization() }
        .onChange(of: scenePhase) { _, phase in
            // Back from iOS Settings: permission may have changed there.
            if phase == .active { Task { await notifications.refreshAuthorization() } }
        }
        .confirmationDialog(
            "Reset notification settings?",
            isPresented: $showResetConfirm,
            titleVisibility: .visible
        ) {
            Button("Reset", role: .destructive) {
                preferences.reset()
                saveToServer()
                toast = "Notification settings reset"
                Haptics.notification(.success)
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Every switch on this screen goes back to how a new install notifies. Muted chats stay muted.")
        }
    }

    // MARK: - Permission

    @ViewBuilder
    private var permissionCard: some View {
        switch notifications.authorization {
        case .denied:
            card {
                VStack(alignment: .leading, spacing: 10) {
                    Label("Notifications are off for Shroud", systemImage: "bell.slash.fill")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Theme.danger)
                    Text("iOS isn't letting Shroud show notifications. Turn them on in Settings → Notifications → Shroud.")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    Button("Open Settings") {
                        if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    }
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(14)
            }
        case .notDetermined:
            card {
                VStack(alignment: .leading, spacing: 10) {
                    Label("Allow notifications", systemImage: "bell.badge.fill")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Theme.accent)
                    Text("So you hear about new messages while Shroud is closed.")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textSecondary)
                    Button("Allow") {
                        Task {
                            if await notifications.requestAuthorizationIfNeeded() {
                                UIApplication.shared.registerForRemoteNotifications()
                            }
                        }
                    }
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(14)
            }
        default:
            EmptyView()
        }
    }

    // MARK: - Sections

    private var messageCard: some View {
        card {
            toggleRow(
                "Show Notifications",
                subtitle: "New messages on this iPhone while Shroud is closed or locked.",
                isOn: binding(\.enabled, server: true)
            )
            divider
            // Not tied to Show Notifications: in-app banners name the sender by it too.
            toggleRow(
                "Show Sender",
                subtitle: "Off, a notification only says that something arrived.",
                isOn: binding(\.showSender, server: true)
            )
            divider
            Button {
                navigationPath.append(.notificationSound)
            } label: {
                HStack(spacing: 12) {
                    Text("Sound")
                        .font(.system(size: 16))
                        .foregroundStyle(Theme.textPrimary)
                    Spacer(minLength: 8)
                    Text(preferences.sound.title)
                        .font(.system(size: 16))
                        .foregroundStyle(Theme.textSecondary)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Theme.textSecondary.opacity(0.6))
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 13)
                .contentShape(Rectangle())
            }
            .buttonStyle(HighlightRowButtonStyle())
            .accessibilityLabel("Sound, \(preferences.sound.title)")
        }
    }

    private var alsoCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            sectionHeader("Also Notify Me About")
            card {
                toggleRow("Reactions to My Messages", isOn: binding(\.reactions, server: true))
                divider
                toggleRow("Contact Requests", isOn: binding(\.contactRequests, server: true))
            }
        }
    }

    private var inAppCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            sectionHeader("In-App Notifications")
            card {
                toggleRow("Banners", isOn: binding(\.inAppBanners, server: false))
                divider
                toggleRow(
                    "Message Preview",
                    subtitle: "Show the text in banners.",
                    isOn: binding(\.showPreview, server: false)
                )
                .disabled(!preferences.inAppBanners)
                divider
                toggleRow("Sounds", isOn: binding(\.inAppSounds, server: false))
                divider
                toggleRow("Vibrate", isOn: binding(\.inAppVibrate, server: false))
            }
        }
    }

    private var badgeCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            sectionHeader("Badge Counter")
            card {
                toggleRow(
                    "Show Badge",
                    subtitle: "Unread messages on the app icon.",
                    isOn: binding(\.badge, server: true)
                )
                divider
                // Also counts for the Chats tab, so not tied to Show Badge.
                toggleRow("Include Muted Chats", isOn: binding(\.badgeIncludesMuted, server: true))
            }
        }
    }

    private var mutedChats: [ConversationItemDTO] {
        messaging.conversations.filter { messaging.isMuted($0.peer.id) }
    }

    private var mutedCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            sectionHeader("Muted Chats")
            card {
                if mutedChats.isEmpty {
                    Text("No muted chats. Long-press a chat, or open its profile, to mute it.")
                        .font(.system(size: 14))
                        .foregroundStyle(Theme.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(14)
                } else {
                    ForEach(mutedChats) { conversation in
                        mutedRow(conversation)
                        if conversation.id != mutedChats.last?.id {
                            divider.padding(.leading, 44)
                        }
                    }
                }
            }
        }
    }

    private func mutedRow(_ conversation: ConversationItemDTO) -> some View {
        let peer = conversation.peer
        return HStack(spacing: 12) {
            AvatarView(
                initials: AvatarView.initials(for: peer.username),
                size: 32,
                gradient: AvatarView.gradient(for: peer.username),
                fontSize: 13
            )
            VStack(alignment: .leading, spacing: 1) {
                Text(peer.username)
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.textPrimary)
                    .lineLimit(1)
                Text(MuteDuration.label(for: messaging.mute(for: peer.id)) ?? "Muted")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
            }
            Spacer(minLength: 0)
            Button("Unmute") {
                unmute(peer)
            }
            .font(.system(size: 15, weight: .medium))
            .foregroundStyle(Theme.accent)
            .disabled(unmuting.contains(peer.id))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .accessibilityElement(children: .combine)
    }

    private var testCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            card {
                Button {
                    sendTest()
                } label: {
                    HStack(spacing: 12) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 8, style: .continuous)
                                .fill(Color(red: 46 / 255, green: 143 / 255, blue: 224 / 255))
                                .frame(width: 30, height: 30)
                            Image(systemName: "paperplane.fill")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(Color.white)
                        }
                        Text(isTesting ? "Sending…" : "Send a Test Notification")
                            .font(.system(size: 16))
                            .foregroundStyle(Theme.textPrimary)
                        Spacer(minLength: 0)
                        if isTesting { ProgressView().controlSize(.small) }
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .contentShape(Rectangle())
                }
                .buttonStyle(HighlightRowButtonStyle())
                .disabled(isTesting || !preferences.enabled)
            }
            if let testResult {
                sectionFooter(testResult)
                    .transition(.opacity)
            }
        }
        .animation(Motion.fade, value: testResult)
    }

    private var resetCard: some View {
        card {
            Button {
                showResetConfirm = true
            } label: {
                Text("Reset Notification Settings")
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.danger)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 13)
                    .contentShape(Rectangle())
            }
            .buttonStyle(HighlightRowButtonStyle())
        }
    }

    // MARK: - Actions

    private func unmute(_ peer: ConversationPeerDTO) {
        unmuting.insert(peer.id)
        Task {
            let error = await messaging.unmuteChat(peerUserID: peer.id)
            unmuting.remove(peer.id)
            if let error {
                toast = error
                Haptics.notification(.error)
            } else {
                Haptics.impact(.light)
            }
        }
    }

    private func sendTest() {
        guard let token = session.bearerToken else { return }
        isTesting = true
        testResult = nil
        Task {
            // Settings first, so the test uses the sound just picked.
            try? await notifications.pushSettings(token: token)
            testResult = await notifications.sendTest(token: token)
            isTesting = false
        }
    }

    /// The server acts on some settings while the app is closed: saved there shortly after a
    /// change (a burst of flips sends one request).
    private func saveToServer() {
        saveTask?.cancel()
        saveTask = Task {
            try? await Task.sleep(for: .milliseconds(500))
            guard !Task.isCancelled, let token = session.bearerToken else { return }
            do {
                try await notifications.pushSettings(token: token)
            } catch {
                toast = "Saved on this iPhone — the server gets it next time"
            }
        }
    }

    private func binding(_ keyPath: ReferenceWritableKeyPath<NotificationPreferences, Bool>, server: Bool) -> Binding<Bool> {
        Binding(
            get: { preferences[keyPath: keyPath] },
            set: { value in
                guard preferences[keyPath: keyPath] != value else { return }
                preferences[keyPath: keyPath] = value
                Haptics.impact(.light)
                if keyPath == \.badge || keyPath == \.badgeIncludesMuted { messaging.updateBadge() }
                if server { saveToServer() }
            }
        )
    }

    // MARK: - Pieces

    private func toggleRow(_ title: String, subtitle: String? = nil, isOn: Binding<Bool>) -> some View {
        Toggle(isOn: isOn) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.textPrimary)
                if let subtitle {
                    Text(subtitle)
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .tint(Theme.accent)
        .padding(.horizontal, 14)
        .padding(.vertical, subtitle == nil ? 11 : 10)
    }

    private var divider: some View {
        Rectangle()
            .fill(Theme.separator)
            .frame(height: 1)
            .padding(.leading, 14)
    }

    private func sectionHeader(_ title: String) -> some View {
        Text(title.uppercased())
            .font(.system(size: 13))
            .foregroundStyle(Theme.textSecondary)
            .padding(.horizontal, 14)
    }

    private func sectionFooter(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 13))
            .foregroundStyle(Theme.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14)
            .padding(.top, -6)
    }

    private func card<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        VStack(spacing: 0) {
            content()
        }
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

}

/// Picks the notification sound; each choice plays as it is picked.
struct NotificationSoundPicker: View {
    @Environment(NotificationsController.self) private var notifications
    @Environment(SessionController.self) private var session
    @Environment(\.dismiss) private var dismiss

    @State private var saveTask: Task<Void, Never>?

    var body: some View {
        GroupedScreen {
            ScrollView {
                VStack(spacing: 8) {
                    VStack(spacing: 0) {
                        ForEach(NotificationSound.allCases) { sound in
                            row(sound)
                            if sound != NotificationSound.allCases.last {
                                Rectangle()
                                    .fill(Theme.separator)
                                    .frame(height: 1)
                                    .padding(.leading, 14)
                            }
                        }
                    }
                    .background(Theme.background)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))

                    Text("Plays for notifications, and for banners while Shroud is open (unless the iPhone is on silent).")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
        }
        // System navigation bar: Liquid Glass back button, inline title, scroll edge fade.
        .navigationTitle("Sound")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
    }

    private func row(_ sound: NotificationSound) -> some View {
        let selected = notifications.preferences.sound == sound
        return Button {
            notifications.preferences.sound = sound
            sound.play()
            save()
        } label: {
            HStack {
                Text(sound.title)
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.textPrimary)
                Spacer()
                if selected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Theme.accent)
                        .transition(Motion.iconSwap)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
            .contentShape(Rectangle())
        }
        .buttonStyle(HighlightRowButtonStyle())
        .animation(Motion.snappy, value: selected)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func save() {
        saveTask?.cancel()
        saveTask = Task {
            try? await Task.sleep(for: .milliseconds(500))
            guard !Task.isCancelled, let token = session.bearerToken else { return }
            try? await notifications.pushSettings(token: token)
        }
    }
}
