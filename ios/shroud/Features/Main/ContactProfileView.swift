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
    @State private var toast: String?
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

    private var statusLine: String {
        if isOnline { return "online" }
        if let last = messaging.presenceByUser[peerUserID]?.lastSeenAt {
            return "last seen \(messaging.timeLabel(for: last))"
        }
        return "Shroud contact"
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
                    toast = "Edit coming soon"
                    scheduleClear()
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
                Text(peerUsername)
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
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
                        toast = err
                        scheduleClear()
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
                        toast = err
                        scheduleClear()
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
                toast = "Search coming soon"
                scheduleClear()
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
                        if messaging.peerSafetyVerified(peerUserID) {
                            Text("Compared")
                                .font(.system(size: 13))
                                .foregroundStyle(Theme.textSecondary)
                        } else {
                            Button("I've compared this number") {
                                messaging.confirmPeerSafety(peerUserID)
                            }
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(Theme.accent)
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
                toast = "A chat can be muted once it has messages."
                scheduleClear()
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
                toast = error
                Haptics.notification(.error)
            } else {
                toast = duration == nil ? "Notifications on" : "Muted"
                Haptics.impact(.light)
            }
            scheduleClear()
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
            optionsRow(icon: "lock.fill", title: "Encryption", value: "On")
        }
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func optionsRow(icon: String, title: String, value: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Theme.accent)
                .frame(width: 30, height: 30)
                .background(Theme.accentSoft)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            Text(title)
                .font(.system(size: 16))
                .foregroundStyle(Theme.textPrimary)
            Spacer()
            Text(value)
                .font(.system(size: 15))
                .foregroundStyle(Theme.textSecondary)
            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Theme.textSecondary.opacity(0.7))
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
            Button("I verified this contact") {
                showAcceptIdentityConfirm = true
            }
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(Theme.accent)
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
                toast = "New encryption key saved"
                scheduleClear()
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
                toast = message
                Haptics.notification(.error)
                scheduleClear()
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
                toast = error
                Haptics.notification(.error)
            } else {
                toast = block ? "\(peerUsername) blocked" : "\(peerUsername) unblocked"
                Haptics.notification(.success)
            }
            scheduleClear()
        }
    }

    private func scheduleClear() {
        Task {
            try? await Task.sleep(nanoseconds: 1_800_000_000)
            toast = nil
        }
    }
}

#Preview {
    NavigationStack {
        ContactProfileView(peerUserID: UUID(), peerUsername: "Jane Cooper")
    }
    .environment(MessagingController())
}
