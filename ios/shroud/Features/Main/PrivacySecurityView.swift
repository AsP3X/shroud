import SwiftUI

/// Privacy & security — lock chats, background wipe, what contacts can see, vault explanation.
struct PrivacySecurityView: View {
    let router: AppRouter

    @Environment(CryptoController.self) private var crypto
    @Environment(MessagingController.self) private var messaging
    @Environment(\.dismiss) private var dismiss

    @State private var autoLockDelay = SecurityPreferences.autoLockDelay
    @State private var hidesDuringScreenCapture = SecurityPreferences.hidesDuringScreenCapture
    @State private var blocksThirdPartyKeyboards = SecurityPreferences.blocksThirdPartyKeyboards
    @State private var generatesLinkPreviews = SecurityPreferences.generatesLinkPreviews
    @State private var alwaysRelayCalls = SecurityPreferences.alwaysRelayCalls
    @State private var toast: String?
    /// True while the server round-trip for the chat-delete consent flag is in flight.
    @State private var isSavingChatDeleteConsent = false
    /// Visibility switches with a server write in flight.
    @State private var savingVisibility: Set<VisibilitySwitch> = []
    @State private var isSavingDiscoverable = false
    @State private var confirmingShareCodeReset = false
    @State private var isResettingShareCode = false
    /// Unblock calls in flight, so a row can't be tapped twice.
    @State private var unblockingUserIDs: Set<UUID> = []

    var body: some View {
        GroupedScreen {
            ScrollView {
                VStack(spacing: 14) {
                    settingsCard {
                        autoLockRow
                    }

                    settingsCard {
                        deviceProtectionSection
                    }

                    settingsCard {
                        visibilitySection
                    }

                    settingsCard {
                        findingYouSection
                    }

                    settingsCard {
                        chatDeleteConsentToggle
                    }

                    settingsCard {
                        linkPreviewsToggle
                    }

                    settingsCard {
                        relayCallsToggle
                    }

                    settingsCard {
                        Button {
                            lockChatsNow()
                        } label: {
                            HStack(spacing: 12) {
                                ZStack {
                                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                                        .fill(Theme.danger)
                                        .frame(width: 30, height: 30)
                                    Image(systemName: "lock.fill")
                                        .font(.system(size: 14, weight: .semibold))
                                        .foregroundStyle(Color.white)
                                }
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Lock chats now")
                                        .font(.system(size: 16))
                                        .foregroundStyle(Theme.textPrimary)
                                    Text("Leave the chat shell until you unlock again.")
                                        .font(.system(size: 13))
                                        .foregroundStyle(Theme.textSecondary)
                                }
                                Spacer(minLength: 0)
                            }
                            .padding(.horizontal, 14)
                            .padding(.vertical, 12)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(HighlightRowButtonStyle())
                    }

                    if !messaging.blockedUsers.isEmpty {
                        settingsCard {
                            blockedContactsSection
                        }
                    }

                    settingsCard {
                        VStack(alignment: .leading, spacing: 8) {
                            Label("Encrypted on this device", systemImage: "checkmark.shield.fill")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(Theme.accent)
                            Text(
                                "Chat history is sealed with a key from your encryption phrase. That key is not kept in plain Keychain storage — it is wrapped and only unwrapped after you authenticate with biometrics, passcode, or your 12-word phrase."
                            )
                            .font(.system(size: 13))
                            .foregroundStyle(Theme.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(14)
                    }

                    Color.clear.frame(height: 24)
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
        }
        // System navigation bar: Liquid Glass back button, inline title, scroll edge fade.
        .navigationTitle("Privacy and Security")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        .toast($toast)
        // `.alert`, not `.confirmationDialog`: on iOS 26 the anchored dialog drops its Cancel.
        .alert("Reset your QR code?", isPresented: $confirmingShareCodeReset) {
            Button("Reset", role: .destructive) { resetShareCode() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Your current QR code and invite link stop working. Anyone who wants to add you will need the new one. Your contacts aren't affected.")
        }
        .task {
            // The flag lives on the server (only it can enforce a peer's request), so the
            // switch reflects stored state rather than a local default.
            await messaging.refreshPrivacySettings()
            await messaging.refreshBlocks()
        }
    }

    /// Blocked users with a way back out. Blocking happens on the contact profile; this is
    /// the only place it can be undone, so the section exists whenever the list is non-empty.
    private var blockedContactsSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Blocked")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
                .padding(.horizontal, 14)
                .padding(.top, 12)
                .padding(.bottom, 6)

            ForEach(messaging.blockedUsers) { blocked in
                HStack(spacing: 12) {
                    AvatarView(
                        initials: AvatarView.initials(for: blocked.username),
                        size: 32,
                        gradient: AvatarView.gradient(for: blocked.username),
                        fontSize: 13
                    )
                    Text(blocked.username)
                        .font(.system(size: 16))
                        .foregroundStyle(Theme.textPrimary)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                    Button("Unblock") {
                        unblock(blocked)
                    }
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(Theme.accent)
                    .disabled(unblockingUserIDs.contains(blocked.userId))
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .accessibilityElement(children: .combine)
                .accessibilityLabel("\(blocked.username), blocked")

                if blocked.id != messaging.blockedUsers.last?.id {
                    Rectangle()
                        .fill(Theme.separator)
                        .frame(height: 1)
                        .padding(.leading, 58)
                }
            }
            Color.clear.frame(height: 6)
        }
    }

    private func unblock(_ blocked: BlockItemDTO) {
        unblockingUserIDs.insert(blocked.userId)
        Task {
            let error = await messaging.unblockUser(blocked.userId)
            unblockingUserIDs.remove(blocked.userId)
            if let error {
                toast = error
                Haptics.notification(.error)
            } else {
                toast = "\(blocked.username) unblocked"
                Haptics.impact(.light)
            }
        }
    }

    /// When the chats lock after leaving the app.
    ///
    /// Human: Locking clears decrypted messages and the history key from memory; coming back
    /// needs Face ID, the passcode or the phrase. A delay keeps them in memory that long, which
    /// is the trade-off the footnote states.
    /// Agent: WRITES SecurityPreferences.autoLockDelay (UserDefaults); READ by RootView.
    private var autoLockRow: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("Auto-lock")
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.textPrimary)
                Spacer(minLength: 8)
                Picker("Auto-lock", selection: $autoLockDelay) {
                    ForEach(AutoLockDelay.allCases) { delay in
                        Text(delay.label).tag(delay)
                    }
                }
                .pickerStyle(.menu)
                .labelsHidden()
                .tint(Theme.accent)
            }
            Text(
                "When you leave the app, decrypted messages are cleared from memory — right away, or once the time you pick has passed. Re-open with Face ID, device passcode, or your encryption phrase."
            )
            .font(.system(size: 13))
            .foregroundStyle(Theme.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .onChange(of: autoLockDelay) { _, value in
            SecurityPreferences.autoLockDelay = value
            Haptics.impact(.light)
        }
    }

    /// Protections against other things on this iPhone: screen recording and keyboards.
    /// Agent: WRITES SecurityPreferences.hidesDuringScreenCapture / blocksThirdPartyKeyboards.
    private var deviceProtectionSection: some View {
        VStack(spacing: 0) {
            Toggle(isOn: $hidesDuringScreenCapture) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Hide chats during screen recording")
                        .font(.system(size: 16))
                        .foregroundStyle(Theme.textPrimary)
                    Text("While the screen is recorded, mirrored or shared, Shroud shows only its logo.")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .tint(Theme.accent)
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .onChange(of: hidesDuringScreenCapture) { _, value in
                SecurityPreferences.hidesDuringScreenCapture = value
                Haptics.impact(.light)
            }

            Rectangle()
                .fill(Theme.separator)
                .frame(height: 1)
                .padding(.leading, 14)

            Toggle(isOn: $blocksThirdPartyKeyboards) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Only Apple keyboards")
                        .font(.system(size: 16))
                        .foregroundStyle(Theme.textPrimary)
                    Text("Keyboards from other apps can send what you type to their developer. Takes effect the next time Shroud starts.")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .tint(Theme.accent)
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .onChange(of: blocksThirdPartyKeyboards) { _, value in
                SecurityPreferences.blocksThirdPartyKeyboards = value
                Haptics.impact(.light)
            }
        }
    }

    /// What contacts can see of this account's activity.
    ///
    /// Human: Each switch works both ways and the server enforces it: turning one off hides
    /// yours from contacts and theirs from you. The footnote says so once rather than in every row.
    /// Agent: READS messaging.privacySettings; CALLS updatePrivacySettings (HTTP PUT).
    private var visibilitySection: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Visibility")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
                .padding(.horizontal, 14)
                .padding(.top, 12)
                .padding(.bottom, 2)

            ForEach(Array(VisibilitySwitch.allCases.enumerated()), id: \.element) { index, item in
                if index > 0 {
                    Rectangle()
                        .fill(Theme.separator)
                        .frame(height: 1)
                        .padding(.leading, 14)
                }
                visibilityToggle(item)
            }

            Text("These work both ways: when you hide yours, you won't see your contacts' either.")
                .font(.system(size: 13))
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 14)
                .padding(.top, 4)
                .padding(.bottom, 12)
        }
    }

    private func visibilityToggle(_ item: VisibilitySwitch) -> some View {
        Toggle(isOn: Binding(
            get: { item.value(in: messaging.privacySettings) },
            set: { newValue in
                guard newValue != item.value(in: messaging.privacySettings) else { return }
                savingVisibility.insert(item)
                Task {
                    let error = await messaging.updatePrivacySettings(item.change(to: newValue))
                    savingVisibility.remove(item)
                    if let error {
                        toast = error
                        Haptics.notification(.error)
                    } else {
                        Haptics.impact(.light)
                    }
                }
            }
        )) {
            VStack(alignment: .leading, spacing: 4) {
                Text(item.title)
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.textPrimary)
                Text(item.detail)
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .tint(Theme.accent)
        .disabled(savingVisibility.contains(item) || !messaging.hasLoadedPrivacySettings)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }

    /// Whether typing a link fetches its preview from this device.
    ///
    /// Human: Says plainly who sees what: the website sees this iPhone's IP address while the
    /// preview is built — the same as opening the link — and nobody else is involved. The
    /// recipient gets the preview sealed in the message and never contacts the site.
    /// Agent: WRITES SecurityPreferences.generatesLinkPreviews (UserDefaults); no network.
    private var linkPreviewsToggle: some View {
        Toggle(isOn: $generatesLinkPreviews) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Link previews")
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.textPrimary)
                Text(
                    "When you send a link, this iPhone loads the page to build a preview and seals it into the message. The website sees your IP address, as if you had opened the link. People you send it to never contact the website."
                )
                .font(.system(size: 13))
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            }
        }
        .tint(Theme.accent)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .onChange(of: generatesLinkPreviews) { _, value in
            SecurityPreferences.generatesLinkPreviews = value
            Haptics.impact(.light)
        }
    }

    /// Who can find this account, and a way to retire a QR code that reached the wrong people.
    ///
    /// Human: Off, a username is only a way in for contacts and pending requests; everyone else
    /// needs the QR code or share code, and gets the same "not found" as for a free name.
    /// Agent: READS/WRITES messaging.privacySettings.discoverableByUsername (HTTP PUT);
    /// CALLS rotateShareCode (HTTP POST /users/me/share-code).
    private var findingYouSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            Toggle(isOn: Binding(
                get: { messaging.privacySettings.discoverableByUsername },
                set: { newValue in
                    guard newValue != messaging.privacySettings.discoverableByUsername else { return }
                    isSavingDiscoverable = true
                    Task {
                        let error = await messaging.updatePrivacySettings(
                            UpdatePrivacySettingsBody(discoverableByUsername: newValue)
                        )
                        isSavingDiscoverable = false
                        if let error {
                            toast = error
                            Haptics.notification(.error)
                        } else {
                            Haptics.impact(.light)
                        }
                    }
                }
            )) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Find me by username")
                        .font(.system(size: 16))
                        .foregroundStyle(Theme.textPrimary)
                    Text("People who know your username can find you and send a request. Off, they need your QR code or share code; your contacts can still find you.")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .tint(Theme.accent)
            .disabled(isSavingDiscoverable || !messaging.hasLoadedPrivacySettings)
            .padding(.horizontal, 14)
            .padding(.vertical, 12)

            Rectangle()
                .fill(Theme.separator)
                .frame(height: 1)
                .padding(.leading, 14)

            Button {
                confirmingShareCodeReset = true
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Reset QR code")
                        .font(.system(size: 16))
                        .foregroundStyle(Theme.danger)
                    Text("Makes a new QR code and invite link. The old ones stop working.")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .contentShape(Rectangle())
            }
            .buttonStyle(HighlightRowButtonStyle())
            .disabled(isResettingShareCode)
        }
    }

    private func resetShareCode() {
        isResettingShareCode = true
        Task {
            let error = await messaging.rotateShareCode()
            isResettingShareCode = false
            if let error {
                toast = error
                Haptics.notification(.error)
            } else {
                toast = "New QR code ready"
                Haptics.notification(.success)
            }
        }
    }

    /// Whether calls always go through the server's relay.
    ///
    /// Human: A direct call path shows the other person this phone's IP address; the relay shows
    /// them the server's. Off by default because it costs the server bandwidth and adds a little
    /// latency. Without a relay on the server, calls are refused rather than sent direct.
    /// Agent: WRITES SecurityPreferences.alwaysRelayCalls (UserDefaults); READ by CallController.
    private var relayCallsToggle: some View {
        Toggle(isOn: $alwaysRelayCalls) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Always relay calls")
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.textPrimary)
                Text(
                    "Calls from this iPhone go through the Shroud server's relay, so the person you call never sees your IP address. Calls may lag slightly. If the server has no relay, calls won't connect until you turn this off."
                )
                .font(.system(size: 13))
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            }
        }
        .tint(Theme.accent)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .onChange(of: alwaysRelayCalls) { _, value in
            SecurityPreferences.alwaysRelayCalls = value
            Haptics.impact(.light)
        }
    }

    /// Opt-in consent for a contact's "delete chat for both" to also clear this account.
    ///
    /// Human: Off by default. With it off you still lose *their* messages when they delete
    /// for both — those become "Message deleted" — but your own side of the chat survives.
    /// Agent: READS messaging.allowsPeerChatDelete; CALLS setAllowsPeerChatDelete (HTTP PUT).
    private var chatDeleteConsentToggle: some View {
        Toggle(isOn: Binding(
            get: { messaging.allowsPeerChatDelete },
            set: { newValue in
                guard newValue != messaging.allowsPeerChatDelete else { return }
                isSavingChatDeleteConsent = true
                Task {
                    let error = await messaging.setAllowsPeerChatDelete(newValue)
                    isSavingChatDeleteConsent = false
                    if let error {
                        toast = error
                        Haptics.notification(.error)
                    } else {
                        Haptics.impact(.light)
                    }
                }
            }
        )) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Let contacts clear chats for me")
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.textPrimary)
                Text(
                    "When a contact deletes a chat for both of you, your copy is deleted too. Leave this off to keep your own messages — theirs are replaced with “Message deleted” either way. You stay contacts."
                )
                .font(.system(size: 13))
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            }
        }
        .tint(Theme.accent)
        .disabled(isSavingChatDeleteConsent || !messaging.hasLoadedPrivacySettings)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }


    private func lockChatsNow() {
        Haptics.notification(.warning)
        messaging.lockSensitiveMemory()
        messaging.stop(wipeDisk: false)
        crypto.lockHistoryInMemory()
        router.hasUnlockedMessaging = false
        toast = "Chats locked"
    }

    private func settingsCard<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        VStack(spacing: 0) {
            content()
        }
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

/// The account-level switches for what contacts can see (server-enforced, both ways).
private enum VisibilitySwitch: CaseIterable, Hashable {
    case readReceipts
    case typing
    case presence

    var title: String {
        switch self {
        case .readReceipts: "Read receipts"
        case .typing: "Typing indicators"
        case .presence: "Online and last seen"
        }
    }

    var detail: String {
        switch self {
        case .readReceipts: "Contacts see when you've read their messages."
        case .typing: "Contacts see when you're typing or recording a voice message."
        case .presence: "Contacts see when you're online and when you were last here."
        }
    }

    func value(in settings: PrivacySettingsDTO) -> Bool {
        switch self {
        case .readReceipts: settings.sendReadReceipts
        case .typing: settings.sendTyping
        case .presence: settings.sharePresence
        }
    }

    func change(to value: Bool) -> UpdatePrivacySettingsBody {
        switch self {
        case .readReceipts: UpdatePrivacySettingsBody(sendReadReceipts: value)
        case .typing: UpdatePrivacySettingsBody(sendTyping: value)
        case .presence: UpdatePrivacySettingsBody(sharePresence: value)
        }
    }
}
