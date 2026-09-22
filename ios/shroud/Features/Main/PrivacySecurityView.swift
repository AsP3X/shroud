import SwiftUI

/// Privacy & security — lock chats, background wipe, vault explanation.
struct PrivacySecurityView: View {
    let router: AppRouter

    @Environment(CryptoController.self) private var crypto
    @Environment(MessagingController.self) private var messaging
    @Environment(\.dismiss) private var dismiss

    @State private var lockOnBackground = SecurityPreferences.lockChatsOnBackground
    @State private var generatesLinkPreviews = SecurityPreferences.generatesLinkPreviews
    @State private var toast: String?
    /// True while the server round-trip for the chat-delete consent flag is in flight.
    @State private var isSavingChatDeleteConsent = false
    /// Unblock calls in flight, so a row can't be tapped twice.
    @State private var unblockingUserIDs: Set<UUID> = []

    var body: some View {
        GroupedScreen {
            VStack(spacing: 0) {
                navRow

                ScrollView {
                    VStack(spacing: 14) {
                        settingsCard {
                            Toggle(isOn: $lockOnBackground) {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("Lock chats in background")
                                        .font(.system(size: 16))
                                        .foregroundStyle(Theme.textPrimary)
                                    Text(
                                        "When you leave the app, decrypted messages are cleared from memory. Re-open from the welcome screen with Face ID, device passcode, or your encryption phrase."
                                    )
                                    .font(.system(size: 13))
                                    .foregroundStyle(Theme.textSecondary)
                                    .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                            .tint(Theme.accent)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 12)
                            .onChange(of: lockOnBackground) { _, value in
                                SecurityPreferences.lockChatsOnBackground = value
                                Haptics.impact(.light)
                            }
                        }

                        settingsCard {
                            chatDeleteConsentToggle
                        }

                        settingsCard {
                            linkPreviewsToggle
                        }

                        settingsCard {
                            Toggle(isOn: Binding(
                                get: { SecurityPreferences.requireUserPresence },
                                set: { newValue in
                                    SecurityPreferences.requireUserPresence = newValue
                                    Haptics.impact(.light)
                                }
                            )) {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("Require Face ID / passcode")
                                        .font(.system(size: 16))
                                        .foregroundStyle(Theme.textPrimary)
                                    Text(
                                        "When on, the history wrap key asks for biometrics or device passcode. Turn off only on devices that cannot prompt (e.g. some simulators). Lock and unlock chats after changing so the vault re-wraps."
                                    )
                                    .font(.system(size: 13))
                                    .foregroundStyle(Theme.textSecondary)
                                    .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                            .tint(Theme.accent)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 12)
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
        }
        .toast($toast)
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
                    "When a contact deletes a chat for both of you, your copy is deleted too. Leave this off to keep your own messages — theirs are replaced with “Message deleted” either way, and deleting for both always removes the contact."
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

    private var navRow: some View {
        HStack {
            Button {
                dismiss()
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                    .frame(width: 44, height: 44, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .pressable(scale: 0.88)
            .accessibilityLabel("Back")

            Spacer()

            Text("Privacy and Security")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)

            Spacer()

            Color.clear.frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 4)
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
