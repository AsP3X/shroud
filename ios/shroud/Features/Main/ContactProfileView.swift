import SwiftUI

/// Contact header from a chat — maps to `Contact Profile` in `iOS-App.pen`.
struct ContactProfileView: View {
    let peerUserID: UUID
    let peerUsername: String
    /// Called after the chat was deleted; the host leaves the thread (this screen goes with
    /// it). Without it the profile just pops back.
    var onChatDeleted: (() -> Void)? = nil

    @Environment(MessagingController.self) private var messaging
    @Environment(CallController.self) private var calls
    @Environment(\.dismiss) private var dismiss
    @State private var toast: Toast?
    @State private var showBlockConfirm = false
    @State private var showAcceptIdentityConfirm = false
    @State private var isBlocking = false
    @State private var showDeleteChatConfirm = false
    @State private var isDeletingChat = false

    private var isOnline: Bool {
        messaging.presenceByUser[peerUserID]?.online == true
    }

    private var isBlocked: Bool {
        messaging.blockedUsers.contains { $0.userId == peerUserID }
    }

    private var peerActivity: ChatPeerActivity? {
        messaging.peerActivity(for: peerUserID)
    }

    /// Same presence wording as the chat header and the lists ("offline" when the contact
    /// hides it); "Shroud contact" only until the server has answered.
    private var statusLine: String {
        ChatListFormatting.presenceLabel(for: messaging.presenceByUser[peerUserID]) ?? "Shroud contact"
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                profileHead
                actionRow
                if messaging.identityChange(for: peerUserID) != nil {
                    identityWarningCard
                }
                infoCard
                optionsCard
                blockCard
                deleteChatCard
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 32)
        }
        .background(Theme.backgroundGrouped.ignoresSafeArea())
        // System navigation bar: Liquid Glass back button and Edit capsule; the hero
        // scrolls under the bar and fades, so there is no title and no solid backdrop.
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Edit") {
                    toast = .info("Edit coming soon")
                }
                .tint(Theme.accent)
            }
        }
        .toast($toast)
        .task {
            await messaging.refreshPresence(for: [peerUserID])
            await messaging.refreshBlocks()
            await messaging.refreshPeerIdentity(peerUserID)
        }
    }

    private var profileHead: some View {
        VStack(spacing: 3) {
            AvatarView(
                initials: AvatarView.initials(for: peerUsername),
                size: 96,
                gradient: AvatarView.gradient(for: peerUsername),
                fontSize: 34
            )
            .padding(.top, 8)

            VStack(spacing: 2) {
                // A long handle has no spaces to wrap at: shrink it, as the call screen does.
                Text(peerUsername)
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                if let peerActivity {
                    TypingLabel(activity: peerActivity, font: .system(size: 14))
                } else {
                    Text(statusLine)
                        .font(.system(size: 14))
                        .foregroundStyle(isOnline ? Theme.accent : Theme.textSecondary)
                }
            }
            .padding(.top, 8)
        }
        .frame(maxWidth: .infinity)
    }

    private var actionRow: some View {
        HStack(spacing: 8) {
            profileAction(title: "Call", icon: "phone.fill") {
                Task {
                    await calls.startCall(
                        peerUserID: peerUserID,
                        peerUsername: peerUsername,
                        modality: .voice
                    )
                    if let err = calls.lastError {
                        toast = .failure(err)
                    }
                }
            }
            profileAction(title: "Video", icon: "video.fill") {
                Task {
                    await calls.startCall(
                        peerUserID: peerUserID,
                        peerUsername: peerUsername,
                        modality: .video
                    )
                    if let err = calls.lastError {
                        toast = .failure(err)
                    }
                }
            }
            muteMenu {
                VStack(spacing: 5) {
                    Image(systemName: isMuted ? "bell.fill" : "bell.slash.fill")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(Theme.accent)
                        .contentTransition(.symbolEffect(.replace))
                    Text(isMuted ? "Unmute" : "Mute")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(Theme.accent)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .background(Theme.background)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .contentShape(Rectangle())
            }
            .pressable(scale: 0.93)
            .accessibilityLabel(isMuted ? "Unmute" : "Mute")
            profileAction(title: "Search", icon: "magnifyingglass") {
                toast = .info("Search coming soon")
            }
        }
        .padding(.top, 6)
    }

    private func profileAction(title: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 5) {
                Image(systemName: icon)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                Text(title)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(Theme.accent)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 10)
            .background(Theme.background)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .contentShape(Rectangle())
        }
        .pressable(scale: 0.93)
        .accessibilityLabel(title)
    }

    private var infoCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 1) {
                Text("username")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.textSecondary)
                Text("@\(peerUsername)")
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.textPrimary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            // One VoiceOver stop per fact: "username, @jane".
            .accessibilityElement(children: .combine)

            Rectangle()
                .fill(Theme.separator)
                .frame(height: 1)
                .padding(.leading, 14)

            VStack(alignment: .leading, spacing: 1) {
                Text("encryption")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.textSecondary)
                Text("End-to-end encrypted")
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.textPrimary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            .accessibilityElement(children: .combine)

            // During a key change this is the new key's number, the one the contact's phone
            // shows, so "I verified this contact" can be checked against it (P10b). Verifying
            // only appears once the new key is trusted.
            if let number = messaging.safetyNumber(for: peerUserID) {
                Rectangle()
                    .fill(Theme.separator)
                    .frame(height: 1)
                    .padding(.leading, 14)

                VStack(alignment: .leading, spacing: 4) {
                    Text("safety number")
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.textSecondary)
                    Text(number)
                        .font(.system(size: 13, weight: .medium, design: .monospaced))
                        .foregroundStyle(Theme.textPrimary)
                        .textSelection(.enabled)
                    if messaging.identityChange(for: peerUserID) == nil {
                        // "Verified", as on the call screen's badge and its Mark as Verified.
                        if messaging.peerSafetyVerified(peerUserID) {
                            Text("Verified")
                                .font(.system(size: 13))
                                .foregroundStyle(Theme.textSecondary)
                        } else {
                            Button {
                                messaging.confirmPeerSafety(peerUserID)
                            } label: {
                                // A full-width target that grows down, and up only into the 4 pt
                                // gap: a tap on the selectable number above must never verify.
                                Text("Mark as Verified")
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundStyle(Theme.accent)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.top, 4)
                                    .padding(.bottom, 12)
                                    .contentShape(Rectangle())
                                    .padding(.top, -4)
                                    .padding(.bottom, -12)
                            }
                        }
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .accessibilityIdentifier("contact.safetyNumber")
            }
        }
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private var isMuted: Bool { messaging.isMuted(peerUserID) }

    /// Mute for a while or until turned back on — Telegram's choices — or unmute. The mute
    /// follows the account to every device.
    @ViewBuilder
    private func muteMenu<Label: View>(@ViewBuilder label: () -> Label) -> some View {
        if isMuted {
            Button {
                changeMute(to: nil)
            } label: {
                label()
            }
        } else if !messaging.canMute(peerUserID) {
            // No chat yet: a mute shows in the chat list, and there is none to show it in.
            Button {
                toast = .info("A chat can be muted once it has messages.")
            } label: {
                label()
            }
        } else {
            Menu {
                ForEach(MuteDuration.allCases) { duration in
                    Button(duration.title) { changeMute(to: duration) }
                }
            } label: {
                label()
            }
        }
    }

    private func changeMute(to duration: MuteDuration?) {
        Task {
            let error = if let duration {
                await messaging.muteChat(peerUserID: peerUserID, duration: duration)
            } else {
                await messaging.unmuteChat(peerUserID: peerUserID)
            }
            if let error {
                toast = .failure(error)
                Haptics.notification(.error)
            } else {
                // Same wording as the chat list's menu: "Muted until 14:30".
                toast = Toast(duration == nil ? "Notifications on" : (MuteDuration.label(for: messaging.mute(for: peerUserID)) ?? "Muted"))
                Haptics.impact(.light)
            }
        }
    }

    private var optionsCard: some View {
        VStack(spacing: 0) {
            muteMenu {
                optionsRow(
                    icon: isMuted ? "bell.slash.fill" : "bell.fill",
                    title: "Notifications",
                    value: MuteDuration.label(for: messaging.mute(for: peerUserID)) ?? "On"
                )
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Rectangle()
                .fill(Theme.separator)
                .frame(height: 1)
                .padding(.leading, 56)
            // Status only, not a control: no chevron, and read as one VoiceOver stop.
            optionsRow(icon: "lock.fill", title: "Encryption", value: "On", showsChevron: false)
                .accessibilityElement(children: .combine)
        }
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    /// `showsChevron` is for rows that open something; a status row leaves it out.
    private func optionsRow(icon: String, title: String, value: String, showsChevron: Bool = true) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Theme.accent)
                .frame(width: 30, height: 30)
                .background(Theme.accentSoft)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                .accessibilityHidden(true)
            // One line each: "Muted until Mon 2:30 PM" scales down a little instead of
            // wrapping the row.
            Text(title)
                .font(.system(size: 16))
                .foregroundStyle(Theme.textPrimary)
                .lineLimit(1)
                .layoutPriority(1)
            Spacer(minLength: 0)
            Text(value)
                .font(.system(size: 15))
                .foregroundStyle(Theme.textSecondary)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Theme.chevron)
                    .accessibilityHidden(true)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    private var identityWarningCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Encryption key changed", systemImage: "exclamationmark.triangle.fill")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Theme.danger)
            Text(
                "This contact's identity key no longer matches the one saved on this device. Compare safety numbers in person before trusting new messages."
            )
            .font(.system(size: 14))
            .foregroundStyle(Theme.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
            Button {
                showAcceptIdentityConfirm = true
            } label: {
                // Only static text sits above, and this just opens a confirmation, so the
                // target can grow 10 pt on every side.
                Text("I verified this contact")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle().inset(by: -10))
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .confirmationDialog(
            "Trust the new key?",
            isPresented: $showAcceptIdentityConfirm,
            titleVisibility: .visible
        ) {
            Button("Trust new key", role: .destructive) {
                messaging.acceptNewPeerIdentity(peerUserID)
                toast = Toast("New encryption key saved")
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Only do this if you confirmed this contact's safety number through another channel.")
        }
    }

    /// Blocking removes the contact and stops new requests. Deleting the chat does neither.
    private var blockCard: some View {
        Button {
            showBlockConfirm = true
        } label: {
            HStack(spacing: 8) {
                if isBlocking {
                    ProgressView().controlSize(.small)
                }
                Text(isBlocked ? "Unblock \(peerUsername)" : "Block \(peerUsername)")
                    .font(.system(size: 16))
                    .foregroundStyle(isBlocked ? Theme.accent : Theme.danger)
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
            .background(Theme.background)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(isBlocking)
        .confirmationDialog(
            isBlocked ? "Unblock \(peerUsername)?" : "Block \(peerUsername)?",
            isPresented: $showBlockConfirm,
            titleVisibility: .visible
        ) {
            if isBlocked {
                Button("Unblock") { performBlockChange(block: false) }
            } else {
                Button("Block", role: .destructive) { performBlockChange(block: true) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(
                isBlocked
                    ? "They can send you a contact request again. Your existing messages are unaffected."
                    : "They can't message you or send contact requests. You'll also stop being contacts."
            )
        }
    }

    /// Deleting the chat moved here from the thread's header, next to Block, so every
    /// account-level action on this person sits in one place.
    ///
    /// Human: The dialog spells out the asymmetric outcome up front — deleting for both
    /// unsends your messages, and the peer's own messages only disappear if they allowed
    /// it. The contact stays. On success the host pops the thread.
    /// Agent: CALLS messaging.deleteConversation; on success CALLS `onChatDeleted` (or
    /// dismisses); a failure stays here with a toast.
    private var deleteChatCard: some View {
        Button {
            showDeleteChatConfirm = true
        } label: {
            HStack(spacing: 8) {
                if isDeletingChat {
                    ProgressView().controlSize(.small)
                }
                Text("Delete Chat")
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.danger)
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
            .background(Theme.background)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(isDeletingChat)
        .confirmationDialog(
            "Delete chat with \(peerUsername)?",
            isPresented: $showDeleteChatConfirm,
            titleVisibility: .visible
        ) {
            Button("Delete for me and \(peerUsername)", role: .destructive) {
                performChatDelete(scope: .everyone)
            }
            Button("Delete for me", role: .destructive) {
                performChatDelete(scope: .me)
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(
                """
                Deleting for both unsends your messages in \(peerUsername)'s chat. Their own \
                messages stay unless they allow chats to be cleared for them. They stay in your contacts.
                """
            )
        }
    }

    private func performChatDelete(scope: ConversationDeleteScope) {
        isDeletingChat = true
        Task {
            let outcome = await messaging.deleteConversation(peerUserID: peerUserID, scope: scope)
            isDeletingChat = false
            if case let .failed(message) = outcome {
                toast = .failure(message)
                Haptics.notification(.error)
                return
            }
            Haptics.notification(.success)
            if let onChatDeleted {
                onChatDeleted()
            } else {
                dismiss()
            }
        }
    }

    private func performBlockChange(block: Bool) {
        isBlocking = true
        Task {
            let error = block
                ? await messaging.blockUser(peerUserID, username: peerUsername)
                : await messaging.unblockUser(peerUserID)
            isBlocking = false
            if let error {
                toast = .failure(error)
                Haptics.notification(.error)
            } else {
                toast = Toast(block ? "\(peerUsername) blocked" : "\(peerUsername) unblocked")
                Haptics.notification(.success)
            }
        }
    }
}

#Preview {
    NavigationStack {
        ContactProfileView(peerUserID: UUID(), peerUsername: "Jane Cooper")
    }
    .environment(MessagingController())
}
